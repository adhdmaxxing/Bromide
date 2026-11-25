#!/usr/bin/env bash
# Renders the Play Store screenshots into fastlane/metadata/android/en-US/images/.
#
# The photos go through Bromide's own engine (Render.java), so every before/after is
# real Bromide output. Put your own shots in tools/store/photos/ to replace the
# built-in samples (your camera's JPEGs, or RAW exported to JPEG without edits):
#   night.jpg  lights at night (the hero split and the halation close-ups)
#   still.jpg  a still life or detail (the "how it works" slide)
#   colors.jpg varied colors and skin tones (the film grid)
# Framed app screenshots: put phone captures in tools/store/shots/ (home.png, film.png...).
#
# Needs a JDK (javac/java) and Chrome. Usage: tools/store/render.sh
set -euo pipefail
cd "$(dirname "$0")"
ROOT=../..
OUT="$ROOT/fastlane/metadata/android/en-US/images"
CHROME=${CHROME:-"/c/Program Files/Google/Chrome/Application/chrome.exe"}
[ -x "$CHROME" ] || CHROME=$(command -v google-chrome || command -v chromium)

mkdir -p out build "$OUT/phoneScreenshots"
javac -d build "$ROOT"/app/src/main/java/com/bromide/engine/{FilmEngine,Lut,Stock}.java Render.java

photo() { [ -f "photos/$1.jpg" ] && echo "photos/$1.jpg" || echo "$ROOT/app/src/main/assets/samples/$2.jpg"; }
java -cp build Render "$ROOT/app/src/main/assets/luts" "$(photo night dusk)" out/night 2048 cinestill800t portra400
java -cp build Render "$ROOT/app/src/main/assets/luts" "$(photo still still)" out/still 1200 portra400
java -cp build Render "$ROOT/app/src/main/assets/luts" "$(photo colors portrait)" out/colors 1000 \
    portra400 gold200 pro400h kodachrome64 velvia100 trix400

shoot() {  # shoot OUTPUT HASH
  "$CHROME" --headless=new --disable-gpu --hide-scrollbars --allow-file-access-from-files \
    --force-device-scale-factor=1 --window-size=1080,1920 --screenshot="$(cygpath -w "$1" 2>/dev/null || echo "$1")" \
    "file:///$(cygpath -m "$PWD" 2>/dev/null || echo "$PWD")/slides.html#$2" 2>/dev/null
}
for n in 1 2 3 4 5; do shoot "$OUT/phoneScreenshots/0$n.png" "n=$n"; done
i=6
for s in shots/*.png; do
  [ -f "$s" ] || continue
  shoot "$OUT/phoneScreenshots/0$i.png" "n=ui&shot=$s&title=$(basename "$s" .png)"
  i=$((i + 1))
done

# Feature graphic: the banner's wordmark, plus the one-line pitch.
"$CHROME" --headless=new --disable-gpu --hide-scrollbars --allow-file-access-from-files \
  --force-device-scale-factor=1 --window-size=1024,500 --screenshot="$(cygpath -w "$OUT/featureGraphic.png" 2>/dev/null || echo "$OUT/featureGraphic.png")" \
  "file:///$(cygpath -m "$PWD" 2>/dev/null || echo "$PWD")/feature.html" 2>/dev/null
echo "Done: $OUT"
