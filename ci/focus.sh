# Steps for the batch being built (sourced by quick.sh; helpers come from lib.sh).
# Replace these for each new batch. Name screenshots f01-..., f02-... so they sort together.
#
# Ember-26: editing chapters with the keyboard up. The list keeps the room (tips, the
# save-into-file card and the buttons step aside), Next moves to the following chapter.
say() { echo "== $*" | tee -a "$OUT/focus.txt"; }

# Taps the n-th text box from the bottom of what's on screen (1 = lowest), the hardest case.
tap_field() {
  adb shell uiautomator dump /sdcard/ui.xml > /dev/null
  adb pull /sdcard/ui.xml /tmp/ui.xml > /dev/null
  python3 - "$1" <<'PY'
import re, sys, subprocess
n = int(sys.argv[1])
xml = open('/tmp/ui.xml', encoding='utf-8').read()
boxes = []
for m in re.finditer(r'<node [^>]*>', xml):
    node = m.group(0)
    if 'class="android.widget.EditText"' in node:
        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node)
        x1, y1, x2, y2 = map(int, b.groups())
        boxes.append(((x1 + x2) // 2, (y1 + y2) // 2))
boxes.sort(key=lambda p: p[1])
print("text boxes on screen:", len(boxes))
if len(boxes) >= n:
    x, y = boxes[-n]
    print("tapping text box at", x, y)
    subprocess.run(["adb", "shell", "input", "tap", str(x), str(y)])
PY
}

check_editor() {  # $1 = screenshot prefix
  open_route "chapters/demo-0"; sleep 3
  shot "$1-a-editor" 1
  tap_field 1; sleep 3
  shot "$1-b-lowest-field-typing" 1
  tap_text "Done typing"; sleep 2
  shot "$1-c-keyboard-away" 1
  tap_field 4; sleep 3
  adb shell input keyevent KEYCODE_MOVE_END
  adb shell input text "%s2"; sleep 1
  shot "$1-d-middle-field-typing" 1
  adb shell input keyevent KEYCODE_ENTER; sleep 2
  shot "$1-e-next-moves-down" 1
  adb shell input keyevent KEYCODE_BACK; sleep 2
  shot "$1-f-back-hides-keyboard" 1
  tap_text "Cancel"; sleep 1
  tap_text "Discard"; sleep 2
}

check_editor f01-phone
adb shell wm size 1968x2184
adb shell wm density 380
sleep 3
check_editor f02-unfolded
adb shell wm size reset
adb shell wm density reset
sleep 3

# Edit details too (its fields scroll above the keyboard).
open_route "edit/demo-0"; sleep 3
tap_field 1; sleep 3
shot f03-edit-details-typing 1
adb shell input keyevent KEYCODE_BACK; sleep 1
adb shell input keyevent KEYCODE_BACK; sleep 2
