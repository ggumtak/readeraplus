#!/usr/bin/env python3
"""Reads the rows of a settings page (ui.kit row / valueRow / toggleRow) from uiautomator dumps, by geometry.

A row's title and summary are two TextViews, the summary right under the title at the same left edge (a section header
of the same name starts at the page's edge, so it is never taken for the row). A toggle row's switch (EinkToggle, which
reports itself as a checkable android.widget.Switch) is the checkable node right of the title on the same row.

Usage (prints nothing when no dump shows the row). DUMPS is one dump or several joined with ",": the first dump that
shows the row wins (the same section read before and after a drag).
  ui_rows.py value DUMPS "title"      the summary under the row's title: a valueRow's value ("없음", "쪽 번호")
  ui_rows.py checked DUMPS "title"    "on" / "off": the row's switch
  ui_rows.py values DUMPS "t1|t2|…"   "t1=v1; t2=v2; …", "?" for a row no dump shows
screenshots.sh reads one row of one dump at a time (value, checked on /tmp/ui.xml, right after the scroll_find that put
the row on screen). `values` and several dumps are kept for manual use: reading the ui_fail_*.xml dumps of a run.
Summaries built by the kit go through keepAll (a U+2060 word joiner between two Hangul syllables): it is removed.
"""
import re
import sys
import xml.etree.ElementTree as ET
from collections import namedtuple

Node = namedtuple("Node", "text checkable checked box")

JOINER = "⁠"
BOUNDS_RE = re.compile(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]")
# The summary starts at the title's left edge and right under it (its own 3 dp top padding is inside its box).
SAME_LEFT = 4
SUMMARY_GAP = 16
# A switch is centred on its row: within this many px of the title's centre (rows are 56 dp = 112 px apart at least).
SWITCH_REACH = 90


def nodes(path):
    """The nodes of a dump with a non-empty box, in document order (an unreadable dump has none)."""
    try:
        root = ET.parse(path).getroot()
    except (OSError, ET.ParseError):
        return []
    out = []
    for n in root.iter("node"):
        m = BOUNDS_RE.fullmatch(n.get("bounds", ""))
        if not m:
            continue
        box = tuple(map(int, m.groups()))
        if box[2] <= box[0] or box[3] <= box[1]:
            continue
        text = (n.get("text") or "").replace(JOINER, "")
        out.append(Node(text, n.get("checkable") == "true", n.get("checked") == "true", box))
    return out


def value(ns, title):
    """The summary under the first row titled [title] that has one, or None."""
    for t in ns:
        if t.text != title:
            continue
        below = [(n.box[1] - t.box[3], n.text) for n in ns
                 if n is not t and n.text and abs(n.box[0] - t.box[0]) <= SAME_LEFT
                 and 0 <= n.box[1] - t.box[3] <= SUMMARY_GAP]
        if below:
            return min(below)[1]
    return None


def checked(ns, title):
    """"on" / "off" of the switch on the first row titled [title] that has one, or None."""
    for t in ns:
        if t.text != title:
            continue
        cy = (t.box[1] + t.box[3]) / 2
        near = [(abs((n.box[1] + n.box[3]) / 2 - cy), n.checked) for n in ns
                if n.checkable and n.box[0] >= t.box[2] - SAME_LEFT
                and abs((n.box[1] + n.box[3]) / 2 - cy) <= SWITCH_REACH]
        if near:
            return "on" if min(near)[1] else "off"
    return None


def first(dumps, read, title):
    """[read] of [title] in the first dump (node lists) that shows it, or None."""
    for ns in dumps:
        v = read(ns, title)
        if v is not None:
            return v
    return None


def main(argv):
    if len(argv) < 3 or argv[0] not in ("value", "checked", "values"):
        print("usage: ui_rows.py value|checked|values DUMP[,DUMP...] TITLE", file=sys.stderr)
        return
    dumps = [nodes(p) for p in argv[1].split(",") if p]
    if argv[0] == "values":
        out = []
        for title in argv[2].split("|"):
            v = first(dumps, value, title)
            out.append(f"{title}={'?' if v is None else v}")
        print("; ".join(out))
        return
    v = first(dumps, value if argv[0] == "value" else checked, argv[2])
    if v is not None:
        print(v)


if __name__ == "__main__":
    main(sys.argv[1:])
