#!/usr/bin/env bash
# Downloads sherpa-onnx Android runtime (Apache-2.0) and the compact 20 MiB streaming
# Zipformer CTC Chinese/English byte-BPE int8 ASR model into third_party/ and assets/asr/.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SHERPA_VER="v1.13.8"
MODEL_NAME="sherpa-onnx-streaming-zipformer-small-ctc-zh-int8-2025-04-01"

mkdir -p "$ROOT/third_party/sherpa-onnx" "$ROOT/tools/data/asr" "$ROOT/app/src/main/assets/asr"

curl -L "https://github.com/k2-fsa/sherpa-onnx/releases/download/${SHERPA_VER}/sherpa-onnx-static-link-onnxruntime-${SHERPA_VER#v}.aar" \
  -o "$ROOT/third_party/sherpa-onnx/sherpa-onnx.aar"

curl -L "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/${MODEL_NAME}.tar.bz2" \
  -o "$ROOT/tools/data/asr/${MODEL_NAME}.tar.bz2"

tar -xjf "$ROOT/tools/data/asr/${MODEL_NAME}.tar.bz2" -C "$ROOT/tools/data/asr"
cp "$ROOT/tools/data/asr/${MODEL_NAME}/model.int8.onnx" "$ROOT/app/src/main/assets/asr/model.int8.onnx"
cp "$ROOT/tools/data/asr/${MODEL_NAME}/tokens.txt" "$ROOT/app/src/main/assets/asr/tokens.txt"
echo "sherpa-onnx ${SHERPA_VER} + ${MODEL_NAME} ready."
