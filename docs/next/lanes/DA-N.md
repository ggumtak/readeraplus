# Lane DA-N

LANE DA-N (stub owner tag in code: "DA-N") — notes read side: notes SQL, paging keys, lookups, export, debug seed
Main files (relative to app/src/main/java/com/ggumtak/readeraplus/): data/Notes.kt, data/NotesSql.kt, data/NotesKeys.kt, data/NotesExport.kt, data/Lookups.kt, data/DebugSeed.kt
Test files (relative to app/src/test/java/com/ggumtak/readeraplus/): data/NotesSqlTest.kt (new), data/NotesPagingTest.kt (new), data/NotesExportTest.kt (new), data/LookupWordsTest.kt (new), data/NotesKeysTest.kt (new)
Module-mode flags <OWN>: --own data/Notes.kt --own data/NotesSql.kt --own data/NotesKeys.kt --own data/NotesExport.kt --own data/Lookups.kt --own data/DebugSeed.kt --own data/NotesSqlTest.kt --own data/NotesPagingTest.kt --own data/NotesExportTest.kt --own data/LookupWordsTest.kt --own data/NotesKeysTest.kt  (+ --own for every new file you add, main or test)
Status note: docs/next/DA_N_STATUS.md

TASKS
PLAN §4 "DA-N": N §5.3 (data/NotesSql.kt pure + data/Notes.kt: union arms with every column aliased, keyset page keys, book orders as one statement, details with substr, scalar counts, caches keyed by Library.notesGen, one key scan per search), N §5.4 (Lookups + LookupWords: record/dedupe SQL, recordLookups opt-out honoured), N §5.7 (NotesExport streaming to a Writer: markdown/text formats, escaping additions, the 50k share cap TextActions.SHARE_MAX_CHARS), N §14 DebugSeed (DEBUG-only seeder, e.g. DEBUG_SEED_NOTES 10000, registered by App in DEBUG), and the N §5.8 budgets with a `RANotes` DEBUG log. See docs/next/notes/hub.md for details the spec adopts. The write side (Library.kt) is lane DA-C's; you read the same tables (schema v3 in data/LibrarySchema.kt, frozen).
Accept: the five new tests green; every query plan uses the v3 indexes (document the expected plan per query in a comment that tools/check_sql.py from DA-C can assert later).
