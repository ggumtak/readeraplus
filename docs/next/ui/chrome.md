# Reader chrome, status bar and pin: design spec (task #18, items 1, 2, 4, 5)

Status: design only, written read-only against the working tree of 2026-09-30 (R2 uncommitted, `LibrarySchema` v2
not shipped: HEAD is still v1). Another workflow was editing `ReaderActivity.kt` while this was written, so code is
cited by **function name**; the line numbers are from that snapshot and only approximate.
Density math below is for the Comet: 720×1440 px, density 2.0 (360×720 dp), font scale 1 (1 sp = 2 px).

Consistency: this spec fits `scratchpad/scroll/design-A-tall-section.md`, which reuses task #18's slot footer "as is"
(see §7). `SPEC.md` did not exist yet when this was written. Brightness item 3 is a separate spec: this one only
defines the options panel it plugs into (§5).

---

## 0. Decisions at a glance

| # | User feedback | Decision |
|---|---|---|
| 1 | Page number floats left of centre, looks cheap | The bottom bar's label row becomes a `FrameLayout`. The label is **centred on the full width**: 17 sp bold, tabular digits, no underline. Bookmark goes on the left; rotation lock and pin go on the right. There are no grey "selected" boxes: state is shown by the icon only (filled or outline). The top and bottom bars use one grid (§2). |
| 1 | "10 / 3614" or "10 중 3614" | **"10 / 3614"**. ReadEra's "10 중 3614" is a literal translation of "10 of 3614", and in Korean "A 중 B" means "B out of A", so it reads backwards. The correct Korean "3614 중 10" is longer and puts the changing number last. "N / M" is language-neutral, 3 chars shorter, fits a 5-digit book in the 160 dp label slot, and matches the footer's 쪽 번호 item, the TOC page column and the go-to dialog. |
| 2 | Choose left / centre / right footer items; nothing chosen = no footer | `enum StatusItem` (12 entries). The **header and the footer** get 3 slots each (`headerLeft/Center/Right`, `footerLeft/Center/Right`). Defaults: header = chapter title in the centre (today's look); footer = all 없음, so **no footer band**. |
| 2 | ReadEra-style progress line | `progressBar: Boolean = true`. It is a 1 px line in the bottom margin with end caps, a 7 dp position dot and optional chapter ticks, all in pure black/white (§6.4). With the default margins it costs no text height and needs no relayout. |
| 4 | Pin must pin a return point, not the chrome; the pages flip wildly | `pinChrome` and `applyPinnedArea`'s resize path are **deleted**. Root cause confirmed in §1. The pin now sets the book's **return point**, persisted in `book_prefs.return_mark`. The strip docked in the bottom bar reads "‹ N 페이지로 · 지우기 · M 페이지로 ›". Jumps from TOC, search, go-to, the seek bar and links use the **same** component. The old "← 돌아가기 (p. N)" chip becomes the strip's floating form while the chrome is hidden (§4). |
| 5 | The icon right of the brightness slider opens options | The ▾ button opens an inline panel under the row: "스와이프로 밝기 조절" with a subtitle and a switch, plus a slot for item 3's row. The brightness row is **never hidden** any more (`PREF_BRIGHTNESS_COLLAPSED` and the `brightnessShow` button are deleted). |
| 6 | "Care much more about the UI" | §2 sets one visual system for the whole chrome: a 16 dp optical edge, 44/48/56 dp bands, 1 px black hairlines between bands, three text sizes, no grey selection boxes, and single-line ellipsized titles. |

---

## 1. The pin "flipping" bug: root cause (confirmed in code) and the removal

### 1.1 What the code does today

`ReaderChrome` wires `pin` to `Actions.onPin()`. `ReaderActivity.chromeActions.onPin` calls `togglePin()`
(≈ l.1913), which does `saveApp(app.copy(pinChrome = on))`, then calls `applyPinnedArea()` (or `setChromeVisible(true)`).

1. **A resize, a relayout and a recount on every pin press and on every menu open or close.**
   `applyPinnedArea()` (≈ l.1866) sets the **PageView's** `topMargin`/`bottomMargin` to the bar heights whenever
   `pinnedArea()` (`app.pinChrome && (chromeVisible || pinPending)`) is true, and back to the insets when it is false.
   A margin change gives `PageView.onSizeChanged` → `onViewSizeChanged` → `BookSession.setViewport(w, h)` →
   `rebuild()`. That makes a new generation and a new page-count key (the key hashes the content box), so the code
   calls `relayout()` and `s.startCounting()` from scratch. `setChromeVisible()` calls `applyPinnedArea()` every
   time, so with the pin on, **every menu open and every menu close reflows the page and restarts page counting**.
   The label and the seek bar then jump between estimates ("3 / 167" → "3 / 190" → "3 / 167"), and each step is a
   full e-ink update.
2. **With the chrome up, taps turn pages instead of closing the menu.** `handleTap()` (≈ l.2048) starts with
   `if (chromeVisible && !app.pinChrome) { closeChrome(); return }`. `pageCallbacks.onSwipe` has the same guard.
   With the pin on, a tap on the page runs the tap-zone action instead: in `LEFT_RIGHT` the left third goes back and
   the rest goes forward. The taps the user makes to get rid of the menu therefore flip pages back and forth. That
   is the "미친듯이 왔다갔다".
3. **The mode is sticky.** `pinChrome` is an `AppSettings` field. Every book then opens through the `pinPending`
   path in `startOpen` (≈ l.765): it is laid out in the shrunken box, the chrome is forced open in `showPage`
   (`pinShown`, ≈ l.1050), and the book is laid out again when the user closes the chrome.

There is no infinite callback loop in the code: `barsResized` → `onBarsResized` fires only when a bar's own height
changes. The "flipping" is (1) plus (2): reflows on every chrome toggle, and page turns on every tap meant to close the
menu.

### 1.2 Why removal fixes it

After the change, the PageView's margins come **only from the system-bar insets**. Showing or hiding the chrome only
changes the visibility of overlay views, so `onSizeChanged` cannot fire. A tap with the chrome visible always closes
it. Nothing persists a chrome mode.

### 1.3 Removal list (exhaustive)

| Where | Symbol | Change |
|---|---|---|
| `settings/ReaderSettings.kt` (contract) | `AppSettings.pinChrome` | delete (and its KDoc) |
| `settings/Settings.kt` (contract) | `saveApp` `putBoolean("a.pinChrome")`, `loadApp` `pinChrome =` | delete; `saveApp` adds `remove("a.pinChrome")` so the stale raw pref never travels as an "unmapped" key |
| `data/SettingsJson.kt` (contract) | `appToJson .put("a.pinChrome")`, `appFromJson pinChrome =` | delete. Add `"a.pinChrome"` to a `DROPPED_KEYS` set that `addUnmapped` and `unmappedFromJson` skip, so an old backup's value is ignored, never restored raw |
| `test/.../data/SettingsMappingTest.kt` | uses `pinChrome = true` | use another boolean (e.g. `bookmarkByTouch = false`) |
| `reader/ReaderActivity.kt` | fields `pinShown`, `pinPending` | delete |
| | `viewPart()` entry `a.pinChrome` | delete |
| | `startOpen`: `if (app.pinChrome && !pinShown) { pinPending = true; applyPinnedArea() }` | delete |
| | `showPage`: `if (app.pinChrome && !pinShown && !chromeVisible) { … setChromeVisible(true) }` | delete |
| | `closeCurrentBook`: `pinShown = false` | delete |
| | `setChromeVisible`: `pinPending = false`, `chrome.setPinned(app.pinChrome)`, `applyPinnedArea()` | delete; add `bindReturn()` (§4.6) |
| | `pinnedArea()`, `chromeBarHeights()`, `togglePin()` | delete |
| | `applyPinnedArea()` | rename to `applyPageInsets()`: `topMargin = insets[1]`, `bottomMargin = insets[3]`, nothing else. Callers `applyInsets`, `applyAppSettings`, `onBarsResized` |
| | `applyAppSettings`: `chrome.setPinned(app.pinChrome)` | delete (`chrome.setPinned(returns.pinned)` moves to `bindChrome`) |
| | `buildViews`: `barsResized` on `chrome.top`/`chrome.bottom` | delete those two registrations: the floating chip never shows with the chrome (§4.5). Keep the listener for extras overlays (TTS / search bars) |
| | `onBarsResized()` | only `updateChipPosition()` |
| | `pageCallbacks.onSwipe`: `if (chromeVisible && !app.pinChrome)` | `if (chromeVisible)` |
| | `handleTap`: `if (chromeVisible && !app.pinChrome)` | `if (chromeVisible)` |
| | `openReadingSettings()` pinned branch | delete: always `ReaderPanels.showReadingSettings(this, chrome.gear)` |
| | `chromeActions.onPin = togglePin()` | `onPinHere()` (§4.6) |
| `reader/ReaderChrome.kt` | `Actions.onPin`, `pin` "메뉴 고정", `setPinned` (with `isSelected`), `boundPinned` | the pin button stays but means "return point" (§2.3, §4) |
| `reader/extras/ReadingSettingsPopup.kt` | `val hideBars = !Settings.app.pinChrome` and the `else if (anchor…)` branch | always hide the bars: `anchorBottom = Overlay.topInset(root)`. Update the class KDoc |
| `ui/settings/PageTurningPage.kt` | `toggleRow("메뉴 고정", …)` (≈ l.91) | delete |
| `docs/ARCHITECTURE.md`, scroll design A §4.3 | "pinned chrome" relayout row | delete (doc) |

---

## 2. One visual system for the chrome

### 2.1 Grid and type (all bars, the strip, the options panel)

| Token | Value | Use |
|---|---|---|
| Optical edge | **16 dp** from the screen edge | the first text or glyph on every row: title, strip label, options title. Icon rows use 4 dp row padding plus a 48 dp button with its 24 dp glyph centred, so the glyph box starts at 16 dp as well |
| Band heights | 56 (actions) · 48 (brightness, label, seek) · 44 (strip) · ≥ 56 (two-line option rows) | every band a multiple of 4 dp; tap targets ≥ 44 dp |
| Hairline | 1 **physical** px, `Ink.LINE` (black) | between every band; the bars' inner edges |
| Primary text | 17 sp **bold** | book title, page label |
| Secondary text | 15 sp regular | strip items, option titles |
| Tertiary text | 13 sp regular `Ink.GRAY` (#555) | option subtitles |
| Disabled text | `Ink.DISABLED` (#999) | strip item "you are here" |
| State | **icon swap only** (outline ↔ fill / variant). Never `isSelected` with the grey `pressableBackground` square, which is the cheap-looking grey box on the auto-brightness button in `ours/13_txt_chrome.png` | rotation, pin, auto brightness |
| Digits | `fontFeatureSettings = "tnum"` | page label, strip pages: the label width does not jitter while paging |
| Motion | none; `animationStyle = 0`; no ripples (`pressableBackground`) | |

### 2.2 Top bar (`ReaderChrome.top`)

```
┌──────────────────────────────────────────────┐  (top inset padding)
│ ←                    🔊  🔍  ☰  ⚙  ⋮        │  actions 56 dp: [back 48] spacer [tts][search][toc][settings][more], row padding 4 dp
│ 배드 본 블러드 1-353 완…                       │  title: 17 sp bold, maxLines 1, END ellipsis, padding 16/0/16/12 dp
├──────────────────────────────────────────────┤  1 px
│ Ⓐ  ━━━━━━━━●───────────────────────   ▾     │  brightness 48 dp: [auto 48] [SeekBar w=1, pad 12 dp h] [options 48], row padding 4 dp
├──────────────────────────────────────────────┤  1 px (only while the options panel is open)
│ 스와이프로 밝기 조절                      (◯ ) │  option row ≥ 56 dp, 16 dp sides, 8 dp top/bottom
│ 화면 왼쪽 가장자리를 위아래로 밀어 밝기를 바꿉니다  │  13 sp gray, ≤ 2 lines
│ [item-3 row, if that spec adds one]           │
└──────────────────────────────────────────────┘  1 px (the bar's inner edge)
```
- Title: one line instead of today's 2 lines at 18 sp, so the bar height no longer depends on the title length.
- The `brightnessShow` button in the title row is deleted (the row is never hidden).
- Top bar height: 56 + 32 + 1 + 48 + 1 = **138 dp** closed, ≈ 196 dp with the options panel open.

### 2.3 Bottom bar (`ReaderChrome.bottom`)

```
┌──────────────────────────────────────────────┐  1 px
│ ‹ 10 페이지로          지우기          512 페이지로 › │  return strip 44 dp (GONE when there is no return point), §4.5
├──────────────────────────────────────────────┤  1 px (part of the strip)
│ 🔖               10 / 3614                ⟳  📌 │  label row 48 dp, FrameLayout
│ ⏮  ━━━━━━●────────────────────────────────  ⏭ │  seek row 48 dp: [prev chapter 48][SeekBar w=1][next chapter 48], padding 4 dp
└──────────────────────────────────────────────┘  (bottom inset padding)
```
Label row (a `FrameLayout`, `minimumHeight = 48 dp`):
- `bookmark` (48×48, `Gravity.START|CENTER_VERTICAL`, `marginStart 4 dp`): icon only, `ic_bookmark` / `ic_bookmark_fill`.
- `rightGroup`: a horizontal `[rotation 48][pin 48]` (`Gravity.END|CENTER_VERTICAL`, `marginEnd 4 dp`).
  - rotation: `ic_screen_rotation` / `ic_screen_lock_rotation`, no `isSelected`.
  - pin: `ic_push_pin` (no pinned return point in this book) / `ic_push_pin_fill` (pinned). contentDescription is
    "이 쪽을 돌아올 곳으로 고정" / "고정한 쪽 해제·옮기기"; the long-press toast shows it.
- `pageLabel`: `label("", 17f, bold = true, maxLines = 1)`, `LayoutParams(WRAP_CONTENT, 48 dp, Gravity.CENTER)`,
  `gravity = CENTER`, `minWidth = 96 dp`, `padding = 16 dp` left and right, `fontFeatureSettings = "tnum"`,
  `pressableBackground()`, contentDescription "페이지 이동". **No underline** (drop the working tree's
  `UNDERLINE_TEXT_FLAG`: an underline is a web-link look). Tap → `onPageLabel()` (go-to dialog), unchanged.
  - `maxWidth = rowWidth − 2 × RESERVE`, with `RESERVE = 4 + 48 + 48 = 100 dp` (the wider side), set in an
    `OnLayoutChangeListener` only when the row width changed. The label stays centred on the **full width** and can
    never run under an icon. On the Comet that leaves 160 dp; "12345 / 23259" at 17 sp bold tnum needs ≈ 114 dp.
- Seek bar (`einkSeekBar`): thumb 16 dp (was 20), `thumbOffset 8 dp`, horizontal padding 12 dp, progress tint black,
  background tint `Ink.DISABLED`.
- Bottom bar height: 1 + 48 + 48 = **97 dp**, or 142 dp with the strip.

### 2.4 `ReaderChrome` API after the change (READER_A)

```kotlin
internal class ReaderChrome(ctx: Context, actions: Actions, returnStrip: ReturnStrip) {
    interface Actions {
        fun onBack(); fun onTts(); fun onSearch(); fun onToc()
        fun onSettings(anchor: View); fun onMore(anchor: View)
        fun onBrightnessAuto()
        fun onBrightness(value: Float, done: Boolean)
        /** The options panel's "스와이프로 밝기 조절" switch. */
        fun onBrightnessSwipe(on: Boolean)
        fun onPageLabel()
        fun onChapter(next: Boolean)
        fun onRotation(); fun onRotationChooser()
        fun onBookmark()
        /** The pin: make the page on screen the book's return point (or release it, on that page). */
        fun onPinHere()
        fun onSeekStart(); fun onSeekPreview(progress: Int): String; fun onSeekDone(progress: Int)
        // deleted: onPin (메뉴 고정), onBrightnessCollapsed
    }
    fun setPinned(pinned: Boolean)                     // icon only
    fun setRotationLocked(locked: Boolean)             // icon only (drop isSelected)
    fun setBookmarked(on: Boolean)
    fun setBrightness(value: Float, systemValue: Float) // §5: auto look vs manual look
    fun setBrightnessSwipe(on: Boolean)
    /** Item 3 (brightness spec) adds its row to the options panel through this. */
    fun addBrightnessOption(row: View)
    // deleted: setBrightnessCollapsed, brightnessShow, boundPinned-with-isSelected
}
```
`ReturnStrip.dock` is inserted into `bottom` at index 1 (after the top hairline) by the constructor.

---

## 3. Status items and slots (settings contract)

### 3.1 `StatusItem` (new, `settings/ReaderSettings.kt`, contract)

```kotlin
/**
 * What one slot of the page's status lines shows; the header and the footer share the list. Stored by name
 * ("r.footerLeft" = "CLOCK"): never rename an entry, append new ones. The declaration order is the chooser order.
 */
enum class StatusItem(val label: String, val short: String) {
    NONE("없음", "없음"),
    PAGE("쪽 번호", "쪽 번호"),                       // "12 / 3259"
    PERCENT("진행률", "진행률"),                      // "34%"
    CHAPTER("챕터 제목", "챕터 제목"),                 // current chapter (TOC entry, else section title, else book)
    BOOK_TITLE("책 제목", "책 제목"),
    CLOCK("시계", "시계"),                           // "14:05" / "2:05" (12 h, no 오전/오후, as today)
    BATTERY("배터리", "배터리"),                      // outline icon + "80"
    CLOCK_BATTERY("시계 · 배터리", "시계·배터리"),        // "14:05" + 0.5 em + icon + "80" (the 마루뷰어 corner)
    CHAPTER_PAGES_LEFT("이 챕터 남은 쪽", "남은 쪽"),     // "챕터 5쪽 남음" / "챕터 마지막 쪽"
    EPISODE("회차", "회차"),                          // R2 T1-5: "123/540화" or "87/612"
    TIME_LEFT_EPISODE("이 화 남은 시간", "화 남은 시간"),  // R2 T1-7: "이 화 3분"
    TIME_LEFT_BOOK("책 남은 시간", "책 남은 시간");       // R2 T1-7: "책 7시간 20분"

    /** Titles: the only items shortened with "…" when their slot is narrow; numbers never are. */
    val elastic: Boolean get() = this == CHAPTER || this == BOOK_TITLE
}
```
`CLOCK_BATTERY` is the only combined item. Three single slots can hold four items at most with it
(e.g. 마루뷰어's [battery+time] [title] [page]). It also keeps the migration of today's "34% · 14:05 · ▭100" nearly
lossless (§3.4).

### 3.2 `ReaderSettings` fields (contract)

These replace `showHeader, showFooter, footerPage, footerChapterLeft, footerEpisode, footerTimeLeft, footerPercent,
footerClock, footerBattery` (all removed: one source of truth).
```kotlin
    /** Status line at the top: left / centre / right (all NONE = no header band). */
    val headerLeft: StatusItem = StatusItem.NONE,
    val headerCenter: StatusItem = StatusItem.CHAPTER,
    val headerRight: StatusItem = StatusItem.NONE,
    /** Status line at the bottom (all NONE = no footer band: the default, user request). */
    val footerLeft: StatusItem = StatusItem.NONE,
    val footerCenter: StatusItem = StatusItem.NONE,
    val footerRight: StatusItem = StatusItem.NONE,
    /** ReadEra-style reading-progress line along the bottom edge (in the bottom margin). */
    val progressBar: Boolean = true,
    val statusFontSizeSp: Float = 11f,          // unchanged
```
Class body (computed, not part of `equals`):
```kotlin
    val hasHeader: Boolean get() = headerLeft != StatusItem.NONE || headerCenter != StatusItem.NONE || headerRight != StatusItem.NONE
    val hasFooterText: Boolean get() = footerLeft != StatusItem.NONE || footerCenter != StatusItem.NONE || footerRight != StatusItem.NONE
    fun shows(item: StatusItem): Boolean = headerLeft == item || headerCenter == item || headerRight == item ||
        footerLeft == item || footerCenter == item || footerRight == item
```
Companion: `const val PROGRESS_LANE_DP = 12` (shared by LayoutKeys and PageRenderer). `TIME_LEFT_*` move to
`StatusMigration`.

Call sites to update (all found by grep):

| File | Now | Then |
|---|---|---|
| `reader/LayoutKeys.kt` | `geometry`, `layoutPart` | §6.1 |
| `reader/BookSession.kt` `rebuild` | `statusFontSizeSp` | unchanged |
| `reader/extras/SelectionController.kt` ≈ l.281 | `if (s.showHeader) …` | `if (s.hasHeader) …` |
| `render/Covers.kt` ≈ l.205 | `showHeader = false, showFooter = false` | `headerCenter = StatusItem.NONE, progressBar = false` |
| `render/PageRenderer.kt` | footer drawing | §6.3 |
| `reader/ReaderActivity.kt` | `buildDecor`, `episodeLabel`, `timeLeftLabel`, `afterOpen`/`reopenDocument` `footerEpisode` | §6.5 (`shows(EPISODE)`) |
| popup, `PageTurningPage` | switch rows | §6.6 |
| tests | `UserStylesTest`, `ReaderReviewFixesTest`, `CompactSettingsTest`, `LayoutKeysTest`, `SettingsJson*Test`, `SettingsStoreTest`, `ReaderFormatTest`, `ReaderR2FeaturesTest` | same fields in slot form |

`UserStyle`, `StylePreset`: they do not touch status fields, so there is no change. The popup's "기본값 복원"
(`ReaderSettings()`) now means: header = chapter title, no footer, progress bar on.

### 3.3 Persistence (`settings/Settings.kt`, contract)

New keys: `r.headerLeft`, `r.headerCenter`, `r.headerRight`, `r.footerLeft`, `r.footerCenter`, `r.footerRight`
(enum names, tolerant via `enumOr`: an unknown name falls back to the default), and `r.progressBar` (bool).
- `saveReader`: puts the 7 keys, and `for (k in StatusMigration.LEGACY_KEYS) remove(k)` in the same editor.
- `loadReader`:
  ```kotlin
  val mig = if (p.contains(StatusMigration.MARKER_KEY)) null else StatusMigration.migrate(StatusMigration.Legacy.from(p))
  headerLeft = mig?.headerLeft ?: enumOr(p.getString("r.headerLeft", null), d.headerLeft), … // ×6
  progressBar = p.getBoolean("r.progressBar", d.progressBar),
  ```
  Cold-start cost: 9 extra `contains`/`getX` map lookups, only until the first save. That is plain prefs, which
  R2 rule 2 allows.

### 3.4 Migration (`settings/StatusMigration.kt`, new, contract, pure, tested)

```kotlin
object StatusMigration {
    const val MARKER_KEY = "r.footerLeft"        // present = already slot-based
    val LEGACY_KEYS = listOf("r.showHeader", "r.showFooter", "r.footerPage", "r.footerChapterLeft", "r.footerEpisode",
        "r.footerTimeLeft", "r.footerPercent", "r.footerClock", "r.footerBattery")
    const val LEGACY_TIME_LEFT_OFF = 0; const val LEGACY_TIME_LEFT_EPISODE = 1; const val LEGACY_TIME_LEFT_BOOK = 2

    /** The ≤ R2 status fields; null = key absent. */
    class Legacy(val showHeader: Boolean?, val showFooter: Boolean?, val page: Boolean?, val chapterLeft: Boolean?,
                 val episode: Boolean?, val timeLeft: Int?, val percent: Boolean?, val clock: Boolean?, val battery: Boolean?) {
        companion object { fun from(p: SharedPreferences): Legacy; fun from(o: JSONObject): Legacy }  // JSON: BackupJson-style tolerant reads
    }
    class Slots(val headerLeft: StatusItem, val headerCenter: StatusItem, val headerRight: StatusItem,
                val footerLeft: StatusItem, val footerCenter: StatusItem, val footerRight: StatusItem) {
        fun applyTo(s: ReaderSettings): ReaderSettings
    }
    fun migrate(l: Legacy): Slots
}
```
Rules. Missing values take the old defaults: showHeader T, showFooter T, page T, chapterLeft F, episode F, timeLeft 0,
percent T, clock T, battery T.
1. Header: `showHeader` → `headerCenter = CHAPTER`, else all NONE. Header left/right are always NONE.
2. Footer: `!showFooter` → all NONE.
3. **Untouched old default** (page T, chapterLeft F, episode F, timeLeft 0, percent T, clock T, battery T) → all
   NONE, which is the user's new default. `Settings.saveReader` writes every key on any change, so "stored" cannot be
   told apart from "chosen". The only honest signal is "differs from the old defaults". The user asked for no footer
   unless chosen, and a fresh install lands here too.
4. **Customized**: `queue = [PAGE if page, EPISODE if episode, CHAPTER_PAGES_LEFT if chapterLeft,
   TIME_LEFT_EPISODE/BOOK if timeLeft 1/2, PERCENT if percent]`,
   `rightItem = CLOCK_BATTERY if clock && battery, CLOCK if clock, BATTERY if battery, else null`;
   `footerLeft = queue[0] ?: NONE`, `footerCenter = queue[1] ?: NONE`, `footerRight = rightItem ?: queue[2] ?: NONE`.
   Queue items past the slots are dropped. Worst case, everything on, drops 챕터 남은 쪽, 남은 시간 and %. Examples:
   page + % + clock → `12 / 3259 | 34% | 14:05`; % + clock + battery → `34% | – | 14:05 ▭80`.

`progressBar` is absent in every legacy store, so it takes its default (on) for everyone.

### 3.5 Backups (`data/SettingsJson.kt`, contract)

- `readerToJson`: put the 7 new keys. Legacy keys are not written.
- `readerFromJson(o, base)`: if `o` has any slot key, read each (`enumOf`, falling back to base's value). Else, if
  `o` has any `LEGACY_KEYS`, use `StatusMigration.migrate(Legacy.from(o)).applyTo(…)`. Otherwise keep `base`. Then
  `progressBar = BackupJson.bool(o, "r.progressBar", base.progressBar)`.
- `DROPPED_KEYS = LEGACY_KEYS + "a.pinChrome"`: skipped by `addUnmapped`, which would otherwise export a device's
  not-yet-migrated raw legacy prefs, and by `unmappedFromJson`.
- Old builds restoring a new backup ignore the unknown keys (typed mapping, `unmappedFromJson` needs the key to exist
  on the device). This is a personal app that only upgrades, so that is acceptable.

---

## 4. Pin = return point (unified with the return chip)

> **2026-10-05 (user):** the two-place model below is replaced by a back / forward history as ReadEra's (UI_SPEC §3):
> the pin saves this page as a place to go back to; the row shows "‹ N쪽으로" only while there is a place back and
> "M쪽으로 ›" only while there is one ahead. Every remembered jump's origin is a place (also two seeks with no page
> turned between them), and pinning never hides an older place on the row.

### 4.1 Model: two places, fixed meaning

| Slot | Label | Meaning | Set by |
|---|---|---|---|
| **mark** (left) | "‹ N 페이지로" | the book's return point: **pinned** (persisted) or **temporary** (a jump's origin) | pin button; a remembered jump when there is no pinned mark |
| **other** (right) | "M 페이지로 ›" | the other place: where you were when you used the mark, or a jump's origin when a pinned mark exists | using the mark; a jump while pinned |
| centre | "지우기" | clears both (and the persisted pin) | |

A jump **never overwrites a pinned mark**. Explicit beats automatic, and the pinned page stays reachable "언제든". The
place you jumped from is not lost either: it becomes `other`.

### 4.2 State machine (`reader/ReturnPoints.kt`, new, READER_A, pure, JVM-tested)

```kotlin
internal class ReturnPoints {
    enum class Chip { NONE, MARK, OTHER }
    var mark: DocPosition? = null; private set
    var pinned = false; private set
    var other: DocPosition? = null; private set
    /** Which place the floating chip offers (only while the chrome is hidden). */
    var chip = Chip.NONE; private set
    private var turns = 0
    val isEmpty: Boolean get() = mark == null && other == null

    /** Pin at [here]. On the pinned page itself → un-pin (clear); elsewhere → pin here (moves the pin). Returns [pinned]. */
    fun pin(here: DocPosition, onMarkPage: Boolean): Boolean
    /** A remembered jump left [from] ([fromOnMarkPage]: it left the mark's page). [chrome]: the chrome is visible. */
    fun jumped(from: DocPosition, fromOnMarkPage: Boolean, chrome: Boolean)
    /** "‹ N 페이지로" tapped at [here]: the target, or null when already on the mark's page. */
    fun useMark(here: DocPosition, onMarkPage: Boolean): DocPosition?
    /** "M 페이지로 ›" tapped at [here]. */
    fun useOther(here: DocPosition, onMarkPage: Boolean): DocPosition?
    fun clear()
    /** A manual page turn; true when the floating chip hides now (after [CHIP_TURNS] = 2, as today). */
    fun manualTurn(): Boolean
    /** The chrome was shown (the docked strip takes over) or the chip's ✕ was tapped. */
    fun hideChip()
    /** The persisted pin, read after the first page. */
    fun restorePinned(pos: DocPosition)
    /** A re-parse: temporary places are gone; the pin moves to [pinnedPos] (null = dropped). */
    fun reparsed(pinnedPos: DocPosition?)
}
```
Transitions:
- `pin(here, onMark)`: if `pinned && onMark` → `clear()`. Else `mark = here; pinned = true; other = null; chip = NONE`.
- `jumped(from, fromOnMark, chrome)`: if `mark == null || !pinned` → `mark = from; pinned = false; other = null;
  chip = if (chrome) NONE else MARK`. Else (pinned) → `other = if (fromOnMark) null else from;
  chip = if (chrome) NONE else if (fromOnMark) MARK else OTHER`. Then `turns = 0`.
- `useMark(here, onMark)`: `if (mark == null || onMark) null else { other = here; mark }`.
- `useOther(here, onMark)`: `val t = other ?: return null; other = if (onMark) null else here; t`. Tapping left then
  right returns you to where you were: a ReadEra-like toggle between two places.
- `manualTurn()`: `if (chip == NONE) false else if (++turns >= 2) { chip = NONE; true } else false`.
- `restorePinned(pos)`: if `pinned` → no-op. If a temporary mark exists, it moves to `other` (a pin outranks a jump).
  Then `mark = pos; pinned = true`.
- `reparsed(p)`: `other = null; chip = NONE`; if `pinned && p != null` → `mark = p`, else `mark = null; pinned = false`.

### 4.3 Persistence: contract change (DATA + frozen `LibrarySchema`)

- `data/LibrarySchema.kt` (contract): `CREATE_BOOK_PREFS` gains **`return_mark TEXT`**, the book's pinned return
  point (`ReturnMarkCodec` text; NULL = none). v2 is **unreleased** (HEAD `DB_VERSION = 1`), so it is part of v2's
  single schema bump and needs no new version or ALTER. `LibrarySchemaV2Test` checks the column. If any v2 build
  shipped before this lands, add `AddedColumn("book_prefs", "return_mark", 3, "ALTER TABLE book_prefs ADD COLUMN
  return_mark TEXT")` with `DB_VERSION = 3` instead.
- `data/BookPrefs.kt` (DATA):
  ```kotlin
  /** This book's pinned return point (ReturnMarkCodec text), or null. Blocking, IO, never throws for a missing row. */
  fun returnMark(bookId: Long): String?
  /** Stores [value] (null clears it), keeping the row's other columns (UPDATE, then INSERT when no row changed). */
  fun setReturnMark(bookId: Long, value: String?)
  ```
  The backup exports it in the book's `book_prefs` entry, keyed by path like the others (`"returnMark"`). Old
  backups without it restore unchanged. `deleteBookRows` already deletes the row, and `resetProgress` ("읽은 기록
  초기화") also clears `return_mark`.
- Why not the `reader_text_positions` prefs file: it is not in backups (nor in scroll design A part C's auto-backup)
  and it outlives deleted books. That remains the fallback if the schema change is refused: key `m<bookId>`, same
  codec.
- **Codec** (`ReturnMarkCodec` in `reader/ReturnPoints.kt`, pure): `"m1|<section>|<offset>|<charFraction>|<textSignature or empty>"`.
  `decode` is tolerant: it returns null for a bad prefix, bad numbers, NaN or a negative section/offset, and clamps
  the fraction to 0..1. On restore, if the book is TXT and the stored signature ≠ the current
  `LayoutKeys.textSignature(settings, format, encoding)`, use `counts.locateFraction(fraction)` (the same rule as
  `TextPositions`). Otherwise use `DocPosition(section.coerceIn, offset.coerceIn)`. EPUB signatures are null, so
  EPUB always uses (section, offset).
- Load: `afterOpen` → `ReaderIo.launch { v = BookPrefs.returnMark(id) }` → main → `returns.restorePinned(decoded)`
  → `bindReturn()`. **Nothing on the open path.** Save: on pin, re-pin, clear and reparse, via
  `ReaderIo.launch { BookPrefs.setReturnMark(id, text) }`.

### 4.4 When each presentation shows

| Chrome | What shows | Rule |
|---|---|---|
| **visible** | **docked strip** (`ReturnStrip.dock`, top of the bottom bar) | shown iff `!returns.isEmpty`. Left item iff `mark != null`, drawn **disabled grey** (not clickable) while the page on screen is the mark's page (ReadEra shows "< 10 페이지로" while on page 10: immediate feedback that the pin took). Right item iff `other != null && !isOnCurrentPage(other)`. Centre "지우기" whenever the strip shows. The floating chip is always hidden |
| **hidden** | **floating chip** (bottom-left, as today) | shown iff `returns.chip != NONE`. That happens only after a remembered jump made **with the chrome hidden** (TOC, search, bookmark/quote lists, links, go-to), until 2 manual turns, ✕, or the chrome opening (`hideChip()`). The chip shows exactly the strip's label for that place ("‹ 12 페이지로" for MARK, "12 페이지로 ›" for OTHER) plus ✕. **✕ only hides; 지우기 clears**. Pinned marks never float over the page: the page stays clean |

Remembered jumps are unchanged: `goTo(remember = true)`, `goToPage(remember = true)`, `goToProgress`, seek-bar
release and links. Chapter prev/next stays `remember = false`, as do auto turn and TTS. A seek-bar jump happens with
the chrome up, so its origin appears immediately as "‹ N 페이지로" in the docked strip, as in ReadEra.

### 4.5 `ReturnStrip` views (`reader/ReturnStrip.kt`, new, READER_A)

```kotlin
/** The return point's two presentations (docked row in the bottom bar, floating chip) with one label logic. */
internal class ReturnStrip(ctx: Context, private val actions: Actions) {
    interface Actions { fun onReturnMark(); fun onReturnOther(); fun onReturnClear(); fun onReturnChipClose() }
    /** 44 dp row + 1 px bottom hairline; GONE when empty. Added into ReaderChrome.bottom at index 1. */
    val dock: LinearLayout
    /** Bordered chip [label | ✕], added by ReaderActivity to root at BOTTOM|START (position logic of today's chip). */
    val chip: LinearLayout
    /** Page numbers < 1 = hidden; [markHere]: the mark's page is on screen (left item disabled). */
    fun bindDock(markPage: Int, markHere: Boolean, otherPage: Int)
    fun showChip(page: Int, toMark: Boolean)
    fun hideChip()
}
```
Dock row (`FrameLayout`, 44 dp):
- left `TextView`: `START|CENTER_VERTICAL`, height MATCH, `paddingStart 12 dp`, `paddingEnd 12 dp`, 15 sp regular,
  compound drawable `ic_chevron_left` at 18 dp tinted like the text, `drawablePadding 2 dp`. That puts the chevron
  glyph on the 16 dp edge. Text "12 페이지로", tnum, `pressableBackground`.
- centre "지우기": `CENTER`, `minWidth 72 dp`, `padding 16 dp` h, 15 sp. It sits on the page label's vertical axis
  below it.
- right `TextView`: mirrored, `ic_chevron_right` at the end.
- Label strings are rebuilt only when the page number changes (cached last value), so `bindDock` on a turn with the
  chrome up allocates nothing. The disabled state sets text and chevron to `Ink.DISABLED`, `isClickable = false`,
  contentDescription "지금 보는 쪽이 돌아올 곳입니다".
- Chip: `borderBox()`, `[label 44 dp: chevron + "12 페이지로", 15 sp, padding 14/12 dp] | 1 px vertical hairline |
  [✕ 44 dp]`. `ReaderFormat.returnChip` ("← 돌아가기 (p. N)") is deleted; `ReaderFormat.returnLabel(page) =
  "$page 페이지로"`.

### 4.6 `ReaderActivity` wiring (READER_A)

- `private var returns = ReturnPoints()`; `private lateinit var returnStrip: ReturnStrip`. This replaces
  `returnStack`, `MAX_RETURN_STACK`, `chip`, `chipLabel` and `turnsSinceJump`.
- `pushReturn(pos)` → `returns.jumped(pos, fromOnMarkPage = returns.mark?.let(::isOnCurrentPage) == true,
  chrome = chromeVisible); bindReturn()`. The callers `goTo`, `goToPage`, `goToProgress` and `followLink` keep their
  guards.
- `onManualTurn()` → `if (returns.manualTurn()) bindReturn()`.
- `onPinHere()` → `returns.pin(currentPosition(), returns.mark?.let(::isOnCurrentPage) == true);
  chrome.setPinned(returns.pinned); persistPin(); bindReturn()`.
- Strip actions: `onReturnMark` → `returns.useMark(currentPosition(), onMark)?.let { jumpTo(it.section, it.offset, -1) }`;
  `onReturnOther` likewise; `onReturnClear` → `returns.clear(); persistPin(); chrome.setPinned(false)`;
  `onReturnChipClose` → `returns.hideChip()`. Each then calls `bindReturn()`. The chrome stays open while the strip
  is used: the page changes between the bars, and `showPage` → `bindChrome` rebinds.
- `bindReturn()`: if `chromeVisible` → `returnStrip.hideChip()` and `bindDock(pageOf(mark), mark != null &&
  isOnCurrentPage(mark), pageOf(other) unless isOnCurrentPage(other))`. Else → `bindDock(-1, false, -1)` (the dock
  is invisible anyway) and show or hide the chip per `returns.chip`. `pageOf` = `globalPageOf` (an estimate until
  counted).
- Called from: `bindChrome()`, which runs on every shown page while the chrome is up; `setChromeVisible(true)`
  (after `returns.hideChip()`); `setChromeVisible(false)`; `sessionListener.onCountsChanged(complete = true)`
  (labels become exact); `pushReturn`; `onManualTurn`.
- `persistPin()`: `val m = returns.mark.takeIf { returns.pinned }`, then `text = m?.let { ReturnMarkCodec.encode(it,
  counts.charProgress(it.section, it.offset), textSignature()) }`, then `ReaderIo.launch { BookPrefs.setReturnMark(id,
  text) }`.
- `reopenDocument`: replace `dismissReturnChip()` with the remapped pin: `pinnedPos = if (same section count &&
  EPUB) mark else s.counts.locateFraction(markFraction)`. `markFraction` is computed with the **old** counts before
  the switch, like `ratio`. Then `returns.reparsed(pinnedPos); persistPin()`.
- `closeCurrentBook`: `returns = ReturnPoints(); returnStrip.hideChip()`.
- `updateChipPosition()`: only the hidden-chrome branch remains: `insets[3] + 6 dp`, and above extras bars
  (`overlayBarsHeight`), as today.

---

## 5. Brightness row and options panel (item 5; item 3's fix plugs in)

- Row: `[auto 48][SeekBar][options 48]`, **always visible**. Delete `PREF_BRIGHTNESS_COLLAPSED`, the
  `onBrightnessCollapsed` action and the `brightnessShow` button. One-time cleanup in `ReaderActivity.onCreate`:
  `Settings.raw().run { if (contains("reader.brightnessCollapsed")) edit().remove("reader.brightnessCollapsed").apply() }`.
- **Auto look** (`app.brightness < 0`): icon `ic_brightness_auto`; slider progress tint `Ink.DISABLED`; thumb a hollow
  16 dp ring (white fill, 1.5 dp black stroke). The slider shows the system value; dragging switches to manual.
  **Manual look**: icon `ic_brightness_medium`, black progress, solid black thumb. There is no grey selected box.
  Both thumb drawables are cached, and they are swapped only when the look changes (checked in `setBrightness`).
- **Options button**: `ic_expand_more` when closed, `ic_expand_less` when open (ReadEra's ⌄/⌃). contentDescription
  "밝기 옵션". Tap toggles `optionsPanel.visibility`. The panel **closes whenever the chrome hides**
  (`setVisible(false)`), so the top bar is compact each time it opens.
- **Options panel** (a vertical `LinearLayout` under the row, preceded by a 1 px hairline):
  - Row "스와이프로 밝기 조절": 15 sp title and 13 sp gray subtitle "화면 왼쪽 가장자리를 위아래로 밀어 밝기를
    바꿉니다", with an `InkToggle` on the right. It is ≥ 56 dp, and a tap anywhere on the row toggles it. The action
    is `onBrightnessSwipe(v)`, and ReaderActivity **must set `page.brightnessSwipe = v` itself**: `saveApp` stores
    `appliedApp` before notifying, so `onAppSettingsSaved` will not call `applyAppSettings`.
    `chrome.setBrightnessSwipe(app.brightnessSwipe)` runs in `bindChrome`.
  - Item 3's row, added through `chrome.addBrightnessOption(row)` and only if the brightness spec needs a user switch
    (e.g. "기기 밝기 직접 조절", writing the device brightness where the window brightness does not reach the
    Comet's front light). It uses the same row style.
  - Opening or closing the panel is one partial e-ink update of the top bar. The page is not resized (overlay).

---

## 6. Status lines and progress bar: geometry, drawing, model, UX

### 6.1 Geometry (`reader/LayoutKeys.kt`, READER_B)

```kotlin
fun geometry(s: ReaderSettings, viewW: Int, viewH: Int, density: Float, statusPx: Float): PageGeometry {
    …ml, mr, mt, mb as today…
    val band = Math.round(statusPx * STATUS_BAND)
    val header = if (s.hasHeader) band else 0
    val footer = if (s.hasFooterText) band else 0
    val lane = if (s.progressBar) Math.round(ReaderSettings.PROGRESS_LANE_DP * density) else 0
    val mbEff = maxOf(mb, lane)                 // the bar lives in the bottom margin; only a margin < 12 dp grows
    var h = viewH - mt - mbEff - header - footer
    var top = mt + header
    …
}
```
`layoutPart` normalises what cannot move a line, so only a band appearing or disappearing forces a relayout:
```kotlin
val mbDp = if (s.pageMargins) s.marginBottomDp.coerceAtLeast(0) else TINY_MARGIN_DP
s.copy(invert = false,
    headerLeft = NONE, headerCenter = if (s.hasHeader) CHAPTER else NONE, headerRight = NONE,
    footerLeft = if (s.hasFooterText) PAGE else NONE, footerCenter = NONE, footerRight = NONE,
    progressBar = s.progressBar && mbDp < ReaderSettings.PROGRESS_LANE_DP,
    statusFontSizeSp = if (s.hasHeader || s.hasFooterText) s.statusFontSizeSp else ReaderSettings().statusFontSizeSp)
```
The key composition is unchanged. A user whose footer disappears gets a taller box, hence a new key, so counts are
recomputed once by construction. **No `LayoutKeys.VERSION` bump.**

Comet numbers (11 sp status = 22 px; `band = round(22 × 2.2) = 48 px`; default `mb = 16 dp = 32 px`; `lane = 24 px`):

| Config | content bottom | footer text band (baseline centred in) | bar centre `yc` |
|---|---|---|---|
| **default** (no footer text, bar on) | 1440 − 32 = **1408** (same as no bar) | none | 1428 |
| footer text + bar | 1440 − 32 − 48 = 1360 | [1360, 1416] → baseline ≈ 1395.5 | 1428 |
| footer text, no bar | 1360 | [1360, 1440] (today) | – |
| `pageMargins = false` (4 dp) + bar | 1440 − 24 = 1416 (−8 dp of text) | – | 1428 |

Vertical gaps with footer text and bar: content bottom 1360 → digit tops ≈ 1380 (20 px); descenders ≈ 1401 → dot top
1421.5 (20 px). The text is optically balanced between the page and the bar.

### 6.2 Render types (`render/Render.kt` + new `render/StatusDecor.kt`, RENDER; shared types)

```kotlin
class PageDecor(
    val highlights: List<Highlight> = emptyList(),
    val bookmarked: Boolean = false,
    /** Header / footer slots and the progress bar of the page on screen (shared, mutable, UI thread); null = none. */
    val status: StatusDecor? = null,
)   // header, footerLeft, footerRight, battery: deleted

/** One slot: chars in a fixed buffer, or a title by reference, plus an optional battery icon. */
class StatusSlot {
    @JvmField val chars = CharArray(CAPACITY)   // 48
    @JvmField var length = 0
    @JvmField var text: String? = null          // elastic title, drawn instead of chars (never copied)
    @JvmField var battery = -1                  // ≥ 0: battery icon + digits after the chars (BATTERY, CLOCK_BATTERY)
    @JvmField var elastic = false
    val isEmpty: Boolean get() = length == 0 && text == null && battery < 0
    /** Copies [src][0, n) + battery if different; true when anything changed. No allocation. */
    fun set(src: CharArray, n: Int, battery: Int): Boolean
    fun setText(t: String?): Boolean            // reference / equals compare
    fun clear(): Boolean
}
class StatusBand { @JvmField val left = StatusSlot(); @JvmField val center = StatusSlot(); @JvmField val right = StatusSlot()
                   val isEmpty: Boolean get() = left.isEmpty && center.isEmpty && right.isEmpty }
class StatusDecor {
    @JvmField val header = StatusBand(); @JvmField val footer = StatusBand()
    @JvmField var progress = -1f                // 0..1 dot position; < 0 = no bar
    @JvmField val ticks = FloatArray(MAX_TICKS); @JvmField var tickCount = 0   // chapter starts, 0..1, ascending
    companion object { const val MAX_TICKS = 60 }
}
```
`Covers.kt` still passes `PageDecor()`, so status is null and nothing is drawn.

### 6.3 Drawing (`render/PageRenderer.kt` + pure `render/StatusMath.kt`, RENDER)

`drawStatus(canvas, decor, left, top, cw, ch, viewW, viewH, ribbonH)`:
```
st = decor.status ?: return
if (!st.header.isEmpty) {
    baseline = centredBaseline(0, top)
    inset = RibbonMath.headerInset(…)            // as today
    drawBand(st.header, left + inset, cw - 2*inset, baseline)
}
lane = if (settings.progressBar) round(PROGRESS_LANE_DP * density) else 0
if (!st.footer.isEmpty) drawBand(st.footer, left, cw, centredBaseline(top + ch, viewH - lane))
if (st.progress >= 0f && lane > 0) drawProgress(st, left, cw, viewH)
```
**`drawBand`** measures each non-empty slot's natural width, using no allocation:
`statusPaint.measureText(chars, 0, n)` or `measureText(text, 0, len)`, plus the battery width
(`BatteryMath.bodyWidth + nubWidth + gap + digits`) and for `CLOCK_BATTERY` a 0.5 em gap. It then calls
`StatusMath.allocate(...)` and draws on **one shared baseline**: left at `x0`, centre at `x0 + (w − wc)/2`, right at
`x0 + w − wr`. The battery icon uses the existing `drawBattery` (pixel-aligned, `digitMiddle`). Chars are drawn with
`canvas.drawText(char[], i, n, x, y, paint)`.

**`StatusMath.allocate(w, gap, nl, nc, nr, el, ec, er, out: FloatArray)`** is pure and tested. `n*` are natural
widths (0 = empty) and `e*` mark elastic (title) slots; the drawn widths go to `out[0..2]`. `gap = max(statusPx,
8 dp)`, i.e. 1 em.
- Centre empty: if `nl + nr + gap ≤ w`, natural widths. Else shrink the elastic sides: both elastic → split
  `w − gap`, the shorter keeping its natural width if it fits in half; one elastic → it gets `w − gap − fixedOther`.
  Fixed items are never shortened.
- Centre present: it stays **exactly centred on the column**. With `sideFixed = max(fixed widths of l, r)`,
  `wc = ec ? min(nc, w − 2·(sideFixed + gap)) : nc`. Each side gets `min(n, (w − wc)/2 − gap)` if elastic, else `n`.
- An elastic slot allocated less than 3 em is hidden (0), never a lone "…".
- Worked example (40 dp side margins → `w = 280 dp`, gap 11 dp): left `123 / 3614` (55 dp), centre chapter title,
  right `14:05` (28 dp) → the title gets `280 − 2·(55 + 11) = 148 dp`, about 13 Hangul at 11 sp, END-ellipsized.

**Ellipsizing without per-turn allocation**: each slot position (6) caches `(srcRef, availPx) → CharSequence` like
today's `ellipsizedHeader`. `TextUtils.ellipsize` runs only when the title or the width changes, i.e. once per
chapter change.

**`drawProgress`** uses pure math in `render/ProgressMath.kt` (tested). Density 2 in brackets:

| Element | Geometry | Paint |
|---|---|---|
| lane | bottom `PROGRESS_LANE_DP = 12 dp` (24 px) of the view | – |
| centre line `yc` | `viewH − round(6 dp)` → integer px (1428) | – |
| track | rect `[x0, yc, x1, yc + 1)`: exactly **1 physical px**, integer-aligned, no AA | `fg`, FILL |
| travel | `x0 = left + rDot`, `x1 = left + cw − rDot`: aligned with the text column, the dot never overhangs it | |
| end caps | circles r = 2 dp (4 px) at `(x0, yc + .5)` and `(x1, yc + .5)` | `fg`, AA |
| chapter ticks | for each `t`: `x = round(x0 + t·(x1 − x0))`, rect `[x, yc − 4, x + 1, yc + 5)`: 1 px wide, 9 px tall, centred on the track | `fg`, no AA |
| halo | circle `rDot + 1 dp` (9 px) at the dot, in `bg`: clears track and ticks under the dot, giving a crisp separation | `bg`, AA |
| dot | circle `rDot = 3.5 dp` (7 px) at `(x0 + f·(x1 − x0), yc + .5)` | `fg`, AA |

- **Pure black/white**, no greys. The Comet's own per-app waveform may be A2/fast, which snaps greys to black or
  white; 1-bit art survives any waveform. Size is the hierarchy: the dot is 3.5× a cap. Inverted (밤) mode: white on
  black, same geometry. Near the start the dot overlaps the start cap region, as in ReadEra's "●●".
- **Fraction `f`** = **char progress** of the page start (`counts.charProgress(sec, page.start)`, O(1)). The first
  page of the book is 0, the last page is 1. Ticks use the **same** measure, so a chapter's first page puts the dot
  exactly on its tick. The 진행률 text keeps page progress once counted: the two readouts may differ by a hair, but
  the bar never contradicts its own ticks.
- **Ticks**: drawn only when `2 ≤ chapterCount ≤ maxTicks` with `maxTicks = min(60, floor((x1 − x0) / 6 dp))`
  (≈ 45 on the Comet). A web-novel TXT with hundreds of episodes shows a clean line (a comb helps nobody); an EPUB
  with 20 chapters shows a ruler. Ticks at 0 and 1 are skipped. They are computed in `StatusModel.rebuildTicks()`,
  which is O(chapters ≤ 60) × O(1) `charProgress`. It runs synchronously **in the first `showPage`** (so the first
  page draw already has them and needs no second e-ink update), again on `onCountsChanged(complete)` (exact EPUB
  lengths), and after a reparse. A redraw happens only if a tick moved ≥ 1 px (rounded px compared).
- The bar is not interactive: taps on it follow the tap zones, like ReadEra's.

### 6.4 The model (`reader/StatusModel.kt` + `reader/StatusText.kt`, new, READER_A, pure, JVM-tested)

```kotlin
/** Inputs of one status update, filled by the reader for the page on screen (reused, primitives only). */
internal class StatusInputs {
    @JvmField var page = 0; @JvmField var total = 0; @JvmField var percent = 0; @JvmField var bar = -1f
    @JvmField var chapterTitle: String? = null; @JvmField var bookTitle: String? = null
    @JvmField var chapterLeft = -1                      // pages; -1 unknown
    @JvmField var minutesEpisode = -1; @JvmField var minutesBook = -1
    @JvmField var epNumber = -1; @JvmField var epMax = -1; @JvmField var tocIndex = -1; @JvmField var tocCount = 0; @JvmField var epNumbered = false
    @JvmField var minuteOfDay = -1; @JvmField var is24 = true; @JvmField var battery = -1
}
/** Fills the shared [decor] for the slots of [s]; UI thread; zero allocation per call after construction. */
internal class StatusModel {
    val decor = StatusDecor()
    /** True when anything drawn changed (text, battery level, dot moved ≥ 1 px at [trackPx]). */
    fun update(s: ReaderSettings, inp: StatusInputs, trackPx: Int): Boolean
    fun rebuildTicks(fractions: FloatArray, n: Int, trackPx: Int): Boolean
    /** One-off String of [item] for the settings popup's samples (allocates; never on a turn). */
    fun sample(item: StatusItem, inp: StatusInputs): String?
}
/** Allocation-free formatters writing into a CharArray and returning the new length; identical text to ReaderFormat. */
internal object StatusText {
    fun page(buf: CharArray, at: Int, page: Int, total: Int): Int      // "12 / 3259"
    fun percent(buf, at, p): Int                                       // "34%"
    fun clock(buf, at, minuteOfDay, is24): Int                         // "14:05" / "2:05"
    fun chapterLeft(buf, at, pages): Int                               // "챕터 5쪽 남음" / "챕터 마지막 쪽"
    fun episode(buf, at, numbered, n, max, idx, count): Int            // "123/540화" / "87/612"
    fun timeLeft(buf, at, book: Boolean, minutes): Int                 // "이 화 3분" / "책 7시간 20분" (ReaderFormat.duration rules)
    fun int(buf, at, v): Int
}
```
Korean constant pieces are copied with `String.getChars(0, n, buf, at)`, which does not allocate.

`ReaderActivity.buildDecor()` (READER_A) fills `inputs` **only for the items `settings.shows(...)`**:
- `chapterIdx` = one `chapters.indexAt` (O(log C), as today), when CHAPTER or EPISODE is shown.
- `pageLabelOf` → `page`/`total` = `c.globalPage`, `c.total()` (O(1), prefix sums cached).
- `chapterPagesLeft` and `minutesLeft` are today's functions (O(1) / `peek`).
- Battery: today's sticky read, at most once per minute, with the `IntentFilter` cached in a field instead of
  allocated per read.
- **Clock without `Calendar`**: `minuteOfDay = ((now + tz.getOffset(now)) / 60_000).mod(1440)`. `tz` is a cached
  `TimeZone.getDefault()`, and `is24 = DateFormat.is24HourFormat(this)`; both are refreshed in `onResume`. Today's
  `clock()` allocates a `Calendar` and a `String` on every turn.
- `bar` = `barFraction()` (§6.3).

Then `status.update(settings, inputs, trackPx)` returns `changed`, and the frame's decor is
`PageDecor(hl, bookmarked, status.decor)`. `sameDecor(a, b)` compares the highlights and the bookmark as today, plus
`!statusChanged`: the status object is shared, so its changes are tracked by the flag `update` returns, not by
comparing strings. `scheduleEpisodes()` runs `if (settings.shows(StatusItem.EPISODE))`, in `afterOpen`,
`reopenDocument` and `episodeLabel`.

`ReaderFormat` (READER_A): `footerLeft`, `footerRight` and `returnChip` are deleted once `StatusModel` lands, with
their tests moved to `StatusTextTest` as equality checks against `pageLabel`, `clock`, `chapterLeft`, `episodeLabel`,
`timeLeft` and `duration`, which stay as the String twins. Add `returnLabel(page)`.

### 6.5 The settings UX

**Reading-settings popup** (`ReadingSettingsPopup.addPage`, EXTRAS_TOOLS). Delete the "상단 챕터 제목" and
"하단 정보 표시" switches and `footerItems()`, and add:
```
상태 표시                                             (compactHeader)
위     [  없음  ] [ 챕터 제목 ] [  없음  ]              (slot map row, 36 dp)
아래   [  없음  ] [  없음   ] [  없음  ]
진행 막대 · 화면 맨 아래 가는 선                 (◯ )   (switchRow)
상태 글자 크기                            (−) 11 (+)    (visible iff hasHeader || hasFooterText)
```
- The **slot map** is a mini-map of the page, with button position = slot position. It takes two rows instead of the
  6 rows the task suggested, and it covers the header too. Each row is `compactRow(topLine = true)`: a 40 dp row
  label (14 sp "위"/"아래"), then 3 buttons, `weight 1` each, 4 dp gaps, 28 dp tall, 13 sp, `maxLines 1`, END
  ellipsis, showing `item.short`. A filled slot has a 1 dp solid black border, 3 dp radius and black text. An empty
  slot has a **1 dp dashed border** (`GradientDrawable.setStroke(w, Ink.DISABLED, 3 dp, 2 dp)`) with "없음" in
  `Ink.DISABLED`, so empty slots read as empty.
- A tap opens `CompactList.show(ctx, button, entries, widthPx = max(220 dp, button.width), maxHeightFraction =
  0.8f)` with `entries = StatusItem.entries.map { ListEntry(it.label, checked = it == cur, note = sample(it)) {
  update(cur.withSlot(band, pos, it)) } }`. The **note is the live value** ("3 / 167", "1%", "제1화 시작…",
  "14:05", "100"), so the user sees what they pick. Samples come from `(host as? StatusSampleHost)?.statusSample(it)`,
  and without the host there are no notes.
- There are 12 rows × 40 dp = 480 dp, which is more than the 55 % cap (396 dp). Add a `maxHeightFraction:
  Float = PopupGeometry.HEIGHT_FRACTION` parameter to `CompactList.show` and to `PopupGeometry.dropdown` (both
  EXTRAS_TOOLS, not frozen), so this list fits unscrolled (576 dp cap).
- A change goes through `update()` → `host.applySettings(global)`. Item ↔ item is a **repaint**; 없음 ↔ item is
  a **relayout** (§6.1). `progressBar` is a repaint unless the bottom margin is under 12 dp.

**Settings page** (`PageTurningPage`, SETTINGS). The section "페이지 표시" becomes:
```
section("상태 표시줄")
note("위와 아래 줄의 왼쪽 · 가운데 · 오른쪽에 보일 정보를 고르세요. 한 줄이 모두 '없음'이면 그 줄은 나타나지 않습니다.")
valueRow("위 · 왼쪽", label) → chooser("위 · 왼쪽", StatusItem.entries.map { it.label }, idx) { editReader { … } }   ×3
valueRow("아래 · 왼쪽", …) ×3
toggleRow("진행 막대", "화면 맨 아래에 읽은 위치를 가는 선과 점으로 표시", r.progressBar)
stepperRow("상태 글자 크기", …)                        // unchanged
section("페이지") → 흑백 반전, 페이지 여백 (moved, unchanged)
```
The "메뉴 고정" row is deleted (§1.3).

**Host capability** (contract addition in `reader/extras/ReaderPanels.kt`, next to the R2 ones; new interface, no
existing signature changes):
```kotlin
/** Live values of the status items for the popup's slot chooser (ReaderActivity; main thread). */
interface StatusSampleHost { fun statusSample(item: StatusItem): String? }
```

---

## 7. Scroll mode (consistency with scroll design A/B, task #17)

- The header and footer are **fixed bands of the viewport**, not per page. The geometry (§6.1) already reserves the
  header band, the footer band and the progress lane; design A's `drawStrip` clips to the content box and then calls
  `drawStatus`, so the slots **and the progress bar** draw unchanged.
- The status updates on **scroll settle** (design A: "on decor rebuilds"), never per frame: `StatusModel.update` with
  `page` = the paged-equivalent estimate of the top position, `bar` = the char progress of the top position (the same
  measure as paged mode's dot and ticks), and the chapter from design A's span cache.
- The return point works identically: `jumped()` on remembered jumps and `useMark`/`useOther` → `jumpTo` places the
  line at the top. `manualTurn()` counts page-down keys and taps. Drags do not count, so the chip hides after 2 pages
  of scrolling (tracked in design A's `onSettled` via `StatusModel`'s "page changed").
- Delete the "Relayout (… pinned chrome)" row from design A §4.3: pinned chrome no longer exists.
- The side-margin default of 40 dp (shown as "0") does not interact: the track spans the text column, 280 dp on the
  Comet.
- Backup (design A part C / B): `book_prefs.return_mark` travels with the book rows, so the auto-backup restores
  pins too.

---

## 8. Costs

| Event | Work | Allocation | E-ink |
|---|---|---|---|
| **Page turn** (paged) | `buildDecor`: highlights as today + `StatusModel.update`: ≤ 6 slots, each O(1) formatting into fixed buffers. `indexAt` O(log C) (existing), `globalPage`/`total` O(1), `charProgress` O(1), `chapterPagesLeft`/`minutesLeft` O(1) (only if shown), clock via cached tz, battery cached ≤ 1/min | **0 bytes** for status (buffers preallocated, titles by reference). The existing `PageFrame` + `PageDecor` + highlight `ArrayList` stay (optional follow-up: double-buffer the two lists) | the page update itself |
| Page turn, chip up | `manualTurn()`: an int++ | 0 | the chip hides with the turn's own update |
| Page turn, chrome up (keys) | `bindChrome` → `bindDock`: setText only if a page number changed (cached) | 0 unless a number changed | bar region only when changed |
| **Draw** (`onDraw`) | status: ≤ 6 `measureText` + ≤ 6 `drawText` (+ 2 battery rects each); bar: 1 rect + ≤ 45 tick rects + 4 circles: < 0.1 ms on the A53 | 0 (ellipsize cached per slot; changes on a chapter change only) | – |
| Chapter change | one `TextUtils.ellipsize` per visible title slot | ~1 small CharSequence | – |
| Counts complete | `rebuildTicks` (≤ 60) + page labels; redraw only if something moved ≥ 1 px | 0 | **fewer than today**: with the default (no footer text) the "total pages settled" redraw disappears |
| First page | status built synchronously (as today's footer), ticks (≤ 60 × O(1)) | a few objects once | none extra |
| Pin / 지우기 / strip tap | one IO write (`setReturnMark`); strip bind | a label String | bottom bar partial; **never a relayout** |
| Chrome open / close | visibility only (the pinned relayout is gone) | 0 | overlay only |
| Brightness options | visibility toggle | 0 | top bar partial |
| Timers, idle work | **none** (the clock refreshes on turns only, as before) | | |

---

## 9. Contract requests (frozen files), exact

1. `settings/ReaderSettings.kt`: `enum class StatusItem` (§3.1). In `ReaderSettings`, remove the 9 legacy status
   fields and add the 7 fields, 3 computed members and `PROGRESS_LANE_DP` (§3.2). Remove `AppSettings.pinChrome`.
2. `settings/StatusMigration.kt` (new, §3.4).
3. `settings/Settings.kt`: slot keys, migration on load, legacy-key removal on save, `remove("a.pinChrome")` in
   `saveApp` (§3.3, §1.3).
4. `data/SettingsJson.kt`: slot keys; legacy mapping for old backups; `DROPPED_KEYS` (§3.5).
5. `data/LibrarySchema.kt`: `book_prefs.return_mark TEXT` in `CREATE_BOOK_PREFS` (v2, unreleased) (§4.3).
6. `reader/extras/ReaderPanels.kt`: `interface StatusSampleHost` (§6.5). KDoc of `goTo`: "돌아가기 chip" →
   "return point".
7. `reader/ReaderHost.kt`: KDoc of `goTo(remember)` only ("…becomes the book's return point"); no signature change.
8. `render/Render.kt` (RENDER, shared type): `PageDecor(highlights, bookmarked, status)` (§6.2).
9. `docs/ARCHITECTURE.md`: the "Chrome" and "PageView … footer" paragraphs; R2 §2 table rows for `footerEpisode` and
   `footerTimeLeft` → `StatusItem.EPISODE` and `TIME_LEFT_*`.

## 10. Owner work list

| Owner | Files | Work |
|---|---|---|
| READER_A | `ReaderChrome.kt` | §2 layout, API §2.4, brightness §5 |
| | `ReturnPoints.kt` (new), `ReturnStrip.kt` (new) | §4 |
| | `StatusModel.kt`, `StatusText.kt` (new) | §6.4 |
| | `ReaderActivity.kt` | §1.3 removals, §4.6 wiring, `buildDecor`/`sameDecor`/clock/battery (§6.4), `StatusSampleHost`, `onBrightnessSwipe`, raw-pref cleanup |
| | `ReaderFormat.kt` | `returnLabel`; drop `footerLeft/Right`, `returnChip` |
| READER_B | `LayoutKeys.kt` | §6.1 |
| RENDER | `Render.kt`, `StatusDecor.kt` (new), `StatusMath.kt` (new), `ProgressMath.kt` (new), `PageRenderer.kt`, `Covers.kt` | §6.2, §6.3 |
| EXTRAS_TOOLS | `ReadingSettingsPopup.kt`, `CompactUi.kt` (`maxHeightFraction`), `ExtrasFormat.kt` (`PopupGeometry.dropdown` fraction), `SelectionController.kt` (`hasHeader`) | §6.5, §1.3 |
| DATA | `BookPrefs.kt`, `LibrarySql.kt`, `Backup*.kt` | §4.3 |
| SETTINGS | `PageTurningPage.kt` | §6.5, delete 메뉴 고정 |
| Contract | §9 | |

---

## 11. Tests

JVM (`tools/unittest.sh`); Android-native drawing is kept out of the tested classes.

| Test | Cases |
|---|---|
| `settings/StatusMigrationTest` (new) | absent keys → header CHAPTER, footer NONE; **untouched old defaults → footer NONE**; `showFooter=false` → NONE; `showHeader=false` → header NONE; page only → left PAGE; page + % + clock → PAGE / PERCENT / CLOCK; % + clock + battery → PERCENT / – / CLOCK_BATTERY; everything → PAGE / EPISODE / CLOCK_BATTERY (documented drops); timeLeft 1/2 → TIME_LEFT_EPISODE/BOOK; `Legacy.from(JSONObject)` tolerant of wrong types |
| `settings/SettingsStoreTest` (+) | legacy prefs load migrated; after `saveReader` the legacy keys are gone and the marker exists; round trip of all 12 items per slot; unknown enum name → default; `saveApp` removes `a.pinChrome` |
| `data/SettingsJsonStatusTest` (new) | old backup (legacy defaults) → NONE; customized legacy → mapping; new backup round trip; `a.pinChrome` in an old backup neither mapped nor restored raw; `addUnmapped` never exports `LEGACY_KEYS` / `a.pinChrome` |
| `data/LibrarySchemaV2Test` (+), `data/*BookPrefs*` (+) | `return_mark` column in a fresh v2; SQL constants prepare; backup JSON round trip of `returnMark` keyed by path; old backup without it |
| `reader/LayoutKeysTest` (+) | no bands when all NONE; header band iff `hasHeader`; item swap (PAGE→CLOCK, CHAPTER→BOOK_TITLE) **not** a layout change; NONE→PAGE is; `progressBar` toggle with mb 16 dp: same geometry and not a layout change; with `pageMargins=false`: box −8 dp and a layout change; `statusFontSizeSp` change with no bands: not a layout change; the §6.1 numbers table at density 2 |
| `reader/StatusTextTest` (new) | each formatter equals its `ReaderFormat` twin over ranges (page 1..99999 × totals, percent 0..100, all 1440 minutes × 12/24 h, chapterLeft 0..999, episode both modes, durations 0..6000 min); buffer bounds (48 chars) never exceeded by the longest item |
| `reader/StatusModelTest` (new) | only shown items are formatted; `update` returns false for identical inputs and true for a changed minute, battery or page; dot movement < 1 px → false; **zero allocation**: 10 000 `update` calls allocate 0 bytes after warm-up (reuse the reflective `getThreadAllocatedBytes` helper from `TypesetterPerfTest`, extracted to a test util) |
| `render/StatusMathTest` (new) | centre exactly centred with fixed sides; elastic centre shrinks to `w − 2(side + gap)`; fixed items never shrink; two elastic sides split; elastic < 3 em hidden; empty-centre two-slot cases; header ribbon inset keeps the centre on the column |
| `render/ProgressMathTest` (new) | `yc` integer; dot x at f = 0, ½, 1 (dot inside the column); ticks rounded to integer px, 0/1 skipped; `maxTicks` rule (count > max → 0 ticks); tick-moved-≥ 1 px detection |
| `reader/ReturnPointsTest` (new) | pin on a page → pinned; pin again on that page → cleared; pin elsewhere → moved; jump with no pin → temporary mark and chip MARK (chrome hidden) / NONE (chrome up); jump while pinned → `other` = origin, chip OTHER; jump while pinned from the mark's page → no `other`, chip MARK; `useMark`/`useOther` toggle; `useMark` on the mark's page → null; `manualTurn` hides the chip after 2 and keeps the state; `hideChip`; `restorePinned` demotes a temporary mark to `other`; `reparsed` drops temporary places and moves the pin |
| `reader/ReturnMarkCodecTest` (new) | round trip; malformed / NaN / negative → null; fraction clamped; empty signature = EPUB |
| `reader/ReaderFormatTest` (edit) | drop `returnChip`, `footerLeft/Right`; add `returnLabel` |
| `SettingsMappingTest`, `UserStylesTest`, `ReaderReviewFixesTest`, `CompactSettingsTest`, `ReaderR2FeaturesTest` (edit) | legacy fields → slot fields |

**CI screenshots** (`[screens]` run, emulator 720×1440; add these steps):
1. `13_txt_chrome`: the label "3 / 167" is centred on the full width, there is no grey box on auto brightness, and
   the pin is an outline.
2. `13b_pin`: tap the pin. The strip "‹ 3 페이지로 (grey) · 지우기" appears, and the **page text is pixel-identical to
   before** (no relayout; compare the page area crop with `13_txt_chrome`).
3. `13c_pin_close`: tap the page. The chrome closes (no page turn). The page crop equals `12_txt_tap_right`'s layout.
4. `13d_return`: turn 5 pages, open the chrome, tap "‹ 3 페이지로". Page 3 shows, and the strip reads "‹ 3 (grey) ·
   지우기 · 8 페이지로 ›".
5. `13e_brightness_opts`: tap ▾. The panel shows "스와이프로 밝기 조절" with a switch, and the brightness row is still
   visible.
6. `10_txt_page1` (default settings): the header shows the chapter title, there is **no footer text**, and the
   progress line is at the bottom with the dot at the start.
7. `14b_status_slots`: popup → 더보기 → 상태 표시. The slot map and the list open from "아래 · 오른쪽" show live
   samples.
