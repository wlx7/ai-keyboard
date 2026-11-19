package app.aikb.ime.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

class HanziPinyinTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun loadDict() {
            val candidates = listOf(
                File("src/main/assets/rime/cn_dicts/8105.dict.yaml"),
                File("app/src/main/assets/rime/cn_dicts/8105.dict.yaml"),
            )
            val dictFile = candidates.first { it.exists() }
            dictFile.inputStream().use { HanziPinyin.loadFromYamlStream(it, overwrite = true) }
        }
    }

    @Test
    fun matchesExactAndPolyphoneWords() {
        assertTrue(HanziPinyin.isLoaded)
        assertTrue(HanziPinyin.matchesPinyin("禁色", "jinse"))
        assertEquals("jinse", HanziPinyin.primaryPinyin("禁色"))
        assertEquals("js", HanziPinyin.primaryInitials("禁色"))

        // Polyphones: 乐 (yue / le), 行 (hang / xing)
        assertTrue(HanziPinyin.matchesPinyin("音乐", "yinyue"))
        assertTrue(HanziPinyin.matchesPinyin("快乐", "kuaile"))
        assertTrue(HanziPinyin.matchesPinyin("银行", "yinhang"))
        assertTrue(HanziPinyin.matchesPinyin("行走", "xingzou"))
        assertFalse(HanziPinyin.matchesPinyin("禁色", "jinshan"))
    }

    @Test
    fun alignsMultiCharSegmentAndExtractsLongSentencePrefixes() {
        assertEquals(
            listOf("se" to "色", "zhe" to "这", "shou" to "首", "ge" to "歌"),
            HanziPinyin.alignWordToPinyin("色这首歌", "sezheshouge"),
        )

        val chatContext = "《禁色》这首歌是不是说同性恋的？是的，达明一派的《禁色》确实是代表作。"
        assertEquals(listOf("禁色"), HanziPinyin.extractMatchingPhrases(chatContext, "jinse"))
        assertEquals(listOf("达明一派"), HanziPinyin.extractMatchingPhrases(chatContext, "damingyipai"))

        // Prefix extraction when user types a long sentence `jinsezheshouge`
        assertEquals(listOf("禁色" to 5), HanziPinyin.extractPrefixMatches(chatContext, "jinsezheshouge"))
        // Must reject broken syllable boundary (`jin` vs `jing` leaving `gse...`)
        assertEquals(-1, HanziPinyin.prefixMatchLength("金色", "jingsezheshouge"))
    }
}
