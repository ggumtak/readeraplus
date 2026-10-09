# Lane RCA-U (RC-A part U) — read BRIEF.md and RCA-COMMON.md first

Files (relative to the package root): reader/ReaderActivity.kt (U regions, see RCA-COMMON §3), reader/ReaderFormat.kt,
reader/ReaderMenus.kt (only the narrow-screen 북마크 item), reader/ReaderWindow.kt (only if U requires it).
Tests: reader/ReaderFormatTest.kt, reader/ReaderR2FeaturesTest.kt (drop returnChip, footerLeft/Right; previewLabel
"1234쪽 · …"), reader/ReaderReviewFixesTest.kt (maint), any new pure test you add under reader/.
Module flags: --own reader/ReaderActivity.kt --own reader/ReaderFormat.kt --own reader/ReaderMenus.kt
--own reader/ReaderFormatTest.kt --own reader/ReaderR2FeaturesTest.kt --own reader/ReaderReviewFixesTest.kt (+ new files)
Status note: docs/next/RCA_U_STATUS.md · result: docs/next/lanes/RCA-U.result.json

TASKS (PLAN §4 RC-A table, rows "U", plus C18, C21, C22-narrow, C24, C33; spec U = docs/next/ui/UI_SPEC.md)
- U §2.3: delete PREF_BRIGHTNESS_COLLAPSED; stop writing page.brightnessSwipe in applyAppSettings (LightController is
  the only writer, through the private lightHost's setPageBrightnessSwipe).
- U §2.6 table: delete pinShown, pinPending, pinnedArea, chromeBarHeights, togglePin, the barsResized registrations,
  the `!app.pinChrome` guards and the pinned openReadingSettings branch; applyPinnedArea → applyPageInsets (insets
  only); onBarsResized = updateChipPosition. C18: H4's InsetsGate stays; applyInsets(i) stores the insets and calls
  applyPageInsets(). After this, the PageView size depends only on the gated system insets.
- U §3.5 items 1–10: ReturnNav wiring (replace returnStack/chip code; pushReturn → returnNav.onJump at goTo,
  goToPage, goToProgress, followLink, seek release, keeping their guards; onManualTurn → returnNav.onManualTurn();
  onPinHere → returnNav.onPinPressed(); the private returnHost object fully implemented (no TODO left):
  currentPosition = page start (paged) / virtual page start (scroll: `// R3 merge(RCA-S)` if needed), isOnCurrentPage,
  globalPageOf (counts), jumpToReturn = jumpTo(p.section, p.offset, -1), charProgressOf, locateFraction, textSignature,
  saveReturnMark = ReaderIo.launch { BookPrefs.setReturnMark(id, t) }, onReturnChanged = if (chromeVisible) bindChrome();
  updateChipPosition(); bindChrome adds returnNav.bind() and chrome.setPinned(returnNav.pinned, returnNav.markOnScreen());
  sessionListener.onCountsChanged(complete = true) → if (chromeVisible) returnNav.bind(); reopenDocument
  markFraction/reparsed (insert the two lines; RCA-S owns the rest of reopenDocument); closeCurrentBook returnNav.reset().
  (The afterOpen pin load is written by RCA-N.)
- U §4.2 hooks: LightController wiring, the private lightHost object (saveApp, setPageBrightnessSwipe, showChrome, …),
  delete setBrightness; viewPart (C21) = today's list − a.pinChrome + a.brightnessDevice (RCA-S adds a.readMode,
  a.scrollStyle; RCA-N adds a.highlightLook); onCreate light.onCreate(); onResume light.onResume(); onDestroy
  `// R3 merge(RCA-N): light.onDestroy(isFinishing)` is RCA-N's.
- U §5.3: buildDecor(sample) with StatusInputs filled from the current page (page/total from counts.globalPage,
  percent, bar via ProgressMath, chapterTitle, bookTitle, chapterStartsHere (polish 16), chapterPagesLeft, episode
  minutes/numbers, tocIndex/Count, minuteOfDay + is24 without Calendar allocation, cached battery via a cached
  IntentFilter), StatusModel.update(settings, inputs, trackPx) deciding repaint; the mutation invariant; lazy highlight
  list; implement StatusSampleHost (frozen ReaderPanels capability) via StatusModel.sample; scheduleEpisodes only when
  settings.shows(EPISODE); onResume refreshes tz/24h/battery then refreshDecor(onlyIfChanged = true).
- polish 16–17: ReaderFormat deletes footerLeft, footerRight, returnChip; previewLabel "1234쪽 · 제3장 …".
- C22 (narrow): when !ChromeMath.bookmarkFits(rowW, density) add "북마크 추가"/"북마크 삭제" to ⋮ after 페이지 이동.
- C24: chip placement bottomMargin = insets[3] + 12dp + 4dp; the chip is an overlay and never resizes the page.
Accept: `grep -n 'pinChrome\|applyPinnedArea\|returnStack\|PREF_BRIGHTNESS_COLLAPSED' reader/ReaderActivity.kt`
prints nothing; no TODO("owner: RC-A") left in the hosts; tests green.
