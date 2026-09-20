package com.ynk.virtualdisplay.ui.desktop

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout

/**
 * Display-local visual host for desktop freeform windows.
 *
 * This window is intentionally FLAG_NOT_TOUCHABLE. Desktop pointer input is
 * collected by InputController and routed by DesktopWindowInputRouter, so this
 * visual overlay can sit above fullscreen applications without stealing their
 * pointer stream. Chrome controls are dispatched directly to the corresponding
 * decoration view by the same router.
 */
internal class DesktopWindowOverlayHost(
    private val activity: DesktopShellActivity,
) {
    companion object {
        private const val TAG = "DesktopWindowOverlayHost"

        /** Marker retained for compatibility; chrome routing is performed by the router. */
        const val CHROME_TOUCH_TAG = "VirtualDisplay.Desktop.ChromeTouch"
    }

    private val targetDisplay: Display = activity.display
        ?: throw IllegalStateException("Desktop display unavailable")

    private val windowContext: Context = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        activity.createDisplayContext(targetDisplay).createWindowContext(
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            null,
        )
    } else {
        activity.createDisplayContext(targetDisplay)
    }

    private val wm: WindowManager = windowContext.getSystemService(WindowManager::class.java)
        ?: throw IllegalStateException("WindowManager unavailable")

    private val host = FrameLayout(windowContext).apply {
        clipChildren = false
        clipToPadding = false
        isClickable = false
        isFocusable = false
        setWillNotDraw(true)
    }

    private val params = WindowManager.LayoutParams(
        targetDisplay.width.coerceAtLeast(1),
        targetDisplay.height.coerceAtLeast(1),
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = 0
        y = 0
        title = "VirtualDisplay Desktop Windows"
    }

    private var attached = false
    private var inputInteractive = false

    fun attach(): Boolean {
        if (attached) return true
        return runCatching {
            wm.addView(host, params)
            attached = true
        }.onFailure {
            Log.e(TAG, "Failed to attach desktop window overlay", it)
        }.isSuccess
    }

    /**
     * Switch the overlay between visual-only pass-through mode and the
     * touchable mode used while the desktop cursor is over a freeform window.
     *
     * The original overlay becomes visually opaque when FLAG_NOT_TOUCHABLE is
     * removed. Keep FLAG_NOT_FOCUSABLE so the fullscreen app does not lose
     * keyboard focus; the input router decides when this window may intercept
     * pointer events.
     */
    /**
     * The VirtualDisplay can be resized without recreating DesktopShellActivity.
     * Keep the visual host's WindowManager bounds synchronized with the new
     * display size, otherwise newly exposed pixels have nowhere to draw.
     */
    fun updateDisplayGeometry(width: Int, height: Int) {
        if (!attached) return
        val w = width.coerceAtLeast(1)
        val h = height.coerceAtLeast(1)
        if (params.width == w && params.height == h) return
        params.width = w
        params.height = h
        runCatching {
            wm.updateViewLayout(host, params)
            host.requestLayout()
        }.onFailure {
            Log.w(TAG, "Failed to update desktop overlay host geometry ${w}x${h}", it)
        }
    }

    fun setInputInteractive(interactive: Boolean, forceUpdate: Boolean = false) {
        if (!attached) return
        if (!forceUpdate && inputInteractive == interactive) return
        inputInteractive = interactive
        val newFlags = if (interactive) {
            params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        params.flags = newFlags
        runCatching {
            wm.updateViewLayout(host, params)
        }.onFailure {
            Log.w(TAG, "Failed to switch overlay input mode interactive=$interactive force=$forceUpdate", it)
        }
    }

    /**
     * Force the visual overlay back into pass-through mode. This deliberately
     * bypasses the cached state because an external task switch can happen
     * without DesktopWindowInputRouter seeing another pointer event.
     */
    fun forcePassThrough() {
        if (!attached) return
        inputInteractive = false
        params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        runCatching {
            wm.updateViewLayout(host, params)
        }.onFailure {
            Log.w(TAG, "Failed to force desktop overlay pass-through", it)
        }
    }

    fun isInputInteractive(): Boolean = inputInteractive

    fun addView(view: View, width: Int, height: Int, x: Int, y: Int) {
        if (!attached && !attach()) {
            throw IllegalStateException("Desktop window overlay host unavailable")
        }
        if (view.parent === host) return
        (view.parent as? ViewGroup)?.removeView(view)
        view.layoutParams = FrameLayout.LayoutParams(width, height).apply {
            leftMargin = x
            topMargin = y
        }
        host.addView(view)
        view.bringToFront()
        host.invalidate()
    }

    fun updateView(view: View, width: Int, height: Int, x: Int, y: Int) {
        if (view.parent !== host) return
        val lp = (view.layoutParams as? FrameLayout.LayoutParams)
            ?: FrameLayout.LayoutParams(width, height)
        lp.width = width
        lp.height = height
        lp.leftMargin = x
        lp.topMargin = y
        view.layoutParams = lp
    }

    fun commitGeometry(view: View? = null) {
        if (!attached) return
        if (view != null && view.parent !== host) return
        host.requestLayout()
    }

    fun bringToFront(view: View) {
        if (!attached || view.parent !== host) return
        if (activity.isFinishing || activity.isDestroyed) return
        view.bringToFront()
        host.invalidate()
    }

    fun removeView(view: View) {
        if (view.parent !== host) return
        host.removeView(view)
        host.invalidate()
    }

    fun detach() {
        if (!attached) return
        inputInteractive = false
        runCatching { wm.removeViewImmediate(host) }
            .onFailure { Log.w(TAG, "Failed to detach desktop window overlay", it) }
        attached = false
    }

    fun isAttached(): Boolean = attached
}
