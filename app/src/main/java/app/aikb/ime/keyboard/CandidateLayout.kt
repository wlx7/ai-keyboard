package app.aikb.ime.keyboard

/** Pure geometry for the candidate strip, kept free of Android classes so it is unit-tested. */
object CandidateLayout {
    data class Span(val left: Float, val right: Float)

    /**
     * Up to three short candidates get iOS-style equal thirds of the bar; otherwise candidates are
     * laid out back to back at their natural widths and the strip scrolls.
     */
    fun spans(widths: List<Float>, viewWidth: Float): List<Span> {
        if (viewWidth <= 0f || widths.isEmpty()) return emptyList()
        return if (widths.size <= 3 && widths.all { it <= viewWidth / 3f }) {
            val slot = viewWidth / 3f
            widths.indices.map { Span(it * slot, (it + 1) * slot) }
        } else {
            var x = 0f
            widths.map { w -> Span(x, x + w).also { x += w } }
        }
    }

    /** Index of the candidate at content x (view x + scroll), or -1. */
    fun indexAt(spans: List<Span>, contentX: Float): Int {
        for (i in spans.indices) if (contentX >= spans[i].left && contentX < spans[i].right) return i
        return -1
    }
}
