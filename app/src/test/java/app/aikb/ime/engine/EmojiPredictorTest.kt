package app.aikb.ime.engine

import org.junit.Assert.assertTrue
import org.junit.Test

class EmojiPredictorTest {

    @Test
    fun predictsContextualEmojisForChineseAndEnglish() {
        val bday = EmojiPredictor.predictForChinese("祝你生日快乐")
        assertTrue("Expected cake emoji in $bday", "🎂" in bday)

        val thanks = EmojiPredictor.predictForChinese("非常谢谢")
        assertTrue("Expected folded hands in $thanks", "🙏" in thanks)

        val haha = EmojiPredictor.predictForChinese("哈哈哈")
        assertTrue("Expected laughing emoji in $haha", "🤣" in haha || "😂" in haha)

        val enCongrats = EmojiPredictor.predictForEnglish("Congrats")
        assertTrue("Expected party emoji in $enCongrats", "🎉" in enCongrats)
    }
}
