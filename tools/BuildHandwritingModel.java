import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Packs ochwpro's MIT-licensed StrokeTransformer FP16 weights + 7356-character vocabulary
 * into a single compact binary asset (app/src/main/assets/dict/hw.bin, ~3.45 MB).
 *
 * Usage: java tools/BuildHandwritingModel.java tools/data/ochwpro/ochw-wasm app/src/main/assets/dict/hw.bin
 */
public class BuildHandwritingModel {

    static class TensorMeta {
        String dtype;
        long start;
        long end;
    }

    public static void main(String[] args) throws IOException {
        Path srcDir = Paths.get(args[0]);
        Path out = Paths.get(args[1]);
        byte[] st = Files.readAllBytes(srcDir.resolve("ochw_model_fp16.safetensors"));
        ByteBuffer buf = ByteBuffer.wrap(st).order(ByteOrder.LITTLE_ENDIAN);
        long headerLen = buf.getLong(0);
        String headerJson = new String(st, 8, (int) headerLen, StandardCharsets.UTF_8);
        int dataBase = 8 + (int) headerLen;

        Map<String, TensorMeta> tensors = parseSafetensorsHeader(headerJson);
        System.out.println("Parsed safetensors tensors: " + tensors.size());

        String charJson = Files.readString(srcDir.resolve("char_index.json"), StandardCharsets.UTF_8);
        List<String> chars = parseCharsArray(charJson);
        System.out.println("Parsed char_index classes: " + chars.size());
        if (chars.size() != 7356) throw new IllegalStateException("Expected 7356 chars, got " + chars.size());

        List<String> order = new ArrayList<>();
        order.add("cls_token");
        order.add("pos_encoder.pe");
        order.add("input_proj.weight");
        order.add("input_proj.bias");
        for (int i = 0; i < 3; i++) {
            String p = "transformer.layers." + i + ".";
            order.add(p + "norm1.weight");
            order.add(p + "norm1.bias");
            order.add(p + "self_attn.in_proj_weight");
            order.add(p + "self_attn.in_proj_bias");
            order.add(p + "self_attn.out_proj.weight");
            order.add(p + "self_attn.out_proj.bias");
            order.add(p + "norm2.weight");
            order.add(p + "norm2.bias");
            order.add(p + "linear1.weight");
            order.add(p + "linear1.bias");
            order.add(p + "linear2.weight");
            order.add(p + "linear2.bias");
        }
        order.add("norm.weight");
        order.add("norm.bias");
        order.add("classifier.1.weight");
        order.add("classifier.1.bias");
        order.add("classifier.4.weight");
        order.add("classifier.4.bias");

        Files.createDirectories(out.getParent());
        try (DataOutputStream o = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(out)))) {
            o.write("AIKBHW01".getBytes(StandardCharsets.US_ASCII));
            o.writeInt(7356); // numClasses
            o.writeInt(192);  // dModel
            o.writeInt(4);    // nHead
            o.writeInt(3);    // numLayers
            o.writeInt(384);  // dimFeedforward
            o.writeInt(513);  // maxPosLen

            for (String ch : chars) {
                byte[] b = ch.getBytes(StandardCharsets.UTF_8);
                o.writeByte(b.length);
                o.write(b);
            }

            int totalParams = 0;
            for (String name : order) {
                TensorMeta m = tensors.get(name);
                if (m == null) throw new IllegalStateException("Missing tensor: " + name);
                int byteLen = (int) (m.end - m.start);
                int count = m.dtype.equals("F16") ? byteLen / 2 : byteLen / 4;
                totalParams += count;
                o.writeInt(count);
                int off = dataBase + (int) m.start;
                if (m.dtype.equals("F16")) {
                    // Store raw IEEE-754 binary16 big-endian shorts
                    for (int i = 0; i < count; i++) {
                        short h = buf.getShort(off + i * 2);
                        o.writeShort(h);
                    }
                } else if (m.dtype.equals("F32")) {
                    for (int i = 0; i < count; i++) {
                        float v = buf.getFloat(off + i * 4);
                        o.writeShort(floatToHalf(v));
                    }
                } else {
                    throw new IllegalStateException("Unsupported dtype " + m.dtype + " for " + name);
                }
            }
            System.out.printf("Wrote %s: %d params, %d bytes%n", out, totalParams, Files.size(out));
        }
    }

    static Map<String, TensorMeta> parseSafetensorsHeader(String json) {
        Map<String, TensorMeta> map = new HashMap<>();
        Pattern entryPat = Pattern.compile("\"([^\"]+)\"\\s*:\\s*\\{([^{}]+)\\}");
        Pattern dtypePat = Pattern.compile("\"dtype\"\\s*:\\s*\"([^\"]+)\"");
        Pattern offPat = Pattern.compile("\"data_offsets\"\\s*:\\s*\\[\\s*(\\d+)\\s*,\\s*(\\d+)\\s*\\]");
        Matcher m = entryPat.matcher(json);
        while (m.find()) {
            String key = m.group(1);
            if (key.equals("__metadata__")) continue;
            String body = m.group(2);
            Matcher dm = dtypePat.matcher(body);
            Matcher om = offPat.matcher(body);
            if (dm.find() && om.find()) {
                TensorMeta tm = new TensorMeta();
                tm.dtype = dm.group(1);
                tm.start = Long.parseLong(om.group(1));
                tm.end = Long.parseLong(om.group(2));
                map.put(key, tm);
            }
        }
        return map;
    }

    static List<String> parseCharsArray(String json) {
        List<String> out = new ArrayList<>();
        int arrStart = json.indexOf('[');
        int arrEnd = json.lastIndexOf(']');
        String s = json.substring(arrStart + 1, arrEnd);
        int i = 0;
        while (i < s.length()) {
            while (i < s.length() && s.charAt(i) != '"') i++;
            if (i >= s.length()) break;
            i++; // skip opening quote
            StringBuilder sb = new StringBuilder();
            while (i < s.length() && s.charAt(i) != '"') {
                char c = s.charAt(i++);
                if (c == '\\' && i < s.length()) {
                    char esc = s.charAt(i++);
                    switch (esc) {
                        case '"': sb.append('"'); break;
                        case '\\': sb.append('\\'); break;
                        case '/': sb.append('/'); break;
                        case 'n': sb.append('\n'); break;
                        case 'r': sb.append('\r'); break;
                        case 't': sb.append('\t'); break;
                        case 'u':
                            sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            i += 4;
                            break;
                        default: sb.append(esc); break;
                    }
                } else {
                    sb.append(c);
                }
            }
            i++; // skip closing quote
            out.add(sb.toString());
        }
        return out;
    }

    static short floatToHalf(float fval) {
        int fbits = Float.floatToIntBits(fval);
        int sign = (fbits >>> 16) & 0x8000;
        int val = (fbits & 0x7fffffff) + 0x1000;
        if (val >= 0x47800000) {
            if ((fbits & 0x7fffffff) >= 0x47800000) {
                if (val < 0x7f800000) return (short) (sign | 0x7c00);
                return (short) (sign | 0x7c00 | ((fbits & 0x007fffff) >>> 13));
            }
            return (short) (sign | 0x7bff);
        }
        if (val >= 0x38800000) return (short) (sign | ((val - 0x38000000) >>> 13));
        if (val < 0x33000000) return (short) sign;
        val = (fbits & 0x7fffffff) >>> 23;
        return (short) (sign | (((fbits & 0x7fffff | 0x800000) + (0x800000 >>> (val - 102))) >>> (126 - val)));
    }
}
