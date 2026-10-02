#!/usr/bin/env bash
# Runs inside the emulator job: installs the APK, pushes original sample books, drives the app with adb and
# saves screenshots + logs into ./shots. Never fails the job on a UI step (best effort), but records crashes.
# The later steps run through `step`: each in its own shell with a time limit, from a known state (the app restarted
# on the library or on a book), logging what it could not find and moving on.
set -u
PKG=com.ggumtak.readeraplus
APK=$(ls dist/*.apk | head -1)
mkdir -p shots samples
log() { echo "== $*" | tee -a shots/steps.txt; }
shot() { sleep "${2:-2}"; adb exec-out screencap -p > "shots/$1.png"; log "shot $1"; }
rawshot() { adb exec-out screencap > "shots/$1.raw"; }
perf_mark() {
  adb logcat -d -v monotonic -s RAPerf:D '*:S' > "shots/perf_$1.txt"
  log "PERF $1 $(grep 'show ' "shots/perf_$1.txt" | tail -1)"
}
no_relayout() {
  local result
  result=$(python3 - "$1" "$2" <<'PY'
import pathlib, sys
before=pathlib.Path('shots/perf_'+sys.argv[1]+'.txt').read_text().splitlines()
after=pathlib.Path('shots/perf_'+sys.argv[2]+'.txt').read_text().splitlines()
# The prefix also verifies that logcat did not wrap between the two marks.
ok=bool(before) and after[:len(before)]==before and not any('show RELAYOUT ' in x for x in after[len(before):])
print('PASS' if ok else 'FAIL')
PY
  )
  log "CHECK $1->$2 no_relayout $result"
  [ "$result" = PASS ]
}
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
xy_of() { python3 tools/ci/find_node.py /tmp/ui.xml "$1" "${2:-exact}" "${3:-0}"; } # "x y" in the last dump
has() { [ -n "$(xy_of "$@")" ]; }
tap_label() { # tap_label "label" [exact|contains] [index: 0 = first match, -1 = last]
  dump; local xy; xy=$(xy_of "$1" "${2:-exact}" "${3:-0}")
  if [ -n "$xy" ]; then adb shell input tap $xy; log "tap '$1' at $xy"; return 0; fi
  log "NOT FOUND '$1'"; return 1
}
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
restart_library() { restart_app -n $PKG/.ui.library.LibraryActivity; sleep 5; }
fresh_reader() { # fresh_reader file mime: the app cold-started on that book (no bars, panel or dialog)
  restart_app -a android.intent.action.VIEW -t "$2" -d "file:///sdcard/Download/$1" -n $PKG/.reader.ReaderActivity
  sleep 4
}
step() { # step name function: one best-effort UI step in its own shell (5 minutes at most); never fails the job
  # No new step after 20 minutes of this script, so a stuck emulator can't run the job into its time limit.
  if [ "$SECONDS" -gt "${STEPS_UNTIL:-1200}" ]; then log "step $1 skipped (out of time)"; return 0; fi
  log "step $1"
  local rc
  if command -v timeout >/dev/null; then timeout "${STEP_TIMEOUT:-300}" bash -c "$2"; rc=$?; else ( "$2" ); rc=$?; fi
  if [ "$rc" -eq 0 ]; then log "step $1 done"; else
    log "step $1 incomplete (exit $rc), continuing"
    cp /tmp/ui.xml "shots/ui_fail_$1.xml" 2>/dev/null
  fi
  return 0
}
scroll_find() { # scroll_find "label" [exact|contains]: swipes a settings page up until the label is on screen; sets XY
  local i y
  XY=""
  for i in $(seq 1 14); do
    dump || return 1
    XY=$(xy_of "$1" "${2:-exact}")
    if [ -n "$XY" ]; then
      y=${XY#* }
      [ "$y" -ge 150 ] && [ "$y" -le 1250 ] && return 0
    fi
    adb shell input swipe 100 1150 100 450 1000; sleep 1 # slow: the coast after it never skips a whole screen
  done
  log "NOT FOUND '$1' after scrolling"; XY=""; return 1
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
library_more_guard() {
  local before after xy x y opened
  set_list_mode "목록" || return 1
  dump || return 1
  before=$(first_title_stamp); xy=$(xy_of "책 메뉴")
  [ -n "$xy" ] || { log "CHECK 41 FAIL: book menu missing"; return 1; }
  tap_xy "$xy"; sleep 1; dump
  opened=0; has "책 정보" && opened=1
  shot 41_library_more 0; back; dump
  after=$(first_title_stamp)
  if [ "$opened" -eq 1 ] && [ -n "$before" ] && [ "$before" = "$after" ]; then
    log "CHECK 41 PASS: menu opened and title stayed at $after"
  else log "CHECK 41 FAIL: menu=$opened before='$before' after='$after'"; fi
  xy=$(xy_of "책 메뉴"); [ -n "$xy" ] || return 1
  x=${xy% *}; y=${xy#* }
  adb shell input swipe "$x" "$y" "$((x+2))" "$((y+6))" 150
  sleep 1; dump; opened=0; has "책 정보" && opened=1
  back; dump; after=$(first_title_stamp)
  if [ "$opened" -eq 1 ] && [ "$before" = "$after" ]; then log "CHECK 41b PASS: jitter opens menu without seeking"
  else log "CHECK 41b FAIL: menu=$opened before='$before' after='$after'"; fi
  adb shell input swipe 700 1200 700 1190 300
  log "CHECK 41 strip drag completed"
}

# ------------------------------------------------------------------ reader steps

settings_more() { # 14b: the reading-settings popup (already open) with 더보기 expanded, scrolled to the extra rows
  dump || return 1
  has "글자 크기" || { log "14b: the reading-settings popup is not the active window"; return 1; }
  if has "더보기"; then tap_xy "$(xy_of "더보기")"; sleep 2; dump || return 1; fi
  has "접기" || { log "14b: 더보기 did not open"; return 1; }
  align "접기" 140 # the 접기 row to the popup's top: the rows it opened fill the popup
  shot 14b_settings_more 2
}
toc_shots() { # 15: 목차 (header: 지금 · 화 번호 · 검색, pager bar); 15b: one page on with the pager's [다음 ▶]
  tap_label "목차" contains || { adb shell input tap 360 720; sleep 1; tap_label "목차" contains; } || return 1
  shot 15_toc 3
  tap_label "다음 페이지" || { adb shell input keyevent KEYCODE_PAGE_DOWN; log "15b: no pager button, sent PAGE_DOWN"; }
  shot 15b_toc_page2 2
  back
}
open_goto() { # 페이지 이동 from the bottom bar's page label (bars shown first); true once its number pad is on screen
  adb shell input tap 360 720; sleep 2
  tap_label "페이지 이동" || return 1
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
hide_chrome() { # closes the go-to dialog / the reader's bars while they show (BACK without them would leave the book)
  local i
  for i in 1 2 3; do
    dump || return 1
    has "페이지 이동" || return 0
    back
  done
}
goto_numpad() { # 15c: 페이지 이동 with its number pad ([페이지] [%] [화] over the pad), "12" typed
  fresh_reader sample-cp949.txt text/plain
  open_goto || return 1
  numpad_type 12
  shot 15c_goto_numpad 2
  back # 취소: the reader stays where it was
}
dialog_no_reflow() {
  fresh_reader sample-cp949.txt text/plain
  # Keep chrome visible in both captures; the dialog alone takes and returns focus.
  adb shell input tap 360 720; sleep 2
  rawshot 57_before
  perf_mark 57_before
  tap_label "페이지 이동" || return 1
  sleep 2
  shot 57_dialog_no_reflow 0
  back; sleep 2
  rawshot 57_after
  perf_mark 57_after
  local result
  result=$(python3 tools/ci/raw_equal.py shots/57_before.raw shots/57_after.raw 360 1100)
  log "CHECK 57 pixels $result"
  no_relayout 57_before 57_after || return 1
  [ "$result" = EQUAL ]
}
choose_volume_mode() {
  adb shell input tap 360 720; sleep 1
  tap_label "읽기 설정" contains || return 1
  scroll_find "더보기" || return 1
  tap_xy "$XY"; sleep 1
  scroll_find "볼륨 키" || return 1
  tap_xy "$XY"; sleep 1
  dump || return 1
  has "아래 = 다음 페이지 (기본)" && has "위 = 다음 페이지 (방향 반전)" && has "넘기지 않음 (볼륨 조절)" || return 1
  if [ "$1" = "위 = 다음 페이지 (방향 반전)" ]; then shot 14d_volume_mode 0; fi
  tap_label "$1" || return 1
  back; sleep 2
}
volume_mode() {
  fresh_reader sample-cp949.txt text/plain
  perf_mark 14d_before
  choose_volume_mode "위 = 다음 페이지 (방향 반전)" || return 1
  perf_mark 14d_changed
  no_relayout 14d_before 14d_changed || return 1
  adb shell input keyevent KEYCODE_VOLUME_UP; sleep 2
  perf_mark 14d_turned
  local result
  result=$(python3 - <<'PY'
import pathlib, re
def page(mark):
  rows=re.findall(r'show (\w+) s:(\d+) o:(\d+)',pathlib.Path('shots/perf_'+mark+'.txt').read_text())
  return rows[-1] if rows else None
a,b=page('14d_changed'),page('14d_turned')
print('PASS' if a and b and b[0]=='TURN' and tuple(map(int,b[1:]))>tuple(map(int,a[1:])) else 'FAIL')
PY
  )
  log "CHECK 14d volume-up-next $result"
  choose_volume_mode "아래 = 다음 페이지 (기본)" || return 1
  [ "$result" = PASS ]
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
    log "17b: UNEXPECTED - a popup (selection bar?) after a long-press on blank space ($before -> $after)"
    back
  else
    log "17b: ok - no selection bar ($before -> $after popups)"
  fi
  for i in 1 2 3; do
    adb shell input keyevent KEYCODE_PAGE_DOWN; sleep 2
    dump && has "다 읽었습니다" && break
  done
  shot 18_end_panel 1
  if has "다 읽었습니다"; then log "18: end panel shown"; back; else log "18: no end panel after $i page-downs"; return 1; fi
}

# ------------------------------------------------------------------ library and settings steps

set_list_mode() { # set_list_mode 목록|간단히|표지: the toolbar's view toggle (목록 → 간단히 → 표지), else ⋮ → 보기
  local i
  for i in 1 2 3 4; do
    dump || return 1
    has "보기: $1" contains && return 0
    tap_xy "$(xy_of "(눌러서 바꾸기)" contains)" || break
    sleep 2
  done
  tap_label "메뉴" contains -1 || return 1 # ⋮ (the first "메뉴" is the drawer's ☰)
  sleep 1
  tap_label "보기:" contains || { back; return 1; }
  sleep 1
  tap_label "$1" || { back; return 1; }
  sleep 3
}
library_compact() { # 41: the library as 간단히 (T1-13), then back to 목록
  restart_library
  set_list_mode "간단히" || return 1
  shot 41_library_compact 2
  set_list_mode "목록"
}
library_multiselect() { # 42: a long-press on a book starts multi-select ("1권 선택" and the batch actions, T1-13)
  restart_library
  dump || return 1
  local xy
  xy=$(xy_of "sample-utf8")
  [ -n "$xy" ] || xy=$(xy_of "sample" contains)
  [ -n "$xy" ] || xy=$(xy_of "샘플" contains)
  [ -n "$xy" ] || { xy="360 330"; log "42: no sample book title on screen, long-pressing $xy"; }
  set -- $xy
  longpress "$1" "$2"
  shot 42_multiselect 3
  dump || return 1
  if has "권 선택" contains; then log "42: selection toolbar shown"; back; else log "42: no selection toolbar"; return 1; fi
}
open_drawer() { tap_label "메뉴" contains && sleep 1; } # ☰: the first "메뉴" of the library toolbar
stats_page() { # 51: drawer → 읽기 기록 (T1-6)
  restart_library
  open_drawer || return 1
  tap_label "읽기 기록" || return 1
  shot 51_stats 4
}
wifi_page() { # 52: drawer → Wi-Fi로 책 받기 (T1-12)
  restart_library
  open_drawer || return 1
  tap_label "Wi-Fi로 책 받기" || tap_label "Wi-Fi" contains || return 1
  shot 52_wifi 5
}
eink_settings() { # 53: 설정 → 넘김·화면 설정 at its "e-ink 화면" section (T1-3); 53b: the section's 고급 group opened
  restart_library
  open_drawer || return 1
  tap_label "설정" || return 1
  sleep 2
  scroll_find "넘김·화면 설정" || return 1
  tap_xy "$XY" || return 1
  sleep 3
  scroll_find "e-ink 화면" || return 1
  align "e-ink 화면" 260 # the section header just under the toolbar
  shot 53_eink_settings 2
  scroll_find "고급" || return 1
  tap_xy "$XY" || return 1
  sleep 2
  dump && align "고급" 260
  shot 53b_eink_advanced 2
}

export PKG
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
tap_label "메뉴" contains && shot 02_drawer && back

log "reader txt"
adb shell am start -W -a android.intent.action.VIEW -t text/plain -d file:///sdcard/Download/sample-cp949.txt -n $PKG/.reader.ReaderActivity | tee -a shots/steps.txt
shot 10_txt_page1 4
adb shell input keyevent KEYCODE_VOLUME_DOWN; shot 11_txt_page2 2
adb shell input tap 600 900; shot 12_txt_tap_right 2
adb shell input tap 360 720; shot 13_txt_chrome 2
dump; cp /tmp/ui.xml shots/ui_reader_chrome.xml 2>/dev/null
if tap_label "읽기 설정" contains; then
  shot 14_reading_settings 2
  step 14b_settings_more settings_more
  back
fi
step 15_toc toc_shots
adb shell input tap 360 720; sleep 1
if tap_label "검색" contains; then sleep 1; adb shell input text "English" ; adb shell input keyevent KEYCODE_ENTER; shot 16_search 4; back; fi
sleep 1; adb shell input swipe 300 700 300 700 900; shot 17_selection 2; back

log "reader txt: go-to pad, long-press on blank space, end of book"
step 14d_volume_mode volume_mode
step 57_dialog_no_reflow dialog_no_reflow
step 15c_goto_numpad goto_numpad
step 17b_18_end_of_book end_of_book
fresh_reader sample-cp949.txt text/plain # the TXT book in front again, as the EPUB part below always found it

log "reader epub"
adb shell am start -W -a android.intent.action.VIEW -t application/epub+zip -d file:///sdcard/Download/sample.epub -n $PKG/.reader.ReaderActivity | tee -a shots/steps.txt
shot 20_epub_page1 5
for i in 1 2 3; do adb shell input keyevent KEYCODE_PAGE_DOWN; sleep 1; done; shot 21_epub_page4 1
for i in 1 2 3 4 5; do adb shell input keyevent KEYCODE_PAGE_DOWN; sleep 1; done; shot 22_epub_page9 1

log "big txt"
adb shell am force-stop $PKG
adb shell am start -W -a android.intent.action.VIEW -t text/plain -d file:///sdcard/Download/big-cp949.txt -n $PKG/.reader.ReaderActivity | tee -a shots/steps.txt
shot 30_big_txt 3
shot 31_big_txt_later 15
adb shell am force-stop $PKG
adb shell am start -W -a android.intent.action.VIEW -t text/plain -d file:///sdcard/Download/big-cp949.txt -n $PKG/.reader.ReaderActivity | tee -a shots/steps.txt
shot 32_big_txt_reopen 2

log "library after reading"
adb shell am start -W -n $PKG/.ui.library.LibraryActivity | tee -a shots/steps.txt
shot 40_library_after 5
step 41_library_compact library_compact
step 42_multiselect library_multiselect

log "settings (via the library's ⋮ menu; SettingsActivity is not exported)"
restart_library
adb shell input tap 664 104; sleep 1; tap_label "설정" && shot 50_settings 3
back
step 51_stats stats_page
step 52_wifi wifi_page
step 53_eink_settings eink_settings

# ---- U1: back from recents must show the same book and page ------------------------------------------------------
top_is() { # top_is <Activity> <step>: the resumed activity, from dumpsys
  local t; t=$(adb shell dumpsys activity activities | grep -m1 -E "topResumedActivity=|mResumedActivity" | tr -d '\r')
  case "$t" in *"$1"*) log "PASS $2: $1 on top";; *) log "FAIL $2: expected $1, got: $t";; esac
}
same() { python3 tools/ci/same_page.py "shots/$1.png" "shots/$2.png" "$2" | tee -a shots/steps.txt; }
overview_back() { # recents, then the centred (most recent) card
  adb shell input keyevent KEYCODE_APP_SWITCH; sleep 3
  adb shell input tap 360 620; sleep 6
}
launcher_intent="-a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n $PKG/.ui.library.LibraryActivity"

log "U1 setup: a launcher-rooted task, a book opened from the library, page 5"
# A previous reader test left an open marker. Close that book explicitly before testing a fresh launcher root.
adb shell am start -W -a android.intent.action.VIEW -t application/epub+zip -d file:///sdcard/Download/sample.epub \
  -n $PKG/.reader.ReaderActivity | tee -a shots/steps.txt
sleep 3; back
adb shell am force-stop $PKG
adb shell am start -W $launcher_intent | tee -a shots/steps.txt; sleep 4
tap_label "샘플 EPUB" contains; sleep 5
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

log "U1-G (control): the user closed the book with BACK: the library must come back"
back; sleep 2; top_is LibraryActivity 78_closed
adb shell input keyevent KEYCODE_HOME; sleep 1; adb shell am force-stop $PKG
adb shell am start -W -f 0x10100000 $launcher_intent | tee -a shots/steps.txt
sleep 5; shot 78_closed_then_recents 0; top_is LibraryActivity 78_closed_then_recents

adb logcat -d > shots/logcat.txt
adb logcat -d -b crash > shots/crash.txt
adb logcat -d -s ReaderaPlus:* RAPerf:* > shots/perf.txt
log "done"
exit 0
