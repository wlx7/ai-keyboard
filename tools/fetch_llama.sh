#!/usr/bin/env bash
# Fetches llama.cpp (MIT) source into third_party/llama.cpp at a pinned commit.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
REV="${LLAMA_REV:-b1b770d90d94cad9e9ea5b993bdbc383a522fb28}"   # v1.0.0, 2026-11-13
rm -rf "$ROOT/third_party/llama.cpp"
git clone -q https://github.com/ggml-org/llama.cpp.git "$ROOT/third_party/llama.cpp"
git -C "$ROOT/third_party/llama.cpp" checkout -q "$REV"
echo "llama.cpp @ $(git -C "$ROOT/third_party/llama.cpp" rev-parse --short HEAD)"
