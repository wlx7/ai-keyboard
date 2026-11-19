package app.aikb.ime.engine

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.util.concurrent.Executors

/**
 * Process-wide Chinese engine on top of librime.
 *
 * Data: assets/rime is copied to files/rime/shared whenever its version.txt stamp changes; librime
 * compiles it into files/rime/user/build (first run takes a while, so it happens off the UI
 * thread). The user's learned words live in files/rime/user too — private, never uploaded.
 *
 * While deploying, librime holds its lock for a long time, so every UI-thread call is gated on
 * [isReady]; callers fall back to raw letters until then.
 */
object RimeEngine {
    enum class State { IDLE, DEPLOYING, READY, FAILED }

    data class Candidate(val text: String, val comment: String)
    data class Snapshot(val preedit: String, val input: String, val candidates: List<Candidate>) {
        val composing: Boolean get() = input.isNotEmpty()
    }

    /** Schemes shipped in assets/rime/default.yaml, in picker order. */
    val SCHEMAS = listOf(
        "rime_ice" to "全拼",
        "t9" to "九宫格拼音",
        "double_pinyin_flypy" to "小鹤双拼",
        "double_pinyin" to "自然码双拼",
        "double_pinyin_mspy" to "微软双拼",
        "double_pinyin_sogou" to "搜狗双拼",
        "double_pinyin_abc" to "智能ABC双拼",
        "double_pinyin_ziguang" to "紫光双拼",
        "wubi86_jidian" to "五笔86",
        "cangjie5" to "仓颉五代",
    )

    private fun hintMap(letters: String, glyphs: String): Map<Char, String> =
        letters.indices.associate { letters[it] to glyphs.substring(it, it + 1) }

    private val WUBI_HINTS = hintMap("gfdsahjklmtrewqyuiopnbvcx", "王土大木工目日口田山禾白月人金言立水火之已子女又纟")
    private val CANGJIE_HINTS = hintMap("abcdefghijklmnopqrstuvwxyz", "日月金木水火土竹戈十大中一弓人心手口尸廿山女田難卜重")

    /** Radical shown in the corner of each letter key for shape-based schemes. */
    fun keyHints(id: String?): Map<Char, String> = when (id) {
        "wubi86_jidian" -> WUBI_HINTS
        "cangjie5" -> CANGJIE_HINTS
        else -> emptyMap()
    }

    fun shortName(id: String?): String = when (id) {
        null, "rime_ice" -> "拼音"
        "wubi86_jidian" -> "五笔"
        "cangjie5" -> "仓颉"
        "t9" -> "九宫格"
        else -> "双拼"
    }

    data class FuzzyPair(
        val id: String,
        val label: String,
        val group: String,
        val rules: List<String>,
    )

    val FUZZY_PAIRS = listOf(
        FuzzyPair("z_zh", "z = zh（平翘舌）", "声母", listOf("derive/^zh/z/", "derive/^z([^h])/zh$1/")),
        FuzzyPair("c_ch", "c = ch（平翘舌）", "声母", listOf("derive/^ch/c/", "derive/^c([^h])/ch$1/")),
        FuzzyPair("s_sh", "s = sh（平翘舌）", "声母", listOf("derive/^sh/s/", "derive/^s([^h])/sh$1/")),
        FuzzyPair("n_l", "n = l（鼻边音）", "声母", listOf("derive/^l/n/", "derive/^n/l/")),
        FuzzyPair("f_h", "f = h", "声母", listOf("derive/^f/h/", "derive/^h/f/")),
        FuzzyPair("r_l", "r = l", "声母", listOf("derive/^r/l/", "derive/^l/r/")),
        FuzzyPair("k_g", "k = g", "声母", listOf("derive/^k/g/", "derive/^g/k/")),
        FuzzyPair("an_ang", "an = ang（前后鼻音）", "韵母", listOf("derive/ang$/an/", "derive/an$/ang/")),
        FuzzyPair("en_eng", "en = eng（前后鼻音）", "韵母", listOf("derive/eng$/en/", "derive/en$/eng/")),
        FuzzyPair("in_ing", "in = ing（前后鼻音）", "韵母", listOf("derive/ing$/in/", "derive/in$/ing/")),
        FuzzyPair("ian_iang", "ian = iang", "韵母", listOf("derive/iang$/ian/", "derive/ian$/iang/")),
        FuzzyPair("uan_uang", "uan = uang", "韵母", listOf("derive/uang$/uan/", "derive/uan$/uang/")),
    )

    val COMMON_FUZZY_IDS = setOf("z_zh", "c_ch", "s_sh", "n_l", "an_ang", "en_eng", "in_ing")

    private val PINYIN_SCHEMA_FILES = listOf(
        "rime_ice",
        "t9",
        "double_pinyin_flypy",
        "double_pinyin",
        "double_pinyin_mspy",
        "double_pinyin_sogou",
        "double_pinyin_abc",
        "double_pinyin_ziguang",
    )

    const val PREF_FUZZY_ENABLED = "fuzzy_pinyin_enabled"
    const val PREF_FUZZY_PAIRS = "fuzzy_pinyin_pairs"

    fun activeFuzzyIds(context: Context): Set<String> {
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        if (!prefs.getBoolean(PREF_FUZZY_ENABLED, false)) return emptySet()
        return prefs.getStringSet(PREF_FUZZY_PAIRS, COMMON_FUZZY_IDS).orEmpty()
    }

    @Volatile var state = State.IDLE
        private set
    val isReady: Boolean get() = state == State.READY

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "rime").apply { priority = Thread.NORM_PRIORITY - 1 } }
    private val main = Handler(Looper.getMainLooper())
    private val listeners = mutableListOf<() -> Unit>()
    private var currentSchema: String? = null
    @Volatile private var pendingRedeploy = false

    fun addStateListener(l: () -> Unit) { listeners += l }
    fun removeStateListener(l: () -> Unit) { listeners -= l }

    /** Copies data if needed and deploys in the background. Safe to call repeatedly. */
    fun start(context: Context, schema: String?, forceRedeploy: Boolean = false) {
        if (!forceRedeploy && (state == State.DEPLOYING || state == State.READY)) {
            if (state == State.READY && schema != null && schema != currentSchema) selectSchema(schema)
            return
        }
        if (state == State.DEPLOYING) {
            pendingRedeploy = true
            return
        }
        state = State.DEPLOYING
        val app = context.applicationContext
        val targetSchema = schema ?: currentSchema
        notifyListeners()
        worker.execute {
            do {
                pendingRedeploy = false
                val t0 = System.currentTimeMillis()
                val ok = runCatching {
                    val root = File(app.filesDir, "rime")
                    val shared = File(root, "shared")
                    val user = File(root, "user").apply { mkdirs() }
                    val assetsChanged = syncAssets(app, shared)
                    if (assetsChanged) File(user, "fuzzy_stamp.txt").delete()
                    val fuzzyChanged = syncFuzzyAlgebra(shared, user, activeFuzzyIds(app))
                    val logDir = if (app.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0)
                        File(app.cacheDir, "rime_log").apply { mkdirs() }.absolutePath else ""
                    Rime.nativeStart(shared.absolutePath, user.absolutePath, logDir, assetsChanged || fuzzyChanged || forceRedeploy)
                }.onFailure { Log.e(TAG, "rime start failed", it) }.getOrDefault(false)
                if (ok && targetSchema != null) Rime.nativeSelectSchema(targetSchema)
                if (ok) applyOptions()
                currentSchema = if (ok) Rime.nativeCurrentSchema() else null
                Log.i(TAG, "rime ready=$ok schema=$currentSchema in ${System.currentTimeMillis() - t0} ms")
                state = if (ok) State.READY else State.FAILED
            } while (pendingRedeploy)
            notifyListeners()
        }
    }

    /** Triggers a background prism rebuild after Fuzzy Pinyin settings change. */
    fun redeployFuzzy(context: Context) {
        start(context, currentSchema, forceRedeploy = true)
    }

    private fun syncFuzzyAlgebra(shared: File, user: File, enabledIds: Set<String>): Boolean {
        val stampVal = enabledIds.sorted().joinToString(",")
        val stampFile = File(user, "fuzzy_stamp.txt")
        if (stampFile.exists() && stampFile.readText().trim() == stampVal) return false

        val rules = FUZZY_PAIRS.filter { it.id in enabledIds }.flatMap { it.rules }
        val block = if (rules.isEmpty()) "" else buildString {
            append("    # BEGIN_LINGXI_FUZZY\n")
            for (r in rules) append("    - ").append(r).append('\n')
            append("    # END_LINGXI_FUZZY\n")
        }

        val buildDir = File(user, "build")
        for (s in PINYIN_SCHEMA_FILES) {
            val schemaFile = File(shared, "$s.schema.yaml")
            if (!schemaFile.exists()) continue
            var content = schemaFile.readText(Charsets.UTF_8)
            content = content.replace(
                Regex("(?ms)^    # BEGIN_LINGXI_FUZZY\\n.*?^    # END_LINGXI_FUZZY\\n"),
                "",
            )
            if (block.isNotEmpty()) {
                content = content.replaceFirst("  algebra:\n", "  algebra:\n$block")
            }
            schemaFile.writeText(content, Charsets.UTF_8)
            // Remove compiled prism so librime deterministically rebuilds it even within 1s mtime granularity
            File(buildDir, "$s.prism.bin").delete()
            File(buildDir, "$s.schema.yaml").delete()
        }
        stampFile.writeText(stampVal, Charsets.UTF_8)
        return true
    }

    private fun notifyListeners() = main.post { listeners.toList().forEach { it() } }

    private fun syncAssets(context: Context, shared: File): Boolean {
        val assetVersion = context.assets.open("rime/version.txt").bufferedReader().use { it.readText().trim() }
        val stamp = File(shared, "version.txt")
        if (stamp.exists() && stamp.readText().trim() == assetVersion) return false
        shared.deleteRecursively()
        copyAssetDir(context, "rime", shared)
        stamp.writeText(assetVersion)
        return true
    }

    private fun copyAssetDir(context: Context, path: String, dest: File) {
        val children = context.assets.list(path).orEmpty()
        if (children.isEmpty()) { // a file
            dest.parentFile?.mkdirs()
            context.assets.open(path).use { input -> dest.outputStream().use { input.copyTo(it, 1 shl 16) } }
            return
        }
        dest.mkdirs()
        for (c in children) copyAssetDir(context, "$path/$c", File(dest, c))
    }

    // ------------------------------------------------------------- session (UI thread, READY only)

    fun processKey(keycode: Int, mask: Int = 0): Boolean = isReady && Rime.nativeProcessKey(keycode, mask)

    fun takeCommit(): String? = if (isReady) Rime.nativeGetCommit() else null

    fun snapshot(maxCandidates: Int = 80): Snapshot {
        if (!isReady) return Snapshot("", "", emptyList())
        val comp = Rime.nativeGetComposition()
        val input = comp[2].orEmpty()
        if (input.isEmpty()) return Snapshot("", "", emptyList())
        val raw = Rime.nativeGetCandidates(maxCandidates)
        val list = ArrayList<Candidate>(raw.size / 2)
        for (i in 0 until raw.size / 2) list += Candidate(raw[2 * i], raw[2 * i + 1])
        return Snapshot(comp[0].orEmpty(), input, list)
    }

    fun select(index: Int): Boolean = isReady && Rime.nativeSelectCandidate(index)

    /** Whole-sentence alternatives for [input]; safe to call from a background thread. */
    fun alternatives(schema: String, input: String, syllables: Int, firstK: Int = 6, secondK: Int = 3): List<String> =
        if (isReady) Rime.nativeAlternatives(schema, input, syllables, firstK, secondK).toList() else emptyList()

    /** Queries top conversions for [input] in Rime's hidden session without side effects (used by PinyinCorrector). */
    fun queryTop(schema: String, input: String, maxCands: Int = 4): List<String> =
        if (isReady) Rime.nativeQueryTop(schema, input, maxCands, traditional).toList() else emptyList()

    fun forget(index: Int): Boolean = isReady && Rime.nativeDeleteCandidate(index)

    fun clear() { if (isReady) Rime.nativeClearComposition() }

    fun selectSchema(id: String) {
        if (!isReady) return
        if (Rime.nativeSelectSchema(id)) currentSchema = id
        applyOptions()
    }

    /** Output traditional characters. Each scheme names this switch differently. */
    @Volatile var traditional = false
        set(v) { field = v; if (isReady) applyOptions() }

    private fun applyOptions() {
        Rime.nativeSetOption("traditionalization", traditional) // rime-ice family (全拼/双拼/九宫格)
        Rime.nativeSetOption("zh_trad", traditional)            // 五笔86 极点
        Rime.nativeSetOption("simplification", !traditional)    // 仓颉: dictionary is traditional
    }

    fun currentSchema(): String? = currentSchema

    fun setOption(name: String, value: Boolean) { if (isReady) Rime.nativeSetOption(name, value) }

    /** Flushes Rime's binary userdb to plain-text `.userdb.txt` under `files/rime/user/sync` and harvests learned entries. */
    fun exportSyncedUserWords(context: Context): List<Pair<String, String>> {
        if (isReady) runCatching { Rime.nativeSyncUserData() }
        val syncRoot = File(context.filesDir, "rime/user/sync")
        if (!syncRoot.exists()) return emptyList()
        val out = ArrayList<Pair<String, String>>()
        runCatching {
            syncRoot.walkTopDown()
                .filter { it.isFile && it.name.endsWith(".userdb.txt") }
                .forEach { f ->
                    f.forEachLine(Charsets.UTF_8) { line ->
                        if (line.isBlank() || line.startsWith("#")) return@forEachLine
                        val parts = line.split('\t')
                        if (parts.size >= 2) {
                            val py = parts[0].trim().filter { it in 'a'..'z' }
                            val word = parts[1].trim()
                            val meta = parts.getOrNull(2).orEmpty()
                            // Skip deleted entries (c <= 0)
                            if (word.isNotEmpty() && !meta.contains("c=0") && !meta.contains("c=-")) {
                                out += py to word
                            }
                        }
                    }
                }
        }
        return out
    }

    private const val TAG = "RimeEngine"
}
