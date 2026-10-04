#!/usr/bin/env python3
"""Where screenshots.sh taps a row of the 독서 노트 hub, read from a uiautomator dump.

The list starts below the toolbar, the tabs and the filter chips. Each row is a header, when it starts a group, over the
note: its text ('“345”': a quote of one word is short) and the meta line ('《sample-utf8》 · 프롤로그 · 2% · 01:42').
Headers are told by their exact shapes: a day ("오늘 · 10월 4일 (일)", "어제 · …", "9월 28일 (일)", "2025년 12월 3일 (수)")
or a book ("《제목》 · 3", its author beside it on the same line).

Usage (prints nothing when the dump shows no such text):
  hub_rows.py note DUMP   "x y" of the topmost text of a note: never a header or a text beside one
  hub_rows.py day DUMP    "x y" of the topmost day header
"""
import re
import sys

from ui_rows import nodes

DAY = re.compile(r"((오늘|어제) · |\d{4}년 )?\d{1,2}월 \d{1,2}일 \(.\)")
BOOK = re.compile(r"《.*》 · \d+")
# The list's top at density 2 (CI 34's 86): the chips span y 270–334, the first day header starts at y 347.
LIST_TOP = 300


def header(text):
    return bool(DAY.fullmatch(text) or BOOK.fullmatch(text))


def pick(ns, mode):
    """The centre (x, y) of the topmost [mode] text in the list ("note" or "day"), or None."""
    texts = [n for n in ns if n.text and n.box[1] >= LIST_TOP]
    if mode == "day":
        found = [n for n in texts if DAY.fullmatch(n.text)]
    else:
        heads = [n.box for n in texts if header(n.text)]
        found = [n for n in texts if not any(n.box[1] < h[3] and h[1] < n.box[3] for h in heads)]
    if not found:
        return None
    x0, y0, x1, y1 = min(found, key=lambda n: n.box[1]).box
    return (x0 + x1) // 2, (y0 + y1) // 2


def main(argv):
    if len(argv) < 2 or argv[0] not in ("note", "day"):
        print("usage: hub_rows.py note|day DUMP", file=sys.stderr)
        return
    xy = pick(nodes(argv[1]), argv[0])
    if xy is not None:
        print(*xy)


if __name__ == "__main__":
    main(sys.argv[1:])
