#!/usr/bin/env python3
"""Position checks for the emulator run, from the DEBUG `RAPerf show` lines (H4) saved by `perf_mark NAME`.

`perf_mark NAME` saves the whole RAPerf log (`adb logcat -d -v monotonic -s RAPerf:D`) as shots/perf_NAME.txt. Each
show line reads `RAPerf show <OPEN|TURN|JUMP|RELAYOUT> s:<section> o:<page start> a:<anchor> g:<gen> <ms>ms`.
`PageView` exposes no text to uiautomator, so these lines are the only way to tell which text a page starts with.

Usage (prints "PASS <reason>" or "FAIL <reason>"; never exits non-zero, the CI run is best effort):
  perf_log.py first_is A B      every show line logged after mark A, up to mark B (at least one), starts its page
                                (o:, same section) at the anchor a: of the last show line at mark A
  perf_log.py no_relayout A B   no `show RELAYOUT` line lies between marks A and B (FAIL when B has no show line)
  perf_log.py same_start A B    the last show lines at A and at B start at the same s:/o:
  perf_log.py holds A B         every show line after mark A, up to mark B (at least one), shows the page that holds the
                                anchor of the last show line at A (same section, o: at or before it) and keeps that
                                anchor (a:): a scroll → paged switch shows the fixed page around the old top line
  perf_log.py last A            prints the last show line at mark A ("KIND s:S o:O a:A g:G")
  perf_log.py pv_bounds FILE    "top bottom" of the PageView in screen rows, from `dumpsys activity top` saved in FILE
The marks are read from $SHOTS_DIR (default "shots").
"""
import os
import re
import sys
from collections import namedtuple

Show = namedtuple("Show", "kind section start anchor gen ms")

SHOW_RE = re.compile(r"\bshow (\w+) s:(-?\d+) o:(-?\d+) a:(-?\d+) g:(-?\d+) (-?\d+)ms")
TIME_RE = re.compile(r"^\s*(\d+(?:\.\d+)?)\s")


def parse_show(line):
    """The show fields of one log line, or None."""
    m = SHOW_RE.search(line)
    if not m:
        return None
    return Show(m.group(1), *map(int, m.groups()[1:]))


def log_lines(text):
    """The RAPerf lines of a logcat dump (drops the "--------- beginning of" headers and blank lines)."""
    return [x.rstrip("\r") for x in text.splitlines() if "RAPerf" in x]


def stamp(line):
    m = TIME_RE.match(line)
    return float(m.group(1)) if m else None


def after(a_lines, b_lines):
    """The lines of dump B logged after dump A was taken. Exact line match first (the monotonic stamp and pid make a
    line unique); when A's last line is gone from B (the ring buffer wrapped), the stamps decide."""
    if not a_lines:
        return list(b_lines)
    last = a_lines[-1]
    for i in range(len(b_lines) - 1, -1, -1):
        if b_lines[i] == last:
            return list(b_lines[i + 1:])
    t = stamp(last)
    if t is None:
        return []
    return [x for x in b_lines if (stamp(x) or 0.0) > t]


def shows(lines):
    return [s for s in map(parse_show, lines) if s]


def describe(s):
    return f"{s.kind} s:{s.section} o:{s.start} a:{s.anchor} g:{s.gen}"


def first_is(a_lines, b_lines):
    at_a = shows(a_lines)
    if not at_a:
        return "FAIL", "no show line at the first mark"
    anchor = at_a[-1]
    new = shows(after(a_lines, b_lines))
    if not new:
        return "FAIL", "no show line between the marks"
    # Every page shown between the marks (a stepper's several relayouts included) starts at the anchor.
    bad = [s for s in new if (s.section, s.start) != (anchor.section, anchor.anchor)]
    got = bad[0] if bad else new[-1]
    return ("FAIL" if bad else "PASS"), (f"page s:{got.section} o:{got.start} ({got.kind}, {len(new)} show lines) "
                                         f"vs anchor s:{anchor.section} a:{anchor.anchor}")


def no_relayout(a_lines, b_lines):
    if not shows(b_lines):
        return "FAIL", "no RAPerf show lines at all (DEBUG logging off?)"
    new = shows(after(a_lines, b_lines))
    relayouts = [s for s in new if s.kind == "RELAYOUT"]
    if relayouts:
        return "FAIL", f"{len(relayouts)} RELAYOUT of {len(new)} show lines between the marks (first: {describe(relayouts[0])})"
    return "PASS", f"no RELAYOUT ({len(new)} show lines between the marks)"


def holds(a_lines, b_lines):
    at_a = shows(a_lines)
    if not at_a:
        return "FAIL", "no show line at the first mark"
    anchor = at_a[-1]
    new = shows(after(a_lines, b_lines))
    if not new:
        return "FAIL", "no show line between the marks"
    bad = [s for s in new
           if s.section != anchor.section or s.start > anchor.anchor or s.anchor != anchor.anchor]
    got = bad[0] if bad else new[-1]
    return ("FAIL" if bad else "PASS"), (f"page s:{got.section} o:{got.start} a:{got.anchor} ({got.kind}, {len(new)} "
                                         f"show lines) holds anchor s:{anchor.section} a:{anchor.anchor}")


def same_start(a_lines, b_lines):
    a, b = shows(a_lines), shows(b_lines)
    if not a or not b:
        return "FAIL", "no show line at " + ("the first mark" if not a else "the second mark")
    x, y = a[-1], b[-1]
    ok = (x.section, x.start) == (y.section, y.start)
    return ("PASS" if ok else "FAIL"), f"s:{y.section} o:{y.start} vs s:{x.section} o:{x.start}"


VIEW_RE = re.compile(r"^( *)(\S*?)\{[0-9a-f]+ (\S+) \S+ (-?\d+),(-?\d+)-(-?\d+),(-?\d+)")


def pv_bounds(text):
    """(top, bottom) of the first visible PageView in screen rows, from a `dumpsys activity top` view hierarchy
    (each child's bounds are relative to its parent, one level per indent step), or None."""
    stack = []  # (indent, absolute top)
    for raw in text.splitlines():
        m = VIEW_RE.match(raw)
        if not m:
            continue
        indent = len(m.group(1))
        while stack and stack[-1][0] >= indent:
            stack.pop()
        top = (stack[-1][1] if stack else 0) + int(m.group(5))
        height = int(m.group(7)) - int(m.group(5))
        stack.append((indent, top))
        if m.group(2).endswith(".PageView") and m.group(3).startswith("V") and height > 0:
            return top, top + height
    return None


def read(mark):
    path = os.path.join(os.environ.get("SHOTS_DIR", "shots"), f"perf_{mark}.txt")
    try:
        with open(path, encoding="utf-8", errors="replace") as f:
            return log_lines(f.read())
    except OSError:
        return None


def main(argv):
    if len(argv) < 2:
        print("FAIL usage: perf_log.py first_is|holds|no_relayout|same_start A B | last A | pv_bounds FILE")
        return
    cmd = argv[0]
    if cmd == "pv_bounds":
        try:
            with open(argv[1], encoding="utf-8", errors="replace") as f:
                b = pv_bounds(f.read())
        except OSError:
            b = None
        if b:
            print(*b)
        return
    if cmd == "last":
        lines = read(argv[1])
        s = shows(lines or [])
        print(describe(s[-1]) if s else "none")
        return
    checks = {"first_is": first_is, "holds": holds, "no_relayout": no_relayout, "same_start": same_start}
    if cmd not in checks or len(argv) < 3:
        print(f"FAIL unknown check {cmd!r}")
        return
    a, b = read(argv[1]), read(argv[2])
    if a is None or b is None:
        print(f"FAIL mark {argv[1] if a is None else argv[2]} missing")
        return
    result, reason = checks[cmd](a, b)
    print(f"{result} {reason}")


if __name__ == "__main__":
    main(sys.argv[1:])
