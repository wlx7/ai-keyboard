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
 * Spacious Full-Width Handwriting Input View:
 *
 *   ┌─────────────────────────────────────────────── [↶] ┐ [  ⌫  ]
 *   │                                                     │ [ 重写 ]
 *   │           超宽加高手写画布（单字 / 连写 / 叠写）      │ [ 加高 ]
 *   │                                                     │ [ 键盘 ]
 *   └─────────────────────────────────────────────────────┘
 *   [123] [😀] [，] [  空格 / 选定  ] [。] [？] [  完成  ]
 */
@SuppressLint("ViewConstructor")
class HandwritingView(context: Context, private val listener: Listener) : View(context) {

    interface Listener {
        fun onHandwritingStrokes(strokes: List<List<Point>>)
        fun onHandwritingAutoCommitPrevious()
        fun onHandwritingClear()
        fun onHandwritingPunct(punct: String)
        fun onHandwritingExitToKeyboard()
        fun onHandwritingCycleHeight()
        fun onFunction(key: Key)
        fun onDeleteDown(repeat: Boolean)
        fun onKeyDownFeedback(key: Key)
    }

    private enum class BtnKind {
        DELETE, CLEAR, TALL_TOGGLE, EXIT_KB,
        TO_SYMBOLS, EMOJI, PUNCT_COMMA, SPACE, PUNCT_PERIOD, PUNCT_QMARK, ENTER,
    }

    private class Btn(val kind: BtnKind, val label: String = "", val rect: RectF = RectF())

    private val dp = resources.displayMetrics.density
    private val sp = resources.displayMetrics.scaledDensity

    var theme: KbTheme = KbTheme.of(context)
        set(v) { field = v; invalidate() }
    var prefs: KeyboardPrefs = KeyboardPrefs()
        set(v) {
            val hChanged = field.hwHeightScale != v.hwHeightScale || field.heightScale != v.heightScale
            field = v
            inkPaint.strokeWidth = v.hwStrokeWidthDp * dp
            if (hChanged) requestLayout()
            computeRects()
            invalidate()
        }
    var enterLabel = "完成"
        set(v) { field = v; invalidate() }
    var enterAccent = false
        set(v) { field = v; invalidate() }
    var hasPendingComposition = false
        set(v) { field = v; invalidate() }
    /** Shows proportional calligraphy sample strokes in live settings preview when canvas is empty. */
    var previewDemoMode = false
        set(v) { field = v; invalidate() }

    private val padRect = RectF()
    private val undoBadgeRect = RectF()

    private val rightButtons = listOf(
        Btn(BtnKind.DELETE),
        Btn(BtnKind.CLEAR, "重写"),
        Btn(BtnKind.TALL_TOGGLE, "加高"),
        Btn(BtnKind.EXIT_KB, "键盘"),
    )
    private val bottomButtons = listOf(
        Btn(BtnKind.TO_SYMBOLS, "123"),
        Btn(BtnKind.EMOJI),
        Btn(BtnKind.PUNCT_COMMA, "，"),
        Btn(BtnKind.SPACE),
        Btn(BtnKind.PUNCT_PERIOD, "。"),
        Btn(BtnKind.PUNCT_QMARK, "？"),
        Btn(BtnKind.ENTER),
    )

    private val strokes = ArrayList<List<Point>>()
    private var currentStroke: MutableList<Point>? = null
    private var inkDimmed = false
    private var lastUpAt = 0L

    private var downInPad = false
    private var downUndoBadge = false
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
        strokeWidth = 4.4f * dp
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val path = Path()
    private val handler = Handler(Looper.getMainLooper())

    private val autoCommitMs: Long get() = prefs.hwAutoCommitMs.toLong()

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
            handler.postDelayed(dimRunnable, autoCommitMs)
        }
        invalidate()
        return true
    }

    private fun totalHeightPx(): Int {
        val baseH = 224f * dp
        val effScale = (prefs.hwHeightScale * prefs.heightScale).coerceIn(0.90f, 1.75f)
        return (baseH * effScale).toInt()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), totalHeightPx())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = computeRects()

    private fun computeRects() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val outerPad = 4.5f * dp
        val gap = 5f * dp
        val bottomRowH = (41f * dp * prefs.heightScale.coerceIn(0.88f, 1.12f))
        val topAreaBottom = h - outerPad - bottomRowH - gap

        val rightRailW = (w * 0.148f).coerceIn(50f * dp, 64f * dp)
        val railLeft = w - outerPad - rightRailW

        padRect.set(outerPad, outerPad + 1 * dp, railLeft - gap, topAreaBottom)
        undoBadgeRect.set(padRect.right - 36 * dp, padRect.top + 5 * dp, padRect.right - 5 * dp, padRect.top + 31 * dp)

        val railCount = rightButtons.size
        val railBtnH = (padRect.height() - (railCount - 1) * gap) / railCount
        for (i in 0 until railCount) {
            val t = padRect.top + i * (railBtnH + gap)
            rightButtons[i].rect.set(railLeft, t, w - outerPad, t + railBtnH)
        }

        val weights = floatArrayOf(1.10f, 0.95f, 0.85f, 3.45f, 0.85f, 0.85f, 1.95f)
        val totalUnits = 10.0f
        val availW = w - 2 * outerPad - (weights.size - 1) * gap
        val bTop = topAreaBottom + gap
        val bBot = h - outerPad
        var x = outerPad
        for (i in bottomButtons.indices) {
            val bw = availW * (weights[i] / totalUnits)
            bottomButtons[i].rect.set(x, bTop, x + bw, bBot)
            x += bw + gap
        }
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(theme.background)
        drawWritingPad(c)
        for (b in rightButtons) drawBtn(c, b, b === downBtn)
        for (b in bottomButtons) drawBtn(c, b, b === downBtn)
    }

    private fun drawWritingPad(c: Canvas) {
        val r = (prefs.keyRadiusDp + 2f) * dp
        if (theme.keyShadow != 0) {
            fill.color = theme.keyShadow
            c.drawRoundRect(padRect.left, padRect.top + 1.2f * dp, padRect.right, padRect.bottom + 1.4f * dp, r, r, fill)
        }
        fill.color = if (theme.isDark) 0xFF232732.toInt() else 0xFFFFFFFF.toInt()
        c.drawRoundRect(padRect, r, r, fill)
        strokePaint.color = theme.divider
        strokePaint.strokeWidth = 0.8f * dp
        c.drawRoundRect(padRect, r, r, strokePaint)

        guidePaint.color = theme.divider
        val cx = padRect.centerX()
        val cy = padRect.centerY()
        c.drawLine(padRect.left + 14 * dp, cy, padRect.right - 14 * dp, cy, guidePaint)
        c.drawLine(cx, padRect.top + 12 * dp, cx, padRect.bottom - 12 * dp, guidePaint)

        val u = minOf(sp, dp * 1.12f) * prefs.keyFontScale
        if (strokes.isEmpty() && currentStroke == null) {
            if (previewDemoMode) {
                drawPreviewDemoSample(c, cx, cy, u)
                return
            }
            text.textSize = 14f * u
            text.color = (theme.textSecondary and 0x00FFFFFF) or 0x70000000
            val fm = text.fontMetrics
            c.drawText("全字集手写（4万生僻字 · 连写 · 叠写）", cx, cy - (fm.ascent + fm.descent) / 2f, text)
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

        if (strokes.size >= 2 && currentStroke == null) {
            fill.color = if (downUndoBadge) theme.keyPressed else ((theme.funcKey and 0x00FFFFFF) or 0xCC000000.toInt())
            c.drawRoundRect(undoBadgeRect, 7 * dp, 7 * dp, fill)
            KeyDraw.drawKeyText(c, "↶", undoBadgeRect, 14f * u, theme.textSecondary, text, dp, padH = 2 * dp)
        }
        c.restore()
    }

    private fun drawPreviewDemoSample(c: Canvas, cx: Float, cy: Float, u: Float) {
        val box = minOf(padRect.width(), padRect.height()) * 0.35f
        val oy = cy - 6f * dp
        fun pt(nx: Float, ny: Float) = Point(cx + nx * box, oy + ny * box)
        val demo = listOf(
            listOf(pt(-0.08f, -0.78f), pt(0.04f, -0.68f), pt(0.11f, -0.58f)),
            listOf(pt(-0.26f, -0.42f), pt(0.04f, -0.47f), pt(0.04f, 0.52f), pt(0.02f, 0.68f), pt(-0.15f, 0.55f)),
            listOf(pt(-0.56f, -0.08f), pt(-0.24f, -0.15f), pt(-0.37f, 0.22f), pt(-0.62f, 0.50f)),
            listOf(pt(0.48f, -0.26f), pt(0.28f, -0.06f), pt(0.11f, 0.06f)),
            listOf(pt(0.09f, 0.02f), pt(0.35f, 0.31f), pt(0.64f, 0.54f)),
        )
        c.save()
        c.clipRect(padRect)
        inkPaint.strokeWidth = prefs.hwStrokeWidthDp * dp
        inkPaint.color = theme.text
        for (s in demo) drawSmoothStroke(c, s)
        text.textSize = 11.5f * u
        text.color = (theme.textSecondary and 0x00FFFFFF) or 0x88000000.toInt()
        c.drawText("实时笔迹粗细与画布预览（也可直接在此试写）", cx, padRect.bottom - 9f * dp, text)
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
        val radius = prefs.keyRadiusDp * dp
        val isSpace = b.kind == BtnKind.SPACE
        val isTallActive = b.kind == BtnKind.TALL_TOGGLE && prefs.hwHeightScale >= 1.35f
        val accent = (b.kind == BtnKind.ENTER && enterAccent) || isTallActive
        if (theme.keyShadow != 0) {
            fill.color = theme.keyShadow
            c.drawRoundRect(rc.left, rc.top + 1.1f * dp, rc.right, rc.bottom + 1.2f * dp, radius, radius, fill)
        }
        fill.color = when {
            accent -> if (pressed) theme.funcKeyPressed else theme.accent
            isSpace -> if (pressed) theme.keyPressed else theme.key
            else -> if (pressed) theme.funcKeyPressed else theme.funcKey
        }
        c.drawRoundRect(rc, radius, radius, fill)
        val fg = if (accent && !pressed) theme.accentText else theme.text
        val cx = rc.centerX(); val cy = rc.centerY()
        val u = minOf(sp, dp * 1.12f) * prefs.keyFontScale
        val iconS = minOf(rc.width(), rc.height()) * 0.50f
        when (b.kind) {
            BtnKind.DELETE -> drawDelete(c, cx, cy, iconS.coerceIn(16 * dp, 21 * dp), fg)
            BtnKind.EMOJI -> drawEmoji(c, cx, cy, iconS.coerceIn(16 * dp, 20 * dp), fg)
            BtnKind.SPACE -> KeyDraw.drawKeyText(
                c,
                if (hasPendingComposition || hasStrokes) "选定" else "空格",
                rc, 14.5f * u, fg, text, dp,
            )
            BtnKind.TALL_TOGGLE -> {
                val lbl = when {
                    prefs.hwHeightScale >= 1.40f -> "半屏"
                    prefs.hwHeightScale >= 1.18f -> "加高"
                    else -> "标准"
                }
                KeyDraw.drawKeyText(c, lbl, rc, 12.8f * u, fg, text, dp, padH = 3 * dp, bold = isTallActive)
            }
            BtnKind.ENTER -> KeyDraw.drawKeyText(c, enterLabel, rc, 14.5f * u, fg, text, dp, bold = accent)
            else -> KeyDraw.drawKeyText(c, b.label, rc, 14f * u, fg, text, dp, padH = 3 * dp)
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

    private fun buttonAt(x: Float, y: Float): Btn? {
        for (b in rightButtons) if (RectF(b.rect).apply { inset(-2.5f * dp, -2.5f * dp) }.contains(x, y)) return b
        for (b in bottomButtons) if (RectF(b.rect).apply { inset(-2.5f * dp, -2.5f * dp) }.contains(x, y)) return b
        return null
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downUndoBadge = strokes.size >= 2 && currentStroke == null && undoBadgeRect.contains(e.x, e.y)
                downInPad = !downUndoBadge && padRect.contains(e.x, e.y)
                downBtn = null

                when {
                    downUndoBadge -> listener.onKeyDownFeedback(Key(KeyType.DELETE))
                    downInPad -> {
                        handler.removeCallbacks(dimRunnable)
                        val now = SystemClock.uptimeMillis()
                        if (strokes.isNotEmpty() && (inkDimmed || now - lastUpAt >= autoCommitMs)) {
                            listener.onHandwritingAutoCommitPrevious()
                            strokes.clear()
                            inkDimmed = false
                        }
                        currentStroke = mutableListOf(Point(e.x, e.y))
                    }
                    else -> {
                        downBtn = buttonAt(e.x, e.y)
                        downBtn?.let { b ->
                            listener.onKeyDownFeedback(Key(if (b.kind == BtnKind.DELETE) KeyType.DELETE else KeyType.CHAR))
                            if (b.kind == BtnKind.DELETE) {
                                if (hasStrokes || hasPendingComposition) {
                                    clearCanvas(notify = true)
                                } else {
                                    repeats = 0
                                    listener.onDeleteDown(false)
                                }
                                handler.postDelayed(deleteRepeat, 420)
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
                }
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(deleteRepeat)
                when {
                    downUndoBadge -> if (undoBadgeRect.contains(e.x, e.y)) undoLastStroke()
                    downInPad -> {
                        val cur = currentStroke
                        currentStroke = null
                        if (cur != null && cur.size >= 2) {
                            strokes += cur.toList()
                            lastUpAt = SystemClock.uptimeMillis()
                            inkDimmed = false
                            listener.onHandwritingStrokes(strokes.toList())
                            handler.removeCallbacks(dimRunnable)
                            handler.postDelayed(dimRunnable, autoCommitMs)
                        } else if (cur != null && cur.size == 1 && strokes.isNotEmpty()) {
                            strokes += listOf(cur[0], Point(cur[0].x + 2 * dp, cur[0].y + 2 * dp))
                            lastUpAt = SystemClock.uptimeMillis()
                            listener.onHandwritingStrokes(strokes.toList())
                            handler.removeCallbacks(dimRunnable)
                            handler.postDelayed(dimRunnable, autoCommitMs)
                        }
                    }
                    else -> downBtn?.let { if (buttonAt(e.x, e.y) === it) fireBtn(it) }
                }
                downInPad = false; downUndoBadge = false; downBtn = null
            }
            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(deleteRepeat)
                currentStroke = null; downInPad = false; downUndoBadge = false; downBtn = null
            }
        }
        invalidate()
        return true
    }

    private fun fireBtn(b: Btn) {
        when (b.kind) {
            BtnKind.DELETE -> Unit
            BtnKind.CLEAR -> clearCanvas(notify = true)
            BtnKind.TALL_TOGGLE -> listener.onHandwritingCycleHeight()
            BtnKind.EXIT_KB -> listener.onHandwritingExitToKeyboard()
            BtnKind.TO_SYMBOLS -> listener.onFunction(Key(KeyType.TO_SYMBOLS))
            BtnKind.EMOJI -> listener.onFunction(Key(KeyType.EMOJI))
            BtnKind.PUNCT_COMMA -> listener.onHandwritingPunct("，")
            BtnKind.SPACE -> listener.onFunction(Key(KeyType.SPACE, " "))
            BtnKind.PUNCT_PERIOD -> listener.onHandwritingPunct("。")
            BtnKind.PUNCT_QMARK -> listener.onHandwritingPunct("？")
            BtnKind.ENTER -> listener.onFunction(Key(KeyType.ENTER))
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        handler.removeCallbacks(dimRunnable)
        handler.removeCallbacks(deleteRepeat)
    }
}
