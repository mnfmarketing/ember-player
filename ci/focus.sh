# Steps for the batch being built (sourced by quick.sh; helpers come from lib.sh).
# Replace these for each new batch. Name screenshots f01-..., f02-... so they sort together.
#
# Ember-29: book page redesign, narrators, chapter names from the catalog, even out loudness,
# download the next Drive book, mini player look.
say() { echo "== $*" | tee -a "$OUT/focus.txt"; }
up() { adb shell input swipe 540 1900 540 700 400; sleep 1; }

say "Book page: top, then sections"
open_route "book/demo-0"; sleep 4
shot f01-book-top 1
up
shot f02-book-quick-row 1
up; up
shot f03-book-sections 1
tap_text "Details"; sleep 2
tap_text "Bookmarks & clips"; sleep 2
up
shot f04-book-sections-open 1
open_route "book/demo-0"; sleep 3
tap_text "More"; sleep 2
shot f05-book-menu 1
adb shell input keyevent KEYCODE_BACK; sleep 1

say "Narrator page from the book page"
tap_text "Read by Ada Quill"; sleep 3
shot f06-narrator-page 1
adb shell input keyevent KEYCODE_BACK; sleep 2

say "Books > Authors > Narrators"
open_route "library"; sleep 3
tap_text "Authors"; sleep 2
tap_text "Authors"; sleep 2
tap_text "Narrators"; sleep 2
shot f07-books-narrators 1

say "Get chapter names (catalog over the network)"
open_route "chapters/demo-0?lookup=true"; sleep 12
shot f08-chapter-lookup-editions 1
tap_text "last:Pride and Prejudice"; sleep 10
shot f09-chapter-lookup-preview 1
adb shell input keyevent KEYCODE_BACK; sleep 1
adb shell input keyevent KEYCODE_BACK; sleep 2

say "Sound: Even out loudness"
open_route "player"; sleep 3
tap_text "More"; sleep 2
tap_text "Sound & EQ"; sleep 3
up
shot f10-sound-even-loudness 1
tap_text "Even out loudness"; sleep 1
adb shell input keyevent KEYCODE_BACK; sleep 2

say "Settings > Downloads and storage"
open_route "settings/storage"; sleep 3
shot f11-settings-download-next 1

say "Mini player"
open_route "home"; sleep 3
shot f12-home-mini-player 1
