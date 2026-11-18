import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Turns tools/eval/sentences.txt into an on-device test set of (context, pinyin, target).
 *
 * Input lines:  [context <TAB>] text        ('#' comments allowed)
 * Each Chinese clause of `text` becomes one item; its context is the conversation context plus
 * the text before the clause, which is what the keyboard would see before the cursor.
 * Pinyin comes from rime-ice dictionaries by forward maximum matching (word readings first,
 * then the most frequent single-character reading), i.e. what a user would type.
 *
 * Usage: java tools/BuildEval.java tools/data/rime-src/rime-ice/cn_dicts tools/eval/sentences.txt out.tsv
 */
public class BuildEval {
    static Map<String, String> reading = new HashMap<>();
    static Map<String, Long> weight = new HashMap<>();
    static int maxLen = 1;

    public static void main(String[] args) throws IOException {
        Path dicts = Paths.get(args[0]);
        for (String f : new String[]{"8105.dict.yaml", "41448.dict.yaml", "base.dict.yaml", "ext.dict.yaml"})
            load(dicts.resolve(f));
        List<String> out = new ArrayList<>();
        int unresolved = 0;
        for (String line : Files.readAllLines(Paths.get(args[1]), StandardCharsets.UTF_8)) {
            if (line.isBlank() || line.startsWith("#")) continue;
            String ctx = "", text = line.trim();
            int tab = line.indexOf('\t');
            if (tab >= 0) { ctx = line.substring(0, tab).trim(); text = line.substring(tab + 1).trim(); }
            StringBuilder before = new StringBuilder(ctx);
            int i = 0;
            while (i < text.length()) {
                int j = i;
                while (j < text.length() && isHan(text.charAt(j))) j++;
                if (j > i) {
                    String clause = text.substring(i, j);
                    String py = pinyin(clause);
                    if (py == null) unresolved++;
                    else if (clause.length() >= 2) out.add(before + "\t" + py + "\t" + clause);
                    before.append(clause);
                    i = j;
                } else {
                    before.append(text.charAt(i));
                    i++;
                }
            }
        }
        Files.write(Paths.get(args[2]), out, StandardCharsets.UTF_8);
        System.out.println("items=" + out.size() + " unresolved=" + unresolved);
    }

    static boolean isHan(char c) { return c >= 0x3400 && c <= 0x9FFF; }

    static void load(Path p) throws IOException {
        boolean body = false;
        for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
            if (!body) { if (line.startsWith("...")) body = true; continue; }
            if (line.startsWith("#") || line.isBlank()) continue;
            String[] f = line.split("\t");
            if (f.length < 2) continue;
            String word = f[0], code = f[1].replace(" ", "");
            if (!code.matches("[a-z]+")) continue;
            long w = f.length > 2 ? parse(f[2]) : 0;
            Long cur = weight.get(word);
            if (cur == null || w > cur) { weight.put(word, w); reading.put(word, code); }
            maxLen = Math.max(maxLen, word.length());
        }
    }

    static long parse(String s) { try { return Long.parseLong(s.trim()); } catch (Exception e) { return 0; } }

    static String pinyin(String s) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < s.length()) {
            int best = 0;
            for (int len = Math.min(maxLen, s.length() - i); len >= 1; len--)
                if (reading.containsKey(s.substring(i, i + len))) { best = len; break; }
            if (best == 0) return null;
            sb.append(reading.get(s.substring(i, i + best)));
            i += best;
        }
        return sb.toString();
    }
}
