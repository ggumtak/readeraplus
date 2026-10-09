#!/usr/bin/env bash
# Quick PDF viewer check on the emulator (commit message with "[pdf]"): opens a generated PDF, taps the settings
# gear, the pen tools and a pen preset, draws a stroke, and prints every crash to the job log. Best effort: never fails.
set -u
PKG=com.ggumtak.readeraplus
APK=$(ls dist/*.apk | head -1)
mkdir -p shots
log() { echo "== $*"; }
shot() { sleep "${2:-2}"; adb exec-out screencap -p > "shots/$1.png"; log "shot $1"; }
dump() { rm -f /tmp/ui.xml; adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb pull /sdcard/ui.xml /tmp/ui.xml >/dev/null 2>&1; }
tap_label() { dump; local xy; xy=$(python3 tools/ci/find_node.py /tmp/ui.xml "$1" exact "${2:-0}")
  if [ -n "$xy" ]; then adb shell input tap $xy; log "tap '$1' at $xy"; else log "NOT FOUND '$1'"; fi; }
crashes() { # prints new crashes since the last call
  local c; c=$(adb logcat -d -b crash 2>/dev/null)
  if [ -n "$c" ]; then echo "::group::CRASH after $1"; echo "$c"; echo "::endgroup::"; echo "CRASH_FOUND after $1"; adb logcat -c -b crash; fi
  log "top: $(adb shell dumpsys activity activities | grep -m1 'topResumedActivity' | sed 's/^ *//')"
}
size=$(adb shell wm size | sed -n 's/.*: \([0-9]*\)x\([0-9]*\).*/\1 \2/p' | tail -1)
W=${size% *}; H=${size#* }; CX=$((W / 2)); CY=$((H / 2))
log "screen ${W}x${H}"

adb install -r -g "$APK"
python3 tools/ci/make_pdf.py shots/test.pdf
adb push shots/test.pdf /sdcard/Download/test.pdf
adb shell appops set $PKG MANAGE_EXTERNAL_STORAGE allow 2>/dev/null
adb logcat -c; adb logcat -c -b crash

log "open"
open_pdf() { adb shell am start -W -a android.intent.action.VIEW -t application/pdf -d file:///sdcard/Download/test.pdf -n $PKG/.reader.pdf.PdfActivity; }
sleep 2; open_pdf; sleep 4
# The storage grant can land late on a fresh emulator: one more try when the file could not be read.
if adb logcat -d -s PdfActivity:W | grep -q "open failed"; then
  log "open failed once, retrying"; adb shell am force-stop $PKG; adb shell appops set $PKG MANAGE_EXTERNAL_STORAGE allow; sleep 2; open_pdf; sleep 4
fi
shot 01_open 1; crashes open

log "gear (settings sheet)"
tap_label "PDF 설정"; shot 03_settings 2; crashes gear
tap_label "어둡게"; shot 03b_dark 1; crashes tone
tap_label "기본"; crashes tone_back
tap_label "닫기"; sleep 1

descs() { dump; log "ui: $(grep -o 'content-desc="[^"]*"' /tmp/ui.xml | sed 's/content-desc=//' | grep -v '""' | tr '\n' ' ')"; }
inklog() { log "app log: $(adb logcat -d -s PdfActivity:D | grep -E 'tools:|ink changed|lasso' | tail -4 | tr '\n' '|')"; }
loop() { # a closed loop around the middle of the page (a lasso)
  adb shell input motionevent DOWN $((CX - 150)) $((CY - 80))
  for xy in "$CX $((CY - 120))" "$((CX + 150)) $((CY - 80))" "$((CX + 170)) $CY" "$((CX + 150)) $((CY + 80))" "$CX $((CY + 120))" "$((CX - 150)) $((CY + 80))" "$((CX - 170)) $CY" "$((CX - 150)) $((CY - 85))"; do
    adb shell input motionevent MOVE $xy; done
  adb shell input motionevent UP $((CX - 150)) $((CY - 85)); }

log "tools straight from reading (highlighter, lasso)"
descs
tap_label "형광펜"
# Screenshot while the highlighter is still down (the live layer), then lift: no black tiles around the line.
shot 04a0_before 0; log "dark share before: $(python3 tools/ci/dark_share.py shots/04a0_before.png $((CY - 120)) $((CY + 120)))"
adb shell input motionevent DOWN $((CX - 120)) $CY
for dx in -90 -60 -30 0 30 60 90 120; do adb shell input motionevent MOVE $((CX + dx)) $CY; done
shot 04a1_live 0; log "dark share live: $(python3 tools/ci/dark_share.py shots/04a1_live.png $((CY - 120)) $((CY + 120)))"
adb shell input motionevent UP $((CX + 120)) $CY
shot 04a_hl_read 1; log "dark share after: $(python3 tools/ci/dark_share.py shots/04a_hl_read.png $((CY - 120)) $((CY + 120)))"
inklog; crashes hl_read
tap_label "필기 끝내기"; sleep 1; inklog
tap_label "선택"; loop; shot 04b_lasso 2; inklog; crashes lasso
adb shell input keyevent KEYCODE_BACK; sleep 1; tap_label "필기 끝내기"; sleep 1

log "dark page: a black pen shows light while drawing too"
tap_label "PDF 설정"; sleep 1; tap_label "어둡게"; tap_label "닫기"; sleep 1
tap_label "펜" 0; YP=$((CY + 70))
shot 04p0_before 0; log "light share before: $(python3 tools/ci/dark_share.py shots/04p0_before.png $((YP - 6)) $((YP + 6)) light)"
adb shell input motionevent DOWN $((CX - 120)) $YP
for dx in -90 -60 -30 0 30 60 90 120; do adb shell input motionevent MOVE $((CX + dx)) $YP; done
shot 04p1_live 0; log "light share live: $(python3 tools/ci/dark_share.py shots/04p1_live.png $((YP - 6)) $((YP + 6)) light)"
adb shell input motionevent UP $((CX + 120)) $YP
shot 04p2_after 1; log "light share after: $(python3 tools/ci/dark_share.py shots/04p2_after.png $((YP - 6)) $((YP + 6)) light)"
inklog; crashes dark_pen
tap_label "필기 끝내기"; tap_label "PDF 설정"; sleep 1; tap_label "기본"; tap_label "닫기"; sleep 1

log "tool bar layout (fold, float, drag, dock)"
tap_label "도구 접기"; shot 04c_folded 1; descs; crashes fold
tap_label "도구 펼치기"; sleep 1; crashes unfold
tap_label "띄우기"; shot 04d_floating 1; crashes float
dump; xy=$(python3 tools/ci/find_node.py /tmp/ui.xml "도구 막대 옮기기" exact 0)
if [ -n "$xy" ]; then adb shell input swipe $xy $CX $((H * 2 / 3)) 700; log "dragged from $xy"; fi
shot 04e_dragged 1; descs; crashes drag
tap_label "위에 붙이기"; shot 04f_docked 1; crashes dock
# The docked strip dragged by its grip comes off and floats; then back to the start via the settings.
dump; xy=$(python3 tools/ci/find_node.py /tmp/ui.xml "도구 막대 옮기기" exact 0)
if [ -n "$xy" ]; then adb shell input swipe $xy $CX $((H / 2)) 700; log "undocked by drag from $xy"; fi
descs; crashes undock
tap_label "PDF 설정"; sleep 1; tap_label "도구 막대 위치 초기화"; sleep 1; tap_label "닫기"; sleep 1; descs; crashes reset

log "pen tools"
tap_label "필기"; shot 04_tools 2; crashes tools
tap_label "펜" 1; shot 05_pen2 2; crashes pen_select
tap_label "펜" 1; shot 06_pen_panel 2; crashes pen_panel
tap_label "닫기"; sleep 1
adb shell input swipe $((CX - 100)) $((CY - 60)) $((CX + 100)) $((CY + 40)) 400; shot 07_stroke 1; crashes stroke
tap_label "형광펜"; shot 08_hl 1; crashes hl_select
tap_label "형광펜"; shot 09_hl_panel 2; crashes hl_panel
tap_label "닫기"; sleep 1
tap_label "지우개"; adb shell input swipe $((CX - 100)) $((CY - 60)) $((CX + 100)) $((CY + 40)) 400; crashes eraser
tap_label "되돌리기"; crashes undo
tap_label "필기 끝내기"; shot 10_done 1; crashes done

log "side panels"
tap_label "페이지 탐색"; shot 11_pages 3; crashes pages
adb shell input keyevent KEYCODE_BACK; sleep 1
tap_label "찾기"; sleep 2; adb shell input text lighthouse; adb shell input keyevent KEYCODE_ENTER; shot 12_search 4; crashes search
adb shell input keyevent KEYCODE_ENTER; sleep 1; crashes enter_again
adb shell input keyevent KEYCODE_BACK; sleep 1; descs; crashes back1
# A second back only while the search panel is still open (it would close the viewer otherwise).
if grep -q '검색' /tmp/ui.xml; then adb shell input keyevent KEYCODE_BACK; sleep 1; fi; crashes back2
tap_label "책갈피"; crashes bookmark
adb shell input keyevent KEYCODE_DPAD_RIGHT; sleep 1; adb shell input keyevent KEYCODE_DPAD_LEFT; shot 13_keys 1; crashes keys
adb shell input tap $CX $CY; sleep 1; shot 14_fullscreen 1; crashes fullscreen
adb shell input tap $CX $CY; sleep 1; crashes bars_back

echo "::group::logcat (app, warnings and errors)"
adb logcat -d '*:W' | grep -iE "readeraplus|AndroidRuntime|FATAL|Pdf" | tail -200
echo "::endgroup::"
log "done"
exit 0
