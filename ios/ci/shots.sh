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

for dev in "$PHONE" "$PAD"; do
  xcrun simctl boot "$dev" < /dev/null > /dev/null 2>&1 || true
done
xcrun simctl bootstatus "$PHONE" -b
xcrun simctl bootstatus "$PAD" -b
for dev in "$PHONE" "$PAD"; do
  xcrun simctl install "$dev" "$APP"
  xcrun simctl status_bar "$dev" override --time 9:41 --batteryState charged --batteryLevel 100 > /dev/null 2>&1 < /dev/null || true
done

# Warm-up: the first launch builds the demo library (audio and covers), so give it time before any screenshot.
for dev in "$PHONE" "$PAD"; do
  limit 60 xcrun simctl launch "$dev" $BID -EmberDemo YES
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
  shot "$PAD" "t-$m-02-player" player 9
  shot "$PAD" "t-$m-03-books" books 9
  shot "$PAD" "t-$m-04-book" book 9
done

# Sync: another device moved this book on, so Ember asks before jumping.
xcrun simctl ui "$PHONE" appearance dark
shot "$PHONE" "p-sync-prompt" home 10 -EmberSyncTest YES -EmberSyncFake demo-0,360000
crashes
ls -la "$OUT" "$OUT/crashes"
# Nothing left holding this step's output open.
xcrun simctl shutdown all > /dev/null 2>&1 < /dev/null || true
