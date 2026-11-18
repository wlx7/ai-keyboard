#!/usr/bin/env bash
# Assembles app/src/main/assets/rime from upstream Rime data:
#   rime-ice (iDvel, GPL-3.0)            全拼 + 双拼 schemes, dictionaries, lua, opencc
#   rime-wubi86-jidian (KyleBing, MIT)   五笔 86
#   rime-cangjie (rime, LGPL-3.0)        仓颉五代
# Sources are expected in tools/data/rime-src/<repo> (see ROADMAP.md).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/tools/data/rime-src"
OUT="$ROOT/app/src/main/assets/rime"
mkdir -p "$OUT" && find "$OUT" -mindepth 1 -delete   # clear contents (the dir itself may be in use on Windows)

ICE="$SRC/rime-ice"
cp "$ICE"/default.yaml "$ICE"/symbols_v.yaml "$ICE"/symbols_caps_v.yaml "$ICE"/custom_phrase.txt "$OUT"/
cp "$ICE"/rime_ice.schema.yaml "$ICE"/rime_ice.dict.yaml "$OUT"/
cp "$ICE"/double_pinyin*.schema.yaml "$OUT"/
cp "$ICE"/melt_eng.schema.yaml "$ICE"/melt_eng.dict.yaml "$OUT"/
cp "$ICE"/radical_pinyin.schema.yaml "$ICE"/radical_pinyin.dict.yaml "$OUT"/
cp -r "$ICE"/cn_dicts "$ICE"/en_dicts "$ICE"/lua "$ICE"/opencc "$OUT"/
[ -f "$ICE/rime.lua" ] && cp "$ICE/rime.lua" "$OUT"/

WB="$SRC/rime-wubi86-jidian"
cp "$WB"/wubi86_jidian.schema.yaml "$WB"/wubi86_jidian.dict.yaml "$WB"/wubi86_jidian_extra*.dict.yaml \
   "$WB"/wubi86_jidian_user.dict.yaml "$WB"/wubi86_jidian_user_hamster.dict.yaml "$WB"/pinyin_simp.schema.yaml "$WB"/pinyin_simp.dict.yaml "$OUT"/

CJ="$SRC/rime-cangjie"
cp "$CJ"/cangjie5.schema.yaml "$CJ"/cangjie5*.dict.yaml "$OUT"/
# No essay.txt (preset vocabulary) is shipped; Cangjie is typed character by character.
sed -i 's/use_preset_vocabulary: true/use_preset_vocabulary: false/' "$OUT/cangjie5.dict.yaml"
# Its punctuator imports prelude symbols.yaml; rime-ice's default.yaml carries the same section.
sed -i 's/import_preset: symbols/import_preset: default/' "$OUT/cangjie5.schema.yaml"
# Cangjie's pinyin reverse lookup expects luna_pinyin, which we do not ship: use rime-ice instead.
sed -i 's/^\(\s*\)- luna_quanpin/\1- rime_ice/; s/dictionary: luna_pinyin/dictionary: rime_ice/; s/prism: luna_quanpin/prism: rime_ice/' "$OUT/cangjie5.schema.yaml"

# OpenCC simplified<->traditional (BYVoid/OpenCC, Apache-2.0) as plain-text dictionaries, so no
# ocd2 compile step is needed. Header comments are stripped for older TextDict parsers.
OCC="$ROOT/tools/data/opencc"
for f in STCharacters STPhrases TSCharacters TSPhrases; do
  grep -v '^#' "$OCC/$f.txt" | grep -v '^\s*$' > "$OUT/opencc/$f.txt"
done
occ_config() { # name phrases chars
  cat > "$OUT/opencc/$1.json" <<JSON
{
  "name": "$1",
  "segmentation": { "type": "mmseg", "dict": { "type": "text", "file": "$2.txt" } },
  "conversion_chain": [ { "dict": { "type": "group", "dicts": [
    { "type": "text", "file": "$2.txt" }, { "type": "text", "file": "$3.txt" } ] } } ]
}
JSON
}
occ_config s2t STPhrases STCharacters
occ_config t2s TSPhrases TSCharacters

# Wubi converts with s2hk.json, which we do not ship; use plain s2t.
sed -i 's/^  opencc_config: s2hk.json/  opencc_config: s2t.json/' "$OUT/wubi86_jidian.schema.yaml"

# 九宫格: rime-ice's t9 scheme (pinyin on 2-9, built on the rime_ice dictionary).
cp "$ICE"/t9.schema.yaml "$OUT"/

# Our scheme list (order = order in the settings picker).
awk 'BEGIN{skip=0}
  /^schema_list:/ {print "schema_list:";
    print "  - schema: rime_ice               # 全拼";
    print "  - schema: t9                     # 九宫格拼音";
    print "  - schema: double_pinyin_flypy    # 小鹤双拼";
    print "  - schema: double_pinyin          # 自然码双拼";
    print "  - schema: double_pinyin_mspy     # 微软双拼";
    print "  - schema: double_pinyin_sogou    # 搜狗双拼";
    print "  - schema: double_pinyin_abc      # 智能 ABC 双拼";
    print "  - schema: double_pinyin_ziguang  # 紫光双拼";
    print "  - schema: wubi86_jidian          # 五笔 86";
    print "  - schema: cangjie5               # 仓颉五代";
    skip=1; next}
  skip && /^[^ #-]/ {skip=0}
  !skip {print}' "$OUT/default.yaml" > "$OUT/default.yaml.tmp" && mv "$OUT/default.yaml.tmp" "$OUT/default.yaml"

# Version stamp (not a dotfile: aapt drops those from assets): the app re-copies data and redeploys when this changes.
( cd "$OUT" && find . -type f ! -name version.txt -print0 | sort -z | xargs -0 cat | sha1sum | cut -c1-12 ) > "$OUT/version.txt"
echo "rime data $(cat "$OUT/version.txt"): $(du -sh "$OUT" | cut -f1), $(find "$OUT" -type f | wc -l) files"
