package app.aikb.ime.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files

class EnglishEngineTest {
    companion object {
        lateinit var engine: EnglishEngine

        @BeforeClass @JvmStatic fun load() {
            val f = File("src/main/assets/dict/en.dict")
            val dict = EnglishDictionary.fromBuffer(ByteBuffer.wrap(f.readBytes()))
            engine = EnglishEngine(dict, UserHistory(Files.createTempFile("hist", ".tsv").toFile()))
        }
    }

    @Test fun correctsCommonTypo() = assertEquals("the", engine.suggest("teh").autoCorrect)
    @Test fun correctsAdjacentKey() = assertEquals("hello", engine.suggest("hrllo").autoCorrect)
    @Test fun addsApostrophe() = assertEquals("don't", engine.suggest("dont").autoCorrect)
    @Test fun correctsMisspellingsAndMissingSpace() {
        assertEquals("receive", engine.suggest("recieve").autoCorrect)
        assertEquals("definitely", engine.suggest("definately").autoCorrect)
        val splitCands = engine.suggest("thebest").suggestions.map { it.text }
        assertTrue("Expected 'the best' in $splitCands", "the best" in splitCands)
    }
    @Test fun capitalI() = assertEquals("I", engine.suggest("i").suggestions.first().text)
    @Test fun keepsValidWord() = assertNull(engine.suggest("cat").autoCorrect)
    @Test fun keepsCase() = assertEquals("The", engine.suggest("Teh").autoCorrect)

    @Test fun properNounCasing() {
        assertEquals("Monday", engine.suggest("monday").autoCorrect)
        assertEquals("iPhone", engine.suggest("iphone").autoCorrect)
        assertEquals("January", engine.suggest("january").autoCorrect)
    }

    @Test fun completes() {
        val s = engine.suggest("beau").suggestions.map { it.text }
        assertTrue(s.toString(), "beautiful" in s)
    }

    @Test fun predictsNextWord() {
        val afterThank = engine.predictNext("thank")
        assertTrue("after thank: $afterThank", afterThank.firstOrNull() == "you")
        val afterGood = engine.predictNext("Good")
        assertTrue("after Good: $afterGood", "morning" in afterGood && "luck" in afterGood)
        val afterIm = engine.predictNext("I'm")
        assertTrue("after I'm: $afterIm", "going" in afterIm || "not" in afterIm || "sorry" in afterIm)
    }

    @Test fun contextualPrefixAndDisambiguation() {
        val goodM = engine.suggest("m", prevWord = "Good").suggestions.map { it.text }
        assertTrue("Good m -> $goodM", "morning" in goodM.take(3))
        val lookF = engine.suggest("f", prevWord = "looking").suggestions.map { it.text }
        assertTrue("looking f -> $lookF", "forward" in lookF.take(3))
        assertEquals("forward", engine.suggest("forw", prevWord = "looking").suggestions.first().text)

        // Contextual "its" vs "it's"
        assertEquals("it's", engine.suggest("its", prevWord = "think").autoCorrect)
        assertNull(engine.suggest("its", prevWord = "in").autoCorrect)
    }

    @Test fun learnsUserWordsAndBigrams() {
        val h = UserHistory(Files.createTempFile("hist2", ".tsv").toFile())
        val dict = EnglishDictionary.fromBuffer(ByteBuffer.wrap(File("src/main/assets/dict/en.dict").readBytes()))
        val e = EnglishEngine(dict, h)
        repeat(3) { e.learn("kubectl") }
        assertNull(e.suggest("kubectl").autoCorrect)
        assertTrue("kubectl" in e.suggest("kube").suggestions.map { it.text })

        repeat(2) { e.learn("zephyr", prevWord = "project") }
        assertEquals("zephyr", e.predictNext("project").firstOrNull())
    }

    @Test fun printSamples() {
        for (w in listOf("teh", "recieve", "thx", "wierd", "goin", "pls", "hapy", "beutiful", "im", "youre", "wont", "its", "definately", "alot", "lol", "omg", "ok", "hrllo", "wrok", "becuase", "febuary", "gonna", "u", "ur", "hel", "goo", "tha", "wh", "bec", "tom", "beau", "monday", "iphone"))
            println("$w -> auto=${engine.suggest(w).autoCorrect} top=${engine.suggest(w).suggestions.take(5).map { it.text }}")
    }
}
