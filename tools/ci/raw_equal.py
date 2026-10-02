#!/usr/bin/env python3
"""Compare exact RGBA rows of Android screencap's 12- or 16-byte raw format."""
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
    count = sum(pa[i:i + 4] != pb[i:i + 4] for i in range(start, end, 4))
    return f"DIFF {count}"


if __name__ == "__main__":
    try:
        result = compare(sys.argv[1], sys.argv[2], int(sys.argv[3]), int(sys.argv[4]))
    except (ValueError, IndexError, OSError):
        result = "BADSIZE"
    print(result)
    # Diagnostic helper: CHECK lines are recorded even when the pixels differ.
