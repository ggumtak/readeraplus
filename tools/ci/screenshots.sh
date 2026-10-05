#!/usr/bin/env bash
# Runs inside the emulator job: installs the APK, pushes original sample books, drives the app with adb and
# saves screenshots + logs into ./shots. Never fails the job on a UI step (best effort), but records crashes.
# The later steps run through `step`: each in its own shell with a time limit, from a known state (the app restarted
# on the library or on a book), logging what it could not find and moving on.
# The shots follow docs/next/wave2/PLAN.md §5.3 in its run order. Every expectation the script can measure becomes a
# `CHECK <n> PASS|FAIL <reason>` line in shots/steps.txt; none of them fails the job.
set -u
PKG=com.ggumtak.readeraplus
APK=$(ls dist/*.apk | head -1)
RESTORE_BACKUP=readeraplus-auto-0badc0de-20260929-2114.json # written by make_samples.py (S §3.9)
mkdir -p shots samples
log() { echo "== $*" | tee -a shots/steps.txt; }
shot() { sleep "${2:-2}"; adb exec-out screencap -p > "shots/$1.png"; log "shot $1"; }
rawshot() { adb exec-out screencap > "shots/$1.raw"; }
perf_mark() {
  adb logcat -d -v monotonic -s RAPerf:D '*:S' > "shots/perf_$1.txt"
  log "PERF $1 $(grep ': show ' "shots/perf_$1.txt" | tail -1)" # a page's show line, never "chrome show …" (13v)
}

# ------------------------------------------------------------------ CHECK lines

check() { # check <n> <0|1 = PASS|FAIL> <reason>: one CHECK line
  if [ "$2" -eq 0 ]; then log "CHECK $1 PASS $3"; else log "CHECK $1 FAIL $3"; fi
}
perf_check() { # perf_check <n> first_is|no_relayout|same_start <mark A> <mark B> (tools/ci/perf_log.py)
  local r; r=$(python3 tools/ci/perf_log.py "$2" "$3" "$4")
  log "CHECK $1 ${r:-FAIL perf_log.py printed nothing} ($2 $3 $4)"
  [ "${r%% *}" = PASS ]
}
no_relayout() { perf_check "$1" no_relayout "$2" "$3"; } # no_relayout <n> <mark A> <mark B>
pv_rows() { # "top bottom" of the PageView in screen rows (dumpsys bounds); the whole screen when it can't be found
  local b
  adb shell dumpsys activity top > /tmp/top.txt 2>/dev/null
  b=$(python3 tools/ci/perf_log.py pv_bounds /tmp/top.txt)
  echo "${b:-0 1440}"
}
content_rows() { # "Y0 Y1" of the text box: pv + 80 … pv + 1360 (valid only at 상하 여백 "0", PLAN §5.3, with the
  # default bands: the header's 22 dp + 18 dp, the progress line's 16 dp + 24 dp; the emulator has no display cutout). The
  # margins count from the status bands since 2026-10-05: with footer items (36 dp, 52 on) the box ends 40 px higher and
  # the rows below it down to pv + 1360 are margin paper, still fine for an EQUAL of the same page.
  local pv; pv=$(pv_rows); pv=${pv%% *}
  echo "$((pv + 80)) $((pv + 1360 > 1440 ? 1440 : pv + 1360))"
}
top_rows() { # "Y0 Y1" of the text box's upper half, pv + 80 … pv + 720: its first lines stay put when a status band that
  # comes or goes moves only the box's bottom and the page keeps its first character (10b, 52, 53)
  local pv; pv=$(pv_rows); pv=${pv%% *}
  echo "$((pv + 80)) $((pv + 720))"
}
band_check() { # band_check <n> <raw A> <raw B> <Y0> <Y1>: a status band that must stay put while the text scrolls. EQUAL
  # passes, and so does a DIFF under 1500 px (its live values changed: 쪽 번호, the clock, the progress dot); scrolled text
  # in the band differs by thousands (about 90 px per text row).
  local r n
  r=$(python3 tools/ci/raw_equal.py "shots/$2.raw" "shots/$3.raw" "$4" "$5")
  case "$r" in
    EQUAL) check "$1" 0 "pixels EQUAL $2 vs $3 rows $4..$5";;
    DIFF*) n=${r#DIFF }; n=${n%% *}
      if [ "$n" -lt 1500 ]; then check "$1" 0 "band fixed, only its live values changed ($r) rows $4..$5"
      else check "$1" 1 "band changed like moving text ($r) rows $4..$5"; fi;;
    *) check "$1" 1 "pixels ${r:-error} $2 vs $3 rows $4..$5";;
  esac
}
raw_check() { # raw_check <n> <raw A> <raw B> <Y0 Y1 | content | contenttop | pageview | belowheader>: raw_equal.py over
  # those rows, as a CHECK. belowheader: the page view but its top 48 rows, where the default header (MaruViewer's line,
  # 2026-10-05) draws the clock 8 px (4 dp) below the top (its glyph box ends at row 40 at 11 sp, its band at 44): a minute
  # may pass between two shots. contenttop: top_rows.
  local r y0 y1
  case "$4" in
    content) read -r y0 y1 <<<"$(content_rows)" ;;
    contenttop) read -r y0 y1 <<<"$(top_rows)" ;;
    pageview) read -r y0 y1 <<<"$(pv_rows)" ;;
    belowheader) read -r y0 y1 <<<"$(pv_rows)"; y0=$((y0 + 48)) ;;
    *) y0=$4; y1=$5 ;;
  esac
  r=$(python3 tools/ci/raw_equal.py "shots/$2.raw" "shots/$3.raw" "$y0" "$y1")
  if [ "$r" = EQUAL ]; then check "$1" 0 "pixels EQUAL $2 vs $3 rows $y0..$y1"
  else check "$1" 1 "pixels ${r:-error} $2 vs $3 rows $y0..$y1"; fi
  [ "$r" = EQUAL ]
}
raw_pixel() { python3 tools/ci/raw_equal.py pixel "shots/$1.raw" "$2" "$3"; } # raw_pixel <raw> x y: "#RRGGBB"
near_check() { # near_check <n> <colour> <expected> <what>: within 2 levels in every channel, as a CHECK
  local r; r=$(python3 tools/ci/raw_equal.py near "$2" "$3" 2)
  [ "$r" = PASS ]; check "$1" $? "$4 is $2 (expected $3)"
}
darker_check() { # darker_check <n> <colour> <page colour> <levels> <what>: a shadow that much darker, as a CHECK
  local r; r=$(python3 tools/ci/raw_equal.py darker "$2" "$3" "$4")
  [ "$r" = PASS ]; check "$1" $? "$5 is $2 against the page $3 (at least $4 levels darker)"
}

# ------------------------------------------------------------------ UI helpers

dump() { # the active window's UI tree into /tmp/ui.xml; a failed dump leaves no file (never a stale tree)
  rm -f /tmp/ui.xml
  local i
  for i in 1 2 3; do
    adb shell rm -f /sdcard/ui.xml >/dev/null 2>&1
    adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
    adb pull /sdcard/ui.xml /tmp/ui.xml >/dev/null 2>&1 && [ -s /tmp/ui.xml ] && return 0
    sleep 1
  done
  log "ui dump failed"; return 1
}
dump_all() { # every window on screen into /tmp/ui.xml (uiautomator dump --windows): the selection bar is the app's only
  # NON-focusable PopupWindow (touches outside it reach the page and the handles), which the plain dump (the focused
  # window only) never lists
  rm -f /tmp/ui.xml
  local i
  for i in 1 2 3; do
    adb shell rm -f /sdcard/ui.xml >/dev/null 2>&1
    adb shell uiautomator dump --windows /sdcard/ui.xml >/dev/null 2>&1
    adb pull /sdcard/ui.xml /tmp/ui.xml >/dev/null 2>&1 && [ -s /tmp/ui.xml ] && return 0
    sleep 1
  done
  log "ui dump --windows failed"; return 1
}
xy_of() { python3 tools/ci/find_node.py /tmp/ui.xml "$1" "${2:-exact}" "${3:-0}"; } # "x y" in the last dump
box_of() { python3 tools/ci/find_node.py /tmp/ui.xml "$1" "${2:-exact}" "${3:-0}" --box; } # "x1 y1 x2 y2"
has() { [ -n "$(xy_of "$@")" ]; }
page_label() { # the reader's page label in the last dump: its description "페이지 이동, N / M" (U §8.2)
  python3 - <<'PY'
import xml.etree.ElementTree as ET
try:
  nodes=list(ET.parse('/tmp/ui.xml').getroot().iter('node'))
except Exception:
  nodes=[]
for n in nodes:
  d=n.get('content-desc') or ''
  if d.startswith('페이지 이동'): print(d); break
PY
}
page_no() { local l n; l=$(page_label); n=${l#*, }; n=${n%% /*}; case "$n" in ''|*[!0-9]*) echo "";; *) echo "$n";; esac; }
stepper_value() { # stepper_value "상하 여백": the value text between its 줄이기 and 늘리기 buttons in the last dump
  python3 - "$1" <<'PY'
import re, sys, xml.etree.ElementTree as ET
t=sys.argv[1]
def box(n):
  m=re.fullmatch(r'\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]',n.get('bounds',''))
  return tuple(map(int,m.groups())) if m else None
try:
  nodes=[(n,box(n)) for n in ET.parse('/tmp/ui.xml').getroot().iter('node')]
except Exception:
  nodes=[]
minus=[b for n,b in nodes if b and n.get('content-desc')==t+' 줄이기']
plus=[b for n,b in nodes if b and n.get('content-desc')==t+' 늘리기']
if minus and plus:
  a,b=minus[0],plus[0]
  for n,c in nodes:
    v=n.get('text') or ''
    if v and c and c[0]>=a[2]-4 and c[2]<=b[0]+4 and c[1]<b[3] and c[3]>a[1]: print(v); break
PY
}
tap_label() { # tap_label "label" [exact|contains] [index: 0 = first match, -1 = last]
  dump; local xy; xy=$(xy_of "$1" "${2:-exact}" "${3:-0}")
  if [ -n "$xy" ]; then adb shell input tap $xy; log "tap '$1' at $xy"; return 0; fi
  log "NOT FOUND '$1'"; return 1
}
sel_tap() { # sel_tap "label" [exact|contains]: a cell of the selection bar (found in an all-windows dump)
  dump_all; local xy; xy=$(xy_of "$1" "${2:-exact}")
  if [ -n "$xy" ]; then adb shell input tap $xy; log "tap '$1' at $xy (selection bar)"; return 0; fi
  log "NOT FOUND '$1' in the selection bar"; return 1
}
on_top() { adb shell dumpsys activity activities | grep -m1 -E "topResumedActivity=|mResumedActivity" | grep -q "$1"; } # on_top <Activity>
back() { adb shell input keyevent KEYCODE_BACK; sleep 1; }
tap_xy() { [ -n "${1:-}" ] || return 1; adb shell input tap $1; log "tap at $1"; }
longpress() { adb shell input swipe "$1" "$2" "$1" "$2" "${3:-1500}"; log "long-press at $1 $2"; }
drag() { # drag x y_from y_to: a slow vertical drag that rests before the finger lifts, so the list doesn't coast on
  local x=$1 a=$2 b=$3 s
  if [ "$b" -lt "$a" ]; then s=$((a - 40)); else s=$((a + 40)); fi
  adb shell "input motionevent DOWN $x $a && input motionevent MOVE $x $s && input motionevent MOVE $x $b && sleep 1 && input motionevent UP $x $b" >/dev/null 2>&1 \
    || { adb shell input motionevent UP "$x" "$b" >/dev/null 2>&1; adb shell input swipe "$x" "$a" "$x" "$b" 1500; }
}
align() { # align "label" y [exact|contains]: drags the label (on screen in the last dump) up to about y
  local xy y0 y1
  xy=$(xy_of "$1" "${3:-exact}"); [ -n "$xy" ] || return 1
  y0=${xy#* }
  [ $((y0 - $2)) -lt 60 ] && return 0
  drag 100 "$y0" "$2"; sleep 1
  dump || return 1
  xy=$(xy_of "$1" "${3:-exact}")
  [ -z "$xy" ] && return 0 # past the top: it moved
  y1=${xy#* }
  [ $((y0 - y1)) -gt 30 ] && return 0
  # The injected drag moved nothing: a slow swipe that ends lower (its short coast takes the label on up).
  adb shell input swipe 100 "$y0" 100 $(($2 + 120)) 2000; sleep 1
}
popups() { adb shell dumpsys window windows 2>/dev/null | grep -cE 'Window #[0-9]+ Window\{.*PopupWindow'; } # on screen

# ------------------------------------------------------------------ known states and the step runner

restart_app() { # restart_app <am start args>: HOME first (onPause saves the position and reading time), then a cold start
  adb shell input keyevent KEYCODE_HOME; sleep 1
  adb shell am force-stop $PKG
  adb shell am start -W "$@" | tee -a shots/steps.txt
}
restart_library() { restart_app -n $PKG/.ui.library.LibraryActivity; sleep 5; library_home; }
library_home() { # back to 모든 책, whatever shelf an earlier step left saved (library.shelf survives a force-stop)
  # With the drawer closed (GONE), "모든 책" in the dump is the toolbar title.
  dump || return 0
  has "모든 책" && return 0
  tap_label "메뉴" || return 0 # ☰: the first "메뉴"
  sleep 1
  tap_label "모든 책" || back
  sleep 2
}
fresh_reader() { # fresh_reader file mime: the app cold-started on that book (no bars, panel or dialog)
  restart_app -a android.intent.action.VIEW -t "$2" -d "file:///sdcard/Download/$1" -n $PKG/.reader.ReaderActivity
  sleep 4
}
step() { # step name function: one best-effort UI step in its own shell (STEP_TIMEOUT s, 300 by default); never fails the job
  # No new step after STEPS_UNTIL seconds of this script, so a stuck emulator can't run the job into its time limit.
  if [ "$SECONDS" -gt "${STEPS_UNTIL:-3000}" ]; then log "step $1 skipped (out of time)"; return 0; fi
  log "step $1"
  local rc
  if command -v timeout >/dev/null; then timeout "${STEP_TIMEOUT:-300}" bash -c "$2"; rc=$?; else ( "$2" ); rc=$?; fi
  if [ "$rc" -eq 0 ]; then log "step $1 done"; else
    log "step $1 incomplete (exit $rc), continuing"
    log "CHECK $1 FAIL step incomplete (exit $rc): its later expectations were not reached"
    cp /tmp/ui.xml "shots/ui_fail_$1.xml" 2>/dev/null
  fi
  return 0
}
list_swipe() { # list_swipe down|up: one swipe INSIDE the active scroll container (its largest scrollable box in the last
  # dump): down shows what is below, moving the list up by 60 % of the box (about 800 px on a settings page)
  local gesture
  gesture=$(python3 - "$1" <<'PY'
import re, sys, xml.etree.ElementTree as ET
up = sys.argv[1] == 'up'
boxes=[]
try:
    nodes = list(ET.parse('/tmp/ui.xml').getroot().iter('node'))
except Exception:
    nodes = []
for n in nodes:
    if n.get('scrollable')!='true': continue
    m=re.fullmatch(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]',n.get('bounds',''))
    if not m: continue
    x0,y0,x1,y1=map(int,m.groups())
    if y1-y0>100: boxes.append(((x1-x0)*(y1-y0),x0,y0,x1,y1))
if boxes:
    _,x0,y0,x1,y1=max(boxes); x=x0+min(80,(x1-x0)//2);h=y1-y0
    a,b=y0+int(h*.85),y0+int(h*.25)
else:
    x,a,b=100,1150,450
print(x,b,x,a) if up else print(x,a,x,b)
PY
  )
  adb shell input swipe $gesture 1000; sleep 1
}
scroll_find() { # scroll_find "label" [exact|contains]: scroll INSIDE the active scroll container (a page, a dialog's list)
  local i y prev="" last=""
  XY=""
  for i in $(seq 1 14); do
    dump || return 1
    [ "$i" -eq 9 ] && last=$(bottom_texts) # where the swipes down ended, for the NOT FOUND line
    XY=$(xy_of "$1" "${2:-exact}")
    if [ -n "$XY" ]; then
      y=${XY#* }
      [ "$y" -ge 150 ] && [ "$y" -le 1250 ] && return 0
      # The end of the list: the last swipe down left the label below the window, where it was. It is on screen, so
      # it is taken there (CI 30 50d: 고급, then the last row of the old 넘김·화면 설정, rested just under y 1250 at the
      # page's end).
      if [ "$y" -gt 1250 ] && [ "$y" = "$prev" ] && [ "$i" -le 9 ]; then log "scroll_find: '$1' at the end of the list (y $y)"; return 0; fi
      prev=$y
    else
      prev=""
    fi
    # Down first; past 8 swipes (the end of the list) back up, for a row above the first one shown.
    if [ "$i" -gt 8 ]; then list_swipe up; else list_swipe down; fi
  done
  log "NOT FOUND '$1' after scrolling (lowest rows after the swipes down: $last)"; XY=""; return 1
}
bottom_texts() { # the 3 lowest texts of the last dump with their centre y ("text@y; ...")
  python3 - <<'PY'
import re, xml.etree.ElementTree as ET
rows=[]
try:
  for n in ET.parse('/tmp/ui.xml').getroot().iter('node'):
    t=(n.get('text') or '').replace('\u2060','')
    m=re.fullmatch(r'\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]',n.get('bounds',''))
    if t and m: rows.append(((int(m[2])+int(m[4]))//2,t[:24]))
except Exception:
  pass
print('; '.join(f'{t}@{y}' for y,t in sorted(rows)[-3:]))
PY
}

first_title_stamp() {
  python3 - <<'PY'
import re, xml.etree.ElementTree as ET
rows=[]
for n in ET.parse('/tmp/ui.xml').getroot().iter('node'):
  text=n.get('text',''); b=n.get('bounds','')
  m=re.fullmatch(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]',b)
  if text and n.get('class')=='android.widget.TextView' and m and int(m[2])>=160:
    rows.append((int(m[2]),int(m[1]),text,b))
if rows:
  r=min(rows); print(r[2]+' '+r[3])
PY
}
book_menu_open() { has "책 정보"; } # the library's book menu (its 책 정보 item; never 문서, the glossary)
library_more_guard() { # 41 + CHECK 41, 41b (N H0): ⋮ taps open the menu and never move the list
  local before after xy x y opened
  set_list_mode "자세히" || return 1
  dump || return 1
  before=$(first_title_stamp); xy=$(xy_of "책 메뉴")
  [ -n "$xy" ] || { check 41 1 "book menu (책 메뉴) missing"; return 1; }
  tap_xy "$xy"; sleep 1; dump
  opened=0; book_menu_open && opened=1
  shot 41_library_more 0; back; dump
  after=$(first_title_stamp)
  if [ "$opened" -eq 1 ] && [ -n "$before" ] && [ "$before" = "$after" ]; then
    check 41 0 "menu opened and the first title stayed at $after"
  else check 41 1 "menu=$opened before='$before' after='$after'"; fi
  xy=$(xy_of "책 메뉴"); [ -n "$xy" ] || return 1
  x=${xy% *}; y=${xy#* }
  adb shell input swipe "$x" "$y" "$((x+2))" "$((y+6))" 150
  sleep 1; dump; opened=0; book_menu_open && opened=1
  back; dump; after=$(first_title_stamp)
  if [ "$opened" -eq 1 ] && [ "$before" = "$after" ]; then check 41b 0 "a 6 px roll opens the menu without seeking"
  else check 41b 1 "menu=$opened before='$before' after='$after'"; fi
  adb shell input swipe 700 1200 700 1190 300
  log "41: strip drag completed"
}

# ------------------------------------------------------------------ reader helpers

chrome_open() { dump && has "페이지 이동" contains; } # the reader's bars are on screen (the page label)
show_chrome() { # the reader's bars; the blind tap only while the reader is on top (CI 29: a failed 67 left the library's
  # drawer open, 68's tap at 360 720 hit its 작가 row, and every later library start opened on that saved shelf)
  chrome_open && return 0
  on_top ReaderActivity || { log "show_chrome: ReaderActivity not on top"; return 1; }
  # Never a blind tap into a popup left open (CI 30 14d: the tap landed on the reading-settings popup's rows)
  if popup_focused; then log "show_chrome: a popup has the focus, BACK first"; back; chrome_open && return 0; fi
  adb shell input tap 360 720; sleep 2; chrome_open
}
select_at() { # select_at x y: long-press a word; on blank space (leading, a blank line, the end of a short line) nothing is
  # selected and no page turns, so up to 5 more points 48 px lower are tried; SEL_Y = the y that selected
  local x=$1 y=$2 i
  for i in 0 1 2 3 4 5; do
    SEL_Y=$((y + 48 * i))
    adb shell input swipe "$x" $SEL_Y "$x" $SEL_Y 900; sleep 2
    dump_all && has "복사" && { [ "$i" -eq 0 ] || log "selected at $x $SEL_Y"; return 0; }
  done
  log "no selection bar after long-presses at $x $y..$SEL_Y"; return 1
}
hide_chrome() { # closes the go-to dialog / the reader's bars while they show (BACK without them would leave the book)
  local i
  for i in 1 2 3; do
    dump || return 1
    has "페이지 이동" contains || return 0
    back
  done
}
reader_more() { # reader_more "item": the reader's ⋮ (content description 더보기) → item
  show_chrome || return 1
  tap_label "더보기" || return 1
  sleep 1
  tap_label "$1" || { back; return 1; }
  sleep 3
}
open_popup() { # the quick reading options (⚙, content description 읽기 설정): since 68aa271 a top bar "전체 읽기 설정 ›"
  # · 닫기, the 글자 크기 · 굵기 · 줄 간격 · 문단 간격 · 좌우 여백 · 상하 여백 steppers and 글꼴 (384 dp: no 더보기, no
  # scrolling). A 설정 a failed step left in front is left first.
  if on_top SettingsActivity; then log "open_popup: 설정 is still in front, leaving it first"; leave_settings; fi
  show_chrome || return 1
  tap_label "읽기 설정" contains || return 1
  sleep 2; dump || return 1
  has "글자 크기" contains && has "전체 읽기 설정" || { log "the quick options popup is not the active window"; return 1; }
}
popup_focused() { # a focusable PopupWindow (the quick options popup, its 글꼴 list, a menu) has the input focus
  adb shell dumpsys window 2>/dev/null | grep -m1 -E 'mCurrentFocus=' | grep -q 'PopupWindow'
}
close_popup() { # one BACK closes the popup (and the bars); BACK again while a popup still has the focus, then the bars
  # CI 30 14d: a BACK sent right after a drop-down entry was tapped reached the list being dismissed, the settings popup
  # stayed open, and VOLUME_UP, the center tap and 15's 목차 all went to the popup.
  local i
  back; sleep 2
  for i in 1 2; do
    popup_focused || break
    log "close_popup: a popup still has the focus, BACK again"
    back; sleep 2
  done
  hide_chrome; sleep 1
}
margins_zero() { # margins_zero <n>: on 설정 → 읽기 설정 (open; the quick options show the same two steppers since
  # 68aa271, CHECK 14q), 좌우 여백 and 상하 여백 both read "0" (S §2.4, A), each from the dump of the scroll_find that put
  # its stepper on screen. No align: on settings pages it dragged the list past both rows (CI 34; its dump rightly had
  # neither).
  local l v=""
  scroll_find "좌우 여백 늘리기" || { check "$1" 1 "no 좌우 여백 stepper"; return 1; }
  l=$(stepper_value "좌우 여백")
  scroll_find "상하 여백 늘리기" && v=$(stepper_value "상하 여백")
  [ "$l" = 0 ] && [ "$v" = 0 ]; check "$1" $? "좌우 여백 '$l', 상하 여백 '$v'"
}
popup_tap() { tap_label "$1" "${2:-exact}" || return 1; sleep "${3:-2}"; } # a button of the quick options (they never scroll)
choose() { tap_label "$1" || tap_label "$1" contains; } # an item of an open chooser list
chooser_open() { dump && { grep -q 'select_dialog_listview' /tmp/ui.xml || has "취소"; }; } # an AlertDialog chooser in front
choose_item() { # choose_item "item": an item of the open chooser (exact, else contains), scrolled into view inside the dialog
  # when its list is taller than the dialog (the 12 status items); else BACK cancels the chooser
  choose "$1" && return 0
  if chooser_open && scroll_find "$1" contains; then tap_xy "$XY"; return 0; fi
  chooser_open && back
  return 1
}
slot_row() { # slot_row "아래 가운데": that status slot's row on 화면·밝기 for ui_rows.py ("아래쪽 상태 표시줄 › 가운데"):
  # since the 2026-10-04 review each band is a section of its own and its three rows say only 왼쪽 / 가운데 / 오른쪽
  echo "${1%% *}쪽 상태 표시줄 › ${1#* }"
}
slot_xy() { python3 tools/ci/ui_rows.py xy /tmp/ui.xml "$(slot_row "$1")"; } # "x y" of a slot row in the last dump
missing_slots() { # missing_slots "아래 왼쪽" …: the slots whose rows the last dump does not show, joined with ", "
  local t out=""
  for t in "$@"; do [ -n "$(slot_xy "$t")" ] || out="$out, $t"; done
  echo "${out#, }"
}
present_slots() { # present_slots "위 왼쪽" …: the slots whose rows the last dump shows, joined with ", "
  local t out=""
  for t in "$@"; do [ -n "$(slot_xy "$t")" ] && out="$out, $t"; done
  echo "${out#, }"
}
set_slot() { # set_slot "아래 가운데" "쪽 번호": a status slot of 화면·밝기 (open): its band's header found by scrolling, the
  # slot's row under it ("가운데" below "아래쪽 상태 표시줄") and an item of its chooser ("쪽 번호 (12 / 3259)": contains,
  # the chooser's title says "아래 가운데"); the row's value is logged
  local row; row=$(slot_row "$1")
  scroll_find "${row% › *}" || return 1
  XY=$(slot_xy "$1")
  [ -n "$XY" ] || { log "slot '$1': no row under '${row% › *}' on screen"; return 1; }
  tap_xy "$XY" || return 1
  sleep 2
  choose_item "$2" || return 1
  sleep 2
  dump && log "slot '$1' reads '$(row_value "$row")'"
  return 0
}
seek_to() { # seek_to <from %> <to %>: drags the chrome's seek bar (open) from one fraction to another and releases
  local b x0 y0 x1 y1 y
  dump || return 1
  b=$(box_of SeekBar class -1); [ -n "$b" ] || { log "no seek bar"; return 1; }
  read -r x0 y0 x1 y1 <<<"$b"; y=$(((y0 + y1) / 2))
  adb shell input swipe $((x0 + (x1 - x0) * $1 / 100)) $y $((x0 + (x1 - x0) * $2 / 100)) $y 600
  log "seek $1% -> $2%"; sleep 2
}
open_settings() { # 설정 (SettingsActivity is not exported) from the library, with its whole main list (읽기 화면 · 조작·기능 ·
  # 서재 · 책 가져오기 · 기타): the toolbar's ⋮ → 설정 (library_more). The drawer's 설정 row (next to last) is below the
  # fold of a 720 dp-high screen.
  restart_library
  library_more "설정" || return 1
  sleep 2
}
open_settings_page() { # open_settings_page "page": 설정 from the library → that row of the main list
  open_settings || return 1
  scroll_find "$1" || return 1
  tap_xy "$XY"; sleep 3
}
open_turning_page() { open_settings_page "넘기기·터치·키"; } # 넘기는 방식, 스크롤 움직임, 볼륨 키 (68aa271)
open_screen_page() { open_settings_page "화면·밝기"; } # 위쪽 / 아래쪽 상태 표시줄 (slots, 진행 막대), 인용문 색 표시, 밝기
library_more() { # library_more "item" [exact|contains]: the library toolbar's ⋮ (described "더보기" since a3b8826; the ☰
  # is "메뉴") → item. The ⋮ holds the library's own work, in groups (정렬, 보기 | 파일 열기, Wi-Fi로 책 받기 | 지금 스캔,
  # 스캔 폴더 추가 | 설정); the drawer is for moving between shelves.
  tap_label "더보기" || return 1
  sleep 1
  tap_label "$1" "${2:-exact}" || { back; return 1; }
}
pick_setting() { # pick_setting "row" "choice" [exact|contains] [index]: a settings row (found by scrolling; index -1 = its
  # last match on screen, past a section header of the same name, as the old 넘김·화면 설정's 넘기는 방식 had) and an item
  # of its chooser (exact, else contains: choosers read "값 (설명)" and mark the default "(기본)" since 68aa271)
  scroll_find "$1" "${3:-contains}" || return 1
  [ -n "${4:-}" ] && XY=$(xy_of "$1" "${3:-contains}" "$4")
  tap_xy "$XY" || return 1
  sleep 2
  choose_item "$2" || return 1
  sleep 2
}

# ------------------------------------------------------------------ 설정 over the open book (672e85d, 68aa271)
# The quick options keep seven rows (the four steppers, 좌우 여백 · 상하 여백 since 68aa271, 글꼴). Everything else is a
# 설정 row: 페이지 나눔, 정렬 … on 읽기 설정 (the popup's "전체 읽기 설정"); since 68aa271 the old 넘김·화면 설정 is three
# pages of 설정's main list: 넘기기·터치·키 (넘기는 방식, 스크롤 움직임, 볼륨 키), 화면·밝기 (위쪽 / 아래쪽 상태 표시줄: the
# slots and 진행 막대; 인용문 색 표시; 밝기) and e-ink 새로고침. The reader's ⋮ → 설정 shows the main list without the 서재
# and 책 가져오기 groups, 읽기 기록, 백업·복원 and 캐시 비우기 (R3-book-context-main): steps that need those (목록 넘기기,
# 백업·복원) open 설정 from the library. Rows save at once; the reader applies what changed ONCE when it is back in front
# (onResume), keeping the page's first character. So a step changes the row there over the open book (never through the
# library, which would reopen it), leaves 설정 with BACK and checks the book as it did with the popup.

open_reading_page() { # ⚙ → 전체 읽기 설정: 설정 → 읽기 설정 over the open book, the only page of its stack (one BACK leaves)
  open_popup || return 1
  tap_label "전체 읽기 설정" || { close_popup; return 1; }
  sleep 3
  reading_page_shown || { log "전체 읽기 설정 did not bring 설정 → 읽기 설정 to the front"; leave_settings; return 1; }
}
reading_page_shown() { # 설정 → 읽기 설정 in front: its toolbar title and its first section, 스타일 (the main list has neither)
  on_top SettingsActivity && dump && has "읽기 설정" && has "스타일"
}
open_page_over_reader() { # open_page_over_reader "page" "row": the reader's ⋮ → 설정 → that page of the 읽기 group over the
  # open book, checked by its toolbar title and one of its own rows (neither is an exact text of the main list): two pages
  # in 설정's stack, two BACKs to the book. (읽기 설정 opened from ⚙ links 모든 설정 too, but at its end: a page of swipes
  # on every trip.)
  if on_top SettingsActivity; then log "open_page_over_reader: 설정 is still in front, leaving it first"; leave_settings; fi
  reader_more "설정" || return 1
  on_top SettingsActivity || { log "⋮ → 설정 did not bring 설정 to the front"; return 1; }
  scroll_find "$1" || { leave_settings; return 1; }
  tap_xy "$XY"; sleep 3
  dump && has "$1" && has "$2" && return 0
  log "$1 did not open from 설정"; leave_settings; return 1
}
open_turning_over_reader() { open_page_over_reader "넘기기·터치·키" "넘기는 방식"; } # 넘기는 방식, 스크롤 움직임, 볼륨 키
open_screen_over_reader() { open_page_over_reader "화면·밝기" "위쪽 상태 표시줄"; } # the status slots and 진행 막대 (its first sections)
leave_settings() { # BACK while 설정 is in front (one per page of its stack, one more for a chooser left open), then the
  # book must be in front with its bars closed. The guard before each BACK matters: one BACK too many would close the book.
  local i n=0
  for i in 1 2 3 4; do
    on_top SettingsActivity || break
    back; sleep 1; n=$((n + 1))
  done
  if ! on_top ReaderActivity; then log "leave_settings: the reader is not in front after $n BACKs"; return 1; fi
  log "left 설정 with $n BACKs"
  hide_chrome
}
row_value() { python3 tools/ci/ui_rows.py value /tmp/ui.xml "$1"; } # a settings row's summary (its value) in the last dump
row_checked() { python3 tools/ci/ui_rows.py checked /tmp/ui.xml "$1"; } # "on" / "off": a toggle row's switch in the last dump
set_toggle() { # set_toggle "title" on|off: a toggle row of the open settings page (found by scrolling), tapped only when its
  # switch reads otherwise; false unless it then reads $2 (a switch the dump does not show is tapped once, blind)
  local s
  scroll_find "$1" || return 1
  s=$(row_checked "$1")
  if [ "$s" = "$2" ]; then log "toggle '$1' already $2"; return 0; fi
  tap_xy "$XY"; sleep 2
  dump || return 1
  if [ -z "$s" ]; then log "toggle '$1': no switch in the dump, tapped once (now '$(row_checked "$1")')"; return 0; fi
  s=$(row_checked "$1"); log "toggle '$1' -> '$s'"
  [ "$s" = "$2" ]
}
missing() { # missing "label" …: the labels the last dump lacks (exact), quoted ("" = all there)
  local t out=""
  for t in "$@"; do has "$t" || out="$out '$t'"; done
  echo "${out# }"
}
present() { # present "label" …: the labels the last dump shows (exact), ", "-joined in the order given
  local t out=""
  for t in "$@"; do has "$t" && out="$out, $t"; done
  echo "${out#, }"
}
status_row() { # status_row "title": a status row in the last dump: a slot's value ("아래 가운데": the row 가운데 under
  # 아래쪽 상태 표시줄), 진행 막대's switch (on|off)
  if [ "$1" = "진행 막대" ]; then row_checked "$1"; else row_value "$(slot_row "$1")"; fi
}
status_rows() { # STATUS = "위 왼쪽=…; …; 아래 오른쪽=…; 진행 막대=on|off", read top down on 화면·밝기 (open; its first two
  # sections, one per band, since the 2026-10-04 review). A row is read from the dump on hand when that shows it with its
  # band's header, else from the dump of the scroll_find that puts the header on screen (진행 막대: the row itself). No
  # align: on settings pages it dragged the list past the rows (CI 34; its dumps rightly had none). Once a row is not
  # found, it and the rows after it read "?" without more searching: a regression is a quick 14b FAIL with the details,
  # not a step timeout.
  local t v out="" lost="" find
  STATUS=""
  scroll_find "위쪽 상태 표시줄" || lost="위쪽 상태 표시줄"
  for t in "위 왼쪽" "위 가운데" "위 오른쪽" "아래 왼쪽" "아래 가운데" "아래 오른쪽" "진행 막대"; do
    v=""
    if [ -z "$lost" ]; then
      v=$(status_row "$t")
      if [ -z "$v" ]; then
        find=$t; [ "$t" = "진행 막대" ] || find=$(slot_row "$t"); find=${find% › *}
        if scroll_find "$find"; then v=$(status_row "$t"); else lost=$t; fi
      fi
    fi
    out="$out; $t=${v:-?}"
  done
  STATUS="${out#; }"
  log "status rows: $STATUS${lost:+ (not found from '$lost' on)}"
}

# ------------------------------------------------------------------ reader steps: chrome (U §8.2)

chrome_pin() { # 13 (+rawshot), 13b–13h, then rawshot 10a_pre and the bars open again for 14
  show_chrome || return 1
  shot 13_txt_chrome 1; rawshot 13_txt_chrome
  dump; cp /tmp/ui.xml shots/ui_reader_chrome.xml 2>/dev/null
  if has "이 페이지 고정" && ! has "지우기"; then check 13 0 "bars open, pin outline, no strip"
  else check 13 1 "pin (이 페이지 고정) or no-strip expectation missing"; fi
  local lb x0 x1 cx
  lb=$(box_of "페이지 이동" contains); read -r x0 _ x1 _ <<<"${lb:-0 0 0 0}"; cx=$(((x0 + x1) / 2))
  case "$(page_label)" in
    *", 3 / "*) [ "$cx" -ge 358 ] && [ "$cx" -le 362 ]; check 13_label $? "label '$(page_label)' centred at x = $cx";;
    *) check 13_label 1 "label '$(page_label)' is not page 3";;
  esac
  # 13b: pin this page; the page itself must not change
  tap_label "이 페이지 고정" || return 1
  shot 13b_pin 2; rawshot 13b_pin
  raw_check 13b 13_txt_chrome 13b_pin 360 1100
  dump; if has "고정 해제" && has "지우기"; then check 13b_pin 0 "pin filled (고정 해제) and the strip with 지우기"
  else check 13b_pin 1 "no filled pin or no strip"; fi
  # 13c: a tap on the page closes the bars and turns nothing (below the header's clock: 12b is a minute or so older)
  adb shell input tap 360 700
  shot 13c_pin_close 2; rawshot 13c
  raw_check 13c 12b 13c belowheader
  dump; if has "쪽으로" contains; then check 13c 1 "a return chip is shown"; else check 13c 0 "no chip"; fi
  # 13d: five pages on, the strip offers the pinned page; going there offers the way back. The strip and the chip say
  # "3쪽" / "‹ 3쪽으로" / "8쪽으로 ›" since 79cd1a5 (the unit after a number is 쪽, attached; it was "3 페이지로").
  for i in 1 2 3 4 5; do adb shell input keyevent KEYCODE_VOLUME_DOWN; sleep 1; done
  show_chrome
  shot 13d_strip 1
  if has "3쪽으로" contains && has "지우기" && [ "$(page_no)" = 8 ]; then check 13d 0 "label 8, strip '‹ 3쪽으로' · 지우기"
  else check 13d 1 "label '$(page_label)', strip '3쪽으로' or 지우기 missing"; fi
  tap_label "3쪽으로" contains || return 1
  shot 13d_return 2
  dump; if has "8쪽으로" contains && has "3쪽" contains && [ "$(page_no)" = 3 ]; then check 13d_return 0 "back on 3 with '3쪽' · '8쪽으로 ›'"
  else check 13d_return 1 "label '$(page_label)', '3쪽' or '8쪽으로' missing after the return"; fi
  history_cols 13d_cols "3쪽" "8쪽으로" # the history row's three columns (2026-10-05)
  # 13e: the brightness options (the row stays)
  tap_label "밝기 옵션" || return 1
  shot 13e_brightness_opts 2
  dump; if has "스와이프로 밝기 조절" && has "기기 밝기 직접 조절"; then check 13e 0 "brightness options listed"
  else check 13e 1 "스와이프로 밝기 조절 / 기기 밝기 직접 조절 missing"; fi
  # 13f: 지우기 drops the pin (the bars stay)
  tap_label "지우기" || return 1
  shot 13f_clear 2
  dump; if ! has "지우기" && has "이 페이지 고정"; then check 13f 0 "strip gone, pin outline"
  else check 13f 1 "strip or filled pin still shown"; fi
  # 13g: two seeks with the menu open, then close: the chip offers the FIRST origin (3)
  seek_to 2 70 || return 1
  seek_to 70 60
  adb shell input tap 360 700
  shot 13g_seek_chip 2
  dump; if has "3쪽으로" contains; then check 13g 0 "chip '‹ 3쪽으로' after two seeks"
  else check 13g 1 "no '3쪽으로' chip (shown: $(grep -o 'text="[^"]*쪽으로"' /tmp/ui.xml 2>/dev/null | head -1))"; fi
  # 13h: two manual turns drop the chip
  adb shell input keyevent KEYCODE_VOLUME_DOWN; sleep 1; adb shell input keyevent KEYCODE_VOLUME_DOWN
  shot 13h_chip_gone 2
  dump; if has "쪽으로" contains; then check 13h 1 "the chip is still shown"; else check 13h 0 "chip gone after 2 turns"; fi
  rawshot 10a_pre; perf_mark 10a_pre # chrome closed: 10b compares against this page
  show_chrome # the bars open again for 14_reading_settings
}

# ------------------------------------------------------------------ reader steps: bars in the page colours (13t–13v)
# User feedback 2026-10-05 (PLAN): the bars take the reading theme's colours (흰 바탕, 마루뷰어, 흑백 반전), meet the page
# with a short shadow (a 1 px line on black), the history row sits on the page colour right above the bottom panel in
# three fixed columns, and the bars fade in and out unless the system's animations are off. The emulator is a phone:
# the e-ink looks (solid lines, no fade, no pressed flash) are covered by the JVM tests and the device checklist.

history_cols() { # history_cols <n> <left text> [right text]: the history row's columns in the last dump (U §3.4):
  # 지우기 centred on the 720 px row (x 358..362), the side labels inside their own thirds, with or without the other
  local cb lb rb c1 c2 cx l2 r1 ok=0
  cb=$(box_of "지우기"); lb=$(box_of "$2")
  read -r c1 _ c2 _ <<<"${cb:-0 0 0 0}"; cx=$(((c1 + c2) / 2))
  read -r _ _ l2 _ <<<"${lb:-0 0 9999 0}"
  r1=480
  if [ -n "${3:-}" ]; then rb=$(box_of "$3"); read -r r1 _ _ _ <<<"${rb:-0 0 0 0}"; fi
  [ -n "$cb" ] && [ -n "$lb" ] && [ "$cx" -ge 358 ] && [ "$cx" -le 362 ] || ok=1
  [ "$l2" -le 240 ] && [ "$r1" -ge 480 ] || ok=1
  check "$1" $ok "지우기 at x = $cx, '$2' ends at $l2${3:+, '$3' starts at $r1}"
  HIST_CX=$cx
}
set_page_look() { # set_page_look "흰 바탕"|마루뷰어 on|off: 화면 색 and 흑백 반전 on 설정 → 읽기 설정 (⚙ → 전체 읽기
  # 설정) in one visit over the open book; a repaint only, never a relayout (PLAN 2026-10-04). Leaves 설정, bars closed.
  local rc=0
  open_reading_page || return 1
  pick_setting "화면 색" "$1" exact || rc=1
  set_toggle "흑백 반전" "$2" || rc=1
  dump && log "읽기 설정: 화면 색 '$(row_value "화면 색")', 흑백 반전 '$(row_checked "흑백 반전")'"
  leave_settings
  return $rc
}
history_row() { # 13u <tag> <page #RRGGBB>: the history row on the page colour, right on the panel, its columns fixed.
  # Pins the current page (only its left label: the right column stays empty), turns twice, comes back by the row (both
  # labels), then 지우기 empties it without moving the panel. Ends on the same page, nothing pinned, the bars open.
  local t=$1 pg=$2 p cb lb y_label y_row v cx_one
  show_chrome || return 1
  has "지우기" && { tap_label "지우기" || return 1; sleep 1; dump; } # a clean row (13g's seeks left a place)
  p=$(page_no); [ -n "$p" ] || { log "13u_$t: no page label"; return 1; }
  tap_label "이 페이지 고정" || return 1
  sleep 1; dump
  history_cols "13u_${t}_one" "${p}쪽"; cx_one=$HIST_CX
  ! has "쪽으로" contains; check "13u_${t}_one_side" $? "only '${p}쪽' (pinned, on screen): no '…쪽으로' label"
  hide_chrome
  adb shell input keyevent KEYCODE_VOLUME_DOWN; sleep 1; adb shell input keyevent KEYCODE_VOLUME_DOWN; sleep 1
  show_chrome || return 1
  tap_label "${p}쪽으로" contains || return 1
  shot "13u_${t}_history" 2; rawshot "13u_${t}_history"
  dump; history_cols "13u_${t}_cols" "${p}쪽" "$((p + 2))쪽으로"
  [ "$HIST_CX" = "$cx_one" ]; check "13u_${t}_still" $? "지우기 at x = $HIST_CX with both labels, $cx_one with one"
  cb=$(box_of "지우기"); lb=$(box_of "페이지 이동" contains)
  read -r _ y_row _ v <<<"${cb:-0 0 0 0}"; y_row=$(((y_row + v) / 2))
  read -r _ y_label _ _ <<<"${lb:-0 0 0 0}"
  [ -n "$cb" ] && [ -n "$lb" ] && [ $((y_label - v)) -ge 0 ] && [ $((y_label - v)) -le 2 ]
  check "13u_${t}_on_panel" $? "the row ends at y = $v, the page label row starts at $y_label"
  near_check "13u_${t}_page" "$(raw_pixel "13u_${t}_history" 8 "$y_row")" "$pg" "the row's background at (8, $y_row)"
  v=$(raw_pixel "13u_${t}_history" 8 $((y_label - 2)))
  darker_check "13u_${t}_shadow" "$v" "$pg" 12 "the panel's shadow at (8, $((y_label - 2)))"
  tap_label "지우기" || return 1
  shot "13u_${t}_empty" 2
  dump; lb=$(box_of "페이지 이동" contains); read -r _ v _ _ <<<"${lb:-0 0 0 0}"
  ! has "지우기" && [ "$v" = "$y_label" ]; check "13u_${t}_empty" $? "no 지우기, the label row at y = $v (was $y_label)"
}
chrome_look() { # 13t <tag> <page> <surface> shadow|<edge> (#RRGGBB): the bars in this page's colours. Closed / open /
  # closed again: the page at (8, 700), the top bar's surface right of ←, the bottom panel's at the label row, the first
  # row under the brightness bar a shadow (darker than the page) or the 1 px edge; hiding the bars changes no page pixel
  # (below the header band, where a clock may tick) and lays nothing out
  local t=$1 pg=$2 sf=$3 ed=$4 b y0 y1 x2 y2 v
  hide_chrome; sleep 1
  rawshot "13t_${t}_closed"; perf_mark "13t_${t}_a"
  show_chrome || return 1
  shot "13t_${t}_open" 1; rawshot "13t_${t}_open"
  near_check "13t_${t}_page" "$(raw_pixel "13t_${t}_open" 8 700)" "$pg" "the page at (8, 700)"
  b=$(box_of "뒤로"); read -r _ y0 x2 y2 <<<"${b:-0 0 0 0}"
  v=$(raw_pixel "13t_${t}_open" $((x2 + 16)) $(((y0 + y2) / 2)))
  near_check "13t_${t}_top" "$v" "$sf" "the top bar right of ←"
  b=$(box_of "페이지 이동" contains); read -r _ y0 _ y2 <<<"${b:-0 0 0 0}"
  v=$(raw_pixel "13t_${t}_open" 8 $(((y0 + y2) / 2)))
  near_check "13t_${t}_bottom" "$v" "$sf" "the bottom panel at its label row"
  b=$(box_of "밝기"); read -r _ _ _ y2 <<<"${b:-0 0 0 0}"
  v=$(raw_pixel "13t_${t}_open" 8 "$y2")
  if [ "$ed" = shadow ]; then darker_check "13t_${t}_edge" "$v" "$pg" 12 "the row under the top bar (8, $y2)"
  else near_check "13t_${t}_edge" "$v" "$ed" "the 1 px edge under the top bar (8, $y2)"; fi
  hide_chrome; sleep 1
  rawshot "13t_${t}_closed2"; perf_mark "13t_${t}_b"
  read -r y0 y1 <<<"$(pv_rows)"
  raw_check "13t_${t}_hide" "13t_${t}_closed" "13t_${t}_closed2" $((y0 + 48)) "$y1"
  no_relayout "13t_${t}_norelayout" "13t_${t}_a" "13t_${t}_b"
}
chrome_lines() { adb logcat -d -s RAPerf:D "*:S" 2>/dev/null | grep -c "chrome $1"; } # RAPerf "chrome <what>" lines
motion_check() { # 13v: the bars follow the system's animation scale. At 1 they fade in and out (RAPerf "chrome show
  # fade"), end where the instant ones do, and leave no trace on the page; at 0 (this run's default, like 접근성
  # "애니메이션 제거") they switch at once ("chrome show instant")
  local y0 y1 b top bottom n0 n1
  hide_chrome; sleep 1; rawshot 13v_closed_ref
  show_chrome || return 1
  sleep 1; rawshot 13v_open_ref
  b=$(box_of "밝기"); read -r _ _ _ top <<<"${b:-0 0 0 0}"
  b=$(box_of "페이지 이동" contains); read -r _ bottom _ _ <<<"${b:-0 0 0 1440}"
  hide_chrome; sleep 1
  adb shell settings put global animator_duration_scale 1; sleep 2
  n0=$(chrome_lines "show fade")
  show_chrome || return 1
  sleep 1; rawshot 13v_open_fade
  n1=$(chrome_lines "show fade")
  [ "$n1" -gt "$n0" ]; check 13v_fade $? "RAPerf 'chrome show fade' at animator scale 1 ($n0 → $n1 lines)"
  raw_check 13v_top 13v_open_ref 13v_open_fade 0 $((top + 8))
  raw_check 13v_bottom 13v_open_ref 13v_open_fade $((bottom - 8)) 1440
  hide_chrome; sleep 1
  dump; ! has "페이지 이동" contains; check 13v_hidden $? "the bars are gone a second after the fade out"
  rawshot 13v_closed_fade
  read -r y0 y1 <<<"$(pv_rows)"
  raw_check 13v_page 13v_closed_ref 13v_closed_fade $((y0 + 48)) "$y1"
  adb shell settings put global animator_duration_scale 0; sleep 2
  n0=$(chrome_lines "show instant")
  show_chrome; hide_chrome
  n1=$(chrome_lines "show instant")
  [ "$n1" -gt "$n0" ]; check 13v_instant $? "RAPerf 'chrome show instant' at animator scale 0 ($n0 → $n1 lines)"
}
chrome_looks_run() {
  history_row paper "#FFFFFF"
  chrome_look paper "#FFFFFF" "#F5F5F5" shadow
  LOOK_SET=1
  set_page_look 마루뷰어 off || return 1
  chrome_look maru "#323232" "#3C3C3C" shadow
  history_row maru "#323232"
  set_page_look 마루뷰어 on || return 1
  chrome_look invert "#000000" "#1A1A1A" "#333333"
}
chrome_looks() { # 13t–13v, then as 13 left it: 흰 바탕, 흑백 반전 off, the animation scale at 0, the same page with the
  # bars open, and 10a_pre taken again (bars closed) for 10b
  local rc
  LOOK_SET=0
  chrome_looks_run; rc=$?
  if [ "$LOOK_SET" = 1 ]; then set_page_look "흰 바탕" off || log "13t: 흰 바탕 and 흑백 반전 off not restored"; fi
  motion_check || rc=1
  adb shell settings put global animator_duration_scale 0
  hide_chrome; sleep 1
  rawshot 10a_pre; perf_mark 10a_pre
  show_chrome
  return $rc
}
reading_settings() { # 14 the quick options (⚙) and 14q their margins; 14s their "전체 읽기 설정" → 설정 → 읽기 설정, and
  # BACK to the same page (14s_back, 14s_same); 14m the margins there; 14b, 14c the status slots on 화면·밝기; then 10b
  # (+rawshot, first_is): the footer band that comes keeps the page's first character
  local label0 label1 miss t top first l v
  show_chrome || return 1
  label0=$(page_label); perf_mark 14s_a
  tap_label "읽기 설정" contains || return 1
  shot 14_reading_settings 2
  # 14: the quick options since 68aa271: one top bar "전체 읽기 설정 ›" · 닫기 over six steppers (글자 크기 · 굵기 ·
  # 줄 간격 · 문단 간격 · 좌우 여백 · 상하 여백) and 글꼴; no 더보기 / 접기, and no "읽기 설정 · 모든 책에 적용" bar any more
  dump; miss=$(missing "전체 읽기 설정" "닫기" "글꼴")
  for t in "글자 크기" "굵기" "줄 간격" "문단 간격" "좌우 여백" "상하 여백"; do
    has "$t 줄이기" && has "$t 늘리기" || miss="$miss '$t 줄이기/늘리기'"
  done
  top=$(xy_of "전체 읽기 설정"); first=$(xy_of "글자 크기 줄이기")
  if [ -n "$top" ] && [ -n "$first" ] && [ "${top#* }" -lt "${first#* }" ]; then :; else miss="$miss '전체 읽기 설정 above 글자 크기'"; fi
  if [ -z "$miss" ] && ! has "더보기" contains && ! has "접기" && ! has "모든 책에 적용"; then
    check 14 0 "quick options: 전체 읽기 설정 › · 닫기 on top, 6 steppers (with 좌우 여백 · 상하 여백), 글꼴; no 더보기"
  else check 14 1 "quick options: missing [${miss# }], 더보기 / 접기 / 모든 책에 적용 $(has "더보기" contains || has "접기" || has "모든 책에 적용" && echo shown || echo absent)"; fi
  # 14q: the quick options' margins read as 읽기 설정's (14m): "0" at the defaults (R1-quick-margins, same steps and values)
  l=$(stepper_value "좌우 여백"); v=$(stepper_value "상하 여백")
  [ "$l" = 0 ] && [ "$v" = 0 ]; check 14q $? "quick options: 좌우 여백 '$l', 상하 여백 '$v'"
  # 14s: 전체 읽기 설정 opens 설정 → 읽기 설정 (the only page of its stack); BACK returns to the same page of the book
  tap_label "전체 읽기 설정" || return 1
  sleep 3
  if reading_page_shown; then check 14s 0 "설정 → 읽기 설정 in front (title 읽기 설정, section 스타일)"
  else check 14s 1 "설정 → 읽기 설정 not in front after 전체 읽기 설정"; return 1; fi
  shot 14s_reading_page 0
  margins_zero 14m
  leave_settings || return 1
  perf_mark 14s_b
  show_chrome; label1=$(page_label)
  if [ -n "$label0" ] && [ "$label0" = "$label1" ]; then check 14s_back 0 "back on the book with BACK, label '$label1' as before"
  else check 14s_back 1 "label '$label0' before 설정, '$label1' after BACK"; fi
  perf_check 14s_same same_start 14s_a 14s_b
  # 14b: the status slots, on 화면·밝기 since 68aa271 (⋮ → 설정 → 화면·밝기, its first two sections: 위쪽 상태 표시줄 and
  # 아래쪽 상태 표시줄, whose rows say 왼쪽 / 가운데 / 오른쪽 only). The shot shows the screen status_rows read its last row
  # on, 진행 막대: the bottom band's rows, the other rows 14b judges, are on it too (about 135 px apart) with their header.
  # Only when 진행 막대 came up near the top without them does one swipe up bring them back (not scroll_find: it swipes
  # down first).
  open_screen_over_reader || return 1
  status_rows
  if has "진행 막대" && [ -n "$(missing_slots "아래 왼쪽" "아래 가운데" "아래 오른쪽")" ]; then list_swipe up; dump; fi
  shot 14b_status_slots 0
  case "$STATUS" in
    *"; 아래 왼쪽=없음; 아래 가운데=없음; 아래 오른쪽=없음; 진행 막대=on") check 14b 0 "bottom slots 없음, 진행 막대 on ($STATUS)";;
    *) check 14b 1 "bottom slots not all 없음 or 진행 막대 not on ($STATUS)";;
  esac
  # The default header is MaruViewer's line (2026-10-05): battery icon and clock, the book's title, the page.
  case "$STATUS" in
    "위 왼쪽=배터리 아이콘 · 시계; 위 가운데=책 제목; 위 오른쪽=쪽 번호;"*)
      check 14b_top 0 "top slots [배터리 아이콘 · 시계][책 제목][쪽 번호]";;
    *) check 14b_top 1 "top slots not [배터리 아이콘 · 시계][책 제목][쪽 번호] ($STATUS)";;
  esac
  # 14c: the slot chooser of 아래 가운데 ("쪽 번호 (12 / 3259)" … "챕터 쪽 번호 (2 / 32)": the first "쪽 번호" is the
  # page), its row 가운데 under 아래쪽 상태 표시줄
  scroll_find "아래쪽 상태 표시줄" || { leave_settings; return 1; }
  XY=$(slot_xy "아래 가운데"); [ -n "$XY" ] || { log "14c: no 가운데 row under 아래쪽 상태 표시줄"; leave_settings; return 1; }
  tap_xy "$XY"
  shot 14c_slot_list 2
  dump; if has "쪽 번호" contains && has "없음"; then check 14c 0 "slot list with 쪽 번호 and 없음"; else check 14c 1 "slot list items missing"; fi
  choose_item "쪽 번호" || { leave_settings; return 1; }
  sleep 2; dump && log "14c: 아래 가운데 reads '$(row_value "$(slot_row "아래 가운데")")'"
  leave_settings || return 1 # two pages (설정, 화면·밝기): the book applies the slot once, back in front
  # 10b: same page as 10a_pre, footer now on. Its band (36 dp) ends the text box 40 px higher (2026-10-05: the margins
  # count from the bands): one relayout that keeps the page's first character, and the first lines in place.
  shot 10b_footer_slots 2; rawshot 10b; perf_mark 10b
  raw_check 10b 10a_pre 10b contenttop
  perf_check 10b first_is 10a_pre 10b
}

# ------------------------------------------------------------------ reader steps: H3, TOC, go-to, end of book

toc_shots() { # 15: 목차 (header: 현재 위치 · 화 번호 · 검색, pager bar); 15b: one page on with the pager's [다음 ▶]
  # From a known state, whatever 14d left on screen (CI 30: its reading-settings popup, which took both 목차 lookups).
  # 15b pages with the pager's [다음 ▶] (or PAGE_DOWN): since 672e85d a drag scrolls and flings the list instead. The
  # pager buttons are described "이전 화면" / "다음 화면" since a3b8826 (a page of a list is one screen of it).
  fresh_reader sample-cp949.txt text/plain
  show_chrome || return 1
  tap_label "목차" contains || return 1
  shot 15_toc 3
  tap_label "다음 화면" || { adb shell input keyevent KEYCODE_PAGE_DOWN; log "15b: no pager button, sent PAGE_DOWN"; }
  shot 15b_toc_page2 2
  back
}
open_goto() { # 페이지 이동 from the bottom bar's page label (bars shown first); true once its number pad is on screen
  show_chrome || return 1
  tap_label "페이지 이동" contains || return 1
  sleep 2
  dump && has "이동" && has "5" && return 0
  log "go-to dialog: no number pad"; return 1
}
numpad_type() { # numpad_type 123: taps the pad's keys (found in the last dump, the field still empty); else digit keys
  local digits=$1 i d xy
  for ((i = 0; i < ${#digits}; i++)); do
    d=${digits:i:1}
    xy=$(xy_of "$d")
    if [ -n "$xy" ]; then adb shell input tap $xy; else adb shell input keyevent "KEYCODE_$d"; fi
    sleep 1
  done
  log "typed $digits on the number pad"
}
goto_page() { # goto_page N: 페이지 이동 → N (17b/18 leave the TXT book on its last page)
  open_goto || return 1
  numpad_type "$1"
  tap_label "이동" || adb shell input keyevent KEYCODE_ENTER
  sleep 3; hide_chrome
}
goto_numpad() { # 15c: 페이지 이동 with its number pad ([쪽] [%] [화] over the pad since 79cd1a5), "12" typed
  fresh_reader sample-cp949.txt text/plain
  open_goto || return 1
  numpad_type 12
  shot 15c_goto_numpad 2
  back # 취소: the reader stays where it was
}
set_volume_keys() { # set_volume_keys "entry": 볼륨 키 on 넘기기·터치·키 (open) and an entry of its chooser (exact, else
  # contains). One chooser for both keys since 68aa271 (P8, as the popup's 3-entry list was before 672e85d), in plain
  # sentences since the 2026-10-04 review: "아래 키로 다음 페이지 (기본)" / "위 키로 다음 페이지" / "넘기지 않음 (소리 크기
  # 조절)"; the row's value is the entry without
  # " (기본)". VOL_BEFORE / VOL_AFTER = the row's value before and after, VOL_LIST = the entries the chooser showed (in
  # that order, ", "-joined). False unless the row then reads the entry.
  VOL_BEFORE=""; VOL_AFTER=""; VOL_LIST=""
  scroll_find "볼륨 키" || return 1
  VOL_BEFORE=$(row_value "볼륨 키")
  tap_xy "$XY" || return 1
  sleep 2
  dump && VOL_LIST=$(present "아래 키로 다음 페이지 (기본)" "위 키로 다음 페이지" "넘기지 않음 (소리 크기 조절)")
  choose_item "$1" || return 1
  sleep 2
  dump && VOL_AFTER=$(row_value "볼륨 키")
  log "볼륨 키: '$VOL_BEFORE' -> '$VOL_AFTER' (the chooser listed: ${VOL_LIST:-nothing})"
  [ "$VOL_AFTER" = "${1% (기본)}" ]
}
choose_volume_mode() { # choose_volume_mode on|off: 볼륨 키 on 넘기기·터치·키 over the open book, on = "위 키로 다음
  # 페이지" (VOLUME_UP turns to the next page), off = the default "아래 키로 다음 페이지"; then back to the book. With on,
  # CHECK 14d_list: the row read the default and its chooser listed the three entries.
  local want="아래 키로 다음 페이지 (기본)"
  [ "$1" = on ] && want="위 키로 다음 페이지"
  open_turning_over_reader || return 1
  set_volume_keys "$want" || { leave_settings; return 1; }
  if [ "$1" = on ]; then
    if [ "$VOL_BEFORE" = "아래 키로 다음 페이지" ] \
      && [ "$VOL_LIST" = "아래 키로 다음 페이지 (기본), 위 키로 다음 페이지, 넘기지 않음 (소리 크기 조절)" ]; then
      check 14d_list 0 "버튼·키: 볼륨 키 read '$VOL_BEFORE', its chooser listed the 3 entries"
    else check 14d_list 1 "볼륨 키 read '$VOL_BEFORE' (want '아래 키로 다음 페이지'), the chooser listed '${VOL_LIST}'"; fi
    shot 14d_volume_mode 0
  fi
  leave_settings # two pages; the reader reads the key setting when it is back in front
}
volume_default() { # 볼륨 키 back to "아래 키로 다음 페이지", also after a failed 14d: later steps turn with VOLUME_DOWN
  # (CI 30: 14d stopped with 위 = 다음 set, and 56c's VOLUME_DOWN went back a page); through the library's 설정 when that
  # fails over the book
  if on_top ReaderActivity && ! popup_focused && choose_volume_mode off; then return 0; fi
  log "14d: setting 볼륨 키 back to 아래 키로 다음 페이지 through the library's 설정"
  open_turning_page || return 1
  set_volume_keys "아래 키로 다음 페이지 (기본)"
}
volume_mode() { # 14d (R U5): 위 키로 다음 페이지 (볼륨 키 on 넘기기·터치·키) makes VOLUME_UP the next page without a relayout;
  # then the default again
  fresh_reader sample-cp949.txt text/plain
  local n0 n1
  show_chrome && n0=$(page_no); hide_chrome
  perf_mark 14d_before
  choose_volume_mode on || { volume_default; return 1; }
  perf_mark 14d_changed
  no_relayout 14d_norelayout 14d_before 14d_changed
  popup_focused && log "14d: a popup still has the focus before VOLUME_UP"
  adb shell input keyevent KEYCODE_VOLUME_UP; sleep 2
  perf_mark 14d_turned
  local result
  result=$(python3 - <<'PY'
import pathlib, re
def page(mark):
  rows=re.findall(r'show (\w+) s:(\d+) o:(\d+)',pathlib.Path('shots/perf_'+mark+'.txt').read_text())
  return rows[-1] if rows else None
a,b=page('14d_changed'),page('14d_turned')
ok=a and b and b[0]=='TURN' and tuple(map(int,b[1:]))>tuple(map(int,a[1:]))
print(('PASS' if ok else 'FAIL')+' VOLUME_UP turned from '+str(a)+' to '+str(b))
PY
  )
  log "CHECK 14d $result"
  show_chrome && n1=$(page_no)
  if [ -n "$n0" ] && [ -n "$n1" ] && [ "$n1" -eq $((n0 + 1)) ]; then check 14d_label 0 "label $n0 -> $n1"
  else check 14d_label 1 "label '$n0' -> '$n1' (expected one page further)"; fi
  hide_chrome
  volume_default || return 1
  [ "${result%% *}" = PASS ]
}
selection_shot() { # 17: one row of 5 (복사 · 인용 · 메모 · 사전·번역 · ⋮); the bar is read from an all-windows dump
  hide_chrome
  select_at 300 700
  shot 17_selection 1
  dump_all; if has "복사" && has "인용" contains && has "메모" && has "사전·번역"; then check 17 0 "selection row with 복사 · 인용 · 메모 · 사전·번역"
  else check 17 1 "selection actions missing"; fi
  if has "복사"; then back; fi # BACK clears the selection; without one, BACK would leave the book
}
end_of_book() { # 17b: a long-press on the blank part of a page selects nothing; 18: "next" on the last page = end panel
  fresh_reader sample-cp949.txt text/plain
  open_goto || return 1
  numpad_type 9999 # more than the pages (or 100%): the last page
  tap_label "이동" || adb shell input keyevent KEYCODE_ENTER
  sleep 3
  hide_chrome
  # The last page is the 에필로그 section alone (about 8 lines at the top): y 1150 is blank page under its last line.
  local before after i
  before=$(popups)
  longpress 360 1150
  shot 17b_longpress_blank 2
  after=$(popups)
  if [ "${after:-0}" -gt "${before:-0}" ]; then
    check 17b 1 "a popup (selection bar?) after a long-press on blank space ($before -> $after)"
    back
  else
    check 17b 0 "no selection bar ($before -> $after popups)"
  fi
  for i in 1 2 3; do
    adb shell input keyevent KEYCODE_PAGE_DOWN; sleep 2
    dump && has "다 읽었습니다" && break
  done
  shot 18_end_panel 1
  if has "다 읽었습니다"; then check 18 0 "end panel shown"; back; else check 18 1 "no end panel after $i page-downs"; return 1; fi
}

# ------------------------------------------------------------------ scroll mode (S §1.15), 60–69b

scroll_on() { # 60: the sample EPUB switched to 스크롤 by 넘기는 방식, the first row of 넘기기·터치·키 (68aa271; its
  # section is 넘기기 now, and the chooser reads "스크롤 (위아래로 읽기)": contains); the book switches once back in front
  fresh_reader sample.epub application/epub+zip
  perf_mark 60a
  open_turning_over_reader || return 1
  pick_setting "넘기는 방식" "스크롤" exact || { leave_settings; return 1; }
  dump && log "60: 넘기는 방식 reads '$(row_value "넘기는 방식")'"
  leave_settings || return 1
  shot 60_scroll_on 2; perf_mark 60b
  # Scroll mode logs no `RAPerf show` line yet: position checks 60/66/68/69 are logged for the eye, not CHECKed.
  log "60: first_is 60a 60b: $(python3 tools/ci/perf_log.py first_is 60a 60b) (log only)"
}
scroll_moves() { # 61–66 in the scroll mode set by 60
  hide_chrome
  local top bot
  rawshot 61a
  # 61: a 600 px drag up. 스크롤 움직임 자동 follows the finger on every device and a release faster than the minimum
  # fling velocity (50 dp/s = 100 px/s here) flings since 672e85d: the old 400 ms swipe (1500 px/s) would coast on. At
  # 9 s (about 67 px/s) it stays a plain drag, so 61–63 still end near the start of chapter 2 for 64. Not `drag`: each
  # `input motionevent` is a process start, and a DOWN left alone for the 500 ms long-press would select text.
  adb shell input swipe 360 1100 360 500 9000; shot 61_scroll_drag 2; rawshot 61b
  read -r top bot <<<"$(pv_rows)"
  # Both bands stay put while the text scrolls; their live values follow the position and the time: the header's 쪽 번호
  # and clock (MaruViewer's line, the default since 2026-10-05), the footer's 쪽 번호 (14c's 아래 가운데 until 52) and the
  # progress dot. The header's glyphs fill rows 8 (4 dp below the edge) to about 40 (11 sp × 1.45 = 32 px; its band ends
  # at 44, then its 18 dp margin): its live values may change there (band_check). The paper below them down to the text
  # box (rows 48..80, as raw_check's belowheader) has nothing live, so it must stay EQUAL: scrolled text clipped a few px
  # too high would show there.
  band_check 61_header 61a 61b "$top" $((top + 48))
  raw_check 61_header_gap 61a 61b $((top + 48)) $((top + 80))
  band_check 61_footer 61a 61b $((bot - 80)) "$bot"
  local y0 y1 r
  read -r y0 y1 <<<"$(content_rows)"
  r=$(python3 tools/ci/raw_equal.py shots/61a.raw shots/61b.raw "$y0" "$y1")
  case "$r" in DIFF*) check 61_moved 0 "the text moved ($r)";; *) check 61_moved 1 "the text did not move ($r)";; esac
  adb shell input tap 600 900; shot 62_scroll_step 2
  for i in 1 2 3; do adb shell input keyevent KEYCODE_PAGE_DOWN; sleep 1; done; shot 63_scroll_keys 1
  show_chrome || return 1
  tap_label "목차" contains || return 1
  sleep 2
  # The fourth entry: 61–63 take the reader to about the start of chapter 2, and a jump to text already on screen
  # remembers no return point (ReaderActivity.goTo), so the second entry offers no chip (CI 28/29).
  tap_label "제4장 샘플 챕터" || tap_label "샘플 챕터" contains 3 || return 1
  shot 64_scroll_toc 3
  dump; if has "쪽으로" contains; then check 64 0 "return chip after the TOC jump"; else check 64 1 "no return chip"; fi
  select_at 300 700; shot 65_scroll_select 1
  has "복사" && back # BACK clears the selection; without one it would leave the book (66 steps on in it)
  # 66: step on until the section changes (the seam between chapters)
  local s0 s i
  adb logcat -d -v monotonic -s RAPerf:D '*:S' > shots/perf_66.txt
  s0=$(python3 tools/ci/perf_log.py last 66); s0=${s0#* s:}; s0=${s0%% *}
  for i in $(seq 1 25); do
    adb shell input tap 600 900; sleep 1
    adb logcat -d -v monotonic -s RAPerf:D '*:S' > shots/perf_66.txt
    s=$(python3 tools/ci/perf_log.py last 66); s=${s#* s:}; s=${s%% *}
    [ "$s" != "$s0" ] && break
  done
  shot 66_scroll_seam 1
  log "66: section $s0 -> $s after $i steps (log only: scroll mode logs no show line yet)"
}
scroll_release() { # 67: 스크롤 움직임 (넘기기·터치·키, shown in SCROLL only) → 손을 떼면 이동, the book reopened, a slow
  # 300 px swipe (a release steps a screen; only 손가락을 따라 flings)
  open_turning_page || return 1
  pick_setting "스크롤 움직임" "손을 떼면 이동" || return 1
  fresh_reader sample.epub application/epub+zip
  adb shell input swipe 360 1000 360 700 1500
  shot 67_step_release 2; perf_mark 67
}
scroll_round_trip() { # 68: ⋮ → 페이지로 보기 (the page holds the old top line); 69: ⋮ → 스크롤로 보기 (the same top line)
  on_top ReaderActivity || { log "68: 67 left no book open, reopening it"; fresh_reader sample.epub application/epub+zip; }
  reader_more "페이지로 보기" || return 1
  hide_chrome; shot 68_back_to_paged 1; perf_mark 68
  log "68: first_is 67 68: $(python3 tools/ci/perf_log.py first_is 67 68) (log only)"
  reader_more "스크롤로 보기" || return 1
  hide_chrome; shot 69_scroll_again 1; perf_mark 69
  log "69: same_start 68 69: $(python3 tools/ci/perf_log.py same_start 68 69) (log only)"
}
scroll_off() { # 69b: 스크롤 움직임 → the default (the row shows only in SCROLL), then ⋮ → 페이지로 보기; logged only
  open_turning_page && pick_setting "스크롤 움직임" "손가락을 따라" # the item reads "손가락을 따라 (기본)" (68aa271; contains)
  fresh_reader sample.epub application/epub+zip
  if reader_more "페이지로 보기"; then hide_chrome; else log "69b: no '페이지로 보기' (already paged?)"; hide_chrome; fi
  shot 69b_back_to_paged 1
}

# ------------------------------------------------------------------ library and settings steps

set_list_mode() { # set_list_mode 자세히|간단히|"큰 표지"|"작은 표지" (C29; the views were 전체 · 요약 · 썸네일 · 그리드
  # before a3b8826): the toolbar's view toggle, described "보기: <view>" (a tap moves to the next view), else ⋮ (더보기) →
  # "보기: <view>" → the view in its chooser ("큰 표지 (3열)": contains)
  local i
  for i in 1 2 3 4 5; do
    dump || return 1
    has "보기: $1" && return 0
    tap_xy "$(xy_of "보기: " contains)" || break
    sleep 2
  done
  library_more "보기:" contains || return 1
  sleep 1
  choose "$1" || { back; return 1; }
  sleep 3
}
open_drawer() { tap_label "메뉴" && sleep 1; } # ☰: "메뉴" of the library toolbar (its ⋮ is "더보기")
drawer_tap() { # drawer_tap "row" [exact|contains]: a row of the open drawer, scrolled into view first (since a3b8826
  # 48 dp rows in groups: 읽고 있는 책 … 다 읽은 책 | 독서 노트 · 단어장 | 컬렉션 … 형식 | 휴지통 | 설정 · 읽기 기록; from
  # 휴지통 on the rows start below the fold of a 720 dp-high screen, and the plain dump omits them)
  local i xy
  for i in 1 2 3; do
    dump || return 1
    xy=$(xy_of "$1" "${2:-exact}")
    if [ -n "$xy" ] && [ "${xy#* }" -le 1400 ]; then adb shell input tap $xy; log "tap '$1' at $xy (drawer)"; return 0; fi
    drag 100 1200 400; sleep 1 # x 100 is on the drawer panel; a slow drag that rests, so it doesn't coast
  done
  log "NOT FOUND '$1' in the drawer"; return 1
}
row_count() { # row_count "label": the count right of a drawer row's label in the last dump ('' when it shows none)
  python3 - "$1" <<'PY'
import re, sys, xml.etree.ElementTree as ET
t=sys.argv[1]
def box(n):
  m=re.fullmatch(r'\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]',n.get('bounds',''))
  return tuple(map(int,m.groups())) if m else None
try:
  nodes=[(n,box(n)) for n in ET.parse('/tmp/ui.xml').getroot().iter('node')]
except Exception:
  nodes=[]
lab=[b for n,b in nodes if b and n.get('text')==t]
if lab:
  a=lab[-1]
  # The label and its count are two TextViews in one row; the x window leaves out the library's own counts (x 623)
  # behind the drawer; the nearest one in y wins.
  near=[(abs((b[1]+b[3])-(a[1]+a[3])),v) for n,b in nodes for v in [n.get('text') or '']
        if v.isdigit() and b and a[2]-4<=b[0]<=a[2]+40 and b[1]<a[3] and b[3]>a[1]]
  if near: print(min(near)[1])
PY
}
library_compact() { # 42 + CHECK 42b: 12 more books, a scan, 보기 → 간단히 (was 요약); a tap 2 px inside the ⋮'s right edge
  # The scan is ⋮ → 지금 스캔 (책 스캔 in a3b8826, renamed in the 2026-10-04 review as 설정 → 책 스캔's own row; 도서 스캔
  # left the drawer). 간단히's second line is "작가 · 새 책" for a book never opened.
  local n b x0 y0 x1 y1
  for n in 01 02 03 04 05 06 07 08 09 10 11 12; do
    adb shell cp /sdcard/Download/sample-utf8.txt "/sdcard/Download/extra-$n.txt"
  done
  restart_library
  library_more "지금 스캔" || return 1
  sleep 8
  set_list_mode "간단히" || return 1
  shot 42_library_compact 2
  dump; if has "새 책" contains; then check 42 0 "'새 책' meta in 간단히"; else check 42 1 "no '새 책' meta"; fi
  dump; b=$(box_of "책 메뉴"); [ -n "$b" ] || { check 42b 1 "no 책 메뉴 in 간단히"; return 1; }
  read -r x0 y0 x1 y1 <<<"$b"
  adb shell input tap $((x1 - 2)) $(((y0 + y1) / 2)); sleep 1
  dump; if book_menu_open; then check 42b 0 "a tap 2 px inside the ⋮'s right edge opens the menu"; back
  else check 42b 1 "no menu after the edge tap"; fi
}
library_views() { # 43 큰 표지 (3 columns; was 썸네일), 44 작은 표지 (4 columns; was 그리드), then 자세히 (was 전체) again
  restart_library
  set_list_mode "큰 표지" || return 1
  shot 43_library_thumbs 2
  set_list_mode "작은 표지" || return 1
  shot 44_library_grid 2
  set_list_mode "자세히"
}
library_paged() { # 45/46 + CHECK 46: 목록 넘기기 (설정 → 서재, from the library only) → 한 화면씩 (was 쪽 단위), the next
  # page by a swipe; then 스크롤 (the default) again
  open_settings || return 1
  pick_setting "목록 넘기기" "한 화면씩" || return 1
  restart_library
  shot 45_library_paged 2
  dump; if has "1 / " contains; then check 45 0 "pager '1 / N'"; else check 45 1 "no pager label '1 / N'"; fi
  adb shell input swipe 360 1000 360 600 300
  shot 46_library_page2 2
  dump; if has "2 / " contains; then check 46 0 "pager '2 / N' after a swipe"; else check 46 1 "no '2 / N' after a swipe"; fi
  open_settings && pick_setting "목록 넘기기" "스크롤"
}
library_multiselect() { # 46c: a long-press on a book starts multi-select ("1권 선택" and the batch actions, T1-13)
  restart_library
  set_list_mode "자세히"
  dump || return 1
  local xy
  xy=$(xy_of "sample-utf8")
  [ -n "$xy" ] || xy=$(xy_of "sample" contains)
  [ -n "$xy" ] || xy=$(xy_of "샘플" contains)
  [ -n "$xy" ] || { check 46c 1 "no sample book on screen"; return 1; }
  set -- $xy
  longpress "$1" "$2"
  shot 46c_multiselect 3
  dump || return 1
  if has "권 선택" contains; then check 46c 0 "selection toolbar shown"; back; else check 46c 1 "no selection toolbar"; return 1; fi
}
status_page() { # 51: 설정 → 화면·밝기, its first two sections 위쪽 상태 표시줄 and 아래쪽 상태 표시줄 (one per band since
  # the 2026-10-04 review; each row says 왼쪽 / 가운데 / 오른쪽, its header the band), its six slot rows checked in the
  # dumps of the screens on the way to the shot (the last kept as ui_fail_51_status_page.xml on a FAIL). No align: on
  # settings pages it dragged the list past the rows (CI 34, likely CI 31 too: its dumps rightly had none). scroll_find
  # puts the first header on screen; while 아래 오른쪽 is not seen under its header, one more swipe (about 800 px, two at
  # most). A row counts when any of these screens showed it: one that left the top was seen just before.
  local slots=("위 왼쪽" "위 가운데" "위 오른쪽" "아래 왼쪽" "아래 가운데" "아래 오른쪽") t i on seen above="" gone=""
  open_screen_page || return 1
  scroll_find "위쪽 상태 표시줄" || { check 51 1 "no 위쪽 상태 표시줄 section on 화면·밝기"; return 1; }
  seen=$(present_slots "${slots[@]}")
  for i in 1 2; do
    [ -n "$(slot_xy "아래 오른쪽")" ] && break
    list_swipe down
    dump || { check 51 1 "no dump after a swipe to 아래 오른쪽"; return 1; }
    seen="$seen, $(present_slots "${slots[@]}")"
  done
  shot 51_status_page 2
  on=$(present_slots "${slots[@]}")
  for t in "${slots[@]}"; do
    case ", $on, " in *", $t, "*) continue ;; esac
    case ", $seen, " in *", $t, "*) above="$above, $t" ;; *) gone="$gone, $t" ;; esac
  done
  if [ -z "$gone" ]; then check 51 0 "the shot shows the slot rows $on${above:+ (${above#, } just above it, seen before the last swipe)}"
  else
    cp /tmp/ui.xml shots/ui_fail_51_status_page.xml 2>/dev/null
    check 51 1 "slot rows ${gone#, } not found (the shot shows: ${on:-none}${above:+; above it: ${above#, }})"
  fi
}
stats_page() { # 50b: drawer → 읽기 기록 (T1-6)
  restart_library
  open_drawer || return 1
  drawer_tap "읽기 기록" || return 1
  shot 50b_stats 4
}
wifi_page() { # 50c: ⋮ → Wi-Fi로 책 받기 (T1-12; it left the drawer for the toolbar ⋮ in a3b8826)
  restart_library
  library_more "Wi-Fi로 책 받기" || return 1
  shot 50c_wifi 5
}
eink_settings() { # 50d: 설정 → e-ink 새로고침 (e-ink 화면 until the 2026-10-04 review), its own page since 68aa271 (T1-3;
  # was a section of 넘김·화면 설정), at its top; 50e: its 고급 group opened
  open_settings_page "e-ink 새로고침" || return 1
  dump && has "자동 새로고침" || log "50d: no 자동 새로고침 section on top of e-ink 새로고침"
  shot 50d_eink_settings 2
  scroll_find "고급" || return 1
  tap_xy "$XY" || return 1
  sleep 2
  dump && align "고급" 260
  shot 50e_eink_advanced 2
}

# ------------------------------------------------------------------ notes (N §17), 80–90

notes_quotes() { # 80 quote, 81/81b palette → 초록, 82 the existing quote's popup
  # The selection bar is read from all-windows dumps (dump_all, sel_tap); the palette is focusable (plain dump).
  fresh_reader sample-utf8.txt text/plain
  select_at 300 700 || return 1
  local qy=$SEL_Y # where the first quote is, for 82
  sel_tap "인용" || return 1
  shot 80_quote_saved 1
  log "80: no toast expected (checked by eye)"
  select_at 300 $((qy + 120 > 820 ? qy + 120 : 820)) || return 1 # never on the quote just made
  dump_all || return 1
  local xy; xy=$(xy_of "인용"); [ -n "$xy" ] || xy=$(xy_of "인용" contains)
  [ -n "$xy" ] || { check 81 1 "no 인용 cell"; back; return 1; }
  set -- $xy; longpress "$1" "$2" 900
  shot 81_palette 2
  dump; if has "노랑" contains && has "초록" contains; then check 81 0 "palette with 노랑 and 초록"; else check 81 1 "palette cells missing"; fi
  choose "초록" || return 1
  shot 81b_green 2
  longpress 300 "$qy" 900
  shot 82_quote_popup 2
  dump_all; if has "인용 삭제"; then check 82 0 "existing quote popup with 인용 삭제"; else check 82 1 "no 인용 삭제"; fi
  back
}
notes_toc() { # 83: TOC → 인용문 (its toolbar link to the hub is "독서 노트 (모든 책)" since 79cd1a5)
  dump_all && has "복사" && back # a selection a failed 80 left: BACK clears it (its tap would only clear it)
  show_chrome || return 1
  tap_label "목차" contains || return 1
  sleep 2
  tap_label "인용문" || return 1
  shot 83_toc_quotes 2
  dump; if has "독서 노트 (모든 책)" && has "전체 2" contains; then check 83 0 "link 독서 노트 (모든 책), chip 전체 2"; else check 83 1 "no 독서 노트 (모든 책) or chip 전체 2"; fi
  back
}
notes_lookup() { # 84: 사전·번역 cancelled, then 웹 검색 (logged only)
  hide_chrome
  select_at 300 940 || return 1
  sel_tap "사전·번역" || { back; return 1; }
  sleep 2; back
  select_at 300 940 || return 1
  sel_tap "사전·번역" || { back; return 1; }
  sleep 2
  # Without an ACTION_PROCESS_TEXT app TextActions.lookUp says so in a toast and opens the web search itself.
  tap_label "웹 검색" contains || log "84: no 사전 · 번역 chooser (no PROCESS_TEXT app): the app went straight to the web search"
  shot 84_lookup 4
  back; sleep 2
}
hub_xy() { python3 tools/ci/hub_rows.py "$1" /tmp/ui.xml; } # hub_xy note|day: "x y" of the first note's text / day header
first_row_xy() { # the first note's text in the last dump (never a header: CI 34's 87/88 tapped "오늘 · …"), else a point
  # in the list, logged (to stderr: the caller reads "x y" from stdout)
  local xy; xy=$(hub_xy note)
  if [ -z "$xy" ]; then xy="360 600"; log "first_row_xy: no note text in the dump, tapping $xy" >&2; fi
  echo "$xy"
}
notes_hub() { # 85a drawer, 85 hub, 86 인용문, 87 jump (+CHECK 87), 88 select, 89 단어장
  # 87's chip offers the way back to the book's saved place, and only when that is not the quote's page (PLAN §1.6.1:
  # if (!isOnCurrentPage(saved)) returnNav.onJump(saved)). 80–84 made the quotes on the page sample-utf8.txt was saved
  # at (CI 30: no chip, correctly), so the book is read 3 pages on first; 90 turns back to the quotes.
  local i
  fresh_reader sample-utf8.txt text/plain
  for i in 1 2 3; do adb shell input keyevent KEYCODE_PAGE_DOWN; sleep 1; done
  restart_library # HOME first: onPause saves the place 3 pages after the quotes
  open_drawer || return 1
  shot 85a_drawer 1 # 독서 노트 · 단어장 follow the reading shelves since a3b8826: on screen without scrolling
  # Each row's label and count are two TextViews: no single node reads "독서 노트 2". The counts need 80's 2 quotes and
  # 84's word.
  local nq nw
  dump; nq=$(row_count "독서 노트"); nw=$(row_count "단어장")
  if [ "$nq" = 2 ] && [ "$nw" = 1 ]; then check 85a 0 "drawer rows 독서 노트 2 · 단어장 1"
  else check 85a 1 "drawer rows 독서 노트 '$nq' 단어장 '$nw' (want 2 / 1)"; fi
  drawer_tap "독서 노트" || return 1
  shot 85_notes_hub 3
  tap_label "인용문" || return 1
  shot 86_notes_quotes 2
  dump; if has "모든 색" contains; then check 86 0 "filter row with 모든 색"; else check 86 1 "no 모든 색 filter"; fi
  local xy; xy=$(first_row_xy)
  tap_xy "$xy"
  shot 87_notes_jump 5
  dump; if has "쪽으로" contains; then check 87 0 "the reader at the quote with the return chip"
  else check 87 1 "no '쪽으로' chip (on screen: $(grep -o 'text="[^"]*쪽[^"]*"' /tmp/ui.xml 2>/dev/null | head -2 | tr '\n' ' '))"; fi
  back; sleep 2 # to the hub
  dump; xy=$(first_row_xy); set -- $xy
  longpress "$1" "$2" 900
  choose "선택" || return 1
  shot 88_notes_select 2
  dump; if has "1개 선택" contains; then check 88 0 "'1개 선택' bar"; else check 88 1 "no '1개 선택' bar"; fi
  back
  tap_label "단어장" || return 1 # the tab is 단어장 since a3b8826 (was 단어)
  shot 89_notes_words 2
  dump; if has "다시 찾기"; then check 89 0 "word row with 다시 찾기"; else check 89 1 "no word row (or the empty state, no browser)"; fi
}
notes_paged() { # 89p: 목록 넘기기 → 한 화면씩, a tap on the first row's day header still opens the book (a paged list
  # keeps every touch for paging, the row takes its own: the header takes none, so the tap is the row's); then 스크롤
  # (the default) again. On purpose the header, not the note text 87 taps: both ways to the row are covered.
  open_settings || return 1
  pick_setting "목록 넘기기" "한 화면씩" || return 1
  restart_library
  open_drawer || return 1
  drawer_tap "독서 노트" || return 1
  sleep 3
  tap_label "인용문" || return 1
  sleep 2
  dump; if ! has "모든 색" contains; then check 89p 1 "paged hub: no 인용문 tab"; open_settings && pick_setting "목록 넘기기" "스크롤"; return 1; fi
  local xy where="the first row's day header"
  xy=$(hub_xy day)
  [ -n "$xy" ] || { where="the first note's text (no day header on screen)"; xy=$(first_row_xy); }
  tap_xy "$xy"
  shot 89p_notes_paged_jump 5
  dump; if ! has "모든 색" contains; then check 89p 0 "paged hub: a tap on $where opens the book"
  else check 89p 1 "paged hub: a tap on $where left the hub on screen"; fi
  back; sleep 2
  open_settings && pick_setting "목록 넘기기" "스크롤"
}
notes_ink() { # 90: 인용문 색 표시 (설정 → 화면·밝기 since 68aa271) → 흑백 무늬 on the quotes; then 자동 again
  local i
  open_screen_page || return 1
  pick_setting "인용문 색 표시" "흑백 무늬" || return 1
  fresh_reader sample-utf8.txt text/plain
  # 85_89 left the book 3 pages after the quotes' page (the hub's open is a peek: it saves nothing). Turns, not a
  # jump: no return chip over the shot. On the first page a PAGE_UP does nothing.
  for i in 1 2 3; do adb shell input keyevent KEYCODE_PAGE_UP; sleep 1; done
  shot 90_highlight_ink 2
  open_screen_page && pick_setting "인용문 색 표시" "자동" # "자동 (색 그대로)" on the emulator (contains)
}

# ------------------------------------------------------------------ anchor checks (A §6.6 → 52–57)

footer_toggle() { # 52: footer slots and header changed on 화면·밝기 (over the book; 68aa271): once the book is back in
  # front, the page keeps its first character and its first lines. The footer's band that comes (36 dp) ends the text box
  # higher since 2026-10-05 (the margins count from the bands): one relayout, anchored; the header keeps its band (two of
  # its slots keep their items), so the box's top stays.
  fresh_reader sample-cp949.txt text/plain
  goto_page 3 || return 1 # a full page of text (the book was left on its short last page by 18)
  open_screen_over_reader || return 1
  set_slot "아래 가운데" "없음" || { leave_settings; return 1; } # 10b had set 쪽 번호
  leave_settings || return 1
  rawshot 52a; perf_mark 52a
  open_screen_over_reader || return 1
  set_slot "아래 가운데" "쪽 번호" || { leave_settings; return 1; }
  set_slot "아래 오른쪽" "배터리 아이콘 · 시계" || { leave_settings; return 1; } # "배터리 아이콘 · 시계 (14:05)" (contains)
  set_slot "위 가운데" "없음" || { leave_settings; return 1; }
  leave_settings || return 1
  shot 52_footer_toggle_same_text 1; rawshot 52b; perf_mark 52b
  raw_check 52 52a 52b contenttop
  perf_check 52 first_is 52a 52b
}
progress_toggle() { # 53: 진행 막대 off (화면·밝기, over the book): the footer's band shrinks by the lane (36 → 22 dp), one
  # anchored relayout: the same first character and first lines; then on again
  open_screen_over_reader || return 1
  set_toggle "진행 막대" off || { leave_settings; return 1; }
  leave_settings || return 1
  shot 53_progress_toggle_same_text 1; rawshot 53; perf_mark 53
  raw_check 53 52b 53 contenttop
  perf_check 53 first_is 52b 53
  open_screen_over_reader && set_toggle "진행 막대" on; leave_settings
}
margin_v_exact() { # 54: 상하 여백 +10 (설정 → 읽기 설정, over the book; the quick options have the same stepper again since
  # 68aa271, 14q reads it there) keeps the exact first character when the book applies it, back in front; then back to
  # "0" (K8)
  local i v
  perf_mark 54a
  open_reading_page || return 1
  scroll_find "상하 여백 늘리기" || { leave_settings; return 1; }
  for i in 1 2 3 4 5; do tap_xy "$XY"; sleep 1; done
  dump && log "54: 상하 여백 reads '$(stepper_value "상하 여백")'"
  leave_settings || return 1 # one page: one BACK, then the one relayout
  sleep 1; perf_mark 54b
  shot 54_margin_v_exact 0
  perf_check 54 first_is 54a 54b
  open_reading_page || return 1
  scroll_find "상하 여백 줄이기" || { leave_settings; return 1; }
  for i in $(seq 1 45); do # back to "0" whatever the +10 taps did (K8: 55 needs the content rows)
    dump; v=$(stepper_value "상하 여백")
    case "$v" in 0) break;; −*|-*) tap_xy "$(xy_of "상하 여백 늘리기")";; *) tap_xy "$(xy_of "상하 여백 줄이기")";; esac
    sleep 1
  done
  [ "$v" = 0 ]; check 54r $? "상하 여백 restored to '$v'"
  leave_settings
}
font_up_down() { # 55: 글자 크기 +1 then −1 (still in the quick options) gives back the exact text box
  rawshot 55a
  open_popup || return 1
  popup_tap "글자 크기 늘리기" exact 2 || return 1
  popup_tap "글자 크기 줄이기" exact 2 || return 1
  close_popup
  shot 55_font_up_down 1; rawshot 55b
  raw_check 55 55a 55b content
}
page_break_paragraph() { # 56: 페이지 나눔 = 문단 단위 (설정 → 읽기 설정, over the book) keeps the first character when the
  # book applies it, back in front; the next page; then 줄 단위 again. The chooser items read "문단 단위 (페이지 아래가 빌
  # 수 있음)" and "줄 단위 (기본)" (contains).
  perf_mark 56a
  open_reading_page || return 1
  pick_setting "페이지 나눔" "문단 단위" exact || { leave_settings; return 1; }
  dump && log "56: 페이지 나눔 reads '$(row_value "페이지 나눔")'"
  leave_settings || return 1
  sleep 1; perf_mark 56b
  perf_check 56 first_is 56a 56b
  adb shell input keyevent KEYCODE_VOLUME_DOWN
  shot 56_page_break_paragraph 2; perf_mark 56c
  log "56: the next page (a paragraph start, checked by eye): $(python3 tools/ci/perf_log.py last 56c)"
  open_reading_page && pick_setting "페이지 나눔" "줄 단위" exact; leave_settings
}
dialog_no_reflow() { # 57 (H4): the 페이지 이동 dialog leaves the page pixel-identical, no relayout
  fresh_reader sample-cp949.txt text/plain
  # Keep chrome visible in all captures; the dialog alone takes and returns focus.
  show_chrome || return 1
  # 57_still: the same screen twice with nothing touched (the page does not change on its own).
  rawshot 57_open
  sleep 3; rawshot 57_still_b
  raw_check 57_still 57_open 57_still_b 360 1100
  # CI 28-32: the cold-opened first frame and every later redraw differ by 3457 px inside the text column only
  # (bbox 81,360-617,1056), with no RELAYOUT, while all redraws are pixel-identical to each other (CI 32 57_repeat):
  # a first-frame rasterization effect, not a moved page. So 57 compares redrawn states: one warm-up dialog first.
  goto_dialog_open_close warmup || return 1
  rawshot 57_before
  log "57_firstframe (info, not a CHECK): $(python3 tools/ci/raw_equal.py shots/57_open.raw shots/57_before.raw 360 1100) 57_open vs 57_before rows 360..1100"
  perf_mark 57_before
  goto_dialog_open_close 57 || return 1
  rawshot 57_after
  perf_mark 57_after
  raw_check 57 57_before 57_after 360 1100
  no_relayout 57 57_before 57_after
}
goto_dialog_open_close() { # opens 페이지 이동 from the shown bars and cancels it (shot only for "57")
  tap_label "페이지 이동" contains || return 1
  sleep 2
  dump && has "이동" && has "5" || log "57: no number pad on screen (the go-to dialog did not open?)"
  [ "$1" = 57 ] && shot 57_dialog_no_reflow 0
  back; sleep 2
  # The capture needs the dialog gone (its number pad): one more BACK if it is still up.
  if dump && has "이동" && has "5"; then log "57: the go-to dialog is still open after BACK, BACK again"; back; sleep 2; fi
  return 0
}

# ------------------------------------------------------------------ W2 page thumbnails (92–93)

page_thumbs() { # guarded: before W2 lands the ⋮ has no 페이지 미리보기 (페이지 썸네일 until the 2026-10-04 review); that
  # is logged and the run goes on
  fresh_reader sample-cp949.txt text/plain
  show_chrome || return 1
  tap_label "더보기" || return 1
  sleep 1; dump
  if ! has "페이지 미리보기"; then log "92: no '페이지 미리보기' in the ⋮ menu (W2 not merged yet), skipped"; back; hide_chrome; return 0; fi
  tap_label "페이지 미리보기" || return 1
  shot 92_thumbs 3
  adb shell input swipe 600 900 100 900 300
  shot 93_thumbs_next 2
  back
}

# ------------------------------------------------------------------ U1 helpers (R §7)

top_is() { # top_is <Activity> <step>: the resumed activity, from dumpsys
  local t; t=$(adb shell dumpsys activity activities | grep -m1 -E "topResumedActivity=|mResumedActivity" | tr -d '\r')
  case "$t" in *"$1"*) check "$2" 0 "$1 on top";; *) check "$2" 1 "expected $1, got: $t";; esac
}
same() { # same <before> <after>: same_page.py as a CHECK line (SKIP counts as FAIL)
  local r verdict
  r=$(python3 tools/ci/same_page.py "shots/$1.png" "shots/$2.png" "$2")
  verdict=${r%% *}; r=${r#* }; r=${r#*: }
  if [ "$verdict" = PASS ]; then check "$2" 0 "same page as $1 ($r)"; else check "$2" 1 "vs $1: $verdict $r"; fi
}
overview_back() { # recents, then the centred (most recent) card
  adb shell input keyevent KEYCODE_APP_SWITCH; sleep 3
  adb shell input tap 360 620; sleep 6
}

# ------------------------------------------------------------------ restore offer (S §3.9 → 95–98), LAST: pm clear

restore_offer() {
  adb shell mkdir -p /sdcard/Download/ReaderaPlus/backup
  adb shell 'rm -f /sdcard/Download/ReaderaPlus/backup/readeraplus-auto-*.json' # this run's own: the crafted one is the offer
  adb push "samples/$RESTORE_BACKUP" /sdcard/Download/ReaderaPlus/backup/ >/dev/null
  adb shell pm clear $PKG
  adb shell appops set --uid $PKG MANAGE_EXTERNAL_STORAGE allow
  adb shell am start -W -n $PKG/.ui.library.LibraryActivity | tee -a shots/steps.txt
  shot 95_restore_offer 8
  dump; if has "복원"; then check 95 0 "restore offer shown"; else check 95 1 "no restore offer (복원)"; return 1; fi
  tap_label "복원" || return 1
  sleep 8 # restore on IO, then the library recreates itself
  # 읽고 있는 책 is a shelf (the drawer's first row; last_read_at > 0, not finished), not a heading of 모든 책: the
  # restored book must be listed on it (CI 30 looked for the words on 모든 책, where they never are).
  open_drawer && drawer_tap "읽고 있는 책" && sleep 2
  shot 96_restored 1
  # The drawer closed (no 휴지통 row): 읽고 있는 책 is the toolbar title, and the list behind is that shelf's.
  dump; if has "읽고 있는 책" && ! has "휴지통" && has "샘플 EPUB" contains; then check 96 0 "the book is listed on 읽고 있는 책"
  else check 96 1 "샘플 EPUB not on the 읽고 있는 책 shelf (title 읽고 있는 책: $(has "읽고 있는 책" && echo yes || echo no))"; fi
  tap_label "샘플 EPUB" contains || return 1
  sleep 5
  open_reading_page || return 1 # 설정 → 읽기 설정 (⚙ → 전체 읽기 설정), as 14m
  margins_zero 97
  shot 97_restored_margins 1
  leave_settings
  open_settings || return 1 # from the library: 백업·복원 is not on the list 설정 shows over a book (68aa271)
  scroll_find "백업·복원" || return 1
  tap_xy "$XY"; sleep 3
  scroll_find "자동 백업" || return 1
  align "자동 백업" 260
  shot 98_backup_page 2
  check 98 0 "자동 백업 section found"
}

export PKG RESTORE_BACKUP
# shellcheck disable=SC2046 # one word per function name
export -f $(compgen -A function)

adb wait-for-device
adb shell wm size 720x1440
adb shell wm density 320
adb shell settings put system screen_off_timeout 1800000
adb install -r -g "$APK" || { log "install failed"; exit 1; }
python3 tools/ci/make_samples.py samples
adb shell mkdir -p /sdcard/Download
adb push samples/sample-cp949.txt samples/sample-utf8.txt samples/sample.epub samples/big-cp949.txt /sdcard/Download/ >/dev/null
adb shell appops set --uid $PKG MANAGE_EXTERNAL_STORAGE allow
adb logcat -c
adb shell setprop log.tag.RAPerf DEBUG

log "library"
adb shell am start -W -n $PKG/.ui.library.LibraryActivity | tee -a shots/steps.txt
shot 01_library 10
step 41_library_more library_more_guard
if tap_label "메뉴"; then
  # The drawer since a3b8826: 독서 노트 · 단어장 are its second group, right after the reading shelves (… 다 읽은 책) and
  # before 컬렉션, all on screen without scrolling; no counts before the first note. Each row is the LAST match: the
  # drawer is built after the list, and the 자세히 cards behind it have buttons described "다 읽은 책" and "컬렉션".
  sleep 1; shot 02_drawer 1; dump
  h=$(xy_of "다 읽은 책" exact -1); n=$(xy_of "독서 노트" exact -1); w=$(xy_of "단어장" exact -1); c=$(xy_of "컬렉션" exact -1)
  nq=$(row_count "독서 노트"); nw=$(row_count "단어장")
  if [ -n "$h" ] && [ -n "$n" ] && [ -n "$w" ] && [ -n "$c" ] && [ "${n#* }" -gt "${h#* }" ] && [ "${w#* }" -gt "${n#* }" ] \
    && [ "${c#* }" -gt "${w#* }" ] && [ -z "$nq" ] && [ -z "$nw" ]; then
    check 02 0 "독서 노트 · 단어장 between 다 읽은 책 and 컬렉션, no counts"
  else check 02 1 "다 읽은 책 '$h' 독서 노트 '$n' ('$nq') 단어장 '$w' ('$nw') 컬렉션 '$c' (exact rows in that order, no counts)"; fi
  back
fi

log "reader txt"
adb shell am start -W -a android.intent.action.VIEW -t text/plain -d file:///sdcard/Download/sample-cp949.txt -n $PKG/.reader.ReaderActivity | tee -a shots/steps.txt
shot 10_txt_page1 4
adb shell input keyevent KEYCODE_VOLUME_DOWN; shot 11_txt_page2 2
adb shell input tap 600 900; shot 12_txt_tap_right 2; rawshot 12b
step 13_chrome_pin chrome_pin
# 13t–13v visit 설정 three times and take some 30 shots: more time than the 300 s default
STEP_TIMEOUT=600 step 13t_chrome_looks chrome_looks
# 13v turns the animator scale to 1 for its fades: a step cut off by its timeout must not leave them on for the rest
adb shell settings put global animator_duration_scale 0
# 14, 14d, 52 and 53 go to 설정 and back (twice for most): more time than the 300 s default
STEP_TIMEOUT=480 step 14_reading_settings reading_settings
STEP_TIMEOUT=480 step 14d_volume_mode volume_mode
step 15_toc toc_shots
show_chrome
if tap_label "검색" contains; then sleep 1; adb shell input text "English" ; adb shell input keyevent KEYCODE_ENTER; shot 16_search 4; back; fi
sleep 1; step 17_selection selection_shot

log "reader txt: go-to pad, long-press on blank space, end of book"
step 15c_goto_numpad goto_numpad
step 17b_18_end_of_book end_of_book
fresh_reader sample-cp949.txt text/plain # the TXT book in front again, as the EPUB part below always found it
goto_page 3 # off the short last page 18 left it on (52–57 and 92 reopen this book)

log "reader epub"
adb shell am start -W -a android.intent.action.VIEW -t application/epub+zip -d file:///sdcard/Download/sample.epub -n $PKG/.reader.ReaderActivity | tee -a shots/steps.txt
shot 20_epub_page1 5
for i in 1 2 3; do adb shell input keyevent KEYCODE_PAGE_DOWN; sleep 1; done; shot 21_epub_page4 1
for i in 1 2 3 4 5; do adb shell input keyevent KEYCODE_PAGE_DOWN; sleep 1; done; shot 22_epub_page9 1

log "scroll mode (S §1.15)"
step 60_scroll_on scroll_on
step 61_66_scroll_moves scroll_moves
step 67_step_release scroll_release
step 68_69_round_trip scroll_round_trip
step 69b_back_to_paged scroll_off

log "big txt"
adb shell am force-stop $PKG
adb shell am start -W -a android.intent.action.VIEW -t text/plain -d file:///sdcard/Download/big-cp949.txt -n $PKG/.reader.ReaderActivity | tee -a shots/steps.txt
shot 30_big_txt 3
shot 31_big_txt_later 15
perf_mark 31
adb shell input keyevent KEYCODE_HOME; sleep 1 # onPause saves the position the reopen must come back to
adb shell am force-stop $PKG
adb shell am start -W -a android.intent.action.VIEW -t text/plain -d file:///sdcard/Download/big-cp949.txt -n $PKG/.reader.ReaderActivity | tee -a shots/steps.txt
shot 32_big_txt_reopen 2
perf_mark 32
perf_check 32 first_is 31 32

log "library after reading"
adb shell am start -W -n $PKG/.ui.library.LibraryActivity | tee -a shots/steps.txt
shot 40_library_after 5
top_is LibraryActivity 40_library_after
step 42_library_compact library_compact
step 43_44_library_views library_views
step 45_46_library_paged library_paged
step 46c_multiselect library_multiselect

log "settings (via the library's ⋮ menu; SettingsActivity is not exported)"
restart_library
library_more "설정" && shot 50_settings 3 # the whole main list: 읽기 화면 · 조작·기능 · 서재 · 책 가져오기 · 기타, a black rule above each later group
back
step 50b_stats stats_page
step 50c_wifi wifi_page
step 50d_eink_settings eink_settings
step 51_status_page status_page

log "notes (N §17)"
step 80_82_quotes notes_quotes
step 83_toc_quotes notes_toc
step 84_lookup notes_lookup
step 85_89_notes_hub notes_hub
step 89p_notes_paged notes_paged
step 90_highlight_ink notes_ink

log "anchored layout (A §6.6)"
STEP_TIMEOUT=480 step 52_footer_toggle footer_toggle
STEP_TIMEOUT=480 step 53_progress_toggle progress_toggle
step 54_margin_v_exact margin_v_exact
step 55_font_up_down font_up_down
step 56_page_break_paragraph page_break_paragraph
step 57_dialog_no_reflow dialog_no_reflow

log "page thumbnails (W2)"
step 92_93_thumbs page_thumbs

# ---- U1: back from recents must show the same book and page ------------------------------------------------------
launcher_intent="-a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n $PKG/.ui.library.LibraryActivity"

log "U1 setup: a launcher-rooted task, a book opened from the library, page 5"
# A previous reader test left an open marker. Close that book explicitly before testing a fresh launcher root.
adb shell am start -W -a android.intent.action.VIEW -t application/epub+zip -d file:///sdcard/Download/sample.epub \
  -n $PKG/.reader.ReaderActivity | tee -a shots/steps.txt
sleep 3; back
adb shell am force-stop $PKG
adb shell am start -W $launcher_intent | tee -a shots/steps.txt; sleep 4
library_home # library.shelf persists: an earlier step may have left a shelf without the book (CI 29: 작가)
set_list_mode "자세히"
tap_label "샘플 EPUB" contains || check 70_setup 1 "the sample book is not on the library list (shelf or view left by an earlier step)"
sleep 5
for i in 1 2 3 4; do adb shell input keyevent KEYCODE_PAGE_DOWN; sleep 1; done
shot 70_before 2; top_is ReaderActivity 70_before

log "U1-A: killed in the background, records kept (LMK)"
adb shell input keyevent KEYCODE_HOME; sleep 2
adb shell am kill $PKG; sleep 1
log "pid after am kill: '$(adb shell pidof $PKG | tr -d '\r')' (expected empty)"
overview_back; shot 71_after_kill 0; top_is ReaderActivity 71_after_kill; same 70_before 71_after_kill

log "U1-B: activities removed (force-stop = One UI cleaner / sleeping apps)"
adb shell input keyevent KEYCODE_HOME; sleep 2
adb shell am force-stop $PKG
adb shell dumpsys activity recents | grep -E "realActivity|baseIntent" | grep $PKG | head -2 | tee -a shots/steps.txt
overview_back; shot 72_after_force_stop 0; top_is ReaderActivity 72_after_force_stop; same 70_before 72_after_force_stop

log "U1-C: the exact intent recents sends for a task without activities (deterministic)"
adb shell am force-stop $PKG
adb shell am start -W -f 0x10100000 $launcher_intent | tee -a shots/steps.txt   # NEW_TASK | LAUNCHED_FROM_HISTORY
sleep 5; shot 73_history_intent 0; top_is ReaderActivity 73_history_intent; same 70_before 73_history_intent

log "U1-D: app updated in place while in the background"
adb shell input keyevent KEYCODE_HOME; sleep 2
adb install -r -g "$APK" | tee -a shots/steps.txt
overview_back; shot 74_after_update 0; top_is ReaderActivity 74_after_update; same 70_before 74_after_update

log "U1-E: a second book via onNewIntent, then killed: the SECOND book comes back"
adb shell am start -W -a android.intent.action.VIEW -t text/plain -d file:///sdcard/Download/sample-utf8.txt \
  -n $PKG/.reader.ReaderActivity | tee -a shots/steps.txt
sleep 4; adb shell input keyevent KEYCODE_PAGE_DOWN; shot 75_second_book 2
adb shell input keyevent KEYCODE_HOME; sleep 2; adb shell am kill $PKG; sleep 1
overview_back; shot 76_second_after_kill 0; top_is ReaderActivity 76_second_after_kill; same 75_second_book 76_second_after_kill

log "U1-F: 'Don't keep activities' (One UI developer option): destroyed and recreated in process"
adb shell settings put global always_finish_activities 1
adb shell input keyevent KEYCODE_HOME; sleep 2
overview_back; shot 77_dont_keep 0; top_is ReaderActivity 77_dont_keep; same 75_second_book 77_dont_keep
adb shell settings put global always_finish_activities 0

log "U1-H: a task manager or launcher that clears the task above the library (e-reader, 2026-10-04): the book comes back"
adb shell input keyevent KEYCODE_HOME; sleep 2
adb shell am start -W -f 0x14000000 $launcher_intent | tee -a shots/steps.txt   # NEW_TASK | CLEAR_TOP
sleep 5; shot 79_clear_top 0; top_is ReaderActivity 79_clear_top; same 75_second_book 79_clear_top
adb shell input keyevent KEYCODE_HOME; sleep 2
adb shell am start -W -f 0x10008000 $launcher_intent | tee -a shots/steps.txt   # NEW_TASK | CLEAR_TASK
sleep 5; shot 79b_clear_task 0; top_is ReaderActivity 79b_clear_task; same 75_second_book 79b_clear_task

log "U1-G (control): the user closed the book with BACK: the library must come back"
back; sleep 2; top_is LibraryActivity 78_closed
adb shell input keyevent KEYCODE_HOME; sleep 1; adb shell am force-stop $PKG
adb shell am start -W -f 0x10100000 $launcher_intent | tee -a shots/steps.txt
sleep 5; shot 78_closed_then_recents 0; top_is LibraryActivity 78_closed_then_recents

log "restore offer (S §3.9), last: pm clear wipes everything"
STEPS_UNTIL=$((${STEPS_UNTIL:-3000} + 600)) step 95_98_restore restore_offer

adb logcat -d > shots/logcat.txt
adb logcat -d -b crash > shots/crash.txt
adb logcat -d -s ReaderaPlus:* RAPerf:* > shots/perf.txt
if [ -s shots/crash.txt ] && grep -q "$PKG" shots/crash.txt; then check crash 1 "crash buffer names $PKG (shots/crash.txt)"
elif grep -q "draw failed" shots/logcat.txt; then check crash 1 "'draw failed' in logcat"
else check crash 0 "no crash, no 'draw failed'"; fi
log "done"
exit 0
