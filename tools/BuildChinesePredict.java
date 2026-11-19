import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Builds `app/src/main/assets/dict/zh_predict.bin` (`AIKBZH01` compact binary table)
 * for instant (<0.05 ms) offline Chinese next-word & idiom prediction (`中文联想`).
 *
 * Sources combined:
 *   1. Curated high-frequency conversational Chinese starters & daily chat collocations
 *   2. Word-to-word transitions mined via FMM segmentation over `tools/data/distill_corpus_zh.txt`
 *   3. Multi-character compound & 4-char idiom continuations from `rime-ice` (`base.dict.yaml`, `ext.dict.yaml`)
 *
 * Usage:
 *   java tools/BuildChinesePredict.java tools/data app/src/main/assets/dict/zh_predict.bin
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
        if (head.isEmpty() || cont.isEmpty() || head.equals(cont)) return;
        if (head.length() > 4 || cont.length() > 4) return;
        if (!isAllCjk(head) || !isAllCjk(cont)) return;
        table.computeIfAbsent(head, k -> new HashMap<>()).merge(cont, score, Double::sum);
    }

    private static void seedConversational(Map<String, Map<String, Double>> table) {
        String[][] pairs = {
            {"我", "们,在,想,要,现在,已经,觉得,可以"},
            {"你", "好,们,在,看,说,觉得,现在,去"},
            {"他", "们,说,在,的,已经,现在"},
            {"她", "们,说,在,的,已经,很"},
            {"好", "的,吧,啊,看,吃,久不见,像,好"},
            {"不", "好意思,是,要,知道,可以,错,过,行"},
            {"没", "有,事,问题,关系,办法,空,想到"},
            {"请", "问,稍等,查收,注意,大家,放心,进"},
            {"祝", "你,您,大家,生日快乐,工作顺利,一切顺利"},
            {"在", "哪里,吗,干嘛,一起,家,路上,公司,线"},
            {"有", "空,没有,点,时间,问题,什么,事"},
            {"去", "哪里,吃饭,看看,上班,休息,找"},
            {"想", "要,问一下,吃,去,知道,看看,你"},
            {"要", "不要,去,不,注意,求,是"},
            {"看", "一下,看,起来,到,见,似"},
            {"来", "了,不及,看看,说,自"},
            {"说", "明,话,实话,不定,得对"},
            {"很", "好,多,高兴,快,喜欢,棒,不错"},
            {"太", "好了,棒了,多了,晚了,感谢,厉害了"},
            {"真", "的,好,是,不错,棒,厉害"},
            {"先", "去,看,吃饭,休息,这样,等一下"},
            {"再", "见,说,次,看看,试一下,来"},
            {"还", "有,是,没,可以,好,行"},
            {"都", "可以,是,好,在,有,没问题"},
            {"今天", "天气,晚上,下午,早上,吃什么,有空,真是,工作"},
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
            {"对不", "起"},
            {"没关", "系"},
            {"没有", "问题,关系,办法,时间,想到,收到"},
            {"可以", "吗,的,试试,看看,先,啊,发我"},
            {"应该", "可以,是,没问题,不会,会"},
            {"需要", "我,什么,帮忙,注意,多久"},
            {"我们", "一起,可以,现在,今天,先,公司,需要"},
            {"你们", "在,可以,什么时候,觉得,去"},
            {"他们", "在,说,已经,公司"},
            {"大家", "好,辛苦了,晚上好,早上好,一起,注意"},
            {"这个", "问题,时候,地方,项目,功能,怎么,真的"},
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
            {"秋水", "时至,共长天,伊人,长天"},
            {"百川", "灌河,归海"},
            {"落霞", "与孤鹜,孤鹜"},
            {"大模", "型"},
            {"大模型", "蒸馏,推理,训练,部署,应用"},
            {"智能", "体,纠错,输入法,选句,家居"},
        };
        for (String[] row : pairs) {
            String head = row[0];
            String[] conts = row[1].split(",");
            for (int i = 0; i < conts.length; i++) {
                addScore(table, head, conts[i].trim(), 500_000.0 - i * 30_000.0);
            }
        }
    }

    public static void main(String[] args) throws Exception {
        Path dataDir = Paths.get(args.length >= 1 ? args[0] : "tools/data");
        Path outFile = Paths.get(args.length >= 2 ? args[1] : "app/src/main/assets/dict/zh_predict.bin");

        Map<String, Map<String, Double>> table = new HashMap<>(65536);
        Set<String> knownWords = new HashSet<>(131072);

        seedConversational(table);

        // 1. Load rime-ice + sogou_cell + tencent dicts for vocabulary + compound/idiom continuations
        Path appCn = Paths.get("app/src/main/assets/rime/cn_dicts");
        Path[] dicts = {
            appCn.resolve("base.dict.yaml"),
            appCn.resolve("ext.dict.yaml"),
            appCn.resolve("sogou_cell.dict.yaml"),
            appCn.resolve("tencent.dict.yaml"),
            appCn.resolve("8105.dict.yaml"),
            dataDir.resolve("rime-src/rime-ice/cn_dicts/base.dict.yaml"),
        };

        Map<String, Long> wordFreq = new HashMap<>(200_000);
        for (Path d : dicts) {
            if (!Files.exists(d)) continue;
            try (BufferedReader br = Files.newBufferedReader(d, StandardCharsets.UTF_8)) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (line.isEmpty() || line.charAt(0) == '#') continue;
                    int t1 = line.indexOf('\t');
                    if (t1 <= 0) continue;
                    String w = line.substring(0, t1).trim();
                    if (!isAllCjk(w) || w.length() > 6) continue;
                    long wt = 10;
                    int t2 = line.indexOf('\t', t1 + 1);
                    if (t2 > 0 && t2 + 1 < line.length()) {
                        String ws = line.substring(t2 + 1).trim();
                        try { wt = Long.parseLong(ws); } catch (NumberFormatException ignored) {}
                    }
                    wordFreq.merge(w, wt, Math::max);
                    if (w.length() <= 4 && wt >= 20) {
                        knownWords.add(w);
                    }
                }
            }
        }

        // Extract natural compound / 4-char idiom splits where the prefix itself is a known word!
        for (Map.Entry<String, Long> e : wordFreq.entrySet()) {
            String w = e.getKey();
            long wt = e.getValue();
            if (wt < 30) continue;
            int len = w.length();
            if (len == 2) {
                // 1 + 1 split only for high-freq words so single-char heads get natural 2nd chars
                if (wt >= 800) {
                    addScore(table, w.substring(0, 1), w.substring(1, 2), wt * 0.35);
                }
            } else if (len == 3) {
                String p2 = w.substring(0, 2);
                if (knownWords.contains(p2)) {
                    addScore(table, p2, w.substring(2), wt * 1.6);
                }
                if (wt >= 500) {
                    addScore(table, w.substring(0, 1), w.substring(1), wt * 0.5);
                }
            } else if (len == 4) {
                String p2 = w.substring(0, 2);
                if (knownWords.contains(p2) || wt >= 150) {
                    addScore(table, p2, w.substring(2), wt * 2.5 + 120.0);
                }
                String p3 = w.substring(0, 3);
                if (knownWords.contains(p3)) {
                    addScore(table, p3, w.substring(3), wt * 1.5);
                }
            } else if (len >= 5 && wt >= 80) {
                String p2 = w.substring(0, 2);
                String rest = w.substring(2);
                if (rest.length() <= 4 && knownWords.contains(p2)) {
                    addScore(table, p2, rest, wt * 2.0);
                }
                String p3 = w.substring(0, 3);
                String rest3 = w.substring(3);
                if (rest3.length() <= 3 && knownWords.contains(p3)) {
                    addScore(table, p3, rest3, wt * 2.0);
                }
            }
        }

        // 2. Mine real sentence word-to-word bigrams from distill_corpus_zh.txt (FMM segmentation)
        Path corpus = dataDir.resolve("distill_corpus_zh.txt");
        int minedSentences = 0;
        if (Files.exists(corpus)) {
            try (BufferedReader br = Files.newBufferedReader(corpus, StandardCharsets.UTF_8)) {
                String line;
                while ((line = br.readLine()) != null) {
                    minedSentences++;
                    List<String> segs = segmentFmm(line, knownWords);
                    for (int i = 0; i + 1 < segs.size(); i++) {
                        String a = segs.get(i);
                        String b = segs.get(i + 1);
                        if (a != null && b != null) {
                            double boost = (a.length() >= 2 ? 95.0 : 28.0) * (b.length() >= 2 ? 1.35 : 0.85);
                            addScore(table, a, b, boost);
                        }
                    }
                }
            }
        }

        // 3. Rank & prune to top 24,000 most useful heads (up to 8 continuations per head)
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
                double priority = sum * (head.length() == 2 ? 1.8 : head.length() == 1 ? 2.5 : 1.0);
                candidates.add(new HeadEntry(hb, top, priority));
            }
        }

        candidates.sort((a, b) -> Double.compare(b.totalScore, a.totalScore));
        if (candidates.size() > 36_000) {
            candidates = new ArrayList<>(candidates.subList(0, 36_000));
        }

        // Sort lexicographically by unsigned UTF-8 bytes for binary search!
        candidates.sort((a, b) -> compareUnsigned(a.headBytes, b.headBytes));

        // Serialize `AIKBZH01`
        ByteArrayOutputStream pool = new ByteArrayOutputStream(1 << 20);
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

        System.out.printf("Built %s: %,d heads (mined %,d corpus sentences), %,d bytes (%.2f MB)%n",
            outFile, candidates.size(), minedSentences, Files.size(outFile), Files.size(outFile) / 1048576.0);
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
            for (int len = Math.min(4, n - i); len >= 2; len--) {
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
