package com.ggumtak.readeraplus.reader

import com.ggumtak.readeraplus.engine.PageBreakMode
import com.ggumtak.readeraplus.engine.LayoutConfig
import com.ggumtak.readeraplus.format.BookFormat
import com.ggumtak.readeraplus.format.ParseOptions
import com.ggumtak.readeraplus.format.epub.EpubPlanCache
import com.ggumtak.readeraplus.format.txt.TxtDocuments
import com.ggumtak.readeraplus.render.FontMath
import com.ggumtak.readeraplus.settings.ReaderSettings
import com.ggumtak.readeraplus.settings.StatusBands
import java.security.MessageDigest

/** Placement of the page content box inside the page view (px). */
data class PageGeometry(
    val viewWidth: Int,
    val viewHeight: Int,
    val contentLeft: Int,
    val contentTop: Int,
    val contentWidth: Int,
    val contentHeight: Int,
    /**
     * Px at the view's top that a display cutout covers ([LayoutKeys.geometry]'s extraTop; the S25's camera band in
     * fullscreen), 0 without one. The header's band is reserved below it (only paper is drawn there), the header itself
     * is drawn inside it at the very top as MaruViewer draws it (`StatusFit.headerBaseline`), the bookmark ribbon hangs
     * from the view's top over it (`RibbonMath`), and a thumbnail leaves it out.
     */
    val cutoutTop: Int = 0,
    /**
     * Pages across the view: 1, or 2 for a landscape spread ([LayoutKeys.columnsFor]). Then [contentWidth] is ONE page's
     * column (the width the sections are typeset at), [contentLeft] the left column's x, and the right column starts a
     * [gutter] after the left one ([columnLeft]); the status bands, the progress line and the header still span the view.
     */
    val columns: Int = 1,
    /** Px between two columns (0 with one). */
    val gutter: Int = 0,
    /**
     * Width of the view a single page would have with these margins ([viewWidth] with one column; a spread's left
     * margin, one column and the right margin): what a thumbnail of one page shows ([PageThumbs]).
     */
    val pageWidth: Int = viewWidth,
) {
    /** Left edge (px) of column [i] (0 = left page). */
    fun columnLeft(i: Int): Int = contentLeft + i * (contentWidth + gutter)

    /** Width of all columns and the gutters between them: the text box of a single page of the same view. */
    val spanWidth: Int get() = columns * contentWidth + (columns - 1) * gutter
}

/** Pure derivation of page geometry, LayoutConfig and the page-count cache key from settings (unit-tested). */
object LayoutKeys {
    /**
     * Bump when the geometry rules ([geometry], [config]) or the key composition change so cached page counts are
     * recomputed (the typesetter's own output is [ALGO_VERSION]).
     * 2: keys are per format (the other format's parse options no longer count) and use the layout weight class.
     * 3: [ALGO_VERSION] replaces the app's version code, and [keyFor] adds the format's parse version (A2).
     * The parse version ([parseVersionOf]) covers the TXT parse and the EPUB section split only: a change to what the
     * EPUB content parser makes of an item's XHTML (text, block styles) has no version of its own, so bump this then.
     * 4: the text box is a whole number of body lines tall, the rest split above and below it ([geometry] with emPx).
     */
    const val VERSION = 4

    /**
     * Version of the line-breaking output: bump exactly when `TypesetPass` (or the measurer) can produce different
     * lines for the same input, and only then, so an app update keeps every cached page count (A2). The typesetter
     * half is enforced by `LayoutGoldenTest` (test/.../engine), which hashes the layout of a fixed corpus and compares
     * it with [GOLDEN_HASH]: update both together. Measurer changes (FontManager, AndroidTextMeasurer, the synthetic
     * stroke's advances or line metrics) are not covered by that test: whoever makes one bumps this by hand, unless the
     * change only touches some fonts and their part of the key says so instead: the 2026-10-05 blank-glyph repairs
     * (한자 빈칸: a blank 聖 was 0.95 em in 나눔명조 OTF, an empty Hangul syllable as wide as 가 in 마루 부리 / SUIT /
     * 바른바탕) change the widths of the repaired fonts only, so the font identity carries `FontManager.layoutTag`
     * (`|hg<rules>:<files>`, "" without a repair) and 나눔명조's own key changed with its file (OTF → TTF), while the
     * system faces, Pretendard and every other font without blank glyphs keep their counts.
     * 2 (2026-10-05, 마루뷰어만큼 선명하게): the body paints are hinted (`CrispText`: no LINEAR_TEXT_FLAG) at a whole-px
     * size, so every font measures whole-px hinted advances (나눔명조 at 17 sp on the S25: a Hangul syllable 45 px, not
     * 45.43; a space 14, not 14.33) and its metrics at that size. Every cached page count is counted once again; an open
     * book keeps its first character (the reopen is an anchored layout).
     * 3 (2026-10-06): a TXT chapter heading opens a page (TxtParagraphs), so TXT books' cached counts are counted again.
     */
    const val ALGO_VERSION = 3

    /** Hash of `LayoutGoldenTest`'s layouts at [ALGO_VERSION]; see there. */
    const val GOLDEN_HASH = "071717a86d158ac8"
    const val GOLDEN_HASH_PARAGRAPH = "c9982a735d4822a9"
    private val DEFAULTS = ReaderSettings()
    /** Margin used when the "페이지 여백" switch is off. */
    const val TINY_MARGIN_DP = 4
    /** The space between the two pages of a landscape spread is at least this wide (twice the side margin otherwise). */
    const val MIN_GUTTER_DP = 24

    /**
     * Pages across the view: 2 for a paged view wider than tall whose [landscapePages] is 2, else 1. The scroll mode
     * ([paged] false) and portrait never split the width. [geometry] still falls back to 1 when a column would be too narrow.
     */
    fun columnsFor(landscapePages: Int, viewW: Int, viewH: Int, paged: Boolean): Int =
        if (paged && landscapePages == 2 && viewW > viewH) 2 else 1

    /**
     * The content box of a [viewW] × [viewH] page view. From the top: [extraTop], px that a display cutout covers
     * (fullscreen, system bars hidden: the S25's camera band; 0 elsewhere, the Comet has none), left out like a system
     * bar; the header's band ([StatusBands]), which a cutout's band contains (the header is drawn inside it); the top
     * margin; the text box; the bottom margin; the footer's band (footer items and the progress line) at the view's
     * bottom. The 위·아래 여백 count from the bands since 2026-10-05 (user: "위 여백은 위 아래 애들을 제외하고 본문영역에서만
     * 계산해야지"); band + margin is rounded once, so without a cutout the default box is where 40 dp from the top put it
     * (Comet row 80); under the S25's 87 px band it starts one 15 dp margin below it (row 129). Only settings decide the
     * bands: nothing shown or hidden on the page moves the box.
     *
     * With [emPx] (1 em of the body text in px) the box is then cut to a whole number of body lines (lineHeight × em),
     * the part of a line left over split half above, half below. Without it the lines fill the box from its top and that
     * part piled up at the bottom: raising 위·아래 여백 together moved the text down at once while its bottom stayed put
     * until a whole line dropped (user, 2026-10-09: "위에만 여백이 늘어나서 아래로 내려오는 느낌"). Now a dropped line
     * takes half a line from each side. A line is lineHeight × em or, when larger, [naturalLinePx] (the body font's
     * ascent + descent, 0 when unknown), as the engine makes it: at a small 줄 간격 the font's own height is the pitch.
     * Every caller that maps touches to the page must pass the same [emPx] and [naturalLinePx].
     */
    fun geometry(
        s: ReaderSettings,
        viewW: Int,
        viewH: Int,
        density: Float,
        extraTop: Int = 0,
        columns: Int = 1,
        emPx: Float = 0f,
        naturalLinePx: Float = 0f,
    ): PageGeometry {
        fun px(dp: Int): Int = Math.round(dp * density)
        fun margin(dp: Int): Int = if (s.pageMargins) dp.coerceAtLeast(0) else TINY_MARGIN_DP
        val ml = px(margin(s.marginLeftDp))
        val mr = px(margin(s.marginRightDp))
        val mt = px(StatusBands.headerDp(s) + margin(s.marginTopDp))
        val mb = px(StatusBands.footerDp(s) + margin(s.marginBottomDp))
        val minBox = Math.round(48 * density).coerceAtLeast(16)
        var w = viewW - ml - mr
        var left = ml
        if (w < minBox) {
            w = minOf(minBox, viewW).coerceAtLeast(1)
            left = ((viewW - w) / 2).coerceAtLeast(0)
        }
        // Two columns: the text box splits into two pages with a gutter of twice the side margin (at least 24 dp) between
        // them; a column that would be narrower than the minimum box leaves the view a single page.
        var cols = 1
        var gutter = 0
        var pageW = viewW
        if (columns >= 2) {
            val g = maxOf(ml + mr, px(MIN_GUTTER_DP))
            val colW = (w - g) / 2
            if (colW >= minBox) {
                cols = 2
                gutter = g
                pageW = ml + colW + mr
                w = colW
            }
        }
        val band = extraTop.coerceIn(0, viewH)
        val below = viewH - band
        // Under a display cutout the header is drawn inside the cutout's band (StatusFit.headerBaseline), so its own band
        // is not stacked below it: the text starts one top margin under the taller of the two (user, 2026-10-06: "윗여백은
        // 왤케 넓음?"; the S25 fullscreen text rose 70 px). Without a cutout: the header's band and the margin, as before.
        val topEdge = if (band > 0) maxOf(band, px(StatusBands.headerDp(s))) + px(margin(s.marginTopDp)) else mt
        var h = viewH - topEdge - mb
        var top = topEdge
        if (h < minBox) {
            h = minOf(minBox, below).coerceAtLeast(1)
            top = band + ((below - h) / 2).coerceAtLeast(0)
        } else {
            val pitch = if (emPx > 0f) maxOf(s.lineHeightPct / 100f * emPx, naturalLinePx) else 0f
            val snapped = linesBox(h, pitch)
            top += (h - snapped) / 2
            h = snapped
        }
        return PageGeometry(viewW, viewH, left, top, w, h, band, cols, gutter, pageW)
    }

    /**
     * [h] cut to the height of the most whole lines of [pitch] px it holds (rounded up to a pixel, so they still fit),
     * at least two lines; [h] itself when [pitch] is unknown or a line doesn't fit twice.
     */
    internal fun linesBox(h: Int, pitch: Float): Int {
        if (!(pitch >= 1f) || h < 2 * pitch) return h
        val lines = (h / pitch).toInt()
        return Math.ceil((lines * pitch).toDouble()).toInt().coerceIn(1, h)
    }

    /** [txt]: TXT books always honour their parser's block hints (centred scene breaks, headings). */
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

    /**
     * Rough chars per page for estimates before counting: full-width Hangul cells per line × lines per page,
     * minus ~25% for paragraph ends, spacing and word gaps.
     */
    fun charsPerPageHint(c: LayoutConfig, emPx: Float): Int {
        if (!(emPx > 0f) || c.width <= 0 || c.height <= 0) return PageCounts.DEFAULT_CHARS_PER_PAGE
        val perLine = c.width / emPx
        val lineH = emPx * (if (c.lineHeightEm > 0f) c.lineHeightEm else 1.7f)
        val lines = c.height / lineH
        return (perLine * lines * 0.75f).toInt().coerceAtLeast(20)
    }

    /**
     * Settings with every field that does NOT change the layout normalised away. The status slots, the progress line and
     * the status size count only through the bands they make ([bandsChanged]; the key through the box they leave).
     */
    private fun layoutPart(s: ReaderSettings): ReaderSettings = s.copy(
        invert = false,
        // The page width a spread makes is part of the geometry (and so of the key), and [columnsFor] decides it with the
        // view and the read mode: the setting alone changes nothing about the layout.
        landscapePages = DEFAULTS.landscapePages,
        pageTheme = DEFAULTS.pageTheme,
        headerLeft = DEFAULTS.headerLeft, headerCenter = DEFAULTS.headerCenter, headerRight = DEFAULTS.headerRight,
        footerLeft = DEFAULTS.footerLeft, footerCenter = DEFAULTS.footerCenter, footerRight = DEFAULTS.footerRight,
        progressBar = DEFAULTS.progressBar, statusFontSizeSp = DEFAULTS.statusFontSizeSp,
    )

    /**
     * [layoutPart] for a book of [format]: the other format's options are normalised away too. EPUB ignores every
     * txt* option for its layout (those that change its text, chapter detection, re-parse it: [parseOptionsFor]); a TXT layout always honours block hints (see [config]), so the epub* options are moot there.
     */
    private fun layoutPart(s: ReaderSettings, format: BookFormat): ReaderSettings {
        val base = layoutPart(s)
        if (format != BookFormat.EPUB) return base.copy(epubPublisherStyles = true, epubIgnoreBookSizes = true)
        val d = ReaderSettings()
        return base.copy(
            txtBlankLines = d.txtBlankLines,
            txtStripIndent = d.txtStripIndent,
            txtJoinWrappedLines = d.txtJoinWrappedLines,
            txtDetectChapters = d.txtDetectChapters,
            txtChapterRegex = d.txtChapterRegex,
            txtEmphasizeHeadings = d.txtEmphasizeHeadings,
            txtReplaceRules = d.txtReplaceRules,
        )
    }

    /**
     * True when going from [a] to [b] requires a new layout: anything but colours and which item a status slot shows. A
     * status band that comes, goes or changes its height (all slots of a band none ↔ some item, the progress line, the
     * status size of a band with items) moves the text box ([bandsChanged]); one item for another does not.
     */
    fun layoutChanged(a: ReaderSettings, b: ReaderSettings): Boolean = layoutPart(a) != layoutPart(b) || bandsChanged(a, b)

    /** [layoutChanged] for a book of [format]: options of the other format never force a re-layout. */
    fun layoutChanged(a: ReaderSettings, b: ReaderSettings, format: BookFormat): Boolean =
        layoutPart(a, format) != layoutPart(b, format) || bandsChanged(a, b)

    /** True when the status bands of [a] and [b] differ in height ([StatusBands]), so their text boxes do too. */
    fun bandsChanged(a: ReaderSettings, b: ReaderSettings): Boolean =
        StatusBands.headerDp(a) != StatusBands.headerDp(b) || StatusBands.footerDp(a) != StatusBands.footerDp(b)

    /** True when the document must be re-parsed. */
    fun parseChanged(a: ReaderSettings, b: ReaderSettings, encoding: String): Boolean =
        a.parseOptions(encoding) != b.parseOptions(encoding)

    /** [parseChanged] for a book of [format]: only the options that format's parser reads count. */
    fun parseChanged(a: ReaderSettings, b: ReaderSettings, format: BookFormat, encoding: String): Boolean =
        parseOptionsFor(a, format, encoding) != parseOptionsFor(b, format, encoding)

    /**
     * The parse options a book of [format] actually depends on, with every other field at its default: EPUB reads
     * epubPublisherStyles and epubIgnoreBookSizes and, for the chapters it detects when its own TOC is poor
     * (`EpubHeadings`), txtDetectChapters, txtChapterRegex and txtEmphasizeHeadings; TXT reads the txt* options and
     * the encoding (and always keeps block hints).
     */
    fun parseOptionsFor(s: ReaderSettings, format: BookFormat, encoding: String): ParseOptions =
        if (format == BookFormat.EPUB) {
            ParseOptions(
                txtDetectChapters = s.txtDetectChapters,
                txtChapterRegex = s.txtChapterRegex,
                txtEmphasizeHeadings = s.txtEmphasizeHeadings,
                epubPublisherStyles = s.epubPublisherStyles,
                epubIgnoreBookSizes = s.epubIgnoreBookSizes,
            )
        } else {
            s.parseOptions(encoding).copy(epubPublisherStyles = true, epubIgnoreBookSizes = true)
        }

    /**
     * Identity of the text coordinates (section split and char offsets) a parse of a book of [format] produces, or
     * null when saved (section, offset) positions do not depend on changeable options (EPUB: spine items are fixed).
     * TXT: the parser's own version ([TxtDocuments.PARSE_VERSION]: an update that splits sections differently remaps
     * each position once, by fraction), every option that can move text between sections or shift offsets, plus the
     * encoding; heading emphasis only styles text and is left out so toggling it never remaps a position.
     */
    fun textSignature(s: ReaderSettings, format: BookFormat, encoding: String, sizeBytes: Long): String? {
        if (format == BookFormat.EPUB) return null
        val p = s.parseOptions(encoding)
        val sb = StringBuilder(128)
        // The file's size: a book replaced at the same path (a corrected copy over Wi-Fi, a re-import) has other
        // sections, so its saved (section, offset) is found again by fraction instead of opening somewhere else.
        sb.append("t1|v").append(TxtDocuments.PARSE_VERSION).append("|s").append(sizeBytes)
            .append('|').append(p.txtBlankLines).append(',').append(p.txtStripIndent)
            .append(',').append(p.txtJoinWrappedLines).append(',').append(p.txtDetectChapters)
            .append(",enc=").append(p.txtEncoding)
            .append(",re=").append(p.txtChapterRegex.length).append(':').append(p.txtChapterRegex)
            .append(",rr=").append(p.txtReplaceRules.length).append(':').append(p.txtReplaceRules)
        return sha1Hex(sb.toString()).substring(0, 16)
    }

    /**
     * A weight value that changes only when the text layout (advances / metrics) can change. A static font keeps
     * the same typeface file for many weights and only thickens its strokes (the stroke is not part of text
     * measuring), so only its regular/bold file choice for body and bold runs matters; variable fonts ('wght'
     * axis) and system faces measure differently at every weight.
     */
    fun layoutWeight(weight: Int, variable: Boolean, system: Boolean, hasBoldFile: Boolean): Int {
        val w = FontMath.normalizeWeight(weight)
        if (variable || system) return w
        val base = FontMath.effectiveBase(w, FontMath.minWeight(variable = false, system = false))
        val bodyBold = FontMath.usesBoldFile(base, hasBoldFile)
        val runsBold = FontMath.usesBoldFile(FontMath.runWeight(base, true), hasBoldFile)
        return when {
            bodyBold -> -3
            runsBold -> -2
            else -> -1
        }
    }

    /**
     * [key] for a book of [format]: only the options that format depends on are part of it, so changing a TXT
     * option keeps every EPUB's cached counts valid and vice versa. The format's [parseVersion] is part of it too:
     * a parser update can change a section's text but keep the section count, and `PageCounts.setKnown` checks only
     * the length.
     */
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

    /**
     * Version of the sections a parse of a book of [format] produces: TXT [TxtDocuments.PARSE_VERSION] (at most one
     * bump per release), EPUB [EpubPlanCache.VERSION] (the section split).
     */
    fun parseVersionOf(format: BookFormat): Int =
        if (format == BookFormat.EPUB) EpubPlanCache.VERSION else TxtDocuments.PARSE_VERSION

    /**
     * Stable key for cached page counts: every layout-affecting setting, the parse options, the content box,
     * density, the font file identity and the key / layout algorithm versions ([VERSION], [ALGO_VERSION]; not the
     * app's version, so an update that leaves the layout alone keeps the counts), hashed to 24 hex chars.
     */
    fun key(
        s: ReaderSettings,
        parse: ParseOptions,
        g: PageGeometry,
        density: Float,
        fontIdentity: String,
        algoVersion: Int = ALGO_VERSION,
    ): String {
        val sb = StringBuilder(512)
        sb.append("v").append(VERSION).append("|a").append(algoVersion)
        sb.append("|font=").append(s.fontId).append('|').append(fontIdentity)
        sb.append("|size=").append(s.fontSizeSp)
        sb.append("|w=").append(s.fontWeight)
        sb.append("|lh=").append(s.lineHeightPct)
        sb.append("|ps=").append(s.paragraphSpacingPct)
        sb.append("|in=").append(s.indentPct)
        sb.append("|ls=").append(s.letterSpacingPm)
        sb.append("|al=").append(s.align.name)
        sb.append("|lb=").append(s.lineBreak.name)
        sb.append("|wo=").append(s.widowOrphanControl)
        if (s.pageBreak != PageBreakMode.LINE) sb.append("|pb=").append(s.pageBreak.name)
        sb.append("|pub=").append(s.epubPublisherStyles)
        sb.append("|box=").append(g.contentWidth).append('x').append(g.contentHeight)
        if (g.columns > 1) sb.append("|cols=").append(g.columns)
        sb.append("|d=").append(density)
        sb.append("|p=").append(parse.txtBlankLines).append(',').append(parse.txtStripIndent)
            .append(',').append(parse.txtJoinWrappedLines).append(',').append(parse.txtDetectChapters)
            .append(',').append(parse.txtEmphasizeHeadings).append(',').append(parse.epubPublisherStyles).append(',').append(parse.epubIgnoreBookSizes)
            .append(",enc=").append(parse.txtEncoding)
            .append(",re=").append(parse.txtChapterRegex.length).append(':').append(parse.txtChapterRegex)
            .append(",rr=").append(parse.txtReplaceRules.length).append(':').append(parse.txtReplaceRules)
        return sha1Hex(sb.toString()).substring(0, 24)
    }

    private fun sha1Hex(s: String): String {
        val d = MessageDigest.getInstance("SHA-1").digest(s.toByteArray(Charsets.UTF_8))
        val hex = "0123456789abcdef"
        val out = CharArray(d.size * 2)
        for (i in d.indices) {
            val v = d[i].toInt() and 0xFF
            out[i * 2] = hex[v ushr 4]
            out[i * 2 + 1] = hex[v and 0xF]
        }
        return String(out)
    }
}
