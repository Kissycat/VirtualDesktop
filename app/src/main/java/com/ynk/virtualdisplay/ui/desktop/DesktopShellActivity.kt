package com.ynk.virtualdisplay.ui.desktop

import android.content.Intent
import android.app.ActivityOptions
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.PixelFormat
import android.os.Bundle
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.Display
import android.view.MotionEvent
import android.view.InputDevice
import android.view.WindowManager
import android.animation.ValueAnimator
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.FrameLayout
import android.widget.EditText
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import android.content.ActivityNotFoundException
import android.util.Log
import android.widget.PopupWindow
import android.widget.BaseAdapter
import android.widget.ListView
import android.widget.GridView
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.content.pm.LauncherApps.ShortcutQuery
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.ynk.virtualdisplay.domain.DisplayInteractor
import com.ynk.virtualdisplay.webrtc.GatewayProcessController
import com.ynk.virtualdisplay.ui.display.DisplayActivity
import com.ynk.virtualdisplay.data.repository.RecentAppHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader
import java.lang.reflect.Method
import java.util.regex.Pattern
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.LinkedHashSet
import android.content.SharedPreferences

/**
 * Lightweight desktop shell rendered INSIDE the 1920x1080 virtual display.
 * It intentionally does not mirror the phone UI. It provides a wallpaper,
 * a small launcher/taskbar and a stable background beneath Lineage/AOSP
 * Full-screen application launcher rendered inside the virtual display.
 */
class DesktopShellActivity : ComponentActivity() {

    companion object {
        const val PREFS_NAME = "desktop_shell_preferences"
        const val PREF_DESKTOP_WIDTH = "desktop_width"
        const val PREF_DESKTOP_HEIGHT = "desktop_height"
        const val PREF_DESKTOP_DPI = "desktop_dpi"

        private val instances = ConcurrentHashMap<Int, WeakReference<DesktopShellActivity>>()

        /**
         * Show the EXISTING desktop Dock on the requested virtual display.
         * The DisplayActivity is on the physical display, so it must never
         * create a PopupWindow there. We route the command to the DesktopShell
         * instance that is already running on the virtual display and move the
         * very same Dock view into a display-local overlay window.
         */
        fun showDockForDisplay(displayId: Int) {
            instances[displayId]?.get()?.let { activity ->
                activity.runOnUiThread {
                    if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                    activity.dockForcedVisible = true
                    activity.showDockOverlay()
                }
            }
        }

        fun hideDockForDisplay(displayId: Int) {
            instances[displayId]?.get()?.let { activity ->
                activity.runOnUiThread {
                    if (!activity.isFinishing && !activity.isDestroyed) {
                        activity.dockForcedVisible = false
                        activity.syncDockVisibility()
                    }
                }
            }
        }

        /**
         * Returns true when a floating Dock was hidden because the user
         * clicked outside it while a fullscreen app was active.
         */
        fun hideFloatingDockOnOutsideClick(
            displayId: Int,
            action: Int,
            x: Float,
            y: Float,
        ): Boolean = instances[displayId]?.get()?.hideFloatingDockOnOutsideClickInternal(
            action, x, y
        ) ?: false

        /** Dispatch injected mouse input to the topmost context menu. */
        fun dispatchPointerToContextMenu(
            displayId: Int,
            action: Int,
            x: Float,
            y: Float,
            actionButton: Int,
            buttons: Int,
        ): Boolean = instances[displayId]?.get()?.dispatchPointerToContextMenuInternal(
            action, x, y, actionButton, buttons
        ) ?: false

        /** Dispatch injected mouse input to the drawer before freeform windows. */
        fun dispatchPointerToDrawer(
            displayId: Int,
            action: Int,
            x: Float,
            y: Float,
            actionButton: Int,
            buttons: Int,
        ): Boolean = instances[displayId]?.get()?.dispatchPointerToDrawerInternal(
            action, x, y, actionButton, buttons
        ) ?: false

        /** Dispatch two-finger scroll into the drawer's active scroller. */
        fun dispatchScrollToDrawer(
            displayId: Int,
            x: Float,
            y: Float,
            hScroll: Float,
            vScroll: Float,
        ): Boolean = instances[displayId]?.get()?.dispatchScrollToDrawerInternal(
            x, y, hScroll, vScroll
        ) ?: false
    }

    internal val displayInteractor: DisplayInteractor by inject()
    private val gatewayProcessController: GatewayProcessController by inject()
    // Retained only for backward-compatible helper code; Freeform decoration is no longer started.
    private val desktopWindows = LinkedHashSet<FreeformOverlayDecoration>()
    internal var desktopDisplayId: Int = Display.DEFAULT_DISPLAY
    private var taskbarAppsContainer: LinearLayout? = null
    private var taskbarMonitorJob: Job? = null
    private var renderedTaskbarSignature: List<String> = emptyList()
    private val appLabelCache = ConcurrentHashMap<String, String>()
    private val appIconStateCache = ConcurrentHashMap<String, Drawable.ConstantState>()
    private var cachedDrawerApps: List<com.ynk.virtualdisplay.protocol.DeviceMessage.AppEntry> = emptyList()
    private var cachedDrawerAppsAt: Long = 0L
    private var cachedRecentPackages: List<String> = emptyList()
    private var cachedRecentPackagesAt: Long = 0L
    private var drawerOverlayWindowManager: WindowManager? = null
    private var drawerOverlayRoot: FrameLayout? = null
    private var drawerOverlayParams: WindowManager.LayoutParams? = null
    private var drawerExpanded = false
    private var drawerLastHoverY = Float.NaN
    private var drawerLastHoverX = Float.NaN
    private var drawerPointerLatched = false
    private var drawerRightButtonLatched = false
    private var drawerLastGlobalX = Float.NaN
    private var drawerLastGlobalY = Float.NaN
    private var drawerRecentScroll: android.widget.HorizontalScrollView? = null
    private var drawerFullScroll: View? = null
    private var contextMenuRequestSerial = 0L
    // The context menu is a separate PopupWindow overlay. Desktop mouse events are
    // injected manually, so the popup must participate in the same explicit
    // topmost hit-testing stack as the drawer and freeform windows.
    private var contextMenuPopup: PopupWindow? = null
    private var contextMenuRoot: View? = null
    private var contextMenuLeft = 0
    private var contextMenuTop = 0
    private var contextMenuWidth = 0
    private var contextMenuHeight = 0
    private var contextMenuPointerLatched = false
    private var contextMenuRightButtonLatched = false
    @Volatile private var desktopWindowFocused = false
    private var portraitWindowSerial = 0
    private var landscapeWindowSerial = 0
    private var desktopRoot: View? = null
    private var desktopWindowLayer: FrameLayout? = null
    private var desktopWindowOverlayHost: DesktopWindowOverlayHost? = null

    private val desktopDisplayListener = object : android.hardware.display.DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit

        override fun onDisplayChanged(displayId: Int) {
            if (displayId != desktopDisplayId) return
            val decor = window.decorView
            decor.post { refreshDesktopDisplayGeometry() }
            // Some platform builds deliver the DisplayManager callback before
            // Display.width/height expose the new geometry. A second pass covers
            // that ordering without polling continuously.
            decor.postDelayed({ refreshDesktopDisplayGeometry() }, 120L)
        }
    }

    private var desktopTaskbar: View? = null
    private var dockClock: TextView? = null
    private var dockClockJob: Job? = null
    private var dockOverlayWindowManager: WindowManager? = null
    private var dockOverlayAttached = false
    private var dockOverlayVisible = false
    private var dockIsDocked = false
    private var dockForcedVisible = false
    private var dockAnimation: ValueAnimator? = null
    private var dockOverlayParams: WindowManager.LayoutParams? = null
    private var maximizedDesktopWindowCount = 0
    private val desktopPrefs: SharedPreferences by lazy {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
    }
    private val wallpaperPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            desktopPrefs.edit().putString("wallpaper_uri", uri.toString()).putString("wallpaper_mode", "image").apply()
            applyWallpaper()
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                Log.d("DesktopShell", "Back ignored on desktop shell; use Exit Desktop from wallpaper menu")
            }
        })
        desktopDisplayId = display?.displayId ?: intent.getIntExtra("display_id", Display.DEFAULT_DISPLAY)
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM
            setPadding(dp(24), dp(18), dp(24), dp(18))
            background = createWallpaperDrawable()
            // Desktop-level settings are now opened from the Dock clock.
            // Keep the wallpaper itself free of the old right-click menu so
            // right-click remains available to the normal desktop/app input path.
        }
        desktopRoot = root

        // All freeform decorations live inside the DesktopShell view hierarchy.
        // This guarantees they are rendered on the same virtual display and
        // avoids cross-window token/display issues on MIUI.
        val spacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        root.addView(spacer)

        val taskbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = rounded(0xE01B1F26.toInt(), 18f)
            elevation = dp(8).toFloat()
        }

        lateinit var launcher: TextView
        launcher = button("☰  应用") { showAppLauncher() }
        taskbar.addView(launcher, LinearLayout.LayoutParams(dp(150), dp(52)).apply {
            marginEnd = dp(10)
        })

        val taskScroll = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            background = null
        }
        taskbarAppsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(4), 0)
        }
        taskScroll.addView(taskbarAppsContainer, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, dp(52)
        ))
        taskbar.addView(taskScroll, LinearLayout.LayoutParams(0, dp(52), 1f).apply {
            marginEnd = dp(8)
        })

        val clock = TextView(this).apply {
            text = currentDockClockText()
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.CENTER
            alpha = 0.9f
            isClickable = true
            setOnClickListener { showDesktopSettingsMenu(this) }
        }
        taskbar.addView(clock, LinearLayout.LayoutParams(dp(90), dp(52)))

        dockClock = clock
        startDockClockUpdates()
        desktopTaskbar = taskbar
        root.addView(taskbar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(68)
        ))

        instances[desktopDisplayId] = WeakReference(this)

        // Shell content remains the wallpaper/taskbar layer. Freeform windows
        // are no longer children of this hierarchy; they live in the separate
        // DesktopWindowOverlayHost window above fullscreen applications.
        val shell = FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
        }
        shell.addView(root, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        setContentView(shell)

        // Freeform windows live in their own display-local application overlay,
        // independent of DesktopShellActivity's View hierarchy. The overlay is
        // attached before the Dock so Dock/drawer/context-menu overlays remain
        // above it. Input routing stays in DesktopWindowInputRouter, while this
        // window provides the actual visual layer above fullscreen apps.
        desktopWindowOverlayHost = DesktopWindowOverlayHost(this).also {
            if (!it.attach()) {
                desktopWindowOverlayHost = null
                Log.w("DesktopShell", "Desktop freeform overlay could not be attached")
            }
        }

        ensureDockOverlayAttached()
        syncDockVisibility()

        getSystemService(android.hardware.display.DisplayManager::class.java)
            ?.registerDisplayListener(desktopDisplayListener, null)
        refreshDesktopDisplayGeometry()
    }

    /**
     * Synchronize every display-local surface with the current VirtualDisplay
     * geometry after a resolution/DPI change.
     */
    private fun refreshDesktopDisplayGeometry() {
        if (isFinishing || isDestroyed) return
        val targetDisplay = display ?: return
        val width = targetDisplay.width
        val height = targetDisplay.height
        if (width <= 0 || height <= 0) return

        desktopWindowOverlayHost?.updateDisplayGeometry(width, height)
        desktopWindows.toList().forEach { window ->
            runCatching { window.onDesktopDisplaySizeChanged(width, height) }
                .onFailure { Log.w("DesktopShell", "Failed to update desktop window geometry", it) }
        }

        // The Dock is another display-sized overlay window and must follow the
        // same resolution change.
        dockAnimation?.cancel()
        dockAnimation = null
        val dock = desktopTaskbar
        val wm = dockOverlayWindowManager
        val params = dockOverlayParams
        if (dock != null && wm != null && params != null && dockOverlayAttached) {
            val target = if (dockIsDocked) dockedDockParams() else floatingDockParams()
            params.width = target.width
            params.x = target.x
            params.y = target.y
            runCatching { wm.updateViewLayout(dock, params) }
                .onFailure { Log.w("DesktopShell", "Failed to update Dock geometry", it) }
        }

        desktopRoot?.requestLayout()
        desktopRoot?.invalidate()
    }

    internal fun addDesktopWindowView(view: View, width: Int, height: Int, x: Int, y: Int) {
        desktopWindowOverlayHost?.addView(view, width, height, x, y)
            ?: throw IllegalStateException("Desktop window overlay host is not attached")
    }

    internal fun updateDesktopWindowView(view: View, width: Int, height: Int, x: Int, y: Int) {
        desktopWindowOverlayHost?.updateView(view, width, height, x, y)
    }

    internal fun setDesktopWindowOverlayInteractive(interactive: Boolean) {
        // Dock state is the authoritative desktop/fullscreen boundary:
        // a visible Dock means DesktopShell owns the interactive surface;
        // a hidden floating Dock means a foreign fullscreen task owns it.
        // The router's explicit `interactive=true` still permits a small
        // window to become touchable while the Dock is hidden.
        val effective = dockOverlayVisible || interactive
        desktopWindowOverlayHost?.setInputInteractive(effective)
    }

    /** Force the overlay into pass-through mode after restoring a fullscreen task. */
    private fun forceDesktopOverlayPassThrough() {
        desktopWindowOverlayHost?.forcePassThrough()
    }

    internal fun commitDesktopWindowOverlayGeometry(view: View? = null) {
        desktopWindowOverlayHost?.commitGeometry(view)
    }

    internal fun bringDesktopWindowToFront(view: View) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            desktopWindowOverlayHost?.bringToFront(view)
        } else {
            view.post {
                if (!isFinishing && !isDestroyed) {
                    desktopWindowOverlayHost?.bringToFront(view)
                }
            }
        }
    }

    internal fun removeDesktopWindowView(view: View) {
        desktopWindowOverlayHost?.removeView(view)
    }


    internal fun desktopDpiPublic(): Int = desktopPrefs.getInt(PREF_DESKTOP_DPI, 160)
    internal fun desktopDockHeightPublic(): Int = dp(68)

    private fun currentDockClockText(): String =
        java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())

    private fun startDockClockUpdates() {
        dockClockJob?.cancel()
        dockClockJob = lifecycleScope.launch {
            // Refresh on the next minute boundary, then exactly once per minute.
            val now = System.currentTimeMillis()
            val delayToNextMinute = 60_000L - (now % 60_000L)
            delay(delayToNextMinute.coerceIn(250L, 60_000L))
            while (isActive && !isFinishing && !isDestroyed) {
                dockClock?.text = currentDockClockText()
                delay(60_000L)
            }
        }
    }

    override fun onDestroy() {
        Log.w("DesktopShell", "onDestroy: shell is actually being destroyed; releasing child windows")
        // Release desktop-window resources only when the shell Activity is truly destroyed.
        closeAppDrawer()
        taskbarMonitorJob?.cancel()
        taskbarMonitorJob = null
        dockClockJob?.cancel()
        dockClockJob = null
        dockClock = null
        dockAnimation?.cancel()
        dockAnimation = null
        desktopTaskbar?.let { dock ->
            runCatching { dockOverlayWindowManager?.removeViewImmediate(dock) }
        }
        dockOverlayWindowManager = null
        dockOverlayAttached = false
        dockOverlayVisible = false
        desktopWindows.toList().forEach { runCatching { it.remove() } }
        desktopWindows.clear()
        desktopWindowOverlayHost?.detach()
        desktopWindowOverlayHost = null
        getSystemService(android.hardware.display.DisplayManager::class.java)
            ?.unregisterDisplayListener(desktopDisplayListener)
        instances.remove(desktopDisplayId)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        // When DesktopShell becomes foreground again, there is no foreign
        // fullscreen app in front of it. Reset the freeform host to its
        // pass-through state immediately; pointer routing will re-enable it
        // only while the cursor is actually over a desktop window.
        desktopWindowOverlayHost?.setInputInteractive(true)

        // A floating dock is only allowed while another full-screen app owns
        // the Desktop display. When we return to the bare desktop, force it
        // away even if the previous app left the overlay attached.
        syncDockVisibility()
        // Task discovery is allowed only while the DesktopShell itself is the
        // focused/visible window. This keeps dumpsys/shizuku work completely
        // out of the active full-screen application/video path.
        if (window.decorView.hasWindowFocus()) {
            desktopWindowFocused = true
            lifecycleScope.launch {
                refreshTaskbar()
                startTaskbarMonitor()
            }
        }
    }

    override fun onPause() {
        desktopWindowFocused = false
        if (!dockOverlayVisible) {
            taskbarMonitorJob?.cancel()
            taskbarMonitorJob = null
        }
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        desktopWindowFocused = hasFocus
        if (hasFocus && !isFinishing && !isDestroyed) {
            // DesktopShell foreground: keep the freeform overlay touchable.
            // DesktopWindowInputRouter will temporarily add FLAG_NOT_TOUCHABLE
            // when the pointer is outside a small window.
            desktopWindowOverlayHost?.setInputInteractive(true)
            syncDockVisibility()
            taskbarMonitorJob?.cancel()
            taskbarMonitorJob = lifecycleScope.launch {
                // Query immediately when we really return to the desktop.
                refreshTaskbar()
                startTaskbarMonitor()
            }
        } else {
            taskbarMonitorJob?.cancel()
            taskbarMonitorJob = null
        }
    }


    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Returning HOME must not destroy the DesktopShell task: existing
        // floating windows belong to this shell lifecycle and must survive.
        // Do not implicitly hide a user-visible floating Dock here; the only
        // automatic hide path is an actual click outside it while a full-screen
        // app owns the desktop display.
        syncDockVisibility()
        window.decorView.post {
            if (window.decorView.width > 0 && window.decorView.height > 0) {
                window.decorView.requestLayout()
                window.decorView.invalidate()
            }
        }
    }

    /**
     * Routes the injected desktop pointer stream into the currently visible
     * context menu. The PopupWindow is a separate TYPE_APPLICATION_OVERLAY
     * window, therefore Android's native touch dispatch does not see the
     * desktop cursor injection. Keep the popup latched for the duration of a
     * gesture so clicks cannot fall through to the drawer/window underneath.
     */
    private fun dispatchPointerToContextMenuInternal(
        action: Int,
        x: Float,
        y: Float,
        actionButton: Int,
        buttons: Int,
    ): Boolean {
        val root = contextMenuRoot ?: return false
        val popup = contextMenuPopup ?: return false
        if (!popup.isShowing || contextMenuWidth <= 0 || contextMenuHeight <= 0) {
            return false
        }

        val localX = x - contextMenuLeft
        val localY = y - contextMenuTop
        val inside = localX >= 0f && localY >= 0f &&
            localX < contextMenuWidth.toFloat() &&
            localY < contextMenuHeight.toFloat()
        val secondary = actionButton == MotionEvent.BUTTON_SECONDARY ||
            (buttons and MotionEvent.BUTTON_SECONDARY) != 0

        if (secondary || contextMenuRightButtonLatched) {
            when (action) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_BUTTON_PRESS -> {
                    if (!inside) return false
                    contextMenuRightButtonLatched = true
                    contextMenuPointerLatched = true
                    findInteractiveChildUnder(root, localX, localY)?.performContextClick()
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_BUTTON_RELEASE -> {
                    val handled = contextMenuRightButtonLatched
                    contextMenuRightButtonLatched = false
                    contextMenuPointerLatched = false
                    return handled
                }
                else -> if (contextMenuRightButtonLatched) return true
            }
        }

        if (action == MotionEvent.ACTION_DOWN) {
            if (!inside) return false
            contextMenuPointerLatched = true
        }

        if (!contextMenuPointerLatched && action != MotionEvent.ACTION_HOVER_MOVE &&
            action != MotionEvent.ACTION_HOVER_ENTER && action != MotionEvent.ACTION_HOVER_EXIT) {
            return false
        }

        if (action == MotionEvent.ACTION_OUTSIDE) return true

        val now = android.os.SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(
            now, now, action, localX, localY, 0
        ).apply {
            source = InputDevice.SOURCE_MOUSE
        }
        return try {
            val handled = when (action) {
                MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE, MotionEvent.ACTION_HOVER_EXIT ->
                    root.dispatchGenericMotionEvent(event)
                else -> root.dispatchTouchEvent(event)
            }
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                contextMenuPointerLatched = false
            }
            handled || contextMenuPointerLatched
        } finally {
            event.recycle()
        }
    }

    private fun dispatchPointerToDrawerInternal(
        action: Int,
        x: Float,
        y: Float,
        actionButton: Int,
        buttons: Int,
    ): Boolean {
        val root = drawerOverlayRoot ?: return false
        val params = drawerOverlayParams ?: return false
        val targetDisplay = display ?: return false
        val top = targetDisplay.height - params.height - params.y
        val localX = x - params.x
        val localY = y - top
        val inside = localX >= 0f && localY >= 0f &&
            localX < params.width.toFloat() && localY < params.height.toFloat()
        val secondary = actionButton == MotionEvent.BUTTON_SECONDARY ||
            (buttons and MotionEvent.BUTTON_SECONDARY) != 0

        if (secondary || drawerRightButtonLatched) {
            when (action) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_BUTTON_PRESS -> {
                    if (!inside) return false
                    val target = findInteractiveChildUnder(root, localX, localY)
                    drawerRightButtonLatched = true
                    drawerPointerLatched = true
                    target?.performContextClick()
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_BUTTON_RELEASE -> {
                    val handled = drawerRightButtonLatched
                    drawerRightButtonLatched = false
                    drawerPointerLatched = false
                    return handled
                }
                else -> if (drawerRightButtonLatched) return true
            }
        }

        if (action == MotionEvent.ACTION_DOWN && !inside) {
            drawerPointerLatched = false
            return false
        }

        val contextMenuOpen = contextMenuPopup?.isShowing == true
        if (!drawerExpanded && !contextMenuOpen &&
            (action == MotionEvent.ACTION_HOVER_MOVE || action == MotionEvent.ACTION_HOVER_ENTER)) {
            val previousY = drawerLastGlobalY
            val crossedTopEdge = !previousY.isNaN() &&
                previousY >= top.toFloat() &&
                y < top.toFloat() &&
                x >= params.x.toFloat() && x < (params.x + params.width).toFloat()
            drawerLastGlobalX = x
            drawerLastGlobalY = y
            if (crossedTopEdge) {
                expandDrawerFromPointer(root, params)
                return true
            }
        } else if (action == MotionEvent.ACTION_HOVER_EXIT) {
            drawerLastGlobalX = Float.NaN
            drawerLastGlobalY = Float.NaN
        }

        if (action == MotionEvent.ACTION_DOWN) drawerPointerLatched = true
        if (!drawerPointerLatched && action != MotionEvent.ACTION_HOVER_ENTER &&
            action != MotionEvent.ACTION_HOVER_MOVE && action != MotionEvent.ACTION_HOVER_EXIT) {
            return false
        }

        val now = android.os.SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(now, now, action, localX, localY, 0).apply {
            source = InputDevice.SOURCE_MOUSE
        }
        return try {
            val handled = when (action) {
                MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE, MotionEvent.ACTION_HOVER_EXIT ->
                    root.dispatchGenericMotionEvent(event)
                else -> root.dispatchTouchEvent(event)
            }
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                drawerPointerLatched = false
            }
            handled || drawerPointerLatched
        } finally {
            event.recycle()
        }
    }

    private fun dispatchScrollToDrawerInternal(
        x: Float,
        y: Float,
        hScroll: Float,
        vScroll: Float,
    ): Boolean {
        val root = drawerOverlayRoot ?: return false
        val params = drawerOverlayParams ?: return false
        val targetDisplay = display ?: return false
        val top = targetDisplay.height - params.height - params.y
        val localX = x - params.x
        val localY = y - top
        val inside = localX >= 0f && localY >= 0f &&
            localX < params.width.toFloat() && localY < params.height.toFloat()
        if (!inside) return false

        // The InputController supplies AXIS_* values derived from the finger
        // delta (delta / 288). Do not reinterpret that value with a second
        // animation step: apply one immediate pixel delta per sample. Touch
        // scrolling convention is opposite the finger movement (drag right ->
        // content moves left), so the sign is intentionally inverted here.
        // The current drawer path uses immediate pixel deltas. Increase the
        // drawer-specific gain 3x (2x -> 6x) while preserving the user's
        // global scroll-sensitivity multiplier in InputController.
        val drawerGain = 6f
        val deltaX = (-hScroll * dp(288) * drawerGain).roundToInt()
        val deltaY = (-vScroll * dp(288) * drawerGain).roundToInt()
        return when {
            drawerExpanded && drawerFullScroll != null -> {
                when (val scrollable = drawerFullScroll) {
                    is android.widget.ScrollView -> if (deltaY != 0) scrollable.scrollBy(0, deltaY)
                    is android.widget.GridView -> if (deltaY != 0) scrollable.scrollListBy(deltaY)
                    is android.widget.ListView -> if (deltaY != 0) scrollable.scrollListBy(deltaY)
                    else -> return false
                }
                true
            }
            !drawerExpanded && drawerRecentScroll != null && deltaX != 0 -> {
                // Touch-style scrolling: finger right -> content left.
                drawerRecentScroll?.scrollBy(deltaX, 0)
                true
            }
            else -> false
        }
    }

    private fun expandDrawerFromPointer(
        root: FrameLayout,
        params: WindowManager.LayoutParams,
    ) {
        if (drawerExpanded || drawerOverlayRoot !== root) return
        val targetDisplay = display ?: return
        drawerExpanded = true
        val fullHeight = minOf(dp(850), targetDisplay.height - dp(120)).coerceAtLeast(dp(84))
        params.height = fullHeight
        drawerOverlayParams = params
        runCatching { drawerOverlayWindowManager?.updateViewLayout(root, params) }
        populateFullDrawer(root, params.width, fullHeight)
    }

    private fun findInteractiveChildUnder(view: View, x: Float, y: Float): View? {
        if (view !is ViewGroup) {
            return view.takeIf { x >= 0f && y >= 0f && x < it.width && y < it.height }
        }
        for (i in view.childCount - 1 downTo 0) {
            val child = view.getChildAt(i)
            if (child.visibility != View.VISIBLE) continue
            val left = child.left.toFloat()
            val top = child.top.toFloat()
            val right = child.right.toFloat()
            val bottom = child.bottom.toFloat()
            if (x < left || x >= right || y < top || y >= bottom) continue
            val nested = findInteractiveChildUnder(child, x - left, y - top)
            if (nested != null && (nested.isClickable || nested.isLongClickable)) return nested
            if (child.isClickable || child.isLongClickable) return child
        }
        return view.takeIf { x >= 0f && y >= 0f && x < it.width && y < it.height }
    }

    /**
     * Desktop dock app drawer. Keep this in sync with the normal-mode app drawer:
     * use the daemon/RPC app list (not the phone's PackageManager), preserve
     * recent-app ordering, and do not arbitrarily truncate the application list.
     */
    private fun showAppLauncher() {
        if (isFinishing || isDestroyed || drawerOverlayRoot != null) return
        if (!ensureOverlayPermissionSilently()) return
        val targetDisplay = display ?: return
        val displayContext = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            createDisplayContext(targetDisplay).createWindowContext(
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null
            )
        } else {
            createDisplayContext(targetDisplay)
        }
        val wm = displayContext.getSystemService(WindowManager::class.java) ?: return

        val fullWidth = minOf(dp(580), targetDisplay.width - dp(32)).coerceAtLeast(dp(320))
        val rowHeight = dp(84)
        val fullHeight = minOf(dp(850), targetDisplay.height - dp(120)).coerceAtLeast(rowHeight)

        val container = FrameLayout(displayContext).apply {
            background = roundedPublic(0xF0161A20.toInt(), 20f)
            elevation = dp(48).toFloat()
            clipChildren = true
            clipToPadding = true
            isClickable = true
            isFocusable = false
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        val recentRow = LinearLayout(displayContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), 0, dp(2), 0)
        }
        val scroll = android.widget.HorizontalScrollView(displayContext).apply {
            isHorizontalScrollBarEnabled = false
            isSmoothScrollingEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            addView(recentRow, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, rowHeight - dp(12)))
        }
        drawerRecentScroll = scroll
        container.addView(scroll, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, rowHeight - dp(12), Gravity.TOP
        ))

        fun addRecentItem(pkg: String, index: Int) {
            val item = createAppItem(
                packageName = pkg,
                label = appLabel(pkg),
                compact = true,
                onClick = {
                    closeAppDrawer()
                    launchRemoteAppMaximized(pkg)
                }
            )
            recentRow.addView(item, LinearLayout.LayoutParams(
                if (showAppIcons()) dp(62) else dp(160), rowHeight - dp(18)
            ).apply { marginEnd = dp(6) })
        }

        val recent = cachedRecentPackages.distinct().filter { it != packageName }
        if (recent.isEmpty()) {
            recentRow.addView(TextView(displayContext).apply {
                text = "最近应用"
                setTextColor(0xFFCBD2DC.toInt())
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), 0, dp(12), 0)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        } else {
            recent.take(12).forEachIndexed { index, pkg -> addRecentItem(pkg, index) }
        }

        val params = WindowManager.LayoutParams(
            fullWidth,
            rowHeight,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            x = dp(26)
            y = dp(94)
            title = "VirtualDisplayDesktopAppDrawer-$desktopDisplayId"
        }

        drawerExpanded = false
        drawerLastHoverX = Float.NaN
        drawerLastHoverY = Float.NaN
        drawerOverlayRoot = container
        drawerOverlayWindowManager = wm
        drawerOverlayParams = params

        container.setOnHoverListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_HOVER_MOVE, MotionEvent.ACTION_HOVER_ENTER -> {
                    drawerLastHoverX = event.x
                    drawerLastHoverY = event.y
                }
                MotionEvent.ACTION_HOVER_EXIT -> {
                    drawerLastHoverX = Float.NaN
                    drawerLastHoverY = Float.NaN
                }
                MotionEvent.ACTION_OUTSIDE -> {
                    Log.d("DesktopDrawer", "HOVER_OUTSIDE ignored in collapsed drawer")
                }
            }
            false
        }
        container.setOnGenericMotionListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_HOVER_MOVE, MotionEvent.ACTION_HOVER_ENTER -> {
                    drawerLastHoverX = event.x
                    drawerLastHoverY = event.y
                    false
                }
                MotionEvent.ACTION_HOVER_EXIT -> {
                    drawerLastHoverX = Float.NaN
                    drawerLastHoverY = Float.NaN
                    true
                }
                else -> false
            }
        }
        container.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                Log.d("DesktopDrawer", "TOUCH_OUTSIDE expanded=$drawerExpanded")
                closeAppDrawer()
                true
            } else false
        }

        runCatching { wm.addView(container, params) }
            .onSuccess {
                // Newly added drawer must be the highest interactive overlay.
                // Keep the full drawer above both the dock and freeform windows.
                raiseDesktopInteractiveOverlays()
            }
            .onFailure {
                drawerOverlayRoot = null
                drawerOverlayWindowManager = null
                drawerOverlayParams = null
                Log.w("DesktopShell", "Failed to show app drawer overlay", it)
            }

        // Recent row is cache-only. Start loading the full app list in the background.
        lifecycleScope.launch(Dispatchers.IO) {
            val cacheFresh = cachedDrawerApps.isNotEmpty() && System.currentTimeMillis() - cachedDrawerAppsAt < 30_000L
            if (!cacheFresh) {
                val result = displayInteractor.listApps()
                result.onSuccess {
                    cachedDrawerApps = it
                    cachedDrawerAppsAt = System.currentTimeMillis()
                }
            }
        }
    }

    private fun populateFullDrawer(container: FrameLayout, drawerWidth: Int, drawerHeight: Int) {
        val existing = container.getChildAt(1)
        if (existing != null) {
            container.removeViewAt(1)
        }
        val apps = cachedDrawerApps.filter { it.packageName != packageName }
        if (apps.isEmpty()) {
            container.addView(TextView(container.context).apply {
                text = "正在加载应用…"
                setTextColor(0xFFCBD2DC.toInt())
                gravity = Gravity.CENTER
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, drawerHeight - dp(92), Gravity.TOP).apply {
                topMargin = dp(86)
            })
            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) { displayInteractor.listApps() }
                result.onSuccess {
                    cachedDrawerApps = it
                    cachedDrawerAppsAt = System.currentTimeMillis()
                    if (drawerOverlayRoot === container && drawerExpanded) {
                        populateFullDrawer(container, drawerWidth, drawerHeight)
                    }
                }
            }
            return
        }
        val recentSet = cachedRecentPackages.toSet()
        val ordered = apps.sortedWith(compareByDescending<com.ynk.virtualdisplay.protocol.DeviceMessage.AppEntry> { it.packageName in recentSet }.thenBy { it.name.lowercase() })
        // Cache the actual item views by package. The old adapter ignored
        // convertView and recreated the complete icon/text hierarchy (including
        // drawable setup and gesture listeners) every time GridView/ListView
        // requested a row, which caused visible GC/layout spikes while scrolling.
        val viewCache = HashMap<String, View>(ordered.size)
        val adapter = object : BaseAdapter() {
            override fun getCount() = ordered.size
            override fun getItem(position: Int) = ordered[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val app = ordered[position]
                val recent = app.packageName in recentSet
                val label = if (recent) "★  ${app.name}" else app.name
                val cached = viewCache[app.packageName]
                if (cached != null && cached.parent == null) {
                    return cached
                }
                val created = createAppItem(app.packageName, label, compact = false) {
                    closeAppDrawer()
                    launchRemoteAppMaximized(app.packageName)
                }
                viewCache[app.packageName] = created
                return created
            }
        }
        val list: View = if (showAppIcons()) {
            GridView(this).apply {
                numColumns = 5
                horizontalSpacing = dp(4)
                verticalSpacing = dp(4)
                columnWidth = dp(104)
                stretchMode = GridView.NO_STRETCH
                this.adapter = adapter
                setPadding(dp(6), dp(6), dp(6), dp(6))
                clipToPadding = false
            }
        } else {
            ListView(this).apply {
                divider = null
                dividerHeight = dp(4)
                this.adapter = adapter
                setPadding(dp(6), dp(6), dp(6), dp(6))
                clipToPadding = false
            }
        }
        drawerFullScroll = list
        container.addView(list, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, drawerHeight - dp(92), Gravity.TOP
        ).apply { topMargin = dp(86) })
    }

    private fun closeAppDrawer() {
        val root = drawerOverlayRoot ?: return
        runCatching { drawerOverlayWindowManager?.removeViewImmediate(root) }
        drawerOverlayWindowManager = null
        drawerOverlayRoot = null
        drawerOverlayParams = null
        drawerRecentScroll = null
        drawerFullScroll = null
        drawerExpanded = false
        drawerPointerLatched = false
        drawerRightButtonLatched = false
        drawerLastGlobalX = Float.NaN
        drawerLastGlobalY = Float.NaN
    }

    private fun launchRemoteApp(packageName: String) {
        val displayId = desktopDisplayId
        lifecycleScope.launch(Dispatchers.IO) {
            val (existing, foreign) = VirtualDisplayTaskManager.findTaskByPackageAcrossDisplays(
                this@DesktopShellActivity, displayId, packageName
            )
            if (existing != null && VirtualDisplayTaskManager.focusTask(existing.taskId)) {
                return@launch
            }

            if (foreign != null && VirtualDisplayTaskManager.moveExistingTaskToDisplay(
                    this@DesktopShellActivity, foreign, displayId
                )) {
                return@launch
            }

            val result = displayInteractor.launchApp(packageName, displayId, freeform = false)
            delay(300)
            withContext(Dispatchers.Main) {
                result.onSuccess {
                    lifecycleScope.launch(Dispatchers.IO) {
                        RecentAppHelper.addRecentApp(this@DesktopShellActivity, packageName)
                    }
                }.onFailure {
                    Toast.makeText(
                        this@DesktopShellActivity,
                        "启动失败：${it.message ?: "daemon 拒绝启动"}",
                        Toast.LENGTH_LONG
                    ).show()
                }
                refreshTaskbar()
            }
        }
    }

    internal fun migrateDesktopWindowToMainDisplay(packageName: String) {
        if (isFinishing || isDestroyed) return
        launchRemoteApp(packageName)
    }

    private fun startTaskbarMonitor() {
        taskbarMonitorJob?.cancel()
        taskbarMonitorJob = lifecycleScope.launch {
            delay(2000)
            while (isActive && (desktopWindowFocused || dockOverlayVisible) && !isFinishing && !isDestroyed) {
                if (dockOverlayVisible || (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) &&
                    window.decorView.hasWindowFocus())) {
                    refreshTaskbar(allowWhenUnfocused = dockOverlayVisible)
                }
                delay(2000)
            }
        }
    }

    internal suspend fun refreshTaskbar(allowWhenUnfocused: Boolean = false) {
        if (!allowWhenUnfocused && (!desktopWindowFocused && !dockOverlayVisible)) return
        val now = System.currentTimeMillis()
        val taskSnapshot = withContext(Dispatchers.IO) {
            VirtualDisplayTaskManager.findTasks(this@DesktopShellActivity, desktopDisplayId)
                .filter { it.packageName != packageName }
        }
        if (now - cachedRecentPackagesAt > 5_000L) {
            val recent = withContext(Dispatchers.IO) {
                runCatching { RecentAppHelper.getRecentApps(this@DesktopShellActivity) }.getOrDefault(emptyList())
            }
            cachedRecentPackages = recent
            cachedRecentPackagesAt = now
        }

        // Avoid rebuilding the entire taskbar every polling tick. The monitor
        // runs periodically because Android task state is queried through
        // dumpsys; most polls produce an identical task list.
        val visibleWindowSignature = desktopWindows
            .asSequence()
            .filter { it.isVisible() }
            .map { "w:${it.packageName}:${it.isMinimizedForLayout()}" }
            .toList()
        val signature = ArrayList<String>(taskSnapshot.size + visibleWindowSignature.size + 1)
        signature.add(if (showAppIcons()) "icons" else "labels")
        taskSnapshot.forEach { signature.add("t:${it.taskId}:${it.packageName}") }
        signature.addAll(visibleWindowSignature)
        if (signature == renderedTaskbarSignature) return

        withContext(Dispatchers.Main) {
            val container = taskbarAppsContainer ?: return@withContext
            // State can change while the IO snapshot is being processed;
            // signature is deliberately assigned only after the UI is rebuilt.
            container.removeAllViews()
            val shown = LinkedHashSet<String>()
            taskSnapshot.forEach { task ->
                if (!shown.add(task.packageName)) return@forEach
                val window = desktopWindows.firstOrNull { it.packageName == task.packageName }
                val item = createAppItem(
                    packageName = task.packageName,
                    label = appLabel(task.packageName),
                    compact = true
                ) {
                    if (window != null) {
                        window.toggleMinimizedFromDock()
                    } else {
                        // Restoring a normal fullscreen task has to cross the same
                        // boundary as any other fullscreen transition. Let Dock state
                        // drive the overlay flag: once the floating Dock hides, the
                        // overlay must be FLAG_NOT_TOUCHABLE even when there are no
                        // DesktopWindowInputRouter entries.
                        val focused = VirtualDisplayTaskManager.focusTask(task.taskId)
                        if (focused) {
                            this@DesktopShellActivity.window.decorView.post {
                                syncDockVisibility()
                                if (!dockOverlayVisible && !isFinishing && !isDestroyed) {
                                    forceDesktopOverlayPassThrough()
                                }
                            }
                            this@DesktopShellActivity.window.decorView.postDelayed({
                                if (!isFinishing && !isDestroyed) {
                                    syncDockVisibility()
                                    if (!dockOverlayVisible) {
                                        forceDesktopOverlayPassThrough()
                                    }
                                }
                            }, 180L)
                        }
                    }
                    lifecycleScope.launch {
                        renderedTaskbarSignature = emptyList()
                        refreshTaskbar(allowWhenUnfocused = true)
                    }
                }
                container.addView(item, LinearLayout.LayoutParams(
                    if (showAppIcons()) dp(58) else dp(150), dp(48)
                ).apply { marginEnd = dp(6) })
            }
            desktopWindows.forEach { win ->
                if (!win.isVisible() || !shown.add(win.packageName)) return@forEach
                val item = createAppItem(packageName = win.packageName, label = win.displayLabel, compact = true) {
                    win.toggleMinimizedFromDock()
                    lifecycleScope.launch {
                        renderedTaskbarSignature = emptyList()
                        refreshTaskbar(allowWhenUnfocused = true)
                    }
                }
                container.addView(item, LinearLayout.LayoutParams(
                    if (showAppIcons()) dp(58) else dp(150), dp(48)
                ).apply { marginEnd = dp(6) })
            }
            renderedTaskbarSignature = signature
        }
    }

    /**
     * Desktop settings opened from the Dock clock.
     *
     * This intentionally uses the same overlay/popup visual language as the
     * app drawer and app shortcut menus instead of an AlertDialog. The old
     * desktop right-click entry point is removed so the wallpaper area keeps
     * its normal pointer behavior.
     */
    private fun showDesktopSettingsMenu(anchor: View) {
        ++contextMenuRequestSerial
        contextMenuPopup?.dismiss()
        lifecycleScope.launch {
            lateinit var popup: PopupWindow
            val popupContent = LinearLayout(this@DesktopShellActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), dp(10), dp(10), dp(10))
                background = rounded(0xF0161A20.toInt(), 18f)
                minimumWidth = dp(360)
            }

            val header = LinearLayout(this@DesktopShellActivity).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(4), dp(8), dp(8))
            }
            val icon = ImageView(this@DesktopShellActivity).apply {
                setImageResource(android.R.drawable.ic_menu_manage)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
            }
            header.addView(icon, LinearLayout.LayoutParams(dp(38), dp(38)))
            val title = TextView(this@DesktopShellActivity).apply {
                text = "桌面设置"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), 0, 0, 0)
            }
            header.addView(title, LinearLayout.LayoutParams(0, dp(38), 1f))
            popupContent.addView(header)

            addContextAction(popupContent, "DLNA 投屏", android.R.drawable.ic_menu_share, action = {
                popup.dismiss()
                toggleDesktopDlna()
            })
            addContextAction(popupContent, "修改壁纸", android.R.drawable.ic_menu_gallery, action = {
                popup.dismiss()
                showWallpaperMenu()
            })
            addContextAction(
                popupContent,
                if (showAppIcons()) "应用显示：图标" else "应用显示：名称",
                android.R.drawable.ic_menu_view,
                action = {
                popup.dismiss()
                toggleAppPresentation()
                }
            )
            addContextAction(popupContent, "分辨率 / DPI", android.R.drawable.ic_menu_crop, action = {
                popup.dismiss()
                showDisplaySettingsDialog()
            })
            addContextAction(popupContent, "光标灵敏度", android.R.drawable.ic_menu_mylocation, action = {
                popup.dismiss()
                showCursorSensitivityMenu()
            })
            addContextAction(popupContent, "刷新应用与任务栏", android.R.drawable.ic_popup_sync, action = {
                popup.dismiss()
                lifecycleScope.launch {
                    renderedTaskbarSignature = emptyList()
                    refreshTaskbar(allowWhenUnfocused = true)
                }
            })
            addContextSeparator(popupContent)
            addContextAction(popupContent, "退出桌面", android.R.drawable.ic_menu_close_clear_cancel, action = {
                popup.dismiss()
                Log.d("DesktopShell", "Exit desktop selected from Dock settings")
                exitDesktop()
            })

            popup = PopupWindow(
                popupContent,
                minOf(dp(400), targetDisplayWidthForPopup() - dp(24)),
                ViewGroup.LayoutParams.WRAP_CONTENT,
                true
            ).apply {
                elevation = dp(32).toFloat()
                isOutsideTouchable = true
                inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    setWindowLayoutType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
                }
                setClippingEnabled(false)
                popupContent.elevation = dp(32).toFloat()
            }

            val maxWidth = minOf(dp(400), targetDisplayWidthForPopup() - dp(24))
            popupContent.measure(
                View.MeasureSpec.makeMeasureSpec(maxWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(dp(700), View.MeasureSpec.AT_MOST)
            )
            val loc = IntArray(2)
            anchor.getLocationOnScreen(loc)
            val popupWidth = popupContent.measuredWidth.coerceAtLeast(1)
            val popupHeight = popupContent.measuredHeight.coerceAtLeast(1)
            val screenWidth = desktopWindowLayer?.width?.takeIf { it > 0 } ?: targetDisplayWidthForPopup()
            val screenHeight = desktopWindowLayer?.height?.takeIf { it > 0 } ?: (display?.height ?: resources.displayMetrics.heightPixels)
            val margin = dp(12)
            val left = (loc[0] + anchor.width - popupWidth).coerceIn(
                margin, (screenWidth - popupWidth - margin).coerceAtLeast(margin)
            )
            // Prefer the menu directly above the clock so it visually belongs
            // to the Dock rather than floating in the desktop center.
            val top = (loc[1] - popupHeight - dp(8)).coerceAtLeast(margin)
            Log.d("DesktopSettingsMenu", "SHOW x=$left top=$top size=${popupWidth}x$popupHeight displayId=$desktopDisplayId")

            popup.setOnDismissListener {
                if (contextMenuPopup === popup) {
                    contextMenuPopup = null
                    contextMenuRoot = null
                    contextMenuPointerLatched = false
                    contextMenuRightButtonLatched = false
                    contextMenuLeft = 0
                    contextMenuTop = 0
                    contextMenuWidth = 0
                    contextMenuHeight = 0
                }
            }

            runCatching {
                popup.showAtLocation(anchor, Gravity.TOP or Gravity.START, left, top)
                contextMenuPopup = popup
                contextMenuRoot = popupContent
                contextMenuLeft = left
                contextMenuTop = top
                contextMenuWidth = popupWidth
                contextMenuHeight = popupHeight
                contextMenuPointerLatched = false
                contextMenuRightButtonLatched = false
            }.onFailure {
                Log.e("DesktopSettingsMenu", "SHOW_FAILED", it)
            }
        }
    }

    /**
     * Start/reuse the same bundled GO WebRTC gateway used by the floating WebRTC
     * control, then open its DLNA control page. The first menu invocation keeps
     * local playback intact; once the gateway is already running, a subsequent
     * invocation suppresses the local preview just like the floating control.
     */
    private fun toggleDesktopDlna() {
        val displayId = desktopDisplayId
        if (displayId < 0) {
            Toast.makeText(this, "无有效虚拟屏幕", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val running = gatewayProcessController.isRunning()
            if (running) {
                // Second DLNA click keeps the existing gateway alive but switches
                // this entry point to local-preview suppression. Clear the first-
                // launch exception before applying the suppression.
                DisplayActivity.setDlnaKeepLocalPreviewForDisplay(displayId, false)
                DisplayActivity.setLocalPreviewSuppressedForDisplay(displayId, true)
                runOnUiThread {
                    Toast.makeText(this@DesktopShellActivity, "已按 WebRTC 控制隐藏本机画面", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }

            // The first DLNA launch intentionally keeps the local preview. Set
            // this before starting the gateway so DisplayActivity's 500 ms monitor
            // cannot hide the local decoder merely because desktopMode is active.
            DisplayActivity.setDlnaKeepLocalPreviewForDisplay(displayId, true)

            val expectedWidth = display?.width?.takeIf { it > 0 } ?: 0
            val expectedHeight = display?.height?.takeIf { it > 0 } ?: 0
            val result = gatewayProcessController.start(
                displayId = displayId,
                gatewayPort = GatewayProcessController.DEFAULT_GATEWAY_PORT,
                bindHost = "0.0.0.0",
                expectedWidth = expectedWidth,
                expectedHeight = expectedHeight,
            )
            result.onSuccess {
                runOnUiThread {
                    openDlnaPage()
                    Toast.makeText(this@DesktopShellActivity, "DLNA 网关已启动，本机画面保持显示", Toast.LENGTH_SHORT).show()
                }
            }.onFailure { error ->
                Log.e("DesktopShell", "Failed to start DLNA gateway", error)
                DisplayActivity.setDlnaKeepLocalPreviewForDisplay(displayId, false)
                runOnUiThread {
                    Toast.makeText(this@DesktopShellActivity, "DLNA 网关启动失败：${error.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun openDlnaPage() {
        val uri = Uri.parse("http://127.0.0.1:${GatewayProcessController.DEFAULT_GATEWAY_PORT}/dlna")
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "系统没有可用的浏览器或 WebView", Toast.LENGTH_LONG).show()
        } catch (t: Throwable) {
            Log.e("DesktopShell", "Failed to open DLNA page", t)
            Toast.makeText(this, "无法打开 DLNA 页面：${t.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun showCursorSensitivityMenu() {
        contextMenuRequestSerial++
        contextMenuPopup?.dismiss()
        val cursorPercent = desktopPrefs.getInt("desktop_cursor_sensitivity_percent", 0).coerceIn(-100, 100)
        val scrollPercent = desktopPrefs.getInt("desktop_scroll_sensitivity_percent", 0).coerceIn(-100, 100)
        lifecycleScope.launch {
            lateinit var popup: PopupWindow
            val content = LinearLayout(this@DesktopShellActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), dp(10), dp(10), dp(10))
                background = rounded(0xF0161A20.toInt(), 18f)
                minimumWidth = dp(380)
            }
            val header = LinearLayout(this@DesktopShellActivity).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(4), dp(8), dp(8))
            }
            header.addView(ImageView(this@DesktopShellActivity).apply {
                setImageResource(android.R.drawable.ic_menu_mylocation)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
            }, LinearLayout.LayoutParams(dp(38), dp(38)))
            header.addView(TextView(this@DesktopShellActivity).apply {
                text = "光标灵敏度"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), 0, 0, 0)
            }, LinearLayout.LayoutParams(0, dp(38), 1f))
            content.addView(header)

            fun addSensitivitySlider(title: String, initialPercent: Int, onChanged: (Int) -> Unit) {
                val row = LinearLayout(this@DesktopShellActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(10), dp(6), dp(10), dp(8))
                }
                val captionRow = LinearLayout(this@DesktopShellActivity).apply {
                    gravity = Gravity.CENTER_VERTICAL
                }
                val label = TextView(this@DesktopShellActivity).apply {
                    text = title
                    setTextColor(Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                }
                val value = TextView(this@DesktopShellActivity).apply {
                    text = sensitivityPercentText(initialPercent)
                    setTextColor(0xFFCBD2DC.toInt())
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    gravity = Gravity.CENTER_VERTICAL or Gravity.END
                }
                captionRow.addView(label, LinearLayout.LayoutParams(0, dp(32), 1f))
                captionRow.addView(value, LinearLayout.LayoutParams(dp(80), dp(32)))
                row.addView(captionRow)
                val seek = SeekBar(this@DesktopShellActivity).apply {
                    max = 200
                    progress = initialPercent + 100
                    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                            val percent = (progress - 100).coerceIn(-100, 100)
                            value.text = sensitivityPercentText(percent)
                            if (fromUser) onChanged(percent)
                        }
                        override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                        override fun onStopTrackingTouch(bar: SeekBar?) = Unit
                    })
                }
                row.addView(seek, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)))
                content.addView(row)
            }

            addSensitivitySlider("光标速度", cursorPercent) { percent ->
                desktopPrefs.edit().putInt("desktop_cursor_sensitivity_percent", percent).apply()
                com.ynk.virtualdisplay.ui.display.InputController.setDesktopCursorSensitivityPercent(percent)
            }
            addSensitivitySlider("双指滚动速度", scrollPercent) { percent ->
                desktopPrefs.edit().putInt("desktop_scroll_sensitivity_percent", percent).apply()
                com.ynk.virtualdisplay.ui.display.InputController.setDesktopScrollSensitivityPercent(percent)
            }

            addContextSeparator(content)
            val hint = TextView(this@DesktopShellActivity).apply {
                text = "-100% ～ +100%：相对默认速度的调节幅度"
                setTextColor(0xFF9FA9B7.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(dp(10), dp(2), dp(10), dp(8))
            }
            content.addView(hint)
            val back = TextView(this@DesktopShellActivity).apply {
                text = "‹  返回桌面设置"
                setTextColor(0xFFCBD2DC.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), 0, dp(10), 0)
                isClickable = true
                setOnClickListener {
                    popup.dismiss()
                    showDesktopSettingsMenu(desktopTaskbar ?: content)
                }
            }
            content.addView(back, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)))

            popup = PopupWindow(
                content,
                minOf(dp(400), targetDisplayWidthForPopup() - dp(24)),
                ViewGroup.LayoutParams.WRAP_CONTENT,
                true
            ).apply {
                elevation = dp(32).toFloat()
                isOutsideTouchable = true
                inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    setWindowLayoutType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
                }
                setClippingEnabled(false)
            }
            content.measure(
                View.MeasureSpec.makeMeasureSpec(minOf(dp(400), targetDisplayWidthForPopup() - dp(24)), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(dp(500), View.MeasureSpec.AT_MOST)
            )
            val anchor = desktopTaskbar ?: desktopRoot ?: content
            val loc = IntArray(2)
            anchor.getLocationOnScreen(loc)
            val popupWidth = content.measuredWidth.coerceAtLeast(1)
            val popupHeight = content.measuredHeight.coerceAtLeast(1)
            val screenWidth = desktopWindowLayer?.width?.takeIf { it > 0 } ?: targetDisplayWidthForPopup()
            val margin = dp(12)
            val left = (loc[0] + anchor.width - popupWidth).coerceIn(
                margin, (screenWidth - popupWidth - margin).coerceAtLeast(margin)
            )
            val top = (loc[1] - popupHeight - dp(8)).coerceAtLeast(margin)
            popup.setOnDismissListener {
                if (contextMenuPopup === popup) {
                    contextMenuPopup = null
                    contextMenuRoot = null
                    contextMenuPointerLatched = false
                    contextMenuRightButtonLatched = false
                    contextMenuLeft = 0
                    contextMenuTop = 0
                    contextMenuWidth = 0
                    contextMenuHeight = 0
                }
            }
            runCatching {
                popup.showAtLocation(anchor, Gravity.TOP or Gravity.START, left, top)
                contextMenuPopup = popup
                contextMenuRoot = content
                contextMenuLeft = left
                contextMenuTop = top
                contextMenuWidth = popupWidth
                contextMenuHeight = popupHeight
                contextMenuPointerLatched = false
                contextMenuRightButtonLatched = false
            }.onFailure { Log.e("DesktopSettingsMenu", "SENSITIVITY_SHOW_FAILED", it) }
        }
    }

    private fun sensitivityPercentText(percent: Int): String = when {
        percent > 0 -> "+${percent}%"
        else -> "${percent}%"
    }

    private fun showWallpaperMenu() {
        val currentMode = desktopPrefs.getString("wallpaper_mode", "gradient") ?: "gradient"
        val checked = when (currentMode) { "dark" -> 1; "black" -> 2; "image" -> 3; else -> 0 }
        showDesktopSettingsSubmenu(
            title = "桌面壁纸",
            icon = android.R.drawable.ic_menu_gallery,
            items = listOf(
                "系统渐变" to { desktopPrefs.edit().putString("wallpaper_mode", "gradient").remove("wallpaper_uri").apply(); applyWallpaper() },
                "深色渐变" to { desktopPrefs.edit().putString("wallpaper_mode", "dark").remove("wallpaper_uri").apply(); applyWallpaper() },
                "纯黑" to { desktopPrefs.edit().putString("wallpaper_mode", "black").remove("wallpaper_uri").apply(); applyWallpaper() },
                "从设备选择图片" to { wallpaperPicker.launch(arrayOf("image/*")) }
            ),
            selectedIndex = checked,
            selectedIcon = android.R.drawable.btn_radio
        )
    }

    private fun exitDesktop() {
        Log.d("DesktopShell", "Exit desktop requested from wallpaper menu")
        runCatching { closeAppDrawer() }
        runCatching { hideDockOverlay() }
        finishAndRemoveTask()
    }

    private fun applyWallpaper() {
        val root = desktopRoot ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            val drawable = createWallpaperDrawable()
            withContext(Dispatchers.Main) { root.background = drawable }
        }
    }

    private fun toggleAppPresentation() {
        desktopPrefs.edit().putBoolean("show_app_icons", !showAppIcons()).apply()
        renderedTaskbarSignature = emptyList()
        lifecycleScope.launch { refreshTaskbar() }
        Toast.makeText(this, if (showAppIcons()) "应用改为显示图标" else "应用改为显示名称", Toast.LENGTH_SHORT).show()
    }

    private fun showDisplaySettingsDialog() {
        val displayMetrics = resources.displayMetrics
        val currentW = runCatching { display?.mode?.physicalWidth ?: resources.displayMetrics.widthPixels }.getOrDefault(resources.displayMetrics.widthPixels)
        val currentH = runCatching { display?.mode?.physicalHeight ?: resources.displayMetrics.heightPixels }.getOrDefault(resources.displayMetrics.heightPixels)
        val currentDpi = displayMetrics.densityDpi
        val presets = listOf(
            "当前: ${currentW}×${currentH} @ ${currentDpi} DPI" to {},
            "1280×720 @ 160" to { resizeDesktopDisplay(1280, 720, 160) },
            "1920×1080 @ 160" to { resizeDesktopDisplay(1920, 1080, 160) },
            "1920×1080 @ 240" to { resizeDesktopDisplay(1920, 1080, 240) },
            "2560×1440 @ 160" to { resizeDesktopDisplay(2560, 1440, 160) },
            "自定义" to { showCustomDisplayDialog(currentW, currentH, currentDpi) }
        )
        showDesktopSettingsSubmenu(
            title = "分辨率 / DPI",
            icon = android.R.drawable.ic_menu_crop,
            items = presets,
            selectedIndex = 0,
            selectedIcon = android.R.drawable.btn_radio
        )
    }

    /** Dock-style second-level menu used by desktop settings. */
    private fun showDesktopSettingsSubmenu(
        title: String,
        icon: Int,
        items: List<Pair<String, () -> Unit>>,
        selectedIndex: Int? = null,
        selectedIcon: Int = android.R.drawable.ic_menu_more,
    ) {
        contextMenuPopup?.dismiss()
        lifecycleScope.launch {
            lateinit var popup: PopupWindow
            val content = LinearLayout(this@DesktopShellActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), dp(10), dp(10), dp(10))
                background = rounded(0xF0161A20.toInt(), 18f)
                minimumWidth = dp(360)
            }
            val header = LinearLayout(this@DesktopShellActivity).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(4), dp(8), dp(8))
            }
            header.addView(ImageView(this@DesktopShellActivity).apply {
                setImageResource(icon)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
            }, LinearLayout.LayoutParams(dp(38), dp(38)))
            header.addView(TextView(this@DesktopShellActivity).apply {
                text = title
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), 0, 0, 0)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, dp(38), 1f))
            content.addView(header)

            items.forEachIndexed { index, pair ->
                val row = LinearLayout(this@DesktopShellActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    isClickable = true
                    background = rounded(0x281F2630.toInt(), 12f)
                    setPadding(dp(10), dp(6), dp(10), dp(6))
                    setOnClickListener {
                        popup.dismiss()
                        pair.second()
                    }
                }
                val rowIcon = ImageView(this@DesktopShellActivity).apply {
                    setImageResource(if (selectedIndex == index) selectedIcon else android.R.drawable.ic_menu_more)
                    alpha = if (selectedIndex == index) 1f else 0.72f
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                }
                row.addView(rowIcon, LinearLayout.LayoutParams(dp(34), dp(42)))
                row.addView(TextView(this@DesktopShellActivity).apply {
                    text = pair.first
                    setTextColor(Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(10), 0, dp(8), 0)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(0, dp(54), 1f))
                content.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)).apply {
                    topMargin = dp(3)
                })
            }

            addContextSeparator(content)
            val back = TextView(this@DesktopShellActivity).apply {
                text = "‹  返回桌面设置"
                setTextColor(0xFFCBD2DC.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), 0, dp(10), 0)
                isClickable = true
                setOnClickListener {
                    popup.dismiss()
                    showDesktopSettingsMenu(desktopTaskbar ?: content)
                }
            }
            content.addView(back, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)))

            popup = PopupWindow(
                content,
                minOf(dp(400), targetDisplayWidthForPopup() - dp(24)),
                ViewGroup.LayoutParams.WRAP_CONTENT,
                true
            ).apply {
                elevation = dp(32).toFloat()
                isOutsideTouchable = true
                inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    setWindowLayoutType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
                }
                setClippingEnabled(false)
            }
            val maxWidth = minOf(dp(400), targetDisplayWidthForPopup() - dp(24))
            content.measure(
                View.MeasureSpec.makeMeasureSpec(maxWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(dp(700), View.MeasureSpec.AT_MOST)
            )
            val anchor = desktopTaskbar ?: desktopRoot ?: content
            val loc = IntArray(2)
            anchor.getLocationOnScreen(loc)
            val popupWidth = content.measuredWidth.coerceAtLeast(1)
            val popupHeight = content.measuredHeight.coerceAtLeast(1)
            val screenWidth = desktopWindowLayer?.width?.takeIf { it > 0 } ?: targetDisplayWidthForPopup()
            val screenHeight = desktopWindowLayer?.height?.takeIf { it > 0 } ?: (display?.height ?: resources.displayMetrics.heightPixels)
            val margin = dp(12)
            val left = (loc[0] + anchor.width - popupWidth).coerceIn(margin, (screenWidth - popupWidth - margin).coerceAtLeast(margin))
            val top = (loc[1] - popupHeight - dp(8)).coerceAtLeast(margin)
            popup.setOnDismissListener {
                if (contextMenuPopup === popup) {
                    contextMenuPopup = null
                    contextMenuRoot = null
                    contextMenuPointerLatched = false
                    contextMenuRightButtonLatched = false
                    contextMenuLeft = 0
                    contextMenuTop = 0
                    contextMenuWidth = 0
                    contextMenuHeight = 0
                }
            }
            runCatching {
                popup.showAtLocation(anchor, Gravity.TOP or Gravity.START, left, top)
                contextMenuPopup = popup
                contextMenuRoot = content
                contextMenuLeft = left
                contextMenuTop = top
                contextMenuWidth = popupWidth
                contextMenuHeight = popupHeight
                contextMenuPointerLatched = false
                contextMenuRightButtonLatched = false
            }.onFailure { Log.e("DesktopSettingsMenu", "SUBMENU_SHOW_FAILED", it) }
        }
    }

    private fun showCustomDisplayDialog(currentW: Int, currentH: Int, currentDpi: Int) {
        // Third-level Dock-style editor for custom display parameters. Keep it
        // inside the same PopupWindow interaction model as the desktop settings
        // menus instead of falling back to a platform AlertDialog.
        contextMenuPopup?.dismiss()
        lifecycleScope.launch {
            lateinit var popup: PopupWindow
            val content = LinearLayout(this@DesktopShellActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), dp(10), dp(10), dp(10))
                background = rounded(0xF0161A20.toInt(), 18f)
                minimumWidth = dp(360)
            }

            val header = LinearLayout(this@DesktopShellActivity).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(4), dp(8), dp(8))
            }
            header.addView(ImageView(this@DesktopShellActivity).apply {
                setImageResource(android.R.drawable.ic_menu_crop)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
            }, LinearLayout.LayoutParams(dp(38), dp(38)))
            header.addView(TextView(this@DesktopShellActivity).apply {
                text = "自定义分辨率 / DPI"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), 0, 0, 0)
            }, LinearLayout.LayoutParams(0, dp(38), 1f))
            content.addView(header)

            fun field(label: String, value: Int): EditText = EditText(this@DesktopShellActivity).apply {
                setText(value.toString())
                hint = label
                setTextColor(Color.WHITE)
                setHintTextColor(0xFF9EA7B5.toInt())
                setSingleLine(true)
                setSelectAllOnFocus(false)
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                setPadding(dp(14), 0, dp(14), 0)
                background = rounded(0x281F2630.toInt(), 12f)

                // Keep normal EditText caret positioning, but explicitly calculate
                // the tapped character as a fallback for overlay-window input on
                // devices where the first tap otherwise leaves the caret at index 0.
                setOnTouchListener { view, event ->
                    if (event.action == MotionEvent.ACTION_DOWN) {
                        view.post {
                            val edit = view as EditText
                            val offset = runCatching {
                                edit.getOffsetForPosition(event.x, event.y)
                            }.getOrDefault(edit.selectionStart.coerceAtLeast(0))
                            edit.setSelection(offset.coerceIn(0, edit.text.length))
                        }
                    }
                    false
                }
            }

            val widthField = field("宽度", currentW)
            val heightField = field("高度", currentH)
            val dpiField = field("DPI", currentDpi)
            content.addView(widthField, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)).apply {
                topMargin = dp(4)
            })
            content.addView(heightField, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)).apply {
                topMargin = dp(6)
            })
            content.addView(dpiField, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)).apply {
                topMargin = dp(6)
            })

            addContextSeparator(content)

            val buttons = LinearLayout(this@DesktopShellActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val cancel = TextView(this@DesktopShellActivity).apply {
                text = "‹  返回分辨率 / DPI"
                setTextColor(0xFFCBD2DC.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                setPadding(dp(10), 0, dp(8), 0)
                setOnClickListener {
                    popup.dismiss()
                    showDisplaySettingsDialog()
                }
            }
            buttons.addView(cancel, LinearLayout.LayoutParams(0, dp(46), 1f))

            val apply = TextView(this@DesktopShellActivity).apply {
                text = "应用"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                gravity = Gravity.CENTER
                isClickable = true
                background = rounded(0xFF2D3642.toInt(), 10f)
                setPadding(dp(18), 0, dp(18), 0)
                setOnClickListener {
                    val width = widthField.text.toString().toIntOrNull()
                    val height = heightField.text.toString().toIntOrNull()
                    val density = dpiField.text.toString().toIntOrNull()
                    if (width == null || height == null || density == null ||
                        width < 320 || height < 240 || density < 80 || density > 640) {
                        Toast.makeText(this@DesktopShellActivity, "参数无效", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    popup.dismiss()
                    resizeDesktopDisplay(width, height, density)
                }
            }
            buttons.addView(apply, LinearLayout.LayoutParams(dp(82), dp(46)).apply {
                marginStart = dp(6)
            })
            content.addView(buttons)

            popup = PopupWindow(
                content,
                minOf(dp(400), targetDisplayWidthForPopup() - dp(24)),
                ViewGroup.LayoutParams.WRAP_CONTENT,
                true
            ).apply {
                elevation = dp(32).toFloat()
                isOutsideTouchable = true
                inputMethodMode = PopupWindow.INPUT_METHOD_NEEDED
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    setWindowLayoutType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
                }
                setClippingEnabled(false)
            }

            val maxWidth = minOf(dp(400), targetDisplayWidthForPopup() - dp(24))
            content.measure(
                View.MeasureSpec.makeMeasureSpec(maxWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(dp(700), View.MeasureSpec.AT_MOST)
            )
            val anchor = desktopTaskbar ?: desktopRoot ?: content
            val loc = IntArray(2)
            anchor.getLocationOnScreen(loc)
            val popupWidth = content.measuredWidth.coerceAtLeast(1)
            val popupHeight = content.measuredHeight.coerceAtLeast(1)
            val screenWidth = desktopWindowLayer?.width?.takeIf { it > 0 } ?: targetDisplayWidthForPopup()
            val margin = dp(12)
            val left = (loc[0] + anchor.width - popupWidth).coerceIn(
                margin, (screenWidth - popupWidth - margin).coerceAtLeast(margin)
            )
            val top = (loc[1] - popupHeight - dp(8)).coerceAtLeast(margin)

            popup.setOnDismissListener {
                if (contextMenuPopup === popup) {
                    contextMenuPopup = null
                    contextMenuRoot = null
                    contextMenuPointerLatched = false
                    contextMenuRightButtonLatched = false
                    contextMenuLeft = 0
                    contextMenuTop = 0
                    contextMenuWidth = 0
                    contextMenuHeight = 0
                }
            }

            runCatching {
                popup.showAtLocation(anchor, Gravity.TOP or Gravity.START, left, top)
                contextMenuPopup = popup
                contextMenuRoot = content
                contextMenuLeft = left
                contextMenuTop = top
                contextMenuWidth = popupWidth
                contextMenuHeight = popupHeight
                contextMenuPointerLatched = false
                contextMenuRightButtonLatched = false
                widthField.post {
                    if (contextMenuPopup === popup) {
                        widthField.requestFocus()
                        widthField.setSelection(widthField.text.length)
                    }
                }
            }.onFailure { Log.e("DesktopSettingsMenu", "CUSTOM_SHOW_FAILED", it) }
        }
    }

    private fun resizeDesktopDisplay(width: Int, height: Int, dpi: Int) {
        lifecycleScope.launch(Dispatchers.IO) {
            val result = displayInteractor.resizeDisplay(desktopDisplayId, width, height, dpi)
            withContext(Dispatchers.Main) {
                result.onSuccess {
                    desktopPrefs.edit()
                        .putInt(PREF_DESKTOP_WIDTH, width)
                        .putInt(PREF_DESKTOP_HEIGHT, height)
                        .putInt(PREF_DESKTOP_DPI, dpi)
                        .apply()
                    Toast.makeText(this@DesktopShellActivity, "已调整为 ${width}×${height} @ ${dpi} DPI", Toast.LENGTH_SHORT).show()
                    desktopRoot?.requestLayout()
                    desktopRoot?.invalidate()
                    desktopRoot?.post { refreshDesktopDisplayGeometry() }
                    desktopRoot?.postDelayed({ refreshDesktopDisplayGeometry() }, 150L)
                }.onFailure {
                    Toast.makeText(this@DesktopShellActivity, "调整失败：${it.message ?: "未知错误"}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showAppIcons(): Boolean = desktopPrefs.getBoolean("show_app_icons", true)

    private fun createAppItem(packageName: String, label: String, compact: Boolean, onClick: () -> Unit): View {
        return if (!showAppIcons()) {
            button(label, onClick).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                setPadding(if (compact) dp(10) else dp(18), 0, dp(10), 0)
                installAppContextGesture(this, packageName)
            }
        } else {
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = rounded(0x401F242C.toInt(), 14f)
                isClickable = true
                isLongClickable = true
                setOnClickListener { onClick() }
                installAppContextGesture(this, packageName)
                val icon = ImageView(this@DesktopShellActivity).apply {
                    setImageDrawable(loadAppIcon(packageName))
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                }
                val iconSize = if (compact) dp(28) else dp(68)
                addView(icon, LinearLayout.LayoutParams(iconSize, iconSize))
                if (!compact) {
                    // Icon drawer: keep the enlarged icon and show a short name underneath.
                    val caption = TextView(this@DesktopShellActivity).apply {
                        text = label.removePrefix("★  ")
                        setTextColor(Color.WHITE)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        gravity = Gravity.CENTER
                        includeFontPadding = false
                    }
                    addView(caption, LinearLayout.LayoutParams(dp(96), dp(20)).apply {
                        topMargin = dp(2)
                    })
                }
            }
        }
    }

    /**
     * Mirrors Launcher3's app icon interaction model: long-press or secondary mouse
     * click opens a context menu that combines the app's published shortcuts with
     * desktop actions. The popup is anchored to this Activity, so it stays on the
     * same virtual display rather than creating a physical-display overlay.
     */
    private fun installAppContextGesture(view: View, pkg: String) {
        view.isLongClickable = true
        view.setOnLongClickListener {
            showAppShortcutMenu(view, pkg)
            true
        }
        view.setOnContextClickListener {
            showAppShortcutMenu(view, pkg)
            true
        }
        view.setOnGenericMotionListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_BUTTON_PRESS &&
                (event.buttonState and MotionEvent.BUTTON_SECONDARY) != 0
            ) {
                showAppShortcutMenu(view, pkg)
                true
            } else {
                false
            }
        }
    }

    private fun showAppShortcutMenu(anchor: View, pkg: String) {
        val requestSerial = ++contextMenuRequestSerial
        contextMenuPopup?.dismiss()
        contextMenuPointerLatched = false
        contextMenuRightButtonLatched = false
        lifecycleScope.launch {
            val shortcuts = withContext(Dispatchers.IO) { queryPublishedShortcuts(pkg) }
            if (requestSerial != contextMenuRequestSerial || isFinishing || isDestroyed) return@launch
            lateinit var popup: PopupWindow
            val popupContent = LinearLayout(this@DesktopShellActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), dp(10), dp(10), dp(10))
                background = rounded(0xF0161A20.toInt(), 18f)
                minimumWidth = dp(360)
            }

            val header = LinearLayout(this@DesktopShellActivity).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(4), dp(8), dp(8))
            }
            val appIcon = ImageView(this@DesktopShellActivity).apply {
                setImageDrawable(loadAppIcon(pkg))
                scaleType = ImageView.ScaleType.CENTER_INSIDE
            }
            header.addView(appIcon, LinearLayout.LayoutParams(dp(42), dp(42)))
            val title = TextView(this@DesktopShellActivity).apply {
                text = appLabel(pkg)
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), 0, 0, 0)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            header.addView(title, LinearLayout.LayoutParams(0, dp(42), 1f))
            popupContent.addView(header)

            addContextAction(
                popupContent,
                "全屏打开",
                loadAppIcon(pkg),
                action = {
                    popup.dismiss()
                    launchRemoteApp(pkg)
                }
            )
            addContextAction(
                popupContent,
                "小窗打开",
                android.R.drawable.ic_menu_crop,
                action = {
                    // Left click: portrait freeform window (historical behavior).
                    popup.dismiss()
                    launchRemoteAppFreeform(pkg, portrait = true)
                },
                secondaryAction = {
                    // Right click: landscape freeform window (historical behavior).
                    popup.dismiss()
                    launchRemoteAppFreeform(pkg, portrait = false)
                }
            )
            addContextAction(
                popupContent,
                "分屏打开",
                android.R.drawable.ic_menu_view,
                action = {
                    popup.dismiss()
                    launchRemoteAppSplit(pkg, left = true)
                },
                secondaryAction = {
                    popup.dismiss()
                    launchRemoteAppSplit(pkg, left = false)
                }
            )

            if (shortcuts.isNotEmpty()) {
                addContextSeparator(popupContent)
                val caption = TextView(this@DesktopShellActivity).apply {
                    text = "应用快捷方式"
                    setTextColor(0xFFB8C0CC.toInt())
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setPadding(dp(10), dp(6), dp(10), dp(4))
                }
                popupContent.addView(caption)
                shortcuts.forEach { si ->
                    val text = si.shortLabel?.toString()?.takeIf { it.isNotBlank() }
                        ?: si.longLabel?.toString()?.takeIf { it.isNotBlank() }
                        ?: si.id
                    val icon: Drawable? = runCatching {
                        val launcherApps = getSystemService(LauncherApps::class.java)
                        launcherApps?.getShortcutIconDrawable(si, resources.displayMetrics.densityDpi)
                    }.getOrNull()
                    addContextAction(popupContent, text, icon ?: loadAppIcon(pkg), action = {
                        popup.dismiss()
                        launchPublishedShortcut(si)
                    })
                }
            }

            addContextSeparator(popupContent)
            addContextAction(popupContent, "关闭应用", android.R.drawable.ic_menu_close_clear_cancel, action = {
                popup.dismiss()
                closeAppTask(pkg)
            })
            addContextAction(popupContent, "应用信息", android.R.drawable.ic_menu_info_details, action = {
                popup.dismiss()
                showAppInfo(pkg)
            })

            popup = PopupWindow(
                popupContent,
                minOf(dp(430), targetDisplayWidthForPopup() - dp(32)),
                ViewGroup.LayoutParams.WRAP_CONTENT,
                true
            ).apply {
                elevation = dp(32).toFloat()
                isOutsideTouchable = true
                inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    setWindowLayoutType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
                }
                setClippingEnabled(false)
                popupContent.elevation = dp(32).toFloat()
            }

            popupContent.measure(
                View.MeasureSpec.makeMeasureSpec(minOf(dp(430), targetDisplayWidthForPopup() - dp(32)), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(dp(1200), View.MeasureSpec.AT_MOST)
            )
            val loc = IntArray(2)
            anchor.getLocationOnScreen(loc)
            val popupWidth = popupContent.measuredWidth.coerceAtLeast(1)
            val popupHeight = popupContent.measuredHeight.coerceAtLeast(1)
            val screenWidth = desktopWindowLayer?.width?.takeIf { it > 0 } ?: targetDisplayWidthForPopup()
            val screenHeight = desktopWindowLayer?.height?.takeIf { it > 0 } ?: (display?.height ?: resources.displayMetrics.heightPixels)
            val margin = dp(12)
            val left = loc[0].coerceIn(margin, (screenWidth - popupWidth - margin).coerceAtLeast(margin))
            val belowTop = (loc[1] + anchor.height + dp(6)).coerceIn(margin, (screenHeight - popupHeight - margin).coerceAtLeast(margin))
            val fitsBelow = belowTop + popupHeight <= screenHeight - margin
            val top = if (fitsBelow) {
                belowTop
            } else {
                (loc[1] - popupHeight - dp(6)).coerceAtLeast(margin)
            }
            Log.d("DesktopContextMenu", "SHOW pkg=$pkg anchor=${anchor.javaClass.simpleName} x=${loc[0]} y=${loc[1]} displayId=$desktopDisplayId popup=${popupWidth}x$popupHeight screen=${screenWidth}x$screenHeight top=$top fitsBelow=$fitsBelow")
            popup.setOnDismissListener {
                contextMenuPopup = null
                contextMenuRoot = null
                contextMenuPointerLatched = false
                contextMenuRightButtonLatched = false
                contextMenuLeft = 0
                contextMenuTop = 0
                contextMenuWidth = 0
                contextMenuHeight = 0
            }
            runCatching {
                popup.showAtLocation(anchor, Gravity.TOP or Gravity.START, left, top)
                contextMenuPopup = popup
                contextMenuRoot = popupContent
                contextMenuLeft = left
                contextMenuTop = top
                contextMenuWidth = popupWidth
                contextMenuHeight = popupHeight
                contextMenuPointerLatched = false
                contextMenuRightButtonLatched = false
                Log.d("DesktopContextMenu", "SHOWN pkg=$pkg left=$left top=$top overlayType=${WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY}")
            }.onFailure {
                contextMenuPopup = null
                contextMenuRoot = null
                contextMenuPointerLatched = false
                contextMenuRightButtonLatched = false
                Log.e("DesktopContextMenu", "SHOW_FAILED pkg=$pkg", it)
            }
        }
    }

    private fun targetDisplayWidthForPopup(): Int =
        display?.width?.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels

    private fun addContextSeparator(parent: LinearLayout) {
        val v = View(this).apply {
            setBackgroundColor(0x333A4350)
        }
        parent.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply {
            setMargins(dp(8), dp(5), dp(8), dp(5))
        })
    }

    private fun addContextAction(
        parent: LinearLayout,
        text: String,
        icon: Any?,
        action: () -> Unit,
        secondaryAction: (() -> Unit)? = null,
    ) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isLongClickable = secondaryAction != null
            background = rounded(0x281F2630.toInt(), 12f)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setOnClickListener { action() }
            if (secondaryAction != null) {
                setOnContextClickListener {
                    secondaryAction()
                    true
                }
                setOnGenericMotionListener { _, event ->
                    if (event.actionMasked == MotionEvent.ACTION_BUTTON_PRESS &&
                        (event.actionButton == MotionEvent.BUTTON_SECONDARY ||
                            (event.buttonState and MotionEvent.BUTTON_SECONDARY) != 0)
                    ) {
                        secondaryAction()
                        true
                    } else {
                        false
                    }
                }
                setOnTouchListener { _, event ->
                    if (event.actionMasked == MotionEvent.ACTION_BUTTON_PRESS &&
                        event.actionButton == MotionEvent.BUTTON_SECONDARY) {
                        secondaryAction()
                        true
                    } else {
                        false
                    }
                }
            }
        }
        val iv = ImageView(this).apply {
            when (icon) {
                is Drawable -> setImageDrawable(icon)
                is Int -> setImageResource(icon)
            }
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }
        row.addView(iv, LinearLayout.LayoutParams(dp(34), dp(34)))
        val label = TextView(this).apply {
            this.text = text
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(12), 0, dp(6), 0)
        }
        row.addView(label, LinearLayout.LayoutParams(0, dp(46), 1f))
        parent.addView(row, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(50)
        ).apply {
            setMargins(0, dp(2), 0, dp(2))
        })
    }

    private fun queryPublishedShortcuts(pkg: String): List<ShortcutInfo> {
        val launcherApps = getSystemService(LauncherApps::class.java) ?: return emptyList()
        val query = ShortcutQuery()
            .setPackage(pkg)
            .setQueryFlags(
                ShortcutQuery.FLAG_MATCH_DYNAMIC or
                    ShortcutQuery.FLAG_MATCH_MANIFEST or
                    ShortcutQuery.FLAG_MATCH_PINNED
            )
        return runCatching {
            launcherApps.getShortcuts(query, android.os.Process.myUserHandle()).orEmpty()
                .filter { it.isEnabled }
                .sortedBy { it.rank }
        }.getOrElse {
            Log.w("DesktopShell", "Unable to query shortcuts for $pkg", it)
            emptyList()
        }
    }

    private fun launchPublishedShortcut(info: ShortcutInfo) {
        val launcherApps = getSystemService(LauncherApps::class.java) ?: return
        val options = makeVirtualDisplayLaunchOptions(freeform = false, anchor = null)
        runCatching {
            launcherApps.startShortcut(info, null, options.toBundle())
            lifecycleScope.launch(Dispatchers.IO) {
                RecentAppHelper.addRecentApp(this@DesktopShellActivity, info.`package`)
            }
        }.onFailure {
            Log.e("DesktopShell", "Failed to launch deep shortcut ${info.id}", it)
            Toast.makeText(this, "快捷方式启动失败：${it.message ?: "未知错误"}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun makeVirtualDisplayLaunchOptions(freeform: Boolean, anchor: View?): ActivityOptions =
        ActivityOptions.makeBasic().apply {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                setLaunchDisplayId(desktopDisplayId)
            }
        }

    private fun launchRemoteAppFreeform(
        packageName: String,
        portrait: Boolean,
        maximizeAfterCreate: Boolean = false,
    ) {
        if (!ensureOverlayPermissionSilently()) {
            Toast.makeText(this, "需要悬浮窗权限才能显示桌面窗口装饰", Toast.LENGTH_SHORT).show()
            return
        }
        val dockHeight = dp(68)
        val availableOuterHeight = (display?.height ?: resources.displayMetrics.heightPixels) - dockHeight
        val topChrome = dp(40)
        val bottomChrome = dp(32)
        val minWidth = dp(280)
        val minHeight = dp(220)
        val screenWidth = display?.width ?: resources.displayMetrics.widthPixels
        val screenHeight = display?.height ?: resources.displayMetrics.heightPixels

        val contentWidth: Int
        val contentHeight: Int
        val x: Int
        val y: Int
        if (portrait) {
            contentWidth = dp(480).coerceAtMost((screenWidth - dp(24)).coerceAtLeast(minWidth))
            contentHeight = (availableOuterHeight - topChrome - bottomChrome).coerceAtLeast(minHeight)
            val maxX = (screenWidth - contentWidth).coerceAtLeast(0)
            x = if (maxX == 0) 0 else ((portraitWindowSerial++ * dp(48)) % (maxX + 1))
            y = 0
        } else {
            contentWidth = dp(900).coerceAtMost((screenWidth - dp(24)).coerceAtLeast(minWidth))
            contentHeight = dp(600).coerceAtMost((availableOuterHeight - topChrome - bottomChrome).coerceAtLeast(minHeight))
            val maxX = (screenWidth - contentWidth).coerceAtLeast(0)
            val maxY = (availableOuterHeight - (topChrome + contentHeight + bottomChrome)).coerceAtLeast(0)
            val step = dp(48)
            x = if (maxX == 0) 0 else ((landscapeWindowSerial * step) % (maxX + 1))
            y = if (maxY == 0) 0 else ((landscapeWindowSerial++ * step) % (maxY + 1))
        }

        lifecycleScope.launch(Dispatchers.Main.immediate) {
            val window = FreeformOverlayDecoration(
                this@DesktopShellActivity,
                packageName,
                initialX = x,
                initialY = y,
                initialContentWidth = contentWidth,
                initialContentHeight = contentHeight,
                portraitMode = portrait,
            )
            desktopWindows.add(window)
            window.show()
            if (maximizeAfterCreate) {
                window.toggleMaximizedFromDock()
            }
            lifecycleScope.launch(Dispatchers.IO) {
                RecentAppHelper.addRecentApp(this@DesktopShellActivity, packageName)
            }
            refreshTaskbar(allowWhenUnfocused = true)
        }
    }

    private fun launchRemoteAppMaximized(packageName: String) {
        launchRemoteAppFreeform(packageName, portrait = true, maximizeAfterCreate = true)
    }

    private fun launchRemoteAppSplit(packageName: String, left: Boolean) {
        if (!ensureOverlayPermissionSilently()) {
            Toast.makeText(this, "需要悬浮窗权限才能显示桌面窗口装饰", Toast.LENGTH_SHORT).show()
            return
        }
        val dockHeight = dp(68)
        val topChrome = dp(40)
        val bottomChrome = dp(32)
        val screenWidth = display?.width ?: resources.displayMetrics.widthPixels
        val screenHeight = display?.height ?: resources.displayMetrics.heightPixels
        val halfWidth = (screenWidth / 2).coerceAtLeast(dp(280))
        val availableOuterHeight = (screenHeight - dockHeight).coerceAtLeast(topChrome + bottomChrome + dp(220))
        val contentHeight = (availableOuterHeight - topChrome - bottomChrome).coerceAtLeast(dp(220))
        lifecycleScope.launch(Dispatchers.Main.immediate) {
            val window = FreeformOverlayDecoration(
                this@DesktopShellActivity,
                packageName,
                initialX = if (left) 0 else (screenWidth - halfWidth).coerceAtLeast(0),
                initialY = 0,
                initialContentWidth = halfWidth,
                initialContentHeight = contentHeight,
                portraitMode = true,
                splitMode = true,
            )
            desktopWindows.add(window)
            window.show()
            lifecycleScope.launch(Dispatchers.IO) {
                RecentAppHelper.addRecentApp(this@DesktopShellActivity, packageName)
            }
            refreshTaskbar(allowWhenUnfocused = true)
        }
    }

    internal fun killDesktopWindowTask(taskId: Int) {
        if (taskId <= 0) return
        lifecycleScope.launch(Dispatchers.IO) {
            VirtualDisplayTaskManager.removeTask(taskId)
        }
    }

    private fun closeAppTask(packageName: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val stopped = runCatching {
                VirtualDisplayTaskManager.forceStopPackage(packageName)
            }.getOrElse {
                Log.e("DesktopShell", "关闭应用失败", it)
                false
            }
            withContext(Dispatchers.Main.immediate) {
                // Reuse the exact same close path as the title-bar close button.
                // This keeps display/session teardown identical for manual and
                // app-context-menu initiated window closing.
                desktopWindows.filter { it.packageName == packageName }.toList().forEach { it.closeWindow() }
            }
        }
    }

    private fun showAppInfo(packageName: String) {
        val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:$packageName")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    private fun appLabel(pkg: String): String {
        appLabelCache[pkg]?.let { return it }
        val label = runCatching {
            packageManager.getApplicationInfo(pkg, 0).loadLabel(packageManager).toString()
        }.getOrDefault(pkg.substringAfterLast('.'))
        appLabelCache.putIfAbsent(pkg, label)
        return label
    }

    private fun loadAppIcon(pkg: String): android.graphics.drawable.Drawable {
        val state = appIconStateCache[pkg]
        if (state != null) {
            return state.newDrawable(resources)
        }
        val drawable = runCatching { packageManager.getApplicationIcon(pkg) }.getOrElse {
            getDrawable(android.R.drawable.sym_def_app_icon)!!
        }
        drawable.constantState?.let { appIconStateCache.putIfAbsent(pkg, it) }
        return drawable
    }

    /** Execute a privileged shell command through Shizuku. */
    internal fun runShizuku(command: String): String {
        val method: Method = Shizuku::class.java.getDeclaredMethod(
            "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
        )
        method.isAccessible = true
        val process = method.invoke(
            null, arrayOf("sh", "-c", command), null, null
        ) as java.lang.Process
        val out = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
        process.waitFor()
        return out
    }

    private fun ensureOverlayPermissionSilently(): Boolean {
        if (Settings.canDrawOverlays(this)) return true
        if (!Shizuku.pingBinder()) return false
        runCatching {
            runShizuku("appops set $packageName SYSTEM_ALERT_WINDOW allow")
        }.onFailure {
            Log.w("DesktopShell", "Unable to grant overlay permission via Shizuku", it)
        }
        return Settings.canDrawOverlays(this)
    }

    private fun hasFullscreenDesktopApp(): Boolean {
        val tasks = runCatching { VirtualDisplayTaskManager.findTasks(this, desktopDisplayId) }
            .getOrDefault(emptyList())
        // Background tasks on the Desktop display must not hide a floating Dock.
        // Only a foreign task that is actually top-resumed/foreground qualifies.
        return tasks.any { it.packageName != packageName && it.topResumed }
    }

    /** One persistent Dock overlay. Floating/docked transitions only update
     * LayoutParams; the Dock View itself is never detached during normal use. */
    internal fun raiseDesktopInteractiveOverlays() {
        // Drawer is added after the persistent Dock, so it remains above it.
        // Desktop windows are children of the Activity and remain below both.
    }

    private fun floatingDockParams(): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            ((display?.width ?: resources.displayMetrics.widthPixels) - dp(48)).coerceAtLeast(dp(320)),
            dp(68),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            x = dp(24)
            y = dp(18)
            title = "VirtualDisplayDesktopDock-$desktopDisplayId"
        }

    private fun dockedDockParams(): WindowManager.LayoutParams = floatingDockParams().also {
        it.width = display?.width ?: resources.displayMetrics.widthPixels
        it.x = 0
        it.y = 0
    }

    private fun ensureDockOverlayAttached() {
        if (dockOverlayAttached || isFinishing || isDestroyed) return
        val dock = desktopTaskbar ?: return
        if (!ensureOverlayPermissionSilently()) return
        val parent = dock.parent as? ViewGroup
        val targetDisplay = display ?: return
        val displayContext = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            createDisplayContext(targetDisplay).createWindowContext(
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null
            )
        } else createDisplayContext(targetDisplay)
        val wm = displayContext.getSystemService(WindowManager::class.java) ?: return
        val params = floatingDockParams()
        parent?.removeView(dock)
        runCatching {
            wm.addView(dock, params)
            dockOverlayWindowManager = wm
            dockOverlayParams = params
            dockOverlayAttached = true
            dockOverlayVisible = false
            dock.visibility = View.GONE
        }.onFailure {
            parent?.addView(dock, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(68)
            ))
            Log.w("DesktopShell", "Failed to attach persistent virtual-display Dock overlay", it)
        }
    }

    private fun hideFloatingDockOnOutsideClickInternal(action: Int, x: Float, y: Float): Boolean {
        val displayId = desktopDisplayId
        if (action != MotionEvent.ACTION_DOWN ||
            desktopDisplayId != displayId || !dockOverlayVisible || dockIsDocked || !hasFullscreenDesktopApp()) {
            return false
        }
        val params = dockOverlayParams ?: return false
        val targetDisplay = display ?: return false
        val top = targetDisplay.height - params.height - params.y
        val inside = x >= params.x && x < params.x + params.width &&
            y >= top && y < top + params.height
        if (inside) return false
        dockForcedVisible = false
        hideDockOverlay()
        return true
    }

    private fun syncDockVisibility() {
        if (isFinishing || isDestroyed) return
        // The bare DesktopShell always keeps the floating Dock visible. Only a
        // different foreground app on the desktop display can make a floating
        // Dock eligible for automatic hiding. Maximized desktop windows always
        // require the docked/full-width form.
        val foreignFullscreen = hasFullscreenDesktopApp()
        val shouldBeVisible = maximizedDesktopWindowCount > 0 ||
            !foreignFullscreen || dockForcedVisible
        if (shouldBeVisible) showDockOverlay() else hideDockOverlay()
    }

    private fun showDockOverlay() {
        ensureDockOverlayAttached()
        val dock = desktopTaskbar ?: return
        dock.visibility = View.VISIBLE
        dockOverlayVisible = true
        // Dock-visible state means the DesktopShell owns the interactive surface.
        desktopWindowOverlayHost?.setInputInteractive(true)
        animateDockMode(maximizedDesktopWindowCount > 0)
        lifecycleScope.launch { refreshTaskbar(allowWhenUnfocused = true) }
    }

    private fun hideDockOverlay() {
        if (!dockOverlayAttached) return
        // Dock is the owner of the desktop interactive surface. When it hides,
        // dismiss every transient UI that belongs to it as well.
        runCatching { closeAppDrawer() }
        runCatching { contextMenuPopup?.dismiss() }
        val dock = desktopTaskbar ?: return
        dockOverlayVisible = false
        // A hidden floating Dock is the fullscreen-app state. Force the shared
        // visual overlay into pass-through immediately, including when there are
        // zero freeform windows and therefore no router callback to trigger it.
        desktopWindowOverlayHost?.forcePassThrough()
        dockAnimation?.cancel()
        dockAnimation = null
        dock.visibility = View.GONE
    }

    private fun animateDockMode(docked: Boolean) {
        val dock = desktopTaskbar ?: return
        val wm = dockOverlayWindowManager ?: return
        val params = dockOverlayParams ?: return
        val target = if (docked) dockedDockParams() else floatingDockParams()
        val same = dockIsDocked == docked &&
            params.width == target.width && params.x == target.x && params.y == target.y
        if (same) {
            updateDockAppearance(docked)
            return
        }
        dockAnimation?.cancel()
        val startW = params.width
        val startX = params.x
        val startY = params.y
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 220L
            addUpdateListener { a ->
                val t = a.animatedValue as Float
                params.width = (startW + (target.width - startW) * t).roundToInt()
                params.x = (startX + (target.x - startX) * t).roundToInt()
                params.y = (startY + (target.y - startY) * t).roundToInt()
                runCatching { wm.updateViewLayout(dock, params) }
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    params.width = target.width
                    params.x = target.x
                    params.y = target.y
                    runCatching { wm.updateViewLayout(dock, params) }
                    dockIsDocked = docked
                    updateDockAppearance(docked)
                    dockAnimation = null
                }
                override fun onAnimationCancel(animation: android.animation.Animator) {
                    dockAnimation = null
                }
            })
        }
        dockAnimation = animator
        animator.start()
    }

    private fun updateDockAppearance(docked: Boolean) {
        val dock = desktopTaskbar ?: return
        dock.background = rounded(0xE01B1F26.toInt(), if (docked) 0f else 18f)
        dock.elevation = if (docked) 0f else dp(8).toFloat()
    }

    private fun button(text: String, onClick: () -> Unit): TextView = TextView(this).apply {
        this.text = text
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        gravity = Gravity.CENTER
        background = rounded(0x401F242C.toInt(), 14f)
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun createWallpaperDrawable(): android.graphics.drawable.Drawable {
        val mode = desktopPrefs.getString("wallpaper_mode", "gradient") ?: "gradient"
        if (mode == "image") {
            val uri = desktopPrefs.getString("wallpaper_uri", null)?.let { Uri.parse(it) }
            if (uri != null) {
                val bmp = runCatching { contentResolver.openInputStream(uri).use { input -> BitmapFactory.decodeStream(input) } }.getOrNull()
                if (bmp != null) return CenterCropBitmapDrawable(bmp)
            }
        }
        return when (mode) {
            "dark" -> android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF05070B.toInt(), 0xFF111827.toInt(), 0xFF020308.toInt()))
            "black" -> android.graphics.drawable.ColorDrawable(Color.BLACK)
            else -> android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF101826.toInt(), 0xFF182A42.toInt(), 0xFF0B1220.toInt()))
        }
    }

    private class CenterCropBitmapDrawable(private val bitmap: Bitmap) : android.graphics.drawable.Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        override fun draw(canvas: Canvas) {
            if (bitmap.width <= 0 || bitmap.height <= 0 || bounds.width() <= 0 || bounds.height() <= 0) return
            val scale = maxOf(bounds.width().toFloat() / bitmap.width, bounds.height().toFloat() / bitmap.height)
            val drawW = bitmap.width * scale
            val drawH = bitmap.height * scale
            val left = bounds.left + (bounds.width() - drawW) / 2f
            val top = bounds.top + (bounds.height() - drawH) / 2f
            val dst = android.graphics.RectF(left, top, left + drawW, top + drawH)
            canvas.drawBitmap(bitmap, null, dst, paint)
        }

        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { paint.colorFilter = colorFilter }
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
        override fun getIntrinsicWidth(): Int = bitmap.width
        override fun getIntrinsicHeight(): Int = bitmap.height
    }

    internal val displayInteractorPublic: DisplayInteractor get() = displayInteractor
    internal fun dpPublic(value: Int): Int = dp(value)
    internal fun roundedPublic(color: Int, radiusDp: Float): android.graphics.drawable.Drawable = rounded(color, radiusDp)
    internal fun ensureOverlayPermissionSilentlyPublic(): Boolean = ensureOverlayPermissionSilently()
    internal fun logDesktopWindow(message: String, t: Throwable? = null) { Log.w("DesktopShell", message, t) }

    private fun rounded(color: Int, radiusDp: Float): android.graphics.drawable.Drawable =
        android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
        }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).roundToInt()

    private fun dp(value: Float): Int =
        (value * resources.displayMetrics.density).roundToInt()

    private fun Int.roundToInt(): Int = this
    private fun Float.roundToInt(): Int = kotlin.math.round(this).toInt()


    internal fun onDesktopWindowMaximizedChanged(
        window: FreeformOverlayDecoration,
        maximized: Boolean,
    ) {
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            val visibleMaximizedCount = desktopWindows.count { it !== window && it.isMaximized() } +
                if (maximized && window.isVisible()) 1 else 0
            maximizedDesktopWindowCount = visibleMaximizedCount
            applyMaximizedDockState()
            lifecycleScope.launch { refreshTaskbar(allowWhenUnfocused = true) }
        }
    }

    private fun applyMaximizedDockState() {
        syncDockVisibility()
    }

    internal fun removeDesktopWindowPublic(window: FreeformOverlayDecoration) {
        desktopWindows.remove(window)
        lifecycleScope.launch { refreshTaskbar(allowWhenUnfocused = true) }
    }

}
