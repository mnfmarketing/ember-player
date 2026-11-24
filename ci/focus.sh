# Steps for the batch being built (sourced by quick.sh; helpers come from lib.sh).
# Ember-29 recheck: book menu, narrator link, Narrators switch, Sound sheet.
say() { echo "== $*" | tee -a "$OUT/focus.txt"; }
fresh() { adb shell am force-stop $PKG; sleep 2; adb shell am start -W -n "$ACT" > /dev/null; sleep 4; }

say "Book menu"
fresh
open_route "book/demo-0"; sleep 4
tap_text "More"; sleep 2
shot f01-book-menu 1
adb shell input keyevent KEYCODE_BACK; sleep 1

say "Narrator link"
tap_text "Read by Ada Quill"; sleep 3
shot f02-narrator-page 1

say "Narrators switch"
fresh
open_route "library"; sleep 3
tap_text "Authors"; sleep 2
tap_text "last:Authors"; sleep 2
shot f03-people-menu 1
tap_text "Narrators"; sleep 2
shot f04-books-narrators 1

say "Sound sheet"
fresh
open_route "player"; sleep 3
tap_text "More"; sleep 2
tap_text "Sound & EQ"; sleep 4
shot f05-sound-sheet 1
adb shell input swipe 540 1700 540 900 600; sleep 2
shot f06-sound-even-loudness 1
