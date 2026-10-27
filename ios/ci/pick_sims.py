"""Picks the newest iPhone Pro and 11-inch iPad Pro simulators. Prints PHONE=, PAD= lines for $GITHUB_ENV."""
import json, re, sys

devices = json.load(open(sys.argv[1]))["devices"]
best = {}
for runtime, devs in devices.items():
    m = re.search(r"iOS-(\d+)-(\d+)", runtime)
    if not m:
        continue
    ver = (int(m.group(1)), int(m.group(2)))
    for d in devs:
        n = d["name"]
        if n.startswith("iPhone") and "Pro" in n and "Max" not in n:
            kind = "phone"
        elif n.startswith("iPad Pro") and "11" in n:
            kind = "pad"
        elif n.startswith("iPhone") and "phone_any" not in best:
            kind = "phone_any"
        else:
            continue
        num = int((re.findall(r"\d+", n) or ["0"])[0])
        rank = (ver, num)
        if kind not in best or rank > best[kind][0]:
            best[kind] = (rank, d["udid"], n)
phone = best.get("phone") or best.get("phone_any")
pad = best.get("pad") or phone
print(f"PHONE={phone[1]}")
print(f"PHONE_NAME={phone[2]}")
print(f"PAD={pad[1]}")
print(f"PAD_NAME={pad[2]}")
