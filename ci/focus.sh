# Steps for the batch being built (sourced by quick.sh; helpers come from lib.sh).
# Replace these for each new batch. Name screenshots f01-..., f02-... so they sort together.

# Bug: closing the player shrank the cover while it slid away. The CI emulator has animations
# off, so turn them on and record video of the player closing (arrow, then pulling down).
adb shell settings put global window_animation_scale 1
adb shell settings put global transition_animation_scale 1
adb shell settings put global animator_duration_scale 1
record_close() {
  adb shell screenrecord --time-limit 5 --bit-rate 4000000 /sdcard/$1.mp4 &
  local rec=$!
  sleep 1.5
  eval "$2"
  wait $rec
  sleep 1
  adb pull /sdcard/$1.mp4 "$OUT/$1.mp4"
}
open_route library; sleep 3
open_route player; sleep 4
record_close f01-close-arrow 'tap_text "Close player"'
sleep 2
open_route player; sleep 4
record_close f02-close-pull 'adb shell input swipe 540 500 540 1700 300'
sleep 2
open_route home; sleep 3
open_route player; sleep 4
record_close f03-close-to-home 'tap_text "Close player"'
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
