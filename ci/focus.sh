# Steps for the batch being built (sourced by quick.sh; helpers come from lib.sh).
# Replace these for each new batch. Name screenshots f01-..., f02-... so they sort together.

# Bug: closing the player shrank the cover while it slid away. Grab frames mid-close.
open_route library; sleep 3
open_route player; sleep 4
tap_text "Close player"
for i in 1 2 3; do adb exec-out screencap -p > "$OUT/f01-closing-$i.png"; done
sleep 2
open_route player; sleep 4
adb shell input swipe 540 500 540 1700 300
for i in 1 2 3; do adb exec-out screencap -p > "$OUT/f02-pulled-closing-$i.png"; done
sleep 2
