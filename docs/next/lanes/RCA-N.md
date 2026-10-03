# Lane RCA-N (RC-A part N: notes, merged open path, lifecycle) — read BRIEF.md and RCA-COMMON.md first

Files: reader/ReaderActivity.kt (N regions + startOpen/afterOpen/onNewIntent/onPause/onDestroy/onSaveInstanceState
per RCA-COMMON §3), reader/ReaderJump.kt (JumpAnchor.find), new reader/DecorDiff.kt, new reader/PeekRule.kt,
reader/ResumeState.kt (maint), reader/ReaderMenus.kt (final C22 order + "독서 노트").
Tests: reader/ReaderJumpTest.kt (+ find, NoteSig, EPUB sig mismatch), new reader/DecorDiffTest.kt, new
reader/PeekRuleTest.kt, reader/ReaderMathTest.kt / reader/ReaderRestoreTest.kt (maint).
Module flags: --own reader/ReaderActivity.kt --own reader/ReaderJump.kt --own reader/DecorDiff.kt --own reader/PeekRule.kt
--own reader/ResumeState.kt --own reader/ReaderMenus.kt --own reader/ReaderJumpTest.kt --own reader/DecorDiffTest.kt
--own reader/PeekRuleTest.kt --own reader/ReaderMathTest.kt --own reader/ReaderRestoreTest.kt
Status note: docs/next/RCA_N_STATUS.md · result: docs/next/lanes/RCA-N.result.json

TASKS (PLAN §4 RC-A table rows "N", "R", and the A startOpen row; PLAN §1.6.1, §1.6.2, C8, C16, C17, C20, C22, C23;
N = docs/next/notes/NOTES_SPEC.md §6; A = docs/next/wave2/anchor.md §5.5–5.6; R = docs/next/wave2/recents.md §10)
- startOpen = PLAN §1.6.1 exactly (restored place > note jump > TXT fraction remap > DB row; anchor computed before
  publishing the session; s.setViewport(vw, vh, if (target != null) null else AnchorSpec(sec, anchor.offset));
  AnchorMath.pageFor for non-jump opens, pageForOffset for a note; the OWNER_JUMP mark only when JumpAnchor.matches;
  returnNav.onJump(saved) + peekUntilTurn + setIntent(ReaderJump.strip(intent)) in the SAME main-thread message as
  showPage (one e-ink update); toast "노트가 있던 곳을 찾지 못했습니다" when unusable). Nothing new before the first
  page except ReaderJump.from, the OWNER_JUMP put and AnchorSpec.
- afterOpen = PLAN §1.6.2's 8 steps in order, each IO step launched never awaited, plus a DEBUG
  `RAPerf afterOpen <ms>` line behind ReaderPerf.turns: ResumeState.opened (exists) · InstallState.ensure(this) ·
  light.afterFirstPage() · DeviceClass.probeAsync(appCtx) { QuoteLook.update(app.highlightLook, it);
  `// R3 merge(RCA-S): scroll?.onDeviceClass()`; one refresh if QuoteLook.generation changed } · the return-mark load
  (U §3.3: BookPrefs.returnMark(id) on IO, back on main guarded by book id + session → returnNav.restore(text)) ·
  if (settings.shows(EPISODE)) scheduleEpisodes() · the N §6.4 anchor check when a jump was consumed (capped at 48
  sections / 3 M chars, cancellable, JumpAnchor.find, toasts "노트 위치를 다시 찾았습니다" / "노트가 있던 곳을 찾지
  못해 가까운 위치를 열었습니다") · the note-place backfill on reloadAnnotations delivery (64 rows per post).
- Peek (N §6.2) through a pure PeekRule (tested): skip savePositionNow, scheduled save, writeTextPosition and the
  pause save while peekUntilTurn; cleared by the first manual turn, TTS start, auto-turn start, a remembered jump,
  using the return strip/chip, "여기서 읽기", and (scroll) the first user settle (`// R3 merge(RCA-S)` at the settle).
  rp.peek in onSaveInstanceState / restore (C8; no jump_done).
- onNewIntent: same book + jump → goTo(resolve…, remember = true) + mark + anchor check (N §6.3); another book →
  closeCurrentBook() then startOpen(intent).
- NotePlaceHost (frozen ReaderPanels capability): notePlace(pos) = chapter title (cleaned, ≤ 200), charProgress, the
  session NoteSig ("" for EPUB), NotePlace.UNKNOWN without a session. toggleBookmark adds the place (computed on main
  before the IO launch).
- Quotes on the page (N §6.5–6.6 + PLAN K2 refinement): Highlight(q.start, q.end, QUOTE, q.style); a QUOTE is drawn
  when its sig equals the session NoteSig, or (legacy '' / mismatched sig) when JumpAnchor.matches(section text,
  q.start, q.text) holds — checked lazily the first time the section's layout is used for decor, ≤ 24 visible chars,
  cached per (generation, section); the same result feeds the TOC "· 위치 바뀜" suffix (expose it to ContentsDialog
  through whatever ReaderPanels/NotePlaceHost contract exists; if none fits, record a contractRequest).
- DecorDiff.same(a, b) (pure, tested: highlights start/end/kind/style, bookmarked, statusVersion) used by sameDecor;
  removing OWNER_JUMP/OWNER_SEARCH also calls `// R3 merge(RCA-S): scroll?.onHighlightsChanged(section)`.
- applyAppSettings order (§1.6.2): light.onAppSettingsApplied() → QuoteLook.update(app.highlightLook,
  DeviceClass.cached(this)) + one refreshDecor() if the generation changed → `// R3 merge(RCA-S): applyReadMode()` →
  `// R3 merge(RCA-U): applyPageInsets()`; viewPart + a.highlightLook.
- onPause: `// R3 merge(RCA-S): scroll?.stopMotion()` → position save unless peekUntilTurn → ResumeState.paused() →
  light.onPause(). onDestroy: ResumeState rule (exists) → light.onDestroy(isFinishing) → `// R3 merge(RCA-S)` detach.
- ReaderMenus (C22 final order): 목차 … 페이지 이동 · (W2: 페이지 썸네일, later) · (narrow) 북마크 추가/삭제 [RCA-U]
  · 스크롤로 보기/페이지로 보기 [RCA-S] · 자동 넘김/자동 스크롤 · … 내 리뷰 · 독서 노트 (ic_format_quote →
  NotesActivity.open(this, NotesTab.ALL, book.id)) · …; existing items keep their places.
Accept: §1.6.1/§1.6.2 match line by line (list them in the status note); new tests green.
