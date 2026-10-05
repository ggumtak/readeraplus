#!/usr/bin/env python3
"""Unit tests for the pure CI helpers: perf_log.py, find_node.py, ui_rows.py, hub_rows.py, raw_equal.py (with
--uniform, ink and crisp), the glyph and crisp samples and the crafted restore backup of make_samples.py. Run from the repository root:
python3 -m unittest tools/ci/test_ci_tools.py"""
import json
import os
import struct
import subprocess
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import hub_rows  # noqa: E402
import perf_log  # noqa: E402
import raw_equal  # noqa: E402
import ui_rows  # noqa: E402


def line(t, kind, s, o, a, g=1, ms=12, pid=4242):
    """One `logcat -v monotonic -s RAPerf:D` show line as H4's ReaderActivity logs it."""
    return f"{t:12.3f}  {pid}  {pid} D RAPerf  : show {kind} s:{s} o:{o} a:{a} g:{g} {ms}ms"


OPEN_LINE = "     40.000  4242  4242 I RAPerf  : open 7: first page 85 ms"


class ParseTest(unittest.TestCase):
    def test_parses_every_field(self):
        s = perf_log.parse_show(line(51.5, "RELAYOUT", 3, 1200, 1210, g=4, ms=37))
        self.assertEqual(s, perf_log.Show("RELAYOUT", 3, 1200, 1210, 4, 37))

    def test_ignores_other_lines(self):
        self.assertIsNone(perf_log.parse_show(OPEN_LINE))
        self.assertIsNone(perf_log.parse_show("--------- beginning of main"))

    def test_ignores_the_measuring_lines(self):
        # The DEBUG lines of PerfLines (DEVICE_CHECKLIST §15d) share the tag but are never show lines.
        pre = "     41.000  4242  4242 D RAPerf  : "
        for text in ("turn #3 tap: contact 96 ms, wait 2 ms, up+18 ms, down+114 ms, onDraw 4.2 ms",
                     "frame #3: total 21.3 ms (delay 0.4, draw 4.1, sync 0.6), done up+25 ms, down+121 ms",
                     "open doc TXT index 4.1 ms, 15204352 bytes, 7480012 chars, 312 sections",
                     "open layout s:12 g:1 load 3.0 ms 24011 chars, typeset 19.2 ms 11 pages",
                     "open 7: onDraw 5.1 ms"):
            self.assertIsNone(perf_log.parse_show(pre + text))
            self.assertEqual(perf_log.log_lines(pre + text), [pre + text])

    def test_log_lines_keeps_only_raperf(self):
        text = "--------- beginning of main\r\n" + line(1, "OPEN", 0, 0, 0) + "\r\n\n" + OPEN_LINE + "\n"
        self.assertEqual(perf_log.log_lines(text), [line(1, "OPEN", 0, 0, 0), OPEN_LINE])


class AfterTest(unittest.TestCase):
    def test_lines_after_the_exact_match(self):
        a = [line(1, "OPEN", 0, 0, 0), line(2, "TURN", 0, 500, 500)]
        b = a + [line(3, "RELAYOUT", 0, 500, 500)]
        self.assertEqual(perf_log.after(a, b), [line(3, "RELAYOUT", 0, 500, 500)])

    def test_a_repeated_line_counts_from_its_last_copy(self):
        x = line(1, "TURN", 0, 0, 0)
        self.assertEqual(perf_log.after([x], [x, line(2, "TURN", 0, 9, 9), x]), [])

    def test_wrapped_buffer_falls_back_to_the_stamps(self):
        a = [line(10, "OPEN", 0, 0, 0), line(20, "TURN", 0, 500, 500)]
        b = [line(15, "TURN", 0, 250, 250), line(25, "RELAYOUT", 0, 500, 500)]  # A's last line rotated out
        self.assertEqual(perf_log.after(a, b), [line(25, "RELAYOUT", 0, 500, 500)])

    def test_empty_first_mark_takes_everything(self):
        b = [line(1, "OPEN", 0, 0, 0)]
        self.assertEqual(perf_log.after([], b), b)


class FirstIsTest(unittest.TestCase):
    def test_page_start_equals_the_anchor(self):
        a = [line(1, "OPEN", 0, 0, 0), line(2, "TURN", 2, 4000, 4010)]
        b = a + [line(3, "RELAYOUT", 2, 4010, 4010), line(4, "RELAYOUT", 2, 4010, 4010)]
        self.assertEqual(perf_log.first_is(a, b)[0], "PASS")

    def test_every_line_between_counts(self):
        a = [line(2, "TURN", 2, 4000, 4010)]
        b = a + [line(3, "RELAYOUT", 2, 3990, 4010), line(4, "RELAYOUT", 2, 4010, 4010)]
        result, reason = perf_log.first_is(a, b)
        self.assertEqual(result, "FAIL")
        self.assertIn("o:3990", reason)

    def test_other_start_fails(self):
        a = [line(2, "TURN", 2, 4000, 4010)]
        b = a + [line(3, "RELAYOUT", 2, 3990, 4010)]
        result, reason = perf_log.first_is(a, b)
        self.assertEqual(result, "FAIL")
        self.assertIn("o:3990", reason)

    def test_other_section_fails(self):
        a = [line(2, "TURN", 2, 4000, 4010)]
        self.assertEqual(perf_log.first_is(a, a + [line(3, "OPEN", 3, 4010, 4010)])[0], "FAIL")

    def test_reopen_in_a_new_process(self):
        a = [line(100, "OPEN", 5, 7000, 7000, pid=11)]
        b = a + [line(130, "OPEN", 5, 7000, 7000, pid=22)]
        self.assertEqual(perf_log.first_is(a, b)[0], "PASS")

    def test_needs_a_line_on_both_sides(self):
        self.assertEqual(perf_log.first_is([], [line(1, "OPEN", 0, 0, 0)])[0], "FAIL")
        a = [line(1, "OPEN", 0, 0, 0)]
        result, reason = perf_log.first_is(a, list(a))
        self.assertEqual(result, "FAIL")
        self.assertIn("between", reason)


class NoRelayoutTest(unittest.TestCase):
    def test_turns_only_pass(self):
        a = [line(1, "RELAYOUT", 0, 0, 0)]  # before the first mark: not counted
        b = a + [line(2, "TURN", 0, 100, 100), OPEN_LINE]
        result, reason = perf_log.no_relayout(a, b)
        self.assertEqual(result, "PASS")
        self.assertIn("1 show lines", reason)

    def test_nothing_between_passes(self):
        a = [line(1, "OPEN", 0, 0, 0)]
        self.assertEqual(perf_log.no_relayout(a, list(a))[0], "PASS")

    def test_logging_off_fails(self):
        result, reason = perf_log.no_relayout([OPEN_LINE], [OPEN_LINE])
        self.assertEqual(result, "FAIL")
        self.assertIn("logging off", reason)

    def test_a_relayout_between_fails(self):
        a = [line(1, "OPEN", 0, 0, 0)]
        self.assertEqual(perf_log.no_relayout(a, a + [line(2, "RELAYOUT", 0, 0, 0)])[0], "FAIL")


class HoldsTest(unittest.TestCase):
    # CI 68: scroll → paged shows the fixed page around the old top line (scroll SPEC §1.15) and keeps it as the anchor.
    def test_the_page_around_the_anchor(self):
        a = [line(1, "TURN", 4, 27, 27)]
        self.assertEqual(perf_log.holds(a, a + [line(2, "RELAYOUT", 4, 0, 27)])[0], "PASS")
        self.assertEqual(perf_log.holds(a, a + [line(2, "RELAYOUT", 4, 27, 27)])[0], "PASS")
        a = [line(1, "TURN", 4, 113, 113)]
        self.assertEqual(perf_log.holds(a, a + [line(2, "RELAYOUT", 4, 63, 113)])[0], "PASS")

    def test_a_page_past_the_anchor_another_section_or_a_moved_anchor_fails(self):
        a = [line(1, "TURN", 4, 27, 27)]
        self.assertEqual(perf_log.holds(a, a + [line(2, "RELAYOUT", 4, 40, 27)])[0], "FAIL")
        self.assertEqual(perf_log.holds(a, a + [line(2, "RELAYOUT", 3, 0, 27)])[0], "FAIL")
        result, reason = perf_log.holds(a, a + [line(2, "RELAYOUT", 4, 0, 0)])
        self.assertEqual(result, "FAIL")
        self.assertIn("holds anchor s:4 a:27", reason)
        self.assertEqual(perf_log.holds(a, list(a))[0], "FAIL")
        self.assertEqual(perf_log.holds([], a)[0], "FAIL")


class SameStartTest(unittest.TestCase):
    def test_same_and_different(self):
        a = [line(1, "TURN", 1, 300, 300)]
        self.assertEqual(perf_log.same_start(a, [line(2, "JUMP", 1, 300, 290)])[0], "PASS")
        self.assertEqual(perf_log.same_start(a, [line(2, "JUMP", 1, 310, 300)])[0], "FAIL")
        self.assertEqual(perf_log.same_start([], a)[0], "FAIL")


DUMPSYS = """
TASK 123 id=5
  ACTIVITY com.ggumtak.readeraplus/.reader.ReaderActivity 3f2e1d pid=4242
    View Hierarchy:
      DecorView@5c1a2b[ReaderActivity]{8d3e4f V.E...... R....... 0,0-720,1440}
        android.widget.LinearLayout{1a2b3c V.E...... ........ 0,0-720,1440}
          android.view.ViewStub{2b3c4d G.E...... ......I. 0,0-0,0 #10201b0 android:id/action_mode_bar_stub}
          android.widget.FrameLayout{3c4d5e V.E...... ........ 0,48-720,1440 #1020002 android:id/content}
            android.widget.FrameLayout{4d5e6f V.E...... ........ 0,0-720,1392}
              com.ggumtak.readeraplus.reader.PageView{5e6f70 VFED..C.. ........ 0,0-720,1392 #7f0a0042}
              android.widget.LinearLayout{6f7081 G.E...... ........ 0,0-720,300}
"""


class PvBoundsTest(unittest.TestCase):
    def test_adds_the_parents_tops(self):
        self.assertEqual(perf_log.pv_bounds(DUMPSYS), (48, 1440))

    def test_fullscreen(self):
        text = DUMPSYS.replace("0,48-720,1440 #1020002", "0,0-720,1440 #1020002").replace("0,0-720,1392", "0,0-720,1440")
        self.assertEqual(perf_log.pv_bounds(text), (0, 1440))

    def test_a_gone_page_view_is_skipped(self):
        self.assertIsNone(perf_log.pv_bounds(DUMPSYS.replace("PageView{5e6f70 VFED", "PageView{5e6f70 GFED")))

    def test_siblings_do_not_add_up(self):
        text = DUMPSYS.replace(
            "          android.widget.FrameLayout{3c4d5e",
            "          android.widget.TextView{999999 V.E...... ........ 0,500-720,600}\n          android.widget.FrameLayout{3c4d5e")
        self.assertEqual(perf_log.pv_bounds(text), (48, 1440))


class MainTest(unittest.TestCase):
    def run_tool(self, *args, files=None):
        with tempfile.TemporaryDirectory() as d:
            for name, lines in (files or {}).items():
                with open(os.path.join(d, f"perf_{name}.txt"), "w", encoding="utf-8") as f:
                    f.write("--------- beginning of main\n" + "\n".join(lines) + "\n")
            env = dict(os.environ, SHOTS_DIR=d)
            out = subprocess.run([sys.executable, os.path.join(HERE, "perf_log.py"), *args],
                                 capture_output=True, text=True, env=env)
            self.assertEqual(out.returncode, 0)
            return out.stdout.strip()

    def test_first_is_from_the_mark_files(self):
        a = [line(1, "OPEN", 0, 0, 0), line(2, "TURN", 1, 800, 800)]
        out = self.run_tool("first_is", "54a", "54b", files={"54a": a, "54b": a + [line(3, "RELAYOUT", 1, 800, 800)]})
        self.assertTrue(out.startswith("PASS "), out)

    def test_missing_mark(self):
        self.assertTrue(self.run_tool("no_relayout", "x", "y").startswith("FAIL mark x missing"))

    def test_last(self):
        self.assertEqual(self.run_tool("last", "66", files={"66": [line(1, "TURN", 4, 10, 12, g=2)]}), "TURN s:4 o:10 a:12 g:2")
        self.assertEqual(self.run_tool("last", "66", files={"66": [OPEN_LINE]}), "none")

    def test_unknown_check(self):
        self.assertTrue(self.run_tool("bogus", "a", "b").startswith("FAIL"))


UI_XML = """<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
<hierarchy rotation="0">
  <node index="0" text="" class="android.widget.FrameLayout" content-desc="" bounds="[0,0][720,1440]">
    <node index="0" text="" class="android.widget.ImageButton" content-desc="책 메뉴" bounds="[640,200][704,264]" />
    <node index="1" text="" class="android.widget.SeekBar" content-desc="" bounds="[100,1300][620,1340]" />
    <node index="2" text="" class="android.widget.ImageButton" content-desc="보기: 자세히" bounds="[20,500][240,560]" />
    <node index="3" text="" class="android.widget.ImageButton" content-desc="책 메뉴" bounds="[640,400][704,464]" />
    <node index="4" text="숨김" class="android.widget.TextView" content-desc="" bounds="[0,0][0,0]" />
  </node>
</hierarchy>
"""


class FindNodeTest(unittest.TestCase):
    def find(self, *args):
        with tempfile.NamedTemporaryFile("w", suffix=".xml", delete=False, encoding="utf-8") as f:
            f.write(UI_XML)
        try:
            out = subprocess.run([sys.executable, os.path.join(HERE, "find_node.py"), f.name, *args],
                                 capture_output=True, text=True)
            return out.stdout.strip()
        finally:
            os.unlink(f.name)

    def test_centre_by_label_and_index(self):
        self.assertEqual(self.find("책 메뉴"), "672 232")
        self.assertEqual(self.find("책 메뉴", "exact", "-1"), "672 432")
        self.assertEqual(self.find("보기: ", "contains"), "130 530")  # the library's view toggle (set_list_mode)
        self.assertEqual(self.find("보기: "), "")
        self.assertEqual(self.find("보기: 자세히"), "130 530")

    def test_box_and_class(self):
        self.assertEqual(self.find("책 메뉴", "exact", "0", "--box"), "640 200 704 264")
        self.assertEqual(self.find("SeekBar", "class", "0", "--box"), "100 1300 620 1340")
        self.assertEqual(self.find("android.widget.SeekBar", "class"), "360 1320")
        self.assertEqual(self.find("Bar", "class"), "")

    def test_empty_box_is_never_found(self):
        self.assertEqual(self.find("숨김"), "")


def settings_xml(rows):
    """A settings page dump: each row (text, bounds, extra attributes) one node, in document order."""
    body = "\n".join(f'    <node index="{i}" text="{t}" class="{"android.widget.Switch" if "checkable" in a else "android.widget.TextView"}" '
                     f'content-desc="" {a} bounds="{b}" />' for i, (t, b, a) in enumerate(rows))
    return ("<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>\n<hierarchy rotation=\"0\">\n"
            '  <node index="0" text="" class="android.widget.FrameLayout" content-desc="" bounds="[0,0][720,1440]">\n'
            f"{body}\n  </node>\n</hierarchy>\n")


SWITCH_ON = 'checkable="true" checked="true"'
SWITCH_OFF = 'checkable="true" checked="false"'
PLAIN = 'checkable="false" checked="false"'
# 화면·밝기 at density 2, a few of its rows: section headers start at x 0 (their 16 dp padding is inside the box), row
# titles and summaries at x 32 (16 dp row padding); kit summaries carry U+2060 word joiners between Hangul syllables
# (keepAll). Since the 2026-10-04 review each status band is a section ("위쪽 상태 표시줄", "아래쪽 상태 표시줄") whose
# rows say only 왼쪽 / 가운데 / 오른쪽: the same titles twice, told apart by their header ("아래쪽 상태 표시줄 › 가운데").
SCREEN_ROWS = [
    ("위쪽 상태 표시줄", "[0,160][720,240]", PLAIN),  # the first section: no rule above it
    ("가운데", "[32,260][130,306]", PLAIN),
    ("챕⁠터 제⁠목", "[32,306][160,350]", PLAIN),
    ("아래쪽 상태 표시줄", "[0,370][720,516]", PLAIN),  # a later section: its 20 dp gap and rule inside its box
    ("가운데", "[32,536][130,582]", PLAIN),
    ("없⁠음", "[32,582][110,626]", PLAIN),
    ("진행 막대", "[32,666][160,712]", PLAIN),
    ("화⁠면 맨 아⁠래 가⁠는 선", "[32,712][330,756]", PLAIN),
    ("", "[584,699][688,763]", SWITCH_ON),
    ("상태 글자 크기", "[32,815][250,861]", PLAIN),  # a stepper row: no summary
    ("11", "[460,815][604,861]", PLAIN),
    ("모두 ‘없음’인 줄은 숨깁니다.", "[0,883][720,947]", PLAIN),  # a note: its 16 dp padding is inside its box
]
TOP_MID = "위쪽 상태 표시줄 › 가운데"
BOTTOM_MID = "아래쪽 상태 표시줄 › 가운데"
BOTTOM_RIGHT = "아래쪽 상태 표시줄 › 오른쪽"
# 넘기기·터치·키 at density 2, a few of its rows: a later section's header carries the 20 dp gap and the black rule above
# it inside its own box.
TURNING_ROWS = [
    ("넘기기", "[0,160][720,226]", PLAIN),
    ("넘기는 방식", "[32,246][190,292]", PLAIN),
    ("스크롤", "[32,292][100,336]", PLAIN),
    ("스와이프·길게 누르기", "[0,900][720,984]", PLAIN),
    ("위아래 스와이프로 넘김", "[32,1004][330,1050]", PLAIN),
    ("위⁠로 밀⁠면 다⁠음 페⁠이⁠지", "[32,1050][330,1094]", PLAIN),
    ("", "[584,1017][688,1081]", SWITCH_OFF),
    ("버⁠튼·키", "[0,1134][720,1218]", PLAIN),
    ("볼륨 키", "[32,1238][150,1284]", PLAIN),
    ("아⁠래 키⁠로 다⁠음 페⁠이⁠지", "[32,1284][380,1328]", PLAIN),
]


class UiRowsTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.page = self.write("page.xml", SCREEN_ROWS)
        self.turning = self.write("turning.xml", TURNING_ROWS)

    def tearDown(self):
        self.dir.cleanup()

    def write(self, name, rows):
        path = os.path.join(self.dir.name, name)
        with open(path, "w", encoding="utf-8") as f:
            f.write(settings_xml(rows))
        return path

    def tool(self, *args):
        out = subprocess.run([sys.executable, os.path.join(HERE, "ui_rows.py"), *args], capture_output=True, text=True)
        self.assertEqual(out.returncode, 0, out.stderr)
        return out.stdout.strip()

    def test_value_is_the_summary_without_joiners(self):
        ns = ui_rows.nodes(self.page)
        self.assertEqual(ui_rows.value(ns, BOTTOM_MID), "없음")
        self.assertEqual(ui_rows.value(ns, TOP_MID), "챕터 제목")
        turning = ui_rows.nodes(self.turning)
        self.assertEqual(ui_rows.value(turning, "볼륨 키"), "아래 키로 다음 페이지")
        self.assertEqual(ui_rows.value(turning, "넘기는 방식"), "스크롤")

    def test_a_section_header_of_the_same_name_is_skipped(self):
        # As the old 넘김·화면 설정 had (its 넘기는 방식 section over the row of that name): the header is never the row.
        ns = ui_rows.nodes(self.write("same.xml", [
            ("넘기는 방식", "[0,1119][720,1221]", PLAIN),
            ("넘기는 방식", "[32,1241][190,1287]", PLAIN),
            ("스크롤", "[32,1287][100,1331]", PLAIN),
        ]))
        self.assertEqual(ui_rows.value(ns, "넘기는 방식"), "스크롤")

    def test_a_row_is_found_under_its_own_section_header(self):
        ns = ui_rows.nodes(self.page)
        # Unqualified, the first row of that name wins (the top band's); qualified, the one under its header.
        self.assertEqual(ui_rows.value(ns, "가운데"), "챕터 제목")
        self.assertEqual(ui_rows.value(ns, BOTTOM_MID), "없음")
        self.assertEqual(ui_rows.xy(ns, TOP_MID), "81 283")
        self.assertEqual(ui_rows.xy(ns, BOTTOM_MID), "81 559")
        # A header the dump does not show finds nothing (not a row of another section).
        self.assertIsNone(ui_rows.value(ns, "없는 머리글 › 가운데"))
        self.assertIsNone(ui_rows.xy(ns, "없는 머리글 › 가운데"))
        # The header itself is never the row.
        self.assertIsNone(ui_rows.xy(ns, "위쪽 상태 표시줄"))
        self.assertEqual(ui_rows.xy(ns, "진행 막대"), "96 689")

    def test_no_summary_and_no_row(self):
        ns = ui_rows.nodes(self.page)
        self.assertIsNone(ui_rows.value(ns, "상태 글자 크기"))  # the note below starts at x 0, not at the title's edge
        self.assertIsNone(ui_rows.value(ns, BOTTOM_RIGHT))
        self.assertIsNone(ui_rows.value(ui_rows.nodes(self.turning), "버튼·키"))  # a header has no summary

    def test_checked_reads_the_switch_on_the_row(self):
        ns = ui_rows.nodes(self.page)
        self.assertEqual(ui_rows.checked(ns, "진행 막대"), "on")
        self.assertEqual(ui_rows.checked(ui_rows.nodes(self.turning), "위아래 스와이프로 넘김"), "off")
        self.assertIsNone(ui_rows.checked(ns, BOTTOM_MID))  # 진행 막대's switch is a row lower
        self.assertIsNone(ui_rows.checked(ui_rows.nodes(self.turning), "볼륨 키"))  # a chooser row has no switch
        self.assertIsNone(ui_rows.checked(ns, "없는 행"))

    def test_cli_value_checked_xy_and_missing(self):
        self.assertEqual(self.tool("value", self.page, BOTTOM_MID), "없음")
        self.assertEqual(self.tool("value", self.turning, "볼륨 키"), "아래 키로 다음 페이지")
        self.assertEqual(self.tool("checked", self.page, "진행 막대"), "on")
        self.assertEqual(self.tool("xy", self.page, BOTTOM_MID), "81 559")
        self.assertEqual(self.tool("value", self.page, BOTTOM_RIGHT), "")
        self.assertEqual(self.tool("xy", self.page, BOTTOM_RIGHT), "")
        self.assertEqual(self.tool("value", os.path.join(self.dir.name, "none.xml"), BOTTOM_MID), "")

    def test_cli_values_take_the_first_dump_that_shows_the_row(self):
        lower = self.write("lower.xml", [
            ("아래쪽 상태 표시줄", "[0,100][720,180]", PLAIN),
            ("가운데", "[32,200][130,246]", PLAIN), ("쪽 번호", "[32,246][130,290]", PLAIN),
            ("오른쪽", "[32,330][130,376]", PLAIN), ("배터리 아이콘 · 시계", "[32,376][240,420]", PLAIN),
        ])
        self.assertEqual(self.tool("values", f"{self.page},{lower}", f"{TOP_MID}|{BOTTOM_MID}|{BOTTOM_RIGHT}|위쪽 상태 표시줄 › 왼쪽"),
                         f"{TOP_MID}=챕터 제목; {BOTTOM_MID}=없음; {BOTTOM_RIGHT}=배터리 아이콘 · 시계; 위쪽 상태 표시줄 › 왼쪽=?")


# The 독서 노트 hub's 인용문 tab as in CI 34 (86_notes_quotes.png, density 2), with a3b8826's 40 dp chips on a 48 dp row
# (the list starts 8 px lower): toolbar, tabs, filter chips, then one day header over two quotes of one word each, every
# quote with its meta line (the book's title in 《》, the time alone: the day header names the day).
HUB_ROWS = [
    ("독서 노트", "[104,75][520,133]", PLAIN),
    ("전체", "[0,161][120,257]", PLAIN), ("인용문", "[120,161][240,257]", PLAIN), ("메모", "[240,161][360,257]", PLAIN),
    ("모든 책 ▾", "[32,266][182,346]", PLAIN), ("최신순 ▾", "[198,266][340,346]", PLAIN),
    ("모든 색 ▾", "[357,266][507,346]", PLAIN),
    ("오늘 · 10월 4일 (일)", "[32,365][266,411]", PLAIN),
    ("“345”", "[32,440][110,486]", PLAIN),
    ("《sample-utf8》 · 프롤로그 · 2% · 01:42", "[32,496][476,532]", PLAIN),
    ("“Reader”", "[32,616][156,662]", PLAIN),
    ("《sample-utf8》 · 프롤로그 · 1% · 01:42", "[32,672][476,708]", PLAIN),
]


class HubRowsTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()

    def tearDown(self):
        self.dir.cleanup()

    def write(self, rows):
        path = os.path.join(self.dir.name, "hub.xml")
        with open(path, "w", encoding="utf-8") as f:
            f.write(settings_xml(rows))
        return path

    def tool(self, *args):
        out = subprocess.run([sys.executable, os.path.join(HERE, "hub_rows.py"), *args], capture_output=True, text=True)
        self.assertEqual(out.returncode, 0, out.stderr)
        return out.stdout.strip()

    def test_a_short_quote_under_the_day_header_is_the_first_note(self):
        hub = self.write(HUB_ROWS)
        self.assertEqual(self.tool("note", hub), "71 463")  # “345”, not the header nor a chip
        self.assertEqual(self.tool("day", hub), "149 388")

    def test_a_book_header_and_its_author_are_never_the_note(self):
        ns = ui_rows.nodes(self.write(HUB_ROWS[:7] + [
            ("《sample-utf8》 · 2개", "[32,365][300,411]", PLAIN), ("테스트 작가", "[324,365][688,411]", PLAIN),
        ] + HUB_ROWS[8:]))
        self.assertEqual(hub_rows.pick(ns, "note"), (71, 463))
        self.assertIsNone(hub_rows.pick(ns, "day"))

    def test_headers_by_their_shapes(self):
        for t in ("오늘 · 10월 4일 (일)", "어제 · 9월 29일 (월)", "9월 28일 (일)", "2025년 12월 3일 (수)", "《제목》 · 12개"):
            self.assertTrue(hub_rows.header(t), t)
        for t in ("“345”", "《sample-utf8》 · 프롤로그 · 2% · 01:42", "《sample-utf8》(휴지통) · 37%", "모든 책 ▾",
                  "《제목》 · 21:04"):
            self.assertFalse(hub_rows.header(t), t)

    def test_nothing_below_the_chips(self):
        self.assertEqual(self.tool("note", self.write(HUB_ROWS[:7])), "")
        self.assertEqual(self.tool("day", os.path.join(self.dir.name, "none.xml")), "")


def raw_image(path, w, h, rows, header=16):
    """A screencap raw file: w, h, format 1 (RGBA_8888) [+ colour space], then w*h RGBA pixels (rows[y] = grey)."""
    with open(path, "wb") as f:
        f.write(struct.pack("<III", w, h, 1) + (struct.pack("<I", 0) if header == 16 else b""))
        for y in range(h):
            f.write(bytes([rows[y], rows[y], rows[y], 255]) * w)


class RawEqualTest(unittest.TestCase):
    def test_rows_compare(self):
        with tempfile.TemporaryDirectory() as d:
            a, b, c = (os.path.join(d, n) for n in ("a.raw", "b.raw", "c.raw"))
            raw_image(a, 4, 6, [0, 10, 20, 30, 40, 50])
            raw_image(b, 4, 6, [99, 10, 20, 30, 40, 99], header=12)
            raw_image(c, 4, 5, [0, 10, 20, 30, 40])
            self.assertEqual(raw_equal.compare(a, b, 1, 5), "EQUAL")
            self.assertEqual(raw_equal.compare(a, b, 0, 5), "DIFF 4 bbox 0,0-3,0")
            self.assertEqual(raw_equal.compare(a, b, 0, 6), "DIFF 8 bbox 0,0-3,5")
            self.assertEqual(raw_equal.compare(a, c, 1, 5), "BADSIZE")
            self.assertEqual(raw_equal.compare(a, b, 3, 9), "BADSIZE")

    def test_diff_box_names_the_pixels(self):
        with tempfile.TemporaryDirectory() as d:
            a, b = (os.path.join(d, n) for n in ("a.raw", "b.raw"))
            raw_image(a, 4, 6, [0, 10, 20, 30, 40, 50])
            data = bytearray(open(a, "rb").read())
            pixel = 16 + (3 * 4 + 2) * 4  # (x 2, y 3) after the 16-byte header
            data[pixel] = 200
            with open(b, "wb") as f:
                f.write(bytes(data))
            self.assertEqual(raw_equal.compare(a, b, 1, 5), "DIFF 1 bbox 2,3-2,3")
            self.assertEqual(raw_equal.compare(a, b, 0, 3), "EQUAL")

    def test_pixel_reads_one_colour(self):
        with tempfile.TemporaryDirectory() as d:
            a, b = (os.path.join(d, n) for n in ("a.raw", "b.raw"))
            raw_image(a, 4, 6, [0, 0x32, 0x3C, 0xF5, 0xFF, 0x1A])
            raw_image(b, 4, 6, [0, 0x32, 0x3C, 0xF5, 0xFF, 0x1A], header=12)
            data = bytearray(open(a, "rb").read())
            data[16 + (2 * 4 + 1) * 4:16 + (2 * 4 + 1) * 4 + 3] = bytes([0xF0, 0xD0, 0x96])  # (x 1, y 2): the gold
            with open(a, "wb") as f:
                f.write(bytes(data))
            self.assertEqual(raw_equal.pixel(a, 0, 1), "#323232")
            self.assertEqual(raw_equal.pixel(a, 1, 2), "#F0D096")
            self.assertEqual(raw_equal.pixel(a, 3, 5), "#1A1A1A")
            self.assertEqual(raw_equal.pixel(b, 1, 3), "#F5F5F5")  # the 12-byte header
            self.assertEqual(raw_equal.pixel(a, 4, 0), "BADSIZE")
            self.assertEqual(raw_equal.pixel(a, 0, 6), "BADSIZE")
            self.assertEqual(raw_equal.main(["pixel", a, "1", "2"]), "#F0D096")
            self.assertEqual(raw_equal.main(["pixel", a, "x", "2"]), "BADSIZE")
            self.assertEqual(raw_equal.main(["pixel", os.path.join(d, "none.raw"), "0", "0"]), "BADSIZE")

    def test_near_and_darker(self):
        self.assertTrue(raw_equal.near("#3C3C3C", "#3C3C3C", 0))
        self.assertTrue(raw_equal.near("#3E3A3C", "3C3C3C", 2))
        self.assertFalse(raw_equal.near("#3F3C3C", "#3C3C3C", 2))
        # A 30 % shadow on white is far darker; one grey level of noise is not a shadow.
        self.assertTrue(raw_equal.darker("#B3B3B3", "#FFFFFF", 12))
        self.assertFalse(raw_equal.darker("#FEFEFE", "#FFFFFF", 12))
        self.assertFalse(raw_equal.darker("#FFFFFF", "#B3B3B3", 12))
        self.assertTrue(raw_equal.darker("#262626", "#323232", 12))
        self.assertEqual(raw_equal.main(["near", "#333333", "#343434", "2"]), "PASS")
        self.assertEqual(raw_equal.main(["near", "#333333", "#000000", "2"]), "FAIL")
        self.assertEqual(raw_equal.main(["darker", "#C0C0C0", "#FFFFFF", "12"]), "PASS")
        self.assertEqual(raw_equal.main(["darker", "#FFFFFF", "#FFFFFF", "12"]), "FAIL")
        # A missing or broken colour (a pixel read that failed) is a FAIL, never a crash.
        self.assertEqual(raw_equal.main(["near", "BADSIZE", "#FFFFFF", "2"]), "FAIL")
        self.assertEqual(raw_equal.main(["darker", "", "#FFFFFF", "12"]), "FAIL")
        self.assertEqual(raw_equal.main(["near"]), "FAIL")

    def test_cli_keeps_the_row_compare(self):
        with tempfile.TemporaryDirectory() as d:
            a, b = (os.path.join(d, n) for n in ("a.raw", "b.raw"))
            raw_image(a, 4, 6, [0, 10, 20, 30, 40, 50])
            raw_image(b, 4, 6, [99, 10, 20, 30, 40, 99])
            out = subprocess.run([sys.executable, os.path.join(HERE, "raw_equal.py"), a, b, "1", "5"],
                                 capture_output=True, text=True)
            self.assertEqual(out.stdout.strip(), "EQUAL")
            out = subprocess.run([sys.executable, os.path.join(HERE, "raw_equal.py"), "pixel", b, "0", "0"],
                                 capture_output=True, text=True)
            self.assertEqual(out.stdout.strip(), "#636363")
            self.assertEqual(raw_equal.main([a, b, "0", "6"]), "DIFF 8 bbox 0,0-3,5")
            self.assertEqual(raw_equal.main([a]), "BADSIZE")

    def test_uniform_rows_are_one_colour(self):
        # CI 10b_margin: the paper between the text box and the footer's band holds nothing (raw_equal.py --uniform).
        with tempfile.TemporaryDirectory() as d:
            a, b = (os.path.join(d, n) for n in ("a.raw", "b.raw"))
            raw_image(a, 4, 6, [0, 10, 10, 10, 40, 50], header=12)
            self.assertEqual(raw_equal.uniform(a, 1, 4), "UNIFORM")
            self.assertEqual(raw_equal.uniform(a, 2, 3), "UNIFORM")
            self.assertEqual(raw_equal.uniform(a, 1, 5), "MIXED 4 bbox 0,4-3,4")
            self.assertEqual(raw_equal.uniform(a, 0, 2), "MIXED 4 bbox 0,1-3,1")
            self.assertEqual(raw_equal.uniform(a, 4, 9), "BADSIZE")
            self.assertEqual(raw_equal.uniform(a, 3, 3), "BADSIZE")
            # One stray pixel (a glyph's descender clipped a row too low) is named.
            data = bytearray(open(a, "rb").read())
            data[12 + (2 * 4 + 1) * 4] = 200  # (x 1, y 2) after the 12-byte header
            with open(b, "wb") as f:
                f.write(bytes(data))
            self.assertEqual(raw_equal.uniform(b, 1, 4), "MIXED 1 bbox 1,2-1,2")
            out = subprocess.run([sys.executable, os.path.join(HERE, "raw_equal.py"), "--uniform", b, "1", "4"],
                                 capture_output=True, text=True)
            self.assertEqual(out.stdout.strip(), "MIXED 1 bbox 1,2-1,2")
            out = subprocess.run([sys.executable, os.path.join(HERE, "raw_equal.py"), "--uniform", a, "1", "4"],
                                 capture_output=True, text=True)
            self.assertEqual(out.stdout.strip(), "UNIFORM")

    def test_ink_is_drawn_text_across_the_line(self):
        # CI 99: a page of Hanja the font draws blank is paper only; drawn text puts many pixels across the line.
        with tempfile.TemporaryDirectory() as d:
            a, b = (os.path.join(d, n) for n in ("a.raw", "b.raw"))
            raw_image(a, 4, 6, [255, 255, 255, 0, 255, 255])
            self.assertEqual(raw_equal.ink(a, 0, 3, 1, 1), "NOINK 0")
            self.assertEqual(raw_equal.ink(a, 0, 6, 4, 4), "INK 4 bbox 0,3-3,3")
            self.assertEqual(raw_equal.ink(a, 0, 6, 5, 4), "NOINK 4 bbox 0,3-3,3")  # too few pixels
            self.assertEqual(raw_equal.ink(a, 0, 9, 1, 1), "BADSIZE")
            # One dot (a stray mark, a tofu corner) is too narrow.
            with open(a, "rb") as f:
                data = bytearray(f.read())
            data[16 + (1 * 4 + 2) * 4] = 0  # (x 2, y 1) after the 16-byte header
            with open(b, "wb") as f:
                f.write(bytes(data))
            self.assertEqual(raw_equal.ink(b, 0, 3, 1, 2), "NOINK 1 bbox 2,1-2,1")
            out = subprocess.run([sys.executable, os.path.join(HERE, "raw_equal.py"), "ink", b, "0", "6", "5", "4"],
                                 capture_output=True, text=True)
            self.assertEqual(out.stdout.strip(), "INK 5 bbox 0,1-3,3")
            self.assertEqual(raw_equal.main(["ink", b, "0"]), "BADSIZE")


def raw_grid(path, grid):
    """A screencap raw file of grid[y][x] greys (16-byte header)."""
    h, w = len(grid), len(grid[0])
    with open(path, "wb") as f:
        f.write(struct.pack("<IIII", w, h, 1, 0))
        for row in grid:
            f.write(b"".join(bytes([v, v, v, 255]) for v in row))


GLYPH = [[0, 128, 0], [64, 255, 64], [0, 200, 30]]  # ink (0 = paper) of one 3 x 3 "glyph"


def crisp_page(w=70, h=30, lines=(5, 15), period=9, copies=6, x0=4, phase=None, edit=None):
    """A white page with text lines of `copies` glyphs `period` px apart from x0; phase(k, line) shifts copy k right by
    a pixel (a quarter-pixel glyph drawn at another phase), edit(grid) changes pixels afterwards."""
    grid = [[255] * w for _ in range(h)]
    for n, top in enumerate(lines):
        for k in range(copies):
            dx = phase(k, n) if phase else 0
            for y, row in enumerate(GLYPH):
                for x, ink in enumerate(row):
                    grid[top + y][x0 + k * period + x + dx] = 255 - ink
    if edit:
        edit(grid)
    return grid


class CrispTest(unittest.TestCase):
    # CI 100: hinted text on whole pixels repeats a repeated pattern pixel for pixel; unhinted quarter-pixel text does not.
    def crisp(self, grid, y0=0, y1=None):
        with tempfile.TemporaryDirectory() as d:
            a = os.path.join(d, "a.raw")
            raw_grid(a, grid)
            return raw_equal.crisp(a, y0, len(grid) if y1 is None else y1)

    def test_identical_copies_at_a_whole_pixel_period(self):
        r = self.crisp(crisp_page())
        self.assertTrue(r.startswith("CRISP lines 2 period 9 full/lit "), r)
        # Full (ink >= 200 of 255) over lit (>= 20): 255 and 200 of the six lit pixels per copy.
        self.assertEqual(r.split()[-1], "0.333")
        # The first and the last copy may differ (no neighbour's shadow or overhang there).
        def ends(g):
            for top in (5, 15):
                g[top][4 + 0] = 0
                g[top + 2][4 + 5 * 9 + 2] = 10
        self.assertTrue(self.crisp(crisp_page(edit=ends)).startswith("CRISP lines 2 period 9"))

    def test_copies_at_other_phases_are_soft(self):
        # The linear paint: fractional advances on quarter pixels draw some copies at another phase.
        r = self.crisp(crisp_page(phase=lambda k, n: 1 if k in (2, 3) else 0))
        self.assertTrue(r.startswith("SOFT lines 2 line 1 rows 5..7"), r)
        # One interior copy that differs in a single pixel is enough.
        r = self.crisp(crisp_page(edit=lambda g: g[6].__setitem__(4 + 2 * 9 + 1, 1)))
        self.assertTrue(r.startswith("SOFT lines 2 line 1"), r)

    def test_every_line_the_same_pixels(self):
        # Periodic lines that sit differently (a line drawn a pixel further right: another row profile or start) differ.
        r = self.crisp(crisp_page(phase=lambda k, n: n))
        self.assertEqual(r, "UNEVEN lines 2 line 2 rows 15..17 differs from line 1")
        # A different period in another line is soft (another advance).
        def other(g):
            for x in range(70):
                g[15][x] = g[16][x] = g[17][x] = 255
            for k in range(6):
                for y, row in enumerate(GLYPH):
                    for x, ink in enumerate(row):
                        g[15 + y][4 + k * 10 + x] = 255 - ink
        r = self.crisp(crisp_page(edit=other))
        self.assertTrue(r.startswith("SOFT lines 2 line 2"), r)

    def test_lines_cut_by_the_rows_do_not_count(self):
        self.assertEqual(self.crisp(crisp_page(), y0=6), "NOLINES 1")
        self.assertEqual(self.crisp(crisp_page(), y0=0, y1=17), "NOLINES 1")
        self.assertEqual(self.crisp(crisp_page(lines=(5,))), "NOLINES 1")
        self.assertEqual(self.crisp(crisp_page(), y0=0, y1=40), "BADSIZE")
        # Too few copies for four periods: no period found.
        r = self.crisp(crisp_page(copies=3))
        self.assertTrue(r.startswith("SOFT lines 2 line 1"), r)

    def test_command_line(self):
        with tempfile.TemporaryDirectory() as d:
            a = os.path.join(d, "a.raw")
            raw_grid(a, crisp_page())
            out = subprocess.run([sys.executable, os.path.join(HERE, "raw_equal.py"), "crisp", a, "0", "30"],
                                 capture_output=True, text=True)
            self.assertTrue(out.stdout.startswith("CRISP lines 2 period 9"), out.stdout)
            self.assertEqual(raw_equal.main(["crisp", a, "0"]), "BADSIZE")


class GlyphSamplesTest(unittest.TestCase):
    def test_glyph_samples_start_with_only_those_characters(self):
        # CI 99 checks ink in the top rows of the first page: they must hold nothing but the characters under test.
        with tempfile.TemporaryDirectory() as d:
            out = subprocess.run([sys.executable, os.path.join(HERE, "make_samples.py"), d],
                                 capture_output=True, text=True)
            self.assertEqual(out.returncode, 0, out.stderr)
            with open(os.path.join(d, "glyphs-hanja.txt"), encoding="utf-8", newline="") as f:
                hanja = [p for p in f.read().split("\r\n") if p]
            with open(os.path.join(d, "glyphs-hangul.txt"), encoding="utf-8", newline="") as f:
                hangul = [p for p in f.read().split("\r\n") if p]
            for p in hanja[:4]:
                self.assertEqual(len(p), 40)
                # KS X 1001 Hanja: two bytes each in EUC-KR (the old 나눔명조 OTF drew all 4,888 blank).
                self.assertTrue(all(0x4E00 <= ord(c) <= 0x9FFF and len(c.encode("euc-kr")) == 2 for c in p), p)
            for p in hangul[:4]:
                # Syllables outside KS X 1001's 2,350 (EUC-KR spells them with 8 bytes): blank in 학교안심 바른바탕.
                self.assertTrue(all(0xAC00 <= ord(c) <= 0xD7A3 and len(c.encode("euc-kr")) == 8 for c in p), p)
            self.assertIn("성(聖)과 속(俗)", hanja[4])
            self.assertEqual(hanja[5], "漢字 \U00020000")
            import zipfile
            with zipfile.ZipFile(os.path.join(d, "glyphs-hanja.epub")) as z:
                self.assertEqual(z.read("mimetype"), b"application/epub+zip")
                page = z.read("OEBPS/Text/ch1.xhtml").decode("utf-8")
            body = page[page.index("<body>") + 6:]
            self.assertTrue(body.startswith("<p>" + hanja[0] + "</p>"), body[:60])
            # CI 100: one-line paragraphs of one pattern (six "가o"), blank lines between, so no TXT option joins them.
            with open(os.path.join(d, "crisp.txt"), encoding="utf-8", newline="") as f:
                crisp = f.read()
            self.assertEqual(crisp, "\r\n\r\n".join(["가o" * 6] * 12) + "\r\n")


class RestoreBackupTest(unittest.TestCase):
    def test_crafted_backup(self):
        with tempfile.TemporaryDirectory() as d:
            out = subprocess.run([sys.executable, os.path.join(HERE, "make_samples.py"), d],
                                 capture_output=True, text=True)
            self.assertEqual(out.returncode, 0, out.stderr)
            name = "readeraplus-auto-0badc0de-20260929-2114.json"
            with open(os.path.join(d, name), encoding="utf-8") as f:
                data = json.load(f)
            self.assertEqual(data["format"], "readeraplus-backup")
            reader, app = data["settings"]["reader"], data["settings"]["app"]
            self.assertEqual((reader["r.marginLeftDp"], reader["r.marginRightDp"]), (18, 18))
            self.assertNotIn("r.marginBase", reader)
            # CI 97 reads 상하 여백 "0" after the restore through the conversion: R2's 16/16 without r.marginBaseV is
            # 40/40 from the edge, then 18/22 counted from the default status bands.
            self.assertEqual((reader["r.marginTopDp"], reader["r.marginBottomDp"]), (16, 16))
            self.assertNotIn("r.marginBaseV", reader)
            self.assertEqual(app["a.readMode"], "PAGED")
            book = data["books"][0]
            self.assertEqual(book["path"], "/sdcard/Download/sample.epub")
            self.assertEqual(book["size"], os.path.getsize(os.path.join(d, "sample.epub")))
            self.assertTrue(book["bookmarks"])
            self.assertGreater(book["posSection"] + book["posOffset"], 0)


if __name__ == "__main__":
    unittest.main()
