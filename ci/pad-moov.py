"""Copies an MP4 whose index (moov) is at the end, adding a big "free" box inside the index.
Players skip free boxes, so the file plays the same; its index is just much bigger."""
import struct, sys

src, dst, pad = sys.argv[1], sys.argv[2], int(sys.argv[3])
with open(src, "rb") as f:
    data = f.read()
pos, moov = 0, None
while pos + 8 <= len(data):
    size, kind = struct.unpack(">I4s", data[pos:pos + 8])
    hdr = 8
    if size == 1:
        size = struct.unpack(">Q", data[pos + 8:pos + 16])[0]
        hdr = 16
    elif size == 0:
        size = len(data) - pos
    if kind == b"moov":
        moov = (pos, size, hdr)
    pos += size
assert moov and moov[0] + moov[1] == len(data), "moov must be the last box"
start, size, hdr = moov
with open(dst, "wb") as out:
    out.write(data[:start])
    out.write(struct.pack(">I4s", size - hdr + 8 + pad, b"moov"))
    out.write(data[start + hdr:start + size])
    out.write(struct.pack(">I4s", pad, b"free"))
    chunk = b"\0" * (1 << 20)
    left = pad - 8
    while left > 0:
        out.write(chunk[:min(left, len(chunk))])
        left -= min(left, len(chunk))
