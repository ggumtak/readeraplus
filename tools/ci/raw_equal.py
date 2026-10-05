#!/usr/bin/env python3
"""Compare exact RGBA rows of Android screencap's 12- or 16-byte raw format, and read single pixels.

Usage (prints one word or line; never exits non-zero, the CI run is best effort):
  raw_equal.py A.raw B.raw Y0 Y1    EQUAL, DIFF <n> bbox x0,y0-x1,y1, or BADSIZE for rows [Y0, Y1)
  raw_equal.py pixel A.raw X Y      the pixel's colour as #RRGGBB (BADSIZE outside the image)
  raw_equal.py near A B TOL         PASS when colours #RRGGBB A and B differ by at most TOL in every channel, else FAIL
  raw_equal.py darker A B N         PASS when colour A is at least N levels darker than B (channel mean), else FAIL
  raw_equal.py --uniform A.raw Y0 Y1  UNIFORM when rows [Y0, Y1) are one colour (the page's paper: the themes are flat),
                                    MIXED <n> bbox x0,y0-x1,y1 (n pixels differ from the first one), or BADSIZE
"""
import struct
import sys
from pathlib import Path


def read(path):
    data = Path(path).read_bytes()
    if len(data) < 12:
        raise ValueError("short header")
    width, height, fmt = struct.unpack_from("<III", data)
    header = len(data) - width * height * 4
    if width == 0 or height == 0 or fmt != 1 or header not in (12, 16):
        raise ValueError("invalid RGBA screencap")
    return width, height, data[header:]


def compare(a, b, y0, y1):
    wa, ha, pa = read(a)
    wb, hb, pb = read(b)
    if (wa, ha) != (wb, hb) or not 0 <= y0 < y1 <= ha:
        return "BADSIZE"
    start, end = y0 * wa * 4, y1 * wa * 4
    if pa[start:end] == pb[start:end]:
        return "EQUAL"
    # Where they differ, in screen pixels (x0,y0-x1,y1 inclusive): a centred box is an overlay, a band at an edge of
    # the range a bar that changed height.
    count = 0
    x0 = y0d = wa
    x1 = y1d = -1
    for i in range(start, end, 4):
        if pa[i:i + 4] != pb[i:i + 4]:
            count += 1
            x, y = (i // 4) % wa, (i // 4) // wa
            x0, x1, y0d, y1d = min(x0, x), max(x1, x), min(y0d, y), max(y1d, y)
    return f"DIFF {count} bbox {x0},{y0d}-{x1},{y1d}"


def pixel(path, x, y):
    """The colour at (x, y) as "#RRGGBB" (the alpha is dropped: a screenshot is opaque)."""
    w, h, p = read(path)
    if not (0 <= x < w and 0 <= y < h):
        return "BADSIZE"
    i = (y * w + x) * 4
    return "#%02X%02X%02X" % (p[i], p[i + 1], p[i + 2])


def rgb(colour):
    """(r, g, b) of "#RRGGBB" (or "RRGGBB"); ValueError otherwise."""
    c = colour.strip().lstrip("#")
    if len(c) != 6:
        raise ValueError(f"not a colour: {colour!r}")
    return tuple(int(c[i:i + 2], 16) for i in (0, 2, 4))


def near(a, b, tol):
    """True when the colours differ by at most tol in every channel."""
    return all(abs(x - y) <= tol for x, y in zip(rgb(a), rgb(b)))


def darker(a, b, n):
    """True when a is at least n levels darker than b, by the mean of the channels (a shadow over the page)."""
    return sum(rgb(b)) / 3 - sum(rgb(a)) / 3 >= n


def uniform(a, y0, y1):
    """UNIFORM when every pixel of rows y0..y1 equals the first one; else MIXED with the count and box of the others."""
    w, h, p = read(a)
    if not 0 <= y0 < y1 <= h:
        return "BADSIZE"
    start, end = y0 * w * 4, y1 * w * 4
    first = p[start:start + 4]
    if p[start:end] == first * ((end - start) // 4):
        return "UNIFORM"
    count = 0
    x0 = y0d = w
    x1 = y1d = -1
    for i in range(start, end, 4):
        if p[i:i + 4] != first:
            count += 1
            x, y = (i // 4) % w, (i // 4) // w
            x0, x1, y0d, y1d = min(x0, x), max(x1, x), min(y0d, y), max(y1d, y)
    return f"MIXED {count} bbox {x0},{y0d}-{x1},{y1d}"


def main(argv):
    mode = argv[0] if argv else ""
    try:
        if mode == "--uniform":
            return uniform(argv[1], int(argv[2]), int(argv[3]))
        if mode == "pixel":
            return pixel(argv[1], int(argv[2]), int(argv[3]))
        if mode == "near":
            return "PASS" if near(argv[1], argv[2], int(argv[3])) else "FAIL"
        if mode == "darker":
            return "PASS" if darker(argv[1], argv[2], int(argv[3])) else "FAIL"
        return compare(argv[0], argv[1], int(argv[2]), int(argv[3]))
    except (ValueError, IndexError, OSError):
        return "FAIL" if mode in ("near", "darker") else "BADSIZE"


if __name__ == "__main__":
    print(main(sys.argv[1:]))
    # Diagnostic helper: CHECK lines are recorded even when the pixels differ.
