#!/usr/bin/env bash
# Fetches Android static libraries for librime and its dependencies from
# fcitx5-android/prebuilt (built with NDK 28.0.13004108) into third_party/prebuilt.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$ROOT/third_party/prebuilt"
REV="${PREBUILT_REV:-3f987c6}"   # pinned; bump deliberately
rm -rf "$DEST"
git clone -q --filter=blob:none --no-checkout https://github.com/fcitx5-android/prebuilt.git "$DEST"
cd "$DEST"
git sparse-checkout init --no-cone
{
  echo /toolchain-versions.json
  for lib in librime boost glog leveldb marisa opencc yaml-cpp lua; do
    for abi in arm64-v8a x86_64; do echo "/$lib/$abi/"; done
  done
} > .git/info/sparse-checkout
git checkout -q "$REV"
echo "prebuilt @ $(git rev-parse --short HEAD)"; cat toolchain-versions.json
