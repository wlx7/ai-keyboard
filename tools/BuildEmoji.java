import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Converts Unicode emoji-test.txt into app/src/main/assets/emoji.txt:
 *   "#<group name>" lines followed by one line per group of space-separated emoji.
 * Skin-tone variants are dropped (offered later via long-press).
 * Usage: java tools/BuildEmoji.java tools/data/emoji-test.txt app/src/main/assets/emoji.txt
 */
public class BuildEmoji {
    public static void main(String[] args) throws IOException {
        Map<String, List<String>> groups = new LinkedHashMap<>();
        String group = null;
        for (String line : Files.readAllLines(Paths.get(args[0]), StandardCharsets.UTF_8)) {
            if (line.startsWith("# group: ")) { group = line.substring(9).trim(); continue; }
            if (line.startsWith("#") || line.isBlank() || group == null) continue;
            if (group.equals("Component")) continue;
            int semi = line.indexOf(';'), hash = line.indexOf('#');
            if (semi < 0 || hash < 0) continue;
            if (!line.substring(semi + 1, hash).trim().equals("fully-qualified")) continue;
            String cps = line.substring(0, semi).trim();
            if (cps.matches(".*\\b1F3F[B-F]\\b.*")) continue; // skin tones
            StringBuilder sb = new StringBuilder();
            for (String cp : cps.split("\\s+")) sb.appendCodePoint(Integer.parseInt(cp, 16));
            groups.computeIfAbsent(group, k -> new ArrayList<>()).add(sb.toString());
        }
        try (Writer w = Files.newBufferedWriter(Paths.get(args[1]), StandardCharsets.UTF_8)) {
            int n = 0;
            for (Map.Entry<String, List<String>> e : groups.entrySet()) {
                w.write("#" + e.getKey() + "\n");
                w.write(String.join(" ", e.getValue()) + "\n");
                n += e.getValue().size();
            }
            System.out.println("groups=" + groups.size() + " emoji=" + n);
        }
    }
}
