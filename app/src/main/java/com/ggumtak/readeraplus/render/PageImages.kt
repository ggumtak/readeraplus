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
}
