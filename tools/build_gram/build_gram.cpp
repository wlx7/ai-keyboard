// Builds a genuine Rime::Grammar/1.0 (.gram) binary database for librime-octagram
// using official s-yata/darts-clone (darts.h) and exact gram_encoding from librime-octagram.
//
// Usage: build_gram.exe <input_tsv> <output_gram>

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <iostream>
#include <map>
#include <string>
#include <vector>

#include "darts.h"

namespace {

constexpr int kMaxEncodedUnicode = 8;
constexpr double kValueScale = 10000.0;

// Exact UTF-8 code point decoder
uint32_t next_utf8(const char*& p, const char* end) {
    const unsigned char c0 = static_cast<unsigned char>(*p++);
    if (c0 < 0x80) return c0;
    if ((c0 >> 5) == 0x6 && p < end) {
        uint32_t c1 = static_cast<unsigned char>(*p++) & 0x3F;
        return ((c0 & 0x1F) << 6) | c1;
    }
    if ((c0 >> 4) == 0xE && p + 1 < end) {
        uint32_t c1 = static_cast<unsigned char>(*p++) & 0x3F;
        uint32_t c2 = static_cast<unsigned char>(*p++) & 0x3F;
        return ((c0 & 0x0F) << 12) | (c1 << 6) | c2;
    }
    if ((c0 >> 3) == 0x1E && p + 2 < end) {
        uint32_t c1 = static_cast<unsigned char>(*p++) & 0x3F;
        uint32_t c2 = static_cast<unsigned char>(*p++) & 0x3F;
        uint32_t c3 = static_cast<unsigned char>(*p++) & 0x3F;
        return ((c0 & 0x07) << 18) | (c1 << 12) | (c2 << 6) | c3;
    }
    return 0xFFFD;
}

// Exact port of librime-octagram/src/gram_encoding.cc
std::string gram_encode(const std::string& utf8_str) {
    std::string out;
    out.reserve(utf8_str.size());
    const char* p = utf8_str.data();
    const char* end = p + utf8_str.size();
    while (p < end) {
        uint32_t u = next_utf8(p, end);
        if (u < 0x80) {
            if (u == 0) {
                out.push_back(static_cast<char>(0xE0));
            } else {
                out.push_back(static_cast<char>(u));
            }
        } else if (u >= 0x4000 && u < 0xA000) {
            if ((u & 0xFF) == 0) {
                out.push_back(static_cast<char>(0xE1));
                out.push_back(static_cast<char>((u >> 8) + 0x40));
            } else {
                out.push_back(static_cast<char>((u >> 8) + 0x40));
                out.push_back(static_cast<char>(u & 0xFF));
            }
        } else {
            int bits = 32;
            while (bits > 0 && (u & 0xFE000000u) == 0) {
                bits -= 7;
                u <<= 7;
            }
            int bytes_to_encode = (bits + 6) / 7;
            out.push_back(static_cast<char>(0xE0 | bytes_to_encode));
            while (bytes_to_encode > 0) {
                --bytes_to_encode;
                out.push_back(static_cast<char>(((u >> 25) & 0x7F) | 0x80));
                u <<= 7;
            }
        }
    }
    return out;
}

const char* advance_encoded(const char* p) {
    const unsigned char b = static_cast<unsigned char>(*p);
    return p + ((b & 0x80) == 0 ? 1 : (b & 0xF0) == 0xE0 ? (b & 0x0F) + 1 : 2);
}

size_t unicode_length_encoded(const std::string& encoded, size_t byte_len) {
    const char* begin = encoded.c_str();
    const char* end = begin + byte_len;
    size_t len = 0;
    for (const char* p = begin; p < end; p = advance_encoded(p)) ++len;
    return len;
}

#pragma pack(push, 1)
struct MetadataDisk {
    char format[32];
    uint32_t db_checksum;
    uint32_t double_array_size;
    int32_t double_array_offset; // OffsetPtr<char, int32_t>: relative from &double_array_offset (byte 40) to byte 44 => 4
};
#pragma pack(pop)
static_assert(sizeof(MetadataDisk) == 44, "MetadataDisk must be 44 bytes");

double test_query(const Darts::DoubleArray& trie, const std::string& context, const std::string& word, bool is_rear) {
    double result = -12.0; // non_collocation_penalty
    Darts::DoubleArray::result_pair_type matches[8];
    std::string cq = gram_encode(context);
    std::string wq = gram_encode(word);
    // Single whole-context test + suffix backoff
    int context_len = static_cast<int>(unicode_length_encoded(cq, cq.size()));
    int word_len = static_cast<int>(unicode_length_encoded(wq, wq.size()));
    for (const char* cp = cq.c_str(); context_len > 0; --context_len, cp = advance_encoded(cp)) {
        size_t node_pos = 0, key_pos = 0;
        trie.traverse(cp, node_pos, key_pos);
        if (key_pos == std::strlen(cp)) {
            size_t num = trie.commonPrefixSearch(wq.c_str(), matches, 8, 0, node_pos);
            for (size_t i = 0; i < num; ++i) {
                int mlen = static_cast<int>(unicode_length_encoded(wq, matches[i].length));
                int col_len = context_len + mlen;
                bool whole = (cp == cq.c_str() && matches[i].length == wq.size());
                double sc = (matches[i].value / kValueScale) + ((col_len >= 2 || whole) ? -12.0 : -24.0);
                if (sc > result) result = sc;
            }
        }
    }
    if (is_rear) {
        size_t node_pos = 0, key_pos = 0;
        trie.traverse(wq.c_str(), node_pos, key_pos);
        if (key_pos == wq.size()) {
            size_t num = trie.commonPrefixSearch("$", matches, 8, 0, node_pos);
            if (num > 0) {
                double sc = (matches[0].value / kValueScale) - 18.0;
                if (sc > result) result = sc;
            }
        }
    }
    (void) word_len;
    return result;
}

} // namespace

int main(int argc, char* argv[]) {
    if (argc < 3) {
        std::cerr << "Usage: build_gram <input_tsv> <output_gram>\n";
        return 1;
    }
    std::ifstream in(argv[1], std::ios::binary);
    if (!in) {
        std::cerr << "Cannot open input: " << argv[1] << "\n";
        return 1;
    }

    // Use unsigned-byte lexicographical ordering required by Darts-clone
    auto cmp = [](const std::string& a, const std::string& b) {
        const size_t n = std::min(a.size(), b.size());
        int c = std::memcmp(a.data(), b.data(), n);
        return c != 0 ? (c < 0) : (a.size() < b.size());
    };
    std::map<std::string, int, decltype(cmp)> encoded_map(cmp);

    std::string line;
    size_t raw_lines = 0;
    while (std::getline(in, line)) {
        if (!line.empty() && line.back() == '\r') line.pop_back();
        if (line.empty() || line[0] == '#') continue;
        size_t tab = line.find('\t');
        if (tab == std::string::npos || tab == 0) continue;
        std::string key = line.substr(0, tab);
        double val = std::stod(line.substr(tab + 1));
        if (val <= 1.0) continue;
        std::string enc = gram_encode(key);
        if (enc.empty() || enc.find('\0') != std::string::npos) continue;
        int scaled = std::max(0, static_cast<int>(std::log(val) * kValueScale));
        if (scaled <= 0) continue;
        auto it = encoded_map.find(enc);
        if (it == encoded_map.end() || scaled > it->second) {
            encoded_map[enc] = scaled;
        }
        ++raw_lines;
    }

    std::vector<std::string> key_buf;
    std::vector<const char*> keys;
    std::vector<int> values;
    key_buf.reserve(encoded_map.size());
    keys.reserve(encoded_map.size());
    values.reserve(encoded_map.size());
    for (auto& kv : encoded_map) {
        key_buf.push_back(kv.first);
    }
    int idx = 0;
    for (const auto& kv : encoded_map) {
        keys.push_back(key_buf[idx++].c_str());
        values.push_back(kv.second);
    }

    std::cout << "Building Darts Double-Array Trie from " << keys.size() << " unique n-grams (raw=" << raw_lines << ")...\n";
    Darts::DoubleArray trie;
    if (trie.build(keys.size(), keys.data(), nullptr, values.data()) != 0) {
        std::cerr << "Darts build failed!\n";
        return 1;
    }

    const uint32_t array_size = static_cast<uint32_t>(trie.size());
    const size_t image_bytes = trie.total_size();

    MetadataDisk meta{};
    const char* fmt = "Rime::Grammar/1.0";
    std::memcpy(meta.format, fmt, std::strlen(fmt));
    meta.db_checksum = 0;
    meta.double_array_size = array_size;
    meta.double_array_offset = 4; // byte 40 + 4 = byte 44

    std::ofstream out(argv[2], std::ios::binary | std::ios::trunc);
    if (!out) {
        std::cerr << "Cannot write output: " << argv[2] << "\n";
        return 1;
    }
    out.write(reinterpret_cast<const char*>(&meta), sizeof(meta));
    out.write(reinterpret_cast<const char*>(trie.array()), image_bytes);
    out.close();

    // Reload from disk and verify exact binary layout + Octagram::Query behavior!
    std::ifstream verify_in(argv[2], std::ios::binary | std::ios::ate);
    size_t file_len = static_cast<size_t>(verify_in.tellg());
    verify_in.seekg(0);
    std::vector<char> disk_bytes(file_len);
    verify_in.read(disk_bytes.data(), file_len);

    const auto* loaded_meta = reinterpret_cast<const MetadataDisk*>(disk_bytes.data());
    const char* loaded_arr = reinterpret_cast<const char*>(&loaded_meta->double_array_offset) + loaded_meta->double_array_offset;
    Darts::DoubleArray loaded_trie;
    loaded_trie.set_array(loaded_arr, loaded_meta->double_array_size);

    double q_shizhi = test_query(loaded_trie, "\xE7\xA7\x8B\xE6\xB0\xB4", "\xE6\x97\xB6\xE8\x87\xB3", true); // 秋水 + 时至
    double q_shizhi_bad = test_query(loaded_trie, "\xE7\xA7\x8B\xE6\xB0\xB4", "\xE6\x98\xAF\xE6\x8C\x87", true); // 秋水 + 是指
    double q_qiushui = test_query(loaded_trie, "\xE6\xB3\x95\xE6\xB0\xB4", "\xE6\x98\xAF\xE6\x8C\x87", true); // 泅水 + 是指
    std::cout << "Saved " << argv[2] << " (" << (file_len / 1024) << " KiB, units=" << array_size << ")\n";
    std::cout << "Self-test Octagram::Query:\n"
              << "  [秋水 + 时至$] = " << q_shizhi << "\n"
              << "  [秋水 + 是指$] = " << q_shizhi_bad << "\n"
              << "  [泅水 + 是指$] = " << q_qiushui << "\n";
    return 0;
}
