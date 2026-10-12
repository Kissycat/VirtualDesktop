package com.ynk.virtualdisplay.ui.desktop

import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.util.Log
import java.lang.ref.WeakReference

/**
 * Routes the existing desktop cursor stream into the correct visual/input
 * target. Content goes into the private virtual-display session; decoration
 * chrome is dispatched directly to its already-attached View hierarchy.
 */
internal object DesktopWindowInputRouter {
    private const val TAG = "DesktopWindowRouter"

    data class Entry(
        val session: DesktopWindowSession,
        val displayId: Int,
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
        val chromeTop: Int,
        val root: View,
        val promoteVisual: () -> Unit,
        val setOverlayInteractive: (Boolean) -> Unit,
    ) {
        fun containsContent(px: Float, py: Float): Boolean =
            width > 0 && height > 0 &&
                px >= x && px < x + width &&
                py >= y + chromeTop && py < y + chromeTop + height

        fun containsWindow(px: Float, py: Float): Boolean =
            root.visibility == View.VISIBLE &&
                px >= x && px < x + width &&
                py >= y && py < y + root.height.coerceAtLeast(chromeTop + height)

        fun mapPoint(px: Float, py: Float): Pair<Int, Int> {
            val localX = (px - x).coerceIn(0f, width.toFloat())
            val localY = (py - y - chromeTop).coerceIn(0f, height.toFloat())
            return session.mapContentPointToVideo(localX.toInt(), localY.toInt(), width, height)
        }
    }

    private enum class PointerRoute { NONE, CONTENT, CHROME }

    private val lock = Any()
    private val entries = LinkedHashMap<DesktopWindowSession, Entry>()
    private var pointerTarget: WeakReference<DesktopWindowSession>? = null
    private var pointerRoute = PointerRoute.NONE
    private var focusedTarget: WeakReference<DesktopWindowSession>? = null

    fun register(
        session: DesktopWindowSession,
        displayId: Int,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        chromeTop: Int,
        root: View,
        promoteVisual: () -> Unit = {},
        setOverlayInteractive: (Boolean) -> Unit = {},
    ) {
        synchronized(lock) {
            entries[session] = Entry(session, displayId, x, y, width, height, chromeTop, root, promoteVisual, setOverlayInteractive)
            focusedTarget = WeakReference(session)
        }
        Log.d(TAG, "register sessionDisplay=${session.windowDisplayId()} parentDisplay=$displayId rect=$x,$y ${width}x$height chromeTop=$chromeTop")
    }

    fun update(
        session: DesktopWindowSession,
        displayId: Int,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        chromeTop: Int,
    ) {
        synchronized(lock) {
            entries[session]?.let { current ->
                entries[session] = current.copy(
                    displayId = displayId,
                    x = x,
                    y = y,
                    width = width,
                    height = height,
                    chromeTop = chromeTop,
                )
            }
        }
    }

    fun bringToFront(session: DesktopWindowSession) {
        val promote: (() -> Unit)?
        synchronized(lock) {
            val e = entries.remove(session) ?: return
            entries[session] = e
            pointerTarget = WeakReference(session)
            focusedTarget = WeakReference(session)
            pointerRoute = PointerRoute.NONE
            promote = e.promoteVisual
        }
        promote?.invoke()
        Log.d(TAG, "front sessionDisplay=${session.windowDisplayId()}")
    }

    fun clearFocus() {
        synchronized(lock) {
            focusedTarget = null
            pointerTarget = null
            pointerRoute = PointerRoute.NONE
        }
        Log.d(TAG, "focus cleared")
    }

    fun focusedSession(parentDisplayId: Int? = null): DesktopWindowSession? = synchronized(lock) {
        val session = focusedTarget?.get()?.takeIf { entries.containsKey(it) } ?: return@synchronized null
        val entry = entries[session] ?: return@synchronized null
        if (parentDisplayId == null || entry.displayId == parentDisplayId) session else null
    }

    fun focusSession(session: DesktopWindowSession, promote: Boolean = true) {
        if (synchronized(lock) { !entries.containsKey(session) }) return
        synchronized(lock) { focusedTarget = WeakReference(session) }
        if (promote) bringToFront(session)
    }

    fun unregister(session: DesktopWindowSession) {
        val interactiveCallback: ((Boolean) -> Unit)?
        synchronized(lock) {
            interactiveCallback = entries.remove(session)?.setOverlayInteractive
            if (pointerTarget?.get() === session) {
                pointerTarget = null
                pointerRoute = PointerRoute.NONE
            }
            if (focusedTarget?.get() === session) focusedTarget = null
        }
        // Never leave the shared full-display host in the interactive state
        // after its last interactive window is destroyed.
        interactiveCallback?.invoke(false)
        Log.d(TAG, "unregister sessionDisplay=${session.windowDisplayId()}")
    }

    private fun entriesAt(displayId: Int, x: Float, y: Float): List<Entry> = synchronized(lock) {
        entries.values.filter { it.displayId == displayId && it.containsWindow(x, y) }
    }

    private fun findContentAt(displayId: Int, x: Float, y: Float): Entry? = synchronized(lock) {
        entries.values.asSequence().filter { it.displayId == displayId && it.containsContent(x, y) }.lastOrNull()
    }

    private fun findChromeAt(displayId: Int, x: Float, y: Float): Entry? = synchronized(lock) {
        entries.values.asSequence()
            .filter { it.displayId == displayId && isChromePoint(it, x, y) }
            .lastOrNull()
    }

    private fun isChromePoint(entry: Entry, x: Float, y: Float): Boolean {
        if (entry.width <= 0 || entry.root.visibility != View.VISIBLE) return false
        val localX = x - entry.x
        val localY = y - entry.y
        if (localX < 0f || localX >= entry.root.width || localY < 0f || localY >= entry.root.height) return false
        return findTaggedChildAt(entry.root, localX, localY)
    }

    private fun findTaggedChildAt(view: View, x: Float, y: Float): Boolean {
        if (view.visibility != View.VISIBLE) return false
        if (view.tag == DesktopWindowOverlayHost.CHROME_TOUCH_TAG) {
            return x >= 0f && y >= 0f && x < view.width && y < view.height
        }
        if (view is ViewGroup) {
            for (i in view.childCount - 1 downTo 0) {
                val child = view.getChildAt(i)
                val left = child.left.toFloat()
                val top = child.top.toFloat()
                val right = child.right.toFloat()
                val bottom = child.bottom.toFloat()
                if (x >= left && x < right && y >= top && y < bottom &&
                    findTaggedChildAt(child, x - left, y - top)
                ) return true
            }
        }
        return false
    }

    private fun dispatchChrome(entry: Entry, event: MotionEvent): Boolean {
        val copy = MotionEvent.obtain(event)
        copy.offsetLocation(-entry.x.toFloat(), -entry.y.toFloat())
        return try {
            entry.root.dispatchTouchEvent(copy)
        } finally {
            copy.recycle()
        }
    }

    suspend fun injectKeyEvent(event: android.view.KeyEvent): Boolean =
        focusedSession()?.injectKeyEvent(event)?.getOrDefault(false) ?: false

    suspend fun injectKeyCode(keyCode: Int): Boolean =
        focusedSession()?.injectKeyCode(keyCode)?.getOrDefault(false) ?: false

    private fun updateOverlayInteractivity(displayId: Int, x: Float, y: Float): Boolean {
        val target = synchronized(lock) {
            entries.values.asSequence()
                .filter { it.displayId == displayId && it.containsWindow(x, y) }
                .lastOrNull()
        }
        // Only one desktop overlay host exists per DesktopShell display, but
        // each window supplies the same callback. The callback is deliberately
        // driven by cursor geometry, not by focus, so moving back to the
        // fullscreen app immediately restores FLAG_NOT_TOUCHABLE.
        synchronized(lock) {
            entries.values
                .filter { it.displayId == displayId }
                .forEach { it.setOverlayInteractive(it === target) }
        }
        return target != null
    }

    suspend fun injectPointer(
        displayId: Int,
        action: Int,
        x: Float,
        y: Float,
        actionButton: Int,
        buttons: Int,
        pointerId: Long,
        fallbackWidth: Int,
        fallbackHeight: Int,
        latch: Boolean,
    ): Boolean {
        // Toggle the real WindowManager flag before the event is allowed to
        // fall through to the fullscreen application. When the cursor is over
        // a freeform window, removing FLAG_NOT_TOUCHABLE is also what makes
        // the overlay render as an opaque window on the target device.
        updateOverlayInteractivity(displayId, x, y)
        val target: Entry?
        val route: PointerRoute
        synchronized(lock) {
            val latched = pointerTarget?.get()?.let { entries[it] }
            if (latch && action == MotionEvent.ACTION_DOWN) {
                val chrome = findChromeAt(displayId, x, y)
                target = chrome ?: findContentAt(displayId, x, y)
                route = if (chrome != null) PointerRoute.CHROME else if (target != null) PointerRoute.CONTENT else PointerRoute.NONE
            } else if (latch && latched != null && action != MotionEvent.ACTION_HOVER_MOVE && action != MotionEvent.ACTION_HOVER_ENTER) {
                target = latched
                route = pointerRoute
            } else {
                val content = findContentAt(displayId, x, y)
                target = content
                route = if (content != null) PointerRoute.CONTENT else PointerRoute.NONE
            }
        }

        if (target == null) {
            if (action == MotionEvent.ACTION_DOWN) clearFocus()
            return false
        }

        if (action == MotionEvent.ACTION_DOWN) {
            synchronized(lock) {
                pointerTarget = WeakReference(target.session)
                focusedTarget = WeakReference(target.session)
                pointerRoute = route
            }
            target.promoteVisual.invoke()
            synchronized(lock) {
                val e = entries.remove(target.session)
                if (e != null) entries[target.session] = e
            }
        }

								if (route == PointerRoute.CHROME) {
												val props = MotionEvent.PointerProperties()
												props.id = 0
												props.toolType = MotionEvent.TOOL_TYPE_MOUSE

												val coords = MotionEvent.PointerCoords()
												coords.x = x
												coords.y = y
												coords.pressure = 1.0f
												coords.size = 1.0f

												val now = MotionEvent.obtain(
																android.os.SystemClock.uptimeMillis(), // downTime
																android.os.SystemClock.uptimeMillis(), // eventTime
																action,
																1, // pointerCount
																arrayOf(props),
																arrayOf(coords),
																0, // metaState
																buttons, // buttonState
																1.0f, // xPrecision
																1.0f, // yPrecision
																0, // deviceId
																0, // edgeFlags
																android.view.InputDevice.SOURCE_MOUSE, // source
																0 // flags
												)

												try {
																return dispatchChrome(target, now)
												} finally {
																now.recycle()
																if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
																				synchronized(lock) { pointerTarget = null; pointerRoute = PointerRoute.NONE }
																}
												}
								}
        val (mappedX, mappedY) = target.mapPoint(x, y)
        val (iw, ih) = target.session.inputCoordinateSize(target.width, target.height)
        val result = target.session.injectMouseEvent(action, pointerId, mappedX, mappedY, iw, ih, actionButton, buttons)
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            synchronized(lock) { pointerTarget = null; pointerRoute = PointerRoute.NONE }
        }
        return result.getOrDefault(false)
    }

    suspend fun injectScroll(
        displayId: Int,
        x: Float,
        y: Float,
        hScroll: Float,
        vScroll: Float,
        buttons: Int,
        fallbackWidth: Int,
        fallbackHeight: Int,
    ): Boolean {
        updateOverlayInteractivity(displayId, x, y)
        val target = findContentAt(displayId, x, y) ?: return false
        val (mappedX, mappedY) = target.mapPoint(x, y)
        val (iw, ih) = target.session.inputCoordinateSize(target.width, target.height)
        return target.session.injectScrollEvent(mappedX, mappedY, iw, ih, hScroll, vScroll, buttons).getOrDefault(false)
    }
}
