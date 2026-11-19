package app.aikb.ime.keyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View

/**
 * Refined 27dp preedit composition strip shown right above [CandidateBar] while typing Chinese
 * pinyin / Wubi / Cangjie codes, styled as a subtle floating pill on [KbTheme.barBackground].
 */
class PreeditBar(context: Context) : View(context) {

    private val dp = resources.displayMetrics.density
    private val sp = resources.displayMetrics.scaledDensity

    var theme: KbTheme = KbTheme.of(context)
        set(v) { field = v; invalidate() }

    var text: String = ""
        set(v) {
            if (field == v) return
            field = v
            val wantVis = if (v.isNotEmpty()) VISIBLE else GONE
            if (visibility != wantVis) visibility = wantVis
            invalidate()
        }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textAlign = Paint.Align.LEFT
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pillRect = RectF()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (27 * dp).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        if (text.isEmpty()) return
        canvas.drawColor(theme.barBackground)
        fill.color = theme.divider
        canvas.drawRect(0f, 0f, width.toFloat(), 0.5f * dp, fill)

        val u = minOf(sp, dp * 1.12f)
        textPaint.textSize = 14.0f * u
        textPaint.color = theme.text
        textPaint.letterSpacing = 0.03f

        val padX = 12 * dp
        val availW = (width - padX * 2 - 12 * dp).coerceAtLeast(24f)
        val fullW = textPaint.measureText(text)

        val pillW = (fullW + 18 * dp).coerceAtMost(width - 14 * dp)
        pillRect.set(7 * dp, 2.5f * dp, 7 * dp + pillW, height - 1.5f * dp)
        fill.color = theme.candidateHighlight
        canvas.drawRoundRect(pillRect, 9 * dp, 9 * dp, fill)

        val innerLeft = pillRect.left + 8 * dp
        val innerRight = pillRect.right - 6 * dp
        val drawX = if (fullW > availW) innerRight - 4 * dp - fullW else innerLeft

        val fm = textPaint.fontMetrics
        val baseline = pillRect.centerY() - (fm.ascent + fm.descent) / 2f

        canvas.save()
        canvas.clipRect(innerLeft, 0f, innerRight, height.toFloat())
        canvas.drawText(text, drawX, baseline, textPaint)

        val caretX = (drawX + fullW + 2 * dp).coerceAtMost(innerRight - 2 * dp)
        fill.color = theme.accent
        canvas.drawRoundRect(caretX, pillRect.top + 4.5f * dp, caretX + 2f * dp, pillRect.bottom - 4.5f * dp, dp, dp, fill)
        canvas.restore()
    }
}
