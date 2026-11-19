package app.aikb.ime.engine

import android.content.Context
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Real-time adaptive Chinese user dictionary & context-memory learner (`动态调频 + 自造词记忆 + 上下文即时学词`).
 *
 * Learns new phrases & personal word preferences immediately in 3 ways:
 *   1. Multi-segment composition (`jinse` -> pick `禁` + pick `色` => learns `jinse -> 禁色` immediately at #1).
 *   2. Consecutive single-character composition (`jin` -> pick `禁`, then `se` -> pick `色` within 8s => learns `禁色`).
 *   3. Candidate frequency promotion (picking #2/#3/#4 `堇色` promotes `堇色` to #1 next time `jinse` is typed).
 *   4. Zero-shot Context & Session extraction via [HanziPinyin] (if `《禁色》` is in the text box or recent
 *      chat context, typing `jinse` surfaces `✦ 禁色` at #1 even on the very first time!).
 */
object UserZhLexicon {

    private const val FILE_NAME = "zh_learned_words.tsv"
    private const val MAX_ENTRIES = 15_000
    private const val CONSECUTIVE_CHAR_WINDOW_MS = 8_500L

    private val STOP_PARTICLES: Set<Char> = (
        "的了呢吧吗啊呀哦嗯是在有我你他她它不也都就还要会能可把被让给和跟向从到对比着过得地之儿个这那"
    ).toSet()

    private val GRAMMAR_PARTICLES: Set<Char> = "的了呢吧吗啊呀哦嗯着过得地".toSet()
    private val CLAUSE_GLUE_CHARS: Set<Char> = "我你他她它是在有不这那的了把被让给和跟到去来要会能可就都也还".toSet()

    data class LearnedEntry(
        val schema: String,
        val code: String,
        val initials: String,
        val word: String,
        var count: Int,
        var updatedAt: Long,
    )

    data class LookupResult(
        /** High-confidence learned / context-matched words placed at Candidate #1. */
        val topPriority: List<String>,
        /** Contextually relevant prefix / initials / 1-count words placed right after #1. */
        val secondary: List<String>,
        /** Learned prefix words `(word, remainingRawInput)` for long sentence inputs (`jinsezheshouge` -> `("禁色", "zheshouge")`). */
        val prefixSplits: List<Pair<String, String>>,
        /** Subset of [topPriority] that originated from live context/AI memory (display with `✦`). */
        val contextSparkleSet: Set<String>,
    )

    private val entries = ArrayList<LearnedEntry>()
    private var loadedFile: File? = null
    private var dirty = false

    // Tracks previous isolated 1-character Pinyin commit to catch `jin`->`禁` + `se`->`色`
    private var prevCharSchema = ""
    private var prevCharCode = ""
    private var prevCharText = ""
    private var prevCharNonDefault = false
    private var prevCharAt = 0L

    @Synchronized
    fun ensureLoaded(context: Context) {
        HanziPinyin.ensureLoadedAsync(context)
        loadFromFile(File(context.filesDir, FILE_NAME))
    }

    @Synchronized
    fun loadFromFile(file: File, force: Boolean = false) {
        if (!force && loadedFile?.absolutePath == file.absolutePath) return
        loadedFile = file
        entries.clear()
        dirty = false
        if (!file.exists()) return
        runCatching {
            file.forEachLine(Charsets.UTF_8) { line ->
                if (line.isBlank() || line.startsWith("#")) return@forEachLine
                val p = line.split('\t')
                if (p.size >= 6) {
                    val sc = p[0].trim()
                    val code = p[1].trim()
                    val init = p[2].trim()
                    val w = p[3].trim()
                    val cnt = p[4].toIntOrNull() ?: return@forEachLine
                    val ts = p[5].toLongOrNull() ?: 0L
                    if (sc.isNotEmpty() && code.isNotEmpty() && w.isNotEmpty() && cnt > 0) {
                        entries += LearnedEntry(sc, code, init, w, cnt, ts)
                    }
                }
            }
        }
    }

    @Synchronized
    fun resetConsecutiveTracker() {
        prevCharCode = ""
        prevCharText = ""
        prevCharAt = 0L
    }

    private fun cleanCode(raw: String): String =
        raw.lowercase().filter { it in 'a'..'z' || it in '0'..'9' }

    /** Drops [cleanPrefixLen] alphanumeric chars from [rawInput] (plus any trailing `'` separator). */
    private fun dropCleanPrefix(rawInput: String, cleanPrefixLen: Int): String {
        var matched = 0
        var idx = 0
        while (idx < rawInput.length && matched < cleanPrefixLen) {
            val lc = rawInput[idx].lowercaseChar()
            if (lc in 'a'..'z' || lc in '0'..'9') matched++
            idx++
        }
        while (idx < rawInput.length && (rawInput[idx] == '\'' || rawInput[idx] == ' ')) idx++
        return rawInput.substring(idx)
    }

    private fun isValidCjkPhrase(word: String): Boolean {
        val cpLen = word.codePointCount(0, word.length)
        return cpLen in 2..10 && word.all(ChinesePredictor::isCjk)
    }

    /**
     * Records an explicitly composed or selected multi-character phrase (`禁色`, `堇色`, `达明一派`).
     */
    @Synchronized
    fun learnPhrase(
        schema: String,
        rawCode: String,
        word: String,
        boost: Int = 2,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        resetConsecutiveTracker()
        if (!isValidCjkPhrase(word)) return
        if (word.last() in GRAMMAR_PARTICLES) return
        val code = cleanCode(rawCode).ifEmpty {
            if (schema == "rime_ice") HanziPinyin.primaryPinyin(word).orEmpty() else ""
        }
        if (code.length < 2) return
        val initials = HanziPinyin.primaryInitials(word).orEmpty()

        upsertLocked(schema, code, initials, word, boost, nowMs)
        // If learned under Double Pinyin or T9, also index Full Pinyin so switching schemas retains memory
        if (schema != "rime_ice") {
            val fullPy = HanziPinyin.primaryPinyin(word)
            if (!fullPy.isNullOrEmpty() && fullPy != code) {
                upsertLocked("rime_ice", fullPy, initials, word, boost, nowMs)
            }
        }
        saveLocked()
    }

    /**
     * Learns self-made words from a multi-segment Pinyin composition chain
     * (e.g. `jinse` -> `[("jin","禁"),("se","色")]`, `jinsede` -> strips `的` -> learns `禁色`,
     * `woxihuanjinse` -> splits clause prefix `我喜欢` -> learns `禁色`).
     */
    @Synchronized
    fun learnFromSegmentChain(
        schema: String,
        segments: List<Pair<String, String>>,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        resetConsecutiveTracker()
        if (segments.size < 2) return
        var start = 0
        var end = segments.size
        while (start < end && segments[start].second.length == 1 && segments[start].second[0] in GRAMMAR_PARTICLES) start++
        while (end > start && segments[end - 1].second.length == 1 && segments[end - 1].second[0] in GRAMMAR_PARTICLES) end--
        val trimmed = segments.subList(start, end)
        if (trimmed.size < 2) return

        val totalWord = trimmed.joinToString("") { it.second }
        val totalLen = totalWord.codePointCount(0, totalWord.length)
        if (totalLen in 2..4) {
            learnPhrase(schema, trimmed.joinToString("") { it.first }, totalWord, boost = 3, nowMs = nowMs)
            return
        }

        // Longer sentence composition: split on clause glue segments to extract clean 2..6 char words/names
        val sub = ArrayList<Pair<String, String>>(4)
        fun flushSub() {
            if (sub.size >= 2) {
                val w = sub.joinToString("") { it.second }
                val len = w.codePointCount(0, w.length)
                if (len in 2..6) {
                    learnPhrase(schema, sub.joinToString("") { it.first }, w, boost = 3, nowMs = nowMs)
                }
            }
            sub.clear()
        }
        for (seg in trimmed) {
            val w = seg.second
            val isBoundary = (w.length == 1 && w[0] in STOP_PARTICLES) ||
                (w.length >= 2 && (w.first() in CLAUSE_GLUE_CHARS || w.last() in CLAUSE_GLUE_CHARS))
            if (isBoundary) {
                flushSub()
            } else {
                sub += seg
            }
        }
        flushSub()
    }

    /**
     * Tracks isolated 1-character commits so typing `jin` -> `禁` followed immediately by `se` -> `色`
     * automatically learns `jinse -> 禁色`!
     */
    @Synchronized
    fun onSingleCharCommit(
        schema: String,
        rawCode: String,
        charText: String,
        wasNonDefaultPick: Boolean,
        recentContext: String = "",
        nowMs: Long = System.currentTimeMillis(),
    ) {
        val code = cleanCode(rawCode)
        if (charText.length != 1 || !ChinesePredictor.isCjk(charText[0]) || code.isEmpty()) {
            resetConsecutiveTracker()
            return
        }
        val ch = charText[0]
        if (prevCharText.length == 1 &&
            prevCharSchema == schema &&
            nowMs - prevCharAt in 1L..CONSECUTIVE_CHAR_WINDOW_MS &&
            prevCharText[0] !in STOP_PARTICLES &&
            ch !in STOP_PARTICLES
        ) {
            val pairWord = prevCharText + charText
            val pairCode = prevCharCode + code
            val seenBefore = pairWord in recentContext || entries.any { it.word == pairWord }
            if (prevCharNonDefault || wasNonDefaultPick || seenBefore) {
                val boost = if (seenBefore || (prevCharNonDefault && wasNonDefaultPick)) 2 else 2
                val initials = HanziPinyin.primaryInitials(pairWord).orEmpty()
                upsertLocked(schema, pairCode, initials, pairWord, boost, nowMs)
                if (schema != "rime_ice") {
                    HanziPinyin.primaryPinyin(pairWord)?.let { py ->
                        upsertLocked("rime_ice", py, initials, pairWord, boost, nowMs)
                    }
                }
                saveLocked()
            }
        }

        prevCharSchema = schema
        prevCharCode = code
        prevCharText = charText
        prevCharNonDefault = wasNonDefaultPick
        prevCharAt = nowMs
    }

    /**
     * Cross-engine learning: when the user commits a 2..6 char phrase via Handwriting or Voice
     * (e.g. handwrites `禁色`), index its Pinyin immediately so Pinyin typing knows it too!
     */
    @Synchronized
    fun observeHandwritingOrVoice(text: String, nowMs: Long = System.currentTimeMillis()) {
        val trimmed = text.trim()
        if (!isValidCjkPhrase(trimmed) || trimmed.codePointCount(0, trimmed.length) > 6) return
        val py = HanziPinyin.primaryPinyin(trimmed) ?: return
        val init = HanziPinyin.primaryInitials(trimmed).orEmpty()
        upsertLocked("rime_ice", py, init, trimmed, boost = 2, nowMs = nowMs)
        saveLocked()
    }

    private fun upsertLocked(
        schema: String,
        code: String,
        initials: String,
        word: String,
        boost: Int,
        nowMs: Long,
    ) {
        val idx = entries.indexOfFirst { it.schema == schema && it.code == code && it.word == word }
        if (idx >= 0) {
            val existing = entries.removeAt(idx)
            existing.count = (existing.count + boost).coerceAtMost(10_000)
            existing.updatedAt = nowMs
            entries.add(0, existing)
        } else {
            entries.add(0, LearnedEntry(schema, code, initials, word, boost.coerceAtLeast(1), nowMs))
            while (entries.size > MAX_ENTRIES) entries.removeAt(entries.lastIndex)
        }
        dirty = true
    }

    /**
     * Queries learned user phrases AND live context homophones for [rawInput] in <0.1 ms.
     */
    @Synchronized
    fun lookup(
        schema: String,
        rawInput: String,
        contextBeforeCursor: String = "",
        sessionMemory: String = "",
    ): LookupResult {
        val code = cleanCode(rawInput)
        if (code.length < 2) return LookupResult(emptyList(), emptyList(), emptyList(), emptySet())

        val top = ArrayList<String>(4)
        val secondary = ArrayList<String>(4)
        val prefixes = ArrayList<Pair<String, String>>(2)
        val sparkle = HashSet<String>()

        // 1) Exact learned code matches (sorted by recency + count)
        entries.asSequence()
            .filter { it.schema == schema && it.code == code }
            .sortedWith(compareByDescending<LearnedEntry> { it.count >= 2 }.thenByDescending { it.updatedAt })
            .forEach { e ->
                if (e.count >= 2 || e.word in contextBeforeCursor || e.word in sessionMemory) {
                    if (e.word !in top) top += e.word
                } else if (e.word !in top && e.word !in secondary) {
                    secondary += e.word
                }
            }

        // 2) Zero-shot Context & Session Homophone Extraction (`《禁色》` in chat -> `jinse` => `✦ 禁色`!)
        if (schema == "rime_ice" && HanziPinyin.isLoaded) {
            val ctxHits = HanziPinyin.extractMatchingPhrases(contextBeforeCursor, code, maxChars = 6, maxResults = 2)
            for (h in ctxHits) {
                if (h !in top) {
                    top.add(0, h)
                    sparkle += h
                }
            }
            if (top.isEmpty() && sessionMemory.isNotEmpty()) {
                val memHits = HanziPinyin.extractMatchingPhrases(sessionMemory.takeLast(500), code, maxChars = 6, maxResults = 2)
                for (h in memHits) {
                    if (h !in top) {
                        top += h
                        sparkle += h
                    }
                }
            }
        }

        // 3) Initials (`js` -> `禁色`) and incomplete prefix (`jins` -> `禁色`)
        for (e in entries) {
            if (e.schema != schema) continue
            if (e.word in top || e.word in secondary) continue
            if (code.length in 2..5 && e.initials.isNotEmpty() && e.initials == code && e.count >= 2) {
                secondary += e.word
            } else if (code.length >= 3 && e.code.startsWith(code) && e.code.length <= code.length + 4 && e.count >= 2) {
                secondary += e.word
            }
            if (secondary.size >= 3) break
        }

        // 4) Long-sentence learned prefix split (`jinsezheshouge` starts with learned `jinse` -> `禁色`)
        if (code.length >= 5) {
            for (e in entries) {
                if (e.schema != schema || e.count < 2 || e.code.length < 4) continue
                if (code.length > e.code.length && code.startsWith(e.code)) {
                    val rem = dropCleanPrefix(rawInput, e.code.length)
                    if (rem.isNotEmpty() && prefixes.none { it.first == e.word }) {
                        prefixes += e.word to rem
                        if (prefixes.size >= 2) break
                    }
                }
            }
        }

        return LookupResult(top.take(3), secondary.take(3), prefixes, sparkle)
    }

    @Synchronized
    fun forget(word: String): Boolean {
        val removed = entries.removeAll { it.word == word }
        if (removed) {
            dirty = true
            saveLocked()
        }
        return removed
    }

    @Synchronized
    fun learnedCount(context: Context? = null): Int {
        if (context != null) ensureLoaded(context)
        return entries.map { it.word }.distinct().size
    }

    @Synchronized
    fun allEntries(context: Context? = null): List<LearnedEntry> {
        if (context != null) ensureLoaded(context)
        return entries.toList()
    }

    @Synchronized
    fun clearAll(context: Context? = null) {
        if (context != null) ensureLoaded(context)
        entries.clear()
        resetConsecutiveTracker()
        dirty = false
        loadedFile?.delete()
    }

    @Synchronized
    fun save() {
        if (dirty) saveLocked()
    }

    private fun saveLocked() {
        val f = loadedFile ?: return
        dirty = false
        runCatching {
            f.parentFile?.mkdirs()
            val tmp = File(f.path + ".tmp")
            tmp.bufferedWriter(Charsets.UTF_8).use { w ->
                for (e in entries) {
                    val cleanW = e.word.replace('\t', ' ').replace('\n', ' ')
                    w.write("${e.schema}\t${e.code}\t${e.initials}\t$cleanW\t${e.count}\t${e.updatedAt}\n")
                }
            }
            Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
