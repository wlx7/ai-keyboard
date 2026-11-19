package app.aikb.ime.engine

import android.content.Context
import android.util.Log
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.math.ln

/**
 * Re-ranks librime's sentence & contextual 2-syllable candidates with an on-device language model.
 *
 * Supports BOTH:
 *   1. Built-in APK asset model (`assets/models/zh-lm-builtin.gguf`, Lingxi-285M Q4_0) — works
 *      out-of-the-box on first install with zero manual setup!
 *   2. User-imported custom `.gguf` (`files/models/zh-lm.gguf`), which overrides the built-in model
 *      when present and can be removed anytime to revert to the built-in model.
 */
object AiRanker {
    const val USER_MODEL_NAME = "zh-lm.gguf"
    const val BUILTIN_ASSET_PATH = "models/zh-lm-builtin.gguf"
    const val BUILTIN_FILE_NAME = "zh-lm-builtin.gguf"
    private const val TAG = "AiRanker"

    /** Default weight of librime's order, tuned on tools/eval (see ROADMAP.md). */
    const val DEFAULT_ALPHA = 2.6f

    /** The AI must beat librime's first choice by this many nats before overriding it. */
    @Volatile var margin = 0.95f
    const val MIN_SYLLABLES = 3
    const val MIN_CONTEXTUAL_SYLLABLES = 2
    private const val MIN_ALPHA = 1.2f
    private const val MAX_ALPHA = 3.6f
    private const val MAX_CANDIDATES = 20

    @Volatile var alpha = DEFAULT_ALPHA
    @Volatile private var loaded = false
    @Volatile private var loadedPath: String? = null
    @Volatile var failed = false
        private set
    @Volatile private var cachedHasAsset: Boolean? = null

    /** User-imported external GGUF file (optional override). */
    fun modelFile(context: Context) = File(context.filesDir, "models/$USER_MODEL_NAME")

    /** Extracted built-in GGUF file for zero-copy mmap by llama.cpp. */
    fun builtinExtractedFile(context: Context) = File(context.filesDir, "models/$BUILTIN_FILE_NAME")

    fun hasUserModel(context: Context): Boolean =
        modelFile(context).let { it.exists() && it.length() > 1_000_000 }

    fun hasBuiltinModel(context: Context): Boolean {
        cachedHasAsset?.let { return it }
        val ok = runCatching {
            context.assets.list("models")?.contains(BUILTIN_FILE_NAME) == true
        }.getOrDefault(false) || builtinExtractedFile(context).let { it.exists() && it.length() > 1_000_000 }
        cachedHasAsset = ok
        return ok
    }

    fun hasModel(context: Context): Boolean = hasUserModel(context) || hasBuiltinModel(context)

    /** Resolves the active GGUF file on disk (extracting the built-in asset once if needed). */
    private fun resolveActiveModelFile(context: Context): File? {
        val user = modelFile(context)
        if (user.exists() && user.length() > 1_000_000) return user
        if (!hasBuiltinModel(context)) return null
        val dst = builtinExtractedFile(context)
        val expectedLen = runCatching {
            context.assets.openFd(BUILTIN_ASSET_PATH).use { it.length }
        }.getOrDefault(-1L)
        if (dst.exists() && dst.length() > 1_000_000 && (expectedLen <= 0L || dst.length() == expectedLen)) {
            return dst
        }
        return runCatching {
            dst.parentFile?.mkdirs()
            val tmp = File(dst.path + ".tmp")
            context.assets.open(BUILTIN_ASSET_PATH).use { input ->
                tmp.outputStream().use { out -> input.copyTo(out, 1 shl 20) }
            }
            Files.move(tmp.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING)
            dst
        }.onFailure { Log.e(TAG, "Failed extracting built-in AI model", it) }.getOrNull()
    }

    /** Loads the model (blocking, ~0.4 s with mmap); call off the UI thread. */
    @Synchronized fun ensureLoaded(context: Context, threadsOverride: Int = 0): Boolean {
        val target = resolveActiveModelFile(context) ?: return false
        if (loaded && loadedPath == target.absolutePath) return true
        if (loaded) { Llm.nativeFree(); loaded = false; loadedPath = null }
        if (failed) return false
        val threads = if (threadsOverride > 0) threadsOverride else Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
        loaded = runCatching { Llm.nativeLoad(target.absolutePath, threads) }
            .onFailure { Log.e(TAG, "load failed", it) }.getOrDefault(false)
        if (loaded) loadedPath = target.absolutePath else failed = true
        return loaded
    }

    /** Resets failure state when a new model is imported or switched. */
    @Synchronized fun resetAndUnload() {
        if (loaded) { Llm.nativeFree(); loaded = false }
        loadedPath = null
        failed = false
    }

    /** Stops a running evaluation (new keystroke); cheap, any thread. */
    fun abort() { if (loaded) Llm.nativeAbort() }

    @Synchronized fun unload() {
        if (loaded) { Llm.nativeFree(); loaded = false; loadedPath = null }
    }

    fun syllableCount(spelled: String): Int = spelled.split(' ', '\'').count { it.isNotBlank() }

    private fun hasCjkContext(context: String): Boolean {
        var i = 0
        while (i < context.length) {
            val cp = context.codePointAt(i)
            if (cp in 0x3400..0x9FFF || cp in 0x20000..0x323AF) return true
            i += Character.charCount(cp)
        }
        return false
    }

    fun minRequiredSyllables(context: String): Int =
        if (hasCjkContext(context)) MIN_CONTEXTUAL_SYLLABLES else MIN_SYLLABLES

    private fun isFullHan(s: String, n: Int): Boolean {
        if (s.codePointCount(0, s.length) != n) return false
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            if (!(cp in 0x3400..0x9FFF || cp in 0xF900..0xFAFF || cp in 0x20000..0x323AF)) return false
            i += Character.charCount(cp)
        }
        return true
    }

    /** Indices (in librime order) of candidates that cover the whole input. */
    fun coverage(candidates: List<String>, syllables: Int): List<Int> =
        candidates.indices.filter { isFullHan(candidates[it], syllables) }.take(MAX_CANDIDATES)

    class Scored(val indices: List<Int>, val lm: FloatArray)

    /** Raw LM scores for the covering candidates; null when not applicable or on failure. */
    fun scoreRaw(context: String, candidates: List<String>, syllables: Int): Scored? {
        if (!loaded || syllables < 2) return null
        val idx = coverage(candidates, syllables)
        if (idx.size < 2) return null
        val lm = Llm.nativeScore(context.takeLast(56), Array(idx.size) { candidates[idx[it]] }) ?: return null
        return Scored(idx, lm)
    }

    /** Minimum total log-prob floor accounting for ~-11.5 first-token prior + -4.6 per continuation Hanzi. */
    private fun minLogProbFloor(charLen: Int): Float = -11.5f - 4.6f * (charLen - 1).coerceAtLeast(0)

    /** Index (into [candidates]) of the best candidate under [a]. */
    fun best(s: Scored, candidates: List<String>, a: Float = alpha, useCache: Boolean = true, m: Float = margin): Int {
        var best = s.indices[0]
        var bestRank = 0
        var bestScore = Float.NEGATIVE_INFINITY
        s.indices.forEachIndexed { rank, i ->
            val score = s.lm[rank] - a * ln(1f + rank) + (if (useCache) cacheBoost(candidates[i]) else 0f)
            if (score > bestScore) { bestScore = score; best = i; bestRank = rank }
        }
        val first = s.indices[0]
        val firstScore = s.lm[0] + (if (useCache) cacheBoost(candidates[first]) else 0f)
        if (best != first) {
            val charLen = candidates[best].codePointCount(0, candidates[best].length).coerceAtLeast(1)
            if (bestScore - firstScore < m || s.lm[bestRank] < minLogProbFloor(charLen)) return first
        }
        return best
    }

    /** Schemes whose input is pinyin (the AI applies); shape-based 五笔/仓颉 are left alone. */
    fun supports(schema: String?): Boolean =
        schema != null && (schema == "rime_ice" || schema == "t9" || schema.startsWith("double_pinyin"))

    class Suggestion(val text: String, val runnerUp: String?, val alternatives: List<String>)

    /**
     * Explores sentence & contextual homophone alternatives with librime and lets the LM pick.
     * Returns null when the AI agrees with librime (or cannot help). Runs on a background thread.
     */
    fun suggest(
        context: String,
        schema: String,
        input: String,
        syllables: Int,
        extraCandidates: List<String> = emptyList(),
        stale: () -> Boolean = { false },
    ): Suggestion? {
        val minSyl = minRequiredSyllables(context)
        if (!loaded || syllables < minSyl || !supports(schema)) return null
        val t0 = android.os.SystemClock.elapsedRealtime()
        val rawAlts = RimeEngine.alternatives(schema, input, syllables)
        if (stale()) return null
        val alts = if (extraCandidates.isEmpty() || rawAlts.isEmpty()) rawAlts else buildList {
            add(rawAlts[0])
            for (ec in extraCandidates) if (ec.isNotEmpty() && ec !in this) add(ec)
            for (k in 1 until rawAlts.size) if (rawAlts[k] !in this) add(rawAlts[k])
        }
        val s = scoreRaw(context, alts, syllables)
        val effMargin = if (syllables == 2) 0.75f else margin
        val top = s?.let { best(it, alts, m = effMargin) }
        Log.d(TAG, "suggest syl=$syllables alts=${alts.size} scored=${s?.indices?.size} changed=${s != null && top != s.indices[0]} " +
            "in ${android.os.SystemClock.elapsedRealtime() - t0} ms")
        if (s == null || top == null || top == s.indices[0]) return null

        // Also surface second-best distinct AI sentence only if strongly fluent and superior
        var second: String? = null
        var secondScore = Float.NEGATIVE_INFINITY
        val firstScore = s.lm[0] + cacheBoost(alts[s.indices[0]])
        s.indices.forEachIndexed { rank, idx ->
            if (idx == top || idx == s.indices[0]) return@forEachIndexed
            val charLen = alts[idx].codePointCount(0, alts[idx].length).coerceAtLeast(1)
            if (s.lm[rank] < minLogProbFloor(charLen)) return@forEachIndexed
            val sc = s.lm[rank] - alpha * ln(1f + rank) + cacheBoost(alts[idx])
            if (sc > secondScore && sc - firstScore >= effMargin + 0.6f) {
                secondScore = sc
                second = alts[idx]
            }
        }
        return Suggestion(alts[top], second, alts)
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

    @Synchronized
    fun remember(committed: String) {
        recent.append(committed)
        if (recent.length > 4000) recent.delete(0, recent.length - 3000)
    }

    @Synchronized
    fun sessionMemory(): String = recent.toString()

    @Synchronized
    fun clearMemory() = recent.setLength(0)

    @Synchronized
    private fun cacheBoost(c: String): Float = if (c.length >= 2 && recent.indexOf(c) >= 0) 1.5f else 0f
}
