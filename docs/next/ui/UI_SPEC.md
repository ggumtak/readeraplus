# UI_SPEC: reader chrome, return point, brightness, status slots, polish (task #18)

Status: buildable spec, design only. The repo was not edited.

**Baseline.** HEAD `b3dbc72` plus the R2 working tree of 2026-09-30, which another workflow is still editing. Every code
reference names a **symbol**. Line numbers are "≈" working-tree numbers and will drift.

**Paths.** Relative to `app/src/main/java/com/ggumtak/readeraplus/`. Tests are under `app/src/test/java/…/<same path>`.

**Inputs merged.**
- `ui/audit.md` (screenshot audit), `ui/chrome.md` (chrome, slots, pin) and `ui/brightness.md` (Comet light).
- `scroll/design-A/B/C`, and **[Δ] the final `scroll/SPEC.md`** (design B "stitched pages" + C's e-ink grafts).
  This file was re-checked against it: §5.6, §7.1 and §7.4 now cite its real owners (READER_CORE, ENGINE_RENDER,
  EXTRAS, DATA, UI) and API (`drawChrome`, `DeviceClass`, `onScrollStart`, `ScreenCounter`).

**[Δ] Critic pass (2026-09-30).** Every change made by the adversarial review is marked **[Δ]** in place and listed in
§10 (changelog). Where a [Δ] line contradicts an unmarked line elsewhere, the [Δ] line wins.

**Precedence.** This file wins over the three sibling docs. Where it says "reference: brightness.md §3.2", that code
is the intended implementation unless this file overrides a detail.

**House rules.** These apply to every item below:
- Nothing new happens before the first page.
- A page turn is O(1) and allocates nothing for status text or drawing.
- No idle redraws, no timers. **[Δ]** The page (and so the footer) is redrawn only when a page is shown (turn, jump,
  open, relayout), at a scroll settle, on `onResume`, or when a *data* item on it changed (exact page count, episode
  numbers, highlights, bookmark). A clock minute or battery percent never causes a redraw by itself (§5.3).
- E-ink: no animations, no shadows or elevation, 1 px lines, black on white, touch targets ≥ 44 dp.
- **[Δ]** No state is shown by grey alone. Fast e-ink waveforms (A2/DU) threshold `Ink.DISABLED` (#999) and
  `Ink.LINE_LIGHT` (#AAA) to white, so every state also changes an icon, a glyph or the text. Light greys are only
  used where losing them is harmless (separators, the inactive seek track).

---

## 0. Decisions

### 0.1 The user's six points

| # | User feedback (translated) | Decision |
|---|---|---|
| 1 | The page number floats awkwardly left of centre in the bottom bar. It looks cheap. | The bottom bar copies ReadEra's structure (§2.4). **"N / M"** is centred on the **full width**: 17 sp bold, tabular digits, no underline. Only **[rotation][pin]** sit on the right. The bookmark moves to the top action row, into ReadEra's 6th-icon slot. No grey "selected" squares anywhere: state is shown by swapping the icon. |
| 2 | Let me pick what shows at bottom-left, bottom-centre and bottom-right (chapter title, time, battery, …). If I pick nothing, show no footer. A thin progress line like ReadEra's would be good too. | `enum StatusItem` (12 entries). Three **footer** slots, all `NONE` by default, so a fresh install has **no footer text**. There are also three **header** slots (default: chapter title in the centre, same as today), so the user can build the "마루뷰어" top line (7a7a0a23). A **progress line** (`progressBar = true`) sits in the bottom margin: a 1 px line with end caps and a position dot. With default margins it costs no text height. |
| 3 | Moving the brightness bar at the top changes nothing. | The window override stays the default (it works on phones). Add an opt-in **device path** that writes `Settings.System.SCREEN_BRIGHTNESS` with WRITE_SETTINGS. On e-ink devices, a **one-time question** after the first drag finds out which path works. If neither works, the reader says so honestly and links to the device's own light panel instead of leaving a dead slider (§4). **[Δ]** The device path never puts back a stale value over one the user set in the system panel, always gives auto-brightness back, and is a device-local choice (not in backups). |
| 4 | The pin makes pages flip back and forth. It should pin a page so I can return to it anytime, like the "< 10 페이지로 · 지우기" strip. | "메뉴 고정" (pinned chrome) and all its relayout paths are **deleted**. That is the root cause of the flipping (§2.6). The pin now sets the book's **return point**, stored per book. A **return strip** docked in the bottom bar reads "‹ 10 페이지로 · 지우기 · 512 페이지로 ›". The old "← 돌아가기 (p. N)" chip becomes the same component's floating form, shown after a remembered jump whenever the chrome is hidden (§3). **[Δ]** That includes a seek made with the menu open (today's chip survives closing the menu; so does the new one), and scrubbing the seek bar several times keeps the *first* origin (2026-10-05: for the chip only; the history row keeps every origin, as ReadEra's). |
| 5 | The icon to the right of the brightness slider opens options (ReadEra: "스와이프로 밝기 조절" + switch). | The icon is ⌄/⌃ (`ic_expand_more`/`ic_expand_less`) and opens an **options panel** under the slider. The brightness row is never hidden again (§2.3). |
| 6 | Care much more about the UI. | §2.1 defines one visual system for the chrome: keylines, bands, type scale and state rules. §6 is the ranked polish list with exact values. §8 lists the CI screenshots that must prove each item. |

### 0.2 Conflicts between the sibling docs, resolved

| Topic | audit.md | chrome.md | brightness.md | **This spec** | Why |
|---|---|---|---|---|---|
| Label text | "3 / 167" | "10 / 3614" | – | **"N / M"** | ReadEra's "10 중 3614" is a literal translation and reads backwards in Korean. The same format appears in the footer item, the TOC, go-to and the strip. |
| Title and label weight | medium (500) | bold | – | **bold (700) or regular (400) only** | Android's CJK system font ships weight 400 only. A 500 request renders Hangul regular and digits/Latin medium, so labels get mixed weights ("배드 본 블러드 **1-353**"). Two weights stay consistent on every firmware. |
| Bookmark icon | removed from the bar | bottom-left of the bar | – | **top action row**, left of TTS | ReadEra's bottom bar has only [rotation][pin]. The top row has a free 6th slot (ReadEra's crown), which is also next to the ribbon's corner. |
| Progress line span | page edges, 12 dp | text column | – | **page edges, 12 dp** (ReadEra) | This is the user's reference. With the scroll spec's 40 dp side margins, a column-wide line would look detached. |
| Chapter ticks | none | 2–60 chapters | – | **none** (P2 option later) | Web-novel TXT files have hundreds of chapters, so ticks never show there. Leaving them out drops code, a redraw path and a test class. |
| Pin model | single pin + strip | mark/other state machine + chip | – | **chrome.md's model**, with 2 changes (§3.2), **[Δ] plus 3 from the critic** (★3 chain keeps its first origin, ★4 the reading place survives a visit to the pin, ★5 the chip's offer survives the menu). **2026-10-05 (user): replaced by a back / forward history as ReadEra's (§3); ★5 stays, ★3 only for the chip (every jump's origin is a place, as in ReadEra)** | It keeps the jump origin reachable, and one component replaces the old chip. |
| Brightness curve | linear | – | p² for both paths | **window path linear (unchanged); device path p²** | This avoids a one-time shift of every phone user's saved brightness. p² is only needed for the 1..255 device int. |
| Brightness wiring | in ReaderActivity | – | in ReaderActivity (~200 lines) | **`LightController` (READER_UI) behind a `LightHost` interface** | Keeps ReaderActivity small, because the scroll spec edits the same file in parallel (§7). |
| DB column | `BookPrefs` pin | `book_prefs.return_mark`, no bump (v2 unshipped) | – | **`return_mark` + `DB_VERSION = 3` via `ADDED_COLUMNS`** | R2 (v2) will very likely have shipped to the device before this lands. The column guard makes this safe either way. |
| Popup height cap | 55 % with 9 × 44 dp | – | – | **`HEIGHT_FRACTION = 0.56`** | 9 × 44 dp + 2 px of border is 794 px, over 55 % of 1440 px (792 px). 0.56 avoids a scrollbar. |

### 0.3 Open user questions: decided

1. **Progress line default: ON.** The user wrote "readera처럼 … 막대기로 해서 해주는 것도 좋은 것 같아". Turning it off is one switch.
2. **Big TXT total that jumps between opens (estimate).** This is out of scope. The label keeps its no-"~" estimate.
   §9 R9 records it as a READER_B P2.

---

## 1. Contract changes (phase 0, the lead, one serial step merged with the scroll SPEC's contract step)

Frozen files:
- `settings/*`, `data/SettingsJson.kt`, `data/LibrarySchema.kt`
- `ui/kit/Ui.kt`, `ui/kit/Toggle.kt`
- `reader/ReaderHost.kt`, the frozen interfaces in `reader/extras/ReaderPanels.kt`
- `AndroidManifest.xml`, `res/**`, `docs/**`

Phase 0 also lands the **cross-owner skeleton files** (§1.9) and the **compile fallout** (§1.10), as R2 did with task
#13. Every owner then compiles against fixed signatures from the first minute.

### 1.1 `settings/ReaderSettings.kt`

**New enum** (top level, next to `TapAction`):
```kotlin
/**
 * What one slot of the page's status lines shows. The header and the footer each have three slots
 * (left / centre / right). Stored by name ("r.footerLeft" = "CLOCK"): never rename an entry, only append.
 * The declaration order is the chooser order. [short] labels the popup's slot buttons (≤ 6 Hangul).
 * [example] is shown in choosers that have no live value.
 */
enum class StatusItem(val label: String, val short: String, val example: String?) {
    NONE("없음", "없음", null),
    CHAPTER("챕터 제목", "챕터 제목", "제3화 비밀"),
    BOOK_TITLE("책 제목", "책 제목", "책 제목"),
    PAGE("쪽 번호", "쪽 번호", "12 / 3259"),
    PERCENT("진행률", "진행률", "34%"),
    CHAPTER_PAGES_LEFT("챕터 남은 쪽", "남은 쪽", "챕터 5쪽 남음"),
    EPISODE("회차", "회차", "123/540화"),
    TIME_LEFT_EPISODE("이 화 남은 시간", "화 남은 시간", "이 화 3분"),
    TIME_LEFT_BOOK("책 남은 시간", "책 남은 시간", "책 7시간 20분"),
    CLOCK("시계", "시계", "14:05"),
    BATTERY("배터리", "배터리", "80"),                       // [Δ] no "▭": U+25AD is missing from some firmware fonts
    CLOCK_BATTERY("시계 · 배터리", "시계·배터리", "14:05 · 80");

    /** Titles are the only items shortened with "…" when their slot is narrow. Numbers never are. */
    val elastic: Boolean get() = this == CHAPTER || this == BOOK_TITLE
}
```

**`ReaderSettings`:**
- **Remove** `showHeader`, `showFooter`, `footerPage`, `footerChapterLeft`, `footerEpisode`, `footerTimeLeft`,
  `footerPercent`, `footerClock` and `footerBattery`, and the companion's `TIME_LEFT_*`. Those move to
  `StatusMigration` as legacy constants.
- **Add**, in place of those fields:
```kotlin
    /** Status line at the top: left / centre / right. All NONE = no header band. Default: chapter title centred. */
    val headerLeft: StatusItem = StatusItem.NONE,
    val headerCenter: StatusItem = StatusItem.CHAPTER,
    val headerRight: StatusItem = StatusItem.NONE,
    /** Status line at the bottom. All NONE = no footer band. That is the default (user request). */
    val footerLeft: StatusItem = StatusItem.NONE,
    val footerCenter: StatusItem = StatusItem.NONE,
    val footerRight: StatusItem = StatusItem.NONE,
    /** ReadEra-style reading-progress line along the bottom edge, drawn in the bottom margin ("진행 막대"). */
    val progressBar: Boolean = true,
    val statusFontSizeSp: Float = 11f,            // unchanged
```
- **Add** to the class body. These are computed members, not constructor properties, so they are not part of `equals`:
```kotlin
    val hasHeader: Boolean get() = headerLeft != StatusItem.NONE || headerCenter != StatusItem.NONE || headerRight != StatusItem.NONE
    val hasFooterText: Boolean get() = footerLeft != StatusItem.NONE || footerCenter != StatusItem.NONE || footerRight != StatusItem.NONE
    fun shows(item: StatusItem): Boolean = headerLeft == item || headerCenter == item || headerRight == item ||
        footerLeft == item || footerCenter == item || footerRight == item
    /** [band] 0 = header, 1 = footer; [pos] 0 = left, 1 = centre, 2 = right. */
    fun slot(band: Int, pos: Int): StatusItem = when (band * 3 + pos) {
        0 -> headerLeft; 1 -> headerCenter; 2 -> headerRight; 3 -> footerLeft; 4 -> footerCenter; else -> footerRight }
    fun withSlot(band: Int, pos: Int, item: StatusItem): ReaderSettings = when (band * 3 + pos) {
        0 -> copy(headerLeft = item); 1 -> copy(headerCenter = item); 2 -> copy(headerRight = item)
        3 -> copy(footerLeft = item); 4 -> copy(footerCenter = item); else -> copy(footerRight = item) }
```
- Companion: `const val PROGRESS_LANE_DP = 12`. Its users are `LayoutKeys` and `PageRenderer`.

**`AppSettings`:**
- **Remove** `pinChrome` and its KDoc.
- **Add:**
```kotlin
    /**
     * "기기 밝기 직접 조절": write the device brightness setting (WRITE_SETTINGS) instead of the window override.
     * [Δ] Device-local: never exported or restored (SettingsJson.DROPPED_KEYS), like its verdict in `reader_light`.
     * The WRITE_SETTINGS grant does not survive a reinstall, and a backup must never switch on a global-brightness
     * writer on another device.
     */
    val brightnessDevice: Boolean = false,
    /** "리더를 나가면 원래 밝기로": put the device brightness back when the reader leaves (device path only). */
    val brightnessRestore: Boolean = true,
    /** Slider POSITION 0..1, or -1 = the device's own. Window path: light = position. Device path: light = position² (LightCurve). */
    val brightness: Float = -1f,                  // KDoc only
```

### 1.2 `settings/StatusMigration.kt` (new, contract, pure)

```kotlin
package com.ggumtak.readeraplus.settings

/** Maps the ≤ R2 footer toggles to status slots (prefs and backups). Pure, JVM-tested. */
object StatusMigration {
    /** Present = the store is already slot-based. */
    const val MARKER_KEY = "r.footerLeft"
    val LEGACY_KEYS = listOf("r.showHeader", "r.showFooter", "r.footerPage", "r.footerChapterLeft", "r.footerEpisode",
        "r.footerTimeLeft", "r.footerPercent", "r.footerClock", "r.footerBattery")
    const val LEGACY_TIME_LEFT_OFF = 0; const val LEGACY_TIME_LEFT_EPISODE = 1; const val LEGACY_TIME_LEFT_BOOK = 2

    /** The legacy fields; null = key absent (or of the wrong type). */
    class Legacy(val showHeader: Boolean?, val showFooter: Boolean?, val page: Boolean?, val chapterLeft: Boolean?,
                 val episode: Boolean?, val timeLeft: Int?, val percent: Boolean?, val clock: Boolean?, val battery: Boolean?) {
        companion object {
            /** Each read in try/catch: a wrongly typed pref reads as null (never throws). */
            fun from(p: android.content.SharedPreferences): Legacy
            /** BackupJson-style tolerant reads (Boolean / Number only). */
            fun from(o: org.json.JSONObject): Legacy
        }
    }
    class Slots(val headerLeft: StatusItem, val headerCenter: StatusItem, val headerRight: StatusItem,
                val footerLeft: StatusItem, val footerCenter: StatusItem, val footerRight: StatusItem) {
        fun applyTo(s: ReaderSettings): ReaderSettings = s.copy(headerLeft = headerLeft, headerCenter = headerCenter,
            headerRight = headerRight, footerLeft = footerLeft, footerCenter = footerCenter, footerRight = footerRight)
    }

    fun migrate(l: Legacy): Slots {
        val header = if (l.showHeader ?: true) StatusItem.CHAPTER else StatusItem.NONE
        val page = l.page ?: true; val chapterLeft = l.chapterLeft ?: false; val episode = l.episode ?: false
        val timeLeft = l.timeLeft ?: 0; val percent = l.percent ?: true; val clock = l.clock ?: true; val battery = l.battery ?: true
        // The untouched old default is indistinguishable from "chosen" (saveReader writes every key): the user asked
        // for "nothing chosen → no footer", so it becomes no footer.
        val untouched = page && !chapterLeft && !episode && timeLeft == 0 && percent && clock && battery
        if (!(l.showFooter ?: true) || untouched) return Slots(StatusItem.NONE, header, StatusItem.NONE, StatusItem.NONE, StatusItem.NONE, StatusItem.NONE)
        val q = ArrayList<StatusItem>(5)
        if (page) q += StatusItem.PAGE
        if (episode) q += StatusItem.EPISODE
        if (chapterLeft) q += StatusItem.CHAPTER_PAGES_LEFT
        if (timeLeft == LEGACY_TIME_LEFT_EPISODE) q += StatusItem.TIME_LEFT_EPISODE
        if (timeLeft == LEGACY_TIME_LEFT_BOOK) q += StatusItem.TIME_LEFT_BOOK
        if (percent) q += StatusItem.PERCENT
        val right = when { clock && battery -> StatusItem.CLOCK_BATTERY; clock -> StatusItem.CLOCK; battery -> StatusItem.BATTERY; else -> null }
        return Slots(StatusItem.NONE, header, StatusItem.NONE,
            q.getOrNull(0) ?: StatusItem.NONE, q.getOrNull(1) ?: StatusItem.NONE, right ?: q.getOrNull(2) ?: StatusItem.NONE)
    }
}
```

Examples (these are test cases):

| Legacy setting | Footer slots |
|---|---|
| Old defaults | none |
| page + % + clock | `12 / 3259 · 34% · 14:05` |
| % + clock + battery | `34% · – · 14:05 [battery icon]80` |
| Everything on | `PAGE · EPISODE · CLOCK_BATTERY` (drops 챕터 남은 쪽, 남은 시간 and %, as documented) |

### 1.3 `settings/Settings.kt`

- **`saveReader`**, in the same editor:
  - `putString` for the 6 slot keys `r.headerLeft`, `r.headerCenter`, `r.headerRight`, `r.footerLeft`,
    `r.footerCenter` and `r.footerRight`, each storing the enum name.
  - `putBoolean("r.progressBar", …)`.
  - `for (k in StatusMigration.LEGACY_KEYS) remove(k)`.
- **`loadReader`:**
  ```kotlin
  val mig = if (p.contains(StatusMigration.MARKER_KEY)) null else StatusMigration.migrate(StatusMigration.Legacy.from(p))
  headerLeft = mig?.headerLeft ?: enumOr(p.getString("r.headerLeft", null), d.headerLeft),   // ×6
  progressBar = p.getBoolean("r.progressBar", d.progressBar),
  ```
  This is 9 extra map lookups, and only until the first save. It reads plain prefs only, as the R2 rule requires.
- **`saveApp`:**
  - `remove("a.pinChrome")` and **[Δ]** `remove("reader.brightnessCollapsed")` (the dead pref of the old collapse
    button; this replaces the `onCreate` cleanup that §2.3 had put on the open path).
  - `putBoolean("a.brightnessDevice", …)` and `putBoolean("a.brightnessRestore", …)`.
- **`loadApp`:** read both brightness keys with their defaults.
- **Merged with the scroll spec in the same pass** (independent keys):
  - `r.marginBase` and the 18/18 → 40/40 margin migration;
  - `a.readMode` / `a.scrollStyle` (or `a.scrollMode`) and `a.autoBackup`.

### 1.4 `data/SettingsJson.kt`

- **`readerToJson`:** put the 7 new keys. Legacy keys are never written.
- **`readerFromJson(o, base)`**, after today's field mapping:
  ```kotlin
  val slotKeys = arrayOf("r.headerLeft", "r.headerCenter", "r.headerRight", "r.footerLeft", "r.footerCenter", "r.footerRight")
  r = when {
      slotKeys.any { o.has(it) } -> r.copy(headerLeft = enumOf(BackupJson.strOrNull(o, "r.headerLeft"), base.headerLeft), /* ×6 */)
      StatusMigration.LEGACY_KEYS.any { o.has(it) } -> StatusMigration.migrate(StatusMigration.Legacy.from(o)).applyTo(r)
      else -> r
  }
  r = r.copy(progressBar = BackupJson.bool(o, "r.progressBar", base.progressBar))
  ```
- **`appToJson` / `appFromJson`:** drop `a.pinChrome`. Map `a.brightnessRestore`. **[Δ]** `a.brightnessDevice` is
  **not** mapped either way: it is device-local (§1.1), so a restore keeps this device's own value. (The earlier
  "the backup carries the choice" rule restored a switch whose WRITE_SETTINGS grant is always gone after a
  reinstall, and on another device it would have turned on global-brightness writes nobody chose there.)
- **Dropped keys:**
  ```kotlin
  private val DROPPED_KEYS: Set<String> =
      StatusMigration.LEGACY_KEYS.toSet() + "a.pinChrome" + "reader.brightnessCollapsed" +
      "a.brightnessDevice"                                   // [Δ] device-local: never exported, never restored raw
  ```
  `a.brightnessDevice` must be in this set: a typed field that `appToJson` does not write would otherwise be
  exported by `addUnmapped` and restored raw by `unmappedFromJson`. `TRANSIENT` does not help here, because it
  filters only keys without the `r.` / `a.` prefix.
  Both `addUnmapped` and `unmappedFromJson` skip `k in DROPPED_KEYS`. Without that, a device's un-migrated raw
  legacy prefs would travel as "unmapped" keys and restore raw.
- **Merged with the scroll spec in the same pass:**
  - `r.marginBase` and its migration;
  - the scroll mode / auto-backup app keys;
  - additions to `TRANSIENT` (`installid`, `backupauto`, `restoreoffer`, `deviceclass`).
  The light state lives in its own prefs file `reader_light`, never in `Settings.raw()`, so it needs no entry here.
  **[Δ]** Android's own Auto Backup (`allowBackup="true"`, no rules file) *does* copy `reader_light.xml` to a new
  install. That is harmless by design: `DeviceLight` ignores a pending original whose install stamp differs (§4.3),
  and the verdict is keyed by `Build.FINGERPRINT`.

### 1.5 `data/LibrarySchema.kt`

```kotlin
const val DB_VERSION = 3
const val CREATE_BOOK_PREFS = "CREATE TABLE IF NOT EXISTS book_prefs(" +
    "book_id INTEGER PRIMARY KEY," +
    "txt_override TEXT," +
    "finished_at INTEGER NOT NULL DEFAULT 0," +
    "episode_label TEXT," +
    "return_mark TEXT)"            // v3: the book's return history (ReturnHistoryCodec text, §3.3), NULL = none
/** v3 column for databases created by v2. */
const val ADD_RETURN_MARK = "ALTER TABLE book_prefs ADD COLUMN return_mark TEXT"
private val ADDED_COLUMNS = listOf(
    AddedColumn("quotes", "style", 2, ADD_QUOTE_STYLE),
    AddedColumn("book_prefs", "return_mark", 3, ADD_RETURN_MARK),
)
```

How the upgrades behave:
- **v1 → v3:** `CREATE_ALL` makes `book_prefs` with the column, and the column guard then skips the ALTER.
- **v2 → v3:** the ALTER runs.
- **An unshipped v2 build:** harmless.

If another spec in the same run also bumps the schema, both share this single `3`.

### 1.6 `ui/kit/Ui.kt` and `ui/kit/Toggle.kt` (kit tokens used by every owner)

| Change | Exact code or value |
|---|---|
| `Ink.LINE_LIGHT` (new) | `0xFFAAAAAA.toInt()`: row separators inside lists, popups and panels, inset 16 dp (12 dp in the compact popup). Black `Ink.LINE` stays for panel edges and group breaks. |
| `pressableBackground()` | Delete `addState(state_selected → PRESSED)`. Only `ReaderChrome` relies on it today (grep `isSelected =`), and this spec changes those uses to icon swaps. |
| `label()` | Add `if (Build.VERSION.SDK_INT >= 33) { lineBreakWordStyle = LineBreakConfig.LINE_BREAK_WORD_STYLE_PHRASE; textLocale = Locale.KOREAN }`. This gives Korean word-boundary breaks ("이어서" never splits). The signature is unchanged. |
| `keepAll(text: CharSequence): CharSequence` (new) | Inserts U+2060 WORD JOINER between two adjacent Hangul syllables (U+AC00..U+D7A3). Returns the same instance when nothing changes. Used **only** for multi-line summaries and notes: `row()` summary, SettingsPage `note()`, dialog messages. Never on tap targets or selectable or searchable text, because CI `tap_label` and span indices must stay intact. It is the fallback if §9 R6 finds that PHRASE is ignored for Korean. |
| `row()` | Padding `(16, 10, 16, 10)` dp (end was 12). The summary is `label(keepAll(summary), 14f, GRAY)`. Trailing views end exactly at W − 16 dp. |
| `sectionHeader()` | Padding `(16, 24, 16, 8)` dp. SETTINGS drops the hairline above sections (§6 P1-12). |
| `toolbar()` title | Stays 20 sp bold. Padding start becomes 12 dp after the nav icon, as today. |
| `Toggle.kt` `InkToggle.onDraw` | **Off:** white track, 1.5 dp black outline, **filled black knob** on the left. **On:** black track, white knob on the right. The knob radius is `track.height/2 − 3dp`. Size stays 52×32 dp. |

### 1.7 Interfaces and KDoc

- `reader/extras/ReaderPanels.kt` (frozen block), next to the R2 host capabilities:
  ```kotlin
  /** Live values of the status items for the slot chooser (ReaderActivity; main thread; null = no value now). */
  interface StatusSampleHost { fun statusSample(item: StatusItem): String? }
  ```
- `reader/ReaderHost.kt`: KDoc of `goTo(pos, remember)` only: "remember = the origin becomes the book's return point
  (ReturnNav): the docked strip, or the floating chip while the chrome is hidden."

### 1.8 Manifest and res

- `AndroidManifest.xml`:
  - add `xmlns:tools="http://schemas.android.com/tools"`;
  - add `<uses-permission android:name="android.permission.WRITE_SETTINGS" tools:ignore="ProtectedPermissions" />`,
    commented "기기 밝기 직접 조절 only (opt-in)".
- `res/drawable/fast_scroll_thumb.xml`: a 4×40 dp black rect. `res/drawable/fast_scroll_track.xml`: a 1 dp
  `#AAAAAA` line, inset 1.5 dp.
- `res/values/themes.xml` `Base.AppTheme`: `android:fastScrollThumbDrawable` and `android:fastScrollTrackDrawable`
  point at those two drawables. This is P1-14, LIBRARY's list.
- **[Δ]** The final scroll SPEC adds no Auto Backup rules ("no manifest change", §3.1 there), so there is nothing to
  exclude. If a later task adds `res/xml` rules, exclude `sharedpref/reader_light.xml` there.

### 1.9 Cross-owner skeletons that phase 0 lands

Signatures are fixed. Bodies are `TODO("owner: X")` or a safe stub (`// R3 stub (owner: X)`).

| File (new unless noted) | Owner after phase 0 | Contents at phase 0 |
|---|---|---|
| `render/StatusDecor.kt` | RENDER | complete (plain holders, §5.2) |
| `render/Render.kt` `PageDecor` | RENDER | new signature (§5.2) |
| `reader/StatusModel.kt` | READER_UI | `StatusInputs` complete. `StatusModel.update` stub: clears the decor, returns false. `sample` returns null. `StatusText` signatures. |
| `reader/ReturnNav.kt` | READER_UI | `ReturnHost` complete. `ReturnNav` API with empty `FrameLayout` views and no-op bodies. `ReturnPoints` and `ReturnMarkCodec` signatures. |
| `reader/LightController.kt` | READER_UI | `LightHost` complete. `LightController` API; the stub `apply(pos)` does today's `ReaderWindow.applyBrightness`. |
| `reader/LightCurve.kt`, `reader/DeviceLight.kt`, `reader/LightProbe.kt` | READER_UI | signatures (§4.2). SETTINGS calls them. |
| `reader/ReaderChrome.kt` (existing) | READER_UI | the new constructor and `Actions` (§2.5), bodies adapted to compile |
| `data/BookPrefs.kt` (existing) | DATA | `returnMark(bookId): String?` returns null; `setReturnMark(bookId, v)` is a no-op |

### 1.10 Compile fallout that phase 0 applies (mechanical only; owners finish the real work)

| File | Fallout edit |
|---|---|
| `reader/LayoutKeys.kt` | `s.showHeader` → `s.hasHeader`, `s.showFooter` → `s.hasFooterText`. `layoutPart` loses the legacy fields (READER_B completes §5.1). |
| `reader/ReaderActivity.kt` | Replace `app.pinChrome` with `false` and drop `a.pinChrome` from `viewPart`. `buildDecor` returns `PageDecor(hl, bookmarked)`. `settings.footerEpisode` → `settings.shows(StatusItem.EPISODE)`. Adapt `chromeActions` to the new `Actions`. |
| `reader/extras/ReadingSettingsPopup.kt` | `hideBars = true`. Delete "상단 챕터 제목", "하단 정보 표시", `footerItems()` and the 남은 시간 segment. |
| `reader/extras/SelectionController.kt` | `if (s.showHeader)` → `if (s.hasHeader)` (≈ l.286) |
| `render/Covers.kt` | `showHeader = false, showFooter = false` → `headerCenter = StatusItem.NONE, progressBar = false` (≈ l.205) |
| `render/PageRenderer.kt` | `drawStatus` reads `decor.status` and draws nothing until RENDER lands §5.4 |
| `ui/settings/PageTurningPage.kt` | Delete "메뉴 고정" (≈ l.91) and the 7 legacy status rows (≈ l.156-162) |
| Tests | `LayoutKeysTest`, `CompactSettingsTest`, `ReaderFormatTest`, `ReaderReviewFixesTest`, `ReaderR2FeaturesTest`, `UserStylesTest`, `SettingsStoreTest`, `SettingsJsonTest`, `SettingsJsonR2Test`, `SettingsMappingTest` (use `bookmarkByTouch` where it used `pinChrome`): legacy fields → slot fields, compile only |

Then `tools/typecheck.sh`, the contract tests (§8.1) and `tools/snapshot_contracts.sh`.

### 1.11 Merge with the scroll SPEC's contract step

Both specs edit `ReaderSettings.kt`, `Settings.kt`, `SettingsJson.kt`, `ReaderHost.kt` (KDoc) and
`docs/ARCHITECTURE.md`. They touch **different fields and keys**, so the lead applies both lists in one pass.

**[Δ] Checked against the final scroll SPEC (design B):**
- It needs no `ContentOriginHost` (that was design A), so `ReaderPanels.kt`'s frozen block gets only
  `StatusSampleHost` from this spec.
- Its phase 0 also lands `engine/Layout.kt` (`PageInfo.lead`), `settings/UserStyles.kt` (`marginBase`), the
  `PageRenderer.drawChrome/drawBody/drawOverlay/prefetchPage` stubs, `render/DeviceClass.kt`, `data/AutoBackup.kt`
  and `data/InstallState.kt`. There is no overlap with §1.9.
- `render/DeviceClass.kt` is shared: `DeviceLight.looksEink` calls it (§4.2), so its stub must return
  `cached = null` and `probe = einkByBuild(...)`, never throw.
- Its docs go to a new `docs/R3_INTERFACES.md`. This spec's interface rows (StatusItem/slots, `StatusDecor`,
  `ReturnHost`/`ReturnNav`, `LightHost`, `book_prefs.return_mark`, the footer redraw rule) go there too, and the
  ARCHITECTURE rows below go to `docs/ARCHITECTURE.md`.
- Three lines of the scroll SPEC are void and are edited in the same pass:
  - §1.10 `pageCallbacks.onScrollStart`: "Close unpinned chrome" becomes "close the chrome".
  - §1.12 parity row "Relayout (…, rotation, **pinned chrome**, TXT re-parse)": drop "pinned chrome".
  - §1.1 gate "CI screenshots of paged mode must differ only by the new margins" becomes "…only by the new margins,
    the default footer (none), the progress line and the new chrome (§8.2)".
- Its `14_reading_settings` expectation "the first row is '넘기는 방식 · 페이지 넘김'" means the first row of the
  **페이지 넘김** section, which lives under "더보기". The main section stays at 9 rows (§6 P1-8), so the popup never
  scrolls. EXTRAS must not put "넘기는 방식" above the fold.

`docs/ARCHITECTURE.md` gets:
- **Chrome:** bars are overlays, never resize the page; no pinned chrome.
- **Status:** slots, progress line, zero-allocation model; **[Δ]** the redraw rule (House rules): the clock and the
  battery are sampled only when a page is shown, at a scroll settle and on resume.
- **Return point:** `book_prefs.return_mark`.
- **Brightness:** window path, or the opt-in device path with a per-firmware verdict.
- **Type scale:** two weights.
- **R2 table:** the `footerEpisode` / `footerTimeLeft` rows become `StatusItem.EPISODE` / `TIME_LEFT_*`.
- **Scroll spec rows:** delete any "pinned chrome" row.

---

## 2. Chrome redesign (READER_UI: `reader/ReaderChrome.kt`)

### 2.1 One visual system (chrome, return strip, options panel)

| Token | Value |
|---|---|
| Keylines | **Text** starts 20 dp from the left: the stroke of the back arrow (the 48 dp button at 4 dp row padding centres its 24 dp glyph at 16..40 dp, and `ic_arrow_back`'s ink starts at 4/24). **Trailing** content ends 16 dp from the right: icon glyph boxes and toggle tracks. |
| Bands | Actions 56 · title ≈ 32 (17 sp line + 10 dp bottom) · brightness 48 · option rows ≥ 56 · return strip 44 · label 52 · seek 48 dp. Every tap target is ≥ 44 dp. **[2026-10-05]** Actions 56 · title ≈ 36 (18 sp line + 12 dp bottom) · brightness 48 · option rows ≥ 56 · history row 48 · label 48 · seek 48 dp: every control of the bars is a ≥ 48 dp target. **[2026-10-05, ReadEra side by side]** The bottom panel as low as ReadEra's: label row 50 (its 48 dp controls centred, the label's centre 25 dp under the panel's top) and seek row 48 from 37 dp (track centre 61 dp): **85 dp** of panel, then the bottom gap. The two rows' 48 dp targets overlap by 12 dp, exactly the space between their 24 dp glyphs, where the label row takes the touch (`ChromeMath.LABEL_CENTRE_DP` / `SEEK_CENTRE_DP` / `PANEL_DP`). **[2026-10-05, review]** The title row starts 9 dp inside the actions row's empty foot (`ChromeMath.TITLE_LIFT_DP`): the title block 83 dp as ReadEra's (was 92); the history row's text sits 10 dp low in its 48 dp box (`ChromeMath.HISTORY_TEXT_TOP_DP`), 19 dp above the panel as ReadEra's (was 24). |
| Colours **[2026-10-05]** | From `render/ChromePalette` (table below), never `Ink` constants: the bars follow the page's theme (흰 바탕, 마루뷰어, 흑백 반전). |
| Lines **[2026-10-05]** | **Phone:** where a bar meets the page, a 4 dp shadow (`ChromeBar`, linear, `shadow` → transparent; 흑백 반전: a 1 px `edge` line instead); **[2026-10-05, later]** the top bar's panel ends under the title, so its edge lies between the title and the brightness row (no title rule any more) and nothing lies under the brightness row, which sits on the page colour as in ReadEra; with the options open a second edge lies under them; the options rule 1 px `rule`, inset 20 / 16 dp; option rows 1 px `divider`, inset 20 / 16 dp. The dock's own line and the bars' black hairlines are gone. **E-ink:** solid 1 px lines only: the bar edges `edge` (black on 흰 바탕), the rules `rule` across the full width as before, option rows `divider`, and a 1 px `divider` on top of the history row. |
| Type **[2026-10-05]** | Title > page label > history row, as in ReadEra (title ≈ 17 sp, at least its page label's ≈ 16.5 sp). **Title** 18 sp bold. **Page label** 17 sp: the current page bold `text`, " / total" ×0.82 (≈ 14 sp) regular `text2`. **History row** 14 sp regular `hist`. **Option titles, question** 15 sp regular. **Subtitles** 13 sp regular `text2`. **Seek preview** 16 sp bold. Disabled titles `text2`. Only two weights (§0.2). |
| Digits | Page label and strip labels: `fontFeatureSettings = "tnum"` (set once) |
| Icons **[2026-10-05]** | Every chrome icon is a 24 dp Material Symbols glyph in a 48 × 48 dp target, drawn inside the 20 dp live area with ≈ 2 dp strokes: the glyphs that fill their whole box (`ic_screen_rotation`, `ic_screen_lock_rotation`, `ic_brightness_medium`, `ic_brightness_auto`) are scaled to 85 % by a `<group>` in their XML (≈ 20 dp, ≈ 1.7 dp strokes), so rotation and the pin, or the sun and the chevron, read the same size. The pin (`ic_push_pin`, `_fill`) is the Material Symbols file (24-unit viewport), 12 × 20 dp. The history row's chevrons are 16 dp. Left icons centre at 28 dp, right icons at W − 28 (rotation W − 76); the text keyline is 20 dp; both slider tracks run from 64 dp to W − 64. |
| State | **Icon swap**, plus **[2026-10-05]** on a phone the accent on a state that stays (bookmark, pin, rotation lock: icon tinted `accent` over a 40 dp `active` circle) and a pressed overlay (`pressed`: a 40 dp circle behind icons, the same circle stacked over the `active` one, an 8 dp rounded rect hugging a text cell's words, a full-width rect on option rows, fading out in 120 ms, at once when the animator scale is 0). The `active` circle is set apart from the `pressed` one (≥ 1.4 : 1), so an active toggle never looks like a press that did not release. **E-ink: icon swap only, no pressed state** (one update per action). `isSelected` is never used for looks. Bookmark: `ic_bookmark` ↔ `ic_bookmark_fill`. Rotation: `ic_screen_rotation` ↔ `ic_screen_lock_rotation`. Pin: `ic_push_pin` ↔ `ic_push_pin_fill`. Brightness: `ic_brightness_auto` (auto) ↔ `ic_brightness_medium` (manual). Options: `ic_expand_more` (closed) ↔ `ic_expand_less` (open). **[2026-10-05]** The history row has no state of its own: it only ever shows places to go to, and a side without one is invisible (§3.4). |
| Motion **[2026-10-05]** | **Phone:** the bars fade and slide 12 dp from their edge, 180 ms in (`PathInterpolator(0, 0, 0.2, 1)`) and 150 ms out (`(0.4, 0, 1, 1)`), alpha and translation only (the page never re-lays out); a new touch passes to the page while they leave (a drag that started on a bar, such as the seek bar, still ends there); instant when the system's animator duration scale is 0 (개발자 옵션, 접근성 "애니메이션 제거": `ChromeMath.animates` with `animatorScale()` = `ValueAnimator.getDurationScale()` from API 33, `areAnimatorsEnabled()` before; e-ink never reads it). **E-ink and an unprobed device:** none, the bars switch in one frame. No ripples, `animationStyle = 0`, no autosize steps. Page turns stay instant everywhere (PLAN 2026-10-02). |

**[2026-10-05] Tokens** (`render/ChromePalette.of(page, eink)`; six shared instances; `eink == null` → the e-ink set).
The accent is the page's own status colour. E-ink sets are the old `Ink` colours as the page's greys
(`PagePalette.grey`): 흰 바탕 on e-ink is exactly the chrome of before.

| token | 흰 바탕 phone | 마루뷰어 phone | 흑백 반전 phone | e-ink (흰 바탕 / 마루뷰어 / 흑백 반전) |
|---|---|---|---|---|
| page (the history row; **[2026-10-05, later]** the brightness row and its options: the page's own pixels) | #FFFFFF | #323232 | #000000 | the page |
| surface | #F5F5F5 | #3C3C3C | #1A1A1A | the page |
| text / text2 | #1A1A1A / #5E5E5E | #DDDDDD / #A8A8A8 | #FFFFFF / #B3B3B3 | grey(0) / grey(0x55): #000/#555, #DDD/#A4A4A4, #FFF/#AAA |
| accent | #000000 | #F0D096 | #FFFFFF | the page's status colour |
| divider = rule | #DDDDDD | #4E4E4E | #333333 | divider grey(0xCC), rule grey(0) |
| edge | none | none | #333333 | grey(0) |
| track (also, on a phone, the 1 px border of the chip and the seek preview) | #C8C8C8 | #606060 | #4A4A4A | grey(0x99) |
| hist (also the brightness row's icons and its NONE link) | #5E5E5E | #A8A8A8 | #B3B3B3 | grey(0) |
| shadow | #33000000 | #80000000 | none | none |
| pressed / active | #14000000 / #38000000 | #1AFFFFFF / #4DF0D096 | #1AFFFFFF / #42FFFFFF | none |

Contrast floors (`ChromePaletteTest`): text on surface ≥ 7, text2 ≥ 4.5, history row on the page ≥ 4.5, accent ≥ 3;
**[2026-10-05, later]** on the page colour too, for the brightness row and its options: `hist` ≥ 4.5, text ≥ 7, text2 ≥ 4.5,
accent ≥ 3, track ≥ 1.6 (the accent ≥ 2 on the track); and that row's colour is `PagePalette.background` in every look;
a phone's surface is within 1.3 : 1 of its page; on a phone also the
floating boxes' border (`track`) on the page ≥ 1.6 (and below the text), and the `active` circle ≥ 1.4 apart from the
`pressed` one with the accent ≥ 3 on it. API 30+: the system bars are transparent over the page (or the open bar's
surface), with light icons on a dark page (`ReaderWindow.applyBarLook`); full-screen dialogs keep white bars.
**Exception (this pass, PLAN 2026-10-05):** the surfaces opened from the bars (the ⋮ menu, the ⚙ quick options, the
rotation chooser, the full-screen TOC and search dialogs) stay white on every page colour; only the bars, the history
row, the chip and the seek preview take the tokens.

### 2.2 Top bar (`ReaderChrome.top`, a vertical `LinearLayout`, white, clickable, top inset as padding)

```
┌──────────────────────────────────────────────┐
│ ←                  🔖  🔊  🔍  ☰  ⚙  ⋮       │ actions 56dp, row padding 4dp h
│ 배드 본 블러드 1-353 완                         │ title row: 18sp bold, 1 line, END ellipsis
└──────────────────────────────────────────────┘ panel edge [2026-10-05]: 4 dp shadow (phone) / 1px edge (e-ink, 흑백 반전)
  Ⓐ  ━━━━━━━━●───────────────────────   ⌄       brightness row 48dp on the PAGE colour, nothing under it
  ────────────────────────────────────────────   1px rule (only while the options panel is open)
  [question row, only while asked, §4.4]          the options, on the page colour too
  스와이프로 밝기 조절                     (●  )   option row ≥56dp
  화면 왼쪽 가장자리를 위아래로 밀어 밝기를 바꿉니다
  ─────────────────────────────── (light)
  기기 밝기 직접 조절                      (●  )   option row (§4.4)
  전면광이 안 바뀔 때 켜세요 · 기기 전체 밝기를 바꿉니다
  [기기 조명 설정 열기 ›, conditional, §4.4]
 ▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔  while open: the same edge under the options
```

| View | Spec |
|---|---|
| bar **[2026-10-05]** | `ChromeBar(edgeAtTop = false)`: fills the surface from the screen's top through the inset padding, draws its edge band in its own bottom padding (`edgeArea`: 4 dp with a shadow, 1 px with a line) over the page. **[2026-10-05, later]** `pageFrom` = the brightness row's index: the panel (surface) ends at that row's top, the rows from it on are filled with `page` (the page's own background, so the row reads as part of the page: the user, "색 조절하는 부분만이라도 색을 아예 똑같이"), and the edge band covers the brightness row's head. The bar pads its bottom by `edgeArea` only while the options are open, and draws a second band there; closed, the brightness row ends the bar with nothing under it. Still one view: the phone's fade and slide move it as one unit, and nothing in the page re-lays out. |
| actions row | `horizontal`, `minimumHeight 56dp`, padding `(4, 0, 4, 0)` dp. Contents: `[back 48]` `[spacer weight 1]` `[bookmark 48][tts 48][search 48][toc 48][gear 48][more 48]`. Content descriptions: "뒤로", "북마크 추가"/"북마크 삭제", "TTS 읽기", "검색", "목차", "읽기 설정", "더보기". On the Comet: 8 + 7·48 = 344 dp, spacer 16 dp. **Width guard [Δ]:** `bookmark` is `GONE` when the bar is under 352 dp wide. It is decided in `setVisible(true)` from `root.width − left − right insets` (cached; recomputed only when that width changes), **never inside an `OnLayoutChangeListener`**: changing visibility or sizes during a layout pass forces a second layout and draw, i.e. a second e-ink update on the first show. While it is hidden, the ⋮ menu gains "북마크 추가" / "북마크 삭제" (READER_CORE, `ReaderMenus`), so the action is never lost on a narrow phone. |
| title row | Padding `(20, 0, 16, 12)` dp. `label("", 18f, bold = true, maxLines = 1)` **[2026-10-05]** (the top of the type scale, §2.1), END ellipsis. One line, so the bar height never depends on the title. `setTitle` sets it only on change (existing guard). **[2026-10-05, review]** Top margin −9 dp (`ChromeMath.TITLE_LIFT_DP`): the box starts at 47 dp, inside the actions row's empty foot (its 48 dp buttons end at 52 dp, their 40 dp pressed circle at 48 dp, the title's glyphs start ≈ 51 dp). The title is not clickable, so a tap there still reaches the buttons. Its glyph centre ≈ 59 dp under the bar's top and the surface's end ≈ 83 dp, as ReadEra's (59.8 / 83.7 dp on the user's S25; ours were 68.8 / 91.7, the icons at the same 28 dp). |
| rule | ~~1 px `rule`~~ **[2026-10-05, later]** gone: the panel's edge separates the title from the brightness row |
| brightness row | Padding `(4, 0, 4, 0)` dp. Contents: `[auto 48]` `[SeekBar weight 1]` `[options 48]`. The auto button's content description is "시스템 밝기 따르기" (manual) or "직접 밝기 조절" (auto). The options button's is **"밝기 옵션"**. Verdict NONE (§4.4) replaces the auto button and the SeekBar with one 15 sp link "기기 조명 설정에서 조절 ›". **[2026-10-05, later]** On the page colour: the two icons and the link in `hist` (the history row's colour on the page), the phone's pressed circle as elsewhere. |
| SeekBar (`chromeSeekBar`, shared with the seek row; **[2026-10-05]**) | A rounded track (`LayerDrawable`: background + `ClipDrawable` progress, `setLayerHeight`, centred), inactive part `track`, progress `accent`, and an `accent` dot thumb (`thumbOffset` = half its width): **2 dp track and 16 dp thumb on a phone** (ReadEra measures 2 dp / 12 dp on the S25; 16 dp is this spec's smallest thumb), **3 dp / 18 dp on e-ink**. `sizeSliders` builds new drawables only when the device class changes (once, when the probe says phone, in the update that shows the bars). Padding `(12, 15, 12, 15)` dp, fixed 48 dp height (the drag area), `splitTrack = false`, still a `SwipeSafeSeekBar`. **Auto look:** progress `track` and a hollow ring thumb of the same size (`page` fill **[2026-10-05, later]**, was `surface`; 1.5 dp `text` stroke). **Manual look:** solid accent thumb. Both thumb drawables are cached and swapped only when the look changes. The first `onProgressChanged(fromUser)` switches to the manual look immediately, so the icon never says "auto" mid-drag. **[Δ]** Content descriptions: "밝기" (brightness bar) and "페이지 위치" (seek bar). A bare SeekBar is read only as a percentage. |
| options panel | A vertical `LinearLayout`, `GONE` by default, preceded by a black hairline that is visible with it. **[2026-10-05, later]** On the page colour like the brightness row (the toggles' paper `page`), with the bar's edge under it while open. **[Δ] Built lazily:** the constructor adds only the empty container; its rows (question, two toggles, the panel link) are created on the first `setBrightnessOptionsOpen(true)`, so opening a book inflates nothing new before the first page. The `setLight*` / `setSwipeOption` setters only cache their values until the rows exist. It is **closed by `setVisible(false)`**. `LightController.bind()` reopens it while a question is pending. Rows are built by the private `optionRow(title, subtitle, trailing)`: `minHeight 56dp`, padding `(20, 8, 16, 8)` dp, title 15 sp, subtitle 13 sp GRAY `keepAll`, max 2 lines, tapping anywhere toggles; the trailing `InkToggle` ends at W − 16 dp. A light hairline separates rows. **[Δ] Accessibility:** the row is the one focusable unit. The toggle gets `importantForAccessibility = NO`, and the row's `AccessibilityDelegate` reports `isCheckable = true`, `isChecked` = the toggle's state, and the title and subtitle as its text. Otherwise TalkBack announces an unlabelled switch next to a clickable text block. |
| bar edge | Drawn by `ChromeBar` (no view): a 4 dp shadow on a phone, 1 px `edge` on e-ink and 흑백 반전. **[2026-10-05, later]** Between the title and the brightness row (over the row's head), and under the open options |

Heights **[2026-10-05, later]**: 56 + ≈ 36 + 48 ≈ **140 dp** closed (the edge lies over the brightness row's head; was
145 with the title rule and the edge under the bar), plus the top inset. About 257 dp with two option rows open (the rule,
the rows and the edge under them). **[2026-10-05, review]** 56 − 9 + ≈ 36 + 48 ≈ **131 dp** closed (the surface block
≈ 83 dp, ReadEra's ≈ 83; the brightness track's centre ≈ 107 dp under the bar's top, ReadEra's ≈ 103), ≈ 248 dp open.

### 2.3 Brightness row behaviour (item 5)

- The ⌄/⌃ button toggles the options panel. It never hides the brightness row.
  - Delete `brightnessShow`, `setBrightnessCollapsed` and `Actions.onBrightnessCollapsed`.
  - In `ReaderActivity`, delete `PREF_BRIGHTNESS_COLLAPSED` (≈ l.120, 509, 2306).
  - **[Δ]** No cleanup code in `onCreate` (a prefs write on the open path breaks "nothing new before the first
    page"). `Settings.saveApp` removes the dead key (§1.3), and `DROPPED_KEYS` keeps it out of backups.
- Opening or closing the panel changes the top bar's height. The bar is an overlay, so the page is **never** resized
  (§2.6). That costs one partial e-ink update of the bar.
- The row "스와이프로 밝기 조절" binds `AppSettings.brightnessSwipe`. Its action is `LightController.onSwipeSwitch(v)`,
  which saves and **also sets `page.brightnessSwipe = v` through `LightHost`**. `saveApp` sets `appliedApp` first, so
  `onAppSettingsSaved` would not apply it. At verdict NONE the row is disabled and its subtitle reads
  "이 기기에서는 밝기 스와이프를 쓸 수 없습니다".
- **[Δ]** `applyAppSettings` must stop writing `page.brightnessSwipe = app.brightnessSwipe` itself. The one writer is
  `LightController.onAppSettingsApplied()`, which sets `app.brightnessSwipe && swipeUsable`. Otherwise every
  `onResume` would switch a swipe back on that verdict NONE had turned off, and the swipe would move nothing.

### 2.4 Bottom bar (`ReaderChrome.bottom`, a vertical `LinearLayout`, white, clickable, bottom inset as padding)

```
  ‹ 10쪽으로          지우기          512쪽으로 ›    history row 48dp on the PAGE colour, 3 equal columns — GONE when empty (§3)
├──────────────────────────────────────────────┤ panel edge: 4 dp shadow over the row's foot (phone) / 1px edge (e-ink)
│                  10 / 3614             ⟳  📌  │ label row 50dp: its 48dp controls centred 25 dp under the panel's top
│ ⏮  ━━━━━━●──────────────────────────────  ⏭  │ seek row 48dp from 37 dp: track centre 61 dp (36 dp under the label's)
└──────────────────────────────────────────────┘ 85 dp, then ChromeMath.bottomGap: max(nav inset, gesture strip, 16 dp),
                                                   0 in a window above the display's bottom with no bottom inset
```

| View | Spec |
|---|---|
| bar **[2026-10-05]** | `ChromeBar(edgeAtTop = true)`, `panelFrom = 1`: the panel (surface) starts at the label row and reaches the screen edge; the edge band sits in the bar's top padding (`edgeArea`) or, with the history row shown, over that row's bottom 4 dp, as in ReadEra. The row's top margin is −`edgeArea` (`ChromeBar.fitLead`, set with the look, never in a layout pass): it starts over that padding, so no strip of bare page is left above it where the clickable bar would swallow a tap. |
| history row | `ReturnNav.dock` (§3.4), **[2026-10-05]** at index 0, right above the panel, on the page colour |
| label row | `FrameLayout`, `minimumHeight 48dp` **[2026-10-05]** (was 52). **[2026-10-05, later]** 50 dp (`ChromeMath.LABEL_ROW_DP`), its 48 dp children centred (the label's centre 25 dp under the panel's top, as in ReadEra), in one `FrameLayout` with the seek row; added after it, so in the 12 dp the two rows' targets share (37..49 dp, empty space between the glyphs) the label row's controls take the touch and a near miss of the pin or the label never jumps to another chapter or page. Where the label row is empty (left of the label) the touch reaches the seek row. **[2026-10-05, review]** So in that 12 dp band the label row's controls win: ⏭ under the pin, and the seek bar under the label and rotation, answer from 49 dp down (36 dp of their own; their views and hit rects stay 48 dp); ⏮ and the seek bar's left end keep all 48 dp (11f-17). |
| ↳ `pageLabel` | **[2026-10-05]** `label("", 17f, maxLines = 1)`: the weight and colours come from spans. **[Δ]** `FrameLayout.LayoutParams(labelW, 48dp, Gravity.CENTER)` with a **fixed** width `labelW = rowW − 2·RESERVE` (`RESERVE = 4 + 48 + 48 + 8 = 108 dp`; `ChromeMath.labelMaxWidth`), not `WRAP_CONTENT`. Android documents autosize as unreliable with `wrap_content` (it can re-measure on every text change), and a fixed box also gives a steady, larger tap target. `labelW` is computed in `setVisible(true)` from `root.width − insets` and applied only when it changes, never in a layout listener (see the width guard in §2.2). `gravity = CENTER`, padding 12 dp on each side, `fontFeatureSettings = "tnum"`, a phone's pressed rect (none on e-ink), tap → `actions.onPageLabel()`. **[2026-10-05]** The text is a `SpannableString` split at `ReaderFormat.pageLabelCut` (" / "): the page `StyleSpan(BOLD)` in `text`, the total `RelativeSizeSpan(0.82)` (≈ 14 sp) regular in `text2` (`ForegroundColorSpan`); 17 sp base, below the 18 sp title. **No underline**: delete the working tree's `Paint.UNDERLINE_TEXT_FLAG`. On the Comet `labelW` is 144 dp: "12345 / 23259" (17 sp bold head, ≈ 14 sp total, tnum) measures under the 110 dp it took all at 17 sp bold, plus 24 dp of padding. `setAutoSizeTextTypeUniformWithConfiguration(14, 17, 1, SP)` (API 26) covers large font scales. The label stays centred on the **full width** and can never run under an icon. **[Δ] Accessibility:** the content description is "페이지 이동, 3 / 167", set together with the text in `setPage` (chrome visible only, so the String is not a per-turn cost while reading). A fixed "페이지 이동" would hide the page number from TalkBack. CI taps it with `tap_label "페이지 이동" contains`. |
| ↳ right cluster | `horizontal`, `LayoutParams(WRAP, 48dp, END or CENTER_VERTICAL)`, `marginEnd 4dp`: `[rotation 48][pin 48]`. Rotation: content description "화면 회전 잠금", long-press → `onRotationChooser()`, icon swap only. Pin: content descriptions in §3.1. |
| seek row | `horizontal`, padding `(4, 0, 4, 0)` dp: `[⏮ 48 "이전 화"][SeekBar weight 1][⏭ 48 "다음 화"]` (the working tree's T1-5 buttons, kept). **[2026-10-05, later]** Top margin 37 dp in the panel's `FrameLayout` (`ChromeMath.SEEK_TOP_DP`): the track's centre 61 dp under the panel's top and 24 dp above its content's bottom (ReadEra's 36 dp from the label, ours were 47). |
| seek preview box | Unchanged mechanics. Its text changes to `ReaderFormat.previewLabel(page, chapter)` = **"1234쪽 · 제3장 …"** (was "p. 1234 · …"; READER_A, §6 P1-17). **[2026-10-05]** `surface` box, 16 sp bold `text`; on a phone an 8 dp rounded 1 px border in `track` (it floats over the page text, where `divider` would vanish), on e-ink a square 1 dp `edge`. |

Heights **[2026-10-05]**: 4 + 48 + 48 = **100 dp** on a phone (1 px edge on e-ink), 144 dp with the history row (48 dp,
starting over the 4 dp edge padding), plus the bottom padding. **[2026-10-05, later] (the user beside ReadEra: "하단에 …
바가 훨씬 위로 크지")** 4 + 85 = **89 dp**, 133 dp with the history row, plus the bottom gap `ChromeMath.bottomGap(bar
inset, mandatory gesture inset, 16 dp, floats)`: one value, never a sum. S25 full screen: the 48 px gesture strip → a 101
dp panel (ReadEra 100), the seek track's centre 40 dp above the edge; the S25's upper split-screen window
(`ReaderWindow.floatsAboveBottom`: multi-window and, API 30+, the window's bottom above the display's; no bottom inset at
all) → 0: nothing of the system's under it, an 85 dp panel (ReadEra's panel content, measured in the user's shots with
ReadEra in the LOWER window, above its own 15 dp navigation strip); the lower window keeps its navigation bar / gesture
inset; the Comet (full screen, no gesture navigation, a bezel over the outer rows) → 16 dp, never dropped. Was 112 dp in
the S25's upper window. **[2026-10-05, review]** Before API 30 `floatsAboveBottom` is false for every window: its
position is unknown there, and a lower split window in full screen reports no bottom inset at all (`insetsOf` zeroes the
bars, no gesture inset before API 29), so it keeps the 16 dp.

### 2.5 `ReaderChrome` API (fixed at phase 0)

```kotlin
internal class ReaderChrome(
    private val ctx: Context,
    private val actions: Actions,
    returnDock: View,                 // ReturnNav.dock, inserted into [bottom] at index 1
    private val light: LightController, // brightness row + options panel talk to it directly
) {
    // [Δ] The constructor never passes `this` to [light]: ReaderActivity calls `light.attach(chrome)` right after
    // construction. Leaking `this` from `init` would let LightController call setters on half-built views.
    interface Actions {
        fun onBack(); fun onTts(); fun onSearch(); fun onToc()
        fun onSettings(anchor: View); fun onMore(anchor: View)
        fun onBookmark()
        fun onPageLabel()
        fun onChapter(next: Boolean)
        fun onRotation(); fun onRotationChooser()
        /** The pin: this page is saved as the newest place to go back to (or, when it already is, released). */
        fun onPinHere()
        fun onSeekStart(); fun onSeekPreview(progress: Int): String; fun onSeekDone(progress: Int)
        // deleted: onPin (메뉴 고정), onBrightnessAuto, onBrightness, onBrightnessCollapsed (→ LightController)
    }
    val top: ChromeBar; val bottom: ChromeBar; val gear: ImageButton; val more: ImageButton   // ChromeBar is a LinearLayout
    fun setLook(page: PagePalette, eink: Boolean?)   // [2026-10-05] stored while hidden, applied at show (or at once)
    val isSeeking: Boolean; val isVisible: Boolean; val bottomHeight: Int
    fun attach(root: FrameLayout)
    fun setVisible(visible: Boolean)          // hiding also closes the options panel and the seek preview
    fun owns(v: View): Boolean
    fun setInsets(left: Int, topInset: Int, right: Int, bottomInset: Int)
    fun setTitle(text: CharSequence)
    fun setPage(label: String, max: Int, progress: Int)
    fun setBookmarked(on: Boolean)            // top-row icon; cached, no redraw when unchanged
    fun setPinned(on: Boolean)                // icon + content description (§3.1): filled "고정 해제" / outline
    fun setRotationLocked(locked: Boolean)    // icon only
    // Bound by LightController only (same owner):
    fun setBrightness(pos: Float, auto: Boolean)
    fun setBrightnessOptionsOpen(open: Boolean)
    fun setSwipeOption(on: Boolean, enabled: Boolean, subtitle: String)
    fun setLightAsk(kind: Int)                // LightController.ASK_NONE / ASK_WINDOW / ASK_DEVICE
    fun setLightDevice(on: Boolean, subtitle: String, enabled: Boolean)
    fun setBrightnessUnavailable(unavailable: Boolean)
    fun setLightPanelRow(visible: Boolean, subtitle: String)
}
```
Every setter compares with the last bound value and does nothing when it is unchanged. That is an e-ink rule: an
unchanged view is never redrawn.

### 2.6 Pinned chrome: root cause and exhaustive removal (item 4's "flipping")

**Root cause** (confirmed in code, chrome.md §1):
1. `togglePin()` calls `applyPinnedArea()`, which sets the **PageView's** margins to the bar heights. A PageView size
   change runs `onViewSizeChanged` → `setViewport` → `rebuild()`: a new generation and page-count key, a relayout and
   a recount.
2. `setChromeVisible()` calls it on **every** menu open and close, so the label and seek bar jump between estimates.
3. With the pin on, `handleTap` and `onSwipe` skip "close the chrome" (`if (chromeVisible && !app.pinChrome)`). The
   taps a user makes to dismiss the menu therefore **turn pages back and forth**.
4. The setting is an `AppSettings` field, so every book opens this way.

**Removal**, READER_A unless noted:

| Symbol | Change |
|---|---|
| fields `pinShown`, `pinPending` | delete |
| `viewPart()` entry `a.pinChrome` | delete (phase 0) |
| `startOpen`: `if (app.pinChrome && !pinShown) { pinPending = true; applyPinnedArea() }` | delete |
| `showPage`: `if (app.pinChrome && !pinShown && !chromeVisible) { … }` | delete |
| `closeCurrentBook`: `pinShown = false` | delete; add `returnNav.reset()` |
| `setChromeVisible`: `pinPending = false`, `chrome.setPinned(app.pinChrome)`, `applyPinnedArea()` | delete; add `if (visible) returnNav.onChromeShown() else returnNav.onChromeHidden()` |
| `pinnedArea()`, `chromeBarHeights()`, `togglePin()` | delete |
| `applyPinnedArea()` | rename to `applyPageInsets()`: `topMargin = insets[1]`, `bottomMargin = insets[3]`, nothing else |
| `applyAppSettings`: `chrome.setPinned(app.pinChrome)`, `applyPinnedArea()` | → `applyPageInsets()` |
| `buildViews`: the `barsResized` listener on `chrome.top` / `chrome.bottom` | delete those two registrations and keep the one for extras overlays |
| `onBarsResized()` | only `updateChipPosition()` |
| `pageCallbacks.onSwipe` / `handleTap` / ≈ l.2439 / ≈ l.2588: `!app.pinChrome` guards | → unconditional (`if (chromeVisible) { closeChrome(); return }`) |
| `openReadingSettings()` pinned branch | delete: always `ReaderPanels.showReadingSettings(this, chrome.gear)` |
| `chromeActions.onPin = togglePin()` | → `onPinHere()` (§3.5) |
| `ReaderChrome.setPinned` with `isSelected`, "메뉴 고정" | READER_UI (§2.5) |
| `ReadingSettingsPopup.show`: `hideBars` / `anchor` branch | X-T: always hide the bars; `anchorBottom = Overlay.topInset(root)`; update the class KDoc |
| `PageTurningPage` "메뉴 고정" row | SETTINGS (phase 0) |

After this, the PageView's size depends **only** on the system insets. Showing or hiding the chrome flips overlay
visibility, and a tap with the chrome up always closes it.

---

## 3. Pin and return history (READER_UI: `reader/ReturnNav.kt`; wiring READER_A)

**[2026-10-05] User decision: a browser-style history, as ReadEra's.** The user rejected the mark/other model (★1–★4
below the line, until 2026-10-05) after CI showed the row "104쪽 | 지우기 | 106쪽으로" with a grey, non-clickable
"📌 104쪽" on the pinned page: "이전이 없으면 왼쪽에 사라지고 이전이 있으면 왼쪽이 생기는 방식이어야지. 오른쪽은 다음이
있으면 생기고 없으면 없고". Their two ReadEra screenshots (3259 pages): on page 1749 the row above the bottom panel reads
"< 1 페이지로 | 지우기 | 150 페이지로 >"; on page 1 it reads "지우기 | 1749 페이지로 >" (no left item, 지우기 still in the
middle). That is a back / forward history: at 1, seek to 1749 (back [1]); seek to 150, with or without reading 1749
first (back [1, 1749]); "‹ 1749" → on 1749 back [1], forward [150] = shot 1; "‹ 1" → on 1 back [], forward
[150, 1749] (the nearest, 1749, shows) = shot 2.
Kept from before: the pin button of the bottom bar (chrome.md item 4: "pin a RETURN POINT, not the chrome"), the row
right above the bottom panel in three equal columns with 지우기 that never moves (c184879), the floating chip after a
jump made with the menu hidden and ★5 (the chip's visibility is derived). **Review fix (2026-10-05):** ★3 (several
jumps before a manual turn keep only the first origin) swallowed the user's own sequence (a seek to 1749 looked at
without turning a page, then a seek to 150, left no 1749), so every remembered jump's origin is now a place, as in
ReadEra; ★3 stays only for the chip, which offers the first origin of such a chain.

### 3.1 What the user sees

| Action | Result |
|---|---|
| TOC, search, bookmark, go-to, seek bar, link or note jump (a *remembered* jump) from page F | F becomes the newest place to go back to; places gone back from are forgotten (as in a browser). With the menu open the row reads "‹ F쪽으로" · 지우기. |
| The same **with the menu hidden** | A **floating chip** appears bottom-left: "‹ F쪽으로 \| ✕". It hides after 2 manual turns, on ✕, on using the history (row or chip), on the pin and on 지우기. Opening the menu only *covers* it (the row shows F, or after several jumps in a row the newest origin, which steps back to F); closing the menu brings it back while the 2 turns have not passed. |
| Several remembered jumps before a manual turn (seek, seek again; TOC, then a link) | Every origin is a place, as in ReadEra: at 1, seek to 1749, then (with or without a page turned, the menu open or closed) seek to 150 → on 150 "‹ 1749쪽으로", then "‹ 1쪽으로". ★3 (the chip only): the chip shown after such a chain offers its **first** origin (where you were reading, not a page passed while scrubbing); a tap on it goes there as that many ‹ taps would (the pages passed become places ahead). |
| Tap "‹ N쪽으로" (left) on page H | Go to N with the menu still open. N leaves the back list and H becomes the nearest place ahead: the row reads "‹ (the next older place)" or nothing on the left, 지우기, "H쪽으로 ›". |
| Tap "M쪽으로 ›" (right) on page H | Go to M. H becomes the newest place back; the right item shows the next place ahead, or nothing. |
| A tap on the row or the chip while a jump is still on its way (a far layout) | Ignored: the row still describes the page on screen. A use whose page cannot be laid out ("이 부분을 표시하지 못했습니다") is undone, so its place is not lost. |
| A new remembered jump after going back | The places ahead are forgotten (browser rule). |
| Menu open, tap the **pin** (outline icon, content description **"이 페이지 고정"**) | This page is saved as the newest place to go back to (the places ahead stay, less this page). The icon fills ("고정 해제"). The row never offers the page you are on and keeps offering the older place, so it does not change on this page (on 1749 in ReadEra's first shot it still reads "‹ 1쪽으로 · 지우기 · 150쪽으로 ›"); once you leave, "‹ P쪽으로" shows. The page does not move or relayout, and there is no toast. |
| On that page, tap the filled pin ("고정 해제") | That place is removed again; the icon is an outline. |
| 지우기 | Both lists are emptied (also in storage); the row and the chip go. |
| Next day, reopen the book | The history (both lists) is still there. It loads **after** the first page and shows in the row the next time the menu opens. |

**Pin rule (decided 2026-10-05, the simplest that gives clear feedback):** the pin icon is **filled iff back's top was
saved by the pin and is on the current page** (`pinTop`), i.e. right after pinning while you stay on that page, and
again when you page back to it by hand or reopen the book there. A jump's origin on this page is no pin: the icon is an
outline there, and a tap turns that place into the pin (no new entry). A tap on the filled pin removes that place
("고정 해제"); a tap on the outline pin saves this page. Pinning the page that is the nearest place ahead moves it to the
back list (it is not on both sides). Going back to a pinned page uses the pin up: it leaves the back list (you are on
that page) and the page you left becomes the nearest place ahead, so the icon is an outline there and, once you read
on, the pinned page is in neither list; pinning it again is one tap. Going back past a pin from its own page (‹ to an
older place) takes the pin along with the page you leave: it becomes the nearest place ahead.

Where the history stays off the page:
- Places never float over the page except the chip after a remembered jump. The page stays clean, and the history is
  one menu tap away.
- **[Δ]** `ReturnNav.PIN_FLOATS = true` (one constant, default **false**, §9 R13) would show the chip "‹ N쪽으로 | ✕"
  whenever back's top exists, the menu is hidden and that place is not on screen. It never hides by turns, and ✕ hides
  it until the next pin or jump. No other code changes, and there is still no relayout because the chip is an overlay.
- Chapter ⏮/⏭, auto turn, TTS and manual turns do not create places (unchanged).

### 3.2 `ReturnHistory` (pure, JVM-tested; in `ReturnNav.kt`)

State:
- `back: List<DocPosition>`: the places to go back to, **the most recent last**;
- `forward: List<DocPosition>`: the places gone back from, **the nearest last**;
- both keep `MAX = 20` places; the oldest go first;
- `offer: Boolean`: the chip offers back's top (the last remembered jump's way back). The chip shows iff `offer`, the
  chrome is hidden and back's top is not on screen (★5), so the offer survives opening and closing the menu;
- `turns: Int`: manual turns since that jump;
- `landed: Boolean`: a chain of remembered jumps is open (from a jump until the next manual turn, use, pin or clear);
- `chainBase` (private): the index in back of the chain's first origin, the chip's place while `offer` (★3, the chip
  only); a trim of the oldest place keeps it on the chain's oldest place still kept;
- `pinTop: Boolean`: back's top was saved by the pin and has not been replaced since (stored with the history).

"The same page" is the host's question, so every call takes `here: (DocPosition) -> Boolean` = `isOnCurrentPage`.
Every place pushed is the current one, so **a push is skipped when the list's top is `here`** (one entry per page).
The row asks for **each list's newest place that is not `here`**, so a place on this page (a pin, a jump's origin,
two places a re-parse put on one page) never hides an older one; these queries use index loops and allocate nothing.

| Call | Effect |
|---|---|
| `jumped(from, here): Boolean` | Push `from` on back (skipped when back's top is on this page), empty forward. If no chain is open, `chainBase` = back's top (the chain's origin). `landed = true; offer = true; turns = 0` (re-armed if ✕ had hidden it). True when a list changed (only then is the history stored). |
| `goBack(at, here)` | T = back's newest place not on this page; `null` if none. The places above T are on this page (a pin, an origin): they go, and `at` is pushed on forward (skipped when forward's top is on this page), so this page is kept ahead. Pop T, `pinTop = false`, end the chain (`offer = false; landed = false`), return T. |
| `chipBack(at, here)` | The chip: back to `chipPlace` as that many ‹ taps would: `at`, then the places above it (nearest last, those on this page left out) go on forward. `null` when back is empty. |
| `goForward(at, here)` | T = forward's newest place not on this page; `null` if none. Drop T and the places above it, push `at` on back (skipped when back's top is on this page, e.g. a pin, which stays the pin), end the chain, return T. |
| `pin(at, here)` | If `pinnedHere(here)`: pop back's top (고정 해제), `pinTop = false`. Else push `at` on back (an origin already on top here becomes the pin), `pinTop = true`, and drop forward's top places on this page. Forward is otherwise kept. End the chain. |
| `pinnedHere(here)` | `pinTop` and back's top is on this page (the pin icon). |
| `leftPlace(here)` / `rightPlace(here)` | back's / forward's newest place not on this page, or null. |
| `chipPlace()` | While `offer`, back[`chainBase`] (the chain's first origin); otherwise back's top. |
| `rowShown(here)` | `leftPlace != null \|\| rightPlace != null`. |
| `clear()` | Both lists empty; `pinTop = false; offer = false; turns = 0; landed = false`. |
| `manualTurn(): Boolean` | `landed = false`. Then, if `offer && ++turns >= 2` → `offer = false`, return true (hide now). |
| `hideChip()` (✕) | `offer = false` (the places stay: the row still has them). |
| `restore(storedBack, storedForward, storedPin)` | The stored lists, loaded after the first page. Places of this session stay on top: the stored back list goes under them (a stored top equal to the session's first place is kept once); the stored forward list is kept only while this session has no places at all (a jump would have cut it). `pinTop` takes `storedPin` when the stored top is back's top. |
| `reparsed(map)` | Each place goes where `map(index, place)` puts it (index in back-then-forward order; null drops it), a place equal to the one before it is dropped; `pinTop` stays only while back's top was kept; `offer = false; turns = 0; landed = false`. |
| `copyFrom(other)` | The whole state of `other` (ReturnNav undoes a use whose page could not be laid out). |
| `chipVisible(offer, chromeVisible, targetOnScreen)` | ★5 `offer && !chromeVisible && !targetOnScreen` (derived, never stored). |
| `label(page)` | "N쪽으로" (the row and the chip add the chevron). |

### 3.3 Persistence

**`ReturnHistoryCodec`** (pure, in `ReturnNav.kt`), in the existing column `book_prefs.return_mark` (no schema change):
- **Format:** `"h1|<back>|<forward>|<textSignature or empty>"`, each list oldest first as `;`-joined places
  `<section>,<offset>,<char fraction in millionths>`; back's top ends in `,p` when the pin saved it (`pinTop`).
  Example: `h1|0,1000,250000;4,50,500000,p|1,200,125000|`.
- **Size:** at most `BookPrefs.MAX_RETURN_MARK` (1000) chars. Two full lists of the longest places take about 850; the
  encoder leaves the oldest places out (the longer list first) until the text fits. An empty history encodes as null
  (the column is cleared).
- **`decode`** is tolerant: null for a bad prefix or shape or no valid place; a malformed place (bad numbers, a
  negative section or offset, a 4th field other than `p`) is skipped; fractions are clamped to 0..1; each list keeps
  its newest 20; a `p` on any place but back's top is ignored.
- **Migration:** an old single pin `"m1|<section>|<offset>|<charFraction>|<sig>"` (until 2026-10-05) decodes as a back
  list of that one pinned place, with the old rules (null for bad numbers, NaN, negative values; fraction clamped).
- **Placement when restoring** (each place): TXT whose stored signature differs from `LayoutKeys.textSignature(...)`,
  and not the very start → `counts.locateFraction(fraction)`; otherwise `DocPosition(section, offset)` clamped. EPUB
  always uses this, since its signature is null. Places found by fraction are stored again under this parse.

**DATA, `data/BookPrefs.kt`** (unchanged API; the text is now the history):
```kotlin
fun returnMark(bookId: Long): String?          // ReturnHistoryCodec text or null
fun setReturnMark(bookId: Long, value: String?) // null clears; longer than MAX_RETURN_MARK is not stored
```
- **Backups:** carried as `"returnMark"` in the book's `book_prefs` entry, opaque text (an old backup's "m1" pin
  restores and migrates on the next open).
- **Deletes:** `deleteBookRows` deletes the row; `resetProgress` ("읽은 기록 초기화") sets `return_mark = NULL`.

**When it is read and written:**
- **Load:** `afterOpen()` (after the first page) → `ReaderIo.launch { BookPrefs.returnMark(id) }` → main thread →
  `returnNav.restore(text)`, guarded by `isDestroyed || bookRef?.id != id || session == null` (a book switched meanwhile
  never gets the previous one's places). A re-parse meanwhile still gets it: the places carry their parse's signature
  and are found again by fraction. `restore` runs once per open and is a no-op after `reset()`. **A failed read is not
  applied** (no `restore(null)`): the history then counts as not loaded and nothing is written this session, so a
  database hiccup never overwrites the stored lists with this session's places alone.
- **Save:** on every change of the lists (jump, use, pin, 지우기, re-parse) through `ReturnHost.saveReturnMark(text)` →
  `ReturnWrites.launch { BookPrefs.setReturnMark(id, text) }`: one serial IO lane (`Dispatchers.IO.limitedParallelism(1)`,
  process-wide), so quick "‹" "›" taps are stored in the order they were made (`ReaderIo` is a pool). **Before the load has been applied nothing is written**:
  a change made meanwhile (e.g. the jump of an open-at-note) is written together with the stored history by
  `restore`, so it never overwrites the stored lists with this session's places alone. 지우기 before the load gives
  the stored history up (it is cleared, and the late load is ignored).

### 3.4 `ReturnNav` (views and logic)

```kotlin
internal interface ReturnHost {                       // implemented by ReaderActivity (READER_A)
    val chromeVisible: Boolean
    val navigating: Boolean                           // a jump is on its way (navJob active)
    fun currentPosition(): DocPosition                // paged: page start; scroll: top line
    fun isOnCurrentPage(pos: DocPosition): Boolean    // paged: on the page; scroll: in the visible range
    fun globalPageOf(pos: DocPosition): Int           // 1-based; estimate until counted (never "~")
    fun jumpToReturn(pos: DocPosition): Boolean       // no new place; true when the page is already shown
    fun charProgressOf(pos: DocPosition): Float       // counts.charProgress
    fun clampPosition(pos: DocPosition): DocPosition
    fun locateFraction(f: Float): DocPosition         // counts.locateFraction
    fun textSignature(): String?                      // LayoutKeys.textSignature(...) for TXT, null for EPUB
    fun saveReturnMark(text: String?)                 // IO write of ReturnHistoryCodec text
    fun onReturnChanged()                             // host: chrome.setPinned(...), updateChipPosition()
}
internal class ReturnNav(ctx: Context, private val host: ReturnHost) {
    val dock: View            // the history row (ReaderChrome inserts it)
    val chip: View            // the floating chip (ReaderActivity adds it to root, BOTTOM|START)
    fun pinnedHere(): Boolean                          // the pin icon: back's top is the pin's and on this page
    fun onJump(from: DocPosition)                      // every remembered jump, before it
    fun onManualTurn()
    fun onPinPressed()
    fun onJumpFailed()                                 // layoutFailed: a use made for this jump is undone
    fun onSectionLaidOut(section: Int)                 // onSectionStored: its places note their exact page
    fun onChromeShown()                                // hides the chip VIEW (offer kept) + binds the row
    fun onChromeHidden()                               // shows the chip iff offered and its place is off screen
    fun bind()                                         // cached; 0 alloc if unchanged
    fun restore(saved: String?)
    fun fractions(): FloatArray                        // before a reparse, with the OLD counts (back then forward)
    fun reparsed(fractions: FloatArray, exact: Boolean) // after it; exact = EPUB with the same section count
    fun reset()
}
```

**Dock = the history row** right above the bottom panel, on the page colour (`ChromePalette.page`; on e-ink with a
1 px `divider` on top), 48 dp (`ChromeMath.HISTORY_ROW_DP`), **three equal columns** (`LinearLayout`, weights 1/1/1,
each a `FrameLayout`) holding their labels at their own width and the full 48 dp height (`WRAP_CONTENT × MATCH_PARENT`),
so the touch target and the pressed rect hug the words:
- **Left** = `leftPlace`: `START`, padding 20 / 4 dp, 16 dp `ic_chevron_left` at the start (centred 28 dp in, on the
  icon column), text "N쪽으로", content description "N쪽으로". Tap → `goBack`.
- **[2026-10-05, review] Vertical:** every label pads its top by 10 dp (`ChromeMath.HISTORY_TEXT_TOP_DP`, bottom 0) and
  centres its text and chevron in the rest: their centre 29 dp under the row's top, 19 dp (57 px on the S25) above the
  panel, the glyphs ≈ 9 dp clear of the 4 dp shadow, as ReadEra's (18.5–18.8 dp and ≈ 8.3 dp in the user's S25 shots;
  centred in the box ours sat 24 dp above). The box, its touch target and the bar's height keep their 48 dp; the
  pressed rect starts 10 dp under the box's top (a layer inset of `chromePressed`), so it stays centred on the words.
- **A tap while a jump is on its way** (`host.navigating`) is ignored, on both sides and the chip: the row still
  describes the page on screen, and a second "‹" would take a second place before the first one is reached. A use
  whose page shows later keeps the history from before it; `onJumpFailed()` (called by `layoutFailed`) puts it back,
  and the next page shown with no jump on its way drops it.
- **A remembered jump with the menu up** stores its origin but leaves the row and the pin to the new page's
  `bindChrome` (bound before the jump, the row would change over the old page: two e-ink updates for one far seek).
  A jump never changes the pin's state.
- **Centre** "지우기": `CENTER`, `minWidth 72dp`, padding 16 dp on each side; never a third of the row. Tap → clear.
- **Right** = `rightPlace`: `END`, padding 4 / 20 dp, "M쪽으로" with 16 dp `ic_chevron_right` at the end. Tap →
  `goForward`.
- A side without a place is **`INVISIBLE`, not `GONE`**, so 지우기 and the other side never move. The row shows iff a
  side shows (`rowShown`), with the chrome visible. There is no grey or non-clickable state any more (the "📌 N쪽" label,
  `histOff` and its description are gone): the row only ever offers places to go to.
- 14 sp regular, tabular digits, `hist` text and chevrons; pressed rect on phones only.
- **Fit rule:** `ChromeMath.stripShort(left, right, rowW)`: a side label wider than its third (text + paddings +
  glyph, 0 when hidden) → both sides short, "‹ 12345" / "23259 ›". Content descriptions keep "12345쪽으로". The check
  runs only when a label or the row width changes.
- **Labels** are rebuilt only when a page number changes (cached ints), so `bind()` on a page turn with the chrome up
  allocates nothing. Before a place enters the history its page is asked while it is on screen (`notePage`), restored
  places are asked once when the history loads, and every place is asked again whenever its section is laid out
  (`onSectionLaidOut`, from `BookSession.Listener.onSectionStored`), so `ReturnPageMemo` keeps its exact page after its
  section leaves the layout cache. The memo holds `2 × MAX + 2` places (both full lists and the page being left; a
  linear scan, no allocation), so a place several taps deep never falls back to the char estimate.
- **Lazy views:** `dock` and `chip` start as empty `GONE` frames; their contents are created on first use.

**Chip:** a box on the bars' surface (1 px `edge` on e-ink or `track` on a phone) with `[label 48 dp tall: 18 dp
chevron + "N쪽으로", 15 sp, padding (12, 0, 14, 0) dp]`, a 1 px vertical line and `[✕ 48×48 dp, "닫기"]`. It offers
`chipPlace` ("‹ N쪽으로"): the last jump's origin, or after several jumps in a row their first origin (★3); a tap =
`chipBack`. ✕ **only hides** it; 지우기 clears. **Position** (READER_A
`updateChipPosition`, hidden-chrome branch): `bottomMargin = insets[3] + dp(PROGRESS_LANE_DP) + dp(4)`,
`leftMargin = insets[0] + dp(8)`.

### 3.5 READER_A wiring (`ReaderActivity`)

1. `returnNav = ReturnNav(this, returnHost)` in `buildViews` before `ReaderChrome(this, chromeActions, returnNav.dock,
   light)`; `returnNav.chip` added to `root`. `ReturnHost` is a **private object** (`returnHost`).
2. Remembered jumps (`goTo(remember)`, `goToPage(remember)`, `goToProgress`, links, the open-at-note's
   `if (!isOnCurrentPage(saved)) onJump(saved)`) → `returnNav.onJump(currentPosition())` before the jump, with their
   "not the page already shown" guards.
3. `onManualTurn()` → `returnNav.onManualTurn()`.
4. `chromeActions.onPinHere()` → `returnNav.onPinPressed()`.
5. `ReturnHost`: `navigating` = `navJob?.isActive == true`; `jumpToReturn(p)` = `jumpTo(p.section, p.offset, -1)`;
   `saveReturnMark(t)` = `ReturnWrites.launch { BookPrefs.setReturnMark(id, t) }`; `onReturnChanged()` =
   `if (chromeVisible) bindChrome(); updateChipPosition()`.
6. `bindChrome()`: `returnNav.bind()` and `chrome.setPinned(returnNav.pinnedHere())` (`ReaderChrome.setPinned(on)`:
   filled + "고정 해제" / outline + "이 페이지 고정").
7. Every page shown and the exact counts: `if (chromeVisible) bindChrome() else returnNav.bind()`.
8. `reopenDocument`: `val returnF = returnNav.fractions()` before switching sessions (old counts), then
   `returnNav.reparsed(returnF, exact = epub && sameSectionCount)`.
9. `closeCurrentBook`: `returnNav.reset()`.
10. `afterOpen`: the history load (§3.3); a failed read posts nothing.
11. `layoutFailed`: `returnNav.onJumpFailed()` before the old page's bind; `sessionListener.onSectionStored`:
    `returnNav.onSectionLaidOut(section)`.

**Until 2026-10-05** (replaced above, kept for the record): `ReturnPoints` held a pinned or temporary `mark`, an
`other` place and `offer: Chip { NONE, MARK, OTHER }`; the left item was the mark (grey "📌 N쪽" while on it), the
right the other place; ★1 a temporary origin became the other place on pin, ★2 only the pinned mark was stored
(`"m1|…"`), ★4 a jump from the mark kept the other place. Decoded today as a one-place back list.

---

## 4. Brightness (item 3; READER_UI owns the code, READER_A the hooks, SETTINGS the pages)

### 4.1 Diagnosis and decision

- **Fact:** only `WindowManager.LayoutParams.screenBrightness` is set (`ReaderWindow.applyBrightness`). The user
  confirms the Comet's front light ignores it.
- **Likely cause:** the Comet is very likely a Bigme build (LM3630A cold and warm channels, custom `Settings.System`
  keys `ColdValue`, `screen_brightness_cold`, …). On Android 14 an app can **read** those keys but **write** only
  `PUBLIC_SETTINGS` (`screen_brightness`, `screen_brightness_mode`), and only with WRITE_SETTINGS. The sysfs nodes
  need root. Source: brightness.md §1, F1–F9.
- **Decision:**
  1. Keep the window path as the default (phones).
  2. Add an **opt-in device path** ("기기 밝기 직접 조절").
  3. **Ask once per firmware**, and only on e-ink, whether the light changed.
  4. If nothing works, verdict NONE: the reader shows an honest link instead of a dead slider.
  5. Warm light is shown as a link only; no warm slider this round.
- **§9 R1 is the main risk:** neither path may move the Comet's light. The flow is built to discover that and say so.

### 4.2 Components (`reader/`, READER_UI; signatures fixed at phase 0)

| File | API | Notes |
|---|---|---|
| `LightCurve.kt` (pure) | `LEVEL_MIN = 1`, `LEVEL_MAX = 255`; `out(pos) = pos²`, `pos(out) = √out`, `level(out, min, max)`, `fraction(level, min, max)`, `isExternal(value, ours, sinceOurWriteMs, queued, echoMs = 1500)` | reference: brightness.md §3.1. **Device path only.** The window path keeps `ReaderWindow.applyBrightness(activity, pos)` linear with its 0.01 floor, and `ReaderWindow.systemBrightness()` stays. |
| `DeviceLight.kt` (process-wide `object`) | `init(ctx)`, `set(out)`, `restore()`, `restoreIfStale(ctx)`, `refresh()`, `verdict(ctx)`, `setVerdict(ctx, v)`, `asks(ctx)`, `countAsk(ctx)`, `looksEink(ctx)` **[Δ]** (IO; = `DeviceClass.cached(ctx) ?: DeviceClass.probe(ctx)` from the scroll SPEC, no word list of its own), `@Volatile noPermission`, `@Volatile deviceOut`, `onExternal: Runnable?`, `onNoPermission: Runnable?`, `VERDICT_UNKNOWN/WINDOW/DEVICE/NONE`, `MIN_GAP_MS = 100`, `ECHO_MS = 1500` | reference: brightness.md §3.2, **normative**: one serial `HandlerThread` created lazily; latest value wins; ≤ 10 writes/s; nothing allocated while dragging; manual mode forced once; the original value and mode committed to the `reader_light` prefs before the first write; install-stamp guard; `SecurityException` → `noPermission`. |
| `LightProbe.kt` (IO only, read-only) | `lightKeys(ctx): Map<String,String>`, `hasWarm(keys)`, `coldNode()`, `warmNode()`, `read(file)`, `report(ctx): List<String>` | reference: brightness.md §3.3. `report` also reads the xrz getters by reflection (getters only). |
| `LightController.kt` | see below | brightness.md §4, moved behind `LightHost` |

```kotlin
internal interface LightHost {                        // implemented by ReaderActivity (READER_A)
    val activity: Activity
    val handler: Handler
    val app: AppSettings                              // the live one
    val chromeVisible: Boolean
    fun saveApp(a: AppSettings)                       // ReaderActivity.saveApp
    fun setPageBrightnessSwipe(on: Boolean)           // page.brightnessSwipe = on
    fun showChrome()                                  // setChromeVisible(true)
}
internal class LightController(private val host: LightHost) {
    companion object { const val ASK_NONE = 0; const val ASK_WINDOW = 1; const val ASK_DEVICE = 2 }
    fun attach(chrome: ReaderChrome)                  // [Δ] called by ReaderActivity right after `ReaderChrome(...)`
    fun onCreate()                                    // window override only, no IO
    fun afterFirstPage()                              // afterOpen: DeviceLight.init, verdict (IO), first device write, observer, warm probe
    fun onAppSettingsApplied()                        // applyAppSettings(): apply(app.brightness) + swipe flag
    fun onResume(); fun onPause(); fun onDestroy(finishing: Boolean)
    fun markOwnLaunch()                               // right before the reader starts our SettingsActivity
    fun currentPos(): Float                           // PageView.brightnessStart
    fun onDrag(pos: Float, done: Boolean)             // slider AND edge swipe; save + PREF_LAST_BRIGHTNESS on done; first-drag question
    fun onAuto()                                      // Ⓐ: auto ↔ manual (last position)
    fun onSwipeSwitch(on: Boolean)
    fun onAnswer(yes: Boolean); fun onDeviceSwitch(on: Boolean); fun onOpenPanel()
    fun bind()                                        // from bindChrome(): setBrightness, setLight*, setSwipeOption, reopen panel if asking
    val swipeUsable: Boolean                          // false at verdict NONE
}
```

READER_A hooks, one line each:
- `onCreate` → `light.onCreate()`.
- `afterOpen` → `light.afterFirstPage()`.
- `applyAppSettings` → `light.onAppSettingsApplied()`. This replaces `ReaderWindow.applyBrightness(this, app.brightness)`.
- `onResume` / `onPause` / `onDestroy` → the matching `light.*` call.
- Before `startActivity(SettingsActivity)` from the reader menu → `light.markOwnLaunch()`.
- `pageCallbacks.brightnessStart()` → `light.currentPos()`.
- `pageCallbacks.onBrightness(v, done)` → `light.onDrag(v, done)`, plus today's `showBrightnessOverlay(v, done)`.
- `bindChrome()` → `light.bind()`.
- Delete `setBrightness(...)` and the chrome's brightness actions; `PREF_LAST_BRIGHTNESS` moves into LightController
  with the same key.
- Add `a.brightnessDevice` to `viewPart()`.

### 4.3 Lifecycle and restore policy

The policy decided in brightness.md §6 is kept as is:
- **When the device path writes:** only after the first page (`lightReady`).
- **Observer:** registered only while the reader is in front with the device path on. It watches
  `Settings.System.SCREEN_BRIGHTNESS`.
- **Leaving the reader** (`onPause`, screen interactive, not our own settings page) with `brightnessRestore` on →
  `DeviceLight.restore()`.
- **Screen off:** no restore.
- **Crash:** the pending original is repaired by `DeviceLight.restoreIfStale` from LIBRARY. That is one line in
  `LibraryActivity`, on IO, **after the first list is shown**.
- **User changes the light in the device panel while reading** → `isExternal` → their value wins: nothing is put back,
  and the slider adopts it (`adoptDeviceLight`, round-trip exact).
- **Ⓐ (auto)** → restore now and stop writing. **Switch off** → restore now.
- **Revoked permission** → `SecurityException` → window path at once. The subtitle says so.

**[Δ] Four fixes to brightness.md §3.2 (normative; `LightCurveTest` covers the pure parts):**
1. **Never put back a stale value.** The observer is off while the reader is paused or the screen is off, and after a
   crash. A value the user set in the system panel during that time must win.
   - `DeviceLight` persists `K_LAST` (the level it last wrote) with `apply()` when a drag finishes and right before
     `restore()`. It is never persisted per drag write.
   - `restoreTask` (and so `restoreIfStale`) reads the current level first. It puts the original back only if
     `LightCurve.stillOurs(current, last)` is true, i.e. `|current − last| ≤ max(2, last / 32)`, which tolerates
     vendor quantisation. Otherwise it just clears `pending`.
   - Example: the reader crashes at 30 (original 200), and the user then sets 120 in the panel. The next library
     start keeps 120. The old code would have forced 200.
2. **Auto-brightness always comes back.** `brightnessRestore = false` ("나가도 그대로 유지") keeps the *level*, but
   the mode is always restored: if `origMode` was automatic, leaving the reader writes the mode back. Without this, a
   phone would silently lose auto-brightness for good. The switch subtitle then reads
   "… · 나가도 그대로 유지 (자동 밝기는 다시 켜짐)" when `origMode` was automatic. The Comet has no light sensor, so
   nothing changes there.
3. **No pending record without permission.** `write()` checks `Settings.System.canWrite(c)` once per enable, on the
   light thread, before it commits `pending`. A restored or revoked switch then leaves no half-recorded original
   behind. `SecurityException` stays the per-write signal.
4. **E-ink detection** is `DeviceClass` (scroll SPEC §1.11), shared with scroll STEP. brightness.md's own
   `EINK_WORDS` is dropped. It matched `Build.DEVICE` "comet", which is also the **Pixel 9 Pro Fold's codename**, and
   brand-only "hisense", which also makes LCD phones. `DeviceClass.einkByBuild` must match makers by
   `MANUFACTURER`/`BRAND` ("innospace", "bigme", "onyx", "boox", …) and Hisense only by e-ink model
   ("A5", "A7", "A9", "Touch"), never by device codename substrings. `DeviceClassTest` adds a
   `("Google", "google", "Pixel 9 Pro Fold")` → false case.

### 4.4 The verdict flow and UI states

The state machine is reference brightness.md §4.3, and the pure `nextVerdict(ask, yes)` is extracted for tests.

- **Trigger:** the first finished drag while the verdict is `UNKNOWN`.
  - `looksEink(ctx)` (**[Δ]** `DeviceClass`) is false (phones) → verdict WINDOW silently. Nothing is ever asked.
  - E-ink and asked < 3 times → **ASK_WINDOW**.
- **ASK_WINDOW:** the question row appears at the top of the options panel, and the panel opens the next time the
  chrome shows: **"전면광 밝기가 바뀌었나요?"** [예] [아니요]. Buttons are 15 sp bold, each ≥ 56×48 dp.
  - 예 → verdict WINDOW.
  - 아니요 → dialog §4.5-A → [허용하러 가기] → `ACTION_MANAGE_WRITE_SETTINGS` (`package:` uri; fallback without the
    uri; fallback dialog §4.5-C) → back in the reader, `canWrite` checked on IO:
    - granted → `brightnessDevice = true`, apply now, **ASK_DEVICE**;
    - not granted → toast "권한이 허용되지 않아 앱 화면 밝기로 조절합니다".
- **ASK_DEVICE:** "막대를 움직여 보세요. 전면광이 바뀌나요?" [예] [아니요].
  - 예 → verdict DEVICE.
  - 아니요 → restore, `brightnessDevice = false`, verdict NONE, swipe off (`setPageBrightnessSwipe(false)`), dialog §4.5-B.
- **Auto-confirm:** brightness.md §4.4. After each finished drag, with a 400 ms delay and an IO read: a readable
  LM3630A node that changed, or a vendor light key that followed our write, answers 예 by itself.
- **Persistence:** the verdict is stored in `reader_light` prefs with `Build.FINGERPRINT`, so an OTA asks again. It is
  device-local and never backed up. "밝기 방식 다시 확인" resets it.

**Verdict NONE, brightness row:** `[ic_brightness_medium] 기기 조명 설정에서 조절 [ic_chevron_right]` (15 sp link,
weight 1, 48 dp, the drawables as 24 dp / 18 dp compound drawables) plus the ⌄ button. **[Δ]** No "☼" or "›"
characters: U+263C is missing from some firmware fonts, and the chevron drawable matches the strip's.

**Options rows**, in order:

| Row | When | Title / subtitle / action |
|---|---|---|
| question | asked | per ASK_* above |
| 스와이프로 밝기 조절 | always | "화면 왼쪽 가장자리를 위아래로 밀어 밝기를 바꿉니다" / NONE: disabled, "이 기기에서는 밝기 스와이프를 쓸 수 없습니다" |
| 기기 밝기 직접 조절 | always | Subtitles:<br>• off: "전면광이 안 바뀔 때 켜세요 · 기기 전체 밝기를 바꿉니다"<br>• on + restore: "기기 전체 밝기를 바꿉니다 · 리더를 나가면 원래대로"<br>• on, no restore: "… · 나가도 그대로 유지"<br>• permission missing: "'시스템 설정 수정' 권한이 필요합니다 · 눌러서 허용"<br>• NONE: disabled, "이 기기는 앱이 전면광을 바꿀 수 없습니다"<br>Turning it on → the permission flow if needed. Turning it off → restore. |
| 기기 조명 설정 열기 › | a warm channel was detected, or verdict NONE | "색온도(따뜻한 빛)는 기기 조명에서 바꿉니다" (NONE: "밝기와 색온도는 기기 조명에서 조절합니다"). Opens `ACTION_DISPLAY_SETTINGS`; if that does not resolve, toast "화면 위에서 아래로 내려 기기 조명을 조절하세요". |

### 4.5 Dialogs

All use `ctx.alert()` (InkDialog) with no animation. The texts are brightness.md §5.3–5.5, verbatim:
- **A. "기기 밝기 직접 조절":** explains the global effect and the restore-on-leave behaviour. Buttons [취소]
  [허용하러 가기].
- **B. "앱에서 조명을 바꿀 수 없어요":** buttons [확인] [기기 설정 열기].
- **C. "권한 화면을 찾을 수 없어요":** shows the adb one-liner
  `adb shell appops set com.ggumtak.readeraplus WRITE_SETTINGS allow`. Buttons [닫기] [명령 복사].

### 4.6 Settings pages (SETTINGS)

- **`MainPage`, "읽기 설정"**, right after "스와이프로 밝기 조절" (≈ l.74):
  - toggle **기기 밝기 직접 조절** (subtitles as §4.4; on without `canWrite` → dialog A → permission page;
    `SettingsPage.onResume()` re-checks `canWrite` on IO);
  - toggle **리더를 나가면 원래 밝기로** (disabled while the one above is off), "켜 두면 다른 앱과 서재는 원래 밝기를
    씁니다. 끄면 리더에서 바꾼 밝기가 기기 밝기로 남습니다.";
  - row **밝기 방식 다시 확인**, "다음에 밝기를 조절할 때 어떤 방식이 되는지 다시 묻습니다" →
    `DeviceLight.setVerdict(ctx, VERDICT_UNKNOWN)` on IO;
  - nav row **기기 조명 설정 열기**.
- **`AboutPage`**, new section "조명 진단" (P1):
  - `LightProbe.report(ctx)` lines, filled on IO when the page opens, each copyable;
  - a [변화 감지 30초] button (brightness.md §5.7). It registers a `ContentObserver` on
    `Settings.System.CONTENT_URI` with descendants for 30 s and lists changed keys.
  This is how the user reports what the Comet firmware does (§9 D1).

### 4.7 Cost

- A page turn does no brightness work.
- **Drag (main thread):** one volatile float, one CAS and a pooled Message. At most 10 settings writes a second, on
  the light thread.
- **Idle:** no timers. The observer is registered only in front on the device path.
- **E-ink:** the question row, link and subtitles are each one partial update of the top bar, and only when they
  change.

---

## 5. Status slots and progress line

### 5.1 Geometry (READER_B: `reader/LayoutKeys.kt`)

```kotlin
fun geometry(s: ReaderSettings, viewW: Int, viewH: Int, density: Float, statusPx: Float): PageGeometry {
    …ml, mr, mt, mb as today…
    val band = Math.round(statusPx * STATUS_BAND)
    val header = if (s.hasHeader) band else 0
    val footer = if (s.hasFooterText) band else 0
    val lane = if (s.progressBar) Math.round(ReaderSettings.PROGRESS_LANE_DP * density) else 0
    val mbEff = maxOf(mb, lane)                   // the line lives in the bottom margin; only a margin < 12 dp grows
    var h = viewH - mt - mbEff - header - footer
    var top = mt + header
    …minBox rule as today…
}

/** Only a band appearing or disappearing (or the lane outgrowing the margin) moves a line. */
private fun layoutPart(s: ReaderSettings): ReaderSettings {
    val mbDp = if (s.pageMargins) s.marginBottomDp.coerceAtLeast(0) else TINY_MARGIN_DP
    val hh = s.hasHeader; val ff = s.hasFooterText
    return s.copy(
        invert = false,
        headerLeft = StatusItem.NONE, headerCenter = if (hh) StatusItem.CHAPTER else StatusItem.NONE, headerRight = StatusItem.NONE,
        footerLeft = if (ff) StatusItem.PAGE else StatusItem.NONE, footerCenter = StatusItem.NONE, footerRight = StatusItem.NONE,
        progressBar = s.progressBar && mbDp < ReaderSettings.PROGRESS_LANE_DP,
        statusFontSizeSp = if (hh || ff) s.statusFontSizeSp else ReaderSettings().statusFontSizeSp,
    )
}
```
- The key composition is unchanged. **No `LayoutKeys.VERSION` bump.**
- A migrated user whose footer disappears gets a taller box, so a new key, so one background recount. The scroll
  spec's 40 dp side margins cause a recount anyway, so the two coincide.
- **Item ↔ item is a repaint. `NONE` ↔ item is a relayout.**

Comet numbers (density 2; 11 sp = 22 px; `band = round(22 × 2.2) = 48 px`; default `mb = 16 dp = 32 px`;
`lane = 24 px`):

| Config | Content bottom | Footer text band (baseline centred) | Line `yc` |
|---|---|---|---|
| **Default** (no footer text, line on) | 1440 − 32 = **1408** (same as no line) | – | 1428 |
| Footer text + line | 1440 − 32 − 48 = 1360 | [1360, 1416] | 1428 |
| Footer text, no line | 1360 | [1360, 1440] (today's rule) | – |
| `pageMargins = false` (4 dp) + line | 1440 − 24 = 1416 (8 dp less text) | – | 1428 |

### 5.2 Shared types (RENDER owns them; phase 0 lands them)

`render/Render.kt`:
```kotlin
class PageDecor(
    val highlights: List<Highlight> = emptyList(),
    val bookmarked: Boolean = false,
    /** Status slots + progress of the page on screen (shared, mutable, UI thread only); null = draw none (covers, thumbnails). */
    val status: StatusDecor? = null,
    /** StatusDecor.version when this decor was built: sameDecor compares this int, never strings. */
    val statusVersion: Int = 0,
)   // header, footerLeft, footerRight, battery: deleted
```
`render/StatusDecor.kt`:
```kotlin
/** One slot: text in a fixed buffer, or a title by reference, plus an optional battery icon with its digits. */
class StatusSlot {
    @JvmField val chars = CharArray(CAPACITY); @JvmField var length = 0
    @JvmField var text: String? = null                    // elastic title (CHAPTER / BOOK_TITLE), drawn instead of chars
    @JvmField var battery = -1                            // ≥ 0: icon + batteryChars after the chars
    @JvmField val batteryChars = CharArray(3); @JvmField var batteryLength = 0
    val isEmpty: Boolean get() = length == 0 && text == null && battery < 0
    /** Sets chars from [src][0, n) and the battery; true when anything changed. No allocation. */
    fun set(src: CharArray, n: Int, battery: Int): Boolean
    fun setText(t: String?): Boolean                      // equals compare; the reference is kept (never copied)
    fun clear(): Boolean
    companion object { const val CAPACITY = 48 }
}
class StatusBand {
    @JvmField val left = StatusSlot(); @JvmField val center = StatusSlot(); @JvmField val right = StatusSlot()
    val isEmpty: Boolean get() = left.isEmpty && center.isEmpty && right.isEmpty
}
class StatusDecor {
    @JvmField val header = StatusBand(); @JvmField val footer = StatusBand()
    /**
     * [Δ] The progress lane exists (= settings.progressBar). The lane decides the footer band's geometry, so it comes
     * from the settings, never from the data: an unknown position must not move the footer text by 12 dp.
     */
    @JvmField var lane = false
    /** 0..1 dot position; < 0 = unknown (lane drawn with its track and caps, no dot). */
    @JvmField var progress = -1f
    /** Bumped by StatusModel.update whenever anything drawn changed. */
    @JvmField var version = 0
}
```

### 5.3 The model (READER_UI: `reader/StatusModel.kt`; filled by READER_A)

```kotlin
/** Inputs of one status update for the page on screen. Reused, primitives and existing references only. */
internal class StatusInputs {
    @JvmField var page = 0; @JvmField var total = 0              // globalPage / counts.total()
    @JvmField var percent = 0                                     // ReaderFormat.percent(progress())
    @JvmField var bar = -1f                                       // char progress of the page start; last page = 1; -1 = off
    @JvmField var chapterTitle: String? = null; @JvmField var bookTitle: String? = null
    @JvmField var chapterStartsHere = false                       // the page begins the chapter: CHAPTER draws nothing (§6 P1-16)
    @JvmField var chapterPagesLeft = -1
    @JvmField var minutesEpisode = -1; @JvmField var minutesBook = -1
    @JvmField var epNumbered = false; @JvmField var epNumber = -1; @JvmField var epMax = -1
    @JvmField var tocIndex = -1; @JvmField var tocCount = 0
    @JvmField var minuteOfDay = -1; @JvmField var is24 = true
    @JvmField var battery = -1
}
internal class StatusModel {
    val decor = StatusDecor()
    /** Fills decor for the slots of [s]. Zero allocation. True when anything drawn changed (then decor.version++). */
    fun update(s: ReaderSettings, inp: StatusInputs, trackPx: Int): Boolean
    /** One-off String of [item] for the slot chooser (allocates; never on a turn). */
    fun sample(item: StatusItem, inp: StatusInputs): String?
}
/** Allocation-free formatters; output identical to their ReaderFormat twins (tested). Return the new length. */
internal object StatusText {
    fun page(buf: CharArray, at: Int, page: Int, total: Int): Int        // "12 / 3259" (total ≥ page)
    fun percent(buf: CharArray, at: Int, p: Int): Int                    // "34%"
    fun clock(buf: CharArray, at: Int, minuteOfDay: Int, is24: Boolean): Int  // "14:05" / "2:05"
    fun chapterLeft(buf: CharArray, at: Int, pages: Int): Int            // "챕터 5쪽 남음" / "챕터 마지막 쪽"
    fun episode(buf: CharArray, at: Int, numbered: Boolean, n: Int, max: Int, idx: Int, count: Int): Int  // "123/540화" / "87/612"
    fun timeLeft(buf: CharArray, at: Int, book: Boolean, minutes: Int): Int   // "이 화 3분" / "책 7시간 20분" / "… 1분 미만"
    fun int(buf: CharArray, at: Int, v: Int): Int
}
```

**`update` rules:**
- **Formatting:**
  - Only the items in the 6 slots are formatted.
  - Korean constant pieces are copied with `String.getChars`, which does not allocate.
  - An unknown input (−1 or null) leaves that slot empty.
- **Items:**
  - CHAPTER uses `setText(chapterTitle)`, or clears the slot when `chapterStartsHere`. BOOK_TITLE uses
    `setText(bookTitle)`.
  - BATTERY: `battery = level`, `batteryChars` = digits, no chars.
  - CLOCK_BATTERY: chars = the clock, plus the battery.
- **Progress line:** **[Δ]** `decor.lane = s.progressBar`; `decor.progress = if (s.progressBar) inp.bar else -1f`.
  For change detection, the dot counts as moved only when `round(bar × trackPx)` changed.

**READER_A `buildDecor()`** (and the scroll spec's settle path):
- **Fill scope:** fill only the inputs whose item `settings.shows(…)`. `bar` is filled when `progressBar` is on.
- **Chapter:** one `chapters.indexAt` when CHAPTER or EPISODE is shown (existing).
  `chapterStartsHere = idx >= 0 && chapters.section(idx) == curSection && chapters.offset(idx) == p.start`.
- **Page / percent:** `page`, `total` and `percent` from the existing helpers (O(1)).
  `bar = if (last page of the book) 1f else counts.charProgress(curSection, p.start)`.
- **Clock without `Calendar`:** `minuteOfDay = (((now + tz.getOffset(now)) / 60_000) % 1440).toInt()`. `tz` is a
  cached `TimeZone.getDefault()` and `is24` a cached `DateFormat.is24HourFormat(this)`, both refreshed in `onResume`.
  Today's `clock()` allocates a `Calendar` and a `String` per turn: delete it.
- **Battery:** today's sticky read, at most once a minute, with the `IntentFilter` cached in a field.
- **[Δ] When the clock and battery are sampled (the footer redraw rule).** `buildDecor(sample: Boolean)`:
  - `sample = true` only from `showPage` (turn, jump, open, relayout), the scroll settle and `onResume`. `onResume`
    uses `refreshDecor(onlyIfChanged = true)`; the window is redrawn on resume anyway, so this adds no e-ink update.
  - Every other caller passes `false`: `refreshDecor` from counts complete, highlights, bookmarks, quote reload,
    episodes and `redraw()`, and `repaint()`. They reuse the last `minuteOfDay` / `battery` in `StatusInputs`. A
    background event therefore redraws the page only when *its own* data changed. Without this, a counts-complete
    event 3 minutes after a turn would also move the clock, which is an unsolicited full-page e-ink update. Today's
    `clock()` in `buildDecor` already has that bug.
- **[Δ] Mutation invariant.** `status.update` mutates the one shared `StatusDecor` that the frame on screen also
  points to. It may run only in the same main-thread step that installs the new `PageFrame` / `scroll.decor` and
  invalidates. `refreshDecor` and `repaint` already return early when the frame is stale, and that check must stay
  **before** `buildDecor`. `sameDecor` compares the `statusVersion` snapshots, never the shared object.
- **Highlights:** allocate the `ArrayList` **lazily**, only when a highlight overlaps the page; otherwise `emptyList()`.
- **Result:** `val changed = status.update(settings, inputs, trackPx)`, then
  `PageDecor(hl, bookmarked, status.decor, status.decor.version)`.
  `trackPx = viewW − 2·round(12dp) − 2·round(rCap + rDot)` (§5.4; **[2026-10-05]** `viewW − 2·round(7dp) − dot`, below).
- **`sameDecor(a, b)`:** today's highlight and bookmark comparison, plus `a.statusVersion == b.statusVersion`.
- **Episodes:** `scheduleEpisodes()` when `settings.shows(StatusItem.EPISODE)`, in `afterOpen`, `reopenDocument` and
  `episodeLabel`.
- **`StatusSampleHost.statusSample(item)`:** fill a scratch `StatusInputs` for the current page, then
  `status.sample(item, it)`. This allocates, but only when the popup list opens.
- **`ReaderFormat`:** delete `footerLeft`, `footerRight` and `returnChip`. Their tests move to `StatusTextTest`.

### 5.4 Drawing (RENDER: `render/PageRenderer.kt` + pure `render/StatusMath.kt`, `render/ProgressMath.kt`)

`statusPaint.fontFeatureSettings = "tnum"`, set once in the constructor. Delete `FOOTER_SEP` and `footerSepWidth`.

**Shared entry point**, called by `draw(...)` (paged) and **[Δ]** by the scroll SPEC's `drawChrome(canvas, decor,
contentLeft, contentTop, contentWidth, contentHeight, viewWidth, viewHeight)`. `drawChrome` has no ribbon argument:
it computes `ribbonH` from `decor.bookmarked` exactly as `draw()` does, so the header keeps clear of the ribbon that
`drawOverlay` paints last.
```kotlin
internal fun drawStatus(canvas: Canvas, decor: PageDecor, left: Float, top: Float, cw: Float, ch: Float,
                        viewW: Int, viewH: Int, ribbonH: Float) {
    val st = decor.status ?: return
    val lane = if (st.lane) Math.round(ReaderSettings.PROGRESS_LANE_DP * density) else 0   // [Δ] settings, not data
    if (!st.header.isEmpty) {
        val inset = RibbonMath.headerInset(…)                     // as today: keeps the ribbon clear
        drawBand(canvas, st.header, left + inset, cw - 2 * inset, centredBaseline(0f, top), HEADER)
    }
    if (!st.footer.isEmpty) drawBand(canvas, st.footer, left, cw, centredBaseline(top + ch, (viewH - lane).toFloat()), FOOTER)
    if (lane > 0) drawProgress(canvas, st.progress, viewW, viewH)
}
```

**`drawBand`:**
- **Measuring:** each non-empty slot's natural width is measured with no allocation: `statusPaint.measureText(chars,
  0, n)` or `measureText(text, 0, len)`, plus the battery width (`BatteryMath` body + nub + 2 px + digits) and a
  0.5 em gap when a slot has both chars and a battery.
- **Allocation:** `StatusMath.allocate(...)`.
- **[Δ] Cached per version.** The renderer keeps the six slot x positions and widths in a `FloatArray(12)` keyed by
  `(st.version, cw, header inset)`. Measuring and allocation run only when that key changes (a page turn with a
  changed status), never per draw. Redraws of the same page (TTS highlight, selection, and **every frame of the
  scroll SPEC's SMOOTH mode**, 60–120 draws a second) then cost 6 `drawText` calls and nothing else.
- **Drawing:** all three slots on **one shared baseline**: left at `x0`, centre at `x0 + (w − wc)/2` (exactly centred
  on the text column), right at `x0 + w − wr`. Chars use `canvas.drawText(char[], 0, n, x, y, paint)`; the battery
  uses the existing pixel-aligned `drawBattery`.

**`StatusMath.allocate(w, gap, nl, nc, nr, el, ec, er, minElastic, out: FloatArray)`** is pure and tested.
- **Inputs:**
  - `n*`: natural widths (0 = empty);
  - `e*`: elastic (title) slots;
  - `gap = max(statusPx, 8dp)`, i.e. 1 em;
  - `minElastic = 3·statusPx`.
- **Rules:**
  - **No centre:** natural widths if `nl + nr + gap ≤ w`. Otherwise shrink the elastic side(s):
    - both elastic → split `w − gap`, where a side that needs ≤ half keeps its natural width;
    - one elastic → it gets `w − gap − fixedOther`;
    - both fixed and overflowing → hide the left.
  - **Centre present:** `side = max(fixed widths of L, R)`, and `wc = ec ? min(nc, w − 2(side + gap)) : nc`. Each
    side gets `room = (w − wc)/2 − gap`: an elastic side gets `min(n, room)`; a fixed side is `n` if `n ≤ room`,
    else hidden.
  - An elastic slot allocated less than `min(natural, minElastic)` is hidden (0). It never shows a lone "…".
  - **Fixed items are never shortened.**
- **Ellipsizing:** each of the 6 slot positions caches `(srcRef, allocatedPx) → CharSequence`, like today's
  `ellipsizedHeader`. So `TextUtils.ellipsize` runs only when the title or its width changes (about once per chapter).
- **Worked example:** 40 dp side margins → `w = 280dp`, gap 11 dp. Left `123 / 3614` (55 dp), centre = chapter title,
  right `14:05` (28 dp). The title gets `280 − 2·(55 + 11) = 148 dp`.

> **[2026-10-05, user] ReadEra's 탐색줄 replaces the table below** ("대놓고 빡!! 하고 보이는 게 아니라 있었구나 하면서
> 볼 정도로 사진을 최대한 카피해"): a `max(1, round(0.67dp))` line (S25 2 px, Comet 1 px) between two end dots and the
> position dot, all one size (`ProgressMath.dotD`: S25 14 px, Comet 9 px), outer edges 7 dp from the view's sides,
> centred 8 dp above its bottom (S25 line rows 2315–2316, dots 2309–2322; Comet 1423 and 1419–1427), in faint greys of
> the page (`PagePalette.progressLine` / `progressDot`: #D1D1D1 / #B4B4B4 on white, #1F1F1F / #323232 on black, whole
> e-ink levels on e-ink), never the status colour. The lane and the bands keep their heights. PLAN note at the top.

**`drawProgress`** (`ProgressMath` pure; px at density 2 in brackets). Black on white only (inverted: white on
black). Order: track, caps, dot.

| Element | Geometry | Paint |
|---|---|---|
| lane | bottom `PROGRESS_LANE_DP = 12dp` (24 px) of the view | – |
| `yc` | `viewH − round(6dp)` (1428), an integer px | – |
| track | rect `[x0, yc, x1, yc + 1)`, **1 physical px**, `x0 = round(12dp)` (24), `x1 = viewW − round(12dp)` (696) | `fg`, FILL, no AA |
| end caps | circles `rCap = 1.5dp` (3 px) at `(x0, yc + .5)` and `(x1, yc + .5)` | `fg`, AA |
| dot | circle `rDot = 3dp` (6 px) at `(round(d0 + f·(d1 − d0)) + .5, yc + .5)`, with `d0 = x0 + rCap + rDot` and `d1 = x1 − rCap − rDot`. At 0 it touches the start cap ("●●", as ReadEra); at 1 it touches the end cap. `trackPx = d1 − d0`. | `fg`, AA |

- **[Δ] Unknown position** (`progress < 0` with `lane`): track and caps only, no dot. The band geometry does not change.
- **Page edges, not the text column:** this matches ReadEra (4f482943) and is independent of the side margins.
- **Not interactive:** taps follow the tap zones.
- **Cost:** 1 rect and 3 circles per draw.

### 5.5 Settings UX

**Reading-settings popup** (EXTRAS_TOOLS, `ReadingSettingsPopup.addPage`, which replaces the phase-0-deleted rows):
```
상태 표시                                            (compactHeader; black group line above)
위     [ 없음 ] [ 챕터 제목 ] [ 없음 ]                  44dp slot-map row
아래   [ 없음 ] [   없음   ] [ 없음 ]                  44dp slot-map row
진행 막대                                       (●  )   44dp switch row, summary "화면 맨 아래 가는 선"
상태 글자 크기                            (−) 11 (+)    44dp; visible iff hasHeader || hasFooterText
```
- **Slot-map row:** `compactRow(topLine = true)` with a 40 dp label ("위" / "아래", 15 sp), then 3 buttons.
  - **Layout:** `weight 1`, 4 dp gaps, **44 dp tall** (the touch target), visual box 36 dp via
    `InsetDrawable(box, 0, 4dp, 0, 4dp)`.
  - **Text:** 13 sp, `maxLines 1`, END ellipsis, `item.short`.
  - **Filled slot:** 1 dp solid black border, radius 0, black text.
  - **Empty slot:** 1 dp **dashed** border (`GradientDrawable.setStroke(1dp, Ink.GRAY, 3dp, 2dp)`) and "없음" in
    `Ink.GRAY`. **[Δ]** The earlier value was `Ink.DISABLED`. #999 thresholds to white in fast e-ink modes, which
    would leave an empty-looking, unlabelled button. Dashed vs solid carries the state, and #555 stays visible.
  - **Content description:** **"아래 오른쪽: 시계"** (band word, position word, colon, item label). CI uses it.
- **Tap** → `CompactList.show(ctx, button, entries, widthPx = max(dp(220), button.width), maxHeightFraction = 0.8f)`.
  - `entries = StatusItem.entries.map { ListEntry(it.label, checked = it == cur, note = sampleOrExample(it)) { update(cur.withSlot(band, pos, it)) } }`.
  - `sampleOrExample` = `(host as? StatusSampleHost)?.statusSample(it) ?: it.example`, shown right-aligned in grey.
    The user sees the live value ("3 / 167", "1%", "제1화 시작", "14:05", "100"). **[Δ]** `sample(CHAPTER)` ignores
    `chapterStartsHere`, so the chooser shows the title even on a chapter's first page.
  - `CompactList.show` and `PopupGeometry.dropdown` gain `maxHeightFraction: Float = PopupGeometry.HEIGHT_FRACTION`
    (X-T, not frozen). 12 rows × 40 dp = 480 dp fits under 0.8 × 720 dp.
- **Apply:** `update()` → `host.applySettings(global)`. Item ↔ item repaints; NONE ↔ item relays out.

**Settings page** (SETTINGS, `PageTurningPage`, section "페이지 표시", ≈ l.155):
```kotlin
section("상태 표시줄")
note("위 · 아래 줄의 왼쪽 · 가운데 · 오른쪽에 보일 정보를 고르세요. 한 줄이 모두 '없음'이면 그 줄은 나타나지 않습니다.")
for (band in 0..1) for (pos in 0..2)   // "위 · 왼쪽" … "아래 · 오른쪽"
    valueRow(title(band, pos), r.slot(band, pos).label) { chooser(title, StatusItem.entries.map { "${it.label}${it.example?.let { e -> "  ($e)" } ?: ""}" }, idx) { i -> editReader { it.withSlot(band, pos, StatusItem.entries[i]) } } }
toggleRow("진행 막대", "화면 맨 아래에 읽은 위치를 가는 선과 점으로 표시", r.progressBar) { v -> editReader { it.copy(progressBar = v) } }
// existing "상태 표시 글자 크기" stepper stays; 흑백 반전 / 페이지 여백 rows stay where they are
```

**"기본값 복원"** (popup footer) resets to `ReaderSettings()`: header = chapter title, no footer, line on. The confirm
text already mentions 상태 표시.

### 5.6 Scroll mode ([Δ] rewritten against the final scroll SPEC, design B + C grafts)

- **Bands:** the header band, footer band and progress lane are **fixed in the viewport**. The geometry above is
  identical in both modes (scroll SPEC §1.4: "viewport = the paged content box"). The scroll SPEC's
  `PageRenderer.drawChrome` (§1.8 there) draws the background and calls `drawStatus(...)`, which draws the slots **and**
  the progress line unchanged. `drawBody` clips the text to the content box, so the lane is never scrolled over.
- **When the status updates:** at **settle** only (scroll SPEC §1.6 "the whole settle pipeline"), never per frame.
  `onTopPageChanged` rebuilds `scroll.decor` with `buildDecor(sample = true)`. Every settle also re-samples; if the
  version changed, `scroll.decor` is rebuilt in the same step. Inputs:
  - `page` / `total` = the **real** paged page holding the anchor line (design B: exact, not an estimate);
  - `bar` = the char progress of the anchor line (`counts.charProgress(section, anchor.offset)`), 1 at `atBookEnd()`;
  - `chapterStartsHere` = the anchor line is the chapter start.
- **One e-ink update per STEP [Δ].** In STEP mode the settle (and so the status update) must run in the same
  main-thread task as the step's `invalidate()`, so the moved text and the new footer land in **one** frame. Posting
  the settle would draw twice, i.e. two e-ink updates per tap.
- **Return point:** `ReturnHost.currentPosition()` = the virtual page's `(vp.section, vp.page.start)` (scroll SPEC
  §1.10); `isOnCurrentPage` = inside `visibleRanges`. `onManualTurn()` is called once per step and once per screen of
  accumulated drag (`ScreenCounter`). `jumpToReturn` = `showAt(…, TOP, JUMP)` through the existing `jumpTo` branch.
- **Pinned chrome:** does not exist any more. The scroll SPEC's two mentions are edited in the phase-0 pass (§1.11):
  `onScrollStart` closes the chrome unconditionally, and the §1.12 relayout row drops "pinned chrome".
- **SMOOTH frames:** `drawChrome` runs every frame. The per-version cache (§5.4) keeps that at 6 `drawText` calls
  plus 1 rect and 3 circles, with 0 measuring and 0 allocation.
- **Side margins:** 40 dp shown as "0" does not interact. Slot text follows the text column; the line follows the
  page edges.

### 5.7 Costs

| Event | Work | Allocation |
|---|---|---|
| Page turn | `update`: ≤ 6 slots, each O(1) into fixed buffers; `indexAt` O(log C) (existing); `charProgress` O(1); clock via cached tz; battery ≤ 1/min | **0 bytes for status**; highlight list only when a highlight overlaps. `PageFrame` + `PageDecor` remain (existing, one small object each). |
| Draw | ≤ 6 `drawText` (char[]), ≤ 2 batteries, 1 rect + 3 circles; **[Δ]** `measureText` only when `decor.version` or the width changed: < 0.1 ms | 0 (the ellipsize cache changes once per chapter) |
| Counts complete | page labels exact; with default settings (no PAGE item) **no status redraw** (version unchanged) | 0 |
| Slot change item → item | repaint | – |
| Slot change NONE ↔ item | relayout plus one recount under the new key (cached afterwards) | – |
| Background refresh (counts, highlights, bookmark, episodes) **[Δ]** | clock and battery are **not** re-sampled; a redraw happens only when that event's own data changed | 0 |
| `onResume` **[Δ]** | clock and battery re-sampled; `refreshDecor(onlyIfChanged)` rides on the resume redraw | 0 |
| Timers, idle work | **none**: the clock refreshes on turns, settles and resume only | – |

---

## 6. Polish list (P0 all, P1 most), with exact locations and values

"≈ l." = working-tree line. Owners use the §7 abbreviations.

| # | P | Owner | Location (symbol, ≈ line) | Exact change |
|---|---|---|---|---|
| 1 | P0 | READER_UI | `ReaderChrome` init, `pageLabel` (≈ l.150-165), `row` → `FrameLayout` | §2.4 (full-width centre, 17 sp bold, tnum, no underline, **[Δ]** fixed width `rowW − 216dp` set outside layout passes, autosize 14–17 sp, content description with the page) |
| 2 | P0 | READER_A, READER_UI, X-T, SET, contract | §2.6 table | pinned chrome deleted; pin = return point (§3) |
| 3 | P0 | READER_UI, READER_A, SET, LIB, contract | §4 | device path, verdict flow, honest fallback |
| 4 | P0 | READER_UI | `ReaderChrome` `brightnessShow`, expand button (≈ l.118-145) | §2.2/2.3 options panel |
| 5 | P0 | contract, READER_B, RENDER, READER_UI, READER_A, X-T, SET | §1.1, §5 | slots + progress line |
| 6 | P0 | contract + READER_UI | `Ui.kt` `pressableBackground` (≈ l.161-165); `ReaderChrome.setPinned/setRotationLocked/setBrightness` (`isSelected`, ≈ l.330-348) | delete `state_selected`; icon swap only |
| 7 | P1 | X-T | `ReadingSettingsPopup.show()` (≈ l.100-150); `ExtrasFormat.PopupGeometry` (≈ l.342-374) | `showAtLocation(root, TOP or CENTER_HORIZONTAL, 0, place.top)`. `SIDE_GAP_DP = 16`, `MAX_WIDTH_DP = 400` → `width = min(screenW − 16dp, 400dp)`. `settings(...)`: `top = anchorBottom + 8dp` where `anchorBottom = Overlay.topInset(root)`. `HEIGHT_FRACTION = 0.56f`. |
| 8 | P1 | X-T | `CompactUi.Compact` (≈ l.35-52), `compactRow`, `compactToggle` (≈ l.100-120), `addTypography` | `ROW_DP = 44`, `STEP_DP = 44`, `LABEL_SP = 15f`, `VALUE_SP = 16f`, `HEADER_SP = 13f`, toggle `minHeight = 36dp`. Merge 정렬 and 줄바꿈 into one row "정렬 [왼쪽│양쪽]   줄바꿈 [어절│글자]" → `MAIN_ROWS = 9` (9 × 44 = 396 dp). Group lines: `compactRowBackground(…, topLine)` draws `Ink.LINE_LIGHT` inset 12 dp for ordinary rows; **black** only before section headers (페이지 넘김, 글자, 페이지, 상태 표시, TXT 파일, EPUB 파일) and before 더보기. The 더보기 row shows "더보기" + chevron only (drop the grey summary). |
| 9 | P1 | contract (Ui.kt) | `label()`, `row()` summary, `note()` | PHRASE line breaking on API 33+; `keepAll()` for summaries and notes (§1.6). Verify on the Comet (§9 D3). |
| 10 | P1 | READER_UI, X-N | `ReaderChrome` title row (≈ l.115); `ContentsDialog` title (≈ l.72) | Chrome title padding `(20, 0, 16, 10)`, 17 sp bold, 1 line. TOC title 20 sp bold through the kit toolbar style (was 19 sp). |
| 11 | P1 | READER_UI, RENDER | `pageLabel`; `PageRenderer.statusPaint` (≈ l.46) | `fontFeatureSettings = "tnum"`, set once |
| 12 | P1 | contract (Toggle.kt, Ui.kt), SET | `InkToggle.onDraw`; `Ui.row()`; `SettingsPage.section` (≈ l.80-83), `navRow`/`valueRow` (≈ l.92-97) | Off = filled black knob (§1.6). Row end padding 16 dp. `section()` drops the hairline above (spacing only, header padding 24/8). `navRow` chevron and `valueRow` ▾ are both 24 dp `Ink.GRAY`. |
| 13 | P1 | X-T | `SelectionController.actionList()` / `showActions()` (≈ l.360-410) | One row of 5: **복사, 인용 (or 메모 when a quote exists), 메모 (or 인용 삭제), 사전·번역, ⋮ 더보기**. ⋮ opens `popupMenu` with 공유, 문단, 검색, 웹 검색, 여기서 읽기, 문구 지우기. Cells `(W − 16dp)/5` wide, 56 dp tall, labels 13 sp. `borderBox(radiusDp = 0f)`. Pure helper `SelectionActions.split(list): Pair<primary, overflow>` (tested). |
| 14 | P1 | LIBRARY, contract (res) | `LibraryViews` card (≈ l.150-166); `LibraryActivity` list (≈ l.424-432) | Card padding `(10, 10, 6, 10)` dp. Card border removed: a 1 px `LINE_LIGHT` separator between cards, inset 8 dp; pressed = `PRESSED` fill. The cover keeps its 1 px border. List `paddingEnd 12dp`. Theme fast-scroll drawables (§1.8). **Coordinate with task #19 (library views)** if it runs in the same pass: same owner, same files. |
| 15 | P1 | READER_UI | `ReturnNav.chip` | Same language as the strip: "‹ N 페이지로 \| ✕", 15 sp regular, 1 px border, radius 0 (§3.4). |
| 16 | P1 | READER_A, READER_UI | `buildDecor` `chapterStartsHere`; `StatusModel.update` | The header chapter title is not drawn on the page that begins that chapter ("프롤로그" over "프롤로그"). The band stays, so no relayout. |
| 17 | P1 | READER_A | `ReaderFormat.previewLabel` (≈ l.148) | "1234쪽 · 제3장 …" (was "p. 1234 · …"); `ReaderFormatTest` updated |
| 18 | P1 | X-T | `compactToggle` / `setCompactToggle` | A joined segmented control: one 1 px border, 1 px inner dividers, no radius. Selected = black fill + white **regular** text. There is no weight change, so the bold-width reservation hack goes. |

**Deferred (P2; not in this run):**
- Selection handles as teardrops.
- TOC current-row bar (drop the ▶ glyph).
- Search page-number style and snippet word cut.
- Library title weight and progress-fill thickness.
- Drawer selected state (bar instead of fill).
- Status font default 12 sp.
- Popup title header, and the "일반 설정" link.
- Chapter ticks on the progress line.
- Big-TXT estimate stability (§9 R9).

---

## 7. Owner map (disjoint files; compatible with the scroll SPEC in one implementation run)

### 7.1 Owners

The R2 owners are kept. READER_A's UI files are carved out into **READER_UI**. None of the three scroll designs edits
a READER_UI file: they touch `ReaderActivity`, `PageView`, `ReaderFormat`, `ReaderMenus` and new scroll files. The
scroll spec's owners then do not change, and READER_A's load halves.

| Owner | Files (main + their tests) | This spec's work | The scroll spec's work in the same files (for the same agent) |
|---|---|---|---|
| **CONTRACT** (lead, phase 0, serial) | `settings/*` (incl. new `StatusMigration.kt`), `data/SettingsJson.kt`, `data/LibrarySchema.kt`, `ui/kit/Ui.kt`, `ui/kit/Toggle.kt`, `reader/ReaderHost.kt`, frozen block of `reader/extras/ReaderPanels.kt`, `AndroidManifest.xml`, `res/**`, `docs/**`, `tools/ci/*`, skeletons and fallout (§1.9–1.10) | §1; CI steps §8.2 | ReadMode/ScrollStyle/autoBackup fields, `r.marginBase` migration, TRANSIENT keys, `PageInfo.lead`, `UserStyles` `marginBase`, the scroll stubs (§1.11), docs (**[Δ]** no `ContentOriginHost`: design B needs none) |
| **READER_UI** (new) | `reader/ReaderChrome.kt`, new `reader/ReturnNav.kt`, `reader/StatusModel.kt`, `reader/LightController.kt`, `reader/LightCurve.kt`, `reader/DeviceLight.kt`, `reader/LightProbe.kt`, new `reader/ChromeMath.kt` (pure: label max width, keylines) | §2, §3.2–3.4, §4.2–4.5, §5.3 model, polish 1, 4, 6, 10, 11, 15, 16 | none |
| **READER_A** | `reader/ReaderActivity.kt`, `PageView.kt`, `ReaderFormat.kt`, `ReaderMenus.kt`, `ReaderWindow.kt` (holds `ReaderIo`; this spec does not edit it, and the window brightness path is unchanged), `KeyMap.kt`, `TapZones.kt`, `ReaderMath.kt`, `ChapterIndex.kt`, `IntentFiles.kt`, `UriPaths.kt`, `TxtOverrides.kt`, `EndPanel.kt`, `ReadingTracker.kt`, plus the scroll spec's new reader files | §2.6 removals, §3.5 wiring, §4.2 hooks, §5.3 `buildDecor` / clock / battery / `StatusSampleHost`, polish 16, 17 | **[Δ]** final SPEC: `ScrollMath` / `ScrollReader`, the `scroll?.let` branch points, `PageView.ScrollInput`, `switchMode`, menu labels (as READER_CORE) |
| **READER_B** | `reader/BookSession.kt`, `PageCounts.kt`, `LayoutKeys.kt` | §5.1 | **[Δ]** final SPEC: `touch(section, shownTo)`, `startsUnit` (as READER_CORE); no strip config, no counts guard |
| **RENDER** | `render/*` except `FontCatalog.kt`: `PageRenderer.kt`, `Render.kt`, `StatusDecor.kt`, new `StatusMath.kt`, `ProgressMath.kt`, `Covers.kt` | §5.2, §5.4, polish 11 | **[Δ]** final scroll SPEC: `drawChrome` / `drawBody` / `drawOverlay` / `prefetchPage`, `drawLine(decode)`, `DeviceClass` (as ENGINE_RENDER, §7.1 mapping) |
| **EXTRAS_TOOLS** | `reader/extras/*` except EXTRAS_NAV's files: `ReadingSettingsPopup.kt`, `CompactUi.kt`, `ExtrasFormat.kt`, `SelectionController.kt`, `ReaderPanels.kt` (non-frozen part), … | §5.5 popup, §2.6 popup branch, polish 7, 8, 13, 18 | "넘기는 방식" row, the side-margin stepper and `Fmt.signed` |
| **EXTRAS_NAV** | `ContentsDialog.kt`, `InfoDialogs.kt`, `SearchPanel.kt`, `Episodes.kt`, `ui/kit/InkPager.kt`, `InkNumPad.kt` | polish 10 (TOC title) only | none required |
| **SETTINGS** | `ui/settings/*` | §4.6, §5.5 page, polish 12 (SettingsPage) | PageTurningPage "넘기는 방식" section, MainPage brightness summary in scroll mode, BackupPage auto-backup |
| **LIBRARY** | `ui/library/*` | `DeviceLight.restoreIfStale` call (§4.3), polish 14 | auto-backup triggers, the restore offer |
| **DATA** | `data/*` except the frozen ones: `BookPrefs.kt`, `Backup*.kt`, `BookRows.kt`, `Library*.kt` | §3.3 (`returnMark`, backup `"returnMark"`, `resetProgress`) | `AutoBackup.kt`, `InstallState.kt`, envelope `origin` / `summary` |
| **FORMAT** | `format/**` | none | none |

Shared hot spots, and why they do not conflict:
- **`ReaderActivity.kt`:** one owner (READER_A) does both specs' edits. This spec's edits there are hooks and
  deletions; the logic lives in READER_UI files.
- **`PageRenderer.kt`:** one owner (RENDER = ENGINE_RENDER). `drawStatus` becomes the single entry point that the
  scroll SPEC's `drawChrome` calls **[Δ]**. The scroll SPEC keeps the paged `draw()` byte-identical *for its own
  changes*; this spec's status changes inside `drawStatus` are the only paged-draw change.
- **`ReadingSettingsPopup.kt`:** one owner (X-T). This spec edits `addPage`, `show()` and `Compact`. The scroll spec
  edits `addPageTurning` and the margins row.
- **`PageTurningPage.kt`:** one owner (SET). The status section here; the "넘기는 방식" section from the scroll spec.
- **`LayoutKeys.kt`:** one owner (READER_B = READER_CORE). `geometry` / `layoutPart` here. **[Δ]** The final scroll SPEC
  does not touch it (its viewport is the paged content box); only its margin test expectations change.
  Scroll mode must stay out of `layoutPart`.

If the run wants a single reader owner, merge READER_UI into READER_A; nothing else changes.

**[Δ] Combined run with the final scroll SPEC (its §4 owner names win; this spec's names map onto them):**

| This spec | Scroll SPEC owner in a combined run | Notes |
|---|---|---|
| CONTRACT | CONTRACT (the lead) | One phase-0 commit "R3" with both specs' §1 lists, after R2 merges |
| READER_A + READER_B | **READER_CORE** | The scroll SPEC's READER_CORE already absorbs READER_B (`BookSession`); it also takes `LayoutKeys.kt` / `PageCounts.kt` and `ReaderMenus` (bookmark fallback, §2.2). |
| READER_UI | **READER_UI** (kept separate) | The scroll SPEC's Risk 2 says "READER_CORE owns all of `reader/*.kt`". It is amended to "…except `ReaderChrome.kt` and the new `ReturnNav`, `StatusModel`, `Light*`, `DeviceLight`, `ChromeMath`". The scroll SPEC edits none of these. |
| RENDER | **ENGINE_RENDER** | `PageRenderer.kt` gets both specs' work (`drawStatus`/`drawBand`/`drawProgress` plus `drawChrome`/`drawBody`/`drawOverlay`); also `Render.kt`, `StatusDecor.kt`, `StatusMath.kt`, `ProgressMath.kt`, `Covers.kt`, `DeviceClass.kt` |
| EXTRAS_TOOLS + EXTRAS_NAV | **EXTRAS** | The scroll SPEC's EXTRAS "only `ReadingSettingsPopup.kt` changes" is widened to `CompactUi.kt`, `ExtrasFormat.kt`, `SelectionController.kt`, the non-frozen part of `ReaderPanels.kt` and `ContentsDialog.kt` (polish 7, 8, 10, 13, 18) |
| SETTINGS + LIBRARY | **UI** | `ui/settings/*` and `ui/library/*`. `tools/ci/screenshots.sh` moves to UI as well, per the scroll SPEC, so both specs' shots are in one file. |
| DATA | DATA | `BookPrefs.returnMark`, backup `"returnMark"`, `resetProgress`, plus the scroll SPEC's `AutoBackup` / `InstallState` |

Dependencies across owners in phase 1 are only phase-0 stubs:
- READER_UI → `DeviceClass` (ENGINE_RENDER stub);
- READER_CORE → `ReturnNav`, `LightController`, `StatusModel` (READER_UI stubs);
- EXTRAS → `StatusSampleHost`.

### 7.2 Phases

1. **Phase 0 (CONTRACT, serial):**
   - §1.1–1.8 **plus the scroll spec's contract requests**;
   - skeletons (§1.9) and fallout (§1.10);
   - contract tests (§8.1 "contract");
   - `tools/typecheck.sh` green and `tools/snapshot_contracts.sh`.
2. **Phase 1 (parallel):** all other owners. Each owner runs `tools/typecheck.sh --own` and its tests.
   - READER_A codes against READER_UI's phase-0 signatures; READER_UI's stubs are safe no-ops, so the order does not
     matter.
   - RENDER and READER_B are independent.
   - X-T needs only `StatusSampleHost` and `StatusItem`.
3. **Phase 2 (lead):**
   - full `tools/unittest.sh`, then a `[screens]` CI run;
   - check every §8.2 expectation against the screenshots;
   - the adversarial review pass;
   - remove any `// R3 stub` left behind: `grep -rn 'R3 stub\|TODO("owner' app/src/main/java` must print nothing.

### 7.3 Order inside an owner, for the smallest broken window

- **READER_A:** removals (§2.6) first. They delete most relayout paths and make the chrome an overlay.
- **READER_UI:** `ReaderChrome` layout and the ReturnPoints/Codec tests, then `ReturnNav` views, `StatusModel`, and the
  light components.

### 7.4 [Δ] The scroll SPEC's assumptions, checked (the SPEC now exists)

| Earlier assumption | Final scroll SPEC | Consequence here |
|---|---|---|
| Keeps the R2 owners and does not edit READER_UI's files | Renames owners (READER_CORE, ENGINE_RENDER, EXTRAS, DATA, UI); edits no READER_UI file | §7.1 mapping table |
| Status bands fixed in scroll mode, drawn through `drawStatus`, updated at settle | Yes: `drawChrome` → the shared private `drawStatus`; decor rebuilt at settle / top-page change | §5.4 entry point, §5.6 |
| "Page" in scroll mode = the paged-equivalent page of the top line | Better: the **real** page holding the anchor line (exact) | §5.6 inputs |
| No pinned chrome, no bar-driven resize | Two stale mentions ("Close unpinned chrome", "pinned chrome" in the relayout row) | edited in phase 0 (§1.11) |
| Contract edits use different fields and keys | Yes: `readMode`, `scrollStyle`, `autoBackup`, `r.marginBase`, `TRANSIENT` additions | merged in one pass |
| (new) E-ink detection | `render/DeviceClass.kt` | `DeviceLight.looksEink` uses it (§4.3 fix 4) |
| (new) Paged-shot gate "differ only by the margins" | Would fail on this spec's chrome/footer | gate text widened (§1.11) |

## 8. Tests

### 8.1 JVM (`tools/unittest.sh`)

| Owner | Test | Cases |
|---|---|---|
| contract | `settings/StatusMigrationTest` (new) | absent keys → header CHAPTER, footer NONE; **untouched old defaults → footer NONE**; `showFooter=false` → NONE; `showHeader=false` → header NONE; page only → left PAGE; page+%+clock → PAGE/PERCENT/CLOCK; %+clock+battery → PERCENT/NONE/CLOCK_BATTERY; everything → PAGE/EPISODE/CLOCK_BATTERY; timeLeft 1/2; `Legacy.from(JSONObject)` with wrong types → null fields |
| contract | `settings/SettingsStoreTest` (+) | legacy prefs load migrated; after `saveReader` the legacy keys are gone and the marker exists; round trip of every item in every slot; unknown enum name → default; `saveApp` removes `a.pinChrome`; `brightnessDevice`/`brightnessRestore` round trip; `slot`/`withSlot` cover all 6 positions |
| contract | `data/SettingsJsonStatusTest` (new) | old backup with legacy defaults → NONE; customised legacy → mapping; new-backup round trip; `a.pinChrome` in an old backup neither mapped nor restored raw; `addUnmapped` never exports a `DROPPED_KEYS` key; **[Δ]** `a.brightnessDevice = true` on the device is never exported, and one in a backup never changes the device's value |
| contract | `data/LibrarySchemaV2Test` (+ v3 cases) | fresh file has `book_prefs.return_mark`; v1 → v3 and v2 → v3 via `upgradeStatements` (ALTER only when missing); every `LibrarySql` statement prepares |
| contract | `ui/kit/KitResourcesTest` (+) | `keepAll` inserts U+2060 only between Hangul syllables; ASCII and mixed text unchanged; the same instance is returned when there is no Hangul pair |
| READER_UI | `reader/StatusTextTest` (new) | each formatter equals its `ReaderFormat` twin over ranges: page 1..99999 × totals, percent 0..100, all 1440 minutes × 12/24 h, chapterLeft −1..999, episode both modes, durations 0..6000 min; the 48-char buffer is never exceeded |
| READER_UI | `reader/StatusModelTest` (new) | only shown items are formatted; `update` returns false for identical inputs and true for a changed minute, battery, page or dot px; dot < 1 px → false; `chapterStartsHere` blanks CHAPTER only; **[Δ]** `lane` follows `progressBar` even when `bar = −1`; `sample(CHAPTER)` ignores `chapterStartsHere`; **zero allocation**: 10 000 `update` calls allocate 0 bytes after warm-up (extract `TypesetterPerfTest`'s `getThreadAllocatedBytes` helper into `test/.../AllocCounter.kt`) |
| READER_UI | `reader/ReturnHistoryTest` **[2026-10-05]** (was `ReturnPointsTest`) | every §3.2 call; the user's two ReadEra sequences exactly (1 → 1749 → 150 with **no page turned**; back → on 1749 left 1, right 150; back → on 1 no left, right 1749); every remembered jump keeps its origin (seek, seek; TOC, link); forward then back; a new jump after going back cuts forward; ★3 the chip offers a chain's first origin and `chipBack` goes there as several ‹ would, a manual turn starts a new chain; both caps (a chain longer than the list); one entry per page (push skipped when the top is here); **pinning never hides the way back** (screenshot 1's state + pin → the row unchanged, the pin filled; reopened on a pinned page; two places on one page; paged back onto an origin); the filled pin only for a pin (`pinTop`; an origin becomes the pin on a tap); pinning forward's top; pin / unpin and the pin keeping forward; `copyFrom` (undo); `restore` under this session's places with the stored pin; `reparsed` maps, drops and dedupes, keeps a moved pin; the chip: 2 turns, ✕, using the history, 지우기; ★5 `chipVisible`; the row's sides and `rowShown` in every state; `label`. `ReturnPageMemoTest`: every place of a full history keeps its page; `ReturnHistoryCodecTest`: the pin's `,p` mark |
| READER_UI | `reader/ReturnHistoryCodecTest` **[2026-10-05]** (was `ReturnMarkCodecTest`) | round trip of both lists with the signature (bars inside it too); one side, empty signature = EPUB; empty → null; an old "m1" pin → a one-place back list (its old rules: malformed, NaN or negative → null, fraction clamped); a malformed place skipped; fractions clamped; two full lists fit `BookPrefs.MAX_RETURN_MARK`; an overlong text leaves the oldest places out; decode keeps the newest 20 |
| READER_UI | `reader/LightCurveTest` (new) | brightness.md §8: monotonic; `level` in 1..255; `level(out(pos(fraction(v)))) == v` for v in 1..255; `isExternal` table; **[Δ]** `stillOurs(current, last)` (equal, ±2, ±last/32 → true; the user's 120 vs our 30 → false); `LightProbe.KEY_RE` matches `ColdValue`, `screen_brightness_warm`, `LastWarmLight`, `screen_cool_brightness` and not `font_scale`; the pure `nextVerdict(ask, yes)` covers all 5 edges |
| READER_UI | `reader/ChromeMathTest` (new) | `labelMaxWidth(rowW = 720 px, density 2) = 288 px`; the label centre equals the row centre for any label width ≤ max; **[Δ]** `stripShort(left, centre, right, rowW, gap)`: false for "‹ 10 페이지로" at 720 px, true for "‹ 12345 페이지로" + "23259 페이지로 ›" at 1.3× font scale; `bookmarkFits(rowW)` flips at 352 dp |
| READER_UI **[2026-10-05]** | `render/ChromePaletteTest` (new), `reader/ChromeMathTest` (+), `reader/ReaderFormatTest` (+) | six shared sets, `of(eink = null)` = the e-ink set; `page` = the page background, accent = the status colour; 흰 바탕 on e-ink = the old `Ink` values; every e-ink set has no shadow, pressed, active or motion and opaque colours; dark e-ink sets = `PagePalette.grey` of the old greys; phone 흑백 반전: no shadow, a #333333 edge; contrast floors (text ≥ 7, text2 ≥ 4.5, history ≥ 4.5, accent ≥ 3) and a phone surface within 1.3 of its page; on a phone the floating boxes' `track` border ≥ 1.6 on the page, `active` ≥ 1.4 apart from `pressed` with the accent ≥ 3 on it. `stripShort(left, right, rowW)` (a side over its third), `HISTORY_ROW_DP = 48`, `animates(motion, scale)`, `SHOW_MS` / `HIDE_MS` in 150..200; `pageLabelCut`. **[2026-10-05, later / review]** ReadEra's bottom panel (label 25, seek 61, 85 dp of rows), the two rows' touch overlap = the space between their glyphs, `bottomGap` (S25 full screen 101 dp, upper split window 85, lower window and Comet keep their gap, a window of unknown position keeps 16 dp), the history text 19 dp above the panel (`HISTORY_TEXT_TOP_DP`), the title's 9 dp lift (`TITLE_LIFT_DP`: glyphs clear of the buttons' pressed circle); the brightness row's token = the page background in every look, its icons, slider and option text on the page (the views: CI 13t / 13u, checklist 11f-14 to 11f-18) |
| READER_A | `ReaderFormatTest`, `ReaderReviewFixesTest`, `ReaderR2FeaturesTest` (edit) | drop `returnChip`, `footerLeft/Right`; `previewLabel` → "1234쪽 · …" |
| READER_B | `reader/LayoutKeysTest` (+) | no bands when all NONE; header band iff `hasHeader`; item swap (PAGE→CLOCK, CHAPTER→BOOK_TITLE) is **not** a layout change; NONE→PAGE is; `progressBar` toggle with mb 16 dp gives the same geometry and no layout change; with `pageMargins = false`: box −8 dp and a layout change; `statusFontSizeSp` change with no bands: no layout change; the §5.1 table at density 2 |
| RENDER | `render/StatusMathTest` (new) | centre exactly centred with fixed sides; elastic centre = `w − 2(side + gap)`; fixed items never shrink; two elastic sides split; an elastic slot below `min(natural, 3em)` hidden; empty-centre cases; overflow of two fixed slots hides the left |
| RENDER | `render/ProgressMathTest` (new) | `yc` is an integer; track x0/x1; dot at f = 0 touches the start cap and at 1 the end cap; px rounding; `trackPx` |
| RENDER (ENGINE_RENDER) | `render/DeviceClassTest` (scroll SPEC, **[Δ]** +) | `("Google", "google", "Pixel 9 Pro Fold")` → false (codename "comet"); a Hisense LCD phone → false; Hisense A5/A7/A9 → true; Innospace → true |
| RENDER | `render/StatusDrawCacheTest` **[Δ]** (new, pure part of the cache key) | same `(version, cw, inset)` → no re-measure; a changed version or width → re-measure |
| X-T | `CompactSettingsTest` (edit), `PopupGeometryTest` (+) | width `min(W − 16dp, 400dp)`, centred; `top = inset + 8dp`; `HEIGHT_FRACTION 0.56` fits 9 × 44 dp + 2 px at 1440 px; `dropdown(maxHeightFraction = 0.8)` |
| X-T | `SelectionActionsTest` (new) | `split` → 5 primary in order (quote vs no quote variants), the rest in overflow; `문구 지우기` only for TXT |
| DATA | `data/BookPrefsBackupTest` (new or +) | `returnMark` in the book_prefs backup entry keyed by path; an old backup without it; `resetProgress` clears it |

### 8.2 Emulator screenshots (`tools/ci/screenshots.sh`, CONTRACT/lead; **[Δ]** the UI owner in a combined run with the scroll SPEC; run with `[screens]`)

New helpers:
- **`rawshot NAME`:** `adb exec-out screencap > shots/NAME.raw`, raw RGBA with no PNG, for pixel comparisons.
- **`tools/ci/raw_equal.py A.raw B.raw Y0 Y1`:** standard library only. It prints `EQUAL` or `DIFF n` for rows
  `[Y0, Y1)` and logs to `steps.txt`. It never fails the job, like the other steps. **[Δ]** It reads the header
  instead of assuming it: `w, h, fmt` as three little-endian uint32, and the header size is `file size − w·h·4`
  (12 bytes before Android 8.x, 16 with the colour-space field on the emulator's Android 14). It prints `BADSIZE`
  when the sizes differ. Row ranges must stay inside the PageView: the system bars (status-bar clock) change
  between shots.

Content descriptions used below are fixed by this spec: "밝기 옵션", "이 페이지 고정", "고정 해제", "지우기", "N쪽으로",
"아래 가운데: 없음", "페이지 이동".

| Shot | Steps (after today's 10–12) | What it must show |
|---|---|---|
| `10_txt_page1` | as today (defaults) | **No footer text.** The progress line spans x 24..696 px at y = 1428, with a 1 px track, dots at both ends and the position dot touching the start cap. **[2026-10-05]** ReadEra's 탐색줄 (§5.4 note): a 1 px line on row 1423 between 9 px end dots at x 14–22 and 697–705 (rows 1419–1427), in #D1D1D1 / #B4B4B4; on page 1 the position dot is the left end dot (not drawn twice). The header shows the chapter title unless the page begins that chapter. |
| `13_txt_chrome` | as today; also `rawshot 13_txt_chrome` | Top: back, then 🔖 🔊 🔍 ☰ ⚙ ⋮. The title is one line starting at x = 40 px. The brightness row is visible, **Ⓐ has no grey square**, and ⌄ sits at the right. Bottom: "3 / 167" **centred on x = 360 ± 2 px**, not underlined, bold. Only ⟳ and [pin icon] (outline) on the right. The seek row has ⏮ and ⏭. No strip. |
| `13b_pin` | `tap_label "이 페이지 고정"`; shot; `rawshot 13b_pin` | **[2026-10-05]** The pin is **filled** ("고정 해제") and there is **no history row**: the pinned page is the one on screen, and the row only offers places to go to (never a grey "(pin) 3쪽"). `raw_equal 13_txt_chrome 13b_pin 360 1100` → **EQUAL** (the page did not move or relayout; **[Δ]** rows start at 360 so the top bar's edge, about 324 px with a 24 dp inset, is never included). |
| `13c_pin_close` | `adb shell input tap 360 700`; shot; `rawshot 13c` | The chrome is **closed** and **no page turn** happened: `raw_equal` against a `rawshot 12b` taken right after `12_txt_tap_right` → EQUAL over **[Δ]** the PageView rows (`dumpsys` bounds, or 80..1440 when the status bar shows). No chip (pinned marks never float; pinning is not a jump). |
| `13d_return`, `13d_forward` | volume-down ×5; tap 360 720; shot `13d_strip`; `tap_label "3쪽으로"`; shot `13d_return`; `tap_label "8쪽으로"`; shot `13d_forward` | **[2026-10-05]** `13d_strip`: label "8 / 167"; row "‹ 3쪽으로" · "지우기", no right item. `13d_return`: label "3 / 167"; no left item (nothing before 3), "지우기", "8쪽으로 ›"; the pin an outline. `13d_forward`: label "8 / 167", "‹ 3쪽으로" again, no right item. |
| `13e_brightness_opts` | `tap_label "밝기 옵션"`; shot | The brightness row is **still visible**, the icon is now ⌃, and the panel lists "스와이프로 밝기 조절" (switch, off with a filled knob) and "기기 밝기 직접 조절". No question row (the emulator is not e-ink). |
| `13f_clear` | **[2026-10-05]** after `13i` (on q): `tap_label "지우기"`; shot; then the bars close for `rawshot 10a_pre` and open again for `14_reading_settings` | The row is gone and the pin is an outline |
| `13g_seek_chip` **[Δ]** | after `13e` (menu open, on 8, back [3]): drag the seek bar thumb to 70 % with `input swipe`, release (the page m, read from the label); drag again to 60 %, release; tap 360 700 (closes the menu); shot. Then volume-down ×2, shot `13h_chip_gone` | `13g`: the chip "‹ 8쪽으로 \| ✕" floats bottom-left above the progress line, not "m쪽으로". It offers the **first** origin of both seeks (★3, the chip only) and appears even though both seeks were made with the menu open (★5). `13h`: the chip is gone after 2 manual turns. |
| `13i_row`, `13i_both`, `13i_pin`, `13i_back`, `13i_first`, `13i_ahead` **[2026-10-05]** (the user's two ReadEra shots: at 1 a seek to 1749, from there a seek to 150 with no page turned, ‹, ‹) | on q (the second seek's landing + 2 turns; back [3, 8, m]): open the menu; "m쪽으로"; shot; "이 페이지 고정"; shot; "8쪽으로"; shot; "3쪽으로"; shot; "8쪽으로", "m쪽으로", "q쪽으로"; shot | `13i_row`: "‹ m쪽으로" only (the second seek's origin is a place). `13i_both` (on m): "‹ 8쪽으로" · 지우기 · "q쪽으로 ›" (shot 1). `13i_pin`: the pin filled ("고정 해제") and the row **unchanged** ("8쪽으로", "q쪽으로": the pin never hides the way back). `13i_back` (on 8): "‹ 3쪽으로" · 지우기 · "m쪽으로 ›". `13i_first` (on 3): 지우기 · "8쪽으로 ›" only, the nearest place ahead (shot 2). `13i_ahead`: forward three times, on q with "‹ m쪽으로" only. |
| `13d_left_cols`, `13d_cols`, `13i_both_cols`, `13i_pin_cols`, `13i_first_cols` **[2026-10-05]** | in those steps' dumps (`history_cols n left right`, "" = that side empty) | "지우기" centred at x 358..362 in every state (left only, right only, both); a shown left label ends at x ≤ 240, a shown right one starts at x ≥ 480; an empty side is not in the dump (INVISIBLE) |
| `13u_<tag>_history`, `13u_<tag>_empty` **[2026-10-05]** (`history_row`, 흰 바탕 then 마루뷰어) | 지우기 if shown; pin the page P (`13u_pin`: the pin filled, no row); volume-down ×2 (`13u_one`: only "‹ P쪽으로"); "P쪽으로"; shot; 지우기; shot | On P: no left item, "P+2쪽으로 ›" in its third (`13u_cols`, `13u_no_left`), 지우기 at the same x as with the left label alone (`13u_still`); the row ends on the panel's top, the page label's centre 50 px (25 dp) below it (±1, `13u_on_panel`; **[2026-10-05, later]** was "the label row starts there, 0..2 px"); its background at (8, row centre) is the page colour; (8, row end − 2) is ≥ 12 levels darker (the panel's shadow over the row's foot); after 지우기 no row and the label row at the same y. Ends on P with an empty history. |
| `13t_<tag>_open` (+ rawshots closed / open / closed2) **[2026-10-05]** (`chrome_look`: 흰 바탕 #FFFFFF/#F5F5F5 shadow, 마루뷰어 #323232/#3C3C3C shadow, 흑백 반전 #000000/#1A1A1A edge #333333; the look set on 설정 → 읽기 설정 between them) | bars closed, open, closed again | The page at (8, 700); the top bar's surface right of ←; the bottom panel's at the label row's centre; ~~the row under the brightness bar ≥ 12 levels darker than the page (shadow) or the edge colour (±2 levels)~~ **[2026-10-05, later]** the brightness row's background at (8, its centre) is exactly the page's pixel at (8, 700) (`13t_bright`); the title block above it is the surface (`13t_title`, (8, row top − 2)); the top bar's edge on the row's first row (8, row top): ≥ 12 levels darker than the page (shadow) or the edge colour (±2, `13t_edge`); right under the row (8, row bottom) and 6 px lower exactly the page pixel (`13t_under`: nothing under it); the bottom panel's edge right above it (8, label centre − 51) (`13t_panel_edge`); the seek track's centre 72 ± 1 px under the label's and at least 48 + 32 px above the screen's bottom (`13t_rows`: ReadEra's offsets, the 16 dp minimum in a full-screen window); closed vs closed2 EQUAL below the header band; no RELAYOUT between the marks. Then 흰 바탕 and 흑백 반전 off again, `10a_pre` retaken. |
| `13w_<tag>_dot`, `_line`, `_above`, `_below` **[2026-10-05]** (`progress_look`: 흰 바탕 #D1D1D1 / #B4B4B4, 마루뷰어 #4B4B4B / #5A5A5A, 흑백 반전 #1F1F1F / #323232; `bot` = the PageView's last row from `pv_rows`) | none: reads 13t's `13t_<tag>_closed` raw | The left end dot's centre (18, bot − 17) in the dot colour; the line colour at x 40 or x 680 on row bot − 17 (the position dot covers at most one); the page colour right above (18, bot − 22) and below (18, bot − 12) the end dot. All ±2 levels. The line before (status colour on row bot − 25, end caps at x 24 and 696) fails the first two. |
| `13v_*` **[2026-10-05]** (`motion_check`) | references at animator scale 0; `settings put global animator_duration_scale 1`: show, hide; scale 0: show, hide | At 1: RAPerf "chrome show fade" (`13v_fade`), the bars end on the same pixels as the instant ones (`13v_top`, `13v_bottom`: **[2026-10-05, later]** from the panel's top, the label's centre − 50, less its 8 px edge band), they are gone a second after hiding and the page is EQUAL (`13v_hidden`, `13v_page`). At 0: "chrome show instant" (`13v_instant`). The scale is left at 0. |
| `14_reading_settings` | as today | The popup is **horizontally centred** (side gaps 16 ± 1 px each), its top 16 px below the status inset, rows 88 px tall, 정렬 and 줄바꿈 on **one row**, no scrollbar, light row lines, black group lines |
| `14b_status_slots` | in the popup: `tap_label "더보기" contains`, swipe up inside the popup; shot | "상태 표시": 위 [없음(dashed)][챕터 제목][없음(dashed)] / 아래 all dashed; 진행 막대 switch on |
| `14c_slot_list` | `tap_label "아래 가운데: 없음"`; shot; `tap_label "쪽 번호"` (the list closes); `back` once (closes the popup; a second back would leave the reader) | The list shows 12 items, with live notes on the right ("3 / 167", "1%", "2:39" …) and "없음" checked |
| `10b_footer_slots` | shot after 14c (the page is visible) | The footer centre shows the page label (e.g. "3 / 1xx"; the total may change after the relayout) **centred on the text column**, on the same baseline band above the progress line. The page relaid out once (a footer band now exists). |
| `17_selection` | as today | **One row of 5** (복사 · 인용 · 메모 · 사전·번역 · ⋮) with no hole |
| `50_settings` | as today; plus `tap_label "페이지 넘김" contains`, scroll to "상태 표시줄", shot `51_status_page` | Off switches have filled knobs. Trailing controls end at x = 688 px. No black lines between sections. `51`: six slot rows + 진행 막대. |
| `01_library` | as today | Card padding 20 px, hairline separators instead of boxes, a thin fast-scroll thumb |

The scroll spec's screenshots, if it adds any, must also show the footer, progress line and return strip fixed while
the text scrolls.

---

## 9. Risks and device checks

| # | Risk | Mitigation / check |
|---|---|---|
| R1 | **Neither brightness path moves the Comet's front light** (likely if it is a Bigme build) | The verdict flow ends in NONE: the original value is restored, and the honest "기기 조명 설정에서 조절 ›" replaces the slider. **D1** tells us for sure. |
| R2 | `screen_brightness` works but maps oddly (vendor 36-step quantisation, a 0–100 range, a floor that stays lit) | The echo window ignores the vendor echoing back. The 5/220 test in D1 shows the range. Add a learned `levelMax` only if needed (brightness.md R2). |
| R3 | Global side effect of the device path (other apps, crash) | Restore on leave by default. The original is committed before the first write. `restoreIfStale` runs at library start. Install-stamp guard. |
| R4 | Removing pinned chrome surprises someone who used it | It was the bug the user reported. There is no replacement mode. Old prefs and backups are dropped silently (`DROPPED_KEYS`). |
| R5 | Migration hides a footer that a user liked | Only the untouched old default migrates to "none", which is the user's own request. Any customised footer maps to slots. |
| R6 | `LINE_BREAK_WORD_STYLE_PHRASE` is ignored for Korean on the Comet's Android 14 | **D3.** If words still split, turn on `keepAll()` for `label()` text of more than 12 chars that is not a tap target. The helper already exists. |
| R7 | The CJK bold is synthetic (fake bold) on some firmwares and looks smeared at 17 sp | This is today's rendering already. If D2 shows smear, drop the page label to regular with 18 sp (one constant in `ReaderChrome`). |
| R8 | 1 px progress track and 3 px caps under the Comet's per-app waveform (A2/fast snaps greys) | Pure black/white art survives any waveform. The dot is 6 px radius. D2 checks legibility; if needed, a 2 px track is one `ProgressMath` constant. |
| R9 | Big TXT total still jumps between opens while estimating (43828 → 32719) | Out of scope (READER_B P2: seed the prior from persisted counts of the same font family). The strip, label and footer all use the same `globalPageOf`, so they never disagree with each other. |
| R10 | The DB v3 bump races another spec's bump in the same run | One shared `DB_VERSION = 3`; `ADDED_COLUMNS` entries are independent and column-guarded |
| R11 | The shared mutable `StatusDecor` is drawn by an off-screen renderer (future page thumbnails, task #19) | Thumbnails must pass `PageDecor(status = null)` or their own `StatusDecor`. Documented in `PageDecor`'s KDoc. |
| R12 | Library polish collides with task #19's library views | Same LIBRARY owner. If #19 runs in the same pass, apply polish 14 on top of its views. Otherwise #19 inherits it. |
| R13 **[Δ]** | The user reads "띄워주는" as "the pinned link stays on the page with the menu closed", not "shown in the menu" | D2 asks the question outright. The fallback is the single constant `ReturnNav.PIN_FLOATS = true` (§3.1): an overlay chip, still no relayout. |
| R14 **[Δ]** | Greys vanish under the Comet's fast per-app waveforms (A2/DU threshold #999 and #AAA to white) | No state depends on grey alone (House rules). Light lines only separate, and losing them is harmless. D2 also photographs the chrome in the device's "fast" mode. |
| R15 **[Δ]** | The device path fights another app or the system over `screen_brightness` (auto-dim, a vendor light service) | The echo window plus `isExternal`: their value wins and the slider adopts it. `stillOurs` stops a stale put-back (§4.3 fix 1). |

**Device checks for the user** (one Comet session; screenshots back):
- **D1, brightness** (brightness.md §9):
  1. Drag the bar fully left, then fully right, and answer the question honestly.
  2. If 아니요: allow "시스템 설정 수정", come back and drag again.
  3. 설정 › 정보 › 조명 진단: screenshot, then tap [변화 감지 30초], move the device's own brightness slider and then
     its 색온도 slider, and take a second screenshot.
  4. With adb, optionally: `settings list system` before/after moving the device slider; `settings put system
     screen_brightness 5` / `220`; `ls -lZ /sys/bus/i2c/devices/*-0036/`; `input keyevent 220/221`.
- **D2, chrome look:** open a book, tap the centre, photograph the bars.
  - The label is centred and crisp.
  - No grey squares.
  - The pin works: tap it, turn 5 pages, tap "‹ N쪽으로", and the page never flips on its own (2026-10-05: on N the
    row reads 지우기 · "N+5쪽으로 ›", no left item).
  - **[Δ] Ask the user:** "고정 버튼을 누른 뒤 메뉴를 닫았을 때, '‹ N 페이지로' 줄이 책 화면 위에 계속 떠 있어야 하나요,
    아니면 메뉴를 열었을 때만 보이면 되나요?" (R13).
  - **[Δ]** Repeat the photo with the Comet's per-app refresh mode set to its fastest mode: the row's labels and
    chevrons still read, and the empty status slots still show "없음".
  - The progress line is visible at the bottom.
- **D3, Korean line breaks:** settings summaries and the brightness option subtitle break only between words.
- **D4, return history persistence:** pin, turn a page, close the book, reopen, open the menu. The row still shows
  "‹ N쪽으로" (2026-10-05: both lists are kept).
- **D5 [Δ], brightness put-back:** with the device path on, set the light in the reader, then
  `adb shell am force-stop com.ggumtak.readeraplus` (a stand-in for a crash). Change the light in the system panel,
  then open the library. The panel's value stays: `restoreIfStale` does not put the old original back over it.

---

## 10. [Δ] Changelog: adversarial review (2026-09-30)

Each entry names the problem found and where it is fixed.

| # | Problem | Fix (section) |
|---|---|---|
| C1 | Written before the scroll SPEC existed. It cited design A/C APIs (`drawFrame`, `drawStrip`, `stripConfig`, `ContentOriginHost`, a "paged-equivalent" page) and R2 owner names that the final SPEC (design B) renamed. | Re-checked against `scroll/SPEC.md`: §1.11 merge list (void "unpinned chrome" and "pinned chrome" lines, widened paged-shot gate, "넘기는 방식" stays under 더보기), §5.6 rewritten, §7.1 owner mapping, §7.4 assumption table |
| C2 | Any `refreshDecor` (counts complete, highlights, bookmarks, episodes) re-read the clock, so a background event could redraw the page with only a new minute: an unsolicited e-ink update. Today's `clock()` has the same bug. | House rules; §5.3 `buildDecor(sample)`: the clock and battery are sampled only when a page is shown, at settle and on resume; §5.7 |
| C3 | Whether the progress lane existed depended on the data (`progress ≥ 0`), so an unknown position would move the footer text by 12 dp. | §5.2 `StatusDecor.lane` from the settings; §5.4 draws track and caps without a dot |
| C4 | Scroll SMOOTH draws the chrome at 60–120 fps, which meant 6 `measureText` calls per frame. | §5.4 slot widths and x positions cached per `(version, cw, inset)` |
| C5 | Seeking with the menu open left no chip after the menu closed (today's app shows one). Opening the menu killed the chip for good. A second seek overwrote the first origin, and visiting the pin lost the reading position. | §3.1, §3.2 ★3 `landed`/`chainOffer`, ★4 keep `other`, ★5 derived `offer`; §3.4 API; tests §8.1; CI `13g`/`13h` |
| C6 | State shown by grey alone (the mark-page item, empty slots, "없음") disappears under fast e-ink waveforms. This also contradicted §2.1's "icon swap only". | House rules; §2.1; §3.4 pin glyph + "N 페이지"; §5.5 `Ink.GRAY` dashes; R14 |
| C7 | The strip overflowed with 5-digit pages or a large font scale (4 dp to spare at 1.0×). | §3.4 fit rule with the short form "‹ N" / "N ›"; `ChromeMathTest` |
| C8 | `WRAP_CONTENT` with autosize (unsupported by Android), and sizes and visibility set from layout listeners, meant a second layout, i.e. a second e-ink update on the first chrome show. | §2.2 width guard and §2.4 fixed label width, both computed in `setVisible(true)` |
| C9 | Open path: about ten return-strip/chip views, the options rows and a prefs cleanup ran before the first page. | §2.2 lazy options rows; §3.4 lazy dock and chip; §1.3/§2.3 cleanup moved to `Settings.saveApp` |
| C10 | `LightController.attach` was called from inside `ReaderChrome`'s constructor, leaking `this` before the fields existed. | §2.5, §3.5, §4.2: ReaderActivity calls `light.attach(chrome)` |
| C11 | Brightness side effects: a stale original put back over a value the user set later (after a crash or with the observer off); auto-brightness left off for good with "keep"; `pending` recorded without permission; the switch travelled in backups to devices without the grant; the e-ink word list matched the Pixel 9 Pro Fold ("comet") and Hisense LCD phones. | §4.3 fixes 1–4 (`stillOurs`, mode always restored, `canWrite` before `pending`, `DeviceClass`); §1.1/§1.4 `a.brightnessDevice` device-local via `DROPPED_KEYS`; tests; D5; R15 |
| C12 | `applyAppSettings` re-enabled a swipe that verdict NONE had turned off, on every resume. | §2.3: `LightController` is the single writer of `page.brightnessSwipe` |
| C13 | The async return-mark load had no book or session guard (a switched book could get the previous book's pin). | §3.3 guard; placement reuses `TextPositions.remapFraction` |
| C14 | Accessibility: the page label's description hid the page number, option rows read as unlabelled switches, and the seek bars had no labels. | §2.4, §2.2 |
| C15 | Glyphs missing from some firmware fonts ("▭", "☼"). | §1.1 examples, §4.4 drawables |
| C16 | CI: `raw_equal` assumed the header size, and its row ranges included the top bar and the status-bar clock. | §8.2 header parsing, PageView-only rows, `13b` rows 360..1100 |
| C17 | The one open reading of the user's "띄워주는" (a pinned link floating on the page) was not tracked. | §3.1 `PIN_FLOATS` fallback, R13, a D2 question |
