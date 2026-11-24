# Steps for the batch being built (sourced by quick.sh; helpers come from lib.sh).
# Replace these for each new batch. Name screenshots f01-..., f02-... so they sort together.
#
# Ember-28: Books chips turn on and off, and several can be on: In progress + Not started
# shows both (finished hidden). All clears them. Remembered after Ember restarts.
say() { echo "== $*" | tee -a "$OUT/focus.txt"; }

open_route "library"; sleep 3
shot f01-books-all 1
tap_text "In progress"; sleep 2
shot f02-in-progress 1
adb shell input swipe 900 626 300 626 300; sleep 1
tap_text "Not started"; sleep 2
shot f03-in-progress-and-not-started 1

say "restart Ember: In progress and Not started should both still be on"
adb shell am force-stop $PKG; sleep 2
adb shell am start -W -n "$ACT" > /dev/null; sleep 4
open_route "library"; sleep 3
shot f04-after-restart 1

adb shell wm size 1968x2184
adb shell wm density 380
sleep 3
open_route "library"; sleep 3
shot f05-unfolded 1
adb shell wm size reset
adb shell wm density reset
sleep 2
