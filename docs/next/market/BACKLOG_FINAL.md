# ReaderaPlus next-release backlog: final version after the performance and e-ink review

**Reviewer's verdict.**
- **Open path.** In the whole backlog, only one thing touches the open path: a single indexed DB row read (T1-9). The EPUB plan-cache lookup (A12-1) also runs there, but only when it replaces a scan that costs much more.
- **Page turns.** The added per-turn work is O(1): a few integer operations and one string. Nothing new redraws the reading page while it sits idle.
- **Problems found.**
  - Four contradictions in the spec:
    - A5's cache bump sits in the hotfix wave.
    - A12's measurement lands after the work it is supposed to gate.
    - T1-3c turns on an app flash by default, against decision 3.
    - T1-10's device risk is already handled in the code.
  - Six specs that can't be built as written with the platform APIs, plus one typo in a user-visible string.
  - Three cuts and five missing items.
- **Where changes are marked.** Every change is marked **[Δ]** inline and listed in the changelog at the end.

All code paths are relative to `/home/user/readeraplus/app/src/main/java/com/ggumtak/readeraplus/` unless they start with `/`.

**Confidence tags**
- **[V]**: checked in our code at HEAD `7663f97`, by the product lead or in this review.
- **[R]**: taken from one of the research reports.
- **[I]**: has to be confirmed on the Comet itself.
- **[Δ]**: changed in the performance and e-ink review.

## 0. How I ranked, and decisions already made

**Ranking rules**
1. **The Comet comes first.** Anything that adds e-ink updates, startup work, per-page cost, background polling or animation is rejected or redesigned.
2. **Fast opening is sacred.**
   - Nothing new may run between `ReaderActivity.startOpen` and `showPage`. There are two exceptions:
     - a single DB or prefs read folded into the IO block that already exists (`ReaderActivity.kt:505-511` [V]);
     - **[Δ]** a cache read that replaces more expensive work already done on that path, and only when that work would actually run (A12-1).
   - Counting, annotations, statistics, speed data and episode parsing all stay after the first page (`afterOpen`).
3. **Value per effort.** The Korean web-novel TXT workflow comes before EPUB polish, which comes before general features.
4. **Platform APIs only.** No AndroidX and no libraries.
   - The only new permissions are `INTERNET`, for Wi-Fi transfer while its screen is open, and `FOREGROUND_SERVICE(_MEDIA_PLAYBACK)`, for TTS.
5. **[Δ] Every flash the app starts on its own is opt-in.** This makes decision 3 below concrete. A new refresh trigger ships switched off unless the user asked for that refresh (the 새로고침 key or action, or the refresh test).
6. **[Δ] At most one `TxtIndexStore.VERSION` bump per release.** Each bump makes every large TXT open slowly once.

**Decisions not to reopen**
- **No "~" on page numbers.** The user removed it in `d61f4ab`. The fix for jumping totals (A2) is better estimates and persisted counts.
- **Instant taps stay.** There is no tap debounce, and fresh key presses are never throttled (`d61f4ab`). Only auto-repeat may be paced.
- **The device keeps its own waveform.** The default e-ink mode stays "system" and the app's refresh cadence stays 0.

**Release shape [Δ]**
- **Wave 0 (hotfix build, 1–2 days, no contract change, no cache-version bump):**
  - A1;
  - A3 (the unfrozen strings only);
  - A4 (everything except the `Ui.kt` and `values-v31` parts);
  - A6;
  - A11;
  - A12-3 (auto-scan);
  - A12-5 (open and turn timing logs). **Record the Comet baseline from this build before any Wave 1 code lands.**
- **Wave 1:**
  - Land contract revision R2 (section D) as one commit.
  - Then build the tier-1 items in parallel by owner.
  - A2 and A5 ship here. A5 carries this release's only `TxtIndexStore.VERSION` bump.
- **Wave 2:**
  - A8, A9, A12-1/2/4, A13;
  - the T1-6 statistics screen;
  - the rest of T1.
- **Tier 2** comes later, starting with T2-10.

---

## A. Must-fix: bugs and rough edges from the audit, with fix specs

### A1 (H) Long-pressing where there is no text still starts a selection. This is the user's reported bug.

**What happens today [V]**
- `onLongPress` (`reader/ReaderActivity.kt:1561-1569`) calls `hitTest`, then `fingerOnChar(off, x, y, LONG_PRESS_SLOP_DP = 6)`.
- `fingerOnChar` tests against `LineGeometry.rangeRects`, which returns the **full line box**, `ln.top` to `ln.bottom` (`engine/Typesetter.kt:96-128`).
- The line box follows CSS half-leading (`TypesetPass.setBox`: `h = lineHeightEm·em·scale`, baseline at `(h − natural)/2 + asc`). At the default 200% line height, about half of every line box is white leading, so a press in the gap between lines still selects.
- **The second path has no check.**
  - While a selection is showing, `SelectionController.reselect` (`reader/extras/SelectionController.kt:85-90`, posted at `:153`) calls `startAt()` with no glyph check.
  - It also uses `ViewConfiguration.getLongPressTimeout()` instead of `PageView.LONG_PRESS_MS` (500, `PageView.kt:337`).
- `7663f97` was committed with `[skip ci]`, so no APK the user has contains even the partial fix.
- `reader/extras/InfoDialogs.kt:96` and `ui/settings/AboutPage.kt:99` use `setTextIsSelectable(true)` on full-width TextViews.

**Fix**
1. **Add a pure function to `LineGeometry`** (`engine/Typesetter.kt`, owned by ENGINE, not frozen):
   ```kotlin
   /** Offset of a visible, non-space char whose glyph box contains (x, y) ± slop (content-box px); -1 otherwise. No snapping. */
   fun glyphAt(layout: SectionLayout, page: PageInfo, x: Float, y: Float, slop: Float): Int
   /** Glyph band of a text line in content-box px. */
   fun bandTop(layout: SectionLayout, ln: LineInfo): Float
   fun bandBottom(layout: SectionLayout, ln: LineInfo): Float
   ```
   - **Line:** only the line with `ln.top <= y < ln.bottom`. There is no nearest-line fallback. Image, rule and empty lines return -1.
   - **Band:**
     - `e = (ln.bottom - ln.top) / max(1f, layout.config.lineHeightEm)`. This is em × heading scale. `LineInfo` is frozen and does not store ascent, so em is derived from the line height.
     - `bandTop = ln.baseline - 0.95f*e` and `bandBottom = ln.baseline + 0.30f*e`.
     - If y is outside `[bandTop - slop, bandBottom + slop]`, return -1.
   - **Horizontal:** walk the line with the existing `walk`.
     - A candidate is a char i with `adv[i] > 0`, where `text[i]` is not whitespace (including U+3000) and not `OBJECT_CHAR`, and x lies in `[xi - slop, next + slop]`.
     - Return the candidate closest to x.
2. **`ReaderActivity`:**
   - Add a private `glyphAtView(x, y, slopPx)` that applies the `frame.left` / `frame.top` offset, like `hitTest` at `:1477-1485`.
   - In `onLongPress`, replace `hitTest` + `fingerOnChar` with `glyphAtView(x, y, dp(4)) >= 0`.
   - `fingerOnChar` stays for link taps.
3. **`SelectionController`:**
   - Add `var canSelectAt: ((Float, Float) -> Boolean)? = null`. `afterOpen()` sets it right after the controller is created.
   - In `reselect`: if `canSelectAt?.invoke(downX, downY) == false`, **[Δ]** set `tapCandidate = false` and return. Without that, the finger's `ACTION_UP` would reach the "tap outside clears" branch and drop the selection the spec says should stay.
   - Replace `getLongPressTimeout()` with `PageView.LONG_PRESS_MS`. After R2 this becomes `AppSettings.longPressMs`.
4. **Keep the release behaviour.** Lifting after a rejected long-press stays a no-op (`longPressFired`), so the page does not turn.
5. **Highlights and handles:**
   - `render/PageRenderer.drawHighlights` (`:230-291`) fills QUOTE, SELECTION, SEARCH and TTS highlights from `top + bandTop` to `top + bandBottom`.
   - Handles are anchored at `bandBottom` (`SelectionController.kt:282-283`).
   - **[Δ]** `HandleDrag` lifts its hit point by `(first.bottom - first.top)/2` today, meaning the middle of the line box. Change the lift to `(bandBottom - bandTop)/2`. Otherwise, after the anchor moves, dragging a handle lands on the line above.
6. **Dialogs:**
   - `InfoDialogs.kt:96`: `setTextIsSelectable(false)` and a `WRAP_CONTENT` label. A long-press on the label copies its value and shows "복사했습니다".
   - `AboutPage.kt:99`: set it to `false`.
7. **Ship it.** The next commit runs CI, with no `[skip ci]`.
8. **Settings (after R2):** `AppSettings.longPressMs` (default 500) and a row in 페이지 넘김: "길게 누르기 시간", with the choices 0.4초 / 0.5초 (기본) / 0.7초 / 1.0초.

**Performance [Δ]:** `glyphAt` walks one line, once per long-press. Band highlights add two multiplies per highlighted line. There is no per-turn cost.

**Tests** (JVM, `app/src/test/java/com/ggumtak/readeraplus/engine/`), with 3 paragraphs at `lineHeightEm = 2.0` and the fake measurer:
- These must return -1: the left margin, the blank tail of a short line, `y = ln.top + 0.05·h`, the paragraph gap, below the last line, a space, an image line.
- These must return the offset: the glyph centre, the glyph edge ± slop, a heading line with a 1.4 scale.

**Effort:** S. **Risk:** font ascents differ. Check on the device [I] with 나눔명조, 리디바탕 and one user font at 200%.

---

### A2 (H) Page totals change visibly while you read

**Evidence:** `30_big_txt.png` shows 43828 and `31_big_txt_later.png` shows 32719 [R].

**Causes [V]**
- Counts are saved only once complete (`reader/BookSession.kt:432-468`, `PageCounts.toArray()` returns null until complete).
- The key includes `BuildConfig.VERSION_CODE` (`BookSession.kt:520`), so every update discards every cached count.
- The estimate is driven by whichever sections were counted first.

**Fix**
1. **Algorithm version, not app version.**
   - `LayoutKeys.ALGO_VERSION = 1` replaces `VERSION_CODE` in `computeKey`.
   - Rule, written in `docs/ARCHITECTURE.md`: bump it only when the output of `TypesetPass` or the measurer can change.
   - **[Δ] Enforce the rule with a golden test.**
     - `LayoutGoldenTest` lays out a fixed original corpus with the fake measurer: Korean and Latin text, headings, an image, justification and CHAR/WORD breaking.
     - It hashes every `LineInfo` field and compares the hash with `LayoutKeys.GOLDEN_HASH`.
     - On failure it prints "layout output changed: bump ALGO_VERSION and update GOLDEN_HASH".
     - Measurer changes (`FontManager`, stroke) are not covered, so note them in the rule.
2. **Save partial counts.**
   - `toArray()` returns a partial array, with -1 for unknown sections. The BLOB is unchanged.
   - Save every 25 counted sections, and on `BookSession.close()` through `ReaderIo.launch`. Copy the array on the main thread first.
   - `PageCounts.setKnown(arr): Boolean` sets only entries `>= 0` and returns `isComplete`. It rejects a length mismatch. `setAll` keeps rejecting `<= 0` [V].
   - Every save is one small BLOB write, about 6 KB for 1,565 sections.
3. **Counting order.**
   - The foreground section first, then 3 samples at 25%, 50% and 75% of the range, then 0..n-1 skipping known sections.
   - **[Δ] For EPUB, sample only single-part spine items** (`parts[item] == 1`). Loading one part of a split item converts the whole item, and random sampling would evict the `SplitItem` cache that sequential counting reuses.
4. **Better estimate.** `pagesPerChar()` ignores known sections under 2,000 chars when at least one larger section is known.
5. **No change to the display.** There is no "~", and the footer updates only on turns (`ReaderActivity.kt:656-669` [V]).

**Owners:** `reader/BookSession.kt`, `PageCounts.kt`, `LayoutKeys.kt`, `data/Library.kt`, tests in `reader/` and `engine/`.

**Effort:** M.

**Risk:**
- A stale partial array: `setKnown` rejects a length mismatch, and the key has the file size, mtime and settings.
- **[Δ]** Every cached count is recounted once when the key format changes. That happens in the background and is off the open path.

---

### A3 (H for any public release) Competitor names in text the user sees

| Where | Now | New |
|---|---|---|
| `ui/library/LibraryDialogs.kt:369`, `ui/settings/AboutPage.kt:41` | "ReadEra의 흐름을 참고해 새로 만든 개인용 전자책 리더입니다" | "e-ink 전자책 리더기를 위해 만든 가볍고 빠른 TXT·EPUB 리더입니다." |
| `ui/settings/PageTurningPage.kt:124` | "ReadEra처럼 잔상이 적게 하려면 … ReadEra와 같은 모드로 지정하세요." | "잔상이 거슬리면 기기의 e-ink 설정(앱별 최적화)에서 이 앱의 새로고침 모드를 바꿔 보세요. 아래 'e-ink 모드'를 고르면 그 값이 우선합니다." |
| `settings/ReaderSettings.kt:82-84` (frozen, goes in R2) | 마루뷰어풍 / 리디풍 / 종이책풍 | **웹소설** / **전자책** / **종이책**. The enum names are unchanged. |
| `ui/settings/FontsPage.kt:128` | "기본 글꼴(리디바탕)" | "기본 글꼴(나눔명조)" (A8) |

- **Product note:** the preset names came from the user. The new labels keep the exact look. **The user decides.** If they object, keep the old names in personal builds only.
- **Effort:** XS.

---

### A4 (M, specific to the Comet) Calm screen: remove needless e-ink updates

1. **Blinking cursors.** A new `ui/kit/InkInput.kt` provides `EditText.inkCursor(singleLine)`:
   - A single-line field always sets `isCursorVisible = false`.
   - A multi-line field starts hidden, shows the cursor on the first `ACTION_DOWN` inside it, and hides it on focus loss.
   - **[Δ]** Clearing focus when the IME hides uses `WindowInsets.isVisible(ime())`, which is **API 30+**; minSdk is 26. Guard it with `SDK_INT >= 30`. Below 30, rely on focus loss only.
   - Apply it at:
     - `SearchPanel.kt:177`;
     - `LibraryActivity.kt:478`;
     - `LibraryDialogs.kt:141`;
     - `InfoDialogs.kt:142`, `:215`;
     - `ExtrasUi.kt:257`;
     - `Ui.prompt()` (R2).
2. **Throttle continuous labels to 4 Hz.**
   - Add a pure `Throttle(250)` to `reader/ReaderMath.kt`, with a test.
   - Brightness: set the window value on every event, and the overlay text at most every 250 ms, plus on `done`.
   - The chrome seek preview is throttled the same way.
3. **`Ui.sliderRow`** (`Ui.kt:331-347`, R2) gets the plain-dot thumb from `ReaderChrome.einkSeekBar` (`:193-205`) [V: today it keeps the animated platform thumb].
4. **`ScanPage.kt:305`** uses `toast()`.
   - [V] `Ui.toast()` already routes to the in-app `InkMessage` (`Ui.kt:64-78`). It falls back to the system Toast, which fades in and out, only when the window has no focus.
   - `ScanPage` is the one place that calls `Toast.makeText` directly.
5. **Splash screen (API 31+, R2):**
   - `res/values-v31/themes.xml` sets, for `AppTheme` and `ReaderTheme`:
     - `windowSplashScreenAnimatedIcon=@android:color/transparent`
     - `windowSplashScreenBackground=@android:color/white`
     - `windowSplashScreenIconBackgroundColor=@android:color/white`
   - `LibraryActivity.onCreate` and `ReaderActivity.onCreate` call `splashScreen.setOnExitAnimationListener { it.remove() }`.
   - **[Δ] Where it applies:** only process or task starts show a splash: the launcher, or a file manager opening a book. Opening a book from the library never does. [I] Also check whether `ReaderTheme`'s `windowDisablePreview` already suppresses it on this firmware.
6. **Search:** flush every 500 ms (`SearchPanel.kt:314`). The status text reads "검색 중 34%".

**Acceptance:** `adb shell dumpsys gfxinfo com.ggumtak.readeraplus reset`, then idle 60 s, then `… | grep "Total frames"` must read 0. Run it on the reading page, in the library and with the TOC open. Frames per page turn are checked in E.

**Effort:** S. **Risk:** none.

---

### A5 (M) "작가의 말" turns into table-of-contents entries and page breaks. **[Δ] Moved to Wave 1.**

**Why [V]:** `format/txt/TxtChapters.kt:108` has `selMask = chosen or R_K3`, so every K3 "special" is a heading. `prune` removes only headings with no body text between them.

**Fix, in `TxtChapters.detect` after `chooseRule`**
- When `chosen != R_K3`, classify the K3 candidates by normalised title prefix.
- NOTE = {작가의 말, 작가 후기, 후기, 완결 후기}.
- If there are 3 or more NOTE candidates, drop every NOTE candidate from `sel`, except those after the last candidate of the chosen rule.
- 프롤로그, 에필로그, 서장, 종장, 서문, 외전, 번외, 후일담 and 막간 always stay.

**Cache version [Δ]**
- A5 changes the parser's output, so it must bump `TxtIndexStore.VERSION` (3 → 4). **This is the release's only bump.**
- T1-10 needs no parser change (see T1-10). That is why A5 moved out of the hotfix: a hotfix shouldn't make the user's 14 MB book open slowly.
- The first parse measured about 120–165 ms on a desktop JVM (scratch perf logs). Expect roughly 1–1.5 s on the A53 [I].
- **Cut:** the "rebuild the most recent TXT index in the background from the library" mitigation. It competes with the user's likeliest next action, opening that same book. Without single-flight coordination it would parse the file twice.
- **Instead:** when the TXT index misses on a file over 4 MB, the delayed loading text reads "목차를 만드는 중…" instead of "불러오는 중…". It shows only after the existing 300 ms delay.

**Tests:**
- Five times "N화 / 본문 / 작가의 말 / 짧은 글" gives 5 TOC entries.
- Adding "에필로그" and "완결 후기" at the end gives 7.

**Owner:** `format/txt/`. **Effort:** S.

---

### A6 (M) The footer shows two bare percentages, "0% · 11:45 · 100%"

- `PageDecor` gets `battery: Int = -1`, and `ReaderFormat.footerRight` drops the battery (`ReaderFormat.kt:35-41` [V]).
- `PageRenderer` draws a battery outline at the far right, using preallocated Paint and RectF objects:
  - outline 0.9·ts × 0.5·ts, 1 px stroke;
  - nub 0.08·ts × 0.25·ts;
  - a fill proportional to the level;
  - a 0.25·ts gap, then the digits with no "%".
- The level is still read at most once a minute, on turns only (`ReaderActivity.battery()` [V]).
- **Owners:** `render/`, `reader/ReaderFormat.kt`, `buildDecor`. **Effort:** S. **Risk:** none.

### A7 (M) Finishing a book never marks it as read

- **Fixed by T1-2.**
- If T1-2 slips, "next" on the last page calls `Library.setHaveRead(id, true)`. It shows the ink message "완독으로 표시했습니다 · 책 메뉴에서 되돌릴 수 있습니다".
- **[Δ]** The 되돌리기 action is dropped: `InkMessage` has no action button [V] (`Ui.kt:109-147`). Undo lives in T1-2's toggle.
- **Effort:** XS.

### A8 (M) Terminology, value ranges and duplicated screens

The glossary and the exact changes are unchanged:
- **Glossary:** 책, 페이지 / 쪽, 글꼴, e-ink, 음높이.
- **Shelf labels:** in `data/Models.kt` (R2), with the hard-coded copies replaced by `Shelf.X.label`.
- **Other string and range fixes:** 책 instead of 문서; one "책 정보 편집"; the wording fixes; "넘김·화면 설정" and "설정"; the same ranges and labels in both places; the CP949 label.
- **Default font:** `FontsPage` treats `ReaderSettings().fontId` as 기본값, and `FontCatalog.DEFAULT_ID = "nanummyeongjo"` (R2).
  - **[Δ] Side effects of the default-font change** [V]:
    - `DEFAULT_ID` is also the missing-font fallback (`FontManager.kt:117`, `:215`) and the font of generated covers (`Covers.kt:195`, `:244`).
    - Benefit: the library covers and the default reading font then share one inflated CJK `Typeface` instead of two.
    - Covers already generated keep the old font until they are regenerated [I: check whether covers are cached on disk].
- **Duplicated screens:** "정보" goes to `PAGE_ABOUT`; one collection-dialog behaviour.
- **Effort:** S, plus R2.

### A9 (M) The reading-settings popup leaves a strip of clipped text

`PopupGeometry.width` becomes `min(screenW - dp(8), dp(420))`. The height is still at most 55%. **Effort:** XS.

### A10 (L) The shared stepper saves even at its limit

In `stepperRow` (`Ui.kt:318-322`, R2), return when the new value equals `v`. **Effort:** XS.

### A11 (L) Raw exception text reaches users, and the error panel only offers 닫기

- **Friendly messages:** `describe()` maps each error to a message:

  | Error | Message |
  |---|---|
  | `FileNotFoundException` | "파일을 찾을 수 없습니다" |
  | `ZipException` | "EPUB 파일이 손상되었습니다" |
  | `IOException` | "파일을 읽지 못했습니다" |
  | anything else | "책을 열지 못했습니다" |

- The class name moves to a small grey line underneath ("자세히: ZipException").
- **`userMessage(t)`** also recognises ENOSPC as "저장 공간이 부족합니다". Route these call sites through it:
  - `LibraryJobs.kt:73`, `:106`;
  - `BackupPage.kt:98`, `:133`;
  - `LibraryActivity.kt:924`, `:1096`, `:1297`;
  - `LibraryDialogs.kt:110`, `:135`;
  - `FontChooser.kt:191`.
- **Error panel** (`ReaderActivity.kt:356-367`):
  - [다시 시도] re-runs `startOpen(intent)`.
  - For TXT only, [인코딩 선택] opens a chooser, then `Library.setEncoding`, then retries.
  - [닫기].
- **Effort:** S.

### A12 (M) Performance safety for the open path. **[Δ] Split across waves.**

1. **EPUB section-plan cache (Wave 2).**
   - Background [V]: `planSections` (`format/epub/EpubBook.kt:134-160`) reads and scans only spine items larger than `EpubSplit.SCAN_MIN_BYTES` (192 KB, `EpubSplit.kt:25`), but it does so on every open.
   - New `format/epub/EpubPlanCache.kt`:
     - Key: `"v${EpubPlanCache.VERSION}|path|size|mtime"`.
     - Value, per spine item: `parts[i]`, `itemChars[i]` and `fragParts[i]`, written with `DataOutputStream` to `cacheDir/epubplan/<fnv64>.bin`.
     - Keep the newest 200 files.
   - **[Δ] Look the cache up only when at least one spine item is larger than `SCAN_MIN_BYTES`.** A small EPUB must not pay for a cache-miss file open.
   - **[Δ] Write the cache after the first page** (`ReaderIo.launch`), not on the thread that is opening the book.
   - **[Δ]** `EpubPlanCache.VERSION` must be bumped whenever `EpubSplit.partsFor`, `scan` or `assign` change. A golden test (fixed synthetic XHTML → expected parts and anchors) guards this, as in A2.
2. **User-font catalogue off the main thread (Wave 2).** In the `startOpen` IO block, call `FontManager.font(settings.fontId)` for `user:` ids.
3. **Auto-scan must not compete with an open (Wave 0).**
   - The 30-minute auto-scan starts only after the library has been idle 3 s (instead of 1.5 s after it shows).
   - It pauses while `readerInFront` (a `@Volatile` flag set in `ReaderActivity.onResume` / `onPause`), checked between directories.
   - **[Δ]** The scan thread runs at `THREAD_PRIORITY_BACKGROUND`.
4. **Quotes (Wave 2) [Δ].** The spec described a change that already exists [V]:
   - `afterOpen` already creates `SelectionController` (`ReaderActivity.kt:580-586`).
   - `reloadAnnotations` already loads the quotes.
   - The actual fix: `reloadAnnotations` fills `QuoteCache` with the rows it just loaded. That makes one query instead of two, and the first long-press on a quote recognises it.
5. **Instrumentation (Wave 0) [Δ].**
   - Debug-only logs:
     - `open <id>: first page N ms`, from the start of `startOpen` to the end of `showPage`'s draw;
     - `turn N ms`, from the input event time to the end of `PageView.onDraw`.
   - **Capture the baseline on the Comet from the Wave 0 build.** The Wave 1 acceptance gates compare against it.

**Effort:** M for the EPUB cache, S for the rest.

### A13 (L) Small polish

- **Bookmark empty-state text** mentions the corner tap only if `bookmarkByTouch` is on.
- **Page label:** underlined, with `contentDescription = "페이지 이동"`.
- **Library cards:** a book never opened shows "새 책".
- **TTS voice choice:**
  - `TtsPage` gets a "목소리" row. Its `TextToSpeech` instance exists only while the page is open.
  - ko-KR voices come first, with readable names.
  - The reader's chooser creates the engine when asked, instead of saying "먼저 재생하세요" (`TtsController.kt:750-753`).

---

## B. New features

### Tier 1 (do now; 13 items, in priority order)

#### T1-1 목차 2.0: page-at-a-time list, jump to an episode number, read episodes marked, missing-episode check

**User value:** a web novel with 1,000+ episodes becomes one tap to "137화". There is no fling smear, and the reader can see what is missing from the file [R].

**Behaviour** (TOC tab of `reader/extras/ContentsDialog.kt`)
- **Header (36dp).**
  - Left side: "540화 · 지금 123화", or "목차 612개 · 지금 87번째".
  - Right side, three text buttons:
    - **[지금]** scrolls so the current entry is the 4th row.
    - **[화 번호]** — **[Δ]** opens `ui/kit/InkNumPad.kt` (below), not a system-keyboard prompt. The jump uses `remember = true`. If the number is missing, it goes to the next higher number and says "57화가 없어 58화로 이동했습니다".
    - **[검색]** opens a text prompt, using the system IME because Hangul needs it. The list is filtered by titles containing the query, ignoring case and whitespace. The header then reads "'외전' 12개 · [전체 보기]".
- **Second header line**, only when detected with confidence: "빠진 화 3개 · 중복 1개 ›". Tapping it opens a dialog "빠진 화: 57, 120, 121 / 중복: 88화 (2번)" whose numbers are tappable.
- **Rows:** entries before the current one are drawn in `Ink.GRAY`. The current entry is bold with ▶.
- **Pager bar (44dp):** [◀ 이전] "3 / 27" [다음 ▶].
  - Volume keys and learned page keys page the list (`setOnKeyListener`).
  - The same pager goes on the 북마크 and 인용문 tabs and the `SearchPanel` results.
- **Go-to dialog** (`InfoDialogs.kt` 페이지 이동):
  - Segments [페이지] [%] [화]. [화] is enabled when at least 50% of titles parse and there are at least 2 entries.
  - **[Δ]** Number entry uses `InkNumPad` in all three segments.

**Pure helpers (unit-tested)**
- **`reader/extras/Episodes.kt`, `EpisodeNumbers.parse(title)`:** rules are tried in order:
  1. `(?:제\s*)?(\d{1,5})\s*(?:화|회|話)`
  2. `(?i)(?:ep|episode|chapter|ch|#)\s*\.?\s*(\d{1,5})`
  3. `(\d{1,5})\s*(?:장|편|章)`
  4. `^\s*[\[<(【〈《]?\s*(\d{1,5})(?!\d)`
- **`Episodes.of(titles)`** gives `numbers`, `find(n)`, `gaps()` and `dupes()`.
  - Titles with 외전, 번외, 특별, 후기 or 공지 are excluded from gap and duplicate checks.
  - Gaps and duplicates are shown only when at least 70% of entries parse and `max - min <= 5 × count`.
- **[Δ] One parse per session.**
  - `BookSession.episodes` is lazy: an `Episodes?` computed once on `Dispatchers.Default` by whichever needs it first (the TOC or T1-5's footer), then reused.
  - Estimate: about 20 ms for 2,000 titles [I].
  - Never on the open path.
- **[Δ] `ui/kit/InkNumPad.kt`** (new, not frozen):
  - A dialog with a large number label, a 3×4 grid of 56dp buttons (1–9, ⌫, 0, 이동) and an optional hint line ("1–540").
  - Pure state is in `NumPadState` (digits, max length, clamp), unit-tested.
  - Why: on the Comet, the system IME slides in with an animation and resizes the dialog window, which costs several full-screen e-ink updates. The pad costs one small update per digit, and the go-to dialog never moves.
- **`ui/kit/InkPager.kt`, `ListView.inkPaging(bar)` [Δ redesigned]:**
  - A page is `visibleCount - 1` rows, moved with `setSelection(first ± page)`.
  - **A vertical drag or fling beyond touch slop is consumed.** On `ACTION_UP` it becomes exactly one page jump, so there are no scrolling frames at all. Taps still reach the rows.
  - The indicator updates right after each `setSelection`. **[Δ]** `setSelection` does not raise `onScrollStateChanged(IDLE)`, so the original "update on IDLE" spec would never fire.
  - The `setFriction` / `setVelocityScale` taming is no longer needed.

**Performance:** nothing at open or on turns. The paged lists produce fewer frames than today's scrolling.

**Owners:** `ContentsDialog.kt`, `InfoDialogs.kt`, `SearchPanel.kt`, new `Episodes.kt`, `ui/kit/InkPager.kt`, `ui/kit/InkNumPad.kt`, and `BookSession.episodes` (READER).

**Effort:** M. **Risk:** odd TOCs yield garbage numbers, and the confidence rule hides the gaps line.

---

#### T1-2 끝 화면: continue with the next part, and mark the book finished

**User value:** Korean 텍본 are split into parts, so continuing into the next file matters [R]. This also fixes A7.

**Behaviour**
- **Trigger:** "next" on the last page, from a tap, key, swipe, auto turn or the TTS end. It replaces "마지막 페이지입니다"; "첫 페이지입니다" stays.
- **Panel:** new `reader/EndPanel.kt`, a white overlay with 1px borders and no animation (one e-ink update). Contents:
  ```
  다 읽었습니다
  <제목>
  읽은 시간 4시간 12분                          (T1-6)
  [ 다음 권 읽기 › ]  소설A 101-200화.txt        (only if found)
  [ 완독 처리됨 ]  (toggle)
  [ 서재로 ]  [ 처음부터 ]  [ 리뷰 쓰기 ]
  ```
  - [리뷰 쓰기] opens the existing review editor.
  - Back, "previous" or a tap outside the buttons closes the panel.
  - **[Δ]** Key events with `repeatCount > 0` are swallowed while the panel shows, so a held "next" key neither closes it nor presses a button.
- **Auto-mark:**
  - `AppSettings.autoMarkFinished` (default true), labelled "끝까지 읽으면 완독 처리".
  - It calls `Library.setHaveRead(true)` and saves progress as 1.0.
  - **[Δ]** It stores `finished_at` in the new `book_prefs` DB table (T1-9).
- **Finding the next file:** `data/NextPart.kt`, pure apart from one `listFiles`.
  - For an EPUB with `series`, take the next `seriesIndex` first.
  - Otherwise list the parent folder, on IO and only when the panel shows.
  - Keep the same format family, sort with `NaturalOrder`, and take the first file after the current one with an equal `seriesKey`. `seriesKey` and its tests are unchanged.
- **Opening it:** `Library.addOrUpdateFile(next)` on IO, then open by id through `onNewIntent` / `closeCurrentBook`.

**Performance:** zero until the panel shows, then one directory listing on IO.

**Owners:** `ReaderActivity.kt`, `EndPanel.kt`, `NextPart.kt`, R2. **Effort:** S–M. **Risk:** a wrong guess in mixed folders. The file name is always shown.

---

#### T1-3 e-ink 새로고침 2.0

**User value:** ghost clearing that reliably works on the Comet.
- Today `Eink.vendorRefresh` counts "no exception" from `forceGlobalRefresh(4)` as success (`render/Eink.kt:166-176` [V]).
- If that call is a no-op on this firmware, the app never refreshes and never falls back to the flash.

**Behaviour** (e-ink section of `PageTurningPage.kt`)

a) **"새로고침 방식"**, under a **[Δ] "고급" group:**
   - Options: 자동 / 기기 GC16 / 기기 잔상 제거 (CLEAN) / 검은 화면 깜빡임. The device options appear only when the xrz hook is found.
   - "깜빡임 길이": 100 / 200 / 350 ms.
   - **[테스트]:** 1 s of 16px black and white stripes, then a sample text, then the chosen refresh, then "잔상이 깨끗이 지워졌나요?" with [예, 이 방식으로] / [다른 방식 시험].
   - Stored in `AppSettings.einkRefreshMethod` (0–3) and `einkFlashMs`. `Eink.fullRefresh` reads a static set in `applyAppSettings`, and there is no chain.
   - The result on the Comet sets the "자동" order.
   - **[Δ]** The same group shows the diagnostics readout moved here from T2-20: the vendor API found, the device auto-clean state (e) and the density.

b) **"밤 모드(반전)에서":** 낮과 같게 / 3 / 5 / 10 / 20쪽마다.
   - Stored in `einkRefreshEveryNight` (-1 means the same as day).
   - `cadence.every` is chosen from `s.invert` in `applyAppSettings`, `applySettings` and `toggleInvert`.

c) **"그림 있는 쪽에서 새로고침"** (`einkFlashImages`) — **[Δ] default off** (rule 5).
   - Pure `ImageCoverage.of(layout, pageIndex)`: image area divided by content area, O(lines on the page).
   - `imageDue = cov >= 0.075f || abs(cov - prevCov) >= 0.075f`, passed as `cadence.onTurn(chapterChanged, imageDue)`.
   - At most one refresh per turn, and the rapid-flip hold still applies.

d) **[Δ] Simplified:** closing the TOC, search, the settings popup or the chrome calls `EinkCadence.onPanelClosed()`, which counts one turn. There is no setting, and it only matters when a cadence is on. The "바로 새로고침" option and `einkRefreshOnPanelClose` are removed.

e) **Device ghost-clear readout:**
   - `Eink.deviceCleanInfo()` reads `XrzEinkManagerInternal.isAutoCleanCheckEnable()` and `getCleanFrequency()` by reflection, once, on IO. It never writes them.
   - It shows "기기 자체 잔상 제거: 켜짐 · 10쪽마다".
   - If both the device and an app cadence are on, it warns "기기와 앱이 모두 잔상을 지우면 두 번 깜빡입니다. 한쪽만 켜세요."

f) **Night-mode images:** a cached inverting `ColorMatrixColorFilter` on the bitmap paint when invert is on.

**Performance:** the work runs only on turns that are due. Coverage is O(lines on the page). There are no timers.

**Owners:** `render/Eink.kt`, `PageRenderer.kt`, `ReaderMath.kt` (`EinkCadence`), `ReaderActivity.kt`, `PageTurningPage.kt`, R2.

**Effort:** S–M. **Risk [I]:** what codes 4 and 176 actually do on the Comet. The test flow settles it.

---

#### T1-4 Assign actions to keys, key-hold actions, and paced repeat

**User value:** the Comet has few keys [R]. Holding a volume key overshoots today, because repeats are accepted every 150 ms (`KeyMap.kt` `RepeatFilter.NORMAL_MS` [V]).

**Behaviour**
- **Key learning:** after capture, the chooser "이 키로 할 동작" offers 다음 페이지 / 이전 페이지 / 다음 화 / 이전 화 / 목차 / 메뉴 / 북마크 / 화면 새로고침 / 흑백 반전 / TTS 읽기 / 자동 넘김 / 페이지 이동 / 없음(시스템에 맡김). Assigned keys are listed with [삭제].
- **Resolution:** `keyBindings`, then the legacy `nextPageKeys` / `prevPageKeys`, then the built-in `KeyMap`.
  - A binding on a volume key overrides `volumeKeysTurn`.
  - Unbound volume keys keep controlling the volume while TTS speaks.
- **"키를 길게 누르면"** (`keyHold`): 계속 넘기기 (default) / 다음·이전 화로 / 10쪽씩 / 한 쪽만. **[Δ] The hold action is anchored at the key-down position:**
  - The page turn on `ACTION_DOWN` stays instant (decision 2).
  - The reader remembers the position from before that turn.
  - At `repeatCount == 1`, the hold action runs relative to that position, then swallows repeats until `ACTION_UP`:
    - **CHAPTER:** the next or previous chapter start, as `jumpChapter` would compute from the remembered position. If the instant turn already crossed into that chapter, it stays put. Without the anchor, holding on a chapter's last page would skip a whole episode.
    - **TEN:** the remembered page ± 10.
    - **SINGLE:** nothing more.
- **Pacing:** in "계속 넘기기", repeats are accepted every 250 ms in the system, HD, REGAL and NORMAL modes, and every 150 ms in FAST. Fresh presses are never throttled. Learned keys keep their 300 ms filter.
- **New tap actions:** `GOTO("페이지 이동")` and `AUTO_TURN("자동 넘김")`.

**Performance:** one map lookup per key event.

**Owners:** `reader/KeyMap.kt`, `ReaderActivity.kt` (`dispatchKeyEvent` ≈1680, `runTapAction`), `PageTurningPage.kt`, `KeyNames.kt`, R2.

**Effort:** S–M. **Risk [I]:** the Bigme custom key may never reach apps [R device §3]. Check with `getevent -lt`.

---

#### T1-5 Episode buttons and an episode counter in the footer

- **Chrome seek row** (`ReaderChrome.kt:170-188`): [이전 화] [seek bar] [다음 화], with 48dp buttons.
  - **[Δ]** They use `remember = false`, as `jumpChapter` does today (`ReaderActivity.kt:1111-1128` [V]).
  - Moving episode by episode is sequential navigation. A 돌아가기 chip after every hop would be noise and an extra e-ink element.
- **Footer "회차"** (`footerEpisode`, default off):
  - It shows "123/540화", or "87/612", after the page label.
  - It uses `BookSession.episodes` from T1-1, computed after open, and is left out until that is ready.
- **Performance:** one string per turn.
- **Owners:** `ReaderChrome.kt`, `ReaderActivity.kt` (`buildDecor`), `ReaderFormat.kt`, `ReadingSettingsPopup.kt`, `PageTurningPage.kt`, R2.
- **Effort:** S.

---

#### T1-6 읽기 기록: accurate reading time, then statistics. **[Δ] The tracker ships in Wave 1; the screen in Wave 2.**

**User value:** statistics are marketed everywhere [R]. The fix also stops counting idle screen-on time as reading (`flushReadingTime`, `ReaderActivity.kt:2073-2079` [V]).

**Data (DATA owner)**
- **[Δ] `LibrarySchema` v2 is the only schema bump this release.** It covers `reading_log`, `book_prefs` (T1-9) and `quotes.style` (T2-3).
  ```
  reading_log(day INTEGER NOT NULL, book_id INTEGER NOT NULL, seconds INTEGER NOT NULL DEFAULT 0,
              pages INTEGER NOT NULL DEFAULT 0, chars INTEGER NOT NULL DEFAULT 0,
              PRIMARY KEY(day, book_id)) WITHOUT ROWID
  ```
  - Add an index on `book_id`.
  - New tables come from `CREATE_ALL`, which is idempotent. **[Δ]** The new *column* needs `ALTER TABLE quotes ADD COLUMN style INTEGER NOT NULL DEFAULT 0` in `onUpgrade` when `oldVersion < 2`, because `CREATE_ALL` does not add columns.
- **Upsert:** SQLite 3.18 has no UPSERT, so use `UPDATE … SET seconds = seconds + ?, …` via `executeUpdateDelete()`; if it returns 0, `INSERT`. Both run in one transaction.
- `deleteBookRows` also deletes the book's log rows, and `BackupJson` exports them keyed by path.
- New `data/ReadingLog.kt`: `add`, `summary`, `days`, `perBook`, `cpm`.

**Tracking** (pure `reader/ReadingTracker.kt`, unit-tested)
- `onPageShown(now, pageChars)`: if the page was shown for at least 2 s, count `min(d, 300 s)`, one page and its chars.
- `onPause` closes the current page and returns the delta. `ReaderActivity.onPause` upserts it on IO and calls `Library.addReadingTime`.
- **[Δ] Screen-off TTS:** the tracker stops at `onPause`, so while TTS keeps speaking after that, `TtsController` reports its own speaking seconds (from start and stop timestamps) to the log.
- A day change mid-session flushes on the next turn.

**Screen "읽기 기록" (Wave 2):**
- Content: summary, streak, the 20×7 잔디 `HeatmapView`, speed, most read this month, and "올해 다 읽은 책 N권" from `book_prefs.finished_at`.
- It is static with no fling, and its queries run on IO when the page opens.

**Performance:** three integer additions per turn, and one transaction per pause off the main thread.

**Owners:** `data/LibrarySchema.kt`, `LibraryDb.kt`, `LibrarySql.kt`, `ReadingLog.kt`, `Backup*.kt`, `reader/ReadingTracker.kt`, `ReaderActivity.kt`, `TtsController.kt`, `SettingsActivity.kt`, `StatsPage.kt`, `HeatmapView.kt`, `LibraryActivity.kt`.

**Effort:** M. **Risk:** the DB bump. `onDowngrade` already keeps the data [V].

---

#### T1-7 남은 시간: time left in the episode and the book

Unchanged. It depends on T1-6's `cpm`, which is loaded in `afterOpen` on IO.
- **Footer item:** `footerTimeLeft` (0 끔 / 1 이 화 / 2 책).
- **Formats:** "1분 미만", "n분", or "h시간 m분".
- **Also shown in:** the TOC header, the end panel, and document info.
- **Cost:** episode remaining is 1–3 sections of arithmetic; book remaining is O(1) through a suffix-sum array rebuilt only in `onCountsChanged`.
- **Effort:** S.

---

#### T1-8 내 스타일: saved style presets

Unchanged behaviour:
- The style row reads [웹소설] [전자책] [종이책] [내 스타일 ▾], with up to 5 saved styles and 저장 / 관리.
- A style stores the typography fields plus `pageMargins`.
- **[Δ]** `Settings.userStyles` is parsed **lazily, on first use** (the popup's style row), never in `Settings.loadApp()`. The first `Settings.app` access on a cold start happens on the main thread, and it must not parse JSON.
- **Owners:** R2 `settings/`, `ReadingSettingsPopup.kt`, `SettingsJson.kt`.
- **Effort:** S.

---

#### T1-9 TXT settings for this book only

**User value:** the TXT index key contains every option (`format/txt/TxtIndex.kt:64-76` [V]). Changing any option globally makes every large TXT reparse on its next open. A per-book setting protects "fast opening" [R].

**Behaviour**
- **[Δ] Storage:** a `book_prefs` table in the library DB (v2), not a prefs file:
  ```
  book_prefs(book_id INTEGER PRIMARY KEY, txt_override TEXT, finished_at INTEGER NOT NULL DEFAULT 0,
             episode_label TEXT)
  ```
  - `data/BookPrefs.kt` offers `txtOverride(bookId)`, `setTxtOverride(bookId, o?)`, `finishedAt`, and `setEpisodeLabel` (for T2-13).
  - `TxtOverride` holds `blankLines`, `stripIndent`, `joinWrapped`, `detectChapters`, `chapterRegex`, `emphasizeHeadings` and `replaceRules`, encoded with org.json.
  - The table is deleted in `deleteBookRows` and included in the backup.
  - Why the DB:
    - The open path already queries this DB (`IntentFiles.resolveBook` → `Library.book(id)` [V]), so this is one more indexed read on an open connection.
    - A prefs XML grows with every book's rules and is parsed whole on first access.
    - A book opened from a file manager in a cold process would pay that parse.
    - No warm-up is needed.
- **`ReaderSettings.withTxt(o)`** in `reader/` is the only place the two settings sources merge.
- **`startOpen`:**
  - Inside the existing IO block, after `resolveBook`: `val eff = settings.withTxt(BookPrefs.txtOverride(b.id))`.
  - `eff` feeds `Documents.open`, `BookSession` and `textSignature`.
  - `onResume` and the settings listener compare `Settings.reader.withTxt(bookOverride)` with `s.settings`.
- **Popup "TXT 파일 · 이 책에만 적용":**
  - Changes go through the popup's existing reparse debounce (`ReadingSettingsPopup.kt:141-152` [V]). One reparse per burst of taps; each reparse of a 14 MB file costs about 1 s on the A53 [I].
  - Two new rows: "모든 TXT 기본값으로 저장" and "이 책 설정 지우기 (기본값 사용)".
- **Global defaults:** the page "TXT 기본 정리 설정".

**Performance:** one indexed DB read inside the existing IO block.

**Owners:** `data/BookPrefs.kt`, `LibrarySchema.kt` (DATA), `ReaderActivity.kt`, `ReadingSettingsPopup.kt`, `MainPage.kt`, `TxtDefaultsPage.kt`, `Backup*.kt`.

**Effort:** M. **Risk:** a relayout loop. Route every read through `withTxt`, and test that apply → `onResume` → no relayout.

---

#### T1-10 Replacement-rule manager, cleanup packs, and "delete this phrase" from a selection

**Behaviour**
- **The format does not change.** Rules stay `pattern => replacement`. The UI adds `## 이름` name comments and `#- ` for disabled rules; both are comments to `ReplaceRules.parse`.
- **[Δ] No parser change and no `TxtIndexStore.VERSION` bump.** The product lead's [I] risk is already handled in code [V]:
  - A line that a rule turns blank is flagged `DELETED` (`format/txt/TxtLines.kt:411-418`).
  - `DELETED` lines split blank runs, so deleting an ad line between two blank lines does not create a false scene break (`TxtParagraphs.kt:90-116`).
- **Screen "치환 규칙"** — **[Δ]** typo fixed; the draft read "치环 규칙".
  - It is a full-screen ink dialog. Each row shows a checkbox, the name and the rule.
  - The editor has 이름 / 찾을 내용 / 바꿀 내용, a "정규식" switch (off means `Pattern.quote`) and "시험해 보기".
  - Long-press offers 위로 / 아래로 / 삭제.
  - Bottom buttons: [+ 규칙 추가] [정리 규칙 팩…] [텍스트로 편집].
  - Scope follows T1-9.
- **Cleanup packs** are all off until added. Patterns 1–5 are unchanged; pack 4 keeps its warning.
- **"이 문구 지우기" [Δ spec completed]:**
  - The dialog offers [줄 전체 지우기] [이 문구만] [취소].
  - **Build the literal safely.** Split the phrase at `"=>"`, `Pattern.quote` each part, and join the parts with `=[>]`. A raw `\Q…\E` containing `=>` would be cut at the arrow by `ReplaceRules.parse`, and `Pattern.quote` also handles an embedded `\E`.
  - The rule is `^.*<lit>.*$ =>` or `<lit> =>`.
  - **The action is disabled when the selection contains `\n`.** Rules apply per source line.
  - **After the reparse, check the result.** If the phrase still occurs in the current section's text, say "원본 줄과 모양이 달라 지우지 못했습니다 (줄 합치기·공백 정리 때문일 수 있음)". Rules match source lines before hard-wrap joining and whitespace normalisation. The check is one `indexOf` on the current section.
- **Performance [Δ]:**
  - Rules are compiled once. A change costs one reparse of this book.
  - Measure the first parse of the 14.8 MB perf file with all 5 packs on (`TxtPerfTest`).
  - If the line stage grows by more than 30%, add a per-rule literal prefilter (`String.contains` on a required literal) before `Matcher.find`. Pack 4's alternation is the likely cost.
- **Owners:** `ReadingSettingsPopup.kt`, `RulesDialog.kt` plus a pure `RuleList.parse/serialize` and `RuleLiteral.build`, `SelectionController.kt`, `BookPrefs`.
- **Effort:** M.

---

#### T1-11 TTS that keeps reading with the screen off

**The risk [V/I]:** the manifest has no service, so speech lives only as long as the activity process [R]. ReadEra puts background TTS behind Premium [R].

**Behaviour**
- **`reader/extras/TtsService.kt`** is a keep-alive and controls shell; the engine stays in `TtsController`.
  - `startForeground(…, FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)`.
  - A platform `MediaSession` whose callbacks call `TtsBridge.controller` (a WeakReference).
  - A `Notification.MediaStyle` notification on channel "tts" at `IMPORTANCE_LOW`. It updates only on play/pause and chapter changes.
  - It holds a `PARTIAL_WAKE_LOCK` only while speaking, with a timeout of the sleep deadline or 2 h.
- **[Δ] Paused state:**
  - The service stays in the foreground with its notification, so play from the notification works without needing a background-start exemption.
  - The wake lock is released.
  - After 10 minutes paused, the service stops itself.
- **Screen off:** pages still turn internally. **[Δ]** On resume, the reader redraws and runs one e-ink refresh **only if the page on screen changed while it was stopped**. It never flashes on every wake.
- **[Δ] "읽는 문장 표시"** (`AppSettings.ttsHighlight`, default on):
  - Today every sentence calls `host.setHighlights("tts", …)` (`TtsController.kt:455-458` [V]). That redraws the whole page, which means one panel update every few seconds while listening with the screen on.
  - Turning it off gives zero updates between page turns.
- **"수면 타이머":** 끔 / 15 / 30 / 45 / 60 / 90분 / 이 화 끝까지 / 2화 끝까지 (`ttsSleepChapters`).
  - **[Δ]** Check the deadline against `elapsedRealtime`. Today it is a `postAtTime` on `uptimeMillis` (`TtsController.kt:625-633` [V]), and uptime stops while the CPU sleeps.
- **No `POST_NOTIFICATIONS` prompt:** MediaStyle notifications that carry a media session are exempt on Android 13+ [verify on the Comet].

**Performance:** the service exists only while TTS is active, and nothing else is registered.

**Owners:** `TtsController.kt`, `TtsService.kt`, `TtsPage.kt`, R2.

**Effort:** M. **Risk [I]:** vendor task killers. Test 30 minutes with the screen off.

---

#### T1-12 Wi-Fi 전송: receive books over Wi-Fi

Unchanged:
- Entry points: the drawer row and `PAGE_WIFI`, with the same screen text.
- `data/LanUpload.kt` on `ServerSocket`: ports 8080–8089, alive only while the page is shown and resumed, one accept thread plus one handler thread, 30 s timeouts.
- Routes: `GET /<code>` and `POST /<code>/upload`, streaming to disk.
- Files: `.txt` / `.epub`, at most 200 MB, sanitised names, "이름 (2).txt" on collision.
- Destination: `<primary>/Books`, or `getExternalFilesDir("books")` without all-files access.
- `addOrUpdateFile` runs per finished file. The screen stays on while the page is open.

**Security and correctness changes [Δ]:**
- **Access code:** 4 characters from an unambiguous 31-character alphabet (no 0/o/1/l/i), about 920k combinations. A 4-digit code has 10k combinations and can be brute-forced over a LAN in seconds.
  - After 10 wrong paths, the server ignores requests for 30 s.
  - The screen shows the code in the URL, e.g. `http://192.168.0.23:8080/k7m3`.
- **Bind to the Wi-Fi interface.** Bind the socket to the `wlan*` site-local IPv4 address, not `0.0.0.0`, so it is unreachable over USB tethering or a VPN.
- **File names:** decode `filename=` as UTF-8 (browsers send raw UTF-8) and honour `filename*=`.

**Performance:** zero when the page is closed.

**Effort:** M. **Risks:** the About text on network use; AP isolation (show the hint).

---

#### T1-13 Library: "간단히" compact list and multi-select

Unchanged:
- `COMPACT("간단히")`: 56dp rows with the title plus "작가 · 34% · 3일 전", and a ⋮ button.
- Multi-select: the toolbar reads "N권 선택" with [전체] [컬렉션에 추가] [다 읽음으로] [읽을 책으로] [휴지통] [닫기], plus [더보기] when exactly one book is selected.
- Batch writes run in one transaction.

**[Δ] Note:** long-press opens the single-book menu today (`LibraryViews.kt:162`, `:264` [V]). The new behaviour matches ReadEra [R], but it changes the user's habit, so mention it in the release notes.

**Effort:** M.

---

### Tier 2 (later, in value order) [Δ reordered]

- **T2-10 Open TXT and EPUB inside a ZIP. [Δ] Do first.**
  - Korean 텍본 are commonly passed around as ZIP.
  - Extract once to `cacheDir/zip/<crc>` and then use the normal index.
  - Use `ZipFile(file, Charset.forName("MS949"))` (API 24). Entries with the UTF-8 flag are still decoded as UTF-8, which is exactly the needed fallback.
  - A picker appears when a ZIP holds several books.
  - **Owners:** `format/Documents.kt` (frozen, R3), `data/FileScanner.kt`. **Effort:** S–M.
- **T2-1 독서노트 모아보기 and export.** A drawer row lists every quote, note and bookmark across books; [내보내기] writes Markdown or TXT. **Effort:** M.
- **T2-2 Selection across pages.** Hold a handle near the page edge for 600 ms to turn the page, within the same section. Needs `ReaderHost.turnPageKeepingSelection()` (frozen). **Effort:** M.
- **T2-3 Highlight styles** (회색 / 밑줄 / 테두리 / 반전).
  - **[Δ]** The `quotes.style` column is added in T1-6's v2 migration, so no v3 is needed. Only the UI, the `Quote` model (R3) and `PageRenderer` remain.
  - **Effort:** S–M.
- **T2-4 EPUB footnote popup.** Unchanged. **Effort:** M.
- **T2-5 E-ink image pipeline and image viewer.** Unchanged, with dithering off until tested [I]. About 5–15 ms per image on the prefetch thread. **Effort:** M.
- **T2-7 잠들 때 표지 (sleep cover).** Unchanged. Probe the standby app first. **Effort:** M.
- **T2-8 Front-light care.** Unchanged: auto-dim through `ScreenOnKeeper`'s existing check, a quadratic brightness curve, and the screen-on choices. No new timers. **Effort:** S.
- **T2-9 비밀 책장 / 앱 잠금.** Unchanged. `android.hardware.biometrics.BiometricPrompt` needs API 28, so it is PIN-only on 26–27. **Effort:** M.
- **T2-11 합본.** Unchanged. **Effort:** L; deferred.
- **T2-12 새 버전 텍본으로 이어 읽기, plus [Δ] rename-safe identity.**
  - Today a moved file is re-linked by name and size only (`data/FileScanner.kt:235` [V]), so renaming a file in place loses its position, bookmarks and quotes. ReadEra keys books by file hash [R].
  - Add a fingerprint at scan time: size plus CRC32 of the first and last 64 KB. It is computed only for new paths whose size matches a vanished entry.
  - The same fingerprint helps the "updated file" match.
  - **Performance:** scan time only, at most 128 KB read per candidate.
  - **Effort:** M.
- **T2-13 File-name parsing and badges.** Unchanged, at scan time. "123/540화" is written to `book_prefs.episode_label` in the DB. **Effort:** S.
- **T2-14 이어 읽기 card.** Unchanged. **Effort:** S.
- **T2-15 Automatic backup.**
  - **[Δ] When it runs:**
    - never in `LibraryActivity.onStop` when that stop comes from opening a book, because it would compete with the open path;
    - instead on `onTrimMemory(TRIM_MEMORY_UI_HIDDEN)` (the app went to the background), or after 3 s idle in the library;
    - at most once a day, on IO.
  - **Effort:** S.
- **T2-16 Position sync through a file.** Unchanged: read after the first page, write on pause. **Effort:** M.
- **T2-17 작가의 말 접기.** Unchanged, after A5. **Effort:** M.
- **T2-18 TTS 발음 규칙.** Unchanged. **Effort:** S.
- **T2-20 e-ink profiles and 코멧 설정 도우미.** **[Δ]** The diagnostics moved to T1-3a. The profiles and "깨어날 때 새로고침" remain. **Effort:** S.
- **T2-21 UI 크기.** Unchanged. **Effort:** S–M.
- **T2-22 Status bar slots and chapter-tick bar.** Unchanged. **Effort:** M.
- **T2-23 Multi-level location history.** Unchanged. **Effort:** S.
- **T2-24 Tags and search syntax.** Unchanged. **Effort:** M.
- **T2-25 Small input items [Δ trimmed]:** auto page turn proportional to characters per minute, and a tap-zone diagram shown once on first open. **Effort:** S each.
- **T2-26 Safe mode and diagnostic log.** Unchanged. **Effort:** S.
- *(T2-6, T2-19 and the two-finger refresh tap moved to C.)*

---

## C. Deliberately not doing, and why

| Not doing | Why |
|---|---|
| Page-turn animations, curl, fades, ripples, smooth scroll or a scroll reading mode | Each costs e-ink frames and ghosting, and breaks the "no animations" rule. |
| Colour themes, background images, BGM | Pointless on a 16-grey panel. |
| A full refresh on every page by default, or A2/FAST as the default reading mode | Slow, and anti-aliased text breaks up [R]. |
| **[Δ]** Any new app-initiated flash switched on by default, image pages included | Decision 3 and rule 5. The device's own auto-clean may already run, and two mechanisms flash twice (T1-3e). |
| Writing the vendor's persistent settings | System-wide changes the user did not ask for. We only read them. |
| Showing "~" on page totals | Removed by the user in `d61f4ab`. |
| Any work before the first page | Opening fast is the product. |
| **[Δ]** Rebuilding the TXT index in the background after a cache-version bump (the A5 mitigation as drafted) | It races with the user opening that same book and would parse it twice. A clear "목차를 만드는 중…" is cheaper. |
| **[Δ]** The system keyboard for numeric input (page, %, episode) | The IME's slide-in and window resize cost several e-ink updates. `InkNumPad` does the same job. |
| **[Δ]** T2-6 글자 진하기 (an AGSL or ColorMatrix gamma `RenderEffect` on the page view) | A GPU pass on every frame of the page view risks page-turn latency on the PowerVR GE8320. A ColorMatrix below API 33 can't do gamma. The app already has weight and synthetic stroke, and the Comet's E-Ink Center has per-app contrast and text enhancement [R]. Point users there. |
| **[Δ]** Two-finger tap to refresh (from T2-25) | It conflicts with two-finger drumming (`fb1`: each short, still finger is its own tap). Overlapping drum taps would flash instead of turning pages. |
| **[Δ]** T2-19 export a cleaned TXT as EPUB | Little value on the device. Desktop tools do it. |
| Pre-rendering pages to bitmaps | About 4 MB each, and the direct draw is already fast. |
| Background services, polling, JobScheduler, always-on servers, background sync | Battery and wakeups. |
| Accounts, cloud, social, ads, analytics, network crash reporting | Privacy, and the no-network stance. |
| AndroidX, Compose, OkHttp, NanoHTTPD, Room, Material | Size, startup cost and the house rule. |
| PDF, DJVU, MOBI, FB2, CBZ | New parsers, slow on an A53, and off-core. |
| Two-page landscape | A narrow bar device, and double the layout cost. |
| Built-in AI, online translation, StarDict | `PROCESS_TEXT` covers lookup. |
| Vocabulary SRS, quote cards, bionic reading, streak notifications | Little value, and notifications nag. |
| Blue-light or warmth hacks | Not until a device probe finds a writable control. |
| Folder-as-one-book (T2-11) this release | L effort, and a risk to the TXT index. |
| Casual `TxtIndexStore.VERSION` bumps | At most one per release (rule 6). |
| Spinners or indeterminate progress | Keep "no text under 300 ms, then static text". |
| Play Store distribution work now | Keep sideloading. |

---

## D. Contract revision R2: all frozen-file changes, landed as one commit before tier-1 work

- **`settings/ReaderSettings.kt`**
  - `ReaderSettings`: `footerEpisode: Boolean = false`, `footerTimeLeft: Int = 0`.
  - `StylePreset` labels: 웹소설 / 전자책 / 종이책.
  - `TapAction`: add `GOTO("페이지 이동")` and `AUTO_TURN("자동 넘김")`.
  - `enum class KeyHold { REPEAT("계속 넘기기"), CHAPTER("다음·이전 화로"), TEN("10쪽씩"), SINGLE("한 쪽만") }`.
  - `AppSettings`, new fields:
    - `longPressMs = 500`
    - `keyBindings: Map<Int, TapAction> = emptyMap()`
    - `keyHold = KeyHold.REPEAT`
    - `einkRefreshMethod = EINK_REFRESH_AUTO`
    - `einkFlashMs = 100`
    - `einkRefreshEveryNight = -1`
    - **[Δ]** `einkFlashImages = false`
    - `autoMarkFinished = true`
    - `ttsSleepChapters = 0`
    - **[Δ]** `ttsHighlight: Boolean = true`
  - **[Δ]** `einkRefreshOnPanelClose` is removed.
  - Constants `EINK_REFRESH_AUTO/GC16/CLEAN/FLASH = 0..3`.
  - `LibraryListMode.COMPACT("간단히")`.
- **`settings/Settings.kt`:**
  - Persist every new field; `keyBindings` is stored as `"24:NEXT,25:PREV"`.
  - `userStyles` / `saveUserStyles()` store JSON under `a.userStyles`. **[Δ]** It is parsed lazily on the first `userStyles` access, never inside `loadApp()`.
- **`settings/UserStyles.kt`** (new): `UserStyle` with `applyTo`, `matches` and `from`.
- **`data/Models.kt`:** the `Shelf` labels (A8). The `Quote.style` field is deferred to R3 with T2-3; the column already exists from v2.
- **`ui/kit/Ui.kt`:**
  - `stepperRow` does nothing at its limits.
  - The `sliderRow` thumb is a plain dot.
  - `prompt()` hides the cursor until touched.
  - **[Δ]** No `inputType` parameter is needed: numeric entry uses the new non-frozen `ui/kit/InkNumPad.kt`.
- **`render/FontCatalog.kt`:** `DEFAULT_ID = "nanummyeongjo"`, with the side effects noted in A8.
- **`AndroidManifest.xml`:**
  - `INTERNET`
  - `FOREGROUND_SERVICE`
  - `FOREGROUND_SERVICE_MEDIA_PLAYBACK`
  - the `TtsService` declaration with `foregroundServiceType="mediaPlayback"`.
- **`res/values-v31/themes.xml`** (new): the splash attributes.
- **Not frozen, but must land with R2 [Δ]:**
  - `data/LibrarySchema.kt` v2: `reading_log`, `book_prefs`, and `quotes.style` via a guarded `ALTER TABLE`, owned by DATA as one migration.
  - `data/SettingsJson.kt`: the new fields and user styles.
  - `docs/ARCHITECTURE.md`: `ALGO_VERSION` and the golden-hash rule, `EpubPlanCache.VERSION`, `book_prefs`, `reading_log`, `InkNumPad` / `InkPager`, and rules 5 and 6.
- **No change** to `ReaderHost.kt`.

---

## E. Build order, owners and acceptance checks

**Order [Δ]**
1. **Wave 0**, shipped through CI:
   - A1 (ENGINE for `glyphAt`; READER and EXTRAS for wiring; RENDER for the band);
   - A3 (unfrozen strings);
   - A4 (non-kit, non-values parts);
   - A6;
   - A11;
   - A12-3;
   - A12-5.
   - **Record the baseline on the Comet:**
     - cached reopen of the 14.8 MB TXT;
     - first open of a large EPUB, and its second open;
     - page-turn time;
     - library cold start via `adb shell am start -W -n com.ggumtak.readeraplus/.ui.library.LibraryActivity` (TotalTime).
2. **R2 contract commit**, including the v2 schema.
3. **Wave 1, in parallel by owner:**
   - **READER:** A2, T1-2, T1-3 (cadence), T1-4, T1-5, T1-6 (tracker), T1-7, T1-9 (wiring), `BookSession.episodes`.
   - **EXTRAS:** T1-1 (with `InkPager` and `InkNumPad`), T1-8, T1-9 (popup), T1-10, T1-11.
   - **RENDER:** T1-3 (`Eink`, images, diagnostics).
   - **DATA:** the v2 schema, `ReadingLog`, `BookPrefs` (DB), `NextPart`, `LanUpload`, the A2 partial BLOB.
   - **LIBRARY:** T1-13 and the drawer rows.
   - **SETTINGS:** the T1-3 and T1-4 pages, `WifiTransferPage`, `TxtDefaultsPage`.
   - **TXT:** A5 with the release's single `VERSION` bump 3 → 4. T1-10 makes no parser change.
4. **Wave 2:** A8, A9, A12-1/2/4, A13, the T1-6 `StatsPage` and `HeatmapView`.

**Dependencies**
- T1-7 depends on T1-6's tracker and `ReadingLog`.
- T1-2's `finished_at`, T1-9 and T1-10's per-book scope depend on the v2 schema.
- T1-5 and T1-1 share `BookSession.episodes`.
- The go-to dialog and T1-1 share `InkNumPad`.

**Acceptance checks, all required before release**
- **Open time.** The debug log "first page N ms" for the cached reopen of the 14.8 MB TXT is within +10 ms of the **Wave 0 baseline**. The first open after A5's bump may be slow once, and must show "목차를 만드는 중…".
- **EPUB second open.** Faster than the baseline (A12-1).
- **[Δ] Library cold start.** `am start -W` TotalTime is within +5% of the baseline. The `userStyles` parse and the settings additions must not show up here.
- **Page-turn time.** Unchanged against the baseline, measured from the input event to the end of `onDraw`.
- **[Δ] Frames per turn.**
  - Run `dumpsys gfxinfo … reset`, then 20 taps on text pages; "Total frames" must read 20.
  - With TTS speaking and "읽는 문장 표시" off, 0 frames between turns.
  - Opening and closing the TOC with `InkPager`, paging 3 times: 1 frame per page plus the open and close.
- **Idle frames.** 0 over 60 s in the reader, the library and the TOC (A4).
- **Wake locks.** `dumpsys batterystats` shows none except while TTS speaks; none while TTS is paused.
- **Long-press matrix on the device** at 200% line height, with 나눔명조 and 리디바탕:
  - never selects on margins, line gaps, paragraph gaps, the blank tail of a short line, below the text, the header or the footer;
  - selects on glyphs;
  - **[Δ]** while a selection is showing, a long-press on blank space keeps it, and handle dragging stays on its own line.
- **[Δ] TXT first-parse cost.** `TxtPerfTest` on the 14.8 MB file with all 5 cleanup packs on is at most +30% over today's line stage.
- **Screen-off TTS.** 30 minutes on the Comet [I], with no flash on wake unless the page changed.
- **Refresh test.** Record the working method on the Comet (T1-3a) and set the "자동" order accordingly.
- **[Δ] Cold start after install [I].** Measure once whether ART's background profile compilation (after a day of use, or `adb shell cmd package compile -m speed-profile -f com.ggumtak.readeraplus`) changes the cold start. If it does, add that step to the sideload guide.
- **Unit tests:**
  - `glyphAt` and the band functions;
  - **[Δ]** `LayoutGoldenTest` (`ALGO_VERSION`) and the `EpubPlanCache` golden test;
  - `PageCounts.setKnown` and the estimator;
  - the 작가의 말 pruning;
  - `EpisodeNumbers` and `Episodes`;
  - **[Δ]** `NumPadState`;
  - `seriesKey` and `NextPart`;
  - `ReadingTracker`;
  - the `ReadingLog` and **[Δ]** v2 migration SQL strings;
  - `withTxt`;
  - `RuleList` parse and serialize, and **[Δ]** `RuleLiteral.build` (phrases containing `=>`, `\E` and regex metacharacters);
  - the `LanUpload` parsers, the sanitiser and **[Δ]** the code alphabet;
  - `ImageCoverage`;
  - the extended `EinkCadence` (night, images, panel close);
  - `Throttle`;
  - `KeyMap` with bindings and **[Δ]** hold anchored at key-down.

---

## Changelog of review edits

**Sequencing and contradictions**
1. **A5 moved from Wave 0 to Wave 1.** It needs a `TxtIndexStore.VERSION` bump; a hotfix shouldn't make the user's biggest book open slowly.
2. **T1-10 needs no bump and no parser change.** The code already flags rule-emptied lines `DELETED`, and they don't create scene breaks (`TxtLines.kt:411-418`, `TxtParagraphs.kt:90-116`).
3. **A12-5 timing logs moved to Wave 0, with a baseline captured there.** The draft put the measurement in Wave 2, after the Wave 1 work it was supposed to gate. A12-3 (auto-scan) also moved to Wave 0.
4. **T1-3c image flash now defaults to off, and T1-3d's option was removed.** This keeps decision 3; rule 5 (app-started flashes are opt-in) was added.
5. **One schema bump (v2)** now covers `reading_log`, `book_prefs` and `quotes.style` (guarded `ALTER`), so T2-3 no longer needs a v3.

**Specs that couldn't be built as written**
6. **A4:** `isVisible(ime())` is API 30+ and now has a guard.
7. **T1-1:** `setSelection` never fires the IDLE callback, so the pager indicator now updates directly. Numeric entry needed an input type that `Ui.prompt` lacks, so it uses `InkNumPad` instead.
8. **T1-10:** the typo "치环" is fixed. A literal phrase containing `=>` would have broken the rule format, so it is now split and quoted. Multi-line selections and unmatched phrases are handled.
9. **A7:** `InkMessage` has no action button, so the undo moved to T1-2's toggle.
10. **A12-4 already existed.** `afterOpen` already creates the controller; the real fix is sharing one quote query.
11. **T1-11:** the sleep timer used `uptimeMillis` and now uses `elapsedRealtime`. The paused service state is specified.

**Performance and e-ink redesigns**
12. **T1-9 overrides move to a DB table.** A prefs file would be parsed whole on first access, including on cold opens from a file manager; the table is one indexed read on the connection the open path already uses.
13. **T1-8 `userStyles` are parsed lazily**, never during the first `Settings.app` read at cold start.
14. **A12-1:** the EPUB plan cache is looked up only when a large item exists, written after the first page, versioned and golden-tested.
15. **A2:** a golden test enforces `ALGO_VERSION`, and EPUB sampling is limited to single-part items.
16. **T1-4:** the hold action is anchored at the key-down position, so it can't skip an episode.
17. **T1-5:** the episode buttons use `remember = false`, and the episode parse is shared with T1-1.
18. **T1-11:** the refresh on resume runs only if the page changed while the screen was off.
19. **T1-12:** a stronger code, throttling, binding to the Wi-Fi interface, and UTF-8 file names.
20. **A1:** two gaps closed — `tapCandidate` is reset on a rejected long-press, and the handle-drag lift uses the band.
21. **T2-15:** the auto backup must not run while a book is opening.

**Cuts** (moved to C)
22. T2-6 글자 진하기: a per-frame GPU pass that duplicates the device's own contrast setting.
23. T2-19 export as EPUB: low value on the device.
24. Two-finger tap to refresh: conflicts with two-finger drumming.
25. The background index rebuild from A5: races with opening the same book.

**Missing high-value items added**
26. **`InkNumPad`**, so numeric entry never opens the system IME on e-ink.
27. **`InkPager`:** a swipe is one page jump, with no scroll frames.
28. **"읽는 문장 표시":** a TTS highlight toggle. Today every sentence redraws the whole page.
29. **Rename-safe identity:** a partial fingerprint in T2-12. Today renaming a file in place loses its history.
30. **T2-10 ZIP promoted to the first Tier 2 item**, because 텍본 are commonly shared as ZIP.
31. **New acceptance gates:** frames per turn, library cold start via `am start -W`, the TXT parse cost with cleanup packs, and a note on post-install ART compilation.

**Kept as they were:** A3, A6, A9–A11, A13, T1-7, T1-13 and most of Tier 2. They add no open-path work, no idle redraws, and at most O(1) work per turn.

The main files checked in this review are in `/home/user/readeraplus/app/src/main/java/com/ggumtak/readeraplus/`: `reader/ReaderActivity.kt`, `engine/Typesetter.kt`, `engine/TypesetPass.kt`, `reader/extras/SelectionController.kt`, `render/PageRenderer.kt`, `render/Eink.kt`, `reader/BookSession.kt`, `reader/PageCounts.kt`, `format/txt/TxtChapters.kt`, `format/txt/TxtLines.kt`, `format/txt/TxtParagraphs.kt`, `format/txt/TxtIndex.kt`, `format/epub/EpubBook.kt`, `reader/KeyMap.kt`, `reader/extras/TtsController.kt`, `ui/kit/Ui.kt`, `settings/Settings.kt`, `data/LibrarySchema.kt`, `data/FileScanner.kt` and `ui/library/LibraryViews.kt`.