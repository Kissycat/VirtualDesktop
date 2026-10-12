package com.ynk.virtualdisplay.ui.desktop

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import kotlin.math.max

internal enum class DockStatusKind {
    NOTIFICATIONS, AUDIO, BATTERY, WIFI, BLUETOOTH
}

/** Small vector status glyphs that remain crisp at any virtual-desktop density. */
internal class DockStatusIconView(
    context: Context,
    val kind: DockStatusKind,
) : View(context) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF0F3F8.toInt()
        strokeWidth = max(1.5f, density * 1.7f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        style = Paint.Style.STROKE
    }
    private var batteryLevel: Int = 100
    private val batteryTextOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xE5000000.toInt()
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val batteryTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        style = Paint.Style.FILL
    }

    fun setBatteryLevel(level: Int) {
        batteryLevel = level.coerceIn(0, 100)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        // Scale each glyph against its own actual path bounds. The speaker arcs
        // need more horizontal room than the battery, so do not shrink every glyph
        // to the speaker's maximum extent. Both axes are constrained to avoid clips.
        val (extentX, extentY) = when (kind) {
            DockStatusKind.NOTIFICATIONS -> 8.5f to 11f
            DockStatusKind.AUDIO -> 15f to 10f
            DockStatusKind.BATTERY -> 10.5f to 5.5f
            DockStatusKind.WIFI -> 11f to 13f
            DockStatusKind.BLUETOOTH -> 5.5f to 8.5f
        }
        val inset = max(1f, density)
        val horizontalScale = (width / 2f - inset).coerceAtLeast(0f) / extentX
        val verticalScale = (height / 2f - inset).coerceAtLeast(0f) / extentY
        val unit = minOf(density, horizontalScale, verticalScale).coerceAtLeast(0.1f)
        canvas.save()
        when (kind) {
            DockStatusKind.NOTIFICATIONS -> drawBell(canvas, cx, cy, unit)
            DockStatusKind.AUDIO -> drawSpeaker(canvas, cx, cy, unit)
            DockStatusKind.BATTERY -> drawBattery(canvas, cx, cy, unit)
            DockStatusKind.WIFI -> drawWifi(canvas, cx, cy, unit)
            DockStatusKind.BLUETOOTH -> drawBluetooth(canvas, cx, cy, unit)
        }
        canvas.restore()
    }

    private fun drawBell(canvas: Canvas, cx: Float, cy: Float, d: Float) {
        val path = Path().apply {
            moveTo(cx - 8 * d, cy + 5 * d)
            lineTo(cx - 6.5f * d, cy + 2 * d)
            lineTo(cx - 6.5f * d, cy - 2.5f * d)
            quadTo(cx - 6.5f * d, cy - 8 * d, cx, cy - 8 * d)
            quadTo(cx + 6.5f * d, cy - 8 * d, cx + 6.5f * d, cy - 2.5f * d)
            lineTo(cx + 6.5f * d, cy + 2 * d)
            lineTo(cx + 8 * d, cy + 5 * d)
            close()
        }
        canvas.drawPath(path, paint)
        canvas.drawLine(cx - 3 * d, cy + 8 * d, cx + 3 * d, cy + 8 * d, paint)
        canvas.drawCircle(cx, cy - 9.5f * d, 1.3f * d, paint)
    }

    private fun drawSpeaker(canvas: Canvas, cx: Float, cy: Float, d: Float) {
        val path = Path().apply {
            moveTo(cx - 9 * d, cy - 4 * d)
            lineTo(cx - 4 * d, cy - 4 * d)
            lineTo(cx + 2 * d, cy - 9 * d)
            lineTo(cx + 2 * d, cy + 9 * d)
            lineTo(cx - 4 * d, cy + 4 * d)
            lineTo(cx - 9 * d, cy + 4 * d)
            close()
        }
        canvas.drawPath(path, paint)
        canvas.drawArc(RectF(cx - 1 * d, cy - 7 * d, cx + 11 * d, cy + 7 * d), -55f, 110f, false, paint)
        canvas.drawArc(RectF(cx + 1 * d, cy - 10 * d, cx + 15 * d, cy + 10 * d), -48f, 96f, false, paint)
    }

    private fun drawBattery(canvas: Canvas, cx: Float, cy: Float, d: Float) {
        val body = RectF(cx - 9 * d, cy - 5.5f * d, cx + 8 * d, cy + 5.5f * d)
        canvas.drawRoundRect(body, 2 * d, 2 * d, paint)
        canvas.drawRoundRect(RectF(cx + 8 * d, cy - 2.3f * d, cx + 10.5f * d, cy + 2.3f * d), d, d, paint)
        val inset = 1.5f * d
        val fillWidth = (body.width() - inset * 2) * batteryLevel / 100f
        val fill = Paint(paint).apply { style = Paint.Style.FILL; alpha = 225 }
        canvas.drawRoundRect(
            RectF(body.left + inset, body.top + inset, body.left + inset + fillWidth, body.bottom - inset),
            d, d, fill
        )

        // Draw the percentage inside the body. A dark outline keeps it readable on
        // both the light battery fill and the unfilled portion of the dark Dock.
        val label = "$batteryLevel%"
        val textSize = 5.8f * d
        batteryTextPaint.textSize = textSize
        batteryTextOutlinePaint.textSize = textSize
        batteryTextOutlinePaint.strokeWidth = max(0.8f, 1.15f * d)
        val textX = cx - 0.5f * d
        val fontMetrics = batteryTextPaint.fontMetrics
        val baseline = cy - (fontMetrics.ascent + fontMetrics.descent) / 2f
        canvas.drawText(label, textX, baseline, batteryTextOutlinePaint)
        canvas.drawText(label, textX, baseline, batteryTextPaint)
    }

    private fun drawWifi(canvas: Canvas, cx: Float, cy: Float, d: Float) {
        canvas.drawArc(RectF(cx - 11 * d, cy - 9 * d, cx + 11 * d, cy + 13 * d), 225f, 90f, false, paint)
        canvas.drawArc(RectF(cx - 7.5f * d, cy - 5.5f * d, cx + 7.5f * d, cy + 9.5f * d), 225f, 90f, false, paint)
        canvas.drawArc(RectF(cx - 4 * d, cy - 2 * d, cx + 4 * d, cy + 6 * d), 225f, 90f, false, paint)
        val fill = Paint(paint).apply { style = Paint.Style.FILL }
        canvas.drawCircle(cx, cy + 7.5f * d, 1.7f * d, fill)
    }

    private fun drawBluetooth(canvas: Canvas, cx: Float, cy: Float, d: Float) {
        val path = Path().apply {
            moveTo(cx - 5.5f * d, cy - 6.5f * d)
            lineTo(cx + 4.5f * d, cy + 3.5f * d)
            lineTo(cx, cy + 8.5f * d)
            lineTo(cx, cy - 8.5f * d)
            lineTo(cx + 4.5f * d, cy - 3.5f * d)
            lineTo(cx - 5.5f * d, cy + 6.5f * d)
        }
        canvas.drawPath(path, paint)
    }
}
