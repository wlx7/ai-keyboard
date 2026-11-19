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
        UserZhLexicon.learnFromSegmentChain(
            schema = "rime_ice",
            segments = listOf("jin" to "禁", "se" to "色"),
            nowMs = 1_000L,
        )

        val exact = UserZhLexicon.lookup("rime_ice", "jinse")
        assertEquals(listOf("禁色"), exact.topPriority)
        assertTrue("禁色" in UserZhLexicon.lookup("rime_ice", "js").secondary)
        assertTrue("禁色" in UserZhLexicon.lookup("rime_ice", "jins").secondary)

        // Long sentence `jinsezheshouge` splits `("禁色", "zheshouge")`, and `minCompleteSyllables("zheshouge") > 0`!
        val sentenceSplit = UserZhLexicon.lookup("rime_ice", "jinsezheshouge")
        assertEquals(listOf("禁色" to "zheshouge"), sentenceSplit.prefixSplits)
        val cleanRem = sentenceSplit.prefixSplits.first().second
        assertTrue(PinyinCorrector.minCompleteSyllables(cleanRem) > 0)

        val apostropheSplit = UserZhLexicon.lookup("rime_ice", "jin'se'zheshouge")
        assertEquals(listOf("禁色" to "zheshouge"), apostropheSplit.prefixSplits)

        UserZhLexicon.loadFromFile(tempFile, force = true)
        assertEquals(listOf("禁色"), UserZhLexicon.lookup("rime_ice", "jinse").topPriority)
    }

    @Test
    fun extractsTwoCharWordAcrossSeamWhenPickingFirstCharThenRimeTail() {
        // User types `jinsezheshouge`, picks `禁` ("jin"), then picks Rime's #1 `色这首歌` ("sezheshouge")!
        UserZhLexicon.learnFromSegmentChain(
            schema = "rime_ice",
            segments = listOf("jin" to "禁", "sezheshouge" to "色这首歌"),
            nowMs = 1_500L,
        )
        // Must learn BOTH standalone `禁色` ("jinse") AND `禁色这首歌` ("jinsezheshouge")!
        assertEquals(listOf("禁色"), UserZhLexicon.lookup("rime_ice", "jinse").topPriority)
        assertEquals(listOf("禁色这首歌"), UserZhLexicon.lookup("rime_ice", "jinsezheshouge").topPriority)
        // And any brand-new sentence `jinsehaotingma` now splits `("禁色", "haotingma")`!
        assertEquals(listOf("禁色" to "haotingma"), UserZhLexicon.lookup("rime_ice", "jinsehaotingma").prefixSplits)
    }

    @Test
    fun stripsClauseParticlesWhenLearningFromSentenceChain() {
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
            nowMs = 28_000L, // 18s later (within 25s window)
        )
        assertEquals(listOf("禁色"), UserZhLexicon.lookup("rime_ice", "jinse").topPriority)
    }

    @Test
    fun zeroShotContextAndSessionPrefixLearningWorksOnLongSentences() {
        // Exact match from context
        val res = UserZhLexicon.lookup(
            schema = "rime_ice",
            rawInput = "jinse",
            contextBeforeCursor = "达明一派的《禁色》",
        )
        assertEquals(listOf("禁色"), res.topPriority)
        assertTrue("禁色" in res.contextSparkleSet)

        // Long-sentence prefix match from sessionMemory even when contextBeforeCursor is empty (e.g. browser search bar!)
        val longRes = UserZhLexicon.lookup(
            schema = "rime_ice",
            rawInput = "jinsezheshouge",
            contextBeforeCursor = "",
            sessionMemory = "刚才看到了《禁色》",
        )
        assertEquals(listOf("禁色" to "zheshouge"), longRes.prefixSplits)
        assertTrue("禁色" in longRes.contextSparkleSet)
    }

    @Test
    fun incognitoSessionOnlyLearningWorksInRamWithoutTouchingDisk() {
        UserZhLexicon.learnFromSegmentChain(
            schema = "rime_ice",
            segments = listOf("jin" to "禁", "se" to "色"),
            persist = false,
            nowMs = 5_000L,
        )
        assertEquals(listOf("禁色"), UserZhLexicon.lookup("rime_ice", "jinse").topPriority)
        assertTrue(!tempFile.exists() || tempFile.length() == 0L)
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
