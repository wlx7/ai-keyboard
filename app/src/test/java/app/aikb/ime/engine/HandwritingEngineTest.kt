package app.aikb.ime.engine

import app.aikb.ime.engine.HandwritingEngine.Point
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

class HandwritingEngineTest {
    companion object {
        lateinit var hw: HandwritingEngine

        @BeforeClass @JvmStatic fun load() {
            hw = File("src/main/assets/dict/hw.bin").inputStream().use { HandwritingEngine.fromStream(it) }
        }
    }

    @Test fun recognizesHorizontalStrokeYi() {
        val strokes = listOf(
            listOf(Point(60f, 150f), Point(180f, 148f), Point(300f, 150f))
        )
        val t0 = System.nanoTime()
        val cands = hw.recognize(strokes, topK = 5, preferChinese = true)
        val ms = (System.nanoTime() - t0) / 1e6
        println("Handwriting '一' -> $cands in ${"%.2f".format(ms)} ms")
        assertTrue("Expected '一' in $cands", "一" in cands.take(3))
    }

    @Test fun recognizesCrossShi() {
        val strokes = listOf(
            listOf(Point(80f, 150f), Point(200f, 150f), Point(320f, 150f)),
            listOf(Point(200f, 50f), Point(200f, 150f), Point(200f, 260f)),
        )
        val cands = hw.recognize(strokes, topK = 5, preferChinese = true)
        println("Handwriting '十' -> $cands")
        assertTrue("Expected '十' in $cands", "十" in cands.take(3))
    }

    @Test fun recognizesContinuousMultiChar() {
        // '十' on the left + '口' on the right (continuous writing)
        val strokes = listOf(
            // 十 (x: 40..160)
            listOf(Point(40f, 150f), Point(100f, 150f), Point(160f, 150f)),
            listOf(Point(100f, 80f), Point(100f, 150f), Point(100f, 230f)),
            // 口 (x: 240..380)
            listOf(Point(240f, 90f), Point(240f, 220f)),
            listOf(Point(240f, 90f), Point(380f, 90f), Point(380f, 220f)),
            listOf(Point(240f, 220f), Point(380f, 220f)),
        )
        val cands = hw.recognize(strokes, topK = 8, preferChinese = true)
        println("Handwriting continuous '十口' -> $cands")
        assertTrue("Expected multi-char '十口' or '古' in $cands", cands.any { it.length >= 2 && it.startsWith("十") })
    }
}
