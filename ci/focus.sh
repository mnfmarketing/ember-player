# Ember-30: Books › All shows every book again; the Series tab shows each series as a stack.
say() { echo "== $*" | tee -a "$OUT/focus.txt"; }
say "Books: All, then Series"
open_route "library"; sleep 3
shot f01-books-all 1
tap_text "Series"; sleep 3
shot f02-series-stacks 1
adb shell wm size 1968x2184
adb shell wm density 380
sleep 3
open_route "library"; sleep 3
shot f03-series-unfolded 1
adb shell wm size reset
adb shell wm density reset
