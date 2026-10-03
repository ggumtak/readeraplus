# Lane NOTES

LANE NOTES (stub owner tag in code: "NOTES") — 독서 노트 hub screen
Main files (relative to app/src/main/java/com/ggumtak/readeraplus/): ui/notes/ (whole directory: NotesActivity.kt + new NotesAdapter.kt, NotesMenus.kt, NotesText.kt, NotesWindow.kt)
Test files (relative to app/src/test/java/com/ggumtak/readeraplus/): ui/notes/NotesTextTest.kt (new), ui/notes/NotesWindowTest.kt (new)
Module-mode flags <OWN>: --own ui/notes  (plus nothing else; tests in those directories are included)
Status note: docs/next/NOTES_STATUS.md

TASKS
PLAN §4 "NOTES": N §9 in full (with docs/next/notes/hub.md §8–§9 as adopted): the screen (Comet 360 × 720 dp), tabs (전체/인용문/북마크/메모/단어 — exactly as N §9 names them), row kinds and gestures, day headers, multi-select as HashSet<Long> (a query when > 20k), overflow, chooser dialogs, search, empty states (§9.6 texts), the windowed adapter with fetch-then-move, one e-ink update to open and per page, saved state, the 단어 tab (§9.9, 다시 찾기), export (text/markdown, open "w" then "wt", an app-scope job, saved state) and share (§9.10). Read data only through data/Notes.kt, data/Lookups.kt, data/NotesExport.kt (lane DA-N implements them; code against their frozen signatures) and data/Library.kt writes (DA-C). The hub never opens book files (queries only). Jumping to a note: ReaderActivity.open(context, bookId, ReaderJump(...)) from the frozen contract (reader/ReaderJump.kt). Keep the frozen companion (EXTRA_TAB, EXTRA_BOOK_ID, open()).
Accept: NotesTextTest + NotesWindowTest green; N §9.11 budgets designed in (RANotes DEBUG timings); CI 85–89 expectations (PLAN §5.3 rows 34–36).
