#!/usr/bin/env bash
# =============================================================================
# build_android.sh  — Build the Android APK using Gradle
# Run from the main/ directory: bash scripts/build_android.sh [debug|release]
# =============================================================================
set -euo pipefail

VARIANT="${1:-debug}"
ANDROID_DIR="$(cd "$(dirname "$0")/../android" && pwd)"

# Sanity checks
if [ ! -d "$ANDROID_DIR/app/src/main/cpp/third_party/llama.cpp" ]; then
  echo "✗ llama.cpp not found. Run: bash scripts/setup.sh"
  exit 1
fi

if [ -z "${ANDROID_HOME:-}" ] && [ -z "${ANDROID_SDK_ROOT:-}" ]; then
  echo "✗ ANDROID_HOME / ANDROID_SDK_ROOT not set."
  echo "  Set it to your Android SDK path (e.g. ~/Android/Sdk)"
  exit 1
fi

echo "→ Building $VARIANT APK…"
cd "$ANDROID_DIR"

if [ "$VARIANT" = "release" ]; then
  ./gradlew assembleRelease --no-daemon --parallel
  APK_PATH="app/build/outputs/apk/release/app-release.apk"
else
  ./gradlew assembleDebug --no-daemon --parallel
  APK_PATH="app/build/outputs/apk/debug/app-debug.apk"
fi

echo ""
echo "✓ Build successful!"
echo "  APK: $ANDROID_DIR/$APK_PATH"
