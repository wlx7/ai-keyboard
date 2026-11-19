package app.aikb.ime.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.OverScroller
import kotlin.math.abs

/**
 * Expanded candidate grid shown in place of the keys (iOS "more candidates" view).
 * Candidates flow left to right in rows; every cell is a tap target computed from the same
 * rects that are drawn, so hit-testing cannot drift from what is on screen.
 */
@SuppressLint("ViewConstructor")
class CandidatePanel(context: Context, private val onPick: (Int) -> Unit) : View(context) {
    private val dp = resources.displayMetrics.density
    private val sp = resources.displayMetrics.scaledDensity
    var theme: KbTheme = KbTheme.of(context)
        set(v) { field = v; invalidate() }
    var onLongPressPick: ((Int) -> Unit)? = null

    private var items: List<String> = emptyList()
    private var cells: List<RectF> = emptyList()
    private var contentH = 0f
    private var scroll = 0f
    private var pressed = -1
    private var longPressFired = false
    private val longPressHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val longPressRunnable = Runnable {
        val idx = pressed
        if (!dragging && idx in items.indices && onLongPressPick != null) {
            longPressFired = true
            pressed = -1
            invalidate()
            onLongPressPick?.invoke(idx)
        }
    }

    private val rowH = 52 * dp
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val scroller = OverScroller(context)
    private var velocity: VelocityTracker? = null
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downY = 0f
    private var lastY = 0f
    private var dragging = false

    fun setItems(list: List<String>) {
        items = list
        scroll = 0f
        relayout()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = relayout()

    private fun relayout() {
        if (width == 0) return
        text.textSize = 21 * sp
        val minW = width / 6f
        val out = ArrayList<RectF>(items.size)
        var x = 0f
        var y = 0f
        // Greedy flow; the last cell of each row stretches to the edge so rows look even.
        val rowStart = ArrayList<Int>()
        rowStart += 0
        items.forEachIndexed { i, s ->
            val w = maxOf(minW, text.measureText(s) + 28 * dp).coerceAtMost(width.toFloat())
            if (x + w > width && x > 0f) { x = 0f; y += rowH; rowStart += i }
            out += RectF(x, y, x + w, y + rowH)
            x += w
        }
        rowStart += items.size
        for (r in 0 until rowStart.size - 1) {
            val a = rowStart[r]; val b = rowStart[r + 1]
            if (b <= a) continue
            val used = out[b - 1].right
            val extra = (width - used) / (b - a)
            for (i in a until b) {
                val shift = extra * (i - a)
                out[i].left += shift; out[i].right += shift + extra
            }
        }
        cells = out
        contentH = if (out.isEmpty()) 0f else out.last().bottom
    }

    private fun maxScroll() = maxOf(0f, contentH - height)

    private fun indexAt(x: Float, y: Float): Int {
        val cy = y + scroll
        for (i in cells.indices) if (cells[i].contains(x, cy)) return i
        return -1
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scroll = scroller.currY.toFloat().coerceIn(0f, maxScroll())
            postInvalidateOnAnimation()
        }
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(theme.background)
        text.textSize = 21 * sp
        text.color = theme.text
        val fm = text.fontMetrics
        c.save()
        c.translate(0f, -scroll)
        cells.forEachIndexed { i, r ->
            if (r.bottom < scroll || r.top > scroll + height) return@forEachIndexed
            if (i == pressed) {
                fill.color = theme.keyPressed
                c.drawRoundRect(r.left + 3 * dp, r.top + 4 * dp, r.right - 3 * dp, r.bottom - 4 * dp, 8 * dp, 8 * dp, fill)
            }
            c.drawText(items[i], r.centerX(), r.centerY() - (fm.ascent + fm.descent) / 2, text)
            fill.color = theme.divider
            c.drawRect(r.left, r.bottom - 0.5f * dp, r.right, r.bottom, fill)
            c.drawRect(r.right - 0.5f * dp, r.top + 12 * dp, r.right, r.bottom - 12 * dp, fill)
        }
        c.restore()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (velocity == null) velocity = VelocityTracker.obtain()
        velocity!!.addMovement(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scroller.forceFinished(true)
                downY = e.y; lastY = e.y; dragging = false
                longPressFired = false
                pressed = indexAt(e.x, e.y); invalidate()
                longPressHandler.removeCallbacks(longPressRunnable)
                if (pressed >= 0 && onLongPressPick != null) {
                    longPressHandler.postDelayed(longPressRunnable, 450L)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && abs(e.y - downY) > slop) {
                    dragging = true; pressed = -1
                    longPressHandler.removeCallbacks(longPressRunnable)
                }
                if (dragging) { scroll = (scroll - (e.y - lastY)).coerceIn(0f, maxScroll()); invalidate() }
                lastY = e.y
            }
            MotionEvent.ACTION_UP -> {
                longPressHandler.removeCallbacks(longPressRunnable)
                if (dragging) {
                    velocity!!.computeCurrentVelocity(1000)
                    scroller.fling(0, scroll.toInt(), 0, -velocity!!.yVelocity.toInt(), 0, 0, 0, maxScroll().toInt())
                    postInvalidateOnAnimation()
                } else if (!longPressFired) {
                    val i = indexAt(e.x, e.y)
                    if (i >= 0) onPick(i)
                }
                longPressFired = false
                pressed = -1; invalidate()
                velocity?.recycle(); velocity = null
            }
            MotionEvent.ACTION_CANCEL -> {
                longPressHandler.removeCallbacks(longPressRunnable)
                longPressFired = false
                pressed = -1; dragging = false; velocity?.recycle(); velocity = null; invalidate()
            }
        }
        return true
    }
}
