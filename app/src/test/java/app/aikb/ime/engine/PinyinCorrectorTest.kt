package app.aikb.ime.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PinyinCorrectorTest {

    @Test
    fun detectsValidBrokenAndMidTypingSyllables() {
        assertEquals(2, PinyinCorrector.minCompleteSyllables("nihao"))
        assertEquals(2, PinyinCorrector.minCompleteSyllables("budong"))
        assertEquals(4, PinyinCorrector.minCompleteSyllables("qiushuishizhi"))
        assertEquals(-1, PinyinCorrector.minCompleteSyllables("nihso"))
        assertEquals(-1, PinyinCorrector.minCompleteSyllables("zhognguo"))

        assertTrue(PinyinCorrector.allCompleteSyllables("bai yun sheng chu"))
        assertFalse(PinyinCorrector.allCompleteSyllables("jin ri ch"))

        // Mid-typing trailing initial must NOT trigger premature typo guesses
        assertTrue(PinyinCorrector.isMidTypingInitial("jinrich"))
        assertTrue(PinyinCorrector.isMidTypingInitial("bud"))
        assertTrue(PinyinCorrector.suggestPinyinFixes("jinrich").isEmpty())
        assertFalse(PinyinCorrector.isMidTypingInitial("nihso"))
    }

    @Test
    fun repairsAdjacentKeyAndTransposedPinyin() {
        val nihso = PinyinCorrector.suggestPinyinFixes("nihso").map { it.correctedPinyin }
        assertTrue("Expected nihao in $nihso", "nihao" in nihso)

        val shebme = PinyinCorrector.suggestPinyinFixes("shebme").map { it.correctedPinyin }
        assertTrue("Expected shenme in $shebme", "shenme" in shebme)

        val zhognguo = PinyinCorrector.suggestPinyinFixes("zhognguo").map { it.correctedPinyin }
        assertTrue("Expected zhongguo in $zhognguo", "zhongguo" in zhognguo)

        val womne = PinyinCorrector.suggestPinyinFixes("womne").map { it.correctedPinyin }
        assertTrue("Expected women in $womne", "women" in womne)

        val niihao = PinyinCorrector.suggestPinyinFixes("niihao").map { it.correctedPinyin }
        assertTrue("Expected nihao in $niihao", "nihao" in niihao)

        val xiansai = PinyinCorrector.suggestPinyinFixes("xiansai").map { it.correctedPinyin }
        assertTrue("Expected xianzai in $xiansai", "xianzai" in xiansai)
    }
}
