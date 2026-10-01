package com.ggumtak.readeraplus.engine

/*
 * Document content model shared by the format parsers (TXT/EPUB) and the typesetter.
 * Pure Kotlin: no android.* imports anywhere under engine/ so it can be unit-tested on the JVM.
 *
 * A section ("layout unit") is one flat String. Paragraphs are separated by exactly one '\n'.
 * Every Block covers a range [start, end) of that string that never includes the separating '\n'.
 * An image occupies exactly one OBJECT_CHAR (U+FFFC) inside its own ImageBlock.
 * Offsets into SectionContent.text are the canonical reading positions (DocPosition.offset).
 */

const val OBJECT_CHAR = '￼'

/** Inline style of a run of characters. Data class: used as a cache key for paints. */
data class RunStyle(
    val bold: Boolean = false,
    val italic: Boolean = false,
    /** Relative to the user's base font size (1.0 = base). Headings use > 1. */
    val sizeScale: Float = 1f,
    /** -1 = subscript, 0 = normal, +1 = superscript. */
    val baselineShift: Int = 0,
    val underline: Boolean = false,
    val strike: Boolean = false,
    val monospace: Boolean = false,
    /** Link target (raw href) or null. */
    val link: String? = null,
) {
    companion object {
        @JvmField val PLAIN = RunStyle()
    }
}

/** Styled range inside SectionContent.text. Ranges are sorted and non-overlapping. Gaps are PLAIN. */
class StyleRun(@JvmField val start: Int, @JvmField val end: Int, @JvmField val style: RunStyle)

enum class Align { DEFAULT, LEFT, CENTER, RIGHT, JUSTIFY }

/** Block-level presentation hints coming from the source document. All ems are relative to base font size. */
data class BlockStyle(
    /** DEFAULT = follow the user's alignment setting. */
    val align: Align = Align.DEFAULT,
    /** Apply the user's first-line indent to this paragraph. */
    val indent: Boolean = true,
    /** 0 = body text, 1..6 = heading level. */
    val headingLevel: Int = 0,
    /** Extra space before/after this block, in em, added on top of the user's paragraph spacing. */
    val marginTopEm: Float = 0f,
    val marginBottomEm: Float = 0f,
    /** Left/right inset for blockquotes, lists, nested content (em). */
    val insetLeftEm: Float = 0f,
    val insetRightEm: Float = 0f,
    /** Avoid a page break right after this block (headings). */
    val keepWithNext: Boolean = false,
    /** Start this block on a new page. */
    val pageBreakBefore: Boolean = false,
    /** Keep runs of spaces as-is and never justify (pre / poetry). */
    val preformatted: Boolean = false,
    /**
     * This paragraph continues the previous one after a forced line break (<br>, poetry lines):
     * no paragraph spacing before it and no first-line indent.
     */
    val softBreak: Boolean = false,
) {
    companion object {
        @JvmField val BODY = BlockStyle()
    }
}

sealed class Block {
    abstract val start: Int
    abstract val end: Int
}

/** A paragraph of text. May be empty (start == end): an empty paragraph renders as one blank line. */
class ParagraphBlock(
    override val start: Int,
    override val end: Int,
    val style: BlockStyle = BlockStyle.BODY,
) : Block()

/**
 * An image. [src] is resolved by BookDocument.loadImage. Intrinsic size in CSS px if known (0 = unknown,
 * the renderer/measurer supplies it via TextMeasurer.imageSize).
 */
class ImageBlock(
    override val start: Int,
    val src: String,
    val intrinsicWidth: Int = 0,
    val intrinsicHeight: Int = 0,
    val alt: String? = null,
    val style: BlockStyle = BlockStyle(align = Align.CENTER, indent = false),
) : Block() {
    override val end: Int get() = start + 1
}

/** Horizontal rule / scene break. Occupies an empty range (start == end) in the text (an empty line). */
class RuleBlock(override val start: Int) : Block() {
    override val end: Int get() = start
}

/**
 * One laid-out unit of a book (an EPUB spine item or a TXT chapter/chunk).
 * [anchors] maps element ids (EPUB fragment identifiers) to offsets for link/TOC resolution.
 */
class SectionContent(
    val text: String,
    val blocks: List<Block>,
    val styleRuns: List<StyleRun> = emptyList(),
    val anchors: Map<String, Int> = emptyMap(),
) {
    val length: Int get() = text.length

    /** Style at [offset] (binary search over styleRuns). */
    fun styleAt(offset: Int): RunStyle {
        var lo = 0
        var hi = styleRuns.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val r = styleRuns[mid]
            when {
                offset < r.start -> hi = mid - 1
                offset >= r.end -> lo = mid + 1
                else -> return r.style
            }
        }
        return RunStyle.PLAIN
    }

    /** Index of the block containing [offset] (or the nearest following block). */
    fun blockIndexAt(offset: Int): Int {
        var lo = 0
        var hi = blocks.size - 1
        var ans = blocks.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (blocks[mid].end >= offset) { ans = mid; hi = mid - 1 } else lo = mid + 1
        }
        return ans.coerceAtLeast(0)
    }

    companion object {
        @JvmField val EMPTY = SectionContent("", emptyList())
    }
}
