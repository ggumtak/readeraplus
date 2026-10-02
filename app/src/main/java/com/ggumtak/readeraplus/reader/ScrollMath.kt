package com.ggumtak.readeraplus.reader

import com.ggumtak.readeraplus.engine.SectionLayout

internal interface StripSource {
    val sectionCount: Int
    /** Laid-out layout of [section] or null (never starts a layout). */
    fun layoutOf(section: Int): SectionLayout?
    /** Extra px above page 0 of [section] (> 0 only at a chapter / spine-item start; never called for section 0). */
    fun unitGap(section: Int): Float
}

internal class ScrollPos {
    @JvmField var section = 0
    @JvmField var page = 0
    @JvmField var dy = 0f
    /** Section a move stopped at because it is not laid out (-1 = none). */
    @JvmField var blockedAt = -1
    fun set(o: ScrollPos) { section=o.section; page=o.page; dy=o.dy; blockedAt=o.blockedAt }
}

internal enum class Step { MOVED, EDGE, NEED_SECTION }
/** [Δ] Moved here from ScrollReader so the pure settle rule is JVM-testable. */
internal enum class SettleKind { STEP, DRAG, FLING, JUMP, RELAYOUT, OPEN, SWITCH }
internal enum class Placement { TOP, CONTEXT }

internal object ScrollMath {
    const val MAX_STRIPS = 64                 // per viewport walk: chains of empty pages / tiny sections
    const val CONTEXT_FRACTION = 0.25f
    const val CHAPTER_GAP_EM = 2f

    fun gapAbove(src: StripSource, section: Int, l: SectionLayout, page: Int): Float = TODO("owner: RC-S")
    fun body(l: SectionLayout, page: Int): Float = TODO("owner: RC-S")
    fun height(src: StripSource, section: Int, l: SectionLayout, page: Int): Float = TODO("owner: RC-S")

    /** Moves the viewport top by [delta] px (> 0 = towards the end); returns px moved. Stops at the book start, at the
     *  book end (the last line may rise to the viewport bottom, not above), and at a section not laid out
     *  ([ScrollPos.blockedAt]); never reveals unknown content. O(strips crossed + strips in one viewport). */
    fun scrollBy(src: StripSource, pos: ScrollPos, delta: Float, viewH: Float): Float = TODO("owner: RC-S")

    /** Positions [pos] for [offset] of [section]: TOP (or offset at a line start) puts that line's top at the viewport
     *  top; CONTEXT puts it ~25 % down, the top snapped to a whole line and clamped at the section start; both clamp at
     *  the book end. [lineAligned] (STEP) keeps the top on a line top even at the book end. */
    fun place(src: StripSource, section: Int, l: SectionLayout, offset: Int, placement: Placement,
              viewH: Float, lineAligned: Boolean, pos: ScrollPos): Unit = TODO("owner: RC-S")

    /** One screen down into [out]: the new top is the first line not wholly visible (a line taller than the viewport:
     *  the next line). Never skips a line. EDGE at the book end, NEED_SECTION (out.blockedAt) when unknown. */
    fun stepDown(src: StripSource, pos: ScrollPos, viewH: Float, out: ScrollPos): Step = TODO("owner: RC-S")
    /** One screen up into [out]: the line above the first wholly visible line ends at the viewport bottom; the new top is
     *  a whole line. EDGE at the book start. */
    fun stepUp(src: StripSource, pos: ScrollPos, viewH: Float, out: ScrollPos): Step = TODO("owner: RC-S")
    /** STEP release: snaps [pos] so the nearest line top is at the viewport top (clamped). */
    fun snapToLine(src: StripSource, pos: ScrollPos, viewH: Float): Unit = TODO("owner: RC-S")
    /** STEP release decision: +1 / -1 = one screen (a fling: |vy| ≥ 2 × [flingMin] and |totalDy| < viewH / 3),
     *  0 = move by the dragged distance (then [snapToLine]). Sign: finger up = +1 (towards the end). */
    fun releaseStep(totalDy: Float, vy: Float, flingMin: Float, viewH: Float): Int = TODO("owner: RC-S")

    /** Reading anchor, packed (section shl 32) or offset: the first line at least half visible. */
    fun anchor(src: StripSource, pos: ScrollPos, viewH: Float): Long = TODO("owner: RC-S")
    /** Packed (section shl 32) or page of the anchor line. */
    fun topPage(src: StripSource, pos: ScrollPos, viewH: Float): Long = TODO("owner: RC-S")
    /** The book's last line is wholly visible. */
    fun atBookEnd(src: StripSource, pos: ScrollPos, viewH: Float): Boolean = TODO("owner: RC-S")
    /** View-relative bottom of the last wholly visible line (STEP clips there, so the cut line is not drawn). */
    fun lastFullyVisibleBottom(src: StripSource, pos: ScrollPos, viewH: Float): Float = TODO("owner: RC-S")
    /** Px between two positions (for the SMOOTH step animation); NaN when an unknown section lies between. */
    fun distance(src: StripSource, from: ScrollPos, to: ScrollPos, limit: Float): Float = TODO("owner: RC-S")
    /** Visits visible strips top-down: (section, layout, page, stripTop relative to the viewport top, gapAbove). */
    fun forEachVisible(src: StripSource, pos: ScrollPos, viewH: Float,
                              visit: (Int, SectionLayout, Int, Float, Float) -> Unit): Unit = TODO("owner: RC-S")
    /** [Δ] Settle rule of §1.4: [placed] for OPEN / RELAYOUT / SWITCH, else [computed] (both packed). */
    fun anchorAfter(kind: SettleKind, placed: Long, computed: Long): Long = TODO("owner: RC-S")
    /** First movement beyond the slop: true = vertical (scroll), false = horizontal (swipe). */
    fun isVertical(dx: Float, dy: Float): Boolean = Math.abs(dy) > Math.abs(dx)
}

/** Whole screens of accumulated scrolling (return chip "manual turns"). */
internal class ScreenCounter { fun reset() {} // R3 stub (owner: RC-S)
    fun add(px: Float, viewH: Float): Int = TODO("owner: RC-S") }
