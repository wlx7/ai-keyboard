package app.aikb.ime.engine

import kotlin.math.ln

data class Suggestion(val text: String, val score: Double, val isCorrection: Boolean = false)

data class EnglishResult(
    val typed: String,
    val suggestions: List<Suggestion>,
    /** What space should commit instead of [typed], or null to keep what was typed. */
    val autoCorrect: String?,
)

/**
 * Word completion + typo correction over the memory-mapped dictionary.
 * Frequencies are Zipf*30 (0..255): ~200 = "the", ~90 = a moderately common word.
 */
class EnglishEngine(private val dict: EnglishDictionary, private val history: UserHistory) {

    private val rows = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
    private val pos: Map<Char, Pair<Float, Int>> = buildMap {
        rows.forEachIndexed { r, row -> row.forEachIndexed { c, ch -> put(ch, (c + r * 0.5f) to r) } }
    }

    private fun adjacent(a: Char, b: Char): Boolean {
        val pa = pos[a] ?: return false
        val pb = pos[b] ?: return false
        return kotlin.math.abs(pa.second - pb.second) <= 1 && kotlin.math.abs(pa.first - pb.first) <= 1.0f
    }

    fun suggest(typedRaw: String, max: Int = 12): EnglishResult {
        val typed = typedRaw
        val key = typed.lowercase().filter { it in 'a'..'z' }
        if (key.isEmpty()) return EnglishResult(typed, emptyList(), null)
        val best = HashMap<String, Suggestion>()

        fun offer(display: String, score: Double, correction: Boolean) {
            val cur = best[display]
            if (cur == null || cur.score < score) best[display] = Suggestion(display, score, correction)
        }
        fun boost(word: String): Double {
            val c = history.count(word.lowercase())
            return if (c > 0) 18.0 * ln(1.0 + c) + 25.0 else 0.0
        }

        // 1) exact key matches ("dont" -> "don't", "dont")
        var literalScore = Double.NEGATIVE_INFINITY
        var literalFreq = 0
        for (i in dict.exactRange(key)) {
            val d = dict.display(i)
            val s = dict.freq(i) + 30.0 + boost(d)
            offer(d, s, false)
            if (d.equals(typed, ignoreCase = true)) { literalScore = s; literalFreq = dict.freq(i) }
        }
        if (history.count(typed.lowercase()) > 0) literalScore = maxOf(literalScore, 120.0 + boost(typed))

        // 2) completions: longer words starting with the key
        if (key.length >= 1) {
            val range = dict.prefixRange(key)
            val top = TopK(max * 2)
            for (i in range) {
                val f = dict.freq(i)
                if (f + 0.0 > top.threshold()) top.add(i, f.toDouble())
            }
            for ((i, f) in top.items()) {
                val d = dict.display(i)
                val extra = d.length - key.length
                if (extra <= 0) continue
                offer(d, f - 6.0 - 1.5 * extra + boost(d), false)
            }
        }
        // user's own words that the dictionary lacks
        for ((w, c) in history.withPrefix(key, 5)) offer(w, 110.0 + 18.0 * ln(1.0 + c), false)

        // 3) corrections at edit distance 1 (substitution / deletion / insertion / transposition)
        if (key.length >= 2) {
            val seen = HashSet<String>()
            fun tryKey(k: String, penalty: Double) {
                if (!seen.add(k)) return
                for (i in dict.exactRange(k)) {
                    val d = dict.display(i)
                    offer(d, dict.freq(i) + 30.0 - penalty + boost(d), true)
                }
            }
            for (p in key.indices) {
                for (ch in 'a'..'z') {
                    if (ch == key[p]) continue
                    val pen = if (adjacent(ch, key[p])) 34.0 else 62.0
                    tryKey(key.substring(0, p) + ch + key.substring(p + 1), pen)
                }
                // extra letter typed; dropping the last letter just yields a shorter prefix word, rarely meant
                if (key.length > 2) tryKey(key.removeRange(p, p + 1), if (p == key.lastIndex) 75.0 else 50.0)
                if (p < key.length - 1 && key[p] != key[p + 1])                           // swapped letters
                    tryKey(key.substring(0, p) + key[p + 1] + key[p] + key.substring(p + 2), 36.0)
            }
            for (p in 0..key.length) for (ch in 'a'..'z')                                  // missing letter
                tryKey(key.substring(0, p) + ch + key.substring(p), 46.0)
        }

        val ranked = best.values.sortedByDescending { it.score }.take(max).map { it.copy(text = matchCase(typed, it.text)) }

        // Auto-correct only when the top choice is clearly better than what was typed.
        // A reasonably common word ("thx", "pls") is never replaced by a different word; only
        // same-letter fixes (dont -> don't, im -> I'm) still apply to it.
        val top = ranked.firstOrNull()
        val sameLetters = top != null && top.text.lowercase().filter { it in 'a'..'z' } == key
        val auto = if (top != null && !top.text.equals(typed, ignoreCase = false) &&
            top.score >= 95.0 && top.score >= literalScore + 40.0 && key.length >= 2 &&
            (sameLetters || literalFreq < 75)
        ) top.text else null

        return EnglishResult(typed, ranked, auto)
    }

    /** "Hel" -> "Hello", "HEL" -> "HELLO"; keeps dictionary casing ("I", "iPhone") otherwise. */
    private fun matchCase(typed: String, word: String): String {
        if (typed.isEmpty()) return word
        val letters = typed.filter { it.isLetter() }
        return when {
            letters.length > 1 && letters.all { it.isUpperCase() } -> word.uppercase()
            typed[0].isUpperCase() -> word.replaceFirstChar { it.uppercaseChar() }
            else -> word
        }
    }

    fun learn(word: String) {
        val w = word.trim()
        if (w.length in 2..32 && w.any { it.isLetter() }) history.add(w.lowercase())
    }
}

/** Small fixed-capacity min-heap of (index, score). */
private class TopK(private val k: Int) {
    private val heap = java.util.PriorityQueue<Pair<Int, Double>>(compareBy { it.second })
    fun threshold(): Double = if (heap.size < k) Double.NEGATIVE_INFINITY else heap.peek()!!.second
    fun add(i: Int, s: Double) {
        heap.add(i to s)
        if (heap.size > k) heap.poll()
    }
    fun items(): List<Pair<Int, Double>> = heap.toList()
}
