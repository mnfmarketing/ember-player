#!/usr/bin/env bash
# Runs Ember on iPhone and iPad simulators with the demo library and takes a screenshot of each main screen.
set -x
OUT=ios-out
APP=ios/build/Build/Products/Debug-iphonesimulator/Ember.app
BID=app.ember.audiobooks
mkdir -p "$OUT"

# Runs a command, giving up after $1 seconds (a stuck simulator must not stall the whole run).
limit() {
  local t=$1; shift
  "$@" < /dev/null > /dev/null 2>&1 &
  local pid=$!
  ( sleep "$t"; kill -9 "$pid" 2>/dev/null && echo "TIMED OUT: $*" >> "$OUT/progress.txt" ) < /dev/null > /dev/null 2>&1 &
  local dog=$!
  wait "$pid"
  kill "$dog" 2>/dev/null
}

crashes() {
  mkdir -p "$OUT/crashes"
  cp ~/Library/Logs/DiagnosticReports/Ember* "$OUT/crashes/" 2>/dev/null || true
}

shot() {  # device name route [wait] [extra args...]
  local dev=$1 name=$2 route=$3 wait=${4:-6}
  shift 4 2>/dev/null || shift $#
  echo "$name" >> "$OUT/progress.txt"
  limit 60 xcrun simctl launch --terminate-running-process "$dev" $BID -EmberDemo YES -EmberRoute "$route" "$@"
  sleep "$wait"
  limit 30 xcrun simctl io "$dev" screenshot "$OUT/$name.png"
  crashes
}

# The on-screen keyboard, as on a real phone (for the chapter editor's typing check).
defaults write com.apple.iphonesimulator ConnectHardwareKeyboard -bool false 2>/dev/null || true
for dev in "$PHONE" "$PAD"; do
  xcrun simctl boot "$dev" < /dev/null > /dev/null 2>&1 || true
done
xcrun simctl bootstatus "$PHONE" -b
xcrun simctl bootstatus "$PAD" -b
for dev in "$PHONE" "$PAD"; do
  xcrun simctl install "$dev" "$APP"
  xcrun simctl status_bar "$dev" override --time 9:41 --batteryState charged --batteryLevel 100 > /dev/null 2>&1 < /dev/null || true
done

# Warm-up: the first launch builds the demo library (audio and covers). One device at a time, until it says it's ready.
for dev in "$PHONE" "$PAD"; do
  limit 60 xcrun simctl launch "$dev" $BID -EmberDemo YES
  DATA=$(xcrun simctl get_app_container "$dev" $BID data 2>/dev/null)
  for i in $(seq 1 60); do
    [ -f "$DATA/Documents/Demo/.ready" ] && break
    sleep 3
  done
  echo "demo ready on $dev after $((i * 3))s" >> "$OUT/progress.txt"
done

if [ "${EMBER_SHOTS:-all}" = all ]; then
for mode in dark light; do
  for dev in "$PHONE" "$PAD"; do xcrun simctl ui "$dev" appearance $mode; done
  m=${mode:0:1}
  # First launch makes the demo library, so it waits longer.
  shot "$PHONE" "p-$m-01-home" home 8
  shot "$PHONE" "p-$m-02-books" books 6
  shot "$PHONE" "p-$m-03-book" book 6
  shot "$PHONE" "p-$m-04-player" player 7
  shot "$PHONE" "p-$m-05-podcasts" podcasts 6
  shot "$PHONE" "p-$m-06-show" show 6
  shot "$PHONE" "p-$m-07-profile" profile 6
  [ $m = d ] && shot "$PHONE" "p-$m-08-search" search 6
  [ $m = l ] && continue  # iPad in dark only (time on the CI Mac is limited)
  shot "$PAD" "t-$m-01-home" home 8
  shot "$PAD" "t-$m-02-player" player 9
  shot "$PAD" "t-$m-03-books" books 9
  shot "$PAD" "t-$m-04-book" book 9
done

# Settings as a list with its own pages, search, and downloads one tap away (pretend downloads).
xcrun simctl ui "$PHONE" appearance light
xcrun simctl ui "$PAD" appearance light
shot "$PHONE" "p-set-01-settings-list" settings 7
shot "$PHONE" "p-set-02-settings-page" settings-playback 7
shot "$PHONE" "p-set-03-settings-search" settings 7 -EmberSettingsSearch rewind
shot "$PHONE" "p-set-04-books-downloading" books 7 -EmberFakeDownloads 2,45
shot "$PHONE" "p-set-05-home-downloading" home 8 -EmberFakeDownloads 2,45
shot "$PHONE" "p-set-06-downloads" downloads 7 -EmberFakeDownloads 2,45
shot "$PHONE" "p-set-07-podcasts-idle" podcasts 6
shot "$PAD" "t-set-01-settings-two-panes" settings 9
shot "$PAD" "t-set-02-books-downloading" books 9 -EmberFakeDownloads 1,70
shot "$PAD" "t-set-03-settings-page" settings-library 9
xcrun simctl ui "$PHONE" appearance dark
shot "$PHONE" "p-set-08-settings-dark" settings 7

fi

# Just a few (commit message [few-shots]).
if [ "${EMBER_SHOTS:-all}" = few ]; then
  xcrun simctl ui "$PHONE" appearance light
  shot "$PHONE" "p-f-02-search" search 8
  xcrun simctl ui "$PHONE" appearance dark
  shot "$PHONE" "p-f-03-search-dark" search 8
  crashes
  xcrun simctl shutdown all > /dev/null 2>&1 < /dev/null || true
  exit 0
fi

# Ember-iOS-3: themes, sound, sleep, car mode, clips, cards, editing, widgets.
xcrun simctl ui "$PHONE" appearance light
xcrun simctl ui "$PAD" appearance dark
shot "$PHONE" "p-n-01-appearance" settings-appearance 7
shot "$PHONE" "p-n-02-sleep" settings-sleep 7 -bedtimeOn YES -sleepFade YES
shot "$PHONE" "p-n-03-sound" settings-sound 7 -eqEnabled YES -skipSilence YES
shot "$PHONE" "p-n-04-car-settings" settings-car 7
shot "$PHONE" "p-n-05-playback" settings-playback 7
shot "$PHONE" "p-n-06-player" player 8
shot "$PHONE" "p-n-07-clip" clip 9
shot "$PHONE" "p-n-08-clips" home 8 -EmberSheet clips
shot "$PHONE" "p-n-09-quote" home 10 -EmberSheet quote
shot "$PHONE" "p-n-10-recap" home 10 -EmberSheet recap
shot "$PHONE" "p-n-11-edit" home 8 -EmberSheet edit
shot "$PHONE" "p-n-12-chapters" home 8 -EmberSheet chapters
shot "$PHONE" "p-n-13-chapters-typing" home 9 -EmberSheet chapters -EmberChapterFocus 3
shot "$PHONE" "p-n-14-merge" home 8 -EmberSheet merge
shot "$PHONE" "p-n-15-add-external" home 8 -EmberSheet external
shot "$PHONE" "p-n-16-notes" home 8 -EmberSheet notes
shot "$PHONE" "p-n-17-lookup" home 12 -EmberSheet lookup
shot "$PHONE" "p-n-18-finished" finished 8
shot "$PHONE" "p-n-19-car" car 8
shot "$PHONE" "p-n-20-widgets" widgets 8
shot "$PHONE" "p-n-21-external-book" book 8 -lastBookID demo-ext
shot "$PHONE" "p-th-02-books-forest" books 7 -themeId forest
shot "$PHONE" "p-th-03-settings-paper" settings 7 -themeId paper
shot "$PHONE" "p-th-07-book-paper" book 7 -themeId paper
xcrun simctl ui "$PHONE" appearance dark
shot "$PHONE" "p-th-01-home-midnight" home 8 -themeId midnight
shot "$PHONE" "p-th-04-home-trueblack" home 8 -themeId ocean -trueBlack YES
shot "$PHONE" "p-th-05-appearance-slate" settings-appearance 7 -themeId slate
shot "$PHONE" "p-th-06-profile-midnight" profile 7 -themeId midnight
shot "$PHONE" "p-n-22-sound-dark" settings-sound 7 -eqEnabled YES
shot "$PAD" "t-n-01-car" car 9
shot "$PAD" "t-n-02-appearance" settings-appearance 9
shot "$PAD" "t-n-03-home-midnight" home 9 -themeId midnight
shot "$PAD" "t-n-04-recap" home 10 -EmberSheet recap
shot "$PAD" "t-n-05-chapters" home 9 -EmberSheet chapters -EmberChapterFocus 2
shot "$PAD" "t-n-06-player" player 9

# Sync: another device moved this book on, so Ember asks before jumping.
xcrun simctl ui "$PHONE" appearance dark
shot "$PHONE" "p-sync-prompt" home 10 -EmberSyncTest YES -EmberSyncFake demo-0,360000
crashes
ls -la "$OUT" "$OUT/crashes"
# Nothing left holding this step's output open.
xcrun simctl shutdown all > /dev/null 2>&1 < /dev/null || true
