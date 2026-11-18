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

    @Volatile var state = State.IDLE
        private set
    val isReady: Boolean get() = state == State.READY

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "rime").apply { priority = Thread.NORM_PRIORITY - 1 } }
    private val main = Handler(Looper.getMainLooper())
    private val listeners = mutableListOf<() -> Unit>()
    private var currentSchema: String? = null

    fun addStateListener(l: () -> Unit) { listeners += l }
    fun removeStateListener(l: () -> Unit) { listeners -= l }

    /** Copies data if needed and deploys in the background. Safe to call repeatedly. */
    fun start(context: Context, schema: String?) {
        if (state == State.DEPLOYING || state == State.READY) {
            if (state == State.READY && schema != null && schema != currentSchema) selectSchema(schema)
            return
        }
        state = State.DEPLOYING
        val app = context.applicationContext
        notifyListeners()
        worker.execute {
            val t0 = System.currentTimeMillis()
            val ok = runCatching {
                val root = File(app.filesDir, "rime")
                val shared = File(root, "shared")
                val user = File(root, "user").apply { mkdirs() }
                val changed = syncAssets(app, shared)
                val logDir = if (app.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0)
                    File(app.cacheDir, "rime_log").apply { mkdirs() }.absolutePath else ""
                Rime.nativeStart(shared.absolutePath, user.absolutePath, logDir, changed)
            }.onFailure { Log.e(TAG, "rime start failed", it) }.getOrDefault(false)
            if (ok && schema != null) Rime.nativeSelectSchema(schema)
            if (ok) applyOptions()
            currentSchema = if (ok) Rime.nativeCurrentSchema() else null
            Log.i(TAG, "rime ready=$ok schema=$currentSchema in ${System.currentTimeMillis() - t0} ms")
            state = if (ok) State.READY else State.FAILED
            notifyListeners()
        }
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
    fun alternatives(schema: String, input: String, syllables: Int, firstK: Int = 4, secondK: Int = 2): List<String> =
        if (isReady) Rime.nativeAlternatives(schema, input, syllables, firstK, secondK).toList() else emptyList()

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

    private const val TAG = "RimeEngine"
}
