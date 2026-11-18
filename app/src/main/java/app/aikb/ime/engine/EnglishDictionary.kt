package app.aikb.ime.engine

import android.content.Context
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Read-only view over assets/dict/en.dict (built by tools/BuildEnglishDict.java).
 * Supports AIKBEN02 (unigrams + memory-mapped bigram graph) and legacy AIKBEN01.
 * The asset is stored uncompressed so it can be memory-mapped: no heap copy, near-zero load time.
 */
class EnglishDictionary private constructor(private val buf: ByteBuffer) {
    val size: Int
    private val offsetsStart: Int
    private val dataStart: Int
    private val bigramHeadCount: Int
    private val bigramHeadsStart: Int
    private val bigramEdgeStartTable: Int
    private val bigramEdgesDataStart: Int

    init {
        buf.order(ByteOrder.BIG_ENDIAN)
        val magic = String(bytes(0, 8), Charsets.US_ASCII)
        when (magic) {
            "AIKBEN02" -> {
                size = buf.getInt(8)
                bigramHeadCount = buf.getInt(12)
                val biStart = buf.getInt(16)
                offsetsStart = 20
                dataStart = offsetsStart + size * 4
                bigramHeadsStart = biStart
                bigramEdgeStartTable = biStart + bigramHeadCount * 4
                bigramEdgesDataStart = bigramEdgeStartTable + (bigramHeadCount + 1) * 4
            }
            "AIKBEN01" -> {
                size = buf.getInt(8)
                offsetsStart = 12
                dataStart = offsetsStart + size * 4
                bigramHeadCount = 0
                bigramHeadsStart = 0
                bigramEdgeStartTable = 0
                bigramEdgesDataStart = 0
            }
            else -> error("bad en.dict magic: $magic")
        }
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
        while (lo < hi) {
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

    /** Canonical unigram index matching [word] (distinguishing e.g. "its" vs "it's"), or -1. */
    fun unigramIndexOf(word: String): Int {
        val clean = word.trim().replace('’', '\'')
        val alpha = clean.lowercase().filter { it in 'a'..'z' }
        if (alpha.isEmpty()) return -1
        val range = exactRange(alpha)
        var fallback = -1
        for (i in range) {
            val d = display(i)
            val dAlpha = d.lowercase().filter { it in 'a'..'z' }
            if (dAlpha != alpha) continue // skip misspelling aliases
            if (d.equals(clean, ignoreCase = true)) return i
            if (fallback < 0) fallback = i
        }
        return fallback
    }

    /**
     * Packed bigram edges `(targetUnigramIdx shl 8) or score` following [prevWord].
     * Sorted by descending conditional score (1..255). Empty when unseen.
     */
    fun bigrams(prevWord: String?): IntArray {
        if (bigramHeadCount == 0 || prevWord.isNullOrBlank()) return EMPTY_INTS
        val headIdx = unigramIndexOf(prevWord)
        if (headIdx < 0) return EMPTY_INTS
        var lo = 0
        var hi = bigramHeadCount - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val v = buf.getInt(bigramHeadsStart + mid * 4)
            when {
                v < headIdx -> lo = mid + 1
                v > headIdx -> hi = mid - 1
                else -> {
                    val e0 = buf.getInt(bigramEdgeStartTable + mid * 4)
                    val e1 = buf.getInt(bigramEdgeStartTable + (mid + 1) * 4)
                    val n = (e1 - e0).coerceAtLeast(0)
                    return IntArray(n) { buf.getInt(bigramEdgesDataStart + (e0 + it) * 4) }
                }
            }
        }
        return EMPTY_INTS
    }

    companion object {
        private val EMPTY_INTS = IntArray(0)

        fun edgeTarget(packed: Int): Int = packed ushr 8
        fun edgeScore(packed: Int): Int = packed and 0xFF

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
