#!/usr/bin/env bash
# Idempotent Cloud Agent bootstrap for the SMSpend Android app.
# Installs the Android SDK (if missing), wires it up via local.properties,
# and warms the Gradle cache by assembling the debug APK.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SDK_ROOT="${ANDROID_SDK_ROOT:-$HOME/android-sdk}"
CMDLINE_TOOLS_VERSION="13114758"
PLATFORM="android-36"
BUILD_TOOLS="35.0.0"

echo "==> SMSpend install: repo=$ROOT sdk=$SDK_ROOT"

install_cmdline_tools() {
  if [[ -x "$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" ]]; then
    echo "==> cmdline-tools already present"
    return
  fi
  echo "==> Downloading Android command-line tools ($CMDLINE_TOOLS_VERSION)"
  local zip="/tmp/cmdline-tools-${CMDLINE_TOOLS_VERSION}.zip"
  curl -fsSL -o "$zip" \
    "https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_VERSION}_latest.zip"
  rm -rf "$SDK_ROOT/cmdline-tools/tmp"
  mkdir -p "$SDK_ROOT/cmdline-tools/tmp"
  unzip -q -o "$zip" -d "$SDK_ROOT/cmdline-tools/tmp"
  rm -rf "$SDK_ROOT/cmdline-tools/latest"
  mv "$SDK_ROOT/cmdline-tools/tmp/cmdline-tools" "$SDK_ROOT/cmdline-tools/latest"
  rm -rf "$SDK_ROOT/cmdline-tools/tmp" "$zip"
}

install_cmdline_tools

SDKMANAGER="$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager"

echo "==> Accepting SDK licenses"
yes | "$SDKMANAGER" --sdk_root="$SDK_ROOT" --licenses >/dev/null 2>&1 || true

echo "==> Installing SDK packages (platform-tools, platforms;$PLATFORM, build-tools;$BUILD_TOOLS)"
"$SDKMANAGER" --sdk_root="$SDK_ROOT" \
  "platform-tools" \
  "platforms;$PLATFORM" \
  "build-tools;$BUILD_TOOLS" >/dev/null

echo "==> Writing local.properties"
printf 'sdk.dir=%s\n' "$SDK_ROOT" > "$ROOT/local.properties"

echo "==> Warming Gradle cache (assembleDebug + unit test classes)"
cd "$ROOT"
./gradlew --no-daemon :app:assembleDebug :app:compileDebugUnitTestKotlin

echo "==> Install complete. Debug APK:"
ls -la "$ROOT/app/build/outputs/apk/debug/app-debug.apk"
