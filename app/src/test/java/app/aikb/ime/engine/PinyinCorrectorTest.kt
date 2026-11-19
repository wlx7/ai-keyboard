package app.aikb.ime.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PinyinCorrectorTest {

    @Test
    fun detectsValidAndBrokenSyllables() {
        assertEquals(2, PinyinCorrector.minCompleteSyllables("nihao"))
        assertEquals(4, PinyinCorrector.minCompleteSyllables("qiushuishizhi"))
        assertEquals(-1, PinyinCorrector.minCompleteSyllables("nihso"))
        assertEquals(-1, PinyinCorrector.minCompleteSyllables("zhognguo"))
    }

    @Test
    fun repairsAdjacentKeyAndTransposedPinyin() {
        // 1. Adjacent key fat-finger: s next to a ("nihso" -> "nihao")
        val nihso = PinyinCorrector.suggestPinyinFixes("nihso").map { it.correctedPinyin }
        assertTrue("Expected nihao in $nihso", "nihao" in nihso)

        // 2. Adjacent key fat-finger: b next to n ("shebme" -> "shenme")
        val shebme = PinyinCorrector.suggestPinyinFixes("shebme").map { it.correctedPinyin }
        assertTrue("Expected shenme in $shebme", "shenme" in shebme)

        // 3. Fast-typing transposition: "zhognguo" -> "zhongguo", "womne" -> "women"
        val zhognguo = PinyinCorrector.suggestPinyinFixes("zhognguo").map { it.correctedPinyin }
        assertTrue("Expected zhongguo in $zhognguo", "zhongguo" in zhognguo)

        val womne = PinyinCorrector.suggestPinyinFixes("womne").map { it.correctedPinyin }
        assertTrue("Expected women in $womne", "women" in womne)

        // 4. Accidental double tap: "niihao" -> "nihao"
        val niihao = PinyinCorrector.suggestPinyinFixes("niihao").map { it.correctedPinyin }
        assertTrue("Expected nihao in $niihao", "nihao" in niihao)

        // 5. Close neighbor on valid-syllable non-word ("xiansai" -> "xianzai")
        val xiansai = PinyinCorrector.suggestPinyinFixes("xiansai").map { it.correctedPinyin }
        assertTrue("Expected xianzai in $xiansai", "xianzai" in xiansai)
    }
}
