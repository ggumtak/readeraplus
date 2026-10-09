# ReaderaPlus R3 interfaces — Phase 0

`wave2/PLAN.md` §§1.6 and 3 are the merged behaviour specification. This document records the source contracts
and their owners. The R2 document remains historical; legacy footer flags and pinned chrome are removed in R3.
This is an intermediate contract commit: named stubs are replaced in W1, then W2 supplies thumbnails.

## Shared invariants

- Pure Kotlin engine, platform Android View UI, no AndroidX or new library dependency.
- Page commands replace the viewport immediately on every device and in every mode. No fade, slide, curl or
  `startScroll` interpolation for taps, page keys or auto paging. Live finger scrolling is a separate gesture.
- `PageGeometry` is view minus the status bands and the margins (since 2026-10-05; before, minus margins only). Chrome,
  return chip and transient dialogs are overlays; the status bands are not: each has its own place at its screen edge.
- Default margins are 40 dp (`0` in controls). Marked deliberate 18/16 dp values stay unchanged. Since 2026-10-05 the
  side margins' `0` is MaruViewer's 20 dp (`SideMargin.ZERO_DP`; untouched R3 40/40 and R2 18/18 become 20/20 once).
  Since 2026-10-05 (user: "위 여백은 위 아래 애들을 제외하고 본문영역에서만 계산해야지") top/bottom count from the
  status bands (`StatusBands`, whole dp from the settings only: header 25 dp at MaruViewer's 13 sp, progress line 18 dp
  at the defaults), and their `0` is each side's default, 15 / 22 dp, so the default text box is where 40 dp from the
  edge put it (Comet
  80..1360). One stepper moves both by its step (`VerticalMargin.step`). Values saved from the edge (`r.marginBaseV` 40 or none) move once by their own bands; new saves write
  `VerticalMargin.BANDS`. This replaces "the text box never makes room for the status bands" and the '가려짐' fit note.
  The bands hug the screen edges (`StatusFit.headerBaseline` / `footerBaseline`), and in fullscreen a cutout-only top
  inset goes into `LayoutKeys.geometry`'s `extraTop` instead of the page view's margin: left out like a system bar, the
  header's band is reserved below it (paper only), the text box below that as the user's screenshot of the installed
  build has it (S25: 207..2220, as before). Since the MaruViewer status line (2026-10-05) the header itself is drawn at
  the very top inside that band (ink ≈ 15–49 px; `StatusFit.INK_TOP_DP`), spans the page view less its own side insets
  (`StatusFit.sideInset`: 15 dp or the display's rounded corner, `InsetSplit.pageCorners`), and the bookmark ribbon is
  ReadEra's from the view's top (`RibbonMath`: 42 × 62 px at x 987 on the S25, blue on phones). Prefs at the old 11 sp
  default become 13 sp once with margins less the bands' growth (`MaruSize`; not while 여백 사용 is off, whose fixed
  minimal margin could not give it back). Saved styles without `statusSizeV` keep their 11 sp-band margins and lose the
  growth only when applied at 13 sp (`UserStyle.elevenSpBands`).
- No probe, database write, counting, backfill, brightness-device initialization or auto-backup before the first page.
- Main thread owns Views, `BookSession` state, scroll positions and decor. Its IO and layout work are dispatched.
- Engine/math/migration/export helpers are pure; database APIs and `DeviceLight`/`LightProbe` IO are blocking off-main.
- A generation anchors one relayout; persistent page counts exclude any altered anchor-section count.
- Six status slots use reusable `StatusSlot` buffers; a pixel-identical status update does not repaint.
- Brightness device opt-in, resume marker, light originals, install stamp and probe verdicts are local, never JSON backup.
- Schema v3 creates tables and safe indexes before guarded ALTERs. An index never references an added column,
  including its WHERE clause. Upgrade cleanup removes rows belonging to missing books.
- Note jumps are primitive extras consumed once. Peeking does not update lastRead/progress until a manual turn.
  The notes hub queries the database only; it never opens book files. `Library.notesGen` invalidates read caches.

## Frozen surfaces and threading

| Owner | Surface | Users / thread |
|---|---|---|
| P0 | settings, JSON mapping, schema, models, Layout/Render fields, kit primitives, ReaderHost/panel capabilities | all lanes; pure values, prefs read on main; backup/SQL off-main |
| E1 / E2 | engine / rendering and device class | RC-P, RC-S, RC-A; engine on layout thread, drawing on main, vendor probe on IO |
| RC-P | generation, anchors, cache/count policy | RC-A and scroll; state on main, layout and count dispatched |
| RC-S / RC-A | scroll viewport / Activity wiring | extras through ReaderHost; main, never keep a PageInfo across calls |
| RU | chrome, status model, return state, light control | RC-A; main; DeviceLight serial IO and LightProbe IO |
| DA-C / DA-N | library/backup / notes/lookups/export | all UI through IO jobs; cache generation bumps after write commit |
| EX-P / EX-S / EX-N | settings popup / selection / contents | main Views, data writes and book searches on IO |
| LIB / SET / NOTES | library / settings / notes hub | main Views, list queries/scanning/export on IO |
| CI | scripts and screenshot comparisons | emulator; pixel and logged page-start acceptance |

## API inventory

The following declarations reflect the Phase 0 source. Default parameters preserve existing callers. For data,
all database entry points require IO; callbacks from reader/chrome APIs are main-thread callbacks. Stubs marked
`R3 stub (owner: …)` or `TODO("owner: …")` in code have no enabled product entry point in Phase 0.


### `settings/ReaderSettings.kt` — P0

```kotlin
data class ReaderSettings(
    val fontId: String = "nanummyeongjo",
    val fontSizeSp: Float = 20f,
    val fontWeight: Int = 500,
    val lineHeightPct: Int = 200,
    val paragraphSpacingPct: Int = 100,
    val indentPct: Int = 0,
    val letterSpacingPm: Int = 0,
    val align: Align = Align.LEFT,
    val lineBreak: LineBreakMode = LineBreakMode.WORD,
    val marginLeftDp: Int = 40,
    val marginRightDp: Int = 40,
    val marginTopDp: Int = 40,
    val marginBottomDp: Int = 40,
    val pageMargins: Boolean = true,
    val invert: Boolean = false,
    val headerLeft: StatusItem = StatusItem.NONE,
    val headerCenter: StatusItem = StatusItem.CHAPTER,
    val headerRight: StatusItem = StatusItem.NONE,
    val footerLeft: StatusItem = StatusItem.NONE,
    val footerCenter: StatusItem = StatusItem.NONE,
    val footerRight: StatusItem = StatusItem.NONE,
    val progressBar: Boolean = true,
    val statusFontSizeSp: Float = 11f,
    val widowOrphanControl: Boolean = true,
    val pageBreak: PageBreakMode = PageBreakMode.LINE,
    
    val txtBlankLines: Int = ParseOptions.BLANK_AUTO,
    val txtStripIndent: Boolean = true,
    val txtJoinWrappedLines: Int = 1,
    val txtDetectChapters: Boolean = true,
    val txtChapterRegex: String = "",
    val txtEmphasizeHeadings: Boolean = true,
    val txtReplaceRules: String = "",
    val epubPublisherStyles: Boolean = true,
    )
fun shows(item: StatusItem): Boolean = headerLeft == item || headerCenter == item || headerRight == item ||
fun slot(band: Int, pos: Int): StatusItem = when (band * 3 + pos)
fun withSlot(band: Int, pos: Int, item: StatusItem): ReaderSettings = when (band * 3 + pos)
fun parseOptions(txtEncoding: String = ""): ParseOptions = ParseOptions(
    txtBlankLines = txtBlankLines,
    txtStripIndent = txtStripIndent,
    txtJoinWrappedLines = txtJoinWrappedLines,
    txtDetectChapters = txtDetectChapters,
    txtChapterRegex = txtChapterRegex,
    txtEmphasizeHeadings = txtEmphasizeHeadings,
    txtReplaceRules = txtReplaceRules,
    txtEncoding = txtEncoding,
    epubPublisherStyles = epubPublisherStyles,
    )
const val MIN_FONT_SP = 8f
const val MAX_FONT_SP = 60f
const val PROGRESS_LANE_DP = 12
enum class StylePreset(val label: String, val description: String)
fun applyTo(s: ReaderSettings): ReaderSettings = when (this)
fun matches(s: ReaderSettings): Boolean = applyTo(s) == s
enum class TapZoneMode
enum class TapAction(val label: String)
enum class KeyHold(val label: String)
data class AppSettings(
    val tapZoneMode: TapZoneMode = TapZoneMode.LEFT_RIGHT,
    val customTapZones: List<TapAction> = listOf(
    TapAction.PREV, TapAction.NEXT, TapAction.NEXT,
    TapAction.PREV, TapAction.MENU, TapAction.NEXT,
    TapAction.PREV, TapAction.NEXT, TapAction.NEXT,
    ),
    val invertTaps: Boolean = false,
    val swipeToTurn: Boolean = true,
    val verticalSwipe: Boolean = false,
    val volumeKeysTurn: Boolean = true,
    val invertVolumeKeys: Boolean = false,
    val nextPageKeys: Set<Int> = emptySet(),
    val prevPageKeys: Set<Int> = emptySet(),
    val keyBindings: Map<Int, TapAction> = emptyMap(),
    val keyHold: KeyHold = KeyHold.REPEAT,
    val longPressSelect: Boolean = true,
    val longPressMs: Int = 500,
    val bookmarkByTouch: Boolean = true,
    val invertByTouch: Boolean = false,
    val fullscreen: Boolean = true,
    val keepScreenOn: Boolean = true,
    val brightnessSwipe: Boolean = false,
    val openLastOnStart: Boolean = false,
    val autoMarkFinished: Boolean = true,
    val einkRefreshEvery: Int = 0,
    val einkMode: Int = EINK_MODE_SYSTEM,
    val einkRefreshOnChapter: Boolean = false,
    val einkRefreshMethod: Int = EINK_REFRESH_AUTO,
    val einkFlashMs: Int = 100,
    val einkRefreshEveryNight: Int = -1,
    val einkFlashImages: Boolean = false,
    val autoTurnSeconds: Int = 30,
    val ttsRate: Float = 1f,
    val ttsPitch: Float = 1f,
    val ttsSleepMinutes: Int = 0,
    val ttsSleepChapters: Int = 0,
    val ttsHighlight: Boolean = true,
    val ttsVoice: String = "",
    val webSearchUrl: String = "https:
    val librarySort: LibrarySort = LibrarySort.RECENT,
    val libraryListMode: LibraryListMode = LibraryListMode.LIST,
    val scanFolders: Set<String> = emptySet(),
    val excludedFolders: Set<String> = emptySet(),
    val orientationLock: Int = -1,
    val readMode: ReadMode = ReadMode.PAGED,
    val scrollStyle: ScrollStyle = ScrollStyle.AUTO,
    val autoBackup: Boolean = true,
    val brightnessDevice: Boolean = false,
    val brightnessRestore: Boolean = true,
    val highlightLook: Int = HL_LOOK_AUTO,
    val listPaging: Int = LIST_PAGING_AUTO,
    val recordLookups: Boolean = true,
    val brightness: Float = -1f,
    )
const val EINK_MODE_SYSTEM = 0
const val EINK_MODE_HD = 177
const val EINK_MODE_REGAL = 180
const val EINK_MODE_FAST = 179
const val EINK_MODE_NORMAL = 178
const val EINK_REFRESH_AUTO = 0
const val EINK_REFRESH_GC16 = 1
const val EINK_REFRESH_CLEAN = 2
const val EINK_REFRESH_FLASH = 3
enum class LibrarySort(val label: String)
enum class LibraryListMode(val label: String)
enum class StatusItem(val label: String, val short: String, val example: String?)
enum class ReadMode(val label: String)
enum class ScrollStyle(val label: String)
const val HL_LOOK_AUTO = 0
const val HL_LOOK_COLOR = 1
const val HL_LOOK_INK = 2
const val LIST_PAGING_AUTO = 0
const val LIST_PAGING_PAGED = 1
const val LIST_PAGING_SCROLL = 2
```


### `settings/Margins.kt` — P0

```kotlin
object SideMargin
const val ZERO_DP = 20                      // 2026-10-05: MaruViewer (was 40)
const val LEGACY_DEFAULT_DP = 18
const val R3_ZERO_DP = 40
const val UI_MIN = -20
const val UI_MAX = 60
const val UI_STEP = 2
const val KEY = "r.marginBase"              // value = the "0" the margins were saved with
const val STYLE_KEY = "marginBase"
fun toUi(actualDp: Int): Int = actualDp - ZERO_DP
fun toDp(ui: Int): Int = (ui + ZERO_DP).coerceAtLeast(0)
fun label(ui: Int): String = when
fun isLegacyDefault(base: Int?, left: Int, right: Int): Boolean =
object VerticalMargin
const val ZERO_DP = 40
const val LEGACY_DEFAULT_DP = 16
const val UI_MIN = -40
const val UI_MAX = 40
const val UI_STEP = SideMargin.UI_STEP
const val KEY = "r.marginBaseV"
const val STYLE_KEY = "marginBaseV"
fun toUi(actualDp: Int): Int = actualDp - ZERO_DP
fun toDp(ui: Int): Int = (ui + ZERO_DP).coerceAtLeast(0)
fun label(ui: Int): String = SideMargin.label(ui)
fun isLegacyDefault(hasMarker: Boolean, top: Int, bottom: Int): Boolean =
```


### `settings/StatusMigration.kt` — P0

```kotlin
object StatusMigration
const val MARKER_KEY = "r.footerLeft"
const val LEGACY_TIME_LEFT_OFF = 0; const val LEGACY_TIME_LEFT_EPISODE = 1; const val LEGACY_TIME_LEFT_BOOK = 2
class Legacy(val showHeader: Boolean?, val showFooter: Boolean?, val page: Boolean?, val chapterLeft: Boolean?,
    val episode: Boolean?, val timeLeft: Int?, val percent: Boolean?, val clock: Boolean?, val battery: Boolean?)
fun from(p: android.content.SharedPreferences): Legacy
fun b(k: String): Boolean? = try
fun i(k: String): Int? = try
fun from(o: org.json.JSONObject): Legacy
fun b(k: String): Boolean? = o.opt(k) as? Boolean
class Slots(val headerLeft: StatusItem, val headerCenter: StatusItem, val headerRight: StatusItem,
    val footerLeft: StatusItem, val footerCenter: StatusItem, val footerRight: StatusItem)
fun applyTo(s: ReaderSettings): ReaderSettings = s.copy(headerLeft = headerLeft, headerCenter = headerCenter,
    headerRight = headerRight, footerLeft = footerLeft, footerCenter = footerCenter, footerRight = footerRight)
fun migrate(l: Legacy): Slots
```


### `settings/Settings.kt` — P0

```kotlin
object Settings
const val KEY_USER_STYLES = "a.userStyles"
fun init(context: Context)
internal fun initForTest(p: SharedPreferences)
fun saveUserStyles(list: List<UserStyle>)
fun saveReader(s: ReaderSettings)
fun saveApp(s: AppSettings)
fun addListener(l: () -> Unit): () -> Unit
fun raw(): SharedPreferences = prefs
fun encodeKeyBindings(map: Map<Int, TapAction>): String =
fun decodeKeyBindings(text: String?): Map<Int, TapAction>
```


### `settings/UserStyles.kt` — P0

```kotlin
data class UserStyle(
    val name: String,
    val fontId: String,
    val fontSizeSp: Float,
    val fontWeight: Int,
    val lineHeightPct: Int,
    val paragraphSpacingPct: Int,
    val indentPct: Int,
    val letterSpacingPm: Int,
    val align: Align,
    val lineBreak: LineBreakMode,
    val marginLeftDp: Int,
    val marginRightDp: Int,
    val marginTopDp: Int,
    val marginBottomDp: Int,
    val pageMargins: Boolean,
    )
fun applyTo(s: ReaderSettings): ReaderSettings = s.copy(
    fontId = fontId,
    fontSizeSp = fontSizeSp,
    fontWeight = fontWeight,
    lineHeightPct = lineHeightPct,
    paragraphSpacingPct = paragraphSpacingPct,
    indentPct = indentPct,
    letterSpacingPm = letterSpacingPm,
    align = align,
    lineBreak = lineBreak,
    marginLeftDp = marginLeftDp,
    marginRightDp = marginRightDp,
    marginTopDp = marginTopDp,
    marginBottomDp = marginBottomDp,
    pageMargins = pageMargins,
    )
fun matches(s: ReaderSettings): Boolean = applyTo(s) == s
fun from(name: String, s: ReaderSettings): UserStyle = UserStyle(
    name = name,
    fontId = s.fontId,
    fontSizeSp = s.fontSizeSp,
    fontWeight = s.fontWeight,
    lineHeightPct = s.lineHeightPct,
    paragraphSpacingPct = s.paragraphSpacingPct,
    indentPct = s.indentPct,
    letterSpacingPm = s.letterSpacingPm,
    align = s.align,
    lineBreak = s.lineBreak,
    marginLeftDp = s.marginLeftDp,
    marginRightDp = s.marginRightDp,
    marginTopDp = s.marginTopDp,
    marginBottomDp = s.marginBottomDp,
    pageMargins = s.pageMargins,
    )
object UserStyles
const val MAX = 5
const val MAX_NAME = 12
fun defaultName(existing: List<UserStyle>): String
fun cleanName(name: String): String
fun toJson(list: List<UserStyle>): JSONArray
fun toJson(u: UserStyle): JSONObject = JSONObject()
fun parse(text: String?): List<UserStyle>
fun fromJson(a: JSONArray): List<UserStyle>
fun fromJson(o: JSONObject): UserStyle?
```


### `data/SettingsJson.kt` — P0

```kotlin
internal data class RawPref(val key: String, val type: String, val value: Any)
internal object SettingsJson
const val TYPE_STRING = "string"
const val TYPE_INT = "int"
const val TYPE_LONG = "long"
const val TYPE_FLOAT = "float"
const val TYPE_BOOL = "bool"
const val TYPE_SET = "set"
const val READER_PREFIX = "r."
const val APP_PREFIX = "a."
const val USER_STYLES = "userStyles"
fun isTransient(key: String): Boolean
fun readerToJson(s: ReaderSettings): JSONObject = JSONObject()
fun readerFromJson(o: JSONObject, base: ReaderSettings): ReaderSettings = base.copy(
    fontId = BackupJson.str(o, "r.fontId", base.fontId).trim().ifEmpty
fun appToJson(s: AppSettings): JSONObject = JSONObject()
fun appFromJson(o: JSONObject, base: AppSettings): AppSettings
fun otherToJson(all: Map<String, *>): Pair<JSONObject, JSONObject>
fun otherFromJson(values: JSONObject?, types: JSONObject?): List<RawPref>
fun settingsToJson(
    reader: ReaderSettings,
    app: AppSettings,
    raw: Map<String, *>,
    userStyles: List<UserStyle>? = null,
    ): JSONObject
fun userStylesFromJson(settings: JSONObject?): List<UserStyle>?
fun addUnmapped(target: JSONObject, prefix: String, raw: Map<String, *>): JSONObject
fun unmappedFromJson(o: JSONObject?, prefix: String, current: Map<String, *>): List<RawPref>
```


### `data/LibrarySchema.kt` — P0

```kotlin
internal object LibrarySchema
const val DB_NAME = "library.db"
const val DB_VERSION = 3
const val CREATE_BOOKS = "CREATE TABLE IF NOT EXISTS books(" +
    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
    "path TEXT NOT NULL UNIQUE," +
    "file_name TEXT NOT NULL DEFAULT ''," +
    "folder TEXT NOT NULL DEFAULT ''," +
    "title TEXT NOT NULL DEFAULT ''," +
    "author TEXT NOT NULL DEFAULT ''," +
    "series TEXT," +
    "series_index REAL," +
    "format TEXT NOT NULL DEFAULT 'TXT'," +
    "size INTEGER NOT NULL DEFAULT 0," +
    "mtime INTEGER NOT NULL DEFAULT 0," +
    "added_at INTEGER NOT NULL DEFAULT 0," +
    "last_read_at INTEGER NOT NULL DEFAULT 0," +
    "pos_section INTEGER NOT NULL DEFAULT 0," +
    "pos_offset INTEGER NOT NULL DEFAULT 0," +
    "progress REAL NOT NULL DEFAULT 0," +
    "favorite INTEGER NOT NULL DEFAULT 0," +
    "to_read INTEGER NOT NULL DEFAULT 0," +
    "have_read INTEGER NOT NULL DEFAULT 0," +
    "trashed INTEGER NOT NULL DEFAULT 0," +
    "review TEXT NOT NULL DEFAULT ''," +
    "encoding TEXT NOT NULL DEFAULT ''," +
    "language TEXT," +
    "reading_seconds INTEGER NOT NULL DEFAULT 0," +
    
    "meta_locked INTEGER NOT NULL DEFAULT 0," +
    "review_at INTEGER NOT NULL DEFAULT 0,missing_at INTEGER NOT NULL DEFAULT 0)"
const val CREATE_BOOKMARKS = "CREATE TABLE IF NOT EXISTS bookmarks(" +
    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
    "book_id INTEGER NOT NULL," +
    "section INTEGER NOT NULL DEFAULT 0," +
    "char_offset INTEGER NOT NULL DEFAULT 0," +
    "snippet TEXT NOT NULL DEFAULT ''," +
    "note TEXT NOT NULL DEFAULT ''," +
    "created_at INTEGER NOT NULL DEFAULT 0,chapter TEXT NOT NULL DEFAULT '',frac REAL NOT NULL DEFAULT -1,sig TEXT NOT NULL DEFAULT '')"
const val CREATE_QUOTES = "CREATE TABLE IF NOT EXISTS quotes(" +
    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
    "book_id INTEGER NOT NULL," +
    "section INTEGER NOT NULL DEFAULT 0," +
    "start_offset INTEGER NOT NULL DEFAULT 0," +
    "end_offset INTEGER NOT NULL DEFAULT 0," +
    "quote_text TEXT NOT NULL DEFAULT ''," +
    "note TEXT NOT NULL DEFAULT ''," +
    "created_at INTEGER NOT NULL DEFAULT 0," +
    
    "style INTEGER NOT NULL DEFAULT 0,chapter TEXT NOT NULL DEFAULT '',frac REAL NOT NULL DEFAULT -1,sig TEXT NOT NULL DEFAULT '')"
const val CREATE_COLLECTIONS = "CREATE TABLE IF NOT EXISTS collections(" +
    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
    "name TEXT NOT NULL UNIQUE COLLATE NOCASE," +
    "created_at INTEGER NOT NULL DEFAULT 0)"
const val CREATE_BOOK_COLLECTIONS = "CREATE TABLE IF NOT EXISTS book_collections(" +
    "book_id INTEGER NOT NULL," +
    "collection_id INTEGER NOT NULL," +
    "PRIMARY KEY(book_id, collection_id)) WITHOUT ROWID"
const val CREATE_PAGE_COUNTS = "CREATE TABLE IF NOT EXISTS page_counts(" +
    "book_id INTEGER NOT NULL," +
    "layout_key TEXT NOT NULL," +
    "counts BLOB NOT NULL," +
    "updated_at INTEGER NOT NULL DEFAULT 0," +
    "PRIMARY KEY(book_id, layout_key))"
const val CREATE_IGNORED = "CREATE TABLE IF NOT EXISTS ignored(" +
    "path TEXT PRIMARY KEY NOT NULL," +
    "removed_at INTEGER NOT NULL DEFAULT 0)"
const val CREATE_READING_LOG = "CREATE TABLE IF NOT EXISTS reading_log(" +
    "day INTEGER NOT NULL," +
    "book_id INTEGER NOT NULL," +
    "seconds INTEGER NOT NULL DEFAULT 0," +
    "pages INTEGER NOT NULL DEFAULT 0," +
    "chars INTEGER NOT NULL DEFAULT 0," +
    "PRIMARY KEY(day, book_id)) WITHOUT ROWID"
const val CREATE_BOOK_PREFS = "CREATE TABLE IF NOT EXISTS book_prefs(" +
    "book_id INTEGER PRIMARY KEY," +
    "txt_override TEXT," +
    "finished_at INTEGER NOT NULL DEFAULT 0," +
    "episode_label TEXT,return_mark TEXT)"
const val ADD_QUOTE_STYLE = "ALTER TABLE quotes ADD COLUMN style INTEGER NOT NULL DEFAULT 0"
const val ADD_RETURN_MARK = "ALTER TABLE book_prefs ADD COLUMN return_mark TEXT"
const val CREATE_LOOKUPS = "CREATE TABLE IF NOT EXISTS lookups(" +
    "id INTEGER PRIMARY KEY AUTOINCREMENT,book_id INTEGER NOT NULL," +
    "word TEXT NOT NULL DEFAULT '',word_key TEXT NOT NULL DEFAULT ''," +
    "section INTEGER NOT NULL DEFAULT 0,start_offset INTEGER NOT NULL DEFAULT 0,end_offset INTEGER NOT NULL DEFAULT 0," +
    "context TEXT NOT NULL DEFAULT '',chapter TEXT NOT NULL DEFAULT '',frac REAL NOT NULL DEFAULT -1," +
    "sig TEXT NOT NULL DEFAULT '',via INTEGER NOT NULL DEFAULT 0,app TEXT NOT NULL DEFAULT ''," +
    "note TEXT NOT NULL DEFAULT '',created_at INTEGER NOT NULL DEFAULT 0)"
fun upgradeStatements(oldVersion: Int, columnsOf: (String) -> Set<String>): List<String>
```


### `data/Models.kt` — P0

```kotlin
data class Book(
    val id: Long,
    val path: String,
    val fileName: String,
    val title: String,
    val author: String,
    val series: String?,
    val seriesIndex: Float?,
    val format: BookFormat,
    val sizeBytes: Long,
    val modifiedAt: Long,
    val addedAt: Long,
    val lastReadAt: Long = 0,
    val posSection: Int = 0,
    val posOffset: Int = 0,
    val progress: Float = 0f,
    val favorite: Boolean = false,
    val toRead: Boolean = false,
    val haveRead: Boolean = false,
    val trashed: Boolean = false,
    val review: String = "",
    val encoding: String = "",
    val language: String? = null,
    val readingSeconds: Long = 0,
    val missingAt: Long = 0,
    )
data class Bookmark(
    val id: Long,
    val bookId: Long,
    val section: Int,
    val offset: Int,
    val snippet: String,
    val createdAt: Long,
    val note: String = "",
    val chapter: String = "", val frac: Float = -1f, val sig: String = "",
    )
data class Quote(
    val id: Long,
    val bookId: Long,
    val section: Int,
    val start: Int,
    val end: Int,
    val text: String,
    val note: String = "",
    val createdAt: Long,
    val style: Int = 0,
    val chapter: String = "", val frac: Float = -1f, val sig: String = "",
    )
data class BookCollection(val id: Long, val name: String, val createdAt: Long, val bookCount: Int = 0)
enum class Shelf(val label: String)
data class LibraryQuery(
    val shelf: Shelf = Shelf.ALL,
    val group: String? = null,
    val query: String = "",
    )
data class ShelfGroup(val key: String, val label: String, val count: Int)
data class NotePlace(val chapter: String, val frac: Float, val sig: String)
enum class NoteKind(val code: Int, val label: String)
enum class NotesTab(val label: String)
enum class NotesOrder(val label: String)
data class NotesQuery(
    val tab: NotesTab = NotesTab.ALL, val order: NotesOrder = NotesOrder.NEWEST,
    val bookId: Long? = null, val text: String = "", val style: Int? = null,
    val wordsOnce: Boolean = false,
    )
data class NoteRef(val kind: NoteKind, val id: Long)
fun packed(): Long = (kind.code.toLong() shl 56) or (id and 0x00FF_FFFF_FFFF_FFFFL)
fun unpack(v: Long): NoteRef?
data class NoteRow(
    val ref: NoteRef, val bookId: Long,
    val section: Int, val start: Int, val end: Int,
    val body: String, val bodyCut: Boolean, val note: String, val noteCut: Boolean,
    val word: String, val wordCount: Int,
    val style: Int,
    val via: Int, val app: String,
    val chapter: String, val frac: Float, val sig: String,
    val time: Long,
    )
data class NoteBook(val id: Long, val title: String, val author: String, val path: String,
    val trashed: Boolean, val missing: Boolean, val lastReadAt: Long, val count: Int)
data class NotesCounts(val quotes: Int, val memos: Int, val bookmarks: Int, val reviews: Int, val words: Int)
fun of(tab: NotesTab): Int = when (tab)
data class Lookup(
    val id: Long, val bookId: Long, val word: String, val section: Int, val start: Int, val end: Int,
    val context: String, val chapter: String, val frac: Float, val sig: String,
    val via: Int, val app: String, val note: String, val createdAt: Long,
    )
```


### `render/Render.kt` — P0

```kotlin
enum class FontSource
class FontInfo(
    val id: String,
    val name: String,
    val source: FontSource,
    val path: String,
    val boldPath: String? = null,
    val variable: Boolean = false,
    val serif: Boolean = true,
    )
enum class HighlightKind
class Highlight(val start: Int, val end: Int, val kind: HighlightKind, val style: Int = 0)
class PageDecor(
    val highlights: List<Highlight> = emptyList(),
    val bookmarked: Boolean = false,
    val status: StatusDecor? = null,
    val statusVersion: Int = 0,
    )
internal object RenderContext
@Volatile var app: Context? = null
```


### `render/StatusDecor.kt` — P0

```kotlin
class StatusSlot
@JvmField val chars = CharArray(CAPACITY)
@JvmField var length = 0
@JvmField var text: String? = null
@JvmField var battery = -1
@JvmField val batteryChars = CharArray(3)
@JvmField var batteryLength = 0
fun set(src: CharArray, n: Int, battery: Int): Boolean
fun setText(t: String?): Boolean
fun clear(): Boolean = setText(null)
class StatusBand
@JvmField val left = StatusSlot()
@JvmField val center = StatusSlot()
@JvmField val right = StatusSlot()
class StatusDecor
@JvmField val header = StatusBand()
@JvmField val footer = StatusBand()
@JvmField var lane = false
@JvmField var progress = -1f
@JvmField var version = 0
```


### `reader/ReaderHost.kt` — P0

```kotlin
interface ReaderHost
fun currentPosition(): DocPosition
fun goTo(pos: DocPosition, remember: Boolean = true)
fun nextPage(): Boolean
fun prevPage(): Boolean
fun pageLabel(pos: DocPosition): String
fun totalPagesKnown(): Boolean
fun setHighlights(owner: String, section: Int, highlights: List<Highlight>)
fun applySettings(settings: ReaderSettings)
fun setChromeVisible(visible: Boolean)
fun hitTest(x: Float, y: Float): Int
fun textOf(section: Int, start: Int, end: Int): String
fun toggleBookmark()
fun redraw()
```


### `reader/extras/ReaderPanels.kt` — P0

```kotlin
object ReaderPanels
fun showReadingSettings(host: ReaderHost, anchor: View)
fun showContents(host: ReaderHost, initialTab: Int = 0)
fun showSearch(host: ReaderHost, initialQuery: String = "")
fun showReview(host: ReaderHost)
fun showDocumentInfo(activity: Activity, book: Book, document: BookDocument?)
fun editBookInfo(activity: Activity, book: Book, onSaved: (() -> Unit)? = null)
fun showGoTo(host: ReaderHost)
fun closeSearchBar(host: ReaderHost): Boolean
fun dismissAll(host: ReaderHost)
interface PageJumpHost
fun goToPage(section: Int, pageIndex: Int, remember: Boolean)
fun progressFraction(): Float
fun goToProgress(fraction: Float)
interface BookInsightsHost
fun episodes(onReady: (Episodes?) -> Unit)
fun minutesLeft(bookScope: Boolean): Int?
fun charsPerMinute(): Int
interface TxtOverrideHost
fun applyTxtOverride(o: TxtOverride?, onApplied: (() -> Unit)? = null)
fun saveTxtAsDefaults()
interface ReaderEndHost
fun showBookEnd()
internal object PanelRegistry
fun <T : Dialog> dialog(owner: Context, d: T): T = d.also
fun popup(owner: Context, p: PopupWindow): PopupWindow = p.also
fun job(owner: Context, j: Job): Job = j.also
fun closeAll(owner: Activity)
fun openCount(owner: Activity): Int
interface StatusSampleHost
interface NotePlaceHost
interface PageThumbsHost
fun thumbTotal(): Int; fun thumbCurrent(): Int; fun thumbAspect(): Float
fun requestThumbs(first: Int, count: Int, widthPx: Int, heightPx: Int, progressive: Boolean, onBatch: (ThumbBatch)->Unit)
fun cancelThumbs()
class ThumbCell(val page: Int, val section: Int, val pageIndex: Int, val bitmap: android.graphics.Bitmap?, val marks: Int)
class ThumbBatch(val first: Int, val cells: List<ThumbCell>, val total: Int, val current: Int, val complete: Boolean)
```


### `engine/Layout.kt` — E1

```kotlin
class FontMetricsPx(@JvmField val ascent: Float, @JvmField val descent: Float)
interface TextMeasurer
fun measure(text: String, start: Int, end: Int, style: RunStyle, out: FloatArray, outOffset: Int)
fun metrics(style: RunStyle): FontMetricsPx
fun imageSize(src: String): IntSize?
data class IntSize(val width: Int, val height: Int)
enum class PageBreakMode
enum class LineBreakMode
data class LayoutConfig(
    val width: Int,
    val height: Int,
    val lineHeightEm: Float = 1.7f,
    val paragraphSpacingEm: Float = 0.5f,
    val indentEm: Float = 1f,
    val align: Align = Align.JUSTIFY,
    val lineBreak: LineBreakMode = LineBreakMode.CHAR,
    val publisherStyles: Boolean = true,
    val maxImageHeightFraction: Float = 1f,
    val widowOrphanControl: Boolean = true,
    val pageBreak: PageBreakMode = PageBreakMode.LINE,
    )
class LineInfo(
    @JvmField val start: Int,
    @JvmField val end: Int,
    @JvmField val x: Float,
    @JvmField val top: Float,
    @JvmField val baseline: Float,
    @JvmField val bottom: Float,
    @JvmField val justifyExtra: Float,
    @JvmField val expandMode: Int,
    @JvmField val imageBlock: ImageBlock? = null,
    @JvmField val imageWidth: Float = 0f,
    @JvmField val imageHeight: Float = 0f,
    @JvmField val isRule: Boolean = false,
    )
const val EXPAND_NONE = 0
const val EXPAND_SPACES = 1
const val EXPAND_CHARS = 2
class PageInfo(
    @JvmField val start: Int, @JvmField val end: Int, @JvmField val lines: List<LineInfo>,
    @JvmField val lead: Float = 0f,
    )
class SectionLayout(
    val content: SectionContent,
    val config: LayoutConfig,
    val pages: List<PageInfo>,
    val advances: FloatArray,
    val anchorBreak: Int = -1,
    val anchorPage: Int = -1,
    val anchorShifted: Boolean = false,
    )
fun pageForOffset(offset: Int): Int
class PageTally(@JvmField val pages: Int, @JvmField val anchorPage: Int, @JvmField val anchorShifted: Boolean)
```


### `engine/Typesetter.kt` — E1

```kotlin
class Typesetter(private val measurer: TextMeasurer, private val config: LayoutConfig)
fun layout(content: SectionContent, anchorBreak: Int = -1): SectionLayout
fun countPages(content: SectionContent): Int = runPass(content, retain = false, -1).pageCount
fun count(content: SectionContent, anchorBreak: Int): PageTally
class RectPx(@JvmField var left: Float, @JvmField var top: Float, @JvmField var right: Float, @JvmField var bottom: Float)
object LineGeometry
fun charPositions(layout: SectionLayout, line: LineInfo, out: FloatArray): Float
fun hitTest(layout: SectionLayout, page: PageInfo, x: Float, y: Float): Int
fun rangeRects(layout: SectionLayout, page: PageInfo, start: Int, end: Int): List<RectPx>
fun bandTop(layout: SectionLayout, ln: LineInfo): Float = maxOf(ln.top, ln.baseline - 0.95f * bandEm(layout, ln))
fun bandBottom(layout: SectionLayout, ln: LineInfo): Float = minOf(ln.bottom, ln.baseline + 0.30f * bandEm(layout, ln))
fun glyphAt(layout: SectionLayout, page: PageInfo, x: Float, y: Float, slop: Float, slopY: Float = 0f): Int
fun wordAt(text: String, offset: Int): Long
fun packedStart(packed: Long): Int = (packed ushr 32).toInt()
fun packedEnd(packed: Long): Int = (packed and 0xFFFFFFFFL).toInt()
```


### `render/StatusFit.kt` — E2

```kotlin
// 2026-10-05: the bands' own places (px of settings/StatusBands' whole dp); size(), lane(), fitsDp() and the
// '가려짐' note are gone: no margin hides or shrinks a band any more.
internal object StatusFit
const val PAD_DP = StatusBands.PAD_DP           // 2
const val LANE_DP = StatusBands.LANE_DP         // ReaderSettings.PROGRESS_LANE_DP
const val EDGE_DP = StatusBands.EDGE_DP         // 4
fun edgePx(density: Float): Int
fun laneTopPx(density: Float): Int              // the return chip sits above it (2026-10-05: lanePx and laneBottomPx
                                                // went with ReadEra's 탐색줄, which ProgressMath places from the bottom)
fun glyphPx(s: ReaderSettings, density: Float): Int
fun headerBandPx(s: ReaderSettings, density: Float): Int
fun footerBandPx(s: ReaderSettings, density: Float): Int
const val INK_SAMPLE = "(가g0"                  // the renderer measures its ink once
fun headerBaseline(cutoutTop: Float, contentTop: Float, ascentPx: Float, descentPx: Float, inkTopPx: Float,
    inkBottomPx: Float, glyphPx: Float, density: Float): Float   // no cutout: glyph box EDGE below the top; below one:
                                                                 // centred between it and the text box; ink kept inside
fun footerBaseline(viewBottom: Float, lane: Boolean, descentPx: Float, inkTopPx: Float, inkBottomPx: Float,
    glyphPx: Float, density: Float): Float
fun fitTextPx(textPx: Float, inkPx: Float, glyphPx: Float): Float  // smaller once only when the ink is taller than the box

// settings/Margins.kt
object StatusBands { EDGE_DP = 4; PAD_DP = 2; LANE_DP = 12; GLYPH_EM = 1.45
    fun statusSp(s): Float; fun glyphDp(s): Int; fun headerDp(s): Int; fun footerDp(s): Int }
object VerticalMargin { EDGE_DP = 40; TOP_ZERO_DP = 18; BOTTOM_ZERO_DP = 22; MAX_DP = 80; UI_MIN = -22; UI_MAX = 62
    KEY = "r.marginBaseV"; BANDS = 2; EDGE = 40
    fun topDp(ui): Int; fun bottomDp(ui): Int; fun toUi(top, bottom): Int; fun countsFromEdge(base: Int?): Boolean
    fun step(top, bottom, from, to): IntArray   // on the defaults' line follow it, else both sides by to − from
    fun fromEdge(s, top = true, bottom = true): ReaderSettings }
```


### `render/ProgressMath.kt` — E2

```kotlin
// 2026-10-05: ReadEra's 탐색줄 on the user's S25 screenshots (S25 / Comet px): a 2 / 1 px line between two end dots
// and the position dot, all 14 / 9 px, outer edges 7 dp from the page view's sides, centred 8 dp above its bottom
// (line rows 2315–2316 / 1423, dots 2309–2322 / 1419–1427); colours PagePalette.progressLine / progressDot
// (inkProgressLine / inkProgressDot on e-ink). Replaces yc, rDot, rCap, x0, x1 and the lane-based track.
internal object ProgressMath
const val LINE_DP = 2f / 3f; const val DOT_DP = 14f / 3f; const val SIDE_DP = 7; const val CENTRE_DP = 8
fun lineH(density: Float): Int                              // max(1, round(LINE_DP · density))
fun dotD(density: Float): Int                               // nearest DOT_DP · density with lineH's parity
fun lineTop(viewH: Int, density: Float): Int                // viewH − round(CENTRE_DP · density + lineH / 2)
fun dotTop(viewH: Int, density: Float): Int
fun centreY(viewH: Int, density: Float): Float
fun sidePx(density: Float): Int
fun trackPx(viewW: Int, density: Float): Int                // viewW − 2 · sidePx − dotD (StatusModel's dot pixels)
fun dotLeft(f: Float, viewW: Int, density: Float): Int      // sidePx + round(f · trackPx)
fun dotX(f: Float, viewW: Int, density: Float): Float       // dotLeft + dotD / 2
fun onEndDot(f: Float, viewW: Int, density: Float): Boolean // dotLeft on an end dot's: the position dot is not drawn
```


### `render/QuoteStyles.kt` — E2

```kotlin
object QuoteStyles
const val YELLOW = 0; const val GREEN = 1; const val BLUE = 2; const val RED = 3; const val PURPLE = 4
const val UNDERLINE = 5; const val COUNT = 6; const val MAX_STORED = 15
const val LINE_NONE = 0; const val LINE_THIN = 1; const val LINE_THICK = 2; const val LINE_DASHED = 3; const val LINE_BOX = 4
fun of(stored: Int): Int = if (stored in 0 until COUNT) stored else YELLOW
fun label(style: Int): String = labels[of(style)]
fun tag(style: Int): String = "[" + label(style) + "]"
fun colorFill(style: Int, night: Boolean): Int = (if (night) this.night else day)[of(style)]
fun colorLine(style: Int): Int = if (of(style) == UNDERLINE) LINE_THICK else LINE_NONE
fun inkGrey(style: Int): Int = grey[of(style)]
fun inkLine(style: Int): Int = lines[of(style)]
fun thumbGrey(style: Int): Int = inkGrey(style).let
```


### `render/ChromePalette.kt` — RU (2026-10-05)

```kotlin
internal class ChromePalette   // page, surface, text, text2, accent, divider, rule, edge, track, hist, histOff,
                               // shadow, pressed, active: Int; motion, eink, dark: Boolean
const val SHADOW_DP = 4
val DEFAULT: ChromePalette     // the e-ink 흰 바탕 set = the chrome of before
fun of(page: PagePalette, eink: Boolean?): ChromePalette   // six shared sets; eink null → the e-ink set
```


### `reader/ReaderWindow.kt`, `reader/ReaderFormat.kt`, `ui/kit/Toggle.kt` — additions (2026-10-05)

```kotlin
fun applyBarLook(activity: Activity, dark: Boolean)        // API 30+: transparent system bars, light icons on dark
fun pageLabelCut(label: String): Int                        // index of " / " in a page label, -1 = none
fun InkToggle.setColors(ink: Int, paper: Int, on: Int)      // defaults black, white, black
```


### `render/QuoteLook.kt` — E2

```kotlin
internal object QuoteLook
fun update(mode: Int, eink: Boolean?)
fun ink(): Boolean = false
```


### `render/DeviceClass.kt` — E2

```kotlin
object DeviceClass
const val PREF_KEY = "deviceClass"
fun einkByBuild(manufacturer: String, brand: String, model: String): Boolean
fun stamp(manufacturer: String, model: String, fingerprint: String): String = "$manufacturer/$model/${fingerprint.hashCode()}"
fun cached(context: Context): Boolean? = try
fun probe(context: Context): Boolean = einkByBuild(Build.MANUFACTURER, Build.BRAND, Build.MODEL)
fun probeAsync(context: Context, onDone: (Boolean) -> Unit)
```


### `render/PageRenderer.kt` — E2

```kotlin
class PageRenderer(
    context: Context,
    private val measurer: AndroidTextMeasurer,
    private val images: ImageCache?,
    epub: Boolean = false, // [2026-10-05] an EPUB page: the palette's EPUB text shadow (PagePalette.shadowDyDp(epub))
)
fun draw(
    canvas: Canvas,
    layout: SectionLayout,
    pageIndex: Int,
    contentLeft: Float,
    contentTop: Float,
    viewWidth: Int,
    viewHeight: Int,
    decor: PageDecor,
    )
fun preload(layout: SectionLayout, pageIndex: Int)
fun drawChrome(canvas: Canvas, decor: PageDecor, contentLeft: Float, contentTop: Float, contentWidth: Float,
    contentHeight: Float, viewWidth: Int, viewHeight: Int)
fun drawBody(canvas: Canvas, layout: SectionLayout, pageIndex: Int, left: Float, top: Float,
    clipTop: Float, clipBottom: Float, highlights: List<Highlight>): Boolean = false
fun drawOverlay(canvas: Canvas, decor: PageDecor, contentLeft: Float, contentTop: Float, contentWidth: Float, viewWidth: Int)
fun prefetchPages(layouts: Array<SectionLayout?>, pages: IntArray, count: Int, done: Runnable?)
internal object RibbonMath
const val WIDTH_DP = 14f
const val HEIGHT_DP = 24f
const val MIN_HEIGHT_DP = 12f
const val RIGHT_DP = 14f
const val GAP_DP = 3f
const val NOTCH_FRACTION = 0.25f
fun left(viewWidth: Int, density: Float): Float = viewWidth - (RIGHT_DP + WIDTH_DP) * density
fun height(density: Float, contentTop: Float, contentRight: Float, viewWidth: Int): Float
fun headerInset(density: Float, contentRight: Float, viewWidth: Int, ribbonH: Float, glyphTop: Float): Float
    // 2026-10-05: kept free at the header's right end on every page (StatusMath.allocate reserveRight)
internal object BatteryMath
fun bodyWidth(ts: Float, first: Boolean = false): Float     // first (icon before the clock, no number): 1.75 ts
fun bodyHeight(ts: Float, first: Boolean = false): Float    // first: 0.6 ts
fun nubWidth(ts: Float, first: Boolean = false): Float      // first: 0.1 ts
fun nubHeight(ts: Float): Float = maxOf(1f, Math.round(0.25f * ts).toFloat())
fun firstStroke(density: Float): Float = maxOf(1f, Math.round(0.6f * density).toFloat())
fun gap(ts: Float): Float = 0.25f * ts
fun iconWidth(ts: Float, first: Boolean = false): Float
fun labelGap(ts: Float): Float = 0.5f * ts
fun fillRight(inLeft: Float, inRight: Float, level: Int): Float
internal object FooterFit
internal class LatestTaskRunner(name: String)
fun submit(task: Runnable)
```


### `reader/Anchors.kt` — RC-P

```kotlin
class AnchorSpec(val section: Int, val offset: Int, val needle: String? = null)
fun resolve(content: SectionContent): Int
internal object AnchorMath
fun pageFor(l: SectionLayout, offset: Int): Int =
internal object TextRefind
const val NEEDLE = 24
const val MIN_NEEDLE = 6
const val WINDOW = 8192
fun snippet(text: String, offset: Int): String?
fun find(text: String, estimate: Int, needle: String): Int
```


### `reader/LayoutKeys.kt` — RC-P

```kotlin
data class PageGeometry(
    val viewWidth: Int,
    val viewHeight: Int,
    val contentLeft: Int,
    val contentTop: Int,
    val contentWidth: Int,
    val contentHeight: Int,
    val cutoutTop: Int = 0,                 // 2026-10-05: extraTop (camera band); the header's band starts below it
    )
object LayoutKeys
const val VERSION = 3
const val ALGO_VERSION = 1
const val GOLDEN_HASH = "071717a86d158ac8"
const val GOLDEN_HASH_PARAGRAPH = "TBD"
const val TINY_MARGIN_DP = 4
fun geometry(s: ReaderSettings, viewW: Int, viewH: Int, density: Float, extraTop: Int = 0): PageGeometry
// 2026-10-05: top = extraTop + px(StatusBands.headerDp(s) + margin), bottom = px(StatusBands.footerDp(s) + margin)
fun px(dp: Int): Int = Math.round(dp * density)
fun config(s: ReaderSettings, g: PageGeometry, txt: Boolean = false): LayoutConfig = LayoutConfig(
    width = g.contentWidth,
    height = g.contentHeight,
    lineHeightEm = s.lineHeightPct / 100f,
    paragraphSpacingEm = s.paragraphSpacingPct / 100f,
    indentEm = s.indentPct / 100f,
    align = s.align,
    lineBreak = s.lineBreak,
    publisherStyles = txt || s.epubPublisherStyles,
    maxImageHeightFraction = 1f,
    widowOrphanControl = s.widowOrphanControl,
    pageBreak = s.pageBreak,
    )
fun charsPerPageHint(c: LayoutConfig, emPx: Float): Int
fun layoutChanged(a: ReaderSettings, b: ReaderSettings): Boolean = layoutPart(a) != layoutPart(b) || bandsChanged(a, b)
fun layoutChanged(a: ReaderSettings, b: ReaderSettings, format: BookFormat): Boolean =
fun bandsChanged(a: ReaderSettings, b: ReaderSettings): Boolean  // 2026-10-05: StatusBands heights, not the raw slots
fun parseChanged(a: ReaderSettings, b: ReaderSettings, encoding: String): Boolean =
fun parseChanged(a: ReaderSettings, b: ReaderSettings, format: BookFormat, encoding: String): Boolean =
fun parseOptionsFor(s: ReaderSettings, format: BookFormat, encoding: String): ParseOptions =
fun textSignature(s: ReaderSettings, format: BookFormat, encoding: String): String?
fun layoutWeight(weight: Int, variable: Boolean, system: Boolean, hasBoldFile: Boolean): Int
fun keyFor(
    s: ReaderSettings,
    format: BookFormat,
    encoding: String,
    g: PageGeometry,
    density: Float,
    fontIdentity: String,
    algoVersion: Int = ALGO_VERSION,
    parseVersion: Int = parseVersionOf(format),
    ): String = key(
    layoutPart(s, format), parseOptionsFor(s, format, encoding), g, density, "$fontIdentity|pv=$parseVersion",
    algoVersion,
    )
fun parseVersionOf(format: BookFormat): Int =
fun key(
    s: ReaderSettings,
    parse: ParseOptions,
    g: PageGeometry,
    density: Float,
    fontIdentity: String,
    algoVersion: Int = ALGO_VERSION,
    ): String
```


### `reader/BookSession.kt` — RC-P

```kotlin
class BookSession(
    private val context: Context,
    val book: Book,
    val document: BookDocument,
    initialSettings: ReaderSettings,
    )
interface Listener
fun onCountsChanged(complete: Boolean)
fun onSectionStored(section: Int, layout: SectionLayout)
class Generation(
    val id: Int,
    val settings: ReaderSettings,
    val geometry: PageGeometry,
    val config: LayoutConfig,
    val density: Float,
    )
enum class Change
@JvmField var waiters = 0
fun setViewport(width: Int, height: Int, anchor: AnchorSpec? = null): Boolean
fun updateSettings(new: ReaderSettings, anchor: AnchorSpec? = null): Change
fun renderer(): PageRenderer
fun peek(section: Int): SectionLayout? = cache[section]
fun startsUnit(section: Int): Boolean = false
fun touch(section: Int, shownTo: Int = section)
fun prefetch(section: Int)
fun check()
fun startCounting(countDelayMs: Long = 0)
fun episodes(onReady: (Episodes?) -> Unit)
fun charsLeftInBook(section: Int, offset: Int): Long = counts.charsFrom(section, offset)
fun charsLeftInChapter(section: Int, offset: Int): Long
fun trimMemory()
fun close()
const val MAX_CACHED = 4
const val SAVE_EVERY = 25
const val SAVE_SETTLE_MS = 30_000L
internal object CountSaves
fun due(known: Int, savedKnown: Int, complete: Boolean, ageMs: Long): Boolean =
fun maskFailed(arr: IntArray, failed: Collection<Int>): Int
internal class Once<T : Any>
fun get(onReady: (T?) -> Unit, start: () -> Unit)
fun complete(result: T?)
const val IDLE = 0
const val RUNNING = 1
const val DONE = 2
```


### `reader/ScrollMath.kt` — RC-S

```kotlin
internal interface StripSource
fun layoutOf(section: Int): SectionLayout?
fun unitGap(section: Int): Float
internal class ScrollPos
@JvmField var section = 0
@JvmField var page = 0
@JvmField var dy = 0f
@JvmField var blockedAt = -1
fun set(o: ScrollPos)
internal enum class Step
internal enum class SettleKind
internal enum class Placement
internal object ScrollMath
const val MAX_STRIPS = 64
const val CONTEXT_FRACTION = 0.25f
const val CHAPTER_GAP_EM = 2f
fun gapAbove(src: StripSource, section: Int, l: SectionLayout, page: Int): Float
fun body(l: SectionLayout, page: Int): Float
fun height(src: StripSource, section: Int, l: SectionLayout, page: Int): Float
fun scrollBy(src: StripSource, pos: ScrollPos, delta: Float, viewH: Float): Float
fun place(src: StripSource, section: Int, l: SectionLayout, offset: Int, placement: Placement,
    viewH: Float, lineAligned: Boolean, pos: ScrollPos): Unit
fun stepDown(src: StripSource, pos: ScrollPos, viewH: Float, out: ScrollPos): Step
fun stepUp(src: StripSource, pos: ScrollPos, viewH: Float, out: ScrollPos): Step
fun snapToLine(src: StripSource, pos: ScrollPos, viewH: Float): Unit
fun releaseStep(totalDy: Float, vy: Float, flingMin: Float, viewH: Float): Int
fun anchor(src: StripSource, pos: ScrollPos, viewH: Float): Long
fun topPage(src: StripSource, pos: ScrollPos, viewH: Float): Long
fun atBookEnd(src: StripSource, pos: ScrollPos, viewH: Float): Boolean
fun lastFullyVisibleBottom(src: StripSource, pos: ScrollPos, viewH: Float): Float
fun distance(src: StripSource, from: ScrollPos, to: ScrollPos, limit: Float): Float
fun forEachVisible(src: StripSource, pos: ScrollPos, viewH: Float,
    visit: (Int, SectionLayout, Int, Float, Float) -> Unit): Unit
fun anchorAfter(kind: SettleKind, placed: Long, computed: Long): Long
fun isVertical(dx: Float, dy: Float): Boolean = Math.abs(dy) > Math.abs(dx)
internal class ScreenCounter
fun add(px: Float, viewH: Float): Int
```


### `reader/ScrollReader.kt` — RC-S

```kotlin
internal enum class Motion
internal class ScrollReader(private val view: PageView, private val host: Host) : PageView.ScrollInput, StripSource
interface Host
fun session(): BookSession?; fun renderer(): PageRenderer?; fun geometry(): PageGeometry?
fun decor(): PageDecor; fun highlights(section: Int): List<Highlight>; fun unitGap(section: Int): Float
fun onTopPageChanged(section: Int, page: Int); fun onSettled(kind: SettleKind, movedPx: Float)
fun onBlocked(section: Int)
fun showAt(section: Int, layout: SectionLayout, offset: Int, placement: Placement, kind: SettleKind): Long
fun step(next: Boolean): Step
fun onSectionStored(section: Int, layout: SectionLayout): Unit
fun onGenerationChanged(): Unit
fun onHighlightsChanged(section: Int): Unit
fun onTrimMemory(): Unit
fun anchor(): Long
fun topPage(): Long
fun atBookEnd(): Boolean
fun virtualPage(): VirtualPage?
fun focusAt(y: Float): Boolean
fun clearFocus(): Unit
fun lineWhollyVisible(section: Int, offset: Int): Boolean
fun visibleRanges(visit: (section: Int, start: Int, end: Int) -> Unit): Unit
fun userMoving(): Boolean
fun detach(): Unit
fun onDeviceClass(): Unit
internal class VirtualPage(val section: Int, val layout: SectionLayout, val page: PageInfo, val pageIndex: Int)
```


### `reader/PageView.kt` — RC-S

```kotlin
class PageFrame(
    val renderer: PageRenderer,
    val layout: SectionLayout,
    val pageIndex: Int,
    val left: Float,
    val top: Float,
    val decor: PageDecor,
    )
class PageView(context: Context, private val cb: Callbacks) : View(context)
interface ScrollInput
fun isMoving(): Boolean; fun stopMotion(): Boolean; fun dragBy(dy: Float)
fun release(totalDy: Float, velocityY: Float); fun cancelDrag()
fun a11yStep(next: Boolean): Boolean; fun computeScroll(); fun draw(canvas: Canvas, width: Int, height: Int)
interface Callbacks
fun onScrollStart()
fun onTouchStarted()
fun isSelectionActive(): Boolean
fun onSelectionTouch(ev: MotionEvent): Boolean
fun onTap(x: Float, y: Float)
fun onSwipe(dir: SwipeDir)
fun onLongPress(x: Float, y: Float): Boolean
fun brightnessStart(): Float
fun onBrightness(value: Float, done: Boolean)
fun onViewSizeChanged(w: Int, h: Int)
fun onWheel(next: Boolean)
fun traceOpen(bookId: Long, startedAt: Long)
fun traceTurn(inputAt: Long)
const val LONG_PRESS_MS = 500L
const val WHEEL_INTERVAL_MS = 60L
internal object ReaderPerf
const val TAG = "RAPerf"
```


### `reader/ReaderJump.kt` — RC-A

```kotlin
data class ReaderJump(val section: Int, val offset: Int, val end: Int = -1, val frac: Float = -1f,
    val sig: String = "", val anchor: String = "")
fun put(i: Intent): Intent = i.putExtra(EXTRA_SECTION, section).putExtra(EXTRA_OFFSET, offset)
const val EXTRA_SECTION = "jump_section"; const val EXTRA_OFFSET = "jump_offset"; const val EXTRA_END = "jump_end"
const val EXTRA_FRAC = "jump_frac"; const val EXTRA_SIG = "jump_sig"; const val EXTRA_ANCHOR = "jump_anchor"
const val ANCHOR_MAX = 64
fun from(i: Intent): ReaderJump? = try
internal fun sanitize(section: Int, offset: Int, end: Int, frac: Float, sig: String?, anchor: String?): ReaderJump?
fun strip(i: Intent): Intent = Intent(i).apply
fun of(row: NoteRow): ReaderJump = ReaderJump(row.section, row.start, row.end, row.frac, row.sig,
    (if (row.ref.kind == NoteKind.LOOKUP) row.word else row.body).take(ANCHOR_MAX))
fun resolve(j: ReaderJump, currentSig: String?, sectionCount: Int, locate: (Float) -> DocPosition): DocPosition?
object NoteSig
object JumpAnchor
fun matches(text: CharSequence, offset: Int, anchor: String): Boolean
```


### `reader/StatusModel.kt` — RU

```kotlin
internal class StatusInputs
@JvmField var page = 0; @JvmField var total = 0
@JvmField var percent = 0
@JvmField var bar = -1f
@JvmField var chapterTitle: String? = null; @JvmField var bookTitle: String? = null
@JvmField var chapterStartsHere = false
@JvmField var chapterPagesLeft = -1
@JvmField var minutesEpisode = -1; @JvmField var minutesBook = -1
@JvmField var epNumbered = false; @JvmField var epNumber = -1; @JvmField var epMax = -1
@JvmField var tocIndex = -1; @JvmField var tocCount = 0
@JvmField var minuteOfDay = -1; @JvmField var is24 = true
@JvmField var battery = -1
internal class StatusModel
fun update(s: ReaderSettings, inp: StatusInputs, trackPx: Int): Boolean
fun sample(item: StatusItem, inp: StatusInputs): String? = null
internal object StatusText
fun page(buf: CharArray, at: Int, page: Int, total: Int): Int
fun percent(buf: CharArray, at: Int, p: Int): Int
fun clock(buf: CharArray, at: Int, minuteOfDay: Int, is24: Boolean): Int
fun chapterLeft(buf: CharArray, at: Int, pages: Int): Int
fun episode(buf: CharArray, at: Int, numbered: Boolean, n: Int, max: Int, idx: Int, count: Int): Int
fun timeLeft(buf: CharArray, at: Int, book: Boolean, minutes: Int): Int
fun int(buf: CharArray, at: Int, v: Int): Int
```


### `reader/ReturnNav.kt` — RU

```kotlin
internal interface ReturnHost
fun currentPosition(): DocPosition
fun isOnCurrentPage(pos: DocPosition): Boolean
fun globalPageOf(pos: DocPosition): Int
fun jumpToReturn(pos: DocPosition)
fun charProgressOf(pos: DocPosition): Float
fun locateFraction(f: Float): DocPosition
fun textSignature(): String?
fun saveReturnMark(text: String?)
fun onReturnChanged()
internal class ReturnNav(ctx: Context, private val host: ReturnHost)
fun markOnScreen(): Boolean = false
fun onJump(from: DocPosition)
fun onManualTurn()
fun onPinPressed()
fun onChromeShown()
fun onChromeHidden()
fun bind()
fun restore(saved: String?)
fun markFraction(): Float = Float.NaN
fun reparsed(fraction: Float, exact: Boolean)
fun reset()
fun setLook(page: PagePalette, eink: Boolean?)    // 2026-10-05: the history row and the chip in the chrome's colours
internal class ReturnPoints
enum class Chip
fun pin(here: DocPosition, onMark: Boolean)
fun jumped(from: DocPosition, fromOnMark: Boolean)
fun useMark(here: DocPosition, onMark: Boolean): DocPosition? = null
fun useOther(here: DocPosition, onMark: Boolean): DocPosition? = null
fun clear()
fun manualTurn(): Boolean = false
fun hideChip()
fun restorePinned(pos: DocPosition)
fun reparsed(p: DocPosition?)
internal object ReturnMarkCodec
class Mark(val pos: DocPosition, val fraction: Float, val sig: String?)
fun encode(pos: DocPosition, fraction: Float, sig: String?): String
fun decode(text: String?): Mark? = null
```


### `reader/LightController.kt` — RU

```kotlin
internal interface LightHost
fun saveApp(a: AppSettings)
fun setPageBrightnessSwipe(on: Boolean)
fun showChrome()
internal class LightController(private val host: LightHost)
fun attach(chrome: ReaderChrome)
fun onCreate()
fun afterFirstPage()
fun onAppSettingsApplied()
fun onResume()
fun onPause()
fun onDestroy(finishing: Boolean)
fun markOwnLaunch()
fun currentPos(): Float = host.app.brightness
fun onDrag(pos: Float, done: Boolean)
fun onAuto()
fun onSwipeSwitch(on: Boolean)
fun onAnswer(yes: Boolean)
fun onDeviceSwitch(on: Boolean)
fun onOpenPanel()
fun bind()
```


### `reader/LightCurve.kt` — RU

```kotlin
internal object LightCurve
const val LEVEL_MIN=1; const val LEVEL_MAX=255
fun out(pos: Float): Float
fun pos(out: Float): Float
fun level(out: Float, min: Int=LEVEL_MIN, max: Int=LEVEL_MAX): Int
fun fraction(level: Int, min: Int=LEVEL_MIN, max: Int=LEVEL_MAX): Float
fun isExternal(value: Int, ours: Int, sinceOurWriteMs: Long, queued: Boolean, echoMs: Long=1500L): Boolean
```


### `reader/DeviceLight.kt` — RU

```kotlin
internal object DeviceLight
const val VERDICT_UNKNOWN=0; const val VERDICT_WINDOW=1; const val VERDICT_DEVICE=2; const val VERDICT_NONE=3
const val MIN_GAP_MS=100L; const val ECHO_MS=1500L
@Volatile var noPermission=false; @Volatile var deviceOut=-1f
fun init(ctx: Context)
fun set(out: Float)
fun restore()
fun restoreIfStale(ctx: Context)
fun refresh()
fun verdict(ctx: Context): Int = VERDICT_UNKNOWN
fun setVerdict(ctx: Context, v: Int)
fun asks(ctx: Context): Int = 0
fun countAsk(ctx: Context)
fun looksEink(ctx: Context): Boolean = DeviceClass.cached(ctx) ?: DeviceClass.probe(ctx)
```


### `reader/LightProbe.kt` — RU

```kotlin
internal object LightProbe
fun lightKeys(ctx: Context): Map<String,String> = emptyMap()
fun hasWarm(keys: Map<String,String>): Boolean = false
fun coldNode(): File? = null
fun warmNode(): File? = null
fun read(file: File?): Int = -1
fun report(ctx: Context): List<String> = emptyList()
```


### `reader/ChromeMath.kt` — RU

```kotlin
internal object ChromeMath
const val HISTORY_ROW_DP = 48                     // 2026-10-05 (a 48 dp target like the bars' other controls)
const val SHOW_MS = 180L; const val HIDE_MS = 150L; const val SLIDE_DP = 12
fun labelMaxWidth(rowW: Int, density: Float): Int
fun stripShort(left: Float, right: Float, rowW: Float): Boolean   // 2026-10-05: a side label wider than its third
fun bookmarkFits(rowW: Int, density: Float): Boolean
fun animates(motion: Boolean, durationScale: Float): Boolean      // motion && scale > 0
```


### `reader/ChromeBar.kt` — RU (2026-10-05)

```kotlin
internal class ChromeBar(ctx: Context, private val edgeAtTop: Boolean) : LinearLayout(ctx)
var inert: Boolean                                // a new touch passes to the page while the bar fades out
var panelFrom: Int                                // first child on the panel (the bottom bar's history row is 0,
                                                  // its top margin −edgeArea: it starts over the edge padding)
val edgeArea: Int                                 // 4 dp shadow band, 1 px line, or 0; the owner pads the bar by it
fun setLook(look: ChromePalette): Boolean         // true when edgeArea changed
internal fun Context.chromeIconBackground(look: ChromePalette, active: Boolean): Drawable?
internal fun Context.chromePressed(look: ChromePalette, radiusDp: Float): Drawable?
internal fun animatorScale(): Float               // getDurationScale() from API 33, else areAnimatorsEnabled() 1 / 0
```


### `reader/ReaderChrome.kt` — RU

```kotlin
internal class ReaderChrome(private val ctx: Context, private val actions: Actions, returnDock: View, private val light: LightController)
interface Actions
fun onBack()
fun onTts()
fun onSearch()
fun onToc()
fun onSettings(anchor: View)
fun onMore(anchor: View)
fun onPageLabel()
fun onChapter(next: Boolean)
fun onRotation()
fun onRotationChooser()
fun onBookmark()
fun onPinHere()
fun onSeekStart()
fun onSeekPreview(progress: Int): String
fun onSeekDone(progress: Int)
fun attach(root: FrameLayout)
fun setVisible(visible: Boolean)
fun owns(v: View): Boolean = v === top || v === bottom || v === seekInfo
fun setInsets(left: Int, topInset: Int, right: Int, bottomInset: Int)
fun setTitle(text: CharSequence)
fun setPage(label: String, max: Int, progress: Int)
fun setBookmarked(on: Boolean)
fun setPinned(on: Boolean, onMarkPage: Boolean)
fun setRotationLocked(locked: Boolean)
fun setBrightness(value: Float, auto: Boolean)
fun setBrightnessCollapsed(collapsed: Boolean)
fun setBrightnessOptionsOpen(open: Boolean)
fun setSwipeOption(on: Boolean, enabled: Boolean, subtitle: String)
fun setLightAsk(kind: Int)
fun setLightDevice(on: Boolean, subtitle: String, enabled: Boolean)
fun setBrightnessUnavailable(unavailable: Boolean)
fun setLightPanelRow(visible: Boolean, subtitle: String)
fun setLook(page: PagePalette, eink: Boolean?)    // 2026-10-05: stored while hidden, applied when the bars show
val top: ChromeBar; val bottom: ChromeBar         // 2026-10-05: were LinearLayout (ChromeBar is one)
```


### `data/AutoBackup.kt` — DA-C

```kotlin
object AutoBackup
const val RELATIVE_DIR = "Download/ReaderaPlus/backup/"
const val AUTO_PREFIX = "readeraplus-auto-"
const val MANUAL_PREFIX = "readeraplus-backup-"
const val MIN_INTERVAL_MS = 20L * 3_600_000
const val KEEP_OWN = 2
const val PREF_CHECKED_AT = "backupAuto.checkedAt"
const val PREF_WRITTEN_AT = "backupAuto.writtenAt"
const val PREF_HASH = "backupAuto.hash"
const val PREF_SUMMARY = "backupAuto.summary"
const val PREF_OWNER = "backupAuto.owner"
enum class Outcome
class Summary(val books: Int, val read: Int, val bookmarks: Int, val quotes: Int)
class Candidate(val file: File?, val uri: Uri?, val createdAt: Long, val auto: Boolean, val installId8: String?,
    val summary: Summary)
fun isDue(now: Long, checkedAt: Long): Boolean
fun wouldEmpty(last: Summary?, now: Summary): Boolean
fun isBlank(now: Summary, settingsAreDefault: Boolean): Boolean
fun pickDefault(cands: List<Candidate>): Candidate? = null
fun toRotate(ownNames: List<String>): List<String> = emptyList()
fun autoName(id8: String, millis: Long, tz: TimeZone = TimeZone.getDefault()): String
fun parseAutoName(name: String): Pair<String, Long>? = null
fun schedule(context: Context, delayMs: Long, busy: () -> Boolean)
fun cancelScheduled()
fun runNow(context: Context, force: Boolean, busy: () -> Boolean): Outcome = Outcome.DISABLED
fun findCandidates(context: Context): List<Candidate> = emptyList()
fun restore(context: Context, c: Candidate): Int = 0
fun deleteFiles(context: Context, others: Boolean): Int = 0
fun lastWrittenAt(context: Context): Long = 0L
fun locationLabel(): String
```


### `data/InstallState.kt` — DA-C

```kotlin
object InstallState
const val KEY_INSTALL_ID="installId"; const val KEY_INSTALLED_AT="installId.at"; const val KEY_OFFER="restoreOffer.state"
fun ensure(context: Context)
fun verify(context: Context)
fun installId(context: Context): String = ""
fun id8(context: Context): String = ""
fun offerPending(context: Context): Boolean = false
fun settleOffer(context: Context)
```


### `data/BookPrefs.kt` — DA-C

```kotlin
data class TxtOverride(
    val blankLines: Int? = null,
    val stripIndent: Boolean? = null,
    val joinWrapped: Int? = null,
    val detectChapters: Boolean? = null,
    val chapterRegex: String? = null,
    val emphasizeHeadings: Boolean? = null,
    val replaceRules: String? = null,
    )
fun toJson(): String
fun fromJson(text: String?): TxtOverride?
data class FinishedBook(val bookId: Long, val finishedAt: Long)
object BookPrefs
fun returnMark(bookId: Long): String? = null
fun setReturnMark(bookId: Long, value: String?)
fun txtOverride(bookId: Long): TxtOverride?
fun setTxtOverride(bookId: Long, o: TxtOverride?)
fun finishedAt(bookId: Long): Long =
fun setFinishedAt(bookId: Long, t: Long)
fun finishedBetween(fromMs: Long, toMs: Long): List<FinishedBook>
fun setEpisodeLabel(bookId: Long, label: String?)
internal fun overrideJson(o: TxtOverride?): String? = if (o == null || o.isEmpty) null else o.toJson()
```


### `data/Library.kt` — DA-C

```kotlin
object Library
@Volatile var notesGen: Long = 0; private set
fun updateQuoteStyle(id: Long, style: Int)
fun setQuoteStyles(ids: Collection<Long>, style: Int)
fun deleteQuotes(ids: Collection<Long>)
fun deleteBookmarks(ids: Collection<Long>)
fun clearReviews(bookIds: Collection<Long>)
fun fillNotePlaces(bookId: Long, quotes: Map<Long, NotePlace>, bookmarks: Map<Long, NotePlace>)
fun init(context: Context)
internal fun db(): SQLiteDatabase =
internal fun context(): Context? = appContext
internal fun primaryRoot(): String
internal fun normalizePath(path: String): String = DataPaths.normalize(path, primaryRoot())
fun books(query: LibraryQuery, sort: LibrarySort): List<Book>
fun groups(shelf: Shelf): List<ShelfGroup>
fun countBooks(query: LibraryQuery): Int
fun shelfCounts(): Map<Shelf, Int>
fun clearPageCounts()
fun collectionMemberIds(): Set<Long> =
fun book(id: Long): Book? = db().queryFirst(LibrarySql.SELECT_BOOK_BY_ID, args(id), BookRows::book)
fun bookByPath(path: String): Book?
fun addOrUpdateFile(file: File): Book? = addOrUpdate(file, explicit = true)
internal fun addOrUpdate(file: File, explicit: Boolean): Book?
internal fun readMeta(f: File): MetaInfo
internal fun writeFile(db: SQLiteDatabase, info: FileInfo, meta: MetaInfo, existingId: Long?): Long
internal fun moveFile(db: SQLiteDatabase, id: Long, info: FileInfo, meta: MetaInfo?)
fun savePosition(bookId: Long, section: Int, offset: Int, progress: Float)
fun addReadingTime(bookId: Long, seconds: Long)
fun setFavorite(bookId: Long, value: Boolean)
fun setToRead(bookId: Long, value: Boolean)
fun setHaveRead(bookId: Long, value: Boolean)
internal fun clearFinished(db: SQLiteDatabase, bookId: Long)
fun setTrashed(bookId: Long, value: Boolean)
fun setHaveRead(ids: Collection<Long>, value: Boolean)
fun setToRead(ids: Collection<Long>, value: Boolean)
fun addToCollection(ids: Collection<Long>, collectionId: Long)
fun trash(ids: Collection<Long>)
fun setReview(bookId: Long, text: String)
fun setEncoding(bookId: Long, encoding: String)
fun updateMeta(bookId: Long, title: String, author: String, series: String?, seriesIndex: Float?)
fun resetProgress(bookId: Long)
fun remove(bookId: Long, deleteFile: Boolean)
fun emptyTrash(deleteFiles: Boolean)
fun lastOpened(): Book? = db().queryFirst(LibrarySql.SELECT_LAST_OPENED, null, BookRows::book)
internal fun deleteBookRows(db: SQLiteDatabase, bookId: Long)
internal fun invalidateCover(bookId: Long)
internal fun count(): Int = db().queryFirst(LibrarySql.COUNT_LIBRARY, null)
fun bookmarks(bookId: Long): List<Bookmark> =
fun addBookmark(bookId: Long, section: Int, offset: Int, snippet: String, place: NotePlace? = null): Bookmark
fun deleteBookmark(id: Long)
fun updateBookmarkNote(id: Long, note: String)
fun quotes(bookId: Long): List<Quote> = db().queryList(LibrarySql.SELECT_QUOTES, args(bookId), BookRows::quote)
fun addQuote(bookId: Long, section: Int, start: Int, end: Int, text: String, note: String = "", style: Int = 0, place: NotePlace? = null): Quote
fun deleteQuote(id: Long)
fun updateQuoteNote(id: Long, note: String)
fun collections(): List<BookCollection> =
fun createCollection(name: String): BookCollection
internal fun ensureCollection(db: SQLiteDatabase, name: String, createdAt: Long): Long
fun renameCollection(id: Long, name: String)
fun deleteCollection(id: Long)
fun collectionsOf(bookId: Long): Set<Long> =
fun setInCollection(bookId: Long, collectionId: Long, member: Boolean)
internal fun collectionName(name: String): String = MetaInfo.clean(name, MAX_COLLECTION_NAME)
internal fun collectionKey(name: String): String = MetaInfo.asciiLower(collectionName(name))
fun pageCounts(bookId: Long, layoutKey: String): IntArray?
fun savePageCounts(bookId: Long, layoutKey: String, counts: IntArray)
internal fun cap(s: String, max: Int): String = MetaInfo.truncate(s, max)
```


### `data/Notes.kt` — DA-N

```kotlin
object Notes
const val PAGE_ROWS = 50; const val BODY_CHARS = 600; const val NOTE_CHARS = 400
fun counts(q: NotesQuery): NotesCounts = NotesCounts(0,0,0,0,0)
fun styleCounts(q: NotesQuery): IntArray = IntArray(DataLimits.QUOTE_STYLE_MAX+1)
fun books(q: NotesQuery): List<NoteBook> = emptyList()
fun page(q: NotesQuery, index: Int, books: List<NoteBook>?): NotesPage = NotesPage(index,emptyList(),null,BooleanArray(0))
fun refs(q: NotesQuery): LongArray = LongArray(0)
fun fullText(ref: NoteRef): Pair<String, String>? = null
fun drawerCounts(): IntArray = IntArray(2)
fun countForBooks(bookIds: Collection<Long>): Int = 0
fun export(refs: LongArray?, q: NotesQuery, format: NotesExport.Format, out: java.io.Writer, now: Long): Int = 0
class NotesPage(val index: Int, val rows: List<NoteRow>, val before: NoteRow?, val firstOfBook: BooleanArray)
internal object BookSpans
fun locate(prefix: IntArray, row: Int): Long
```


### `data/Lookups.kt` — DA-N

```kotlin
object Lookups
const val VIA_APP=0; const val VIA_WEB=1; const val VIA_WEB_FALLBACK=2; const val DEDUPE_MS=10*60_000L
fun record(bookId: Long, word: String, section: Int, start: Int, end: Int, context: String,
    place: NotePlace?, via: Int, app: String, now: Long=System.currentTimeMillis()): Long = -1
fun setNote(id: Long, note: String)
fun delete(ids: Collection<Long>)
fun clearAll()
fun count(): Int = 0
object LookupWords
```


### `data/NotesExport.kt` — DA-N

```kotlin
object NotesExport
enum class Format(val ext: String,val mime: String)
fun write(rows: Sequence<NoteRow>, books: List<NoteBook>, q: NotesQuery, format: Format, out: java.io.Writer, now: Long): Int
```


### `data/DebugSeed.kt` — DA-N

```kotlin
object DebugSeed
```


### `reader/extras/QuoteSwatch.kt` — EX-S

```kotlin
class QuoteSwatch(context: Context, style: Int, sizeDp: Int, ink: Boolean) : View(context)
```


### `reader/extras/QuotePalette.kt` — EX-S

```kotlin
object QuotePalette
fun show(anchor: View, current: Int?, onPick: (Int)->Unit): PopupWindow? = null
```


### `ui/kit/InkTouch.kt` — LIB

```kotlin
object FastScrollGuard
const val GRAB_DP = 12f
const val ZONE_DP = 56f
fun shieldedX(x: Float, width: Int, density: Float, insetEndPx: Int = 0): Float
object TapSlop
fun px(touchSlop: Int, density: Float): Float = maxOf(touchSlop * 2f, 20f * density)
fun releaseToList(dx: Float, dy: Float, slop: Float): Boolean = abs(dy) > slop && abs(dy) >= abs(dx)
open class InkListView(context: Context) : ListView(context)
open class InkGridView(context: Context) : GridView(context)
class PageDrag(private val slop: Float, private val axisBoth: Boolean=false)
fun down(x: Float,y: Float)
fun move(x: Float,y: Float): Boolean
fun up(x: Float,y: Float): Int
fun cancel()
object PageFit
fun fit(listH: Int,minRowH: Int): Pair<Int,Int>
object ListPaging
class ListPager(val list: AbsListView,val bar: InkPagerBar,private val cols: Int=1)
fun page(dir: Int): Boolean
fun showRow(index: Int)
fun openNumPad()
fun update()
class CardButton(context: Context, iconRes: Int, description: String, onClick: (View) -> Unit) : ImageButton(context)
```


### `ui/notes/NotesActivity.kt` — NOTES

```kotlin
class NotesActivity : Activity()
const val EXTRA_TAB="notes_tab"; const val EXTRA_BOOK_ID="notes_book"
fun open(ctx: Context, tab: NotesTab?=null, bookId: Long=-1L)
```


## H1–H4 retained contracts

| API | Contract / owner | Users / thread |
|---|---|---|
| `ResumeState.init(app)`, `Pending(bookId,tries)`, `pending()`, `opened(id)`, `paused()`, `noteAttempt()`, `clear()`; `MAX_TRIES=2`, `activitiesCreated` | per-process activity count and resume marker; RC-A | App, reader, library; main; marker writes after first page |
| `ReaderRestore.start(saved,bookId,lastReadAt,remapped)` | a newer/remapped DB position wins; RC-A | book open; pure |
| `LibraryText.StartMode { LIBRARY, OPEN_LAST, RESUME }`, `startMode(…)` | root restart/restore can resume, deliberate library navigation cannot; LIB | library onCreate; pure |
| `InsetsGate.offer(insets,settled,forced)`, `settle(current)`, `hasPending` | first forced insets immediate; dialog bars delayed until focus settles; RC-A | Activity listener/focus; main, pure gate |
| `VolumeMode { OFF, DOWN_NEXT, UP_NEXT }`, `KeyMap.volumeMode/withVolumeMode/volumeBound/normalizeVolume` | volume page bindings fold into direction, other actions stay explicit; SET/RC-A | settings and live reader key mapping; pure |
| `FastScrollGuard.shieldedX(x,width,density,insetEndPx=0)`, `TapSlop.px/releaseToList` | platform scroller receives only its 12 dp strip; LIB | InkList/Grid/CardButton; pure |

## Intent and lifecycle contract

`ReaderActivity.open(context, bookId, jump: ReaderJump? = null)` uses `book_id` and the six `jump_*` extras.
`NotesActivity.open(context, tab: NotesTab? = null, bookId: Long = -1L)` uses `notes_tab` and `notes_book`.
Saved Activity keys: `rp.book`, `rp.section`, `rp.offset`, `rp.at`; RC-A adds `rp.peek`.

Reader lifecycle order is PLAN §1.6.2. Restored state wins over a note jump, then TXT fraction remap, then DB position.
Normal/resume opens use an anchored generation. A note jump uses natural pagination and highlights only a matching
anchor. Return-mark IO, device probe, note-place backfill and auto-backup remain after the first page.

## RC-A integration additions (2026-10-04)

- `NoteJumpHost.openNote(ReaderJump)` (main): the contents dialog uses the reader note-resolution/anchor-search path for a moved quote.
- `PageThumbsHost.thumbnailsShown` (main, default true): false in scroll mode, hiding both the fourth tab and overflow entry.
- `ReturnHost.clampPosition(DocPosition)` (main): clamps restored/reparsed pins against the current section count and cached character lengths, without IO.
- `PageView.afterFirstFrame: Runnable?`: one-shot, posted after a successful body draw. ReaderActivity schedules afterOpen and deferred TXT-position persistence through it, guarded by session identity.
