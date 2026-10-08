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
adb shell am start -W -a android.intent.action.VIEW -t application/pdf -d file:///sdcard/Download/test.pdf -n $PKG/.reader.pdf.PdfActivity
shot 01_open 5; crashes open

log "gear"
adb shell input tap $CX $CY; sleep 1; shot 02_chrome 1
tap_label "PDF 설정"; shot 03_settings 2; crashes gear
adb shell input keyevent KEYCODE_BACK; sleep 1

log "pen tools"
adb shell input tap $CX $CY; sleep 1
tap_label "필기"; shot 04_tools 2; crashes tools
tap_label "펜" 1; shot 05_pen2 2; crashes pen_select
tap_label "펜" 1; shot 06_pen_panel 2; crashes pen_panel
adb shell input keyevent KEYCODE_BACK; sleep 1
adb shell input swipe $((CX - 200)) $((CY - 100)) $((CX + 200)) $((CY + 50)) 400; shot 07_stroke 1; crashes stroke
tap_label "형광펜"; shot 08_hl 1; crashes hl_select
tap_label "형광펜"; shot 09_hl_panel 2; crashes hl_panel
adb shell input keyevent KEYCODE_BACK; sleep 1
tap_label "지우개"; adb shell input swipe $((CX - 200)) $((CY - 100)) $((CX + 200)) $((CY + 50)) 400; crashes eraser
tap_label "완료"; shot 10_done 1; crashes done

log "search / thumbs"
adb shell input tap $CX $CY; sleep 1; tap_label "쪽 목록"; shot 11_thumbs 3; crashes thumbs
adb shell input keyevent KEYCODE_BACK; sleep 1
adb shell input keyevent KEYCODE_DPAD_RIGHT; sleep 1; adb shell input keyevent KEYCODE_DPAD_LEFT; shot 12_keys 1; crashes keys

echo "::group::logcat (app, warnings and errors)"
adb logcat -d '*:W' | grep -iE "readeraplus|AndroidRuntime|FATAL|Pdf" | tail -200
echo "::endgroup::"
log "done"
exit 0
