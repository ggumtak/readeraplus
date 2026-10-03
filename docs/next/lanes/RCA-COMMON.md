# RC-A parts: shared rules (read with BRIEF.md)

RC-A ("ReaderActivity and friends", PLAN §4 "RC-A", the single hot-file lane) is split into THREE parts that run in
parallel on separate branches from the same base: **RCA-U** (chrome, return point, brightness hooks, status decor),
**RCA-S** (scroll mode + A's anchored relayouts), **RCA-N** (notes: the merged open path, peek, jumps, quote colours,
lifecycle consolidation). The lead then 3-way merges the branches; conflicts inside ReaderActivity.kt are resolved
against PLAN §1.6.1 (startOpen) and §1.6.2 (lifecycle orders).

For RC-A parts, BRIEF rule 2 is replaced by this: you ARE the integration lane for your part. The W1 lanes (RU-C,
RU-L, DA-*, EX-*, LIB, SET, NOTES) are being implemented in parallel too: code strictly against the frozen
signatures in docs/R3_INTERFACES.md (in your checkout they are still stubs; the real bodies arrive at merge).

Merge-friendliness rules (important):
1. Touch ReaderActivity.kt only where your part needs to. Don't reorder, reformat, rename or move existing code you
   don't have to change. Add new private members/functions near the code they belong to; prefer a new private helper
   over a long inline block. Keep existing signatures unless your part's spec changes them.
2. Only reference members that exist in the base or that YOUR part adds. When your spec item needs another part's
   member (e.g. RCA-N's onPause needs RCA-S's `scroll?.stopMotion()`), do NOT add it; leave the exact comment
   `// R3 merge(RCA-S): scroll?.stopMotion()` (with the owning part's name and the call) at the spot, so the merge adds it.
3. Function ownership inside ReaderActivity.kt (others only leave `// R3 merge(...)` comments there, or add the one
   hook line their spec requires when no other part owns the spot):
   - RCA-U: buildViews/onCreate (incl. `ReaderPresence.inFront = true` as the first line, S §1.10), onResume,
     applyInsets/applyPinnedArea→applyPageInsets, bindChrome, chromeActions, the returnHost/lightHost objects,
     buildDecor's status part (StatusInputs, clock, battery, chapterStartsHere, StatusSampleHost), refreshDecor's
     onlyIfChanged, scheduleEpisodes, viewPart's U keys, everything named in PLAN's RC-A table rows "U".
   - RCA-S: every `scroll?.let` branch of S §1.10, switchMode/applyReadMode, onScrollSettled/onScrollStart,
     navigation suppression during motion, onStart/onStop (new overrides), onTrimMemory (C19 gate), A §5.5 keepHere,
     onViewSizeChanged, applyToSession, relayout, display()/JUMP anchor, reopenDocument, closeCurrentBook's scroll
     detach, viewPart's S keys.
   - RCA-N: startOpen (the WHOLE merged §1.6.1, including A's anchor reorder), afterOpen (the WHOLE §1.6.2 list of 8
     steps — write steps 2–5 yourself from the spec: InstallState.ensure, light.afterFirstPage(), the probeAsync callback
     with QuoteLook (leave `// R3 merge(RCA-S): scroll?.onDeviceClass()` inside it), the return-mark load per U §3.3),
     onNewIntent, onPause (leave the RCA-S stopMotion merge comment first, `light.onPause()` last), onDestroy,
     onSaveInstanceState (rp.peek), applyAppSettings ordering (U line → QuoteLook → `// R3 merge(RCA-S): applyReadMode()`
     → `// R3 merge(RCA-U): applyPageInsets()` as needed), reloadAnnotations/quotes/bookmarks/places, toggleBookmark.
   - ReaderMenus.kt: RCA-N owns the final C22 order; RCA-S adds its two items (스크롤로 보기/페이지로 보기, 자동
     넘김↔자동 스크롤) and RCA-U the narrow-screen 북마크 추가/삭제 item, each as a small self-contained insertion.
   - ReaderFormat.kt: RCA-U. ReaderJump.kt, new DecorDiff.kt, new PeekRule.kt: RCA-N. ReaderMath.kt: whoever needs a
     pure helper adds a new object at the end of the file (no edits to existing objects without need).
4. Keep H4's DEBUG `RAPerf show …` line. Add nothing between startOpen and showPage except what §1.6.1 lists.
5. Module-mode flags for your checks: `--own reader/ReaderActivity.kt` plus every other main/test file you edit or
   create (listed in your part file). At the end also run the full-tree tools.
