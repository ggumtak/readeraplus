# Quote colours and styles (인용문 색 표시): design spec

Status: design only (read-only pass). Nothing in the repo was edited. Sources read: `render/PageRenderer.kt`
(`drawHighlights` after Wave 0 band geometry), `render/Render.kt` (`Highlight`, `HighlightKind`, `PageDecor`),
`render/Eink.kt`, `engine/Typesetter.kt` (`bandTop` / `bandBottom`), `data/Models.kt`, `data/LibrarySchema.kt` (v2
`quotes.style`), `data/LibrarySql.kt`, `data/BookRows.kt`, `data/Library.kt`, `data/Backup.kt`, `data/BackupJson.kt`,
`data/SettingsJson.kt`, `reader/ReaderActivity.kt` (`reloadAnnotations`, `buildDecor`, `sameDecor`, `setHighlights`),
`reader/extras/SelectionController.kt`, `reader/extras/ContentsDialog.kt`, `reader/extras/ExtrasUi.kt` (`QuoteCache`),
`docs/R2_INTERFACES.md`, `docs/ARCHITECTURE.md`. Screens: ReadEra premium sheet (인용문 색 표시), ReadEra selection bar
(`readera/7d420a01`: 복사 · 인용문 with a grey dot · 번역 · 사전 · 더보기), ours (`ours/17_selection.png`), and
`ui/audit.md` item 13 (one row of 5 in the selection popup). The notes hub spec (all quotes in one place) is written
separately. This spec gives that hub the shared pieces it needs (swatch, palette, recolour, filter, export tag).

---

## 0. Decisions in one screen

| Question | Decision |
|---|---|
| Palette | Six styles: **노랑 · 초록 · 파랑 · 빨강 · 보라 · 밑줄**. Stored as `quotes.style` 0..5. Style 0 (노랑) is the default, and every quote made before this change already has it. |
| 취소선 | **Not offered.** It reads as "deleted text", it looks the same as EPUB `<s>`/`<del>` (`RunStyle.strike` draws that exact line), and it hurts reading. 보라 = an outline box takes its slot. A wavy underline is declined too: it needs a Path per draw and turns to mush at 1 px on 16 greys. |
| Two renderings | **색 (colour)**: pastel fills, as on phones. **흑백 무늬 (ink)**: each style is a distinct grey level from the 16-level ramp and/or a line pattern. Colour fills alone do not work on the Comet. Their luminance lands on 199–223 (grey levels 12–13 for all five, see §2.3), so five colours would look like one grey. |
| Which one | New app setting `AppSettings.highlightLook`: 자동 (default) / 색 / 흑백 무늬. 자동 = ink when the screen is likely e-ink (build brand list with no reflection, OR the vendor e-ink probe already found a hook), else colour. The probe runs on IO (library after its first frame, reader `afterOpen`), never on the open path. |
| Night (invert) | Colour mode uses a dark fill table with white text (≥ 8:1). Ink mode inverts the grey (`255 − v`); lines use the text colour. |
| Where to pick | Selection popup (one row of 5, per `ui/audit.md` #13): **인용** shows a swatch in the **last-used** style. Tap = save in that style and close (one tap, as today). **Long-press 인용** or **⋮ → "색 골라 인용…"** = palette first, then save. Long-press an **existing quote** on the page = the quote popup, whose top row is the palette (current style ringed). Tapping a swatch recolours the quote on the spot. |
| Rows | ContentsDialog 인용문 rows and notes-hub rows get a swatch column on the right. Tap = palette, pick = recolour. The long-press menu gets "색 바꾸기". Filter chips (전체 / per style with counts) appear only when a book has ≥ 2 styles. The hub adds batch recolour. |
| Export | Plain-text share and hub export tag each quote with `[초록]` **only when the exported set holds ≥ 2 styles**, so a one-colour export stays exactly as today. A single-quote share is never tagged. The backup JSON carries `"style"` (omitted when 0). HTML export (if the hub has one) uses `<mark>` with the day colour. |
| Render cost | One preallocated fill `Paint` per style, plus the existing `line` / `outline` paints. Colours are recomputed only when `QuoteLook.generation` changes (a volatile int compare per draw that has highlights). A dashed underline is a `drawRect` loop, with no `PathEffect`. Nothing is allocated per draw. The per-turn work is unchanged (styles ride in the `Highlight` objects built once in `reloadAnnotations`). |
| Contract | `Quote.style` (R2 deferred it to R3) and `AppSettings.highlightLook` are frozen-file changes: see §8 CONTRACT REQUESTS. `quotes.style` is already in the v2 schema, so there is **no DB version bump**. |

---

## 1. What exists today (facts the design rests on)

- `PageRenderer.drawHighlights`: per text line, per highlight, it fills the glyph band
  `[LineGeometry.bandTop, bandBottom]` (baseline − 0.95 em … baseline + 0.30 em, clamped to the line box). QUOTE =
  `grey(0xD8)` fill + 1 px underline at `underlineY = round(baseline + max(2px, 0.12em))`. SELECTION = `0xA8`
  fill. SEARCH = `0xC0` fill + 1 px outline. TTS = `0xE0` fill + 2 px underline. `grey()` inverts for night. An image
  inside a highlight gets a 1 px outline 2 px outside the picture. Fills use one shared `fill` Paint whose colour is
  set per rect (no allocation).
- Each highlight is drawn as fill-then-line in list order. Quotes come first in `buildDecor` (`quotesBySection`), then
  the owner highlights (selection, search, TTS). So a later fill **paints over an earlier highlight's underline**:
  selecting inside a quote hides its underline.
- `Highlight(start, end, kind)` is a plain class. Callers: `ReaderActivity.reloadAnnotations` (map per section),
  `ContentsDialog.refreshQuoteHighlights` (owner "quotes" → `quotesBySection`), `SelectionController`, `SearchPanel`,
  `TtsController`.
- `ReaderActivity.sameDecor` compares highlights by `start`, `end` and `kind` only. `setHighlights` → `refreshDecor(
  onlyIfChanged = true)`. **A recolour that doesn't also change `sameDecor` will never repaint.**
- `LibrarySchema` v2 has `quotes.style INTEGER NOT NULL DEFAULT 0` ("0 = the default grey fill; T2-3 adds the
  others"). `Quote` has no `style` field ("deferred to R3"). `SELECT_QUOTES`, `SELECT_ALL_QUOTES`, `INSERT_QUOTE`
  (7 args, asserted by `LibrarySqlTest`), `BookRows.quote`, `BackupQuote` and the restore merge all ignore it.
- Selection popup: a 5-column grid with 9 actions (복사, 인용, 메모, 공유, 문단, 검색, 사전·번역, 웹 검색, 여기서 읽기).
  인용 saves, clears and toasts "인용문에 저장했습니다". A long-press on an existing quote selects its range with
  `editingQuote` set, and the popup offers 메모 / 인용 삭제.
- `Eink.vendorName()` probes by reflection (Bigme xrz → Rockchip → Onyx → NTX) the first time anything asks. It is
  called from the settings/about pages (IO), from `fullRefresh` (main, only with a cadence on) and from
  `prepareReaderView` (only when `einkMode != SYSTEM`). By default, nothing probes during reading.
- Raw prefs (`Settings.raw()`) are all backed up, except keys containing a `SettingsJson.TRANSIENT` substring.
  `a.*` raw keys without a typed mapping travel as "unmapped" app prefs.

---

## 2. The palette

### 2.1 Styles (stored values never change meaning; new ones are appended)

| value | name (UI, export tag) | intent | colour mode, day (on #FFFFFF, black text) | colour mode, night (on #000000, white text) | colour-mode line |
|---|---|---|---|---|---|
| 0 | 노랑 | default, every legacy quote | `0xFFFFE37A` (16.5:1) | `0xFF5E4F00` (8.1:1) | none |
| 1 | 초록 | | `0xFFB9E4A2` (14.7:1) | `0xFF24502A` (9.3:1) | none |
| 2 | 파랑 | | `0xFFAFD3F5` (13.5:1) | `0xFF1D4570` (9.8:1) | none |
| 3 | 빨강 | "important" | `0xFFFFB0AB` (12.1:1) | `0xFF6E2626` (10.7:1) | none |
| 4 | 보라 | | `0xFFD9C2F2` (12.9:1) | `0xFF4D3470` (10.3:1) | none |
| 5 | 밑줄 | no fill, for people who dislike fills | none | none | solid, `t2`, text colour |

The ratios are WCAG contrast of the text colour over the fill, computed with the sRGB relative-luminance formula; the §10 test recomputes them. The rule: day ≥ 12:1,
night ≥ 8:1, so a highlighted line never reads worse than plain text on a dim phone. Fills are **opaque** and drawn
under the text. There is no alpha blending (cheaper, and the result doesn't depend on what is under it).

### 2.2 Ink rendering (흑백 무늬): 16-grey e-ink, and phones that choose it

Grey levels are **exact multiples of 0x11**, so they are one panel level whether the driver maps with `v >> 4` or
`round(v / 17)`. Today's `0xD8` / `0xC0` / `0xE0` fall between levels, and with REGAL/HD waveforms an in-between level
can come out dithered.

| value | name | fill (day) | fill (night = 255 − v) | line (text colour) | how it reads on the Comet |
|---|---|---|---|---|---|
| 0 | 노랑 | `0xFFDDDDDD` | `0xFF222222` | solid underline `t1` | today's quote look (0xD8 → snapped to 0xDD), so legacy quotes don't change |
| 1 | 초록 | `0xFFEEEEEE` | `0xFF111111` | **dashed** underline `t2` (dash `round(3dp)`, gap `round(2dp)`) | lightest grey + dashes |
| 2 | 파랑 | `0xFFCCCCCC` | `0xFF333333` | none | plain medium-dark band |
| 3 | 빨강 | `0xFFBBBBBB` | `0xFF444444` | solid underline `t2` | darkest band + bold line: the strongest mark |
| 4 | 보라 | none | none | **box** `t1` around each line fragment's glyph band | outline only |
| 5 | 밑줄 | none | none | solid underline `t2` | underline only |

`t1 = max(1px, round(0.5dp))`, `t2 = max(2px, round(1dp))`. On the Comet (720 px / 360 dp = density 2) that is
1 px / 2 px. On a density-3 phone it is 2 px / 3 px. Every line is pixel-aligned (`Math.round`), as the footer battery
already is.

Distinctness check (every pair must differ in a property visible at arm's length on 16 greys: fill ≥ 2 levels apart,
or a different line kind):

- 노랑 / 초록: fill 13 vs 14 **and** thin solid vs dashed.
- 노랑 / 파랑: underline vs none.
- 파랑 / 빨강: none vs bold line (and one level).
- 보라: the only box.
- 밑줄: the only line without a fill.
- 빨강 / 밑줄: fill 11 vs none.

The transient highlights stay distinguishable:

- SEARCH has a fill **and** a box; 보라 has no fill.
- TTS is a light fill + solid `2px`; 초록 is dashed; 밑줄 has no fill.
- SELECTION is the darkest fill (0xA8) and has handles.

In Bigme "fast" (A2-like, dithered) modes, fills turn into dot screens, but every style except 파랑 still has its line
and 파랑's screen is the densest of the fills. The styles stay apart even there.

Optional, recommended to RENDER (not needed for this feature): snap SEARCH `0xC0 → 0xBB`, TTS `0xE0 → 0xEE`,
SELECTION `0xA8 → 0xAA` for the same level-exactness.

### 2.3 Why a separate ink mode (the numbers)

Luminance (Y8 = 0.299R + 0.587G + 0.114B) of the colour fills: 노랑 223, 초록 208, 파랑 204, 빨강 199, 보라 206 → panel
levels 13, 12, 12, 11, 12. On the Comet in colour mode, four of the five would be the same grey. Any pastel palette
readable under black text lands in this narrow band, so no choice of colours fixes this. Distinct looks need patterns.

### 2.4 Mode selection (`AppSettings.highlightLook`, see §8)

- `HL_LOOK_AUTO = 0` (default): ink when `EinkScreen.likely()`, else colour.
- `HL_LOOK_COLOR = 1`: always colour. For Kaleido 3 colour e-ink (Innospace also sells the Jigu), or a user who wants
  colours anyway.
- `HL_LOOK_INK = 2`: always patterns. Also a valid choice on a phone (colour-blind users, or prints/screenshots that
  go to mono).

`EinkScreen.likely()` = `byBuild || Eink.vendorIfProbed() != null`:

- `byBuild` is a lazy val from `Build.MANUFACTURER / BRAND / MODEL`, lower-cased, containing any of `innospace`,
  `bigme`, `onyx`, `boox`, `crema` (Yes24 Crema: same company as Innospace), `meebook`, `boyue`, `likebook`,
  `pocketbook`, `tolino`, `kobo`, `hyread`, `inkpalm`, `moaan`, `ratta` (Supernote). This is string work only: no
  reflection, microseconds, safe on the main thread. It deliberately leaves out Hisense (the brand also makes LCD
  phones). The vendor probe or the setting covers Hisense.
- `Eink.vendorIfProbed()` is a **new non-probing accessor** (RENDER): it returns `vendor` only when `probed` is already
  true, else null. It never runs reflection on the calling thread.
- The probe runs off the main thread in two places: `LibraryActivity` after its first frame (`ReaderIo.launch {
  EinkScreen.probe() }`), and `ReaderActivity.afterOpen` (the same call; it covers opens from a file manager). Both
  are after the first frame or first page, so library cold start and the open path are unchanged. A useful side
  effect: the first `fullRefresh` no longer pays for the reflection on the main thread.
- If the probe flips `likely()` (only possible on an e-ink device missing from the brand list), `QuoteLook` bumps its
  generation. The **next** page draw uses the ink looks. There is no forced redraw (rule: no idle redraws).
- Nothing is persisted: the brand check costs nothing, and a persisted flag would need a `TRANSIENT` entry in the
  frozen `SettingsJson` so it doesn't travel to another device in a backup.

The settings row reads "인용문 색 표시 · 자동 (이 기기: 흑백 무늬)" / "… (이 기기: 색)". The part in parentheses shows what
자동 resolved to.

---

## 3. Rendering (RENDER: `render/QuoteStyles.kt` new, `render/Render.kt`, `render/PageRenderer.kt`, `render/Eink.kt`)

### 3.1 Pure tables: `render/QuoteStyles.kt` (unit-tested; no Android types except `Int` colours)

```kotlin
/** Quote highlight looks = `quotes.style`. Stored values never change meaning; new styles are appended. */
object QuoteStyles {
    const val YELLOW = 0; const val GREEN = 1; const val BLUE = 2; const val RED = 3; const val PURPLE = 4
    const val UNDERLINE = 5
    const val COUNT = 6
    /** Largest value a backup may carry (a newer app's styles survive a round trip; they draw as YELLOW here). */
    const val MAX_STORED = 15

    const val LINE_NONE = 0; const val LINE_THIN = 1; const val LINE_THICK = 2; const val LINE_DASHED = 3
    const val LINE_BOX = 4

    fun of(stored: Int): Int = if (stored in 0 until COUNT) stored else YELLOW
    fun label(style: Int): String            // 노랑 초록 파랑 빨강 보라 밑줄
    fun tag(style: Int): String = "[" + label(style) + "]"

    fun colorFill(style: Int, night: Boolean): Int   // ARGB; 0 = no fill (§2.1)
    fun colorLine(style: Int): Int                   // LINE_NONE except UNDERLINE → LINE_THICK
    fun inkGrey(style: Int): Int                     // 0..255 day level; -1 = no fill (§2.2)
    fun inkLine(style: Int): Int                     // §2.2
    /** Fill used where lines vanish (page thumbnails ≤ 1/4 scale): the style's fill, or 0xCC for fill-less styles. */
    fun thumbGrey(style: Int): Int
}
```

The tables are `IntArray` constants, with no allocation per call.

`QuoteLook` (same file, or `render/QuoteLook.kt`):

```kotlin
object QuoteLook {
    @Volatile var generation: Int = 0; private set
    /** READER_A's applyAppSettings and SettingsActivity pass AppSettings.highlightLook; bumps generation when ink() changes. */
    fun setMode(mode: Int)
    /** Effective: INK → true, COLOR → false, AUTO → EinkScreen.likely(). Any thread, no reflection. */
    fun ink(): Boolean
    /** EinkScreen.probe() calls this after the vendor probe (IO): bumps generation if ink() changed. */
    internal fun onProbed()
}
internal object EinkScreen {
    fun guess(manufacturer: String?, brand: String?, model: String?): Boolean   // pure, unit-tested
    val byBuild: Boolean by lazy { guess(Build.MANUFACTURER, Build.BRAND, Build.MODEL) }
    fun likely(): Boolean = byBuild || Eink.vendorIfProbed() != null
    fun probe() { runCatching { Eink.vendorName() }; QuoteLook.onProbed() }      // IO only
}
```

### 3.2 `Highlight` gets a style

```kotlin
class Highlight(val start: Int, val end: Int, val kind: HighlightKind, val style: Int = 0)
```

The default keeps every current caller compiling. `style` is read only for `QUOTE`.

### 3.3 `PageRenderer` changes

Preallocated fields:

```kotlin
private val quoteFill = Array(QuoteStyles.COUNT) { Paint().apply { style = Paint.Style.FILL } }
private val quoteHasFill = BooleanArray(QuoteStyles.COUNT)
private val quoteLine = IntArray(QuoteStyles.COUNT)
private var lookGen = -1
private val t1 = maxOf(1f, Math.round(0.5f * density).toFloat())
private val t2 = maxOf(2f, Math.round(density).toFloat())
private val dashOn = maxOf(2f, Math.round(3f * density).toFloat())
private val dashPeriod = dashOn + maxOf(1f, Math.round(2f * density).toFloat())

private fun syncLook() {                 // called at the top of drawHighlights; a volatile int compare when nothing changed
    val g = QuoteLook.generation
    if (g == lookGen) return
    lookGen = g
    val ink = QuoteLook.ink()
    for (s in 0 until QuoteStyles.COUNT) {
        if (ink) {
            val v = QuoteStyles.inkGrey(s)
            quoteHasFill[s] = v >= 0
            if (v >= 0) quoteFill[s].color = grey(v)          // grey() already inverts for night
            quoteLine[s] = QuoteStyles.inkLine(s)
        } else {
            val c = QuoteStyles.colorFill(s, invert)
            quoteHasFill[s] = c != 0
            if (c != 0) quoteFill[s].color = c
            quoteLine[s] = QuoteStyles.colorLine(s)
        }
    }
}
```

`drawHighlights` becomes **two passes per text line**, so a later fill never hides an earlier highlight's line:

1. **Fills.** For each overlapping `h`: QUOTE → `if (quoteHasFill[st]) canvas.drawRect(l, t, r, bt, quoteFill[st])`.
   SELECTION, SEARCH and TTS keep their fills through `fillRect(grey(…))` as today.
2. **Lines.** For each overlapping `h`:
   - QUOTE → `drawQuoteLine(quoteLine[st], l, t, r, bt, uy)`.
   - SEARCH → outline, as today.
   - TTS → 2 px underline, as today.
   - SELECTION → nothing.

Here `st = QuoteStyles.of(h.style)` and `uy = underlineY(top + ln.baseline, em)` (unchanged). The overlap test and the
`positions()` call happen once per line, as now. The inner loop runs twice, over a list that is 0–5 items long in
practice.

```kotlin
private fun drawQuoteLine(kind: Int, l: Float, t: Float, r: Float, b: Float, uy: Float) {
    when (kind) {
        QuoteStyles.LINE_THIN -> canvas.drawRect(l, uy, r, uy + t1, line)
        QuoteStyles.LINE_THICK -> canvas.drawRect(l, uy, r, uy + t2, line)
        QuoteStyles.LINE_DASHED -> {
            // Dashes on a grid anchored at x = 0: adjacent fragments and quotes line up (clean on e-ink).
            var x = Math.floor((l / dashPeriod).toDouble()).toFloat() * dashPeriod
            while (x < r) {
                val a = maxOf(x, l); val e = minOf(x + dashOn, r)
                if (e > a) canvas.drawRect(a, uy, e, uy + t2, line)
                x += dashPeriod
            }
        }
        QuoteStyles.LINE_BOX -> { rect.set(l + 0.5f, t + 0.5f, r - 0.5f, b - 0.5f); canvas.drawRect(rect, outline) }
    }
}
```

Pull the dash-grid arithmetic out as `internal object DashMath { fun firstDash(l, period): Float }`, so it is
JVM-testable. `outline` is the existing 1 px stroke paint. On a density-3 phone the box line is `onePx`, which is
fine; a `t1`-wide stroke paint is optional. `line` / `outline` already carry `fg` (white at night). **The only
per-draw cost added is the second loop plus the dash rects (≈ 30 `drawRect` for a full-width dashed line). Nothing
is allocated.** The image-in-quote outline stays as it is.

Page thumbnails (another spec): draw QUOTE bands with `QuoteStyles.thumbGrey` / `colorFill` only (lines disappear
below 1/4 scale).

---

## 4. Data (DATA: `LibrarySql.kt`, `BookRows.kt`, `Library.kt`, `BackupJson.kt`, `Backup.kt`)

No schema change: v2 already has the column.

```kotlin
// LibrarySql
const val SELECT_QUOTES = "SELECT id, book_id, section, start_offset, end_offset, quote_text, note, created_at, style " +
    "FROM quotes WHERE book_id = ? ORDER BY section, start_offset, end_offset, id"
const val SELECT_ALL_QUOTES = "SELECT id, book_id, section, start_offset, end_offset, quote_text, note, created_at, style " +
    "FROM quotes ORDER BY book_id, section, start_offset, end_offset, id"
/** Args: book_id, section, start_offset, end_offset, quote_text, note, created_at, style. */
const val INSERT_QUOTE = "INSERT INTO quotes(book_id, section, start_offset, end_offset, quote_text, note, created_at, style) " +
    "VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
const val UPDATE_QUOTE_STYLE = "UPDATE quotes SET style = ? WHERE id = ?"
// For the notes hub's chips (optional): per-style counts over non-trashed books.
const val COUNT_QUOTES_BY_STYLE = "SELECT q.style, COUNT(*) FROM quotes q JOIN books b ON b.id = q.book_id " +
    "WHERE b.trashed = 0 GROUP BY q.style"

// BookRows.quote
style = if (c.columnCount > 8) c.getInt(8) else 0,

// Library (blocking, IO)
fun addQuote(bookId: Long, section: Int, start: Int, end: Int, text: String, note: String = "", style: Int = 0): Quote
fun updateQuoteStyle(id: Long, style: Int)                      // clamps to 0..QuoteStyles.MAX_STORED
fun setQuoteStyles(ids: Collection<Long>, style: Int)           // one transaction (hub batch recolour)
```

`Library` must not import `render/`: add `QUOTE_STYLE_MAX = 15` to `DataLimits` (in `data/MetaInfo.kt`), keep it
equal to `QuoteStyles.MAX_STORED` (a test asserts it), and clamp there. `LibrarySqlTest`: `placeholders(INSERT_QUOTE)` 7 → 8, plus the new statements prepared against the v2 schema,
as the existing cases do.

**Backup.** `BackupQuote.style: Int = 0`. JSON `"style"` is written only when ≠ 0, so old app versions see an unknown
key and ignore it. Parse with `int(q, "style", 0).coerceIn(0, MAX_STORED)`. Restore merge, mirroring the note rule: a
new quote is INSERTed with its style. An existing quote with the same `section:start:end` key gets `UPDATE_QUOTE_STYLE`
only when `cur.style == 0 && b.style != 0`, so a restore never overrides a colour chosen on this device.
`Quote(newId, …, q.createdAt)` in `Backup.kt` needs `style = q.style`.

Cross-spec note for auto-backup (`scroll/design-*`, "unchanged check"): a recolour or note edit changes neither the
quote count nor `MAX(id)` / `MAX(created_at)`. The fingerprint must add `TOTAL(style)` and `TOTAL(LENGTH(note))`
from `quotes` (one aggregate in the query it already runs), or colour changes will never be backed up.

---

## 5. Reader wiring (READER_A: `ReaderActivity.kt`)

1. `reloadAnnotations`: `Highlight(it.start, it.end, HighlightKind.QUOTE, it.style)`.
2. **`sameDecor` compares `x.style != y.style` too.** Without it, a recolour on the page never repaints (see §1).
   Consider moving the comparison into a pure `internal object DecorDiff { fun same(a, b) }` with a unit test.
3. `applyAppSettings`: `QuoteLook.setMode(app.highlightLook)`. If `QuoteLook.generation` changed, call one
   `refreshDecor()` + invalidate. That redraw is user-initiated, so it is allowed.
4. `afterOpen`: `ReaderIo.launch { EinkScreen.probe() }` (§2.4). It costs nothing after the first run in the process.

Nothing is added between `startOpen` and `showPage`. Quotes still load in `reloadAnnotations` after the first page,
with one extra int per row.

---

## 6. Where the user picks (EXTRAS_TOOLS: `SelectionController.kt`, new `QuoteSwatch.kt`, new `QuotePalette.kt`)

### 6.1 Shared UI pieces

**`QuoteSwatch(context, style, sizeDp)`**: a static View (no animation, no ripple) that draws one style.

- **Colour mode**: a filled dot (20 dp in the popup, 12 dp in rows) in `colorFill(style, night = false)` with a 1 px
  `Ink.GRAY` ring, so pale yellow still shows on white. 밑줄 is drawn as "가" (14 sp) over a `t2` underline.
- **Ink mode**: a 30 × 20 dp sample (22 × 14 dp in rows): the style's grey band and line around a black "가". The
  e-ink user sees exactly what the page will show.
- `isChecked` draws a 2 dp black ring 3 dp outside the swatch.
- UI swatches always use **day** values: popups and lists are black on white even over an inverted page.

The mode comes from `QuoteLook.ink()` when the view is built.

**`QuotePalette.show(anchor: View, current: Int?, onPick: (Int) -> Unit): PopupWindow`**: a one-row popup
(`borderBox`, `animationStyle = 0`, `elevation = 0`, outside touch dismisses). It has 6 cells, each
`max(48dp, (W − 16dp) / 6)` wide and 56 dp tall: the swatch plus a 12 sp label (노랑 초록 파랑 빨강 보라 밑줄).
`current` is ringed. A tap calls `onPick` and dismisses. It is registered with `PanelRegistry.popup` like the other
menus. EXTRAS_NAV (ContentsDialog) and the notes hub use it, so keep it `internal` in `reader/extras`, or public if
the hub lives outside that package.

Last-used style: the raw pref `extras.quoteStyle` (Int, default 0), read from `Settings.raw()` (already in memory).
It is written whenever the user picks from a palette (new quote or recolour). It is not a typed setting: it is a
convenience, and backing it up as a raw pref is harmless.

### 6.2 Selection popup (with `ui/audit.md` #13's single row)

```
┌────────┬────────┬────────┬────────┬────────┐
│   ⧉    │  ● ▾   │   ✎    │   文A  │   ⋮    │   ● = QuoteSwatch in the last-used style
│  복사   │  인용   │  메모   │ 사전·번역 │ 더보기 │   ▾ = 9 sp hint: long-press for colours
└────────┴────────┴────────┴────────┴────────┘
```

- **Tap 인용** → `saveQuote(snap, note = "", style = last)` and `clear()`. One tap, the same flow as today. **Drop the
  "인용문에 저장했습니다" toast**: the quote appearing on the page is the confirmation, and a toast costs the Comet two
  extra updates (show + hide). Keep the failure toast.
- **Long-press 인용**, or **⋮ → "색 골라 인용…"** (discoverable without long-press) → `QuotePalette.show(anchor =
  인용 cell, current = last)`. A pick saves the quote with that style and sets `last`.
- **메모** → note prompt, then a quote in the `last` style (as today, plus `style`).
- ⋮ holds: 색 골라 인용…, 공유, 문단 선택, 검색, 웹 검색, 여기서 읽기.

### 6.3 Quote popup: long-press on an existing quote (`editingQuote != null`)

```
┌──────┬──────┬──────┬──────┬──────┬──────┐
│ (●)  │  ●   │  ●   │  ●   │  ●   │  가̲   │  palette row, current ringed
│ 노랑  │ 초록  │ 파랑  │ 빨강  │ 보라  │ 밑줄  │
├──────┴─┬────┴───┬──┴─────┬┴──────┴┬─────┤
│  메모   │  복사   │  공유   │  삭제    │  ⋮  │  ⋮ = 사전·번역, 검색, 웹 검색, 여기서 읽기
└────────┴────────┴────────┴────────┴─────┘
```

- While the range equals the quote's, the **selection fill is not drawn** (`setHighlights("selection", section,
  emptyList())`), so the user sees the quote's real colour. The handles stay to mark its ends. Dragging a handle
  changes the range: `editingQuote` becomes null (existing rule), the selection fill returns and the normal
  selection popup shows.
- **Swatch tap** → IO: `Library.updateQuoteStyle(q.id, s)` + `Library.quotes(bookId)` →
  `ContentsDialog.refreshQuoteHighlights(host, q.section, all)`, which carries `style` (§7). Then set `last = s`,
  move the ring and **keep the popup open**. The page repaints under it (one partial update) and the user can try
  another colour. A tap outside closes it (existing `tapCandidate` path).
- 삭제 keeps its confirm dialog. Recolouring has no confirm, because it is harmless and reversible.

---

## 7. Rows, filters, notes hub, export (EXTRAS_NAV: `ContentsDialog.kt`; notes-hub owner)

**ContentsDialog 인용문 rows.** `noteRow` becomes horizontal: `[text / 메모 / meta (weight 1)] [swatch column 48 dp]`.
The swatch sits at the top, aligned with the first text line, and the whole column is the touch target.

- Tap the swatch column → `QuotePalette.show(anchor = column, current = q.style)`. Pick → IO update →
  `refreshQuoteHighlights(host, q.section, all)` (only when `!stale()`) → re-bind **that row only**
  (`adapter.notifyDataSetChanged()` is fine; the list is static on e-ink). Set `last = s`.
- The long-press menu gets **"색 바꾸기"** (`R.drawable.ic_ink_highlighter`, which already exists; no new resource),
  which opens the same palette.
- `refreshQuoteHighlights` maps `Highlight(it.start, it.end, HighlightKind.QUOTE, it.style)`.

**Filter chips.** A row above the list, shown only when the book's quotes use ≥ 2 styles:
`[전체 12] [● 5] [● 3] [가̲ 4]` (chip = swatch + count, 44 dp tall). A tap filters the adapter's list in memory (no
query) and updates the tab label, e.g. "인용문 5 / 12". The filter is not persisted. 모두 공유 shares the filtered set.

**Notes hub** (quotes of all books). It uses the same row (plus the book title line), the same swatch column and
palette, and the same chips (counts from `COUNT_QUOTES_BY_STYLE` on IO, or from the loaded rows). **Batch recolour**:
in multi-select, the action bar gets 색 바꾸기 → palette → `Library.setQuoteStyles(ids, s)` (one transaction). If the
open book is affected, it refreshes on its own when the reader window regains focus (`onWindowFocusChanged(true)` →
`reloadAnnotations`, the existing 1 s guard at `ReaderActivity.kt:449`). An optional "색별로 묶기" sort groups rows by style, then by book and position.

**Export marker** (`ContentsDialog.shareAllQuotes`, hub export). Pure `QuoteExport.tagged(quotes): Boolean` =
distinct styles ≥ 2. When true, each entry reads:

```
[초록] “…quote…”
  (12쪽)
  메모: …
```

- When all quotes share one style, the output is byte-for-byte today's.
- A single-quote share (`quoteShareText`, the page's 공유) is never tagged.
- Markdown export: `- [초록] “…” (12쪽)`.
- HTML export: `<mark style="background:#B9E4A2">…</mark>` with the day colour. 밑줄 becomes
  `<u>`.

---

## 8. CONTRACT REQUESTS (frozen files: lead edits them)

```kotlin
// data/Models.kt: Quote (last parameter; the default keeps positional calls compiling)
/** Highlight look, `quotes.style` (QuoteStyles: 0 노랑 … 5 밑줄; unknown values draw as 0). */
val style: Int = 0,

// settings/ReaderSettings.kt: AppSettings
/** How quote colours are drawn (T2-3): [HL_LOOK_AUTO] (e-ink → grey patterns, else colours), [HL_LOOK_COLOR], [HL_LOOK_INK]. */
val highlightLook: Int = HL_LOOK_AUTO,
// top level
const val HL_LOOK_AUTO = 0
const val HL_LOOK_COLOR = 1
const val HL_LOOK_INK = 2

// settings/Settings.kt: key "a.highlightLook" (int), missing → HL_LOOK_AUTO, out of range → HL_LOOK_AUTO.
// data/SettingsJson.kt: appToJson/appFromJson map "a.highlightLook" clamped 0..2 (+ SettingsJsonR2Test-style case).
```

No `res/**` change (`ic_ink_highlighter` exists). No `LibrarySchema` change.

## 9. Owners and order

| # | Owner | Work | Depends on |
|---|---|---|---|
| 1 | lead (contract) | §8 | none |
| 2 | RENDER | `QuoteStyles`, `QuoteLook`, `EinkScreen`, `Eink.vendorIfProbed()`, `Highlight.style`, the two-pass `drawHighlights` with per-style paints, `DashMath` | 1 (only for `HL_LOOK_*`) |
| 3 | DATA | SQL, `BookRows`, `Library.addQuote(style)` / `updateQuoteStyle` / `setQuoteStyles`, `DataLimits.QUOTE_STYLE_MAX`, backup codec + merge | 1 |
| 4 | READER_A | §5 (style in `reloadAnnotations`, **`sameDecor`**, `QuoteLook.setMode`, the probe in `afterOpen`) | 2, 3 |
| 5 | EXTRAS_TOOLS | `QuoteSwatch`, `QuotePalette`, selection popup §6.2 / §6.3, `extras.quoteStyle` | 2, 3 |
| 6 | EXTRAS_NAV | ContentsDialog rows, chips, recolour, `refreshQuoteHighlights(style)`, tagged share-all | 5 |
| 7 | SETTINGS | "인용문 색 표시" row (자동 (이 기기: …) / 색 / 흑백 무늬), plus a static preview strip of the 6 `QuoteSwatch`es in the chosen mode | 1, 2 |
| 8 | LIBRARY | `ReaderIo.launch { EinkScreen.probe() }` after the first frame | 2 |
| 9 | notes hub owner | rows, chips, batch recolour, export tags (§7) | 3, 5 |

## 10. Tests (JVM, no Paint/SQLite in the pure parts)

- `render/QuoteStylesTest`:
  - `of()` maps unknown or negative values to 0.
  - Every ink grey is a multiple of 0x11 in 0xBB..0xEE.
  - Every pair of styles differs in (ink grey level by ≥ 1 level with a different line kind, or ≥ 2 levels).
  - Day colour fills have black-text contrast ≥ 12; night fills have white-text contrast ≥ 8 (compute sRGB luminance
    in the test).
  - Labels and tags.
  - `thumbGrey` is never "none".
- `render/EinkScreenTest`: `guess()` for "INNOSPACE", "Bigme", "ONYX/BOOX", "samsung", "Hisense" (false), and nulls.
- `render/DashMathTest`: dashes align on the grid across two fragments; they are clipped to `[l, r)`; zero width
  gives no dash.
- `render/QuoteLookTest`: mode changes bump the generation only when `ink()` flips.
- `data/LibrarySqlTest`: 8 placeholders, and the new statements prepare.
- `data/BackupJsonTest`: `style` round-trips; an old backup gives 0; out-of-range values clamp; 0 is not written.
- Backup merge: the `cur.style == 0` rule.
- `reader/DecorDiffTest` (if extracted): a style change → not the same.
- `reader/extras/QuoteExportTest`: no tags for one style; tags for two or more; single-quote share untagged.
- Device pass (Comet + phone):
  - All 6 looks, day and night.
  - A quote under selection keeps its line.
  - A recolour repaints at once.
  - `RAPerf` turn time unchanged on a page with 5 quotes.
  - Library `am start -W` within +5 %.
  - Cached reopen of the 14.8 MB TXT within +10 ms.

## 11. Risks and edge cases

- **`sameDecor` left as is** means recolours never show. This is item 2 of §5; test it.
- **Overlapping quotes**: the later fill wins in the overlap, and both lines are drawn (two-pass). The long-press
  edits the first quote that contains the offset (existing rule). This is acceptable; no merge logic.
- **Ink labels say colour names.** On the Comet, the palette shows grey samples captioned 노랑 … 보라. This is on
  purpose: the stored value is a colour, and the same quote shows yellow on the phone after a backup restore. The
  settings preview lists the mapping (노랑 → 회색 + 밑줄, …). If the user finds it confusing, a later option could
  caption ink samples by look (기본 / 옅게 / 진하게 / 강조 / 상자 / 밑줄) without touching stored data.
- **Kaleido colour e-ink** is detected as e-ink, so 자동 gives patterns. The user can switch to 색. Documented in the
  settings row subtitle.
- **Newer backups with style > 5** are kept in the DB (≤ 15) and draw as 노랑. A later app version shows them properly.
- **The auto-backup fingerprint** misses recolours unless it adds the aggregates in §4.
