#!/usr/bin/env bash
# =============================================================================
# deploy_adb.sh  — Install APK + push models to device via ADB
# Run from the main/ directory: bash scripts/deploy_adb.sh [debug|release]
# =============================================================================
set -euo pipefail

VARIANT="${1:-debug}"
ANDROID_DIR="$(cd "$(dirname "$0")/../android" && pwd)"
MODELS_DIR="$(cd "$(dirname "$0")/../models" && pwd)"
PACKAGE="com.main.agent"
DEVICE_MODELS="/sdcard/Android/data/$PACKAGE/files/models"

# Check ADB
if ! command -v adb &>/dev/null; then
  echo "✗ adb not found. Install Android Platform Tools."
  exit 1
fi

# Check device connected
DEVICE_COUNT=$(adb devices | tail -n +2 | grep -c "device" || true)
if [ "$DEVICE_COUNT" -eq 0 ]; then
  echo "✗ No Android device detected. Connect via USB and enable ADB."
  exit 1
fi
echo "✓ Device connected"

# ── Install APK ───────────────────────────────────────────────────────────────
if [ "$VARIANT" = "release" ]; then
  APK="$ANDROID_DIR/app/build/outputs/apk/release/app-release.apk"
else
  APK="$ANDROID_DIR/app/build/outputs/apk/debug/app-debug.apk"
fi

if [ ! -f "$APK" ]; then
  echo "✗ APK not found at $APK"
  echo "  Build first: bash scripts/build_android.sh $VARIANT"
  exit 1
fi

echo "→ Installing APK…"
adb install -r "$APK"

# ── Push models ───────────────────────────────────────────────────────────────
echo "→ Creating model directory on device…"
adb shell mkdir -p "$DEVICE_MODELS"

for model_file in "$MODELS_DIR"/*.gguf "$MODELS_DIR"/*.bin; do
  [ -f "$model_file" ] || continue
  filename=$(basename "$model_file")
  size_mb=$(( $(stat -c%s "$model_file" 2>/dev/null || stat -f%z "$model_file") / 1048576 ))
  echo "  Pushing $filename ($size_mb MB)…"
  adb push "$model_file" "$DEVICE_MODELS/$filename"
done

echo ""
echo "✓ Deployed $VARIANT build to device."
echo "  Launch: adb shell am start -n $PACKAGE/.MainActivity"
adb shell am start -n "$PACKAGE/.MainActivity"
