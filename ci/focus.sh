# Steps for the batch being built (sourced by quick.sh; helpers come from lib.sh).
# Replace these for each new batch. Name screenshots f01-..., f02-... so they sort together.
#
# Ember-28: chips that combine, series stacks, chips on Series/Authors, next in series on Home,
# finished near the end, heard chapters, time listened, clip audio.
say() { echo "== $*" | tee -a "$OUT/focus.txt"; }
up() { adb shell input swipe 540 1900 540 700 400; sleep 1; }

say "Books: March Family shows as one stack"
open_route "library"; sleep 3
shot f01-books-stacks 1
tap_text "In progress"; sleep 1
adb shell input swipe 900 626 300 626 300; sleep 1
tap_text "Not started"; sleep 2
shot f02-in-progress-and-not-started 1
say "restart: both chips still on"
adb shell am force-stop $PKG; sleep 2
adb shell am start -W -n "$ACT" > /dev/null; sleep 4
open_route "library"; sleep 3
shot f03-after-restart 1
tap_text "Series"; sleep 2
shot f04-series-with-chips 1
tap_text "Authors"; sleep 2
shot f05-authors-with-chips 1
tap_text "All"; sleep 2
tap_text "March Family"; sleep 3
shot f06-stack-opens-series 1
adb shell input keyevent KEYCODE_BACK; sleep 2

say "Home: Next in your series (Good Wives after Little Women)"
open_route "home"; sleep 3
up; sleep 1
shot f07-home-next-in-series 1

say "Book page: heard chapters and time listened"
open_route "book/demo-0"; sleep 3
up; up
shot f08-book-chapters-heard 1
up; up; up
shot f09-book-time-listened 1

say "Settings > Playback: Count a book as finished"
open_route "settings/playback"; sleep 3
up
shot f10-settings-finish-within 1

say "Clip audio: share menu, then Audio opens the share sheet"
open_route "clips"; sleep 3
tap_text "Share clip"; sleep 2
shot f11-clip-share-menu 1
tap_text "Audio (30s)"; sleep 12
shot f12-clip-audio-share-sheet 1
adb shell ls -l /data/data/$PKG/cache/clips/ 2>&1 | tee -a "$OUT/focus.txt"
adb shell run-as $PKG ls -l cache/clips/ 2>&1 | tee -a "$OUT/focus.txt"
adb shell input keyevent KEYCODE_BACK; sleep 2

say "Finished near the end: play The Time Machine's last chapter (under 2 min left)"
open_route "book/demo-3"; sleep 3
up; up
tap_text "A Letter Arrives"; sleep 12
shot f13-finished-sheet-near-end 1
adb shell input keyevent KEYCODE_BACK; sleep 2
open_route "player"; sleep 3
shot f14-still-playing-chapters 1
