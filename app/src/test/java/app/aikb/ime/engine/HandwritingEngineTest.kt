package app.aikb.ime.engine

import app.aikb.ime.engine.HandwritingEngine.Point
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

class HandwritingEngineTest {
    companion object {
        lateinit var hw: HandwritingEngine
        lateinit var ids: IdsComposer

        private fun assetFile(rel: String): File =
            listOf(File("src/main/assets/$rel"), File("app/src/main/assets/$rel")).first { it.exists() }

        @BeforeClass @JvmStatic fun load() {
            val t2s = assetFile("rime/opencc/TSCharacters.txt").inputStream().use(IdsComposer::parseT2S)
            ids = IdsComposer(assetFile("dict/hw_ids.bin").readBytes(), t2s)
            hw = assetFile("dict/hw.bin").inputStream().use { HandwritingEngine.fromStream(it, ids) }
        }

        /** Builds a clean 3-stroke '土' inside [x0..x1] x [y0..y1]. */
        fun strokesTu(x0: Float, y0: Float, x1: Float, y1: Float): List<List<Point>> {
            val cx = (x0 + x1) * 0.5f
            val midY = y0 + (y1 - y0) * 0.48f
            return listOf(
                listOf(Point(x0 + (x1 - x0) * 0.18f, midY), Point(cx, midY), Point(x1 - (x1 - x0) * 0.18f, midY)),
                listOf(Point(cx, y0 + (y1 - y0) * 0.08f), Point(cx, midY), Point(cx, y1 - (y1 - y0) * 0.08f)),
                listOf(Point(x0 + (x1 - x0) * 0.05f, y1 - (y1 - y0) * 0.06f), Point(cx, y1 - (y1 - y0) * 0.06f), Point(x1 - (x1 - x0) * 0.05f, y1 - (y1 - y0) * 0.06f)),
            )
        }

        /** Builds a clean 3-stroke '口' inside [x0..x1] x [y0..y1]. */
        fun strokesKou(x0: Float, y0: Float, x1: Float, y1: Float): List<List<Point>> = listOf(
            listOf(Point(x0, y0), Point(x0, (y0 + y1) * 0.5f), Point(x0, y1)),
            listOf(Point(x0, y0), Point((x0 + x1) * 0.5f, y0), Point(x1, y0), Point(x1, (y0 + y1) * 0.5f), Point(x1, y1)),
            listOf(Point(x0, y1), Point((x0 + x1) * 0.5f, y1), Point(x1, y1)),
        )
    }

    @Test fun recognizesHorizontalStrokeYi() {
        val strokes = listOf(
            listOf(Point(60f, 150f), Point(180f, 148f), Point(300f, 150f))
        )
        val t0 = System.nanoTime()
        val cands = hw.recognize(strokes, topK = 5, preferChinese = true)
        val ms = (System.nanoTime() - t0) / 1e6
        println("Handwriting '一' -> $cands in ${"%.2f".format(ms)} ms")
        assertEquals("一", cands.firstOrNull())
    }

    @Test fun recognizesCrossShi() {
        val strokes = listOf(
            listOf(Point(80f, 150f), Point(200f, 150f), Point(320f, 150f)),
            listOf(Point(200f, 50f), Point(200f, 150f), Point(200f, 260f)),
        )
        val cands = hw.recognize(strokes, topK = 5, preferChinese = true)
        println("Handwriting '十' -> $cands")
        assertEquals("十", cands.firstOrNull())
    }

    @Test fun recognizesContinuousMultiChar() {
        val strokes = listOf(
            listOf(Point(40f, 150f), Point(100f, 150f), Point(160f, 150f)),
            listOf(Point(100f, 80f), Point(100f, 150f), Point(100f, 230f)),
            listOf(Point(240f, 90f), Point(240f, 220f)),
            listOf(Point(240f, 90f), Point(380f, 90f), Point(380f, 220f)),
            listOf(Point(240f, 220f), Point(380f, 220f)),
        )
        val cands = hw.recognize(strokes, topK = 8, preferChinese = true)
        println("Handwriting continuous '十口' -> $cands")
        assertTrue("Expected multi-char '十口' in $cands", cands.any { it.length >= 2 && it.startsWith("十") })
    }

    @Test fun recognizesRareCharactersWhileBiasingCommonChars() {
        // 1. Write '垚' (three 土 in a 品 triangle) — was 100% absent from 7,356 GB2312!
        val yaoStrokes = strokesTu(95f, 20f, 205f, 120f) +
            strokesTu(25f, 140f, 135f, 250f) +
            strokesTu(165f, 140f, 275f, 250f)
        val yaoCands = hw.recognize(yaoStrokes, topK = 16, preferChinese = true)
        println("Handwriting rare '垚' -> $yaoCands")
        assertTrue("Expected rare char '垚' in $yaoCands", "垚" in yaoCands)

        // 2. Write '土' over '口' ('吉' common vs '𠮷' Extension-B rare):
        //    Common character '吉' should be favored near top while rare '𠮷' MUST also appear in candidates!
        val jiStrokes = strokesTu(60f, 20f, 240f, 125f) + strokesKou(75f, 145f, 225f, 250f)
        val jiCands = hw.recognize(jiStrokes, topK = 20, preferChinese = true)
        println("Handwriting '吉/𠮷' -> $jiCands")
        assertTrue("Expected common '吉' or '古' in top 3: $jiCands", jiCands.take(3).any { it in setOf("吉", "古") })
        assertTrue("Expected Extension-B rare '𠮷' in candidates: $jiCands", "𠮷" in jiCands)

        // 3. Verify 40,000-character Spatial IDS Coverage across ultra-rare chars (including Ext-B 4-byte UTF-8)
        assertTrue("犇" in ids.lookup3("牛", "牛", "牛"))
        assertTrue("龘" in ids.lookup3("龙", "龙", "龙") && "龘" in ids.lookup3("龍", "龍", "龍"))
        assertTrue("曌" in ids.lookup2('V', "明", "空") && "曌" in ids.lookup3("日", "月", "空"))
        assertTrue("烎" in ids.lookup2('V', "开", "火") && "烎" in ids.lookup2('V', "開", "火"))
        assertTrue("嫑" in ids.lookup2('V', "不", "要"))
        assertTrue("砳" in ids.lookup2('H', "石", "石"))
        assertTrue("䶮" in ids.lookup2('V', "龙", "天") && "䶮" in ids.lookup2('V', "龍", "天"))
        assertTrue("齾" in ids.lookup2('V', "献", "齿") && "齾" in ids.lookup2('V', "獻", "齒"))
        assertTrue("𨰻" in ids.lookup2('V', "鍂", "鍂"))
        assertTrue("𪚥" in ids.lookup2('V', "龖", "龖"))
        assertTrue("𠮷" in ids.lookup2('V', "土", "口"))
    }
}
