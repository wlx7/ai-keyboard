package app.aikb.ime.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ChinesePredictorTest {

    private fun loadPredictor(userFile: File? = null): ChinesePredictor {
        val candidates = listOf(
            File("src/main/assets/dict/zh_predict.bin"),
            File("app/src/main/assets/dict/zh_predict.bin"),
        )
        val file = candidates.first { it.exists() }
        return ChinesePredictor(file.readBytes(), userFile)
    }

    @Test
    fun conversationalAndIdiomPredictionsWork() {
        val p = loadPredictor()

        val afterToday = p.predict("今天", max = 6)
        assertTrue("Expected 天气 or 晚上 after 今天, got $afterToday",
            "天气" in afterToday || "晚上" in afterToday)

        val afterThanks = p.predict("谢谢", max = 6)
        assertTrue("Expected 大家 or 你 after 谢谢, got $afterThanks",
            "大家" in afterThanks || "你" in afterThanks)

        val afterSorry = p.predict("不好", max = 6)
        assertEquals("意思", afterSorry.firstOrNull())

        val idiom = p.predict("祝你一帆", max = 4)
        assertTrue("Expected 风顺 after 一帆, got $idiom", "风顺" in idiom)

        val classical = p.predict("秋水", max = 6)
        assertTrue("Expected 时至 in predictions after 秋水, got $classical", "时至" in classical)

        val emptyAfterPunct = p.predict("今天天气真好。", max = 6)
        assertTrue("Should not predict immediately after sentence period", emptyAfterPunct.isEmpty())
    }

    @Test
    fun personalUserBigramLearningPersists() {
        val tmp = File.createTempFile("zh_user_test", ".tsv").apply { deleteOnExit() }
        val p = loadPredictor(tmp)
        p.learn("灵犀", "输入法")
        p.learn("灵犀", "输入法")
        p.save()

        val p2 = loadPredictor(tmp)
        val res = p2.predict("我刚装了灵犀", max = 5)
        assertEquals("输入法", res.firstOrNull())

        p2.clearUserHistory()
        assertFalse(tmp.exists())
    }
}
