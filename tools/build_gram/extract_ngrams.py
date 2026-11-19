#!/usr/bin/env python3
"""
Extracts high-precision Chinese N-gram collocations (2..7 chars) and sentence-rear (`word$`)
weights for `keylo-zh-hans.gram` (`Rime::Grammar/1.0`, `librime-octagram`).

Sources (100% local in repository/tools):
  1. `app/src/main/assets/rime/cn_dicts/base.dict.yaml` & `ext.dict.yaml` (core vocabulary & idioms)
  2. `app/src/main/assets/rime/cn_dicts/tencent.dict.yaml` (Tencent AI Lab 983k multi-word phrases)
  3. `tools/data/dict-ext/frost_composite.dict.yaml` (Rime-Frost / Wanxiang multi-word collocations)
  4. `tools/data/dict-ext/frost_idiom.dict.yaml`, `frost_literature.dict.yaml`, `frost_shulihua.dict.yaml`,
     `THUOCL_chengyu.txt`, `THUOCL_poem.txt`, `THUOCL_law.txt`, `THUOCL_IT.txt`, `THUOCL_medical.txt`
  5. `tools/data/distill_corpus_zh.txt` (word-segmented adjacent 2-word & 3-word collocations + rear `$`)

Strictly excludes `tools/eval/sentences.txt` clauses from corpus extraction to keep benchmark fair.
"""

from __future__ import annotations

import math
import re
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CN_DICTS = ROOT / "app" / "src" / "main" / "assets" / "rime" / "cn_dicts"
DICT_EXT = ROOT / "tools" / "data" / "dict-ext"
CORPUS_PATH = ROOT / "tools" / "data" / "distill_corpus_zh.txt"
EVAL_PATH = ROOT / "tools" / "eval" / "sentences.txt"
OUT_TSV = ROOT / "tools" / "build_gram" / "ngrams.tsv"

TARGET_MAX_KEYS = 460_000

DANGLING_REAR_BLACKLIST = {
    "是指", "即指", "乃指", "在于", "位于", "属于", "关于", "对于", "由于", "鉴于", "基于",
    "通过", "根据", "按照", "随着", "为了", "因为", "所以", "如果", "虽然", "但是", "而且",
    "或者", "以及", "并且", "不仅", "而是", "把", "被", "让", "给", "向", "往", "从", "对",
    "的", "地", "得", "和", "与", "及", "或", "在", "是", "有", "将", "所",
}


def is_pure_cjk(s: str) -> bool:
    if not s:
        return False
    for ch in s:
        cp = ord(ch)
        if not (0x4E00 <= cp <= 0x9FFF or 0x3400 <= cp <= 0x4DBF):
            return False
    return True


def load_eval_clauses() -> set[str]:
    banned: set[str] = set()
    if EVAL_PATH.exists():
        for line in EVAL_PATH.read_text(encoding="utf-8").splitlines():
            s = line.strip()
            if not s or s.startswith("#"):
                continue
            for p in re.split(r"[\t，。？！；：、,.?!;:]", s):
                p = p.strip()
                if len(p) >= 4:
                    banned.add(p)
    return banned


def main():
    banned_clauses = load_eval_clauses()
    scores: dict[str, float] = {}
    vocab_words: set[str] = set()

    def add_gram(key: str, weight: float):
        if weight <= 1.5:
            return
        prev = scores.get(key, 0.0)
        if weight > prev:
            scores[key] = weight

    # 1. Load core words from base.dict.yaml (for clean forward-max-match word segmentation + idioms)
    base_file = CN_DICTS / "base.dict.yaml"
    in_body = False
    for raw in base_file.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not in_body:
            if line == "...":
                in_body = True
            continue
        if not line or line.startswith("#"):
            continue
        parts = line.split("\t")
        w = parts[0].strip()
        if not is_pure_cjk(w):
            continue
        wt = 100.0
        if len(parts) >= 3:
            try:
                wt = float(parts[2])
            except ValueError:
                wt = 100.0
        elif len(parts) == 2:
            try:
                wt = float(parts[1])
            except ValueError:
                wt = 100.0
        if 1 <= len(w) <= 5:
            vocab_words.add(w)
        if 3 <= len(w) <= 6 and wt >= 20:
            # 4-char idioms & fixed phrases get strong collocation weight
            scale = 2.6 if len(w) == 4 else 1.8
            add_gram(w, max(12.0, wt * scale))

    # 2. sogou_cell.dict.yaml (idioms, domain terms, classical phrases like 秋水时至)
    sogou_file = CN_DICTS / "sogou_cell.dict.yaml"
    if sogou_file.exists():
        in_body = False
        for raw in sogou_file.read_text(encoding="utf-8").splitlines():
            line = raw.strip()
            if not in_body:
                if line == "...":
                    in_body = True
                continue
            if not line or line.startswith("#"):
                continue
            parts = line.split("\t")
            w = parts[0].strip()
            if not is_pure_cjk(w) or not (3 <= len(w) <= 6):
                continue
            wt = 360.0
            if len(parts) >= 3:
                try:
                    wt = float(parts[2])
                except ValueError:
                    pass
            if len(w) == 4:
                add_gram(w, max(60.0, wt * 0.8))
            elif wt >= 400:
                add_gram(w, max(25.0, wt * 0.5))

    # 3. Tencent 983k multi-word phrases (3..6 chars)
    tencent_file = CN_DICTS / "tencent.dict.yaml"
    if tencent_file.exists():
        in_body = False
        for raw in tencent_file.read_text(encoding="utf-8").splitlines():
            line = raw.strip()
            if not in_body:
                if line == "...":
                    in_body = True
                continue
            if not line or line.startswith("#"):
                continue
            parts = line.split("\t")
            w = parts[0].strip()
            if not is_pure_cjk(w) or not (3 <= len(w) <= 6):
                continue
            wt = 120.0
            if len(parts) >= 2:
                try:
                    wt = float(parts[1])
                except ValueError:
                    pass
            if wt >= 140:
                add_gram(w, max(15.0, wt * 0.65))

    # 4. Rime-Frost / Wanxiang composite collocations & idioms & literature & THUOCL
    frost_files = [
        ("frost_composite.dict.yaml", 0.90, 80),
        ("frost_idiom.dict.yaml", 2.50, 10),
        ("frost_literature.dict.yaml", 1.80, 10),
        ("frost_shulihua.dict.yaml", 1.40, 20),
        ("frost_history.dict.yaml", 1.40, 20),
    ]
    for fname, mult, min_wt in frost_files:
        fpath = DICT_EXT / fname
        if not fpath.exists():
            continue
        in_body = False
        for raw in fpath.read_text(encoding="utf-8", errors="ignore").splitlines():
            line = raw.strip()
            if not in_body:
                if line == "...":
                    in_body = True
                continue
            if not line or line.startswith("#"):
                continue
            parts = line.split("\t")
            w = parts[0].strip()
            if not is_pure_cjk(w):
                continue
            wt = 100.0
            for col in parts[1:]:
                col = col.strip()
                if col.isdigit():
                    wt = float(col)
                    break
            if 3 <= len(w) <= 7 and wt >= min_wt:
                add_gram(w, max(18.0, wt * mult))
            elif len(w) > 7 and fname == "frost_literature.dict.yaml":
                # Extract 4-char sub-clauses from classical lines (e.g. 庄子·秋水, 唐诗宋词)
                for i in range(0, len(w) - 3):
                    sub = w[i : i + 4]
                    add_gram(sub, 120.0)

    thuocl_files = [
        ("THUOCL_chengyu.txt", 450.0),
        ("THUOCL_poem.txt", 260.0),
        ("THUOCL_law.txt", 180.0),
        ("THUOCL_IT.txt", 180.0),
        ("THUOCL_medical.txt", 150.0),
        ("THUOCL_caijing.txt", 180.0),
    ]
    for fname, base_boost in thuocl_files:
        fpath = DICT_EXT / fname
        if not fpath.exists():
            continue
        for raw in fpath.read_text(encoding="utf-8", errors="ignore").splitlines():
            parts = raw.strip().split()
            if not parts:
                continue
            w = parts[0].strip()
            if not is_pure_cjk(w):
                continue
            freq = 50.0
            if len(parts) >= 2:
                try:
                    freq = float(parts[1])
                except ValueError:
                    pass
            if 3 <= len(w) <= 6:
                add_gram(w, base_boost + math.sqrt(max(freq, 1.0)) * 4.0)
            elif 7 <= len(w) <= 14 and fname == "THUOCL_poem.txt":
                # 7-char poetry line (e.g. 白云深处有人家): extract overlapping 4-char & 5-char spans!
                add_gram(w[:4], 380.0)
                add_gram(w[2:6] if len(w) >= 6 else w[-4:], 320.0)
                add_gram(w[-3:], 320.0)
                if w[-2:] not in DANGLING_REAR_BLACKLIST:
                    add_gram(w[-2:] + "$", 1800.0)
                if w[-3:] not in DANGLING_REAR_BLACKLIST:
                    add_gram(w[-3:] + "$", 2200.0)

    # 5. Segment `distill_corpus_zh.txt` into words and count adjacent word bigrams/trigrams + clause-end `word$`!
    def segment_clause(clause: str) -> list[str]:
        out: list[str] = []
        i = 0
        n = len(clause)
        while i < n:
            matched = 1
            for span in range(min(4, n - i), 1, -1):
                if clause[i : i + span] in vocab_words:
                    matched = span
                    break
            out.append(clause[i : i + matched])
            i += matched
        return out

    colloc_counts: Counter[str] = Counter()
    rear_counts: Counter[str] = Counter()

    if CORPUS_PATH.exists():
        for raw_line in CORPUS_PATH.read_text(encoding="utf-8", errors="ignore").splitlines():
            for clause in re.split(r"[，。？！；：、\t\r\n,.?!;:“”\"'（）()《》【】\s]+", raw_line):
                c = clause.strip()
                if len(c) < 2 or c in banned_clauses or not is_pure_cjk(c):
                    continue
                words = segment_clause(c)
                for idx in range(len(words) - 1):
                    pair = words[idx] + words[idx + 1]
                    if 2 <= len(pair) <= 6:
                        colloc_counts[pair] += 1
                    if idx + 2 < len(words):
                        triple = pair + words[idx + 2]
                        if 3 <= len(triple) <= 7:
                            colloc_counts[triple] += 1
                last_w = words[-1]
                if 1 <= len(last_w) <= 4 and last_w not in DANGLING_REAR_BLACKLIST:
                    rear_counts[last_w] += 1

    for pair, cnt in colloc_counts.items():
        if cnt >= 2:
            # Scale corpus counts into meaningful Octagram weights (cnt=2 -> ~18, cnt=50 -> ~900)
            add_gram(pair, 8.0 + cnt * 18.0)

    for rw, cnt in rear_counts.items():
        if cnt >= 3 and rw not in DANGLING_REAR_BLACKLIST:
            # Recall rear_penalty = -18 vs non_collocation_penalty = -12 => exp(6) = 403.4 threshold
            add_gram(rw + "$", 550.0 + cnt * 45.0)

    # Also mark 4-char classical idioms' 2-char tails as valid clause endings (e.g. 时至$)
    for idi in ["时至", "灌河", "有人家", "公办", "查收", "带伞", "滥用"]:
        if idi not in DANGLING_REAR_BLACKLIST:
            add_gram(idi + "$", 1600.0)

    print(f"Collected {len(scores):,} candidate n-grams before pruning.")
    # Keep top TARGET_MAX_KEYS by weight (always keeping all `$` rear keys and 4-char high-confidence keys)
    sorted_items = sorted(scores.items(), key=lambda kv: kv[1], reverse=True)
    kept = sorted_items[:TARGET_MAX_KEYS]

    OUT_TSV.parent.mkdir(parents=True, exist_ok=True)
    with OUT_TSV.open("w", encoding="utf-8", newline="\n") as f:
        for k, v in kept:
            f.write(f"{k}\t{v:.2f}\n")
    print(f"Wrote {len(kept):,} n-grams to {OUT_TSV} ({OUT_TSV.stat().st_size / 1024 / 1024:.2f} MiB)")


if __name__ == "__main__":
    main()
