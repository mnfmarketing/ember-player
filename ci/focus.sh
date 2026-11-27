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
say "Books: All, then Series"
open_route "library"; sleep 3
shot f01-books-all 1
tap_text "Series"; sleep 3
shot f02-series-stacks 1
say "Player: tap time left for percent, then back"
open_route "player"; sleep 4
shot f03-player-time-left 1
tap_ending " left"; sleep 2
shot f04-player-percent 1
tap_ending "% complete"; sleep 2
shot f05-player-time-left-again 1
