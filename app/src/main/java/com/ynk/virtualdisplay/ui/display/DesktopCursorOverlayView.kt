package com.ynk.virtualdisplay.ui.display

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.util.AttributeSet
import android.view.View

/**
 * Software mouse cursor rendered in the application's own UI layer.
 *
 * This is intentionally above VideoSurfaceView so the cursor remains visible
 * on the phone while the same absolute cursor position is injected into the
 * virtual display. Its physical size follows the virtual display DPI rather
 * than the phone's density.
 */
class DesktopCursorOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFFFFFFFF.toInt()
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = 0xFF000000.toInt()
    }
    private val path = Path()

    private var targetView: VideoSurfaceView? = null
    private val cursorChangedListener: () -> Unit = { postInvalidateOnAnimation() }

    init {
        setWillNotDraw(false)
        isClickable = false
        isFocusable = false
        // Keep the overlay transparent and let touches fall through.
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
    }

    fun attachToVideoView(view: VideoSurfaceView) {
        targetView = view
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        DesktopCursorState.addChangeListener(cursorChangedListener)
        postInvalidateOnAnimation()
    }

    override fun onDetachedFromWindow() {
        DesktopCursorState.removeChangeListener(cursorChangedListener)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val state = DesktopCursorState.snapshot()
        val target = targetView ?: return
        if (!state.visible || !target.isShown || state.width <= 0 || state.height <= 0 ||
            target.width <= 0 || target.height <= 0
        ) {
            return
        }

        // VideoSurfaceView is itself the fit-center rectangle. Convert the
        // virtual-display coordinate into this view's coordinates, then into
        // the overlay's coordinate space.
        val scaleX = target.width.toFloat() / state.width.toFloat()
        val scaleY = target.height.toFloat() / state.height.toFloat()
        val x = target.left + state.x * scaleX
        val y = target.top + state.y * scaleY

        // 24px at 160dpi, scaling linearly with the virtual display DPI.
        // This is independent of the phone density.
        val size = 24f * (state.dpi.coerceAtLeast(1) / 160f)
            .coerceIn(0.75f, 4.0f)

        path.reset()
        path.moveTo(x, y)
        path.lineTo(x, y + size * 1.48f)
        path.lineTo(x + size * 0.38f, y + size * 1.10f)
        path.lineTo(x + size * 0.72f, y + size * 1.92f)
        path.lineTo(x + size * 0.98f, y + size * 1.78f)
        path.lineTo(x + size * 0.65f, y + size * 0.96f)
        path.lineTo(x + size * 1.05f, y + size * 0.98f)
        path.close()

        canvas.drawPath(path, strokePaint)
        canvas.drawPath(path, fillPaint)
    }

    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        // Never steal touches from the underlying video/touchpad surface.
        return false
    }
}
