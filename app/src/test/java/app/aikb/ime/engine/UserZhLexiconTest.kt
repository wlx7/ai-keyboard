package app.aikb.ime.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.nio.file.Files

class UserZhLexiconTest {

    private lateinit var tempFile: File

    companion object {
        @JvmStatic
        @BeforeClass
        fun loadPinyinTable() {
            val candidates = listOf(
                File("src/main/assets/rime/cn_dicts/8105.dict.yaml"),
                File("app/src/main/assets/rime/cn_dicts/8105.dict.yaml"),
            )
            candidates.first { it.exists() }.inputStream().use {
                HanziPinyin.loadFromYamlStream(it, overwrite = true)
            }
        }
    }

    @Before
    fun setUp() {
        tempFile = Files.createTempFile("zh_learned_test", ".tsv").toFile()
        tempFile.deleteOnExit()
        UserZhLexicon.loadFromFile(tempFile, force = true)
        UserZhLexicon.clearAll()
    }

    @Test
    fun learnsMultiSegmentSelfMadeWordsAndSplitsLongSentences() {
        // User types `jinse` -> picks `禁` + picks `色`
        UserZhLexicon.learnFromSegmentChain(
            schema = "rime_ice",
            segments = listOf("jin" to "禁", "se" to "色"),
            nowMs = 1_000L,
        )

        val exact = UserZhLexicon.lookup("rime_ice", "jinse")
        assertEquals(listOf("禁色"), exact.topPriority)

        // Initials `js` and incomplete prefix `jins` surface `禁色` in secondary
        assertTrue("禁色" in UserZhLexicon.lookup("rime_ice", "js").secondary)
        assertTrue("禁色" in UserZhLexicon.lookup("rime_ice", "jins").secondary)

        // Long sentence `jinsezheshouge` (or `jin'se'zheshouge`) splits `禁色` + `zheshouge`
        val sentenceSplit = UserZhLexicon.lookup("rime_ice", "jinsezheshouge")
        assertEquals(listOf("禁色" to "zheshouge"), sentenceSplit.prefixSplits)
        val apostropheSplit = UserZhLexicon.lookup("rime_ice", "jin'se'zheshouge")
        assertEquals(listOf("禁色" to "zheshouge"), apostropheSplit.prefixSplits)

        // Reloading from disk preserves learned words
        UserZhLexicon.loadFromFile(tempFile, force = true)
        assertEquals(listOf("禁色"), UserZhLexicon.lookup("rime_ice", "jinse").topPriority)
    }

    @Test
    fun stripsClauseParticlesWhenLearningFromSentenceChain() {
        // User types `woxihuanjinsede` -> `我喜欢` + `禁` + `色` + `的`
        UserZhLexicon.learnFromSegmentChain(
            schema = "rime_ice",
            segments = listOf("woxihuan" to "我喜欢", "jin" to "禁", "se" to "色", "de" to "的"),
            nowMs = 2_000L,
        )
        assertEquals(listOf("禁色"), UserZhLexicon.lookup("rime_ice", "jinse").topPriority)
        assertTrue(UserZhLexicon.lookup("rime_ice", "jinsede").topPriority.isEmpty())
    }

    @Test
    fun learnsConsecutiveSingleCharacterPicksWithinWindow() {
        // User types `jin` -> picks `禁` (non-default #20), then 2s later types `se` -> presses Space for `色`
        UserZhLexicon.onSingleCharCommit(
            schema = "rime_ice",
            rawCode = "jin",
            charText = "禁",
            wasNonDefaultPick = true,
            nowMs = 10_000L,
        )
        UserZhLexicon.onSingleCharCommit(
            schema = "rime_ice",
            rawCode = "se",
            charText = "色",
            wasNonDefaultPick = false,
            nowMs = 12_000L,
        )
        assertEquals(listOf("禁色"), UserZhLexicon.lookup("rime_ice", "jinse").topPriority)
    }

    @Test
    fun zeroShotContextLearningSurfacesHomophoneEvenWithEmptyLexicon() {
        val res = UserZhLexicon.lookup(
            schema = "rime_ice",
            rawInput = "jinse",
            contextBeforeCursor = "达明一派的《禁色》",
        )
        assertEquals(listOf("禁色"), res.topPriority)
        assertTrue("禁色" in res.contextSparkleSet)
    }

    @Test
    fun forgetRemovesLearnedWordCleanly() {
        UserZhLexicon.learnPhrase("rime_ice", "jinse", "禁色", boost = 3)
        assertEquals(listOf("禁色"), UserZhLexicon.lookup("rime_ice", "jinse").topPriority)
        assertTrue(UserZhLexicon.forget("禁色"))
        assertFalse(UserZhLexicon.forget("禁色"))
        assertTrue(UserZhLexicon.lookup("rime_ice", "jinse").topPriority.isEmpty())
    }
}
