#!/usr/bin/env bash
# =============================================================================
# download.sh  — Download GGUF model files with checksum verification
# Run from the main/ directory: bash models/download.sh
# =============================================================================
set -euo pipefail

MODELS_DIR="$(cd "$(dirname "$0")" && pwd)"
CHECKSUMS="$MODELS_DIR/checksums.sha256"

# ── Download helper ───────────────────────────────────────────────────────────
download() {
  local name="$1" url="$2" file="$MODELS_DIR/$name"
  if [ -f "$file" ]; then
    echo "✓ $name already exists ($(du -sh "$file" | cut -f1))"
    return
  fi
  echo "→ Downloading $name …"
  curl -L --progress-bar -o "$file.tmp" "$url"
  mv "$file.tmp" "$file"
  echo "✓ Saved: $file"
}

# ── Verify checksums ──────────────────────────────────────────────────────────
verify_checksums() {
  if [ -f "$CHECKSUMS" ]; then
    echo "→ Verifying checksums…"
    (cd "$MODELS_DIR" && sha256sum -c "$CHECKSUMS" --ignore-missing)
    echo "✓ All checksums OK"
  fi
}

echo "=== Agent Model Downloader ==="
echo ""

# ── Models ────────────────────────────────────────────────────────────────────

# Llama 3.1 8B Instruct Q4_K_M  — ~4.9 GB — TIER_HIGH devices (Redmi Note 13 Pro+)
# Uncomment if your device has 8 GB+:
# download \
#   "Meta-Llama-3.1-8B-Instruct-Q4_K_M.gguf" \
#   "https://huggingface.co/bartowski/Meta-Llama-3.1-8B-Instruct-GGUF/resolve/main/Meta-Llama-3.1-8B-Instruct-Q4_K_M.gguf"

# Qwen 2.5 1.5B Instruct Q4_K_M  — ~1 GB — default model (SMALL tier, all devices)
download \
  "Qwen2.5-1.5B-Instruct-Q4_K_M.gguf" \
  "https://huggingface.co/bartowski/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/Qwen2.5-1.5B-Instruct-Q4_K_M.gguf"

# Whisper base.en  — 142 MB — Phase 3 (STT)
download \
  "ggml-base.en.bin" \
  "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.en.bin"

verify_checksums

echo ""
echo "=== Download complete ==="
echo "Models are in: $MODELS_DIR"
echo "Next: bash scripts/build_android.sh && bash scripts/deploy_adb.sh"
