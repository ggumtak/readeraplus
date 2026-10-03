package com.ggumtak.readeraplus.reader

import com.ggumtak.readeraplus.render.PageDecor

/**
 * Whether two page decors draw the same (N §6.6): `refreshDecor(onlyIfChanged = true)` skips the redraw, and so the
 * e-ink update, when nothing visible changed. Compares the highlights in order (start, end, kind and the quote colour
 * [com.ggumtak.readeraplus.render.Highlight.style]: without it a recolour would never repaint), the bookmark corner and
 * the status version. Pure, allocation-free.
 */
internal object DecorDiff {
    fun same(a: PageDecor, b: PageDecor): Boolean {
        if (a === b) return true
        if (a.bookmarked != b.bookmarked || a.statusVersion != b.statusVersion) return false
        val x = a.highlights
        val y = b.highlights
        if (x.size != y.size) return false
        for (i in x.indices) {
            val p = x[i]
            val q = y[i]
            if (p === q) continue
            if (p.start != q.start || p.end != q.end || p.kind != q.kind || p.style != q.style) return false
        }
        return true
    }
}
