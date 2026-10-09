# Lane EX-P

LANE EX-P (stub owner tag in code: "EX-P") — reading-settings popup: 9 main rows, status slots, margins, page break, read mode
Main files (relative to app/src/main/java/com/ggumtak/readeraplus/): reader/extras/ReadingSettingsPopup.kt, reader/extras/CompactUi.kt, reader/extras/ExtrasFormat.kt
Test files (relative to app/src/test/java/com/ggumtak/readeraplus/): reader/extras/CompactSettingsTest.kt, reader/extras/PopupGeometryTest.kt (new or extend), reader/extras/FormatTest.kt, reader/extras/PopupStateTest.kt
Module-mode flags <OWN>: --own reader/extras/ReadingSettingsPopup.kt --own reader/extras/CompactUi.kt --own reader/extras/ExtrasFormat.kt --own reader/extras/CompactSettingsTest.kt --own reader/extras/PopupGeometryTest.kt --own reader/extras/FormatTest.kt --own reader/extras/PopupStateTest.kt  (+ --own for every new file you add, main or test)
Status note: docs/next/EX_P_STATUS.md

TASKS
PLAN §4 "EX-P", §1.6.3 "Reading-settings popup", conflict C27:
- Main section = exactly 9 rows, no scroll (U polish 8): 스타일 presets · 글꼴 · 글자 크기 · 굵기 · 줄 간격 · 문단 간격 · 들여쓰기 · 정렬 │ 줄바꿈 (merged row "정렬 [왼쪽│양쪽]   줄바꿈 [어절│글자]") · 더보기 ›. Compact metrics ROW_DP 44, STEP_DP 44, LABEL_SP 15, VALUE_SP 16, HEADER_SP 13, toggle minHeight 36dp; group lines LINE_LIGHT inset 12 dp, black only before section headers and 더보기; 더보기 shows label + chevron only.
- Under 더보기, in order: 페이지 넘김 (넘기는 방식 dropdown — choosing 스크롤 starts DeviceClass.probeAsync — · 화면 터치 · 볼륨 키 (keep H3's 3-way: 아래 = 다음 / 위 = 다음 / 끔)) · 글자 (글자 간격) · 페이지 (좌우 여백 stepper −40…+40 shown, "0" = 40 dp, live region, speaks "−10" (Fmt.signed) · 상하 여백 stepper (A §3.3) · 페이지 여백 switch (hides both steppers when off) · 흑백 반전 · 페이지 나눔 (줄 단위 │ 문단 단위; relayout, NOT debounced) · 외톨이 줄 방지 (summary "한 쪽보다 긴 문단에만 적용" in PARAGRAPH)) · TXT 파일 / EPUB 파일 as today (order by format) · 상태 표시 (U §5.5: 위 [slot][slot][slot] · 아래 [slot][slot][slot] chooser cells with live samples via StatusSampleHost and dashed "없음" cells · 진행 막대 switch with summary "화면 맨 아래 가는 선" · 상태 글자 크기 visible iff hasHeader || hasFooterText · the grey note "여백이 좁아 위 · 아래 정보가 보이지 않습니다. 상하 여백을 늘리세요." when !StatusFit.fitsDp (A §2.7)). Every status change is a REPAINT (never a relayout).
- U §2.6 popup branch (pinned chrome is gone: no pinned-openReadingSettings path), polish 7 (showAtLocation(root, TOP|CENTER_HORIZONTAL, 0, place.top); width = min(W − 16dp, 400dp); top = Overlay.topInset(root) + 8dp; HEIGHT_FRACTION 0.56), polish 18 (joined segmented control: one 1 px border, 1 px dividers, no radius; selected = black fill + white REGULAR text; drop the bold-width reservation hack).
- CompactList.show / PopupGeometry.dropdown(maxHeightFraction = 0.8).
Accept: the popup never scrolls on the main section at 1440 px (emulator density 2); margin steppers speak "−10"; PopupGeometryTest covers width/top/HEIGHT_FRACTION/dropdown fraction.
