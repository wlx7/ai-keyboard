package app.aikb.ime.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.roundToInt

/**
 * Live interactive keyboard size & position adjuster (`调节键盘大小与单手模式`), shown directly
 * over the translucent live keyboard so the user sees exact real-time changes without distortion:
 *   - Drag top bar / `[高度 - / +]` to adjust keyboard height (`75% .. 135%`)
 *   - Adjust width squeeze (`76% .. 100%`) and alignment (`靠左 / 居中 / 靠右`)
 *   - Adjust bottom elevation offset (`0dp .. 40dp`)
 */
@SuppressLint("ViewConstructor")
class ResizeOverlay(context: Context, private val listener: Listener) : View(context) {

    interface Listener {
        fun onResizeChanged(heightScale: Float, widthRatio: Float, align: Int, bottomOffsetDp: Int)
        fun onResizeReset()
        fun onResizeDone()
    }

    private val dp = resources.displayMetrics.density
    private val sp = resources.displayMetrics.scaledDensity

    var theme: KbTheme = KbTheme.of(context)
        set(v) { field = v; invalidate() }

    var heightScale = 1.00f
    var widthRatio = 1.00f
    var align = 0
    var bottomOffsetDp = 0

    private val btnRects = Array(10) { RectF() }
    private var draggingMode = 0 // 1 = height drag, 2 = bottom offset drag
    private var downY = 0f
    private var startVal = 0f
    private var pressedBtn = -1

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f * dp
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    override fun onDraw(c: Canvas) {
        val u = minOf(sp, dp * 1.12f)
        // Translucent scrim so the live underlying keyboard keys remain visible!
        c.drawColor(if (theme.isDark) 0xCC0F1117.toInt() else 0xCCF1F5F9.toInt())

        // Accent bounding frame + top drag handle
        val framePad = 4 * dp
        stroke.color = theme.accent
        c.drawRoundRect(framePad, framePad, width - framePad, height - framePad, 12 * dp, 12 * dp, stroke)
        fill.color = theme.accent
        c.drawRoundRect(width / 2f - 28 * dp, 3 * dp, width / 2f + 28 * dp, 8 * dp, 3 * dp, 3 * dp, fill)

        val cx = width / 2f
        text.color = theme.text
        text.isFakeBoldText = true
        text.textSize = 14f * u
        c.drawText(
            "高度 ${(heightScale * 100).roundToInt()}%  ·  宽度 ${(widthRatio * 100).roundToInt()}%  ·  底距 ${bottomOffsetDp}dp",
            cx,
            26 * dp,
            text,
        )
        text.isFakeBoldText = false

        val side = 12 * dp
        val gap = 8 * dp
        val rowH = ((height - 44 * dp) / 3.4f).coerceIn(32 * dp, 42 * dp)

        // Row 1: [高度 -] [高度 +] [宽度 -] [宽度 +]
        val r1Top = 36 * dp
        val w4 = (width - 2 * side - 3 * gap) / 4f
        val r1Labels = arrayOf("变矮 -", "变高 +", "变窄 -", "变宽 +")
        for (i in 0..3) {
            val l = side + i * (w4 + gap)
            btnRects[i].set(l, r1Top, l + w4, r1Top + rowH)
            drawChip(c, btnRects[i], r1Labels[i], active = false, down = pressedBtn == i, u = u)
        }

        // Row 2: [单手靠左] [居中全宽] [单手靠右] [底距 +4dp]
        val r2Top = r1Top + rowH + gap
        val r2Labels = arrayOf("← 靠左", "居中", "靠右 →", if (bottomOffsetDp >= 32) "底距清零" else "抬高底距")
        for (i in 0..3) {
            val idx = 4 + i
            val l = side + i * (w4 + gap)
            btnRects[idx].set(l, r2Top, l + w4, r2Top + rowH)
            val isAct = (i == 0 && align < 0 && widthRatio < 0.99f) ||
                (i == 1 && align == 0) ||
                (i == 2 && align > 0 && widthRatio < 0.99f)
            drawChip(c, btnRects[idx], r2Labels[i], active = isAct, down = pressedBtn == idx, u = u)
        }

        // Row 3: [恢复默认]  [ ✓ 完成保存 ]
        val r3Top = (height - rowH - 8 * dp).coerceAtLeast(r2Top + rowH + gap)
        val w2 = (width - 2 * side - gap) / 2f
        btnRects[8].set(side, r3Top, side + w2 * 0.75f, r3Top + rowH)
        btnRects[9].set(btnRects[8].right + gap, r3Top, width - side, r3Top + rowH)
        drawChip(c, btnRects[8], "恢复默认", active = false, down = pressedBtn == 8, u = u)
        drawChip(c, btnRects[9], "完成", active = true, down = pressedBtn == 9, u = u)
    }

    private fun drawChip(c: Canvas, rc: RectF, label: String, active: Boolean, down: Boolean, u: Float) {
        fill.color = when {
            active -> theme.accent
            down -> theme.keyPressed
            else -> theme.key
        }
        c.drawRoundRect(rc, 9 * dp, 9 * dp, fill)
        val fg = if (active) theme.accentText else theme.text
        KeyDraw.drawKeyText(c, label, rc, 13f * u, fg, text, dp, padH = 4 * dp, bold = active)
    }

    private fun hitBtn(x: Float, y: Float): Int {
        for (i in btnRects.indices) if (btnRects[i].contains(x, y)) return i
        return -1
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressedBtn = hitBtn(e.x, e.y)
                if (pressedBtn < 0 && e.y < 34 * dp) {
                    draggingMode = 1
                    downY = e.rawY
                    startVal = heightScale
                } else {
                    draggingMode = 0
                }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                if (draggingMode == 1) {
                    val deltaScale = -(e.rawY - downY) / (220f * dp)
                    heightScale = (startVal + deltaScale).coerceIn(0.75f, 1.35f)
                    listener.onResizeChanged(heightScale, widthRatio, align, bottomOffsetDp)
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                if (draggingMode == 0 && pressedBtn >= 0 && hitBtn(e.x, e.y) == pressedBtn) {
                    when (pressedBtn) {
                        0 -> heightScale = (heightScale - 0.05f).coerceIn(0.75f, 1.35f)
                        1 -> heightScale = (heightScale + 0.05f).coerceIn(0.75f, 1.35f)
                        2 -> {
                            widthRatio = (widthRatio - 0.05f).coerceIn(0.76f, 1.00f)
                            if (widthRatio < 0.99f && align == 0) align = 1
                        }
                        3 -> widthRatio = (widthRatio + 0.05f).coerceIn(0.76f, 1.00f)
                        4 -> { align = -1; if (widthRatio > 0.92f) widthRatio = 0.86f }
                        5 -> { align = 0 }
                        6 -> { align = 1; if (widthRatio > 0.92f) widthRatio = 0.86f }
                        7 -> bottomOffsetDp = if (bottomOffsetDp >= 32) 0 else (bottomOffsetDp + 8)
                        8 -> {
                            heightScale = 1.00f; widthRatio = 1.00f; align = 0; bottomOffsetDp = 0
                            listener.onResizeReset()
                            pressedBtn = -1; invalidate()
                            return true
                        }
                        9 -> {
                            listener.onResizeDone()
                            pressedBtn = -1; invalidate()
                            return true
                        }
                    }
                    listener.onResizeChanged(heightScale, widthRatio, align, bottomOffsetDp)
                }
                draggingMode = 0
                pressedBtn = -1
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                draggingMode = 0; pressedBtn = -1; invalidate()
            }
        }
        return true
    }
}
