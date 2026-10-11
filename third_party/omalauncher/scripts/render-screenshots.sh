#!/usr/bin/env bash
# Renders store/screenshots/frame.html (captions + raw/ captures) into the
# Play listing's phone screenshots, 1080x1920 each.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CHROME="${CHROME:-/Applications/Google Chrome.app/Contents/MacOS/Google Chrome}"
OUT="$ROOT/fastlane/metadata/android/en-US/images/phoneScreenshots"
COUNT=8

mkdir -p "$OUT"
rm -f "$OUT"/*.png
for i in $(seq 1 $COUNT); do
  file=$(printf '%s/%02d.png' "$OUT" "$i")
  "$CHROME" --headless=new --hide-scrollbars --force-device-scale-factor=1 \
    --window-size=1080,1920 --virtual-time-budget=8000 \
    --screenshot="$file" "file://$ROOT/store/screenshots/frame.html?s=$i" 2>/dev/null
  echo "$file"
done
