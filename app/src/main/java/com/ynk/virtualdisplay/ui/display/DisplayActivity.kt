package com.ynk.virtualdisplay.ui.display

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.LinearLayout
import android.graphics.drawable.GradientDrawable
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.ynk.virtualdisplay.R
import com.ynk.virtualdisplay.MyApplication
import com.ynk.virtualdisplay.data.AppSettings
import com.ynk.virtualdisplay.data.model.AppInfo
import com.ynk.virtualdisplay.data.repository.IDisplayRepository
import com.ynk.virtualdisplay.data.repository.RecentAppHelper
import com.ynk.virtualdisplay.manager.DisplayMetricsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import org.koin.android.ext.android.inject
import com.ynk.virtualdisplay.webrtc.GatewayProcessController
import com.ynk.virtualdisplay.ui.desktop.VirtualDisplayTaskManager
import com.ynk.virtualdisplay.ui.desktop.DesktopShellActivity

@SuppressLint("ClickableViewAccessibility", "UseKtx")
class DisplayActivity : ComponentActivity() {

    companion object {
        private const val TAG = "DisplayActivity"
        private val instances = ConcurrentHashMap<Int, WeakReference<DisplayActivity>>()

        // Set only by the floating WebRTC control. A temporary gateway disconnect
        // or a decoder recreation must never implicitly re-enable local video.
        private val floatingWebRtcSuppressedDisplays = ConcurrentHashMap.newKeySet<Int>()

        // DLNA has deliberately different first-click semantics from the floating
        // WebRTC button: starting the gateway from the desktop menu must keep the
        // local preview visible. This flag is cleared when the menu is used again
        // while the gateway is already running (that second click suppresses local
        // playback), or when the activity is destroyed.
        private val dlnaKeepLocalPreviewDisplays = ConcurrentHashMap.newKeySet<Int>()

        // Explicit user choice from the eye button. null = follow the normal
        // WebRTC/DLNA policy; true = force local video drop; false = force local
        // preview on. This override intentionally has higher priority than the
        // floating-WebRTC sticky marker so the eye button remains independent.
        private val localPreviewDropOverrides = ConcurrentHashMap<Int, Boolean>()

        /**
         * Mark the first DLNA launch as a local-preview-preserving launch. The
         * marker is set before gateway startup so the gateway monitor cannot race
         * and hide the local preview when it observes the gateway becoming alive.
         */
        suspend fun setDlnaKeepLocalPreviewForDisplay(displayId: Int, keep: Boolean): Boolean {
            if (keep) {
                // A fresh DLNA first launch is an explicit request to keep the
                // local preview. Clear any stale floating-WebRTC sticky marker
                // and any previous eye-button override so this first DLNA launch
                // deterministically keeps the local preview visible.
                floatingWebRtcSuppressedDisplays.remove(displayId)
                localPreviewDropOverrides.remove(displayId)
                dlnaKeepLocalPreviewDisplays.add(displayId)
            } else {
                dlnaKeepLocalPreviewDisplays.remove(displayId)
            }

            val activity = instances[displayId]?.get() ?: return false
            if (keep && !activity.isFinishing && !activity.isDestroyed &&
                activity.isDlnaKeepLocalPreview()) {
                // This is intentionally sequential: caller must not start the
                // gateway until local decoding has been re-enabled.
                activity.setLocalDecodeSuppressedSync(false)
                kotlinx.coroutines.withContext(Dispatchers.Main.immediate) {
                    if (!activity.isFinishing && !activity.isDestroyed &&
                        activity.isDlnaKeepLocalPreview()) {
                        activity.applyLocalPreviewUi(false)
                    }
                }
            }
            return true
        }

        /**
         * Used by the desktop settings DLNA action. It changes only the local
         * preview state; the shared gateway remains running and the scrcpy
         * transport is untouched.
         */
        fun setLocalPreviewSuppressedForDisplay(displayId: Int, suppressed: Boolean): Boolean {
            val activity = instances[displayId]?.get() ?: return false
            localPreviewDropOverrides[displayId] = suppressed
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                activity.lifecycleScope.launch {
                    activity.setLocalDecodeSuppressedSync(suppressed)
                    activity.applyLocalPreviewUi(suppressed)
                }
            }
            return true
        }

        fun isLocalPreviewSuppressedForDisplay(displayId: Int): Boolean {
            return localPreviewDropOverrides[displayId]
                ?: floatingWebRtcSuppressedDisplays.contains(displayId)
        }

        fun createIntent(
            context: Context,
            displayId: Int,
            nodeKey: String? = null,
            desktopMode: Boolean = false
        ): Intent {
            return Intent(context, DisplayActivity::class.java).apply {
                putExtra("display_id", displayId)
                putExtra("node_key", nodeKey)
                putExtra("desktop_mode", desktopMode)
            }
        }
    }

    private var remoteDisplayId: Int? = null
    private var nodeKey: String? = null
    private var videoWidth = 0
    private var videoHeight = 0
    // Stable virtual-display dimensions. In desktop mode orientation policy must
    // follow the virtual screen aspect ratio, not a rotated video frame.
    private var virtualDisplayWidth = 0
    private var virtualDisplayHeight = 0
    private var currentVideoRotation = 0
    private var videoDpi = 160
    private var desktopMode = false
    private var desktopWebRtcRunning = false
    private var trackpadEnabled = false

    // Four-finger desktop gestures are consumed as one gesture so they never
    // reach the normal mouse/touchpad path.
    private var fourFingerGestureActive = false
    private var fourFingerGestureTriggered = false
    private var fourFingerStartX = 0f
    private var fourFingerStartY = 0f

    // Tracks the surface reported by VideoSurfaceView before videoWidth/Height
    // are known. Without this, onSurfaceAvailable drops the surface when
    // videoWidth==0 (the virtual display may not be registered in the system
    // DisplayManager yet at onCreate time), and nobody retroactively feeds it
    // to the decoder → permanent black screen.
    @Volatile private var pendingSurface: Surface? = null

    private var resizeJob: Job? = null
    private var remoteDisplayMonitorJob: Job? = null
    private var desktopWebRtcMonitorJob: Job? = null

    // Monotonic generation for local-preview policy requests. Older async
    // requests must never override a newer DLNA/WebRTC decision.
    private val localPreviewPolicyGeneration = AtomicLong(0L)
    private val isLocalNode: Boolean = AppSettings.getCurrentServerNodeSync().isLocal

    private lateinit var rootLayout: FrameLayout
    private lateinit var videoSurfaceView: VideoSurfaceView
    private lateinit var cursorOverlayView: DesktopCursorOverlayView
    private lateinit var statsOverlay: TextView
    private lateinit var controlPanel: DisplayControlPanel
    private lateinit var inputController: InputController

    private val interactor: com.ynk.virtualdisplay.domain.DisplayInteractor by inject()
    private val displayMetricsManager: DisplayMetricsManager by inject()
    private val gatewayProcessController: GatewayProcessController by inject()

    private val performanceStatsCallback: (String) -> Unit = { stats ->
        lifecycleScope.launch(Dispatchers.Main.immediate) {
            if (::statsOverlay.isInitialized) {
                statsOverlay.text = stats
            }
        }
    }
    
    private val repository: IDisplayRepository by lazy {
        val key = nodeKey
        if (key != null) {
            interactor.getSlot(key) ?: error("No slot found for node key $key")
        } else {
            // Fallback to active repository (legacy behavior or current node)
            val activeRepoByInject: IDisplayRepository by inject()
            activeRepoByInject
        }
    }

    private val displayListener = object : android.hardware.display.DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {
            Log.d(TAG, "onDisplayAdded: $displayId")
        }

        override fun onDisplayRemoved(displayId: Int) {
            Log.w(TAG, "onDisplayRemoved: $displayId")
            if (displayId == remoteDisplayId) {
                Log.w(TAG, "Remote display removed, finishing activity")
                finish()
            }
        }

        override fun onDisplayChanged(displayId: Int) {
            if (displayId == remoteDisplayId) {
                rootLayout.post { updateDisplayInfo(displayId) }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val displayId = intent.getIntExtra("display_id", -1)
        require(displayId != -1) { "Invalid display_id" }
        remoteDisplayId = displayId
        nodeKey = intent.getStringExtra("node_key")
        instances[displayId] = WeakReference(this)
        desktopMode = intent.getBooleanExtra("desktop_mode", false)
        // Desktop mode is inherently a trackpad/mouse-only surface. Normal mode
        // keeps the legacy touchscreen behavior unless the user explicitly enables
        // the touchpad switch.
        trackpadEnabled = desktopMode

        requestHighRefreshRate()
        enterFullscreen()
        setupContentView(displayId)

        inputController = InputController(
            repository = repository,
            displayIdProvider = { remoteDisplayId },
            scope = lifecycleScope,
            context = this,
        )
        inputController.setDesktopModeEnabled(desktopMode)
        inputController.setTrackpadModeEnabled(trackpadEnabled)

        backCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (AppSettings.captureBackCache.value) {
                    inputController.injectKey(KeyEvent.KEYCODE_BACK)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        }
        onBackPressedDispatcher.addCallback(this, backCallback)

        lifecycleScope.launch {
            AppSettings.captureBackCache.collect { capture ->
                backCallback.isEnabled = capture
            }
        }

        lifecycleScope.launch {
            AppSettings.showPerformanceStatsCache.collect {
                if (::statsOverlay.isInitialized) {
                    // Settings changes must immediately win over any mode/preview
                    // transition that may have happened at the same time.
                    applyStatsOverlayVisibility()
                }
            }
        }

        if (isLocalNode) {
            val dm = getSystemService(android.hardware.display.DisplayManager::class.java)
            dm.registerDisplayListener(displayListener, null)
        }
        updateDisplayInfo(displayId)
        startDisconnectDetection(displayId)
        startWebRtcMonitor()

        // Diagnostics are opt-in. When the overlay is hidden, the decoder no
        // longer maintains per-frame latency maps just for invisible UI.
        applyStatsOverlayVisibility()

        repository.setVideoConfigCallback { width, height ->
            lifecycleScope.launch(Dispatchers.Main) {
                onVideoConfigChanged(width, height)
            }
        }
    }

    override fun onDestroy() {
        desktopWebRtcMonitorJob?.cancel()
        // The gateway is an independent privileged process and intentionally
        // survives Activity/Surface recreation and switching the APP to background.
        // It is stopped only by the explicit WebRTC stop control, never as a side
        // effect of Activity destruction.
        super.onDestroy()
        backCallback.remove()
        repository.setPerformanceStatsCallback(null)
        repository.setVideoConfigCallback(null)
        resizeJob?.cancel()
        remoteDisplayMonitorJob?.cancel()
        desktopWebRtcMonitorJob?.cancel()
        if (isLocalNode) {
            val dm = getSystemService(android.hardware.display.DisplayManager::class.java)
            dm.unregisterDisplayListener(displayListener)
        }
        remoteDisplayId?.let { id ->
            floatingWebRtcSuppressedDisplays.remove(id)
            dlnaKeepLocalPreviewDisplays.remove(id)
            localPreviewDropOverrides.remove(id)
            val current = instances[id]?.get()
            if (current === this) instances.remove(id)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            enterFullscreen()
        }
    }

    private fun requestHighRefreshRate() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            @Suppress("DEPRECATION")
            val display = windowManager.defaultDisplay
            val modes = display.supportedModes
            val bestMode = modes.maxByOrNull { it.refreshRate }

            if (bestMode != null && bestMode.refreshRate > 60f) {
                val params = window.attributes
                params.preferredDisplayModeId = bestMode.modeId
                window.attributes = params
                Log.i(TAG, "Set preferredDisplayModeId to ${bestMode.modeId} (${bestMode.refreshRate}Hz)")
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val params = window.attributes
                params.preferredRefreshRate = 120f
                window.attributes = params
            }
        }
    }

    private fun enterFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.statusBars())
            hide(WindowInsetsCompat.Type.navigationBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupContentView(displayId: Int) {
        rootLayout = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            setBackgroundColor(Color.BLACK)
            isFocusable = false
            isFocusableInTouchMode = false
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                updateContentRect()
                if (::cursorOverlayView.isInitialized) cursorOverlayView.invalidate()
            }
            setOnTouchListener { view, event ->
                handleTouchEvent(event)
            }
        }

        videoSurfaceView = VideoSurfaceView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER,
            )
            setVideoCallbacks(object : VideoSurfaceView.VideoCallbacks {
                override fun onSurfaceAvailable(surface: Surface) {
                    Log.i(TAG, "onSurfaceAvailable: surface=$surface valid=${surface.isValid} videoSize=${videoWidth}x${videoHeight}")
                    pendingSurface = surface
                    // Regardless of whether videoWidth/Height are known, we MUST set the surface to the repository.
                    // For remote displays, we might never find the display in the local DisplayManager.
                    // Starting the stream allows the decoder to eventually report the correct dimensions.
                    setVideoSurface(surface)
                    pendingSurface = null
                }

                override fun onSurfaceDestroyed() {
                    Log.i(TAG, "onSurfaceDestroyed")
                    pendingSurface = null
                    setVideoSurface(null)
                }

                override fun onSurfaceChanged(width: Int, height: Int) {
                    Log.d(TAG, "onSurfaceChanged: ${width}x${height}")
                }
            })
            setInputCallbacks(object : VideoSurfaceView.InputCallbacks {
                override fun injectKeyEvent(event: KeyEvent): Boolean {
                    return inputController.handleKeyEvent(event)
                }

                override fun injectText(text: String): Boolean {
                    return injectTextViaKeyMap(text)
                }

                override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                    for (i in 0 until beforeLength) {
                        inputController.injectKey(KeyEvent.KEYCODE_DEL)
                    }
                    return true
                }
            })
        }
        rootLayout.addView(videoSurfaceView)

        // Draw the desktop/trackpad cursor in the app window itself. A system
        // mouse pointer is a separate SurfaceFlinger layer and is not included
        // in the captured virtual-display frame, so this overlay is also what
        // lets the phone act as a visible local touchpad.
        cursorOverlayView = DesktopCursorOverlayView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.TOP or Gravity.START,
            )
            attachToVideoView(videoSurfaceView)
            visibility = if (desktopMode || trackpadEnabled) View.VISIBLE else View.GONE
        }
        rootLayout.addView(cursorOverlayView)

        controlPanel = DisplayControlPanel(
            context = this,
            onBackClick = { inputController.injectKey(KeyEvent.KEYCODE_BACK) },
            onDesktopHomeClick = {
                val id = remoteDisplayId
                if (desktopMode && id != null) {
                    lifecycleScope.launch {
                        repository.launchHome(id)
                            .onFailure { Log.w(TAG, "Failed to return to virtual desktop home", it) }
                    }
                }
            },
            onAppLauncherClick = { showAppSelectionDialog() },
            onKeyboardClick = { toggleKeyboard() },
            onTrackpadClick = { toggleTrackpadMode() },
            showTrackpadButton = !desktopMode,
            onWebRtcClick = { toggleWebRtc() },
            onLocalPreviewToggleClick = { toggleLocalPreviewDrop() },
            onCloseClick = { finish() },
        )
        controlPanel.setLocalPreviewSuppressed(
            shouldHideLocalPreview(gatewayProcessController.isRunning())
        )
        rootLayout.addView(controlPanel)

        statsOverlay = TextView(this).apply {
            setTextColor(Color.GREEN)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setBackgroundColor(Color.parseColor("#80000000"))
            setPadding(16, 16, 16, 16)
            // Start hidden. AppSettings loads DataStore asynchronously; the collector
            // below is responsible for making the persisted diagnostics setting visible.
            // This prevents a disabled setting from flashing during Activity creation.
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END,
            ).apply {
                topMargin = 120
                rightMargin = 50
            }
        }
        rootLayout.addView(statsOverlay)

        setContentView(rootLayout)
    }

    private fun startWebRtcMonitor() {
        desktopWebRtcMonitorJob?.cancel()
        desktopWebRtcMonitorJob = lifecycleScope.launch(Dispatchers.IO) {
            while (!isFinishing && !isDestroyed) {
                MyApplication.awaitPhysicalDisplayForeground()
                if (isFinishing || isDestroyed) break
                val running = gatewayProcessController.isRunning()
                val stickySuppressed = isFloatingWebRtcLocalPreviewSuppressed()
                val displayId = remoteDisplayId
                val override = displayId?.let { localPreviewDropOverrides[it] }

                // The eye-button choice is authoritative while present.
                // This prevents the WebRTC sticky monitor or desktop-mode fallback
                // from fighting an explicit user command.
                if (override != null) {
                    setLocalDecodeSuppressedSync(override)
                } else {
                    // DLNA first launch deliberately keeps local playback. Do not
                    // clear the marker merely because gateway startup briefly reports
                    // not-running. It is explicitly cleared by the second DLNA click,
                    // floating WebRTC, or activity destruction.
                    if (isDlnaKeepLocalPreview()) {
                        setLocalDecodeSuppressedSync(false)
                    }

                    // Keep the floating-WebRTC sticky policy asserted independently
                    // of gateway liveness. VideoStreamController also persists this
                    // flag across decoder recreation.
                    if (stickySuppressed) {
                        setLocalDecodeSuppressedSync(true)
                    }
                }

                if (running != desktopWebRtcRunning) {
                    desktopWebRtcRunning = running
                    runOnUiThread { updateWebRtcUi(running) }
                }
                kotlinx.coroutines.delay(500)
            }
        }
    }

    private fun isFloatingWebRtcLocalPreviewSuppressed(): Boolean {
        val displayId = remoteDisplayId ?: return false
        return floatingWebRtcSuppressedDisplays.contains(displayId)
    }

    private fun isDlnaKeepLocalPreview(): Boolean {
        val displayId = remoteDisplayId ?: return false
        return dlnaKeepLocalPreviewDisplays.contains(displayId)
    }

    private fun shouldHideLocalPreview(webRtcRunning: Boolean): Boolean {
        remoteDisplayId?.let { displayId ->
            // The eye-button override is an explicit user command and therefore
            // outranks every automatic WebRTC/DLNA policy.
            localPreviewDropOverrides[displayId]?.let { return it }
        }

        // Once WebRTC is explicitly enabled from the floating control, the local
        // decoder stays disabled until that same control explicitly stops WebRTC.
        if (isFloatingWebRtcLocalPreviewSuppressed()) return true

        // The first DLNA menu launch deliberately keeps local playback even though
        // desktopMode is normally a reason to suppress the local decoder. This check
        // is evaluated by the gateway monitor, so it also covers the race where the
        // gateway becomes alive before its startup callback reaches DesktopShellActivity.
        if (webRtcRunning && isDlnaKeepLocalPreview()) return false

        // Preserve the pre-existing desktop/trackpad-only suppression for other
        // gateway entry points such as the second DLNA click.
        return webRtcRunning && (desktopMode || trackpadEnabled)
    }

    private suspend fun setLocalDecodeSuppressedSync(suppressed: Boolean) {
        val generation = localPreviewPolicyGeneration.incrementAndGet()
        setLocalDecodeSuppressedForGeneration(suppressed, generation)
    }

    private suspend fun setLocalDecodeSuppressedForGeneration(
        suppressed: Boolean,
        generation: Long,
    ) {
        if (generation != localPreviewPolicyGeneration.get()) return
        val displayId = remoteDisplayId ?: return
        repository.setLocalVideoDecodeEnabled(displayId, !suppressed)
            .onFailure { Log.w(TAG, "Failed to set local video decode=${!suppressed}", it) }
    }

    private fun applyStatsOverlayVisibility() {
        if (!::statsOverlay.isInitialized) return
        val shouldShow = AppSettings.showPerformanceStatsCache.value &&
            !shouldHideLocalPreview(gatewayProcessController.isRunning())
        statsOverlay.visibility = if (shouldShow) View.VISIBLE else View.GONE
        repository.setPerformanceStatsCallback(if (shouldShow) performanceStatsCallback else null)
    }

    private fun applyLocalPreviewUi(suppressed: Boolean) {
        if (::videoSurfaceView.isInitialized) {
            videoSurfaceView.visibility = if (suppressed) View.INVISIBLE else View.VISIBLE
        }
        if (::rootLayout.isInitialized) {
            // Keep the whole activity canvas opaque black at all times. The
            // SurfaceView is fit-center sized, so the unused area around the
            // video must never expose a transparent/underlying layer.
            rootLayout.setBackgroundColor(Color.BLACK)
        }
        if (::controlPanel.isInitialized) {
            controlPanel.visibility = View.VISIBLE
            controlPanel.setLocalPreviewSuppressed(suppressed)
        }
        // Do not use the preview state as a command to show diagnostics.
        // The diagnostics switch in Settings remains authoritative across all
        // WebRTC/trackpad/desktop-mode transitions.
        if (::statsOverlay.isInitialized) {
            applyStatsOverlayVisibility()
        }
    }

    private fun setLocalDecodeSuppressed(suppressed: Boolean) {
        val generation = localPreviewPolicyGeneration.incrementAndGet()
        lifecycleScope.launch(Dispatchers.IO) {
            setLocalDecodeSuppressedForGeneration(suppressed, generation)
        }
    }

    private fun updateWebRtcUi(running: Boolean) {
        // Always clear any old input mode before switching state. In particular,
        // disabling the normal-mode touchpad must leave the next gesture entirely
        // on the legacy touchscreen path.
        inputController.cancelActiveTouch()
        inputController.setTrackpadModeEnabled(if (desktopMode) true else trackpadEnabled)

        if (desktopMode || trackpadEnabled) {
            if (videoWidth > 0 && videoHeight > 0) {
                DesktopCursorState.reset(videoWidth, videoHeight, videoDpi)
            }
        } else {
            DesktopCursorState.hide()
        }
        if (::cursorOverlayView.isInitialized) {
            cursorOverlayView.visibility = if (desktopMode || trackpadEnabled) View.VISIBLE else View.GONE
            cursorOverlayView.invalidate()
        }

        val hideLocalPreview = shouldHideLocalPreview(running)
        setLocalDecodeSuppressed(hideLocalPreview)
        applyLocalPreviewUi(hideLocalPreview)

        Log.i(
            TAG,
            "WebRTC UI updated: running=$running desktopMode=$desktopMode " +
                "trackpadEnabled=$trackpadEnabled hideLocalPreview=$hideLocalPreview"
        )
    }

    private fun toggleTrackpadMode() {
        if (desktopMode) return

        val newEnabled = !trackpadEnabled

        // Finish every remote gesture before changing input mode. When switching
        // back to touchscreen mode this prevents a previous mouse/touchpad gesture
        // from leaking into the first subsequent touchscreen gesture.
        inputController.cancelActiveTouch()
        trackpadEnabled = newEnabled
        inputController.setTrackpadModeEnabled(newEnabled)

        val running = gatewayProcessController.isRunning()
        val hideLocalPreview = shouldHideLocalPreview(running)
        setLocalDecodeSuppressed(hideLocalPreview)
        applyLocalPreviewUi(hideLocalPreview)
        if (trackpadEnabled) {
            if (videoWidth > 0 && videoHeight > 0) {
                DesktopCursorState.reset(videoWidth, videoHeight, videoDpi)
            }
        } else {
            DesktopCursorState.hide()
        }
        if (::cursorOverlayView.isInitialized) {
            cursorOverlayView.visibility = if (trackpadEnabled || desktopMode) View.VISIBLE else View.GONE
            cursorOverlayView.invalidate()
        }
        Log.i(TAG, "trackpadEnabled=$trackpadEnabled running=$running hideLocalPreview=$hideLocalPreview")
    }

    private fun toggleLocalPreviewDrop() {
        val displayId = remoteDisplayId
        if (displayId == null || displayId < 0) return

        val current = shouldHideLocalPreview(gatewayProcessController.isRunning())
        val next = !current

        // The eye button is the explicit user override. In particular, it can
        // override the WebRTC startup sticky policy and keep the local decoder on
        // without touching the WebRTC connection itself.
        localPreviewDropOverrides[displayId] = next
        localPreviewPolicyGeneration.incrementAndGet()

        lifecycleScope.launch(Dispatchers.IO) {
            setLocalDecodeSuppressedSync(next)
        }
        applyLocalPreviewUi(next)

        Log.i(
            TAG,
            "Local preview eye toggle: displayId=$displayId suppressed=$next " +
                "gatewayRunning=${gatewayProcessController.isRunning()}"
        )
    }

    private fun toggleWebRtc() {
        val displayId = remoteDisplayId
        if (displayId == null || displayId < 0) {
            android.widget.Toast.makeText(this, "无有效虚拟屏幕", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            if (gatewayProcessController.isRunning()) {
                // Explicit floating-button stop ends both entry-point markers.
                floatingWebRtcSuppressedDisplays.remove(displayId)
                localPreviewDropOverrides.remove(displayId)
                dlnaKeepLocalPreviewDisplays.remove(displayId)
                setLocalDecodeSuppressedSync(false)
                runCatching { gatewayProcessController.stop() }
                runOnUiThread {
                    applyLocalPreviewUi(false)
                    updateWebRtcUi(false)
                    android.widget.Toast.makeText(this@DisplayActivity, "WebRTC 已停止", android.widget.Toast.LENGTH_SHORT).show()
                }
                return@launch
            }

            // Floating-button WebRTC always drops the local decoder, regardless
            // of desktop/trackpad mode. A floating launch takes precedence over
            // any previous DLNA first-launch marker.
            dlnaKeepLocalPreviewDisplays.remove(displayId)

            // Mark this before gateway startup so even a slow/failed handshake
            // cannot temporarily re-enable local decode. The eye-button override
            // starts in the hidden state as well, but can be toggled independently.
            localPreviewDropOverrides[displayId] = true
            floatingWebRtcSuppressedDisplays.add(displayId)
            setLocalDecodeSuppressedSync(true)
            runOnUiThread { applyLocalPreviewUi(true) }

            // WebRTC Gateway 现在直接连接当前 scrcpy daemon 的 ROLE_NEGOTIATION +
            // ROLE_VIDEO，不再创建额外的 VDH1 H.264 旁路。
            val result = gatewayProcessController.start(
                displayId = displayId,
                gatewayPort = GatewayProcessController.DEFAULT_GATEWAY_PORT,
                bindHost = "0.0.0.0",
                expectedWidth = videoWidth,
                expectedHeight = videoHeight,
            )
            result.onSuccess { url ->
                runOnUiThread {
                    updateWebRtcUi(true)
                    android.widget.Toast.makeText(this@DisplayActivity, "WebRTC 已启动: $url", android.widget.Toast.LENGTH_SHORT).show()
                }
            }.onFailure { error ->
                Log.e(TAG, "Failed to start WebRTC gateway", error)
                // The activation never completed, so undo the sticky policy.
                floatingWebRtcSuppressedDisplays.remove(displayId)
                localPreviewDropOverrides.remove(displayId)
                dlnaKeepLocalPreviewDisplays.remove(displayId)
                setLocalDecodeSuppressedSync(false)
                runOnUiThread {
                    applyLocalPreviewUi(false)
                    updateWebRtcUi(false)
                    android.widget.Toast.makeText(this@DisplayActivity, "WebRTC 启动失败: ${error.message}", android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun setVideoSurface(surface: Surface?) {
        // 架构边界：协程内任何未处理异常最终都会崩溃进程（CoroutineExceptionHandler 默认行为）
        // 此处 runCatching 不是"隐藏问题"，是防止协程内部意外异常变成 uncaught 崩溃；
        // 失败统一走 Result + 日志 + UI 反馈传导，绝不静默吞。
        lifecycleScope.launch(Dispatchers.Main.immediate) {
            val result = runCatching {
                repository.setDisplaySurface(remoteDisplayId!!, surface)
            }.fold(
                onSuccess = { it },
                onFailure = { Result.failure(it) }
            )
            result.onFailure {
                Log.e(TAG, "Failed to set display surface (surface=$surface)", it)
                // TODO: 通知 UI 层（Toast/内联错误提示）视频连接失败
                // 目前先只保证进程不崩溃，失败信息通过日志可追踪
            }

            // Surface rebinds are another point where a fresh decoder/output
            // path may otherwise drift away from the explicit eye/WebRTC/DLNA
            // preview policy.
            val suppressed = shouldHideLocalPreview(gatewayProcessController.isRunning())
            setLocalDecodeSuppressedSync(suppressed)
            applyLocalPreviewUi(suppressed)
        }
    }

    private fun onVideoConfigChanged(width: Int, height: Int) {
        Log.i(TAG, "onVideoConfigChanged: ${width}x${height}")
        videoWidth = width
        videoHeight = height
        inputController.updateVideoSize(width, height)
        videoSurfaceView.setVideoSize(width, height)
        if ((desktopMode || trackpadEnabled) && width > 0 && height > 0) {
            DesktopCursorState.reset(width, height, videoDpi)
            if (::cursorOverlayView.isInitialized) cursorOverlayView.invalidate()
        }

        val viewW = rootLayout.width
        val viewH = rootLayout.height
        if (viewW > 0 && viewH > 0) {
            // 根据宽高比自动调整旋转角度
            // 如果画面变横屏（w > h）但 Activity 还是竖屏（w < h），则需要旋转
            val needRotate = (width > height) != (viewW > viewH)
            currentVideoRotation = if (needRotate) 90 else 0
            inputController.setVideoRotation(currentVideoRotation)
            updateContentRect()
        }

        if (!desktopMode) {
            applyOrientationForVideo(width, height)
        }
    }

    private fun updateDisplayInfo(displayId: Int) {
        lifecycleScope.launch(Dispatchers.IO) {
            // 优先走 RPC：远程和本地节点统一从服务端获取显示尺寸
            val infosResult = repository.getActiveDisplayInfos()
            val displayInfo = infosResult.getOrNull()?.firstOrNull { it.displayId == displayId }
            if (displayInfo != null) {
                val isFirstDiscovery = (videoWidth == 0 && videoHeight == 0)
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    Log.i(TAG, "updateDisplayInfo: RPC display #$displayId size=${displayInfo.width}x${displayInfo.height}")
                    applyDisplayDimensions(displayInfo.width, displayInfo.height, displayInfo.dpi, isFirstDiscovery)
                }
                return@launch
            }

            // 回退路径：本机节点本地 DisplayManager 直接查
            if (isLocalNode) {
                val dm = getSystemService(android.hardware.display.DisplayManager::class.java)
                val display = dm.getDisplay(displayId)
                if (display != null) {
                    val spec = displayMetricsManager.getVirtualDisplaySpec(display)
                    val isFirstDiscovery = (videoWidth == 0 && videoHeight == 0)
                    kotlinx.coroutines.withContext(Dispatchers.Main) {
                        applyDisplayDimensions(spec.width, spec.height, spec.dpi, isFirstDiscovery)
                    }
                    return@launch
                }
            }

            // 最后回退：从 AppSettings 读缓存（所有节点通用）
            val node = AppSettings.getCurrentServerNodeSync()
            val saved = AppSettings.getDisplaysForServer(this@DisplayActivity, node).find { it.id == displayId }
            if (saved != null) {
                val isFirstDiscovery = (videoWidth == 0 && videoHeight == 0)
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    Log.i(TAG, "updateDisplayInfo: found saved display #$displayId size=${saved.width}x${saved.height}")
                    applyDisplayDimensions(saved.width, saved.height, saved.dpi, isFirstDiscovery)
                }
            } else {
                Log.w(TAG, "updateDisplayInfo: Display #$displayId not found via RPC, local DM, or saved cache")
            }
        }
    }

    /**
     * 统一的"显示器存活检测"：
     * - 对所有节点：监听 connectionStatus，当连接断开（DISCONNECTED/ERROR）时立即 finish()
     * - 对远程节点额外：每 15s RPC 轮询服务端显示器列表，检测 displayId 是否被销毁
     *   （远程 DisplayManager.DisplayListener 收不到本地系统回调，需要 RPC 替代）
     */
    private fun startDisconnectDetection(displayId: Int) {
        // 1. 即时路径：监听连接状态变化
        lifecycleScope.launch {
            repository.connectionStatus.collect { status ->
                if (status == com.ynk.virtualdisplay.data.repository.ConnectionStatus.DISCONNECTED ||
                    status == com.ynk.virtualdisplay.data.repository.ConnectionStatus.ERROR
                ) {
                    // Do not destroy the controller Activity while the app is hidden on
                    // physical display 0. Wait without polling, then re-check the latest
                    // state in case the reconnect completed while we were suspended.
                    MyApplication.awaitPhysicalDisplayForeground()
                    if (isFinishing || isDestroyed) return@collect
                    val latest = repository.connectionStatus.value
                    if (latest == com.ynk.virtualdisplay.data.repository.ConnectionStatus.DISCONNECTED ||
                        latest == com.ynk.virtualdisplay.data.repository.ConnectionStatus.ERROR
                    ) {
                        Log.w(TAG, "Connection status=$latest, finishing activity")
                        finish()
                    }
                }
            }
        }

        // 2. 远程节点兜底：15s RPC 轮询服务端显示器列表
        if (!isLocalNode) {
            remoteDisplayMonitorJob = lifecycleScope.launch(Dispatchers.IO) {
                while (!isFinishing) {
                    MyApplication.awaitPhysicalDisplayForeground()
                    if (isFinishing) break
                    kotlinx.coroutines.delay(15_000)
                    if (isFinishing) break
                    if (!MyApplication.isPhysicalDisplayForeground &&
                        !GatewayProcessController.isGatewayRunningKnown()
                    ) continue
                    val result = repository.getActiveDisplayInfos()
                    val stillExists = result.getOrNull()?.any { it.displayId == displayId } ?: true
                    if (!stillExists) {
                        Log.w(TAG, "Remote display #$displayId no longer active on server, finishing activity")
                        kotlinx.coroutines.withContext(Dispatchers.Main) { finish() }
                    }
                }
            }
        }
    }

    private fun applyDisplayDimensions(width: Int, height: Int, dpi: Int, isFirstDiscovery: Boolean) {
        if (width <= 0 || height <= 0) return
        virtualDisplayWidth = width
        virtualDisplayHeight = height
        if (desktopMode) {
            applyOrientationForVirtualDisplay(width, height)
        }
        if (videoWidth != width || videoHeight != height) {
            Log.i(TAG, "applyDisplayDimensions: #$remoteDisplayId size=${width}x${height} (prev=${videoWidth}x${videoHeight})")
            videoWidth = width
            videoHeight = height
            videoDpi = dpi.coerceAtLeast(1)
            inputController.updateVideoSize(width, height)
            videoSurfaceView.setVideoSize(width, height)
            if ((desktopMode || trackpadEnabled) && width > 0 && height > 0) {
                DesktopCursorState.reset(width, height, videoDpi)
                if (::cursorOverlayView.isInitialized) cursorOverlayView.invalidate()
            }

            // Retroactively feed the pending surface to the decoder if it
            // arrived before display dimensions were known.
            val ps = pendingSurface
            if (ps != null && ps.isValid) {
                Log.i(TAG, "applyDisplayDimensions: applying pending surface now that dimensions are known")
                pendingSurface = null
                setVideoSurface(ps)
            }

            // Only send a resize command to the daemon when the display
            // dimensions *changed* from a previously known value. On first
            // discovery (isFirstDiscovery), the display was just created
            // with the correct dimensions by createDisplay — sending a
            // redundant resize causes the server to restart its
            // SurfaceEncoder, which closes the video socket and kills the
            // decoder stream.
            if (!isFirstDiscovery) {
                resizeJob?.cancel()
                resizeJob = lifecycleScope.launch {
                    kotlinx.coroutines.delay(250)
                    MyApplication.awaitPhysicalDisplayForeground()
                    if (!isFinishing && !isDestroyed) {
                        repository.resizeDisplay(remoteDisplayId!!, width, height, dpi)
                    }
                }
            } else {
                Log.i(TAG, "applyDisplayDimensions: first discovery, skipping resize (display already created with correct dimensions)")
            }
        }
    }

    private fun updateContentRect() {
        if (::inputController.isInitialized && ::rootLayout.isInitialized) {
            inputController.getContentRect(rootLayout)
        }
    }

    private fun applyOrientationForVirtualDisplay(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        // Desktop mode keeps the orientation axis fixed to the virtual display's
        // aspect ratio. SENSOR_LANDSCAPE/SENSOR_PORTRAIT allow the two directions
        // on the same axis (0/180) while preventing 90/270 from changing the
        // virtual screen between portrait and landscape.
        val targetOrientation = when {
            width > height -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            height > width -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            else -> ActivityInfo.SCREEN_ORIENTATION_NOSENSOR
        }
        if (requestedOrientation != targetOrientation) {
            requestedOrientation = targetOrientation
            Log.i(TAG, "applyOrientationForVirtualDisplay ${width}x${height} -> orientation=$targetOrientation")
        }
    }

    private fun applyOrientationForVideo(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        val targetOrientation = when {
            width > height -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            height > width -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            else -> ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        }
        if (requestedOrientation != targetOrientation) {
            requestedOrientation = targetOrientation
            Log.i(TAG, "applyOrientationForVideo ${width}x${height} -> orientation=$targetOrientation")
        }
    }

    private fun toggleKeyboard() {
        if (!::videoSurfaceView.isInitialized) return
        val willEnable = !videoSurfaceView.isKeyboardActive()
        videoSurfaceView.requestKeyboardInput(willEnable)

        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        if (willEnable) {
            val targetView = videoSurfaceView
            targetView.requestFocus()
            Handler(Looper.getMainLooper()).post {
                @Suppress("DEPRECATION")
                imm.showSoftInput(targetView, InputMethodManager.SHOW_IMPLICIT)
            }
        } else {
            imm.hideSoftInputFromWindow(videoSurfaceView.windowToken, 0)
        }
        Log.d(TAG, "Keyboard ${if (willEnable) "shown" else "hidden"}")
    }

    /**
     * Desktop mode has exactly one touch entry point. We consume every touch
     * gesture outside the floating control panel here, before Android dispatches
     * it to VideoSurfaceView/root children. This prevents a second listener or
     * child view from producing a legacy touchscreen stream for the same finger
     * sequence.
     *
     * Normal mode deliberately delegates to the framework, preserving the
     * original rootLayout touch listener and touchscreen injection path.
     */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        // Pure touchscreen mode delegates to the original view hierarchy. Its
        // idle wake-up is handled later only when the event is routed to the
        // actual remote video area by handleTouchEvent().
        if (!desktopMode && !trackpadEnabled) {
            return super.dispatchTouchEvent(event)
        }

        val inControlPanel = ::controlPanel.isInitialized &&
            isPointInsideView(event.rawX, event.rawY, controlPanel)

        if (inControlPanel) {
            // Local control-panel clicks are not input to the virtual display;
            // they must not cancel its idle policy. Visual changes caused by a
            // button action are detected by the server-side frame monitor.
            return super.dispatchTouchEvent(event)
        }

        // Desktop/touchpad mode sends touches outside the local control panel
        // through the virtual-display input controller. Only this routed input
        // counts as activity; no app-wide/physical-screen touch signal is used.
        if (::inputController.isInitialized && inputController.hasVideoSize() &&
            event.actionMasked != MotionEvent.ACTION_CANCEL &&
            event.actionMasked != MotionEvent.ACTION_OUTSIDE) {
            remoteDisplayId?.let { repository.notifyVideoUserActivity(it) }
        }

        // Four-finger gestures are a desktop-only command layer. Once the
        // fourth pointer arrives, consume the entire gesture and do not feed
        // any of it into the mouse/trackpad state machine.
        if (fourFingerGestureActive || event.pointerCount >= 4) {
            handleFourFingerGesture(event)
            return true
        }

        if (::inputController.isInitialized && ::rootLayout.isInitialized) {
            inputController.handleTrackpadEvent(rootLayout, event)
        }

        // Hard-consume the event so VideoSurfaceView and rootLayout never see
        // the same gesture after the desktop trackpad has handled it.
        return true
    }

    private fun handleFourFingerGesture(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP -> {
                if (event.pointerCount >= 4) {
                    // Explicitly cancel any deferred 2/3-finger click before the
                    // 4-finger command is handled. This is the hard priority wall.
                    if (::inputController.isInitialized) {
                        inputController.cancelPendingMultiFingerClick()
                    }
                }

                if (event.pointerCount >= 4 && !fourFingerGestureActive) {
                    fourFingerGestureActive = true
                    fourFingerGestureTriggered = false
                    var sx = 0f
                    var sy = 0f
                    for (i in 0 until event.pointerCount) {
                        sx += event.getX(i)
                        sy += event.getY(i)
                    }
                    fourFingerStartX = sx / event.pointerCount
                    fourFingerStartY = sy / event.pointerCount

                    // 4 fingers are a global desktop command layer. Route the
                    // command to the DesktopShell that is actually running on
                    // this virtual display. Never create UI on the physical
                    // DisplayActivity window.
                    remoteDisplayId?.let { DesktopShellActivity.showDockForDisplay(it) }
                }

                if (fourFingerGestureActive && !fourFingerGestureTriggered && event.pointerCount >= 4) {
                    var x = 0f
                    var y = 0f
                    for (i in 0 until event.pointerCount) {
                        x += event.getX(i)
                        y += event.getY(i)
                    }
                    val cx = x / event.pointerCount
                    val cy = y / event.pointerCount
                    val dx = cx - fourFingerStartX
                    val dy = cy - fourFingerStartY
                    val threshold = TypedValue.applyDimension(
                        TypedValue.COMPLEX_UNIT_DIP,
                        120f,
                        resources.displayMetrics
                    )

                    if (kotlin.math.hypot(dx.toDouble(), dy.toDouble()) >= threshold) {
                        fourFingerGestureTriggered = true
                        val id = remoteDisplayId
                        when {
                            -dy > kotlin.math.abs(dx) * 1.15f -> {
                                // Returning to the desktop no longer implicitly hides
                                // the floating Dock. The user can keep it visible and
                                // invoke it again with the same four-finger gesture.
                                if (id != null) {
                                    lifecycleScope.launch {
                                        repository.launchHome(id)
                                            .onFailure { Log.w(TAG, "Four-finger home failed", it) }
                                    }
                                }
                            }
                            -dx > kotlin.math.abs(dy) * 1.15f -> {
                                if (id != null) {
                                    lifecycleScope.launch(Dispatchers.IO) {
                                        VirtualDisplayTaskManager.focusRelativeTask(
                                            this@DisplayActivity, id, +1
                                        )
                                    }
                                }
                            }
                            dx > kotlin.math.abs(dy) * 1.15f -> {
                                inputController.injectKey(KeyEvent.KEYCODE_BACK)
                            }
                        }
                    }
                }
            }
        }

        if (event.actionMasked == MotionEvent.ACTION_UP ||
            event.actionMasked == MotionEvent.ACTION_CANCEL) {
            fourFingerGestureActive = false
            fourFingerGestureTriggered = false
            fourFingerStartX = 0f
            fourFingerStartY = 0f
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun isPointInsideView(rawX: Float, rawY: Float, view: View): Boolean {
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        return rawX >= location[0] &&
            rawX < location[0] + view.width &&
            rawY >= location[1] &&
            rawY < location[1] + view.height
    }

    @SuppressLint("RestrictedApi")
    private fun handleTouchEvent(event: MotionEvent): Boolean {
        // A non-clickable gap/padding inside the local control-panel container can
        // fall through to the root touch listener. Keep it local instead of treating
        // it as a gesture on the virtual-display input surface.
        if (::controlPanel.isInitialized &&
            isPointInsideView(event.rawX, event.rawY, controlPanel)) {
            return false
        }

        if (desktopMode || trackpadEnabled) {
            // Desktop mode is always a trackpad surface. Normal mode enters the
            // same path only when the user enables the explicit touchpad switch.
            // Never fall through to the legacy touchscreen path in either case.
            if (inputController.hasVideoSize()) {
                remoteDisplayId?.let { repository.notifyVideoUserActivity(it) }
            }
            return inputController.handleTrackpadEvent(rootLayout, event)
        }

        val insideVideoArea = isInsideVideoArea(event)
        val shouldHandleRemotely = inputController.shouldHandleRemotely(event) &&
            (insideVideoArea || event.actionMasked == MotionEvent.ACTION_DOWN)

        // Only actual pointer events on the displayed video content can end
        // idle. Touches on letterbox bars, local controls, or unrelated app
        // windows never reach this notification path.
        if (shouldHandleRemotely && insideVideoArea &&
            event.actionMasked != MotionEvent.ACTION_CANCEL &&
            event.actionMasked != MotionEvent.ACTION_OUTSIDE) {
            remoteDisplayId?.let { repository.notifyVideoUserActivity(it) }
        }

        val handled = if (shouldHandleRemotely) {
            inputController.handleMotionEvent(rootLayout, event)
        } else {
            false
        }
        return handled
    }

    private fun isInsideVideoArea(event: MotionEvent): Boolean {
        val rect = inputController.getContentRect(rootLayout) ?: return false
        return event.x in rect.left..rect.right && event.y in rect.top..rect.bottom
    }

    private fun injectTextViaKeyMap(text: String): Boolean {
        val kcm = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)
        val events: Array<KeyEvent> = kcm.getEvents(text.toCharArray()) ?: return false
        lifecycleScope.launch(Dispatchers.Main) {
            for (event in events.iterator()) {
                val cleanEvent = KeyEvent(
                    event.downTime,
                    event.eventTime,
                    event.action,
                    event.keyCode,
                    event.repeatCount,
                    event.metaState,
                    0,
                    0,
                    event.flags,
                    InputDevice.SOURCE_KEYBOARD,
                )
                inputController.handleKeyEvent(cleanEvent)
            }
        }
        return true
    }

    private lateinit var backCallback: OnBackPressedCallback

    @SuppressLint("RestrictedApi", "GestureBackNavigation")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        remoteDisplayId?.let { repository.notifyVideoUserActivity(it) }

        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (AppSettings.captureBackCache.value) {
                if (event.action == KeyEvent.ACTION_UP) {
                    inputController.injectKey(KeyEvent.KEYCODE_BACK)
                }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun showAppSelectionDialog() {
        lifecycleScope.launch(Dispatchers.IO) {
            val recentPkgs = RecentAppHelper.getRecentApps(this@DisplayActivity)
            val appsResult = repository.listApps()
            appsResult.onSuccess { apps ->
                val allApps = apps.map { AppInfo(it.name, it.packageName) }

                val recentList = mutableListOf<AppInfo>()
                recentPkgs.forEach { pkg ->
                    val app = allApps.find { it.packageName == pkg }
                    if (app != null) recentList.add(app)
                }
                val otherList = allApps.filter { it.packageName !in recentPkgs }.sortedBy { it.name }
                val sortedAppList = recentList + otherList

                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    if (sortedAppList.isEmpty()) {
                        android.app.AlertDialog.Builder(this@DisplayActivity)
                            .setTitle("选择应用")
                            .setMessage("无法从服务端获取应用列表")
                            .setNegativeButton("取消", null)
                            .show()
                        return@withContext
                    }
                    val names = sortedAppList.map { app ->
                        if (app.packageName in recentPkgs) "${app.name} (最近)" else app.name
                    }.toTypedArray()
                    android.app.AlertDialog.Builder(this@DisplayActivity)
                        .setTitle("选择要在该屏幕启动的应用")
                        .setItems(names) { _, which ->
                            val selectedApp = sortedAppList[which]
                            val id = remoteDisplayId ?: return@setItems
                            lifecycleScope.launch(Dispatchers.IO) {
                                val existing = VirtualDisplayTaskManager.findTaskByPackage(this@DisplayActivity, id, selectedApp.packageName)
                                if (existing != null && VirtualDisplayTaskManager.focusTask(existing.taskId)) {
                                    return@launch
                                }
                                val foreign = VirtualDisplayTaskManager.findTaskOnOtherDisplay(
                                    this@DisplayActivity, id, selectedApp.packageName
                                )
                                if (foreign != null && VirtualDisplayTaskManager.moveExistingTaskToDisplay(
                                        this@DisplayActivity, foreign, id
                                    )) {
                                    return@launch
                                }
                                repository.launchApp(selectedApp.packageName, id, freeform = false)
                            }
                        }
                        .setNegativeButton("取消", null)
                        .show()
                }
            }.onFailure { e ->
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    android.app.AlertDialog.Builder(this@DisplayActivity)
                        .setTitle("选择应用")
                        .setMessage("加载应用列表失败: ${e.message}")
                        .setNegativeButton("取消", null)
                        .show()
                }
            }
        }
    }
}
