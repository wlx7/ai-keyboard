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
import app.aikb.ime.engine.ClipboardStore
import kotlin.math.abs

/**
 * Keyboard-sized clipboard panel showing pinned items + 1-hour auto-expiring recent items.
 */
@SuppressLint("ViewConstructor")
class ClipboardPanel(
    context: Context,
    private val store: ClipboardStore,
    private val listener: Listener,
) : View(context) {

    interface Listener {
        fun onClipboardCommit(text: String)
        fun onClipboardChanged()
        fun onClipboardClose()
    }

    private val dp = resources.displayMetrics.density
    private val sp = resources.displayMetrics.scaledDensity
    var theme: KbTheme = KbTheme.of(context)
        set(v) { field = v; invalidate() }

    private val headerH = 38 * dp
    private val cardH = 52 * dp
    private val cardGap = 6 * dp
    private val sidePad = 10 * dp

    private var entries: List<ClipboardStore.Item> = emptyList()
    private var scroll = 0f
    private var pressedRow = -1
    private var pressedZone = 0 // 0=body, 1=pin, 2=delete
    private var headerPressed = 0 // 1=back, 2=clear

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private val scroller = OverScroller(context)
    private var velocity: VelocityTracker? = null
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var lastY = 0f
    private var dragging = false

    private val backRect = RectF()
    private val clearRect = RectF()

    fun refresh() {
        entries = store.items()
        scroll = scroll.coerceIn(0f, maxScroll())
        invalidate()
    }

    private fun listH() = (height - headerH).coerceAtLeast(0f)
    private fun contentH() = if (entries.isEmpty()) 0f else entries.size * (cardH + cardGap) + 6 * dp
    private fun maxScroll() = maxOf(0f, contentH() - listH())

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        backRect.set(4 * dp, 2 * dp, 68 * dp, headerH - 2 * dp)
        clearRect.set(w - 68 * dp, 2 * dp, w - 4 * dp, headerH - 2 * dp)
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scroll = scroller.currY.toFloat().coerceIn(0f, maxScroll())
            postInvalidateOnAnimation()
        }
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(theme.background)
        val u = minOf(sp, dp * 1.12f)

        // Header: ‹ 返回 | 未置顶内容 1 小时自动清除 | 清空
        if (headerPressed == 1) {
            fill.color = theme.keyPressed
            c.drawRoundRect(backRect, 6 * dp, 6 * dp, fill)
        } else if (headerPressed == 2 && entries.any { !it.pinned }) {
            fill.color = theme.keyPressed
            c.drawRoundRect(clearRect, 6 * dp, 6 * dp, fill)
        }
        text.typeface = Typeface.DEFAULT
        text.textAlign = Paint.Align.LEFT
        text.textSize = 14 * u
        text.color = theme.accent
        val hBaseline = headerH / 2f - (text.fontMetrics.ascent + text.fontMetrics.descent) / 2f
        c.drawText("‹ 返回", 12 * dp, hBaseline, text)

        text.textAlign = Paint.Align.CENTER
        text.textSize = 12 * u
        text.color = theme.textSecondary
        c.drawText("未置顶内容 1 小时自动清除", width / 2f, hBaseline, text)

        val hasUnpinned = entries.any { !it.pinned }
        text.textAlign = Paint.Align.RIGHT
        text.textSize = 13.5f * u
        text.color = if (hasUnpinned) theme.text else (theme.textSecondary and 0x00FFFFFF) or 0x55000000
        c.drawText("清空", width - 12 * dp, hBaseline, text)

        fill.color = theme.divider
        c.drawRect(sidePad, headerH - 0.5f * dp, width - sidePad, headerH, fill)

        if (entries.isEmpty()) {
            text.textAlign = Paint.Align.CENTER
            text.textSize = 14.5f * u
            text.color = theme.textSecondary
            val cy = headerH + listH() / 2f
            c.drawText("剪贴板暂无内容", width / 2f, cy - 8 * dp, text)
            text.textSize = 12 * u
            c.drawText("复制的文本仅保存在本地内存，1 小时后自动销毁", width / 2f, cy + 14 * dp, text)
            return
        }

        c.save()
        c.clipRect(0f, headerH, width.toFloat(), height.toFloat())
        c.translate(0f, headerH - scroll)
        val actionW = 38 * dp
        entries.forEachIndexed { i, item ->
            val top = 4 * dp + i * (cardH + cardGap)
            val bottom = top + cardH
            if (bottom < scroll || top > scroll + listH()) return@forEachIndexed
            val rc = RectF(sidePad, top, width - sidePad, bottom)
            fill.color = theme.keyShadow
            c.drawRoundRect(rc.left, rc.top + 1f * dp, rc.right, rc.bottom + 1f * dp, 8 * dp, 8 * dp, fill)
            fill.color = if (pressedRow == i && pressedZone == 0) theme.keyPressed else theme.key
            c.drawRoundRect(rc, 8 * dp, 8 * dp, fill)

            // Single-line preview + expiry/pinned subtitle
            val preview = item.text.replace('\n', ' ').replace(Regex("\\s+"), " ").trim()
            val maxTextW = rc.width() - 2 * actionW - 22 * dp
            text.textAlign = Paint.Align.LEFT
            text.textSize = 14.5f * u
            text.color = theme.text
            c.drawText(ellipsize(preview, maxTextW), rc.left + 12 * dp, rc.top + 22 * dp, text)

            text.textSize = 11 * u
            text.color = if (item.pinned) theme.accent else theme.textSecondary
            val sub = if (item.pinned) "已置顶 · 常驻保留" else "${store.remainingMinutes(item)} 分钟后自动清除"
            c.drawText(sub, rc.left + 12 * dp, rc.top + 40 * dp, text)

            // Pin button & Delete button on the right
            val pinCx = rc.right - actionW * 1.5f
            val delCx = rc.right - actionW * 0.5f
            val cy = rc.centerY()
            if (pressedRow == i && pressedZone == 1) {
                fill.color = theme.keyPressed
                c.drawCircle(pinCx, cy, 15 * dp, fill)
            } else if (pressedRow == i && pressedZone == 2) {
                fill.color = theme.keyPressed
                c.drawCircle(delCx, cy, 15 * dp, fill)
            }
            drawPin(c, pinCx, cy, 14 * dp, if (item.pinned) theme.accent else theme.textSecondary, item.pinned)
            drawCross(c, delCx, cy, 5.5f * dp, theme.textSecondary)
        }
        c.restore()
    }

    private fun drawPin(c: Canvas, cx: Float, cy: Float, s: Float, color: Int, active: Boolean) {
        stroke.color = color
        stroke.strokeWidth = 1.6f * dp
        val r = s * 0.34f
        if (active) {
            fill.color = color
            c.drawCircle(cx, cy - s * 0.16f, r, fill)
        } else {
            c.drawCircle(cx, cy - s * 0.16f, r, stroke)
        }
        c.drawLine(cx, cy + r - s * 0.16f, cx, cy + s * 0.46f, stroke)
    }

    private fun drawCross(c: Canvas, cx: Float, cy: Float, r: Float, color: Int) {
        stroke.color = color
        stroke.strokeWidth = 1.6f * dp
        c.drawLine(cx - r, cy - r, cx + r, cy + r, stroke)
        c.drawLine(cx - r, cy + r, cx + r, cy - r, stroke)
    }

    private fun ellipsize(s: String, maxW: Float): String {
        if (text.measureText(s) <= maxW) return s
        var end = s.length
        val ell = "…"
        val ellW = text.measureText(ell)
        while (end > 1 && text.measureText(s, 0, end) + ellW > maxW) end--
        return s.substring(0, end) + ell
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (velocity == null) velocity = VelocityTracker.obtain()
        velocity!!.addMovement(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scroller.forceFinished(true)
                downX = e.x; downY = e.y; lastY = e.y; dragging = false
                pressedRow = -1; headerPressed = 0
                if (e.y < headerH) {
                    headerPressed = when {
                        backRect.contains(e.x, e.y) -> 1
                        clearRect.contains(e.x, e.y) -> 2
                        else -> 0
                    }
                } else {
                    hitRow(e.x, e.y)?.let { (r, z) -> pressedRow = r; pressedZone = z }
                }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                if (downY >= headerH && !dragging && abs(e.y - downY) > slop && maxScroll() > 0f) {
                    dragging = true
                    pressedRow = -1
                }
                if (dragging) {
                    scroll = (scroll - (e.y - lastY)).coerceIn(0f, maxScroll())
                    invalidate()
                }
                lastY = e.y
            }
            MotionEvent.ACTION_UP -> {
                if (dragging) {
                    velocity!!.computeCurrentVelocity(1000)
                    scroller.fling(0, scroll.toInt(), 0, -velocity!!.yVelocity.toInt(), 0, 0, 0, maxScroll().toInt())
                    postInvalidateOnAnimation()
                } else if (headerPressed == 1 && backRect.contains(e.x, e.y)) {
                    listener.onClipboardClose()
                } else if (headerPressed == 2 && clearRect.contains(e.x, e.y) && entries.any { !it.pinned }) {
                    store.clear(includePinned = false)
                    refresh()
                    listener.onClipboardChanged()
                } else if (pressedRow in entries.indices) {
                    val item = entries[pressedRow]
                    when (pressedZone) {
                        0 -> listener.onClipboardCommit(item.text)
                        1 -> { store.togglePin(item.text); refresh(); listener.onClipboardChanged() }
                        2 -> { store.delete(item.text); refresh(); listener.onClipboardChanged() }
                    }
                }
                pressedRow = -1; headerPressed = 0
                velocity?.recycle(); velocity = null
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                pressedRow = -1; headerPressed = 0; dragging = false
                velocity?.recycle(); velocity = null
                invalidate()
            }
        }
        return true
    }

    private fun hitRow(x: Float, y: Float): Pair<Int, Int>? {
        if (x < sidePad || x > width - sidePad) return null
        val ly = y - headerH + scroll - 4 * dp
        if (ly < 0f) return null
        val idx = (ly / (cardH + cardGap)).toInt()
        if (idx !in entries.indices) return null
        if (ly - idx * (cardH + cardGap) > cardH) return null
        val actionW = 38 * dp
        val rightEdge = width - sidePad
        val zone = when {
            x >= rightEdge - actionW -> 2
            x >= rightEdge - 2 * actionW -> 1
            else -> 0
        }
        return idx to zone
    }
}
