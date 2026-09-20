package com.ynk.virtualdisplay.video

import android.util.Log
import android.view.Surface
import com.ynk.virtualdisplay.net.DaemonTransport
import com.ynk.virtualdisplay.data.local.AppSettingsDataSource
import com.ynk.virtualdisplay.data.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.CopyOnWriteArraySet
import kotlinx.coroutines.withContext
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
    }

    private val mutex = Mutex()
    private var decoder: H264StreamDecoder? = null
    private var tracker: PerformanceTracker? = null
    private var pingJob: kotlinx.coroutines.Job? = null
    private val encodedVideoSinks = CopyOnWriteArraySet<EncodedVideoSink>()
    // Persist the local-preview policy across decoder/codec recreation.
    @Volatile private var localVideoDecodeEnabled = true
    @Volatile private var encodedWidth = 0
    @Volatile private var encodedHeight = 0
    @Volatile private var running = false

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

    suspend fun start(displayId: Int, surface: Surface?, w: Int, h: Int): Result<Unit> = mutex.withLock {
        running = false
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
            return@withLock Result.failure(IOException("Scrcpy channels connection failed (displayId=$displayId)"))
        }

        // 5. 成功建立物理连接后，获取视频流读取端
        val videoIn = try {
            transport.videoInputStream()
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to get videoInputStream from transport", t)
            return@withLock Result.failure(IllegalStateException("Video stream input unavailable", t))
        }
        if (videoIn == null) {
            val msg = "Video stream input is null after handshake"
            Log.e(TAG, msg)
            return@withLock Result.failure(IllegalStateException(msg))
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
        decoderResult.onFailure {
            Log.e(TAG, "Failed to create/start H264StreamDecoder", it)
            transport.disconnectScrcpyChannels()
            return@withLock Result.failure(it)
        }

        Log.i(TAG, "VideoStreamController started successfully for display $displayId (surface bound=${surface != null && surface.isValid})")
        
        startPingLoop()
        Result.success(Unit)
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
        running = false
        stopInternal()
    }

    private suspend fun stopInternal() {
        Log.i(TAG, "Stopping video stream...")
        pingJob?.cancel()
        pingJob = null
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
        decoder?.setDisplaySurface(surface)
    }

    /** Toggle only local MediaCodec decoding. Does not reconnect or modify any socket. */
    fun setLocalVideoDecodeEnabled(enabled: Boolean) {
        localVideoDecodeEnabled = enabled
        decoder?.setLocalDecodeEnabled(enabled)
    }

    fun updateResolution(w: Int, h: Int) {
        decoder?.updateResolution(w, h)
    }
}
