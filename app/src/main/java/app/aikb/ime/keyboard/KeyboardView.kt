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

enum class ShiftState { OFF, ONCE, LOCK }

@SuppressLint("ViewConstructor")
class KeyboardView(context: Context, private val listener: Listener) : View(context) {

    interface Listener {
        fun onText(key: Key, text: String)
        fun onFunction(key: Key)
        fun onDeleteDown(repeat: Boolean)
        fun onCursorMove(steps: Int)
        fun onLongPressFunction(key: Key)
        fun onKeyDownFeedback(key: Key)
    }

    private val dp = resources.displayMetrics.density
    private val sp = resources.displayMetrics.scaledDensity

    var theme: KbTheme = KbTheme.of(context)
        set(v) { field = v; invalidate() }
    var layout: Layout = Layouts.letters(BottomRow.TEXT, false)
        set(v) { field = v; cancelAll(); requestLayout(); computeRects(); invalidate() }
    var shift: ShiftState = ShiftState.OFF
        set(v) { field = v; invalidate() }
    var chinese = false
        set(v) { field = v; invalidate() }
    var enterLabel = "return"
        set(v) { field = v; invalidate() }
    var enterAccent = false
        set(v) { field = v; invalidate() }

    /** Small glyphs shown in the corner of letter keys (五笔/仓颉 radicals). */
    var hints: Map<Char, String> = emptyMap()
        set(v) { field = v; invalidate() }

    /** Draws key previews / alternates above the keys, may extend over the candidate bar. */
    var previewOverlay: PreviewOverlay? = null

    private val topPad = 8 * dp
    private val bottomPad = 4 * dp
    private val sidePad = 3 * dp
    private val rowPitch = 54 * dp
    private val hGap = 6 * dp
    private val vGap = 11 * dp
    private val radius = 5.5f * dp

    private var rects: List<List<RectF>> = emptyList()

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.create("sans-serif", Typeface.NORMAL) }
    private val path = Path()

    private val handler = Handler(Looper.getMainLooper())

    private inner class Touch(val id: Int, var key: Key, var rect: RectF, val downX: Float) {
        var longPressed = false
        var alts: List<String> = emptyList()
        var altIndex = 0
        var cursorMode = false
        var lastCursorX = downX
        var repeats = 0
        val longPress = Runnable { onLongPress(this) }
        val repeat: Runnable = object : Runnable {
            override fun run() {
                repeats++
                listener.onDeleteDown(true)
                handler.postDelayed(this, if (repeats > 15) 35L else 70L)
            }
        }
    }

    private val touches = LinkedHashMap<Int, Touch>()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = (topPad + layout.rows.size * rowPitch + bottomPad).toInt()
        setMeasuredDimension(w, h)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = computeRects()

    private fun computeRects() {
        if (width == 0) return
        val unit = (width - 2 * sidePad) / layout.units
        rects = layout.rows.mapIndexed { r, row ->
            val total = row.keys.sumOf { it.width.toDouble() }.toFloat()
            var x = sidePad + (layout.units - total) / 2f * unit
            val top = topPad + r * rowPitch
            row.keys.map { k ->
                val rect = RectF(x + hGap / 2, top + vGap / 2, x + k.width * unit - hGap / 2, top + rowPitch - vGap / 2)
                x += k.width * unit
                rect
            }
        }
    }

    // ---------------------------------------------------------------- drawing

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(theme.background)
        val pressed = touches.values.map { it.key }.toSet()
        layout.rows.forEachIndexed { r, row ->
            row.keys.forEachIndexed { i, key ->
                if (key.type != KeyType.SPACER) drawKey(canvas, key, rects[r][i], key in pressed)
            }
        }
    }

    private fun drawKey(c: Canvas, key: Key, rc: RectF, pressed: Boolean) {
        val func = key.isFunctional
        val active = (key.type == KeyType.SHIFT && shift != ShiftState.OFF)
        val accentEnter = key.type == KeyType.ENTER && enterAccent
        val bg = when {
            accentEnter -> if (pressed) theme.funcKey else theme.accent
            active -> theme.key
            func -> if (pressed) theme.funcKeyPressed else theme.funcKey
            else -> if (pressed) theme.keyPressed else theme.key
        }
        fill.color = theme.keyShadow
        c.drawRoundRect(rc.left, rc.top + 1.2f * dp, rc.right, rc.bottom + 1.2f * dp, radius, radius, fill)
        fill.color = bg
        c.drawRoundRect(rc, radius, radius, fill)

        val fg = if (accentEnter && !pressed) theme.accentText else theme.text
        val cx = rc.centerX()
        val cy = rc.centerY()
        when (key.type) {
            KeyType.SHIFT -> drawShift(c, cx, cy, 20 * dp, fg)
            KeyType.DELETE -> drawDelete(c, cx, cy, 22 * dp, fg, pressed)
            KeyType.EMOJI -> drawEmoji(c, cx, cy, 21 * dp, fg)
            KeyType.LANG -> drawLang(c, cx, cy, fg)
            KeyType.ENTER -> drawLabel(c, enterLabel, cx, cy, 15.5f * sp, fg)
            KeyType.SPACE -> drawLabel(c, key.label, cx, cy, 15.5f * sp, fg)
            KeyType.TO_SYMBOLS, KeyType.TO_SYMBOLS2, KeyType.TO_LETTERS -> drawLabel(c, key.label, cx, cy, 15.5f * sp, fg)
            KeyType.TEXT -> drawLabel(c, key.label, cx, cy, 16 * sp, fg)
            KeyType.CHAR -> {
                val label = displayLabel(key)
                val size = if (key.isLetter) 23.5f * sp else if (label.length > 1) 16 * sp else 22 * sp
                drawLabel(c, label, cx, cy, size, fg)
                if (key.isLetter) hints[key.label[0]]?.let { h ->
                    text.textAlign = Paint.Align.RIGHT
                    drawLabel(c, h, rc.right - 4 * dp, rc.top + 9 * dp, 10.5f * sp, theme.textSecondary)
                    text.textAlign = Paint.Align.CENTER
                }
            }
            KeyType.SPACER -> Unit
        }
    }

    fun displayLabel(key: Key): String =
        if (key.isLetter && shift != ShiftState.OFF) key.label.uppercase() else key.label

    private fun drawLabel(c: Canvas, s: String, cx: Float, cy: Float, size: Float, color: Int) {
        text.textSize = size
        text.color = color
        text.isFakeBoldText = false
        val fm = text.fontMetrics
        c.drawText(s, cx, cy - (fm.ascent + fm.descent) / 2, text)
    }

    private fun drawShift(c: Canvas, cx: Float, cy: Float, s: Float, color: Int) {
        val locked = shift == ShiftState.LOCK
        val dy = if (locked) -0.08f * s else 0f
        path.reset()
        path.moveTo(cx, cy - 0.46f * s + dy)
        path.lineTo(cx + 0.46f * s, cy + 0.02f * s + dy)
        path.lineTo(cx + 0.2f * s, cy + 0.02f * s + dy)
        path.lineTo(cx + 0.2f * s, cy + 0.4f * s + dy)
        path.lineTo(cx - 0.2f * s, cy + 0.4f * s + dy)
        path.lineTo(cx - 0.2f * s, cy + 0.02f * s + dy)
        path.lineTo(cx - 0.46f * s, cy + 0.02f * s + dy)
        path.close()
        if (shift != ShiftState.OFF) {
            fill.color = color
            c.drawPath(path, fill)
        } else {
            stroke.color = color; stroke.strokeWidth = 1.6f * dp
            c.drawPath(path, stroke)
        }
        if (locked) {
            fill.color = color
            c.drawRoundRect(cx - 0.2f * s, cy + 0.44f * s, cx + 0.2f * s, cy + 0.56f * s, dp, dp, fill)
        }
    }

    private fun drawDelete(c: Canvas, cx: Float, cy: Float, s: Float, color: Int, pressed: Boolean) {
        path.reset()
        path.moveTo(cx - 0.55f * s, cy)
        path.lineTo(cx - 0.22f * s, cy - 0.36f * s)
        path.lineTo(cx + 0.5f * s, cy - 0.36f * s)
        path.lineTo(cx + 0.5f * s, cy + 0.36f * s)
        path.lineTo(cx - 0.22f * s, cy + 0.36f * s)
        path.close()
        stroke.color = color; stroke.strokeWidth = 1.6f * dp
        if (pressed) { fill.color = color; c.drawPath(path, fill) } else c.drawPath(path, stroke)
        val x0 = cx + 0.12f * s
        val r = 0.15f * s
        stroke.color = if (pressed) theme.funcKeyPressed else color
        c.drawLine(x0 - r, cy - r, x0 + r, cy + r, stroke)
        c.drawLine(x0 - r, cy + r, x0 + r, cy - r, stroke)
    }

    private fun drawEmoji(c: Canvas, cx: Float, cy: Float, s: Float, color: Int) {
        stroke.color = color; stroke.strokeWidth = 1.6f * dp
        c.drawCircle(cx, cy, s / 2, stroke)
        fill.color = color
        c.drawCircle(cx - 0.17f * s, cy - 0.1f * s, 0.06f * s, fill)
        c.drawCircle(cx + 0.17f * s, cy - 0.1f * s, 0.06f * s, fill)
        c.drawArc(cx - 0.25f * s, cy - 0.15f * s, cx + 0.25f * s, cy + 0.28f * s, 20f, 140f, false, stroke)
    }

    private fun drawLang(c: Canvas, cx: Float, cy: Float, color: Int) {
        text.textSize = 15.5f * sp
        val fm = text.fontMetrics
        val y = cy - (fm.ascent + fm.descent) / 2
        val zh = "中"; val sep = "/"; val en = "英"
        val wz = text.measureText(zh); val ws = text.measureText(sep); val we = text.measureText(en)
        var x = cx - (wz + ws + we) / 2
        val dim = (color and 0x00FFFFFF) or 0x66000000
        text.textAlign = Paint.Align.LEFT
        text.color = if (chinese) color else dim; c.drawText(zh, x, y, text); x += wz
        text.color = dim; c.drawText(sep, x, y, text); x += ws
        text.color = if (chinese) dim else color; c.drawText(en, x, y, text)
        text.textAlign = Paint.Align.CENTER
    }

    // ---------------------------------------------------------------- touch

    private fun keyAt(x: Float, y: Float): Pair<Key, RectF>? {
        if (rects.isEmpty()) return null
        val r = ((y - topPad) / rowPitch).toInt().coerceIn(0, layout.rows.size - 1)
        val keys = layout.rows[r].keys
        var best = -1
        var bestDist = Float.MAX_VALUE
        keys.forEachIndexed { i, k ->
            if (k.type == KeyType.SPACER) return@forEachIndexed
            val rc = rects[r][i]
            val d = when {
                x < rc.left - hGap / 2 -> rc.left - x
                x > rc.right + hGap / 2 -> x - rc.right
                else -> 0f
            }
            if (d < bestDist) { bestDist = d; best = i }
        }
        return if (best < 0) null else keys[best] to rects[r][best]
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = e.actionIndex
                // Rolling input: a new finger landing commits keys still held by other fingers.
                for (t in touches.values.toList()) {
                    if (!t.longPressed && !t.cursorMode && (t.key.type == KeyType.CHAR || t.key.type == KeyType.TEXT)) {
                        release(t, commit = true)
                        touches.remove(t.id)
                    }
                }
                val hit = keyAt(e.getX(idx), e.getY(idx)) ?: return true
                val t = Touch(e.getPointerId(idx), hit.first, hit.second, e.getX(idx))
                touches[t.id] = t
                press(t)
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until e.pointerCount) {
                    val t = touches[e.getPointerId(i)] ?: continue
                    move(t, e.getX(i), e.getY(i))
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val id = e.getPointerId(e.actionIndex)
                touches.remove(id)?.let { release(it, commit = true) }
            }
            MotionEvent.ACTION_CANCEL -> cancelAll()
        }
        invalidate()
        return true
    }

    private fun press(t: Touch) {
        listener.onKeyDownFeedback(t.key)
        when (t.key.type) {
            KeyType.DELETE -> {
                listener.onDeleteDown(false)
                handler.postDelayed(t.repeat, 400)
            }
            KeyType.SHIFT -> listener.onFunction(t.key)
            KeyType.CHAR, KeyType.TEXT -> {
                showPreview(t)
                if (t.key.alts.isNotEmpty()) handler.postDelayed(t.longPress, 330)
            }
            KeyType.SPACE -> handler.postDelayed(t.longPress, 450)
            KeyType.LANG, KeyType.EMOJI -> handler.postDelayed(t.longPress, 450)
            else -> Unit
        }
    }

    private fun move(t: Touch, x: Float, y: Float) {
        when {
            t.longPressed && t.alts.isNotEmpty() -> {
                val i = previewOverlay?.altIndexAt(x, this) ?: 0
                if (i != t.altIndex) { t.altIndex = i; previewOverlay?.setAltSelection(i) }
            }
            t.key.type == KeyType.SPACE -> {
                if (!t.cursorMode && abs(x - t.downX) > 14 * dp) {
                    t.cursorMode = true
                    t.lastCursorX = x
                    handler.removeCallbacks(t.longPress)
                }
                if (t.cursorMode) {
                    val step = 11 * dp
                    val steps = ((x - t.lastCursorX) / step).toInt()
                    if (steps != 0) { listener.onCursorMove(steps); t.lastCursorX += steps * step }
                }
            }
            t.key.type == KeyType.CHAR || t.key.type == KeyType.TEXT -> {
                // Sliding onto a neighbouring key retargets the press (commit happens on release).
                val hit = keyAt(x, y) ?: return
                if (hit.first !== t.key && (hit.first.type == KeyType.CHAR || hit.first.type == KeyType.TEXT)) {
                    handler.removeCallbacks(t.longPress)
                    t.key = hit.first; t.rect = hit.second
                    showPreview(t)
                    if (t.key.alts.isNotEmpty()) handler.postDelayed(t.longPress, 330)
                }
            }
        }
    }

    private fun onLongPress(t: Touch) {
        if (touches[t.id] !== t) return
        when (t.key.type) {
            KeyType.CHAR, KeyType.TEXT -> {
                t.longPressed = true
                t.alts = t.key.alts.map { if (shift != ShiftState.OFF && t.key.isLetter) it.uppercase() else it }
                t.altIndex = 0
                previewOverlay?.showAlts(t.alts, t.rect, this)
            }
            KeyType.SPACE -> {
                t.cursorMode = true // iOS-style trackpad: hold space, then slide
                listener.onKeyDownFeedback(t.key)
            }
            else -> {
                t.longPressed = true
                listener.onLongPressFunction(t.key)
            }
        }
        invalidate()
    }

    private fun release(t: Touch, commit: Boolean) {
        handler.removeCallbacks(t.longPress)
        handler.removeCallbacks(t.repeat)
        previewOverlay?.hide()
        if (!commit) return
        when {
            t.longPressed && t.alts.isNotEmpty() -> listener.onText(t.key, t.alts[t.altIndex.coerceIn(0, t.alts.lastIndex)])
            t.longPressed -> Unit
            t.cursorMode -> Unit
            t.key.type == KeyType.CHAR || t.key.type == KeyType.TEXT -> listener.onText(t.key, if (t.key.isLetter) displayLabel(t.key) else t.key.text)
            t.key.type == KeyType.DELETE || t.key.type == KeyType.SHIFT -> Unit // handled on down
            else -> listener.onFunction(t.key)
        }
    }

    private fun showPreview(t: Touch) {
        // Previews only make sense for single glyph keys on full keyboards (not number pads).
        if (t.key.width > 1.6f) { previewOverlay?.hide(); return }
        previewOverlay?.showPreview(displayLabel(t.key), t.rect, this)
    }

    fun cancelAll() {
        for (t in touches.values) release(t, commit = false)
        touches.clear()
        invalidate()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cancelAll()
    }
}
