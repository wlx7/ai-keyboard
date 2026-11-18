package app.aikb.ime.engine

import android.content.Context
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Read-only view over assets/dict/en.dict (built by tools/BuildEnglishDict.java).
 * The asset is stored uncompressed so it can be memory-mapped: no heap copy, near-zero load time.
 */
class EnglishDictionary private constructor(private val buf: ByteBuffer) {
    val size: Int
    private val offsetsStart = 12
    private val dataStart: Int

    init {
        buf.order(ByteOrder.BIG_ENDIAN)
        val magic = bytes(0, 8)
        require(String(magic, Charsets.US_ASCII) == "AIKBEN01") { "bad en.dict" }
        size = buf.getInt(8)
        dataStart = offsetsStart + size * 4
    }

    private fun bytes(at: Int, len: Int): ByteArray = ByteArray(len) { buf.get(at + it) }

    private fun entry(i: Int): Int = dataStart + buf.getInt(offsetsStart + i * 4)

    fun freq(i: Int): Int = buf.get(entry(i)).toInt() and 0xFF

    fun key(i: Int): String {
        val p = entry(i) + 1
        val len = buf.get(p).toInt() and 0xFF
        return bytes(p + 1, len).toString(Charsets.UTF_8)
    }

    fun display(i: Int): String {
        val p = entry(i) + 1
        val kLen = buf.get(p).toInt() and 0xFF
        val dp = p + 1 + kLen
        val dLen = buf.get(dp).toInt() and 0xFF
        return bytes(dp + 1, dLen).toString(Charsets.UTF_8)
    }

    /** Compares entry i's key with [k]; when [prefix] is true a key that starts with k counts as equal. */
    private fun compare(i: Int, k: ByteArray, prefix: Boolean): Int {
        val p = entry(i) + 1
        val len = buf.get(p).toInt() and 0xFF
        val n = minOf(len, k.size)
        for (j in 0 until n) {
            val a = buf.get(p + 1 + j).toInt() and 0xFF
            val b = k[j].toInt() and 0xFF
            if (a != b) return a - b
        }
        if (prefix && len >= k.size) return 0
        return len - k.size
    }

    /** First index whose key is >= k (or starts with k when prefix). */
    private fun lowerBound(k: ByteArray, prefix: Boolean): Int {
        var lo = 0
        var hi = size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (compare(mid, k, prefix) < 0) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** Index range [from, to) of entries whose key starts with [prefix]. */
    fun prefixRange(prefix: String): IntRange {
        val k = prefix.toByteArray(Charsets.UTF_8)
        val from = lowerBound(k, true)
        var lo = from
        var hi = size
        while (lo < hi) { // first index past the prefix block
            val mid = (lo + hi) ushr 1
            if (compare(mid, k, true) <= 0) lo = mid + 1 else hi = mid
        }
        return from until lo
    }

    /** Index range of entries whose key equals [key] exactly (several displays may share one key). */
    fun exactRange(key: String): IntRange {
        val k = key.toByteArray(Charsets.UTF_8)
        val from = lowerBound(k, false)
        var to = from
        while (to < size && compare(to, k, false) == 0) to++
        return from until to
    }

    companion object {
        /** For tests and tools: wrap an already-loaded buffer. */
        fun fromBuffer(buffer: ByteBuffer): EnglishDictionary = EnglishDictionary(buffer)

        fun load(context: Context): EnglishDictionary {
            val afd = context.assets.openFd("dict/en.dict")
            FileInputStream(afd.fileDescriptor).channel.use { ch ->
                val mapped = ch.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.length)
                return EnglishDictionary(mapped)
            }
        }
    }
}
