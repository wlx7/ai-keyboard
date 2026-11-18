package app.aikb.ime.engine

import java.io.File
import java.util.TreeMap

/**
 * Words the user actually types, with counts. Stored only in the app's private files dir;
 * there is no network permission, so it never leaves the phone.
 */
class UserHistory(private val file: File) {
    private val counts = TreeMap<String, Int>()
    private var dirty = false

    init {
        runCatching {
            if (file.exists()) file.forEachLine { line ->
                val tab = line.indexOf('\t')
                if (tab > 0) line.substring(tab + 1).toIntOrNull()?.let { counts[line.substring(0, tab)] = it }
            }
        }
    }

    @Synchronized fun count(word: String): Int = counts[word] ?: 0

    @Synchronized fun add(word: String) {
        counts[word] = (counts[word] ?: 0) + 1
        dirty = true
        if (counts.size > 50_000) { // forget the rarest words
            counts.entries.sortedBy { it.value }.take(10_000).forEach { counts.remove(it.key) }
        }
    }

    /** Most-used learned words that start with [prefix] (lowercase), excluding exact match. */
    @Synchronized fun withPrefix(prefix: String, max: Int): List<Pair<String, Int>> {
        if (prefix.isEmpty()) return emptyList()
        return counts.subMap(prefix, true, prefix + Char.MAX_VALUE, false).entries
            .filter { it.key != prefix && it.value >= 2 }
            .sortedByDescending { it.value }.take(max).map { it.key to it.value }
    }

    @Synchronized fun save() {
        if (!dirty) return
        runCatching {
            val tmp = File(file.path + ".tmp")
            tmp.bufferedWriter().use { w -> counts.forEach { (k, v) -> w.write("$k\t$v\n") } }
            tmp.renameTo(file)
            dirty = false
        }
    }

    @Synchronized fun clear() {
        counts.clear(); dirty = true; save()
    }
}
