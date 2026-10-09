# Lane SET

LANE SET (stub owner tag in code: "SET") — settings pages: read mode, margins, page break, status bar, brightness, backups, lookups
Main files (relative to app/src/main/java/com/ggumtak/readeraplus/): ui/settings/ (whole directory; mainly PageTurningPage, MainPage, BackupPage, AboutPage, LookupPage, SettingsPage, SettingsLogic, KeyNames, SettingsActivity only if a page is added)
Test files (relative to app/src/test/java/com/ggumtak/readeraplus/): ui/settings/SettingsFormatTest.kt, ui/settings/KeyNamesTest.kt, the other ui/settings tests (maintenance)
Module-mode flags <OWN>: --own ui/settings  (plus nothing else; tests in those directories are included)
Status note: docs/next/SET_STATUS.md

TASKS
PLAN §4 "SET" and §1.6.3 (PageTurningPage table, MainPage list, BackupPage, AboutPage, LookupPage), K12:
- PageTurningPage: new first section 넘기는 방식 (페이지 넘김 (기본) / 스크롤; SCROLL starts DeviceClass.probeAsync; 스크롤 움직임 row only in SCROLL with the AUTO summary "자동 (이 기기: …)"; note); 화면 터치 minus 메뉴 고정; 스와이프 · 길게 누르기 (스와이프로 넘김 summary in SCROLL "좌우로 밀면 한 화면씩"; 세로 스와이프 DISABLED in SCROLL via setRowEnabled); 버튼 · 키 (keep H3 rows); 자동 넘김, 책 끝 as today; 페이지 표시 (좌우 여백 stepper first, 상하 여백, the note "0이 기본 여백입니다. −로 좁히고 +로 넓힙니다. 위 · 아래 정보와 진행 막대는 이 여백 안에 표시됩니다.", 페이지 나눔 valueRow + chooser texts per A §4.4, 흑백 반전, 페이지 여백); new 상태 표시줄 section (note, 위 · 왼쪽 / 위 · 가운데 / 위 · 오른쪽 / 아래 · 왼쪽 / 아래 · 가운데 / 아래 · 오른쪽 valueRows, 진행 막대, 상태 표시 글자 크기, A §2.7 fit note); e-ink 화면 as today.
- MainPage: 일반 (… 서재 보기 with the 4 N views and chooser texts, 목록 넘기기 (N)); 읽기 설정 in the §1.6.3 order including 스와이프로 밝기 조절 (S summary in SCROLL), 기기 밝기 직접 조절, 리더를 나가면 원래 밝기로, 밝기 방식 다시 확인, 기기 조명 설정 열기 (U §4.6), 인용문 색 표시 with the swatch strip (N; AUTO/흑백 무늬/색 — HL_LOOK_* consts), …; "설정 초기화" (K12) must ALSO keep autoBackup, recordLookups, brightnessDevice and listPaging (R2 already keeps TXT defaults, key bindings and the library view); reader settings reset to 40/40/40/40, LINE and the default slots; list the kept fields in the dialog text.
- BackupPage: existing rows + 자동 백업 section (S §3.8: toggle, 지금 자동 백업하기, 자동 백업에서 복원, 자동 백업 파일 지우기, privacy note mentioning 단어장) + the N §11 merge note.
- AboutPage: keep R's 최근 종료; new 조명 진단 section (U §4.6 with the 30 s change watch, via LightProbe.report / DeviceLight).
- LookupPage: new 단어장 section (N §11: record toggle a.recordLookups, clear, open the hub's 단어 tab).
- polish 12: section() without the hairline (header padding 24/8), navRow chevron and valueRow ▾ both 24 dp Ink.GRAY, row end padding 16 dp; TalkBack reads disabled rows as unavailable.
Accept: SettingsFormatTest additions (incl. "자동 (이 기기: 흑백 무늬 | 쪽 단위 | e-ink → 손을 떼면 이동)" for true/false/null device class); KeyNamesTest green; CI 50, 51, 14d expectations (PLAN §5.3 row 28).
