#!/usr/bin/env python3
"""Prints the centre "x y" of the first node in a uiautomator dump whose content-desc or text matches.
Usage: find_node.py dump.xml "label" [exact|contains]"""
import re, sys, xml.etree.ElementTree as ET
path, label = sys.argv[1], sys.argv[2]
mode = sys.argv[3] if len(sys.argv) > 3 else "exact"
try:
    root = ET.parse(path).getroot()
except Exception:
    sys.exit(0)
def match(v):
    return v == label if mode == "exact" else (label in v)
for node in root.iter("node"):
    for attr in ("content-desc", "text"):
        v = node.get(attr) or ""
        if v and match(v):
            m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds", ""))
            if m:
                x1, y1, x2, y2 = map(int, m.groups())
                print((x1 + x2) // 2, (y1 + y2) // 2)
                sys.exit(0)
