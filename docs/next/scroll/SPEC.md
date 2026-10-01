# R3 spec: scroll reading mode, side margins "40 dp = 0", auto-backup + restore on reinstall

Status: the buildable spec chosen from designs A (tall section), B (stitched pages) and C (parity / e-ink). Nothing in
the repo was edited. It was checked against the working tree of 2026-09-30 while R2 was still in progress.
[Δ] Revised after an adversarial review against the code (`TypesetPass`, `PageRenderer`, `PageView`, `BookSession`,
`ReaderActivity`, `TtsController`, `SelectionController`, `Backup`, `SettingsJson`, the manifest). Every change is
marked [Δ]; the changelog is at the end.

**Land it after R2 merges**, as one contract commit ("R3", §4.1) followed by five parallel owners (§4.2). Line
numbers in `ReaderActivity`, `BookSession` and `PageView` will have moved by then, so those files are referenced by
function name.

Paths are relative to `app/src/main/java/com/ggumtak/readeraplus/`; tests live under `app/src/test/java/…/<same path>`.

The user's requests:

1. **Scroll mode:** add an opt-in continuous vertical scroll mode for phones. Paged mode with tap-to-turn stays the
   default and must not get slower in any way.
2. **Side margins:** the default side margin becomes 40 dp, and the settings show it as "0".
3. **Automatic backup:** a daily backup to a place that survives uninstall. On a fresh install, the app asks
   "이전 설정과 읽기 기록을 복원할까요?".

House rules still apply:

- Nothing new happens before the first page.
- Each turn costs O(1).
- No idle redraws or timers on e-ink.
- Every flash the app starts itself is opt-in.
- Black on white.

---

## 0. Verdict

### 0.1 Scorecard (1–5, higher is better; weights reflect the user's and the lead's priorities)

| Criterion (weight) | A tall section | B stitched pages | C strip + parity |
|---|---|---|---|
| Zero impact on paged mode (×3) | 4 | **5** | 3 |
| Phone smoothness (×2) | 4 | 4 | **5** |
| E-ink behaviour (×3) | 3 | 3 | **5** |
| Feature parity (×2) | 3 | **4** | **4** |
| Implementation risk and size (×2) | 3 | **4** | 2 |
| Memory (×1) | 3 | **5** | 4 |
| **Weighted total** | 44 | **53** | 50 |

**Winner: B (stitched pages) as the foundation.** It supplies the layout, position, cache and page-number model.
**C's device behaviour and parity rules are grafted on top**, which lifts B's weak e-ink score. A few of A's pieces are
added as well.

### 0.2 Why B: claims checked against the code

- **Stitched pages equal one continuous layout.** In `engine/TypesetPass.kt`, `paragraph()` calls
  `breakLines(bs, be, …)` once per block. It justifies inside the same loop (`Align.JUSTIFY -> if (!pre && j < nl - 1)
  justify(…)`). `commit()`, `decideCut()` and `emitPage()` only decide which page a line goes to and its `top`.
  - Line contents and justification therefore do not depend on where pages break.
  - What does depend on pagination:
    - space-before is swallowed at a page top (`sb = if (s > 0) … else 0f`, and the re-topping in `emitPage`);
    - blank paragraphs are dropped at a page top;
    - page-bottom whitespace is left by widow/orphan control, keep-with-next and image moves.
  - One write-only field, `PageInfo.lead`, restores the first two. Cutting each page at its last line's bottom
    removes the third.
- **No `ALGO_VERSION` bump.** `LayoutGoldenTest` hashes page boundaries and `LineInfo` fields only, so `lead` changes
  neither `ALGO_VERSION` nor `GOLDEN_HASH`.
- **The count path is untouched.** `BookSession.store()` keeps calling `counts.set(section, layout.pageCount, …)` with
  real paged layouts. A and C both introduce a second layout generation, and with it the risk of saving wrong page
  counts; B has neither.
- **Everything that uses `curPageIdx` stays exact.** Scroll mode walks the *real* pages, so the footer "12 / 3259",
  the TOC, bookmark and search page labels, `goToPage`, the seek bar, `chapterPagesLeft` and `progress` need no change.
  - A only offers estimates.
  - C needs an extra background paged pass per visited section.
- **Mode switch is one redraw and no relayout.** A and C relayout on every switch, which costs 60–300 ms on the A53.
- **Memory is the paged LRU** (`MAX_CACHED = 4`) plus about 8 B per page. A's tall LRU can reach about 3.5 MB.
- **`ReaderHost` and `SelectionController` stay unchanged.** The viewport is presented as a *virtual page* whose lines
  are re-based to content-box coordinates, so `SelectionController.origin()` (cached per `layout.config` and view size)
  calibrates to the same fixed origin as paged mode. A's section-coordinate model would need a new `ContentOriginHost`
  contract and changes in the extras.

### 0.3 Grafted from C (device behaviour and parity)

- **`AppSettings.scrollStyle`: AUTO / SMOOTH / STEP.** On the Comet, STEP means:
  - nothing moves while the finger moves;
  - on release the text jumps once by the dragged distance, line-snapped;
  - a fling moves exactly one screen;
  - the cut bottom line is hidden;
  - the result is one e-ink update per gesture.
- **Settle, not frame.** Strings, database work, extras and tracking run only when the motion settles, never per frame.
- **Placement rule.** An offset at a line start puts that line at the top. A mid-line offset (a search hit, a TTS
  sentence) puts its line 25 % down the screen.
- **TTS after a drag or fling** restarts only when the spoken sentence has left the screen. The existing `navRestart`
  already checks this against `currentPage`.
- **The end panel** opens only on an explicit "next" at the end, never from a drag.
- **Return chip:** one "manual turn" per screen of accumulated scrolling (`ScreenCounter`).
- **Chapter jumps:** fix `pageIndex` for a top line that sits in page 0 but past its start.
- **Brightness strip:** the rule is unchanged.
- **Auto-backup:** the install-state "offer pending" state ([Δ] it now drives only the offer, not writing), manual
  exports considered as candidates, the JSON-hash check for unchanged content, and the guard against replacing a
  richer backup with an empty one.

### 0.4 Grafted from A

- **Visible sections are never evicted,** generalised to a *range*.
- **Prefetch window sized by fling velocity.**
- **Decor rebuilt only when the top page changes.**
- **Book start and end clamps.**
- **Auto-backup:** `isDue` tolerates a clock that went backwards; the offer check runs before the first scan and holds
  that scan. [Δ] A's "nothing is written until the restore question is answered" is **dropped** (§3.2): it disabled
  the safety net for good on installs that never get all-files access; own-`id8` files plus `pickDefault` give the same
  protection.

### 0.5 Rejected, and why

- **A's `LayoutConfig.paginate` with tall layouts:** it needs a second layout generation, a relayout on every switch,
  estimated page labels only, a bigger LRU, and a contract change in the extras.
- **C's strip config (`height = 2^24`, `maxImageHeightFraction = 3·H/2^24`):**
  - images could grow up to 3 screens tall, and their `ImageCache` keys would differ from paged mode;
  - page counts need a `store()` guard;
  - exact page numbers need an extra paged pass per section.
- **C's install-state rule "`Library.lastOpened() != null` ⇒ settled":** it contradicts C's own requirement that a
  book opened from a file manager before the library must not settle the offer. Replaced by an empty-prefs rule
  (§3.3).
- **B's `draw()` split:** refactoring the paged `draw()` risks changing paged pixels. The paged `draw()` stays
  byte-for-byte, and the scroll methods are new (§1.8).
- **B's `touch(section, alsoShown)`:** too narrow when three or more tiny EPUB sections share one screen. Replaced by a
  range.
- **B's PBB lead rule:** incomplete. A `pageBreakBefore` block that lands at a page top *naturally* must also get the
  break gap, or the stitch property fails (§1.3).

---

## 1. Scroll mode

### 1.1 Invariants for paged mode (checked at review and by gates)

| Area | What paged mode gains | Cost |
|---|---|---|
| Open path | Reads `Settings.app.readMode` from the cached `AppSettings`. [Δ] `applyReadMode()` returns at once while `readMode == PAGED && scroll == null`: no `DeviceClass` read, no object created. [Δ] The reader calls `InstallState.ensure` (§3.3) from `afterOpen()`, i.e. *after* the first page, never before `startOpen` | 0 before the first page |
| `TypesetPass` | 1–3 float additions per *page* (`pageLead`). [Δ] No constructor change (the earlier "extra constructor argument" was stale) | < 0.1 % of layout time; counting results identical |
| `PageInfo` | One `Float` field | ≈ 8 B per page (object alignment) |
| `PageRenderer.draw` | [Δ] Nothing: `draw()` **and `drawLine()`** stay byte-identical (no default parameter, which would change the call site's bytecode). `drawBody` draws image lines through its own peek-only helper and text/rule lines through the unchanged `drawLine` | 0 |
| `PageView` | One null check in `onDraw`, `onTouchEvent`, `computeScroll` and `onGenericMotionEvent` ([Δ] plus the accessibility hooks of §1.7, which run only when an accessibility service queries the view). No `VelocityTracker` or `OverScroller` exists in paged mode | 1 field read |
| `ReaderActivity` | A top-of-function `scroll?.let { …; return }` at the entry points in §1.10. [Δ] The paged code below each branch is byte-identical: `showPage`'s bookkeeping is **not** extracted into a shared function; scroll mode has its own `onScrollSettled` (§1.10) | 1 field read |
| `BookSession` | `touch(section)` becomes `touch(section, shownTo = section)`, so the eviction test is a range check | 0 |
| Page counts, keys, LRU, prefetch | Unchanged | 0 |

**Gates:** RAPerf "open … first page" and "turn N ms" on the Comet must stay within noise of the R2 baseline. The
cached reopen of the 14.8 MB TXT must stay within +10 ms, and a library cold start (`am start -W`) within +5 %. CI
screenshots of paged mode must differ only by the new margins (§2).

### 1.2 Settings (contract): which object, defaults, labels

Both fields live in **`AppSettings`**, not `ReaderSettings`. So they never enter `LayoutKeys.key` or `layoutPart`,
style presets, "내 스타일" or TXT overrides, and switching mode can never relayout or change a page-count key.

```kotlin
// settings/ReaderSettings.kt
/** How the reader moves through a book ([AppSettings.readMode]). Stored by name: never rename. */
enum class ReadMode(val label: String) { PAGED("페이지 넘김"), SCROLL("스크롤") }

/** How scroll mode moves ([AppSettings.scrollStyle]). AUTO = STEP on e-ink devices, SMOOTH elsewhere. Stored by name. */
enum class ScrollStyle(val label: String) {
    AUTO("기기에 맞춤"), SMOOTH("부드럽게 (휴대폰)"), STEP("손을 떼면 이동 (e-ink)"),
}

data class AppSettings(
    /* …existing fields… */
    /** Page turning (default on every device) or continuous vertical scroll (opt-in, R3). */
    val readMode: ReadMode = ReadMode.PAGED,
    val scrollStyle: ScrollStyle = ScrollStyle.AUTO,
    /** Daily backup to shared storage that survives uninstall (§3). */
    val autoBackup: Boolean = true,
)
```

- **Prefs keys:** `a.readMode` (enum name), `a.scrollStyle` (enum name), `a.autoBackup` (bool). `loadApp` reads only
  plain values, and an unknown name falls back to the default.
- **Backups:** `SettingsJson.appToJson` / `appFromJson` map all three.
- **Live switching:** `ReaderActivity.viewPart()` adds `a.readMode` and `a.scrollStyle`, so `onAppSettingsSaved`
  switches the open book live.

**Where the user sees them:**

| Place | Owner | Row |
|---|---|---|
| Reading-settings popup, section "페이지 넘김", first row | EXTRAS | `dropdownRow("넘기는 방식", label)`. Entries: "페이지 넘김 (기본)" and "스크롤 (위아래로 내려 읽기)". Choosing one calls `Settings.saveApp(Settings.app.copy(readMode = m))`. The popup stays open while the book switches behind it (one redraw). |
| Settings → 넘김 page (`PageTurningPage`), new first section "넘기는 방식" | UI | **"넘기는 방식"** chooser: 페이지 넘김 (기본) / 스크롤.<br>**"스크롤 움직임"** chooser (shown only when SCROLL is chosen): 기기에 맞춤 (이 기기: e-ink → 손을 떼면 이동 \| 휴대폰 → 부드럽게) / 부드럽게 (휴대폰) / 손을 떼면 이동 (e-ink).<br>Note: "스크롤에서도 화면 터치 · 볼륨 키 · 페이지 키는 한 화면씩 넘깁니다. 화면 아래에서 잘린 줄이 다음 화면의 첫 줄이 됩니다." |
| Same page, existing rows while in SCROLL | UI | "세로 스와이프": summary "스크롤 모드에서는 쓰지 않음 (위아래로 끌면 스크롤)", row [Δ] **disabled** (`isEnabled = false` on the row and its toggle, which also dims it), so TalkBack announces it as unavailable instead of a merely grey row that still toggles.<br>"스와이프로 넘김": summary "좌우로 밀면 한 화면씩". |
| Main settings, "스와이프로 밝기 조절" | UI | In SCROLL, summary "화면 왼쪽 끝(10%)을 위아래로 끌면 밝기 · 나머지는 스크롤" |
| Reader ⋮ overflow menu | READER_CORE | "스크롤로 보기" / "페이지로 보기": a quick toggle that saves `readMode` |

[Δ] **Accessibility of the switch.** Every place above is an ordinary text row or menu item, so TalkBack and Switch
Access reach it without the page gesture being changed; the ⋮ item is always the way back. Whenever a UI changes
`readMode` to SCROLL and `DeviceClass.cached()` is still null, it starts `DeviceClass.probe()` on IO right away
(§1.11), so AUTO is resolved before the first scroll gesture.

### 1.3 Engine: `PageInfo.lead` (contract), implemented in `TypesetPass` (ENGINE_RENDER)

**Contract (`engine/Layout.kt`):**

```kotlin
/**
 * A page: lines whose text covers [start, end) of the section.
 * [lead] (scroll mode only): the vertical space a continuous flow would put above this page's first line and the
 * page break swallowed — the first item's space-before, blank paragraphs dropped at the page top, and
 * [BREAK_GAP_EM] em when the first item is a pageBreakBefore block (never on a section's first page). Stacking the
 * pages of a section with `lead` above each page's lines reproduces one continuous layout. Not part of page counts,
 * not hashed by LayoutGoldenTest.
 */
class PageInfo(
    @JvmField val start: Int,
    @JvmField val end: Int,
    @JvmField val lines: List<LineInfo>,
    @JvmField val lead: Float = 0f,
) {
    companion object { const val BREAK_GAP_EM = 2f }
}
```

**`TypesetPass` bookkeeping.** It does not change line output, page breaks or `pageCount`.

```kotlin
private var pageLead = 0f       // lead of the page under construction

/** Space the page top swallows for staged/carried item [j] placed at index 0. */
private fun topGap(j: Int): Float =
    buf.pSb[j] + if ((buf.pFlags[j] and F_PBB) != 0 && pageCount > 0) PageInfo.BREAK_GAP_EM * em else 0f
```

1. **`commit()`, every `return` that drops a staged `K_EMPTY` at index 0.** There are three sites: `n == 0` at entry,
   after the PBB `emitPage`, and after each cut `emitPage`. Each one does `pageLead += topGap(0) + buf.pH[0]` before
   returning.
2. **`commit()`, just before `val top = y + sb`:** `if (s == 0) pageLead += topGap(0)`.
3. **`emitPage(cut, endOffset, staged)`:**
   - Build `PageInfo(pageStart, endOffset, lines, pageLead)`.
   - After `pageCount++`, the first `shiftItems` and `n -= cut`, set `var next = 0f`.
   - In the drop loop, add `topGap(d) + pH[d]` for each dropped carried empty `d`. Compute this *before* the second
     `shiftItems`.
   - After the drop, `if (n > 0) next += topGap(0)` (carried item 0 is re-topped with its space-before suppressed).
   - Finally `pageLead = next`.
4. **`finish()`:**
   - The empty section keeps `PageInfo(0, len, emptyList())` (lead 0).
   - The last-page replacement becomes `PageInfo(last.start, len, last.lines, last.lead)`. Today's 3-argument copy
     would drop `lead`.
5. **No `if (retain)` guard is needed.** Count mode ignores `pageLead`; the extra work is 1–3 float additions per page.

**Tests (ENGINE_RENDER):**

- `engine/StitchTest.kt` (property test, JVM):
  - Inputs: 200 random sections from `EngineFixtures`, mixing Korean/Latin paragraphs, blank paragraphs, `softBreak`,
    headings with `keepWithNext`, `pageBreakBefore` blocks (some that land at a page top naturally), rules, and images
    [Δ] whose *scaled* height is ≤ 300 px (the smallest H × `maxImageHeightFraction`): `image()` caps heights at
    `pageH × fraction`, so a taller image legitimately differs between H = 300 and the reference.
  - Parameters: H ∈ {300, 517, 1000, 1400}, with widow/orphan control on and off.
  - Stitch rule: `y = 0`; for each page p > 0, `y += p.lead`; each line's absolute top is `y + line.top`; then
    `y += lastLine.bottom`.
  - [Δ] Reference: the H = 10,000,000 layout **stitched with the same rule**. It is not one page: `commit()` still
    emits a page at every `pageBreakBefore` with `n > 0`, whatever H is, so the `BREAK_GAP_EM` gap exists on both
    sides and is compared like any other lead.
  - Assertions:
    - the sequence of `(start, end, x, justifyExtra, expandMode)` equals that of the reference;
    - every absolute top matches within [Δ] 0.5 px (the reference accumulates `y` in one float up to ~10⁵ px, so a
      0.05 px bound fails on float rounding alone; a real `lead` bug is at least one paragraph space, ≥ 8 px);
    - [Δ] every `lead ≥ 0`, and page 0's `lead` equals the first staged item's space-before (plus dropped leading
      blanks).
- `engine/PageLeadTest.kt`, hand-built cases:
  - a break between paragraphs → `lead = sb`;
  - a break inside a paragraph → 0;
  - a scene-break blank paragraph at the page top → its sb + h, plus the next item's sb;
  - a PBB heading → heading sb + 2 em;
  - an orphan move → the paragraph's sb;
  - a keep-with-next heading chain moved → the heading's sb;
  - `finish()` keeps `lead`;
  - `countPages == layout.pageCount` in every case.
- `LayoutGoldenTest` stays untouched and must pass. That proves paged output is identical.

### 1.4 Geometry and position model (READER_CORE)

- **Viewport = the paged content box** (`generation.geometry`), identical in both modes, so layouts, counts and the
  count key are shared.
  - The header and footer bands, top and bottom margins and the ribbon stay fixed. Text scrolls inside the box and is
    clipped to it: full view width horizontally (italic overhang), content top/bottom vertically.
- **Strip (section s, page p):**
  - `gapAbove(s,p)` is:
    - 0 for (0, 0), the book start;
    - `page.lead + unitGap(s)` for p == 0, s > 0;
    - `page.lead` otherwise.
  - `body(s,p)` is `lines.last().bottom`, or 0 for an empty page.
  - `height = gapAbove + body`. Line i of the strip is drawn at `stripTop + gapAbove + line.top`.
  - The whitespace below the last line of a paged page is never drawn.
  - [Δ] **An empty page (no lines) has `height = 0`: no `lead`, no `unitGap`.** A chain of empty spine items (covers
    whose image failed, blank separator files) therefore collapses instead of scrolling past screens of blank paper,
    and `ScrollPos` never rests on a zero-height strip (it is normalised forward to the next non-empty strip).
- **`unitGap(s)` = `CHAPTER_GAP_EM` (2 em) × em px** when section s starts a chapter, else 0:
  - TXT: `document.sections[s].title != null`;
  - EPUB: the first part of a spine item, from `(document as? EpubBook)?.partCounts`, the same data
    `samplableSections()` reads.
  - A 30k-char TXT continuation chunk therefore joins with plain paragraph spacing, which comes from page 0's `lead`.
- **Position:** `ScrollPos(section, page, dy)`, meaning the viewport top sits `dy` px below the top of that strip, with
  `0 ≤ dy < height`. It is rebased on every strip crossing, so floats stay below about 2k px. There is no book-wide
  scrollY; sections that were never laid out need no height.
- **Reading anchor:** the first line at least half visible, as `(section, line.start)`. This is what position saving,
  `anchor` and relayout use.
  - A paged anchor is a page start, which is also a line start, so switching modes and reopening round-trip exactly.
  - [Δ] **The anchor is sticky across placements.** A settle of kind OPEN, RELAYOUT or SWITCH keeps `anchor` equal to
    the offset it placed (it does not recompute "first half-visible line"). Only a user motion (STEP, DRAG, FLING)
    or a JUMP recomputes it. After a font change or rotation the requested offset is mid-line in the new layout;
    recomputing would snap back to that line's start, and repeated rotations would creep backwards a line at a time.
    Paged mode already avoids this drift the same way (`showPage(…, anchorOffset)`).
- **Top page:** the real page holding the anchor line. It becomes `curSection` / `curLayout` / `curPageIdx`. Every
  existing page-based computation (decor, labels, progress, chapter pages left, tracker, cadence) uses it unchanged.

### 1.5 `reader/ScrollMath.kt`: pure, JVM-tested, allocation-free (READER_CORE)

```kotlin
internal interface StripSource {
    val sectionCount: Int
    /** Laid-out layout of [section] or null (never starts a layout). */
    fun layoutOf(section: Int): SectionLayout?
    /** Extra px above page 0 of [section] (> 0 only at a chapter / spine-item start; never called for section 0). */
    fun unitGap(section: Int): Float
}

internal class ScrollPos {
    @JvmField var section = 0
    @JvmField var page = 0
    @JvmField var dy = 0f
    /** Section a move stopped at because it is not laid out (-1 = none). */
    @JvmField var blockedAt = -1
    fun set(o: ScrollPos)
}

internal enum class Step { MOVED, EDGE, NEED_SECTION }
/** [Δ] Moved here from ScrollReader so the pure settle rule is JVM-testable. */
internal enum class SettleKind { STEP, DRAG, FLING, JUMP, RELAYOUT, OPEN, SWITCH }
internal enum class Placement { TOP, CONTEXT }

internal object ScrollMath {
    const val MAX_STRIPS = 64                 // per viewport walk: chains of empty pages / tiny sections
    const val CONTEXT_FRACTION = 0.25f
    const val CHAPTER_GAP_EM = 2f

    fun gapAbove(src: StripSource, section: Int, l: SectionLayout, page: Int): Float
    fun body(l: SectionLayout, page: Int): Float
    fun height(src: StripSource, section: Int, l: SectionLayout, page: Int): Float

    /** Moves the viewport top by [delta] px (> 0 = towards the end); returns px moved. Stops at the book start, at the
     *  book end (the last line may rise to the viewport bottom, not above), and at a section not laid out
     *  ([ScrollPos.blockedAt]); never reveals unknown content. O(strips crossed + strips in one viewport). */
    fun scrollBy(src: StripSource, pos: ScrollPos, delta: Float, viewH: Float): Float

    /** Positions [pos] for [offset] of [section]: TOP (or offset at a line start) puts that line's top at the viewport
     *  top; CONTEXT puts it ~25 % down, the top snapped to a whole line and clamped at the section start; both clamp at
     *  the book end. [lineAligned] (STEP) keeps the top on a line top even at the book end. */
    fun place(src: StripSource, section: Int, l: SectionLayout, offset: Int, placement: Placement,
              viewH: Float, lineAligned: Boolean, pos: ScrollPos)

    /** One screen down into [out]: the new top is the first line not wholly visible (a line taller than the viewport:
     *  the next line). Never skips a line. EDGE at the book end, NEED_SECTION (out.blockedAt) when unknown. */
    fun stepDown(src: StripSource, pos: ScrollPos, viewH: Float, out: ScrollPos): Step
    /** One screen up into [out]: the line above the first wholly visible line ends at the viewport bottom; the new top is
     *  a whole line. EDGE at the book start. */
    fun stepUp(src: StripSource, pos: ScrollPos, viewH: Float, out: ScrollPos): Step
    /** STEP release: snaps [pos] so the nearest line top is at the viewport top (clamped). */
    fun snapToLine(src: StripSource, pos: ScrollPos, viewH: Float)
    /** STEP release decision: +1 / -1 = one screen (a fling: |vy| ≥ 2 × [flingMin] and |totalDy| < viewH / 3),
     *  0 = move by the dragged distance (then [snapToLine]). Sign: finger up = +1 (towards the end). */
    fun releaseStep(totalDy: Float, vy: Float, flingMin: Float, viewH: Float): Int

    /** Reading anchor, packed (section shl 32) or offset: the first line at least half visible. */
    fun anchor(src: StripSource, pos: ScrollPos, viewH: Float): Long
    /** Packed (section shl 32) or page of the anchor line. */
    fun topPage(src: StripSource, pos: ScrollPos, viewH: Float): Long
    /** The book's last line is wholly visible. */
    fun atBookEnd(src: StripSource, pos: ScrollPos, viewH: Float): Boolean
    /** View-relative bottom of the last wholly visible line (STEP clips there, so the cut line is not drawn). */
    fun lastFullyVisibleBottom(src: StripSource, pos: ScrollPos, viewH: Float): Float
    /** Px between two positions (for the SMOOTH step animation); NaN when an unknown section lies between. */
    fun distance(src: StripSource, from: ScrollPos, to: ScrollPos, limit: Float): Float
    /** Visits visible strips top-down: (section, layout, page, stripTop relative to the viewport top, gapAbove). */
    inline fun forEachVisible(src: StripSource, pos: ScrollPos, viewH: Float,
                              visit: (Int, SectionLayout, Int, Float, Float) -> Unit)
    /** [Δ] Settle rule of §1.4: [placed] for OPEN / RELAYOUT / SWITCH, else [computed] (both packed). */
    fun anchorAfter(kind: SettleKind, placed: Long, computed: Long): Long
    /** First movement beyond the slop: true = vertical (scroll), false = horizontal (swipe). */
    fun isVertical(dx: Float, dy: Float): Boolean = Math.abs(dy) > Math.abs(dx)
}

/** Whole screens of accumulated scrolling (return chip "manual turns"). */
internal class ScreenCounter { fun reset(); fun add(px: Float, viewH: Float): Int }
```

`EPS = 0.5 px` for "wholly visible". The ported sketch is B's Appendix A plus `clampBottom`.

[Δ] `MAX_STRIPS` bounds the work of **one call**, never progress: a call that reaches it returns the distance moved
so far with `pos` past the last strip crossed, and the next frame or step continues from there. Zero-height strips
(§1.4) count towards the cap (it bounds work) but never stop a later call from crossing them. The fuzz test includes
200 consecutive empty sections and asserts that repeated `stepDown` calls reach the book end.

### 1.6 `reader/ScrollReader.kt`: Android glue (READER_CORE)

It exists only while `readMode == SCROLL` and a session shows a page. It is created in `showPage` and released in
`closeCurrentBook` and on the switch back to paged. It runs on the main thread.

```kotlin
internal class ScrollReader(private val view: PageView, private val host: Host) : PageView.ScrollInput, StripSource {
    interface Host {                                   // a private object inside ReaderActivity
        fun session(): BookSession?
        fun renderer(): PageRenderer?
        fun geometry(): PageGeometry?
        fun decor(): PageDecor                         // chrome only (no highlights), rebuilt by onTopPageChanged
        fun highlights(section: Int): List<Highlight>  // quotes + owners, merged, sorted by start, cached per section
        fun unitGap(section: Int): Float               // session.startsUnit(s) × 2 em
        fun onTopPageChanged(section: Int, page: Int)  // header / footer / ribbon strings
        fun onSettled(kind: SettleKind, movedPx: Float)
        fun onBlocked(section: Int)                    // lay out in the foreground (+ delayed "불러오는 중…")
    }
    // SettleKind: see ScrollMath.kt ([Δ])

    var motion: Motion                                 // STEP or SMOOTH (resolved from AppSettings.scrollStyle + DeviceClass)
    val pos: ScrollPos

    fun showAt(section: Int, layout: SectionLayout, offset: Int, placement: Placement, kind: SettleKind): Long // top page
    fun step(next: Boolean): Step                      // STEP: instant; SMOOTH: 180 ms startScroll (a 2nd step finishes it first)
    fun onSectionStored(section: Int, layout: SectionLayout)
    /** [Δ] Stops motion (settling first) and ignores input until the next showAt, but keeps drawing the frozen
     *  snapshot (slots + the geometry and decor they were shown with), exactly like a stale PageFrame in paged mode.
     *  Dropping the slots here would draw a blank or misaligned frame during every relayout (an extra e-ink update);
     *  they are replaced, not cleared, by the next showAt. Called wherever the session makes a new generation. */
    fun onGenerationChanged()
    fun onHighlightsChanged(section: Int)
    fun onTrimMemory()                                 // drop slots and caches outside the visible range
    fun anchor(): Long
    fun topPage(): Long
    fun atBookEnd(): Boolean
    fun virtualPage(): VirtualPage?                    // lazily built, cached until pos / focus / generation changes
    fun focusAt(y: Float): Boolean                     // long press / link on the other visible section
    fun clearFocus()                                   // [Δ] back to the anchor section (after a tap's link test)
    fun lineWhollyVisible(section: Int, offset: Int): Boolean   // [Δ] isOnCurrentPage / TTS goTo rule (§1.10)
    fun visibleRanges(visit: (section: Int, start: Int, end: Int) -> Unit)   // ribbon, bookmark toggle
    /** [Δ] Stops a fling / step animation / live drag and, when something was moving, runs the settle pipeline
     *  (kind FLING or DRAG) before returning, so `anchor` is current. Every caller that reads `anchor` or the
     *  position right after (relayout, rotation, onPause, switchMode, closeCurrentBook) relies on this. */
    fun stopMotion(): Boolean
    /** [Δ] True from a drag's start until its settle, and while a fling or step animation runs. */
    fun userMoving(): Boolean
    fun detach()
    // PageView.ScrollInput: live, isMoving, stopMotion, dragBy, release, cancelDrag, computeScroll, draw
}

/** The viewport presented to ReaderHost users as a page of [section]. */
internal class VirtualPage(val section: Int, val layout: SectionLayout, val page: PageInfo, val pageIndex: Int)
```

**Layout slots.** [Δ] Eight slots (parallel `IntArray` + `Array<SectionLayout?>`) map section → `SectionLayout`.
They are refreshed from `session.peek` when the top section changes, a section is stored, or [Δ] at the first
`showAt` after a generation change (until then they keep the frozen snapshot, see `onGenerationChanged`).
The frame loop therefore never does a boxed `HashMap<Int,…>.get`; section numbers above 127 would allocate an
`Integer`.
- [Δ] **Every** `onSectionStored` re-peeks **all** slots, not only the stored section: `store()` may have evicted
  another one, and a slot must never keep an evicted layout alive (with huge EPUB items, one pinned layout is
  several MB on top of the LRU).
- [Δ] A slot miss inside a walk (e.g. more tiny sections on screen than slots) refills from `peek` once and replaces
  the slot farthest from the viewport; it cannot happen per frame for the same section.

[Δ] **Visible-range protection is live.** `ScrollReader` tracks `(firstVisibleSection, lastVisibleSection)` during
every walk. Whenever that pair changes — also mid-drag and mid-fling, not only at settle — it calls
`session.touch(first, last)` at once (main thread, O(visible sections)). Otherwise a prefetch stored during a long
fling evicts with a stale protected range and can drop the section that is on screen.

**Virtual page.** It holds the wholly visible lines of the focus section. In STEP these are the lines above the clip.
- The focus section is the anchor section, or the section chosen by `focusAt` while a selection is active. It returns
  to the anchor section once `selection.isActive` is false. [Δ] Two short-lived exceptions: `handleTap` focuses the
  tapped section for its link test and clears it right after, and a suppressed TTS `goTo` to a visible line (§1.10)
  focuses that line's section until the next settle.
- Each line is copied as `LineInfo(start, end, x, top + shift, baseline + shift, bottom + shift, justifyExtra,
  expandMode, imageBlock, imageWidth, imageHeight, isRule)`, where `shift` is the line's y relative to the content-box
  top of the viewport, [Δ] computed from the **same rounded strip top that `draw` uses** (`round(ct + stripTop + gap)
  − ct`). Otherwise hit tests, handles and the calibrated selection origin are off by up to 1 px against the glyphs.
- The page is `PageInfo(first.start, startOfNextLineInSection or content.length, lines)`, and `pageIndex` is the real
  page of the first line.
- It is built only when an extra asks: about 20–40 copies, 2–4 KB. It is never built per frame.
- [Δ] **While `userMoving()`**, `virtualPage()` returns the one built at the last settle (or null before the first).
  Extras never see a page from the middle of a fling, and TTS's per-word `onRange` → `currentPage` does not rebuild
  it every call while the position changes each frame.

**Per-page highlight lists.** `highlights(section)` is filtered to each visible page's `[start, end)` into a cache of
8 slots keyed by (section, page). A list is built when a page first becomes visible, never per frame. Changes are
handled by `onHighlightsChanged`.
- [Δ] A page with no overlapping highlight gets the shared `emptyList()`: a book without quotes allocates nothing at
  page crossings during a fling.
- [Δ] The filter binary-searches the section list by `start` (it is sorted), so a section with thousands of search
  hits costs O(log n + k) per page crossing, not O(n).

**Draw (no allocation):**

```
renderer.drawChrome(canvas, decor, cl, ct, cw, ch, w, h)
val clipBottom = if (STEP) min(ct + ch, ct + lastFullyVisibleBottom) else ct + ch
canvas.save(); canvas.clipRect(0f, ct, w.toFloat(), clipBottom)
forEachVisible { s, l, p, stripTop, gap ->
    missing = missing or renderer.drawBody(canvas, l, p, cl, round(ct + stripTop + gap), ct, clipBottom, pageHighlights(s, p))
}
canvas.restore(); renderer.drawOverlay(canvas, decor, cl, ct, cw, w)
if (missing && !imagesInFlight) requestImages()   // [Δ] one batched renderer.prefetchPages(window) { invalidate }
```

Strip tops are rounded to whole pixels, while lines keep their paged fractional positions. This avoids glyph shimmer
during a drag and 1 px seams.

[Δ] "Missing" uses exactly `needsDecode`'s test (`peek == null && !isKnownFailure`). An undecodable image is drawn
as its outline and never re-requested, so a broken image cannot cause an endless prefetch → invalidate → draw loop.

**Prefetch.** Runs at settle and on a top-section change:
- [Δ] layouts, **bounded by the LRU**: at most `MAX_CACHED − (lastVisible − firstVisible + 1)` sections outside the
  visible range are requested, in the motion direction first (s + 1, then s + 2 when the distance from the viewport
  bottom to the end of section s is less than `3·viewH + |v|·0.5 s`, A's velocity window), and s − 1 only while room
  remains (moving up: s − 1 first). Requesting more than the LRU holds would lay a section out only for `store()` to
  evict it again, which on the A53 is 150–300 ms of wasted CPU per 30k chars, repeatedly;
- images of the pages within ±1 viewport: [Δ] **one** `renderer.prefetchPages(…)` call per settle carrying the whole
  window, nearest page first. The image prefetcher is a `LatestTaskRunner` that keeps only the newest pending task,
  so one call per page would silently drop all but the last.

`session.touch(firstVisibleSection, lastVisibleSection)` keeps every visible section out of LRU eviction ([Δ] called
on every change of that range, see "Visible-range protection" above).

**Blocked at an unknown section.**
- A drag or fling stops at the last known line.
- `host.onBlocked(s)` lays `s` out in the foreground (`s.layout(s)` in a `stripJob`) and starts
  `scheduleLoadingText()`, which appears only after 300 ms.
- When `onSectionStored` delivers the layout, the view is invalidated and the loading text cancelled; the user keeps
  scrolling.
- v1 does not resume the fling automatically.

**The whole settle pipeline** runs once per gesture, step or jump:
1. `anchor` ([Δ] recomputed for STEP, DRAG, FLING and JUMP; kept as placed for OPEN, RELAYOUT and SWITCH, §1.4)
2. top page
3. `onTopPageChanged` when it changed
4. `host.onSettled`
5. [Δ] the virtual page snapshot is rebuilt lazily from here (see "Virtual page")

### 1.7 `reader/PageView.kt` (READER_CORE)

```kotlin
/** Non-null only in scroll mode: draws instead of [frame] and receives vertical drags. */
var scroll: ScrollInput? = null

interface ScrollInput {
    /** SMOOTH (content follows the finger) or STEP (moves on release). */
    val live: Boolean
    fun isMoving(): Boolean
    /** Stops a fling / step animation; true when one was running (this touch is then a "stopper": no tap, no long press). */
    fun stopMotion(): Boolean
    fun dragBy(dy: Float)                          // live only; dy > 0 = content towards the end (finger up)
    fun release(totalDy: Float, velocityY: Float)  // end of a vertical drag (px, px/s; same sign convention)
    /** [Δ] ACTION_CANCEL (or a second finger) during a drag: SMOOTH settles where it is (kind DRAG); STEP discards
     *  the accumulated distance (nothing was drawn) and settles nothing. Never a fling. */
    fun cancelDrag()
    /** [Δ] Accessibility scroll action: one screen step ([next] = forward). */
    fun a11yStep(next: Boolean): Boolean
    fun computeScroll()
    fun draw(canvas: Canvas, width: Int, height: Int)
}
override fun computeScroll() { scroll?.computeScroll() }
```

**Drawing.** `onDraw` becomes `scroll?.let { draw inside the same try/catch; logTraces(); return }` ahead of the frame
path.

**Touch handling** applies only when `scroll != null`; the paged gesture code stays as it is.

| Event | Scroll mode |
|---|---|
| DOWN | `stopper = scroll.stopMotion()`. [Δ] The `VelocityTracker` is obtained lazily on the first scroll-mode DOWN, `clear()`ed at every DOWN, and in `onDetachedFromWindow` recycled **and set to null** (a recycled tracker must never be reused if the view is attached again); `addMovement`. The long-press timer starts only when `!stopper`. [Δ] A pending motion change (`scrollStyle` saved, or the `DeviceClass` probe finished) is applied here, never in the middle of a gesture. |
| First MOVE beyond the slop (SMOOTH: `touchSlop`; STEP: `tapSlop`, so sloppy e-ink taps stay taps) | Brightness strip plus vertical → brightness (unchanged rule; off by default). `isVertical` → **drag**: `moved = true`, long press removed, `cb.onScrollStart()`, [Δ] `lastY = y` (the content starts following from here: no jump by the slop distance). Horizontal → swipe candidate (unchanged). |
| MOVE while dragging | SMOOTH: `dragBy(lastY − y)`. STEP: accumulate only; nothing is drawn. |
| UP after a drag | `computeCurrentVelocity(1000, maxFling)`, then `release(downY − y, −vy)`. It is never a tap or a swipe. |
| [Δ] CANCEL during a drag (rotation, the notification shade, a system gesture, `onPause`) | `cancelDrag()`: the position is settled (SMOOTH) or left untouched (STEP) *before* `onPause` saves `anchor`. The paged code path for CANCEL is unchanged. |
| UP, stopper gesture | Nothing. |
| UP as a tap | Zones as today; NEXT and PREV become screen steps. |
| Horizontal swipe | `swipeToTurn` → a screen step. |
| `verticalSwipe` setting | Ignored (vertical means scroll). |
| Second finger during a drag | The rest of the gesture is ignored (existing `multiIgnored`); [Δ] `cancelDrag()` (SMOOTH settles where it is; STEP discards). |
| Selection active | Every event goes to `SelectionController` (unchanged), so no scrolling happens. |
| Wheel notch or page-turner remote | One screen step (unchanged `onWheel`). |

A new callback, `Callbacks.onScrollStart()`, has a no-op default.

[Δ] **Accessibility (scroll mode only).** `onInitializeAccessibilityNodeInfo` adds `ACTION_SCROLL_FORWARD` /
`ACTION_SCROLL_BACKWARD` and `isScrollable = true` when `scroll != null`; `performAccessibilityAction` maps them to
`scroll.a11yStep(next)` → the same path as a tap-zone step (`userTurn`). TalkBack's scroll gestures and Switch Access
then page through the book. Both overrides are `AccessibilityNodeInfo` APIs from API 21 and run only when an
accessibility service asks; paged mode keeps today's node unchanged.

### 1.8 `render/PageRenderer.kt` additions (ENGINE_RENDER; contract signatures)

`draw()` stays byte-for-byte, including `prefetchNeighbours`. The new methods share the private helpers
`drawStatus`, `drawHighlights`, `drawLine` and `drawRibbon`.

```kotlin
/** Scroll mode: background + header/footer status from [decor] (its highlights are ignored). No text. */
fun drawChrome(canvas: Canvas, decor: PageDecor, contentLeft: Float, contentTop: Float, contentWidth: Float,
               contentHeight: Float, viewWidth: Int, viewHeight: Int)
/** Highlights and lines of page [pageIndex] with the page's content top at [top] (view px); lines wholly outside
 *  [clipTop, clipBottom) are skipped. Never decodes: a missing bitmap draws the 1 px outline. Returns true when an
 *  image was missing. */
fun drawBody(canvas: Canvas, layout: SectionLayout, pageIndex: Int, left: Float, top: Float,
             clipTop: Float, clipBottom: Float, highlights: List<Highlight>): Boolean
/** Bookmark ribbon (drawn last). */
fun drawOverlay(canvas: Canvas, decor: PageDecor, contentLeft: Float, contentTop: Float, contentWidth: Float, viewWidth: Int)
/** [Δ] Decodes the images of every (layouts[i], pages[i]), i < count, in order, as ONE task on the existing
 *  image-prefetch thread (a newer call replaces a pending one, never a running one); [done] runs once on the main
 *  thread after the task decoded something (not when nothing was missing). */
fun prefetchPages(layouts: Array<SectionLayout?>, pages: IntArray, count: Int, done: Runnable?)
```

- [Δ] `drawLine` is **not** changed. `drawBody` handles image lines itself with a private `drawImagePeek` that uses
  `images.peek(...)` (never `images.get(...)`) and the 1 px outline when missing; text and rule lines go to
  `drawLine`. The paged `draw()` → `drawLine` call sites compile to the same bytecode as today.
- [Δ] **`drawChrome` allocates nothing per frame.** `drawStatus` calls `TextUtils.ellipsize` for a footer-left text
  that does not fit, on every draw: harmless once per turn, but an allocation on each of 120 frames a second in
  SMOOTH. New `fun fitFooter(decor: PageDecor, contentWidth: Float): PageDecor` returns the decor with `footerLeft`
  already ellipsized to the width `drawStatus` will offer it (same arithmetic, pure). ScrollReader calls it once when
  it rebuilds the decor (top-page change, settle), so `drawStatus`'s `measureText(fl) <= avail` test always passes
  and its ellipsize branch never runs in scroll mode. `drawStatus` itself is unchanged.
- **Also in ENGINE_RENDER:** `render/DeviceClass.kt` (§1.11).

### 1.9 `reader/BookSession.kt` (READER_CORE, which absorbs R2's READER_B for R3)

```kotlin
/** Marks [section]..[shownTo] as displayed (scroll mode: every section on screen); none of them is evicted.
 *  [Δ] Scroll mode calls it whenever that range changes, also during a drag or fling (§1.6). */
fun touch(section: Int, shownTo: Int = section)
/** True when [section] starts a chapter / spine item (TXT: title != null; EPUB: first part of a spine item). Lazy
 *  BooleanArray, shared with samplableSections' partCounts read. Section 0 → false. */
fun startsUnit(section: Int): Boolean
```

- **Eviction** in `store()` becomes `lru.firstOrNull { it !in protFrom..protTo }`. The cache may briefly exceed
  `MAX_CACHED` while more sections than that are on screen (tiny spine items).
- **`CountOrder`** keeps `protectedSection = section`.
- **Nothing else changes:** generation, config, counts, key, `store()` counts, prefetch.

### 1.10 `reader/ReaderActivity.kt` integration (READER_CORE)

**Mode handling:**
- New field: `private var scroll: ScrollReader? = null`.
- **`applyReadMode()`**, called from `applyAppSettings()`:
  - resolves `motion` = `scrollStyle` + `DeviceClass`;
  - if `readMode` changed and a page is shown, runs `switchMode()`.

**`switchMode()`** never lays anything out:

1. `stopAutoTurn(false)`, `selection?.clear()`, `scroll?.stopMotion()` ([Δ] which settles, so `anchor` is current
   even when the switch arrives mid-fling).
2. [Δ] `a = anchor` — **not** `currentPosition()`. In scroll mode `currentPosition()` is the virtual page's start
   (first *wholly* visible line of the focus section), which can be one line below the anchor or in the other section.
3. **To SCROLL:** create the `ScrollReader`, `page.frame = null`, `page.scroll = it`, then
   `showPage(curSection, curLayout!!, curPageIdx, Nav.RELAYOUT, anchorOffset = o)` with [Δ] `o = a.offset` when the
   anchor lies on the page shown (after a paged turn it is that page's start; after a relayout or an earlier switch it
   is the exact line being read), else the page start. The line `o` goes to the top (TOP).
4. **To PAGED:** `scroll.detach()`, `scroll = null`, `page.scroll = null`, then
   `showPage(sec, l, l.pageForOffset(a.offset), Nav.RELAYOUT, anchorOffset = a.offset)`.
5. [Δ] Round trips are exact **in both directions**: paged → scroll → paged returns to the same page, and
   scroll → paged → scroll puts the same line back at the top, because paged mode keeps `anchor = a.offset` after the
   RELAYOUT show.

**Branches.** Each is `scroll?.let { …; return }` at the top of the function; the paged code is untouched.

| Entry point | Scroll-mode behaviour |
|---|---|
| `showPage(section, layout, idx, kind, anchorOffset)` | [Δ] Paged body **unchanged** (no extraction). The scroll branch at the top: `top = scroll.showAt(section, layout, anchorOffset ≥ 0 ? anchorOffset : p.start, placement, kind)`; `showAt` ends in the settle pipeline, whose `host.onSettled` is `onScrollSettled(kind)` (the "Scroll settle" row below), the single place where scroll mode does the page bookkeeping (`cur*`, `displayedGenId`, `anchor`, `cancelLoadingText`, error panel, traces, pin, tracker, cadence, save, prefetch). Placement is TOP, except for JUMP to an offset that is not a line start, which uses CONTEXT. |
| `navigateTo` → `preloadImages` | In scroll mode, also pages idx + 1 (and idx − 1 for CONTEXT) when `needsImageDecode`. Text-only books pay nothing. |
| `turn(next)` | After the `navJob` / backlog and `layoutStale` checks: `scrollTurn(next)`. MOVED → true, and settle (`Nav.TURN`) happens at the end of the step. EDGE → false. NEED_SECTION → `navJob` runs `s.layout(sec)` and then repeats the step (turns meanwhile go to `backlog`). |
| `flushTurns()` | Applies `min(|n|, 10)` sequential steps instantly (no animation, even in SMOOTH) with one draw at the end; `edgeReached` on EDGE. [Δ] On NEED_SECTION mid-flush the steps not yet applied go back into `backlog` (`backlog.restore`) and the layout job starts, exactly like the paged `walk.remaining`; nothing is dropped except beyond the cap of 10. |
| `holdAction` | TEN = 10 steps through the backlog (existing code path), CHAPTER = `jumpChapter`, REPEAT = paced steps. |
| `userTurn` | Unchanged: it calls `turn`. A drag never calls `userTurn`, so **the end panel only opens on an explicit next**. |
| Scroll settle (`onScrollSettled`) | Set `anchor` and `curSection` / `curLayout` / `curPageIdx`, `displayedGenId`; `s.touch(firstVisible, lastVisible)`; prefetch (§1.6).<br>If the top page changed [Δ] and the kind is not RELAYOUT: `trackPage(topPage)`.<br>[Δ] Unless RELAYOUT: `schedulePositionSave()`; always `keeper.poke()`, `if (chromeVisible) bindChrome()`.<br>`onTurnShown(TURN or JUMP, chapterChanged, layout, topPage)`: one cadence turn per step, release or fling; SMOOTH frames never count.<br>For DRAG or FLING: `if (ttsSpeaking()) tts.onUserNavigated()` (its `navRestart` returns when the spoken sentence is still on `currentPage`), and `onManualTurn()` once per whole screen from `ScreenCounter`.<br>`selection?.onPageChanged()`. |
| `pageCallbacks.onScrollStart` (drag start) | Close unpinned chrome ([Δ] in STEP, together with the release frame: one e-ink update per gesture, not two); `ownerHighlights.remove(OWNER_SEARCH)` [Δ] followed by `scroll.onHighlightsChanged(section)` for the section it was on (otherwise the cached per-page lists keep drawing the search hit); `stopAutoTurn` (already done by `onTouchStarted`); [Δ] `if (ttsSpeaking()) tts.onUserNavigated()`, which sets TTS's `userMoved` so its `follow` stands still while the finger moves (the settle call re-arms the check). |
| [Δ] ReaderHost navigation during a user motion | While `scroll.userMoving()`, the **ReaderHost entry points** `nextPage()`, `prevPage()` and `goTo(pos, remember = false)` return without moving (`nextPage`/`prevPage` return true, so TTS does not treat it as the book end). Only extras call these (TTS `follow`, the go-to-page landing check); the reader's own paths (`userTurn`, `jumpChapter`, keys) call `turn` / `jumpTo` directly and are unaffected. This is what keeps TTS from yanking the text away from the finger during a drag or a 1–2 s fling. |
| [Δ] `goTo(pos, remember = false)` to a visible line | In scroll mode, while `ttsSpeaking()` and when `scroll.lineWhollyVisible(pos.section, pos.offset)`: no motion and no redraw; the focus section becomes `pos.section` (so `currentPosition()` / `currentPage` then describe that section) until the next settle. TTS reading into the section below a seam therefore does not jump the text to 25 % while the sentence is already on screen. |
| [Δ] `isOnCurrentPage(pos)` | In scroll mode: `scroll.lineWhollyVisible(pos.section, pos.offset)`. Internal code never mixes `cur*` (the top page) with `currentLayout` / `currentPage` (the virtual page, possibly another section) in one computation. |
| `buildDecor()` | In scroll mode, `highlights = emptyList()` (strips carry their own); `bookmarked` = any bookmark inside `visibleRanges`. Header, footer, labels, percent, clock, battery and `chapterPagesLeft` use the top page exactly as in paged mode. [Δ] The scroll decor then goes through `renderer.fitFooter` once (§1.8). |
| `refreshDecor()` / `repaint()` | Rebuild `scroll.decor` (and the renderer) and invalidate. |
| `progress()` | `if (scroll?.atBookEnd() == true) return 1f`, then the paged code. |
| `currentLayout` / `currentPage` / `currentPageIndex` / `currentPosition()` | The virtual page (§1.6): `(vp.section, vp.page.start)`. |
| `hitTest` / `glyphAtView` / `fingerOnChar` | The same `LineGeometry` calls on the virtual page with origin `(geometry.contentLeft, geometry.contentTop)`. No allocation per call: `hitTest` runs about 1,400 times during selection-origin calibration. |
| `pageCallbacks.onLongPress` | `scroll.focusAt(y)` before `glyphAtView` / `selection.startAt`. |
| `handleTap` | [Δ] `scroll.focusAt(y)` first, so the virtual page is the section under the finger (a link in the lower section of a seam is tappable); the link test uses `currentLayout` (the virtual page's layout), **not** `curLayout`, because `hitTest`'s offset indexes the virtual page's section; then `scroll.clearFocus()`. Zones and corners are unchanged. |
| `toggleBookmark()` / `isCurrentPageBookmarked()` | On the virtual page: remove bookmarks inside `visibleRanges`, otherwise add one at `vp.page.start`. |
| `jumpChapter` | `chapterTarget(curSection, pageIndex = if (vp.page.start > 0) max(1, curPageIdx) else 0, vp.start, vp.end, next)` (C's fix for TOC-less books). [Δ] The scroll branch navigates with the private `jumpTo` (it is a user action), not the ReaderHost `goTo(remember = false)` that is suppressed during motion. |
| `setHighlights` / `reloadAnnotations` / `closeCurrentBook` | Invalidate the per-section highlight cache, then `scroll.onHighlightsChanged(section)`. |
| `sessionListener.onSectionStored` | `scroll?.onSectionStored(section, layout)`. |
| `relayout()` / `onViewSizeChanged` / `reopenDocument` | `scroll.stopMotion()` first ([Δ] it settles, so a rotation mid-fling keeps the line that was on top, not the one from the previous settle; in `onViewSizeChanged` it runs **before** `s.setViewport`, and the settle measures against ScrollReader's own frozen snapshot, never the new generation's geometry); the existing path then ends in `showPage(RELAYOUT, anchorOffset)` and the anchor line is back at the top. [Δ] The RELAYOUT settle keeps `anchor` as placed (§1.4), so repeated rotations do not drift. |
| `onPause` | `scroll?.stopMotion()` (settles); save as today. |
| `onTrimMemory` | `scroll?.onTrimMemory()`. |
| `closeCurrentBook` | `scroll?.detach(); scroll = null; page.scroll = null`. |
| `onCreate` | Before `startOpen`: `ReaderPresence.inFront = true` (a field write; today it is set in `onResume`). [Δ] **Not** `InstallState.ensure`: it may write prefs on the first launch of a new version, so it runs in `afterOpen()` (after the first page). The reader writes nothing to the settings prefs before the first page, so the empty-prefs decision (§3.3) is the same there. |
| `onStop` (when `!isChangingConfigurations`) / `onStart` | `AutoBackup.schedule(applicationContext, 5_000) { ReaderPresence.inFront }` / `AutoBackup.cancelScheduled()`. |
| ⋮ menu (`ReaderMenus`) | "스크롤로 보기" / "페이지로 보기" save `readMode`. In SCROLL, "자동 넘김" becomes "자동 스크롤" with the toast "자동 스크롤 켜짐 (한 화면/30초)". |

**Delegation checklist.** Review every hit of this grep after the change:

```
grep -n 'page\.frame\|currentPage\b\|currentLayout\|curLayout\|curPageIdx\|pageForOffset\|s\.peek(\|showPage(\|navigateTo(\|goTo(\|anchor\b' reader/ReaderActivity.kt
```

[Δ] The grep now also lists `curLayout` / `currentLayout` (a function must use one frame of reference, see
`isOnCurrentPage`), `goTo(` (host entry vs internal `jumpTo`) and `anchor` (every reader of it after a motion must
run behind a settling `stopMotion()`).

Each hit must be either correct for real paged layouts (most are, in B's model) or behind a `scroll` branch listed
above. `page.frame` is read only in `showPage`, `refreshDecor`, `repaint`, `hitTest`, `glyphAtView`, `fingerOnChar`
and `closeCurrentBook`.

### 1.11 Device behaviour: STEP (e-ink) vs SMOOTH (phone)

**`render/DeviceClass.kt` (ENGINE_RENDER):**

```kotlin
object DeviceClass {
    const val PREF_KEY = "deviceClass"          // raw pref "eink|<stamp>" | "lcd|<stamp>"; transient (never in our JSON backup)
    /** Pure: known e-ink makers by Build strings (bigme, boox, onyx, innospace, meebook, pocketbook, kobo, likebook,
     *  moaan, inkpalm, …). Unit-tested. */
    fun einkByBuild(manufacturer: String, brand: String, model: String): Boolean
    /** [Δ] Pure: "<MANUFACTURER>/<MODEL>/<Build.FINGERPRINT hash>". A cached value with another stamp is ignored. */
    fun stamp(manufacturer: String, model: String, fingerprint: String): String
    /** Main-thread safe: cached pref ([Δ] only when its stamp is this device's), else true when einkByBuild, else
     *  null (unknown). */
    fun cached(context: Context): Boolean?
    /** Blocking (IO): Eink.vendorName() != null || einkByBuild; writes the pref; returns isEink. */
    fun probe(context: Context): Boolean
}
```

**Resolving `ScrollStyle.AUTO`:**
- `cached == true` → STEP;
- `cached == false` → SMOOTH;
- `null` → STEP until `probe()` finishes. It is started on IO [Δ] **when a UI first sets `readMode = SCROLL`** (§1.2),
  and as a fallback from `applyReadMode` / `afterOpen` in scroll mode; it takes well under a second, once ever. So on
  a phone the probe has normally finished before the first scroll gesture. When it finishes, the new `motion` is
  applied [Δ] at the next ACTION_DOWN, never mid-gesture.
- A forced choice always wins.
- [Δ] **Why the stamp.** The settings prefs are also copied by Android's own Auto Backup (`allowBackup="true"`), which
  restores them onto *another* device on GMS phones and Boox tablets. An unstamped "lcd" from the user's old phone
  would give a Boox SMOOTH scrolling on e-ink; a stamped one is ignored and re-probed.

| | STEP (Comet default) | SMOOTH (phone default) |
|---|---|---|
| Tap, key, volume, remote, horizontal swipe | One screen: the cut line becomes the top line; instant; one draw, one e-ink update | Same distance, `OverScroller.startScroll` for 180 ms. A second tap finishes the running step at once and chains the next from its target, so fast tapping never lags. |
| Drag | Nothing is drawn while the finger moves. On release the text moves by the dragged distance and snaps to a line top (`snapToLine`). | Live 1:1 from `touchSlop`, frames at vsync. |
| Fling | Exactly one screen in its direction (`releaseStep`) | `OverScroller.fling` with platform min/max velocity and friction, driven from `computeScroll` → `postInvalidateOnAnimation`. It stops dead at the book ends and at an unknown section: no `EdgeEffect`, glow or bounce. |
| Bottom line | The cut line is hidden (clip at the last wholly visible line) | Drawn, clipped at the content box |
| Frames while moving | 0; one at settle | 60–120/s; only visible lines |
| Auto turn | Timed one-screen step (same timer as paged) | Timed one-screen step with the 180 ms animation |
| Cadence refresh | One turn per step or release; the opt-in rules are unchanged | n/a (LCD) |
| Idle | Nothing runs | Nothing runs (no Choreographer callbacks, timers or polling) |

**SMOOTH forced on an e-ink device** (the user's explicit choice): live frames are throttled to at least 100 ms apart,
and the final position is always drawn on release or when the fling ends. Scroll mode never changes the view's
vendor waveform (`einkMode`), and it adds **no flash**. [Δ] Throttling is done by *not invalidating*, not by skipping
work inside `onDraw` (an invalidated view always presents a frame): `dragBy` only accumulates, and one
`postDelayed(frameTick, remaining)` applies the accumulated delta (and, for a fling, `computeScrollOffset`) and then
invalidates. The tick is removed at settle, so nothing runs at rest.

### 1.12 Feature-parity table

| Feature | Paged (today) | Scroll mode | Code |
|---|---|---|---|
| Tap zones NEXT/PREV | Page | One screen (no line skipped; the cut line becomes the top) | READER_CORE `turn` |
| MENU tap, corners (bookmark, invert) | ✓ | Unchanged | — |
| Keys, volume, learned keys, `keyBindings` | Page | Screen step; hold TEN = 10 steps, CHAPTER, REPEAT paced | `turn`, `holdAction` |
| Wheel, page-turner remote | Page | Screen step | `onWheel` |
| Horizontal swipe | Page | Screen step | `PageView` |
| Vertical swipe option | Up = next | Ignored (vertical = scroll) | `PageView`; SETTINGS summary |
| Brightness strip (opt-in) | Left 10 % | Same; with it on, a scroll can't start in the left 10 % | unchanged rule |
| Header (chapter) and footer (page, 회차, chapter pages left, time left, %, clock, battery) | Per turn | Fixed bands. Strings are rebuilt when the top page changes and at settle. **The page label is the exact paged page of the anchor line.** % is 1.0 at the book end. | `buildDecor`, `progress` |
| Chrome, seek bar, page label | Pages | Unchanged: pages are real, and a seek lands with that page's start at the top | — |
| Go-to page, %, 화 (`PageJumpHost`) | Exact | Exact: page start at the top | — |
| TOC, links, bookmarks list, return chip | Page containing the target | Target line at the top. A mid-line target (hit, sentence, fragment) is 25 % down. [Δ] Links are tappable in either section of a seam (`handleTap` → `focusAt`). | `showAt` placement |
| Search | Hit page; highlight | Hit line 25 % down; highlight on the strips; dropped at the first step or drag | `onScrollStart`, `userTurn` |
| TTS start / follow / user move | Page | Start at the top line.<br>Follow when the sentence passes the virtual end: `nextPage()` (a step) or `goTo` (25 %). [Δ] A sentence already wholly visible (e.g. below a seam) causes no motion.<br>[Δ] While the finger moves or a fling runs, TTS never moves the text (host suppression + `userMoved` from drag start).<br>A drag or fling restarts only if the sentence left the screen.<br>Steps always restart (as today). | settle rule; TTS unchanged |
| Selection, handles, action popup | Within the page | Within the wholly visible lines of the section under the finger. Scrolling is frozen while a selection is active. | virtual page, `focusAt` |
| Quotes and highlights | Drawn | Drawn per visible page (cached per-page lists) | `drawBody` |
| Bookmark toggle and ribbon | Page | Toggle at the top line; the ribbon shows when a bookmark is in the visible range | `toggleBookmark`, `buildDecor` |
| Chapter prev/next | ✓ | ✓ (the `pageIndex` fix) | `jumpChapter` |
| Auto turn | Every N s | A screen step every N s (menu label "자동 스크롤") | timer unchanged |
| End panel, auto-mark 완독 | Next on the last page | Next (tap, key, auto, TTS) while `atBookEnd`. A drag to the end just stops. | `userTurn` |
| Reading tracker, ReadingLog | Page shown for 2–300 s | The same, using the **real page** at the top (tracked at settle when it changes). `pages` means the same thing in both modes. | `trackPage` at settle |
| E-ink cadence, picture pages | Per turn (opt-in) | Per step, release or jump; picture coverage of the top page | `onTurnShown` |
| Images | Pre-decoded per page | Pre-decoded for the first viewport (idx, idx ± 1 when needed) and prefetched ±1 viewport. **Draw never decodes.** | [Δ] `prefetchPages` (batched), `drawBody` |
| Relayout (font, size, margins, rotation, pinned chrome, TXT re-parse) | Keeps the page | Keeps the top line | `showPage(RELAYOUT)` |
| Position save, reopen, library progress | Page start | Anchor line start; exact round trip in both directions | `anchor` |
| Mode switch | — | One frame, no layout, the same place | `switchMode` |
| Night mode (invert) | ✓ | ✓ | renderer colours |

**Deliberately limited in v1:**

1. Auto turn is a timed screen step on both devices. There is no continuous auto-scroll yet.
2. Selection covers only wholly visible lines of one section. Partly visible lines are not selectable, and neither
   handles nor selection auto-scroll.
3. A fling that stops at a section being laid out does not resume by itself.
4. CONTEXT placement clamps at the section start and does not reach into the previous section.
5. A drag to the end never opens the end panel, so auto-mark 완독 needs an explicit "next".
6. The mode is global (`AppSettings`); there is no per-book mode.
7. No scrollbar (the footer has page and %), and no overscroll effects.
8. The SMOOTH tap animation (180 ms) cannot be configured.
9. The wheel is always one screen per notch (remote compatibility), never line-wise.

### 1.13 Performance budget

Measured on two devices:

- **Comet:** 720×1440 at density 2. With the new 40 dp margins the content box is 560×~1280 px, which holds about 16
  lines.
- **Phone:** 1080×2400 at density 2.625. The content box holds about 20–22 lines.

| Item | Paged | Scroll STEP (Comet) | Scroll SMOOTH (phone) | Gate |
|---|---|---|---|---|
| Open to first page | Baseline | Baseline, plus ≤ 1 extra image page preload when images exist. If the anchor is in the last viewport of a section, the blank tail fills about 50–200 ms after the first frame. | same | RAPerf open within noise |
| Turn / step | Baseline | Step search ≤ 40 line compares (< 0.05 ms) plus one draw (≈ paged draw, 6–10 ms on the A53) | 180 ms animation | Comet step ≤ paged turn + 2 ms |
| Per frame while moving | — | 0 frames | UI thread 0.5–1.5 ms (about 25 visible lines × `charPositions` + 1–3 `drawTextRun`) + RenderThread 1–2 ms; **0 allocations** ([Δ] including the chrome: footer pre-fitted by `fitFooter`; decor strings are rebuilt only at top-page crossings) | Phone p95 frame < 4 ms UI; `gfxinfo` janky < 1 % over 10 flings at 120 Hz |
| Top-page crossing | — | Decor strings (same as a paged turn), 1 per-page highlight list | same | — |
| Section load | Layout time | **Identical** (the same paged layout, same LRU, no extra pass): about 150–300 ms per 30k chars on the A53, off the UI thread | 25–60 ms per 30k chars | — |
| Section boundary | — | Prefetch lead: a 30k-char TXT section is about 130–180 screens | s + 2 prefetched when close or when flinging fast | No blocked scroll in normal reading |
| Memory | 4 layouts | +8 B/page, plus a virtual page of ≤ 4 KB, plus ≤ 8 per-page highlight lists; [Δ] slots never pin an evicted layout (re-peeked on every store), so a huge EPUB item is never held beyond the LRU | same | `dumpsys meminfo` within ±1 MB of paged |
| Mode switch | — | One frame, 0 layouts | same | — |
| Idle | 0 | 0 | 0 | `dumpsys gfxinfo` flat at rest |

**RAPerf, DEBUG only.** It logs "scroll: N frames, M > 8 ms" at settle (sampled), and "turn N ms" for steps through
the existing `traceTurn`.

### 1.14 JVM tests

| Test | Owner | Checks |
|---|---|---|
| `engine/StitchTest`, `engine/PageLeadTest` | ENGINE_RENDER | §1.3 |
| `engine/LayoutGoldenTest` | (unchanged) | Must stay green with the same `GOLDEN_HASH` |
| `render/DeviceClassTest` | ENGINE_RENDER | `einkByBuild` positives and negatives; [Δ] `stamp` and "a cached value with a foreign stamp is ignored" |
| [Δ] `render/FitFooterTest` | ENGINE_RENDER | `fitFooter` result always passes `drawStatus`'s fit test (pure arithmetic with a fake width function) |
| `reader/ScrollMathTest` | READER_CORE | Uses fake `SectionLayout`s with explicit `lead` values and `EngineFixtures` layouts.<br>• `scrollBy` across pages and sections; clamps at both book ends; blocked at an unknown section; zero-height strips; `MAX_STRIPS` termination.<br>• Fuzz: `stepDown` never skips a line and moves at least one line; after `stepUp`, every line between the new top and the old first wholly visible line is wholly visible.<br>• `place(TOP)` / `anchor` round trip at random line starts; `place(CONTEXT)` puts the line within [20 %, 30 %] of the viewport and clamps at the section start.<br>• `snapToLine`; `releaseStep` (fling vs distance); `atBookEnd`; `lastFullyVisibleBottom`; `distance`; `isVertical`; `ScreenCounter`; unit-gap only on page 0 of a chapter section.<br>• [Δ] Empty pages/sections have height 0 (no lead, no unit gap); `pos` never rests on one; 200 consecutive empty sections are crossed by repeated calls.<br>• [Δ] Sticky anchor: the pure helper `ScrollMath.anchorAfter(kind, placed, computed)` (used by ScrollReader's settle) returns `placed` for OPEN, RELAYOUT and SWITCH and `computed` otherwise. |
| `reader/BookSessionHelpersTest` (extend) | READER_CORE | Range protection in the pure eviction helper (extract `pickVictim(lru, from, to)`) |
| `settings/SettingsStoreTest` (R3 cases) | CONTRACT | `readMode` / `scrollStyle` / `autoBackup` defaults and round trip; unknown names → defaults |
| `data/SettingsJsonR3Test` (new) | CONTRACT | App fields mapped; unknown names → defaults; transient keys (§3.7) never exported or restored |

### 1.15 Emulator screenshot checks (`tools/ci/screenshots.sh`, UI owner; CI runs it on `[screens]`)

The existing paged shots stay; expect the narrower text column from §2. Scroll mode is added after `22_epub_page9`,
since the emulator resolves as a phone:

| Shot | Steps | Expect |
|---|---|---|
| `14_reading_settings` (existing) | — | "좌우 여백" shows **0**; the first row is "넘기는 방식 · 페이지 넘김" |
| `60_scroll_on` | Open the sample EPUB; tap the centre; `tap_label "읽기 설정"`; `tap_label "넘기는 방식"`; `tap_label "스크롤" contains`; back | The same first line at the top as the paged page; footer page unchanged |
| `61_scroll_drag` | `input swipe 360 1100 360 500 400` | The text moved; header and footer fixed; no half-drawn glyph band at the top edge beyond the clip |
| `62_scroll_step` | `input tap 600 900` | The line that was cut at the bottom of `61` is now the top line |
| `63_scroll_keys` | PAGE_DOWN ×3 | Advanced; footer page increases |
| `64_scroll_toc` | Chrome → 목차 → second entry | Chapter heading at the top; return chip shown |
| `65_scroll_select` | `input swipe 300 700 300 700 900` | Selection handles sit on the text (origin calibration still right) |
| `66_scroll_seam` | Keep stepping until the footer's chapter changes | The chapter gap is visible; no page-bottom holes |
| `67_step_release` | Settings → 넘김 → 스크롤 움직임 → 손을 떼면 이동; reopen; slow swipe of 300 px | The top is line-aligned; the bottom cut line is hidden |
| `68_back_to_paged` | ⋮ → "페이지로 보기" | The page contains the previous top line |
| [Δ] `69_scroll_again` | ⋮ → "스크롤로 보기" | The same top line as `67` (exact round trip scroll → paged → scroll) |

All shots: the `-b crash` buffer is empty, and `logcat` shows no "draw failed". Restore-offer shots are in §3.9.

---

## 2. Side margins: 40 dp shown as "0" (request 2)

### 2.1 What the numbers mean

- **Stored values stay actual dp.** This covers `r.marginLeftDp` / `r.marginRightDp`, backups, `UserStyle` and
  geometry, so every backup stays compatible in both directions. Only the default and the *display* change.
- **The default becomes 40 dp**; today it is 18.
- **UI value = actual − 40.** The range is **−40 … +40** (actual 0 … 80 dp, the same maximum as today), step 2.
- **Labels:** "0", "+4", "−10" (U+2212), with no unit.
- "상하 여백" is unchanged: the user only asked about 좌우.
- **Effect:**
  - Comet (density 2): 80 px per side, so the text column goes from 648 to 560 px (about 2 fewer Hangul per line at
    20 sp).
  - 420 dpi phone: 105 px per side.

### 2.2 Contract (`settings/ReaderSettings.kt`)

```kotlin
val marginLeftDp: Int = 40,
val marginRightDp: Int = 40,

/** Side-margin display scale (R3): the settings show actual dp − [ZERO_DP]; stored values stay actual dp. */
object SideMargin {
    const val ZERO_DP = 40
    const val LEGACY_DEFAULT_DP = 18
    const val UI_MIN = -40          // actual 0 dp
    const val UI_MAX = 40           // actual 80 dp (today's maximum)
    const val UI_STEP = 2
    /** Marker "the side margins were saved by an R3+ build" (prefs + backup reader object; style JSON uses "marginBase"). */
    const val KEY = "r.marginBase"
    fun toUi(actualDp: Int): Int = actualDp - ZERO_DP
    fun toDp(ui: Int): Int = (ui + ZERO_DP).coerceAtLeast(0)
    /** "0", "+4", "−10" (U+2212). */
    fun label(ui: Int): String = when { ui > 0 -> "+$ui"; ui < 0 -> "−${-ui}"; else -> "0" }
    /** Values saved before R3 that equal the old untouched default. */
    fun isLegacyDefault(hasMarker: Boolean, left: Int, right: Int): Boolean =
        !hasMarker && left == LEGACY_DEFAULT_DP && right == LEGACY_DEFAULT_DP
}
```

### 2.3 Migration (contract)

- **`settings/Settings.kt`:**
  - `saveReader` writes `putInt(SideMargin.KEY, SideMargin.ZERO_DP)`.
  - `loadReader` reads `ml` / `mr`; if `SideMargin.isLegacyDefault(p.contains(SideMargin.KEY), ml, mr)`, it loads
    **40/40**.
  - Nothing is written during load: the mapping repeats on every load until the next `saveReader` persists 40/40 and
    the marker. That means no write on the cold-start path and an idempotent migration.
- **`data/SettingsJson.kt`:**
  - `readerToJson` adds `.put(SideMargin.KEY, SideMargin.ZERO_DP)`. The key becomes typed, so it is part of
    `MAPPED_KEYS` and never travels as an unmapped raw pref.
  - `readerFromJson`: when `o.has("r.marginLeftDp")` and `isLegacyDefault(o.has(SideMargin.KEY), ml, mr)`, it
    restores 40/40.
  - The clamp `0..300` is unchanged.
- **`settings/UserStyles.kt`:** `toJson` puts `"marginBase": 40`. `fromJson` maps a legacy 18/18 without the marker to
  40/40.

**What happens to existing values:**

| Stored value | Result |
|---|---|
| Untouched 18/18 on an existing install | 40/40, shown as **0** |
| A deliberate 18 on an existing install | Also 40/40. Accepted: the user asked for 40 as the base, and settings were wiped on every update until now anyway. |
| Any other value (e.g. 24) | Kept, shown as −16 |
| Old backup holding 18/18 | Restores 40/40 |
| Style ("내 스타일") saved at 18/18 | 40/40 |
| New backups | Carry the marker, so a deliberate 18 made on R3 round-trips as 18 |

Side effects:

- Default users get a new content width. The page-count key changes once, and every book recounts once in the
  background (partial counts resume).
- "설정 초기화" resets to 40.
- `pageMargins = false` still means 4 dp (`LayoutKeys.TINY_MARGIN_DP`).
- The `SelectionController` fallback origin reads actual dp, so no change is needed there. The same goes for
  `LayoutKeys.geometry`.

### 2.4 UI

- **Popup** (`reader/extras/ReadingSettingsPopup.addPage`, EXTRAS):

  ```kotlin
  val marginH = stepperRow("좌우 여백", SideMargin.toUi(cur.marginLeftDp).toFloat(),
      SideMargin.UI_MIN.toFloat(), SideMargin.UI_MAX.toFloat(), SideMargin.UI_STEP.toFloat(),
      { SideMargin.label(it.toInt()) }) {
      val dp = SideMargin.toDp(it.toInt())
      update(cur.copy(marginLeftDp = dp, marginRightDp = dp), debounce = true)
  }
  ```

  `lockWidthForValues` already sizes the box for "−40".
- **Settings page** (`ui/settings/PageTurningPage`, "페이지 표시" section, UI owner):
  - A new `ctx.stepperRow("좌우 여백", …)` with the same scale and label, [Δ] placed first in the "페이지 표시"
    section (this page has no "페이지 여백" row today; that switch lives only in the popup). Saving goes through
    `editReader { it.copy(marginLeftDp = dp, marginRightDp = dp) }`.
  - Below it, `ctx.note("0이 기본 여백입니다. −로 좁히고 +로 넓힙니다.")`.
  - It is hidden while `r.pageMargins` is false, the same rule as the popup.
  - [Δ] The value view gets `accessibilityLiveRegion = POLITE` (both steppers), so TalkBack speaks "−10" after a tap
    instead of nothing; the −/+ buttons already carry "좌우 여백 줄이기 / 늘리기" descriptions.

### 2.5 Tests (CONTRACT, in the same commit)

- **`reader/LayoutKeysTest`:**
  - `geometryWithMarginsHeaderFooter`: `contentLeft` 36 → 80, width 648 → 560.
  - Any other default-margin expectations get the same shift.
- **`settings/SettingsStoreTest`:**
  - fresh default is 40;
  - legacy 18/18 without the marker loads 40/40;
  - 24/24 is kept;
  - 18/18 with the marker is kept;
  - `saveReader` writes the marker.
- **`settings/UserStylesTest`:** the same cases for styles.
- **`data/SettingsJsonR3Test`:**
  - an old backup (18/18, no marker) restores 40/40;
  - a new backup round-trips a deliberate 18;
  - a backup without margin keys leaves the device values.
- **`settings/SideMarginTest`:** `toUi`, `toDp`, `label`.

---

## 3. Auto-backup and restore on reinstall (request 3)

### 3.1 Location (survives uninstall; no manifest change)

| Access | Where and how | Visible to a new install |
|---|---|---|
| All-files access on API 30+ (the normal state, since scanning needs it), or legacy READ + WRITE on API ≤ 29 | `File` API: `<primary>/Download/ReaderaPlus/backup/`, via `mkdirs()` | Yes, once access is granted again. The first-run permission panel already asks for it. |
| No all-files access, API 29+ | `MediaStore.Downloads.EXTERNAL_CONTENT_URI` insert with `RELATIVE_PATH = "Download/ReaderaPlus/backup/"`, `MIME = application/json`, `IS_PENDING` 1 → write → 0. No permission needed. The app sees its own rows for rotation. | Not automatically (ownership is dropped on uninstall). It is found once access is granted, or through the SAF picker. |
| API 26–28 without WRITE | Skip (`NO_LOCATION`). Nothing survives there. | — |

- **Why `Download/`:** MediaStore guarantees permission-free non-media inserts only in Download on API 29, so both
  routes share one folder. Every file manager shows it, and app-specific dirs are wiped on uninstall.
- **Google Auto Backup** (`allowBackup="true"`, unchanged) is a complement on GMS phones, not something to rely on;
  the Comet has no GMS. [Δ] It copies the whole settings prefs file, **including the device-local keys** that our own
  JSON backup skips (`SettingsJson.TRANSIENT` does not apply to it). §3.3 makes every device-local value verify that it
  belongs to this install / device, so a restored copy is ignored instead of trusted.

**File names:**
- `readeraplus-auto-<id8>-<yyyyMMdd-HHmm>.json`, where `id8` is the first 8 hex chars of this install's `installId`.
- **Each install writes only its own files and keeps its newest 2.** A new install therefore never overwrites or
  rotates away the previous install's backup, even if a gate were wrong. [Δ] This holds only if a reinstall really
  gets a new `installId`; Google Auto Backup would otherwise hand the new install the old id, and its rotation would
  delete the old install's files. Hence the `firstInstallTime` check in §3.3.

**Atomic writes:**
- `File` route: write `.tmp` → `fd.sync()` → rename. Then delete own files beyond 2, but only after a successful write.
- `MediaStore` route: pending row → publish, then rotate the same way.
- On failure, delete the tmp file or pending row.

### 3.2 When it writes (at most daily; never competing with opening a book)

**New `data/AutoBackup.kt` (DATA):**

```kotlin
object AutoBackup {
    const val RELATIVE_DIR = "Download/ReaderaPlus/backup/"
    const val AUTO_PREFIX = "readeraplus-auto-"
    const val MANUAL_PREFIX = "readeraplus-backup-"        // SettingsFormat.backupFileName
    const val MIN_INTERVAL_MS = 20L * 3_600_000             // "daily", tolerant of reading at slightly earlier hours
    const val KEEP_OWN = 2
    // Raw prefs, device-local (transient substring "backupauto"):
    const val PREF_CHECKED_AT = "backupAuto.checkedAt"
    const val PREF_WRITTEN_AT = "backupAuto.writtenAt"
    const val PREF_HASH = "backupAuto.hash"
    const val PREF_SUMMARY = "backupAuto.summary"           // "books,read,bookmarks,quotes" of the last write
    /** [Δ] installId that wrote the four values above; values with another owner (copied by Android Auto Backup
     *  from an earlier install) are treated as absent. */
    const val PREF_OWNER = "backupAuto.owner"

    enum class Outcome { WROTE, UNCHANGED, NOT_DUE, DISABLED, NO_LOCATION, BUSY, SKIPPED_EMPTY, FAILED }
    class Summary(val books: Int, val read: Int, val bookmarks: Int, val quotes: Int)
    /** [Δ] Header only (read with android.util.JsonReader, `books` skipped); [uri] for MediaStore/SAF sources. */
    class Candidate(val file: File?, val uri: Uri?, val createdAt: Long, val auto: Boolean, val installId8: String?,
                    val summary: Summary)

    /** Pure: never checked, ≥ MIN_INTERVAL since the last check, or the clock went back more than 1 h. */
    fun isDue(now: Long, checkedAt: Long): Boolean
    /** Pure: the new snapshot is empty (0 read, 0 bookmarks, 0 quotes) while the last write was not. */
    fun wouldEmpty(last: Summary?, now: Summary): Boolean
    /** [Δ] Pure: nothing worth keeping (0 read, 0 bookmarks, 0 quotes and settings equal to the defaults). */
    fun isBlank(now: Summary, settingsAreDefault: Boolean): Boolean
    /** [Δ] Pure: the default offer among up to 5 headers: the newest whose score (read + bookmarks + quotes) is at
     *  least half the best score among them; null when none has content. */
    fun pickDefault(cands: List<Candidate>): Candidate?
    /** Pure: own files to delete (newest [KEEP_OWN] kept), by name. */
    fun toRotate(ownNames: List<String>): List<String>
    /** Pure: "readeraplus-auto-<id8>-<yyyyMMdd-HHmm>.json" and its parse (id8, time) or null. */
    fun autoName(id8: String, millis: Long, tz: TimeZone = TimeZone.getDefault()): String
    fun parseAutoName(name: String): Pair<String, Long>?

    /** Main thread; returns at once. Runs [runNow] after [delayMs] on the backup thread (latest request wins). */
    fun schedule(context: Context, delayMs: Long, busy: () -> Boolean)
    fun cancelScheduled()
    /** Blocking (backup thread). [force] (지금 백업) skips isDue, the hash and the empty guard, keeps the other gates. */
    fun runNow(context: Context, force: Boolean, busy: () -> Boolean): Outcome
    /** Blocking (IO). [Δ] Headers of up to 5 newest backups of OTHER installs (and manual exports), newest first;
     *  empty without read access or when none. The offer uses pickDefault(); "다른 백업 보기" lists them all. */
    fun findCandidates(context: Context): List<Candidate>
    /** Blocking (IO). Backup.import(candidate) + InstallState.settleOffer; returns restored book count. */
    fun restore(context: Context, c: Candidate): Int
    /** Blocking (IO). Deletes this install's auto files, and other installs' too when [others]. Returns the count. */
    fun deleteFiles(context: Context, others: Boolean): Int
    fun lastWrittenAt(context: Context): Long
    /** "다운로드/ReaderaPlus/backup". */
    fun locationLabel(): String
}
```

**Threading.** One `Executors.newSingleThreadExecutor` thread at `THREAD_PRIORITY_BACKGROUND`, owned by
`AutoBackup`. `schedule` posts to a main-thread `Handler` with the delay, then submits. A single-flight flag drops
overlapping runs. [Δ] The one-shot delay uses `uptimeMillis`, so a reader stopped by the screen turning off runs it
after the next wake; there is no repeating timer.

**Triggers.** Neither is on an open path.

1. **Library idle** (LIBRARY). In `refreshVisible()`, when `isDue(now, checkedAt)`, start a separate idle wait of
   **10 s** with the window focused and no touches, reusing the `restartAutoScanWait` mechanism. On fire, call
   `AutoBackup.schedule(ctx, 0) { ReaderPresence.inFront || LibraryJobs.scanning }`. Leaving the screen cancels the
   wait.
2. **Reader in the background** (READER_CORE). `ReaderActivity.onStop()` (not during a configuration change) calls
   `AutoBackup.schedule(appCtx, 5_000) { ReaderPresence.inFront }`, and `onStart` calls `cancelScheduled()`. This
   covers people who read for days through 앱 시작 시 문서 읽기 or a file manager. The 5 s delay lets `onPause`'s
   position write land first.

**`runNow` phases.** The worker returns `BUSY` whenever `busy()` is true between phases [Δ] **and between the
snapshot's queries** (books, bookmarks, quotes, memberships, reading log, book prefs, settings) **and every 256 KB of
writing** (a partial `.tmp` or pending row is deleted). The snapshot is the long phase (several hundred ms at
background priority on the A53); checking only between phases would let it run on while a book opens. `checkedAt` is
then left unchanged, so the next trigger retries.

1. **Gates:**
   - `Settings.app.autoBackup` → `DISABLED`;
   - [Δ] `InstallState.verify()` (§3.3: `installId` still belongs to this install; one `getPackageInfo` call, on this
     thread);
   - [Δ] *(removed: "offer pending → nothing is written")*. That gate left auto-backup off **forever** for a phone user
     who never grants all-files access (the offer can then never be answered), which is exactly the user the safety
     net is for. Writing while the offer is pending is safe: files carry this install's own `id8` (never a candidate
     for its own offer, never rotating anyone else's), and `pickDefault` keeps a later install's thin backup from
     winning over a rich older one (§3.4);
   - `isDue` → `NOT_DUE`;
   - location → `NO_LOCATION`.
2. **Snapshot:** `Backup.snapshotJson(ctx)` (new, DATA) returns the full envelope as a `JSONObject`. Store
   `createdAt`, then set `createdAt = 0`. [Δ] Raw prefs are put in **sorted key order** (`prefs.all` is a `HashMap`),
   so an unchanged state always hashes the same.
3. **Hash:** SHA-1 of `root.toString()`. Equal to `PREF_HASH` (with `PREF_OWNER` = this install) → set
   `checkedAt = now` and return `UNCHANGED`. If `wouldEmpty(lastSummary, summary)` [Δ] or `isBlank(summary, …)` →
   return `SKIPPED_EMPTY` and log it (a brand-new install writes nothing until it has something to lose).
4. **Write:** restore `createdAt`, add `origin` and `summary` (§3.6, [Δ] inserted **before** `books` so a header
   read can stop early), serialise with `toString(1)`, then write atomically.
5. **Finish:** rotate. Set `writtenAt = checkedAt = now`, `hash`, `summary`, [Δ] `owner = installId`, then return
   `WROTE`. [Δ] After the first `WROTE` of this install, the library shows once (status strip, no dialog):
   "자동 백업을 다운로드/ReaderaPlus/backup에 저장했습니다 · 설정 → 백업 및 복원에서 끌 수 있습니다".

**Cost:** about 150–400 ms of background-priority CPU on the A53 plus about 50 ms of writing, for about 1–3 MB of JSON
(1,000 books, 2,000 annotations, a year of `reading_log`). The JSON is built at most once per 20 h and written only when
something changed. SQLite WAL reads never block the reader. [Δ] Transient heap: the `JSONObject` tree is several times
the text size (≈ 10–20 MB at the high end) plus two strings (hash, indented write); `largeHeap` covers it, and it is
never built while the reader is in front.

### 3.3 Fresh-install detection: `data/InstallState.kt` (DATA)

```kotlin
object InstallState {
    const val KEY_INSTALL_ID = "installId"            // raw pref, transient
    const val KEY_INSTALLED_AT = "installId.at"       // [Δ] PackageInfo.firstInstallTime when the id was made, transient
                                                      //     (read by ensure only when it creates the id: one binder
                                                      //     call, once per install)
    const val KEY_OFFER = "restoreOffer.state"        // "pending" | "done", transient
    /** Main-thread safe (the settings prefs are already loaded by the first Settings.app read). Idempotent: the first
     *  call of this install decides — no installId yet → create one; offer = pending iff the settings prefs were
     *  empty before this call, else done (an existing user updating, or Android Auto Backup restored the prefs). */
    fun ensure(context: Context)
    /** [Δ] Blocking (IO / backup thread, never the main thread): when KEY_INSTALLED_AT differs from this package's
     *  firstInstallTime, the id was copied from another install by Android Auto Backup → replace it with a fresh id
     *  (the offer state is left alone: a restored "done" is right, the data came back with it). */
    fun verify(context: Context)
    fun installId(context: Context): String
    fun id8(context: Context): String
    /** [Δ] True also when no state exists yet (ensure has not run): unknown counts as pending. */
    fun offerPending(context: Context): Boolean
    /** commit(): the answer must survive a kill. */
    fun settleOffer(context: Context)
    /** Pure (tests): true = offer pending. */
    internal fun decide(existingKeys: Collection<String>): Boolean = existingKeys.isEmpty()
}
```

- **When `ensure` runs:** first thing in `LibraryActivity.onCreate` (LIBRARY; before the `PREF_LEGACY_ASKED` write
  that `onCreate` can make), and [Δ] in `ReaderActivity.afterOpen()` (READER_CORE), i.e. after the first page and
  before the reader could write any pref (it writes settings prefs only on user actions). Nothing writes
  `Settings.raw()` at app start: this was checked, and only `ui/` and `reader/` write it.
- [Δ] **`verify` runs off the main thread** at the start of `AutoBackup.runNow` and `findCandidates`. It costs one
  `PackageManager.getPackageInfo` binder call, which must not sit on the reader's open path; nothing that needs the id
  runs before it. (`ensure` needs the same call only in the one launch that creates the id: in the library's
  `onCreate` that is once per install, in the reader it is after the first page.)
- **Why empty prefs is the right test:**
  - Uninstall and "clear data" wipe prefs and the library together.
  - Android Auto Backup restores both together.
  - So empty prefs ⇔ a fresh install with nothing of its own.
- **A book opened from a file manager first** leaves the offer pending, because `ensure` ran before the reader wrote
  anything. [Δ] Auto-backup still runs (own `id8`, §3.2). If the user later restores, `Backup.import` keeps the newer
  per-book position.
- **Upgrading users are never asked:** they have prefs, so the state is `done`.

### 3.4 The offer: "이전 설정과 읽기 기록을 복원할까요?" (LIBRARY: new `ui/library/AutoRestorePrompt.kt`)

**In `LibraryActivity.refreshVisible()`,** when `InstallState.offerPending` and storage access is granted:

1. **Hold the scan.** This includes the `newlyGranted` immediate scan: set `scanHeld = true` and skip `startScan`.
2. **Find candidates on IO** with [Δ] `AutoBackup.findCandidates`:
   - list `Download/ReaderaPlus/backup/readeraplus-auto-*.json` files whose `id8` is not ours;
   - also list manual exports `readeraplus-backup-*.json` in the top level of `Download/` and `Documents/`, non-recursive
     (the user may have exported one while the signing bug was wiping data);
   - [Δ] order by the time in the **file name** for auto files (`parseAutoName`; a copy or a file-manager move resets
     `lastModified`), by `lastModified` for manual exports, newest first;
   - [Δ] read the **header only** of at most the 5 newest with `android.util.JsonReader` (`version`, `createdAt`,
     `origin`, `summary`; `books` and the rest skipped with `skipValue()`, so no tree is built). A file without
     `summary` (older builds) is counted while streaming its `books` array, still without a tree. About 10–30 ms per
     file on the A53 instead of ~150 ms per MB for a full parse, and the 64 MB guard applies as in `Backup.import`;
   - [Δ] the offered one is `pickDefault(headers)`: the newest whose score (read + bookmarks + quotes) is at least
     half the best score among them. A reinstall that was used for a day and then removed cannot hide the rich
     backup from months of reading, while a later install that was really used wins by recency.
3. **No candidate** (none with content) → `settleOffer()`, release the held scan (`startScan(announce = true)`), done.
4. **Candidate** → show the dialog: `ui/kit` `alert()`, `showNoAnim()`, `setCancelable(false)`, no animation.
   - **Title:** "이전 기록 복원"
   - **Message:** "이전 설정과 읽기 기록을 복원할까요?\n\n{yyyy-MM-dd HH:mm} 백업 · 책 {books}권 (읽던 책 {read}권) ·
     북마크 {bookmarks}개 · 인용문 {quotes}개\n위치: 다운로드/ReaderaPlus/backup (or: 다운로드 / 문서)\n책 파일은 지금 있는
     곳에서 다시 찾습니다."
   - [Δ] When this install already has reading history of its own (the offer was answered late, e.g. access granted
     weeks after install), the message gains a line: "지금 설정은 백업의 설정으로 바뀌고, 책마다 더 최근에 읽은 위치가
     남습니다."
   - **[새로 시작]** (negative): `settleOffer()`, release the scan. The old files are kept (installId-scoped, never
     overwritten) and can still be restored or deleted on the 백업 및 복원 page.
   - [Δ] **[다른 백업 보기]** (neutral, only when more than one candidate was read): a list of the candidates (date ·
     books · read · bookmarks · quotes · "자동" / "직접 내보냄"); choosing one shows the same dialog for it.
   - **[복원]** (positive):
     1. The status strip shows "복원하는 중…".
     2. IO: `AutoBackup.restore` (`Backup.import`, then `settleOffer`, then an empty `Settings.raw().edit().commit()`).
     3. Main thread: toast "책 {n}권의 기록을 복원했습니다", then `recreate()`. The library re-reads list mode and
        sort; `newlyGranted` runs the first scan.
     4. No process restart is needed: no book is open, and `saveReader` / `saveApp` refresh the `Settings` caches.
5. **No storage access yet:** do nothing. The permission panel is already shown, and the check reruns on the resume
   after the grant.
   - If access is never granted, the offer stays pending; [Δ] auto-backup still writes this install's own files
     (MediaStore route), so the safety net works for the next reinstall.
   - `BackupPage` explains this and offers **"백업 파일에서 복원"** (the existing SAF picker, opened at
     `Download/ReaderaPlus/backup` through `EXTRA_INITIAL_URI` where possible). A successful manual restore also
     calls `settleOffer`.

### 3.5 What is and isn't restored

**Restored** (everything `Backup.import` restores today, plus the R2 DATA additions):
- reader and app settings (typed, clamped; this includes `readMode`, `scrollStyle`, `autoBackup` and the margin
  migration);
- "내 스타일";
- unmapped raw prefs, minus transient ones;
- collections;
- per book:
  - flags: favourite, to-read, have-read, trashed;
  - review, encoding, edited metadata;
  - position and progress, last-read time, reading seconds;
  - bookmarks and quotes, with notes;
  - `reading_log` and `book_prefs` (TXT overrides, `finished_at`).
- Books are matched by path, then by file name + size. A file present at its old path is added.

**Not restored:**
- the book files themselves;
- fonts imported into `filesDir/fonts`, which die with the app. A `user:` `fontId` falls back to 나눔명조. Fonts kept
  in `/sdcard/Fonts` survive, and the BackupPage note recommends that folder;
- caches: covers, TXT index, page counts, EPUB plan (all rebuilt);
- device-local prefs: `installId`, offer state, `backupAuto.*`, `deviceClass`, last-scan time, permission-panel flags.
  [Δ] (Our JSON backup never carries them. Android's own Auto Backup may still copy them onto a new install; §3.3's
  `verify`, `PREF_OWNER` and the `DeviceClass` stamp make such copies inert.)

### 3.6 Envelope additions (DATA: `Backup`, `BackupJson`; additive, `BackupJson.VERSION` stays 1)

```json
"origin":  {"installId": "…", "auto": true, "app": "<versionName>", "device": "<MANUFACTURER MODEL>"},
"summary": {"books": 132, "read": 41, "bookmarks": 40, "quotes": 12}
```

Both fields are optional both ways. Older backups without `summary` are counted from `books` when they are parsed as
candidates. Manual exports also carry `origin` (`auto = false`) and `summary`. [Δ] `BackupJson.toJson` puts
`version`, `createdAt`, `origin` and `summary` **before** `books`, so the header reader (§3.4) stops after them.

### 3.7 Transient keys (contract, `SettingsJson.TRANSIENT`)

Add the lower-case substrings `"installid"`, `"restoreoffer"`, `"backupauto"` and `"deviceclass"`.

- They are never exported or restored, so a restored install keeps its own `installId`, a fresh backup schedule and
  its own device class.
- `a.autoBackup` is a typed app setting and *does* travel. Its lower-case form "a.autobackup" does not contain the
  substring "backupauto", which was checked.
- [Δ] The new keys `installId.at` and `backupAuto.owner` are covered by the same substrings.
- [Δ] `TRANSIENT` only filters **our** JSON backup. It does nothing against Android Auto Backup; that is handled by
  ownership checks, not by this list (§3.3).

### 3.8 Settings UI and privacy (UI owner: `ui/settings/BackupPage.kt`, new section "자동 백업")

- **Toggle** "자동 백업 (하루 한 번)" → `AppSettings.autoBackup`.
  - Summary with access: "앱을 지워도 남는 곳에 저장 · 다운로드/ReaderaPlus/backup · 마지막: 2026-09-30 08:12".
  - Summary without all-files access: "모든 파일 접근 권한이 없어 다운로드/ReaderaPlus/backup에 저장합니다. 다시
    설치한 뒤에는 권한을 허용해야 자동으로 찾습니다."
- **Rows:**
  - "지금 자동 백업하기" → `runNow(force = true)` on IO, with the result as a status line;
  - "자동 백업에서 복원" → [Δ] `findCandidates`, including this install's files, as a list (date · counts), then the
    existing confirm, `restore`, and the existing restart prompt;
  - "자동 백업 파일 지우기" → [Δ] with all-files access (or legacy WRITE on API ≤ 29): confirm "자동 백업 파일 {n}개를
    지울까요? 이전 설치의 파일도 함께 지웁니다." → `deleteFiles(others = true)`. Without it (API 30+), only this
    install's own MediaStore rows can be deleted — another install's rows are not visible, and deleting them would need
    per-file consent — so the confirm reads "이 설치에서 만든 자동 백업 파일 {n}개를 지울까요? 이전 설치의 파일은 '모든 파일
    접근'을 허용해야 지울 수 있습니다." → `deleteFiles(others = false)`.
- [Δ] **Privacy note** (text extended): "백업에는 책 제목 · 경로 · 읽은 기록 · 북마크 · 인용문 · 메모 · 리뷰가 들어
  있습니다. 공용 저장소에 있으므로 USB로 연결한 PC나 '모든 파일 접근' 권한이 있는 앱(Android 10 이하에서는 저장소 권한이
  있는 앱)이 읽을 수 있고, 다운로드 폴더를 동기화하도록 설정한 앱이 있으면 그 앱이 올릴 수 있습니다. 이 앱은 인터넷으로 보내지 않습니다."
- **Privacy:**
  - No network, ever.
  - No book content or covers.
  - No encryption: a key kept by the app dies with the uninstall, and a passphrase would defeat "automatic".
  - The file has no media MIME type, so on API 30+ apps without all-files access can't list it. [Δ] On API ≤ 29 any
    app holding READ_EXTERNAL_STORAGE can read `Download/`; the note says so.
  - [Δ] The feature is on by default (the lead's request), so the user is told once, after the first automatic write
    (§3.2 step 5), where the file is and how to turn it off; nothing is written for an install with nothing to lose
    (`isBlank`).

### 3.9 Tests and screenshot checks

**JVM tests (DATA):**
- `data/AutoBackupPolicyTest`:
  - `isDue`, including the clock going back;
  - `wouldEmpty`;
  - `toRotate`;
  - `autoName` / `parseAutoName` round trip and time zone;
  - candidate choice: newest with content, own `id8` excluded, manual files included, malformed ones skipped;
  - [Δ] `pickDefault`: a thin newer backup (score 3) loses to a rich older one (score 400); a rich newer one wins;
    ties → newest; `isBlank`;
  - [Δ] ordering by the name's time, not `lastModified`, for auto files.
- `data/InstallStateTest` (`decide`; [Δ] the pure part of `verify`: a stored `installId.at` ≠ `firstInstallTime`
  → new id, offer state untouched).
- `data/BackupJsonTest`: `origin` / `summary` optional both ways; [Δ] header fields are written before `books`, and the
  header reader returns the same summary as a full parse (also for a file without `summary`).
- `data/SettingsJsonR3Test`: transient filtering.

**Screenshots** (UI owner), at the end of `screenshots.sh` so earlier shots are undisturbed:

1. Push a crafted backup to `/sdcard/Download/ReaderaPlus/backup/readeraplus-auto-0badc0de-20260929-2114.json`. It
   references `/sdcard/Download/sample.epub`, has a position and a bookmark, and holds settings with `readMode =
   PAGED` and `marginLeftDp = 18` with no marker.
2. `adb shell pm clear $PKG`, then `appops set --uid $PKG MANAGE_EXTERNAL_STORAGE allow`, then start the library.

| Shot | Expect |
|---|---|
| `70_restore_offer` | The dialog with the date, counts and location |
| `71_restored` | Tap "복원": the library shows the book as 읽고 있는 책 |
| `72_restored_margins` | Open the book and the reading settings: "좌우 여백 0" (legacy 18 → 40) |
| `73_backup_page` | Settings → 백업 및 복원: the "자동 백업" section |

---

## 4. Owner map (parallel, disjoint files)

### 4.1 Phase 0: CONTRACT commit "R3" (the lead, after R2 merges; everything else compiles against it)

| File (frozen) | Change |
|---|---|
| `engine/Layout.kt` | `PageInfo.lead` + `BREAK_GAP_EM` (§1.3) |
| `settings/ReaderSettings.kt` | `ReadMode`, `ScrollStyle`; `AppSettings.readMode` / `scrollStyle` / `autoBackup`; margin defaults 40/40; `SideMargin` (§1.2, §2.2) |
| `settings/Settings.kt` | Keys `a.readMode`, `a.scrollStyle`, `a.autoBackup`; the `r.marginBase` marker and migration (§2.3) |
| `settings/UserStyles.kt` | `marginBase` and legacy mapping |
| `data/SettingsJson.kt` | Map the three app fields; `SideMargin.KEY` typed; legacy margin rule; `TRANSIENT += installid, restoreoffer, backupauto, deviceclass` |
| `reader/ReaderHost.kt` | KDoc only: "In scroll mode `currentPage` is the visible window (wholly visible lines of one section, content-box coordinates). Never keep a PageInfo/LineInfo across calls." [Δ] Plus: "While the user drags or a fling runs, `currentPage` is the window of the last settled position, and `nextPage` / `prevPage` / `goTo(remember = false)` do not move the text." |
| `docs/ARCHITECTURE.md`, new `docs/R3_INTERFACES.md` | Scroll-mode rules (settle vs frame, STEP on e-ink, the SMOOTH-motion exception to "no animations" limited to scroll SMOOTH, page = the real page of the anchor line); `lead`; margins; the auto-backup location, triggers and gates |
| Stubs in owners' files (R2 style: `// R3 stub (owner: X)` safe defaults, or `TODO("owner: X")` where no one calls first) | `PageRenderer.drawChrome` / `drawBody` / `drawOverlay` / [Δ] `prefetchPages` / `fitFooter`; `render/DeviceClass.kt` ([Δ] with `stamp`); `BookSession.touch(section, shownTo)` + `startsUnit`; `data/AutoBackup.kt` (all `Outcome.DISABLED` / empty list / null / 0; [Δ] `findCandidates`, `pickDefault`, `isBlank`); `data/InstallState.kt` (`ensure` = no-op, [Δ] `verify` = no-op, `offerPending` = false) |
| Tests | `LayoutKeysTest` margin expectations; `SettingsStoreTest`, `UserStylesTest`, `SideMarginTest`, `SettingsJsonR3Test` |
| Checks | `tools/typecheck.sh` and `tools/unittest.sh` green; `tools/snapshot_contracts.sh` refreshes `/opt/tc/contracts` |

### 4.2 Phase 1: five owners in parallel

| Owner | Files (main code and tests) | Work |
|---|---|---|
| **ENGINE_RENDER** | `engine/TypesetPass.kt`; `render/PageRenderer.kt`; `render/DeviceClass.kt` (new); tests `engine/StitchTest.kt`, `engine/PageLeadTest.kt`, `render/DeviceClassTest.kt`, [Δ] `render/FitFooterTest.kt` | §1.3 bookkeeping; §1.8 methods ([Δ] `drawImagePeek`, batched `prefetchPages`, `fitFooter`; `drawLine` untouched); §1.11 `DeviceClass` with the stamp. `LayoutGoldenTest` stays green. The paged `draw()` [Δ] and `drawLine()` stay byte-identical. |
| **READER_CORE** | `reader/ReaderActivity.kt`, `reader/PageView.kt`, `reader/ScrollMath.kt` (new), `reader/ScrollReader.kt` (new), `reader/BookSession.kt`, `reader/ReaderMenus.kt`, `reader/ReaderFormat.kt`, `reader/EndPanel.kt` (only if needed); tests `reader/ScrollMathTest.kt`, `reader/BookSessionHelpersTest.kt` | §1.4–§1.7, §1.9, §1.10; triggers from §3.2 and §3.3 in the reader ([Δ] `ensure` in `afterOpen`); [Δ] PageView accessibility actions; the ⋮ toggle starts the `DeviceClass` probe; the delegation checklist |
| **EXTRAS** | `reader/extras/ReadingSettingsPopup.kt` (only this file changes); verify, without edits, `SelectionController`, `TtsController`, `SearchPanel`, `ContentsDialog`, `InfoDialogs` | The "넘기는 방식" row (§1.2, [Δ] starts the `DeviceClass` probe on IO when SCROLL is chosen and `cached()` is null); the side-margin stepper (§2.4, [Δ] live-region value); report any extras assumption the virtual page breaks ([Δ] in particular: TTS `follow` / `onRange` / `navRestart` against the §1.10 motion rules) |
| **DATA** | `data/AutoBackup.kt` (new), `data/InstallState.kt` (new), `data/Backup.kt` (`snapshotJson`, origin, summary), `data/BackupJson.kt`; tests `data/AutoBackupPolicyTest.kt`, `data/InstallStateTest.kt`, `data/BackupJsonTest.kt` | §3.1–§3.3, §3.5, §3.6 |
| **UI** | `ui/library/LibraryActivity.kt`, `ui/library/AutoRestorePrompt.kt` (new); `ui/settings/PageTurningPage.kt`, `ui/settings/BackupPage.kt`, `ui/settings/MainPage.kt`; `tools/ci/screenshots.sh`; their tests | `InstallState.ensure` in `onCreate`; offer ([Δ] `pickDefault`, "다른 백업 보기", late-answer line), scan hold and idle backup trigger (§3.2, §3.4); [Δ] the one-time "자동 백업을 저장했습니다" status line; settings rows (§1.2 [Δ] incl. disabled rows and the probe on switch, §2.4, §3.8); shots (§1.15, §3.9) |

- **Shared APIs across owners are only the contract stubs above.** READER_CORE uses `PageRenderer`, `DeviceClass`,
  `AutoBackup` and `InstallState`. UI uses `AutoBackup`, `InstallState`, `DeviceClass` and `SideMargin`. EXTRAS uses
  `SideMargin`. Nobody edits another owner's file.
- **Integration (lead):**
  1. typecheck plus the full unit suite;
  2. a `[screens]` CI run;
  3. a Comet and phone device pass (§5.2);
  4. the RAPerf gates (§1.1).

---

## 5. Risks and device checks

### 5.1 Risks

| # | Risk | Mitigation |
|---|---|---|
| 1 | **A `lead` bookkeeping bug** would show only as wrong gaps in scroll mode; paged output can't change because `lead` is write-only there | `StitchTest` property test and `PageLeadTest`; `LayoutGoldenTest` unchanged; the PBB rule "at the top of any non-first page" (§1.3) |
| 2 | **Collision with R2**, which is editing `ReaderActivity` (2,900 lines), `PageView` and `BookSession` now | Land strictly after R2 merges; READER_CORE owns all of `reader/*.kt` for R3 |
| 3 | **Paged regression** through `showPage` or `PageView` | [Δ] No extraction at all: the paged `showPage`, `draw()` and `drawLine()` are byte-identical below a top-of-function branch; RAPerf open and turn gates; CI paged shots compared |
| 4 | **UI-thread image decode** during a phone fling | `drawBody` never decodes; images are prefetched ±1 viewport and preloaded for the first viewport; the worst case is a one-frame outline |
| 5 | **Extras caching the virtual page** | `ReaderHost` KDoc; EXTRAS verification pass; `SelectionController`'s origin key is `layout.config` + view size, identical in both modes |
| 6 | **A visible section evicted** from the LRU | `touch(first, last)` range, [Δ] updated on every visible-range change including mid-fling; prefetch bounded by the LRU size; slots re-peek all on every store |
| 7 | **E-ink misdetection** (a phone getting STEP, or the Comet getting SMOOTH) | Build heuristic, then the vendor probe, cached [Δ] with a device stamp (an Auto-Backup copy from another device is ignored); the probe starts when SCROLL is chosen, so a phone rarely sees the STEP fallback; unknown resolves to STEP; motion changes only at ACTION_DOWN; the user's override always wins |
| 8 | **The brightness strip blocks a scroll starting at the left edge** when enabled | Off by default; the summary explains it |
| 9 | **Margin migration moves a deliberate 18 to 40**; one recount per book | Accepted (the user asked for 40 as the base); partial counts resume in the background |
| 10 | **Auto-backup privacy** (shared storage is readable over USB and by all-files apps) | Off switch, delete row, note, no network, no book content |
| 11 | **Offer false positives or negatives** | Empty-prefs rule decided at the first `onCreate` (library) / `afterOpen` (reader); Auto Backup restores prefs and so settles; [Δ] writes while pending use this install's own id and `pickDefault` protects a richer older backup; "다른 백업 보기" lets the user choose; the manual SAF restore always works |
| 12 | **MediaStore differences** (API 29 vs 30+, OEM e-ink firmwares that strip the all-files screen) | `File` route first, MediaStore as fallback, both in one folder; `NO_LOCATION` on API ≤ 28 without WRITE; `StorageAccess.request` already tries both intents |
| 13 | **Background backup competing with opening a book** on the A53 | Background priority; `busy()` checked between phases [Δ] and between the snapshot's queries and every 256 KB written; `ReaderPresence.inFront` set in `onCreate`; WAL reads never block |
| 14 | **The SMOOTH "animation" exception** to the no-animation rule | Written into the ARCHITECTURE rules as "user-driven motion only, scroll SMOOTH only, never on e-ink by default" |
| 15 | [Δ] **TTS pulling the text against the finger** (utterance-start `follow` during a drag or a long fling) | `tts.onUserNavigated()` at drag start; host ignores `nextPage` / `prevPage` / `goTo(remember = false)` while `userMoving()`; the virtual page is the settled snapshot during motion |
| 16 | [Δ] **Android Auto Backup copying device-local keys** to a new install / device (same `installId` → the new install rotates away the old install's backup files; a phone's "lcd" class on a Boox) | `InstallState.verify` against `firstInstallTime`; `backupAuto.owner`; `DeviceClass` stamp |
| 17 | [Δ] **Dropped image prefetches** (`LatestTaskRunner` keeps only the newest task) | One batched `prefetchPages` per settle; "missing" uses the same known-failure test as `needsDecode`, so no decode loop |
| 18 | [Δ] **Position drift** (anchor recomputed after each relayout, or read before a fling settled) | Sticky anchor for OPEN / RELAYOUT / SWITCH; `stopMotion()` settles before any reader of `anchor`; CANCEL settles |

### 5.2 Device checks

**Comet, paged mode (must be unchanged):**
- RAPerf "open … first page" for the cached 14.8 MB TXT stays within +10 ms of the R2 baseline, and "turn N ms"
  within noise.
- `am start -W` for the library stays within +5 %.
- Text column is 560 px; "좌우 여백" shows 0.
- The cadence refresh behaves as before.

**Comet, SCROLL mode (AUTO = STEP):**
1. Tap, volume and remote steps: one update each; the cut line becomes the top; step time within +2 ms of a paged
   turn.
2. Drag: no frames during the drag, then one update line-aligned on release. Fling: exactly one screen.
3. No ghosting beyond a normal turn; `dumpsys gfxinfo` flat at rest.
4. TTS follow; selection in both sections at a seam; image EPUB (no decode stall, outline for at most one frame).
5. A chapter seam shows the gap, and a TXT 30k split joins seamlessly.
6. The end panel opens only on "next".
7. Switch mode during TTS and inside an open popup.
8. [Δ] TTS reading across a chapter seam with both sections on screen: no motion until the sentence leaves the
   visible lines; a drag while speaking is never pulled back by `follow`.
9. [Δ] Pull the notification shade down in the middle of a drag, then come back: the position is where the finger
   left it (SMOOTH) or unchanged (STEP), and the saved position matches.

**Phone (60 and 120 Hz), SCROLL (AUTO = SMOOTH):**
1. `dumpsys gfxinfo <pkg> framestats` over 10 flings: janky frames < 1 %, p95 under the vsync interval.
2. Fling through a TXT of 5k-char episodes: no blocked seams in normal reading.
3. `dumpsys meminfo` within ±1 MB of paged mode.
4. Fast repeated taps chain steps without lag.
5. Rotation keeps the top line [Δ] — also when rotated in the middle of a fling, and after five rotations in a row
   (no drift).
6. A search hit lands 25 % down.
7. [Δ] TalkBack on: the page announces itself as scrollable; "scroll forward/back" steps one screen; the "넘기는 방식"
   row, the ⋮ toggle and the disabled "세로 스와이프" row are announced correctly; the margin stepper speaks "−10".
8. [Δ] A book whose first spine items are empty or broken covers: no blank screens between them.
9. [Δ] An EPUB with several pictures per screen: after a fast fling every picture appears within one frame of the
   settle (batched prefetch), and a deliberately broken image stays an outline without repeated redraws
   (`gfxinfo` flat).

**Auto-backup:**
1. On the Comet with all-files access: read, leave the reader and wait 5 s → a file appears in
   `Download/ReaderaPlus/backup`. A second leave on the same day writes nothing.
2. Uninstall, reinstall and grant access → the offer appears → restore brings back positions, bookmarks, settings and
   margins.
3. On a phone without all-files access: the MediaStore file exists, and after a reinstall + grant → offer.
4. `pm clear` → offer.
5. Upgrade in place from R2 → no offer, and backups start.
6. Set the clock back 2 days → the next trigger writes.
7. Airplane mode changes nothing (no network use).
8. Dialogs appear without animation on e-ink.
9. [Δ] GMS phone with Google backup on: uninstall, reinstall (Google restores the prefs) → a **new** `installId`
   (new `id8` in the next file name), the previous install's files are not rotated away, and `deviceClass` is re-probed.
10. [Δ] Phone that never grants all-files access: auto files are still written (MediaStore) while the offer is pending.
11. [Δ] Two older installs' backups (one rich and old, one thin and new): the offer shows the rich one;
    "다른 백업 보기" lists both.
12. [Δ] Open a book within 1 s of leaving the reader (backup running): RAPerf "open … first page" within noise.

---

## [Δ] Changelog (adversarial review, 2026-09-30)

Paged mode and the open path
1. `PageRenderer.drawLine` is no longer given a `decode` default parameter (it would change the paged call site's
   bytecode); `drawBody` has its own peek-only image helper. `draw()` and `drawLine()` stay byte-identical (§1.1, §1.8).
2. `showPage`'s bookkeeping is not extracted; scroll mode has its own `onScrollSettled`, so the paged body stays
   byte-identical below the branch (§1.1, §1.10, risk 3).
3. `InstallState.ensure` moved out of `ReaderActivity.onCreate` into `afterOpen()`: nothing new before the first page.
   `applyReadMode()` returns at once in PAGED. The stale "extra `TypesetPass` constructor argument" was removed (§1.1,
   §1.10, §3.3).

Scroll engine and geometry
4. `StitchTest` fixed: the reference is the H = 10⁷ layout stitched with the same rule (it still breaks at every
   `pageBreakBefore`), images limited to ≤ 300 px scaled height (heights are capped by `pageH`), tolerance 0.5 px
   instead of 0.05 px (float accumulation), plus `lead ≥ 0` (§1.3).
5. Empty pages / sections collapse to height 0 (no lead, no unit gap), so broken covers do not scroll as blank screens;
   `MAX_STRIPS` bounds work per call but never blocks progress (§1.4, §1.5).
6. Sticky anchor: OPEN / RELAYOUT / SWITCH settles keep the placed offset, so repeated rotations and relayouts do not
   drift backwards; `ScrollMath.anchorAfter` makes it testable (§1.4, §1.6).

Memory, allocation, prefetch
7. LRU protection `touch(first, last)` is updated on every visible-range change, also mid-fling (a stale range let a
   prefetch evict the section on screen); prefetches are bounded by `MAX_CACHED` minus the visible sections, motion
   direction first (§1.6, §1.9).
8. Layout slots: 8 instead of 4, all re-peeked on every store, so an evicted (possibly multi-MB) layout is never pinned
   (§1.6).
9. Image prefetch batched into one `prefetchPages` call per settle: the prefetcher (`LatestTaskRunner`) keeps only the
   newest task, so per-page calls dropped all but the last; "missing" uses the known-failure test, so a broken image
   cannot loop (§1.6, §1.8).
10. `drawChrome` is allocation-free: `drawStatus` ellipsizes an over-long footer on every draw, so the scroll decor is
    pre-fitted once by `fitFooter` (§1.8). Per-page highlight lists share `emptyList()` and use binary search (§1.6).

Position, selection, TTS, search, links
11. Mode switch uses `anchor`, not `currentPosition()` (the virtual page start, possibly another line or section);
    round trips are exact in both directions; `stopMotion()` settles before anyone reads `anchor` (switch, relayout,
    rotation, `onPause`) and ACTION_CANCEL settles a drag (§1.6, §1.7, §1.10).
12. TTS can no longer pull the text against the finger: `onUserNavigated()` at drag start, the host ignores
    `nextPage` / `prevPage` / `goTo(remember = false)` while the user is moving, and the virtual page is the settled
    snapshot during motion. A spoken sentence already visible below a seam causes no jump (§1.6, §1.10, risk 15).
13. Links in the lower section of a seam are tappable (`handleTap` → `focusAt`) and the link test uses the virtual
    page's layout, not `curLayout`; the search highlight removal at drag start also invalidates the per-page caches;
    `isOnCurrentPage` uses one frame of reference (§1.10).
14. A relayout keeps drawing the frozen snapshot instead of blanking (no extra e-ink update); the vp uses the same
    rounded strip tops as the draw (§1.6).
15. `flushTurns` keeps unapplied steps in the backlog on NEED_SECTION (§1.10). In STEP, chrome closes with the release
    frame (one e-ink update per gesture).

Device class, accessibility
16. `DeviceClass` probe starts when SCROLL is first chosen; motion changes apply at the next ACTION_DOWN; the cached
    class carries a device stamp (Android Auto Backup copies prefs between devices). Forced SMOOTH on e-ink throttles
    by not invalidating (§1.2, §1.11).
17. Accessibility: PageView exposes scroll-forward/back actions in scroll mode; the unused "세로 스와이프" row is
    disabled rather than only dimmed; stepper values are live regions; VelocityTracker lifecycle fixed (§1.2, §1.7,
    §2.4).
18. Margins: the settings page has no "페이지 여백" row, so the new stepper goes first in "페이지 표시" (§2.4).

Auto-backup
19. Android Auto Backup restores the settings prefs including device-local keys: a copied `installId` would make the
    new install rotate away the previous install's files. `InstallState.verify` (off the main thread) checks it
    against `firstInstallTime`; `backupAuto.*` carries an owner; `offerPending` treats "unknown" as pending
    (§3.1, §3.3, §3.7, risk 16).
20. The "offer pending → write nothing" gate is removed. It turned the safety net off forever for phones that never
    grant all-files access. Writes use this install's own id, blank installs write nothing (`isBlank`), and the offer
    picks with `pickDefault` (newest with at least half the best score) plus "다른 백업 보기", so a thin newer backup
    cannot hide a rich older one (§3.2, §3.4).
21. Candidates: header-only parsing with `android.util.JsonReader` (header written before `books`), ordering by the
    file name's time rather than `lastModified`, up to 5 headers; a line in the offer when this install already has
    history (§3.4, §3.6).
22. `busy()` is also checked between the snapshot's queries and every 256 KB written; raw prefs are hashed in sorted
    order; the transient heap cost is stated (§3.2).
23. Privacy: the note covers Android ≤ 10 (any storage-permission app) and folder-sync apps; a one-time notice after
    the first automatic write; deleting other installs' files needs all-files access on API 30+, and the dialog says
    so (§3.2, §3.8).
24. New checks: tests for all of the above (§1.14, §3.9), screenshot `69_scroll_again`, device checks for TTS at a seam,
    CANCEL mid-drag, rotation mid-fling, TalkBack, empty spine items, batched images, Google-restore reinstall,
    no-access phones, candidate choice, and opening a book while a backup runs (§5.2); risks 15–18 (§5.1).
