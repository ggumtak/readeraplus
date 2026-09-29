package com.ggumtak.readeraplus.format.epub

/** A TOC entry before resolution to a spine index: [href] is raw (relative to the TOC document). */
internal class RawTocEntry(val title: String, val level: Int, val href: String)

/** EPUB3 nav / EPUB2 NCX parsing plus light scans of content documents (titles, first image). */
internal object EpubTocParser {
    private const val MAX_ENTRIES = 20000

    private val VOID = hashSetOf("br", "img", "hr", "wbr", "image", "meta", "link", "input", "col", "area", "source")

    /**
     * Entries of the `<nav epub:type="toc">` (or the first `<nav>` when none is typed) in document order.
     * Levels start at 1 (outermost `<ol>`). Label-only items (`<span>` headers) take their first child's target.
     */
    fun parseNav(xhtml: String): List<RawTocEntry> {
        val typed = parseNavImpl(xhtml, requireTocType = true)
        return typed ?: parseNavImpl(xhtml, requireTocType = false) ?: emptyList()
    }

    private class NavItem(val level: Int) {
        var href: String? = null
        val title = StringBuilder()
    }

    /** Returns null if no matching nav element exists. */
    private fun parseNavImpl(xhtml: String, requireTocType: Boolean): List<RawTocEntry>? {
        val r = MarkupReader(xhtml)
        var found = false
        var navDepth = 0
        var olDepth = 0
        val items = ArrayList<NavItem>()
        var cur: NavItem? = null
        var labelDone = true
        var capturing = 0 // nesting depth inside the label element
        var hiddenDepth = 0
        loop@ while (true) {
            when (r.next()) {
                MarkupReader.EOF -> break@loop
                MarkupReader.START -> {
                    val n = r.name
                    if (navDepth == 0) {
                        if (n == "nav" && !r.selfClosing) {
                            val type = r.attr("epub:type") ?: r.attr("role") ?: ""
                            val isToc = type.split(' ').any { it == "toc" || it == "doc-toc" }
                            if (isToc || !requireTocType) {
                                found = true
                                navDepth = 1
                            }
                        }
                        continue@loop
                    }
                    if (r.selfClosing || n == "br" || n == "img" || n == "hr") continue@loop
                    if (hiddenDepth > 0 || n == "rt" || n == "rp" || n == "script" || n == "style") {
                        hiddenDepth++
                        continue@loop
                    }
                    if (capturing > 0) {
                        capturing++
                        continue@loop
                    }
                    when (n) {
                        "nav" -> navDepth++
                        "ol", "ul" -> {
                            olDepth++
                            labelDone = true
                        }
                        "li" -> {
                            if (items.size >= MAX_ENTRIES) break@loop
                            val it = NavItem(maxOf(1, olDepth))
                            items.add(it)
                            cur = it
                            labelDone = false
                        }
                        "a", "span" -> if (!labelDone && cur != null) {
                            if (n == "a") cur.href = r.attr("href")?.trim()?.takeIf { it.isNotEmpty() }
                            capturing = 1
                        }
                    }
                }
                MarkupReader.TEXT -> if (capturing > 0 && hiddenDepth == 0) {
                    cur?.title?.append(r.text())
                }
                MarkupReader.END -> {
                    if (navDepth == 0) continue@loop
                    val n = r.name
                    if (hiddenDepth > 0) {
                        hiddenDepth--
                        continue@loop
                    }
                    if (capturing > 0) {
                        capturing--
                        if (capturing == 0) labelDone = true
                        continue@loop
                    }
                    when (n) {
                        "nav" -> {
                            navDepth--
                            if (navDepth == 0) break@loop
                        }
                        "ol", "ul" -> if (olDepth > 0) olDepth--
                    }
                }
            }
        }
        if (!found) return null
        return finish(items.map { Triple(Entities.collapse(it.title.toString()), it.level, it.href) })
    }

    /** NCX `navMap` entries in document order with nesting levels. */
    fun parseNcx(xml: String): List<RawTocEntry> {
        val r = MarkupReader(xml)
        val titles = ArrayList<StringBuilder>()
        val levels = ArrayList<Int>()
        val hrefs = ArrayList<String?>()
        val stack = ArrayList<Int>() // indices of open navPoints
        var inNavMap = false
        var inLabel = 0
        var inText = false
        var capture: StringBuilder? = null
        loop@ while (true) {
            when (r.next()) {
                MarkupReader.EOF -> break@loop
                MarkupReader.START -> when (r.name) {
                    "navmap" -> inNavMap = true
                    "navpoint" -> if (inNavMap && !r.selfClosing) {
                        if (titles.size >= MAX_ENTRIES) break@loop
                        stack.add(titles.size)
                        titles.add(StringBuilder())
                        levels.add(stack.size)
                        hrefs.add(null)
                    }
                    "navlabel" -> if (stack.isNotEmpty() && !r.selfClosing) {
                        inLabel++
                        // only the first label of a navPoint counts (others are translations)
                        val sb = titles[stack[stack.size - 1]]
                        capture = if (sb.isEmpty()) sb else null
                    }
                    "text" -> if (inLabel > 0 && !r.selfClosing) inText = true
                    "content" -> if (stack.isNotEmpty()) {
                        val i = stack[stack.size - 1]
                        if (hrefs[i] == null) hrefs[i] = r.attr("src")?.trim()?.takeIf { it.isNotEmpty() }
                    }
                }
                MarkupReader.TEXT -> if (inText) capture?.append(r.text())
                MarkupReader.END -> when (r.name) {
                    "navmap" -> {
                        inNavMap = false
                        break@loop
                    }
                    "navpoint" -> if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
                    "navlabel" -> if (inLabel > 0) {
                        inLabel--
                        capture = null
                    }
                    "text" -> inText = false
                }
            }
        }
        return finish(titles.indices.map { Triple(Entities.collapse(titles[it].toString()), levels[it], hrefs[it]) })
    }

    /** Label-only items take the first deeper item's target; untargeted/empty items are dropped; levels from 1. */
    private fun finish(raw: List<Triple<String, Int, String?>>): List<RawTocEntry> {
        val out = ArrayList<RawTocEntry>(raw.size)
        var minLevel = Int.MAX_VALUE
        for (i in raw.indices) {
            val (title, level, h) = raw[i]
            var href = h
            if (href == null) {
                var j = i + 1
                while (j < raw.size && raw[j].second > level) {
                    if (raw[j].third != null) {
                        href = raw[j].third
                        break
                    }
                    j++
                }
            }
            if (href == null || title.isEmpty()) continue
            out.add(RawTocEntry(title, level, href))
            if (level < minLevel) minLevel = level
        }
        if (minLevel > 1 && minLevel != Int.MAX_VALUE) {
            return out.map { RawTocEntry(it.title, it.level - minLevel + 1, it.href) }
        }
        return out
    }

    /**
     * Quick title scan of a content document for the fallback TOC: the first h1–h3 text in the body, else the
     * `<title>`. [bookTitle] is ignored as a `<title>` (generic per-file titles). Null when untitled.
     */
    fun scanTitle(xhtml: String, bookTitle: String?): String? {
        val r = MarkupReader(xhtml)
        var inTitle = false
        var inHead = false
        var title: String? = null
        val titleSb = StringBuilder()
        var heading = 0
        var headingDepth = 0
        val headingSb = StringBuilder()
        var hidden = 0
        var bodyChars = 0
        loop@ while (true) {
            when (r.next()) {
                MarkupReader.EOF -> break@loop
                MarkupReader.START -> {
                    val n = r.name
                    // HTML void elements (`<br>` without a slash) have no end tag: counting them as nesting
                    // would keep the heading open until some later closer and swallow body text.
                    if (r.selfClosing || n in VOID) continue@loop
                    when {
                        n == "head" -> inHead = true
                        n == "title" && inHead -> inTitle = true
                        n == "body" -> inHead = false
                        heading > 0 -> {
                            if (n == "rt" || n == "rp") hidden++
                            headingDepth++
                        }
                        (n == "h1" || n == "h2" || n == "h3") && !inHead -> {
                            heading = n[1] - '0'
                            headingDepth = 0
                            hidden = 0
                        }
                    }
                }
                MarkupReader.TEXT -> when {
                    inTitle -> titleSb.append(r.text())
                    heading > 0 && hidden == 0 -> headingSb.append(r.text()).append(' ')
                    !inHead -> {
                        bodyChars += r.textEnd - r.textStart
                        if (bodyChars > 20000) break@loop
                    }
                }
                MarkupReader.END -> {
                    val n = r.name
                    when {
                        n == "title" && inTitle -> {
                            inTitle = false
                            title = Entities.collapse(titleSb.toString())
                        }
                        n == "head" -> inHead = false
                        // the heading's own closer ends it even if inner markup was left unclosed
                        heading > 0 && headingDepth > 0 && !(n.length == 2 && n[0] == 'h' && n[1] == '0' + heading) -> {
                            if ((n == "rt" || n == "rp") && hidden > 0) hidden--
                            headingDepth--
                        }
                        heading > 0 && n.length == 2 && n[0] == 'h' && n[1] in '1'..'6' -> {
                            val h = Entities.collapse(headingSb.toString())
                            if (h.isNotEmpty()) return h.take(200)
                            heading = 0
                            headingSb.setLength(0)
                        }
                    }
                }
            }
        }
        val t = title?.takeIf { it.isNotEmpty() } ?: return null
        if (bookTitle != null && t.equals(bookTitle, ignoreCase = true)) return null
        return t.take(200)
    }

    /** src of the first `<img>` / SVG `<image>` in a content document (raw href), or null. */
    fun firstImage(xhtml: String): String? {
        val r = MarkupReader(xhtml)
        while (true) {
            when (r.next()) {
                MarkupReader.EOF -> return null
                MarkupReader.START -> when (r.name) {
                    "img" -> r.attr("src")?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
                    "image" -> r.attr("href")?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
                }
            }
        }
    }
}
