package app.aikb.ime.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.OverScroller
import kotlin.math.abs

/**
 * Horizontal candidate strip.
 *
 * Every visible candidate must be tappable regardless of count (CyIME regression: with exactly
 * two English candidates the second one ignored taps). Hit-test rects are therefore rebuilt
 * whenever items or size change, and taps are resolved against content coordinates
 * (x + scroll) so nothing depends on stale layout.
 */
@SuppressLint("ViewConstructor")
class CandidateBar(context: Context, private val onPick: (Int) -> Unit) : View(context) {
    private val dp = resources.displayMetrics.density
    private val sp = resources.displayMetrics.scaledDensity
    var theme: KbTheme = KbTheme.of(context)
        set(v) { field = v; invalidate() }

    private var items: List<String> = emptyList()
    private var highlight = -1
    private var rects: List<RectF> = emptyList()
    private var contentWidth = 0f
    private var scroll = 0f
    private var pressedIndex = -1

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val scroller = OverScroller(context)
    private var velocity: VelocityTracker? = null
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var lastX = 0f
    private var dragging = false

    private val textSize get() = 18 * sp

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (44 * dp).toInt())
    }

    fun setItems(list: List<String>, highlightIndex: Int) {
        items = list
        highlight = highlightIndex
        scroll = 0f
        scroller.forceFinished(true)
        pressedIndex = -1
        relayout()
        invalidate()
    }

    val isEmpty: Boolean get() = items.isEmpty()

    /** Shows the ⌄ button that opens the full candidate grid (Chinese). */
    var expandable = false
        set(v) { if (field != v) { field = v; relayout(); invalidate() } }
    var expanded = false
        set(v) { field = v; invalidate() }
    var onExpandToggle: (() -> Unit)? = null
    private val buttonW get() = 46 * dp
    private val stripW: Float get() = if (expandable) width - buttonW else width.toFloat()
    private var buttonDown = false

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = relayout()

    private fun relayout() {
        val w = stripW
        if (w <= 0f) { rects = emptyList(); return }
        paint.textSize = textSize
        val pad = 14 * dp
        val widths = items.map { maxOf(paint.measureText(it) + 2 * pad, 52 * dp) }
        spans = CandidateLayout.spans(widths, w)
        contentWidth = spans.lastOrNull()?.right ?: 0f
        rects = spans.map { RectF(it.left, 0f, it.right, height.toFloat()) }
    }

    private var spans: List<CandidateLayout.Span> = emptyList()

    private fun maxScroll() = maxOf(0f, contentWidth - stripW)

    private fun indexAt(x: Float): Int = CandidateLayout.indexAt(spans, x + scroll)

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scroll = scroller.currX.toFloat().coerceIn(0f, maxScroll())
            postInvalidateOnAnimation()
        }
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(theme.background)
        if (items.isEmpty()) return
        paint.textSize = textSize
        val fm = paint.fontMetrics
        val baseline = height / 2f - (fm.ascent + fm.descent) / 2
        if (expandable) drawExpandButton(canvas)
        canvas.save()
        canvas.clipRect(0f, 0f, stripW, height.toFloat())
        canvas.translate(-scroll, 0f)
        for (i in items.indices) {
            val r = rects.getOrNull(i) ?: continue
            if (r.right < scroll || r.left > scroll + stripW) continue
            if (i == highlight || i == pressedIndex) {
                fill.color = if (i == pressedIndex) theme.keyPressed else theme.candidateHighlight
                canvas.drawRoundRect(r.left + 4 * dp, 5 * dp, r.right - 4 * dp, height - 5 * dp, 8 * dp, 8 * dp, fill)
            }
            paint.color = theme.text
            paint.typeface = if (i == highlight) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            var label = items[i]
            val maxW = r.width() - 12 * dp
            if (paint.measureText(label) > maxW) label = ellipsize(label, maxW)
            canvas.drawText(label, r.centerX(), baseline, paint)
            if (i < items.lastIndex && i != highlight && i + 1 != highlight) {
                fill.color = theme.divider
                canvas.drawRect(r.right - 0.5f * dp, 12 * dp, r.right + 0.5f * dp, height - 12 * dp, fill)
            }
        }
        canvas.restore()
    }

    private fun drawExpandButton(c: Canvas) {
        val x0 = stripW
        fill.color = theme.divider
        c.drawRect(x0, 10 * dp, x0 + 0.7f * dp, height - 10 * dp, fill)
        if (buttonDown) {
            fill.color = theme.keyPressed
            c.drawRoundRect(x0 + 4 * dp, 5 * dp, width - 4 * dp, height - 5 * dp, 8 * dp, 8 * dp, fill)
        }
        val cx = x0 + buttonW / 2
        val cy = height / 2f
        val s = 6 * dp
        val dir = if (expanded) -1 else 1 // chevron points down to open, up to close
        fill.color = theme.text
        fill.strokeWidth = 2 * dp
        fill.strokeCap = Paint.Cap.ROUND
        c.drawLine(cx - s, cy - dir * s / 2, cx, cy + dir * s / 2, fill)
        c.drawLine(cx, cy + dir * s / 2, cx + s, cy - dir * s / 2, fill)
    }

    private fun ellipsize(s: String, maxW: Float): String {
        var end = s.length
        while (end > 1 && paint.measureText(s, 0, end) + paint.measureText("…") > maxW) end--
        return s.substring(0, end) + "…"
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (velocity == null) velocity = VelocityTracker.obtain()
        velocity!!.addMovement(e)
        if (expandable && (buttonDown || (e.actionMasked == MotionEvent.ACTION_DOWN && e.x >= stripW))) {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> buttonDown = true
                MotionEvent.ACTION_UP -> { buttonDown = false; if (e.x >= stripW - 8 * dp) onExpandToggle?.invoke() }
                MotionEvent.ACTION_CANCEL -> buttonDown = false
            }
            invalidate()
            return true
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scroller.forceFinished(true)
                downX = e.x; lastX = e.x; dragging = false
                pressedIndex = indexAt(e.x)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && abs(e.x - downX) > slop && maxScroll() > 0f) { dragging = true; pressedIndex = -1 }
                if (dragging) {
                    scroll = (scroll - (e.x - lastX)).coerceIn(0f, maxScroll())
                    invalidate()
                }
                lastX = e.x
            }
            MotionEvent.ACTION_UP -> {
                if (dragging) {
                    velocity!!.computeCurrentVelocity(1000)
                    scroller.fling(scroll.toInt(), 0, -velocity!!.xVelocity.toInt(), 0, 0, maxScroll().toInt(), 0, 0)
                    postInvalidateOnAnimation()
                } else {
                    val i = indexAt(e.x)
                    if (i in items.indices) onPick(i)
                }
                pressedIndex = -1
                velocity?.recycle(); velocity = null
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                pressedIndex = -1; dragging = false
                velocity?.recycle(); velocity = null
                invalidate()
            }
        }
        return true
    }
}
