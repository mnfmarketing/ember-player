#!/usr/bin/env bash
# Runs Ember on iPhone and iPad simulators with the demo library and takes a screenshot of each main screen.
set -x
OUT=ios-out
APP=ios/build/Build/Products/Debug-iphonesimulator/Ember.app
BID=app.ember.audiobooks
mkdir -p "$OUT"

shot() {  # device name route [wait] [extra args...]
  local dev=$1 name=$2 route=$3 wait=${4:-6}
  shift 4 2>/dev/null || shift $#
  xcrun simctl launch --terminate-running-process "$dev" $BID -EmberDemo YES -EmberRoute "$route" "$@" > /dev/null
  sleep "$wait"
  xcrun simctl io "$dev" screenshot "$OUT/$name.png" > /dev/null
}

for dev in "$PHONE" "$PAD"; do
  xcrun simctl boot "$dev" || true
done
xcrun simctl bootstatus "$PHONE" -b
xcrun simctl bootstatus "$PAD" -b
for dev in "$PHONE" "$PAD"; do
  xcrun simctl install "$dev" "$APP"
  xcrun simctl status_bar "$dev" override --time 9:41 --batteryState charged --batteryLevel 100 || true
done

# Warm-up: the first launch builds the demo library (audio and covers), so give it time before any screenshot.
for dev in "$PHONE" "$PAD"; do
  xcrun simctl launch "$dev" $BID -EmberDemo YES > /dev/null
done
sleep 30

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
  shot "$PHONE" "p-$m-08-search" search 6
  shot "$PAD" "t-$m-01-home" home 8
  shot "$PAD" "t-$m-02-player" player 7
  shot "$PAD" "t-$m-03-books" books 6
done

# Sync: another device moved this book on, so Ember asks before jumping.
xcrun simctl ui "$PHONE" appearance dark
shot "$PHONE" "p-sync-prompt" home 10 -EmberSyncTest YES -EmberSyncFake demo-0,360000
xcrun simctl spawn "$PHONE" log show --last 3m --style compact --predicate 'subsystem == "app.ember.audiobooks"' > "$OUT/app-log.txt" 2>&1 || true
mkdir -p "$OUT/crashes"
find ~/Library/Logs/DiagnosticReports -name 'Ember*' -newer "$APP" -exec cp {} "$OUT/crashes/" \; 2>/dev/null || true
ls -la "$OUT" "$OUT/crashes"
