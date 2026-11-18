package app.aikb.ime.engine

/** Helpers for 九宫格 pinyin: digit mapping and syllable disambiguation. */
object T9 {
    /** All pinyin syllables, most frequent first (from rime-ice 8105.dict.yaml; generated). */
    private val SYLLABLES = "de shi yi ji wo ta you zai zhi ren li zhe ge zi qi xiang di wei da dao lai jiu zhong shang qing shen jian ye xiao ma huo guo na hui shuo mei jing xi he er wu sheng dan yao yu hai fang jin xia en si shou hou qu eng jie hao ba yan xing fu kan jia zuo hua dui ran tian yuan dei bu zhang xie chang cheng xian fa qian bei zhu zheng ming a lian jiao gong bian an liang chu dian yin ru ci wen xin wang dong tou shu bi zhen tong gan duo ying hu wan mian ju hen ke guan yang jiang kai ban xue yong jue bao cai le feng ting ling fen bai lao suo dang dai fei mo nan xu bing gao fan nian gu o la shan zhao mu liu ding shai zui tai cong shui lu ti que chi quan nv san ya zhan zou rang sui ben gei hang du tu deng huan yue shao man guang su ai kou zen han kong luo chen qiang gen chuan zu yun kuai qin gai wai tiao lin lei ni tan ping mi huang gui zao qie se e bie zhou chao duan cha hong zong gang qiu long ri xiu zhuan geng bo ku zhuang sha biao che meng chong pai tao dou xiong she hei xuan song ze po gou pa rong pi nei men diao pian bang suan mao tuo mang te tui kuang hun leng xun wa pao qiao jun mai lan guai shuang lun nao lv min can chan ruo lang shei chuang ceng cao pu cuo chou lou pin pang cun pei ha chun dun nu liao nai zhuo zhun pan piao nong tang peng re luan tie miao tuan lie shun zan heng cang ce zha juan mou niang kao zhui ning qun sun niu ken ao kang reng gua rou sai nin ka huai cui kui shuai fo ou die fou chui lve teng kun rao sao rui cu kuo sang mie zun kuan bin gun zhai nuo zeng ruan sa sou sen niao kua zang qiong chai diu ne ca za nie pen tun beng shua zei zhua nuan ga zuan jiong run ang cou weng keng cuan pie seng nang nen neng yo chuai zhuai chuo nve shuan miu cen pou qia tei me lo cei ei nou kei den rua biang lia dia chua".split(' ')

    private val DIGIT = buildMap {
        "abc def ghi jkl mno pqrs tuv wxyz".split(' ').forEachIndexed { i, letters ->
            letters.forEach { put(it, '2' + i) }
        }
    }

    fun digitsOf(s: String): String = s.map { DIGIT[it] ?: it }.joinToString("")

    /** Splits raw t9 input into the already-spelled head (letters and ') and the digit tail. */
    fun split(input: String): Pair<String, String> {
        val i = input.indexOfFirst { it in '2'..'9' }
        return if (i < 0) input to "" else input.substring(0, i) to input.substring(i)
    }

    /**
     * Syllables (and bare initials) that the leading digits of [digits] could spell, longest
     * first, then by frequency — the list shown in the 九宫格 side column.
     */
    fun options(digits: String, max: Int = 40): List<String> {
        if (digits.isEmpty()) return emptyList()
        val out = SYLLABLES.filter { s -> s.length <= digits.length && digitsOf(s) == digits.substring(0, s.length) }
            .sortedByDescending { it.length } // stable: keeps frequency order within a length
            .toMutableList()
        val first = digits[0]
        for ((c, d) in DIGIT) if (d == first && c.toString() !in out) out += c.toString()
        return out.take(max)
    }

    /** Input after the user picks [syllable] for the start of the digit tail. */
    fun applyPick(input: String, syllable: String): String {
        val (head, tail) = split(input)
        val rest = tail.drop(syllable.length)
        val sep = if (head.isNotEmpty() && !head.endsWith("'")) "'" else ""
        return head + sep + syllable + (if (rest.isNotEmpty()) "'" else "") + rest
    }
}
