package app.aikb.ime.engine

import android.content.Context
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Spatial IDS component-composition lookup (`assets/dict/hw_ids.bin`, `AIKBID01`, ~85k keys).
 *
 * Resolves 2-component (`H:a,b` left-right, `V:a,b` top-bottom, `E:a,b` enclosure) and
 * 3-component (`P:a,b,c` triangle / 3-column / 3-row / nested) spatial configurations into
 * rare CJK characters across the full 41,448-character Unicode CJK + Extensions A–G repertoire
 * (e.g. `石+石 -> 砳`, `土+土+土 -> 垚`, `牛+牛+牛 -> 犇`, `龙+龙+龙 -> 龘`, `明+空 -> 曌`,
 * `开+火 -> 烎`, `不+要 -> 嫑`, `龙+天 -> 䶮`, `钅+乐 -> 𨰻`, `齿+献 -> 齾`).
 */
class IdsComposer(
    rawBytes: ByteArray,
    private val t2sMap: Map<String, String> = emptyMap(),
) {
    private val count: Int
    private val offsetsOffset: Int
    private val poolOffset: Int
    private val data: ByteArray = rawBytes
    private val buf: ByteBuffer = ByteBuffer.wrap(rawBytes).order(ByteOrder.BIG_ENDIAN)

    init {
        require(rawBytes.size >= 12) { "hw_ids.bin too small" }
        val magic = String(rawBytes, 0, 8, Charsets.US_ASCII)
        require(magic == "AIKBID01") { "Invalid hw_ids magic: $magic" }
        count = buf.getInt(8)
        offsetsOffset = 12
        poolOffset = 12 + count * 4
    }

    fun lookup2(rel: Char, compA: String, compB: String): List<String> {
        val ca = canon(compA)
        val cb = canon(compB)
        return lookupRaw("$rel:$ca,$cb")
    }

    fun lookup3(compA: String, compB: String, compC: String): List<String> {
        val ca = canon(compA)
        val cb = canon(compB)
        val cc = canon(compC)
        return lookupRaw("P:$ca,$cb,$cc")
    }

    private fun canon(c: String): String {
        val s = t2sMap[c] ?: c
        return CANON_MAP[s] ?: CANON_MAP[c] ?: s
    }

    private fun lookupRaw(key: String): List<String> {
        val target = key.toByteArray(Charsets.UTF_8)
        var lo = 0
        var hi = count - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val pos = poolOffset + buf.getInt(offsetsOffset + mid * 4)
            val klen = data[pos].toInt() and 0xFF
            val cmp = compareSlice(pos + 1, klen, target)
            when {
                cmp < 0 -> lo = mid + 1
                cmp > 0 -> hi = mid - 1
                else -> {
                    var p = pos + 1 + klen
                    val n = data[p++].toInt() and 0xFF
                    val out = ArrayList<String>(n)
                    repeat(n) {
                        val clen = data[p].toInt() and 0xFF
                        p++
                        out.add(String(data, p, clen, Charsets.UTF_8))
                        p += clen
                    }
                    return out
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
        private val CANON_MAP = mapOf(
            "金" to "钅", "釒" to "钅", "钅" to "钅",
            "水" to "氵", "氵" to "氵",
            "手" to "扌", "扌" to "扌",
            "心" to "忄", "忄" to "忄",
            "言" to "讠", "訁" to "讠", "讠" to "讠",
            "糸" to "纟", "糹" to "纟", "丝" to "纟", "纟" to "纟",
            "食" to "饣", "飠" to "饣", "饣" to "饣",
            "示" to "礻", "礻" to "礻",
            "衣" to "衤", "衤" to "衤",
            "犬" to "犭", "犭" to "犭",
            "人" to "亻", "亻" to "亻",
            "刀" to "刂", "刂" to "刂",
            "足" to "⻊", "⻊" to "⻊",
            "竹" to "⺮", "⺮" to "⺮",
            "火" to "火", "灬" to "火",
            "草" to "艹", "艹" to "艹", "廾" to "艹",
            "辵" to "辶", "辶" to "辶", "走" to "辶",
            "阜" to "阝", "邑" to "阝",
            "囗" to "口", "口" to "口",
            "牛" to "牛", "牜" to "牛",
            "玉" to "王", "王" to "王",
            "肉" to "月", "⺼" to "月", "月" to "月",
            "龍" to "龙", "龙" to "龙",
            "馬" to "马", "马" to "马",
            "鳥" to "鸟", "鸟" to "鸟",
            "魚" to "鱼", "鱼" to "鱼",
            "車" to "车", "车" to "车",
            "門" to "门", "门" to "门",
            "風" to "风", "风" to "风",
            "齒" to "齿", "齿" to "齿",
            "頁" to "页", "页" to "页",
            "貝" to "贝", "贝" to "贝",
            "見" to "见", "见" to "见",
            "長" to "长", "长" to "长",
            "韋" to "韦", "韦" to "韦",
            "麥" to "麦", "麦" to "麦",
            "齊" to "齐", "齐" to "齐",
            "龜" to "龟", "龟" to "龟",
            "區" to "区", "僉" to "佥", "單" to "单", "喬" to "乔", "堯" to "尧", "壽" to "寿",
            "專" to "专", "廣" to "广", "盧" to "卢", "發" to "发", "肅" to "肃", "與" to "与",
            "萬" to "万", "華" to "华", "豐" to "丰", "獻" to "献",
        )

        fun parseT2S(raw: InputStream): Map<String, String> {
            val m = HashMap<String, String>(4096)
            raw.bufferedReader(Charsets.UTF_8).forEachLine { line ->
                val tab = line.indexOf('\t')
                if (tab > 0) {
                    val src = line.substring(0, tab).trim()
                    val primary = line.substring(tab + 1).trim().split(' ').firstOrNull()
                    if (src.isNotEmpty() && !primary.isNullOrEmpty() && primary != src) {
                        m[src] = primary
                    }
                }
            }
            return m
        }

        fun load(context: Context): IdsComposer? = runCatching {
            val t2s = runCatching {
                context.assets.open("rime/opencc/TSCharacters.txt").use(::parseT2S)
            }.getOrDefault(emptyMap())
            val bytes = context.assets.open("dict/hw_ids.bin").use(InputStream::readBytes)
            IdsComposer(bytes, t2s)
        }.getOrNull()
    }
}
