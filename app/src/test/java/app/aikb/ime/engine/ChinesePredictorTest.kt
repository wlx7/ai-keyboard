package app.aikb.ime.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

class ChinesePredictorTest {

    companion object {
        private lateinit var rawDictBytes: ByteArray

        @JvmStatic
        @BeforeClass
        fun loadDict() {
            val f = listOf(
                File("src/main/assets/dict/zh_predict.bin"),
                File("app/src/main/assets/dict/zh_predict.bin"),
            ).first { it.exists() }
            rawDictBytes = f.readBytes()
        }
    }

    @Test
    fun `qing feng xu lai predicts shui bo bu xing and never leaks single-char history or bound morphemes`() {
        val predictor = ChinesePredictor(rawDictBytes)
        // Simulate earlier chat `"没办法，来菜市场转了一圈"` (`prev = "来"`, `curr = "菜市场"`)
        predictor.learn("来", "菜市场")

        val preds = predictor.predict("清风徐来", max = 6)
        assertTrue("Expected non-empty predictions for 清风徐来, got $preds", preds.isNotEmpty())
        assertEquals("水波不兴", preds[0])
        assertFalse("1-char history '菜市场' must NEVER hijack 4-char idiom 清风徐来", "菜市场" in preds)
        assertFalse("Bound morpheme '自' must never appear after 清风徐来", "自" in preds)
        assertFalse("Bound morpheme '源' must never appear after 清风徐来", "源" in preds)

        // With trailing Chinese comma (`清风徐来，`), still predicts couplet continuation!
        val commaPreds = predictor.predict("清风徐来，", max = 6)
        assertTrue("Expected couplet continuation across comma, got $commaPreds", commaPreds.isNotEmpty())
        assertEquals("水波不兴", commaPreds[0])
        assertTrue(predictor.hasLongExactMatch("清风徐来"))
        assertTrue(predictor.hasLongExactMatch("清风徐来，"))
    }

    @Test
    fun `classical couplets and multi-word phrases use full 4 to 7 char context`() {
        val predictor = ChinesePredictor(rawDictBytes)

        assertEquals("疑是地上霜", predictor.predict("床前明月光").firstOrNull())
        assertEquals("白云生处有人家", predictor.predict("远上寒山石径斜，").firstOrNull())
        assertEquals("秋水共长天一色", predictor.predict("落霞与孤鹜齐飞").firstOrNull())
        assertEquals("长河落日圆", predictor.predict("大漠孤烟直").firstOrNull())
        assertEquals("始于足下", predictor.predict("千里之行").firstOrNull())

        val weatherPreds = predictor.predict("今天天气", max = 5)
        assertTrue("Expected multi-word continuation for 今天天气, got $weatherPreds", "真不错" in weatherPreds || "很好" in weatherPreds)

        val probPreds = predictor.predict("这个问题", max = 5)
        assertTrue("Expected multi-word continuation for 这个问题, got $probPreds", "怎么解决" in probPreds)

        val bdayPreds = predictor.predict("祝你生日", max = 5)
        assertEquals("快乐", bdayPreds.firstOrNull())
    }

    @Test
    fun `user multi-char history prioritizes longer context and supports forgetWord`() {
        val predictor = ChinesePredictor(rawDictBytes)
        predictor.learn("去菜市场", "买排骨")
        predictor.learn("菜市场", "买菜")

        val p1 = predictor.predict("我们一起去菜市场", max = 5)
        assertEquals("买排骨", p1[0])
        assertTrue("买菜" in p1)

        assertTrue(predictor.forgetWord("买排骨"))
        val p2 = predictor.predict("我们一起去菜市场", max = 5)
        assertFalse("买排骨" in p2)
        assertEquals("买菜", p2[0])
    }

    @Test
    fun `filterAiNextPredictions removes echoes and prefix duplicates`() {
        val filtered = AiRanker.filterAiNextPredictions(
            ctx = "这份代码有个漏洞，需要马上",
            raw = listOf("修改", "修改代码", "修复", "漏洞", "上车", "解决"),
            max = 3,
        )
        assertEquals(listOf("修改", "修复", "解决"), filtered)
    }
}
