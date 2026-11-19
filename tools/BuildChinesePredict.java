import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Builds `app/src/main/assets/dict/zh_predict.bin` (`AIKBZH01` compact binary table)
 * for instant (<0.05 ms) offline multi-word Chinese next-word, couplet & idiom prediction (`中文联想`).
 *
 * v3.1 Multi-Word & Classical Couplet Edition:
 *   1. Supports head lengths 1..7 CJK chars and continuation lengths 1..7 CJK chars (UTF-8 <= 21 bytes).
 *   2. Eliminates bound 2-char word splitting (`来自/来源 -> 来+自/源`) so 1-char heads only hold genuine standalone words.
 *   3. Ingests 2-word (`w1 -> w2`) and 3-word (`w1+w2 -> w3`) N-gram collocations from `tools/build_gram/ngrams.tsv`.
 *   4. Ingests symmetrical classical poetry/prose couplets (`frost_literature.dict.yaml` + curated Tang/Song/Guwen couplets & proverbs),
 *      622K composite phrases (`frost_composite.dict.yaml`), and `rime-ice` / `sogou_cell` / `tencent` dictionaries.
 *
 * Usage (explicit UTF-8 source & runtime encoding for Windows JDK 17 compatibility):
 *   javac -encoding UTF-8 tools/BuildChinesePredict.java -d build/jcp && java -Dfile.encoding=UTF-8 -cp build/jcp BuildChinesePredict tools/data app/src/main/assets/dict/zh_predict.bin
 */
public class BuildChinesePredict {

    private static boolean isAllCjk(String s) {
        if (s == null || s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '\u4e00' || c > '\u9fff') return false;
        }
        return true;
    }

    private static void addScore(Map<String, Map<String, Double>> table, String head, String cont, double score) {
        if (head == null || cont == null || head.isEmpty() || cont.isEmpty() || head.equals(cont)) return;
        if (head.length() > 7 || cont.length() > 7) return;
        if (!isAllCjk(head) || !isAllCjk(cont)) return;
        table.computeIfAbsent(head, k -> new HashMap<>()).merge(cont, score, Double::sum);
    }

    private static void seedConversational(Map<String, Map<String, Double>> table) {
        String[][] pairs = {
            {"我", "们,在,想,要,现在,已经,觉得,可以"},
            {"你", "好,们,在,看,说,觉得,现在,去"},
            {"他", "们,说,在,的,已经,现在"},
            {"她", "们,说,在,的,已经,很"},
            {"好", "的,吧,啊,久不见,极了,得很"},
            {"不", "好意思,是,要,知道,可以,错,过,行"},
            {"没", "有,事,问题,关系,办法,空,想到"},
            {"请", "问,稍等,查收,注意,大家,放心,进"},
            {"祝", "你,您,大家,生日快乐,工作顺利,一切顺利"},
            {"在", "哪里,吗,干嘛,一起,家,路上,公司"},
            {"有", "空,没有,点,时间,问题,什么,事"},
            {"去", "哪里,吃饭,看看,上班,休息,找"},
            {"想", "要,问一下,吃,去,知道,看看,你"},
            {"要", "不要,去,不,注意,是"},
            {"看", "一下,看,起来,到,见"},
            {"来", "了,吧,不及,看看,找我,一趟"},
            {"说", "话,实话,不定,得对,明一下"},
            {"很", "好,多,高兴,快,喜欢,棒,不错"},
            {"太", "好了,棒了,多了,晚了,感谢,厉害了"},
            {"真", "的,好,是,不错,棒,厉害"},
            {"先", "去,看,吃饭,休息,这样,等一下"},
            {"再", "见,说,次,看看,试一下,来"},
            {"还", "有,是,没,可以,好,行"},
            {"都", "可以,是,好,在,有,没问题"},
            {"今天", "天气,晚上,下午,早上,吃什么,有空,真是,工作"},
            {"今天天气", "真不错,很好,真好,怎么样,太热了,好冷"},
            {"明天", "见,早上,下午,天气,晚上,开会,再说,去"},
            {"昨天", "晚上,下午,已经,说的"},
            {"现在", "在,可以,开始,去,几点,出发,怎么样"},
            {"刚刚", "到,看到,发了,好,开始"},
            {"晚上", "好,吃什么,见,一起,回去,休息"},
            {"早上", "好,起来,吃,见"},
            {"下午", "好,茶,见,开会,有空"},
            {"周末", "愉快,有空吗,去哪玩,好好休息"},
            {"谢谢", "大家,你,老板,各位,支持,合作"},
            {"非常", "感谢,好,抱歉,喜欢,重要,不错"},
            {"不好", "意思,看,吃,说,玩,受"},
            {"不好意思", "打扰了,久等了,让您久等,我来晚了"},
            {"对不", "起"},
            {"没关", "系"},
            {"没有", "问题,关系,办法,时间,想到,收到"},
            {"可以", "吗,的,试试,看看,先,啊,发我"},
            {"应该", "可以,是,没问题,不会,会"},
            {"需要", "我,什么,帮忙,注意,多久,马上"},
            {"我们", "一起,可以,现在,今天,先,公司,需要"},
            {"我们一起", "去看看,去吃饭,努力,加油"},
            {"你们", "在,可以,什么时候,觉得,去"},
            {"他们", "在,说,已经,公司"},
            {"大家", "好,辛苦了,晚上好,早上好,一起,注意"},
            {"这个", "问题,时候,地方,项目,功能,怎么,真的"},
            {"这个问题", "怎么解决,很关键,要注意,已经解决"},
            {"那个", "时候,地方,人,问题"},
            {"什么", "时候,意思,地方,情况,问题,东西,样"},
            {"怎么", "样,办,回事,了,走,用,弄"},
            {"为什", "么"},
            {"为什么", "不,会,没有,要,这么"},
            {"哪里", "有,可以,人"},
            {"已经", "到了,收到,完成,好了,发了,开始,下班了"},
            {"收到", "了,谢谢,请放心,通知,消息"},
            {"麻烦", "你了,帮我,看一下,大家,问一下"},
            {"请问", "一下,这个,在哪里,怎么,有,什么时候"},
            {"如果", "有,可以,没有,需要,你"},
            {"因为", "这个,时间,所以,工作"},
            {"虽然", "但是,有点,没有"},
            {"不仅", "如此,可以,而且"},
            {"但是", "我,现在,还是,没有,可以"},
            {"所以", "我,我们,现在,需要,就"},
            {"觉得", "怎么样,很好,不错,可以,呢"},
            {"知道", "了,吗,怎么,为什么"},
            {"希望", "大家,你,能,可以,一切顺利"},
            {"准备", "好了,出发,一下,开始,睡觉"},
            {"开始", "了,吧,工作,学习"},
            {"结束", "了,后,时间"},
            {"辛苦", "了,大家,你了"},
            {"生日", "快乐"},
            {"祝你生日", "快乐"},
            {"新年", "快乐,好,新气象"},
            {"节日", "快乐"},
            {"一路", "平安,顺风"},
            {"早点", "休息,睡,回家"},
            {"注意", "安全,身体,休息,事项"},
            {"工作", "顺利,愉快,人员,时间,室"},
            {"身体", "健康"},
            {"万事", "如意"},
            {"心想", "事成"},
            {"一帆", "风顺"},
            {"人工智能", "技术,大模型,发展,时代,应用"},
            {"输入", "法,框,内容,密码"},
            {"输入法", "词库,皮肤,设置,体验"},
            {"浏览", "器,记录,网页"},
            {"手机", "号码,屏幕,充电,没电了,上"},
            {"电脑", "上,屏幕,系统,重启"},
            {"会议", "室,纪要,时间,改到"},
            {"发给", "我,你,大家"},
            {"等一", "下,会儿"},
            {"看一", "下,看,眼"},
            {"试一", "下,试"},
            {"问一", "下"},
            {"想一", "下,想"},
            {"休息", "一下,吧,时间"},
            {"大模", "型"},
            {"大模型", "蒸馏,推理,训练,部署,应用"},
            {"智能", "体,纠错,输入法,选句,家居"},
        };
        for (String[] row : pairs) {
            String head = row[0];
            String[] conts = row[1].split(",");
            for (int i = 0; i < conts.length; i++) {
                addScore(table, head, conts[i].trim(), 80_000_000.0 - i * 3_000_000.0);
            }
        }
    }

    /** Famous classical prose, Tang/Song poetry, & 8-char proverb couplets. */
    private static void seedClassicalCouplets(Map<String, Map<String, Double>> table) {
        String[][] couplets = {
            // 赤壁赋 / 滕王阁序 / 岳阳楼记 / 醉翁亭记 / 古文名篇
            {"清风徐来", "水波不兴"},
            {"山高月小", "水落石出"},
            {"月明星稀", "乌鹊南飞"},
            {"寄蜉蝣于天地", "渺沧海之一粟"},
            {"哀吾生之须臾", "羡长江之无穷"},
            {"挟飞仙以遨游", "抱明月而长终"},
            {"取之无禁", "用之不竭"},
            {"杯盘狼籍", "相与枕藉乎舟中"},
            {"秋水", "时至,共长天,伊人"},
            {"秋水时至", "百川灌河"},
            {"百川", "灌河,归海,东到海"},
            {"落霞与孤鹜齐飞", "秋水共长天一色"},
            {"落霞与孤鹜", "齐飞"},
            {"秋水共长天", "一色"},
            {"老当益壮", "宁移白首之心"},
            {"穷且益坚", "不坠青云之志"},
            {"北海虽赊", "扶摇可接"},
            {"东隅已逝", "桑榆非晚"},
            {"先天下之忧而忧", "后天下之乐而乐"},
            {"不以物喜", "不以己悲"},
            {"居庙堂之高", "处江湖之远"},
            {"醉翁之意不在酒", "在乎山水之间也"},
            {"水陆草木之花", "可爱者甚蕃"},
            {"出淤泥而不染", "濯清涟而不妖"},
            {"可远观而不可亵玩", "焉"},
            {"天时不如地利", "地利不如人和"},
            {"得道者多助", "失道者寡助"},
            {"生于忧患", "死于安乐"},
            {"鱼我所欲也", "熊掌亦我所欲也"},
            {"学而不思则罔", "思而不学则殆"},
            {"知之者不如好之者", "好之者不如乐之者"},
            {"三人行", "必有我师焉"},
            {"温故而知新", "可以为师矣"},
            {"己所不欲", "勿施于人"},
            {"任重而道远", "死而后已"},
            {"路漫漫其修远兮", "吾将上下而求索"},
            {"云销雨霁", "彩彻区明"},
            {"渔舟唱晚", "响穷彭蠡之滨"},
            {"雁阵惊寒", "声断衡阳之浦"},

            // 唐诗宋词核心千古名句
            {"床前明月光", "疑是地上霜"},
            {"举头望明月", "低头思故乡"},
            {"春眠不觉晓", "处处闻啼鸟"},
            {"夜来风雨声", "花落知多少"},
            {"白日依山尽", "黄河入海流"},
            {"欲穷千里目", "更上一层楼"},
            {"远上寒山石径斜", "白云生处有人家"},
            {"停车坐爱枫林晚", "霜叶红于二月花"},
            {"海内存知己", "天涯若比邻"},
            {"无为在歧路", "儿女共沾巾"},
            {"但愿人长久", "千里共婵娟"},
            {"明月几时有", "把酒问青天"},
            {"人有悲欢离合", "月有阴晴圆缺"},
            {"山重水复疑无路", "柳暗花明又一村"},
            {"小荷才露尖尖角", "早有蜻蜓立上头"},
            {"接天莲叶无穷碧", "映日荷花别样红"},
            {"两个黄鹂鸣翠柳", "一行白鹭上青天"},
            {"窗含西岭千秋雪", "门泊东吴万里船"},
            {"好雨知时节", "当春乃发生"},
            {"随风潜入夜", "润物细无声"},
            {"离离原上草", "一岁一枯荣"},
            {"野火烧不尽", "春风吹又生"},
            {"不识庐山真面目", "只缘身在此山中"},
            {"横看成岭侧成峰", "远近高低各不同"},
            {"问渠那得清如许", "为有源头活水来"},
            {"清明时节雨纷纷", "路上行人欲断魂"},
            {"借问酒家何处有", "牧童遥指杏花村"},
            {"独在异乡为异客", "每逢佳节倍思亲"},
            {"遥知兄弟登高处", "遍插茱萸少一人"},
            {"飞流直下三千尺", "疑是银河落九天"},
            {"日照香炉生紫烟", "遥看瀑布挂前川"},
            {"两岸猿声啼不住", "轻舟已过万重山"},
            {"朝辞白帝彩云间", "千里江陵一日还"},
            {"孤帆远影碧空尽", "唯见长江天际流"},
            {"故人西辞黄鹤楼", "烟花三月下扬州"},
            {"春蚕到死丝方尽", "蜡炬成灰泪始干"},
            {"相见时难别亦难", "东风无力百花残"},
            {"身无彩凤双飞翼", "心有灵犀一点通"},
            {"曾经沧海难为水", "除却巫山不是云"},
            {"同是天涯沦落人", "相逢何必曾相识"},
            {"人生若只如初见", "何事秋风悲画扇"},
            {"会当凌绝顶", "一览众山小"},
            {"感时花溅泪", "恨别鸟惊心"},
            {"烽火连三月", "家书抵万金"},
            {"国破山河在", "城春草木深"},
            {"无边落木萧萧下", "不尽长江滚滚来"},
            {"大漠孤烟直", "长河落日圆"},
            {"长风破浪会有时", "直挂云帆济沧海"},
            {"天生我材必有用", "千金散尽还复来"},
            {"抽刀断水水更流", "举杯消愁愁更愁"},
            {"桃花潭水深千尺", "不及汪伦送我情"},
            {"两岸青山相对出", "孤帆一片日边来"},
            {"天门中断楚江开", "碧水东流至此回"},
            {"醉卧沙场君莫笑", "古来征战几人回"},
            {"葡萄美酒夜光杯", "欲饮琵琶马上催"},
            {"秦时明月汉时关", "万里长征人未还"},
            {"但使龙城飞将在", "不教胡马度阴山"},
            {"春风又绿江南岸", "明月何时照我还"},
            {"爆竹声中一岁除", "春风送暖入屠苏"},
            {"千山鸟飞绝", "万径人踪灭"},
            {"孤舟蓑笠翁", "独钓寒江雪"},
            {"锄禾日当午", "汗滴禾下土"},
            {"谁知盘中餐", "粒粒皆辛苦"},
            {"慈母手中线", "游子身上衣"},
            {"谁言寸草心", "报得三春晖"},
            {"红豆生南国", "春来发几枝"},
            {"愿君多采撷", "此物最相思"},
            {"空山新雨后", "天气晚来秋"},
            {"明月松间照", "清泉石上流"},
            {"独坐幽篁里", "弹琴复长啸"},
            {"深林人不知", "明月来相照"},
            {"劝君更尽一杯酒", "西出阳关无故人"},
            {"渭城朝雨浥轻尘", "客舍青青柳色新"},
            {"不知细叶谁裁出", "二月春风似剪刀"},
            {"碧玉妆成一树高", "万条垂下绿丝绦"},
            {"春色满园关不住", "一枝红杏出墙来"},
            {"等闲识得东风面", "万紫千红总是春"},
            {"乱花渐欲迷人眼", "浅草才能没马蹄"},
            {"几处早莺争暖树", "谁家新燕啄春泥"},
            {"沉舟侧畔千帆过", "病树前头万木春"},
            {"山雨欲来风满楼", "溪云初起日沉阁"},
            {"夕阳无限好", "只是近黄昏"},
            {"商女不知亡国恨", "隔江犹唱后庭花"},
            {"旧时王谢堂前燕", "飞入寻常百姓家"},
            {"落红不是无情物", "化作春泥更护花"},
            {"我自横刀向天笑", "去留肝胆两昆仑"},
            {"苟利国家生死以", "岂因祸福避趋之"},
            {"人生自古谁无死", "留取丹心照汗青"},
            {"竹外桃花三两枝", "春江水暖鸭先知"},
            {"欲把西湖比西子", "淡妆浓抹总相宜"},
            {"水光潋滟晴方好", "山色空蒙雨亦奇"},
            {"一骑红尘妃子笑", "无人知是荔枝来"},
            {"回眸一笑百媚生", "六宫粉黛无颜色"},
            {"在天愿作比翼鸟", "在地愿为连理枝"},
            {"天长地久有时尽", "此恨绵绵无绝期"},
            {"青青子衿", "悠悠我心"},
            {"执子之手", "与子偕老"},
            {"关关雎鸠", "在河之洲"},
            {"窈窕淑女", "君子好逑"},
            {"蒹葭苍苍", "白露为霜"},
            {"所谓伊人", "在水一方"},
            {"桃之夭夭", "灼灼其华"},
            {"昔我往矣", "杨柳依依"},
            {"今我来思", "雨雪霏霏"},

            // 八字成语 / 谚语对句
            {"千里之行", "始于足下"},
            {"百尺竿头", "更进一步"},
            {"尺有所短", "寸有所长"},
            {"智者千虑", "必有一失"},
            {"愚者千虑", "必有一得"},
            {"塞翁失马", "焉知非福"},
            {"一言既出", "驷马难追"},
            {"八仙过海", "各显神通"},
            {"仁者见仁", "智者见智"},
            {"前人栽树", "后人乘凉"},
            {"螳螂捕蝉", "黄雀在后"},
            {"失之毫厘", "谬以千里"},
            {"不入虎穴", "焉得虎子"},
            {"成事不足", "败事有余"},
            {"宁为玉碎", "不为瓦全"},
            {"当局者迷", "旁观者清"},
            {"三天打鱼", "两天晒网"},
            {"一波未平", "一波又起"},
            {"一夫当关", "万夫莫开"},
            {"一叶障目", "不见泰山"},
            {"一着不慎", "满盘皆输"},
            {"万事俱备", "只欠东风"},
            {"三十六计", "走为上计"},
            {"不鸣则已", "一鸣惊人"},
            {"亡羊补牢", "为时未晚"},
            {"人无远虑", "必有近忧"},
            {"他山之石", "可以攻玉"},
            {"以子之矛", "攻子之盾"},
            {"只许州官放火", "不许百姓点灯"},
            {"君子之交", "淡如水"},
            {"城门失火", "殃及池鱼"},
            {"天网恢恢", "疏而不漏"},
            {"工欲善其事", "必先利其器"},
            {"得道多助", "失道寡助"},
            {"敏而好学", "不耻下问"},
            {"桃李不言", "下自成蹊"},
            {"欲加之罪", "何患无辞"},
            {"水能载舟", "亦能覆舟"},
            {"江山易改", "本性难移"},
            {"路遥知马力", "日久见人心"},
            {"近朱者赤", "近墨者黑"},
            {"金玉其外", "败絮其中"},
            {"静如处子", "动如脱兔"},
            {"一个愿打", "一个愿挨"},
            {"打破沙锅", "问到底"},
            {"不管三七", "二十一"},
            {"五十步", "笑百步"},
        };
        for (String[] row : couplets) {
            String head = row[0];
            String[] conts = row[1].split(",");
            for (int i = 0; i < conts.length; i++) {
                addScore(table, head, conts[i].trim(), 120_000_000.0 - i * 5_000_000.0);
            }
        }
    }

    public static void main(String[] args) throws Exception {
        Path dataDir = Paths.get(args.length >= 1 ? args[0] : "tools/data");
        Path outFile = Paths.get(args.length >= 2 ? args[1] : "app/src/main/assets/dict/zh_predict.bin");

        Map<String, Map<String, Double>> table = new HashMap<>(131072);
        Set<String> knownWords = new HashSet<>(262144);

        seedConversational(table);
        seedClassicalCouplets(table);

        // 1. Load rime-ice + sogou_cell + tencent + frost_literature + frost_composite + frost_idiom
        Path appCn = Paths.get("app/src/main/assets/rime/cn_dicts");
        Path dictExt = dataDir.resolve("dict-ext");
        Path[] dicts = {
            appCn.resolve("base.dict.yaml"),
            appCn.resolve("ext.dict.yaml"),
            appCn.resolve("sogou_cell.dict.yaml"),
            appCn.resolve("tencent.dict.yaml"),
            appCn.resolve("8105.dict.yaml"),
            dictExt.resolve("frost_literature.dict.yaml"),
            dictExt.resolve("frost_idiom.dict.yaml"),
            dictExt.resolve("frost_composite.dict.yaml"),
            dataDir.resolve("rime-src/rime-ice/cn_dicts/base.dict.yaml"),
        };

        Map<String, Long> wordFreq = new HashMap<>(400_000);
        int litCouplets = 0;
        for (Path d : dicts) {
            if (!Files.exists(d)) continue;
            boolean isLit = d.getFileName().toString().contains("literature");
            boolean isComp = d.getFileName().toString().contains("composite");
            try (BufferedReader br = Files.newBufferedReader(d, StandardCharsets.UTF_8)) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (line.isEmpty() || line.charAt(0) == '#') continue;
                    int t1 = line.indexOf('\t');
                    if (t1 <= 0) continue;
                    String w = line.substring(0, t1).trim();
                    if (!isAllCjk(w) || w.length() > 14) continue;
                    long wt = 10;
                    int t2 = line.indexOf('\t', t1 + 1);
                    if (t2 > 0 && t2 + 1 < line.length()) {
                        String ws = line.substring(t2 + 1).trim();
                        try { wt = Long.parseLong(ws); } catch (NumberFormatException ignored) {}
                    }
                    // Symmetrical classical couplets in frost_literature (`4+4`, `5+5`, `6+6`, `7+7`)
                    if (isLit && (w.length() == 8 || w.length() == 10 || w.length() == 12 || w.length() == 14)) {
                        int half = w.length() / 2;
                        addScore(table, w.substring(0, half), w.substring(half), 60_000_000.0 + wt * 500.0);
                        litCouplets++;
                        continue;
                    }
                    if (w.length() <= 10) {
                        long effWt = isComp ? Math.max(wt * 8, 60) : wt;
                        wordFreq.merge(w, effWt, Math::max);
                        if (w.length() <= 5 && effWt >= 15) {
                            knownWords.add(w);
                        }
                    }
                }
            }
        }

        // Also ingest THUOCL_poem.txt for 5/7-char poem phrase familiarity
        Path poemFile = dictExt.resolve("THUOCL_poem.txt");
        if (Files.exists(poemFile)) {
            try (BufferedReader br = Files.newBufferedReader(poemFile, StandardCharsets.UTF_8)) {
                String line;
                while ((line = br.readLine()) != null) {
                    int t = line.indexOf('\t');
                    if (t <= 0) continue;
                    String w = line.substring(0, t).trim();
                    if (!isAllCjk(w)) continue;
                    long wt = 100;
                    try { wt = Long.parseLong(line.substring(t + 1).trim()); } catch (Exception ignored) {}
                    if (w.length() == 8 || w.length() == 10 || w.length() == 14) {
                        int half = w.length() / 2;
                        addScore(table, w.substring(0, half), w.substring(half), 250_000.0 + wt * 20.0);
                    } else if (w.length() == 5 || w.length() == 7) {
                        knownWords.add(w);
                        // 2+3 or 4+3 natural poetic rhythm split
                        int sp = w.length() == 5 ? 2 : 4;
                        addScore(table, w.substring(0, sp), w.substring(sp), 1200.0 + wt * 2.5);
                    }
                }
            }
        }

        // 2. Extract natural multi-character compound / idiom / proverb splits (NEVER splitting 2-char into 1+1!)
        for (Map.Entry<String, Long> e : wordFreq.entrySet()) {
            String w = e.getKey();
            long wt = e.getValue();
            int len = w.length();
            if (len < 3 || wt < 25) continue;
            if (len == 3) {
                String p2 = w.substring(0, 2);
                if (knownWords.contains(p2)) {
                    addScore(table, p2, w.substring(2), wt * 1.6);
                }
            } else if (len == 4) {
                String p2 = w.substring(0, 2);
                if (knownWords.contains(p2) || wt >= 150) {
                    addScore(table, p2, w.substring(2), wt * 2.5 + 140.0);
                }
                String p3 = w.substring(0, 3);
                if (knownWords.contains(p3)) {
                    addScore(table, p3, w.substring(3), wt * 1.5);
                }
            } else if (len >= 5 && len <= 10 && wt >= 40) {
                // Split at longest known multi-char prefix (4 -> 3 -> 2)
                for (int sp = Math.min(6, len - 2); sp >= 2; sp--) {
                    String pref = w.substring(0, sp);
                    String rest = w.substring(sp);
                    if (rest.length() <= 7 && knownWords.contains(pref)) {
                        addScore(table, pref, rest, wt * 2.4 + 100.0);
                        break;
                    }
                }
                if (len == 8 && wt >= 60) {
                    addScore(table, w.substring(0, 4), w.substring(4), wt * 3.0 + 300.0);
                }
            }
        }

        // 3. Ingest 460K N-grams from `tools/build_gram/ngrams.tsv` (`<concat_key>\t<float_weight>`) if present
        Path ngramsTsv = Paths.get("tools/build_gram/ngrams.tsv");
        int ngramPairs = 0;
        if (Files.exists(ngramsTsv)) {
            try (BufferedReader br = Files.newBufferedReader(ngramsTsv, StandardCharsets.UTF_8)) {
                String line;
                while ((line = br.readLine()) != null) {
                    int tab = line.indexOf('\t');
                    if (tab <= 0) continue;
                    String key = line.substring(0, tab);
                    if (key.endsWith("$")) key = key.substring(0, key.length() - 1);
                    int klen = key.length();
                    if (klen < 4 || klen > 10 || !isAllCjk(key)) continue;
                    double wt;
                    try { wt = Double.parseDouble(line.substring(tab + 1).trim()); } catch (Exception ex) { continue; }
                    double boost = Math.min(wt * 0.25, 180_000.0) + 120.0;
                    for (int sp = Math.min(6, klen - 2); sp >= 2; sp--) {
                        String pref = key.substring(0, sp);
                        String rest = key.substring(sp);
                        if (rest.length() <= 6 && knownWords.contains(rest) &&
                                (knownWords.contains(pref) || (sp >= 4 && knownWords.contains(pref.substring(0, 2)) && knownWords.contains(pref.substring(2))))) {
                            addScore(table, pref, rest, boost);
                            ngramPairs++;
                            break;
                        }
                    }
                }
            }
        }

        // 4. Mine sentence word-to-word bigrams + 2-word trigrams + symmetrical comma couplets from distill_corpus_zh.txt
        Path corpus = dataDir.resolve("distill_corpus_zh.txt");
        int minedSentences = 0;
        if (Files.exists(corpus)) {
            try (BufferedReader br = Files.newBufferedReader(corpus, StandardCharsets.UTF_8)) {
                String line;
                while ((line = br.readLine()) != null) {
                    minedSentences++;
                    // Symmetrical comma-linked 4+4 / 5+5 / 7+7 couplets
                    String[] clauses = line.split("[，；。！？]");
                    for (int ci = 0; ci + 1 < clauses.length; ci++) {
                        String ca = clauses[ci].trim();
                        String cb = clauses[ci + 1].trim();
                        if (ca.length() == cb.length() && (ca.length() == 4 || ca.length() == 5 || ca.length() == 7)
                                && isAllCjk(ca) && isAllCjk(cb) && !ca.equals(cb)) {
                            addScore(table, ca, cb, 800.0);
                        }
                    }
                    List<String> segs = segmentFmm(line, knownWords);
                    for (int i = 0; i + 1 < segs.size(); i++) {
                        String a = segs.get(i);
                        String b = segs.get(i + 1);
                        if (a != null && b != null) {
                            if (a.length() >= 2) {
                                double boost = 95.0 * (b.length() >= 2 ? 1.4 : 0.85);
                                addScore(table, a, b, boost);
                            }
                            if (i >= 1 && segs.get(i - 1) != null) {
                                String ab = segs.get(i - 1) + a;
                                if (ab.length() >= 3 && ab.length() <= 7) {
                                    addScore(table, ab, b, 135.0 * (b.length() >= 2 ? 1.5 : 0.9));
                                }
                            }
                        }
                    }
                }
            }
        }

        // 5. Rank & prune to top 90,000 most useful heads (prioritizing multi-char & couplet heads over 1-char!)
        class HeadEntry {
            final byte[] headBytes;
            final List<byte[]> conts;
            final double totalScore;
            HeadEntry(byte[] headBytes, List<byte[]> conts, double totalScore) {
                this.headBytes = headBytes;
                this.conts = conts;
                this.totalScore = totalScore;
            }
        }

        List<HeadEntry> candidates = new ArrayList<>(table.size());
        for (Map.Entry<String, Map<String, Double>> e : table.entrySet()) {
            String head = e.getKey();
            byte[] hb = head.getBytes(StandardCharsets.UTF_8);
            if (hb.length == 0 || hb.length > 24) continue;
            List<Map.Entry<String, Double>> list = new ArrayList<>(e.getValue().entrySet());
            list.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
            List<byte[]> top = new ArrayList<>(8);
            double sum = 0;
            for (int i = 0; i < Math.min(8, list.size()); i++) {
                double sc = list.get(i).getValue();
                if (sc < 45.0 && i >= 2) break;
                byte[] cb = list.get(i).getKey().getBytes(StandardCharsets.UTF_8);
                if (cb.length > 0 && cb.length <= 24) {
                    top.add(cb);
                    sum += sc;
                }
            }
            if (!top.isEmpty()) {
                double lenMul = head.length() >= 4 ? 2.4 : head.length() == 3 ? 2.1 : head.length() == 2 ? 1.9 : 0.8;
                candidates.add(new HeadEntry(hb, top, sum * lenMul));
            }
        }

        candidates.sort((a, b) -> Double.compare(b.totalScore, a.totalScore));
        if (candidates.size() > 90_000) {
            candidates = new ArrayList<>(candidates.subList(0, 90_000));
        }

        // Sort lexicographically by unsigned UTF-8 bytes for O(log N) binary search!
        candidates.sort((a, b) -> compareUnsigned(a.headBytes, b.headBytes));

        // Serialize `AIKBZH01`
        ByteArrayOutputStream pool = new ByteArrayOutputStream(1 << 21);
        int[] offsets = new int[candidates.size()];
        for (int i = 0; i < candidates.size(); i++) {
            HeadEntry he = candidates.get(i);
            offsets[i] = pool.size();
            pool.write(he.headBytes.length);
            pool.write(he.headBytes);
            pool.write(he.conts.size());
            for (byte[] cb : he.conts) {
                pool.write(cb.length);
                pool.write(cb);
            }
        }

        byte[] poolBytes = pool.toByteArray();
        Files.createDirectories(outFile.getParent());
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(outFile)))) {
            out.write("AIKBZH01".getBytes(StandardCharsets.US_ASCII));
            out.writeInt(candidates.size());
            for (int off : offsets) out.writeInt(off);
            out.write(poolBytes);
        }

        System.out.printf("Built %s: %,d heads (couplets %,d, ngrams %,d, corpus %,d), %,d bytes (%.2f MB)%n",
            outFile, candidates.size(), litCouplets, ngramPairs, minedSentences, Files.size(outFile), Files.size(outFile) / 1048576.0);
    }

    private static List<String> segmentFmm(String s, Set<String> dict) {
        List<String> out = new ArrayList<>();
        int i = 0, n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c < '\u4e00' || c > '\u9fff') {
                out.add(null); // boundary break (punctuation/space)
                i++;
                continue;
            }
            int matchedLen = 1;
            for (int len = Math.min(5, n - i); len >= 2; len--) {
                String sub = s.substring(i, i + len);
                if (isAllCjk(sub) && dict.contains(sub)) {
                    matchedLen = len;
                    break;
                }
            }
            out.add(s.substring(i, i + matchedLen));
            i += matchedLen;
        }
        return out;
    }

    private static int compareUnsigned(byte[] a, byte[] b) {
        int lim = Math.min(a.length, b.length);
        for (int i = 0; i < lim; i++) {
            int va = a[i] & 0xFF;
            int vb = b[i] & 0xFF;
            if (va != vb) return va - vb;
        }
        return a.length - b.length;
    }
}
