#!/usr/bin/env bash
# Renders every widget in the widget store in every bundled Omarchy theme, for the
# store's per-theme previews: widgets/<author>/<name>/previews/<theme>.webp, plus
# preview.png in the default theme for launchers that don't know about themes.
#
# Needs a debug build on an emulator (adb root) with Linux installed, so widgets
# that run commands show real data, and the packages they require (curl, jq,
# python, github-cli). Widgets that need an account show demo data instead; see
# store-previews-demo.sh. The launcher renders each tile off screen
# (WidgetEngine.previewThemes) and takes every theme's colors while it does.
#
#   scripts/store-previews.sh [store repo, default ../omarchy-widgets] [widget name...]
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
STORE="${1:-$HERE/../../omarchy-widgets}"
shift || true
export ANDROID_SERIAL="${ANDROID_SERIAL:-emulator-5554}"
DEFAULT_THEME=tokyo-night
APP=/data/data/dev.omalauncher/files/widgets
WORK="$(mktemp -d)"

adb root >/dev/null
adb wait-for-device
owner=$(adb shell stat -c %u:%g "$APP")
label=$(adb shell ls -Zd "$APP" | cut -d' ' -f1)

# Writes under a hidden name the launcher ignores, then renames it, so it sees the file once, complete.
put() {
  adb push "$1" /data/local/tmp/store-preview.owl >/dev/null
  adb shell "cp /data/local/tmp/store-preview.owl $APP/.$2 && chown $owner $APP/.$2 && chmod 600 $APP/.$2 && chcon $label $APP/.$2 && mv $APP/.$2 $APP/$2"
}

# Polls a condition on the device for up to $1 seconds.
wait_for() {
  local secs=$1
  shift
  for _ in $(seq "$secs"); do
    adb shell "$@" >/dev/null 2>&1 && return 0
    sleep 1
  done
  return 1
}

# The default preview as a 256-color PNG that keeps the tile's translucency (dithered when the
# tile is opaque, so photos don't band), scaled down if
# it's still over the store's 300 KB. Needs Pillow (python3 -m pip install pillow).
small_png() {
  python3 - "$1" "$2" <<'PY'
import os
import sys
from PIL import Image
src, out = sys.argv[1:]
im = Image.open(src).convert("RGBA")
scale = 1.0
while True:
    s = im if scale == 1 else im.resize((round(im.width * scale), round(im.height * scale)), Image.LANCZOS)
    if s.getextrema()[3][0] == 255:
        # opaque, like a photo: dithered, so gradients don't band
        rgb = s.convert("RGB")
        rgb.quantize(palette=rgb.quantize(256, method=Image.Quantize.MEDIANCUT), dither=Image.Dither.FLOYDSTEINBERG).save(out, optimize=True)
    else:
        s.quantize(256, method=Image.Quantize.FASTOCTREE).save(out, optimize=True)
    if os.path.getsize(out) <= 300 * 1024 or scale < 0.5:
        break
    scale *= 0.85
PY
}

# Runs store-previews-demo.sh in the guest (through the debug build's exec intent) and waits for it.
demo() {
  printf 'bash ~/.config/omalauncher/widgets/.demo.sh %s; touch ~/.config/omalauncher/widgets/.demo.done\n' "$1" > "$WORK/run.sh"
  put "$HERE/store-previews-demo.sh" .demo.sh
  put "$WORK/run.sh" .demo-run.sh
  adb shell "rm -f $APP/.demo.done"
  adb shell am start -n dev.omalauncher/.MainActivity --es exec "'bash .config/omalauncher/widgets/.demo-run.sh'" >/dev/null
  wait_for 60 "test -f $APP/.demo.done" || echo "demo $1 didn't finish" >&2
  adb shell "rm -f $APP/.demo.sh $APP/.demo-run.sh $APP/.demo.done"
}
demo on
trap 'demo off; rm -rf "$WORK"' EXIT

dirs=()
if [ $# -gt 0 ]; then
  for n in "$@"; do dirs+=("$(dirname "$(ls "$STORE"/widgets/*/"$n"/widget.owl)")"); done
else
  for f in "$STORE"/widgets/*/*/widget.owl; do dirs+=("$(dirname "$f")"); done
fi

for dir in "${dirs[@]}"; do
  name="store-$(basename "$dir").owl"
  hash=$(shasum -a 256 "$dir/widget.owl" | cut -d' ' -f1)
  echo "$(basename "$dir")"
  adb shell "rm -rf $APP/.preview/themes/$name.done"
  put "$dir/widget.owl" "$name"
  if ! wait_for 30 "grep -q $hash $APP/.check/$name.txt"; then
    echo "  didn't compile in time; skipped" >&2
    continue
  fi
  adb shell "echo > $APP/.check/$name.themes"
  if ! wait_for 120 "test -f $APP/.preview/themes/$name.done"; then
    echo "  no previews after 2 minutes; skipped" >&2
    adb shell "rm -f $APP/$name"
    continue
  fi
  rm -rf "$WORK/png" && mkdir -p "$WORK/png" "$dir/previews"
  for theme in $(adb shell "ls $APP/.preview/themes" | grep -v '\.done$'); do
    adb exec-out cat "$APP/.preview/themes/$theme/$name.png" > "$WORK/png/$theme.png"
    cwebp -quiet -q 80 "$WORK/png/$theme.png" -o "$dir/previews/$theme.webp"
  done
  small_png "$WORK/png/$DEFAULT_THEME.png" "$dir/preview.png"
  echo "  $(ls "$dir/previews" | wc -l | tr -d ' ') themes, $(du -sh "$dir/previews" | cut -f1)"
  adb shell "rm -f $APP/$name; rm -rf $APP/.preview/themes"
done
