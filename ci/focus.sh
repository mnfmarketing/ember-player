# Steps for the batch being built (sourced by quick.sh; helpers come from lib.sh).
# Replace these for each new batch. Name screenshots f01-..., f02-... so they sort together.
#
# Ember-21: books split into CD folders become one, merging and splitting by hand,
# skipping repeated intros and outros per book, and flip to pause.
book() { python3 ci/book.py "$OUT/$1" "$2" "$3"; }
say() { echo "== $*" | tee -a "$OUT/focus.txt"; }
pause_now() { adb shell input keyevent KEYCODE_MEDIA_PAUSE; sleep 2; }

# ---- 1. A folder with CD1 / CD2 / CD3 (plus two separate books) ----
adb shell mkdir -p /sdcard/MergeTest
adb push mergetest/. /sdcard/MergeTest/ > /dev/null
adb shell ls -R /sdcard/MergeTest | head -30
adb shell am start -n "$ACT" --es ember_pick_library_folder MergeTest > /dev/null
sleep 6
shot f01-folder-picker 1
tap_text "Use this folder"; sleep 3
tap_text "Allow"; sleep 20
open_route library; shot f02-library 5
selfcheck f-scan.json
DISC=$(book f-scan.json "The Disc Test Book" id)
L1=$(book f-scan.json "Loose One" id)
L2=$(book f-scan.json "Loose Two" id)
say "disc book id=$DISC tracks: $(book f-scan.json 'The Disc Test Book' tracks)"
say "disc book chapters: $(book f-scan.json 'The Disc Test Book' chapters)"
say "loose ids: $L1 $L2"

# ---- 2. Skip the first 5 seconds of each file ----
adb shell am start -n "$ACT" --es ember_book_skips "$DISC,5,0" > /dev/null; sleep 2
adb shell am start -n "$ACT" --es ember_play "$DISC" > /dev/null; sleep 4
pause_now
selfcheck f-start.json
say "starting from the top (want about 5000+): $(book f-start.json 'The Disc Test Book' positionMs)"
open_route player; sleep 4
tap_text "Sound and equalizer"; sleep 3
shot f03-sound-sheet 1
adb shell input swipe 540 1900 540 700 400; sleep 1
adb shell input swipe 540 1900 540 700 400
shot f04-sound-sheet-skips 2
tap_text "5s"; sleep 1
shot f05-skip-picked 1
adb shell input keyevent KEYCODE_BACK; sleep 2
adb shell am start -n "$ACT" --es ember_seek "0,19000" > /dev/null; sleep 1
adb shell input keyevent KEYCODE_MEDIA_PLAY; sleep 10
pause_now
selfcheck f-intro.json
say "intro skip (want about 33000-36000, without skip about 29000): $(book f-intro.json 'The Disc Test Book' positionMs)"

# ---- 3. Skip the last 5 seconds too ----
adb shell am start -n "$ACT" --es ember_book_skips "$DISC,5,5" > /dev/null; sleep 2
adb shell am start -n "$ACT" --es ember_seek "1,12000" > /dev/null; sleep 1
adb shell input keyevent KEYCODE_MEDIA_PLAY; sleep 13
pause_now
selfcheck f-outro.json
say "outro skip (want about 55000-62000 in file 3, without skip about 48000): $(book f-outro.json 'The Disc Test Book' positionMs)"
# A scrub into the last seconds stays put.
adb shell am start -n "$ACT" --es ember_seek "3,21000" > /dev/null; sleep 1
adb shell input keyevent KEYCODE_MEDIA_PLAY; sleep 2
pause_now
selfcheck f-scrub.json
say "scrubbed into the end (want about 96000-99000): $(book f-scrub.json 'The Disc Test Book' positionMs)"
adb shell am start -n "$ACT" --es ember_book_skips "$DISC,0,0" > /dev/null

# ---- 4. Flip to pause ----
adb shell am start -n "$ACT" --es ember_flip on > /dev/null; sleep 2
adb shell am start -n "$ACT" --es ember_play demo-0 > /dev/null; sleep 6
selfcheck f-flip-0.json
adb shell am start -n "$ACT" --es ember_flip down > /dev/null; sleep 3
selfcheck f-flip-down.json
adb shell am start -n "$ACT" --es ember_flip up > /dev/null; sleep 4
selfcheck f-flip-up.json
say "flip: playing before=$(grep -o '"playing": [a-z]*' $OUT/f-flip-0.json) down=$(grep -o '"playing": [a-z]*' $OUT/f-flip-down.json) up=$(grep -o '"playing": [a-z]*' $OUT/f-flip-up.json)"
python3 -c "import json; print('== flip log', [(e['kind'], e['reason']) for e in json.load(open('$OUT/f-flip-up.json'))['recentLog'][:4]])" | tee -a "$OUT/focus.txt"
pause_now
open_route settings; sleep 3
adb shell input swipe 540 1700 540 700 300
shot f06-settings-a 2
adb shell input swipe 540 1700 540 700 300
shot f07-settings-b 2
adb shell input swipe 540 1700 540 700 300
shot f07c-settings-c 2

# ---- 5. Merge two books by hand, then split them back apart ----
# Play both (Loose Two last) so they're at the top of the library.
adb shell am start -n "$ACT" --es ember_play "$L1" > /dev/null; sleep 3
pause_now
adb shell am start -n "$ACT" --es ember_play "$L2" > /dev/null; sleep 4
adb shell am start -n "$ACT" --es ember_seek "0,8000" > /dev/null; sleep 3
pause_now
adb shell am start -n "$ACT" --es ember_add_bookmark "$L2,10000" > /dev/null; sleep 2
selfcheck f-before-merge.json
say "before merge: L2 pos=$(book f-before-merge.json 'Loose Two' positionMs) marks=$(book f-before-merge.json 'Loose Two' bookmarks)"
open_route library; sleep 4
tap_text "Loose One" 900; sleep 3
shot f08-book-sheet 1
tap_text "Merge with other books…"; sleep 3
tap_text "Loose Two"; sleep 2
shot f09-two-selected 1
tap_text "Merge into one book"; sleep 3
shot f10-merge-dialog 1
tap_text "Merge"; sleep 15
selfcheck f-merged.json
M=$(book f-merged.json "Loose" id)
if [ -z "$M" ]; then
  say "merge through the screens didn't happen; using the hook"
  adb shell am start -n "$ACT" --es ember_merge "$L1,$L2" --es ember_merge_title Loose > /dev/null; sleep 15
  selfcheck f-merged.json
  M=$(book f-merged.json "Loose" id)
fi
open_route library; shot f11-library-merged 4
say "merged id=$M tracks: $(book f-merged.json Loose tracks)"
say "merged (want marks 50000, pos about 48000-52000): pos=$(book f-merged.json Loose positionMs) marks=$(book f-merged.json Loose bookmarks) log=$(book f-merged.json Loose log); Loose Two still there: '$(book f-merged.json 'Loose Two' id)'"

tap_text "Loose" 900; sleep 3
shot f12-merged-sheet 1
tap_text "Split back apart"; sleep 2
shot f13-split-dialog 1
tap_text "Split"; sleep 15
selfcheck f-split.json
if [ -z "$(book f-split.json 'Loose Two' id)" ]; then
  say "split through the screens didn't happen; using the hook"
  adb shell am start -n "$ACT" --es ember_split "$M" > /dev/null; sleep 15
  selfcheck f-split.json
fi
open_route library; shot f14-library-split 4
say "split (want L2 marks 10000, pos about 8000-12000): L2 pos=$(book f-split.json 'Loose Two' positionMs) marks=$(book f-split.json 'Loose Two' bookmarks) log=$(book f-split.json 'Loose Two' log); merged gone: '$(book f-split.json Loose id)'"
