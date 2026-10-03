# Lane RCA-S (RC-A part S: scroll mode + anchored relayouts) — read BRIEF.md and RCA-COMMON.md first

Files: reader/ReaderActivity.kt (S/A regions, see RCA-COMMON §3), reader/ReaderMenus.kt (the two S items only),
reader/TapZones.kt / reader/ReadingTracker.kt / reader/ChapterIndex.kt / reader/EndPanel.kt / reader/KeyMap.kt (only
if S requires), reader/ReaderMath.kt (append-only pure helpers if needed).
Tests: any new pure test under reader/ (e.g. reader/ScrollWiringTest.kt for extracted pure decisions such as the
switchMode offset choice); existing reader tests stay green.
Module flags: --own reader/ReaderActivity.kt --own reader/ReaderMenus.kt (+ every other file you edit/create, + tests)
Status note: docs/next/RCA_S_STATUS.md · result: docs/next/lanes/RCA-S.result.json

TASKS (PLAN §4 RC-A table rows "S" and "A" except startOpen; S = docs/next/scroll/SPEC.md §1.10–1.12, §1.2;
A = docs/next/wave2/anchor.md §5.5; ScrollReader/ScrollMath/PageView/BookSession are DONE (docs/next/RC_S_STATUS.md,
RC_S_INPUT_STATUS.md, RC_P_STATUS.md) — read their APIs and wire them, don't edit them)
- The `scroll: ScrollReader?` field; applyReadMode() (resolves motion = scrollStyle + DeviceClass; when readMode
  changed and a page is shown runs switchMode(); returns at once when PAGED and scroll == null); switchMode() exactly
  as S §1.10 (never lays anything out; exact round trips both directions).
- Every entry point of the S §1.10 table: showPage branch (TOP / CONTEXT placement), navigateTo/preloadImages, turn,
  flushTurns (max 10 instant steps, backlog.restore on NEED_SECTION), holdAction, onScrollSettled (page bookkeeping,
  touch, prefetch, trackPage, schedulePositionSave unless RELAYOUT, keeper.poke, bindChrome, onTurnShown cadence,
  DRAG/FLING TTS + onManualTurn via ScreenCounter, selection?.onPageChanged()), onScrollStart (close the chrome in the
  same e-ink update, OWNER_SEARCH removal + scroll.onHighlightsChanged, TTS userMoved), ReaderHost navigation
  suppression during scroll.userMoving(), goTo(remember=false) to a visible line while TTS speaks, isOnCurrentPage,
  buildDecor's scroll part (highlights = emptyList(), bookmarked within visibleRanges — RCA-U owns the status fill),
  refreshDecor/repaint, progress() atBookEnd, currentLayout/currentPage/currentPageIndex/currentPosition (virtual
  page), hitTest/glyphAtView/fingerOnChar (no allocation), onLongPress focusAt, handleTap focusAt/clearFocus with
  currentLayout, toggleBookmark/isCurrentPageBookmarked on the virtual page (RCA-N adds the note place to the add
  path: keep the add call in one place), jumpChapter via private jumpTo, setHighlights/reloadAnnotations/
  closeCurrentBook invalidation (scroll.onHighlightsChanged), sessionListener.onSectionStored, relayout/
  onViewSizeChanged/reopenDocument stopMotion FIRST, closeCurrentBook detach. NO page-turn animation in any mode
  (user directive): page commands are instant even in SMOOTH.
- U §5.6: scroll settle status inputs (anchor-line page, bar, chapterStartsHere): call the decor rebuild at settle;
  STEP settle redraw in the same task as invalidate.
- S §1.2: the ⋮ items "스크롤로 보기"/"페이지로 보기" (save AppSettings.readMode; choosing scroll starts
  DeviceClass.probeAsync) and, in SCROLL, "자동 넘김" → "자동 스크롤" with toast "자동 스크롤 켜짐 (한 화면/30초)".
- A §5.5: keepHere(); onViewSizeChanged (stopMotion, then setViewport(…, keepHere())); applyToSession
  updateSettings(eff, keepHere()); relayout uses AnchorMath.pageFor; display() JUMP → anchor = page start (C17; scroll
  uses S's rule); reopenDocument needle + AnchorSpec. (startOpen's anchor reorder belongs to RCA-N.)
- Lifecycle S lines: onStart → AutoBackup.cancelScheduled(); onStop (not during a configuration change) →
  AutoBackup.schedule(applicationContext, 5_000) { ReaderPresence.inFront }; C19 onTrimMemory gate
  (`level >= TRIM_MEMORY_BACKGROUND || level == TRIM_MEMORY_RUNNING_LOW || level == TRIM_MEMORY_RUNNING_CRITICAL` →
  session?.trimMemory(); scroll?.onTrimMemory(); thumbnails later); viewPart (C21) + a.readMode, a.scrollStyle.
- Run S §1.10's delegation grep at the end and list every hit with its justification in the status note.
Accept: paged behaviour unchanged below one null check; delegation grep clean; tests green.
