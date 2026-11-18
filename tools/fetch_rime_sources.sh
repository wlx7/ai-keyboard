#!/usr/bin/env bash
# Fetches the upstream Rime / OpenCC data used by tools/build_rime_data.sh, at pinned revisions,
# into tools/data (gitignored). Then run: bash tools/build_rime_data.sh
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/tools/data/rime-src"
OCC="$ROOT/tools/data/opencc"
mkdir -p "$SRC" "$OCC"

ICE_REV=a71a234aed131ffe2abdabff6daf50adc192964f      # iDvel/rime-ice "nightly", 2026-11
WUBI_REV=4d08110b8080f6577dad733ada7e5ae5c5e878fd     # KyleBing/rime-wubi86-jidian
CANGJIE_REV=fcb3d7dd94ac1304663d575727d24d8107f6b082  # rime/rime-cangjie
OPENCC_REV=fae2a0a911c30764e669a7d8b0591cd8e852934a   # BYVoid/OpenCC

fetch_tar() { # repo rev dir
  rm -rf "$SRC/$3"; mkdir -p "$SRC/$3"
  curl -fsSL --retry 3 "https://github.com/$1/archive/$2.tar.gz" | tar -xz -C "$SRC/$3" --strip-components=1
  echo "$1 @ ${2:0:7}"
}
fetch_tar iDvel/rime-ice "$ICE_REV" rime-ice
fetch_tar KyleBing/rime-wubi86-jidian "$WUBI_REV" rime-wubi86-jidian
fetch_tar rime/rime-cangjie "$CANGJIE_REV" rime-cangjie
for f in STCharacters STPhrases TSCharacters TSPhrases; do
  curl -fsSL --retry 3 -o "$OCC/$f.txt" "https://raw.githubusercontent.com/BYVoid/OpenCC/$OPENCC_REV/data/dictionary/$f.txt"
done
echo "OpenCC @ ${OPENCC_REV:0:7}"
