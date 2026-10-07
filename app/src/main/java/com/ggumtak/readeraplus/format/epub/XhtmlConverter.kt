package com.ggumtak.readeraplus.format.epub

import com.ggumtak.readeraplus.engine.Align
import com.ggumtak.readeraplus.engine.Block
import com.ggumtak.readeraplus.engine.BlockStyle
import com.ggumtak.readeraplus.engine.ImageBlock
import com.ggumtak.readeraplus.engine.OBJECT_CHAR
import com.ggumtak.readeraplus.engine.ParagraphBlock
import com.ggumtak.readeraplus.engine.RuleBlock
import com.ggumtak.readeraplus.engine.RunStyle
import com.ggumtak.readeraplus.engine.SectionContent
import com.ggumtak.readeraplus.engine.StyleRun

/** Book resources the converter needs (backed by the zip in production, fakes in tests). */
internal interface XhtmlResources {
    /** Canonical zip path of the image at the resolved [path], or null if the book has no such entry. */
    fun imagePath(path: String): String?

    /** Parsed style sheets of the CSS file at the resolved [path] (its @imports first); empty if missing. */
    fun styleSheets(path: String): List<CssSheet>
}

/**
 * Converts one XHTML content document into a [SectionContent]: flat text with one '\n' between blocks, block
 * styles, inline style runs and anchors. Single use, not thread-safe (create one per conversion; cheap).
 *
 * Paragraph model: a paragraph opens lazily on the first inline content and closes at every block boundary, so
 * block elements never nest paragraphs. `<br>` ends the paragraph and makes the next one a soft-break
 * continuation. Whitespace is collapsed per HTML rules (NBSP kept), except in `pre` / `white-space: pre`.
 */
internal class XhtmlConverter(
    private val publisherStyles: Boolean,
    private val resources: XhtmlResources?,
    /** The book's font-size declarations count on headings only; every other element keeps its parent's size. */
    private val ignoreBookSizes: Boolean = false,
) {
    // ---------------------------------------------------------------- output
    private val sb = StringBuilder(4096)
    private val blocks = ArrayList<Block>(64)
    private val runs = ArrayList<StyleRun>(64)
    private val anchors = HashMap<String, Int>()
    private val pendingAnchors = ArrayList<String>(4)
    /** Ids recorded mid-paragraph in the open paragraph (re-clamped if it is trimmed or dropped). */
    private val paraAnchors = ArrayList<String>(4)

    // ---------------------------------------------------------------- paragraph state
    private var paraOpen = false
    private var paraSep = 0
    private var paraStart = 0
    private var paraContentStart = 0
    private var paraVisible = false
    private var paraBlank = false
    private var paraSoft = false
    private var paraStyle: BlockStyle = BlockStyle.BODY
    private var pendingSpace = false
    private var pendingSoftBreak = false
    private var pendingMarginTop = 0f
    private var pendingBreak = false
    private var cellSepPending = false
    private var preSkipNewline = false

    // ---------------------------------------------------------------- runs
    private var curStyle: RunStyle = RunStyle.PLAIN
    private var runStart = 0

    // ---------------------------------------------------------------- element stack
    private var frames = arrayOfNulls<Frame>(32)
    private var top = 0
    private var ignoredDepth = 0
    private var docDir = ""
    private val cascade = CssCascade()
    private var inlineCssCache: HashMap<String, CssDecl?>? = null
    private val titleSb = StringBuilder()
    private var titleDone = false

    /** Text of the document's `<title>` (collapsed), available after [convert]. */
    var title: String? = null
        private set

    private class Frame {
        @JvmField var tag = ""
        @JvmField var hidden = false
        @JvmField var svg = false
        @JvmField var isBlock = false
        @JvmField var softBlock = false
        /** Hidden text to collect: [CAPTURE_CSS] or [CAPTURE_TITLE]. */
        @JvmField var capture = 0
        @JvmField var owner = 0
        @JvmField var style: RunStyle = RunStyle.PLAIN
        @JvmField var align = Align.DEFAULT
        @JvmField var indent = true
        @JvmField var heading = 0
        @JvmField var insetL = 0f
        @JvmField var insetR = 0f
        @JvmField var pre = false
        @JvmField var inCell = false
        @JvmField var listIdx = -1
        @JvmField var listDepth = 0
        @JvmField var liIdx = -1
        @JvmField var listKind = 0
        @JvmField var listStyle = 0
        @JvmField var counter = 1
        @JvmField var liPrefix: String? = null
        @JvmField var marginBottom = 0f
        @JvmField var breakAfter = false
        @JvmField var blocksAtStart = 0
        @JvmField var blockStyle: BlockStyle? = null
        @JvmField var id: String? = null
        @JvmField var classAttr: String? = null
        @JvmField var childCount = 0
        @JvmField var childIndex = 0
        @JvmField var prevTag: String? = null
        @JvmField var prevId: String? = null
        @JvmField var prevClass: String? = null
        @JvmField var lastTag: String? = null
        @JvmField var lastId: String? = null
        @JvmField var lastClass: String? = null

        fun inheritFrom(p: Frame) {
            hidden = p.hidden
            svg = p.svg
            isBlock = false
            softBlock = false
            capture = 0
            owner = p.owner
            style = p.style
            align = p.align
            indent = p.indent
            heading = p.heading
            insetL = p.insetL
            insetR = p.insetR
            pre = p.pre
            inCell = p.inCell
            listIdx = p.listIdx
            listDepth = p.listDepth
            liIdx = p.liIdx
            listKind = 0
            listStyle = 0
            counter = 1
            liPrefix = null
            marginBottom = 0f
            breakAfter = false
            blockStyle = null
            childCount = 0
            lastTag = null
            lastId = null
            lastClass = null
        }
    }

    /** Converts [src] (the decoded XHTML of the zip entry [docPath]). Never throws on malformed markup. */
    fun convert(src: String, docPath: String): SectionContent {
        docDir = EpubPaths.dirOf(docPath)
        val root = Frame().also { it.tag = "#root" }
        frames[0] = root
        top = 0
        val r = MarkupReader(src)
        while (true) {
            when (r.next()) {
                MarkupReader.EOF -> break
                MarkupReader.TEXT -> onText(r)
                MarkupReader.START -> onStart(r)
                MarkupReader.END -> onEnd(r.name)
            }
        }
        return finish()
    }

    // ================================================================ tokens

    private fun onText(r: MarkupReader) {
        val f = frames[top]!!
        if (f.hidden || f.svg) {
            when (f.capture) {
                CAPTURE_CSS -> addCssText(r.text())
                CAPTURE_TITLE -> if (!titleDone) titleSb.append(r.text())
            }
            return
        }
        if (f.pre) appendPre(r) else appendFlow(r)
    }

    private fun onStart(r: MarkupReader) {
        val name = r.name
        if (name.isEmpty()) return
        preSkipNewline = false
        when (name) {
            "p" -> autoCloseParagraph()
            "li" -> autoClose("li", LIST_BOUNDARY)
            "dt", "dd" -> autoClose2("dt", "dd", "dl")
            "tr" -> autoCloseTableRow()
            "td", "th" -> autoClose2("td", "th", "tr")
            "body" -> closeHead()
            "h1", "h2", "h3", "h4", "h5", "h6" -> if (headingLevel(frames[top]!!.tag) > 0) popFrame() // HTML: <h1>a<h2>
        }
        if (top >= MAX_DEPTH) {
            onStartTooDeep(r, name)
            return
        }
        val parent = frames[top]!!
        val idx = top + 1
        if (idx >= frames.size) frames = frames.copyOf(frames.size * 2)
        val f = frames[idx] ?: Frame().also { frames[idx] = it }
        f.inheritFrom(parent)
        f.tag = name
        val id = r.attr("id")?.takeIf { it.isNotEmpty() }
        val classAttr = r.attr("class")?.takeIf { it.isNotBlank() }
        f.id = id
        f.classAttr = classAttr
        f.childIndex = parent.childCount++
        f.prevTag = parent.lastTag
        f.prevId = parent.lastId
        f.prevClass = parent.lastClass
        parent.lastTag = name
        parent.lastId = id
        parent.lastClass = classAttr
        top = idx
        val void = r.selfClosing || name in VOID
        processStart(f, idx, name, r, id, classAttr)
        if (void) popFrame()
    }

    private fun processStart(f: Frame, idx: Int, name: String, r: MarkupReader, id: String?, classAttr: String?) {
        if (!f.svg) {
            // Style sheets and the document title apply wherever they appear (mostly inside the hidden head).
            when (name) {
                "style" -> {
                    f.hidden = true
                    val media = r.attr("media")
                    if (media == null || mediaOk(media)) f.capture = CAPTURE_CSS
                    return
                }
                "title" -> {
                    f.hidden = true
                    if (idx > 1 && frames[idx - 1]!!.tag == "head") f.capture = CAPTURE_TITLE
                    return
                }
                "link" -> {
                    f.hidden = true
                    val rel = r.attr("rel")?.lowercase() ?: ""
                    if (rel.contains("stylesheet") && !rel.contains("alternate")) {
                        val href = r.attr("href")
                        val media = r.attr("media")
                        if (!href.isNullOrBlank() && !EpubPaths.hasScheme(href) && (media == null || mediaOk(media))) {
                            val path = EpubPaths.resolve(docDir, href)
                            resources?.styleSheets(path)?.let { if (it.isNotEmpty()) cascade.add(it) }
                        }
                    }
                    return
                }
            }
        }
        if (f.hidden) {
            if (id != null) recordAnchor(id)
            return
        }
        if (f.svg) {
            if (name == "image") {
                emitImage(r.attr("href"), r.attr("width"), r.attr("height"), null)
            }
            if (id != null) recordAnchor(id)
            return
        }
        when (name) {
            "head", "script", "noscript", "template", "rt", "rp", "object", "iframe", "video", "audio", "canvas",
            "embed", "button", "select", "input", "datalist", "map", "meta", "base", "param", "source", "track",
            "col", "colgroup", "area" -> {
                f.hidden = true
                return
            }
        }
        val styleAttr = r.attr("style")
        val decl = computeDecl(name, id, classAttr, styleAttr)
        if (decl.displayNone || r.hasAttr("hidden") || isPageBreakMarker(r)) {
            f.hidden = true
            recordAnchors(r, name, id)
            return
        }
        if (name == "svg") {
            f.svg = true
            recordAnchors(r, name, id)
            return
        }
        // inline style (all elements)
        f.style = runStyleFor(f.style, name, r, decl)
        val soft = f.inCell && name in BLOCK && name !in TABLE_STRUCT
        if (!soft && name in BLOCK) {
            startBlock(f, idx, name, r, decl)
        } else if (soft) {
            f.softBlock = true
            softSpace()
        }
        // After startBlock closed the previous paragraph: a block's id must point at the block, not at the end
        // of the text before it (`<div>intro<p id="n1">…` would otherwise land on the previous page).
        recordAnchors(r, name, id)
        setRunStyle(f.style)
        when (name) {
            "br" -> handleBr()
            "hr" -> handleRule()
            "img" -> emitImage(r.attr("src"), r.attr("width"), r.attr("height"), r.attr("alt"))
            "wbr" -> if (paraOpen && paraHasChars() && !pendingSpace) sb.append('\u200B')
            "td", "th" -> {
                f.inCell = true
                if (paraOpen && paraHasChars()) {
                    trimTrailingSpaces()
                    cellSepPending = true
                }
                pendingSpace = false
            }
        }
    }

    /**
     * Pathologically deep markup (e.g. hundreds of unclosed `<div>`s): no more frames are pushed, but line
     * breaks, rules, images and paragraph boundaries still apply so the text doesn't melt into one paragraph.
     */
    private fun onStartTooDeep(r: MarkupReader, name: String) {
        val void = r.selfClosing || name in VOID
        if (!void) ignoredDepth++
        val f = frames[top]!!
        if (f.hidden || f.svg) return
        when {
            name == "br" -> handleBr()
            name == "hr" -> handleRule()
            name == "img" -> emitImage(r.attr("src"), r.attr("width"), r.attr("height"), r.attr("alt"))
            name in BLOCK -> {
                closeParagraph()
                pendingSoftBreak = false
            }
        }
    }

    private fun onEnd(name: String) {
        if (ignoredDepth > 0) {
            ignoredDepth--
            if (name in BLOCK && !frames[top]!!.hidden && !frames[top]!!.svg) {
                closeParagraph()
                pendingSoftBreak = false
            }
            return
        }
        if (name == "br") { // "</br>" is treated as <br> by browsers
            val f = frames[top]!!
            if (!f.hidden && !f.svg) handleBr()
            return
        }
        var k = top
        while (k >= 1 && frames[k]!!.tag != name) k--
        if (k < 1) return // stray closer
        while (top >= k) popFrame()
    }

    private fun popFrame() {
        val f = frames[top]!!
        if (!f.hidden && !f.svg) {
            if (f.isBlock) {
                endBlock(f)
            } else if (f.softBlock) {
                softSpace()
            }
        } else if (f.capture == CAPTURE_TITLE && !titleDone) {
            titleDone = true
            val t = Entities.collapse(titleSb.toString())
            if (t.isNotEmpty()) title = t
        }
        top--
        val p = frames[top]!!
        if (!p.hidden && !p.svg) setRunStyle(p.style)
    }

    private fun closeHead() {
        var k = top
        while (k >= 1 && frames[k]!!.tag != "head") k--
        if (k >= 1) while (top >= k) popFrame()
    }

    /**
     * HTML implied `</p>`: a new `<p>` closes an open `p` when only inline elements lie between. Without it,
     * HTML-style `<p>a<p>b<p>c` nests one level per paragraph: the first paragraph's class/alignment leaks into
     * all later ones and, past [MAX_DEPTH], paragraphs merge. A `p` inside another block (`<p><div><p>`) is
     * left alone, which keeps well-formed XHTML nesting intact.
     */
    private fun autoCloseParagraph() {
        var k = top
        while (k >= 1) {
            val f = frames[k]!!
            if (f.tag == "p") {
                while (top >= k) popFrame()
                return
            }
            if (f.isBlock || f.softBlock || f.tag in BLOCK || f.tag == "td" || f.tag == "th" || f.tag == "svg") return
            k--
        }
    }

    /** HTML implied end: a new [tag] closes an open one unless a [boundary] element lies between. */
    private fun autoClose(tag: String, boundary: Set<String>) {
        var k = top
        while (k >= 1) {
            val t = frames[k]!!.tag
            if (t == tag) {
                while (top >= k) popFrame()
                return
            }
            if (t in boundary) return
            k--
        }
    }

    private fun autoClose2(a: String, b: String, boundary: String) {
        var k = top
        while (k >= 1) {
            val t = frames[k]!!.tag
            if (t == a || t == b) {
                while (top >= k) popFrame()
                return
            }
            if (t == boundary || t == "table") return
            k--
        }
    }

    private fun autoCloseTableRow() {
        var k = top
        while (k >= 1) {
            val t = frames[k]!!.tag
            if (t == "tr") {
                while (top >= k) popFrame()
                return
            }
            if (t == "table") return
            k--
        }
    }

    // ================================================================ blocks

    private fun startBlock(f: Frame, idx: Int, name: String, r: MarkupReader, decl: CssDecl) {
        closeParagraph()
        pendingSoftBreak = false
        cellSepPending = false
        f.isBlock = true
        f.owner = idx
        f.blocksAtStart = blocks.size
        val level = headingLevel(name)
        if (level > 0) {
            f.heading = level
            f.indent = false
            f.align = headingAlign(r, decl)
        }
        when (name) {
            "ul", "ol" -> {
                f.listKind = if (name == "ol") LIST_OL else LIST_UL
                f.listIdx = idx
                f.listDepth++
                f.insetL += 1.2f
                f.counter = if (name == "ol") r.attr("start")?.trim()?.toIntOrNull() ?: 1 else 1
                f.listStyle = when (r.attr("type")?.trim()) {
                    "a" -> CssDecl.LS_LOWER_ALPHA
                    "A" -> CssDecl.LS_UPPER_ALPHA
                    "i" -> CssDecl.LS_LOWER_ROMAN
                    "I" -> CssDecl.LS_UPPER_ROMAN
                    "1" -> CssDecl.LS_DECIMAL
                    "disc" -> CssDecl.LS_DISC
                    "circle" -> CssDecl.LS_CIRCLE
                    "square" -> CssDecl.LS_SQUARE
                    else -> 0
                }
                if (publisherStyles && decl.has(CssDecl.LIST)) f.listStyle = decl.listStyle
            }
            "li" -> {
                f.indent = false
                f.liIdx = idx
                f.liPrefix = listPrefix(r, decl)
            }
            "blockquote" -> {
                f.insetL += 1.5f
                f.insetR += 1f
            }
            "dd" -> {
                f.insetL += 1.5f
                f.indent = false
            }
            "dt", "tr", "caption", "figcaption", "legend", "summary" -> f.indent = false
            "pre" -> {
                f.pre = true
                f.indent = false
                preSkipNewline = true
            }
            "center" -> f.align = Align.CENTER
            "table" -> f.inCell = false
        }
        if (publisherStyles) {
            if (level == 0) {
                r.attr("align")?.let { a -> alignAttr(a)?.let { f.align = it } }
                if (decl.has(CssDecl.ALIGN)) f.align = bodyAlign(decl.align)
                if (decl.has(CssDecl.INDENT)) f.indent = decl.indent
            }
            if (decl.has(CssDecl.WS)) {
                f.pre = decl.pre
                if (decl.pre) {
                    f.indent = false
                    preSkipNewline = true
                }
            }
            if (name != "body" && name != "html" && name != "ul" && name != "ol") {
                if (decl.has(CssDecl.ML)) f.insetL += decl.marginLeft.coerceIn(0f, MAX_INSET_STEP)
                if (decl.has(CssDecl.PL)) f.insetL += decl.paddingLeft.coerceIn(0f, MAX_INSET_STEP)
                if (f.insetL > MAX_INSET) f.insetL = MAX_INSET
            }
            if (decl.has(CssDecl.MT) && decl.marginTop >= MIN_MARGIN_EM && name != "body" && name != "html") {
                pendingMarginTop = maxOf(pendingMarginTop, decl.marginTop.coerceAtMost(MAX_MARGIN_EM))
            }
            if (decl.has(CssDecl.MB) && decl.marginBottom >= MIN_MARGIN_EM && name != "body" && name != "html") {
                f.marginBottom = decl.marginBottom.coerceAtMost(MAX_MARGIN_EM)
            }
            if (decl.has(CssDecl.BB) && decl.breakBefore) pendingBreak = true
            if (decl.has(CssDecl.BA) && decl.breakAfter) f.breakAfter = true
        }
    }

    private fun endBlock(f: Frame) {
        closeParagraph()
        pendingSoftBreak = false
        cellSepPending = false
        if (f.marginBottom > 0f && blocks.size > f.blocksAtStart) applyMarginBottom(f.marginBottom)
        if (f.breakAfter) pendingBreak = true
    }

    private fun applyMarginBottom(mb: Float) {
        val i = blocks.size - 1
        when (val b = blocks[i]) {
            is ParagraphBlock -> if (b.style.marginBottomEm < mb) {
                blocks[i] = ParagraphBlock(b.start, b.end, b.style.copy(marginBottomEm = mb))
            }
            is ImageBlock -> if (b.style.marginBottomEm < mb) {
                blocks[i] = ImageBlock(b.start, b.src, b.intrinsicWidth, b.intrinsicHeight, b.alt,
                    b.style.copy(marginBottomEm = mb))
            }
            is RuleBlock -> {}
        }
    }

    private fun headingAlign(r: MarkupReader, decl: CssDecl): Align {
        if (decl.has(CssDecl.ALIGN)) {
            if (publisherStyles) return headingAlignOf(decl.align)
            if (decl.align == CssDecl.A_CENTER) return Align.CENTER
        }
        if (publisherStyles) {
            val a = r.attr("align")?.trim()?.lowercase()
            when (a) {
                "left", "justify" -> return Align.LEFT
                "right" -> return Align.RIGHT
            }
        }
        return Align.CENTER
    }

    private fun headingAlignOf(a: Int): Align = when (a) {
        CssDecl.A_LEFT, CssDecl.A_JUSTIFY -> Align.LEFT
        CssDecl.A_RIGHT -> Align.RIGHT
        else -> Align.CENTER
    }

    /** Body text: only centre/right are publisher intent; left/justify defer to the user's alignment. */
    private fun bodyAlign(a: Int): Align = when (a) {
        CssDecl.A_CENTER -> Align.CENTER
        CssDecl.A_RIGHT -> Align.RIGHT
        else -> Align.DEFAULT
    }

    private fun alignAttr(a: String): Align? = when (a.trim().lowercase()) {
        "center", "middle" -> Align.CENTER
        "right" -> Align.RIGHT
        "left", "justify" -> Align.DEFAULT
        else -> null
    }

    private fun computeBlockStyle(f: Frame): BlockStyle {
        val insetL = round2(f.insetL)
        val insetR = round2(f.insetR)
        val indent = f.indent && !f.pre
        if (f.align == Align.DEFAULT && indent && f.heading == 0 && insetL == 0f && insetR == 0f && !f.pre) {
            return BlockStyle.BODY
        }
        return BlockStyle(
            align = f.align,
            indent = indent,
            headingLevel = f.heading,
            insetLeftEm = insetL,
            insetRightEm = insetR,
            keepWithNext = f.heading > 0,
            preformatted = f.pre,
        )
    }

    // ================================================================ paragraphs

    private fun paraHasChars(): Boolean = sb.length > paraContentStart

    /** A collapsible space at an inline boundary (soft block, `<br>` in a table cell), never doubled. */
    private fun softSpace() {
        if (paraOpen && paraHasChars() && sb[sb.length - 1] != ' ') pendingSpace = true
    }

    private fun openParagraph() {
        paraSep = sb.length
        if (blocks.isNotEmpty()) sb.append('\n')
        paraStart = sb.length
        paraOpen = true
        paraVisible = false
        paraBlank = false
        paraSoft = pendingSoftBreak
        pendingSoftBreak = false
        val f = frames[top]!!
        val owner = frames[f.owner]!!
        var st = owner.blockStyle ?: computeBlockStyle(owner).also { owner.blockStyle = it }
        if (paraSoft || pendingMarginTop > 0f || pendingBreak) {
            st = st.copy(
                softBreak = paraSoft,
                marginTopEm = maxOf(st.marginTopEm, pendingMarginTop),
                pageBreakBefore = pendingBreak || st.pageBreakBefore,
            )
        }
        pendingMarginTop = 0f
        pendingBreak = false
        paraStyle = st
        paraAnchors.clear()
        flushAnchors(paraStart)
        val li = f.liIdx
        if (li >= 0 && !paraSoft) {
            val lf = frames[li]!!
            val p = lf.liPrefix
            if (p != null) {
                sb.append(p)
                lf.liPrefix = null
            }
        }
        paraContentStart = sb.length
    }

    private fun closeParagraph() {
        if (!paraOpen) return
        paraOpen = false
        pendingSpace = false
        cellSepPending = false
        if (!paraStyle.preformatted) {
            var e = sb.length
            while (e > paraContentStart && sb[e - 1] == ' ') e--
            if (e < sb.length) truncate(e)
        }
        if (paraVisible) {
            blocks.add(ParagraphBlock(paraStart, sb.length, paraStyle))
            if (paraAnchors.isNotEmpty()) clampParaAnchors(sb.length)
            return
        }
        // Dropped or emptied: anchors inside now belong to the start of whatever block comes next.
        if (paraBlank && blocks.isNotEmpty() && !isEmptyParagraph(blocks[blocks.size - 1])) {
            truncate(paraStart)
            blocks.add(ParagraphBlock(paraStart, paraStart, paraStyle))
        } else {
            truncate(paraSep)
        }
        if (paraAnchors.isNotEmpty()) clampParaAnchors(paraStart)
    }

    private fun isEmptyParagraph(b: Block): Boolean = b is ParagraphBlock && b.start == b.end

    /** Ensures an open paragraph before content; flushes a pending cell separator or space. */
    private fun beginContent(blank: Boolean) {
        if (!paraOpen) openParagraph()
        if (cellSepPending) {
            cellSepPending = false
            pendingSpace = false
            if (paraHasChars()) {
                trimTrailingSpaces()
                sb.append(CELL_SEPARATOR)
            }
        } else if (pendingSpace) {
            pendingSpace = false
            sb.append(' ')
        }
        if (blank) paraBlank = true else paraVisible = true
    }

    private fun trimTrailingSpaces() {
        var e = sb.length
        while (e > paraContentStart && sb[e - 1] == ' ') e--
        if (e < sb.length) truncate(e)
    }

    private fun handleBr() {
        if (frames[top]!!.inCell) {
            softSpace()
            return
        }
        if (!paraOpen) openParagraph()
        paraBlank = true
        closeParagraph()
        pendingSoftBreak = true
    }

    private fun handleRule() {
        if (frames[top]!!.inCell) {
            softSpace()
            return
        }
        closeParagraph()
        pendingSoftBreak = false
        if (blocks.isNotEmpty()) sb.append('\n')
        val start = sb.length
        flushAnchors(start)
        blocks.add(RuleBlock(start))
    }

    private fun emitImage(srcRaw: String?, wAttr: String?, hAttr: String?, alt: String?) {
        val src = srcRaw?.trim()
        if (src.isNullOrEmpty() || src.startsWith("#") || EpubPaths.hasScheme(src)) return
        val path = EpubPaths.resolve(docDir, src)
        if (path.isEmpty()) return
        val canonical = if (resources == null) path else resources.imagePath(path) ?: return
        val continues = paraOpen && (paraVisible || paraBlank)
        closeParagraph()
        cellSepPending = false
        if (blocks.isNotEmpty()) sb.append('\n')
        val start = sb.length
        sb.append(OBJECT_CHAR)
        var st = IMAGE_STYLE
        if (pendingBreak || pendingMarginTop > 0f) {
            st = st.copy(pageBreakBefore = pendingBreak, marginTopEm = pendingMarginTop)
            pendingBreak = false
            pendingMarginTop = 0f
        }
        flushAnchors(start)
        blocks.add(ImageBlock(start, canonical, dimension(wAttr), dimension(hAttr), alt?.trim()?.takeIf { it.isNotEmpty() }, st))
        pendingSoftBreak = continues
    }

    private fun dimension(v: String?): Int {
        if (v == null) return 0
        val t = v.trim()
        if (t.endsWith("%")) return 0
        var i = 0
        var n = 0
        while (i < t.length && t[i].isDigit() && n < 100000) {
            n = n * 10 + (t[i] - '0')
            i++
        }
        return if (n in 1..20000) n else 0
    }

    // ================================================================ text

    private fun appendFlow(r: MarkupReader) {
        val s = r.source
        var i = r.textStart
        val end = r.textEnd
        val raw = r.textRaw
        while (i < end) {
            val c = s[i]
            if (isPlain(c)) {
                var j = i + 1
                while (j < end && isPlain(s[j])) j++
                beginContent(false)
                sb.append(s, i, j)
                i = j
                continue
            }
            if (c == '&' && !raw) {
                val e = Entities.parseAt(s, i, end)
                if (e >= 0) {
                    flowCodePoint((e ushr 32).toInt())
                    i = (e and 0xFFFFFFFFL).toInt()
                    continue
                }
                beginContent(false)
                sb.append('&')
                i++
                continue
            }
            flowCodePoint(c.code)
            i++
        }
    }

    private fun flowCodePoint(cp: Int) {
        when {
            cp == 0x20 || cp == 0x09 || cp == 0x0A || cp == 0x0D || cp == 0x0C || cp == 0x2028 || cp == 0x2029 -> {
                // collapses with a space already emitted before an inline boundary
                if (paraOpen && paraHasChars() && sb[sb.length - 1] != ' ') pendingSpace = true
            }
            cp < 0x20 || cp in 0x7F..0x9F || cp == 0xAD || cp == 0xFEFF || cp == 0xFFFC -> {}
            cp == 0xA0 -> {
                beginContent(true)
                sb.append('\u00A0')
            }
            cp == 0x3000 -> {
                val leading = !paraOpen || !paraHasChars()
                val soft = if (paraOpen) paraSoft else pendingSoftBreak
                if (leading && !soft) return
                beginContent(true)
                sb.append('\u3000')
            }
            else -> {
                beginContent(false)
                sb.appendCodePoint(cp)
            }
        }
    }

    private fun appendPre(r: MarkupReader) {
        val s = r.source
        var i = r.textStart
        val end = r.textEnd
        val raw = r.textRaw
        while (i < end) {
            var cp = s[i].code
            var next = i + 1
            if (cp == '&'.code && !raw) {
                val e = Entities.parseAt(s, i, end)
                if (e >= 0) {
                    cp = (e ushr 32).toInt()
                    next = (e and 0xFFFFFFFFL).toInt()
                }
            }
            if (cp == 0x0D) {
                if (next < end && s[next] == '\n') {
                    i = next
                    continue
                }
                cp = 0x0A
            }
            if (cp == 0x0A) {
                if (preSkipNewline) {
                    preSkipNewline = false
                } else {
                    if (!paraOpen) openParagraph()
                    paraBlank = true
                    closeParagraph()
                    pendingSoftBreak = true
                }
                i = next
                continue
            }
            preSkipNewline = false
            when {
                cp == 0x09 -> {
                    beginContent(true)
                    sb.append("    ")
                }
                cp == 0x20 || cp == 0xA0 || cp == 0x3000 -> {
                    beginContent(true)
                    sb.append(if (cp == 0x20) ' ' else cp.toChar())
                }
                cp < 0x20 || cp in 0x7F..0x9F || cp == 0xAD || cp == 0xFEFF || cp == 0xFFFC ||
                    cp == 0x2028 || cp == 0x2029 -> {}
                else -> {
                    beginContent(false)
                    sb.appendCodePoint(cp)
                }
            }
            i = next
        }
    }

    // ================================================================ runs

    private fun setRunStyle(ns: RunStyle) {
        if (ns === curStyle || ns == curStyle) return
        if (pendingSpace && paraOpen) { // the collapsed space belongs before the style change
            pendingSpace = false
            sb.append(' ')
        }
        closeRun()
        curStyle = ns
    }

    private fun closeRun() {
        val end = sb.length
        if (end > runStart && curStyle != RunStyle.PLAIN) addRun(runStart, end, curStyle)
        runStart = end
    }

    private fun addRun(start: Int, end: Int, style: RunStyle) {
        val n = runs.size
        if (n > 0) {
            val last = runs[n - 1]
            if (last.end == start && last.style == style) {
                runs[n - 1] = StyleRun(last.start, end, style)
                return
            }
        }
        runs.add(StyleRun(start, end, style))
    }

    private fun truncate(len: Int) {
        if (len >= sb.length) return
        sb.setLength(len)
        if (runStart > len) runStart = len
        while (runs.isNotEmpty()) {
            val last = runs[runs.size - 1]
            if (last.end <= len) break
            runs.removeAt(runs.size - 1)
            if (last.start < len) {
                runs.add(StyleRun(last.start, len, last.style))
                break
            }
        }
    }

    private fun runStyleFor(parent: RunStyle, name: String, r: MarkupReader, decl: CssDecl): RunStyle {
        var bold = parent.bold
        var italic = parent.italic
        var shift = parent.baselineShift
        var underline = parent.underline
        var strike = parent.strike
        var mono = parent.monospace
        var link = parent.link
        var factor = 1f
        var scale = parent.sizeScale
        when (name) {
            "b", "strong", "th", "dt" -> bold = true
            "i", "em", "cite", "dfn", "var", "address" -> italic = true
            "u", "ins" -> underline = true
            "s", "strike", "del" -> strike = true
            "sup" -> {
                shift = 1
                factor = 0.75f
            }
            "sub" -> {
                shift = -1
                factor = 0.75f
            }
            "small" -> factor = 0.85f
            "big" -> factor = 1.2f
            "code", "tt", "kbd", "samp", "pre" -> mono = true
            "a" -> r.attr("href")?.trim()?.takeIf { it.isNotEmpty() }?.let { link = it }
            else -> {
                val level = headingLevel(name)
                if (level > 0) {
                    bold = true
                    factor = HEADING_SCALE[level]
                }
            }
        }
        if (publisherStyles && !decl.isEmpty()) {
            if (decl.has(CssDecl.WEIGHT)) bold = decl.bold
            if (decl.has(CssDecl.STYLE)) italic = decl.italic
            if (decl.has(CssDecl.DECOR)) {
                underline = decl.underline
                strike = decl.strike
            }
            if (decl.has(CssDecl.VALIGN) && decl.vAlign != shift) {
                shift = decl.vAlign
                factor = if (shift != 0) 0.75f else 1f
            }
            if (decl.has(CssDecl.SIZE) && name != "body" && name != "html" && !(ignoreBookSizes && headingLevel(name) == 0)) {
                if (decl.sizeAbs) {
                    scale = decl.size
                    factor = 1f
                } else {
                    factor = decl.size
                }
            }
        }
        if (factor != 1f || scale != parent.sizeScale) {
            scale = quantize((scale * factor).coerceIn(0.5f, 2.5f))
        }
        if (bold == parent.bold && italic == parent.italic && scale == parent.sizeScale &&
            shift == parent.baselineShift && underline == parent.underline && strike == parent.strike &&
            mono == parent.monospace && link == parent.link
        ) return parent
        return RunStyle(bold, italic, scale, shift, underline, strike, mono, link)
    }

    // ================================================================ lists

    private fun listPrefix(r: MarkupReader, decl: CssDecl): String? {
        val lf = frames[top]!!.listIdx.let { if (it >= 0) frames[it] else null }
        var ls = lf?.listStyle ?: 0
        if (publisherStyles && decl.has(CssDecl.LIST)) ls = decl.listStyle
        if (ls == CssDecl.LS_NONE) {
            if (lf != null && lf.listKind == LIST_OL) lf.counter++
            return null
        }
        if (lf == null) return "• "
        if (lf.listKind == LIST_OL) {
            val n = r.attr("value")?.trim()?.toIntOrNull() ?: lf.counter
            lf.counter = n + 1
            return ListMarkers.ordered(n, ls) + ". "
        }
        return when (ls) {
            CssDecl.LS_DISC -> "• "
            CssDecl.LS_CIRCLE -> "◦ "
            CssDecl.LS_SQUARE -> "▪ "
            CssDecl.LS_DECIMAL, CssDecl.LS_LOWER_ALPHA, CssDecl.LS_UPPER_ALPHA, CssDecl.LS_LOWER_ROMAN,
            CssDecl.LS_UPPER_ROMAN, CssDecl.LS_HANGUL, CssDecl.LS_HANGUL_CONSONANT -> {
                val n = lf.counter
                lf.counter = n + 1
                ListMarkers.ordered(n, ls) + ". "
            }
            else -> when (lf.listDepth) {
                1 -> "• "
                2 -> "◦ "
                else -> "▪ "
            }
        }
    }

    // ================================================================ anchors

    private fun recordAnchors(r: MarkupReader, name: String, id: String?) {
        if (id != null) recordAnchor(id)
        if (name == "a") r.attr("name")?.takeIf { it.isNotEmpty() }?.let { recordAnchor(it) }
    }

    private fun recordAnchor(id: String) {
        if (paraOpen) {
            // a pending collapsed space will be emitted before the anchored content
            if (!anchors.containsKey(id)) {
                anchors[id] = sb.length + (if (pendingSpace) 1 else 0)
                paraAnchors.add(id)
            }
        } else {
            pendingAnchors.add(id)
        }
    }

    /** Anchors set inside the paragraph being closed may not point past [limit] (it was trimmed or dropped). */
    private fun clampParaAnchors(limit: Int) {
        for (id in paraAnchors) {
            val v = anchors[id] ?: continue
            if (v > limit) anchors[id] = limit
        }
        paraAnchors.clear()
    }

    private fun flushAnchors(pos: Int) {
        if (pendingAnchors.isEmpty()) return
        for (id in pendingAnchors) if (!anchors.containsKey(id)) anchors[id] = pos
        pendingAnchors.clear()
    }

    // ================================================================ CSS

    private fun addCssText(css: String) {
        if (css.isBlank()) return
        val sheet = CssParser.parse(css)
        // `<style>@import url(../Styles/main.css);</style>` is a common way to pull in the book's style sheet.
        if (resources != null) {
            for (href in sheet.imports) {
                if (EpubPaths.hasScheme(href)) continue
                val list = resources.styleSheets(EpubPaths.resolve(docDir, href))
                if (list.isNotEmpty()) cascade.add(list)
            }
        }
        cascade.add(listOf(sheet))
    }

    private fun mediaOk(media: String): Boolean {
        val m = media.lowercase()
        return !(m.contains("print") || m.contains("amzn-mobi") || m.contains("speech"))
    }

    private fun computeDecl(name: String, id: String?, classAttr: String?, styleAttr: String?): CssDecl {
        var d = if (cascade.isEmpty) CssDecl.EMPTY else cascade.compute(name, id, classAttr, matcher)
        if (!styleAttr.isNullOrBlank()) {
            val cache = inlineCssCache ?: HashMap<String, CssDecl?>().also { inlineCssCache = it }
            val inline = if (cache.containsKey(styleAttr)) cache[styleAttr] else CssParser.parseInline(styleAttr).also {
                if (cache.size < 512) cache[styleAttr] = it
            }
            if (inline != null) {
                d = d.copy()
                d.mergeFrom(inline)
            }
        }
        return d
    }

    private fun isPageBreakMarker(r: MarkupReader): Boolean {
        val t = r.attr("epub:type")
        if (t != null && t.contains("pagebreak")) return true
        val role = r.attr("role")
        return role != null && role.contains("doc-pagebreak")
    }

    private var matchBudget = 0

    private val matcher = object : CssCascade.Matcher {
        override fun matches(sel: CssSelector): Boolean {
            matchBudget = MATCH_BUDGET
            return matchFrom(sel, sel.parts.size - 1, top, false) == MATCHED
        }
    }

    /**
     * Right-to-left selector matching. Returns [MATCHED], [FAIL_LOCAL] (another ancestor may still match the
     * enclosing descendant combinator) or [FAIL_GLOBAL] (ran out of ancestors: no higher ancestor can match
     * either, so enclosing descendant loops stop). Without the global cut, `.x div div div` against deeply
     * nested divs backtracks through every ancestor combination (seconds per element); [matchBudget] bounds
     * whatever is left.
     */
    private fun matchFrom(sel: CssSelector, pi: Int, fi: Int, prevSibling: Boolean): Int {
        if (fi < 1 || --matchBudget < 0) return FAIL_GLOBAL
        if (!compoundMatches(sel.parts[pi], frames[fi]!!, prevSibling)) return FAIL_LOCAL
        if (pi == 0) return MATCHED
        return when (sel.combinators[pi - 1]) {
            '>' -> matchFrom(sel, pi - 1, fi - 1, false)
            '+' -> if (prevSibling || frames[fi]!!.prevTag == null) FAIL_LOCAL else matchFrom(sel, pi - 1, fi, true)
            else -> {
                var a = fi - 1
                while (a >= 1) {
                    val res = matchFrom(sel, pi - 1, a, false)
                    if (res != FAIL_LOCAL) return res
                    a--
                }
                FAIL_GLOBAL
            }
        }
    }

    private fun compoundMatches(c: CssCompound, f: Frame, prevSibling: Boolean): Boolean {
        val tag = if (prevSibling) f.prevTag else f.tag
        val id = if (prevSibling) f.prevId else f.id
        val cls = if (prevSibling) f.prevClass else f.classAttr
        val first = if (prevSibling) f.childIndex == 1 else f.childIndex == 0
        if (c.tag != null && c.tag != tag) return false
        if (c.id != null && c.id != id) return false
        if (c.firstChild && !first) return false
        for (k in c.classes) if (cls == null || !CssCascade.hasClass(cls, k)) return false
        return true
    }

    // ================================================================ finish

    private fun finish(): SectionContent {
        while (top > 0) popFrame()
        closeParagraph()
        closeRun()
        while (blocks.isNotEmpty() && isEmptyParagraph(blocks[blocks.size - 1])) {
            val b = blocks.removeAt(blocks.size - 1)
            truncate(if (blocks.isEmpty()) 0 else b.start - 1)
        }
        if (!titleDone && titleSb.isNotEmpty()) {
            val t = Entities.collapse(titleSb.toString())
            if (t.isNotEmpty()) title = t
        }
        val text = sb.toString()
        flushAnchors(text.length)
        val len = text.length
        for (e in anchors.entries) if (e.value > len) e.setValue(len)
        return SectionContent(text, blocks, runs, anchors)
    }

    companion object {
        private const val MAX_DEPTH = 400
        private const val MATCHED = 0
        private const val FAIL_LOCAL = 1
        private const val FAIL_GLOBAL = 2
        /** Compound checks allowed per selector evaluation (typical selectors need < 20). */
        private const val MATCH_BUDGET = 4000
        private const val LIST_UL = 1
        private const val LIST_OL = 2
        private const val CELL_SEPARATOR = "  ·  "
        private const val CAPTURE_CSS = 1
        private const val CAPTURE_TITLE = 2
        /** CSS vertical margins below this are paragraph spacing, which the user's setting replaces. */
        const val MIN_MARGIN_EM = 1f
        private const val MAX_MARGIN_EM = 3f
        private const val MAX_INSET_STEP = 4f
        private const val MAX_INSET = 8f

        private val HEADING_SCALE = floatArrayOf(1f, 1.5f, 1.35f, 1.2f, 1.1f, 1f, 1f)
        private val IMAGE_STYLE = BlockStyle(align = Align.CENTER, indent = false)

        private val VOID = hashSetOf(
            "br", "hr", "img", "image", "wbr", "meta", "link", "input", "col", "area", "base", "embed", "param",
            "source", "track", "keygen", "basefont", "frame", "isindex",
        )

        private val BLOCK = hashSetOf(
            "p", "div", "h1", "h2", "h3", "h4", "h5", "h6", "blockquote", "li", "dt", "dd", "pre", "section",
            "article", "header", "footer", "aside", "figure", "figcaption", "table", "tr", "ul", "ol", "dl", "body",
            "html", "center", "address", "nav", "main", "hgroup", "caption", "thead", "tbody", "tfoot", "details",
            "summary", "fieldset", "legend", "form", "dir", "menu", "listing", "plaintext", "xmp",
        )

        private val TABLE_STRUCT = hashSetOf("table", "tr", "thead", "tbody", "tfoot", "caption")
        private val LIST_BOUNDARY = hashSetOf("ul", "ol", "menu", "dir", "table")

        private fun headingLevel(name: String): Int =
            if (name.length == 2 && name[0] == 'h' && name[1] in '1'..'6') name[1] - '0' else 0

        private fun quantize(v: Float): Float = Math.round(v * 20f) / 20f

        private fun round2(v: Float): Float = Math.round(v * 100f) / 100f

        private fun isPlain(c: Char): Boolean {
            if (c < '\u007F') return c > ' ' && c != '&'
            if (c <= '\u00A0') return false
            return c != '\u00AD' && c != '\u3000' && c != '\u2028' && c != '\u2029' && c != '\uFEFF' && c != '\uFFFC'
        }
    }
}

/** Cascade over the sheets seen so far in one document, with a per (tag, class, id) cache. */
internal class CssCascade {
    fun interface Matcher {
        /** Whether the full selector matches the element currently being opened. */
        fun matches(sel: CssSelector): Boolean
    }

    private val sheets = ArrayList<CssSheet>()
    private val cache = HashMap<String, Entry>()
    private val keyBuilder = StringBuilder(64)

    private class Entry(@JvmField val rules: Array<CssRule>, @JvmField val merged: CssDecl?)

    val isEmpty: Boolean get() = sheets.isEmpty()

    fun add(list: List<CssSheet>) {
        var added = false
        for (s in list) {
            if (s.ruleCount > 0 && sheets.size < 64) {
                sheets.add(s)
                added = true
            }
        }
        if (added) cache.clear()
    }

    fun compute(tag: String, id: String?, classAttr: String?, matcher: Matcher): CssDecl {
        val idRelevant = id != null && sheets.any { it.byId.containsKey(id) }
        val kb = keyBuilder
        kb.setLength(0)
        kb.append(tag).append('\u0001')
        if (classAttr != null) kb.append(classAttr)
        if (idRelevant) kb.append('\u0001').append(id)
        val key = kb.toString()
        val entry = cache[key] ?: build(tag, if (idRelevant) id else null, classAttr).also {
            if (cache.size < 4096) cache[key] = it
        }
        entry.merged?.let { return it }
        val d = CssDecl()
        for (rule in entry.rules) {
            if (rule.selector.simple || matcher.matches(rule.selector)) d.mergeFrom(rule.decl)
        }
        return d
    }

    private fun build(tag: String, id: String?, classAttr: String?): Entry {
        val classes = if (classAttr == null) NO_CLASSES else splitClasses(classAttr)
        val found = ArrayList<CssRule>()
        val ranks = ArrayList<Long>()
        for ((si, sheet) in sheets.withIndex()) {
            fun consider(list: List<CssRule>?) {
                if (list == null) return
                for (rule in list) {
                    val s = rule.selector.subject
                    if (s.tag != null && s.tag != tag) continue
                    if (s.id != null && s.id != id) continue
                    var ok = true
                    for (c in s.classes) if (c !in classes) { ok = false; break }
                    if (!ok) continue
                    if (found.any { it === rule }) continue
                    found.add(rule)
                    ranks.add(rank(rule, si))
                }
            }
            if (id != null) consider(sheet.byId[id])
            for (c in classes) consider(sheet.byClass[c])
            consider(sheet.byTag[tag])
            consider(sheet.universal)
        }
        if (found.isEmpty()) return EMPTY_ENTRY
        // insertion sort by rank (lists are short)
        val n = found.size
        val rs = found.toTypedArray()
        val rk = LongArray(n) { ranks[it] }
        for (i in 1 until n) {
            val r = rs[i]
            val k = rk[i]
            var j = i - 1
            while (j >= 0 && rk[j] > k) {
                rs[j + 1] = rs[j]
                rk[j + 1] = rk[j]
                j--
            }
            rs[j + 1] = r
            rk[j + 1] = k
        }
        val allSimple = rs.all { it.selector.simple }
        val merged = if (allSimple) CssDecl().also { d -> for (r in rs) d.mergeFrom(r.decl) } else null
        return Entry(rs, merged)
    }

    private fun rank(rule: CssRule, sheetIndex: Int): Long =
        (if (rule.important) 1L shl 62 else 0L) or
            (rule.specificity.toLong().coerceAtMost(0x1FFFFF) shl 40) or
            (sheetIndex.toLong().coerceAtMost(0xFFFF) shl 24) or
            rule.order.toLong().coerceAtMost(0xFFFFFF)

    companion object {
        private val NO_CLASSES = emptyArray<String>()
        private val EMPTY_ENTRY = Entry(emptyArray(), CssDecl.EMPTY)

        fun splitClasses(attr: String): Array<String> {
            val out = ArrayList<String>(2)
            var i = 0
            val n = attr.length
            while (i < n) {
                while (i < n && attr[i] <= ' ') i++
                val st = i
                while (i < n && attr[i] > ' ') i++
                if (i > st) out.add(attr.substring(st, i))
            }
            return out.toTypedArray()
        }

        /** Whether the whitespace-separated class list [attr] contains [cls]. */
        fun hasClass(attr: String, cls: String): Boolean {
            var from = 0
            while (true) {
                val i = attr.indexOf(cls, from)
                if (i < 0) return false
                val e = i + cls.length
                if ((i == 0 || attr[i - 1] <= ' ') && (e == attr.length || attr[e] <= ' ')) return true
                from = i + 1
            }
        }
    }
}

/** Ordered-list marker text. */
internal object ListMarkers {
    private val HANGUL = "가나다라마바사아자차카타파하"
    private val CONSONANT = "ㄱㄴㄷㄹㅁㅂㅅㅇㅈㅊㅋㅌㅍㅎ"

    fun ordered(n: Int, style: Int): String = when (style) {
        CssDecl.LS_LOWER_ALPHA -> alpha(n, 'a')
        CssDecl.LS_UPPER_ALPHA -> alpha(n, 'A')
        CssDecl.LS_LOWER_ROMAN -> roman(n)?.lowercase() ?: n.toString()
        CssDecl.LS_UPPER_ROMAN -> roman(n) ?: n.toString()
        CssDecl.LS_HANGUL -> if (n >= 1) HANGUL[(n - 1) % HANGUL.length].toString() else n.toString()
        CssDecl.LS_HANGUL_CONSONANT -> if (n >= 1) CONSONANT[(n - 1) % CONSONANT.length].toString() else n.toString()
        else -> n.toString()
    }

    private fun alpha(n: Int, base: Char): String {
        if (n < 1) return n.toString()
        val sb = StringBuilder()
        var v = n
        while (v > 0) {
            v--
            sb.append(base + v % 26)
            v /= 26
        }
        return sb.reverse().toString()
    }

    private fun roman(n: Int): String? {
        if (n < 1 || n > 3999) return null
        val vals = intArrayOf(1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1)
        val syms = arrayOf("M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I")
        val sb = StringBuilder()
        var v = n
        for (i in vals.indices) {
            while (v >= vals[i]) {
                sb.append(syms[i])
                v -= vals[i]
            }
        }
        return sb.toString()
    }
}
