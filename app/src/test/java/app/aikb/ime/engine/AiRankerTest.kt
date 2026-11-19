package app.aikb.ime.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

class AiRankerTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun loadCharWeights() {
            val candidates = listOf(
                File("src/main/assets/rime/cn_dicts/8105.dict.yaml"),
                File("app/src/main/assets/rime/cn_dicts/8105.dict.yaml"),
            )
            candidates.first { it.exists() }.inputStream().use {
                HanziPinyin.loadFromYamlStream(it, overwrite = true)
            }
        }
    }

    @Test
    fun promotesFluentContextualAndPoetryCandidatesWhileBlockingGibberish() {
        // 1. Contextual 2-syllable disambiguation ("秋水" + "shizhi": "是指" vs "时至")
        val twoSylCands = listOf("是指", "实质", "市值", "时至")
        val twoSylScored = AiRanker.Scored(
            indices = listOf(0, 1, 2, 3),
            lm = floatArrayOf(-11.2f, -10.5f, -11.8f, -4.1f),
        )
        assertEquals(3, AiRanker.best(twoSylScored, twoSylCands, a = 2.2f, useCache = false, m = 0.75f, context = "秋水"))

        // 2. 7-char sentence ("白云生处有人家" vs "白云深处有人家")
        val poemCands = listOf("白云生处有人家", "白云深处有人家")
        val poemScored = AiRanker.Scored(
            indices = listOf(0, 1),
            lm = floatArrayOf(-14.5f, -6.2f),
        )
        assertEquals(1, AiRanker.best(poemScored, poemCands, a = 2.2f, useCache = false, m = 0.90f, context = "远上寒山石径斜，"))

        // 3. Low-probability stitched gibberish ("杆菌最详尽" vs "饭均最详尽", both terrible < -30)
        val gibberishCands = listOf("杆菌最详尽", "饭均最详尽")
        val gibberishScored = AiRanker.Scored(
            indices = listOf(0, 1),
            lm = floatArrayOf(-38.0f, -33.5f),
        )
        assertEquals(0, AiRanker.best(gibberishScored, gibberishCands, a = 2.2f, useCache = false, m = 0.90f))
    }

    @Test
    fun blocksEncyclopediaRareHeadwordTrapLikeQiushuiShizhi() {
        // Even if an uncalibrated LM scored "泅水是指" (-15.0) above "秋水时至" (-16.2) and "秋水是指" (-22.0),
        // 2.5B-corpus rarity guard (`泅`=1283 vs `秋`=352446) + empty-context `X是指` guard blocks "泅水是指"
        // and cleanly selects "秋水时至"!
        val cands = listOf("秋水是指", "秋水实质", "秋水时至", "泅水是指")
        val scored = AiRanker.Scored(
            indices = listOf(0, 1, 2, 3),
            lm = floatArrayOf(-22.0f, -24.5f, -16.2f, -15.0f),
        )
        assertEquals(2, AiRanker.best(scored, cands, a = 2.2f, useCache = false, m = 0.90f, context = ""))
        assertTrue(AiRanker.lexicalPriorPenalty("秋水是指", "泅水是指", context = "", useCache = false) > 8.0f)
        assertEquals(0f, AiRanker.lexicalPriorPenalty("秋水是指", "秋水时至", context = "", useCache = false), 0.001f)
    }

    @Test
    fun builtInOctagramDatabaseHasValidRimeGrammarHeader() {
        val gramCandidates = listOf(
            File("src/main/assets/rime/keylo-zh-hans.gram"),
            File("app/src/main/assets/rime/keylo-zh-hans.gram"),
        )
        val gramFile = gramCandidates.first { it.exists() }
        assertTrue(gramFile.length() > 1_000_000L)
        assertTrue(RimeEngine.isValidGramFile(gramFile))
    }
}
