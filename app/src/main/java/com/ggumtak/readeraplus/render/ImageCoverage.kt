package com.ggumtak.readeraplus.render

import com.ggumtak.readeraplus.engine.SectionLayout

/**
 * How much of a page pictures cover (T1-3c "그림 있는 쪽에서 새로고침"). Pure; owner: RENDER; user: READER_A's refresh
 * cadence, only when AppSettings.einkFlashImages is on (off by default).
 */
object ImageCoverage {
    /**
     * Σ imageWidth × imageHeight of the image lines of page [pageIndex], divided by the content area
     * (`config.width × config.height`), in 0..1; 0 for pages without pictures (every TXT page) and for an index out of
     * range. Only the part of a picture inside the content box counts. O(lines on the page), no allocation. The reader refreshes when `cov >= 0.075` or the coverage changed by at least 0.075 from
     * the previous page.
     */
    fun of(layout: SectionLayout, pageIndex: Int): Float {
        val pages = layout.pages
        if (pageIndex < 0 || pageIndex >= pages.size) return 0f
        if (layout.config.width <= 0 || layout.config.height <= 0) return 0f
        val cw = layout.config.width.toFloat()
        val ch = layout.config.height.toFloat()
        val lines = pages[pageIndex].lines
        var area = 0f
        for (i in 0 until lines.size) {
            val ln = lines[i]
            if (ln.imageBlock == null) continue
            val w = minOf(ln.x + ln.imageWidth, cw) - maxOf(ln.x, 0f)
            val h = minOf(ln.top + ln.imageHeight, ch) - maxOf(ln.top, 0f)
            // Also skips NaN sizes: every comparison with NaN is false.
            if (w > 0f && h > 0f) area += w * h
        }
        return minOf(area / (cw * ch), 1f)
    }
}
