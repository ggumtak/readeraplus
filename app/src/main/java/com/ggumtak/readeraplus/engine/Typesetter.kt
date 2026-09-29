package com.ggumtak.readeraplus.engine

/**
 * CONTRACT STUB — implemented by the engine module owner. Public signatures are fixed; other modules
 * compile against them. See docs/ARCHITECTURE.md "engine".
 */
class Typesetter(private val measurer: TextMeasurer, private val config: LayoutConfig) {

    /** Lays out a whole section into pages. Deterministic for the same (content, config, measurer font state). */
    fun layout(content: SectionContent): SectionLayout = TODO("engine")

    /** Same page count as layout(content).pageCount but without retaining lines (background page counting). */
    fun countPages(content: SectionContent): Int = TODO("engine")
}

/** Simple float rectangle (engine stays free of android.graphics). */
class RectPx(@JvmField var left: Float, @JvmField var top: Float, @JvmField var right: Float, @JvmField var bottom: Float)

/** Geometry queries on laid-out lines, shared by rendering, selection, TTS highlight and search highlight. */
object LineGeometry {

    /**
     * Writes the left x (content-box coordinates) of every char of line[start, end) into out[i - line.start]
     * and returns the x of the line's right edge. Includes justification expansion. `out` must hold at least
     * (line.end - line.start) entries.
     */
    fun charPositions(layout: SectionLayout, line: LineInfo, out: FloatArray): Float = TODO("engine")

    /** Offset of the char under (x, y) on [page] (content-box coordinates), or -1 if nothing is there. */
    fun hitTest(layout: SectionLayout, page: PageInfo, x: Float, y: Float): Int = TODO("engine")

    /** Rectangles (one per line) covering offsets [start, end) intersected with [page]. */
    fun rangeRects(layout: SectionLayout, page: PageInfo, start: Int, end: Int): List<RectPx> = TODO("engine")

    /** Word boundaries around [offset] for long-press selection: returns packed (start shl 32 | end). */
    fun wordAt(text: String, offset: Int): Long = TODO("engine")
}
