package com.ggumtak.readeraplus.reader

import com.ggumtak.readeraplus.engine.LineInfo
import com.ggumtak.readeraplus.engine.SectionLayout

/**
 * Where the page starts after 위·아래 여백 alone changed (user, 2026-10-09: "상하여백을 늘리는데 글이 아래로는 안
 * 내려오게"). The body lines fill the box between the margins with whole lines, a little stretched ([LayoutKeys.geometry]),
 * so a margin step squeezes them (top line down, bottom line up) until they would get closer than the settings' line;
 * then the box holds a line fewer and the lines spread again. Kept at the same first line that spread would push the
 * lower lines down by up to a line. Instead the page gives its top line(s) to the page before (takes them back from it
 * when the margins shrink): every line left moves up when the margins grow and down when they shrink. The line breaks
 * are the same (only the height changed), so the lines are those of the layout on screen.
 */
internal object MarginShift {

    /**
     * The new start of page [pageIndex] of [l] (the page on screen, its top line holding [anchor]) when the box went from
     * holding `n` lines to `n - delta`: the start of the line [delta] lines below its top line ([delta] > 0), or above it,
     * on the page before ([delta] < 0). An empty line (a blank paragraph) is never the new top: a page drops it there, so
     * the start goes on to the next line with text (further up the screen when raising, further back when lowering), and
     * raising after lowering lands where it started. -1 keeps the page start: nothing changed, a picture or a rule among
     * the lines that would cross the top (they are not one line tall), or not that many lines to give or take.
     */
    fun start(l: SectionLayout, pageIndex: Int, anchor: Int, delta: Int): Int {
        if (delta == 0) return -1
        val page = l.pages.getOrNull(pageIndex) ?: return -1
        val lines = page.lines
        if (lines.isEmpty()) return -1
        var top = 0
        while (top + 1 < lines.size && lines[top + 1].start <= anchor) top++
        if (delta > 0) {
            var to = top + delta
            while (to < lines.size && empty(lines[to])) to++
            // At least one line stays on the page.
            if (to >= lines.size) return -1
            for (k in top..to) if (!plain(lines[k])) return -1
            return lines[to].start
        }
        var p = pageIndex
        var k = top
        var need = -delta
        var line: LineInfo = lines[top]
        if (!plain(line)) return -1
        while (need > 0 || empty(line)) {
            k--
            while (k < 0) {
                p--
                val before = l.pages.getOrNull(p) ?: return -1
                k = before.lines.size - 1
            }
            line = l.pages[p].lines[k]
            if (!plain(line)) return -1
            need--
        }
        return line.start
    }

    private fun plain(x: LineInfo): Boolean = x.imageBlock == null && !x.isRule

    private fun empty(x: LineInfo): Boolean = x.end <= x.start
}
