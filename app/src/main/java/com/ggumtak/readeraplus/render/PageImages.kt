package com.ggumtak.readeraplus.render

import com.ggumtak.readeraplus.engine.LineInfo
import com.ggumtak.readeraplus.engine.SectionLayout

/**
 * The pictures of a laid-out page at the size [PageRenderer] draws them: the image line's box rounded to px, which is
 * also the size [ImageCache] decodes and keeps them at. Pure; the renderer and the reader's "decode before showing"
 * checks share it, so both always ask about the same size.
 */
object PageImages {
    fun width(ln: LineInfo): Int = Math.round(ln.imageWidth).coerceAtLeast(1)

    fun height(ln: LineInfo): Int = Math.round(ln.imageHeight).coerceAtLeast(1)

    /**
     * True when page [pageIndex] of [layout] has a picture for which [missing] (src, drawn width, drawn height) is
     * true; false for pages without pictures (every TXT page) and for an index out of range. O(lines on the page) and
     * allocation-free: a text line costs one field read.
     */
    inline fun needsDecode(layout: SectionLayout, pageIndex: Int, missing: (String, Int, Int) -> Boolean): Boolean {
        val pages = layout.pages
        if (pageIndex < 0 || pageIndex >= pages.size) return false
        val lines = pages[pageIndex].lines
        for (i in 0 until lines.size) {
            val ln = lines[i]
            val img = ln.imageBlock ?: continue
            if (missing(img.src, width(ln), height(ln))) return true
        }
        return false
    }

    /** The picture line of page [pageIndex] drawn from [src] at w x h px, or null (out of range, gone, other size). */
    fun find(layout: SectionLayout, pageIndex: Int, src: String, w: Int, h: Int): LineInfo? {
        val lines = layout.pages.getOrNull(pageIndex)?.lines ?: return null
        for (i in 0 until lines.size) {
            val ln = lines[i]
            val img = ln.imageBlock ?: continue
            if (width(ln) == w && height(ln) == h && img.src == src) return ln
        }
        return null
    }

    /**
     * The view-px box of [ln] drawn with the content box at ([left], [top]), widened outward to whole px (and one px
     * more, for the outline the empty box drew inside it): [out] = left, top, right, bottom. The part of the screen a
     * picture arriving later has to repaint, and nothing else.
     */
    fun bounds(ln: LineInfo, left: Float, top: Float, out: IntArray) {
        out[0] = Math.floor((left + ln.x).toDouble()).toInt() - 1
        out[1] = Math.floor((top + ln.top).toDouble()).toInt() - 1
        out[2] = Math.ceil((left + ln.x + ln.imageWidth).toDouble()).toInt() + 1
        out[3] = Math.ceil((top + ln.top + ln.imageHeight).toDouble()).toInt() + 1
    }
}
