package com.ynk.virtualdisplay.ui.display

import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Software cursor state used by desktop mode.
 *
 * Android's system mouse pointer is a separate SurfaceFlinger layer and is not
 * included in the H.264 screen capture. The WebRTC browser therefore needs a
 * lightweight software cursor overlay. This object is the process-local source
 * of truth for that overlay.
 */
object DesktopCursorState {
    data class Snapshot(
        val visible: Boolean,
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
        val dpi: Int,
    )

    private val visible = AtomicBoolean(false)
    private val x = AtomicInteger(960)
    private val y = AtomicInteger(540)
    private val width = AtomicInteger(1920)
    private val height = AtomicInteger(1080)
    private val dpi = AtomicInteger(160)
    private val changeListeners = CopyOnWriteArraySet<() -> Unit>()

    fun addChangeListener(listener: () -> Unit) {
        changeListeners.add(listener)
    }

    fun removeChangeListener(listener: () -> Unit) {
        changeListeners.remove(listener)
    }

    private fun notifyChanged() {
        changeListeners.forEach { listener ->
            runCatching { listener() }
        }
    }

    fun reset(w: Int = 1920, h: Int = 1080, displayDpi: Int = 160) {
        val safeW = w.coerceAtLeast(1)
        val safeH = h.coerceAtLeast(1)
        val newX = safeW / 2
        val newY = safeH / 2
        val newDpi = displayDpi.coerceAtLeast(1)
        val changed = width.get() != safeW || height.get() != safeH ||
            dpi.get() != newDpi || x.get() != newX || y.get() != newY || !visible.get()
        width.set(safeW)
        height.set(safeH)
        dpi.set(newDpi)
        x.set(newX)
        y.set(newY)
        visible.set(true)
        if (changed) notifyChanged()
    }

    /**
     * Update the coordinate space without jumping the visible cursor to the
     * center. Video config callbacks can be emitted again after reconnects or
     * Surface rebinding even when the user has not asked to reset the pointer.
     * Keep its relative location across resolution changes and preserve the
     * last location when the overlay is made visible again.
     */
    fun showPreservingPosition(w: Int, h: Int, displayDpi: Int = dpi.get()) {
        val safeW = w.coerceAtLeast(1)
        val safeH = h.coerceAtLeast(1)
        val oldW = width.get().coerceAtLeast(1)
        val oldH = height.get().coerceAtLeast(1)
        val newDpi = displayDpi.coerceAtLeast(1)
        val newX = (x.get().toFloat() / oldW * safeW).toInt().coerceIn(0, safeW)
        val newY = (y.get().toFloat() / oldH * safeH).toInt().coerceIn(0, safeH)
        val changed = width.get() != safeW || height.get() != safeH ||
            dpi.get() != newDpi || x.get() != newX || y.get() != newY || !visible.get()
        width.set(safeW)
        height.set(safeH)
        dpi.set(newDpi)
        x.set(newX)
        y.set(newY)
        visible.set(true)
        if (changed) notifyChanged()
    }

    /** Update the canvas dimensions while keeping the current normalized pointer position. */
    fun resizePreservingPosition(w: Int, h: Int, displayDpi: Int = dpi.get()) =
        showPreservingPosition(w, h, displayDpi)

    fun update(cursorX: Float, cursorY: Float, w: Int, h: Int, displayDpi: Int = dpi.get()) {
        val safeW = w.coerceAtLeast(1)
        val safeH = h.coerceAtLeast(1)
        val newDpi = displayDpi.coerceAtLeast(1)
        val newX = cursorX.coerceIn(0f, safeW.toFloat()).toInt()
        val newY = cursorY.coerceIn(0f, safeH.toFloat()).toInt()
        val changed = width.get() != safeW || height.get() != safeH ||
            dpi.get() != newDpi || x.get() != newX || y.get() != newY || !visible.get()
        width.set(safeW)
        height.set(safeH)
        dpi.set(newDpi)
        x.set(newX)
        y.set(newY)
        visible.set(true)
        if (changed) notifyChanged()
    }

    fun hide() {
        if (visible.getAndSet(false)) {
            notifyChanged()
        }
    }

    fun snapshot(): Snapshot = Snapshot(
        visible = visible.get(),
        x = x.get(),
        y = y.get(),
        width = width.get(),
        height = height.get(),
        dpi = dpi.get(),
    )

    fun json(): String = "{\"visible\":${visible.get()},\"x\":${x.get()},\"y\":${y.get()},\"width\":${width.get()},\"height\":${height.get()},\"dpi\":${dpi.get()}}"
}
