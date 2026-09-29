package com.ggumtak.readeraplus.engine

/*
 * Typesetter contracts. The typesetter turns a SectionContent into pages of positioned lines.
 * All geometry is in pixels, relative to the page CONTENT box (margins already removed).
 * y grows downward; a line's baseline is at `baseline`, glyphs extend `ascent` above and `descent` below.
 */

/** Font metrics for one RunStyle at the current base size, in px (ascent/descent positive). */
class FontMetricsPx(@JvmField val ascent: Float, @JvmField val descent: Float)

/**
 * Measures text. Android implementation wraps TextPaint (render/AndroidTextMeasurer); tests use a fake.
 * NOT thread-safe: each thread (UI layout, background page counter) owns its own instance.
 */
interface TextMeasurer {
    /** Base font size in px (1 em). */
    val emPx: Float

    /**
     * Writes the advance of every char of text[start, end) with [style] into out[outOffset + (i - start)].
     * Advances include letter spacing. For surrogate pairs / clusters the first char carries the whole
     * advance and the following ones 0 (same contract as Paint.getTextWidths).
     */
    fun measure(text: String, start: Int, end: Int, style: RunStyle, out: FloatArray, outOffset: Int)

    fun metrics(style: RunStyle): FontMetricsPx

    /** Intrinsic pixel size of an image, or null if unknown/undecodable (then the block is skipped). */
    fun imageSize(src: String): IntSize?
}

data class IntSize(val width: Int, val height: Int)

enum class LineBreakMode {
    /** Break between any two Hangul syllables / CJK chars (typical Korean book typesetting). */
    CHAR,
    /** Keep words (space-separated eojeol) intact, like CSS word-break: keep-all. */
    WORD,
}

/** Everything the typesetter needs. Derived from ReaderSettings + viewport. Data class = cache key part. */
data class LayoutConfig(
    /** Content box size in px. */
    val width: Int,
    val height: Int,
    /** Line height as a multiple of 1 em (e.g. 1.7). Minimum is the font's natural height. */
    val lineHeightEm: Float = 1.7f,
    /** Extra space between paragraphs, em. */
    val paragraphSpacingEm: Float = 0.5f,
    /** First-line indent, em. */
    val indentEm: Float = 1f,
    /** Default alignment for Align.DEFAULT blocks. JUSTIFY or LEFT. */
    val align: Align = Align.JUSTIFY,
    val lineBreak: LineBreakMode = LineBreakMode.CHAR,
    /** Respect publisher block styles (alignment, margins, heading sizes). If false only headings keep bold. */
    val publisherStyles: Boolean = true,
    /** Max share of page height an image may take (1 = full page). */
    val maxImageHeightFraction: Float = 1f,
    /** Avoid leaving a single line of a paragraph alone at the bottom/top of a page. */
    val widowOrphanControl: Boolean = true,
)

/**
 * One positioned line. Image lines have imageBlock != null and use (x, top, imageWidth, imageHeight).
 * Text lines draw text[start, end) (end excludes trailing hanging spaces) starting at x.
 */
class LineInfo(
    @JvmField val start: Int,
    @JvmField val end: Int,
    /** Left edge of the first glyph relative to content box. */
    @JvmField val x: Float,
    /** Top of the line box and baseline, relative to the page content top. */
    @JvmField val top: Float,
    @JvmField val baseline: Float,
    @JvmField val bottom: Float,
    /** Extra px added at each expansion opportunity when justified (0 = none). */
    @JvmField val justifyExtra: Float,
    /** EXPAND_NONE, EXPAND_SPACES (after each space char), EXPAND_CHARS (after each non-final char). */
    @JvmField val expandMode: Int,
    @JvmField val imageBlock: ImageBlock? = null,
    @JvmField val imageWidth: Float = 0f,
    @JvmField val imageHeight: Float = 0f,
    /** RuleBlock lines draw a short centered rule. */
    @JvmField val isRule: Boolean = false,
) {
    companion object {
        const val EXPAND_NONE = 0
        const val EXPAND_SPACES = 1
        const val EXPAND_CHARS = 2
    }
}

/** A page: lines whose text covers [start, end) of the section. */
class PageInfo(@JvmField val start: Int, @JvmField val end: Int, @JvmField val lines: List<LineInfo>)

/**
 * Full layout of a section. [advances] holds the measured advance of every char of content.text
 * (index = offset), so drawing and hit-testing never re-measure.
 */
class SectionLayout(
    val content: SectionContent,
    val config: LayoutConfig,
    val pages: List<PageInfo>,
    val advances: FloatArray,
) {
    val pageCount: Int get() = pages.size

    /** Page index containing [offset] (clamped). */
    fun pageForOffset(offset: Int): Int {
        if (pages.isEmpty()) return 0
        var lo = 0
        var hi = pages.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (pages[mid].start <= offset) lo = mid else hi = mid - 1
        }
        return lo
    }
}
