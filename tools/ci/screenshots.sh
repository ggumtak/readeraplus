#!/usr/bin/env bash
# Runs inside the emulator job: installs the APK, pushes original sample books, drives the app with adb and
# saves screenshots + logs into ./shots. Never fails the job on a UI step (best effort), but records crashes.
set -u
PKG=com.ggumtak.readeraplus
APK=$(ls dist/*.apk | head -1)
mkdir -p shots samples
log() { echo "== $*" | tee -a shots/steps.txt; }
shot() { sleep "${2:-2}"; adb exec-out screencap -p > "shots/$1.png"; log "shot $1"; }
dump() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb pull /sdcard/ui.xml /tmp/ui.xml >/dev/null 2>&1; }
tap_label() { # tap_label "label" [exact|contains]
  dump; local xy; xy=$(python3 tools/ci/find_node.py /tmp/ui.xml "$1" "${2:-exact}")
  if [ -n "$xy" ]; then adb shell input tap $xy; log "tap '$1' at $xy"; return 0; fi
  log "NOT FOUND '$1'"; return 1
}
back() { adb shell input keyevent KEYCODE_BACK; sleep 1; }

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

log "library"
adb shell am start -W -n $PKG/.ui.library.LibraryActivity | tee -a shots/steps.txt
shot 01_library 10
tap_label "메뉴" contains && shot 02_drawer && back

log "reader txt"
adb shell am start -W -a android.intent.action.VIEW -t text/plain -d file:///sdcard/Download/sample-cp949.txt -n $PKG/.reader.ReaderActivity | tee -a shots/steps.txt
shot 10_txt_page1 4
adb shell input keyevent KEYCODE_VOLUME_DOWN; shot 11_txt_page2 2
adb shell input tap 600 900; shot 12_txt_tap_right 2
adb shell input tap 360 720; shot 13_txt_chrome 2
dump; cp /tmp/ui.xml shots/ui_reader_chrome.xml 2>/dev/null
tap_label "읽기 설정" contains && shot 14_reading_settings 2 && back
tap_label "목차" contains || { adb shell input tap 360 720; sleep 1; tap_label "목차" contains; } && shot 15_toc 3 && back
adb shell input tap 360 720; sleep 1
if tap_label "검색" contains; then sleep 1; adb shell input text "English" ; adb shell input keyevent KEYCODE_ENTER; shot 16_search 4; back; fi
sleep 1; adb shell input swipe 300 700 300 700 900; shot 17_selection 2; back

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

log "settings (via drawer; SettingsActivity is not exported)"
adb shell input tap 664 104; sleep 1; tap_label "설정" && shot 50_settings 3
back

adb logcat -d > shots/logcat.txt
adb logcat -d -b crash > shots/crash.txt
adb logcat -d -s ReaderaPlus:* > shots/perf.txt
log "done"
exit 0
