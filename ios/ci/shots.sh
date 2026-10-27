#!/usr/bin/env bash
# Runs Ember on iPhone and iPad simulators with the demo library and takes screenshots.
set -x
OUT=ios-out
APP=ios/build/Build/Products/Debug-iphonesimulator/Ember.app
BID=app.ember.audiobooks
mkdir -p "$OUT"

shot() {  # device name route
  xcrun simctl launch --terminate-running-process "$1" $BID -EmberDemo YES -EmberRoute "$3" > /dev/null
  sleep "${4:-6}"
  xcrun simctl io "$1" screenshot "$OUT/$2.png" > /dev/null
}

for dev in "$PHONE" "$PAD"; do
  xcrun simctl boot "$dev" || true
done
xcrun simctl bootstatus "$PHONE" -b
xcrun simctl bootstatus "$PAD" -b
for dev in "$PHONE" "$PAD"; do
  xcrun simctl install "$dev" "$APP"
  xcrun simctl ui "$dev" appearance dark
done

shot "$PHONE" p01-home home 10
