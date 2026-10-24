#!/usr/bin/env bash
# The focused emulator check (about 20 minutes instead of 70): installs the build fresh with
# the demo library, then runs only ci/focus.sh, the steps for what changed in this batch.
# The full run (smoke.sh) is kept for once right before a release.
set -x
APK="$1"
OUT=smoke-out
mkdir -p "$OUT"
exec > >(tee "$OUT/run.txt") 2>&1
source "$(dirname "$0")/lib.sh"

adb shell settings put global hide_error_dialogs 1
adb shell settings put secure anr_show_background 0
adb install -r "$APK" || exit 1
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS || true
seed_files
adb shell am force-stop $PKG
adb shell am start -W -n "$ACT" --ez ember_demo true
sleep 30
adb logcat -c

# Always: the main screens still open.
open_route home;    shot q01-home 5
open_route library; shot q02-library 4
adb shell am start -n "$ACT" --es ember_play demo-0 > /dev/null; sleep 5
open_route player;  shot q03-player 4

# This batch's checks.
source "$(dirname "$0")/focus.sh"

selfcheck quick-end.json
finish_logs
exit 0
