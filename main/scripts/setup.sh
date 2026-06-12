#!/usr/bin/env bash
# =============================================================================
# setup.sh  — Clone llama.cpp and whisper.cpp into the NDK build tree
# Run from the main/ directory: bash scripts/setup.sh
# =============================================================================
set -euo pipefail

# ── Config ───────────────────────────────────────────────────────────────────
LLAMA_TAG="b9530"          # Pin to a stable llama.cpp release
WHISPER_TAG="v1.7.4"       # Pin to a stable whisper.cpp release

LLAMA_DIR="android/app/src/main/cpp/third_party/llama.cpp"
WHISPER_DIR="android/app/src/main/cpp/third_party/whisper.cpp"

# ── llama.cpp ────────────────────────────────────────────────────────────────
if [ ! -d "$LLAMA_DIR/.git" ]; then
  echo "→ Cloning llama.cpp at tag $LLAMA_TAG …"
  git clone --depth 1 --branch "$LLAMA_TAG" \
    https://github.com/ggerganov/llama.cpp "$LLAMA_DIR"
else
  echo "✓ llama.cpp already present at $LLAMA_DIR"
fi

# ── whisper.cpp (Phase 3) ─────────────────────────────────────────────────────
if [ ! -d "$WHISPER_DIR/.git" ]; then
  echo "→ Cloning whisper.cpp at tag $WHISPER_TAG …"
  git clone --depth 1 --branch "$WHISPER_TAG" \
    https://github.com/ggerganov/whisper.cpp "$WHISPER_DIR"
else
  echo "✓ whisper.cpp already present at $WHISPER_DIR"
fi

echo ""
echo "✓ Setup complete.  Next steps:"
echo "  1. Download models:  bash models/download.sh"
echo "  2. Build APK:        bash scripts/build_android.sh"
echo "  3. Deploy to device: bash scripts/deploy_adb.sh"
