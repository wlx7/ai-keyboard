package app.aikb.ime.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.OverScroller
import kotlin.math.abs

/**
 * Top 45dp strip of the keyboard:
 *   - While typing / showing candidates: smooth pill-highlighted candidate strip (`candFontScale`).
 *   - When idle: modern toolbar with:
 *       `[⊞]` `[✍ 手写 / ⌨ 键盘]` `[📋 剪贴板]` `[🖼 图片]` ... `[🎤 语音]` `[⌄ 收起]`
 *     plus Gboard-style one-tap **Fresh Screenshot / Photo Chip** OR **Fresh Clipboard Paste Chip**!
 */
@SuppressLint("ViewConstructor")
class CandidateBar(context: Context, private val onPick: (Int) -> Unit) : View(context) {

    enum class ToolbarAction {
        MENU, HANDWRITING_TOGGLE, CLIPBOARD, IMAGES,
        PASTE_OTP, DISMISS_OTP,
        PASTE_FRESH, DISMISS_FRESH,
        SEND_FRESH_IMAGE, DISMISS_FRESH_IMAGE,
        VOICE, HIDE,
    }

    private val dp = resources.displayMetrics.density
    private val sp = resources.displayMetrics.scaledDensity
    var theme: KbTheme = KbTheme.of(context)
        set(v) { field = v; invalidate() }
    var prefs: KeyboardPrefs = KeyboardPrefs()
        set(v) {
            val fChanged = field.candFontScale != v.candFontScale
            field = v
            if (fChanged) relayout()
            invalidate()
        }

    var onToolbarAction: ((ToolbarAction) -> Unit)? = null
    var onLongPressPick: ((Int) -> Unit)? = null

    var menuActive = false
        set(v) { if (field != v) { field = v; invalidate() } }

    var handwritingActive = false
        set(v) { if (field != v) { field = v; invalidate() } }

    var hasClips = false
        set(v) { if (field != v) { field = v; invalidate() } }

    var showClipboardIcon = true
        set(v) { if (field != v) { field = v; invalidate() } }

    var showImageIcon = true
        set(v) { if (field != v) { field = v; invalidate() } }

    var freshClip: String? = null
        set(v) { if (field != v) { field = v; invalidate() } }

    var freshOtpCode: String? = null
        set(v) { if (field != v) { field = v; invalidate() } }

    var freshOtpSource: String? = null
        set(v) { if (field != v) { field = v; invalidate() } }

    var freshImageLabel: String? = null
        set(v) { if (field != v) { field = v; invalidate() } }

    var freshImageThumb: Bitmap? = null
        set(v) { if (field !== v) { field = v; invalidate() } }

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
    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val shaderMatrix = Matrix()
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
    private var longPressFired = false
    private val longPressHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val longPressRunnable = Runnable {
        val idx = pressedIndex
        if (!dragging && idx in items.indices && onLongPressPick != null) {
            longPressFired = true
            pressedIndex = -1
            invalidate()
            onLongPressPick?.invoke(idx)
        }
    }

    private val textSize get() = 17.5f * sp * prefs.candFontScale

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (45 * dp).toInt())
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

    private val menuRect = RectF()
    private val hwRect = RectF()
    private val clipRect = RectF()
    private val imgRect = RectF()
    private val freshBubbleRect = RectF()
    private val freshCloseRect = RectF()
    private val voiceRect = RectF()
    private val hideRect = RectF()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        updateToolbarRects(w.toFloat(), h.toFloat())
        relayout()
    }

    private fun updateToolbarRects(w: Float, h: Float) {
        menuRect.set(4 * dp, 4 * dp, 44 * dp, h - 4 * dp)
        hwRect.set(48 * dp, 5.5f * dp, 112 * dp, h - 5.5f * dp)
        var nextX = 116 * dp
        if (showClipboardIcon) {
            clipRect.set(nextX, 4 * dp, nextX + 40 * dp, h - 4 * dp)
            nextX += 42 * dp
        } else {
            clipRect.setEmpty()
        }
        if (showImageIcon) {
            imgRect.set(nextX, 4 * dp, nextX + 40 * dp, h - 4 * dp)
        } else {
            imgRect.setEmpty()
        }
        hideRect.set(w - 44 * dp, 4 * dp, w - 4 * dp, h - 4 * dp)
        voiceRect.set(w - 86 * dp, 4 * dp, w - 46 * dp, h - 4 * dp)
    }

    private fun relayout() {
        val w = stripW
        if (w <= 0f || items.isEmpty()) { rects = emptyList(); spans = emptyList(); contentWidth = 0f; return }
        paint.textSize = textSize
        val pad = 13.5f * dp
        val widths = items.map { maxOf(paint.measureText(it) + 2 * pad, 50 * dp) }
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
        canvas.drawColor(theme.barBackground)
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
            val isHi = i == highlight
            val isDown = i == pressedIndex
            if (isHi || isDown) {
                fill.color = when {
                    isDown -> theme.keyPressed
                    else -> theme.candidateHighlight
                }
                canvas.drawRoundRect(r.left + 4 * dp, 5.5f * dp, r.right - 4 * dp, height - 5.5f * dp, 11 * dp, 11 * dp, fill)
                if (isHi && !isDown) {
                    // Subtle accent indicator dot / bottom bar inside highlighted pill
                    fill.color = (theme.accent and 0x00FFFFFF) or 0x22000000
                    canvas.drawRoundRect(r.left + 4 * dp, 5.5f * dp, r.right - 4 * dp, height - 5.5f * dp, 11 * dp, 11 * dp, fill)
                }
            }
            paint.color = if (isHi) theme.accent else theme.text
            paint.typeface = if (isHi) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            var label = items[i]
            val maxW = r.width() - 12 * dp
            if (paint.measureText(label) > maxW) label = ellipsize(label, maxW)
            canvas.drawText(label, r.centerX(), baseline, paint)

            // Soft dot separator instead of harsh pipe lines
            if (i < items.lastIndex && i != highlight && i + 1 != highlight) {
                fill.color = theme.divider
                canvas.drawCircle(r.right, height / 2f, 1.3f * dp, fill)
            }
        }
        canvas.restore()
    }

    private fun drawToolbar(c: Canvas) {
        updateToolbarRects(width.toFloat(), height.toFloat())
        val cy = height / 2f
        val u = minOf(sp, dp * 1.12f)

        // 1) Left `[⊞]` panel launcher
        fill.color = when {
            menuActive -> theme.accent
            pressedToolbar == ToolbarAction.MENU -> theme.keyPressed
            else -> theme.funcKey
        }
        c.drawRoundRect(menuRect.left + 2 * dp, 6.5f * dp, menuRect.right - 2 * dp, height - 6.5f * dp, 10 * dp, 10 * dp, fill)
        val menuFg = if (menuActive) theme.accentText else theme.text
        KeyDraw.drawGridIcon(c, menuRect.centerX(), cy, 18f * dp, menuFg, fill, stroke, dp, active = menuActive)

        // 2) Priority 0: Fresh OTP Code -> Priority 1: Fresh Screenshot/Photo -> Priority 2: Fresh Clipboard -> Default Icons
        val otp = freshOtpCode
        val fImgLabel = freshImageLabel
        val fClip = freshClip
        if (otp != null) {
            val maxChipRight = voiceRect.left - 6 * dp
            val src = freshOtpSource?.takeIf { it.isNotBlank() } ?: "信息"
            val preview = "🔑 $otp  · 来自「$src」"
            paint.typeface = Typeface.DEFAULT_BOLD
            paint.textSize = 13.0f * u
            val availTextW = (maxChipRight - hwRect.left - 36 * dp).coerceAtLeast(48 * dp)
            val shown = ellipsize(preview, availTextW)
            val chipW = (paint.measureText(shown) + 36 * dp).coerceAtMost(maxChipRight - hwRect.left)
            freshBubbleRect.set(hwRect.left, 5.5f * dp, hwRect.left + chipW, height - 5.5f * dp)
            freshCloseRect.set(freshBubbleRect.right - 26 * dp, 5.5f * dp, freshBubbleRect.right, height - 5.5f * dp)

            fill.color = if (pressedToolbar == ToolbarAction.PASTE_OTP) theme.keyPressed else theme.candidateHighlight
            c.drawRoundRect(freshBubbleRect, 17 * dp, 17 * dp, fill)
            stroke.color = (theme.accent and 0x00FFFFFF) or 0x77000000
            stroke.strokeWidth = 1.2f * dp
            c.drawRoundRect(freshBubbleRect, 17 * dp, 17 * dp, stroke)

            paint.color = theme.accent
            paint.textAlign = Paint.Align.LEFT
            val fm = paint.fontMetrics
            c.drawText(shown, freshBubbleRect.left + 11 * dp, cy - (fm.ascent + fm.descent) / 2f, paint)
            paint.textAlign = Paint.Align.CENTER
            paint.typeface = Typeface.DEFAULT

            drawSmallCloseX(c, freshCloseRect.centerX(), cy)
        } else if (fImgLabel != null) {
            val maxChipRight = voiceRect.left - 6 * dp
            paint.typeface = Typeface.DEFAULT_BOLD
            paint.textSize = 12.8f * u
            val thumbSpace = if (freshImageThumb != null) 28 * dp else 8 * dp
            val availTextW = (maxChipRight - hwRect.left - thumbSpace - 34 * dp).coerceAtLeast(36 * dp)
            val shown = ellipsize(fImgLabel, availTextW)
            val chipW = (thumbSpace + paint.measureText(shown) + 34 * dp).coerceAtMost(maxChipRight - hwRect.left)
            freshBubbleRect.set(hwRect.left, 5.5f * dp, hwRect.left + chipW, height - 5.5f * dp)
            freshCloseRect.set(freshBubbleRect.right - 26 * dp, 5.5f * dp, freshBubbleRect.right, height - 5.5f * dp)

            fill.color = if (pressedToolbar == ToolbarAction.SEND_FRESH_IMAGE) theme.keyPressed else theme.candidateHighlight
            c.drawRoundRect(freshBubbleRect, 17 * dp, 17 * dp, fill)
            stroke.color = (theme.accent and 0x00FFFFFF) or 0x55000000
            stroke.strokeWidth = 1f * dp
            c.drawRoundRect(freshBubbleRect, 17 * dp, 17 * dp, stroke)

            var textStart = freshBubbleRect.left + 10 * dp
            freshImageThumb?.takeIf { !it.isRecycled }?.let { bmp ->
                val tr = RectF(freshBubbleRect.left + 4 * dp, freshBubbleRect.top + 3 * dp, freshBubbleRect.left + 30 * dp, freshBubbleRect.bottom - 3 * dp)
                val shader = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                val sc = maxOf(tr.width() / bmp.width, tr.height() / bmp.height)
                shaderMatrix.reset()
                shaderMatrix.setScale(sc, sc)
                shaderMatrix.postTranslate(tr.left + (tr.width() - bmp.width * sc) * 0.5f, tr.top + (tr.height() - bmp.height * sc) * 0.5f)
                shader.setLocalMatrix(shaderMatrix)
                bmpPaint.shader = shader
                c.drawRoundRect(tr, 12 * dp, 12 * dp, bmpPaint)
                bmpPaint.shader = null
                textStart = tr.right + 7 * dp
            }

            paint.color = theme.accent
            paint.textAlign = Paint.Align.LEFT
            val fm = paint.fontMetrics
            c.drawText(shown, textStart, cy - (fm.ascent + fm.descent) / 2f, paint)
            paint.textAlign = Paint.Align.CENTER
            paint.typeface = Typeface.DEFAULT

            drawSmallCloseX(c, freshCloseRect.centerX(), cy)
        } else if (fClip != null) {
            val maxChipRight = voiceRect.left - 6 * dp
            val preview = "粘贴: " + fClip.replace('\n', ' ').replace(Regex("\\s+"), " ").trim()
            paint.typeface = Typeface.DEFAULT
            paint.textSize = 13 * u
            val availTextW = (maxChipRight - hwRect.left - 38 * dp).coerceAtLeast(36 * dp)
            val shown = ellipsize(preview, availTextW)
            val chipW = (paint.measureText(shown) + 38 * dp).coerceAtMost(maxChipRight - hwRect.left)
            freshBubbleRect.set(hwRect.left, 6 * dp, hwRect.left + chipW, height - 6 * dp)
            freshCloseRect.set(freshBubbleRect.right - 26 * dp, 6 * dp, freshBubbleRect.right, height - 6 * dp)

            fill.color = if (pressedToolbar == ToolbarAction.PASTE_FRESH) theme.keyPressed else theme.candidateHighlight
            c.drawRoundRect(freshBubbleRect, 16 * dp, 16 * dp, fill)
            paint.color = theme.text
            paint.textAlign = Paint.Align.LEFT
            val fm = paint.fontMetrics
            c.drawText(shown, freshBubbleRect.left + 11 * dp, cy - (fm.ascent + fm.descent) / 2f, paint)
            paint.textAlign = Paint.Align.CENTER

            drawSmallCloseX(c, freshCloseRect.centerX(), cy)
        } else {
            freshBubbleRect.setEmpty()
            freshCloseRect.setEmpty()

            // `[✍ 手写 / ⌨ 键盘]` pill
            fill.color = when {
                handwritingActive -> (theme.accent and 0x00FFFFFF) or 0x26000000
                pressedToolbar == ToolbarAction.HANDWRITING_TOGGLE -> theme.keyPressed
                else -> theme.funcKey
            }
            c.drawRoundRect(hwRect, 10 * dp, 10 * dp, fill)
            val hwColor = if (handwritingActive) theme.accent else theme.text
            val iconX = hwRect.left + 14 * dp
            if (handwritingActive) {
                KeyDraw.drawKeyboardIcon(c, iconX, cy, 16f * dp, hwColor, fill, stroke, dp)
            } else {
                KeyDraw.drawHandwritingIcon(c, iconX, cy, 15.5f * dp, hwColor, stroke, path, dp)
            }
            paint.typeface = if (handwritingActive) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            paint.textSize = 12.8f * u
            paint.color = hwColor
            val fm = paint.fontMetrics
            c.drawText(if (handwritingActive) "键盘" else "手写", hwRect.left + 41 * dp, cy - (fm.ascent + fm.descent) / 2f, paint)

            // `[📋]` Clipboard icon (hidden when clipboard feature is switched off)
            if (showClipboardIcon) {
                if (pressedToolbar == ToolbarAction.CLIPBOARD) {
                    fill.color = theme.keyPressed
                    c.drawRoundRect(clipRect, 9 * dp, 9 * dp, fill)
                }
                KeyDraw.drawClipboardIcon(c, clipRect.centerX(), cy, 18.5f * dp, theme.text, stroke, dp)
                if (hasClips) {
                    fill.color = theme.accent
                    c.drawCircle(clipRect.centerX() + 7.5f * dp, cy - 7.5f * dp, 2.5f * dp, fill)
                }
            }

            // `[🖼]` Recent Photos & Screenshots icon (shown only when editor accepts images)
            if (showImageIcon && !imgRect.isEmpty && imgRect.right <= voiceRect.left - 2 * dp) {
                if (pressedToolbar == ToolbarAction.IMAGES) {
                    fill.color = theme.keyPressed
                    c.drawRoundRect(imgRect, 9 * dp, 9 * dp, fill)
                }
                KeyDraw.drawImageIcon(c, imgRect.centerX(), cy, 18.5f * dp, theme.text, fill, stroke, path, dp)
            }
        }

        // 3) Right Voice icon + Collapse chevron
        if (pressedToolbar == ToolbarAction.VOICE) {
            fill.color = theme.keyPressed
            c.drawRoundRect(voiceRect, 9 * dp, 9 * dp, fill)
        }
        KeyDraw.drawMicIcon(c, voiceRect.centerX(), cy, 18.5f * dp, theme.text, stroke, dp)

        if (pressedToolbar == ToolbarAction.HIDE) {
            fill.color = theme.keyPressed
            c.drawRoundRect(hideRect, 9 * dp, 9 * dp, fill)
        }
        KeyDraw.drawChevron(c, hideRect.centerX(), cy, 5.8f * dp, 1, theme.text, stroke, dp)
    }

    private fun drawSmallCloseX(c: Canvas, xcx: Float, cy: Float) {
        val xr = 3.8f * dp
        stroke.color = theme.textSecondary
        stroke.strokeWidth = 1.4f * dp
        c.drawLine(xcx - xr, cy - xr, xcx + xr, cy + xr, stroke)
        c.drawLine(xcx - xr, cy + xr, xcx + xr, cy - xr, stroke)
    }

    private fun drawPinnedMenuIcon(c: Canvas) {
        val cy = height / 2f
        if (leftMenuDown || menuActive) {
            fill.color = if (menuActive) theme.accent else theme.keyPressed
            c.drawRoundRect(4 * dp, 6 * dp, buttonW - 4 * dp, height - 6 * dp, 9 * dp, 9 * dp, fill)
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
            c.drawRoundRect(x0 + 4 * dp, 5 * dp, width - 4 * dp, height - 5 * dp, 9 * dp, 9 * dp, fill)
        }
        val cx = x0 + buttonW / 2
        val cy = height / 2f
        val dir = if (!isHide && expanded) -1 else 1
        KeyDraw.drawChevron(c, cx, cy, 5.8f * dp, dir, theme.text, stroke, dp)
    }

    private fun ellipsize(s: String, maxW: Float): String {
        if (paint.measureText(s) <= maxW) return s
        var end = s.length
        while (end > 1 && paint.measureText(s, 0, end) + paint.measureText("…") > maxW) end--
        return s.substring(0, end) + "…"
    }

    private fun toolbarHit(x: Float, y: Float): ToolbarAction? {
        if (menuRect.contains(x, y)) return ToolbarAction.MENU
        if (freshOtpCode != null) {
            if (freshCloseRect.contains(x, y)) return ToolbarAction.DISMISS_OTP
            if (freshBubbleRect.contains(x, y)) return ToolbarAction.PASTE_OTP
        } else if (freshImageLabel != null) {
            if (freshCloseRect.contains(x, y)) return ToolbarAction.DISMISS_FRESH_IMAGE
            if (freshBubbleRect.contains(x, y)) return ToolbarAction.SEND_FRESH_IMAGE
        } else if (freshClip != null) {
            if (freshCloseRect.contains(x, y)) return ToolbarAction.DISMISS_FRESH
            if (freshBubbleRect.contains(x, y)) return ToolbarAction.PASTE_FRESH
        } else {
            if (hwRect.contains(x, y)) return ToolbarAction.HANDWRITING_TOGGLE
            if (clipRect.contains(x, y)) return ToolbarAction.CLIPBOARD
            if (imgRect.right <= voiceRect.left - 2 * dp && imgRect.contains(x, y)) return ToolbarAction.IMAGES
        }
        if (voiceRect.contains(x, y)) return ToolbarAction.VOICE
        if (hideRect.contains(x, y)) return ToolbarAction.HIDE
        return null
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (items.isEmpty()) {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { pressedToolbar = toolbarHit(e.x, e.y); invalidate() }
                MotionEvent.ACTION_MOVE -> {
                    if (toolbarHit(e.x, e.y) != pressedToolbar) { pressedToolbar = null; invalidate() }
                }
                MotionEvent.ACTION_UP -> {
                    val act = toolbarHit(e.x, e.y)
                    if (act != null && act == pressedToolbar) onToolbarAction?.invoke(act)
                    pressedToolbar = null; invalidate()
                }
                MotionEvent.ACTION_CANCEL -> { pressedToolbar = null; invalidate() }
            }
            return true
        }

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

        if (velocity == null) velocity = VelocityTracker.obtain()
        velocity!!.addMovement(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scroller.forceFinished(true)
                downX = e.x; lastX = e.x; dragging = false; longPressFired = false
                pressedIndex = indexAt(e.x)
                longPressHandler.removeCallbacks(longPressRunnable)
                if (pressedIndex in items.indices && onLongPressPick != null) {
                    longPressHandler.postDelayed(longPressRunnable, 450L)
                }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && abs(e.x - downX) > slop) {
                    longPressHandler.removeCallbacks(longPressRunnable)
                    if (maxScroll() > 0f) { dragging = true; pressedIndex = -1 }
                }
                if (dragging) {
                    scroll = (scroll - (e.x - lastX)).coerceIn(0f, maxScroll())
                    invalidate()
                }
                lastX = e.x
            }
            MotionEvent.ACTION_UP -> {
                longPressHandler.removeCallbacks(longPressRunnable)
                if (dragging) {
                    velocity!!.computeCurrentVelocity(1000)
                    scroller.fling(scroll.toInt(), 0, -velocity!!.xVelocity.toInt(), 0, 0, maxScroll().toInt(), 0, 0)
                    postInvalidateOnAnimation()
                } else if (!longPressFired) {
                    val i = indexAt(e.x)
                    if (i in items.indices) onPick(i)
                }
                longPressFired = false
                pressedIndex = -1
                velocity?.recycle(); velocity = null
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                longPressHandler.removeCallbacks(longPressRunnable)
                longPressFired = false
                pressedIndex = -1; dragging = false
                velocity?.recycle(); velocity = null
                invalidate()
            }
        }
        return true
    }
}
