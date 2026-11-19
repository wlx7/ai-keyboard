package app.aikb.ime.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * 九宫格 keyboard: side list (pinyin choices / punctuation) | 3×3 letter grid | function column.
 *
 *   [list] [1 分词] [2 ABC] [3 DEF]  [⌫]
 *   [list] [4 GHI ] [5 JKL] [6 MNO]  [重输]
 *   [list] [7 PQRS] [8 TUV] [9 WXYZ] [中/英]
 *   [123 ] [emoji ] [   space    ]   [return]
 *
 * Same outer size and colours as [KeyboardView] so switching layouts doesn't jump.
 */
@SuppressLint("ViewConstructor")
class T9View(context: Context, private val listener: Listener) : View(context) {

    interface Listener {
        fun onT9Digit(digit: Char)
        fun onT9Separator()
        fun onT9Side(index: Int)
        fun onT9Clear()
        fun onFunction(key: Key)
        fun onDeleteDown(repeat: Boolean)
        fun onKeyDownFeedback(key: Key)
    }

    private enum class Kind { DIGIT, SEPARATOR, DELETE, CLEAR, LANG, TO_SYMBOLS, EMOJI, SPACE, ENTER }
    private class Btn(val kind: Kind, val label: String, val digit: Char = ' ', val rect: RectF = RectF())

    private val dp = resources.displayMetrics.density
    private val sp = resources.displayMetrics.scaledDensity

    var theme: KbTheme = KbTheme.of(context)
        set(v) { field = v; invalidate() }
    var prefs: KeyboardPrefs = KeyboardPrefs()
        set(v) {
            val hChanged = field.heightScale != v.heightScale
            field = v
            if (hChanged) requestLayout()
            layoutRects(width)
            invalidate()
        }
    var chinese = true
        set(v) { field = v; invalidate() }
    var enterLabel = "换行"
        set(v) { field = v; invalidate() }
    var enterAccent = false
        set(v) { field = v; invalidate() }
    var spaceLabel = "九宫格"
        set(v) { field = v; invalidate() }

    /** Items of the side column: pinyin choices while composing, punctuation otherwise. */
    var sideItems: List<String> = emptyList()
        set(v) { field = v; sideScroll = 0f; invalidate() }
    var sideHighlightsFirst = false

    private val topPad get() = 6f * dp
    private val bottomPad get() = 5f * dp
    private val rowPitch get() = 53.5f * dp * prefs.heightScale
    private val gap get() = 5.5f * dp
    private val radius get() = prefs.keyRadiusDp * dp
    private val sideItemH get() = 39f * dp * prefs.heightScale.coerceIn(0.88f, 1.15f)

    private val buttons = listOf(
        Btn(Kind.SEPARATOR, "分词"), Btn(Kind.DIGIT, "ABC", '2'), Btn(Kind.DIGIT, "DEF", '3'), Btn(Kind.DELETE, ""),
        Btn(Kind.DIGIT, "GHI", '4'), Btn(Kind.DIGIT, "JKL", '5'), Btn(Kind.DIGIT, "MNO", '6'), Btn(Kind.CLEAR, "重输"),
        Btn(Kind.DIGIT, "PQRS", '7'), Btn(Kind.DIGIT, "TUV", '8'), Btn(Kind.DIGIT, "WXYZ", '9'), Btn(Kind.LANG, "中/英"),
        Btn(Kind.TO_SYMBOLS, "123"), Btn(Kind.EMOJI, ""), Btn(Kind.SPACE, ""), Btn(Kind.ENTER, ""),
    )
    private val side = RectF()
    private var sideScroll = 0f

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val path = Path()
    private val handler = Handler(Looper.getMainLooper())

    // touch state (single pointer is enough for a 9-key pad)
    private var down: Btn? = null
    private var downInSide = false
    private var sideDragging = false
    private var downY = 0f
    private var lastY = 0f
    private var pressedSide = -1
    private var repeats = 0
    private val repeat: Runnable = object : Runnable {
        override fun run() {
            repeats++
            listener.onDeleteDown(true)
            handler.postDelayed(this, if (repeats > 15) 35L else 70L)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (topPad + 4 * rowPitch + bottomPad).toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = layoutRects(w)

    private fun layoutRects(w: Int) {
        if (w <= 0) return
        val unit = (w - 6 * dp) / 10f
        val x0 = 3 * dp
        val cols = floatArrayOf(x0, x0 + 1.9f * unit, x0 + 3.9f * unit, x0 + 5.9f * unit, x0 + 7.9f * unit, x0 + 10f * unit)
        val rp = rowPitch
        val gp = gap
        fun cell(c0: Int, c1: Int, r: Int) =
            RectF(cols[c0] + gp / 2, topPad + r * rp + gp * 0.8f, cols[c1] - gp / 2, topPad + (r + 1) * rp - gp * 0.8f)
        side.set(cell(0, 1, 0).left, cell(0, 1, 0).top, cell(0, 1, 2).right, cell(0, 1, 2).bottom)
        var i = 0
        for (r in 0..2) for (c in 1..4) buttons[i++].rect.set(cell(c, c + 1, r))
        buttons[12].rect.set(cell(0, 1, 3))
        buttons[13].rect.set(cell(1, 2, 3))
        buttons[14].rect.set(cell(2, 4, 3))
        buttons[15].rect.set(cell(4, 5, 3))
    }

    // ------------------------------------------------------------ drawing

    override fun onDraw(c: Canvas) {
        c.drawColor(theme.background)
        drawSide(c)
        for (b in buttons) drawButton(c, b, b === down)
    }

    private fun drawSide(c: Canvas) {
        if (theme.keyShadow != 0) {
            fill.color = theme.keyShadow
            c.drawRoundRect(side.left, side.top + 1.1f * dp, side.right, side.bottom + 1.2f * dp, radius, radius, fill)
        }
        fill.color = theme.funcKey
        c.drawRoundRect(side, radius, radius, fill)
        c.save()
        c.clipRect(side)
        text.textSize = 16 * sp
        val cellRect = RectF()
        sideItems.forEachIndexed { i, s ->
            val top = side.top + i * sideItemH - sideScroll
            if (top + sideItemH < side.top || top > side.bottom) return@forEachIndexed
            if (i == pressedSide) {
                fill.color = theme.funcKeyPressed
                c.drawRect(side.left, top, side.right, top + sideItemH, fill)
            }
            cellRect.set(side.left, top, side.right, top + sideItemH)
            KeyDraw.drawKeyText(c, s, cellRect, 16 * sp, theme.text, text, dp, padH = 3 * dp, bold = i == 0 && sideHighlightsFirst)
            fill.color = theme.divider
            c.drawRect(side.left + 8 * dp, top + sideItemH - 0.5f * dp, side.right - 8 * dp, top + sideItemH, fill)
        }
        c.restore()
    }

    private fun drawButton(c: Canvas, b: Btn, pressed: Boolean) {
        val rc = b.rect
        val func = b.kind !in setOf(Kind.DIGIT, Kind.SPACE, Kind.SEPARATOR)
        val accent = b.kind == Kind.ENTER && enterAccent
        val rad = radius
        if (theme.keyShadow != 0) {
            fill.color = theme.keyShadow
            c.drawRoundRect(rc.left, rc.top + 1.1f * dp, rc.right, rc.bottom + 1.2f * dp, rad, rad, fill)
        }
        fill.color = when {
            accent -> if (pressed) theme.funcKeyPressed else theme.accent
            func -> if (pressed) theme.funcKeyPressed else theme.funcKey
            else -> if (pressed) theme.keyPressed else theme.key
        }
        c.drawRoundRect(rc, rad, rad, fill)
        val fg = if (accent && !pressed) theme.accentText else theme.text
        val cx = rc.centerX(); val cy = rc.centerY()
        val u = minOf(sp, dp * 1.12f) * prefs.keyFontScale
        val iconS = minOf(rc.width(), rc.height()) * 0.50f
        when (b.kind) {
            Kind.DIGIT -> {
                label(c, b.digit.toString(), cx, rc.top + rc.height() * 0.27f, 10.5f * u, theme.textSecondary)
                label(c, b.label, cx, cy + rc.height() * 0.08f, 17.8f * u, fg)
            }
            Kind.DELETE -> drawDelete(c, cx, cy, iconS.coerceIn(16 * dp, 21 * dp), fg)
            Kind.EMOJI -> drawEmoji(c, cx, cy, iconS.coerceIn(16 * dp, 20 * dp), fg)
            Kind.SPACE -> KeyDraw.drawKeyText(c, spaceLabel, rc, 14.5f * u, fg, text, dp)
            Kind.ENTER -> KeyDraw.drawKeyText(c, enterLabel, rc, 14.5f * u, fg, text, dp, bold = accent)
            Kind.LANG -> KeyDraw.drawLangBadge(c, rc, chinese, fg, text, dp, sp * prefs.keyFontScale)
            else -> KeyDraw.drawKeyText(c, b.label, rc, 14.5f * u, fg, text, dp, padH = 3.5f * dp)
        }
    }

    private fun label(c: Canvas, s: String, cx: Float, cy: Float, size: Float, color: Int) {
        text.textSize = size; text.color = color
        val fm = text.fontMetrics
        c.drawText(s, cx, cy - (fm.ascent + fm.descent) / 2, text)
    }

    private fun drawDelete(c: Canvas, cx: Float, cy: Float, s: Float, color: Int) {
        path.reset()
        path.moveTo(cx - 0.55f * s, cy); path.lineTo(cx - 0.22f * s, cy - 0.36f * s)
        path.lineTo(cx + 0.5f * s, cy - 0.36f * s); path.lineTo(cx + 0.5f * s, cy + 0.36f * s)
        path.lineTo(cx - 0.22f * s, cy + 0.36f * s); path.close()
        stroke.color = color; stroke.strokeWidth = 1.6f * dp
        c.drawPath(path, stroke)
        val x0 = cx + 0.12f * s; val r = 0.15f * s
        c.drawLine(x0 - r, cy - r, x0 + r, cy + r, stroke); c.drawLine(x0 - r, cy + r, x0 + r, cy - r, stroke)
    }

    private fun drawEmoji(c: Canvas, cx: Float, cy: Float, s: Float, color: Int) {
        stroke.color = color; stroke.strokeWidth = 1.6f * dp
        c.drawCircle(cx, cy, s / 2, stroke)
        fill.color = color
        c.drawCircle(cx - 0.17f * s, cy - 0.1f * s, 0.06f * s, fill)
        c.drawCircle(cx + 0.17f * s, cy - 0.1f * s, 0.06f * s, fill)
        c.drawArc(cx - 0.25f * s, cy - 0.15f * s, cx + 0.25f * s, cy + 0.28f * s, 20f, 140f, false, stroke)
    }

    // ------------------------------------------------------------ touch

    private fun buttonAt(x: Float, y: Float): Btn? {
        // generous hit areas: nearest button in the touched row band
        buttons.firstOrNull { RectF(it.rect).apply { inset(-gap / 2, -gap) }.contains(x, y) }?.let { return it }
        return null
    }

    private fun maxSideScroll() = maxOf(0f, sideItems.size * sideItemH - side.height())

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downY = e.y; lastY = e.y; sideDragging = false
                downInSide = RectF(side).apply { inset(-gap / 2, -gap / 2) }.contains(e.x, e.y)
                if (downInSide) {
                    pressedSide = ((e.y - side.top + sideScroll) / sideItemH).toInt().takeIf { it in sideItems.indices } ?: -1
                } else {
                    down = buttonAt(e.x, e.y)
                    down?.let { b ->
                        listener.onKeyDownFeedback(Key(if (b.kind == Kind.DELETE) KeyType.DELETE else KeyType.CHAR))
                        if (b.kind == Kind.DELETE) { repeats = 0; listener.onDeleteDown(false); handler.postDelayed(repeat, 400) }
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> if (downInSide) {
                if (!sideDragging && abs(e.y - downY) > 8 * dp) { sideDragging = true; pressedSide = -1 }
                if (sideDragging) sideScroll = (sideScroll - (e.y - lastY)).coerceIn(0f, maxSideScroll())
                lastY = e.y
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(repeat)
                if (downInSide) {
                    if (!sideDragging && pressedSide >= 0) listener.onT9Side(pressedSide)
                } else down?.let { fire(it) }
                down = null; pressedSide = -1
            }
            MotionEvent.ACTION_CANCEL -> { handler.removeCallbacks(repeat); down = null; pressedSide = -1 }
        }
        invalidate()
        return true
    }

    private fun fire(b: Btn) {
        when (b.kind) {
            Kind.DIGIT -> listener.onT9Digit(b.digit)
            Kind.SEPARATOR -> listener.onT9Separator()
            Kind.CLEAR -> listener.onT9Clear()
            Kind.DELETE -> Unit // fired on down
            Kind.LANG -> listener.onFunction(Key(KeyType.LANG))
            Kind.TO_SYMBOLS -> listener.onFunction(Key(KeyType.TO_SYMBOLS))
            Kind.EMOJI -> listener.onFunction(Key(KeyType.EMOJI))
            Kind.SPACE -> listener.onFunction(Key(KeyType.SPACE, " "))
            Kind.ENTER -> listener.onFunction(Key(KeyType.ENTER))
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        handler.removeCallbacks(repeat)
    }
}
