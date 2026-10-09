#!/usr/bin/env bash
# Emulator regression for ChatLens (required step before every release, see PLAN.md).
# Usage: tools/emulator_regression.sh [--apis "34"] [--apk PATH] [--only S1,S2] [--out DIR]
# Builds ChatLens and the FakeWA stand-in (if --apk is missing), creates an AVD for each Android version (google_apis, x86_64, no Play),
# starts it headless, runs tools/emu_ui.py, and writes a Markdown report with screenshots for each version.
# Only one emulator at a time. Environment: EMU_ACCEL=off (default) or on, EMU_BOOT_LIMIT (seconds).
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
source "$HERE/emu_lib.sh"
APIS="33 34 35 36"; APK=""; ONLY=""; OUTDIR=""
while [ $# -gt 0 ]; do case $1 in
  --apis) APIS=$2; shift 2;; --apk) APK=$2; shift 2;; --only) ONLY=$2; shift 2;; --out) OUTDIR=$2; shift 2;; *) echo "unknown: $1"; exit 2;; esac; done
VERSION=$(grep -oP 'versionName = "\K[^"]+' "$ROOT/app/build.gradle.kts")
OUTDIR=${OUTDIR:-$ROOT/reports/emulator-$VERSION}
mkdir -p "$OUTDIR"
if [ -z "$APK" ]; then
  (cd "$ROOT" && ./gradlew --no-daemon -q :app:assembleFullDebug) || exit 1
  # from 0.3.0 there are two flavors; the regression runs with full (S4 needs API mode for the mock server)
  APK="$ROOT/app/build/outputs/apk/full/debug/app-full-debug.apk"
fi
(cd "$ROOT" && ./gradlew --no-daemon -q :fakewa:assembleDebug) || exit 1
FAKE="$ROOT/fakewa/build/outputs/apk/debug/fakewa-debug.apk"
SUM="$OUTDIR/summary.md"
{ echo "# Emulator regression ChatLens $VERSION"; echo; echo "Date: $(date '+%d.%m.%Y %H:%M') (Berlin). Acceleration: $EMU_ACCEL. APK: $(basename "$APK")."; echo
  echo "| Android | API | Start until boot | Scenarios passed | Failures | Crash or ANR |"; echo "|---|---|---|---|---|---|"; } > "$SUM"
for api in $APIS; do
  d="$OUTDIR/api$api"; mkdir -p "$d"
  create_avd "$api"
  echo "== API $api: starting emulator"
  secs=$(start_emulator "$api") || { echo "| $(android_name $api) | $api | start failed | 0 | 0 | unknown |" >> "$SUM"; stop_emulator; continue; }
  echo "Start bis sys.boot_completed: ${secs} s"
  sleep 20
  adb -s $SER shell input keyevent KEYCODE_WAKEUP; adb -s $SER shell wm dismiss-keyguard
  adb -s $SER shell settings put global window_animation_scale 0; adb -s $SER shell settings put global transition_animation_scale 0; adb -s $SER shell settings put global animator_duration_scale 0
  SER=$SER python3 "$HERE/emu_ui.py" --api "$api" --out "$d" --chatlens "$APK" --fakewa "$FAKE" ${ONLY:+--only $ONLY} 2>&1 | tee "$d/run.log"
  adb -s $SER logcat -d > "$d/logcat.txt" 2>/dev/null
  python3 "$HERE/emu_report.py" "$d" "$api" "$(android_name $api)" "$secs" "$VERSION" "$EMU_ACCEL" >> "$SUM"
  stop_emulator
done
echo "Report: $SUM"
