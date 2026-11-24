# Ember-29 recheck: the player's ⋮ menu opens Sound & EQ, with Even out loudness.
say() { echo "== $*" | tee -a "$OUT/focus.txt"; }
say "Sound sheet from the player menu"
open_route "player"; sleep 3
tap_text "More"; sleep 2
tap_text "Sound*"; sleep 4
shot f01-sound-sheet 1
adb shell input swipe 540 1700 540 900 600; sleep 2
shot f02-sound-sheet-scrolled 1
tap_text "Even out loudness"; sleep 2
shot f03-even-loudness-on 1
