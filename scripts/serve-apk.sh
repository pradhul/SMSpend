#!/usr/bin/env bash
# Bump versionCode, assemble debug APK, serve on LAN, print QR.
# Usage: ./scripts/serve-apk.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
GRADLE_KTS="$ROOT/app/build.gradle.kts"
SERVE_DIR="$ROOT/.apk-serve"
PORT="${APK_SERVE_PORT:-8765}"

if [[ -z "${JAVA_HOME:-}" && -d "$ROOT/.jdks/jdk-17.0.20+8/Contents/Home" ]]; then
  export JAVA_HOME="$ROOT/.jdks/jdk-17.0.20+8/Contents/Home"
fi
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"

current="$(grep -Eo 'versionCode[[:space:]]*=[[:space:]]*[0-9]+' "$GRADLE_KTS" | head -1 | grep -Eo '[0-9]+')"
next=$((current + 1))
name="$(grep -Eo 'versionName[[:space:]]*=[[:space:]]*"[^"]+"' "$GRADLE_KTS" | head -1 | sed -E 's/.*"([^"]+)".*/\1/')"
maj="$(echo "$name" | cut -d. -f1)"
min="$(echo "$name" | cut -d. -f2)"
patch="$(echo "$name" | cut -d. -f3)"
patch="${patch:-0}"
patch=$((patch + 1))
new_name="${maj}.${min}.${patch}"

perl -i -pe "s/versionCode\\s*=\\s*\\d+/versionCode = ${next}/" "$GRADLE_KTS"
perl -i -pe "s/versionName\\s*=\\s*\"[^\"]+\"/versionName = \"${new_name}\"/" "$GRADLE_KTS"

echo "Building SMSpend ${new_name} (versionCode ${next})…"
(cd "$ROOT" && ./gradlew :app:assembleDebug --quiet)

mkdir -p "$SERVE_DIR"
APK_NAME="smspend-v${next}.apk"
cp "$ROOT/app/build/outputs/apk/debug/app-debug.apk" "$SERVE_DIR/$APK_NAME"
cp "$ROOT/app/build/outputs/apk/debug/app-debug.apk" "$SERVE_DIR/app-debug.apk"
echo "${next}" > "$SERVE_DIR/versionCode.txt"
echo "${new_name}" > "$SERVE_DIR/versionName.txt"

IP="$(ipconfig getifaddr en0 2>/dev/null || ipconfig getifaddr en1 2>/dev/null || true)"
if [[ -z "${IP:-}" ]]; then
  IP="$(ifconfig 2>/dev/null | awk '/inet / && $2 != "127.0.0.1" {print $2; exit}')"
fi
URL="http://${IP}:${PORT}/${APK_NAME}"
echo "$URL" > "$SERVE_DIR/url.txt"
qrencode -t PNG -o "$SERVE_DIR/qr.png" "$URL"
qrencode -t ANSIUTF8 "$URL"

if lsof -ti tcp:"$PORT" >/dev/null 2>&1; then
  echo "Stopping previous APK server(s) on port ${PORT}..."
  # Kill every listener on the serve port (prior python http.server instances).
  for pid in $(lsof -ti tcp:"$PORT" 2>/dev/null || true); do
    kill "$pid" 2>/dev/null || true
  done
  sleep 0.5
  # Force if still held
  for pid in $(lsof -ti tcp:"$PORT" 2>/dev/null || true); do
    kill -9 "$pid" 2>/dev/null || true
  done
  sleep 0.2
fi
(
  cd "$SERVE_DIR"
  nohup python3 -c "
from http.server import SimpleHTTPRequestHandler, HTTPServer
class Handler(SimpleHTTPRequestHandler):
    extensions_map = {**SimpleHTTPRequestHandler.extensions_map, '.apk': 'application/vnd.android.package-archive'}
    def end_headers(self):
        self.send_header('Access-Control-Allow-Origin', '*')
        self.send_header('Cache-Control', 'no-store')
        super().end_headers()
HTTPServer(('0.0.0.0', ${PORT}), Handler).serve_forever()
" >/dev/null 2>&1 &
)

echo ""
echo "Served: $URL"
echo "QR:     $SERVE_DIR/qr.png"
echo "Build:  ${new_name} (code ${next})"
open "$SERVE_DIR/qr.png" 2>/dev/null || true
