package app.aikb.ime.engine

import android.content.Context
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Gboard-compatible offline Personal Dictionary & User Lexicon manager (`个人词典与词库导入导出`).
 *
 * Supports:
 *   1. Gboard `.zip` (`dictionary.txt` with `# Gboard Dictionary version:1` and `shortcut\tword\tlocale`)
 *      for 100% lossless two-way migration between Gboard and Keylo.
 *   2. Rime `.dict.yaml` / `.userdb.txt`, Sogou TSV, and plain line-delimited word lists.
 *   3. Instant live candidate surfacing for custom phrases, names, addresses, emails, and shortcuts.
 */
object PersonalDictionary {

    const val GBOARD_HEADER = "# Gboard Dictionary version:1"
    private const val FILE_NAME = "personal_dict.tsv"
    private const val MAX_ENTRIES = 20_000

    data class Entry(
        val shortcut: String,
        val word: String,
        val locale: String = "",
    )

    data class Match(
        val word: String,
        val exactShortcut: Boolean,
    )

    data class ImportSummary(
        val importedCustom: Int,
        val importedEnglishWords: Int,
        val importedChineseBigrams: Int,
    ) {
        val total: Int get() = importedCustom + importedEnglishWords + importedChineseBigrams
    }

    private val entries = ArrayList<Entry>()
    private var loadedFile: File? = null

    @Synchronized
    fun ensureLoaded(context: Context) {
        loadFromFile(File(context.filesDir, FILE_NAME))
    }

    @Synchronized
    fun loadFromFile(file: File, force: Boolean = false) {
        if (!force && loadedFile?.absolutePath == file.absolutePath) return
        loadedFile = file
        entries.clear()
        if (!file.exists()) return
        runCatching {
            file.forEachLine(Charsets.UTF_8) { line ->
                if (line.isBlank() || line.startsWith("#")) return@forEachLine
                val parts = line.split('\t')
                if (parts.size >= 2) {
                    val sc = normalizeShortcut(parts[0])
                    val w = parts[1].trim()
                    val loc = parts.getOrNull(2)?.trim().orEmpty()
                    if (w.isNotEmpty()) entries += Entry(sc, w, loc)
                }
            }
        }
    }

    @Synchronized
    fun allEntries(context: Context? = null): List<Entry> {
        if (context != null) ensureLoaded(context)
        return entries.toList()
    }

    @Synchronized
    fun count(context: Context? = null): Int {
        if (context != null) ensureLoaded(context)
        return entries.size
    }

    private fun normalizeShortcut(raw: String): String =
        raw.trim().lowercase().filter { it in 'a'..'z' || it in '0'..'9' || it == '_' || it == '-' }

    private fun inferLocale(word: String): String =
        if (word.any(ChinesePredictor::isCjk)) "zh-CN" else "en-US"

    @Synchronized
    fun addOrUpdate(context: Context, rawShortcut: String, rawWord: String, rawLocale: String = ""): Boolean {
        ensureLoaded(context)
        val file = loadedFile ?: File(context.filesDir, FILE_NAME).also { loadedFile = it }
        return addOrUpdateInFile(file, rawShortcut, rawWord, rawLocale)
    }

    @Synchronized
    fun addOrUpdateInFile(file: File, rawShortcut: String, rawWord: String, rawLocale: String = ""): Boolean {
        loadFromFile(file)
        val word = rawWord.trim()
        if (word.isEmpty() || word.length > 200) return false
        val shortcut = normalizeShortcut(rawShortcut)
        val locale = rawLocale.trim().ifEmpty { inferLocale(word) }
        entries.removeAll { it.word == word && it.shortcut == shortcut }
        entries.add(0, Entry(shortcut, word, locale))
        while (entries.size > MAX_ENTRIES) entries.removeAt(entries.lastIndex)
        saveLocked(file)
        return true
    }

    @Synchronized
    fun remove(context: Context, entry: Entry) {
        ensureLoaded(context)
        val file = loadedFile ?: return
        if (entries.removeAll { it.shortcut == entry.shortcut && it.word == entry.word }) {
            saveLocked(file)
        }
    }

    @Synchronized
    fun clearAll(context: Context) {
        ensureLoaded(context)
        entries.clear()
        loadedFile?.delete()
    }

    private fun saveLocked(file: File) {
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.bufferedWriter(Charsets.UTF_8).use { w ->
                w.write("$GBOARD_HEADER\n")
                for (e in entries) {
                    val cleanWord = e.word.replace('\t', ' ').replace('\n', ' ')
                    w.write("${e.shortcut}\t$cleanWord\t${e.locale}\n")
                }
            }
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /**
     * Matches [rawInput] against personal shortcuts & words during live typing.
     */
    @Synchronized
    fun match(rawInput: String, chinese: Boolean, max: Int = 3): List<Match> {
        if (entries.isEmpty() || rawInput.isEmpty() || max <= 0) return emptyList()
        val q = rawInput.trim().lowercase()
        if (q.isEmpty()) return emptyList()

        val exact = ArrayList<Match>()
        val prefix = ArrayList<Match>()
        val seen = HashSet<String>()

        for (e in entries) {
            val locLow = e.locale.lowercase()
            val localeCompatible = when {
                locLow.isEmpty() -> true
                chinese -> locLow.startsWith("zh") || e.shortcut.isNotEmpty()
                else -> !locLow.startsWith("zh") || e.shortcut.isNotEmpty()
            }
            if (!localeCompatible) continue

            if (e.shortcut.isNotEmpty()) {
                if (e.shortcut == q) {
                    if (seen.add(e.word)) exact += Match(e.word, exactShortcut = true)
                } else if (q.length >= 2 && e.shortcut.startsWith(q)) {
                    if (seen.add(e.word)) prefix += Match(e.word, exactShortcut = false)
                }
            } else if (!chinese && q.length >= 2 && e.word.lowercase().startsWith(q) && e.word.lowercase() != q) {
                if (seen.add(e.word)) prefix += Match(e.word, exactShortcut = false)
            }
            if (exact.size >= max) break
        }
        return (exact + prefix).take(max)
    }

    /**
     * Imports a Gboard `.zip` (`dictionary.txt`), Gboard `.txt`, Rime `.dict.yaml` / `.userdb.txt`,
     * or plain TSV/line wordlist from [rawStream].
     */
    @Synchronized
    fun importFromStream(
        dictFile: File,
        rawStream: InputStream,
        enHistory: UserHistory? = null,
        zhPredictor: ChinesePredictor? = null,
    ): ImportSummary {
        loadFromFile(dictFile)
        val allBytes = rawStream.readBytes()
        if (allBytes.isEmpty()) return ImportSummary(0, 0, 0)

        val textBlobs = ArrayList<String>()
        val isZip = allBytes.size >= 4 &&
            allBytes[0] == 0x50.toByte() && allBytes[1] == 0x4B.toByte() &&
            allBytes[2] == 0x03.toByte() && allBytes[3] == 0x04.toByte()

        if (isZip) {
            ZipInputStream(BufferedInputStream(ByteArrayInputStream(allBytes))).use { zis ->
                var ze: ZipEntry? = zis.nextEntry
                while (ze != null) {
                    if (!ze.isDirectory && (ze.name.endsWith(".txt", true) || ze.name.endsWith(".tsv", true) || ze.name.endsWith(".yaml", true))) {
                        textBlobs += zis.readBytes().toString(Charsets.UTF_8)
                    }
                    zis.closeEntry()
                    ze = zis.nextEntry
                }
            }
        } else {
            textBlobs += allBytes.toString(Charsets.UTF_8)
        }

        var customAdded = 0
        var enAdded = 0
        var zhAdded = 0
        val existingSet = entries.map { "${it.shortcut}\u0000${it.word}" }.toHashSet()
        var inYamlHeader = false

        for (blob in textBlobs) {
            blob.lineSequence().forEach { rawLine ->
                val line = rawLine.trimEnd('\r').trimStart('\uFEFF')
                if (line.isBlank() || line.startsWith("#")) return@forEach
                if (line.trim() == "---") { inYamlHeader = true; return@forEach }
                if (line.trim() == "...") { inYamlHeader = false; return@forEach }
                if (inYamlHeader) return@forEach

                var shortcut = ""
                var word = ""
                var locale = ""

                if ('\t' in line) {
                    val cols = line.split('\t')
                    val c0 = cols[0].trim()
                    val c1 = cols.getOrNull(1)?.trim().orEmpty()
                    val c2 = cols.getOrNull(2)?.trim().orEmpty()

                    when {
                        // 1) Gboard format: `[shortcut]\t[word]\t[locale]` (3 columns, or blank col0)
                        cols.size >= 3 && (c2.contains('-') || c2.length in 2..5 && c2.all { it.isLetter() } || c0.isEmpty()) -> {
                            shortcut = normalizeShortcut(c0)
                            word = c1
                            locale = c2
                        }
                        // 2) Rime .dict.yaml format: `词语\tpin yin\tweight` (col0 has CJK, col1 is ASCII pinyin)
                        c0.any(ChinesePredictor::isCjk) && c1.isNotEmpty() && c1.all { it in 'a'..'z' || it == ' ' || it == '\'' } -> {
                            shortcut = normalizeShortcut(c1)
                            word = c0
                            locale = "zh-CN"
                        }
                        // 3) Rime .userdb.txt format: `pin yin \t词语\tc=3 d=...`
                        c1.any(ChinesePredictor::isCjk) && c0.all { it in 'a'..'z' || it == ' ' || it == '\'' } -> {
                            if (c2.contains("c=0") || c2.contains("c=-")) return@forEach
                            shortcut = normalizeShortcut(c0)
                            word = c1
                            locale = "zh-CN"
                        }
                        // 4) UserHistory TSV (`word\tcount`) or ChinesePredictor TSV (`head\tnext\tcount`)
                        cols.size == 3 && c2.toIntOrNull() != null && c0.all(ChinesePredictor::isCjk) && c1.all(ChinesePredictor::isCjk) -> {
                            zhPredictor?.learn(c0, c1)
                            zhAdded++
                            return@forEach
                        }
                        cols.size == 2 && c1.toIntOrNull() != null -> {
                            word = c0
                            locale = inferLocale(c0)
                        }
                        else -> {
                            shortcut = normalizeShortcut(c0)
                            word = c1.ifEmpty { c0 }
                            locale = inferLocale(word)
                        }
                    }
                } else {
                    word = line.trim()
                    locale = inferLocale(word)
                }

                if (word.isEmpty() || word.length > 200) return@forEach
                if (locale.isEmpty()) locale = inferLocale(word)

                // Teach English single words to UserHistory
                if (shortcut.isEmpty() && ' ' !in word && word.all { it in 'a'..'z' || it in 'A'..'Z' || it == '\'' }) {
                    enHistory?.importWord(word, minCount = 3)
                    enAdded++
                }
                // Teach 4-char Chinese idioms/phrases into 2+2 predictor bigrams too
                if (word.length == 4 && word.all(ChinesePredictor::isCjk)) {
                    zhPredictor?.learn(word.substring(0, 2), word.substring(2, 4))
                    zhAdded++
                }

                val key = "$shortcut\u0000$word"
                if (entries.size < MAX_ENTRIES && existingSet.add(key)) {
                    entries += Entry(shortcut, word, locale)
                    customAdded++
                }
            }
        }

        enHistory?.save()
        zhPredictor?.save()
        if (customAdded > 0) saveLocked(dictFile)
        return ImportSummary(customAdded, enAdded, zhAdded)
    }

    /**
     * Exports all personal dictionary entries + learned English & Chinese user lexicon as a
     * standard Gboard-compatible `.zip` (`dictionary.txt` at archive root).
     */
    @Synchronized
    fun exportGboardZip(
        dictFile: File,
        outStream: OutputStream,
        enHistory: UserHistory? = null,
        zhPredictor: ChinesePredictor? = null,
        rimeUserWords: List<Pair<String, String>> = emptyList(),
    ): Int {
        loadFromFile(dictFile)
        val outRows = LinkedHashSet<String>()

        for (e in entries) {
            val w = e.word.replace('\t', ' ').replace('\n', ' ')
            val loc = e.locale.ifEmpty { inferLocale(w) }
            outRows += "${e.shortcut}\t$w\t$loc"
        }
        for ((py, word) in rimeUserWords) {
            if (word.length >= 2) outRows += "${normalizeShortcut(py)}\t$word\tzh-CN"
        }
        zhPredictor?.allLearnedPairs()?.forEach { (head, next, _) ->
            outRows += "\t$head$next\tzh-CN"
        }
        enHistory?.allWords()?.take(5_000)?.forEach { (w, _) ->
            outRows += "\t$w\ten-US"
        }

        ZipOutputStream(outStream).use { zos ->
            zos.putNextEntry(ZipEntry("dictionary.txt"))
            val sb = StringBuilder()
            sb.append(GBOARD_HEADER).append('\n')
            for (row in outRows) sb.append(row).append('\n')
            zos.write(sb.toString().toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }
        return outRows.size
    }
}
