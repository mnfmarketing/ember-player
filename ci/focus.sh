# Steps for the batch being built (sourced by quick.sh; helpers come from lib.sh).
# Replace these for each new batch. Name screenshots f01-..., f02-... so they sort together.
#
# Ember-23: themes. Settings → Appearance: light/dark/match phone, true black, seven themes
# (each with a light and a dark version) and the accent color.
say() { echo "== $*" | tee -a "$OUT/focus.txt"; }
look() { selfcheck "$1"; say "$1: $(grep -o '"look": "[^"]*"' $OUT/$1)"; }
app() { open_route "settings/Appearance"; sleep 3; }
tour() {  # home, library, player and the profile page in the current look
  open_route home; shot "$1-home" 4
  open_route library; shot "$1-library" 3
  open_route player; shot "$1-player" 4
  adb shell input keyevent KEYCODE_BACK; sleep 2
  open_route you; shot "$1-you" 3
}

adb shell cmd uimode night no; sleep 2
app; shot f01-appearance 1
open_route you; shot f02-you-topics 3
look f-start.json

# Midnight, light
app; tap_text "Midnight"; sleep 2; shot f03-midnight-light-settings 1
tour f04-midnight-light

# Dark, then true black, then Forest
app; tap_text "Dark"; sleep 2; shot f05-dark-settings 1
tour f06-midnight-dark
app; tap_text "True black"; sleep 2
tour f07-midnight-black
app; tap_text "True black"; sleep 1; tap_text "Forest"; sleep 2
tour f08-forest-dark

# Accents: a fixed blue, the theme's own, then a custom color
app; adb shell input swipe 540 1700 540 1000 300; sleep 1
tap_text "Blue accent"; sleep 2; shot f09-blue-accent 1
open_route home; shot f10-forest-blue-home 4
app; adb shell input swipe 540 1700 540 1000 300; sleep 1
tap_text "Theme's color"; sleep 2
tap_text "Custom accent"; sleep 2; shot f11-custom-slider 1
look f-accent.json

# Paper and Ocean in light
app; tap_text "Light"; sleep 1; tap_text "Paper"; sleep 1
adb shell input swipe 540 1700 540 1000 300; sleep 1
tap_text "Match the book"; sleep 2
tour f12-paper-light
app; tap_text "Ocean"; sleep 2
tour f13-ocean-light
app; tap_text "Slate"; sleep 2; open_route home; shot f14-slate-light-home 4
app; tap_text "Graphite"; sleep 2; open_route home; shot f15-graphite-light-home 4

# Match phone: the phone's dark mode switches the theme to its dark version (Paper)
app; tap_text "Paper"; sleep 1; tap_text "Match phone"; sleep 2
open_route home; shot f16-paper-phone-light 4
adb shell cmd uimode night yes; sleep 4
open_route home; shot f17-paper-phone-dark 4
open_route player; shot f18-paper-phone-dark-player 4
adb shell input keyevent KEYCODE_BACK; sleep 1
look f-match-phone.json
adb shell cmd uimode night no; sleep 3
open_route home; shot f19-paper-phone-light-again 4

# Back to the usual look for anything after this
app; tap_text "Ember"; sleep 1
