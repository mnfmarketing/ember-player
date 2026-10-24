#!/usr/bin/env python3
"""Prints one field of a library book from a selfcheck report: book.py report.json "Title" field.
A title ending in * matches the start of the title; otherwise it must match exactly."""
import json, sys

report, title, field = sys.argv[1], sys.argv[2], sys.argv[3]
try:
    books = json.load(open(report)).get("library", [])
except Exception:
    books = []
for b in books:
    t = b.get("title", "")
    if (title.endswith("*") and t.startswith(title[:-1])) or t == title:
        print(b.get(field, ""))
        break
