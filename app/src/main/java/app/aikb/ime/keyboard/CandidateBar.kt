package app.aikb.ime.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.OverScroller
import kotlin.math.abs

/**
 * Top 44dp strip of the keyboard:
 *   - While typing / showing candidates: horizontal scrollable candidate strip.
 *     Every visible candidate is tappable regardless of count (see CandidateLayoutTest).
 *   - When idle (no candidates): clean WeChat-Keyboard-inspired minimal toolbar:
 *     `[⊞ Menu]`  `[✍ Handwriting / ⌨ Keyboard]`  `[📋 Clipboard / Fresh Paste Chip]` ... `[🎤 Voice]`  `[⌄ Hide]`
 *   - During passive English next-word suggestions (`pinnedToolbar = true`): keeps `[⊞]` on the
 *     left and `[⌄]` on the right so tools & collapse remain one tap away.
 */
@SuppressLint("ViewConstructor")
class CandidateBar(context: Context, private val onPick: (Int) -> Unit) : View(context) {

    enum class ToolbarAction { MENU, HANDWRITING_TOGGLE, CLIPBOARD, PASTE_FRESH, DISMISS_FRESH, VOICE, HIDE }

    private val dp = resources.displayMetrics.density
    private val sp = resources.displayMetrics.scaledDensity
    var theme: KbTheme = KbTheme.of(context)
        set(v) { field = v; invalidate() }

    var onToolbarAction: ((ToolbarAction) -> Unit)? = null

    /** True when the `[⊞]` feature drawer (or clipboard/voice sub-panel) is open. */
    var menuActive = false
        set(v) { if (field != v) { field = v; invalidate() } }

    /** True when currently in handwriting mode (switches the quick icon between ✍ and ⌨). */
    var handwritingActive = false
        set(v) { if (field != v) { field = v; invalidate() } }

    /** Shows a subtle accent dot on the clipboard icon when non-expired clips exist. */
    var hasClips = false
        set(v) { if (field != v) { field = v; invalidate() } }

    /** Freshly copied text (< 60s) shown as a one-tap paste chip when idle. */
    var freshClip: String? = null
        set(v) { if (field != v) { field = v; invalidate() } }

    /** Keeps `[⊞]` (left) and `[⌄]` (right) pinned alongside passive next-word predictions. */
    var pinnedToolbar = false
        set(v) { if (field != v) { field = v; relayout(); invalidate() } }

    private var items: List<String> = emptyList()
    private var highlight = -1
    private var rects: List<RectF> = emptyList()
    private var contentWidth = 0f
    private var scroll = 0f
    private var pressedIndex = -1

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
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

    fun setItems(list: List<String>, highlightIndex: Int, keepToolbarEdges: Boolean = false) {
        items = list
        highlight = highlightIndex
        pinnedToolbar = keepToolbarEdges && list.isNotEmpty()
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

    private val buttonW get() = 44 * dp
    private val stripLeft: Float get() = if (items.isNotEmpty() && pinnedToolbar) buttonW else 0f
    private val stripRight: Float
        get() = when {
            items.isNotEmpty() && (expandable || pinnedToolbar) -> width - buttonW
            else -> width.toFloat()
        }
    private val stripW: Float get() = (stripRight - stripLeft).coerceAtLeast(0f)

    private var rightButtonDown = false
    private var leftMenuDown = false
    private var pressedToolbar: ToolbarAction? = null

    // Idle toolbar hit rects
    private val menuRect = RectF()
    private val hwRect = RectF()
    private val clipRect = RectF()
    private val freshPasteRect = RectF()
    private val freshCloseRect = RectF()
    private val voiceRect = RectF()
    private val hideRect = RectF()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        updateToolbarRects(w.toFloat(), h.toFloat())
        relayout()
    }

    private fun updateToolbarRects(w: Float, h: Float) {
        menuRect.set(4 * dp, 4 * dp, 44 * dp, h - 4 * dp)
        hwRect.set(48 * dp, 5 * dp, 114 * dp, h - 5 * dp)
        clipRect.set(118 * dp, 4 * dp, 160 * dp, h - 4 * dp)
        hideRect.set(w - 44 * dp, 4 * dp, w - 4 * dp, h - 4 * dp)
        voiceRect.set(w - 88 * dp, 4 * dp, w - 46 * dp, h - 4 * dp)
    }

    private fun relayout() {
        val w = stripW
        if (w <= 0f || items.isEmpty()) { rects = emptyList(); spans = emptyList(); contentWidth = 0f; return }
        paint.textSize = textSize
        val pad = 14 * dp
        val widths = items.map { maxOf(paint.measureText(it) + 2 * pad, 52 * dp) }
        spans = CandidateLayout.spans(widths, w)
        contentWidth = spans.lastOrNull()?.right ?: 0f
        rects = spans.map { RectF(it.left, 0f, it.right, height.toFloat()) }
    }

    private var spans: List<CandidateLayout.Span> = emptyList()

    private fun maxScroll() = maxOf(0f, contentWidth - stripW)

    private fun indexAt(x: Float): Int = CandidateLayout.indexAt(spans, x - stripLeft + scroll)

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scroll = scroller.currX.toFloat().coerceIn(0f, maxScroll())
            postInvalidateOnAnimation()
        }
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(theme.background)
        // Subtle top border separating keyboard from editor
        fill.color = theme.divider
        canvas.drawRect(0f, 0f, width.toFloat(), 0.5f * dp, fill)

        if (items.isEmpty()) {
            drawToolbar(canvas)
            return
        }

        if (pinnedToolbar) {
            drawPinnedMenuIcon(canvas)
            drawRightActionButton(canvas, isHide = true)
        } else if (expandable) {
            drawRightActionButton(canvas, isHide = false)
        }

        paint.textSize = textSize
        val fm = paint.fontMetrics
        val baseline = height / 2f - (fm.ascent + fm.descent) / 2
        canvas.save()
        canvas.clipRect(stripLeft, 0f, stripRight, height.toFloat())
        canvas.translate(stripLeft - scroll, 0f)
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

    private fun drawToolbar(c: Canvas) {
        updateToolbarRects(width.toFloat(), height.toFloat())
        val cy = height / 2f

        // 1) Left `[⊞]` WeChat-style panel toggle button
        val menuBg = when {
            menuActive -> theme.accent
            pressedToolbar == ToolbarAction.MENU -> theme.keyPressed
            else -> (theme.funcKey and 0x00FFFFFF) or 0x66000000
        }
        fill.color = menuBg
        c.drawRoundRect(menuRect.left + 2 * dp, 6 * dp, menuRect.right - 2 * dp, height - 6 * dp, 9 * dp, 9 * dp, fill)
        val menuFg = if (menuActive) theme.accentText else theme.text
        KeyDraw.drawGridIcon(c, menuRect.centerX(), cy, 18.5f * dp, menuFg, fill, stroke, dp, active = menuActive)

        // 2) Fresh clipboard paste pill OR clean Handwriting + Clipboard quick icons
        val fresh = freshClip
        if (fresh != null) {
            val maxChipRight = voiceRect.left - 6 * dp
            val preview = "粘贴: " + fresh.replace('\n', ' ').replace(Regex("\\s+"), " ").trim()
            val u = minOf(sp, dp * 1.12f)
            paint.typeface = Typeface.DEFAULT
            paint.textSize = 13 * u
            val availTextW = (maxChipRight - hwRect.left - 38 * dp).coerceAtLeast(36 * dp)
            val shown = ellipsize(preview, availTextW)
            val chipW = (paint.measureText(shown) + 38 * dp).coerceAtMost(maxChipRight - hwRect.left)
            freshPasteRect.set(hwRect.left, 6 * dp, hwRect.left + chipW, height - 6 * dp)
            freshCloseRect.set(freshPasteRect.right - 26 * dp, 6 * dp, freshPasteRect.right, height - 6 * dp)

            fill.color = if (pressedToolbar == ToolbarAction.PASTE_FRESH) theme.keyPressed else theme.candidateHighlight
            c.drawRoundRect(freshPasteRect, 16 * dp, 16 * dp, fill)
            paint.color = theme.text
            paint.textAlign = Paint.Align.LEFT
            val fm = paint.fontMetrics
            c.drawText(shown, freshPasteRect.left + 11 * dp, cy - (fm.ascent + fm.descent) / 2f, paint)
            paint.textAlign = Paint.Align.CENTER

            // Dismiss '×' inside right end of chip
            val xcx = freshCloseRect.centerX()
            val xr = 4 * dp
            stroke.color = theme.textSecondary
            stroke.strokeWidth = 1.4f * dp
            c.drawLine(xcx - xr, cy - xr, xcx + xr, cy + xr, stroke)
            c.drawLine(xcx - xr, cy + xr, xcx + xr, cy - xr, stroke)
        } else {
            freshPasteRect.setEmpty()
            freshCloseRect.setEmpty()

            // Quick Handwriting / Keyboard toggle pill with explicit "手写" / "键盘" label
            fill.color = when {
                handwritingActive -> (theme.accent and 0x00FFFFFF) or 0x26000000
                pressedToolbar == ToolbarAction.HANDWRITING_TOGGLE -> theme.keyPressed
                else -> (theme.funcKey and 0x00FFFFFF) or 0x55000000
            }
            c.drawRoundRect(hwRect, 9 * dp, 9 * dp, fill)
            val hwColor = if (handwritingActive) theme.accent else theme.text
            val iconX = hwRect.left + 14 * dp
            if (handwritingActive) {
                KeyDraw.drawKeyboardIcon(c, iconX, cy, 16.5f * dp, hwColor, fill, stroke, dp)
            } else {
                KeyDraw.drawHandwritingIcon(c, iconX, cy, 16f * dp, hwColor, stroke, path, dp)
            }
            val u = minOf(sp, dp * 1.12f)
            paint.typeface = if (handwritingActive) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            paint.textSize = 13f * u
            paint.color = hwColor
            val fm = paint.fontMetrics
            c.drawText(if (handwritingActive) "键盘" else "手写", hwRect.left + 42 * dp, cy - (fm.ascent + fm.descent) / 2f, paint)

            // Clipboard icon (+ small dot when items exist)
            if (pressedToolbar == ToolbarAction.CLIPBOARD) {
                fill.color = theme.keyPressed
                c.drawRoundRect(clipRect, 8 * dp, 8 * dp, fill)
            }
            KeyDraw.drawClipboardIcon(c, clipRect.centerX(), cy, 19 * dp, theme.text, stroke, dp)
            if (hasClips) {
                fill.color = theme.accent
                c.drawCircle(clipRect.centerX() + 7.5f * dp, cy - 7.5f * dp, 2.6f * dp, fill)
            }
        }

        // 3) Right Voice icon + Collapse chevron
        if (pressedToolbar == ToolbarAction.VOICE) {
            fill.color = theme.keyPressed
            c.drawRoundRect(voiceRect, 8 * dp, 8 * dp, fill)
        }
        KeyDraw.drawMicIcon(c, voiceRect.centerX(), cy, 19 * dp, theme.text, stroke, dp)

        if (pressedToolbar == ToolbarAction.HIDE) {
            fill.color = theme.keyPressed
            c.drawRoundRect(hideRect, 8 * dp, 8 * dp, fill)
        }
        KeyDraw.drawChevron(c, hideRect.centerX(), cy, 6 * dp, 1, theme.text, stroke, dp)
    }

    private fun drawPinnedMenuIcon(c: Canvas) {
        val cy = height / 2f
        if (leftMenuDown || menuActive) {
            fill.color = if (menuActive) theme.accent else theme.keyPressed
            c.drawRoundRect(4 * dp, 6 * dp, buttonW - 4 * dp, height - 6 * dp, 8 * dp, 8 * dp, fill)
        }
        val fg = if (menuActive) theme.accentText else theme.text
        KeyDraw.drawGridIcon(c, buttonW / 2f, cy, 17.5f * dp, fg, fill, stroke, dp, active = menuActive)
        fill.color = theme.divider
        c.drawRect(buttonW - 0.6f * dp, 10 * dp, buttonW, height - 10 * dp, fill)
    }

    private fun drawRightActionButton(c: Canvas, isHide: Boolean) {
        val x0 = width - buttonW
        fill.color = theme.divider
        c.drawRect(x0, 10 * dp, x0 + 0.7f * dp, height - 10 * dp, fill)
        if (rightButtonDown) {
            fill.color = theme.keyPressed
            c.drawRoundRect(x0 + 4 * dp, 5 * dp, width - 4 * dp, height - 5 * dp, 8 * dp, 8 * dp, fill)
        }
        val cx = x0 + buttonW / 2
        val cy = height / 2f
        val dir = if (!isHide && expanded) -1 else 1
        KeyDraw.drawChevron(c, cx, cy, 6 * dp, dir, theme.text, stroke, dp)
    }

    private fun ellipsize(s: String, maxW: Float): String {
        if (paint.measureText(s) <= maxW) return s
        var end = s.length
        while (end > 1 && paint.measureText(s, 0, end) + paint.measureText("…") > maxW) end--
        return s.substring(0, end) + "…"
    }

    private fun toolbarHit(x: Float, y: Float): ToolbarAction? {
        if (menuRect.contains(x, y)) return ToolbarAction.MENU
        if (freshClip != null) {
            if (freshCloseRect.contains(x, y)) return ToolbarAction.DISMISS_FRESH
            if (freshPasteRect.contains(x, y)) return ToolbarAction.PASTE_FRESH
        } else {
            if (hwRect.contains(x, y)) return ToolbarAction.HANDWRITING_TOGGLE
            if (clipRect.contains(x, y)) return ToolbarAction.CLIPBOARD
        }
        if (voiceRect.contains(x, y)) return ToolbarAction.VOICE
        if (hideRect.contains(x, y)) return ToolbarAction.HIDE
        return null
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        // 1) Idle toolbar mode
        if (items.isEmpty()) {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    pressedToolbar = toolbarHit(e.x, e.y)
                    invalidate()
                }
                MotionEvent.ACTION_MOVE -> {
                    if (toolbarHit(e.x, e.y) != pressedToolbar) {
                        pressedToolbar = null
                        invalidate()
                    }
                }
                MotionEvent.ACTION_UP -> {
                    val act = toolbarHit(e.x, e.y)
                    if (act != null && act == pressedToolbar) onToolbarAction?.invoke(act)
                    pressedToolbar = null
                    invalidate()
                }
                MotionEvent.ACTION_CANCEL -> { pressedToolbar = null; invalidate() }
            }
            return true
        }

        // 2) Left pinned `[⊞]` button when passive next-word predictions are shown
        if (pinnedToolbar && (leftMenuDown || (e.actionMasked == MotionEvent.ACTION_DOWN && e.x < stripLeft))) {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> leftMenuDown = true
                MotionEvent.ACTION_UP -> {
                    leftMenuDown = false
                    if (e.x < stripLeft + 8 * dp) onToolbarAction?.invoke(ToolbarAction.MENU)
                }
                MotionEvent.ACTION_CANCEL -> leftMenuDown = false
            }
            invalidate()
            return true
        }

        // 3) Right button (Chinese expand chevron OR pinned hide chevron)
        if ((expandable || pinnedToolbar) && (rightButtonDown || (e.actionMasked == MotionEvent.ACTION_DOWN && e.x >= stripRight))) {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> rightButtonDown = true
                MotionEvent.ACTION_UP -> {
                    rightButtonDown = false
                    if (e.x >= stripRight - 8 * dp) {
                        if (expandable) onExpandToggle?.invoke()
                        else onToolbarAction?.invoke(ToolbarAction.HIDE)
                    }
                }
                MotionEvent.ACTION_CANCEL -> rightButtonDown = false
            }
            invalidate()
            return true
        }

        // 4) Scrollable candidate strip
        if (velocity == null) velocity = VelocityTracker.obtain()
        velocity!!.addMovement(e)
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
