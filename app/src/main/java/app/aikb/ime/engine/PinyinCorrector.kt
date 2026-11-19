package app.aikb.ime.engine

import kotlin.math.hypot

/**
 * Offline QWERTY spatial + structural Pinyin typo corrector (`中文智能纠错`).
 *
 * Detects and repairs common mobile typing mistakes in <0.4 ms:
 *   1. Adjacent-key fat-finger substitutions (`nihso -> nihao`, `shebme -> shenme`, `xianxai -> xianzai`, `xiansai -> xianzai`)
 *   2. Fast-typing letter transpositions (`zhognguo -> zhongguo`, `womne -> women`, `mingtina -> mingtian`)
 *   3. Accidental double-taps (`niihao -> nihao`, `xiexiee -> xiexie`)
 *   4. Dropped finals / medials (`zhng -> zhong/zhang`, `shnme -> shenme`)
 */
object PinyinCorrector {

    data class Fix(val correctedPinyin: String, val penalty: Float, val brokenOriginal: Boolean)

    private val QWERTY_ROWS = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
    private val KEY_POS: Map<Char, Pair<Float, Float>> = buildMap {
        val offsets = floatArrayOf(0f, 0.45f, 0.95f)
        QWERTY_ROWS.forEachIndexed { r, row ->
            row.forEachIndexed { c, ch -> put(ch, (c + offsets[r]) to r.toFloat()) }
        }
    }

    private val NEIGHBORS: Map<Char, List<Pair<Char, Float>>> = buildMap {
        for ((ch, p1) in KEY_POS) {
            val list = ArrayList<Pair<Char, Float>>()
            for ((other, p2) in KEY_POS) {
                if (other == ch) continue
                val d = hypot(p1.first - p2.first, p1.second - p2.second)
                if (d <= 1.52f) list += other to d
            }
            list.sortBy { it.second }
            put(ch, list)
        }
    }

    private val VALID_SYLLABLES: Set<String> = setOf(
        "a", "ai", "an", "ang", "ao",
        "ba", "bai", "ban", "bang", "bao", "bei", "ben", "beng", "bi", "bian", "biao", "bie", "bin", "bing", "bo", "bu",
        "ca", "cai", "can", "cang", "cao", "ce", "cen", "ceng", "cha", "chai", "chan", "chang", "chao", "che", "chen",
        "cheng", "chi", "chong", "chou", "chu", "chua", "chuai", "chuan", "chuang", "chui", "chun", "chuo", "ci", "cong",
        "cou", "cu", "cuan", "cui", "cun", "cuo",
        "da", "dai", "dan", "dang", "dao", "de", "dei", "den", "deng", "di", "dia", "dian", "diao", "die", "ding", "diu",
        "dong", "dou", "du", "duan", "dui", "dun", "duo",
        "e", "ei", "en", "eng", "er",
        "fa", "fan", "fang", "fei", "fen", "feng", "fiao", "fo", "fou", "fu",
        "ga", "gai", "gan", "gang", "gao", "ge", "gei", "gen", "geng", "gong", "gou", "gu", "gua", "guai", "guan",
        "guang", "gui", "gun", "guo",
        "ha", "hai", "han", "hang", "hao", "he", "hei", "hen", "heng", "hong", "hou", "hu", "hua", "huai", "huan",
        "huang", "hui", "hun", "huo",
        "ji", "jia", "jian", "jiang", "jiao", "jie", "jin", "jing", "jiong", "jiu", "ju", "juan", "jue", "jun", "jv", "jve",
        "ka", "kai", "kan", "kang", "kao", "ke", "kei", "ken", "keng", "kong", "kou", "ku", "kua", "kuai", "kuan",
        "kuang", "kui", "kun", "kuo",
        "la", "lai", "lan", "lang", "lao", "le", "lei", "leng", "li", "lia", "lian", "liang", "liao", "lie", "lin",
        "ling", "liu", "lo", "long", "lou", "lu", "luan", "lue", "lun", "luo", "lv", "lve",
        "ma", "mai", "man", "mang", "mao", "me", "mei", "men", "meng", "mi", "mian", "miao", "mie", "min", "ming",
        "miu", "mo", "mou", "mu",
        "na", "nai", "nan", "nang", "nao", "ne", "nei", "nen", "neng", "ni", "nian", "niang", "niao", "nie", "nin",
        "ning", "niu", "nong", "nou", "nu", "nuan", "nue", "nun", "nuo", "nv", "nve",
        "o", "ou",
        "pa", "pai", "pan", "pang", "pao", "pei", "pen", "peng", "pi", "pian", "piao", "pie", "pin", "ping", "po", "pou", "pu",
        "qi", "qia", "qian", "qiang", "qiao", "qie", "qin", "qing", "qiong", "qiu", "qu", "quan", "que", "qun", "qv", "qve",
        "ran", "rang", "rao", "re", "ren", "reng", "ri", "rong", "rou", "ru", "rua", "ruan", "rui", "run", "ruo",
        "sa", "sai", "san", "sang", "sao", "se", "sen", "seng", "sha", "shai", "shan", "shang", "shao", "she", "shei",
        "shen", "sheng", "shi", "shou", "shu", "shua", "shuai", "shuan", "shuang", "shui", "shun", "shuo", "si", "song",
        "sou", "su", "suan", "sui", "sun", "suo",
        "ta", "tai", "tan", "tang", "tao", "te", "teng", "ti", "tian", "tiao", "tie", "ting", "tong", "tou", "tu",
        "tuan", "tui", "tun", "tuo",
        "wa", "wai", "wan", "wang", "wei", "wen", "weng", "wo", "wu",
        "xi", "xia", "xian", "xiang", "xiao", "xie", "xin", "xing", "xiong", "xiu", "xu", "xuan", "xue", "xun", "xv", "xve",
        "ya", "yan", "yang", "yao", "ye", "yi", "yin", "ying", "yo", "yong", "you", "yu", "yuan", "yue", "yun", "yv", "yve",
        "za", "zai", "zan", "zang", "zao", "ze", "zei", "zen", "zeng", "zha", "zhai", "zhan", "zhang", "zhao", "zhe",
        "zhei", "zhen", "zheng", "zhi", "zhong", "zhou", "zhu", "zhua", "zhuai", "zhuan", "zhuang", "zhui", "zhun",
        "zhuo", "zi", "zong", "zou", "zu", "zuan", "zui", "zun", "zuo",
    )

    /**
     * Returns the minimum number of complete Mandarin syllables covering [s], or -1 if [s] cannot
     * be segmented into valid complete syllables without leftover consonants/fragments.
     */
    fun minCompleteSyllables(s: String): Int {
        val n = s.length
        if (n == 0 || n > 32) return -1
        for (c in s) if (c !in 'a'..'z') return -1
        val dp = IntArray(n + 1) { 999 }
        dp[0] = 0
        for (i in 0 until n) {
            val base = dp[i]
            if (base >= 999) continue
            val maxLen = minOf(6, n - i)
            for (len in 1..maxLen) {
                val sub = s.substring(i, i + len)
                if (sub in VALID_SYLLABLES && base + 1 < dp[i + len]) {
                    dp[i + len] = base + 1
                }
            }
        }
        return if (dp[n] < 999) dp[n] else -1
    }

    /**
     * Generates ranked Pinyin typo repairs for [rawInput] (Full Pinyin `rime_ice`).
     */
    fun suggestPinyinFixes(rawInput: String, maxFixes: Int = 6): List<Fix> {
        val clean = rawInput.filter { it in 'a'..'z' }
        if (clean.length !in 3..22 || clean != rawInput) return emptyList()

        val origSyl = minCompleteSyllables(clean)
        val isBroken = origSyl < 0
        val best = HashMap<String, Float>()

        fun offer(cand: String, editCost: Float) {
            if (cand == clean) return
            val syl = minCompleteSyllables(cand)
            if (syl <= 0) return
            // Avoid turning a 4-letter typo into 4 single-vowel syllables ("aaaa")
            if (cand.length >= 4 && syl >= cand.length) return
            if (!isBroken && syl > origSyl) return
            val totalPenalty = editCost + syl * 0.35f
            val prev = best[cand]
            if (prev == null || totalPenalty < prev) best[cand] = totalPenalty
        }

        // 1. Adjacent-key substitution (e.g. nihso -> nihao, shebme -> shenme, xiansai -> xianzai)
        val maxDist = if (isBroken) 1.50f else 1.22f
        val chars = clean.toCharArray()
        for (i in chars.indices) {
            val orig = chars[i]
            val isConsonant = orig !in "aeiou"
            val nbrs = NEIGHBORS[orig] ?: continue
            for ((rep, dist) in nbrs) {
                if (dist > maxDist) break
                chars[i] = rep
                val cost = dist * (if (isConsonant && rep !in "aeiou") 0.92f else 1.12f)
                offer(String(chars), cost)
            }
            chars[i] = orig
        }

        // 2. Adjacent letter transposition (e.g. zhognguo -> zhongguo, womne -> women, mingtina -> mingtian)
        for (i in 0 until chars.size - 1) {
            if (chars[i] == chars[i + 1]) continue
            val a = chars[i]; val b = chars[i + 1]
            chars[i] = b; chars[i + 1] = a
            offer(String(chars), 0.95f)
            chars[i] = a; chars[i + 1] = b
        }

        if (isBroken) {
            // 3. Accidental duplicate letter tap (e.g. niihao -> nihao, xiexiee -> xiexie)
            for (i in 0 until clean.length - 1) {
                if (clean[i] == clean[i + 1]) {
                    offer(clean.removeRange(i, i + 1), 0.85f)
                }
            }
            // 4. Dropped medial/final vowel or nasal ('a','e','i','o','u','n','g','h')
            val commonDrops = charArrayOf('a', 'e', 'i', 'o', 'u', 'n', 'g', 'h')
            if (clean.length <= 12) {
                for (pos in 1..clean.length) {
                    for (ins in commonDrops) {
                        offer(clean.substring(0, pos) + ins + clean.substring(pos), 1.45f)
                    }
                }
            }
        }

        return best.entries
            .sortedBy { it.value }
            .take(maxFixes)
            .map { Fix(it.key, it.value, isBroken) }
    }

    /**
     * Queries Rime's hidden session to convert typo-repaired Pinyin into real Chinese phrase candidates.
     * Safe to call on UI or worker thread when Rime is ready (<0.5 ms per candidate).
     */
    fun resolveCandidates(schema: String, rawInput: String, existingCandidates: List<String>): List<String> {
        if (schema != "rime_ice") return emptyList()
        val fixes = suggestPinyinFixes(rawInput, maxFixes = 5)
        if (fixes.isEmpty()) return emptyList()

        val existingSet = existingCandidates.toHashSet()
        val results = ArrayList<String>(3)
        for (fix in fixes) {
            val expSyl = minCompleteSyllables(fix.correctedPinyin)
            val top = RimeEngine.queryTop(schema, fix.correctedPinyin, maxCands = 3)
            for (cand in top) {
                if (cand.isEmpty() || cand in existingSet || cand in results) continue
                // If original input was already valid syllables (e.g. xiansai), only accept whole-word lexical hits!
                val cpLen = cand.codePointCount(0, cand.length)
                if (!fix.brokenOriginal && cpLen < expSyl) continue
                if (cpLen >= 2) {
                    results += cand
                    break
                }
            }
            if (results.size >= 2) break
        }
        return results
    }
}
