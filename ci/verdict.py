"""Compares the emulator self-check reports with what the demo library should contain."""
import json, os, sys

out = sys.argv[1]
problems = []

def load(name):
    p = os.path.join(out, name)
    if not os.path.exists(p):
        problems.append(f"{name} is missing")
        return None
    with open(p) as f:
        return json.load(f)

# What the demo library holds: 8 books in folders, 1 in Audible, 1 bookmark,
# 1 shelf with 2 books, 11 days of listening.
library = {"books": 9, "externalBooks": 1, "bookmarks": 1, "shelves": 1, "shelfLinks": 2, "listeningDays": 11}

def check(label, report, expected):
    if report is None:
        return
    for k, v in expected.items():
        got = report.get(k)
        status = "ok" if got == v else "MISMATCH"
        print(f"{label:28} {k:18} expected {v!s:8} got {got!s:8} {status}")
        if got != v:
            problems.append(f"{label}: {k} expected {v}, got {got}")

up = load("after-upgrade.json")
check("after upgrade", up, {**library, "libraryDbVersion": 6, "podcastDbVersion": 3})
if up is not None and not up.get("dbSnapshots"):
    problems.append("after upgrade: no pre-upgrade database copy was made")
else:
    print("pre-upgrade copies:", up and up.get("dbSnapshots"))

before = load("before-wipe.json")
check("before wipe", before, library)

wiped = load("after-wipe.json")
check("wiped", wiped, {"books": 0, "bookmarks": 0, "shelves": 0, "listeningDays": 0})

mid = load("after-restore-before-folders.json")
check("restored, folders not added", mid, {"books": 1, "externalBooks": 1, "shelves": 1, "listeningDays": 11, "pendingRestore": True})

after = load("after-restore.json")
check("restored, folders added", after, {**library, "clips": 1, "pendingRestore": False})

tor = load("after-torrent.json")
if tor is not None:
    print("torrent:", tor.get("torrents"))
    if "The Torrent Test Book" not in str(tor.get("bookTitles")):
        problems.append("the downloaded book isn't in the library: " + str(tor.get("bookTitles")))
    if "done" not in str(tor.get("torrents")):
        problems.append("torrent download didn't finish: " + str(tor.get("torrents")))
    if tor.get("books") != 10:
        problems.append(f"torrent book not added to library (books={tor.get('books')})")
    else:
        print("torrent book in library: ok")

try:
    print("restore said:", open(os.path.join(out, "restore-result.txt")).read().strip())
except OSError:
    problems.append("restore-result.txt is missing")

crashes = os.path.join(out, "crashes.txt")
if os.path.exists(crashes) and "FATAL EXCEPTION" in open(crashes, errors="replace").read():
    problems.append("the app crashed (see crashes.txt)")

print()
if problems:
    for p in problems:
        print("::error::" + p)
    print("RESULT: PROBLEMS FOUND")
else:
    print("RESULT: upgrade kept everything; backup and restore brought everything back")
