# Steps for the batch being built (sourced by quick.sh; helpers come from lib.sh).
# Replace these for each new batch. Name screenshots f01-..., f02-... so they sort together.
#
# Ember-28: Books › "Not finished" shows not-started and in-progress books together (finished
# ones hidden), and the chip and sort you pick are remembered after Ember restarts.
say() { echo "== $*" | tee -a "$OUT/focus.txt"; }

open_route "library"; sleep 3
shot f01-books-all 1
tap_text "Not finished"; sleep 2
shot f02-books-not-finished 1

say "restart Ember: the Not finished chip should still be picked"
adb shell am force-stop $PKG; sleep 2
adb shell am start -W -n "$ACT" > /dev/null; sleep 4
open_route "library"; sleep 3
shot f03-after-restart 1

adb shell wm size 1968x2184
adb shell wm density 380
sleep 3
open_route "library"; sleep 3
shot f04-unfolded-not-finished 1
adb shell wm size reset
adb shell wm density reset
sleep 2
