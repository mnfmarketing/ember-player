#!/usr/bin/env bash
# Shared helpers for the emulator checks (smoke.sh = the full run, quick.sh = a focused run).
PKG=app.ember.audiobooks
ACT=$PKG/app.ember.ui.MainActivity
REPORTS=/sdcard/Download/EmberTest
OUT=${OUT:-smoke-out}
mkdir -p "$OUT"
# No single adb step may hang the whole run (a stuck one is cut off after 90 seconds).
ADB_BIN="$(command -v adb)"
adb() { timeout 90 "$ADB_BIN" "$@"; }

shot() {
  sleep "${2:-4}"
  adb exec-out screencap -p > "$OUT/$1.png"
}
open_route() {
  adb shell am start -n "$ACT" --es ember_route "$1" > /dev/null
}
selfcheck() {
  adb shell am start -n "$ACT" --es ember_selfcheck "$1" > /dev/null
  sleep 5
  adb pull "$REPORTS/$1" "$OUT/$1"
}
# Taps the first thing on screen whose text or description matches $1.
tap_text() {
  adb shell uiautomator dump /sdcard/ui.xml > /dev/null
  adb pull /sdcard/ui.xml /tmp/ui.xml > /dev/null
  python3 - "$@" <<'PY'
import re, sys, subprocess
want = sys.argv[1]
wl = want.lower()
xml = open('/tmp/ui.xml', encoding='utf-8').read()
hits = []
for m in re.finditer(r'<node [^>]*>', xml):
    n = m.group(0)
    t = re.search(r' text="([^"]*)"', n).group(1)
    d = re.search(r' content-desc="([^"]*)"', n).group(1)
    tl = t.lower()
    if wl.startswith('last:'):
        if tl == wl[5:] or d.lower() == wl[5:]:
            b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n)
            hits.append(((int(b.group(1)) + int(b.group(3))) // 2, (int(b.group(2)) + int(b.group(4))) // 2))
        continue
    if tl == wl or d.lower() == wl or tl.startswith(wl + ' (') or (wl.endswith('*') and tl.startswith(wl[:-1])):
        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n)
        hits.append(((int(b.group(1)) + int(b.group(3))) // 2, (int(b.group(2)) + int(b.group(4))) // 2))
if hits:
    # A trailing * means "starts with": take the last match (buttons sit below titles).
    x, y = hits[-1] if (wl.endswith('*') or wl.startswith('last:')) else hits[0]
    if len(sys.argv) > 2:
        # Long press: a swipe that stays put.
        subprocess.run(['adb', 'shell', 'input', 'swipe', str(x), str(y), str(x), str(y), sys.argv[2]])
    else:
        subprocess.run(['adb', 'shell', 'input', 'tap', str(x), str(y)])
    print(f"tapped {want} at {x},{y}")
else:
    print(f"could not find {want}")
PY
}
# The player's chapter slider: "tap" taps it (which should do nothing now), "swipe" drags it
# most of the way right, "hold" starts a drag and screenshots the bubble before letting go.
slider() {
  adb shell uiautomator dump /sdcard/ui.xml > /dev/null
  adb pull /sdcard/ui.xml /tmp/ui.xml > /dev/null
  python3 - "$1" <<'PY2'
import re, subprocess, sys, time
mode = sys.argv[1]
xml = open('/tmp/ui.xml', encoding='utf-8').read()
for m in re.finditer(r'<node [^>]*>', xml):
    n = m.group(0)
    t = re.search(r' text="([^"]*)"', n).group(1)
    if re.match(r'^-\d+:\d\d', t):
        b = [int(v) for v in re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n).groups()]
        y = (b[1] + b[3]) // 2 - 92
        right = b[2]
        left = 1080 - right
        a = left + int((right - left) * 0.15)
        z = left + int((right - left) * 0.85)
        if mode == 'tap':
            subprocess.run(['adb', 'shell', 'input', 'tap', str((a + z) // 2), str(y)])
        elif mode == 'swipe':
            subprocess.run(['adb', 'shell', 'input', 'swipe', str(a), str(y), str(z), str(y), '600'])
        else:
            sh = lambda *c: subprocess.run(['adb', 'shell', 'input', 'motionevent', *c])
            sh('DOWN', str(a), str(y))
            for i in range(1, 7):
                sh('MOVE', str(a + (z - a) * i // 12), str(y))
            subprocess.run(['bash', '-c', 'adb exec-out screencap -p > smoke-out/06j-scrub-bubble.png'])
            sh('UP', str(a + (z - a) // 2), str(y))
        print(f"slider {mode} at y={y} ({a}..{z})")
        break
else:
    print("could not find the slider")
PY2
}
long_press_text() {
  tap_text "$1" 900
}
seed_files() {
  adb shell am start -W -n "$ACT"
  sleep 4
}

# Collects the log, crashes and "not responding" reports at the end of a run.
finish_logs() {
  adb logcat -d > "$OUT/logcat.txt"
  grep -E "FATAL EXCEPTION|AndroidRuntime" -A 25 "$OUT/logcat.txt" > "$OUT/crashes.txt" || true
  grep -E "ANR in app.ember" -A 4 "$OUT/logcat.txt" > "$OUT/anr.txt" || true
  grep -iE "ember|ExoPlayer|MediaSession|Room|SQLite" "$OUT/logcat.txt" | grep -E " E | W " | head -300 > "$OUT/warnings.txt" || true
  adb shell pidof $PKG > "$OUT/pid.txt" || echo "not running" > "$OUT/pid.txt"
}
