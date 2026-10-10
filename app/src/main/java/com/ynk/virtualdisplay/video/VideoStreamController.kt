package com.ynk.virtualdisplay.video

import android.util.Log
import com.ynk.virtualdisplay.MyApplication
import android.view.Surface
import com.ynk.virtualdisplay.net.DaemonTransport
import com.ynk.virtualdisplay.data.local.AppSettingsDataSource
import com.ynk.virtualdisplay.data.AppSettings
import com.ynk.virtualdisplay.webrtc.GatewayProcessController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.CopyOnWriteArraySet
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.collect
import java.io.IOException

class VideoStreamController(
    private val transport: DaemonTransport,
    private val controlApi: com.ynk.virtualdisplay.rpc.DaemonControlApi,
    private val scope: CoroutineScope,
    private val settingsDataSource: AppSettingsDataSource? = null
) {
    companion object {
        private const val TAG = "VideoStreamController"
        private const val DEFAULT_WIDTH = 1920
        private const val DEFAULT_HEIGHT = 1080
        private const val PING_INTERVAL_MS = 2000L
        private const val USER_INPUT_GRACE_MS = 2_500L
        // Valid trackpad input must actively clear stale server idle state even if
        // the local optimistic flag is already false. Limit burst traffic while
        // still allowing one wake command for click-only gestures.
        private const val IDLE_WAKE_RPC_MIN_INTERVAL_MS = 120L
    }

    private val mutex = Mutex()
    private var decoder: H264StreamDecoder? = null
    private var tracker: PerformanceTracker? = null
    private var pingJob: kotlinx.coroutines.Job? = null
    private var idlePolicyJob: Job? = null
    private var gatewayStateJob: Job? = null
    private var backgroundQuiesceJob: Job? = null
    private var backgroundResumeRetryJob: Job? = null
    private val backgroundWakePending = java.util.concurrent.atomic.AtomicBoolean(false)
    private val idlePolicyMutex = Mutex()
    private val lastUserActivityMs = java.util.concurrent.atomic.AtomicLong(0L)
    // Unlike arbitrary device touches, this timestamp is updated only when input
    // is routed through the virtual-display operation surface (or an explicit
    // local-preview action).
    private val lastVirtualInputMs = java.util.concurrent.atomic.AtomicLong(0L)
    // Idle capture is forbidden until the first decoded frame reaches the real window
    // Surface and a full grace interval has elapsed. This prevents a new floating
    // window's initial black/uninitialized Surface from being mistaken for a stable frame.
    private val firstVisibleFrameRendered = java.util.concurrent.atomic.AtomicBoolean(false)
    private val idleMonitoringNotBeforeMs = java.util.concurrent.atomic.AtomicLong(Long.MAX_VALUE)
    private val idleFreezeArmed = java.util.concurrent.atomic.AtomicBoolean(false)
    private val lastIdleWakeRpcMs = java.util.concurrent.atomic.AtomicLong(-IDLE_WAKE_RPC_MIN_INTERVAL_MS)
    @Volatile private var currentDisplayId = -1
    private val encodedVideoSinks = CopyOnWriteArraySet<EncodedVideoSink>()
    // Persist the local-preview policy across decoder/codec recreation.
    @Volatile private var localVideoDecodeEnabled = true
    @Volatile private var encodedWidth = 0
    @Volatile private var encodedHeight = 0
    @Volatile private var running = false

    // Requested state survives background suspension. The APP's local video worker
    // is only quiesced while another app owns physical display 0 AND GO is stopped.
    @Volatile private var requestedRunning = false
    @Volatile private var pausedForPhysicalBackground = false
    @Volatile private var requestedDisplayId = -1
    @Volatile private var requestedSurface: Surface? = null
    @Volatile private var requestedWidth = DEFAULT_WIDTH
    @Volatile private var requestedHeight = DEFAULT_HEIGHT
    @Volatile private var foregroundListenerRegistered = false
    @Volatile private var physicalBackgroundSinceMs = 0L
    @Volatile private var controlRecoveryJob: Job? = null
    private val CONTROL_REBIND_AFTER_BACKGROUND_MS = 5_000L
    private val physicalForegroundListener: (Boolean) -> Unit = { foreground ->
        // Record the transition synchronously before launching the asynchronous
        // lifecycle worker. If the user clicks immediately after returning, the
        // very first ROLE_CONTROL write must rebind before sending input.
        val now = android.os.SystemClock.elapsedRealtime()
        if (!foreground) {
            if (physicalBackgroundSinceMs == 0L) physicalBackgroundSinceMs = now
        } else {
            val since = physicalBackgroundSinceMs
            if (since > 0L && now - since >= CONTROL_REBIND_AFTER_BACKGROUND_MS && requestedRunning) {
                transport.markControlChannelForRecovery()
            }
        }
        scope.launch(Dispatchers.IO) {
            handlePhysicalForegroundChanged(foreground)
        }
    }

    var onVideoConfig: ((codecLabel: String, width: Int, height: Int) -> Unit)? = null
    var onStreamEnded: ((reason: String) -> Unit)? = null
    var onPerformanceStats: ((String) -> Unit)? = null

    /** Add an optional encoded-video consumer. It does not create another decoder. */
    fun addEncodedVideoSink(sink: EncodedVideoSink) {
        encodedVideoSinks.add(sink)
        decoder?.addEncodedVideoSink(sink)
        val width = encodedWidth
        val height = encodedHeight
        if (width > 0 && height > 0) {
            runCatching { sink.onFormat("H.264", width, height) }
        }
    }

    fun removeEncodedVideoSink(sink: EncodedVideoSink) {
        decoder?.removeEncodedVideoSink(sink)
        encodedVideoSinks.remove(sink)
    }

    suspend fun start(displayId: Int, surface: Surface?, w: Int, h: Int): Result<Unit> {
        // A gateway may have been launched directly through ADB/Shizuku rather than
        // through this APP. Probe once on the background transition/start path so
        // the quiescence decision does not rely solely on cached UI state.
        if (!MyApplication.isPhysicalDisplayForeground) {
            withContext(Dispatchers.IO) {
                GatewayProcessController.refreshGatewayRunningFromLocalPort()
            }
        }
        return mutex.withLock {
            requestedDisplayId = displayId
            requestedSurface = surface
            requestedWidth = w
            requestedHeight = h
            requestedRunning = true
            ensurePhysicalForegroundListener()

            if (!canRunWhilePhysicalBackgrounded()) {
                if (!pausedForPhysicalBackground || running || decoder != null || pingJob != null || idlePolicyJob != null) {
                    pausedForPhysicalBackground = true
                    running = false
                    stopInternal()
                } else {
                    pausedForPhysicalBackground = true
                }
                Log.i(TAG, "Deferring local video stream for display=$displayId while app is backgrounded and GO is stopped")
                return@withLock Result.success(Unit)
            }

            pausedForPhysicalBackground = false
            startTransportLocked(displayId, surface, w, h)
        }
    }

    /** Requires [mutex]. Starts only this APP's local scrcpy sockets/decoder. */
    private suspend fun startTransportLocked(
        displayId: Int,
        surface: Surface?,
        w: Int,
        h: Int,
    ): Result<Unit> {
        running = false
        currentDisplayId = displayId
        val startTime = android.os.SystemClock.elapsedRealtime()
        lastUserActivityMs.set(startTime)
        // Never treat a brand-new stream as already idle. The separate first-visible-
        // frame gate below starts the actual quiet timer only after a frame reached
        // the real Surface, then grants a full grace interval before frame comparison.
        lastVirtualInputMs.set(startTime)
        firstVisibleFrameRendered.set(false)
        idleMonitoringNotBeforeMs.set(Long.MAX_VALUE)
        idleFreezeArmed.set(false)
        Log.i(TAG, "Starting video stream for display $displayId... (surface=$surface valid=${surface?.isValid} dims=${w}x${h})")

        // 1. 先关闭残留 Scrcpy 子通道 (视频 + 控制)：关闭旧 video socket 会使 decoder
        //    input worker 阻塞的 read() 抛 SocketException 退出，从而下面的
        //    decoder.stop() 的 join(500) 快速返回而非超时（旧 worker 仍持旧 codec
        //    DirectByteBuffer 引用，rebuildCodec release 后会 use-after-free）。
        //    disconnectScrcpyChannels 对首启/重启均幂等，统一此路径无需分支。
        //    b7aef962 之后服务端在 video socket 关闭时自动停止编码器、释放 VD 引用。
        transport.disconnectScrcpyChannels()

        // 2. 关闭旧解码器（socket 已断，input worker 已解除阻塞，join 不会超时）
        if (decoder != null) {
            decoder?.stop()
            decoder = null
            tracker = null
        }

        // 3. (start 阶段) 不再发送 TYPE_START_VIDEO_STREAM (209) —— 新协议下
        //    绑定 ROLE_VIDEO socket 时服务端自动启动编码器推流。
        Log.i(TAG, "Pre-start hook OK (post-b7aef962: no 209 command, socket bind auto-starts stream). Connecting Scrcpy channels...")

        // 4. 打开 ROLE_CONTROL + ROLE_VIDEO socket，携带 displayId 路由，
        //    等服务端 displayId ack 后返回。socket 建立即服务端开始推流。
        val channelsConnected = transport.connectScrcpyChannels(displayId)
        if (!channelsConnected) {
            Log.e(TAG, "Failed to connect Scrcpy channels (video and control sockets) with displayId=$displayId")
            return Result.failure(IOException("Scrcpy channels connection failed (displayId=$displayId)"))
        }

        // 5. 成功建立物理连接后，获取视频流读取端
        val videoIn = try {
            transport.videoInputStream()
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to get videoInputStream from transport", t)
            return Result.failure(IllegalStateException("Video stream input unavailable", t))
        }
        if (videoIn == null) {
            val msg = "Video stream input is null after handshake"
            Log.e(TAG, msg)
            return Result.failure(IllegalStateException(msg))
        }
        Log.i(TAG, "Video input stream acquired, creating decoder (surface=$surface valid=${surface?.isValid} dims=${w}x${h})")

        val decoderResult = runCatching {
            val frameReader = ScrcpyFrameReader(videoIn)
            val perfTracker = PerformanceTracker().apply {
                onPerformanceStats = { stats ->
                    this@VideoStreamController.onPerformanceStats?.invoke(stats)
                }
            }
            tracker = perfTracker

            val d = H264StreamDecoder(
                videoStream = videoIn,
                width = w.takeIf { it > 0 } ?: DEFAULT_WIDTH,
                height = h.takeIf { it > 0 } ?: DEFAULT_HEIGHT,
                frameReader = frameReader,
                tracker = perfTracker
            )

            d.onFirstFrameRendered = {
                val firstRenderedAt = android.os.SystemClock.elapsedRealtime()
                if (firstVisibleFrameRendered.compareAndSet(false, true)) {
                    // Start the quiet period only after the first visible frame has
                    // actually reached this window, not when the socket was connected.
                    lastUserActivityMs.set(firstRenderedAt)
                    lastVirtualInputMs.set(firstRenderedAt)
                    idleMonitoringNotBeforeMs.set(firstRenderedAt + USER_INPUT_GRACE_MS)
                    Log.i(TAG, "First visible frame rendered for display=$displayId; idle monitoring delayed by ${USER_INPUT_GRACE_MS}ms")
                }
            }
            d.onVideoConfig = { codec, width, height ->
                if (codec.equals("H.264", ignoreCase = true)) {
                    encodedWidth = width
                    encodedHeight = height
                    encodedVideoSinks.forEach { sink ->
                        runCatching { sink.onFormat(codec, width, height) }
                    }
                }
                onVideoConfig?.invoke(codec, width, height)
            }
            d.onStreamEnded = { reason ->
                if (running) {
                    Log.i(TAG, "Video stream ended for display=$displayId reason=$reason")
                    onStreamEnded?.invoke(reason)
                }
            }
            d.ultraLowLatency = settingsDataSource?.getUltraLowLatencySync()
                ?: AppSettings.getUltraLowLatencySync()
            // New decoders inherit the current local-preview policy. Without
            // this, a decoder recreation can silently resume local rendering.
            d.setLocalDecodeEnabled(localVideoDecodeEnabled)
            encodedVideoSinks.forEach { d.addEncodedVideoSink(it) }
            surface?.let { d.setDisplaySurface(it) }
            d.start()
            decoder = d
            running = true
        }
        val decoderFailure = decoderResult.exceptionOrNull()
        if (decoderFailure != null) {
            Log.e(TAG, "Failed to create/start H264StreamDecoder", decoderFailure)
            transport.disconnectScrcpyChannels()
            return Result.failure(decoderFailure)
        }

        Log.i(TAG, "VideoStreamController started successfully for display $displayId (surface bound=${surface != null && surface.isValid})")
        
        startPingLoop()
        startIdlePolicyLoop(displayId)
        return Result.success(Unit)
    }

    private fun ensurePhysicalForegroundListener() {
        if (!foregroundListenerRegistered) {
            foregroundListenerRegistered = true
            MyApplication.addPhysicalDisplayForegroundListener(physicalForegroundListener)
        }
        if (gatewayStateJob?.isActive != true) {
            gatewayStateJob = scope.launch(Dispatchers.IO) {
                GatewayProcessController.gatewayRunningFlow.collect { gatewayRunning ->
                    if (!requestedRunning || MyApplication.isPhysicalDisplayForeground) return@collect
                    if (gatewayRunning) {
                        // GO can be started/stopped independently. If it becomes
                        // active while this controller is already quiescent, restore
                        // the APP's normal local worker even though display 0 is hidden.
                        cancelBackgroundQuiesce()
                        resumeRequestedStream()
                    } else {
                        // StateFlow's initial false can mean "not probed yet", not
                        // necessarily that GO is stopped. suspendForPhysicalBackground
                        // verifies the live listener before closing any local sockets.
                        suspendForPhysicalBackground()
                    }
                }
            }
        }
    }

    private suspend fun handlePhysicalForegroundChanged(foreground: Boolean) {
        if (!foreground) {
            if (physicalBackgroundSinceMs == 0L) {
                physicalBackgroundSinceMs = android.os.SystemClock.elapsedRealtime()
            }
            // Do not trust the cached GO state on this transition. GO is a separate
            // privileged process and may have been launched through ADB/Shizuku.
            suspendForPhysicalBackground()
        } else {
            val now = android.os.SystemClock.elapsedRealtime()
            val backgroundDuration = physicalBackgroundSinceMs.takeIf { it > 0L }?.let { now - it } ?: 0L
            physicalBackgroundSinceMs = 0L
            cancelBackgroundQuiesce()
            val workerWasQuiesced = pausedForPhysicalBackground
            resumeRequestedStream()

            // If GO kept the APP worker alive, it is possible that its old ROLE_CONTROL
            // TCP socket survived locally but the remote Controller loop was lost while
            // the device was in deep background. A write-only heartbeat cannot detect
            // every half-open TCP case. After a genuinely long background transition,
            // rebind just this APP session's control channel; GO has its own session and
            // keeps its audio/video subscription untouched.
            if (!workerWasQuiesced && backgroundDuration >= CONTROL_REBIND_AFTER_BACKGROUND_MS &&
                requestedRunning && running && currentDisplayId >= 0) {
                recoverControlChannelAfterBackground(currentDisplayId)
            }
        }
    }

    private fun recoverControlChannelAfterBackground(displayId: Int) {
        controlRecoveryJob?.cancel()
        controlRecoveryJob = scope.launch(Dispatchers.IO) {
            repeat(3) { attempt ->
                if (!MyApplication.isPhysicalDisplayForeground || !requestedRunning || currentDisplayId != displayId) return@launch
                val recovered = runCatching { transport.forceReconnectControlChannel(displayId) }.getOrDefault(false)
                if (recovered) {
                    Log.i(TAG, "ROLE_CONTROL recovered after long background (displayId=$displayId, attempt=${attempt + 1})")
                    return@launch
                }
                delay(500L * (attempt + 1))
            }

            // If ROLE_CONTROL alone cannot be rebound (for example the daemon's
            // per-session role declaration expired), rebuild this APP's role sockets
            // as a last resort. This never sends EXIT_DAEMON or releases the display.
            mutex.withLock {
                if (!MyApplication.isPhysicalDisplayForeground || !requestedRunning ||
                    currentDisplayId != displayId || pausedForPhysicalBackground) return@withLock
                val surface = requestedSurface
                if (surface != null && !surface.isValid) return@withLock
                Log.w(TAG, "ROLE_CONTROL recovery exhausted; rebuilding this APP's local stream session for display=$displayId")
                val result = startTransportLocked(displayId, surface, requestedWidth, requestedHeight)
                if (result.isFailure) {
                    Log.e(TAG, "Full local stream-session recovery failed for display=$displayId", result.exceptionOrNull())
                }
            }
        }
    }

    private suspend fun suspendForPhysicalBackground() {
        if (MyApplication.isPhysicalDisplayForeground) return

        // Verify GO's actual listener before tearing down ROLE_VIDEO. A stale/initial
        // false StateFlow value previously allowed this method to close APP's role
        // socket just as GO was starting; with SHM this could also race broadcaster
        // lifetime management and strand GO until it was restarted.
        val gatewayRunning = withContext(Dispatchers.IO) {
            GatewayProcessController.refreshGatewayRunningFromLocalPort()
        }
        if (gatewayRunning) {
            cancelBackgroundQuiesce()
            if (pausedForPhysicalBackground) resumeRequestedStream()
            return
        }

        mutex.withLock {
            // Re-check state after acquiring the mutex: physical focus or GO may
            // have changed while the probe or lock acquisition was in progress.
            val recentVirtualInput =
                android.os.SystemClock.elapsedRealtime() - lastVirtualInputMs.get() < USER_INPUT_GRACE_MS
            if (MyApplication.isPhysicalDisplayForeground ||
                GatewayProcessController.isGatewayRunningKnown() ||
                !requestedRunning || pausedForPhysicalBackground
            ) {
                return@withLock
            }
            if (recentVirtualInput) {
                // A routed virtual-screen click can arrive while the gateway probe
                // is in flight. Never let an older background-transition callback
                // suspend the stream after that newer input; re-arm the grace timer.
                scheduleBackgroundQuiesce()
                return@withLock
            }
            cancelBackgroundQuiesce()
            pausedForPhysicalBackground = true
            running = false
            Log.i(TAG, "Physical app backgrounded and GO confirmed stopped; suspending local video worker for display=$requestedDisplayId")
            stopInternal()
        }
    }

    private fun canRunWhilePhysicalBackgrounded(): Boolean =
        MyApplication.isPhysicalDisplayForeground || GatewayProcessController.isGatewayRunningKnown()

    private suspend fun resumeRequestedStream(allowBackgroundVirtualInput: Boolean = false) {
        mutex.withLock {
            val allowedInCurrentState = canRunWhilePhysicalBackgrounded() || allowBackgroundVirtualInput
            if (!requestedRunning || !pausedForPhysicalBackground || !allowedInCurrentState) {
                return@withLock
            }
            val displayId = requestedDisplayId
            if (displayId < 0) return@withLock
            val surface = requestedSurface
            if (surface != null && !surface.isValid) {
                // Surface callbacks may arrive just after the app regains focus.
                // Stay quiescent and retry when setSurface receives a valid one;
                // a bounded-delay retry also covers a missed lifecycle callback.
                Log.d(TAG, "Waiting for a valid local surface before resuming display=$displayId")
                scheduleBackgroundResumeRetry()
                return@withLock
            }

            pausedForPhysicalBackground = false
            Log.i(
                TAG,
                "Restoring local video worker for display=$displayId " +
                    "(physicalForeground=${MyApplication.isPhysicalDisplayForeground}, gatewayRunning=${GatewayProcessController.isGatewayRunningKnown()}, virtualInputWake=$allowBackgroundVirtualInput)"
            )
            val result = startTransportLocked(
                displayId,
                surface,
                requestedWidth,
                requestedHeight,
            )
            if (result.isFailure) {
                pausedForPhysicalBackground = true
                Log.w(TAG, "Could not resume local video worker for display=$displayId", result.exceptionOrNull())
                scheduleBackgroundResumeRetry()
            } else {
                backgroundResumeRetryJob?.cancel()
                backgroundResumeRetryJob = null
            }
        }
    }

    /** Retry failed foreground/background-resume handshakes instead of leaving a dead input channel until process restart. */
    private fun scheduleBackgroundResumeRetry() {
        if (backgroundResumeRetryJob?.isActive == true) return
        backgroundResumeRetryJob = scope.launch(Dispatchers.IO) {
            var attempt = 0
            val delays = longArrayOf(500L, 1_000L, 2_000L, 3_000L, 5_000L)
            while (isActive && requestedRunning && pausedForPhysicalBackground) {
                if (!canRunWhilePhysicalBackgrounded()) return@launch
                delay(delays[attempt.coerceAtMost(delays.lastIndex)])
                if (!isActive || !requestedRunning || !pausedForPhysicalBackground) return@launch
                if (!canRunWhilePhysicalBackgrounded()) return@launch
                val surface = requestedSurface
                if (surface != null && !surface.isValid) {
                    attempt++
                    continue
                }
                resumeRequestedStream()
                if (!pausedForPhysicalBackground) return@launch
                attempt++
            }
        }
    }

    private fun startPingLoop() {
        pingJob?.cancel()
        pingJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val result = controlApi.ping()
                result.onSuccess { rtt ->
                    tracker?.recordRtt(rtt.toDouble())
                }
                val controlOk = transport.writeControlHeartbeat()
                if (!controlOk) {
                    Log.w(TAG, "ROLE_CONTROL heartbeat/reconnect failed; input may be temporarily unavailable")
                }
                kotlinx.coroutines.delay(PING_INTERVAL_MS)
            }
        }
    }

    suspend fun stop() = mutex.withLock {
        cancelBackgroundQuiesce()
        requestedRunning = false
        requestedDisplayId = -1
        requestedSurface = null
        pausedForPhysicalBackground = false
        running = false
        if (foregroundListenerRegistered) {
            MyApplication.removePhysicalDisplayForegroundListener(physicalForegroundListener)
            foregroundListenerRegistered = false
        }
        gatewayStateJob?.cancel()
        gatewayStateJob = null
        controlRecoveryJob?.cancel()
        controlRecoveryJob = null
        backgroundResumeRetryJob?.cancel()
        backgroundResumeRetryJob = null
        physicalBackgroundSinceMs = 0L
        stopInternal()
    }

    private suspend fun stopInternal() {
        Log.i(TAG, "Stopping video stream...")
        pingJob?.cancel()
        pingJob = null
        idlePolicyJob?.cancel()
        idlePolicyJob = null
        val oldDisplayId = currentDisplayId
        currentDisplayId = -1
        if (idleFreezeArmed.compareAndSet(true, false) && oldDisplayId >= 0) {
            try {
                idlePolicyMutex.withLock { controlApi.setIdleFreezeMonitoring(oldDisplayId, false) }
            } catch (t: Throwable) {
                Log.d(TAG, "Failed to clear idle monitoring during stop", t)
            }
        }
        // Post-b7aef962: stopVideoStream (210) removed. Video server stops
        // automatically when the ROLE_VIDEO socket closes. 先断开 Scrcpy 子信道使
        // input worker 阻塞的 read() 抛 SocketException 退出，从而下面的
        // decoder.stop() 的 join(500) 快速返回而非超时（否则 rebuildCodec 由
        // resizeDisplay/updateResolution 在主线程触发时会 release 旧 codec，而
        // worker 仍持 DirectByteBuffer 引用 → native Data Abort use-after-free）。
        transport.disconnectScrcpyChannels()

        decoder?.stop()
        decoder = null
        tracker = null

        Log.i(TAG, "VideoStreamController stopped")
    }

    fun setSurface(surface: Surface?) {
        requestedSurface = surface
        decoder?.setDisplaySurface(surface)
        if (surface?.isValid == true && canRunWhilePhysicalBackgrounded() &&
            requestedRunning && pausedForPhysicalBackground) {
            scope.launch(Dispatchers.IO) { resumeRequestedStream() }
        }
    }

    /** Toggle only local MediaCodec decoding. Does not reconnect or modify any socket. */
    fun setLocalVideoDecodeEnabled(enabled: Boolean) {
        if (localVideoDecodeEnabled == enabled) {
            if (enabled) resumeFromUserActivity()
            return
        }
        localVideoDecodeEnabled = enabled
        decoder?.setLocalDecodeEnabled(enabled)
        if (enabled) {
            // Re-enabling the local preview is itself an explicit user-visible
            // activity. Do not leave the server encoder frozen while the user
            // waits for the next raw-change sample.
            resumeFromUserActivity()
        }
    }

    /** Called only from input paths that are routed to this virtual display.
     * Physical-screen focus is deliberately irrelevant: unrelated touches in other
     * apps never call this method, while genuine input to the virtual screen must
     * wake it even when its Activity lives on a virtual display. */
    fun notifyUserActivity() {
        resumeFromUserActivity()
    }

    private fun startIdlePolicyLoop(displayId: Int) {
        idlePolicyJob?.cancel()
        idlePolicyJob = scope.launch(Dispatchers.IO) {
            while (isActive && running && currentDisplayId == displayId) {
                val now = android.os.SystemClock.elapsedRealtime()
                if (!firstVisibleFrameRendered.get()) {
                    // Wait for an actual frame to be submitted to the visible Surface.
                    // Never let the startup black Surface be classified as stable.
                    delay(100L)
                    continue
                }
                val startupGraceRemaining = idleMonitoringNotBeforeMs.get() - now
                if (startupGraceRemaining > 0L) {
                    delay(startupGraceRemaining.coerceAtMost(250L).coerceAtLeast(50L))
                    continue
                }
                val appInForeground = MyApplication.isPhysicalDisplayForeground
                val idleFor = now - lastUserActivityMs.get()
                val virtualInputAge = now - lastVirtualInputMs.get()
                val recentVirtualInput = virtualInputAge < USER_INPUT_GRACE_MS

                // Keep the foreground/idle check as a race guard while a lifecycle
                // transition is waiting for the controller mutex. If GO is active,
                // the normal worker remains alive and the existing policy continues.
                val shouldMonitorNow = if (appInForeground) {
                    idleFor >= USER_INPUT_GRACE_MS
                } else {
                    !recentVirtualInput
                }

                if (shouldMonitorNow) {
                    if (idleFreezeArmed.compareAndSet(false, true)) {
                        val result = idlePolicyMutex.withLock {
                            // Re-evaluate after waiting for any concurrent input/resume.
                            val checkTime = android.os.SystemClock.elapsedRealtime()
                            val isForegroundNow = MyApplication.isPhysicalDisplayForeground
                            val foregroundIdle = checkTime - lastUserActivityMs.get() >= USER_INPUT_GRACE_MS
                            val backgroundVirtualInputRecent =
                                checkTime - lastVirtualInputMs.get() < USER_INPUT_GRACE_MS
                            val startupReady = firstVisibleFrameRendered.get() &&
                                checkTime >= idleMonitoringNotBeforeMs.get()
                            val shouldMonitor = startupReady && running && currentDisplayId == displayId &&
                                (if (isForegroundNow) foregroundIdle else !backgroundVirtualInputRecent)

                            if (shouldMonitor) {
                                controlApi.setIdleFreezeMonitoring(displayId, true)
                            } else {
                                // Input arrived while this enable was queued. Clear the
                                // optimistic armed bit so the loop can retry at its next boundary.
                                idleFreezeArmed.compareAndSet(true, false)
                                Result.success(Unit)
                            }
                        }
                        if (result.isFailure) {
                            idleFreezeArmed.compareAndSet(true, false)
                        }
                    }
                    delay(1_000L)
                } else {
                    // Poll the next eligible activity boundary without delaying active input.
                    val graceRemaining = if (appInForeground) {
                        USER_INPUT_GRACE_MS - idleFor
                    } else {
                        USER_INPUT_GRACE_MS - virtualInputAge
                    }
                    delay(graceRemaining.coerceIn(50L, 250L))
                }
            }
        }
    }

    fun resumeFromUserActivity() {
        // This method is called only by input that was routed to the virtual screen.
        // Such input remains authoritative even when display 0 is in the background:
        // a click does not necessarily change pixels, so waiting for frame-difference
        // detection would leave the encoder frozen until the next mouse movement.
        val now = android.os.SystemClock.elapsedRealtime()
        lastUserActivityMs.set(now)
        lastVirtualInputMs.set(now)

        if (pausedForPhysicalBackground) {
            // Normally we keep every APP worker quiescent in the background when GO
            // is stopped. A real virtual-screen input is the one explicit exception:
            // briefly restore the local stream so click-only actions can update the
            // picture, then re-quiesce after the grace interval if no new input arrives.
            if (backgroundWakePending.compareAndSet(false, true)) {
                scope.launch(Dispatchers.IO) {
                    try {
                        val gatewayRunning = GatewayProcessController.refreshGatewayRunningFromLocalPort()
                        if (gatewayRunning) {
                            cancelBackgroundQuiesce()
                            resumeRequestedStream()
                        } else {
                            resumeRequestedStream(allowBackgroundVirtualInput = true)
                            if (!pausedForPhysicalBackground && running) scheduleBackgroundQuiesce()
                        }
                    } catch (t: Throwable) {
                        Log.d(TAG, "Unable to wake local stream for virtual input", t)
                    } finally {
                        backgroundWakePending.set(false)
                    }
                }
            }
            return
        }

        // Do not trust idleFreezeArmed as the sole source of truth. It is an
        // optimistic client-side flag; a timeout/race can leave the server encoder
        // frozen while this flag is false. A click may not alter any pixels, so it
        // cannot rely on RawFrameChangeMonitor to wake the encoder. Always send an
        // explicit resume on the first routed input, then throttle high-rate move
        // events to at most ~8 RPCs/sec. If we know an enable is armed, bypass the
        // throttle so an in-flight idle transition is immediately cancelled.
        val serverMayBeIdle = idleFreezeArmed.getAndSet(false)
        val displayId = currentDisplayId.takeIf { it >= 0 } ?: requestedDisplayId
        if (requestedRunning && displayId >= 0 && shouldSendIdleWakeRpc(now, serverMayBeIdle)) {
            scope.launch(Dispatchers.IO) {
                try {
                    idlePolicyMutex.withLock {
                        val resumeResult = controlApi.setIdleFreezeMonitoring(displayId, false)
                        if (resumeResult.isFailure) {
                            // Keep the local flag cleared. The throttled next input can
                            // retry even if the optimistic flag is already false.
                            idleFreezeArmed.set(false)
                            Log.d(TAG, "Server rejected idle-resume command; next routed input may retry")
                        }
                    }
                } catch (t: Throwable) {
                    idleFreezeArmed.set(false)
                    Log.d(TAG, "Failed to resume video after user activity", t)
                }
            }
        }

        if (!canRunWhilePhysicalBackgrounded()) scheduleBackgroundQuiesce()
    }


    private fun shouldSendIdleWakeRpc(now: Long, bypassThrottle: Boolean): Boolean {
        if (bypassThrottle) {
            lastIdleWakeRpcMs.set(now)
            return true
        }
        while (true) {
            val previous = lastIdleWakeRpcMs.get()
            if (now - previous < IDLE_WAKE_RPC_MIN_INTERVAL_MS) return false
            if (lastIdleWakeRpcMs.compareAndSet(previous, now)) return true
        }
    }

    private fun scheduleBackgroundQuiesce() {
        if (MyApplication.isPhysicalDisplayForeground ||
            GatewayProcessController.isGatewayRunningKnown() ||
            !requestedRunning || pausedForPhysicalBackground
        ) return

        backgroundQuiesceJob?.cancel()
        backgroundQuiesceJob = scope.launch(Dispatchers.IO) {
            val thisJob = kotlinx.coroutines.currentCoroutineContext()[Job]
            delay(USER_INPUT_GRACE_MS)
            if (backgroundQuiesceJob === thisJob) backgroundQuiesceJob = null
            val now = android.os.SystemClock.elapsedRealtime()
            if (!MyApplication.isPhysicalDisplayForeground &&
                !GatewayProcessController.isGatewayRunningKnown() &&
                requestedRunning && !pausedForPhysicalBackground &&
                now - lastVirtualInputMs.get() >= USER_INPUT_GRACE_MS
            ) {
                suspendForPhysicalBackground()
            }
        }
    }

    private fun cancelBackgroundQuiesce() {
        backgroundQuiesceJob?.cancel()
        backgroundQuiesceJob = null
    }

    fun updateResolution(w: Int, h: Int) {
        requestedWidth = w
        requestedHeight = h
        decoder?.updateResolution(w, h)
    }
}
