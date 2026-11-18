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
 * Word completion + typo correction + bigram next-word & contextual disambiguation
 * over the memory-mapped dictionary.
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

    /**
     * Next-word predictions when nothing is currently being composed (right after space).
     */
    fun predictNext(prevWord: String?, capitalizeFirst: Boolean = false, max: Int = 8): List<String> {
        val prev = prevWord?.trim()?.replace('’', '\'')?.takeIf { it.isNotEmpty() } ?: return emptyList()
        val scored = LinkedHashMap<String, Double>()

        fun putBest(word: String, s: Double) {
            val formatted = if (capitalizeFirst) word.replaceFirstChar { it.uppercaseChar() } else word
            val cur = scored[formatted]
            if (cur == null || cur < s) scored[formatted] = s
        }

        // 1) Personal bigram history takes priority once seen
        for ((w, c) in history.nextWords(prev, max)) {
            val idx = dict.unigramIndexOf(w)
            val disp = if (idx >= 0) dict.display(idx) else w
            putBest(disp, 185.0 + 26.0 * ln(1.0 + c))
        }

        // 2) Corpus + conversational bigrams from en.dict
        val edges = dict.bigrams(prev)
        for (packed in edges) {
            val tIdx = EnglishDictionary.edgeTarget(packed)
            val bScore = EnglishDictionary.edgeScore(packed)
            val disp = dict.display(tIdx)
            putBest(disp, bScore + 0.12 * dict.freq(tIdx))
        }

        return scored.entries.sortedByDescending { it.value }.take(max).map { it.key }
    }

    fun suggest(typedRaw: String, prevWord: String? = null, max: Int = 12): EnglishResult {
        val typed = typedRaw
        val key = typed.lowercase().filter { it in 'a'..'z' }
        if (key.isEmpty()) return EnglishResult(typed, emptyList(), null)
        val best = HashMap<String, Suggestion>()

        // Contextual bigram boosts keyed by display.lowercase() (distinguishes "its" vs "it's", "your" vs "you're")
        val ctxBoost = HashMap<String, Double>()
        val edges = dict.bigrams(prevWord)
        for (packed in edges) {
            val tIdx = EnglishDictionary.edgeTarget(packed)
            val bScore = EnglishDictionary.edgeScore(packed)
            val low = dict.display(tIdx).lowercase()
            ctxBoost[low] = maxOf(ctxBoost[low] ?: 0.0, 0.42 * bScore)
        }
        for ((w, c) in history.nextWords(prevWord, 16)) {
            ctxBoost[w] = maxOf(ctxBoost[w] ?: 0.0, 38.0 + 22.0 * ln(1.0 + c))
        }

        fun offer(display: String, baseScore: Double, correction: Boolean, allowCtx: Boolean = true) {
            val rawCtx = if (allowCtx) (ctxBoost[display.lowercase()] ?: 0.0) else 0.0
            val score = baseScore + if (correction) rawCtx * 0.45 else rawCtx
            val cur = best[display]
            if (cur == null || cur.score < score) best[display] = Suggestion(display, score, correction)
        }
        fun boost(word: String): Double {
            val c = history.count(word.lowercase())
            return if (c > 0) 18.0 * ln(1.0 + c) + 25.0 else 0.0
        }

        // 1) exact key matches ("dont" -> "don't", "monday" -> "Monday", "its" -> "its"/"it's")
        var literalScore = Double.NEGATIVE_INFINITY
        var literalFreq = 0
        for (i in dict.exactRange(key)) {
            val d = dict.display(i)
            val base = dict.freq(i) + 30.0 + boost(d)
            offer(d, base, false)
            if (matchCase(typed, d) == typed) {
                literalScore = maxOf(literalScore, base + (ctxBoost[d.lowercase()] ?: 0.0))
                literalFreq = maxOf(literalFreq, dict.freq(i))
            }
        }
        if (history.count(typed.lowercase()) > 0) {
            literalScore = maxOf(literalScore, 120.0 + boost(typed) + (ctxBoost[typed.lowercase()] ?: 0.0))
        }

        // 2) completions: longer words starting with the key
        if (key.isNotEmpty()) {
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
            // Directly consider contextual bigram continuations matching the prefix so 1-2 letter
            // prefixes ("Good m" -> "morning", "Looking f" -> "forward") surface immediately.
            for (packed in edges) {
                val tIdx = EnglishDictionary.edgeTarget(packed)
                val tKey = dict.key(tIdx)
                if (tKey.startsWith(key) && tKey.length > key.length) {
                    val d = dict.display(tIdx)
                    val extra = d.length - key.length
                    offer(d, dict.freq(tIdx) - 5.0 - 1.1 * extra + boost(d), false)
                }
            }
            for ((w, c) in history.nextWords(prevWord, 12)) {
                val wAlpha = w.filter { it in 'a'..'z' }
                if (wAlpha.startsWith(key) && wAlpha.length > key.length) {
                    offer(w, 112.0 + 18.0 * ln(1.0 + c), false)
                }
            }
        }
        // user's own words that the dictionary lacks
        for ((w, c) in history.withPrefix(key, 5)) offer(w, 110.0 + 18.0 * ln(1.0 + c), false)

        // 3) corrections at edit distance 1 (substitution / deletion / insertion / transposition)
        if (key.length >= 2) {
            val seen = HashSet<String>()
            fun tryKey(k: String, penalty: Double, allowCtx: Boolean = true) {
                if (!seen.add(k)) return
                for (i in dict.exactRange(k)) {
                    val d = dict.display(i)
                    if (' ' in d) continue // chat phrase expansions ("btw"->"by the way") require exact typing
                    offer(d, dict.freq(i) + 30.0 - penalty + boost(d), true, allowCtx = allowCtx)
                }
            }
            for (p in key.indices) {
                for (ch in 'a'..'z') {
                    if (ch == key[p]) continue
                    val pen = if (adjacent(ch, key[p])) 34.0 else 62.0
                    tryKey(key.substring(0, p) + ch + key.substring(p + 1), pen)
                }
                if (key.length > 2) tryKey(key.removeRange(p, p + 1), if (p == key.lastIndex) 75.0 else 50.0, allowCtx = p != key.lastIndex)
                if (p < key.length - 1 && key[p] != key[p + 1])
                    tryKey(key.substring(0, p) + key[p + 1] + key[p] + key.substring(p + 2), 36.0)
            }
            for (p in 0..key.length) for (ch in 'a'..'z')
                tryKey(key.substring(0, p) + ch + key.substring(p), 46.0)
        }

        val ranked = best.values.sortedByDescending { it.score }.take(max).map { it.copy(text = matchCase(typed, it.text)) }

        // Auto-correct only when the top choice is clearly better than what was typed.
        // A reasonably common word ("thx", "pls") is never replaced by a different word; only
        // same-letter fixes (dont -> don't, im -> I'm, monday -> Monday, think its -> think it's) still apply to it.
        val top = ranked.firstOrNull()
        val sameLetters = top != null && top.text.lowercase().filter { it in 'a'..'z' } == key
        val auto = if (top != null && !top.text.equals(typed, ignoreCase = false) &&
            top.score >= 95.0 && top.score >= literalScore + 40.0 && key.length >= 2 &&
            (sameLetters || literalFreq < 75)
        ) top.text else null

        return EnglishResult(typed, ranked, auto)
    }

    /** "Hel" -> "Hello", "HEL" -> "HELLO"; keeps dictionary casing ("I", "iPhone", "Monday") otherwise. */
    private fun matchCase(typed: String, word: String): String {
        if (typed.isEmpty()) return word
        val letters = typed.filter { it.isLetter() }
        return when {
            letters.length > 1 && letters.all { it.isUpperCase() } -> word.uppercase()
            typed[0].isUpperCase() -> word.replaceFirstChar { it.uppercaseChar() }
            else -> word
        }
    }

    fun learn(word: String, prevWord: String? = null) {
        val w = word.trim()
        if (w.length in 2..32 && w.any { it.isLetter() }) {
            history.add(w.lowercase(), prevWord?.trim()?.lowercase())
        }
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
