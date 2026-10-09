# Lane DA-C

LANE DA-C (stub owner tag in code: "DA-C") — data core: note writes, scanner safety, return mark storage, v3 upgrade sweep
Main files (relative to app/src/main/java/com/ggumtak/readeraplus/): data/Library.kt, data/LibrarySql.kt, data/BookRows.kt, data/LibraryDb.kt, data/FileScanner.kt, data/MetaInfo.kt, data/BookPrefs.kt
Other files: tools/check_sql.py
Test files (relative to app/src/test/java/com/ggumtak/readeraplus/): data/LibrarySqlTest.kt, data/ScanPlanTest.kt, data/SqlDumpTest.kt, data/RowsAndMetaTest.kt, data/ScanWalkTest.kt, data/ReviewRegressionTest.kt, data/ReadingLogSqlTest.kt, data/LibrarySchemaV3Test.kt (read-only, P0 contract test: run it, do not edit); new tests under data/ (e.g. data/LibraryNotesWritesTest.kt for pure SQL/arg builders, data/BookPrefsReturnMarkTest.kt)
Module-mode flags <OWN>: --own data/Library.kt --own data/LibrarySql.kt --own data/BookRows.kt --own data/LibraryDb.kt --own data/FileScanner.kt --own data/MetaInfo.kt --own data/BookPrefs.kt --own data/LibrarySqlTest.kt --own data/ScanPlanTest.kt --own data/SqlDumpTest.kt --own data/RowsAndMetaTest.kt --own data/ScanWalkTest.kt --own data/ReviewRegressionTest.kt --own data/ReadingLogSqlTest.kt --own data/LibrarySchemaV3Test.kt  (+ --own for every new file you add, main or test)
Status note: docs/next/DA_C_STATUS.md

TASKS
PLAN §4 "DA-C" — this run splits DA-C in two: YOU own the library/DB core; the backup files (Backup.kt, BackupJson.kt, BackupMerge.kt, AutoBackup.kt, InstallState.kt) belong to lane DA-B running separately.
- N §5.1: the Library write API (quote/bookmark writes with style and place, updateQuoteStyle, setQuoteStyles, deleteQuotes, deleteBookmarks, clearReviews, fillNotePlaces, …) replacing the P0 stubs that delegated to old calls; `Library.notesGen` bumps AFTER each write commits (N §5.1 rules); every write off main.
- N §5.2: LibrarySql / BookRows changes (new columns chapter, frac, sig, style, review_at, missing_at; placeholder counts per PLAN: "LibrarySqlTest (11 / 9 placeholders)").
- N §5.5: FileScanner — notes are never lost silently (trash/revive/move, missing_at), plus ScanPlanTest cases.
- C1: LibraryDb runs `LibrarySchema.UPGRADE_SWEEP` in onUpgrade after the ALTERs when oldVersion < 3 (schema itself is frozen P0 work); the v2→v3 upgrade must stay ≤ 50 ms with 10k notes (no per-row work).
- U §3.3: BookPrefs.returnMark / setReturnMark persisted in book_prefs.return_mark; `resetProgress` (wherever reading progress is reset) clears it. (The backup "returnMark" field is DA-B's.)
- New tools/check_sql.py (N §16): runs the schema/upgrade SQL in python sqlite3 and checks plans for v1→v3, v2→v3, v3→"v2 build"→v3 (assert the indexes are used as N §16 / §5.3 expect). Parse the Kotlin constants from LibrarySchema.kt/LibrarySql.kt like SqlDumpTest does or embed via a small extractor; it must run with plain python3.
- SqlDumpTest (+ notes statements) per PLAN.
Accept: lane tests green; nothing is deleted silently by a scan; check_sql.py passes; v2→v3 cost is a constant number of statements.
