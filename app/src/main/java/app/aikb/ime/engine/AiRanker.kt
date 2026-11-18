package app.aikb.ime.engine

import android.content.Context
import android.util.Log
import java.io.File
import kotlin.math.ln

/**
 * Re-ranks librime's sentence candidates with an on-device language model.
 *
 * score(c) = log P_lm(c | text before cursor) − alpha · ln(1 + rimeRank(c)) + cacheBoost(c)
 *
 * Only candidates that spell the whole input (one character per syllable) compete; partial
 * candidates keep their place after them. `alpha` is how much librime's own order is trusted;
 * it adapts to the user: picking the AI's choice lowers it, picking librime's original first
 * choice raises it. The cache boost favours phrases typed earlier in this session (memory only).
 */
object AiRanker {
    const val MODEL_NAME = "zh-lm.gguf"
    private const val TAG = "AiRanker"

    /** Default weight of librime's order, tuned on tools/eval (see ROADMAP.md). */
    const val DEFAULT_ALPHA = 3.0f

    /** The AI must beat librime's first choice by this many nats before overriding it. */
    @Volatile var margin = 1.0f
    const val MIN_SYLLABLES = 3
    private const val MIN_ALPHA = 0.5f
    private const val MAX_ALPHA = 8f
    private const val MAX_CANDIDATES = 10

    @Volatile var alpha = DEFAULT_ALPHA
    @Volatile private var loaded = false
    @Volatile var failed = false
        private set

    fun modelFile(context: Context) = File(context.filesDir, "models/$MODEL_NAME")

    fun hasModel(context: Context) = modelFile(context).let { it.exists() && it.length() > 1_000_000 }

    /** Loads the model (blocking, ~0.5 s with mmap); call off the UI thread. */
    @Synchronized fun ensureLoaded(context: Context, threadsOverride: Int = 0): Boolean {
        if (loaded) return true
        if (failed || !hasModel(context)) return false
        val threads = if (threadsOverride > 0) threadsOverride else Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
        loaded = runCatching { Llm.nativeLoad(modelFile(context).absolutePath, threads) }
            .onFailure { Log.e(TAG, "load failed", it) }.getOrDefault(false)
        if (!loaded) failed = true
        return loaded
    }

    /** Stops a running evaluation (new keystroke); cheap, any thread. */
    fun abort() { if (loaded) Llm.nativeAbort() }

    @Synchronized fun unload() {
        if (loaded) { Llm.nativeFree(); loaded = false }
    }

    fun syllableCount(spelled: String): Int = spelled.split(' ', '\'').count { it.isNotBlank() }

    private fun isFullHan(s: String, n: Int): Boolean =
        s.codePointCount(0, s.length) == n && s.all { it in '㐀'..'鿿' }

    /** Indices (in librime order) of candidates that cover the whole input. */
    fun coverage(candidates: List<String>, syllables: Int): List<Int> =
        candidates.indices.filter { isFullHan(candidates[it], syllables) }.take(MAX_CANDIDATES)

    class Scored(val indices: List<Int>, val lm: FloatArray)

    /** Raw LM scores for the covering candidates; null when not applicable or on failure. */
    fun scoreRaw(context: String, candidates: List<String>, syllables: Int): Scored? {
        if (!loaded || syllables < 2) return null
        val idx = coverage(candidates, syllables)
        if (idx.size < 2) return null
        val lm = Llm.nativeScore(context.takeLast(48), Array(idx.size) { candidates[idx[it]] }) ?: return null
        return Scored(idx, lm)
    }

    /** Index (into [candidates]) of the best candidate under [a]. */
    fun best(s: Scored, candidates: List<String>, a: Float = alpha, useCache: Boolean = true, m: Float = margin): Int {
        var best = s.indices[0]
        var bestScore = Float.NEGATIVE_INFINITY
        s.indices.forEachIndexed { rank, i ->
            val score = s.lm[rank] - a * ln(1f + rank) + (if (useCache) cacheBoost(candidates[i]) else 0f)
            if (score > bestScore) { bestScore = score; best = i }
        }
        // Keep librime's choice unless the LM is clearly more confident.
        val first = s.indices[0]
        val firstScore = s.lm[0] + (if (useCache) cacheBoost(candidates[first]) else 0f)
        return if (best != first && bestScore - firstScore < m) first else best
    }

    /** Schemes whose input is pinyin (the AI applies); shape-based 五笔/仓颉 are left alone. */
    fun supports(schema: String?): Boolean =
        schema != null && (schema == "rime_ice" || schema == "t9" || schema.startsWith("double_pinyin"))

    class Suggestion(val text: String, val alternatives: List<String>)

    /**
     * Explores sentence alternatives with librime and lets the LM pick. Returns null when the
     * AI agrees with librime (or cannot help). Runs on a background thread.
     */
    fun suggest(context: String, schema: String, input: String, syllables: Int, stale: () -> Boolean = { false }): Suggestion? {
        if (!loaded || syllables < MIN_SYLLABLES || !supports(schema)) return null
        val t0 = android.os.SystemClock.elapsedRealtime()
        val alts = RimeEngine.alternatives(schema, input, syllables)
        if (stale()) return null // the user kept typing while librime explored
        val s = scoreRaw(context, alts, syllables)
        val top = s?.let { best(it, alts) }
        // Never log candidate text: it is what the user is typing.
        Log.d(TAG, "suggest syl=$syllables alts=${alts.size} scored=${s?.indices?.size} changed=${s != null && top != s.indices[0]} " +
            "in ${android.os.SystemClock.elapsedRealtime() - t0} ms")
        if (s == null || top == null || top == s.indices[0]) return null
        return Suggestion(alts[top], alts)
    }

    // ------------------------------------------------------------ adaptation

    /** Called when the user picks a candidate while the AI had reordered (aiTop != rimeTop). */
    fun feedback(pickedAi: Boolean, pickedRime: Boolean) {
        alpha = when {
            pickedAi -> (alpha * 0.95f).coerceAtLeast(MIN_ALPHA)
            pickedRime -> (alpha * 1.08f).coerceAtMost(MAX_ALPHA)
            else -> alpha
        }
    }

    private val recent = StringBuilder()

    fun remember(committed: String) {
        recent.append(committed)
        if (recent.length > 4000) recent.delete(0, recent.length - 3000)
    }

    fun clearMemory() = recent.setLength(0)

    private fun cacheBoost(c: String): Float = if (c.length >= 2 && recent.indexOf(c) >= 0) 1.5f else 0f
}
