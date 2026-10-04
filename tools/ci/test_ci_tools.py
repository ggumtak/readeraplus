#!/usr/bin/env python3
"""Unit tests for the pure CI helpers: perf_log.py, find_node.py, ui_rows.py, hub_rows.py, raw_equal.py and the crafted
restore backup of make_samples.py. Run from the repository root: python3 -m unittest tools/ci/test_ci_tools.py"""
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
    <node index="2" text="아래 가운데: 없음" class="android.widget.TextView" content-desc="" bounds="[20,500][240,560]" />
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
        self.assertEqual(self.find("아래 가운데:", "contains"), "130 530")
        self.assertEqual(self.find("아래 가운데:"), "")

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
# 넘김·화면 설정 at density 2: section headers start at x 0, row titles and summaries at x 32 (16 dp row padding);
# kit summaries carry U+2060 word joiners between Hangul syllables (keepAll).
STATUS_ROWS = [
    ("상태 표시줄", "[0,209][720,311]", PLAIN),
    ("위 · 아래 줄의 왼쪽 · 가운데 · 오른쪽에 보일 정보를 고르세요.", "[0,311][720,427]", PLAIN),
    ("위 · 가운데", "[32,447][190,493]", PLAIN),
    ("챕⁠터 제⁠목", "[32,493][160,537]", PLAIN),
    ("아래 · 가운데", "[32,577][210,623]", PLAIN),
    ("없⁠음", "[32,623][110,667]", PLAIN),
    ("진행 막대", "[32,707][160,753]", PLAIN),
    ("화면 맨 아래에 읽은 위치를 가는 선과 점으로 표시", "[32,753][560,837]", PLAIN),
    ("", "[584,740][688,804]", SWITCH_ON),
    ("상태 표시 글자 크기", "[32,890][300,936]", PLAIN),  # a stepper row: no summary
    ("11sp", "[460,890][604,936]", PLAIN),
    ("볼륨 키 방향 반전", "[32,989][260,1035]", PLAIN),
    ("볼륨 위 키로 다음 페이지를 넘깁니다", "[32,1035][500,1079]", PLAIN),
    ("", "[584,1002][688,1066]", SWITCH_OFF),
    ("넘기는 방식", "[0,1119][720,1221]", PLAIN),  # the section header, then its row of the same name
    ("넘기는 방식", "[32,1241][190,1287]", PLAIN),
    ("스크롤", "[32,1287][100,1331]", PLAIN),
]


class UiRowsTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.page = self.write("page.xml", STATUS_ROWS)

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
        self.assertEqual(ui_rows.value(ns, "아래 · 가운데"), "없음")
        self.assertEqual(ui_rows.value(ns, "위 · 가운데"), "챕터 제목")
        self.assertEqual(ui_rows.value(ns, "볼륨 키 방향 반전"), "볼륨 위 키로 다음 페이지를 넘깁니다")

    def test_a_section_header_of_the_same_name_is_skipped(self):
        self.assertEqual(ui_rows.value(ui_rows.nodes(self.page), "넘기는 방식"), "스크롤")

    def test_no_summary_and_no_row(self):
        ns = ui_rows.nodes(self.page)
        self.assertIsNone(ui_rows.value(ns, "상태 표시 글자 크기"))  # the next row's title is 53 px lower
        self.assertIsNone(ui_rows.value(ns, "아래 · 오른쪽"))

    def test_checked_reads_the_switch_on_the_row(self):
        ns = ui_rows.nodes(self.page)
        self.assertEqual(ui_rows.checked(ns, "진행 막대"), "on")
        self.assertEqual(ui_rows.checked(ns, "볼륨 키 방향 반전"), "off")
        self.assertIsNone(ui_rows.checked(ns, "아래 · 가운데"))  # 진행 막대's switch is a row lower
        self.assertIsNone(ui_rows.checked(ns, "없는 행"))

    def test_cli_value_checked_and_missing(self):
        self.assertEqual(self.tool("value", self.page, "아래 · 가운데"), "없음")
        self.assertEqual(self.tool("checked", self.page, "진행 막대"), "on")
        self.assertEqual(self.tool("value", self.page, "아래 · 오른쪽"), "")
        self.assertEqual(self.tool("value", os.path.join(self.dir.name, "none.xml"), "아래 · 가운데"), "")

    def test_cli_values_take_the_first_dump_that_shows_the_row(self):
        lower = self.write("lower.xml", [
            ("아래 · 가운데", "[32,200][210,246]", PLAIN), ("쪽 번호", "[32,246][130,290]", PLAIN),
            ("아래 · 오른쪽", "[32,330][210,376]", PLAIN), ("시계 · 배터리", "[32,376][200,420]", PLAIN),
        ])
        self.assertEqual(self.tool("values", f"{self.page},{lower}", "위 · 가운데|아래 · 가운데|아래 · 오른쪽|위 · 왼쪽"),
                         "위 · 가운데=챕터 제목; 아래 · 가운데=없음; 아래 · 오른쪽=시계 · 배터리; 위 · 왼쪽=?")


# The 독서 노트 hub's 인용문 tab in CI 34 (86_notes_quotes.png, density 2): toolbar, tabs, filter chips, then one day
# header over two quotes of one word each, every quote with its meta line (the book's title in 《》).
HUB_ROWS = [
    ("독서 노트", "[104,75][520,133]", PLAIN),
    ("전체", "[0,161][120,257]", PLAIN), ("인용문", "[120,161][240,257]", PLAIN), ("메모", "[240,161][360,257]", PLAIN),
    ("모든 책", "[32,270][182,334]", PLAIN), ("최신순", "[198,270][340,334]", PLAIN),
    ("모든 색", "[357,270][507,334]", PLAIN),
    ("오늘 · 10월 4일 (일)", "[32,357][266,403]", PLAIN),
    ("“345”", "[32,432][110,478]", PLAIN),
    ("《sample-utf8》 · 프롤로그 · 2% · 01:42", "[32,488][476,524]", PLAIN),
    ("“Reader”", "[32,608][156,654]", PLAIN),
    ("《sample-utf8》 · 프롤로그 · 1% · 01:42", "[32,664][476,700]", PLAIN),
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
        self.assertEqual(self.tool("note", hub), "71 455")  # “345”, not the header nor a chip
        self.assertEqual(self.tool("day", hub), "149 380")

    def test_a_book_header_and_its_author_are_never_the_note(self):
        ns = ui_rows.nodes(self.write(HUB_ROWS[:7] + [
            ("《sample-utf8》 · 2", "[32,357][300,403]", PLAIN), ("테스트 작가", "[324,357][688,403]", PLAIN),
        ] + HUB_ROWS[8:]))
        self.assertEqual(hub_rows.pick(ns, "note"), (71, 455))
        self.assertIsNone(hub_rows.pick(ns, "day"))

    def test_headers_by_their_shapes(self):
        for t in ("오늘 · 10월 4일 (일)", "어제 · 9월 29일 (월)", "9월 28일 (일)", "2025년 12월 3일 (수)", "《제목》 · 12"):
            self.assertTrue(hub_rows.header(t), t)
        for t in ("“345”", "《sample-utf8》 · 프롤로그 · 2% · 01:42", "《sample-utf8》(휴지통) · 37%", "모든 책"):
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
            self.assertEqual(app["a.readMode"], "PAGED")
            book = data["books"][0]
            self.assertEqual(book["path"], "/sdcard/Download/sample.epub")
            self.assertEqual(book["size"], os.path.getsize(os.path.join(d, "sample.epub")))
            self.assertTrue(book["bookmarks"])
            self.assertGreater(book["posSection"] + book["posOffset"], 0)


if __name__ == "__main__":
    unittest.main()
