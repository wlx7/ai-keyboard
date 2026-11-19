package app.aikb.ime.keyboard

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF

/**
 * Shared typography and crisp vector-icon helpers for KeyboardView, T9View, CandidateBar and ToolPanel.
 */
object KeyDraw {
    private val tmpBounds = Rect()

    fun isFullwidthPunct(ch: Char): Boolean {
        val code = ch.code
        return code in 0x3001..0x303F || code in 0xFF01..0xFF65 || ch in "“”‘’—…·"
    }

    private fun isLowPunct(ch: Char): Boolean = ch in "，。、,."

    /**
     * Draws a key label guaranteed to fit within [rc] horizontally (with [padH] side margin),
     * optically centering fullwidth CJK punctuation so `，` and `。` never hug the bottom-left corner.
     */
    fun drawKeyText(
        c: Canvas,
        s: String,
        rc: RectF,
        maxSize: Float,
        color: Int,
        paint: Paint,
        dp: Float,
        padH: Float = 4.5f * dp,
        bold: Boolean = false,
    ) {
        if (s.isEmpty()) return
        paint.color = color
        paint.isFakeBoldText = bold
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = maxSize

        val maxW = (rc.width() - 2 * padH).coerceAtLeast(8 * dp)
        val measured = paint.measureText(s)
        if (measured > maxW && measured > 0f) {
            paint.textSize = (maxSize * (maxW / measured)).coerceAtLeast(9.5f * dp)
        }

        val cx = rc.centerX()
        val cy = rc.centerY()
        val fm = paint.fontMetrics
        val yStd = cy - (fm.ascent + fm.descent) / 2f

        if (s.length == 1 && (isFullwidthPunct(s[0]) || isLowPunct(s[0]))) {
            paint.textAlign = Paint.Align.LEFT
            paint.getTextBounds(s, 0, 1, tmpBounds)
            val x = if (isFullwidthPunct(s[0])) {
                cx - (tmpBounds.left + tmpBounds.right) / 2f
            } else {
                cx - measured / 2f
            }
            val yInk = cy - (tmpBounds.top + tmpBounds.bottom) / 2f
            val y = if (isLowPunct(s[0])) {
                yStd * 0.42f + yInk * 0.58f + 1.8f * dp
            } else {
                yInk
            }
            c.drawText(s, x, y, paint)
            paint.textAlign = Paint.Align.CENTER
        } else {
            c.drawText(s, cx, yStd, paint)
        }
        paint.isFakeBoldText = false
    }

    /**
     * Compact WeChat/iOS-style language toggle badge: bold primary active glyph + small subscript
     * secondary glyph (e.g. `中/英` or `英/中`). Fits comfortably even on narrow 32dp keys.
     */
    fun drawLangBadge(c: Canvas, rc: RectF, chinese: Boolean, color: Int, paint: Paint, dp: Float, sp: Float) {
        val u = minOf(sp, dp * 1.12f)
        val mainStr = if (chinese) "中" else "英"
        val subStr = if (chinese) "/英" else "/中"

        var mainSize = 14.5f * u
        var subSize = 10.0f * u

        paint.isFakeBoldText = true
        paint.textSize = mainSize
        var wMain = paint.measureText(mainStr)

        paint.isFakeBoldText = false
        paint.textSize = subSize
        var wSub = paint.measureText(subStr)

        val gap = 0.8f * dp
        val maxW = (rc.width() - 6f * dp).coerceAtLeast(16 * dp)
        val total = wMain + gap + wSub
        if (total > maxW && total > 0f) {
            val scale = maxW / total
            mainSize *= scale
            subSize *= scale
            paint.isFakeBoldText = true; paint.textSize = mainSize; wMain = paint.measureText(mainStr)
            paint.isFakeBoldText = false; paint.textSize = subSize; wSub = paint.measureText(subStr)
        }

        val startX = rc.centerX() - (wMain + gap + wSub) / 2f
        val cy = rc.centerY()

        paint.textAlign = Paint.Align.LEFT
        paint.isFakeBoldText = true
        paint.textSize = mainSize
        paint.color = color
        val fmMain = paint.fontMetrics
        val mainBaseline = cy - (fmMain.ascent + fmMain.descent) / 2f - 0.6f * dp
        c.drawText(mainStr, startX, mainBaseline, paint)

        paint.isFakeBoldText = false
        paint.textSize = subSize
        paint.color = (color and 0x00FFFFFF) or 0x78000000
        c.drawText(subStr, startX + wMain + gap, mainBaseline + 1.2f * dp, paint)

        paint.textAlign = Paint.Align.CENTER
    }

    // -------------------------------------------------------- crisp vector icons

    /** 2x2 rounded-square grid icon (WeChat-style quick panel launcher). */
    fun drawGridIcon(c: Canvas, cx: Float, cy: Float, s: Float, color: Int, fill: Paint, stroke: Paint, dp: Float, active: Boolean) {
        val half = s * 0.44f
        val gap = s * 0.10f
        val r = 1.8f * dp
        val boxes = arrayOf(
            RectF(cx - half, cy - half, cx - gap, cy - gap),
            RectF(cx + gap, cy - half, cx + half, cy - gap),
            RectF(cx - half, cy + gap, cx - gap, cy + half),
            RectF(cx + gap, cy + gap, cx + half, cy + half),
        )
        if (active) {
            fill.color = color
            for (b in boxes) c.drawRoundRect(b, r, r, fill)
        } else {
            stroke.color = color
            stroke.strokeWidth = 1.6f * dp
            for (b in boxes) c.drawRoundRect(b, r, r, stroke)
        }
    }

    /** Pen / stylus handwriting icon. */
    fun drawHandwritingIcon(c: Canvas, cx: Float, cy: Float, s: Float, color: Int, stroke: Paint, path: Path, dp: Float) {
        stroke.color = color
        stroke.strokeWidth = 1.65f * dp
        val h = s * 0.44f
        path.reset()
        // Tilted pencil body + nib
        path.moveTo(cx - h * 0.75f, cy + h * 0.75f)
        path.lineTo(cx - h * 0.55f, cy + h * 0.15f)
        path.lineTo(cx + h * 0.38f, cy - h * 0.78f)
        path.lineTo(cx + h * 0.78f, cy - h * 0.38f)
        path.lineTo(cx - h * 0.15f, cy + h * 0.55f)
        path.close()
        c.drawPath(path, stroke)
        // Baseline stroke curve
        c.drawLine(cx - h * 0.2f, cy + h * 0.78f, cx + h * 0.8f, cy + h * 0.78f, stroke)
    }

    /** Mini keyboard icon. */
    fun drawKeyboardIcon(c: Canvas, cx: Float, cy: Float, s: Float, color: Int, fill: Paint, stroke: Paint, dp: Float) {
        stroke.color = color
        stroke.strokeWidth = 1.6f * dp
        val w = s * 0.52f
        val h = s * 0.36f
        c.drawRoundRect(cx - w, cy - h, cx + w, cy + h, 2.5f * dp, 2.5f * dp, stroke)
        fill.color = color
        val dot = 1.15f * dp
        for (dx in floatArrayOf(-w * 0.52f, 0f, w * 0.52f)) {
            c.drawCircle(cx + dx, cy - h * 0.28f, dot, fill)
        }
        c.drawRoundRect(cx - w * 0.45f, cy + h * 0.2f, cx + w * 0.45f, cy + h * 0.48f, dp, dp, fill)
    }

    /** Clipboard icon. */
    fun drawClipboardIcon(c: Canvas, cx: Float, cy: Float, s: Float, color: Int, stroke: Paint, dp: Float) {
        stroke.color = color
        stroke.strokeWidth = 1.6f * dp
        val w = s * 0.38f
        val h = s * 0.46f
        c.drawRoundRect(cx - w, cy - h + 2 * dp, cx + w, cy + h, 2.5f * dp, 2.5f * dp, stroke)
        c.drawRoundRect(cx - w * 0.48f, cy - h - 0.5f * dp, cx + w * 0.48f, cy - h + 3.5f * dp, 1.5f * dp, 1.5f * dp, stroke)
        c.drawLine(cx - w * 0.45f, cy - 0.5f * dp, cx + w * 0.45f, cy - 0.5f * dp, stroke)
        c.drawLine(cx - w * 0.45f, cy + h * 0.42f, cx + w * 0.25f, cy + h * 0.42f, stroke)
    }

    /** Microphone icon. */
    fun drawMicIcon(c: Canvas, cx: Float, cy: Float, s: Float, color: Int, stroke: Paint, dp: Float) {
        stroke.color = color
        stroke.strokeWidth = 1.65f * dp
        val mw = s * 0.20f
        val mh = s * 0.34f
        c.drawRoundRect(cx - mw, cy - mh - 1.5f * dp, cx + mw, cy + mh * 0.35f, mw, mw, stroke)
        val aw = s * 0.36f
        c.drawArc(cx - aw, cy - mh * 0.55f, cx + aw, cy + mh * 0.75f, 0f, 180f, false, stroke)
        c.drawLine(cx, cy + mh * 0.75f, cx, cy + mh * 1.25f, stroke)
        c.drawLine(cx - mw * 0.9f, cy + mh * 1.25f, cx + mw * 0.9f, cy + mh * 1.25f, stroke)
    }

    /** Gear / settings icon. */
    fun drawGearIcon(c: Canvas, cx: Float, cy: Float, s: Float, color: Int, stroke: Paint, dp: Float) {
        stroke.color = color
        stroke.strokeWidth = 1.65f * dp
        val rOuter = s * 0.32f
        val rInner = s * 0.15f
        c.drawCircle(cx, cy, rInner, stroke)
        c.drawCircle(cx, cy, rOuter, stroke)
        for (i in 0 until 6) {
            val a = Math.toRadians((i * 60).toDouble())
            val cos = kotlin.math.cos(a).toFloat()
            val sin = kotlin.math.sin(a).toFloat()
            c.drawLine(cx + cos * rOuter, cy + sin * rOuter, cx + cos * (s * 0.46f), cy + sin * (s * 0.46f), stroke)
        }
    }

    /** Globe / switch system IME icon. */
    fun drawGlobeIcon(c: Canvas, cx: Float, cy: Float, s: Float, color: Int, stroke: Paint, dp: Float) {
        stroke.color = color
        stroke.strokeWidth = 1.55f * dp
        val r = s * 0.44f
        c.drawCircle(cx, cy, r, stroke)
        c.drawOval(cx - r * 0.48f, cy - r, cx + r * 0.48f, cy + r, stroke)
        c.drawLine(cx - r, cy, cx + r, cy, stroke)
    }

    /** Chevron icon (down when dir = 1, up when dir = -1). */
    fun drawChevron(c: Canvas, cx: Float, cy: Float, s: Float, dir: Int, color: Int, stroke: Paint, dp: Float) {
        stroke.color = color
        stroke.strokeWidth = 1.85f * dp
        c.drawLine(cx - s, cy - dir * s * 0.48f, cx, cy + dir * s * 0.48f, stroke)
        c.drawLine(cx, cy + dir * s * 0.48f, cx + s, cy - dir * s * 0.48f, stroke)
    }

    /** Photo / screenshot landscape frame icon. */
    fun drawImageIcon(c: Canvas, cx: Float, cy: Float, s: Float, color: Int, fill: Paint, stroke: Paint, path: Path, dp: Float) {
        stroke.color = color
        stroke.strokeWidth = 1.6f * dp
        val w = s * 0.46f
        val h = s * 0.38f
        c.drawRoundRect(cx - w, cy - h, cx + w, cy + h, 2.5f * dp, 2.5f * dp, stroke)
        fill.color = color
        c.drawCircle(cx - w * 0.42f, cy - h * 0.35f, 1.5f * dp, fill)
        path.reset()
        path.moveTo(cx - w * 0.78f, cy + h * 0.68f)
        path.lineTo(cx - w * 0.18f, cy + h * 0.05f)
        path.lineTo(cx + w * 0.22f, cy + h * 0.45f)
        path.lineTo(cx + w * 0.48f, cy + h * 0.15f)
        path.lineTo(cx + w * 0.80f, cy + h * 0.68f)
        c.drawPath(path, stroke)
    }

    /** Four-corner resize / scale icon. */
    fun drawResizeIcon(c: Canvas, cx: Float, cy: Float, s: Float, color: Int, stroke: Paint, dp: Float) {
        stroke.color = color
        stroke.strokeWidth = 1.65f * dp
        val d = s * 0.40f
        val arm = s * 0.22f
        // Top-left & bottom-right corner brackets
        c.drawLine(cx - d, cy - d, cx - d + arm, cy - d, stroke)
        c.drawLine(cx - d, cy - d, cx - d, cy - d + arm, stroke)
        c.drawLine(cx + d, cy - d, cx + d - arm, cy - d, stroke)
        c.drawLine(cx + d, cy - d, cx + d, cy - d + arm, stroke)
        c.drawLine(cx - d, cy + d, cx - d + arm, cy + d, stroke)
        c.drawLine(cx - d, cy + d, cx - d, cy + d - arm, stroke)
        c.drawLine(cx + d, cy + d, cx + d - arm, cy + d, stroke)
        c.drawLine(cx + d, cy + d, cx + d, cy + d - arm, stroke)
    }
}
