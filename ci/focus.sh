# Steps for the batch being built (sourced by quick.sh; helpers come from lib.sh).
# Replace these for each new batch. Name screenshots f01-..., f02-... so they sort together.
#
# Ember-22: the last tab is your profile circle (photo, or your first letter), with your
# listening summary, shortcuts and settings by topic. Stats and Settings open from it.
say() { echo "== $*" | tee -a "$OUT/focus.txt"; }

# ---- No name yet: a plain person outline ----
open_route you; shot f01-you-no-name 5
selfcheck f-start.json
say "start: $(grep -E '"profile(Name|Photo)"' $OUT/f-start.json | tr -d '\n')"

# ---- Add a name: the circle shows its first letter, the tab says the first name ----
tap_text "Add your name"; sleep 2
adb shell input text "Ben%sReader"
shot f02-name-dialog 1
tap_text "Save"; sleep 2
shot f03-you-letter 2
open_route home; shot f04-home-tab-letter 3

# ---- A photo: crop step (moved a little), then saved ----
adb shell am start -n "$ACT" --es ember_profile_photo test > /dev/null; sleep 6
shot f05-crop 1
adb shell input swipe 540 1100 700 1050 400; sleep 1
shot f06-crop-moved 1
tap_text "Save"; sleep 3
shot f07-you-photo 2
open_route library; shot f08-library-tab-photo 3
selfcheck f-photo.json
say "after photo: $(grep -E '"profile(Name|Photo)"' $OUT/f-photo.json | tr -d '\n')"

# ---- From the tab: all stats, settings at a topic, the sound sheet, the gear ----
open_route you; sleep 3
tap_text "See all stats"; shot f09-all-stats 3
adb shell input keyevent KEYCODE_BACK; sleep 2
adb shell input swipe 540 1700 540 700 300; sleep 1
shot f10-you-scrolled 1
tap_text "Sleep timer"; shot f11-settings-sleep-timer 3
adb shell input keyevent KEYCODE_BACK; sleep 2
tap_text "Backup and restore"; shot f12-settings-backup 3
adb shell input keyevent KEYCODE_BACK; sleep 2
tap_text "Sound and equalizer"; shot f13-sound-sheet 3
adb shell input keyevent KEYCODE_BACK; sleep 1
adb shell input keyevent KEYCODE_BACK; sleep 2
open_route you; sleep 2
adb shell input swipe 540 700 540 1900 300; sleep 1
tap_text "Settings"; shot f14-gear-settings 3
adb shell input keyevent KEYCODE_BACK; sleep 2

# ---- Remove the photo: back to the letter ----
open_route you; sleep 2
adb shell input swipe 540 700 540 1900 300; sleep 1
tap_text "Change your photo"; sleep 2
shot f15-photo-menu 1
tap_text "Remove photo"; sleep 2
shot f16-you-letter-again 2

# ---- Dark mode and the unfolded screen ----
adb shell cmd uimode night yes; sleep 3
open_route you; shot f17-you-dark 4
adb shell cmd uimode night no; sleep 2
adb shell wm size 1968x2184
adb shell wm density 380
sleep 3
open_route you; shot f18-unfolded-you 5
adb shell wm size reset
adb shell wm density reset
sleep 2
