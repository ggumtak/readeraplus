#!/usr/bin/env python3
"""Prints the centre "x y" of a node in a uiautomator dump whose content-desc or text matches (nothing when none).
Usage: find_node.py dump.xml "label" [exact|contains] [index]
index: which matching node, in document order: 0 = the first (default), 1 = the second, -1 = the last."""
import re, sys, xml.etree.ElementTree as ET
path, label = sys.argv[1], sys.argv[2]
mode = sys.argv[3] if len(sys.argv) > 3 else "exact"
try:
    index = int(sys.argv[4]) if len(sys.argv) > 4 else 0
except ValueError:
    index = 0
try:
    root = ET.parse(path).getroot()
except Exception:
    sys.exit(0)
def match(v):
    return v == label if mode == "exact" else (label in v)
hits = []
for node in root.iter("node"):
    if not any(v and match(v) for v in (node.get("content-desc") or "", node.get("text") or "")):
        continue
    m = re.match(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]", node.get("bounds", ""))
    if m:
        x1, y1, x2, y2 = map(int, m.groups())
        if x2 > x1 and y2 > y1:  # an empty box (off screen) can't be tapped
            hits.append(((x1 + x2) // 2, (y1 + y2) // 2))
if -len(hits) <= index < len(hits):
    print(*hits[index])
