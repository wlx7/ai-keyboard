package app.aikb.ime.engine

import android.content.Context
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Instant (<0.05 ms) offline Chinese next-word & idiom predictor (`中文联想`),
 * backed by `assets/dict/zh_predict.bin` (`AIKBZH01`, 24,000 heads) plus personal
 * user Chinese bigram history (`zh_user.tsv`).
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
        if (prev.length > 4 || curr.length > 6) return
        if (!prev.all(::isCjk) || !curr.all(::isCjk)) return
        val m = userBigrams.getOrPut(prev) { LinkedHashMap() }
        m[curr] = (m[curr] ?: 0) + 1
        if (userBigrams.size > 2000) {
            val eldest = userBigrams.keys.firstOrNull()
            if (eldest != null) userBigrams.remove(eldest)
        }
        dirty = true
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

    /**
     * Predicts up to [max] natural Chinese continuations following [textBeforeCursor].
     */
    @Synchronized
    fun predict(textBeforeCursor: String, max: Int = 8): List<String> {
        if (textBeforeCursor.isEmpty() || max <= 0) return emptyList()
        // Extract trailing contiguous CJK characters right before cursor
        var i = textBeforeCursor.length
        while (i > 0 && isCjk(textBeforeCursor[i - 1])) i--
        val tail = textBeforeCursor.substring(i)
        if (tail.isEmpty()) return emptyList()

        val out = LinkedHashSet<String>(max * 2)

        // 1. Personal learned bigrams (longest suffix match first: 4 -> 3 -> 2 -> 1)
        for (len in minOf(4, tail.length) downTo 1) {
            val key = tail.substring(tail.length - len)
            val learned = userBigrams[key]
            if (!learned.isNullOrEmpty()) {
                learned.entries.sortedByDescending { it.value }.forEach { (w, _) ->
                    if (out.size < max) out.add(w)
                }
            }
        }

        // 2. Built-in 24,000-head dictionary (prefer specific 4/3/2-char context over 1-char)
        for (len in minOf(4, tail.length) downTo 1) {
            if (out.size >= max) break
            val key = tail.substring(tail.length - len)
            val hits = lookupExact(key)
            for (h in hits) {
                if (out.size >= max) break
                out.add(h)
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
