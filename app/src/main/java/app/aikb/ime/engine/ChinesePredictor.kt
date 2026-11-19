package app.aikb.ime.engine

import android.content.Context
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Instant (<0.05 ms) offline multi-word Chinese next-word, couplet & idiom predictor (`中文联想`),
 * backed by `assets/dict/zh_predict.bin` (`AIKBZH01`, 90,000 heads up to 7 chars) plus personal
 * user Chinese N-gram history (`zh_user.tsv`).
 */
class ChinesePredictor(
    rawBytes: ByteArray,
    private val userFile: File? = null,
) {
    private val count: Int
    private val offsetsOffset: Int
    private val poolOffset: Int
    private val data: ByteArray = rawBytes
    private val buf: ByteBuffer = ByteBuffer.wrap(rawBytes).order(ByteOrder.BIG_ENDIAN)

    private val userBigrams = LinkedHashMap<String, MutableMap<String, Int>>()
    private var dirty = false

    init {
        require(rawBytes.size >= 12) { "zh_predict.bin too small" }
        val magic = String(rawBytes, 0, 8, Charsets.US_ASCII)
        require(magic == "AIKBZH01") { "Invalid magic: $magic" }
        count = buf.getInt(8)
        offsetsOffset = 12
        poolOffset = 12 + count * 4
        loadUserHistory()
    }

    private fun loadUserHistory() {
        val f = userFile ?: return
        if (!f.exists()) return
        runCatching {
            f.forEachLine(Charsets.UTF_8) { line ->
                val parts = line.split('\t')
                if (parts.size == 3) {
                    val head = parts[0]
                    val next = parts[1]
                    val cnt = parts[2].toIntOrNull() ?: return@forEachLine
                    if (head.isNotEmpty() && next.isNotEmpty() && cnt > 0) {
                        userBigrams.getOrPut(head) { LinkedHashMap() }[next] = cnt
                    }
                }
            }
        }
    }

    @Synchronized
    fun learn(prevWord: String?, committedWord: String) {
        val prev = prevWord?.trim().orEmpty()
        val curr = committedWord.trim()
        if (prev.isEmpty() || curr.isEmpty() || prev == curr) return
        if (prev.length > 7 || curr.length > 7) return
        if (!prev.all(::isCjk) || !curr.all(::isCjk)) return
        val m = userBigrams.getOrPut(prev) { LinkedHashMap() }
        m[curr] = (m[curr] ?: 0) + 1
        if (userBigrams.size > 4000) {
            val eldest = userBigrams.keys.firstOrNull()
            if (eldest != null) userBigrams.remove(eldest)
        }
        dirty = true
    }

    @Synchronized
    fun forgetWord(word: String): Boolean {
        val target = word.trim()
        if (target.isEmpty()) return false
        var removed = false
        val it = userBigrams.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            if (entry.value.remove(target) != null) {
                removed = true
                if (entry.value.isEmpty()) it.remove()
            }
        }
        if (removed) {
            dirty = true
            save()
        }
        return removed
    }

    @Synchronized
    fun save() {
        if (!dirty) return
        val f = userFile ?: return
        dirty = false
        runCatching {
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.bufferedWriter(Charsets.UTF_8).use { w ->
                for ((head, nextMap) in userBigrams) {
                    for ((next, cnt) in nextMap) {
                        w.write("$head\t$next\t$cnt\n")
                    }
                }
            }
            Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    @Synchronized
    fun clearUserHistory() {
        userBigrams.clear()
        dirty = false
        userFile?.delete()
    }

    @Synchronized
    fun allLearnedPairs(): List<Triple<String, String, Int>> {
        val out = ArrayList<Triple<String, String, Int>>()
        for ((head, map) in userBigrams) {
            for ((next, cnt) in map) out += Triple(head, next, cnt)
        }
        return out
    }

    private fun extractTailAndComma(textBeforeCursor: String): Pair<String, Boolean> {
        var end = textBeforeCursor.length
        while (end > 0 && textBeforeCursor[end - 1] == ' ') end--
        var hadComma = false
        if (end >= 2 && textBeforeCursor[end - 1] in "，,、" && isCjk(textBeforeCursor[end - 2])) {
            hadComma = true
            end--
        }
        var start = end
        while (start > 0 && isCjk(textBeforeCursor[start - 1]) && end - start < 10) start--
        return textBeforeCursor.substring(start, end) to hadComma
    }

    /**
     * Returns true when [textBeforeCursor] matches an exact 4..7-char couplet/idiom/multi-word head
     * (such as `清风徐来 -> 水波不兴`), allowing callers to preserve the couplet at Rank #1.
     */
    @Synchronized
    fun hasLongExactMatch(textBeforeCursor: String, minHeadLen: Int = 4): Boolean {
        if (textBeforeCursor.isEmpty()) return false
        val (tail, _) = extractTailAndComma(textBeforeCursor)
        if (tail.length < minHeadLen) return false
        for (len in minOf(7, tail.length) downTo minHeadLen) {
            val key = tail.substring(tail.length - len)
            if (!userBigrams[key].isNullOrEmpty() || lookupExact(key).isNotEmpty()) return true
        }
        return false
    }

    /**
     * Predicts up to [max] natural Chinese continuations following [textBeforeCursor].
     *
     * Strictly prioritizes longer multi-word/idiom contexts (`7 -> 6 -> 5 -> 4 -> 3 -> 2`),
     * merging user history and dictionary at each length tier, and NEVER falls back to
     * single-character suffixes (`len == 1`) when [tail] already has >= 2 CJK characters.
     */
    @Synchronized
    fun predict(textBeforeCursor: String, max: Int = 8): List<String> {
        if (textBeforeCursor.isEmpty() || max <= 0) return emptyList()
        val (tail, hadComma) = extractTailAndComma(textBeforeCursor)
        if (tail.isEmpty()) return emptyList()

        val minLen = when {
            hadComma -> 4
            tail.length >= 2 -> 2
            else -> 1
        }
        if (tail.length < minLen) return emptyList()

        val out = LinkedHashSet<String>(max * 2)
        for (len in minOf(7, tail.length) downTo minLen) {
            if (out.size >= max) break
            val key = tail.substring(tail.length - len)

            // 1. Personal learned N-grams at THIS exact length tier
            //    (1-char triggers require count >= 2 so a single accidental adjacency never pollutes)
            val learned = userBigrams[key]
            if (!learned.isNullOrEmpty()) {
                val minCnt = if (len == 1) 2 else 1
                learned.entries
                    .filter { it.value >= minCnt }
                    .sortedByDescending { it.value }
                    .forEach { (w, _) ->
                        if (out.size < max && w != key) out.add(w)
                    }
            }

            // 2. Built-in 90,000-head multi-word & classical couplet dictionary at THIS length tier
            if (out.size < max) {
                val hits = lookupExact(key)
                for (h in hits) {
                    if (out.size >= max) break
                    if (h != key) out.add(h)
                }
            }
        }

        return out.toList()
    }

    private fun lookupExact(head: String): List<String> {
        val target = head.toByteArray(Charsets.UTF_8)
        var lo = 0
        var hi = count - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val entryPos = poolOffset + buf.getInt(offsetsOffset + mid * 4)
            val headLen = data[entryPos].toInt() and 0xFF
            val cmp = compareSlice(entryPos + 1, headLen, target)
            when {
                cmp < 0 -> lo = mid + 1
                cmp > 0 -> hi = mid - 1
                else -> {
                    var p = entryPos + 1 + headLen
                    val nCont = data[p++].toInt() and 0xFF
                    val res = ArrayList<String>(nCont)
                    repeat(nCont) {
                        val clen = data[p].toInt() and 0xFF
                        p++
                        res.add(String(data, p, clen, Charsets.UTF_8))
                        p += clen
                    }
                    return res
                }
            }
        }
        return emptyList()
    }

    private fun compareSlice(offset: Int, len: Int, target: ByteArray): Int {
        val lim = minOf(len, target.size)
        for (k in 0 until lim) {
            val a = data[offset + k].toInt() and 0xFF
            val b = target[k].toInt() and 0xFF
            if (a != b) return a - b
        }
        return len - target.size
    }

    companion object {
        fun isCjk(c: Char): Boolean = c in '\u4e00'..'\u9fff'

        fun load(context: Context, userFile: File? = null): ChinesePredictor {
            val bytes = context.assets.open("dict/zh_predict.bin").use(InputStream::readBytes)
            return ChinesePredictor(bytes, userFile)
        }
    }
}
