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
say "Search finds chapter names"
open_route "search?q=Ball"; sleep 5
shot f01-search-chapters 1
say "A link to a screen that doesn't exist is ignored"
open_route "search/Ball"; sleep 3
shot f02-bad-link-ignored 1
