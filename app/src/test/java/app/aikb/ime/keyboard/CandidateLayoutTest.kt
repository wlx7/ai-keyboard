package app.aikb.ime.keyboard

import org.junit.Assert.assertEquals
import org.junit.Test

/** Regression guard for the CyIME bug: with two English candidates the second was untappable. */
class CandidateLayoutTest {
    private val width = 1080f

    private fun assertAllTappable(widths: List<Float>) {
        val spans = CandidateLayout.spans(widths, width)
        assertEquals(widths.size, spans.size)
        spans.forEachIndexed { i, s ->
            val mid = (s.left + s.right) / 2
            assertEquals("candidate $i of ${widths.size} at x=$mid", i, CandidateLayout.indexAt(spans, mid))
        }
    }

    @Test fun oneCandidate() = assertAllTappable(listOf(150f))
    @Test fun twoCandidates() = assertAllTappable(listOf(150f, 200f))
    @Test fun threeCandidates() = assertAllTappable(listOf(150f, 200f, 120f))
    @Test fun manyCandidates() = assertAllTappable(List(12) { 140f + it * 7 })
    @Test fun twoWideCandidates() = assertAllTappable(listOf(500f, 700f))

    @Test fun twoCandidatesHaveDistinctSlots() {
        val spans = CandidateLayout.spans(listOf(150f, 150f), width)
        assertEquals(0, CandidateLayout.indexAt(spans, 10f))
        assertEquals(1, CandidateLayout.indexAt(spans, width / 3f + 10f))
        assertEquals(-1, CandidateLayout.indexAt(spans, width - 10f)) // empty third slot
    }
}
