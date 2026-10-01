# anchor.md — U2 fixed status bands · U3 40 dp top/bottom margins · U4 page-break mode · U6 position stability

Status: buildable design, read-only on the repo. Checked against the working tree of 2026-09-30 (R2 still being
finished; `engine/*` is unchanged from HEAD `b3dbc72`, `LayoutKeys.kt` / `BookSession.kt` / `ReaderActivity.kt` are
R2-modified). Symbols are named, line numbers are "≈". Paths are relative to `app/src/main/java/com/ggumtak/readeraplus/`.

It sits on top of the three reviewed specs and changes them where it says so (**[U2]**, **[U3]**, **[U4]**, **[U6]**
markers):
- `scroll/SPEC.md` (stitched pages, `PageInfo.lead`, side margins 40 = "0", owners CONTRACT / READER_CORE /
  ENGINE_RENDER / EXTRAS / DATA / UI);
- `ui/UI_SPEC.md` (status slots, progress line, pin removal §2.6);
- `notes/NOTES_SPEC.md` (only touched by the thumbnail note in §5.7).

**Prototype.** The engine half (anchor break, PARAGRAPH mode) and the pure helpers were implemented against a copy of
today's `engine/` in `scratchpad/wave2/proto/` and run on the JVM (`/opt/tc/kotlinc`):
- the unmodified `LayoutGoldenTest` passes → **LINE output is byte-identical, `ALGO_VERSION` stays 1**;
- `TypesetterTest`, `TypesetterFuzzTest`, `TypesetterReviewTest`, `TypesetterPerfTest` pass unchanged;
- the new tests of §6 pass (97 tests); 1 M chars: layout 16 ms / count 14 ms before and after (JIT, fake measurer);
- `engine.diff` in the same folder is the exact engine patch quoted in §4.2 / §5.3.

---

## 0. Decisions

| # | Question | Decision |
|---|---|---|
| U2 | Where do header, footer and progress line live? | **Inside the page margins.** Text box = page view − margins, always. Status settings never enter the layout (normalised out of `layoutPart`): every status change is a **repaint**. |
| U2 | Margin smaller than a band? | The band's text **shrinks to fit (down to 7 sp), else is not drawn**; the progress lane takes `min(12 dp, bottom margin)` and disappears under 6 dp. Text never moves. At the default 40 dp every allowed status size (8–16 sp) fits unscaled. |
| U3 | Top/bottom margins | Default **40/40 dp, shown as "0"** (same scale, labels, range −40…+40 and step 2 as the scroll SPEC's side margins). Untouched legacy 16/16 → 40/40 via a marker `r.marginBaseV` (prefs, backups, styles). With the header/footer on, **the default text box height is exactly today's** (16 dp + 24 dp band = 40 dp). |
| U4 | Page-break setting | `engine.PageBreakMode { LINE, PARAGRAPH }`, `ReaderSettings.pageBreak`, labels **"줄 단위" / "문단 단위"**, default **LINE** (today's pages; PARAGRAPH costs +22 % pages on web-novel text at Comet defaults, measured). |
| U4 | Exact rule | PARAGRAPH: a block whose line boxes sum to ≤ the page height never splits; when one of its lines overflows, the block moves whole (cut before its first line), and a keep-with-next heading chain goes with it only if chain ≤ ½ page and chain + block fit together. Taller blocks split exactly like LINE (widow/orphan applies). |
| U6a | Show/hide never resizes the text | After UI_SPEC §2.6 the only remaining page-size owner is the window insets. **New `InsetsGate`**: inset changes that arrive while the reader window has no focus (a dialog or popup showed the system bars) are held; 400 ms after the focus returns the then-current insets are compared and normally dropped. |
| U6b | Relayout keeps the place | **Anchored pagination**: every layout generation carries an `AnchorSpec(section, offset)`; `TypesetPass` makes the item holding the anchor **open a page** (the page before may be short). Height-only changes (top/bottom margins, line/paragraph spacing, widow control, page-break mode) keep the **exact** first char; width changes (font, side margins, rotation) keep the anchor on the first line. |
| U6b | Which char is the anchor? | The first char of the page being read (`anchor` = page start after TURN **and JUMP**; kept verbatim across RELAYOUT and OPEN so repeated relayouts never drift). |
| U6b | Lifetime | One generation. The next rebuild re-anchors at the then-current place; **opening a book anchors at the saved position** (identical to today when the saved position is a page start of the same layout; exact restore otherwise). No spontaneous "clean-up" relayout. |
| U6b | Counts | In-session counts use the anchored layout (labels, seek bar, TOC pages stay mutually consistent); the page-count cache never receives an anchored count (masked with the cache's own value, or counted once un-anchored in the background). No key change, no `ALGO_VERSION` bump. |
| U6b | TXT re-parse | The anchor carries a 24-char whitespace-insensitive **needle**, re-found within ±8 k chars of the estimate in the new text (`TextRefind`). |
| U6b | Scroll mode | Needs no anchor of its own (it puts the anchor line at the top), and the anchored pages are stitch-invariant (§5.8). One code path. |

---

## 1. Facts from the code (what moves text today)

1. **`LayoutKeys.geometry`** (READER_CORE) subtracts `band = round(statusPx × STATUS_BAND)` (2.2 × 11 sp ≈ 48 px on
   the Comet) from the box height for `showHeader` and again for `showFooter`, and puts the header band *below* the top
   margin: `top = mt + header`, `h = viewH − mt − mb − header − footer`. `layoutPart` keeps `showHeader`, `showFooter`
   and `statusFontSizeSp`, so toggling either band or changing the status size is a **RELAYOUT**
   (`LayoutKeysTest.layoutChangeDetection` asserts `showFooter = false` relays out).
2. **UI_SPEC §5.1** keeps that model: "NONE ↔ item is a relayout", plus `mbEff = max(mb, lane)` (the progress line
   grows a margin < 12 dp). U2 replaces §5.1 entirely (§2.4 below).
3. **`PageRenderer.drawStatus`** already draws the header centred in `[0, contentTop)` and the footer centred in
   `[contentTop + ch, viewHeight)`: the bands are *drawn* in the space above/below the box, they are just *reserved*
   by the geometry. So U2 is mostly a geometry change plus a fit rule.
4. **`BookSession.rebuild()`** makes a new `Generation` (id, settings, geometry, config, density), clears the LRU and
   `counts.reset()`s. Layout (`layoutOnThread`) and count (`countOnThread`) both run `Typesetter(m, gen.config)` on
   the section content. `store()` writes `counts.set(section, pageCount, length)`; `saveCounts` masks failed sections
   (`CountSaves.maskFailed`) and writes under `layoutKey` (no anchor in the key).
5. **`ReaderActivity.relayout()`** lays out `anchor.section` in the new generation and shows
   `l.pageForOffset(anchor.offset)` with `anchorOffset = off` (the anchor survives relayouts verbatim, so rotations
   don't drift). The text shifts: the page shown *contains* the anchor, typically mid-page.
6. **`anchor`** semantics today: page start after TURN, exact target after JUMP (`display(... anchorOffset = if (kind
   == Nav.TURN) -1 else off)`), verbatim after RELAYOUT/OPEN. Saved by `savePositionNow`, used by `reopenDocument`.
7. **`startOpen`** calls `s.setViewport(vw, vh)` *before* it computes the start position, and publishes `session = s`
   before `viewReady.await()`, so an `onViewSizeChanged` during the open can rebuild first (matters for §5.5).
8. **Page view size** (`PageView.onSizeChanged` → `onViewSizeChanged` → `setViewport` → `rebuild` → `relayout`) is
   changed by `applyInsets` (insets) and `applyPinnedArea` (pinned chrome, deleted by UI_SPEC §2.6). Full inventory:
   §5.1.
9. **`TypesetPass`**: `paragraph()` breaks all lines of a block first (`breakLines`), then stages/commits line by line;
   `commit()` handles the empty-at-top drop, `pageBreakBefore`, overflow → `decideCut` (heading, widow/orphan,
   keep-with-next chain capped by `movable` = ½ page) → `emitPage`, and a second overflow check for carried items.
   Line breaking never depends on pagination (the scroll SPEC's stitch property).

---

## 2. U2 — status bands inside the margins

### 2.1 The rule

- **Text box = page view − margins** (`pageMargins = false` → 4 dp all round, as today). Nothing status-related is
  subtracted: no header band, no footer band, no progress lane, no dependence on the status font size.
- **Header band** = `[0, contentTop)`; **footer text band** = `[contentBottom, viewH − lane)`; **lane** =
  `[viewH − lane, viewH)` with `lane = min(12 dp, bottom margin)`, 0 when the bottom margin < 6 dp or
  `progressBar = false`.
- **Fit**: each band draws its slots at the user's status size when `(ascent + descent) × size + 2 × 2 dp ≤ band
  height`; otherwise at the largest size that fits; below 7 sp the band stays empty (the data is still computed; it is
  just not drawn). Pure: `render/StatusFit.kt` (§2.3).
- Consequences: **turning any band, slot, the progress line or the status size on/off is a repaint** — one e-ink
  update of the same text box. The reader never re-paginates, recounts or changes the page-count key for them.

Why not "minimum margin = band height when the band is on": toggling a band would then move the text whenever the
margin is below the band, which is exactly what the user asked to never happen. Why not "bands overlap the text":
unreadable. Shrink-then-hide is the only rule with no text movement and no overlap.

### 2.2 `reader/LayoutKeys.kt` (READER_CORE)

```kotlin
/** Placement of the page content box inside the page view: the view minus the margins, nothing else (U2). */
fun geometry(s: ReaderSettings, viewW: Int, viewH: Int, density: Float): PageGeometry {   // statusPx parameter removed
    fun px(dp: Int): Int = Math.round((if (s.pageMargins) dp else TINY_MARGIN_DP) * density)
    val ml = px(s.marginLeftDp.coerceAtLeast(0))
    val mr = px(s.marginRightDp.coerceAtLeast(0))
    val mt = px(s.marginTopDp.coerceAtLeast(0))
    val mb = px(s.marginBottomDp.coerceAtLeast(0))
    val minBox = Math.round(48 * density).coerceAtLeast(16)
    var w = viewW - ml - mr
    var left = ml
    if (w < minBox) { w = minOf(minBox, viewW).coerceAtLeast(1); left = ((viewW - w) / 2).coerceAtLeast(0) }
    var h = viewH - mt - mb
    var top = mt
    if (h < minBox) { h = minOf(minBox, viewH).coerceAtLeast(1); top = ((viewH - h) / 2).coerceAtLeast(0) }
    return PageGeometry(viewW, viewH, left, top, w, h)
}
// STATUS_BAND is deleted (its only other user, SelectionController's fallback origin, drops the band term: §2.5).

fun config(s: ReaderSettings, g: PageGeometry, txt: Boolean = false): LayoutConfig = LayoutConfig(
    /* …as today… */
    widowOrphanControl = s.widowOrphanControl,
    pageBreak = s.pageBreak,                                   // U4
)

/** Settings with every field that does NOT change the layout normalised away (U2: all status fields). */
private fun layoutPart(s: ReaderSettings): ReaderSettings {
    val d = DEFAULTS                                           // private val DEFAULTS = ReaderSettings()
    return s.copy(
        invert = false,
        headerLeft = d.headerLeft, headerCenter = d.headerCenter, headerRight = d.headerRight,
        footerLeft = d.footerLeft, footerCenter = d.footerCenter, footerRight = d.footerRight,
        progressBar = d.progressBar,
        statusFontSizeSp = d.statusFontSizeSp,
    )
}
```

`key()` needs nothing for U2 (it never hashed the status fields; the box it hashes simply stops depending on them).
**No `LayoutKeys.VERSION` bump**: where the box changes (users with a band on), the `box=` part of the key changes by
itself; where it does not (bands off), the old counts are still exactly right and are kept.

`BookSession.rebuild()` drops `statusPx` and calls `LayoutKeys.geometry(settings, viewW, viewH, dm.density)`.

### 2.3 Drawing (ENGINE_RENDER: `render/PageRenderer.kt`, new pure `render/StatusFit.kt`)

```kotlin
/** U2: fitting the status bands into the page margins (px; pure). The text box never makes room for them. */
internal object StatusFit {
    const val PAD_DP = 2f          // paper above and below the status glyphs
    const val MIN_SP = 7f          // smaller than this: the band stays empty
    const val LANE_DP = 12f        // UI_SPEC PROGRESS_LANE_DP
    const val LANE_MIN_DP = 6f     // bottom margin below this: no progress line
    const val GLYPH_EM = 1.45f     // ascent+descent per px of text size, for dp estimates in the settings UI (CJK system font)

    /** [wantPx] if its glyph box + 2·pad fits [roomPx], else the largest size that does, or 0 below [minPx]. */
    fun size(wantPx: Float, roomPx: Float, glyphPerPx: Float, padPx: Float, minPx: Float): Float {
        if (!(wantPx > 0f) || !(roomPx > 0f) || !(glyphPerPx > 0f)) return 0f
        val fit = (roomPx - 2f * padPx) / glyphPerPx
        if (wantPx <= fit) return wantPx
        return if (fit >= minPx) fit else 0f
    }

    /** Progress lane in a bottom margin of [marginPx]: min(12 dp, margin); 0 under 6 dp. */
    fun lane(marginPx: Float, density: Float): Float =
        if (marginPx < LANE_MIN_DP * density) 0f else minOf(LANE_DP * density, marginPx)

    /** Settings-UI estimate: does a [statusSp] band show in [marginDp] minus [laneDp]? (drives the "숨겨짐" note) */
    fun fitsDp(statusSp: Float, marginDp: Int, laneDp: Float): Boolean =
        marginDp - laneDp >= MIN_SP * GLYPH_EM + 2f * PAD_DP
}
```

UI_SPEC §5.4 `drawStatus` becomes (changes marked; `drawBand` / `drawProgress` / the per-version measuring cache stay
as specified there):

```kotlin
internal fun drawStatus(canvas: Canvas, decor: PageDecor, left: Float, top: Float, cw: Float, ch: Float,
                        viewW: Int, viewH: Int, ribbonH: Float) {
    val st = decor.status ?: return
    val bottomMargin = viewH - (top + ch)
    val lane = if (st.lane) StatusFit.lane(bottomMargin, density) else 0f            // [U2] inside the margin, never grows it
    if (!st.header.isEmpty) {
        val ts = bandSize(HEADER, top)                                               // [U2] fitted to the top margin
        if (ts > 0f) {
            val inset = RibbonMath.headerInset(density, left + cw, viewW, ribbonH, headerBaseline(top, ts) - ascentOf(ts))
            drawBand(canvas, st.header, left + inset, cw - 2 * inset, headerBaseline(top, ts), HEADER, ts)
        }
    }
    if (!st.footer.isEmpty) {
        val ts = bandSize(FOOTER, bottomMargin - lane)                               // [U2]
        if (ts > 0f) drawBand(canvas, st.footer, left, cw, centredBaseline(top + ch, viewH - lane, ts), FOOTER, ts)
    }
    if (lane > 0f) drawProgress(canvas, st.progress, viewW, viewH, lane)             // [U2] lane height passed in
}
```

- `bandSize(band, room)` = `StatusFit.size(statusPx, room, glyphPerPx, 2dp, 7sp)`, cached per band on
  `(room, statusPx)`; `glyphPerPx = (descent − ascent) / textSize` of `statusPaint`, read once in the constructor.
  When the fitted size differs from `statusPaint.textSize`, the band draws with its own `bandPaint[band]` (a copy of
  `statusPaint`, `textSize` set only when the fitted size changes). Zero allocation per draw.
- The per-version slot-position cache (UI_SPEC §5.4) is keyed on `(st.version, cw, header inset, ts)`.
- `drawProgress(…, lane)`: `yc = viewH − round(lane / 2)`, `rDot = min(3 dp, lane/2 − 1 px)`,
  `rCap = min(1.5 dp, rDot / 2)`; at the default lane (24 px on the Comet) this is UI_SPEC's exact geometry
  (`yc = 1428`, `rDot = 6`, `rCap = 3`).
- Scroll mode's `drawChrome` calls the same `drawStatus` (scroll SPEC §1.8): nothing else to change there.

### 2.4 Edits to UI_SPEC

| UI_SPEC place | Edit |
|---|---|
| §5.1 geometry code and `layoutPart` | Replaced by §2.2 above. Delete "Only a band appearing or disappearing (or the lane outgrowing the margin) moves a line", `mbEff`, and "**Item ↔ item is a repaint. `NONE` ↔ item is a relayout.**" → "**Every status change is a repaint** (U2)". |
| §5.1 "Comet numbers" table | Replaced by §2.6. |
| §5.2 `StatusDecor.lane` KDoc | Keep "from the settings, never from the data"; add "the lane's *height* is `StatusFit.lane(bottom margin)`, so it can never move text". |
| §5.4 `drawStatus` | As §2.3 (fit per band, lane height from the margin). |
| §5.5 "Apply: Item ↔ item repaints; NONE ↔ item relays out." | "Apply: always a repaint." Add the note row of §2.7. |
| §5.7 cost row "Slot change NONE ↔ item: relayout plus one recount" | "repaint". |
| §1.10 compile fallout `LayoutKeys.kt` / `SelectionController.kt` | `s.showHeader → s.hasHeader` becomes: delete the header terms (§2.2, §2.5). |
| §8 JVM `LayoutKeysTest` rows for NONE↔item relayout | Replaced by §6.4. |

### 2.5 Other fallout

- `reader/extras/SelectionController.origin()` fallback (EXTRAS): `originY = v.paddingTop + (pageMargins ? dp(marginTopDp) :
  dp(4))` — the `+ if (s.showHeader) sp(statusFontSizeSp) * 2.2f` term is deleted (the box top is the margin).
- `render/Covers.kt`: unaffected (it builds its own `LayoutConfig`; UI_SPEC's slot fallout applies as written).
- `docs/ARCHITECTURE.md` "BookSession … content box = view size − margins … − header/footer heights" → "view size −
  margins; the status bands are drawn inside the margins and never change the box (U2)".

### 2.6 Numbers (Comet 720 × 1440 px, density 2, status 11 sp = 22 px, defaults after R3 + U3)

| Config | Text box (px) | Header band | Footer text band | Lane |
|---|---|---|---|---|
| Default (header = chapter, no footer text, line on) | left 80, top 80, **560 × 1280** | [0, 80): 22 px text needs 32 + 8 → drawn | [1360, 1416), empty | [1416, 1440), yc 1428 |
| + footer text | same box | same | [1360, 1416): 56 px, 22 px text drawn | same |
| footer text, line off | same box | same | [1360, 1440) | – |
| status 16 sp (32 px) | same box | fits (46 + 8 ≤ 80) | fits (46 + 8 ≤ 56) | same |
| top/bottom "−30" (10 dp = 20 px) | top 20, 560 × 1400 | 20 px: 7 sp needs 28 px → **not drawn** | 0 px left → not drawn | the whole 20 px margin: yc 1430, rDot 6 |
| 페이지 여백 off (4 dp = 8 px) | 8, 8, 704 × 1424 | not drawn | not drawn | not drawn (8 px < 6 dp) |
| **R2 today** (16 dp + 48 px bands) | top 32 + 48 = **80**, height **1280** | [0, 80) | [1360, 1440) | – |

The default vertical box is identical to R2's, so for a default user U2+U3 change nothing vertically (the width changes
by the scroll SPEC's side margins, which recounts once anyway).

### 2.7 UI

- Popup "상태 표시" block (UI_SPEC §5.5) and Settings → 페이지 표시 → 상태 표시줄: when
  `!StatusFit.fitsDp(statusFontSizeSp, effectiveTopDp, 0f)` for a header with items, or
  `!fitsDp(statusFontSizeSp, effectiveBottomDp, if (progressBar) 12f else 0f)` for a footer with items, show a grey
  note under the rows: **"여백이 좁아 위 · 아래 정보가 보이지 않습니다. 상하 여백을 늘리세요."** (`effective…Dp` = 4 when
  `pageMargins` is off). Nothing is disabled: the choice is kept and shows again when the margin grows.

---

## 3. U3 — top/bottom margins 40 dp shown as "0"

### 3.1 Contract (`settings/ReaderSettings.kt`, CONTRACT)

```kotlin
val marginTopDp: Int = 40,          // was 16
val marginBottomDp: Int = 40,       // was 16

/** U3: top/bottom margins on the same "40 dp = 0" scale as [SideMargin]; stored values stay actual dp. */
object VerticalMargin {
    const val ZERO_DP = SideMargin.ZERO_DP          // 40
    const val LEGACY_DEFAULT_DP = 16                // ≤ R2 default
    const val UI_MIN = SideMargin.UI_MIN            // −40 → actual 0 dp
    const val UI_MAX = SideMargin.UI_MAX            // +40 → actual 80 dp (today's maximum)
    const val UI_STEP = SideMargin.UI_STEP          // 2
    /** Marker "top/bottom saved by a U3+ build" (prefs + backup reader object). */
    const val KEY = "r.marginBaseV"
    /** The same marker inside a saved style's JSON. */
    const val STYLE_KEY = "marginBaseV"
    fun toUi(actualDp: Int): Int = SideMargin.toUi(actualDp)
    fun toDp(ui: Int): Int = SideMargin.toDp(ui)
    fun label(ui: Int): String = SideMargin.label(ui)          // "0", "+4", "−10" (U+2212)
    fun isLegacyDefault(hasMarker: Boolean, top: Int, bottom: Int): Boolean =
        !hasMarker && top == LEGACY_DEFAULT_DP && bottom == LEGACY_DEFAULT_DP
}
```

A **separate marker** (not the scroll SPEC's `r.marginBase`) so the two migrations are independent: if the side-margin
build ever ships first, a device that already has `r.marginBase` still gets its 16/16 → 40/40.

### 3.2 Migration (same mechanism as scroll SPEC §2.3)

- `Settings.saveReader`: `putInt(VerticalMargin.KEY, VerticalMargin.ZERO_DP)`.
- `Settings.loadReader`: read `mt`, `mb`; `if (VerticalMargin.isLegacyDefault(p.contains(VerticalMargin.KEY), mt, mb))`
  load 40/40. Nothing is written on load (cold start stays write-free; idempotent until the next save).
- `SettingsJson.readerToJson`: `.put(VerticalMargin.KEY, VerticalMargin.ZERO_DP)` (so it is in `MAPPED_KEYS`
  automatically). `readerFromJson`: after the field mapping,
  `if (o.has("r.marginTopDp") && VerticalMargin.isLegacyDefault(o.has(VerticalMargin.KEY), r.marginTopDp, r.marginBottomDp)) r = r.copy(marginTopDp = 40, marginBottomDp = 40)`.
  Clamp `0..300` unchanged.
- `UserStyles.toJson`: `.put(VerticalMargin.STYLE_KEY, VerticalMargin.ZERO_DP)`; `fromJson`: legacy 16/16 without
  the marker → 40/40.
- `StylePreset`s don't touch margins; "기본값 복원" / "설정 초기화" give 40/40.

| Stored | Result |
|---|---|
| untouched 16/16 (existing install) | **40/40, shown "0"**. With the header and footer on (R2 default) the box height is unchanged (16 + 24 band = 40). |
| a deliberate 16/16 | also 40/40 (accepted, as the scroll SPEC accepts it for 18/18). |
| any other value (e.g. 24/24) | kept, shown "−16". If a band was on, the text box grows by that band (24 dp) at the first open after the update (the book reopens at the saved first char, §5.6). The header fits a 24 dp margin up to 13 sp; footer text needs ≈ 26 dp (7 sp) to 32 dp (11 sp) when the progress line is on, else it is hidden and the §2.7 note says so. |
| old backup 16/16 | 40/40 |
| style saved at 16/16 | 40/40 |
| new backups / styles | carry the marker → a deliberate 16 round-trips |

Rejected: "new = old + the band that used to sit under it" (it would keep every custom box exactly, and gives 40 for
the default too). It needs `showHeader` / `showFooter` / `statusFontSizeSp` at migration time, which styles don't carry
and UI_SPEC's first `saveReader` deletes; two different rules for prefs and styles are worse than the one-line rule.

### 3.3 UI

- Popup `ReadingSettingsPopup.addPage` (EXTRAS):
  ```kotlin
  val marginV = stepperRow("상하 여백", VerticalMargin.toUi(cur.marginTopDp).toFloat(),
      VerticalMargin.UI_MIN.toFloat(), VerticalMargin.UI_MAX.toFloat(), VerticalMargin.UI_STEP.toFloat(),
      { VerticalMargin.label(it.toInt()) }) {
      val dp = VerticalMargin.toDp(it.toInt())
      update(cur.copy(marginTopDp = dp, marginBottomDp = dp), debounce = true)
  }
  ```
  Same `accessibilityLiveRegion = POLITE` as the side stepper; hidden while `pageMargins` is off (as today).
- Settings → 넘김 → "페이지 표시" (UI): `ctx.stepperRow("상하 여백", …)` right under the scroll SPEC's "좌우 여백",
  one shared note: "0이 기본 여백입니다. −로 좁히고 +로 넓힙니다. 위 · 아래 정보와 진행 막대는 이 여백 안에 표시됩니다."
- Changing it is a height-only change: **the page the reader is on keeps its exact first char** (§5.2).

### 3.4 Tests (CONTRACT, same commit)

`settings/VerticalMarginTest`, `SettingsStoreTest`, `SettingsJson…Test`, `UserStylesTest`, `LayoutKeysTest` — code in §6.

---

## 4. U4 — page-break mode "줄 단위 / 문단 단위"

### 4.1 Contract

```kotlin
// engine/Layout.kt (CONTRACT)
/** How pages break (U4, "페이지 나눔"). Stored by name: never rename. */
enum class PageBreakMode {
    /** 줄 단위: fill every page line by line; paragraphs split across pages (widow/orphan control applies). */
    LINE,
    /** 문단 단위: a paragraph (block) that fits on one page is never split; the page before it ends early. */
    PARAGRAPH,
}
data class LayoutConfig( /* …existing, unchanged order… */
    val widowOrphanControl: Boolean = true,
    /** U4: LINE (default, today's pagination) or PARAGRAPH. Last parameter: positional callers keep compiling. */
    val pageBreak: PageBreakMode = PageBreakMode.LINE,
)

// settings/ReaderSettings.kt (CONTRACT), next to widowOrphanControl
/** "페이지 나눔": 줄 단위 (LINE, default) or 문단 단위 (PARAGRAPH). Layout-affecting; not part of styles/presets. */
val pageBreak: PageBreakMode = PageBreakMode.LINE,
```

- The enum lives in `engine/` like `LineBreakMode` (the engine must not import settings); labels are UI strings.
- Prefs `r.pageBreak` (enum name; unknown → LINE); `SettingsJson` maps `r.pageBreak` both ways (`enumOf(…, base)`).
- Not in `UserStyle` / `StylePreset` (like `widowOrphanControl`: it is a reading preference, not a look).
- `layoutPart` keeps it (it is a real layout field) → changing it is a RELAYOUT (anchored: exact first char, §5.2).

**Default LINE**, because:
1. it is today's pagination: no existing reader sees a change, cached page counts stay valid;
2. measured at Comet defaults (560 × 1280 px box, 20 sp, 200 % line height, 100 % paragraph spacing): web-novel text
   (10–220 char paragraphs, 30 % dialogue) needs **+22 % pages** in PARAGRAPH mode (mean page fill 0.76, worst 0.06);
   literary text (80–600 chars) +3 % (most of its paragraphs are taller than a page and split anyway). On e-ink every
   extra page is an extra turn and refresh;
3. widow/orphan control (on by default) already removes the ugliest splits in LINE mode.
The user said they aren't sure: they get the familiar one and can try the other in one tap (the page they are on stays
put when they switch, §5.2).

### 4.2 Exact `TypesetPass` rule (ENGINE_RENDER; from `proto/engine.diff`)

```kotlin
private val keepParas: Boolean = cfg.pageBreak == PageBreakMode.PARAGRAPH
/** PARAGRAPH mode: height of the block being staged (its line boxes, no space-before) and whether it fits a page. */
private var blockH = 0f
private var blockFits = false

// paragraph(): right after `val nl = breakLines(...)` and the ls/le/lw locals
if (keepParas && nl > 1) {
    val mc = metricCursor                       // lineBox advances the run cursor: measure ahead, then rewind
    var h = 0f
    for (j in 0 until nl) { lineBox(ls[j], le[j]); h += mLineH }
    metricCursor = mc
    blockH = h
    blockFits = h <= pageH + EPS
} else {
    blockH = 0f
    blockFits = false
}

/** How many of the page's k committed items stay when the staged item k doesn't fit (>= 1). */
private fun decideCut(k: Int): Int {
    val b = buf
    var cut = k
    val kind = b.pKind[k]
    // U4 PARAGRAPH: a block that fits on one page never splits. When one of its lines overflows, the whole block goes
    // to the next page (cut before its first line when that line is on this page after other content).
    var whole = false
    if (keepParas && blockFits && kind == K_TEXT) {
        val p = k - b.pLine[k]
        if (b.pLine[k] == 0) {
            whole = true
        } else if (p > 0 && b.pBlock[p] == b.pBlock[k] && b.pLine[p] == 0) {
            cut = p
            whole = true
        }
    }
    if (!whole) {
        /* today's heading branch and widow/orphan branch, unchanged */
    }
    /* today's keep-with-next chain search, unchanged */
    if (chain > 0 && (if (whole) chainFits(chain, cut, k) else movable(chain))) cut = chain
    return if (cut < 1) 1 else cut
}

/**
 * PARAGRAPH mode: the keep-with-next chain [chain, cut) goes along with the block moving whole from [cut] ([k] = the
 * staged item) only when the chain alone takes at most KEEP_MAX_MOVE of a page and chain + block fit on the next page
 * together; otherwise the next page would have to split the block or hold the chain alone.
 */
private fun chainFits(chain: Int, cut: Int, k: Int): Boolean {
    val b = buf
    val cutTop = if (cut == k) y + b.pSb[k] else b.pTop[cut]
    val before = cutTop - b.pTop[chain]
    return before - b.pSb[cut] <= pageH * KEEP_MAX_MOVE && before + blockH <= pageH + EPS
}
```

Semantics, precisely:
- **Unit = one `ParagraphBlock`** (the parsers' paragraph). A `softBreak` continuation (`<br>` line, TXT overlong-line
  segment, EPUB split tail) is its own block: books that put a whole chapter in one `<p>` with `<br>`s still paginate
  sensibly, and poetry lines are one line each (never split anyway). Keeping a whole stanza together is not promised.
- **Fits** = Σ line-box heights of the block ≤ page height (space-before excluded: it is swallowed at a page top).
- **Taller blocks** split exactly as in LINE mode, including widow/orphan control. Headings taller than a page keep
  today's `movable` cap.
- **Headings / keep-with-next**: the chain moves with a moving block only under `chainFits` (≤ ½ page, and chain +
  block fit), so PARAGRAPH never produces a page holding only a heading because the block behind it did not fit.
  When it can't move, the heading stays at the bottom and the block moves (today's LINE fallback when `movable` fails).
- **Widow/orphan**: moot for blocks that fit (they never split); unchanged for taller blocks. The popup's
  "외톨이 줄 방지" summary becomes "한 쪽보다 긴 문단에만 적용" while PARAGRAPH is selected.
- **Images, rules, blank paragraphs, `pageBreakBefore`**: unchanged (single items).
- **Anchor break (U6)**: the anchor wins. A relayout into PARAGRAPH mode while reading mid-paragraph keeps that
  mid-paragraph page start (the reader's place does not move); every later page follows PARAGRAPH.
- **Scroll mode**: line breaking is untouched, the moved block's page `lead` = its space-before (the scroll SPEC's
  `emitPage` bookkeeping already re-tops carried item 0), so stitched strips are identical to LINE's (§6.1 test
  `aBlockThatFitsIsNeverSplit` asserts `flow(LINE) == flow(PARAGRAPH)`).

### 4.3 Keys, `ALGO_VERSION`, golden

- `LayoutKeys.key()`: `if (s.pageBreak != PageBreakMode.LINE) sb.append("|pb=").append(s.pageBreak.name)` — the key
  string for LINE is byte-identical to today's composition, so no `VERSION` bump and no recount for anyone who keeps
  LINE. PARAGRAPH gets its own cached counts.
- **`ALGO_VERSION` stays 1**: the untouched `LayoutGoldenTest.layoutOutputMatchesTheGoldenHash` passes with the patch
  (LINE output byte-identical, prototype-verified).
- New `LayoutGoldenTest.paragraphModeMatchesItsGoldenHash()` over the same corpus with
  `PARAGRAPH_CONFIGS = listOf(CONFIGS[0].copy(pageBreak = PARAGRAPH), CONFIGS[3].copy(pageBreak = PARAGRAPH))`
  against a new `LayoutKeys.GOLDEN_HASH_PARAGRAPH` (prototype value against today's engine: **`c9982a735d4822a9`**;
  the lead re-reads it from the test message in phase 2). `ALGO_VERSION`'s KDoc: "bump when either hash changes".
  `layoutAll()` gains a `configs` parameter (default `CONFIGS`).

### 4.4 UI

- Popup, "페이지" section (EXTRAS), directly above "외톨이 줄 방지":
  `segmentRow("페이지 나눔", listOf("줄 단위" to PageBreakMode.LINE, "문단 단위" to PageBreakMode.PARAGRAPH), cur.pageBreak) { update(cur.copy(pageBreak = it)) }`
  (a relayout, not debounced).
- Settings → 넘김 → 페이지 표시 (UI): `valueRow("페이지 나눔", label)` → chooser
  - "줄 단위 (기본) — 쪽을 끝까지 채웁니다. 문단이 다음 쪽으로 이어질 수 있습니다."
  - "문단 단위 — 한 쪽에 들어가는 문단은 나누지 않습니다. 쪽 아래가 비기도 합니다."

### 4.5 Cost

LINE: one boolean test per `decideCut` (only on overflow). PARAGRAPH: one extra `lineBox` per line of multi-line
blocks (run lookups only; measuring dominates). Prototype, 1 M chars: LINE layout/count 16/14 ms before and after;
PARAGRAPH within noise of LINE. Count mode runs the same code (count == layout asserted in every new test).

---

## 5. U6 — the reading position never moves

### 5.1 (a) Nothing shown or hidden may change the text box

Inventory (grep of `page.layoutParams`, `setViewport`, `relayout()`, `onSizeChanged`, `addView(… root …)`,
`applyFullscreen`, `insetsController` over `reader/`, `render/`, `ui/kit/`):

| # | Path today | Effect | After this design |
|---|---|---|---|
| 1 | `togglePin()` → `applyPinnedArea()` | page top/bottom margins = bar heights → resize → relayout | deleted (UI_SPEC §2.6; `applyPageInsets()` = insets only) |
| 2 | `setChromeVisible()` → `applyPinnedArea()` (every menu open/close while pinned) | same | deleted (§2.6) |
| 3 | `startOpen` `pinPending` → `applyPinnedArea()`; `showPage` auto-show of pinned chrome | same | deleted (§2.6) |
| 4 | `chrome.top/bottom.addOnLayoutChangeListener(barsResized)` → `onBarsResized()` → `applyPinnedArea()` | bar height (title, brightness row, rotation) resizes the page | registrations on the chrome bars deleted; `onBarsResized()` = `updateChipPosition()` (§2.6) |
| 5 | `LayoutKeys.geometry` header band (`showHeader` / UI_SPEC `hasHeader`) | box − band → relayout on toggle | **U2**: never |
| 6 | footer band (`showFooter` / `hasFooterText`) | same | **U2**: never |
| 7 | `statusFontSizeSp` (band = 2.2 × size) | same | **U2**: normalised out; renderer fits |
| 8 | UI_SPEC §5.1 progress lane `mbEff = max(mb, lane)` | planned relayout for margins < 12 dp | **U2**: lane inside the margin |
| 9 | popup "상단 챕터 제목" / "하단 정보 표시" / footer items / UI_SPEC slot rows / "진행 막대" / "상태 글자 크기"; `PageTurningPage` status rows | `applySettings` → RELAYOUT for 5–8 | REPAINT only |
| 10 | `applyInsets()` from `root.setOnApplyWindowInsetsListener` | system-bar / cutout insets → page margins → resize | kept for real changes, **gated** (`InsetsGate`, below) |
| 11 | AlertDialogs (`alert().showNoAnim()`, e.g. 페이지 이동, rename, info) and focusable PopupWindows in fullscreen on API 30+ | a focused window that does not ask to hide the bars shows them → insets change → #10 → relayout, and again when it closes | held by `InsetsGate` while unfocused; dropped when the bars are gone again 400 ms after the focus returns |
| 12 | `fullScreenDialog` (TOC, search, NOTES hub/thumbnails) | already `matchSystemBars` | also covered by the gate |
| 13 | `ReaderWindow.applyFullscreen` in `onWindowFocusChanged(true)` / `applyAppSettings` | re-hides the bars → insets back | gate: equal to applied → nothing |
| 14 | TTS bar (`TtsController`: `parent.addView(row, Gravity.BOTTOM)`) | overlay | stays an overlay (no change) |
| 15 | search-results bar, return chip / UI_SPEC ReturnNav strip (`updateChipPosition` moves only the chip), selection handles + popup, brightness overlay + UI_SPEC light options panel, loading text, error panel, `EndPanel` | overlays | no change |
| 16 | reading-settings popup (`PopupWindow`, `hideBars`) | window over the page; hides chrome overlays | no change (its focus loss is gated by #10) |
| 17 | scroll-mode switch | never relays out (scroll SPEC) | no change |
| 18 | rotation, orientation lock, multi-window / freeform resize, the fullscreen setting | real size change | **anchored** relayout (U6b) |
| 19 | settings: font, size, weight class, letter spacing, line/paragraph spacing, indent, align, line break, margins, `pageMargins`, widow, page-break mode, publisher styles | RELAYOUT | **anchored** (U6b) |
| 20 | TXT options / encoding (`reopenDocument`) | re-parse | **anchored + needle** (U6b) |

**`reader/InsetsGate.kt`** (READER_CORE, pure, JVM-tested):

```kotlin
/**
 * U6a: when new window insets may resize the page. Changes that arrive while the reader window has no focus (a dialog
 * or popup took it, and with it the system bars) wait; once the window has had the focus back for [SETTLE_MS] the
 * insets then current are taken, normally the old ones again. Pure; main thread.
 */
internal class InsetsGate {
    private var applied: IntArray? = null
    private var pending: IntArray? = null
    val hasPending: Boolean get() = pending != null

    /** [settled]: focus held ≥ SETTLE_MS. [forced]: nothing shown yet, configuration change, fullscreen setting changed. */
    fun offer(i: IntArray, settled: Boolean, forced: Boolean): IntArray? {
        val cur = applied
        if (cur != null && cur.contentEquals(i)) { pending = null; return null }
        if (cur == null || forced || settled) { val c = i.copyOf(); applied = c; pending = null; return c }
        pending = i.copyOf()
        return null
    }

    /** The focus has been back for SETTLE_MS: [now] = the window's insets at this moment. */
    fun settle(now: IntArray): IntArray? {
        if (pending == null) return null
        pending = null
        return offer(now, settled = true, forced = false)
    }

    companion object { const val SETTLE_MS = 400L }
}
```

`ReaderActivity` wiring (READER_CORE):

```kotlin
private val insetsGate = InsetsGate()
private var focusSince = 0L                 // uptime of the last focus gain, 0 = no focus
private var insetsFullscreen: Boolean? = null
private var configChanged = false
private val settleInsets = Runnable {
    val wi = if (isDestroyed) null else root.rootWindowInsets
    if (wi != null) insetsGate.settle(ReaderWindow.insetsOf(wi, app.fullscreen))?.let { applyInsets(it) }
}

root.setOnApplyWindowInsetsListener { _, wi ->                       // buildViews
    val i = ReaderWindow.insetsOf(wi, app.fullscreen)
    val settled = focusSince > 0 && SystemClock.uptimeMillis() - focusSince >= InsetsGate.SETTLE_MS
    val forced = curLayout == null || configChanged || insetsFullscreen != app.fullscreen
    configChanged = false
    insetsFullscreen = app.fullscreen
    insetsGate.offer(i, settled, forced)?.let { applyInsets(it) }
    wi
}
override fun onWindowFocusChanged(hasFocus: Boolean) {
    super.onWindowFocusChanged(hasFocus)
    if (!hasFocus) { focusSince = 0L; handler.removeCallbacks(settleInsets); /* …panelOpen as today… */; return }
    focusSince = SystemClock.uptimeMillis()
    ReaderWindow.applyFullscreen(this, app.fullscreen)
    handler.removeCallbacks(settleInsets)
    handler.postDelayed(settleInsets, InsetsGate.SETTLE_MS)
    /* …annotations reload, panel closed, as today… */
}
override fun onConfigurationChanged(newConfig: Configuration) {
    super.onConfigurationChanged(newConfig)
    configChanged = true
    root.requestApplyInsets()
}
```

The first 400 ms after regaining focus also count as unsettled, so the frame in which a closing dialog's bars are still
visible never resizes the page. A rotation with a dialog open is `forced` and applies at once (the page size changes
anyway). Optional belt-and-braces (CONTRACT, `ui/kit/Ui.kt`): let `showNoAnim()` call `matchSystemBars` like
`fullScreenDialog` does.

### 5.2 (b) The anchored-pagination rule

**Rule.** A rebuild (settings, size, re-parse, open) is given the reader's anchor `A` (section, char offset). In that
section, the first laid-out item that holds `A` or comes after it — the first item with `end > A` or `start ≥ A`
(a text line, an image, a rule, a blank paragraph) — **opens a page**. If the normal pagination already puts it at a
page top, nothing changes; otherwise the page before it ends there, short. Everything before that item is paginated
exactly as without the anchor; everything after it is normal pagination starting from it.

Why the *line* holding the anchor and not a forced *line* break at the anchor char:
- line starts never depend on the page height, so for every **height-only** change — top/bottom margins (U3), line
  spacing, paragraph spacing, widow control, page-break mode, the bands if they ever did — the anchor, a line start in
  the old layout, is a line start in the new one: **the new page begins with exactly the same char** (prototype test
  `aHeightOnlyChangeKeepsTheExactFirstChar`);
- for **width** changes (font size, side margins, rotation, letter spacing) the anchor lands somewhere on the new page's
  first line, at most one line's worth of already-read text before it;
- breaking the *line* at the anchor would make the exact char first in every case, but it creates a fake paragraph
  end (a ragged half line at the bottom of the previous page, a non-indented continuation) that stays in the book
  until the next relayout, changes line breaking (the scroll SPEC's stitch property, `LineInfo` hashes) and is visible
  in scroll mode. Rejected.

Because the anchor is kept verbatim across relayouts (never replaced by the new page start), going back returns exactly:
font 20 → 21 → 20 shows the original page again (test `aWidthChange…ComesBackExactly`), and rotating back and forth
never creeps.

**Which char is `A`.** The first char of the page the reader sees:

| Shown by | `anchor` becomes | Change |
|---|---|---|
| TURN | page start | as today |
| JUMP (TOC, link, search hit, TTS follow, go-to page/percent, return point, bookmark) | **page start** | was the exact target: `display()` passes `anchorOffset = -1` for JUMP as for TURN. "The first visible char stays first" is the U6 promise; a relayout right after a mid-page search hit keeps the page, not the hit's line. |
| RELAYOUT, OPEN | the requested offset, verbatim (sticky) | as today |
| scroll mode | first half-visible line (scroll SPEC §1.4, sticky across RELAYOUT/OPEN/SWITCH) | as specified there |

`savePositionNow` keeps saving `anchor`, so a saved position is always "the first char the reader saw".

### 5.3 Engine mechanism (ENGINE_RENDER; exact patch in `proto/engine.diff`)

```kotlin
// engine/Layout.kt (CONTRACT)
class SectionLayout(
    val content: SectionContent, val config: LayoutConfig, val pages: List<PageInfo>, val advances: FloatArray,
    /** U6: the offset whose item was forced to open a page ([Typesetter.layout]'s anchorBreak), -1 = none. */
    val anchorBreak: Int = -1,
    /** U6: index of the page that item opened, -1 = no anchor. */
    val anchorPage: Int = -1,
    /** U6: false guarantees pages identical to the un-anchored layout; true = they may differ from the anchor on. */
    val anchorShifted: Boolean = false,
)
/** Result of [Typesetter.count]: the page count and where an anchor break landed (U6). */
class PageTally(@JvmField val pages: Int, @JvmField val anchorPage: Int, @JvmField val anchorShifted: Boolean)

// engine/Typesetter.kt
fun layout(content: SectionContent, anchorBreak: Int = -1): SectionLayout {
    val pass = runPass(content, retain = true, anchorBreak)
    val a = if (pass.anchorPage >= 0) anchorBreak else -1
    return SectionLayout(content, config, pass.pages!!, pass.advances, a, pass.anchorPage, pass.anchorShifted)
}
fun countPages(content: SectionContent): Int = runPass(content, retain = false, -1).pageCount   // unchanged contract
/** [countPages] of layout(content, anchorBreak), plus where the anchor landed (U6). */
fun count(content: SectionContent, anchorBreak: Int): PageTally {
    val p = runPass(content, retain = false, anchorBreak)
    return PageTally(p.pageCount, p.anchorPage, p.anchorShifted)
}
```

The anchor is a **Typesetter argument, not a `LayoutConfig` field**: `LayoutConfig` is the data-class identity that
`SelectionController.origin()` caches its calibration on, and it must stay equal for every section of a generation.

`TypesetPass` (new constructor parameter `anchorBreak: Int = -1`, validated `in 1 until len`):

```kotlin
/** U6: pending forced page break: the first item with end > it (or start >= it) opens a page; -1 once placed. */
private var anchorLeft: Int = if (anchorBreak in 1 until len) anchorBreak else -1
var anchorPage = -1; private set
var anchorShifted = false; private set

/** Places the staged item (index n) on the current page, breaking pages as needed. */
private fun commit() {
    val a = anchorLeft
    if (a >= 0 && (buf.pStart[n] >= a || buf.pEnd[n] > a)) {
        // U6: this item holds (or is the first after) the anchor: it opens a page (a blank one is dropped there).
        anchorLeft = -1
        place(anchored = true)
        anchorPage = pageCount
    } else {
        place(anchored = false)
    }
}

private fun place(anchored: Boolean) {
    /* today's commit() body unchanged: blank-at-top drop, pageBreakBefore, overflow → decideCut → emitPage,
       second overflow check … then, just before `val top = y + sb`: */
    if (anchored && s > 0) {
        // U6: the un-anchored pass would put the anchor's item after other items: end the page before it (the page may
        // be short). Everything so far is exactly what the un-anchored pass does.
        anchorShifted = true
        emitPage(s, b.pStart[s], true)
        s = 0
        if (b.pKind[0] == K_EMPTY) return
        sb = 0f
    }
    /* val top = y + sb … as today */
}

private fun finish() {
    finishPages()                                               // today's finish() body
    if (anchorPage >= pageCount) anchorPage = pageCount - 1     // an anchor on trailing blanks opened no page
}
```

Properties (all asserted by §6.1, prototype green):
- **Natural first**: the anchored item goes through the normal PBB / overflow / `decideCut` logic *first*; only if it
  would still sit below other items is the page cut before it. So when the normal pass breaks there anyway, the layout
  is byte-identical and `anchorShifted = false`.
- **The anchor wins** over keep-with-next, widow/orphan and PARAGRAPH (`theAnchorWinsOverWidowAndKeepRules`): the
  reader saw that page start; it must come back.
- **Pages before the anchor page** are the un-anchored ones; the short page shares its start and a common prefix of
  lines with the un-anchored page of the same index.
- **Lines never change**: `flow(anchored) == flow(un-anchored)` (only the pagination moves).
- **Page count**: over 3 125 random anchors (25 seeds × 5 configs × 25) the anchored count differed by −1…+1 from the un-anchored one
  (nothing relies on this bound).
- `anchorShifted` is conservative: 516 of 4 247 natural page starts (a page start decided *later* by an orphan / keep /
  PARAGRAPH carry) report `true` although the output is identical. It only decides what the count cache gets (§5.4),
  so conservative is safe.
- **Scroll SPEC `lead` bookkeeping**: the forced `emitPage` goes through the SPEC's `emitPage` bookkeeping, and the
  anchored item at index 0 gets `pageLead += topGap(0)` at placement; the new `return` after the forced emit (a staged
  blank dropped at the page top) is a **fourth drop site** and does the same `pageLead += topGap(0) + pH[0]` as the
  three the SPEC lists (§1.3 step 1: "every return that drops a staged K_EMPTY at index 0" — now in `place()`).

### 5.4 `BookSession` (READER_CORE)

New pure file `reader/Anchors.kt` (READER_CORE):

```kotlin
/**
 * U6: where one section of a layout generation is forced to start a page: the first char of the page being read.
 * Immutable; resolved identically on the layout and the count thread (pure).
 * [needle]: visible text at the anchor before a re-parse (TXT options), re-found near [offset] in the new text.
 */
class AnchorSpec(val section: Int, val offset: Int, val needle: String? = null) {
    /** The break offset in [content] (the needle's new place when found, else [offset]); -1 = no break. */
    fun resolve(content: SectionContent): Int {
        val len = content.length
        if (len <= 1) return -1
        val est = offset.coerceIn(0, len)
        val found = if (needle != null) TextRefind.find(content.text, est, needle) else -1
        val at = if (found >= 0) found else est
        return if (at in 1 until len) at else -1
    }
}

/** U6: page choices after an anchored relayout / open (pure). */
internal object AnchorMath {
    /** The page for [offset]: the one its anchor break opened when [l] was anchored there, else the one holding it. */
    fun pageFor(l: SectionLayout, offset: Int): Int =
        if (l.anchorPage >= 0 && l.anchorBreak == offset) l.anchorPage
        else l.pageForOffset(offset.coerceIn(0, l.content.length))
}

/** U6: finds the reading position again in a re-parsed text (whitespace-insensitive; pure). */
internal object TextRefind {
    const val NEEDLE = 24          // visible chars taken at the old anchor
    const val MIN_NEEDLE = 6       // fewer (a section's last line): not searched
    const val WINDOW = 8192        // chars searched on each side of the estimate
    fun snippet(text: String, offset: Int): String?                 // ≤ 24 non-whitespace chars from offset
    fun find(text: String, estimate: Int, needle: String): Int      // nearest match (d = 0, +1, −1, +2, …); −1 if none
}
```
(Full bodies in `proto/src/.../reader/Anchors.kt`; `find` skips whitespace, `　` and `OBJECT_CHAR` in the text
while matching, so blank-line removal, indentation stripping and hard-wrap joining don't break the match; cost
≤ 16 k × 24 char compares ≈ 1 ms, on the layout thread, once per re-parse.)

`BookSession` changes:

```kotlin
class Generation(
    val id: Int, val settings: ReaderSettings, val geometry: PageGeometry, val config: LayoutConfig, val density: Float,
    /** U6: the section/offset whose page start this generation keeps (null = none). */
    val anchor: AnchorSpec?,
) {
    fun anchorFor(section: Int, content: SectionContent): Int =
        if (anchor != null && anchor.section == section) anchor.resolve(content) else -1
}

/** U6: whether this generation's anchor changed its section's pagination (null = not known yet). */
private var anchorShifted: Boolean? = null
/** U6: the count cache's own (un-anchored) value for the anchor's section under [layoutKey]; -1 = none. */
private var anchorCached = -1

fun setViewport(width: Int, height: Int, anchor: AnchorSpec?): Boolean { /* as today */ rebuild(anchor); return true }
fun updateSettings(new: ReaderSettings, anchor: AnchorSpec?): Change { /* as today */ rebuild(anchor); return Change.RELAYOUT }

private fun rebuild(anchor: AnchorSpec?) {
    /* … */
    val g = LayoutKeys.geometry(settings, viewW, viewH, dm.density)
    generation = Generation(genCounter, settings, g, LayoutKeys.config(settings, g, txt = …), dm.density,
        anchor?.takeIf { it.section in 0 until sectionCount })
    anchorShifted = null
    anchorCached = -1
    /* … invalidateJobs, cache.clear, counts.reset, hint, layoutKey = null … as today */
}

// layoutOnThread
val content = loadContent(section).content
Typesetter(m, gen.config).layout(content, gen.anchorFor(section, content))   // the error page is never anchored

// countOnThread(gen, section, anchored: Boolean = true)
val t = Typesetter(m, gen.config).count(c, if (anchored) gen.anchorFor(section, c) else -1)
CountResult(t.pages, …, shifted = t.anchorShifted)

// store(section, layout): after counts.set(...)
if (generation?.anchor?.section == section) anchorShifted = layout.anchorShifted

// countAll: after the cache load (saved.size == sectionCount)
anchorCached = gen.anchor?.let { saved[it.section] } ?: -1
// in the loop, after counting section i
if (i == gen.anchor?.section) anchorShifted = r.shifted
// after the loop, before the final saveCounts(key)
val a = gen.anchor
if (a != null && anchorShifted == true && anchorCached < 1 && counts.isComplete) {
    // The cache keys un-anchored pages: count the anchor's section once more without the anchor (one section, background).
    val r = withContext(countDispatcher) { countOnThread(gen, a.section, anchored = false) }
    if (gen !== generation) return
    if (!r.failed) anchorCached = r.pages
}
saveCounts(key)

// saveCounts: before maskFailed
val a = generation?.anchor
if (a != null && anchorShifted != false) CountSaves.maskAnchor(arr, a.section, anchorCached)

// CountSaves
/** U6: the anchored section's count is this generation's, not the un-anchored one the cache keys: keep the cache's own. */
fun maskAnchor(arr: IntArray, section: Int, cached: Int) {
    if (section in arr.indices) arr[section] = if (cached >= 1) cached else -1
}
```

Consistency, point by point:
- **In-session counts** (`PageCounts`): the anchor section's count always comes from the anchored layout or the
  anchored count (both threads resolve the same `AnchorSpec` on the same content), so global page labels, `total()`,
  the seek bar (`counts.locate`), `goToPage`, `flushTurns`' `TurnMath.walk`, `chapterPagesLeft`, TOC / bookmark /
  search page labels and NOTES thumbnails agree with the pages actually shown. The first page of any relayout/open *is*
  the anchor section, stored before it is shown, so no label is ever drawn from a stale cached value (`setKnown` keeps
  already-known sections; a later `store()` overwrites a cached value).
- **Estimates** (`charsPerPageHint`, `pagesPerChar`): unchanged.
- **Page-count cache**: never gets an anchored count (masked with the cache's own value, or `-1`, or the one extra
  un-anchored count). **Key unchanged** (anchors are not layout settings), **no `ALGO_VERSION` bump**.
- **LRU eviction**: a re-layout of the anchor section in the same generation reproduces it exactly (same spec, same
  content).
- **Totals across sessions** may differ by ±1 page in the anchor section (anchored this session, differently or not at
  all the last). Accepted; labels are consistent within a session, which is what the footer, seek bar and TOC need.

### 5.5 `ReaderActivity` (READER_CORE)

```kotlin
/** U6: what a rebuild keeps as the first char of the page being read. */
private fun keepHere(): AnchorSpec = AnchorSpec(anchor.section, anchor.offset)

// startOpen — compute the start BEFORE publishing the session, so a resize during the open anchors there too.
val s = BookSession(this@ReaderActivity, b, d, eff)
val remap = TextPositions.remapFraction(storedPos, LayoutKeys.textSignature(eff, d.format, b.encoding),
    b.posSection, b.posOffset, b.progress)
val start = if (remap != null) s.counts.locateFraction(remap) else DocPosition(b.posSection, b.posOffset)
val sec = start.section.coerceIn(0, s.sectionCount - 1)
anchor = DocPosition(sec, start.offset.coerceAtLeast(0))
s.listener = sessionListener
session = s
adopted = true
viewReady.await()
val (vw, vh) = pageTargetSize()
s.setViewport(vw, vh, keepHere())
s.startCounting(COUNT_DELAY_MS)
val l = s.layout(sec) ?: …
val off = start.offset.coerceIn(0, l.content.length)
val idx = AnchorMath.pageFor(l, off)
preloadImages(s, l, idx)
if (session !== s) return@launch
showPage(sec, l, idx, Nav.OPEN, anchorOffset = off)

// onViewSizeChanged
scroll?.stopMotion()                                  // scroll SPEC: settle the anchor first
if (!s.setViewport(w, h, keepHere())) return
relayout()

// applyToSession
when (s.updateSettings(eff, keepHere())) { … }

// relayout(): the page the anchor opened
val off = target.offset.coerceIn(0, l.content.length)
val idx = AnchorMath.pageFor(l, off)
preloadImages(s, l, idx)
if (session !== s) return@launch
endNavJob(coroutineContext[Job])
showPage(sec, l, idx, Nav.RELAYOUT, anchorOffset = off)

// display(): JUMP now anchors at the page start like TURN (§5.2)
else -> showPage(sec, l, l.pageForOffset(off), kind, anchorOffset = if (kind == Nav.TURN || kind == Nav.JUMP) -1 else off)

// reopenDocument (TXT options / encoding / publisher styles)
val pos = anchor
val needle = old.peek(pos.section)?.let { TextRefind.snippet(it.content.text, pos.offset) }
…
val s = BookSession(this@ReaderActivity, b, d, use)
val target = if (s.sectionCount == oldCount) pos else s.counts.locateFraction(ratio)
val sec = target.section.coerceIn(0, s.sectionCount - 1)
s.listener = sessionListener
val (vw, vh) = pageTargetSize()
s.setViewport(vw, vh, AnchorSpec(sec, target.offset, needle))
val l = s.layout(sec)
…
val off = if (l.anchorBreak >= 0) l.anchorBreak else target.offset.coerceIn(0, l.content.length)
writeTextPosition(b, s, DocPosition(sec, off))
val keep = AnchorSpec(sec, off)
val changed = want != null && want != s.settings && s.updateSettings(want, keep) != BookSession.Change.NONE
if (s.setViewport(nw, nh, keep) || changed) {
    anchor = DocPosition(sec, off)
    relayout()
} else {
    showPage(sec, l, AnchorMath.pageFor(l, off), Nav.JUMP, anchorOffset = off)
    s.startCounting(COUNT_DELAY_MS)
}
```

- A pending jump during a resize/settings change keeps today's behaviour (`relayout()` finishes the jump in the new
  generation); the generation is anchored at the page being left, which only matters if the jump lands in that section
  (then the jump shows the page holding its target in the anchored layout — fine).
- `closeCurrentBook()` resets `anchor = START` as today (a spec at offset 0 resolves to "no break").

### 5.6 Lifetime and clean-up

- The anchor lives exactly as long as its generation. Any later rebuild (another setting, a rotation) is anchored at
  the *then-current* first char; the previous anchor disappears with its generation.
- **No spontaneous clean-up** ("drop the anchor once the reader is N pages away"): it would need a new generation → a
  relayout and recount of the section, possibly under the reader, to remove a single short page they have already
  left behind. The short page is only seen when paging back past it.
- **Open**: anchored at the saved position (§5.5). When the saved position is a page start of the same layout (no
  settings change since, same orientation — the normal case) the break is natural and the layout is **identical to
  today's**; when it is not (rotated between sessions, settings changed in the library, the previous session was
  anchored, a TXT position remapped by fraction, an app update that changed a font), the book reopens with exactly the
  saved char at the top instead of "somewhere on the page". Zero extra cost on the open path (the same single layout).
- Rejected alternative: re-paginating the text *before* the anchor backwards so that the short page lands at the
  section start. It needs a second pagination direction kept equal to the count path, and it only moves the short page
  (TXT chunks start mid-story, so it would still be visible).

### 5.7 Everything else that reads pages

- **Seek bar / 페이지 이동 / TOC / bookmark / search / quote labels / NOTES thumbnails**: all go through
  `counts.globalPage` / `counts.locate` / `session.layout(section)` of the current generation → consistent (§5.4).
- **TTS** follows offsets; **selection** calibrates on `layout.config` (unchanged by anchors).
- **Return point (UI_SPEC ReturnNav)** stores positions (offsets), not page indices → unaffected.
- **Reading log** counts `p.end − p.start` of shown pages; a short page counts fewer chars — correct.

### 5.8 Scroll mode (stitched pages)

- **No anchor of its own is needed**: after a relayout the scroll SPEC places the anchor's line at the top (TOP
  placement, sticky anchor), which is the same line the paged anchor page starts with, so switching modes after a
  relayout stays exact in both directions.
- The generation (and its anchor) is shared by both modes — one code path, no mode branch in `BookSession`. The
  anchored pages are **stitch-invariant**: lines are unchanged (`flow` equality), the forced break's `lead` is the
  swallowed space-before (0 mid-paragraph), so the stitched strip is pixel-identical to an un-anchored one.
- Scroll SPEC edits: §1.3 step 1 lists the fourth drop site (§5.3 above); `StitchTest` adds `anchor ∈ {−1, random}` ×
  `pageBreak ∈ {LINE, PARAGRAPH}` (the reference stays the H = 10⁷ un-anchored layout: it is the same continuous flow).

### 5.9 Cost

| Where | Cost |
|---|---|
| `TypesetPass.commit` | 1 int compare per item while the anchor is pending, 0 after; one extra call frame (`place`). Prototype 1 M chars: 16 / 14 ms before and after. |
| Open | one `AnchorSpec` object; the same single layout |
| Relayout | the same single layout; `pageFor` O(1) |
| Counting | the anchor section counted with the anchor (same cost); at most one extra un-anchored count of that section at the end of background counting, only when the cache lacked it |
| TXT re-parse | `TextRefind.find` ≈ 1 ms on the layout thread |
| Insets | one array compare per insets dispatch; one 400 ms post per focus gain |
| U2 drawing | fitted sizes cached per band on `(room, statusPx)`; 0 allocation per draw |

---

## 6. JVM tests (all pure; `tools/unittest.sh`)

### 6.1 `engine/` (ENGINE_RENDER) — green in the prototype

`engine/LayoutDigest.kt` (test fixture):
```kotlin
object LayoutDigest {
    fun line(ln: LineInfo): String =
        "${ln.start},${ln.end},${ln.x},${ln.top},${ln.baseline},${ln.bottom},${ln.justifyExtra},${ln.expandMode}," +
            "${ln.imageBlock?.start ?: -1},${ln.imageWidth},${ln.imageHeight},${ln.isRule}"
    fun page(p: PageInfo): String = buildString {
        append(p.start).append('-').append(p.end).append('['); for (ln in p.lines) append(line(ln)).append(';'); append(']')
    }
    fun of(l: SectionLayout): String = l.pages.joinToString("\n") { page(it) }
    /** Line boxes without their vertical position (blank paragraphs dropped at page tops are left out). */
    fun flow(l: SectionLayout): List<String> = l.pages.flatMap { p ->
        p.lines.filter { it.end > it.start || it.isRule || it.imageBlock != null }
            .map { "${it.start},${it.end},${it.x},${Math.round((it.bottom - it.top) * 100)},${it.justifyExtra},${it.expandMode},${it.isRule}" }
    }
}
```

`engine/AnchorBreakTest.kt` (fixture: 70 random blocks per seed — body paragraphs, blanks, headings, PBB headings,
images, rules, `softBreak` continuations; configs LINE ± widow control, a second size, PARAGRAPH ± widow control;
every `lay()` also asserts `count(c, a)` == layout on pages / anchorPage / anchorShifted and `LayoutChecks.checkPages`):

```kotlin
@Test fun withoutAnAnchorNothingChanges()            // a ∈ {−1, 0, len, len+7}: digest == layout(c), anchorBreak == anchorPage == −1
@Test fun anAnchorAtAPageStartChangesNothing()       // every page start P of layout(c): digest identical, anchorPage == i
@Test fun theAnchorsItemOpensItsPage() {             // 25 random anchors × 25 seeds × 5 configs
    // page anchorPage: start <= a; its first line has end > a or start >= a; every line of earlier pages ends <= a;
    // pages before anchorPage−1 == un-anchored; the short page shares start + line prefix; flow(anchored) == flow(base);
    // !anchorShifted ⇒ digest identical; page-count delta within [−1, 2]
}
@Test fun aHeightOnlyChangeKeepsTheExactFirstChar()  // from h=400 page starts to h 517/333, lh 2.0, ps 1.2, wo off, PARAGRAPH:
                                                      // first visible line of the anchor page == that of the old page
@Test fun aWidthChangeKeepsTheAnchorOnTheFirstLineAndComesBackExactly()   // w 300 → 260 → 300: digest(back) == digest(w300)
@Test fun theAnchorWinsOverWidowAndKeepRules()       // anchor = a paragraph's last line / a heading's body: they open the page
```

`engine/ParagraphModeTest.kt`:
```kotlin
@Test fun aBlockThatFitsIsNeverSplit()               // 30 seeds × 3 configs: every ParagraphBlock with Σ line heights ≤ page
                                                      // height has all lines on one page; flow(LINE) == flow(PARAGRAPH);
                                                      // some book gets more pages than LINE; countPages == pageCount; checkAll
@Test fun aHeadingStaysWithTheBlockItIntroduces()    // a heading directly followed by a fitting body block never ends a page
                                                      // unless heading + block don't fit together
@Test fun lineModeIsTheDefault()                      // LayoutConfig(100, 100).pageBreak == LINE
```

`engine/LayoutGoldenTest.kt`: unchanged `layoutOutputMatchesTheGoldenHash` (LINE proof); new
`paragraphModeMatchesItsGoldenHash` (§4.3). `engine/StitchTest.kt` (scroll SPEC): anchored + PARAGRAPH variants.
Existing `TypesetterTest` (positional `LayoutConfig(…)` calls keep compiling), `TypesetterFuzzTest`,
`TypesetterReviewTest`, `TypesetterPerfTest`: unchanged and green.

### 6.2 `reader/` pure helpers (READER_CORE) — green in the prototype

`reader/AnchorsTest.kt`:
```kotlin
@Test fun snippetTakesVisibleCharsOnly() {
    assertEquals("가나다라마바사아", TextRefind.snippet("  가나 다\n라마바 사아", 0))
    assertNull(TextRefind.snippet("가나다", 0))
    assertNull(TextRefind.snippet("가나다라마바사", 7))
}
@Test fun findIgnoresWhitespaceChangesOfAReparse() {
    val old = "첫 줄입니다.\n\n  그녀는 낡은 우산을 접으며\n말했다. 오늘은 비가 그칠 거야."
    val at = old.indexOf("그녀는")
    val needle = TextRefind.snippet(old, at)!!
    val new = "첫 줄입니다.\n그녀는 낡은 우산을 접으며 말했다. 오늘은 비가 그칠 거야."   // blanks removed, indent stripped, lines joined
    assertEquals(new.indexOf("그녀는"), TextRefind.find(new, at, needle))
    assertEquals(-1, TextRefind.find("전혀 다른 글입니다. 전혀 다른 글입니다.", 5, needle))
}
@Test fun findPrefersTheOccurrenceNearestTheEstimate()
@Test fun resolveFallsBackToTheClampedEstimate()     // offset 0 / len / beyond → −1; missing needle → estimate;
                                                      // nearest match at the section start → −1 (no break)
@Test fun pageForUsesTheAnchorPageOnlyForItsOwnOffset()
@Test fun insetsGateHoldsBackBarsThatADialogShowed() {
    val g = InsetsGate(); val hidden = intArrayOf(0, 0, 0, 0); val bars = intArrayOf(0, 48, 0, 96)
    assertArrayEquals(hidden, g.offer(hidden, settled = false, forced = false))   // first insets: always
    assertNull(g.offer(hidden, settled = true, forced = false))                   // unchanged
    assertNull(g.offer(bars, settled = false, forced = false)); assertTrue(g.hasPending)   // a dialog showed the bars
    assertNull(g.offer(hidden, settled = false, forced = false)); assertFalse(g.hasPending) // … and they went again
    assertNull(g.settle(hidden))
    assertNull(g.offer(bars, settled = false, forced = false))
    assertArrayEquals(bars, g.settle(bars))                                        // they stayed: a real change
    assertArrayEquals(hidden, g.offer(hidden, settled = false, forced = true))    // rotation / fullscreen setting
    assertArrayEquals(bars, g.offer(bars, settled = true, forced = false))        // focused: at once
}
```
`reader/BookSessionHelpersTest.kt` + `anchoredCountsNeverReachTheCache()`:
```kotlin
val arr = intArrayOf(3, 7, 5)
CountSaves.maskAnchor(arr, 1, cached = 6); assertArrayEquals(intArrayOf(3, 6, 5), arr)
CountSaves.maskAnchor(arr, 2, cached = -1); assertArrayEquals(intArrayOf(3, 6, -1), arr)
CountSaves.maskAnchor(arr, 9, cached = 4); assertArrayEquals(intArrayOf(3, 6, -1), arr)
```

### 6.3 `render/StatusFitTest.kt` (ENGINE_RENDER) — green in the prototype

```kotlin
@Test fun defaultMarginsHoldEveryStatusSize()        // 80 px header band, 56 px footer band: 8..16 sp unscaled
@Test fun narrowMarginsShrinkThenHideTheText()       // 30 px → shrunk ≥ 7 sp; 16 px and 8 px → 0
@Test fun laneTakesTwelveDpOrTheWholeSmallMargin()   // lane(80)=24, lane(16)=16, lane(8)=0; fitsDp(11,40,12) true, (11,20,12) false
```

### 6.4 `reader/LayoutKeysTest.kt` (READER_CORE; replaces the scroll SPEC's and UI_SPEC's edits to it)

```kotlin
private val s = ReaderSettings()                 // after R3 + U3: 40/40/40/40, header = CHAPTER, no footer text, line on
private val density = 2f
private fun key(t: ReaderSettings, g: PageGeometry = LayoutKeys.geometry(t, 720, 1440, density)) =
    LayoutKeys.key(t, t.parseOptions(""), g, density, "BUNDLED:fonts/NanumMyeongjo.ttf")

@Test fun geometryIsThePageMinusTheMargins() {
    val g = LayoutKeys.geometry(s, 720, 1440, density)
    assertEquals(80, g.contentLeft); assertEquals(80, g.contentTop)
    assertEquals(560, g.contentWidth); assertEquals(1280, g.contentHeight)
}
@Test fun statusBandsNeverChangeTheTextBox() {
    val base = LayoutKeys.geometry(s, 720, 1440, density)
    for (t in listOf(
        s.copy(headerCenter = StatusItem.NONE),
        s.copy(footerLeft = StatusItem.PAGE, footerRight = StatusItem.CLOCK_BATTERY),
        s.copy(progressBar = false), s.copy(statusFontSizeSp = 16f),
        s.copy(statusFontSizeSp = 8f, headerLeft = StatusItem.BOOK_TITLE, headerCenter = StatusItem.NONE),
    )) {
        assertEquals(base, LayoutKeys.geometry(t, 720, 1440, density))
        assertFalse(LayoutKeys.layoutChanged(s, t))
        assertFalse(LayoutKeys.layoutChanged(s, t, BookFormat.TXT))
        assertEquals(key(s), key(t))
    }
}
@Test fun geometryWithoutMargins() {             // pageMargins = false: 8/8/704/1424 whatever the status settings
    for (t in listOf(s.copy(pageMargins = false), s.copy(pageMargins = false, footerLeft = StatusItem.PAGE))) {
        val g = LayoutKeys.geometry(t, 720, 1440, density)
        assertEquals(8, g.contentLeft); assertEquals(8, g.contentTop); assertEquals(704, g.contentWidth); assertEquals(1424, g.contentHeight)
    }
}
@Test fun geometryNeverCollapses()               // unchanged (margins 400/900)
@Test fun verticalMarginsAreLayout() {
    assertEquals(40, s.marginTopDp); assertEquals(40, s.marginBottomDp); assertEquals(0, VerticalMargin.toUi(s.marginTopDp))
    val t = s.copy(marginTopDp = 20, marginBottomDp = 20)
    assertTrue(LayoutKeys.layoutChanged(s, t))
    val g = LayoutKeys.geometry(t, 720, 1440, density)
    assertEquals(40, g.contentTop); assertEquals(1360, g.contentHeight); assertEquals(560, g.contentWidth)
}
@Test fun pageBreakModeIsLayout() {
    val p = s.copy(pageBreak = PageBreakMode.PARAGRAPH)
    val g = LayoutKeys.geometry(s, 720, 1440, density)
    assertTrue(LayoutKeys.layoutChanged(s, p)); assertNotEquals(key(s), key(p))
    assertEquals(PageBreakMode.PARAGRAPH, LayoutKeys.config(p, g).pageBreak)
    assertEquals(PageBreakMode.LINE, LayoutKeys.config(s, g).pageBreak)
    assertEquals(g, LayoutKeys.geometry(p, 720, 1440, density))
}
@Test fun configFromSettings()                   // as today; c.width == 560
@Test fun layoutChangeDetection() {              // invert / slots / progress / status size: false; font size, paragraph spacing, margins: true
}
@Test fun keyIsStableAndSensitive()              // as today with geometry(s, W, H, density)
```
`ReaderReviewFixesTest`: `LayoutKeys.geometry(s, 720, 1440, density)` (signature).

### 6.5 `settings/` and `data/` (CONTRACT)

```kotlin
// settings/VerticalMarginTest
@Test fun scaleMatchesTheSideMargins()           // toUi(40)=0, toUi(16)=−24, toDp(40)=80, toDp(−40)=0, toDp(−60)=0, labels "0" "+4" "−10"
@Test fun onlyTheUntouchedOldDefaultMigrates()    // 16/16 no marker → true; with marker, 24/24, 16/20 → false; KEY != SideMargin.KEY

// settings/SettingsStoreTest
@Test fun verticalMarginsMoveFromTheOldDefaultTo40() {
    fresh(); assertEquals(40, Settings.reader.marginTopDp); assertEquals(40, Settings.reader.marginBottomDp)
    fresh(hashMapOf("r.marginTopDp" to 16, "r.marginBottomDp" to 16)); assertEquals(40, Settings.reader.marginTopDp)
    fresh(hashMapOf("r.marginTopDp" to 24, "r.marginBottomDp" to 24)); assertEquals(24, Settings.reader.marginTopDp)
    fresh(hashMapOf("r.marginTopDp" to 16, "r.marginBottomDp" to 16, VerticalMargin.KEY to 40)); assertEquals(16, Settings.reader.marginTopDp)
    val p = fresh(hashMapOf("r.marginTopDp" to 16, "r.marginBottomDp" to 16))
    Settings.reader
    assertFalse(p.map.containsKey(VerticalMargin.KEY))                        // nothing written on load
    Settings.saveReader(Settings.reader)
    assertEquals(40, p.map[VerticalMargin.KEY]); assertEquals(40, p.map["r.marginTopDp"]); assertEquals(40, p.map["r.marginBottomDp"])
}
@Test fun pageBreakModeRoundTrips() {
    val p = fresh(); assertEquals(PageBreakMode.LINE, Settings.reader.pageBreak)
    Settings.saveReader(Settings.reader.copy(pageBreak = PageBreakMode.PARAGRAPH))
    fresh(HashMap(p.map)); assertEquals(PageBreakMode.PARAGRAPH, Settings.reader.pageBreak)
    fresh(hashMapOf("r.pageBreak" to "SENTENCE")); assertEquals(PageBreakMode.LINE, Settings.reader.pageBreak)
}

// data/SettingsJson…Test
@Test fun oldBackupTopBottom16Restores40() {
    val r = SettingsJson.readerFromJson(JSONObject().put("r.marginTopDp", 16).put("r.marginBottomDp", 16),
        ReaderSettings(marginTopDp = 30, marginBottomDp = 30))
    assertEquals(40, r.marginTopDp); assertEquals(40, r.marginBottomDp)
}
@Test fun newBackupKeepsADeliberate16() {
    val s = ReaderSettings(marginTopDp = 16, marginBottomDp = 16)
    val o = SettingsJson.readerToJson(s)
    assertEquals(40, o.getInt(VerticalMargin.KEY))
    assertEquals(16, SettingsJson.readerFromJson(o, ReaderSettings()).marginTopDp)
}
@Test fun backupWithoutMarginKeysKeepsTheDevice() {
    val r = SettingsJson.readerFromJson(JSONObject().put("r.fontSizeSp", 21.0), ReaderSettings(marginTopDp = 30, marginBottomDp = 30))
    assertEquals(30, r.marginTopDp)
}
@Test fun pageBreakTravelsInBackups()            // PARAGRAPH round-trips; an unknown name keeps base

// settings/UserStylesTest
@Test fun legacyStyleVerticalMarginsMove() {
    val o = UserStyles.toJson(UserStyle.from("a", ReaderSettings(marginTopDp = 16, marginBottomDp = 16)))
    o.remove(VerticalMargin.STYLE_KEY)
    assertEquals(40, UserStyles.fromJson(o)!!.marginTopDp)
    assertEquals(16, UserStyles.fromJson(UserStyles.toJson(UserStyle.from("b", ReaderSettings(marginTopDp = 16, marginBottomDp = 16))))!!.marginTopDp)
}
```

### 6.6 Emulator screenshots (UI owner, `tools/ci/screenshots.sh`, `[screens]`)

| Shot | Steps | Expect |
|---|---|---|
| `72_footer_toggle_same_text` | open the sample TXT; shot; popup → 아래 가운데 = 쪽 번호; shot | the content-box crop (80..640 × 80..1360 on the 720×1440 AVD) is pixel-identical; only the footer band differs |
| `73_progress_toggle_same_text` | 진행 막대 off | same crop identical |
| `74_margin_v_exact` | note the first line; 상하 여백 +10 | the first line of the new page is the same line (same first word) |
| `75_font_up_down` | 글자 크기 +1, then −1 | back to the exact first shot |
| `76_page_break_paragraph` | 페이지 나눔 = 문단 단위 | the current page's first line is unchanged; the next page starts at a paragraph start |
| `77_dialog_no_reflow` | fullscreen on; open 페이지 이동 (AlertDialog) and cancel | the page text is pixel-identical before/after (no relayout) |

---

## 7. Owners and phases (combined run: scroll SPEC names win)

| Owner | Work from this doc |
|---|---|
| **CONTRACT** (phase 0) | `engine/Layout.kt`: `PageBreakMode`, `LayoutConfig.pageBreak`, `SectionLayout.anchorBreak/anchorPage/anchorShifted`, `PageTally`. `engine/Typesetter.kt` **signatures** `layout(content, anchorBreak = -1)` / `count(content, anchorBreak)` with stub bodies that ignore the anchor (`// R3 stub (owner: ENGINE_RENDER)`). `settings/ReaderSettings.kt`: `marginTopDp/BottomDp = 40`, `VerticalMargin`, `pageBreak`. `settings/Settings.kt`: `r.pageBreak`, `r.marginBaseV` + migration. `data/SettingsJson.kt`: both keys, legacy rule. `settings/UserStyles.kt`: `marginBaseV`. `reader/LayoutKeys.kt` geometry **signature** (drop `statusPx`) + `GOLDEN_HASH_PARAGRAPH = "TBD"` placeholder (the golden test is `@Ignore`d until phase 2) — the only READER_CORE lines phase 0 touches. Tests §6.5; `LayoutKeysTest`/`ReaderReviewFixesTest` compile fallout. Docs: `ARCHITECTURE.md` pagination (anchor, PARAGRAPH) and geometry (U2) paragraphs; `R3_INTERFACES.md` rows for `PageBreakMode`, anchors, `VerticalMargin`, the status-in-margins rule. |
| **ENGINE_RENDER** | `TypesetPass` (anchor + PARAGRAPH, §4.2 / §5.3), `Typesetter` bodies, `render/StatusFit.kt`, `PageRenderer.drawStatus` fit + lane (§2.3); tests §6.1, §6.3; the fourth `lead` drop site; `StitchTest` variants. Reports the PARAGRAPH golden hash. |
| **READER_CORE** | `LayoutKeys` (§2.2, key `|pb=`), `BookSession` (§5.4), new `reader/Anchors.kt`, new `reader/InsetsGate.kt`, `ReaderActivity` (§5.1 wiring, §5.5); tests §6.2, §6.4. |
| **EXTRAS** | `ReadingSettingsPopup`: 상하 여백 stepper (§3.3), 페이지 나눔 segment + 외톨이 줄 방지 summary (§4.4), status-hidden note (§2.7); `SelectionController` origin fallback (§2.5). |
| **UI** | `PageTurningPage`: 상하 여백 stepper, 페이지 나눔 chooser, notes (§3.3, §4.4, §2.7); screenshots §6.6. |
| **Lead, phase 2** | sets `GOLDEN_HASH_PARAGRAPH` from ENGINE_RENDER's test message and un-ignores the test; checks §6.6. |

Order inside READER_CORE: `LayoutKeys` + `BookSession` first (they compile against the stubs and are testable on the
JVM), then `ReaderActivity`.

---

## 8. Device checks and risks

| Check | Pass |
|---|---|
| Comet, defaults: open a book reached with R2 | the same lines per page vertically as R2 (box height 1280 px); header chapter title centred in the top 40 dp |
| Toggle every status slot, progress line, status size 8 ↔ 16 | the text never moves; one e-ink update each |
| 상하 여백 −10 ↔ +10 | the first char of the page never changes |
| 글자 크기 +1, +1, −1, −1 | returns to the identical page |
| Rotate 5× (phone) | the first line never creeps |
| Fullscreen on, open 페이지 이동 / a rename dialog / the reading-settings popup, close | no relayout (RAPerf log shows no "relayout"; text identical) |
| Switch 줄 단위 ↔ 문단 단위 on a web novel | current page unchanged; later pages end at paragraph ends; page count grows ≈ 20 % |
| TXT: toggle 빈 줄 처리 / 원본 들여쓰기 제거 | the same sentence stays at the top |
| Reopen after rotating in the library | the saved first char is at the top |

Risks:
1. **Short page before the anchor** is visible when paging back — by design (user allowed it); one page, gone at the
   next relayout or reopen elsewhere.
2. **Page totals differ by ±1 between sessions** in the anchor section — accepted (§5.4).
3. **Vendor insets behaviour** (Comet nav bar that refuses to hide): the first insets at open are forced, so a
   permanent bar is applied once before the first page; a bar that appears later while focused is applied at once (a
   real change); only unfocused changes wait.
4. **PARAGRAPH mode near-empty pages** (worst fill 6 % measured) — the literal user rule; documented in the chooser
   text. A future "move only blocks ≤ ½ page" option is a one-line cap in `decideCut` if the user asks.
5. **JUMP anchor change** (page start instead of the exact target) — a relayout right after a mid-page search hit may
   push the hit to the next page on a font increase; the U6 rule ("first visible char stays first") is the user's.
