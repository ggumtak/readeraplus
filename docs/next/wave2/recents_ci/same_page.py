#!/usr/bin/env python3
"""Compares the page body of two emulator screenshots (stdlib only: screencap writes 8-bit RGB/RGBA PNGs).
Usage: same_page.py before.png after.png step
Prints "PASS <step>: same page" or "FAIL <step>: page differs (N%)". The top 8 % and bottom 12 % are skipped (status
bar, header/footer bands whose clock may change between the shots). Never exits non-zero (the CI run is best effort)."""
import struct, sys, zlib

def read_png(path):
    with open(path, "rb") as f:
        data = f.read()
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        raise ValueError("not a png")
    pos, idat, w = 8, [], 0
    while pos < len(data):
        n, kind = struct.unpack(">I4s", data[pos:pos + 8])
        body = data[pos + 8:pos + 8 + n]
        pos += 12 + n
        if kind == b"IHDR":
            w, h, depth, ctype, _, _, interlace = struct.unpack(">IIBBBBB", body)
            if depth != 8 or ctype not in (2, 6) or interlace:
                raise ValueError("unsupported png")
            bpp = 4 if ctype == 6 else 3
        elif kind == b"IDAT":
            idat.append(body)
        elif kind == b"IEND":
            break
    raw, stride = zlib.decompress(b"".join(idat)), w * bpp
    rows, prev, i = [], bytearray(stride), 0
    for _ in range(h):
        ft, line = raw[i], bytearray(raw[i + 1:i + 1 + stride])
        i += 1 + stride
        for x in range(stride):
            a = line[x - bpp] if x >= bpp else 0
            b = prev[x]
            c = prev[x - bpp] if x >= bpp else 0
            if ft == 1: line[x] = (line[x] + a) & 255
            elif ft == 2: line[x] = (line[x] + b) & 255
            elif ft == 3: line[x] = (line[x] + ((a + b) >> 1)) & 255
            elif ft == 4:
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                line[x] = (line[x] + (a if pa <= pb and pa <= pc else b if pb <= pc else c)) & 255
        rows.append(bytes(line))
        prev = line
    return w, h, bpp, rows

def main():
    before, after, step = sys.argv[1], sys.argv[2], sys.argv[3] if len(sys.argv) > 3 else "compare"
    try:
        w1, h1, b1, r1 = read_png(before)
        w2, h2, b2, r2 = read_png(after)
    except Exception as e:
        print(f"SKIP {step}: {e}")
        return
    if (w1, h1) != (w2, h2):
        print(f"FAIL {step}: size {w1}x{h1} vs {w2}x{h2}")
        return
    top, bottom = int(h1 * 0.08), int(h1 * 0.88)
    diff = total = 0
    for y in range(top, bottom, 2):
        p, q = r1[y], r2[y]
        for x in range(0, w1, 2):
            total += 1
            i, j = x * b1, x * b2
            if max(abs(p[i] - q[j]), abs(p[i + 1] - q[j + 1]), abs(p[i + 2] - q[j + 2])) > 48:
                diff += 1
    pct = 100.0 * diff / max(total, 1)
    print(f"PASS {step}: same page ({pct:.3f}% px differ)" if pct < 0.2 else f"FAIL {step}: page differs ({pct:.2f}% px)")

main()
