package app.aikb.ime.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class AiRankerTest {

    @Test
    fun promotesFluentContextualAndPoetryCandidatesWhileBlockingGibberish() {
        // 1. Contextual 2-syllable disambiguation ("秋水" + "shizhi": "是指" vs "时至")
        val twoSylCands = listOf("是指", "实质", "市值", "时至")
        val twoSylScored = AiRanker.Scored(
            indices = listOf(0, 1, 2, 3),
            lm = floatArrayOf(-11.2f, -10.5f, -11.8f, -4.1f),
        )
        assertEquals(3, AiRanker.best(twoSylScored, twoSylCands, a = 2.6f, useCache = false, m = 0.75f))

        // 2. 7-char sentence ("白云生处有人家" vs "白云深处有人家")
        val poemCands = listOf("白云生处有人家", "白云深处有人家")
        val poemScored = AiRanker.Scored(
            indices = listOf(0, 1),
            lm = floatArrayOf(-14.5f, -6.2f),
        )
        assertEquals(1, AiRanker.best(poemScored, poemCands, a = 2.6f, useCache = false, m = 0.95f))

        // 3. Low-probability stitched gibberish ("杆菌最详尽" vs "饭均最详尽", both terrible < -30)
        val gibberishCands = listOf("杆菌最详尽", "饭均最详尽")
        val gibberishScored = AiRanker.Scored(
            indices = listOf(0, 1),
            lm = floatArrayOf(-38.0f, -33.5f),
        )
        // Must stick with Rime's default (index 0) instead of promoting gibberish with `✦`
        assertEquals(0, AiRanker.best(gibberishScored, gibberishCands, a = 2.6f, useCache = false, m = 0.95f))
    }
}
