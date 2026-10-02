package com.ggumtak.readeraplus.reader

import com.ggumtak.readeraplus.engine.PageBreakMode
import com.ggumtak.readeraplus.engine.LayoutConfig
import com.ggumtak.readeraplus.format.BookFormat
import com.ggumtak.readeraplus.format.ParseOptions
import com.ggumtak.readeraplus.format.epub.EpubPlanCache
import com.ggumtak.readeraplus.format.txt.TxtDocuments
import com.ggumtak.readeraplus.render.FontMath
import com.ggumtak.readeraplus.settings.ReaderSettings
import java.security.MessageDigest

/** Placement of the page content box inside the page view (px). */
data class PageGeometry(
    val viewWidth: Int,
    val viewHeight: Int,
    val contentLeft: Int,
    val contentTop: Int,
    val contentWidth: Int,
    val contentHeight: Int,
)

/** Pure derivation of page geometry, LayoutConfig and the page-count cache key from settings (unit-tested). */
object LayoutKeys {
    /**
     * Bump when the geometry rules ([geometry], [config]) or the key composition change so cached page counts are
     * recomputed (the typesetter's own output is [ALGO_VERSION]).
     * 2: keys are per format (the other format's parse options no longer count) and use the layout weight class.
     * 3: [ALGO_VERSION] replaces the app's version code, and [keyFor] adds the format's parse version (A2).
     * The parse version ([parseVersionOf]) covers the TXT parse and the EPUB section split only: a change to what the
     * EPUB content parser makes of an item's XHTML (text, block styles) has no version of its own, so bump this then.
     */
    const val VERSION = 3

    /**
     * Version of the line-breaking output: bump exactly when `TypesetPass` (or the measurer) can produce different
     * lines for the same input, and only then, so an app update keeps every cached page count (A2). The typesetter
     * half is enforced by `LayoutGoldenTest` (test/.../engine), which hashes the layout of a fixed corpus and compares
     * it with [GOLDEN_HASH]: update both together. Measurer changes (FontManager, AndroidTextMeasurer, the synthetic
     * stroke's advances or line metrics) are not covered by that test: whoever makes one bumps this by hand.
     */
    const val ALGO_VERSION = 1

    /** Hash of `LayoutGoldenTest`'s layouts at [ALGO_VERSION]; see there. */
    const val GOLDEN_HASH = "071717a86d158ac8"
    const val GOLDEN_HASH_PARAGRAPH = "TBD"
    private val DEFAULTS = ReaderSettings()
    /** Margin used when the "페이지 여백" switch is off. */
    const val TINY_MARGIN_DP = 4

    fun geometry(s: ReaderSettings, viewW: Int, viewH: Int, density: Float): PageGeometry {
        fun px(dp: Int): Int = Math.round((if (s.pageMargins) dp else TINY_MARGIN_DP) * density)
        val ml = px(s.marginLeftDp.coerceAtLeast(0))
        val mr = px(s.marginRightDp.coerceAtLeast(0))
        val mt = px(s.marginTopDp.coerceAtLeast(0))
        val mb = px(s.marginBottomDp.coerceAtLeast(0))
        val minBox = Math.round(48 * density).coerceAtLeast(16)
        var w = viewW - ml - mr
        var left = ml
        if (w < minBox) {
            w = minOf(minBox, viewW).coerceAtLeast(1)
            left = ((viewW - w) / 2).coerceAtLeast(0)
        }
        var h = viewH - mt - mb
        var top = mt
        if (h < minBox) {
            h = minOf(minBox, viewH).coerceAtLeast(1)
            top = ((viewH - h) / 2).coerceAtLeast(0)
        }
        return PageGeometry(viewW, viewH, left, top, w, h)
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

    /** Settings with every field that does NOT change the layout normalised away. */
    private fun layoutPart(s: ReaderSettings): ReaderSettings = s.copy(
        invert = false,
        headerLeft = DEFAULTS.headerLeft, headerCenter = DEFAULTS.headerCenter, headerRight = DEFAULTS.headerRight,
        footerLeft = DEFAULTS.footerLeft, footerCenter = DEFAULTS.footerCenter, footerRight = DEFAULTS.footerRight,
        progressBar = DEFAULTS.progressBar, statusFontSizeSp = DEFAULTS.statusFontSizeSp,
    )

    /**
     * [layoutPart] for a book of [format]: the other format's options are normalised away too. EPUB ignores every
     * txt* option; a TXT layout always honours block hints (see [config]), so epubPublisherStyles is moot there.
     */
    private fun layoutPart(s: ReaderSettings, format: BookFormat): ReaderSettings {
        val base = layoutPart(s)
        if (format != BookFormat.EPUB) return base.copy(epubPublisherStyles = true)
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

    /** True when going from [a] to [b] requires a new layout (anything but colours / footer items). */
    fun layoutChanged(a: ReaderSettings, b: ReaderSettings): Boolean = layoutPart(a) != layoutPart(b)

    /** [layoutChanged] for a book of [format]: options of the other format never force a re-layout. */
    fun layoutChanged(a: ReaderSettings, b: ReaderSettings, format: BookFormat): Boolean =
        layoutPart(a, format) != layoutPart(b, format)

    /** True when the document must be re-parsed. */
    fun parseChanged(a: ReaderSettings, b: ReaderSettings, encoding: String): Boolean =
        a.parseOptions(encoding) != b.parseOptions(encoding)

    /** [parseChanged] for a book of [format]: only the options that format's parser reads count. */
    fun parseChanged(a: ReaderSettings, b: ReaderSettings, format: BookFormat, encoding: String): Boolean =
        parseOptionsFor(a, format, encoding) != parseOptionsFor(b, format, encoding)

    /**
     * The parse options a book of [format] actually depends on, with every other field at its default: EPUB reads
     * only epubPublisherStyles; TXT reads the txt* options and the encoding (and always keeps block hints).
     */
    fun parseOptionsFor(s: ReaderSettings, format: BookFormat, encoding: String): ParseOptions =
        if (format == BookFormat.EPUB) {
            ParseOptions(epubPublisherStyles = s.epubPublisherStyles)
        } else {
            s.parseOptions(encoding).copy(epubPublisherStyles = true)
        }

    /**
     * Identity of the text coordinates (section split and char offsets) a parse of a book of [format] produces, or
     * null when saved (section, offset) positions do not depend on changeable options (EPUB: spine items are fixed).
     * TXT: the parser's own version ([TxtDocuments.PARSE_VERSION]: an update that splits sections differently remaps
     * each position once, by fraction), every option that can move text between sections or shift offsets, plus the
     * encoding; heading emphasis only styles text and is left out so toggling it never remaps a position.
     */
    fun textSignature(s: ReaderSettings, format: BookFormat, encoding: String): String? {
        if (format == BookFormat.EPUB) return null
        val p = s.parseOptions(encoding)
        val sb = StringBuilder(128)
        sb.append("t1|v").append(TxtDocuments.PARSE_VERSION)
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
        sb.append("|d=").append(density)
        sb.append("|p=").append(parse.txtBlankLines).append(',').append(parse.txtStripIndent)
            .append(',').append(parse.txtJoinWrappedLines).append(',').append(parse.txtDetectChapters)
            .append(',').append(parse.txtEmphasizeHeadings).append(',').append(parse.epubPublisherStyles)
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
