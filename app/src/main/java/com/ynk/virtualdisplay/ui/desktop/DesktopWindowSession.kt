package com.ynk.virtualdisplay.ui.desktop

import android.content.Context
import android.hardware.display.DisplayManager
import android.util.Log
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyEvent
import android.view.Surface
import com.ynk.virtualdisplay.data.AppSettings
import com.ynk.virtualdisplay.data.model.VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED
import com.ynk.virtualdisplay.data.model.VIRTUAL_DISPLAY_FLAG_DEVICE_DISPLAY_GROUP
import com.ynk.virtualdisplay.data.model.VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL
import com.ynk.virtualdisplay.data.model.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
import com.ynk.virtualdisplay.data.model.VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP
import com.ynk.virtualdisplay.data.model.VIRTUAL_DISPLAY_FLAG_OWN_FOCUS
import com.ynk.virtualdisplay.data.model.VIRTUAL_DISPLAY_FLAG_PRESENTATION
import com.ynk.virtualdisplay.data.model.VIRTUAL_DISPLAY_FLAG_PUBLIC
import com.ynk.virtualdisplay.data.model.VIRTUAL_DISPLAY_FLAG_TOUCH_FEEDBACK_DISABLED
import com.ynk.virtualdisplay.data.model.VIRTUAL_DISPLAY_FLAG_TRUSTED
import com.ynk.virtualdisplay.net.DaemonTransport
import com.ynk.virtualdisplay.rpc.DaemonControlApiImpl
import com.ynk.virtualdisplay.rpc.DaemonRpc
import com.ynk.virtualdisplay.video.VideoStreamController
import com.ynk.virtualdisplay.util.NetUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.collect
import java.io.Closeable
import java.util.UUID
import kotlin.math.roundToInt

/**
 * One desktop-window content session.
 *
 * LMO's privileged system implementation can bind a Surface directly to a
 * VirtualDisplay. A normal app cannot call lmo_freeform (its Binder service
 * enforces SYSTEM_UID), so this class uses the already-supported scrcpy
 * multi-session protocol instead:
 *
 *   one VirtualDisplay -> one session -> one ROLE_VIDEO + one ROLE_CONTROL
 *
 * The Surface belongs to a TextureView that lives in the parent DesktopShell's
 * OVERLAY window. The OVERLAY is therefore always on the parent desktop display,
 * while the remote app content comes from this window's private VirtualDisplay.
 */
internal class DesktopWindowSession(
    private val desktopDisplayId: Int,
    private val packageName: String,
    context: Context,
) : Closeable {

    companion object {
        private const val TAG = "DesktopWindowSession"
        private const val CONNECT_TIMEOUT_MS = 5_000L
    }

    private val scope = CoroutineScope(
        Dispatchers.Main.immediate + SupervisorJob()
    )
    private val transport = DaemonTransport()
    private val rpc = DaemonRpc(transport)
    private val controlApi = DaemonControlApiImpl(rpc, transport)
    private val video = VideoStreamController(transport, controlApi, scope)
    private val displayManager = context.applicationContext
        .getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    // All control-channel input must be serialized. The desktop shell can emit
    // DOWN/MOVE/UP events in rapid succession; launching each write in a fresh IO
    // coroutine can otherwise reorder or interleave frames on the socket.
    private val inputMutex = Mutex()
    // close button and TASK_EXITED callback can race; serialize teardown so exactly
    // one path performs RELEASE_VIRTUAL_DISPLAY before the daemon connection closes.
    private val stopMutex = Mutex()

    @Volatile private var displayId: Int = -1
    private var taskId: Int = -1
    @Volatile private var started = false
    @Volatile private var displayListenerRegistered = false
    @Volatile private var displayRemovedExternally = false
    private var outputSurface: Surface? = null
    @Volatile private var videoWidth: Int = 0
    @Volatile private var videoHeight: Int = 0

    var onSessionEnded: ((reason: String) -> Unit)? = null

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) = Unit

        override fun onDisplayRemoved(removedDisplayId: Int) {
            val ownedDisplayId = displayId
            if (removedDisplayId != ownedDisplayId || ownedDisplayId < 0) return
            // The daemon may auto-destroy this display when its app task exits.
            // Mark it before notifying the owner so teardown never sends a second
            // RELEASE_VIRTUAL_DISPLAY against an already-destroyed/reused ID.
            displayRemovedExternally = true
            Log.i(TAG, "OWNED_DISPLAY_REMOVED display=$removedDisplayId package=$packageName")
            if (started) onSessionEnded?.invoke("display_removed")
        }
    }

    private fun registerDisplayListener() {
        if (displayListenerRegistered) return
        displayManager.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
        displayListenerRegistered = true
    }

    private fun unregisterDisplayListener() {
        if (!displayListenerRegistered) return
        runCatching { displayManager.unregisterDisplayListener(displayListener) }
        displayListenerRegistered = false
    }

    suspend fun start(
        surface: Surface,
        width: Int,
        height: Int,
        densityDpi: Int,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        if (started) return@withContext Result.success(Unit)

        outputSurface = surface

        val node = AppSettings.getCurrentServerNodeSync()
        val host = NetUtils.resolveConnectHost(node.host)
        val password = node.password?.takeIf { it.isNotEmpty() }

        Log.i(TAG, "start package=$packageName desktopDisplay=$desktopDisplayId target=${width}x${height} dpi=$densityDpi host=$host:${node.port}")

        if (!transport.connect(host, node.port, CONNECT_TIMEOUT_MS, secretToken = password)) {
            return@withContext Result.failure(
                IllegalStateException("Failed to connect desktop-window session to daemon")
            )
        }
        rpc.startMessageLoop(scope)
        // This session may be the first/only client talking to a daemon (e.g.
        // all small windows use independent sessions), so sync the persisted
        // daemon-global auto-destroy policy before it creates its display.
        controlApi.setAutoDestroyEmptyDisplays(AppSettings.getAutoDestroyEmptyDisplaysSync()).onFailure {
            Log.w(TAG, "Failed to sync auto-destroy-empty-displays policy", it)
        }
        scope.launch {
            rpc.deviceMessages.collect { message ->
                val endedDisplay = (message as? com.ynk.virtualdisplay.protocol.DeviceMessage.GenericResponse)
                if (endedDisplay != null && endedDisplay.sequence == 0L && endedDisplay.displayId == displayId
                    && endedDisplay.statusCode == -2 && endedDisplay.message == "TASK_EXITED") {
                    Log.i(TAG, "TASK_EXIT_EVENT display=$displayId package=$packageName -> onSessionEnded")
                    onSessionEnded?.invoke("task_exit_event")
                }
            }
        }

        // Match the working Desktop Mode display topology/flags. In
        // particular OWN_FOCUS + DEVICE_DISPLAY_GROUP + TRUSTED prevent
        // secondary child activities from being rerouted to the physical
        // display on ROMs such as MIUI. This is intentionally identical to
        // MainViewModel.createDesktopDisplay(), not Android FREEFORM mode.
        val flags =
            VIRTUAL_DISPLAY_FLAG_PUBLIC or
                VIRTUAL_DISPLAY_FLAG_PRESENTATION or
                VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
                VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL or
                    VIRTUAL_DISPLAY_FLAG_TRUSTED or
                VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP or
                VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED or
                VIRTUAL_DISPLAY_FLAG_TOUCH_FEEDBACK_DISABLED or
                VIRTUAL_DISPLAY_FLAG_OWN_FOCUS or
                VIRTUAL_DISPLAY_FLAG_DEVICE_DISPLAY_GROUP

        val displayResult = controlApi.createDisplay(
            name = "desktop-window-${UUID.randomUUID()}",
            w = width.coerceAtLeast(320),
            h = height.coerceAtLeast(240),
            dpi = densityDpi.coerceAtLeast(120),
            flags = flags,
            mirrorDisplayId = -1,
        )
        if (displayResult.isFailure) {
            cleanupConnectionOnly()
            return@withContext Result.failure(displayResult.exceptionOrNull()!!)
        }

        displayId = displayResult.getOrThrow()
        displayRemovedExternally = false
        registerDisplayListener()
        Log.i(TAG, "CREATED display=$displayId parentDesktop=$desktopDisplayId package=$packageName size=${width}x$height dpi=$densityDpi")

        val activityResult = controlApi.startActivity(packageName, displayId, freeform = true)
        if (activityResult.isFailure) {
            unregisterDisplayListener()
            if (!displayRemovedExternally) {
                controlApi.releaseDisplay(displayId, moveTasksToDefaultDisplay = false)
            }
            displayId = -1
            cleanupConnectionOnly()
            return@withContext Result.failure(activityResult.exceptionOrNull()!!)
        }
        taskId = activityResult.getOrNull() ?: -1
        Log.i(TAG, "STARTED package=$packageName display=$displayId taskId=$taskId activitySuccess=${activityResult.isSuccess}")

        video.onStreamEnded = { reason ->
            onSessionEnded?.invoke(reason)
        }
        video.onVideoConfig = { codec, actualWidth, actualHeight ->
            if (codec.equals("H.264", ignoreCase = true) && actualWidth > 0 && actualHeight > 0) {
                videoWidth = actualWidth
                videoHeight = actualHeight
                Log.i(TAG, "VIDEO_CONFIG display=$displayId actual=${actualWidth}x$actualHeight window=${width}x$height")
            }
        }

        val videoResult = video.start(displayId, outputSurface, width, height)
        Log.i(TAG, "VIDEO_START display=$displayId success=${videoResult.isSuccess} error=${videoResult.exceptionOrNull()?.message}")
        if (videoResult.isFailure) {
            unregisterDisplayListener()
            if (!displayRemovedExternally) {
                controlApi.releaseDisplay(displayId, moveTasksToDefaultDisplay = false)
            }
            displayId = -1
            cleanupConnectionOnly()
            return@withContext Result.failure(videoResult.exceptionOrNull()!!)
        }

        started = true
        if (displayRemovedExternally) {
            onSessionEnded?.invoke("display_removed")
        }
        Result.success(Unit)
    }

    suspend fun resize(width: Int, height: Int, densityDpi: Int): Result<Unit> =
        withContext(Dispatchers.IO) {
            val id = displayId
            if (!started || id < 0) return@withContext Result.success(Unit)
            val result = controlApi.resizeDisplay(
                id,
                width.coerceAtLeast(320),
                height.coerceAtLeast(240),
                densityDpi.coerceAtLeast(120),
            )
            result.onSuccess {
                video.updateResolution(width, height)
            }
            result
        }

    fun inputCoordinateSize(fallbackWidth: Int, fallbackHeight: Int): Pair<Int, Int> {
        val w = videoWidth
        val h = videoHeight
        return if (w > 0 && h > 0) w to h else fallbackWidth to fallbackHeight
    }

    fun mapContentPointToVideo(x: Int, y: Int, contentWidth: Int, contentHeight: Int): Pair<Int, Int> {
        val (vw, vh) = inputCoordinateSize(contentWidth, contentHeight)
        val mappedX = (x.toFloat() * vw / contentWidth.coerceAtLeast(1)).roundToInt()
            .coerceIn(0, (vw - 1).coerceAtLeast(0))
        val mappedY = (y.toFloat() * vh / contentHeight.coerceAtLeast(1)).roundToInt()
            .coerceIn(0, (vh - 1).coerceAtLeast(0))
        return mappedX to mappedY
    }

    suspend fun injectInput(
        event: InputEvent,
        width: Int,
        height: Int,
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        inputMutex.withLock {
            val id = displayId
            if (!started || id < 0) {
                return@withLock Result.failure(IllegalStateException("Desktop window session is not started"))
            }
            // Unlike fullscreen input, desktop-window input bypasses ConnectionSlot.
            // Update this window's own VideoStreamController before writing to ROLE_CONTROL;
            // otherwise its idle loop can still believe no input occurred and arm a freeze
            // immediately after a click (particularly when the parent Activity is on a
            // non-physical display). ACTION_CANCEL is a framework cancellation, not input.
            if (event !is android.view.MotionEvent ||
                event.actionMasked != android.view.MotionEvent.ACTION_CANCEL) {
                video.notifyUserActivity()
            }
            Log.d(TAG, "injectInput display=$id type=${event.javaClass.simpleName} action=${(event as? android.view.MotionEvent)?.actionMasked ?: (event as? android.view.KeyEvent)?.action} source=${event.source} size=${width}x${height}")
            val result = controlApi.injectInput(id, event, width, height)
            Log.d(TAG, "injectInputResult display=$id success=${result.isSuccess} value=${result.getOrNull()} error=${result.exceptionOrNull()?.message}")
            result
        }
    }

    suspend fun injectKeyEvent(event: KeyEvent): Result<Boolean> = withContext(Dispatchers.IO) {
        inputMutex.withLock {
            val id = displayId
            if (!started || id < 0) {
                return@withLock Result.failure(IllegalStateException("Desktop window session is not started"))
            }
            video.notifyUserActivity()
            controlApi.injectInput(id, event, 0, 0)
        }
    }

    suspend fun injectText(text: String): Result<Boolean> = withContext(Dispatchers.IO) {
        inputMutex.withLock {
            val id = displayId
            if (!started || id < 0) {
                return@withLock Result.failure(IllegalStateException("Desktop window session is not started"))
            }
            if (text.isEmpty()) return@withLock Result.success(true)
            video.notifyUserActivity()
            controlApi.injectText(id, text)
        }
    }

    suspend fun injectKeyCode(keyCode: Int): Result<Boolean> = withContext(Dispatchers.IO) {
        inputMutex.withLock {
            val id = displayId
            if (!started || id < 0) {
                return@withLock Result.failure(IllegalStateException("Desktop window session is not started"))
            }
            video.notifyUserActivity()
            val downTime = SystemClock.uptimeMillis()
            val down = KeyEvent(downTime, downTime, KeyEvent.ACTION_DOWN, keyCode, 0)
            val up = KeyEvent(downTime, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, keyCode, 0)
            val r1 = controlApi.injectInput(id, down, 0, 0)
            if (r1.isFailure) return@withLock r1
            controlApi.injectInput(id, up, 0, 0)
        }
    }

    suspend fun injectScrollEvent(
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        hScroll: Float,
        vScroll: Float,
        buttons: Int,
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        inputMutex.withLock {
            val id = displayId
            if (!started || id < 0) {
                return@withLock Result.failure(IllegalStateException("Desktop window session is not started"))
            }
            video.notifyUserActivity()
            val props = arrayOf(android.view.MotionEvent.PointerProperties().apply {
                this.id = -1
                toolType = android.view.MotionEvent.TOOL_TYPE_MOUSE
            })
            val coords = arrayOf(android.view.MotionEvent.PointerCoords().apply {
                this.x = x.toFloat()
                this.y = y.toFloat()
                setAxisValue(android.view.MotionEvent.AXIS_HSCROLL, hScroll)
                setAxisValue(android.view.MotionEvent.AXIS_VSCROLL, vScroll)
            })
            val now = SystemClock.uptimeMillis()
            val event = android.view.MotionEvent.obtain(
                now, now, android.view.MotionEvent.ACTION_SCROLL, 1, props, coords,
                0, buttons, 0f, 0f, 0, 0, android.view.InputDevice.SOURCE_MOUSE, 0
            )
            try {
                controlApi.injectInput(id, event, width, height)
            } finally {
                event.recycle()
            }
        }
    }

    suspend fun injectMouseEvent(
        action: Int,
        pointerId: Long,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        actionButton: Int,
        buttons: Int,
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        inputMutex.withLock {
            val id = displayId
            if (!started || id < 0) {
                return@withLock Result.failure(IllegalStateException("Desktop window session is not started"))
            }
            // Hover/move/button events for this floating window must refresh the same
            // input clock used by its idle-policy worker, just like fullscreen input.
            video.notifyUserActivity()
            controlApi.injectMouseEvent(id, action, pointerId, x, y, width, height, actionButton, buttons)
        }
    }

    fun windowDisplayId(): Int = displayId
    fun windowTaskId(): Int = taskId

    override fun close() {
        scope.launch(Dispatchers.IO) {
            runCatching { stop() }.onFailure { Log.w(TAG, "window session cleanup failed", it) }
            scope.cancel()
        }
    }

    suspend fun stop() = stopMutex.withLock {
        withContext(Dispatchers.IO) {
            if (!started && displayId < 0) {
                cleanupConnectionOnly()
                outputSurface?.release()
                outputSurface = null
                return@withContext
            }

            val id = displayId
            started = false
            unregisterDisplayListener()
            runCatching { video.stop() }.onFailure {
                Log.w(TAG, "Video stop failed during window teardown display=$id", it)
            }
            if (id >= 0 && !displayRemovedExternally) {
                val releaseResult = runCatching { controlApi.releaseDisplay(id, moveTasksToDefaultDisplay = false) }.getOrElse {
                    Result.failure<Unit>(it)
                }
                if (releaseResult.isFailure) {
                    Log.e(TAG, "RELEASE_VIRTUAL_DISPLAY failed display=$id", releaseResult.exceptionOrNull())
                } else {
                    Log.i(TAG, "RELEASE_VIRTUAL_DISPLAY succeeded display=$id")
                }
            } else if (id >= 0) {
                Log.i(TAG, "Skipping RELEASE_VIRTUAL_DISPLAY for externally removed display=$id")
            }
            displayId = -1
            taskId = -1
            outputSurface?.release()
            outputSurface = null
            cleanupConnectionOnly()
        }
    }

    private suspend fun cleanupConnectionOnly() {
        unregisterDisplayListener()
        runCatching { rpc.stopMessageLoop() }
        runCatching { transport.disconnect() }
    }

}
