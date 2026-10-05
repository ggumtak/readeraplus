#!/usr/bin/env python3
"""Compare exact RGBA rows of Android screencap's 12- or 16-byte raw format, and read single pixels.

Usage (prints one word or line; never exits non-zero, the CI run is best effort):
  raw_equal.py A.raw B.raw Y0 Y1    EQUAL, DIFF <n> bbox x0,y0-x1,y1, or BADSIZE for rows [Y0, Y1)
  raw_equal.py pixel A.raw X Y      the pixel's colour as #RRGGBB (BADSIZE outside the image)
  raw_equal.py near A B TOL         PASS when colours #RRGGBB A and B differ by at most TOL in every channel, else FAIL
  raw_equal.py darker A B N         PASS when colour A is at least N levels darker than B (channel mean), else FAIL
  raw_equal.py --uniform A.raw Y0 Y1  UNIFORM when rows [Y0, Y1) are one colour (the page's paper: the themes are flat),
                                    MIXED <n> bbox x0,y0-x1,y1 (n pixels differ from the first one), or BADSIZE
  raw_equal.py ink A.raw Y0 Y1 N W  INK <n> bbox … when at least N pixels of rows [Y0, Y1) are off the paper (their
                                    first pixel) and their box is at least W wide (drawn text), else NOINK <n> …, BADSIZE
  raw_equal.py crisp A.raw Y0 Y1    CRISP lines <n> period <p> full/lit <r> when every whole text line in rows [Y0, Y1)
                                    repeats one pattern pixel for pixel at a whole-px period and all lines are the same
                                    pixels (hinted text on whole pixels, CI 100); else SOFT …, UNEVEN …, NOLINES <n>,
                                    BADSIZE
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


def ink(a, y0, y1, min_px, min_w):
    """INK when rows y0..y1 hold drawn text: at least min_px pixels off the paper colour (the rows' first pixel, as in
    uniform) in a box at least min_w wide; else NOINK with the same count and box. A line of glyphs that draw nothing
    (CI 99: 聖 in the old 나눔명조 OTF) leaves only paper: NOINK 0."""
    r = uniform(a, y0, y1)
    if r == "BADSIZE":
        return r
    if r == "UNIFORM":
        return "NOINK 0"
    n = int(r.split()[1])
    box = r.split()[3]
    width = int(box.split("-")[1].split(",")[0]) - int(box.split(",")[0]) + 1
    return ("INK " if n >= min_px and width >= min_w else "NOINK ") + r[len("MIXED "):]


CRISP_MIN_PERIOD = 4
CRISP_MIN_LINES = 2


def _ink_span(row, paper, w):
    """First and last x of row (RGBA bytes) whose pixel is not the paper's, or None for a paper-only row."""
    x0 = 0
    while x0 < w and row[x0 * 4:x0 * 4 + 4] == paper:
        x0 += 1
    if x0 == w:
        return None
    x1 = w - 1
    while row[x1 * 4:x1 * 4 + 4] == paper:
        x1 -= 1
    return x0, x1


def _period(rows, x0, x1):
    """(differing rows, p) for the smallest whole-px period p of a text line's pixels in columns x0..x1, comparing
    pattern copies 1 .. n-2 with copies 2 .. n-1: the first and the last copy lack a neighbour's shadow or overhang. A
    line needs four periods for that; (rows, 0) when no period from CRISP_MIN_PERIOD up fits."""
    best = (len(rows), 0)
    for p in range(CRISP_MIN_PERIOD, (x1 - x0 + 1) // 4 + 1):
        a0, a1, b0, b1 = (x0 + p) * 4, (x1 - 2 * p + 1) * 4, (x0 + 2 * p) * 4, (x1 - p + 1) * 4
        d = sum(1 for r in rows if r[a0:a1] != r[b0:b1])
        if d == 0:
            return 0, p
        if d < best[0]:
            best = (d, p)
    return best


def _full_over_lit(rows, paper, x0, x1):
    """Full pixels over lit ones of a line, for the record: ink is the distance from the paper's grey, full at least
    200/255 of the line's deepest ink, lit at least 20/255 of it (on white: ink >= 200 and >= 20, as measured on the
    S25 screenshots)."""
    pg = sum(paper[:3]) / 3
    inks = []
    for r in rows:
        for x in range(x0, x1 + 1):
            px = r[x * 4:x * 4 + 3]
            inks.append(abs(sum(px) / 3 - pg))
    top = max(inks) if inks else 0
    if top <= 0:
        return 0.0
    lit = sum(1 for v in inks if v >= top * 20 / 255)
    full = sum(1 for v in inks if v >= top * 200 / 255)
    return full / lit if lit else 0.0


def crisp(a, y0, y1):
    """CI 100 (2026-10-05, 마루뷰어만큼 선명하게): rows y0..y1 of a page of identical one-line paragraphs, each a short
    pattern repeated (make_samples.py crisp.txt). CRISP when every text line there (a run of rows off the paper; a line
    cut by y0 or y1 is left out) repeats its pattern pixel for pixel at one whole-px period, the same in every line, and
    every line is the same pixels across the row (the same row profile: baselines on whole rows). Hinted text with
    whole-px advances on whole pixels does; the unhinted body paint of before (fractional advances on quarter pixels)
    draws the copies at different phases, never the same pixels. The full/lit ratio is printed for the record only: at
    the CI's weight 500 the synthetic stroke hides the hinting's gain in it."""
    w, h, p = read(a)
    if not 0 <= y0 < y1 <= h:
        return "BADSIZE"
    paper = p[y0 * w * 4:y0 * w * 4 + 4]
    rows = [p[y * w * 4:(y + 1) * w * 4] for y in range(y0, y1)]
    blank = paper * w
    bands = []
    i = 0
    while i < len(rows):
        if rows[i] == blank:
            i += 1
            continue
        j = i
        while j < len(rows) and rows[j] != blank:
            j += 1
        if i > 0 and j < len(rows):
            bands.append((i, j))
        i = j
    if len(bands) < CRISP_MIN_LINES:
        return f"NOLINES {len(bands)}"
    period = None
    for k, (b0, b1) in enumerate(bands):
        spans = [s for s in (_ink_span(r, paper, w) for r in rows[b0:b1]) if s]
        x0, x1 = min(s[0] for s in spans), max(s[1] for s in spans)
        d, per = _period(rows[b0:b1], x0, x1)
        if d or (period is not None and per != period):
            return f"SOFT lines {len(bands)} line {k + 1} rows {y0 + b0}..{y0 + b1 - 1} period {per} diff {d} rows"
        period = per
        if k == 0:
            ratio = _full_over_lit(rows[b0:b1], paper, x0, x1)
    first = rows[bands[0][0]:bands[0][1]]
    for k, (b0, b1) in enumerate(bands[1:], 2):
        if rows[b0:b1] != first:
            return f"UNEVEN lines {len(bands)} line {k} rows {y0 + b0}..{y0 + b1 - 1} differs from line 1"
    return f"CRISP lines {len(bands)} period {period} full/lit {ratio:.3f}"


def main(argv):
    mode = argv[0] if argv else ""
    try:
        if mode == "--uniform":
            return uniform(argv[1], int(argv[2]), int(argv[3]))
        if mode == "ink":
            return ink(argv[1], int(argv[2]), int(argv[3]), int(argv[4]), int(argv[5]))
        if mode == "crisp":
            return crisp(argv[1], int(argv[2]), int(argv[3]))
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
