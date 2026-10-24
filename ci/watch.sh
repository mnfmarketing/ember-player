#!/usr/bin/env bash
# Galaxy Watch app on a Wear OS emulator: installs it and screenshots its screens.
# There's no phone paired here, so the main screens use the app's demo mode.
set -x
APK="$1"
PKG=app.ember.audiobooks
ACT=$PKG/app.ember.wear.MainActivity
OUT=watch-out
mkdir -p "$OUT"
exec > >(tee "$OUT/run.txt") 2>&1
ADB_BIN="$(command -v adb)"
adb() { timeout 90 "$ADB_BIN" "$@"; }
shot() {
  sleep "${2:-4}"
  adb exec-out screencap -p > "$OUT/$1.png"
}
adb logcat -c
adb install -r "$APK" || exit 1
adb shell input keyevent KEYCODE_WAKEUP
adb shell am start -S -n "$ACT" --ez demo true
shot w1-now-playing 8
adb shell am start -S -n "$ACT" --ez demo true --es screen sleep
shot w2-sleep-timer 6
# Without a phone: it should say so rather than hang.
adb shell am start -S -n "$ACT"
shot w3-no-phone 12
adb shell dumpsys window | grep -E "mCurrentFocus" > "$OUT/focus.txt" || true
adb logcat -d > "$OUT/logcat.txt"
grep -E "FATAL EXCEPTION|AndroidRuntime: (FATAL|Process)" -A 25 "$OUT/logcat.txt" > "$OUT/crashes.txt" || true
ls -la "$OUT"
