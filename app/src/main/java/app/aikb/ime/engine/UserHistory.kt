package app.aikb.ime.engine

import java.io.File
import java.util.TreeMap

/**
 * Words and word-pairs (bigrams) the user actually types, with counts.
 * Stored only in the app's private files dir ("word\tcount" and "prev next\tcount");
 * there is no network permission, so it never leaves the phone.
 */
class UserHistory(private val file: File) {
    private val counts = TreeMap<String, Int>()
    private val bigrams = HashMap<String, MutableMap<String, Int>>()
    private var bigramTotal = 0
    private var dirty = false

    init {
        runCatching {
            if (file.exists()) file.forEachLine { line ->
                val tab = line.indexOf('\t')
                if (tab > 0) {
                    val cnt = line.substring(tab + 1).toIntOrNull() ?: return@forEachLine
                    val k = line.substring(0, tab)
                    val sp = k.indexOf(' ')
                    if (sp > 0 && sp < k.lastIndex) {
                        val w1 = k.substring(0, sp)
                        val w2 = k.substring(sp + 1)
                        bigrams.getOrPut(w1) { HashMap() }[w2] = cnt
                        bigramTotal++
                    } else {
                        counts[k] = cnt
                    }
                }
            }
        }
    }

    @Synchronized fun count(word: String): Int = counts[word] ?: 0

    @Synchronized fun add(word: String, prevWord: String? = null) {
        counts[word] = (counts[word] ?: 0) + 1
        dirty = true
        if (counts.size > 50_000) {
            counts.entries.sortedBy { it.value }.take(10_000).forEach { counts.remove(it.key) }
        }
        val prev = prevWord?.trim()?.lowercase()?.takeIf { it.length in 1..32 && ' ' !in it }
        if (prev != null && ' ' !in word && prev != word) {
            val m = bigrams.getOrPut(prev) { HashMap() }
            if (word !in m) bigramTotal++
            m[word] = (m[word] ?: 0) + 1
            if (m.size > 32) {
                val rarest = m.entries.minByOrNull { it.value }?.key
                if (rarest != null) { m.remove(rarest); bigramTotal-- }
            }
            if (bigramTotal > 60_000) {
                val drop = bigrams.keys.take(5_000).toList()
                for (k in drop) bigramTotal -= (bigrams.remove(k)?.size ?: 0)
            }
        }
    }

    /** Most-used learned words that start with [prefix] (lowercase), excluding exact match. */
    @Synchronized fun withPrefix(prefix: String, max: Int): List<Pair<String, Int>> {
        if (prefix.isEmpty()) return emptyList()
        return counts.subMap(prefix, true, prefix + Char.MAX_VALUE, false).entries
            .filter { it.key != prefix && it.value >= 2 }
            .sortedByDescending { it.value }.take(max).map { it.key to it.value }
    }

    /** Learned next words following [prevWord] (lowercase), sorted by count descending. */
    @Synchronized fun nextWords(prevWord: String?, max: Int): List<Pair<String, Int>> {
        val prev = prevWord?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return emptyList()
        val m = bigrams[prev] ?: return emptyList()
        return m.entries.sortedByDescending { it.value }.take(max).map { it.key to it.value }
    }

    @Synchronized fun save() {
        if (!dirty) return
        runCatching {
            val tmp = File(file.path + ".tmp")
            tmp.bufferedWriter().use { w ->
                counts.forEach { (k, v) -> w.write("$k\t$v\n") }
                bigrams.forEach { (w1, map) ->
                    map.forEach { (w2, v) -> w.write("$w1 $w2\t$v\n") }
                }
            }
            java.nio.file.Files.move(
                tmp.toPath(),
                file.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
            dirty = false
        }
    }

    @Synchronized fun clear() {
        counts.clear()
        bigrams.clear()
        bigramTotal = 0
        dirty = true
        save()
    }
}
