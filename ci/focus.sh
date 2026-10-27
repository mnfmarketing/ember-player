# Steps for the batch being built (sourced by quick.sh; helpers come from lib.sh).
# Replace these for each new batch. Name screenshots f01-..., f02-... so they sort together.
#
# Ember-24: sync between devices. A plain folder stands in for Google Drive, and a test hook
# writes changes as if an iPhone had made them.
say() { echo "== $*" | tee -a "$OUT/focus.txt"; }
report() { selfcheck "$1"; python3 - "$OUT/$1" <<'PY' | tee -a "$OUT/focus.txt"
import json, sys
r = json.load(open(sys.argv[1]))
for k in ("syncOn", "syncError", "syncDevices", "syncFiles", "syncPrompt", "demo0PositionMs", "positions", "demo0Bookmarks", "demo0Log", "libraryDbVersion"):
    print(f"   {k}: {r.get(k)}")
PY
}
pull_sync() { mkdir -p "$OUT/$1"; adb pull /sdcard/Android/data/$PKG/files/sync-test/. "$OUT/$1" > /dev/null 2>&1 || say "could not pull the sync folder"; ls "$OUT/$1" | tee -a "$OUT/focus.txt"; }
# Prints the place items in a pulled state.json: who wrote each one and where it is.
places() { python3 - "$OUT/$1/state.json" <<'PY' | tee -a "$OUT/focus.txt"
import json, sys
s = json.load(open(sys.argv[1]))
print("   devices:", {k: v["name"] for k, v in s.get("devices", {}).items()})
for k, e in sorted(s["items"].items()):
    if k.endswith("/pos") or k.startswith("mark/") or k.startswith("day/"):
        print(f"   {k}  by {e['d']}  {e.get('v')}")
PY
}
sync_now() { adb shell am start -n "$ACT" --ez ember_sync_now true > /dev/null; sleep "${1:-8}"; }

# The demo book is loaded and playing (quick.sh). Pause it: loaded but paused.
adb shell input keyevent 127; sleep 3

say "turn sync on (test folder in place of Drive)"
adb shell am start -n "$ACT" --ez ember_sync_test true > /dev/null; sleep 2
sync_now
report f-first-sync.json
pull_sync f-files-1
places f-files-1
open_route "settings/Sync"; shot f01-sync-settings 4

say "the iPhone is further along in the loaded book: Ember asks"
adb shell am start -n "$ACT" --es ember_sync_fake demo-0,600000 > /dev/null; sleep 3
sync_now
open_route player; shot f02-go-there-prompt 4
report f-prompt.json
tap_text "Go there"; sleep 4
shot f03-after-go-there 2
report f-went-there.json

say "the log shows both devices, and the iPhone's bookmark arrived"
tap_text "Listening log"; shot f04-log-two-devices 4
adb shell input keyevent KEYCODE_BACK; sleep 2

say "Stay here keeps this device's place and sends it out"
adb shell am start -n "$ACT" --es ember_sync_fake demo-0,300000 > /dev/null; sleep 3
sync_now
open_route player; shot f05-prompt-again 4
tap_text "Stay here"; sleep 10
report f-stayed.json
pull_sync f-files-2
places f-files-2

say "a book that isn't loaded just moves, without asking"
adb shell am start -n "$ACT" --es ember_sync_fake demo-1,420000 > /dev/null; sleep 3
sync_now
report f-moved.json
open_route home; shot f06-home-after-sync 4

say "a bookmark made here goes out"
adb shell am start -n "$ACT" --es ember_add_bookmark demo-0,123000 > /dev/null; sleep 2
sync_now
pull_sync f-files-3
places f-files-3

say "settings after syncing with the iPhone"
open_route "settings/Sync"; shot f07-sync-settings-after 4

# Pass/fail summary for this batch.
python3 - "$OUT" <<'PY' | tee -a "$OUT/focus.txt"
import json, os, sys
out = sys.argv[1]
def r(n):
    p = os.path.join(out, n)
    return json.load(open(p)) if os.path.exists(p) else {}
def places(d):
    p = os.path.join(out, d, "state.json")
    if not os.path.exists(p):
        return {}
    s = json.load(open(p))
    return {k: e for k, e in s["items"].items() if k.endswith("/pos")}
checks = []
first = r("f-first-sync.json")
checks.append(("sync turned on and ran", first.get("syncOn") is True and not first.get("syncError")))
checks.append(("state.json and a log file on 'Drive'", "state.json" in str(first.get("syncFiles")) and "log-" in str(first.get("syncFiles"))))
checks.append(("database is version 6", first.get("libraryDbVersion") == 6))
checks.append(("loaded book: asked 'Go there?'", "600000" in str(r("f-prompt.json").get("syncPrompt"))))
went = r("f-went-there.json")
checks.append(("Go there moved to 10:00", abs((went.get("demo0PositionMs") or 0) - 600000) < 15000))
checks.append(("the iPhone's bookmark arrived", "Saved on the iPhone" in str(went.get("demo0Bookmarks"))))
checks.append(("the iPhone's log entries arrived", "test-iphone" in str(went.get("demo0Log"))))
st = r("f-stayed.json")
checks.append(("Stay here kept 10:00", abs((st.get("demo0PositionMs") or 0) - 600000) < 15000 and not st.get("syncPrompt")))
p2 = places("f-files-2")
mine = [e for k, e in p2.items() if '"p":3000' not in (e.get("v") or "") and e.get("d") != "test-iphone"]
checks.append(("Stay here sent this device's place out", any(json.loads(e["v"])["p"] > 500000 for e in mine)))
mv = r("f-moved.json")
checks.append(("a book not loaded moved without asking", "demo-1=420000" in str(mv.get("positions")) and not mv.get("syncPrompt")))
s3 = os.path.join(out, "f-files-3", "state.json")
checks.append(("a bookmark made here went out", os.path.exists(s3) and '\\"p\\":123000' in open(s3).read()))
for name, ok in checks:
    print(("PASS " if ok else "FAIL ") + name)
PY
