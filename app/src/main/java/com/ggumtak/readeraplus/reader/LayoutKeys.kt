package com.ggumtak.readeraplus.reader

import com.ggumtak.readeraplus.engine.LayoutConfig
import com.ggumtak.readeraplus.format.ParseOptions
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
    /** Bump when the layout algorithm or geometry rules change so cached page counts are recomputed. */
    const val VERSION = 1
    /** Header/footer band height as a multiple of the status font size. */
    const val STATUS_BAND = 2.2f
    /** Margin used when the "페이지 여백" switch is off. */
    const val TINY_MARGIN_DP = 4

    fun geometry(s: ReaderSettings, viewW: Int, viewH: Int, density: Float, statusPx: Float): PageGeometry {
        fun px(dp: Int): Int = Math.round((if (s.pageMargins) dp else TINY_MARGIN_DP) * density)
        val ml = px(s.marginLeftDp.coerceAtLeast(0))
        val mr = px(s.marginRightDp.coerceAtLeast(0))
        val mt = px(s.marginTopDp.coerceAtLeast(0))
        val mb = px(s.marginBottomDp.coerceAtLeast(0))
        val band = Math.round(statusPx * STATUS_BAND)
        val header = if (s.showHeader) band else 0
        val footer = if (s.showFooter) band else 0
        val minBox = Math.round(48 * density).coerceAtLeast(16)
        var w = viewW - ml - mr
        var left = ml
        if (w < minBox) {
            w = minOf(minBox, viewW).coerceAtLeast(1)
            left = ((viewW - w) / 2).coerceAtLeast(0)
        }
        var h = viewH - mt - mb - header - footer
        var top = mt + header
        if (h < minBox) {
            h = minOf(minBox, viewH).coerceAtLeast(1)
            top = ((viewH - h) / 2).coerceAtLeast(0)
        }
        return PageGeometry(viewW, viewH, left, top, w, h)
    }

    fun config(s: ReaderSettings, g: PageGeometry): LayoutConfig = LayoutConfig(
        width = g.contentWidth,
        height = g.contentHeight,
        lineHeightEm = s.lineHeightPct / 100f,
        paragraphSpacingEm = s.paragraphSpacingPct / 100f,
        indentEm = s.indentPct / 100f,
        align = s.align,
        lineBreak = s.lineBreak,
        publisherStyles = s.epubPublisherStyles,
        maxImageHeightFraction = 1f,
        widowOrphanControl = s.widowOrphanControl,
    )

    /** Settings with every field that does NOT change the layout normalised away. */
    private fun layoutPart(s: ReaderSettings): ReaderSettings = s.copy(
        invert = false,
        footerPage = true,
        footerChapterLeft = false,
        footerPercent = true,
        footerClock = true,
        footerBattery = true,
    )

    /** True when going from [a] to [b] requires a new layout (anything but colours / footer items). */
    fun layoutChanged(a: ReaderSettings, b: ReaderSettings): Boolean = layoutPart(a) != layoutPart(b)

    /** True when the document must be re-parsed. */
    fun parseChanged(a: ReaderSettings, b: ReaderSettings, encoding: String): Boolean =
        a.parseOptions(encoding) != b.parseOptions(encoding)

    /**
     * Stable key for cached page counts: every layout-affecting setting, the parse options, the content box,
     * density, the font file identity and the app/layout versions, hashed to 24 hex chars.
     */
    fun key(
        s: ReaderSettings,
        parse: ParseOptions,
        g: PageGeometry,
        density: Float,
        fontIdentity: String,
        appVersion: Int,
    ): String {
        val sb = StringBuilder(512)
        sb.append("v").append(VERSION).append('|').append(appVersion)
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
