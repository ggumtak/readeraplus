# Lane DA-B

LANE DA-B (stub owner tag in code: "DA-C") — backups: v3 fields and merge, return mark, auto backup, install state
Main files (relative to app/src/main/java/com/ggumtak/readeraplus/): data/Backup.kt, data/BackupJson.kt, data/BackupMerge.kt (new), data/AutoBackup.kt, data/InstallState.kt
Test files (relative to app/src/test/java/com/ggumtak/readeraplus/): data/BackupJsonTest.kt, data/BackupR2Test.kt, data/BackupMergeTest.kt (new), data/AutoBackupPolicyTest.kt (new), data/InstallStateTest.kt (new), data/BookPrefsBackupTest.kt (new)
Module-mode flags <OWN>: --own data/Backup.kt --own data/BackupJson.kt --own data/BackupMerge.kt --own data/AutoBackup.kt --own data/InstallState.kt --own data/BackupJsonTest.kt --own data/BackupR2Test.kt --own data/BackupMergeTest.kt --own data/AutoBackupPolicyTest.kt --own data/InstallStateTest.kt --own data/BookPrefsBackupTest.kt  (+ --own for every new file you add, main or test)
Status note: docs/next/DA_B_STATUS.md

TASKS
PLAN §4 "DA-C" backup half (this run's lane DA-B; the library/DB core files belong to lane DA-C) and conflict C10:
- N §5.6: backup fields (quote style/place, bookmark place, lookups, reviewAt, missingAt), merge rules incl. placeholders, in a new pure data/BackupMerge.kt (tested by BackupMergeTest per N §16). Nothing is ever deleted by a restore.
- U §3.3: "returnMark" in each book_prefs entry, both ways.
- S §3.1–3.7: AutoBackup — location Download/ReaderaPlus/backup/ (survives uninstall), atomic writes, the triggers API (schedule(context, delayMs, busy), cancelScheduled, runNow), phases with busy() checked between queries and every 256 KB, snapshotJson with sorted raw prefs and a hash (UNCHANGED when equal), isDue/wouldEmpty/isBlank/pickDefault/toRotate(KEEP_OWN)/autoName/parseAutoName, findCandidates, restore, deleteFiles, lastWrittenAt, locationLabel; InstallState ensure/verify/installId/id8/offerPending/settleOffer; the header reader (version, createdAt, origin, summary written BEFORE books — C10) so the restore offer can read a summary without parsing the whole file. BackupJson.VERSION stays 1; every field optional both ways; S's snapshot hash covers every N/U field.
- K11 perf gate: after an auto backup the transient JSON must not linger (drop references, stream where possible); returning to the reader mid-run logs BUSY within one query or 256 KB.
- Callers are wired by others: the reader's onStart/onStop (RC-A), the library's idle trigger and restore offer (LIB), the 자동 백업 settings section (SET). Write rcaNotes for exact calls.
Accept: new tests green (AutoBackupPolicyTest covers isDue/wouldEmpty/isBlank/pickDefault/toRotate/names; InstallStateTest; BookPrefsBackupTest covers returnMark round trip); BackupR2Test still green.
