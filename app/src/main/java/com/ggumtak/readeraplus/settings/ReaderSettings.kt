package com.ggumtak.readeraplus.settings

import com.ggumtak.readeraplus.engine.PageBreakMode
import com.ggumtak.readeraplus.engine.Align
import com.ggumtak.readeraplus.engine.LineBreakMode
import com.ggumtak.readeraplus.format.ParseOptions

/**
 * Typography + page appearance. Immutable; change with copy() and persist with Settings.saveReader().
 * Units: *Pct fields are percent of 1 em (font size); margins are dp; fontSize is sp.
 */
data class ReaderSettings(
    /** FontManager font id ("ridibatang", "nanummyeongjo", "user:<file name>", ...). */
    val fontId: String = "nanummyeongjo",
    val fontSizeSp: Float = 20f,
    /** 100..900; 400 = regular. Static fonts get synthetic emboldening above 400; variable fonts use wght. */
    val fontWeight: Int = 500,
    /** Line height, % of em (170 = 1.7 em). */
    val lineHeightPct: Int = 200,
    /** Space between paragraphs, % of em. */
    val paragraphSpacingPct: Int = 100,
    /** First-line indent, % of em. */
    val indentPct: Int = 0,
    /** Letter spacing, per-mille of em (0 = normal). */
    val letterSpacingPm: Int = 0,
    val align: Align = Align.LEFT,
    /** CHAR = 글자 단위 like ReadEra (tight justified lines); WORD = 어절 단위 (keep-all). */
    val lineBreak: LineBreakMode = LineBreakMode.WORD,
    /** MaruViewer's side margin ([SideMargin.ZERO_DP], 2026-10-05). */
    val marginLeftDp: Int = SideMargin.ZERO_DP,
    val marginRightDp: Int = SideMargin.ZERO_DP,
    val marginTopDp: Int = 40,
    val marginBottomDp: Int = 40,
    /** ReadEra's "페이지 여백" switch: false = use tiny margins. */
    val pageMargins: Boolean = true,
    /** White on black (흑백 반전, 밤 모드). Wins over [pageTheme] while on. */
    val invert: Boolean = false,
    /** Page colours ("화면 색"; default black on white). Like [invert], a change repaints and never re-lays out. */
    val pageTheme: PageTheme = PageTheme.PAPER,
    /**
     * Status line at the top: left / centre / right. All NONE = no header band. Default: MaruViewer's line (2026-10-05):
     * battery and clock, the book title, the page ([MaruHeader] gives it to settings saved before).
     */
    val headerLeft: StatusItem = StatusItem.CLOCK_BATTERY,
    val headerCenter: StatusItem = StatusItem.BOOK_TITLE,
    val headerRight: StatusItem = StatusItem.PAGE,
    /** Status line at the bottom. All NONE = no footer band. That is the default (user request). */
    val footerLeft: StatusItem = StatusItem.NONE,
    val footerCenter: StatusItem = StatusItem.NONE,
    val footerRight: StatusItem = StatusItem.NONE,
    /** ReadEra-style reading-progress line along the bottom edge, drawn in the bottom margin ("진행 막대"). */
    val progressBar: Boolean = true,
    val statusFontSizeSp: Float = 11f,            // unchanged
    val widowOrphanControl: Boolean = true,
    val pageBreak: PageBreakMode = PageBreakMode.LINE,
    // --- parsing options (TXT / EPUB) ---
    val txtBlankLines: Int = ParseOptions.BLANK_AUTO,
    val txtStripIndent: Boolean = true,
    val txtJoinWrappedLines: Int = 1,
    val txtDetectChapters: Boolean = true,
    val txtChapterRegex: String = "",
    val txtEmphasizeHeadings: Boolean = true,
    val txtReplaceRules: String = "",
    val epubPublisherStyles: Boolean = true,
) {
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

    companion object {
        const val MIN_FONT_SP = 8f
        const val MAX_FONT_SP = 60f

        const val PROGRESS_LANE_DP = 12
    }
}

/**
 * One-tap presets: the typography and the page colours ([ReaderSettings.pageTheme]) change; font size, margins, the
 * status bar, 흑백 반전 and parse options stay. The enum names are stored nowhere but kept from the first release; only
 * the labels changed (R2: 웹소설 / 전자책 / 종이책).
 *
 * [MARU] (웹소설) is MaruViewer's page as measured on the user's screenshot (2026-10-04): 나눔명조 Regular, a 2 em
 * line pitch, one empty line between paragraphs, ragged right, no indent, on the [PageTheme.MARU] colours. The
 * defaults of [ReaderSettings] keep the earlier 웹소설 typography (weight 500, a 1 em paragraph gap) on white, so they
 * match no preset: the 스타일 row calls them "기본" (`StyleChoice.isDefault`).
 */
enum class StylePreset(val label: String, val description: String) {
    MARU("웹소설", "마루뷰어 화면 · 나눔명조"),
    RIDI("전자책", "리디바탕 · 양쪽 정렬"),
    BOOK("종이책", "나눔명조 · 들여쓰기");

    fun applyTo(s: ReaderSettings): ReaderSettings = when (this) {
        MARU -> s.copy(
            fontId = "nanummyeongjo", fontWeight = 400, lineHeightPct = 200, paragraphSpacingPct = 200,
            indentPct = 0, letterSpacingPm = 0, align = Align.LEFT, lineBreak = LineBreakMode.WORD,
            pageTheme = PageTheme.MARU,
        )
        RIDI -> s.copy(
            fontId = "ridibatang", fontWeight = 400, lineHeightPct = 170, paragraphSpacingPct = 50,
            indentPct = 100, letterSpacingPm = 0, align = Align.JUSTIFY, lineBreak = LineBreakMode.CHAR,
            pageTheme = PageTheme.PAPER,
        )
        BOOK -> s.copy(
            fontId = "nanummyeongjo", fontWeight = 450, lineHeightPct = 170, paragraphSpacingPct = 0,
            indentPct = 100, letterSpacingPm = 0, align = Align.JUSTIFY, lineBreak = LineBreakMode.CHAR,
            pageTheme = PageTheme.PAPER,
        )
    }

    /** True when [s] currently matches this preset's typography and page colours. */
    fun matches(s: ReaderSettings): Boolean = applyTo(s) == s
}

/**
 * Page colours ("화면 색", [ReaderSettings.pageTheme]); what they paint is `render/PagePalette`. 흑백 반전
 * ([ReaderSettings.invert]) wins while on. Stored by name ("r.pageTheme"; unknown or missing = [PAPER]): never rename
 * an entry, only append.
 */
enum class PageTheme(val label: String) {
    /** Black on white: the e-ink default. */
    PAPER("흰 바탕"),
    /** MaruViewer's web-novel page: light grey text with a short shadow on a dark grey page (웹소설, 2026-10-04). */
    MARU("마루뷰어"),
}

enum class TapZoneMode {
    /** Left third = previous, right two thirds = next, centre = menu (ReadEra default feel). */
    LEFT_RIGHT,
    /** Anywhere = next page (centre = menu, a narrow left edge strip = previous). */
    ALL_NEXT,
    /** Anywhere = previous page (centre = menu, a narrow right edge strip = next). */
    ALL_PREV,
    /** Top half = previous, bottom half = next, centre = menu. */
    TOP_BOTTOM,
    /** User 3x3 grid (AppSettings.customTapZones). */
    CUSTOM,
}

/**
 * What a tap zone or a key does. Stored by name (tap grid "PREV,NEXT,…", key bindings "24:NEXT"): never rename an
 * entry; add new ones at the end.
 */
enum class TapAction(val label: String) {
    /** Tap zone: nothing. Key binding: the reader leaves the key to the system (volume, …): "없음 (시스템에 맡김)". */
    NONE("없음"),
    NEXT("다음 페이지"),
    PREV("이전 페이지"),
    MENU("메뉴"),
    BOOKMARK("북마크"),
    TOC("목차"),
    SEARCH("검색"),
    SETTINGS("읽기 설정"),
    TTS("듣기"),
    NEXT_CHAPTER("다음 챕터"),
    PREV_CHAPTER("이전 챕터"),
    REFRESH("화면 새로고침"),
    INVERT("흑백 반전"),
    /** The go-to dialog (페이지 · % · 화). */
    GOTO("페이지 이동"),
    /** Toggles auto page turn. */
    AUTO_TURN("자동 넘김"),
}

/**
 * What holding a page key does ([AppSettings.keyHold]). The first page turn always happens on key-down; the hold
 * action runs once at the first auto-repeat (≈ 0.5 s) relative to the position from before that turn, then the
 * repeats are swallowed until the key is released. [REPEAT] keeps turning (paced, see T1-4).
 */
enum class KeyHold(val label: String) {
    REPEAT("계속 넘기기"),
    CHAPTER("다음·이전 챕터로"),
    TEN("10쪽씩"),
    SINGLE("한 쪽만"),
}

/** App behaviour settings. */
data class AppSettings(
    val tapZoneMode: TapZoneMode = TapZoneMode.LEFT_RIGHT,
    /** Row-major 3x3 actions used when tapZoneMode == CUSTOM. */
    val customTapZones: List<TapAction> = listOf(
        TapAction.PREV, TapAction.NEXT, TapAction.NEXT,
        TapAction.PREV, TapAction.MENU, TapAction.NEXT,
        TapAction.PREV, TapAction.NEXT, TapAction.NEXT,
    ),
    /** Swap next/previous for tap zones (keys are unaffected). */
    val invertTaps: Boolean = false,
    val swipeToTurn: Boolean = true,
    /** Swipe also vertically (up = next). */
    val verticalSwipe: Boolean = false,
    val volumeKeysTurn: Boolean = true,
    val invertVolumeKeys: Boolean = false,
    /** Extra key codes assigned via "키 지정" (learned from the device's own page keys / remotes). */
    val nextPageKeys: Set<Int> = emptySet(),
    val prevPageKeys: Set<Int> = emptySet(),
    /**
     * Key code → action ("이 키로 할 동작"). Resolution order in the reader: this map, then [nextPageKeys] /
     * [prevPageKeys], then the built-in keys. A binding on a volume key overrides [volumeKeysTurn];
     * [TapAction.NONE] hands the key back to the system. Stored as "24:NEXT,25:PREV".
     */
    val keyBindings: Map<Int, TapAction> = emptyMap(),
    /** Holding a key whose action is next / previous page (T1-4). */
    val keyHold: KeyHold = KeyHold.REPEAT,
    val longPressSelect: Boolean = true,
    /** Long-press duration (ms) for text selection: 400 / 500 / 700 / 1000 ("길게 누르기 시간"). */
    val longPressMs: Int = 500,
    /** Tap top-right corner toggles a bookmark. */
    val bookmarkByTouch: Boolean = true,
    /** Tap top-left corner toggles invert (ReadEra "터치로 주-야간 변경"). */
    val invertByTouch: Boolean = false,
    val fullscreen: Boolean = true,
    /** Keep screen on while reading (+10 min over the system timeout, like ReadEra). */
    val keepScreenOn: Boolean = true,
    val brightnessSwipe: Boolean = false,
    val openLastOnStart: Boolean = false,
    /** Reaching the end of a book marks it 다 읽음 (+ progress 1.0 and book_prefs.finished_at): "끝까지 읽으면 완독 처리". */
    val autoMarkFinished: Boolean = true,
    /** E-ink: flash a full refresh every N page turns (0 = never). */
    val einkRefreshEvery: Int = 0,
    /**
     * Vendor e-ink waveform for the page view: [EINK_MODE_SYSTEM] leaves the device's own per-app setting alone
     * (what ReadEra gets); otherwise one of the Bigme xrz modes (177 HD, 180 REGAL, 179 FAST, 178 NORMAL).
     */
    val einkMode: Int = EINK_MODE_SYSTEM,
    val einkRefreshOnChapter: Boolean = false,
    /**
     * How a full refresh is done ("새로고침 방식"): [EINK_REFRESH_AUTO] (vendor hook, black-frame fallback),
     * [EINK_REFRESH_GC16] / [EINK_REFRESH_CLEAN] (Bigme xrz only), [EINK_REFRESH_FLASH] (black frame only).
     */
    val einkRefreshMethod: Int = EINK_REFRESH_AUTO,
    /** How long the black frame of [EINK_REFRESH_FLASH] (and of the fallback) stays up: 100 / 200 / 350 ms. */
    val einkFlashMs: Int = 100,
    /**
     * Refresh cadence while the page is dark (흑백 반전, or the 마루뷰어 화면 색: `PagePalette.dark`): -1 = same as
     * [einkRefreshEvery], else every N turns.
     */
    val einkRefreshEveryNight: Int = -1,
    /** Refresh on turns to / from pages with pictures (≥ 7.5% of the page). Off by default: app flashes are opt-in. */
    val einkFlashImages: Boolean = false,
    /** Auto page turn interval in seconds (0 = off; toggled from the reader menu). */
    val autoTurnSeconds: Int = 30,
    val ttsRate: Float = 1f,
    val ttsPitch: Float = 1f,
    val ttsSleepMinutes: Int = 0,
    /** 멈춤 예약 by chapters: 0 = off, 1 = "이 챕터 끝까지", 2 = "다음 챕터 끝까지" (instead of [ttsSleepMinutes]). */
    val ttsSleepChapters: Int = 0,
    /** "읽는 문장 표시": underline the sentence being spoken (each sentence redraws the page: one e-ink update). */
    val ttsHighlight: Boolean = true,
    /** TTS voice name ([android.speech.tts.Voice.getName]); "" = the engine's default voice. */
    val ttsVoice: String = "",
    /** Web search URL template, %s = query (URL-encoded). */
    val webSearchUrl: String = "https://www.google.com/search?q=%s",
    /** Library */
    val librarySort: LibrarySort = LibrarySort.RECENT,
    val libraryListMode: LibraryListMode = LibraryListMode.LIST,
    /** Folders scanned for books. Empty = primary external storage root. */
    val scanFolders: Set<String> = emptySet(),
    val excludedFolders: Set<String> = emptySet(),
    val orientationLock: Int = -1,
    val readMode: ReadMode = ReadMode.PAGED,
    val scrollStyle: ScrollStyle = ScrollStyle.AUTO,
    val autoBackup: Boolean = true,
    /** Device-local opt-in; never travels in a backup. */
    val brightnessDevice: Boolean = false,
    val brightnessRestore: Boolean = true,
    val highlightLook: Int = HL_LOOK_AUTO,
    val listPaging: Int = LIST_PAGING_AUTO,
    val recordLookups: Boolean = true,
    /** Slider position 0..1, or -1 = system; the device path applies LightCurve. */
    val brightness: Float = -1f,
)

const val EINK_MODE_SYSTEM = 0
const val EINK_MODE_HD = 177
const val EINK_MODE_REGAL = 180
const val EINK_MODE_FAST = 179
const val EINK_MODE_NORMAL = 178

/** [AppSettings.einkRefreshMethod] values. */
const val EINK_REFRESH_AUTO = 0
const val EINK_REFRESH_GC16 = 1
const val EINK_REFRESH_CLEAN = 2
const val EINK_REFRESH_FLASH = 3

/** The library's sort orders (stored by name); ADDED lists the newest first. */
enum class LibrarySort(val label: String) {
    RECENT("최근 읽은 순"),
    TITLE("제목순"),
    AUTHOR("작가순"),
    ADDED("최근 추가순"),
    SIZE("파일 크기순"),
    PROGRESS("진행률순"),
}

/** Library views, in the order the toolbar toggle cycles through them (stored by name: the names never change). */
enum class LibraryListMode(val label: String) { LIST("자세히"), COMPACT("간단히"), GRID("큰 표지"), COVERS("작은 표지") }

/**
 * What one slot of the page's status lines shows. The header and the footer each have three slots
 * (left / centre / right). Stored by name ("r.footerLeft" = "CLOCK"): never rename or remove an entry.
 * The declaration order is the chooser order. [short] labels the popup's slot buttons (≤ 6 Hangul).
 * [example] is shown in choosers that have no live value.
 */
enum class StatusItem(val label: String, val short: String, val example: String?) {
    NONE("없음", "없음", null),
    CHAPTER("챕터 제목", "챕터 제목", "제3화 비밀"),
    BOOK_TITLE("책 제목", "책 제목", null),
    PAGE("쪽 번호", "쪽 번호", "12 / 3259"),
    CHAPTER_PAGES_LEFT("챕터 쪽 번호", "챕터 쪽", "2 / 32"),          // the name of its old meaning (pages left) stays
    PERCENT("진행률", "진행률", "34%"),
    EPISODE("회차", "회차", "123/540화"),
    TIME_LEFT_EPISODE("챕터 남은 시간", "챕터 시간", "챕터 3분"),
    TIME_LEFT_BOOK("책 남은 시간", "책 남은 시간", "책 7시간 20분"),
    CLOCK("시계", "시계", "14:05"),
    // The page draws the battery icon, then the bare number: no "%" here either.
    BATTERY("배터리", "배터리", "80"),                       // [Δ] no "▭": U+25AD is missing from some firmware fonts
    // MaruViewer's corner: the battery icon (its fill is the level, no number), then the time. The name stays (stored).
    CLOCK_BATTERY("배터리 아이콘 · 시계", "배터리·시계", "14:05");

    /** Titles are the only items shortened with "…" when their slot is narrow. Numbers never are. */
    val elastic: Boolean get() = this == CHAPTER || this == BOOK_TITLE

    /** A shortened book title keeps its end, like MaruViewer's file name ("…능을 전혀 안숨김 1-246"); a chapter its start. */
    val keepsEnd: Boolean get() = this == BOOK_TITLE
}

/** Reading modes; changing mode does not change pagination. */
enum class ReadMode(val label: String) { PAGED("페이지 넘김"), SCROLL("스크롤") }
/** Direct drag policy only. Page commands always move instantly, including SMOOTH. */
enum class ScrollStyle(val label: String) { AUTO("기기에 맞춤"), SMOOTH("손가락을 따라 이동"), STEP("손을 떼면 이동") }
const val HL_LOOK_AUTO = 0
const val HL_LOOK_COLOR = 1
const val HL_LOOK_INK = 2
const val LIST_PAGING_AUTO = 0
const val LIST_PAGING_PAGED = 1
const val LIST_PAGING_SCROLL = 2
