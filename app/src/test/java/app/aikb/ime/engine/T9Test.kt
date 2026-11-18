package app.aikb.ime.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class T9Test {
    @Test fun digits() = assertEquals("64426", T9.digitsOf("nihao"))
    @Test fun optionsForNiHao() {
        val o = T9.options("64426")
        assertTrue(o.toString(), "ni" in o && "mi" in o)
        assertTrue(o.toString(), "m" in o && "n" in o && o.indexOf("ni") < o.indexOf("n"))
    }
    @Test fun longestFirst() = assertTrue(T9.options("9464").first().length >= T9.options("9464").last().length)
    @Test fun pick() = assertEquals("ni'426", T9.applyPick("64426", "ni"))
    @Test fun pickAfterHead() = assertEquals("ni'hao", T9.applyPick("ni'426", "hao"))
    @Test fun pickAll() = assertEquals("hao", T9.applyPick("426", "hao"))
}
