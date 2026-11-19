package app.aikb.ime.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoSpacerTest {

    @Test
    fun spacesChineseAndDigitsWhenEnabled() {
        assertEquals(" 2024", AutoSpacer.formatCommit("你好", "2024", spaceCnEn = false, spaceCnNum = true))
        assertEquals(" 年", AutoSpacer.formatCommit("2024", "年", spaceCnEn = false, spaceCnNum = true))
        assertEquals("2024", AutoSpacer.formatCommit("你好", "2024", spaceCnEn = true, spaceCnNum = false))
    }

    @Test
    fun spacesChineseAndEnglishWithoutSplittingAlphanumericTokens() {
        assertEquals(" Hello", AutoSpacer.formatCommit("你好", "Hello", spaceCnEn = true, spaceCnNum = false))
        assertEquals(" 世界", AutoSpacer.formatCommit("Hello", "世界", spaceCnEn = true, spaceCnNum = false))
        // Keep 5G and iPhone16 intact while spacing CJK boundaries
        assertEquals(" 5G 网络", AutoSpacer.formatCommit("使用", "5G网络", spaceCnEn = true, spaceCnNum = true))
        assertEquals(" iPhone16 系列", AutoSpacer.formatCommit("新款", "iPhone16系列", spaceCnEn = true, spaceCnNum = true))
    }

    @Test
    fun neverInsertsSpacesNextToCjkPunctuation() {
        assertEquals("2024", AutoSpacer.formatCommit("你好，", "2024", spaceCnEn = true, spaceCnNum = true))
        assertEquals("Hello", AutoSpacer.formatCommit("你好：", "Hello", spaceCnEn = true, spaceCnNum = true))
        assertFalse(AutoSpacer.needsLeadingSpace("你好，", 'H', spaceCnEn = true, spaceCnNum = true))
        assertTrue(AutoSpacer.needsLeadingSpace("你好", 'H', spaceCnEn = true, spaceCnNum = false))
    }
}
