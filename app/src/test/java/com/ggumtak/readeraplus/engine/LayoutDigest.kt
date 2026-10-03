package com.ggumtak.readeraplus.engine

/** Readable digest of pages and lines (tests only): the fields LayoutGoldenTest hashes. */
object LayoutDigest {
    fun line(ln: LineInfo): String =
        "${ln.start},${ln.end},${ln.x},${ln.top},${ln.baseline},${ln.bottom},${ln.justifyExtra},${ln.expandMode}," +
            "${ln.imageBlock?.start ?: -1},${ln.imageWidth},${ln.imageHeight},${ln.isRule}"

    fun page(p: PageInfo): String = buildString {
        append(p.start).append('-').append(p.end).append('[')
        for (ln in p.lines) append(line(ln)).append(';')
        append(']')
    }

    fun of(l: SectionLayout): String = l.pages.joinToString("\n") { page(it) }

    /** Line boxes without their vertical position: what stays identical when only the pagination changes. */
    fun flow(l: SectionLayout): List<String> = l.pages.flatMap { p ->
        // Blank paragraphs are dropped at a page top (PageInfo.lead accounts for them in scroll mode): not compared.
        p.lines.filter { it.end > it.start || it.isRule || it.imageBlock != null }.map { "${it.start},${it.end},${it.x},${Math.round((it.bottom - it.top) * 100)},${it.justifyExtra},${it.expandMode},${it.isRule}" }
    }
}
