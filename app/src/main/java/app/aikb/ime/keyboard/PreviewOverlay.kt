package app.aikb.ime.keyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View

/**
 * Transparent layer over the whole keyboard window. Draws the iOS-style key balloon and the
 * long-press alternates strip. It never consumes touches.
 */
class PreviewOverlay(context: Context) : View(context) {
    private val dp = resources.displayMetrics.density
    private val sp = resources.displayMetrics.scaledDensity
    var theme: KbTheme = KbTheme.of(context)

    private var previewLabel: String? = null
    private val previewKey = RectF()
    private var alts: List<String> = emptyList()
    private var altSel = 0
    private val altsRect = RectF()
    private var altCell = 0f

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val path = Path()
    private val tmp = Path()
    private val loc = IntArray(2)
    private val myLoc = IntArray(2)

    init {
        isClickable = false
        isFocusable = false
    }

    /**
     * Must not inflate the keyboard window: under a wrap_content parent report zero, then the
     * FrameLayout re-measures match_parent children with the exact size of the keyboard.
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        fun size(spec: Int) = if (MeasureSpec.getMode(spec) == MeasureSpec.EXACTLY) MeasureSpec.getSize(spec) else 0
        setMeasuredDimension(size(widthMeasureSpec), size(heightMeasureSpec))
    }

    private fun toLocal(src: View, r: RectF): RectF {
        src.getLocationInWindow(loc)
        getLocationInWindow(myLoc)
        return RectF(r).apply { offset((loc[0] - myLoc[0]).toFloat(), (loc[1] - myLoc[1]).toFloat()) }
    }

    fun showPreview(label: String, keyRect: RectF, src: View) {
        alts = emptyList()
        previewLabel = label
        previewKey.set(toLocal(src, keyRect))
        invalidate()
    }

    fun showAlts(list: List<String>, keyRect: RectF, src: View) {
        previewLabel = null
        alts = list
        altSel = 0
        val k = toLocal(src, keyRect)
        altCell = maxOf(k.width(), 34 * dp)
        val w = altCell * list.size + 8 * dp
        var left = k.left - 4 * dp
        if (left + w > width - 4 * dp) left = (width - 4 * dp - w).coerceAtLeast(4 * dp)
        val h = k.height() + 10 * dp
        altsRect.set(left, k.top - h - 4 * dp, left + w, k.top - 4 * dp)
        if (altsRect.top < 0) altsRect.offset(0f, -altsRect.top)
        previewKey.set(k)
        invalidate()
    }

    /** Which alternate is under the finger; x is in [src] coordinates. */
    fun altIndexAt(x: Float, src: View): Int {
        if (alts.isEmpty()) return 0
        src.getLocationInWindow(loc); getLocationInWindow(myLoc)
        val lx = x + loc[0] - myLoc[0]
        return ((lx - altsRect.left - 4 * dp) / altCell).toInt().coerceIn(0, alts.lastIndex)
    }

    fun setAltSelection(i: Int) { altSel = i; invalidate() }

    fun hide() {
        if (previewLabel == null && alts.isEmpty()) return
        previewLabel = null; alts = emptyList(); invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        previewLabel?.let { drawBalloon(canvas, it) }
        if (alts.isNotEmpty()) drawAlts(canvas)
    }

    private fun drawBalloon(c: Canvas, label: String) {
        val k = previewKey
        val bw = k.width() * 1.5f + 6 * dp
        val bh = k.height() * 1.25f
        val top = (k.top - bh - 2 * dp).coerceAtLeast(0f)
        val left = (k.centerX() - bw / 2).coerceIn(2 * dp, width - bw - 2 * dp)
        val r = 9 * dp
        path.reset()
        path.addRoundRect(left, top, left + bw, top + bh, r, r, Path.Direction.CW)
        tmp.reset()
        tmp.addRoundRect(k.left, k.top - 12 * dp, k.right, k.bottom, 5.5f * dp, 5.5f * dp, Path.Direction.CW)
        path.op(tmp, Path.Op.UNION)
        fill.color = theme.keyShadow
        fill.alpha = 90
        c.save(); c.translate(0f, 1.5f * dp); c.drawPath(path, fill); c.restore()
        fill.color = theme.popup
        c.drawPath(path, fill)
        text.color = theme.text
        text.textSize = 32 * sp
        val fm = text.fontMetrics
        c.drawText(label, left + bw / 2, top + bh / 2 - (fm.ascent + fm.descent) / 2, text)
    }

    private fun drawAlts(c: Canvas) {
        val r = 9 * dp
        fill.color = theme.keyShadow; fill.alpha = 90
        c.drawRoundRect(altsRect.left, altsRect.top + 1.5f * dp, altsRect.right, altsRect.bottom + 1.5f * dp, r, r, fill)
        fill.color = theme.popup
        c.drawRoundRect(altsRect, r, r, fill)
        text.textSize = 22 * sp
        val fm = text.fontMetrics
        alts.forEachIndexed { i, s ->
            val l = altsRect.left + 4 * dp + i * altCell
            val cell = RectF(l + 2 * dp, altsRect.top + 5 * dp, l + altCell - 2 * dp, altsRect.bottom - 5 * dp)
            if (i == altSel) {
                fill.color = theme.accent
                c.drawRoundRect(cell, 6 * dp, 6 * dp, fill)
                text.color = theme.accentText
            } else text.color = theme.text
            c.drawText(s, cell.centerX(), cell.centerY() - (fm.ascent + fm.descent) / 2, text)
        }
    }
}
