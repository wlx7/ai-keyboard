package app.aikb.ime.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.OverScroller
import kotlin.math.abs

/** Scrollable emoji grid with category tabs, a recents group, ABC and delete keys. */
@SuppressLint("ViewConstructor")
class EmojiPanel(context: Context, private val listener: Listener) : View(context) {
    interface Listener {
        fun onEmoji(emoji: String)
        fun onEmojiDelete()
        fun onEmojiClose()
    }

    private class Group(val name: String, val icon: String, val items: List<String>)

    private val dp = resources.displayMetrics.density
    private val sp = resources.displayMetrics.scaledDensity
    var theme: KbTheme = KbTheme.of(context)
        set(v) { field = v; invalidate() }

    private val prefs = context.getSharedPreferences("emoji", Context.MODE_PRIVATE)
    private val recents = ArrayList<String>(prefs.getString("recent", "")!!.split(' ').filter { it.isNotEmpty() })
    private val groups = ArrayList<Group>()

    private val cols = 8
    private val headerH = 28 * dp
    private val barH = 46 * dp
    private var cell = 0f
    private val groupTop = ArrayList<Float>()
    private var contentH = 0f
    private var scroll = 0f

    private val emojiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val scroller = OverScroller(context)
    private var velocity: VelocityTracker? = null
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f; private var downY = 0f; private var lastY = 0f
    private var dragging = false

    init {
        val names = mapOf(
            "Smileys & Emotion" to "表情", "People & Body" to "人物", "Animals & Nature" to "动物与自然",
            "Food & Drink" to "食物", "Travel & Places" to "旅行与地点", "Activities" to "活动",
            "Objects" to "物品", "Symbols" to "符号", "Flags" to "旗帜",
        )
        val probe = Paint()
        var name: String? = null
        context.assets.open("emoji.txt").bufferedReader().useLines { lines ->
            lines.forEach { line ->
                if (line.startsWith("#")) name = line.substring(1)
                else name?.let { n ->
                    val list = line.split(' ').filter { it.isNotEmpty() && probe.hasGlyph(it) }
                    if (list.isNotEmpty()) groups += Group(names[n] ?: n, list.first(), list)
                }
            }
        }
    }

    private var shown: List<Group> = emptyList()

    private fun allGroups(): List<Group> =
        if (recents.isEmpty()) groups else listOf(Group("常用", "🕘", recents.toList())) + groups

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = relayout()

    private fun relayout() {
        cell = width / cols.toFloat()
        shown = allGroups()
        groupTop.clear()
        var y = 0f
        for (g in shown) {
            groupTop += y
            y += headerH + ((g.items.size + cols - 1) / cols) * cell
        }
        contentH = y
    }

    private fun gridH() = height - barH
    private fun maxScroll() = maxOf(0f, contentH - gridH())

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scroll = scroller.currY.toFloat().coerceIn(0f, maxScroll())
            postInvalidateOnAnimation()
        }
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(theme.background)
        val gs = shown
        emojiPaint.textSize = cell * 0.62f
        val efm = emojiPaint.fontMetrics
        label.textSize = 12.5f * sp
        label.color = theme.textSecondary
        c.save()
        c.clipRect(0f, 0f, width.toFloat(), gridH())
        c.translate(0f, -scroll)
        gs.forEachIndexed { gi, g ->
            val top = groupTop.getOrElse(gi) { 0f }
            val bottom = top + headerH + ((g.items.size + cols - 1) / cols) * cell
            if (bottom < scroll || top > scroll + gridH()) return@forEachIndexed
            c.drawText(g.name, 12 * dp, top + headerH - 9 * dp, label)
            g.items.forEachIndexed { i, e ->
                val y = top + headerH + (i / cols) * cell
                if (y + cell < scroll || y > scroll + gridH()) return@forEachIndexed
                val x = (i % cols) * cell
                c.drawText(e, x + cell / 2, y + cell / 2 - (efm.ascent + efm.descent) / 2, emojiPaint)
            }
        }
        c.restore()

        // bottom bar: ABC | tabs | delete
        val barTop = gridH()
        val n = gs.size + 2
        val w = width / n.toFloat()
        val current = currentGroup()
        emojiPaint.textSize = 19 * sp
        val bfm = emojiPaint.fontMetrics
        val cy = barTop + barH / 2 - (bfm.ascent + bfm.descent) / 2
        label.textSize = 15 * sp; label.color = theme.text; label.typeface = Typeface.DEFAULT
        label.textAlign = Paint.Align.CENTER
        c.drawText("ABC", w / 2, barTop + barH / 2 - (label.fontMetrics.ascent + label.fontMetrics.descent) / 2, label)
        gs.forEachIndexed { i, g ->
            val x = (i + 1) * w
            if (i == current) {
                fill.color = theme.keyPressed
                c.drawCircle(x + w / 2, barTop + barH / 2, minOf(w, barH) * 0.4f, fill)
            }
            c.drawText(g.icon, x + w / 2, cy, emojiPaint)
        }
        c.drawText("⌫", (n - 0.5f) * w, barTop + barH / 2 - (label.fontMetrics.ascent + label.fontMetrics.descent) / 2, label)
        label.textAlign = Paint.Align.LEFT; label.typeface = Typeface.DEFAULT_BOLD
    }

    private fun currentGroup(): Int {
        var cur = 0
        for (i in groupTop.indices) if (groupTop[i] <= scroll + 1f) cur = i
        return cur
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (velocity == null) velocity = VelocityTracker.obtain()
        velocity!!.addMovement(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scroller.forceFinished(true)
                downX = e.x; downY = e.y; lastY = e.y; dragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (downY < gridH() && !dragging && abs(e.y - downY) > slop) dragging = true
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
                } else tap(e.x, e.y)
                velocity?.recycle(); velocity = null
            }
            MotionEvent.ACTION_CANCEL -> { velocity?.recycle(); velocity = null; dragging = false }
        }
        return true
    }

    private fun tap(x: Float, y: Float) {
        val gs = shown
        if (y >= gridH()) {
            val n = gs.size + 2
            val i = (x / (width / n.toFloat())).toInt()
            when {
                i <= 0 -> listener.onEmojiClose()
                i >= n - 1 -> listener.onEmojiDelete()
                else -> { scroll = groupTop.getOrElse(i - 1) { 0f }.coerceAtMost(maxScroll()); invalidate() }
            }
            return
        }
        val cy = y + scroll
        for (gi in gs.indices.reversed()) {
            val top = groupTop.getOrNull(gi) ?: continue
            if (cy < top) continue
            val local = cy - top - headerH
            if (local < 0) return
            val idx = (local / cell).toInt() * cols + (x / cell).toInt()
            gs[gi].items.getOrNull(idx)?.let { pick(it) }
            return
        }
    }

    private fun pick(emoji: String) {
        listener.onEmoji(emoji)
        recents.remove(emoji)
        recents.add(0, emoji)
        while (recents.size > 32) recents.removeAt(recents.lastIndex)
        prefs.edit().putString("recent", recents.joinToString(" ")).apply()
        // Keep the grid stable while the panel is open; recents re-layout on next open.
    }

    fun onShown() {
        relayout()
        scroll = 0f
        invalidate()
    }
}
