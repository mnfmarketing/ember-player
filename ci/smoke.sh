#!/usr/bin/env bash
# Emulator checks for a new Ember build:
#  1. Upgrade: install the last released build, fill it with a library, install the new
#     build over it, and check every book, bookmark, shelf and stat is still there.
#  2. Backup: export a backup, wipe the app completely, restore, and check it all comes back.
#  3. Screenshots of the main and new screens on phone-size and unfolded-size displays.
set -x
APK="$1"
OLD_APK="$2"
PKG=app.ember.audiobooks
ACT=$PKG/app.ember.ui.MainActivity
REPORTS=/sdcard/Download/EmberTest
OUT=smoke-out
mkdir -p "$OUT"
exec > >(tee "$OUT/run.txt") 2>&1

source "$(dirname "$0")/lib.sh"

adb shell settings put global hide_error_dialogs 1
adb shell settings put secure anr_show_background 0
adb logcat -c

# ---------- 1. Upgrade from the released build ----------
if [ -n "$OLD_APK" ] && [ -f "$OLD_APK" ]; then
  adb install "$OLD_APK" || exit 1
  adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS || true
  seed_files
  adb shell am force-stop $PKG
  adb shell am start -W -n "$ACT" --ez ember_demo true
  sleep 30
  open_route library; shot u1-old-build-library 5
  open_route book/demo-0; shot u2-old-build-book 4
  adb shell am force-stop $PKG

  # -d: the preview build's version number can be lower than the release's.
  adb install -r -d "$APK" || exit 1
  adb shell am start -W -n "$ACT"
  sleep 6
  selfcheck after-upgrade.json
  open_route library; shot u3-new-build-library 4
  open_route book/demo-0; shot u4-new-build-book 4
else
  adb install -r "$APK" || exit 1
  adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS || true
  seed_files
  adb shell am force-stop $PKG
  adb shell am start -W -n "$ACT" --ez ember_demo true
  sleep 30
fi

# ---------- 2. Back up, wipe, restore ----------
selfcheck before-wipe.json
adb shell am start -n "$ACT" --ez ember_wipe_restore_test true > /dev/null
sleep 25
for f in backup-export.json after-wipe.json restore-result.txt after-restore-before-folders.json; do
  adb pull "$REPORTS/$f" "$OUT/$f"
done
# Adding the books back (the demo seeder stands in for adding a folder) matches the rest.
adb shell am start -n "$ACT" --ez ember_demo true > /dev/null
sleep 50
selfcheck after-restore.json
adb shell ls -la $REPORTS

# ---------- 3. Screens (phone size) ----------
open_route home;     shot 01-home 5
open_route library;  shot 02-library
# Long-press a cover: the book's settings without opening it.
long_press_text "Pride and Prejudice"; shot 02b-book-quick-settings 3
# Pick two books and mark them finished, then Undo.
selfcheck before-bulk.json
tap_text "Select more books"; sleep 2
tap_text "Frankenstein"; shot 02c-two-books-selected 2
tap_text "Mark as finished"; shot 02d-undo-bar 1
tap_text "Undo"; sleep 3
selfcheck after-undo.json
grep -h '"finished"' "$OUT/before-bulk.json" "$OUT/after-undo.json"
# Find book info for a book (Audible's catalog).
open_route library; sleep 3
long_press_text "Frankenstein"; sleep 2
tap_text "Find book info*"; shot 62-book-info-matches 8
tap_text "Frankenstein*"; shot 62b-book-info-apply 3
tap_text "Use *"; sleep 6
selfcheck after-info.json
grep -E 'bookTitles' "$OUT/after-info.json" || true
# Your recap.
open_route recap; shot 64-recap-month 6
tap_text "This year"; shot 64b-recap-year 5
# Settings: fill in missing info for the rest.
open_route settings; sleep 2
for i in 1 2 3 4; do adb shell input swipe 500 1800 500 400 300; sleep 1; done
tap_text "Fill in missing book info"; sleep 30
shot 65-settings-new-sections 1
# Home screen icon shortcuts.
adb shell dumpsys shortcut | grep -iE "app.ember|shortLabel|longLabel" | head -40 > "$OUT/shortcuts.txt" || true
open_route book/demo-0; shot 04-book
open_route clips;    shot 20-clips
open_route "card/clip/0"; shot 21-quote-card-post 6
tap_text "Story"; shot 22-quote-card-story 4
tap_text "Light"; shot 23-quote-card-light 4
open_route "search?q=austen"; shot 24-search 5
open_route downloads; shot 25-downloads
open_route settings; shot 05-settings
adb shell input swipe 500 1800 500 400 300; sleep 1
adb shell input swipe 500 1800 500 400 300; shot 26-settings-playback 2
adb shell input swipe 500 1800 500 400 300; sleep 1
adb shell input swipe 500 1800 500 400 300; shot 27-settings-backup 2
open_route home
adb shell am start -n "$ACT" --es ember_play demo-0 > /dev/null
sleep 6
open_route player;   shot 06-player 5
# Tap the time left: chapter <-> whole book.
tap_text "-*"; shot 06b-player-book-time 2
tap_text "Book -*"; sleep 1
# Listening log: two skips forward, one back, pause, then a scrubber slip.
tap_text "Forward 30 seconds"; sleep 1
tap_text "Forward 30 seconds"; sleep 1
tap_text "Back 15 seconds"; sleep 6
tap_text "Pause"; sleep 2
tap_text "Play"; sleep 3
slider tap; shot 06h-scrub-tap-hint 0
sleep 2
slider hold; sleep 3
slider swipe
shot 06e-go-back-bar 1
sleep 6
tap_text "Listening log"; shot 06f-listening-log 3
adb shell input keyevent KEYCODE_BACK; sleep 2
tap_text "PDF"; shot 06g-pdf 5
adb shell input keyevent KEYCODE_BACK; sleep 2
selfcheck after-log.json
grep -E 'listenLogEntries|demo0Pdf|booksWithPdf' "$OUT/after-log.json" || true
# Headphone buttons: Previous skips back, two presses skip back, holding adds a bookmark.
adb shell input keyevent KEYCODE_MEDIA_PREVIOUS; sleep 2
adb shell input keyevent KEYCODE_HEADSETHOOK KEYCODE_HEADSETHOOK; sleep 3
tap_text "Listening log"; shot 06i-log-after-buttons 3
adb shell input keyevent KEYCODE_BACK; sleep 2
# Pocket lock: taps do nothing until you press and hold.
tap_text "Lock the screen"; shot 61-pocket-lock 2
tap_text "Listening log"; sleep 2
shot 61a-locked-tap-ignored 1
adb shell input swipe 540 1200 540 1200 1800; shot 61b-unlocked 2
# Rate it when finished.
adb shell am start -n "$ACT" --es ember_finish_prompt demo-5 > /dev/null
shot 63-finished-sheet 3
adb shell input keyevent KEYCODE_BACK; sleep 2
# Car mode, from the home screen icon shortcut.
adb shell am start -n "$ACT" -a app.ember.SHORTCUT --es ember_shortcut car > /dev/null
shot 60-car-mode 3
tap_text "Bookmark"; shot 60b-car-mode-bookmarked 1
adb shell input keyevent KEYCODE_MEDIA_PLAY_PAUSE; shot 60c-car-mode-paused 2
adb shell input keyevent KEYCODE_MEDIA_PLAY_PAUSE; sleep 1
tap_text "Exit car mode"; sleep 2
adb shell input keyevent KEYCODE_BACK; sleep 2
open_route library; sleep 3

# ---------- Ember-19 ----------
# Up Next: two books queued from their long-press sheets.
long_press_text "Dracula"; sleep 2
tap_text "Add to Up Next"; sleep 2
long_press_text "Treasure Island"; shot 70-quick-sheet-up-next 2
tap_text "Add to Up Next"; sleep 2
open_route home; sleep 3
adb shell input swipe 500 1500 500 1000 300; shot 70b-home-up-next 2
open_route upnext; shot 71-up-next 3
tap_text "last:Move up"; shot 71b-up-next-reordered 2
selfcheck after-upnext.json
grep -E '"upNext"' "$OUT/after-upnext.json" || true
# Export notes (Markdown for Notion) from the book page.
open_route book/demo-0; sleep 3
adb shell input swipe 500 1700 500 1100 300; sleep 1
tap_text "Export notes"; shot 72-export-notes 2
tap_text "Share"; shot 72b-share-sheet 4
adb shell input keyevent KEYCODE_BACK; sleep 2
adb shell am start -n "$ACT" --es ember_export_notes demo-0 > /dev/null; sleep 5
adb pull "$REPORTS/notes-demo-0.txt" "$OUT/notes-demo-0.md"
# The book's own trim silence and voice boost.
open_route player; sleep 3
tap_text "Sound and equalizer"; sleep 3
for i in 1 2 3 4 5 6; do adb shell input swipe 500 2100 500 700 300; sleep 1; done
tap_text "Use its own settings"; sleep 2
adb shell input swipe 500 2100 500 700 300; sleep 1
tap_text "Trim silence"; shot 73-book-sound 2
adb shell input keyevent KEYCODE_BACK; sleep 2
selfcheck after-book-sound.json
grep -E 'demo0Effects' "$OUT/after-book-sound.json" || true
# Sleep timer: an 8-second timer stops the book; the log notes where you were awake.
adb shell am start -n "$ACT" --es ember_play demo-0 > /dev/null; sleep 4
open_route player; sleep 2
adb shell am start -n "$ACT" --ei ember_sleep_seconds 8 > /dev/null
sleep 13
shot 74-sleep-timer-stopped 1
# Shake the phone within 5 minutes: it plays again with the timer back on.
adb emu sensor set acceleration 25:25:25; sleep 0.4
adb emu sensor set acceleration 0:9.8:0; sleep 3
shot 74b-shaken-awake 1
adb shell dumpsys media_session | grep -m3 -E "state=PlaybackState" > "$OUT/after-shake.txt" || true
# If the emulator's motion sensor didn't count as a shake, check the same logic directly.
if grep -q "state=2" "$OUT/after-shake.txt"; then
  echo "sensor shake not seen; using the test hook" >> "$OUT/after-shake.txt"
  adb shell am start -n "$ACT" --ez ember_shake true > /dev/null; sleep 3
  shot 74b2-shaken-awake-hook 1
  adb shell dumpsys media_session | grep -m3 -E "state=PlaybackState" >> "$OUT/after-shake.txt" || true
fi
tap_text "Done"; sleep 1
tap_text "Listening log"; shot 74c-log-sleep 3
adb shell input keyevent KEYCODE_BACK; sleep 2
selfcheck after-sleep.json
grep -E 'sleepLog' "$OUT/after-sleep.json" || true
# Fell asleep? The player offers to go back to where the timer was set.
adb shell am start -n "$ACT" --es ember_sleep_mark "now,1000,99999999" > /dev/null; sleep 2
open_route player; shot 75-fell-asleep-bar 3
tap_text "Go back"; sleep 2
# Pull the player down to close it (back to Home).
open_route home; sleep 2
open_route player; sleep 3
adb shell input swipe 540 500 540 1700 350; shot 79-player-pulled-closed 2
# Home: the mini player steps aside while the big card shows, and comes back on scroll.
open_route home; shot 79b-home-no-mini 3
adb shell input swipe 500 1600 500 700 300; shot 79c-home-scrolled-mini 2
# Settings: the sleep timer section (bedtime on).
open_route settings; sleep 2
for i in 1 2 3; do adb shell input swipe 500 1800 500 400 300; sleep 1; done
tap_text "Bedtime"; shot 78-settings-sleep-timer 2
tap_text "Bedtime"; sleep 1
open_route library; sleep 3

# Long-press play on the mini player: sleep timer.
long_press_text "last:Pause"; shot 06c-mini-long-press-sleep 2
adb shell input keyevent KEYCODE_BACK; sleep 1
adb shell am start -n "$ACT" -a app.ember.SHORTCUT --es ember_shortcut search > /dev/null
shot 06d-shortcut-search 3
adb shell input keyevent KEYCODE_BACK; sleep 1
open_route player; sleep 3
tap_text "Clip";     shot 28-clip-sheet 4
adb shell input keyevent KEYCODE_BACK
sleep 2
tap_text "Chapters"; sleep 3
tap_text "Saved";    shot 29-saved-tab 3
adb shell input keyevent KEYCODE_BACK
adb shell dumpsys media_session > "$OUT/media_session.txt"

# Podcasts: follow a real show, then its page, settings, an episode playing, and finding shows.
adb shell am start -n "$ACT" --es ember_follow "https://feeds.npr.org/510289/podcast.xml" > /dev/null
sleep 20
shot 40-show-page 3
adb shell input swipe 500 1800 500 600 300; shot 41-show-episodes 2
tap_text "Load more"; sleep 2
tap_text "Play"; sleep 8
open_route player; shot 42-episode-player 4
adb shell input keyevent KEYCODE_BACK; sleep 2
adb shell input swipe 500 600 500 1900 300; sleep 1
adb shell input swipe 500 600 500 1900 300; sleep 1
tap_text "Settings"; shot 43-show-settings 3
open_route podcasts; shot 44-podcasts-tab 4
long_press_text "Planet Money"; shot 44b-show-quick-sheet 3
adb shell input keyevent KEYCODE_BACK; sleep 2
tap_text "Add podcasts"; shot 45-podcasts-plus-menu 2
tap_text "Find a show"; shot 46-find-a-show 10
tap_text "1"; shot 47-show-preview 10

# Torrent download. A small test audiobook is shared from this computer as a web seed
# (the emulator reaches it at 10.0.2.2), so the test doesn't depend on outside servers.
# (A web server that supports partial downloads, like real web seeds do.)
(cd torrentsrv && python3 -m RangeHTTPServer 8000 > "../$OUT/http.txt" 2>&1 < /dev/null) &
HTTP_PID=$!
sleep 2
adb shell mkdir -p /sdcard/Audiobooks
adb shell am start -n "$ACT" --ez ember_pick_torrent_folder true > /dev/null
sleep 6
shot 50-folder-picker 1
tap_text "Use this folder"; sleep 3
shot 50b-folder-allow 1
tap_text "Allow"; sleep 3
adb shell am start -n "$ACT" --es ember_torrent "http://10.0.2.2:8000/test-book.torrent" > /dev/null
sleep 20
shot 51-torrent-files 1
tap_text "Download *"; sleep 8
shot 52-torrent-downloading 1
for i in $(seq 1 36); do
  selfcheck torrent-progress.json
  grep -E '"torrents"|torrentDebug|wifiHold' "$OUT/torrent-progress.json" || true
  if grep -q 'done' "$OUT/torrent-progress.json"; then break; fi
  if grep -q 'failed' "$OUT/torrent-progress.json"; then break; fi
  sleep 5
done
open_route downloads; shot 53-torrent-done 4
sleep 10
selfcheck after-torrent.json
open_route library; shot 54-library-with-torrent-book 5
adb shell ls -laR /sdcard/Audiobooks | head -20
pkill -f RangeHTTPServer 2>/dev/null || true

# Dark mode
adb shell cmd uimode night yes
sleep 3
open_route home;     shot 08-home-dark 5
open_route "card/clip/0"; shot 30-quote-card-dark-mode 5
# The newer screens in dark mode.
open_route upnext; shot 31a-up-next-dark 3
adb shell am start -n "$ACT" --es ember_sleep_mark "now,1000,99999999" > /dev/null; sleep 2
open_route player; shot 31b-player-sleep-bar-dark 4
adb shell am start -n "$ACT" -a app.ember.SHORTCUT --es ember_shortcut car > /dev/null
shot 31c-car-mode-dark 3
tap_text "Exit car mode"; sleep 2
adb shell wm size 2184x1968
sleep 3
adb shell am start -n "$ACT" --ez ember_tabletop true > /dev/null
shot 31d-tabletop-dark 5
adb shell am start -n "$ACT" --ez ember_tabletop false > /dev/null
adb shell wm size reset
sleep 3
adb shell cmd uimode night no
sleep 3

# ---------- Unfolded (inner screen) ----------
adb shell wm size 1968x2184
adb shell wm density 380
sleep 3
open_route home;     shot 10-unfolded-home 6
open_route library;  shot 11-unfolded-library
long_press_text "Pride and Prejudice"; shot 11b-unfolded-book-quick-settings 3
adb shell input keyevent KEYCODE_BACK; sleep 2
# A book's page opens beside the library.
tap_text "Frankenstein"; shot 11c-unfolded-book-beside-library 4
adb shell input keyevent KEYCODE_BACK; shot 11d-unfolded-back-closes-page 2
open_route clips;    shot 31-unfolded-clips
open_route "card/clip/0"; shot 32-unfolded-quote-card 6
open_route "search?q=ada"; shot 33-unfolded-search 5
open_route downloads; shot 34-unfolded-downloads
open_route book/demo-0; shot 13-unfolded-book
# Tabletop: half-folded like a laptop (the emulator can't fold, so pretend), inner screen sideways.
adb shell wm size 2184x1968
sleep 3
adb shell am start -n "$ACT" --es ember_play demo-0 > /dev/null; sleep 3
adb shell am start -n "$ACT" --ez ember_tabletop true > /dev/null
shot 77-tabletop 5
tap_text "Library"; shot 77b-tabletop-closed 2
adb shell am start -n "$ACT" --ez ember_tabletop false > /dev/null
adb shell wm size 1968x2184
sleep 3
# A wider player column (dragged to about half the screen).
adb shell am start -n "$ACT" --ef ember_pane 0.6 > /dev/null
sleep 2
adb shell am start -n "$ACT" --es ember_play demo-0 > /dev/null
open_route "collection/series/March%20Family"; shot 13c-unfolded-wide-player 6
tap_text "Chapters"; shot 13d-unfolded-wide-player-chapters 3
adb shell input keyevent KEYCODE_BACK
adb shell am start -n "$ACT" --ef ember_pane 0.33 > /dev/null
sleep 2
# Car mode on the inner screen, then on a landscape phone (as in a dashboard mount).
adb shell am start -n "$ACT" -a app.ember.SHORTCUT --es ember_shortcut car > /dev/null
shot 61-unfolded-car-mode 4
adb shell wm size 2340x1080
adb shell wm density 420
shot 62-landscape-car-mode 5
adb shell wm size 1968x2184
adb shell wm density 380
sleep 3
tap_text "Exit car mode"; sleep 2

# A missing file: the player should explain it in plain words.
adb shell am force-stop $PKG
adb shell am start -W -n "$ACT" --ez ember_delete_demo_audio true
sleep 3
adb shell am start -n "$ACT" --es ember_play demo-1 > /dev/null
shot 35-unfolded-missing-file 8
adb shell wm size reset
adb shell wm density reset
sleep 3
open_route library; shot 36-missing-file-phone 5

finish_logs
exit 0
