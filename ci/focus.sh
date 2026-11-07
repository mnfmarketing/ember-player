# Steps for the batch being built (sourced by quick.sh; helpers come from lib.sh).
# Replace these for each new batch. Name screenshots f01-..., f02-... so they sort together.
#
# Ember-25: downloads one tap away (Books and Podcasts buttons, a bar on Home), and Settings
# as a searchable list where each topic has its own page (two panes unfolded).
say() { echo "== $*" | tee -a "$OUT/focus.txt"; }

# ---- Downloads: nothing running, then pretend two are at 45% ----
adb shell am start -n "$ACT" --es ember_fake_downloads off > /dev/null; sleep 1
open_route library; shot f01-books-downloads-idle 3
adb shell am start -n "$ACT" --es ember_fake_downloads "2,45" > /dev/null; sleep 3
open_route library; shot f02-books-downloading 3
open_route podcasts; shot f03-podcasts-downloading 3
open_route home; adb shell input swipe 540 700 540 1900 300; shot f04-home-downloading-bar 3
tap_text "Downloading 2 · 45%"; sleep 3; shot f05-bar-opens-downloads 1
adb shell input keyevent KEYCODE_BACK; sleep 2
open_route library; sleep 2
tap_text "Downloads, 2 downloading"; sleep 3; shot f06-button-opens-downloads 1
adb shell am start -n "$ACT" --es ember_fake_downloads off > /dev/null; sleep 5
open_route home; shot f07-home-no-bar 3

# ---- Settings: the list, a page, Back, search ----
open_route settings; shot f08-settings-list 3
tap_text "Playback"; sleep 2; shot f09-playback-page 1
adb shell input keyevent KEYCODE_BACK; sleep 2; shot f10-back-to-list 1
tap_text "Sleep timer"; sleep 2; shot f11-sleep-page 1
adb shell input keyevent KEYCODE_BACK; sleep 2
tap_text "Car and headphones"; sleep 2; shot f12-car-page 1
adb shell input keyevent KEYCODE_BACK; sleep 2
tap_text "Downloads and storage"; sleep 2; shot f13-storage-page 1
adb shell input keyevent KEYCODE_BACK; sleep 2
tap_text "About"; sleep 2; shot f14-about-page 1
adb shell input keyevent KEYCODE_BACK; sleep 2
tap_text "Sound"; sleep 3; shot f15-sound-sheet 1
adb shell input keyevent KEYCODE_BACK; sleep 2
tap_text "Search settings"; sleep 1
adb shell input text "wifi"; sleep 2
adb shell input keyevent KEYCODE_BACK; sleep 1
shot f16-search-wifi 1
tap_text "Torrents on Wi-Fi only"; sleep 2; shot f17-search-opens-page 1
adb shell input keyevent KEYCODE_BACK; sleep 2
tap_text "Clear search"; sleep 1
tap_text "Search settings"; sleep 1
adb shell input text "bedtime"; sleep 1
adb shell input keyevent KEYCODE_BACK; sleep 1
shot f18-search-bedtime 1
adb shell input keyevent KEYCODE_BACK; sleep 2

# ---- From the profile page: a topic opens its page; Back returns to the profile ----
open_route you; sleep 3
adb shell input swipe 540 1700 540 700 300; sleep 1
tap_text "Car and headphones"; sleep 2; shot f19-profile-topic-page 1
adb shell input keyevent KEYCODE_BACK; sleep 2; shot f20-back-to-profile 1

# ---- Dark mode ----
adb shell cmd uimode night yes; sleep 3
open_route settings; shot f21-settings-dark 3
adb shell cmd uimode night no; sleep 2

# ---- Unfolded: list on the left, page on the right ----
adb shell wm size 1968x2184
adb shell wm density 380
sleep 3
open_route settings; shot f22-unfolded-settings 5
tap_text "Playback"; sleep 2; shot f23-unfolded-playback 1
tap_text "Library"; sleep 2; shot f24-unfolded-library 1
adb shell am start -n "$ACT" --es ember_fake_downloads "1,70" > /dev/null; sleep 3
open_route library; shot f25-unfolded-books-downloading 3
adb shell am start -n "$ACT" --es ember_fake_downloads off > /dev/null
adb shell wm size reset
adb shell wm density reset
sleep 3
