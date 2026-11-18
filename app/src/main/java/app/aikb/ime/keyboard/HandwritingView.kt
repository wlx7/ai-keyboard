package app.aikb.ime.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import app.aikb.ime.engine.HandwritingEngine.Point
import kotlin.math.abs

/**
 * Dedicated handwriting input view with identical outer height to [KeyboardView] and [T9View]:
 *
 *   [punct] ┌────────────────────────┐ [⌫]
 *   [punct] │    手写区（单字/连写）   │ [重写]
 *   [punct] └────────────────────────┘ [键盘]
 *   [ 123 ] [ 😀 ] [   空格 / 选定   ] [完成]
 *
 * UX flow:
 *   - Strokes drawn within 850 ms accumulate (single-char strokes + continuous/overlapped DP).
 *   - After 850 ms idle, ink dims slightly to signal readiness: candidates stay visible to pick,
 *     or touching the canvas again auto-commits the top candidate and starts a fresh character.
 */
@SuppressLint("ViewConstructor")
class HandwritingView(context: Context, private val listener: Listener) : View(context) {

    interface Listener {
        fun onHandwritingStrokes(strokes: List<List<Point>>)
        fun onHandwritingAutoCommitPrevious()
        fun onHandwritingClear()
        fun onHandwritingPunct(punct: String)
        fun onHandwritingExitToKeyboard()
        fun onFunction(key: Key)
        fun onDeleteDown(repeat: Boolean)
        fun onKeyDownFeedback(key: Key)
    }

    private enum class BtnKind { DELETE, CLEAR, EXIT_KB, TO_SYMBOLS, EMOJI, SPACE, ENTER }
    private class Btn(val kind: BtnKind, val label: String = "", val rect: RectF = RectF())

    private val dp = resources.displayMetrics.density
    private val sp = resources.displayMetrics.scaledDensity

    var theme: KbTheme = KbTheme.of(context)
        set(v) { field = v; invalidate() }
    var enterLabel = "完成"
        set(v) { field = v; invalidate() }
    var enterAccent = false
        set(v) { field = v; invalidate() }
    var hasPendingComposition = false
        set(v) { field = v; invalidate() }

    private val punctItems = listOf("，", "。", "？", "！", "、", "：", "；", "…", "“", "”", "（", "）")

    private val topPad = 8 * dp
    private val bottomPad = 4 * dp
    private val rowPitch = 54 * dp
    private val gap = 6 * dp
    private val radius = 6f * dp
    private val sideItemH = 38 * dp

    private val sideRect = RectF()
    private val padRect = RectF()
    private val buttons = listOf(
        Btn(BtnKind.DELETE),
        Btn(BtnKind.CLEAR, "重写"),
        Btn(BtnKind.EXIT_KB, "拼音"),
        Btn(BtnKind.TO_SYMBOLS, "123"),
        Btn(BtnKind.EMOJI),
        Btn(BtnKind.SPACE),
        Btn(BtnKind.ENTER),
    )

    private val strokes = ArrayList<List<Point>>()
    private var currentStroke: MutableList<Point>? = null
    private var inkDimmed = false
    private var lastUpAt = 0L

    private var sideScroll = 0f
    private var downInPad = false
    private var downInSide = false
    private var sideDragging = false
    private var downY = 0f
    private var lastY = 0f
    private var pressedSide = -1
    private var downBtn: Btn? = null
    private var repeats = 0

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * dp
        pathEffect = DashPathEffect(floatArrayOf(4 * dp, 4 * dp), 0f)
    }
    private val inkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 4.2f * dp
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val path = Path()
    private val handler = Handler(Looper.getMainLooper())

    private val dimRunnable = Runnable {
        if (strokes.isNotEmpty() && currentStroke == null) {
            inkDimmed = true
            invalidate()
        }
    }

    private val deleteRepeat = object : Runnable {
        override fun run() {
            repeats++
            listener.onDeleteDown(true)
            handler.postDelayed(this, if (repeats > 15) 35L else 70L)
        }
    }

    val hasStrokes: Boolean get() = strokes.isNotEmpty() || currentStroke != null

    fun clearCanvas(notify: Boolean = false) {
        handler.removeCallbacks(dimRunnable)
        strokes.clear()
        currentStroke = null
        inkDimmed = false
        invalidate()
        if (notify) listener.onHandwritingClear()
    }

    fun undoLastStroke(): Boolean {
        if (strokes.isEmpty()) return false
        handler.removeCallbacks(dimRunnable)
        strokes.removeAt(strokes.lastIndex)
        inkDimmed = false
        if (strokes.isEmpty()) {
            listener.onHandwritingClear()
        } else {
            listener.onHandwritingStrokes(strokes.toList())
            handler.postDelayed(dimRunnable, AUTO_COMMIT_IDLE_MS)
        }
        invalidate()
        return true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (topPad + 4 * rowPitch + bottomPad).toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val unit = (w - 6 * dp) / 10f
        val x0 = 3 * dp
        val cLeft = x0 + 1.45f * unit
        val cRight = x0 + 8.15f * unit
        val xEnd = x0 + 10f * unit

        fun bandTop(r: Int) = topPad + r * rowPitch + gap * 0.8f
        fun bandBot(r: Int) = topPad + (r + 1) * rowPitch - gap * 0.8f

        sideRect.set(x0 + gap / 2, bandTop(0), cLeft - gap / 2, bandBot(2))
        padRect.set(cLeft + gap / 2, bandTop(0), cRight - gap / 2, bandBot(2))

        // Right column buttons (rows 0..2)
        for (r in 0..2) {
            buttons[r].rect.set(cRight + gap / 2, bandTop(r), xEnd - gap / 2, bandBot(r))
        }
        // Bottom row: [123] [emoji] [space] [enter]
        val bTop = bandTop(3); val bBot = bandBot(3)
        buttons[3].rect.set(x0 + gap / 2, bTop, cLeft - gap / 2, bBot)
        val emoRight = cLeft + 1.25f * unit
        buttons[4].rect.set(cLeft + gap / 2, bTop, emoRight - gap / 2, bBot)
        buttons[5].rect.set(emoRight + gap / 2, bTop, cRight - gap / 2, bBot)
        buttons[6].rect.set(cRight + gap / 2, bTop, xEnd - gap / 2, bBot)
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(theme.background)
        drawSidePunct(c)
        drawWritingPad(c)
        for (b in buttons) drawBtn(c, b, b === downBtn)
    }

    private fun drawSidePunct(c: Canvas) {
        fill.color = theme.keyShadow
        c.drawRoundRect(sideRect.left, sideRect.top + 1f * dp, sideRect.right, sideRect.bottom + 1f * dp, radius, radius, fill)
        fill.color = theme.funcKey
        c.drawRoundRect(sideRect, radius, radius, fill)
        c.save()
        c.clipRect(sideRect)
        val cell = RectF()
        punctItems.forEachIndexed { i, s ->
            val top = sideRect.top + i * sideItemH - sideScroll
            if (top + sideItemH < sideRect.top || top > sideRect.bottom) return@forEachIndexed
            if (i == pressedSide) {
                fill.color = theme.funcKeyPressed
                c.drawRect(sideRect.left, top, sideRect.right, top + sideItemH, fill)
            }
            cell.set(sideRect.left, top, sideRect.right, top + sideItemH)
            KeyDraw.drawKeyText(c, s, cell, 16 * sp, theme.text, text, dp, padH = 2 * dp)
            fill.color = theme.divider
            c.drawRect(sideRect.left + 6 * dp, top + sideItemH - 0.5f * dp, sideRect.right - 6 * dp, top + sideItemH, fill)
        }
        c.restore()
    }

    private fun drawWritingPad(c: Canvas) {
        fill.color = theme.keyShadow
        c.drawRoundRect(padRect.left, padRect.top + 1.2f * dp, padRect.right, padRect.bottom + 1.2f * dp, 8 * dp, 8 * dp, fill)
        fill.color = theme.key
        c.drawRoundRect(padRect, 8 * dp, 8 * dp, fill)

        // Subtle Tian-Zi-Ge guide crosshairs
        guidePaint.color = theme.divider
        val cx = padRect.centerX()
        val cy = padRect.centerY()
        c.drawLine(padRect.left + 12 * dp, cy, padRect.right - 12 * dp, cy, guidePaint)
        c.drawLine(cx, padRect.top + 12 * dp, cx, padRect.bottom - 12 * dp, guidePaint)

        if (strokes.isEmpty() && currentStroke == null) {
            val u = minOf(sp, dp * 1.12f)
            text.textSize = 14 * u
            text.color = (theme.textSecondary and 0x00FFFFFF) or 0x66000000
            val fm = text.fontMetrics
            c.drawText("手写区域 · 支持连写与叠写", cx, cy - (fm.ascent + fm.descent) / 2f, text)
            return
        }

        c.save()
        c.clipRect(padRect)
        inkPaint.color = if (inkDimmed) ((theme.text and 0x00FFFFFF) or 0x66000000) else theme.text
        for (s in strokes) drawSmoothStroke(c, s)
        currentStroke?.let {
            inkPaint.color = theme.text
            drawSmoothStroke(c, it)
        }
        c.restore()
    }

    private fun drawSmoothStroke(c: Canvas, pts: List<Point>) {
        if (pts.isEmpty()) return
        if (pts.size == 1) {
            fill.color = inkPaint.color
            c.drawCircle(pts[0].x, pts[0].y, inkPaint.strokeWidth / 2f, fill)
            return
        }
        path.reset()
        path.moveTo(pts[0].x, pts[0].y)
        for (i in 1 until pts.size - 1) {
            val midX = (pts[i].x + pts[i + 1].x) / 2f
            val midY = (pts[i].y + pts[i + 1].y) / 2f
            path.quadTo(pts[i].x, pts[i].y, midX, midY)
        }
        path.lineTo(pts.last().x, pts.last().y)
        c.drawPath(path, inkPaint)
    }

    private fun drawBtn(c: Canvas, b: Btn, pressed: Boolean) {
        val rc = b.rect
        val isSpace = b.kind == BtnKind.SPACE
        val accent = b.kind == BtnKind.ENTER && enterAccent
        fill.color = theme.keyShadow
        c.drawRoundRect(rc.left, rc.top + 1.2f * dp, rc.right, rc.bottom + 1.2f * dp, radius, radius, fill)
        fill.color = when {
            accent -> if (pressed) theme.funcKey else theme.accent
            isSpace -> if (pressed) theme.keyPressed else theme.key
            else -> if (pressed) theme.funcKeyPressed else theme.funcKey
        }
        c.drawRoundRect(rc, radius, radius, fill)
        val fg = if (accent && !pressed) theme.accentText else theme.text
        val cx = rc.centerX(); val cy = rc.centerY()
        val u = minOf(sp, dp * 1.12f)
        when (b.kind) {
            BtnKind.DELETE -> drawDelete(c, cx, cy, 21 * dp, fg)
            BtnKind.EMOJI -> drawEmoji(c, cx, cy, 20 * dp, fg)
            BtnKind.SPACE -> KeyDraw.drawKeyText(c, if (hasPendingComposition || hasStrokes) "选定" else "空格", rc, 15 * u, fg, text, dp)
            BtnKind.ENTER -> KeyDraw.drawKeyText(c, enterLabel, rc, 15 * u, fg, text, dp, bold = accent)
            else -> KeyDraw.drawKeyText(c, b.label, rc, 14.5f * u, fg, text, dp, padH = 3.5f * dp)
        }
    }

    private fun drawDelete(c: Canvas, cx: Float, cy: Float, s: Float, color: Int) {
        path.reset()
        path.moveTo(cx - 0.55f * s, cy); path.lineTo(cx - 0.22f * s, cy - 0.36f * s)
        path.lineTo(cx + 0.5f * s, cy - 0.36f * s); path.lineTo(cx + 0.5f * s, cy + 0.36f * s)
        path.lineTo(cx - 0.22f * s, cy + 0.36f * s); path.close()
        strokePaint.color = color; strokePaint.strokeWidth = 1.6f * dp
        c.drawPath(path, strokePaint)
        val x0 = cx + 0.12f * s; val r = 0.15f * s
        c.drawLine(x0 - r, cy - r, x0 + r, cy + r, strokePaint)
        c.drawLine(x0 - r, cy + r, x0 + r, cy - r, strokePaint)
    }

    private fun drawEmoji(c: Canvas, cx: Float, cy: Float, s: Float, color: Int) {
        strokePaint.color = color; strokePaint.strokeWidth = 1.6f * dp
        c.drawCircle(cx, cy, s / 2, strokePaint)
        fill.color = color
        c.drawCircle(cx - 0.17f * s, cy - 0.1f * s, 0.06f * s, fill)
        c.drawCircle(cx + 0.17f * s, cy - 0.1f * s, 0.06f * s, fill)
        c.drawArc(cx - 0.25f * s, cy - 0.15f * s, cx + 0.25f * s, cy + 0.28f * s, 20f, 140f, false, strokePaint)
    }

    private fun maxSideScroll() = maxOf(0f, punctItems.size * sideItemH - sideRect.height())

    private fun buttonAt(x: Float, y: Float): Btn? =
        buttons.firstOrNull { RectF(it.rect).apply { inset(-gap / 2, -gap / 2) }.contains(x, y) }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downY = e.y; lastY = e.y; sideDragging = false
                downInPad = padRect.contains(e.x, e.y)
                downInSide = !downInPad && sideRect.contains(e.x, e.y)
                downBtn = null
                pressedSide = -1

                when {
                    downInPad -> {
                        handler.removeCallbacks(dimRunnable)
                        val now = SystemClock.uptimeMillis()
                        if (strokes.isNotEmpty() && (inkDimmed || now - lastUpAt >= AUTO_COMMIT_IDLE_MS)) {
                            listener.onHandwritingAutoCommitPrevious()
                            strokes.clear()
                            inkDimmed = false
                        }
                        currentStroke = mutableListOf(Point(e.x, e.y))
                    }
                    downInSide -> {
                        pressedSide = ((e.y - sideRect.top + sideScroll) / sideItemH).toInt()
                            .takeIf { it in punctItems.indices } ?: -1
                    }
                    else -> {
                        downBtn = buttonAt(e.x, e.y)
                        downBtn?.let { b ->
                            listener.onKeyDownFeedback(Key(if (b.kind == BtnKind.DELETE) KeyType.DELETE else KeyType.CHAR))
                            if (b.kind == BtnKind.DELETE) {
                                if (!undoLastStroke()) {
                                    repeats = 0
                                    listener.onDeleteDown(false)
                                    handler.postDelayed(deleteRepeat, 400)
                                }
                            }
                        }
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (downInPad) {
                    currentStroke?.let { cur ->
                        val last = cur.last()
                        if (abs(e.x - last.x) >= 1.5f || abs(e.y - last.y) >= 1.5f) {
                            cur += Point(e.x, e.y)
                        }
                    }
                } else if (downInSide) {
                    if (!sideDragging && abs(e.y - downY) > 8 * dp) { sideDragging = true; pressedSide = -1 }
                    if (sideDragging) sideScroll = (sideScroll - (e.y - lastY)).coerceIn(0f, maxSideScroll())
                    lastY = e.y
                }
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(deleteRepeat)
                when {
                    downInPad -> {
                        val cur = currentStroke
                        currentStroke = null
                        if (cur != null && cur.size >= 2) {
                            strokes += cur.toList()
                            lastUpAt = SystemClock.uptimeMillis()
                            inkDimmed = false
                            listener.onHandwritingStrokes(strokes.toList())
                            handler.removeCallbacks(dimRunnable)
                            handler.postDelayed(dimRunnable, AUTO_COMMIT_IDLE_MS)
                        } else if (cur != null && cur.size == 1 && strokes.isNotEmpty()) {
                            // Tiny dot stroke (e.g. 丶 in 主 / 玉)
                            strokes += listOf(cur[0], Point(cur[0].x + 2 * dp, cur[0].y + 2 * dp))
                            lastUpAt = SystemClock.uptimeMillis()
                            listener.onHandwritingStrokes(strokes.toList())
                            handler.removeCallbacks(dimRunnable)
                            handler.postDelayed(dimRunnable, AUTO_COMMIT_IDLE_MS)
                        }
                    }
                    downInSide -> {
                        if (!sideDragging && pressedSide in punctItems.indices) {
                            listener.onHandwritingPunct(punctItems[pressedSide])
                        }
                    }
                    else -> downBtn?.let { fireBtn(it) }
                }
                downInPad = false; downInSide = false; downBtn = null; pressedSide = -1
            }
            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(deleteRepeat)
                currentStroke = null; downInPad = false; downInSide = false; downBtn = null; pressedSide = -1
            }
        }
        invalidate()
        return true
    }

    private fun fireBtn(b: Btn) {
        when (b.kind) {
            BtnKind.DELETE -> Unit // handled on down
            BtnKind.CLEAR -> clearCanvas(notify = true)
            BtnKind.EXIT_KB -> listener.onHandwritingExitToKeyboard()
            BtnKind.TO_SYMBOLS -> listener.onFunction(Key(KeyType.TO_SYMBOLS))
            BtnKind.EMOJI -> listener.onFunction(Key(KeyType.EMOJI))
            BtnKind.SPACE -> listener.onFunction(Key(KeyType.SPACE, " "))
            BtnKind.ENTER -> listener.onFunction(Key(KeyType.ENTER))
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        handler.removeCallbacks(dimRunnable)
        handler.removeCallbacks(deleteRepeat)
    }

    companion object {
        private const val AUTO_COMMIT_IDLE_MS = 850L
    }
}
