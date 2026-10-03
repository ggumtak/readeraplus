# Lane LIB

LANE LIB (stub owner tag in code: "LIB") — library: four views, paging, drawer notes rows, auto-restore offer, polish
Main files (relative to app/src/main/java/com/ggumtak/readeraplus/): ui/library/ (whole directory: LibraryActivity, LibraryViews, LibraryDialogs, LibraryText, CoverLoader, LibraryJobs, LibraryImport, BookSelection, new LibraryGridMath.kt, new AutoRestorePrompt.kt), ui/kit/InkTouch.kt (maintenance)
Test files (relative to app/src/test/java/com/ggumtak/readeraplus/): ui/library/LibraryTextTest.kt, ui/library/LibraryGridMathTest.kt (new), ui/kit/InkTouchTest.kt, ui/library/BookSelectionTest.kt, ui/library/ProgressThrottleTest.kt, ui/library/LibraryStartTest.kt
Module-mode flags <OWN>: --own ui/library --own ui/kit/InkTouch.kt --own ui/kit/InkTouchTest.kt  (plus nothing else; tests in those directories are included)
Status note: docs/next/LIB_STATUS.md

TASKS
PLAN §4 "LIB", conflicts C2, C29, C35:
- S §3.2 trigger 1 (idle 10 s → AutoBackup.runNow off main with a busy() that reports user activity), S §3.4 AutoRestorePrompt ("이전 설정과 읽기 기록을 복원할까요?" + scan hold + "다른 백업 보기" + the late-answer line), the one-time status line, InstallState.ensure FIRST in onCreate (C35 order: InstallState.ensure → startMode → RESUME/OPEN_LAST startOpenLast, or ensureUi; after the first list is drawn: DeviceLight.restoreIfStale (IO) then DeviceClass.probeAsync; refreshVisible: the restore-offer check then the idle backup wait).
- U §4.3 restoreIfStale after the first list; polish 14 (card padding (10,10,6,10) dp, no card border, 1 px LINE_LIGHT separator inset 8 dp, pressed = PRESSED fill, cover keeps its 1 px border, list paddingEnd 12dp, thin fast-scroll drawables are already in res).
- N §10 (and docs/next/notes/library.md): drawer rows 독서 노트 · 단어장 after 휴지통 with lazy counts (Notes counts off main; open NotesActivity with the tab), the book menu additions, delete/empty-trash warnings that mention notes, trash rows, the 4 views 전체/요약/썸네일/그리드 (LibraryListMode LIST/COMPACT/GRID/COVERS, frozen labels) with one GridView, measured paging for LIST/COMPACT and fixed rows for GRID/COVERS, ListPager + number pad (ui/kit/InkTouch paging part from P0), cover prefetch, DeviceClass.probeAsync after the first frame, CardButton without a pressed state on e-ink; the 목록 넘기기 (AppSettings.listPaging AUTO/쪽 단위/스크롤) behaviour: paged mode has NO fast scroller (C2); 0 queries on a view switch.
- Keep H1's startMode and H2's guard (InkListView/InkGridView, FastScrollGuard invariant: no clickable row pixel at x ≥ W − 12 dp); LibraryGridMath (pure: columns, cell sizes, last-column invariant < W − inset − 57 dp — tested).
Accept: one e-ink update per library page; 0 queries on a view switch; CI 41–46 expectations in PLAN §5.3 rows 1–3, 25–27.
