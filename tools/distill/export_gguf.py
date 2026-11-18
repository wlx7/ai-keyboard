#!/usr/bin/env python3
"""
Exports the vocab-pruned & distilled Lingxi-MiniLM HuggingFace checkpoint to GGUF
(`q4_0`, `q4_0_pure`, `q4_m`, `q8_0`, or `f16`) in pure Python using `third_party/llama.cpp/gguf-py`.

  - Pins `tokenizer.ggml.pre = "qwen2"` so llama.cpp accepts the custom pruned vocabulary.
  - `q4_0` (default): uses `Q8_0` on `token_embd` / `output.weight` (near-lossless 8-bit lexical
    unembedding) and `Q4_0` on all Transformer matrices. Both `Q8_0` and `Q4_0` hit llama.cpp's
    `LLAMAFILE | REPACK` SIMD fast-path on ARM64 SDOT and x86 AVX2!
  - `q4_0_pure`: uses `Q4_0` on all 2D tensors including `token_embd` (~16 MB smaller).
  - `q8_0`: near-lossless 8-bit quantization across all 2D tensors.

Usage:
  tools/distill/.venv/Scripts/python.exe tools/distill/export_gguf.py \
      dist/lingxi-pro-hf dist/lingxi-pro-q4_0.gguf q4_0
"""

from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
LLAMA_DIR = ROOT / "third_party" / "llama.cpp"
sys.path.insert(0, str(LLAMA_DIR / "gguf-py"))
sys.path.insert(0, str(LLAMA_DIR))

import gguf  # noqa: E402
from conversion.base import TextModel  # noqa: E402
import convert_hf_to_gguf  # noqa: E402


def main():
    if len(sys.argv) < 3:
        print("Usage: export_gguf.py <hf_dir> <out_gguf> [q4_0|q4_0_pure|q4_m|q8_0|f16]")
        sys.exit(1)
    hf_dir = sys.argv[1]
    out_gguf = sys.argv[2]
    out_type = sys.argv[3].lower() if len(sys.argv) >= 4 else "q4_0"

    # Pin pre-tokenizer to "qwen2"
    TextModel.get_vocab_base_pre = lambda self, _tok: "qwen2"

    cli_type = out_type
    if out_type == "q4_0":
        cli_type = "q8_0"

        def force_q40_emb8(self, name: str, new_name: str, bid: int | None, n_dims: int):
            if n_dims < 2:
                return False
            if any(k in new_name for k in ("token_embd", "output.weight")):
                return gguf.GGMLQuantizationType.Q8_0
            return gguf.GGMLQuantizationType.Q4_0

        TextModel.tensor_force_quant = force_q40_emb8
    elif out_type == "q4_0_pure":
        cli_type = "q8_0"

        def force_q40_pure(self, name: str, new_name: str, bid: int | None, n_dims: int):
            if n_dims < 2:
                return False
            return gguf.GGMLQuantizationType.Q4_0

        TextModel.tensor_force_quant = force_q40_pure
    elif out_type in ("q4_m", "q4_k_m", "q4km"):
        cli_type = "q8_0"

        def force_q4m(self, name: str, new_name: str, bid: int | None, n_dims: int):
            if n_dims < 2:
                return False
            if any(k in new_name for k in ("token_embd", "output.weight", "attn_v", "ffn_down")):
                return gguf.GGMLQuantizationType.Q5_1
            return gguf.GGMLQuantizationType.Q4_1

        TextModel.tensor_force_quant = force_q4m

    sys.argv = [
        "convert_hf_to_gguf.py",
        hf_dir,
        "--outfile",
        out_gguf,
        "--outtype",
        cli_type,
    ]
    convert_hf_to_gguf.main()


if __name__ == "__main__":
    main()
