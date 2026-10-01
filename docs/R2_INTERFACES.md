# R2 contract — shared interfaces, owners and stubs

Contract revision R2 (product spec section D plus the lead's additions) for release 2. It landed as one change
before the owners start, so the nine owners can build in parallel and compile against one another.

- **Public signatures in this document are fixed.** Implement them; don't change them. Add private helpers, internal
  members and new files freely inside your own paths.
- **Frozen files** (only the contract author edits them; if you need a change, put it under "CONTRACT REQUESTS" in
  your final answer with the exact code): `settings/*`, `ui/kit/Ui.kt`, `ui/kit/Toggle.kt`, `ui/kit/Errors.kt`,
  `ui/kit/InkInput.kt`, `reader/ReaderHost.kt`, `AndroidManifest.xml`, `res/**`, `data/LibrarySchema.kt`,
  `data/SettingsJson.kt`, `data/Models.kt`, `render/FontCatalog.kt`, `docs/**`. Also frozen: the signatures of the
  interfaces and `ReaderPanels` entry points in `reader/extras/ReaderPanels.kt` (the file itself is EXTRAS_TOOLS').
- **Stubs.** `grep -rn 'R2 stub\|TODO("owner' app/src/main/java` lists every body an owner still has to write.
  - `TODO("owner: X")` throws: nobody else calls it until X implements it.
  - `// R2 stub (owner: X)` is a safe default ("nothing stored", "unknown", no-op) because a caller may run first.
    Replace it; don't leave it.
- **Checks.** `tools/typecheck.sh` (whole tree; other owners' half-done work may break it for a while — your files
  must be clean) and `tools/unittest.sh <fq.TestClass …>` (full suite: `tools/unittest.sh`). `--own` mode compiles
  against the contract snapshot in `/opt/tc/contracts`, refreshed with R2.
- Paths are relative to `app/src/main/java/com/ggumtak/readeraplus/`. Tests go under `app/src/test/java/…/<same path>`.
- The rules of spec section 0 (open path, per-turn cost, no idle redraws, flashes opt-in, one TXT index bump) are in
  `docs/ARCHITECTURE.md` → "Contract revision R2". Every item below respects them; so must every implementation.

---

## 1. Owner map

| Owner | Files (main + their tests) |
|---|---|
| READER_A | `reader/*.kt` EXCEPT `BookSession.kt`, `PageCounts.kt`, `LayoutKeys.kt` (and frozen `ReaderHost.kt`): `ReaderActivity`, `ReaderChrome`, `ReaderFormat`, `KeyMap`, `ReaderMath`, `TapZones`, `ReaderMenus`, `ReaderWindow`, `PageView`, `IntentFiles`, `UriPaths`, `ChapterIndex`, `TxtOverrides` (implemented by R2; keep it the only merge point), new `reader/EndPanel.kt`, new `reader/ReadingTracker.kt`; tests in `test/.../reader/` for those |
| READER_B | `reader/BookSession.kt`, `reader/PageCounts.kt`, `reader/LayoutKeys.kt`; their tests, incl. new `test/.../engine/LayoutGoldenTest.kt` |
| EXTRAS_NAV | `reader/extras/ContentsDialog.kt`, `InfoDialogs.kt`, `SearchPanel.kt`, `Episodes.kt`; `ui/kit/InkPager.kt`, `ui/kit/InkNumPad.kt` (new); their tests |
| EXTRAS_TOOLS | every other `reader/extras/*.kt`: `ReadingSettingsPopup`, `CompactUi`, `ExtrasUi`, `ExtrasFormat`, `ExtrasText`, `FontChooser`, `ReaderPanels` (not the frozen signatures), `SelectionController`, `TtsController`, `TtsService` (new), `RulesDialog` (new), `RuleList` (new); their tests |
| RENDER | `render/*` EXCEPT `FontCatalog.kt` (frozen), incl. new `render/ImageCoverage.kt`; their tests |
| DATA | `data/*` EXCEPT `LibrarySchema.kt`, `SettingsJson.kt`, `Models.kt` (frozen): incl. `ReadingLog.kt`, `BookPrefs.kt`, `NextPart.kt`, `LanUpload.kt` (new), `Library*.kt`, `Backup*.kt`, `BookRows.kt`, `FileScanner.kt`; their tests |
| LIBRARY | `ui/library/*`; its tests |
| SETTINGS | `ui/settings/*` incl. new `WifiTransferPage.kt`, `TxtDefaultsPage.kt`, `StatsPage.kt`, `HeatmapView.kt`; their tests |
| FORMAT | `format/**` incl. new `format/epub/EpubPlanCache.kt`; their tests |

Tests that R2 added (owned by the contract; owners may extend, not weaken): `test/.../settings/SettingsStoreTest.kt`,
`settings/UserStylesTest.kt`, `data/SettingsJsonR2Test.kt`, `data/LibrarySchemaV2Test.kt`, `data/ReadingLogDaysTest.kt`,
`reader/TxtOverridesTest.kt`, `reader/DurationFormatTest.kt`, the R2 cases in `ui/kit/KitResourcesTest.kt` and
`reader/LayoutKeysTest.kt`.

---

## 2. What changed in the frozen files

### `settings/ReaderSettings.kt`
| Change | Type / default | Meaning |
|---|---|---|
| `ReaderSettings.footerEpisode` | `Boolean = false` | footer "123/540화" (or "87/612") after the page label |
| `ReaderSettings.footerTimeLeft` | `Int = TIME_LEFT_OFF` | `TIME_LEFT_OFF` 0 / `TIME_LEFT_EPISODE` 1 ("이 화 3분") / `TIME_LEFT_BOOK` 2 ("책 7시간 20분") |
| `StylePreset` labels | 웹소설 / 전자책 / 종이책 | enum names `MARU` / `RIDI` / `BOOK` and the looks unchanged |
| `TapAction.GOTO("페이지 이동")`, `TapAction.AUTO_TURN("자동 넘김")` | appended | tap zones and keys; `NONE` as a key binding = "없음(시스템에 맡김)" |
| `enum class KeyHold(label)` | `REPEAT` 계속 넘기기, `CHAPTER` 다음·이전 화로, `TEN` 10쪽씩, `SINGLE` 한 쪽만 | |
| `AppSettings.keyBindings` | `Map<Int, TapAction> = emptyMap()` | key code → action; first in resolution order; overrides `volumeKeysTurn` |
| `AppSettings.keyHold` | `KeyHold.REPEAT` | |
| `AppSettings.longPressMs` | `Int = 500` | 400 / 500 / 700 / 1000 ("길게 누르기 시간"); replaces `PageView.LONG_PRESS_MS` |
| `AppSettings.autoMarkFinished` | `Boolean = true` | "끝까지 읽으면 완독 처리" |
| `AppSettings.einkRefreshMethod` | `Int = EINK_REFRESH_AUTO` | `EINK_REFRESH_AUTO` 0 / `_GC16` 1 / `_CLEAN` 2 / `_FLASH` 3 (top-level consts) |
| `AppSettings.einkFlashMs` | `Int = 100` | 100 / 200 / 350 |
| `AppSettings.einkRefreshEveryNight` | `Int = -1` | cadence while inverted; -1 = same as `einkRefreshEvery` |
| `AppSettings.einkFlashImages` | `Boolean = false` | refresh on picture pages — OFF by default (rule 5) |
| `AppSettings.ttsSleepChapters` | `Int = 0` | 1 "이 화 끝까지", 2 "2화 끝까지"; wins over `ttsSleepMinutes` when > 0 |
| `AppSettings.ttsHighlight` | `Boolean = true` | "읽는 문장 표시" |
| `AppSettings.ttsVoice` | `String = ""` | TTS voice name (A13; shared by TtsPage and TtsController). "" = engine default |
| `LibraryListMode.COMPACT("간단히")` | between LIST and GRID | entry order = the toolbar toggle's cycle |
| `einkRefreshOnPanelClose` | — | never existed in code; not added (spec: removed) |

### `settings/Settings.kt`
- Persists every new field (keys in §6). Missing keys → defaults; unknown enum names → defaults.
- `keyBindings` stored as `"24:NEXT,25:PREV"`: `Settings.encodeKeyBindings(map)` / `Settings.decodeKeyBindings(text)`
  (tolerant: bad codes, unknown action names skipped).
- `ttsVoice` falls back to the legacy raw pref `extras.ttsVoice` when `a.ttsVoice` is absent.
- `val userStyles: List<UserStyle>` — parsed from `a.userStyles` (JSON) on first access, cached; never in `loadApp()`.
  `fun saveUserStyles(list)` keeps ≤ 5, removes the key when empty, does not notify listeners. `KEY_USER_STYLES`.
- `internal fun initForTest(prefs)` for JVM tests.

### `settings/UserStyles.kt` (new)
`data class UserStyle(name, fontId, fontSizeSp, fontWeight, lineHeightPct, paragraphSpacingPct, indentPct,
letterSpacingPm, align, lineBreak, marginLeftDp, marginRightDp, marginTopDp, marginBottomDp, pageMargins)` with
`applyTo(s)`, `matches(s)`, `UserStyle.from(name, s)`. `object UserStyles { MAX = 5; MAX_NAME = 12; defaultName(list)
("내 스타일 N"); cleanName(name); toJson(list/one); parse(text); fromJson(array/object) }` — tolerant and clamped.

### `data/LibrarySchema.kt` → v2
`DB_VERSION = 2`; `CREATE_READING_LOG`, `CREATE_BOOK_PREFS`, index `reading_log_book`; `quotes.style` in
`CREATE_QUOTES` (fresh files) and `ADD_QUOTE_STYLE` + `upgradeStatements(oldVersion, columnsOf)` (upgrades; skipped
when the column exists). `LibraryDb.onUpgrade` runs `CREATE_ALL`, then `upgradeStatements` with `PRAGMA table_info`.
Checked on a real SQLite 3.45: fresh create, v1 file with data → v2 (data kept, style = 0), downgrade-and-back (no
duplicate ALTER), every `LibrarySql` statement still prepares.

### `data/SettingsJson.kt`
Maps every new field (clamped). Saved styles travel as a typed array `"userStyles"` in the settings envelope:
`settingsToJson(reader, app, raw, userStyles = null)`, `userStylesFromJson(settingsObject): List<UserStyle>?` (null =
the backup has none: keep the device's). The raw `a.userStyles` pref is never copied or restored as an unmapped pref.
Old backups restore unchanged (missing keys keep the device's values).

### `data/Models.kt`
Shelf labels (A8; single source — replace hard-coded copies with `Shelf.X.label`): `READING_NOW` "읽고 있는 책",
`ALL` "모든 책", `TO_READ` "읽을 책", `HAVE_READ` "다 읽은 책" (others unchanged). `Quote.style` is deferred to R3.

### `ui/kit/Ui.kt`
`stepperRow`: a tap that leaves the value unchanged does nothing (A10). `sliderRow`: plain black-dot thumb, no
background, no split track (A4.3). `prompt()`: `inkCursor(singleLine = false)` (A4.1).

### `render/FontCatalog.kt`
`DEFAULT_ID = "nanummyeongjo"` (= `ReaderSettings().fontId`). Side effects: it is also FontManager's missing-font
fallback and the face of generated covers. Covers are cached on disk (`cacheDir/thumbs/<id>/<mtime>@WxH.png`), so
existing covers keep RIDIBatang until regenerated (file change, 캐시 비우기) — RENDER decides whether to add a style tag
to `CoverKeys.version` (one background regeneration of every cover) or leave it.

### `AndroidManifest.xml`, `res/`
Permissions `INTERNET`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK` (`WAKE_LOCK` existed). Service
`.reader.extras.TtsService` (`exported="false"`, `foregroundServiceType="mediaPlayback"`). No `POST_NOTIFICATIONS`.
`res/values/themes.xml`: the app theme's items moved to `Base.AppTheme`; `AppTheme` extends it.
`res/values-v31/themes.xml`: `AppTheme` + `windowSplashScreenAnimatedIcon=@android:color/transparent`,
`windowSplashScreenBackground` and `windowSplashScreenIconBackgroundColor` = white. `ReaderTheme` (and the dialog
themes) inherit it.

---

## 3. Shared APIs (signature · owner · users · semantics · threading)

### DATA

**`data/BookPrefs.kt`** — owner DATA; users READER_A, EXTRAS_TOOLS (via `TxtOverrideHost`), SETTINGS.
```kotlin
data class TxtOverride(
    val blankLines: Int? = null,          // ReaderSettings.txtBlankLines (ParseOptions.BLANK_*)
    val stripIndent: Boolean? = null,     // txtStripIndent
    val joinWrapped: Int? = null,         // txtJoinWrappedLines (0 off, 1 auto, 2 always)
    val detectChapters: Boolean? = null,  // txtDetectChapters
    val chapterRegex: String? = null,     // txtChapterRegex ("" = none; null = global)
    val emphasizeHeadings: Boolean? = null, // txtEmphasizeHeadings
    val replaceRules: String? = null,     // txtReplaceRules (whole rule text)
) { val isEmpty: Boolean; fun toJson(): String; companion object { fun fromJson(text: String?): TxtOverride? } }
data class FinishedBook(val bookId: Long, val finishedAt: Long)
object BookPrefs {
    fun txtOverride(bookId: Long): TxtOverride?            // R2 stub → null
    fun setTxtOverride(bookId: Long, o: TxtOverride?)      // null/empty clears; R2 stub → no-op
    fun finishedAt(bookId: Long): Long                     // 0 = not finished; R2 stub → 0
    fun setFinishedAt(bookId: Long, t: Long)               // 0 clears; R2 stub → no-op
    fun finishedBetween(fromMs: Long, toMs: Long): List<FinishedBook> // newest first, trashed excluded; R2 stub → []
    fun setEpisodeLabel(bookId: Long, label: String?)      // T2-13; R2 stub → no-op
}
```
`TxtOverride` and its JSON codec are implemented and tested (`TxtOverridesTest`). All `BookPrefs` functions: blocking,
IO thread, thread-safe, never throw for a missing row. `txtOverride` is on the open path: one primary-key read.

**`data/ReadingLog.kt`** — owner DATA; users READER_A (add on pause, `cpm` in afterOpen), EXTRAS_TOOLS (TTS time with
the reader in the background), SETTINGS (StatsPage).
```kotlin
data class LogTotals(val seconds: Long, val pages: Int, val chars: Long, val days: Int) // days with reading
data class DayTotal(val day: Int, val seconds: Long, val pages: Int, val chars: Long)
data class BookTotal(val bookId: Long, val seconds: Long, val pages: Int, val chars: Long)
object ReadingLog {
    const val DEFAULT_CPM = 600; BOOK_DAYS = 14; BOOK_MIN_SECONDS = 600; ALL_DAYS = 30; ALL_MIN_SECONDS = 1800
    fun day(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): Int   // local yyyymmdd — implemented
    fun date(day: Int): LocalDate; fun addDays(day: Int, n: Int): Int       // implemented
    fun add(bookId: Long, day: Int, seconds: Long, pages: Int, chars: Long)  // upsert; R2 stub → no-op
    fun summary(fromDay: Int, toDay: Int): LogTotals                          // inclusive; R2 stub → EMPTY
    fun days(fromDay: Int, toDay: Int): List<DayTotal>                        // ascending; R2 stub → []
    fun perBook(fromDay: Int, toDay: Int): List<BookTotal>                    // seconds desc; R2 stub → []
    fun cpm(bookId: Long?, today: Int = day(now)): Int?                       // tiers below; R2 stub → null
    fun speed(seconds: Long, chars: Long, minSeconds: Long): Int?             // pure rule — implemented
}
```
Upsert: `UPDATE reading_log SET seconds = seconds + ?, pages = pages + ?, chars = chars + ? WHERE day = ? AND book_id = ?`
via `executeUpdateDelete()`; when it returns 0, `INSERT`; one transaction. `cpm`: this book's last 14 days if ≥ 10 min,
else all books' last 30 days if ≥ 30 min, else null (caller uses `DEFAULT_CPM`); `bookId == null` starts at the second
tier. DB functions: blocking, IO, thread-safe.

**`data/NextPart.kt`** — owner DATA; user READER_A (end panel, on IO, only when the panel shows).
```kotlin
object NextPart {
    fun find(book: Book): File?                               // IO; R2 stub → null
    fun seriesKey(fileName: String): String                   // pure — TODO(owner: DATA)
    fun sameSeries(a: String, b: String): Boolean             // pure — TODO
    fun pickNext(current: String, names: List<String>): String? // pure folder step — TODO
}
```
EPUB with `series`: the next `seriesIndex` in the DB first; else the parent folder (same format family, `NaturalOrder`,
first after the current file with the same series key). Rules and test cases in the KDoc (spec T1-2).

**`data/LanUpload.kt`** — owner DATA; user SETTINGS (`WifiTransferPage`).
```kotlin
class LanUpload(context: Context, destDir: File, listener: Listener) {
    interface Listener { fun onReceived(file: File, book: Book?); fun onError(message: String) } // main thread
    data class Result(val url: String, val code: String)   // "http://192.168.0.23:8080/k7m3", "k7m3"
    var lastError: String?; val isRunning: Boolean
    fun start(): Result?   // main thread; null = no Wi-Fi address / no free port (see lastError) — TODO
    fun stop()             // idempotent — TODO
    companion object { const val MAX_FILE_BYTES = 200 MB; val PORTS = 8080..8089; fun destinationDir(context): File }
}
internal object LanCode  { const val ALPHABET = "23456789abcdefghjkmnpqrstuvwxyz"; const val LENGTH = 4; fun generate(random: (Int) -> Int): String }
internal object LanNames { fun sanitize(raw: String): String?; fun unique(name: String, exists: (String) -> Boolean): String; fun fromContentDisposition(header: String): String? }
internal object LanHttp  { fun requestLine(line: String): Pair<String, String>?; fun boundary(contentType: String): String? }
```
Bound to the `wlan*` site-local IPv4 address (never 0.0.0.0); one accept + one handler thread; 30 s timeouts; 10 wrong
paths → ignore requests for 30 s; streams to disk; `Library.addOrUpdateFile` per finished file. Nothing runs while the
page is closed.

**`data/Library.kt` additions** — owner DATA; user LIBRARY (multi-select, T1-13). Implemented by R2 (one transaction
each; blocking, IO):
```kotlin
fun setHaveRead(ids: Collection<Long>, value: Boolean)
fun setToRead(ids: Collection<Long>, value: Boolean)
fun addToCollection(ids: Collection<Long>, collectionId: Long)
fun trash(ids: Collection<Long>)
```
Page counts (A2, users READER_B): `pageCounts(bookId, key)` / `savePageCounts(bookId, key, counts)` keep their
signatures; `counts` may now be PARTIAL (-1 = section not counted). DATA makes `PageCountCodec.decode` accept -1 (reject
anything below -1). Until then a partial blob reads as null (recount), never as wrong counts.

### READER_A

**`reader/TxtOverrides.kt`** — implemented by R2 (tested): `fun ReaderSettings.withTxt(o: TxtOverride?): ReaderSettings`.
The ONLY place global settings and a book's override merge. Returns the same instance for null/empty/no-op overrides.
Pure, any thread.

**`reader/ReaderFormat.kt`** — implemented by R2 (tested), users READER_A, EXTRAS_NAV, SETTINGS:
`fun duration(minutes: Int): String` ("1분 미만" / "n분" / "h시간 m분"; "h시간" when m = 0 or h ≥ 10) and
`fun durationOfSeconds(seconds: Long): String`. Use it for every duration the user sees.

**ReaderActivity implements** (READER_A) the optional host capabilities declared in `reader/extras/ReaderPanels.kt`
(§3 EXTRAS_TOOLS): `BookInsightsHost`, `TxtOverrideHost`, `ReaderEndHost` (plus the existing `PageJumpHost`). Callers use
`host as? X`, so nothing breaks before they exist; they must exist at the end.

**`ReaderHost.applySettings(settings)` (frozen, semantics refined):** `settings` is always the GLOBAL
`ReaderSettings` (what `Settings.reader` holds). ReaderActivity saves it and applies `settings.withTxt(bookOverride)`
to the session. Never pass the session's effective settings back into it.

### READER_B

**`BookSession.episodes(onReady: (Episodes?) -> Unit)`** — users READER_A (footer 회차, chrome, `BookInsightsHost`).
Lazy: parsed once per session with `Episodes.of(document.toc.map { it.title })` on Dispatchers.Default by whichever
asks first; `onReady` on the main thread (at once when parsed); null when no TOC / failure / closed. Never on the open
path. R2 stub → `onReady(null)`.

**`PageCounts.charsAfter(section: Int): Long`** — user READER_A (book time left: `charLength(sec) - offset +
charsAfter(sec)`). Must be O(1) per call (suffix sums rebuilt only when a section's char length changes). R2 stub is
correct but O(sections).

READER_B-internal but documented rules: `LayoutKeys.ALGO_VERSION` replaces `BuildConfig.VERSION_CODE` in the key,
`LayoutKeys.GOLDEN_HASH` + `LayoutGoldenTest` (see ARCHITECTURE.md); `PageCounts.setKnown(arr): Boolean`; partial
saves every 25 sections and on close; counting order; `pagesPerChar` ignores known sections < 2,000 chars when a larger
one is known. R2 already made `LayoutKeys.layoutPart` normalise `footerEpisode` / `footerTimeLeft` (repaint, not
relayout; not in the page-count key).

READER_B also provides (T1-7; main thread; O(1) per turn): `PageCounts.charsFrom(section, offset)`,
`charsBetween(fromSection, fromOffset, toSection, toOffset)`, `totalChars()`; `BookSession.charsLeftInBook(section,
offset)` and `charsLeftInChapter(section, offset)` (to the next TOC entry, else the end of the book; the chapter lookup
is cached while the position stays inside it).

### EXTRAS_NAV

**`reader/extras/Episodes.kt`** — users READER_B (builds), READER_A (footer/chrome), EXTRAS_NAV (TOC, go-to):
```kotlin
object EpisodeNumbers { fun parse(title: String): Int?; fun isSpecial(title: String): Boolean }
class Episodes private constructor(val numbers: IntArray, …) {
    val size: Int; val parsedCount: Int; val maxNumber: Int
    val confident: Boolean      // ≥ 70% parse and max - min ≤ 5 × count
    val usableForJump: Boolean  // ≥ 50% parse and ≥ 2 entries (go-to [화])
    fun find(n: Int): Int       // TOC index of n, else of the smallest number above n, else -1
    fun gaps(): List<Int>; fun dupes(): Map<Int, Int>
    companion object { fun of(titles: List<String>): Episodes }
}
```
Index = `document.toc` index (ChapterIndex skips entries outside the book: READER_A maps). Rules in the KDoc. All TODO.

**`ReaderPanels.editBookInfo(activity: Activity, book: Book, onSaved: (() -> Unit)? = null)`** — the one "책 정보 편집"
(A8), delegating to `InfoDialogs.editMeta` (now internal, with `onSaved` on the main thread after a successful save).
User LIBRARY (replaces its own "제목/작가 편집" dialog). Main thread.

`ui/kit/InkNumPad.kt`, `ui/kit/InkPager.kt`: EXTRAS_NAV's own; other owners may reuse them later (describe their public
API in your final report).

### EXTRAS_TOOLS

**Host capabilities in `reader/extras/ReaderPanels.kt`** (signatures frozen; implemented by ReaderActivity):
```kotlin
interface BookInsightsHost {                      // users: EXTRAS_NAV (TOC header, go-to [화], 책 정보)
    fun episodes(onReady: (Episodes?) -> Unit)    // BookSession.episodes
    fun minutesLeft(bookScope: Boolean): Int?     // episode (false) / book (true); null = unknown
    fun charsPerMinute(): Int                     // ReadingLog.cpm(book) ?: DEFAULT_CPM, loaded in afterOpen
}
interface TxtOverrideHost {                       // users: popup, SelectionController
    val txtOverride: TxtOverride?
    fun applyTxtOverride(o: TxtOverride?, onApplied: (() -> Unit)? = null) // save on IO; re-parse only if effective parse options changed; onApplied on main after the result shows
    fun saveTxtAsDefaults()                       // effective TXT options → global defaults, override cleared, no re-parse
}
interface ReaderEndHost { fun showBookEnd() }     // user: TtsController at the end of the book
```
All main thread. Effective settings shown by the popup's TXT rows: `Settings.reader.withTxt(host.txtOverride)`.

**`reader/extras/RulesDialog.kt`** — users EXTRAS_TOOLS (popup, per book) and SETTINGS (`TxtDefaultsPage`, global):
`object RulesDialog { fun show(activity: Activity, title: String, rulesText: String, onSave: (String) -> Unit) }` —
full-screen manager; `onSave` once on leaving with changes. TODO.

**`reader/extras/RuleList.kt`** — `data class RuleItem(name, pattern, replacement, enabled)`;
`object RuleList { fun parse(text): List<RuleItem>; fun serialize(rules): String; fun enabledCount(text): Int }`
(`enabledCount` implemented: the parser's own notion of an active rule); `object RuleLiteral { fun build(phrase: String,
wholeLine: Boolean): String? }` (split at "=>", `Pattern.quote` parts, join with `=[>]`; null for blank / multi-line).
Format unchanged: `## 이름` above a rule, `#- ` for a disabled rule (plain comments to `ReplaceRules.parse`).

**`TtsController.onReaderPaused()` / `onReaderResumed()`** — caller READER_A (onPause / onResume). While paused and
speaking, TTS writes its speaking seconds to `ReadingLog.add` + `Library.addReadingTime` on IO (the tracker counts
nothing then). R2 stubs are no-ops.

**`reader/extras/TtsService.kt`** — manifest-declared foreground service (mediaPlayback), started only by TtsController.
R2 skeleton stops itself if started.

Existing internal API now shared (A12-4): `QuoteCache.put(bookId, quotes)` / `get(bookId)` (ExtrasUi.kt) — READER_A's
`reloadAnnotations` fills it with the rows it just loaded (one quote query per open instead of two).

### RENDER

**`render/Eink.kt`** additions — users READER_A (cadence), SETTINGS (PageTurningPage e-ink group and [테스트]):
```kotlin
data class DeviceCleanInfo(val autoClean: Boolean, val everyPages: Int)
object Eink {
    fun configure(method: Int, flashMs: Int)                 // READER_A calls it in applyAppSettings; any thread (implemented: stores)
    fun fullRefresh(view: View, method: Int, flashMs: Int)   // exactly that method, no chain; main thread; R2 stub → fullRefresh(view)
    fun fullRefresh(view: View)                              // existing; RENDER makes it follow configure()
    fun hasXrzRefresh(): Boolean                             // Bigme global refresh callable; probes once (first call on IO) — implemented
    fun deviceCleanInfo(): DeviceCleanInfo?                  // read once by reflection on IO, cached, never writes; R2 stub → null
    fun vendorName(): String?                                // existing (diagnostics row)
}
```
**`render/ImageCoverage.kt`** — user READER_A: `object ImageCoverage { fun of(layout: SectionLayout, pageIndex: Int): Float }`
— image area / content area of the page, O(lines on the page), no allocation. R2 stub → 0f.

### FORMAT

- **`TxtDocuments.isBuildingIndex(path: String): Boolean`** — user READER_A's delayed loading text ("목차를 만드는
  중…" for a TXT over 4 MB). A volatile set by `open` during a full parse; zero cost on the open path. Any thread.
  R2 stub → false.
- **`Documents.writeDeferredCaches()`** — user READER_A (`afterOpen` → `ReaderIo.launch`). Writes the EPUB plan cache
  entries the last opens computed. Blocking IO, never throws. R2 stub → no-op.
- A5 bumps `TxtIndexStore.VERSION` 3 → 4 (the release's only bump). `EpubPlanCache` (+ `VERSION`, golden test) is
  internal to FORMAT.

### SETTINGS

- **`SettingsActivity.PAGE_WIFI = "wifi"`, `PAGE_STATS = "stats"`, `PAGE_TXT_DEFAULTS = "txt_defaults"`** and the
  existing `SettingsActivity.open(context, page)` — users LIBRARY (drawer), SETTINGS (MainPage). Unknown ids open
  the main list, so nothing breaks before SETTINGS registers the pages in `createPage`.

### LIBRARY
Provides no API to other owners.

---

## 4. What each owner must do because of the contract (checklist)

**READER_A**
- Implement `BookInsightsHost`, `TxtOverrideHost`, `ReaderEndHost` on ReaderActivity.
- `startOpen`: inside the existing IO block, after `resolveBook`: `bookOverride = BookPrefs.txtOverride(b.id)`,
  `eff = settings.withTxt(bookOverride)` feeds `Documents.open`, `BookSession` and the `textSignature` remap
  (`reopenDocument` likewise). Nothing else new before `showPage`.
- `applySettings` takes global settings (§3); `toggleInvert` must use `Settings.reader.copy(invert = …)` (today it passes
  `s.settings`, which would save the book's override globally); `onResume` and the settings listener compare
  `Settings.reader.withTxt(bookOverride)` with `s.settings`.
- Delayed loading text: `TxtDocuments.isBuildingIndex(path)` + size > 4 MB → "목차를 만드는 중…".
- `afterOpen`: `ReaderIo.launch { Documents.writeDeferredCaches() }`; load `ReadingLog.cpm(id)` on IO; `reloadAnnotations`
  fills `QuoteCache.put`.
- `applyAppSettings`: `Eink.configure(app.einkRefreshMethod, app.einkFlashMs)`; cadence from invert
  (`einkRefreshEveryNight`, also in `applySettings` / `toggleInvert`); long-press = `app.longPressMs`.
- `onPause`/`onResume`: ReadingTracker delta → `ReadingLog.add` + `Library.addReadingTime` on IO (replaces
  `flushReadingTime`); `tts?.onReaderPaused()` / `onReaderResumed()`.
- `EinkCadence.onPanelClosed()`: count it from `onWindowFocusChanged(true)` while a book is shown (TOC, search and the
  settings popup are focusable windows) and from the chrome closing — no EXTRAS hook is needed. Only matters with a
  cadence on.
- `runTapAction`: R2 wired `GOTO` → `ReaderPanels.showGoTo(this)`, `AUTO_TURN` → `toggleAutoTurn()`; keys:
  `keyBindings` → legacy key sets → built-in; `NONE` passes the key to the system; `keyHold` anchored at key-down.
- End panel: auto-mark = `Library.setHaveRead(id, true)` + progress 1.0 + `BookPrefs.setFinishedAt(id, now)`; the toggle
  undoes it (`setFinishedAt(id, 0)`); `NextPart.find` on IO when shown; `ReaderPanels.showReview` for [리뷰 쓰기].
- Splash (A4.5): `if (Build.VERSION.SDK_INT >= 31) splashScreen.setOnExitAnimationListener { it.remove() }` in onCreate.
- A8 strings in `ReaderMenus.kt`: `Shelf.TO_READ.label` / `Shelf.HAVE_READ.label` for the 읽을 문서 / 읽던 문서 items and
  their toasts, "문서 속성" → "책 정보", "…에 추가" → "컬렉션·목록에 추가", "일반 설정" → "설정"; error panel "문서를 열 수
  없습니다" → "책을 열 수 없습니다".

**READER_B** — `episodes`, `charsAfter` O(1), `ALGO_VERSION` + `GOLDEN_HASH` + `LayoutGoldenTest`, partial counts
(`setKnown`, saves every 25 sections and on close via `ReaderIo.launch`, array copied on main), counting order (EPUB:
single-part items only for the samples), the estimator change.

**DATA** — bodies of `BookPrefs`, `ReadingLog`, `NextPart`, `LanUpload` (+ SQL constants in `LibrarySql`, tested like
the existing ones); `deleteBookRows` also deletes `reading_log` and `book_prefs` rows; `resetProgress` ("읽은 기록
초기화") should clear `finished_at` too; `BackupJson`/`Backup` export and restore `reading_log` (keyed by path) and
`book_prefs` (old backups without them still restore); `PageCountCodec.decode` accepts -1. R2 already wired the saved
styles into `Backup.export`/`applySettings`.

**EXTRAS_NAV** — `Episodes`; TOC 2.0 (header, [지금] [화 번호] [검색], gaps line, gray past rows, pager bar) using
`BookInsightsHost`; `InkNumPad`, `InkPager` (TOC, bookmarks, quotes, search results); go-to [페이지] [%] [화];
`InfoDialogs`: "책 정보" / "책 정보 편집" wording, "예상 약 N시간" via `charsPerMinute`; bookmark empty-state text mentions
the corner tap only when `bookmarkByTouch`; ContentsDialog "이 문서에는 목차가 없습니다" → "이 책에는 …".

**EXTRAS_TOOLS** — popup: TXT rows through `TxtOverrideHost` (effective values; "TXT 파일 · 이 책에만 적용"; rows
"모든 TXT 기본값으로 저장" / "이 책 설정 지우기 (기본값 사용)"; the existing reparse debounce), everything else through
`applySettings(global)`; style row [웹소설] [전자책] [종이책] [내 스타일 ▾] with `Settings.userStyles`; footer rows 회차 /
남은 시간; "넘김·화면 설정"; A9 width. `RulesDialog`, `RuleList`, `RuleLiteral`; SelectionController "이 문구 지우기"
(append to `txtOverride?.replaceRules ?: Settings.reader.txtReplaceRules`, `applyTxtOverride(o) { indexOf check on
the current section }`) and `Settings.app.longPressMs`. TtsController: `TtsService`, sleep by chapters on
`elapsedRealtime`, `ttsHighlight`, `Settings.app.ttsVoice` instead of the raw `extras.ttsVoice` pref (Settings reads the
old key as a fallback), book end → `(host as? ReaderEndHost)?.showBookEnd()`, `onReaderPaused/Resumed`.
FontChooser "글꼴" wording.

**RENDER** — `Eink` bodies (`configure`d method in `fullRefresh(view)`, `fullRefresh(view, method, flashMs)`,
`deviceCleanInfo`), `ImageCoverage.of`, night-mode image inversion (cached `ColorMatrixColorFilter`), the covers decision
above.

**FORMAT** — A5 (+ `TxtIndexStore.VERSION` 3 → 4), `isBuildingIndex`, `EpubPlanCache` + `writeDeferredCaches` + golden
test (A12-1).

**SETTINGS** — register `WifiTransferPage` (PAGE_WIFI), `StatsPage` + `HeatmapView` (PAGE_STATS),
`TxtDefaultsPage` (PAGE_TXT_DEFAULTS, uses `RulesDialog.show`) in `createPage`; MainPage row "TXT 기본 정리 설정";
PageTurningPage: e-ink "고급" group (method, flash length, night cadence, picture pages, [테스트] via
`Eink.fullRefresh(view, method, flashMs)`, diagnostics `vendorName` / `hasXrzRefresh` / `deviceCleanInfo` read on IO),
key bindings chooser (NONE = "없음(시스템에 맡김)", assigned keys listed with [삭제]), `keyHold`, "길게 누르기 시간",
footer 회차 / 남은 시간, `autoMarkFinished`; TtsPage: 목소리 (`ttsVoice`), sleep chapters, 읽는 문장 표시; A8 strings
(FontsPage "기본값" on `ReaderSettings().fontId`; MainPage "앱 시작 시"; the popup's labels).

**LIBRARY** — `Shelf.X.label` instead of the copies in `LibraryViews.kt` (flag buttons), `LibraryDialogs.kt` (menu flag
items), `LibraryText.kt` (empty-shelf texts); other "문서" → "책" wording; drawer rows "읽기 기록" (`ic_schedule`,
PAGE_STATS) and "Wi-Fi로 책 받기" (`ic_download`, PAGE_WIFI; reload on return); "정보" → `PAGE_ABOUT` (delete
`showAbout`); `COMPACT` mode; multi-select with the batch ops; `ReaderPanels.editBookInfo` replaces the own editor;
"새 책" on never-opened books; splash listener in onCreate (A4.5).

---

## 5. Edits R2 made in owners' files (compile / contract fallout only)

| File (owner) | Change |
|---|---|
| `reader/ReaderActivity.kt` (READER_A) | `runTapAction`: branches for `GOTO`, `AUTO_TURN` (exhaustive `when`) |
| `ui/settings/TapZoneModel.kt` (SETTINGS) | `shortLabel`: "페이지\n이동", "자동\n넘김" |
| `ui/settings/FontsPage.kt` (SETTINGS) | missing-font note says 나눔명조 (follows `DEFAULT_ID`) |
| `ui/settings/SettingsActivity.kt` (SETTINGS) | `PAGE_WIFI`, `PAGE_STATS`, `PAGE_TXT_DEFAULTS`; KDoc of `open` |
| `reader/LayoutKeys.kt` (READER_B) | `layoutPart` normalises `footerEpisode`, `footerTimeLeft` (+ a case in `LayoutKeysTest`) |
| `reader/BookSession.kt` (READER_B) | `episodes(onReady)` stub |
| `reader/PageCounts.kt` (READER_B) | `charsAfter(section)` (correct, O(n) stub) |
| `reader/ReaderFormat.kt` (READER_A) | `duration`, `durationOfSeconds` (implemented) |
| `reader/TxtOverrides.kt` (READER_A, new) | `withTxt` (implemented) |
| `reader/extras/ReaderPanels.kt` (EXTRAS_TOOLS) | `BookInsightsHost`, `TxtOverrideHost`, `ReaderEndHost`, `ReaderPanels.editBookInfo` |
| `reader/extras/InfoDialogs.kt` (EXTRAS_NAV) | `editMeta` internal + `onSaved` |
| `reader/extras/TtsController.kt` (EXTRAS_TOOLS) | `onReaderPaused` / `onReaderResumed` stubs |
| `reader/extras/Episodes.kt`, `RuleList.kt`, `RulesDialog.kt`, `TtsService.kt` (new) | skeletons |
| `render/Eink.kt` (RENDER) | `DeviceCleanInfo`, `configure`, `fullRefresh(view, method, flashMs)`, `hasXrzRefresh`, `deviceCleanInfo` |
| `render/ImageCoverage.kt` (RENDER, new) | skeleton |
| `data/LibraryDb.kt` (DATA) | `onUpgrade` runs `LibrarySchema.upgradeStatements` after `CREATE_ALL` |
| `data/Library.kt` (DATA) | batch ops (implemented); partial-count KDoc |
| `data/Backup.kt` (DATA) | saved styles in export / restore |
| `data/BookPrefs.kt`, `ReadingLog.kt`, `NextPart.kt`, `LanUpload.kt` (DATA, new) | skeletons (`TxtOverride` codec and the day helpers implemented) |
| `format/txt/TxtDocuments.kt`, `format/Documents.kt` (FORMAT) | `isBuildingIndex`, `writeDeferredCaches` stubs |

---

## 6. New prefs keys (`settings` SharedPreferences)

`r.footerEpisode` (bool), `r.footerTimeLeft` (int), `a.keyBindings` (string "24:NEXT,25:PREV"), `a.keyHold` (enum name),
`a.longPressMs` (int), `a.autoMarkFinished` (bool), `a.einkRefreshMethod` (int), `a.einkFlashMs` (int),
`a.einkRefreshEveryNight` (int), `a.einkFlashImages` (bool), `a.ttsSleepChapters` (int), `a.ttsHighlight` (bool),
`a.ttsVoice` (string; legacy fallback `extras.ttsVoice`), `a.userStyles` (JSON array, lazily parsed; backed up as the
typed envelope key `userStyles`). `a.libraryListMode` may now hold `COMPACT`.
