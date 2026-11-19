package app.aikb.ime.keyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View

/**
 * Compact 26dp preedit composition strip shown right above [CandidateBar] while typing Chinese
 * pinyin / Wubi / Cangjie codes, ensuring the user always sees their exact input syllables even
 * in apps, browsers, or WebViews that hide inline composing text.
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
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        textAlign = Paint.Align.LEFT
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (26 * dp).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        if (text.isEmpty()) return
        canvas.drawColor(theme.background)
        // Top hair-line border
        fill.color = theme.divider
        canvas.drawRect(0f, 0f, width.toFloat(), 0.5f * dp, fill)

        val u = minOf(sp, dp * 1.12f)
        textPaint.textSize = 14.5f * u
        textPaint.color = theme.text
        textPaint.letterSpacing = 0.02f

        val padX = 12 * dp
        val availW = (width - padX * 2 - 6 * dp).coerceAtLeast(20f)
        val fullW = textPaint.measureText(text)
        val drawX = if (fullW > availW) padX - (fullW - availW) else padX

        val fm = textPaint.fontMetrics
        val baseline = height / 2f - (fm.ascent + fm.descent) / 2f

        canvas.save()
        canvas.clipRect(padX, 0f, width - padX, height.toFloat())
        canvas.drawText(text, drawX, baseline, textPaint)

        // Subtle accent caret right after the preedit text
        val caretX = (drawX + fullW + 2 * dp).coerceAtMost(width - padX - 1.5f * dp)
        fill.color = theme.accent
        canvas.drawRoundRect(caretX, 6 * dp, caretX + 1.8f * dp, height - 6 * dp, dp, dp, fill)
        canvas.restore()
    }
}
