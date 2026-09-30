package com.ggumtak.readeraplus.settings

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
    val marginLeftDp: Int = 18,
    val marginRightDp: Int = 18,
    val marginTopDp: Int = 16,
    val marginBottomDp: Int = 16,
    /** ReadEra's "페이지 여백" switch: false = use tiny margins. */
    val pageMargins: Boolean = true,
    /** White-on-black (only other color scheme; default black on white). */
    val invert: Boolean = false,
    /** Status line at the top: chapter title. */
    val showHeader: Boolean = true,
    /** Status line at the bottom: page / progress / clock / battery. */
    val showFooter: Boolean = true,
    /** Book page "12 / 3259". */
    val footerPage: Boolean = true,
    /** Pages left in the current chapter. */
    val footerChapterLeft: Boolean = false,
    val footerPercent: Boolean = true,
    val footerClock: Boolean = true,
    val footerBattery: Boolean = true,
    val statusFontSizeSp: Float = 11f,
    val widowOrphanControl: Boolean = true,
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
    }
}

/**
 * One-tap typography presets (only typography fields change; margins, status bar and parse options stay).
 * The defaults of [ReaderSettings] are [MARU].
 */
enum class StylePreset(val label: String, val description: String) {
    MARU("마루뷰어풍", "나눔명조 · 왼쪽 정렬 · 어절 줄바꿈 · 넓은 줄/문단 간격 · 들여쓰기 없음"),
    RIDI("리디풍", "리디바탕 · 양쪽 정렬 · 글자 줄바꿈 · 1em 들여쓰기"),
    BOOK("종이책풍", "나눔명조 · 양쪽 정렬 · 글자 줄바꿈 · 문단 간격 없이 들여쓰기");

    fun applyTo(s: ReaderSettings): ReaderSettings = when (this) {
        MARU -> s.copy(
            fontId = "nanummyeongjo", fontWeight = 500, lineHeightPct = 200, paragraphSpacingPct = 100,
            indentPct = 0, letterSpacingPm = 0, align = Align.LEFT, lineBreak = LineBreakMode.WORD,
        )
        RIDI -> s.copy(
            fontId = "ridibatang", fontWeight = 400, lineHeightPct = 170, paragraphSpacingPct = 50,
            indentPct = 100, letterSpacingPm = 0, align = Align.JUSTIFY, lineBreak = LineBreakMode.CHAR,
        )
        BOOK -> s.copy(
            fontId = "nanummyeongjo", fontWeight = 450, lineHeightPct = 170, paragraphSpacingPct = 0,
            indentPct = 100, letterSpacingPm = 0, align = Align.JUSTIFY, lineBreak = LineBreakMode.CHAR,
        )
    }

    /** True when [s] currently matches this preset's typography. */
    fun matches(s: ReaderSettings): Boolean = applyTo(s) == s
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

enum class TapAction(val label: String) {
    NONE("없음"),
    NEXT("다음 페이지"),
    PREV("이전 페이지"),
    MENU("메뉴"),
    BOOKMARK("북마크"),
    TOC("목차"),
    SEARCH("검색"),
    SETTINGS("읽기 설정"),
    TTS("TTS 읽기"),
    NEXT_CHAPTER("다음 챕터"),
    PREV_CHAPTER("이전 챕터"),
    REFRESH("화면 새로고침"),
    INVERT("흑백 반전"),
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
    /** ReadEra's pin: keep the reader menu bars visible while page taps still turn pages. */
    val pinChrome: Boolean = false,
    val swipeToTurn: Boolean = true,
    /** Swipe also vertically (up = next). */
    val verticalSwipe: Boolean = false,
    val volumeKeysTurn: Boolean = true,
    val invertVolumeKeys: Boolean = false,
    /** Extra key codes assigned via "키 지정" (learned from the device's own page keys / remotes). */
    val nextPageKeys: Set<Int> = emptySet(),
    val prevPageKeys: Set<Int> = emptySet(),
    val longPressSelect: Boolean = true,
    /** Tap top-right corner toggles a bookmark. */
    val bookmarkByTouch: Boolean = true,
    /** Tap top-left corner toggles invert (ReadEra "터치로 주-야간 변경"). */
    val invertByTouch: Boolean = false,
    val fullscreen: Boolean = true,
    /** Keep screen on while reading (+10 min over the system timeout, like ReadEra). */
    val keepScreenOn: Boolean = true,
    val brightnessSwipe: Boolean = false,
    val openLastOnStart: Boolean = false,
    /** E-ink: flash a full refresh every N page turns (0 = never). */
    val einkRefreshEvery: Int = 0,
    /**
     * Vendor e-ink waveform for the page view: [EINK_MODE_SYSTEM] leaves the device's own per-app setting alone
     * (what ReadEra gets); otherwise one of the Bigme xrz modes (177 HD, 180 REGAL, 179 FAST, 178 NORMAL).
     */
    val einkMode: Int = EINK_MODE_SYSTEM,
    val einkRefreshOnChapter: Boolean = false,
    /** Auto page turn interval in seconds (0 = off; toggled from the reader menu). */
    val autoTurnSeconds: Int = 30,
    val ttsRate: Float = 1f,
    val ttsPitch: Float = 1f,
    val ttsSleepMinutes: Int = 0,
    /** Web search URL template, %s = query (URL-encoded). */
    val webSearchUrl: String = "https://www.google.com/search?q=%s",
    /** Library */
    val librarySort: LibrarySort = LibrarySort.RECENT,
    val libraryListMode: LibraryListMode = LibraryListMode.LIST,
    /** Folders scanned for books. Empty = primary external storage root. */
    val scanFolders: Set<String> = emptySet(),
    val excludedFolders: Set<String> = emptySet(),
    val orientationLock: Int = -1,
    /** Window brightness 0..1, or -1 = system. */
    val brightness: Float = -1f,
)

const val EINK_MODE_SYSTEM = 0
const val EINK_MODE_HD = 177
const val EINK_MODE_REGAL = 180
const val EINK_MODE_FAST = 179
const val EINK_MODE_NORMAL = 178

enum class LibrarySort(val label: String) {
    RECENT("최근 읽은 순"),
    TITLE("제목"),
    AUTHOR("작가"),
    ADDED("추가한 날짜"),
    SIZE("파일 크기"),
    PROGRESS("진행률"),
}

enum class LibraryListMode(val label: String) { LIST("목록"), GRID("표지") }
