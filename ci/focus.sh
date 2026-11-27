# Ember-30: Books › All shows every book; Series as stacks; tap the player's middle label for percent.
say() { echo "== $*" | tee -a "$OUT/focus.txt"; }
# Taps the first label ending with the given text (the label's start changes as it plays).
tap_ending() {
  adb shell uiautomator dump /sdcard/ui.xml > /dev/null
  adb pull /sdcard/ui.xml /tmp/ui.xml > /dev/null
  python3 - "$1" <<'PY'
import re, sys, subprocess
end = sys.argv[1]
for m in re.finditer(r'<node [^>]*>', open('/tmp/ui.xml', encoding='utf-8').read()):
    n = m.group(0); t = re.search(r' text="([^"]*)"', n).group(1)
    if t.endswith(end):
        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n)
        x = (int(b.group(1)) + int(b.group(3))) // 2; y = (int(b.group(2)) + int(b.group(4))) // 2
        subprocess.run(['adb', 'shell', 'input', 'tap', str(x), str(y)]); print('tapped', t); break
else:
    print('could not find', end)
PY
}
# Long-presses the first label with this exact text.
long_text() {
  adb shell uiautomator dump /sdcard/ui.xml > /dev/null
  adb pull /sdcard/ui.xml /tmp/ui.xml > /dev/null
  python3 - "$1" <<'PY'
import re, sys, subprocess
want = sys.argv[1]
for m in re.finditer(r'<node [^>]*>', open('/tmp/ui.xml', encoding='utf-8').read()):
    n = m.group(0); t = re.search(r' text="([^"]*)"', n).group(1)
    if t == want:
        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n)
        x = (int(b.group(1)) + int(b.group(3))) // 2; y = (int(b.group(2)) + int(b.group(4))) // 2
        subprocess.run(['adb', 'shell', 'input', 'swipe', str(x), str(y), str(x), str(y), '900']); print('long-pressed', t); break
else:
    print('could not find', want)
PY
}
say "Books: All, then Series with its sort"
open_route "library"; sleep 3
shot f01-books-all 1
tap_text "Series"; sleep 3
shot f02-series-stacks-sort 1
tap_text "March Family"; sleep 3
shot f03-series-page-start-next 1
adb shell input keyevent KEYCODE_BACK; sleep 2

say "Player: percent toggle, then long-press Speed and Sleep"
open_route "player"; sleep 4
tap_ending " left"; sleep 2
shot f04-player-percent 1
long_text "Speed"; sleep 2
shot f05-speed-presets 1
adb shell input keyevent KEYCODE_BACK; sleep 1
long_text "Sleep"; sleep 2
shot f06-sleep-presets 1
adb shell input keyevent KEYCODE_BACK; sleep 1
adb shell input keyevent KEYCODE_BACK; sleep 2

say "Home and mini player follow the percent choice"
open_route "home"; sleep 3
shot f07-home-percent 1
open_route "library"; sleep 3
shot f08-mini-percent 1
open_route "player"; sleep 3
tap_ending "% complete"; sleep 2

say "Search finds chapter names"
open_route "search/Ball"; sleep 4
shot f09-search-chapters 1
