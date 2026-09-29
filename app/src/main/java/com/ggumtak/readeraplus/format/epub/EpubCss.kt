package com.ggumtak.readeraplus.format.epub

/**
 * Mini CSS for EPUB content: only the properties a reflowing black-and-white reader honours (see
 * ARCHITECTURE "Mini CSS"). Fonts, colours, backgrounds, line-height and letter-spacing are ignored on purpose:
 * the user's reading settings win.
 */
internal class CssDecl {
    @JvmField var mask = 0
    /** Raw text-align: [A_LEFT], [A_CENTER], [A_RIGHT], [A_JUSTIFY]. */
    @JvmField var align = 0
    @JvmField var indent = true
    @JvmField var bold = false
    @JvmField var italic = false
    @JvmField var size = 1f
    /** [size] is absolute (rem / absolute keyword) rather than relative to the parent. */
    @JvmField var sizeAbs = false
    @JvmField var displayNone = false
    @JvmField var marginTop = 0f
    @JvmField var marginBottom = 0f
    @JvmField var marginLeft = 0f
    @JvmField var paddingLeft = 0f
    @JvmField var breakBefore = false
    @JvmField var breakAfter = false
    @JvmField var underline = false
    @JvmField var strike = false
    @JvmField var vAlign = 0
    @JvmField var pre = false
    @JvmField var listStyle = 0

    fun isEmpty(): Boolean = mask == 0

    fun has(bit: Int): Boolean = (mask and bit) != 0

    /** Copies every property set in [o] over this one. */
    fun mergeFrom(o: CssDecl) {
        val m = o.mask
        if (m == 0) return
        if (m and ALIGN != 0) align = o.align
        if (m and INDENT != 0) indent = o.indent
        if (m and WEIGHT != 0) bold = o.bold
        if (m and STYLE != 0) italic = o.italic
        if (m and SIZE != 0) {
            size = o.size
            sizeAbs = o.sizeAbs
        }
        if (m and DISPLAY != 0) displayNone = o.displayNone
        if (m and MT != 0) marginTop = o.marginTop
        if (m and MB != 0) marginBottom = o.marginBottom
        if (m and ML != 0) marginLeft = o.marginLeft
        if (m and PL != 0) paddingLeft = o.paddingLeft
        if (m and BB != 0) breakBefore = o.breakBefore
        if (m and BA != 0) breakAfter = o.breakAfter
        if (m and DECOR != 0) {
            underline = o.underline
            strike = o.strike
        }
        if (m and VALIGN != 0) vAlign = o.vAlign
        if (m and WS != 0) pre = o.pre
        if (m and LIST != 0) listStyle = o.listStyle
        mask = mask or m
    }

    fun copy(): CssDecl = CssDecl().also { it.mergeFrom(this) }

    companion object {
        const val ALIGN = 1
        const val INDENT = 1 shl 1
        const val WEIGHT = 1 shl 2
        const val STYLE = 1 shl 3
        const val SIZE = 1 shl 4
        const val DISPLAY = 1 shl 5
        const val MT = 1 shl 6
        const val MB = 1 shl 7
        const val ML = 1 shl 8
        const val PL = 1 shl 9
        const val BB = 1 shl 10
        const val BA = 1 shl 11
        const val DECOR = 1 shl 12
        const val VALIGN = 1 shl 13
        const val WS = 1 shl 14
        const val LIST = 1 shl 15

        const val A_LEFT = 1
        const val A_CENTER = 2
        const val A_RIGHT = 3
        const val A_JUSTIFY = 4

        const val LS_NONE = 1
        const val LS_DISC = 2
        const val LS_CIRCLE = 3
        const val LS_SQUARE = 4
        const val LS_DECIMAL = 5
        const val LS_LOWER_ALPHA = 6
        const val LS_UPPER_ALPHA = 7
        const val LS_LOWER_ROMAN = 8
        const val LS_UPPER_ROMAN = 9
        const val LS_HANGUL = 10
        const val LS_HANGUL_CONSONANT = 11

        /** Shared empty declaration: never mutate. */
        @JvmField val EMPTY = CssDecl()
    }
}

/** One compound selector (`tag#id.a.b:first-child`); null fields match anything. */
internal class CssCompound(
    @JvmField val tag: String?,
    @JvmField val id: String?,
    @JvmField val classes: Array<String>,
    @JvmField val firstChild: Boolean,
)

/**
 * A selector as compounds left to right with the combinators between them (' ' descendant, '>' child,
 * '+' adjacent sibling). [parts] is never empty; the last part is the subject.
 */
internal class CssSelector(@JvmField val parts: Array<CssCompound>, @JvmField val combinators: CharArray) {
    val subject: CssCompound get() = parts[parts.size - 1]
    /** Matching needs nothing but the element itself (cacheable per tag/class/id). */
    val simple: Boolean = parts.size == 1 && !parts[0].firstChild
}

/** A (selector, declarations) pair. [rank] orders the cascade: importance, specificity, source order. */
internal class CssRule(
    @JvmField val selector: CssSelector,
    @JvmField val decl: CssDecl,
    @JvmField val important: Boolean,
    @JvmField val specificity: Int,
    @JvmField val order: Int,
)

/** A parsed style sheet with its rules indexed by the subject's id / first class / tag. */
internal class CssSheet(rules: List<CssRule>, /** Resolved-later `@import` hrefs, in order. */ val imports: List<String>) {
    val ruleCount: Int = rules.size
    val byId = HashMap<String, ArrayList<CssRule>>()
    val byClass = HashMap<String, ArrayList<CssRule>>()
    val byTag = HashMap<String, ArrayList<CssRule>>()
    val universal = ArrayList<CssRule>()

    init {
        for (r in rules) {
            val s = r.selector.subject
            when {
                s.id != null -> byId.getOrPut(s.id) { ArrayList(2) }.add(r)
                s.classes.isNotEmpty() -> byClass.getOrPut(s.classes[0]) { ArrayList(2) }.add(r)
                s.tag != null -> byTag.getOrPut(s.tag) { ArrayList(2) }.add(r)
                else -> universal.add(r)
            }
        }
    }

    companion object {
        @JvmField val EMPTY = CssSheet(emptyList(), emptyList())
    }
}

/** CSS text → [CssSheet]. Tolerant: anything unparseable is skipped. */
internal object CssParser {
    private const val MAX_RULES = 20000

    fun parse(css: String): CssSheet {
        val text = stripComments(css)
        val rules = ArrayList<CssRule>()
        val imports = ArrayList<String>()
        try {
            parseRules(text, 0, text.length, rules, imports, 0)
        } catch (_: RuntimeException) {
            // keep what was parsed
        }
        return CssSheet(rules, imports)
    }

    /** Parses a `style="..."` attribute. Returns null when nothing relevant is declared. */
    fun parseInline(style: String): CssDecl? {
        val text = stripComments(style)
        val normal = CssDecl()
        val important = CssDecl()
        parseDeclarations(text, 0, text.length, normal, important)
        normal.mergeFrom(important)
        return if (normal.isEmpty()) null else normal
    }

    /**
     * Removes comments and the SGML/XML wrappers that `<style>` content in XHTML often carries (`<!-- -->`,
     * `<![CDATA[ ]]>`): the tokenizer returns style content raw, so they would otherwise break the first rule.
     */
    private fun stripComments(css: String): String {
        if (css.indexOf("/*") < 0 && css.indexOf("<!") < 0 && css.indexOf("]]>") < 0 && css.indexOf("-->") < 0) {
            return css
        }
        val sb = StringBuilder(css.length)
        var i = 0
        val n = css.length
        var quote = 0.toChar()
        while (i < n) {
            val c = css[i]
            if (quote.code != 0) {
                sb.append(c)
                if (c == '\\' && i + 1 < n) {
                    sb.append(css[i + 1])
                    i += 2
                    continue
                }
                if (c == quote || c == '\n') quote = 0.toChar()
                i++
                continue
            }
            if (c == '/' && i + 1 < n && css[i + 1] == '*') {
                val e = css.indexOf("*/", i + 2)
                i = if (e < 0) n else e + 2
                sb.append(' ')
                continue
            }
            if (c == '<' && css.startsWith("<!--", i)) {
                i += 4
                continue
            }
            if (c == '<' && css.startsWith("<![CDATA[", i)) {
                i += 9
                sb.append(' ')
                continue
            }
            if (c == ']' && css.startsWith("]]>", i)) {
                i += 3
                sb.append(' ')
                continue
            }
            if (c == '-' && css.startsWith("-->", i)) {
                i += 3
                continue
            }
            if (c == '"' || c == '\'') quote = c
            sb.append(c)
            i++
        }
        return sb.toString()
    }

    private fun parseRules(s: String, start: Int, end: Int, rules: ArrayList<CssRule>, imports: ArrayList<String>, depth: Int) {
        var i = start
        while (i < end && rules.size < MAX_RULES) {
            while (i < end && s[i] <= ' ') i++
            if (i >= end) break
            val c = s[i]
            if (c == '}' || c == ';') {
                i++
                continue
            }
            if (c == '@') {
                var j = i + 1
                while (j < end && (s[j].isLetterOrDigit() || s[j] == '-')) j++
                val kw = s.substring(i + 1, j).lowercase()
                val stop = findPreludeEnd(s, j, end)
                if (stop >= end) break
                if (s[stop] == ';' || s[stop] == '}') {
                    if (kw == "import") parseImport(s.substring(j, stop))?.let { imports.add(it) }
                    i = stop + 1
                    continue
                }
                val blockEnd = findBlockEnd(s, stop + 1, end)
                if (kw == "media" && depth < 4 && mediaApplies(s.substring(j, stop))) {
                    parseRules(s, stop + 1, blockEnd, rules, imports, depth + 1)
                } else if (kw == "supports" && depth < 4) {
                    parseRules(s, stop + 1, blockEnd, rules, imports, depth + 1)
                }
                i = blockEnd + 1
                continue
            }
            val stop = findPreludeEnd(s, i, end)
            if (stop >= end) break
            if (s[stop] != '{') {
                i = stop + 1
                continue
            }
            val blockEnd = findBlockEnd(s, stop + 1, end)
            val normal = CssDecl()
            val important = CssDecl()
            parseDeclarations(s, stop + 1, blockEnd, normal, important)
            if (!normal.isEmpty() || !important.isEmpty()) {
                for (sel in splitSelectors(s.substring(i, stop))) {
                    val parsed = parseSelector(sel) ?: continue
                    val spec = specificity(parsed)
                    if (!normal.isEmpty()) rules.add(CssRule(parsed, normal, false, spec, rules.size))
                    if (!important.isEmpty()) rules.add(CssRule(parsed, important, true, spec, rules.size))
                }
            }
            i = blockEnd + 1
        }
    }

    /** Index of the first '{', ';' or '}' at nesting level 0 outside strings (or [end]). */
    private fun findPreludeEnd(s: String, from: Int, end: Int): Int {
        var i = from
        var paren = 0
        var quote = 0.toChar()
        while (i < end) {
            val c = s[i]
            if (quote.code != 0) {
                if (c == '\\') i++ else if (c == quote) quote = 0.toChar()
            } else when (c) {
                '"', '\'' -> quote = c
                '(' -> paren++
                ')' -> if (paren > 0) paren--
                '{', ';', '}' -> if (paren == 0) return i
            }
            i++
        }
        return end
    }

    /** Index of the '}' closing a block whose content starts at [from] (or [end]). */
    private fun findBlockEnd(s: String, from: Int, end: Int): Int {
        var i = from
        var depth = 0
        var quote = 0.toChar()
        while (i < end) {
            val c = s[i]
            if (quote.code != 0) {
                if (c == '\\') i++ else if (c == quote) quote = 0.toChar()
            } else when (c) {
                '"', '\'' -> quote = c
                '{' -> depth++
                '}' -> {
                    if (depth == 0) return i
                    depth--
                }
            }
            i++
        }
        return end
    }

    private fun mediaApplies(query: String): Boolean {
        val q = query.lowercase()
        if (q.contains("not ")) return false
        return !(q.contains("print") || q.contains("amzn-mobi") || q.contains("speech") || q.contains("aural"))
    }

    private fun parseImport(prelude: String): String? {
        val p = prelude.trim()
        val u = if (p.startsWith("url(", ignoreCase = true)) {
            val e = p.indexOf(')')
            if (e < 0) return null
            p.substring(4, e)
        } else {
            p.substringBefore(' ')
        }
        val v = u.trim().trim('"', '\'').trim()
        return v.ifEmpty { null }
    }

    private fun splitSelectors(prelude: String): List<String> {
        val out = ArrayList<String>(2)
        var depth = 0
        var st = 0
        for (i in prelude.indices) {
            when (prelude[i]) {
                '(', '[' -> depth++
                ')', ']' -> if (depth > 0) depth--
                ',' -> if (depth == 0) {
                    out.add(prelude.substring(st, i))
                    st = i + 1
                }
            }
        }
        out.add(prelude.substring(st))
        return out
    }

    /**
     * Parses a selector. Supports type, `*`, `.class`, `#id`, `:first-child`, descendant, child and adjacent
     * sibling combinators. Selectors with pseudo-elements, attribute selectors, other pseudo-classes or the
     * general sibling combinator return null (dropped: better unstyled than wrongly styled).
     */
    fun parseSelector(raw: String): CssSelector? {
        val sel = raw.trim()
        if (sel.isEmpty() || sel.length > 400) return null
        val parts = ArrayList<CssCompound>(3)
        val combs = StringBuilder()
        var i = 0
        val n = sel.length
        var pendingComb = 0.toChar()
        while (i < n) {
            val c = sel[i]
            if (c <= ' ') {
                if (parts.isNotEmpty() && pendingComb.code == 0) pendingComb = ' '
                i++
                continue
            }
            if (c == '>' || c == '+' || c == '~') {
                if (c == '~' || parts.isEmpty()) return null
                pendingComb = c
                i++
                continue
            }
            // a compound
            var tag: String? = null
            var id: String? = null
            var classes: ArrayList<String>? = null
            var firstChild = false
            if (c == '*') {
                i++
            } else if (isIdentStart(c)) {
                val st = i
                while (i < n && isIdentChar(sel[i])) i++
                tag = sel.substring(st, i).lowercase()
            }
            if (i < n && sel[i] == '|') { // namespace prefix: ns|tag
                i++
                tag = null
                if (i < n && sel[i] == '*') {
                    i++
                } else {
                    val st = i
                    while (i < n && isIdentChar(sel[i])) i++
                    if (i > st) tag = sel.substring(st, i).lowercase()
                }
            }
            loop@ while (i < n) {
                when (sel[i]) {
                    '.' -> {
                        val st = ++i
                        while (i < n && isIdentChar(sel[i])) i++
                        if (i == st) return null
                        if (classes == null) classes = ArrayList(2)
                        classes.add(sel.substring(st, i))
                    }
                    '#' -> {
                        val st = ++i
                        while (i < n && isIdentChar(sel[i])) i++
                        if (i == st) return null
                        id = sel.substring(st, i)
                    }
                    ':' -> {
                        if (i + 1 < n && sel[i + 1] == ':') return null
                        val st = ++i
                        while (i < n && (isIdentChar(sel[i]))) i++
                        val pseudo = sel.substring(st, i).lowercase()
                        if (i < n && sel[i] == '(') return null
                        when (pseudo) {
                            "first-child" -> firstChild = true
                            "link", "visited" -> {}
                            "root" -> if (tag == null) tag = "html"
                            else -> return null
                        }
                    }
                    '[' -> return null
                    else -> break@loop
                }
            }
            if (i < n && !(sel[i] <= ' ' || sel[i] == '>' || sel[i] == '+' || sel[i] == '~')) return null
            if (parts.isNotEmpty()) {
                combs.append(if (pendingComb.code == 0) ' ' else pendingComb)
            }
            pendingComb = 0.toChar()
            parts.add(CssCompound(tag, id, classes?.toTypedArray() ?: NO_CLASSES, firstChild))
        }
        if (parts.isEmpty() || pendingComb == '>' || pendingComb == '+') return null
        return CssSelector(parts.toTypedArray(), combs.toString().toCharArray())
    }

    private val NO_CLASSES = emptyArray<String>()

    private fun specificity(sel: CssSelector): Int {
        var ids = 0
        var cls = 0
        var tags = 0
        for (p in sel.parts) {
            if (p.id != null) ids++
            cls += p.classes.size + (if (p.firstChild) 1 else 0)
            if (p.tag != null) tags++
        }
        return minOf(ids, 15) * 10000 + minOf(cls, 99) * 100 + minOf(tags, 99)
    }

    private fun isIdentStart(c: Char): Boolean = c in 'a'..'z' || c in 'A'..'Z' || c == '_' || c == '-' || c.code >= 0x80
    private fun isIdentChar(c: Char): Boolean = isIdentStart(c) || c in '0'..'9' || c == '\\'

    /** Parses `prop: value [!important]; ...` in s[start, end) into [normal] / [important]. */
    fun parseDeclarations(s: String, start: Int, end: Int, normal: CssDecl, important: CssDecl) {
        var i = start
        while (i < end) {
            var j = i
            var paren = 0
            var quote = 0.toChar()
            while (j < end) {
                val c = s[j]
                if (quote.code != 0) {
                    if (c == quote) quote = 0.toChar()
                } else if (c == '"' || c == '\'') {
                    quote = c
                } else if (c == '(') {
                    paren++
                } else if (c == ')') {
                    if (paren > 0) paren--
                } else if (c == ';' && paren == 0) {
                    break
                }
                j++
            }
            var colon = i // bounded to this declaration: an unbounded indexOf is O(n²) on colon-less input
            while (colon < j && s[colon] != ':') colon++
            if (colon < j) {
                val prop = s.substring(i, colon).trim().lowercase()
                var value = s.substring(colon + 1, j).trim().lowercase()
                var target = normal
                val bang = value.indexOf('!')
                if (bang >= 0) {
                    if (value.substring(bang + 1).trim() == "important") target = important
                    value = value.substring(0, bang).trim()
                }
                if (prop.isNotEmpty() && value.isNotEmpty()) applyProperty(prop, value, target)
            }
            i = j + 1
        }
    }

    private fun applyProperty(prop: String, v: String, d: CssDecl) {
        when (prop) {
            "text-align" -> {
                val a = when (v) {
                    "left", "start", "-webkit-left", "-moz-left" -> CssDecl.A_LEFT
                    "center", "-webkit-center", "-moz-center", "middle" -> CssDecl.A_CENTER
                    "right", "end", "-webkit-right", "-moz-right" -> CssDecl.A_RIGHT
                    "justify", "justify-all" -> CssDecl.A_JUSTIFY
                    else -> 0
                }
                if (a != 0) {
                    d.align = a
                    d.mask = d.mask or CssDecl.ALIGN
                }
            }
            "text-indent" -> {
                val len = parseLength(v.substringBefore(' ')) ?: return
                d.indent = len.value > 0f
                d.mask = d.mask or CssDecl.INDENT
            }
            "font-weight" -> setWeight(v, d)
            "font-style" -> setStyle(v, d)
            "font" -> {
                for (t in v.split(' ', '/')) {
                    when {
                        t == "bold" || t == "bolder" || (t.length == 3 && t.all { it.isDigit() }) -> setWeight(t, d)
                        t == "italic" || t == "oblique" -> setStyle(t, d)
                    }
                }
            }
            "font-size" -> setSize(v, d)
            "display" -> {
                d.displayNone = v == "none"
                d.mask = d.mask or CssDecl.DISPLAY
            }
            "margin-top" -> emLength(v)?.let { d.marginTop = it; d.mask = d.mask or CssDecl.MT }
            "margin-bottom" -> emLength(v)?.let { d.marginBottom = it; d.mask = d.mask or CssDecl.MB }
            "margin-left", "margin-inline-start" -> emLength(v)?.let { d.marginLeft = it; d.mask = d.mask or CssDecl.ML }
            "padding-left", "padding-inline-start" -> emLength(v)?.let { d.paddingLeft = it; d.mask = d.mask or CssDecl.PL }
            "margin" -> {
                val t = v.split(' ').filter { it.isNotEmpty() }
                if (t.isEmpty() || t.size > 4) return
                val top = t[0]
                val right = t.getOrElse(1) { top }
                val bottom = t.getOrElse(2) { top }
                val left = t.getOrElse(3) { right }
                emLength(top)?.let { d.marginTop = it; d.mask = d.mask or CssDecl.MT }
                emLength(bottom)?.let { d.marginBottom = it; d.mask = d.mask or CssDecl.MB }
                emLength(left)?.let { d.marginLeft = it; d.mask = d.mask or CssDecl.ML }
            }
            "padding" -> {
                val t = v.split(' ').filter { it.isNotEmpty() }
                if (t.isEmpty() || t.size > 4) return
                val left = t.getOrElse(3) { t.getOrElse(1) { t[0] } }
                emLength(left)?.let { d.paddingLeft = it; d.mask = d.mask or CssDecl.PL }
            }
            "page-break-before", "break-before", "-webkit-column-break-before" -> {
                breakValue(v)?.let { d.breakBefore = it; d.mask = d.mask or CssDecl.BB }
            }
            "page-break-after", "break-after" -> {
                breakValue(v)?.let { d.breakAfter = it; d.mask = d.mask or CssDecl.BA }
            }
            "text-decoration", "text-decoration-line" -> {
                if (v == "none") {
                    d.underline = false
                    d.strike = false
                    d.mask = d.mask or CssDecl.DECOR
                } else if (v.contains("underline") || v.contains("line-through")) {
                    d.underline = v.contains("underline")
                    d.strike = v.contains("line-through")
                    d.mask = d.mask or CssDecl.DECOR
                }
            }
            "vertical-align" -> {
                val a = when (v) {
                    "super", "sup" -> 1
                    "sub" -> -1
                    "baseline" -> 0
                    else -> return
                }
                d.vAlign = a
                d.mask = d.mask or CssDecl.VALIGN
            }
            "white-space" -> {
                d.pre = v.startsWith("pre") || v == "break-spaces"
                if (v == "pre" || v == "pre-wrap" || v == "pre-line" || v == "break-spaces" || v == "normal" ||
                    v == "nowrap"
                ) d.mask = d.mask or CssDecl.WS
            }
            "list-style-type", "list-style" -> {
                for (t in v.split(' ')) {
                    val ls = listStyle(t)
                    if (ls != 0) {
                        d.listStyle = ls
                        d.mask = d.mask or CssDecl.LIST
                        break
                    }
                }
            }
        }
    }

    private fun setWeight(v: String, d: CssDecl) {
        val b = when (v) {
            "bold", "bolder" -> true
            "normal", "lighter" -> false
            else -> v.toIntOrNull()?.let { it >= 600 } ?: return
        }
        d.bold = b
        d.mask = d.mask or CssDecl.WEIGHT
    }

    private fun setStyle(v: String, d: CssDecl) {
        val it = when {
            v == "italic" || v.startsWith("oblique") -> true
            v == "normal" -> false
            else -> return
        }
        d.italic = it
        d.mask = d.mask or CssDecl.STYLE
    }

    private fun setSize(v: String, d: CssDecl) {
        var abs = false
        val scale: Float = when (v) {
            "xx-small" -> { abs = true; 0.6f }
            "x-small" -> { abs = true; 0.75f }
            "small" -> { abs = true; 0.89f }
            "medium" -> { abs = true; 1f }
            "large" -> { abs = true; 1.2f }
            "x-large" -> { abs = true; 1.5f }
            "xx-large", "xxx-large" -> { abs = true; 2f }
            "smaller" -> 0.83f
            "larger" -> 1.2f
            else -> {
                val len = parseLength(v) ?: return
                when (len.unit) {
                    "em" -> len.value
                    "%" -> len.value / 100f
                    "rem" -> { abs = true; len.value }
                    else -> return // px, pt and others: ignored (the user's size wins)
                }
            }
        }
        if (scale <= 0f || scale.isNaN()) return
        d.size = scale.coerceIn(0.7f, 2f)
        d.sizeAbs = abs
        d.mask = d.mask or CssDecl.SIZE
    }

    private fun breakValue(v: String): Boolean? = when (v) {
        "always", "page", "left", "right", "recto", "verso" -> true
        "auto", "avoid", "avoid-page" -> false
        else -> null
    }

    private fun listStyle(t: String): Int = when (t) {
        "none" -> CssDecl.LS_NONE
        "disc" -> CssDecl.LS_DISC
        "circle" -> CssDecl.LS_CIRCLE
        "square" -> CssDecl.LS_SQUARE
        "decimal", "decimal-leading-zero" -> CssDecl.LS_DECIMAL
        "lower-alpha", "lower-latin" -> CssDecl.LS_LOWER_ALPHA
        "upper-alpha", "upper-latin" -> CssDecl.LS_UPPER_ALPHA
        "lower-roman" -> CssDecl.LS_LOWER_ROMAN
        "upper-roman" -> CssDecl.LS_UPPER_ROMAN
        "hangul", "korean-hangul-formal" -> CssDecl.LS_HANGUL
        "hangul-consonant" -> CssDecl.LS_HANGUL_CONSONANT
        else -> 0
    }

    class Length(@JvmField val value: Float, @JvmField val unit: String)

    /** Parses `12`, `1.5em`, `.5rem`, `-3px`, `50%`. Returns null if not a length. */
    fun parseLength(v: String): Length? {
        val t = v.trim()
        if (t.isEmpty()) return null
        var i = 0
        if (t[0] == '-' || t[0] == '+') i++
        val ns = i
        var dot = false
        while (i < t.length && (t[i].isDigit() || (t[i] == '.' && !dot))) {
            if (t[i] == '.') dot = true
            i++
        }
        if (i == ns || (i == ns + 1 && dot)) return null
        val num = t.substring(0, i).toFloatOrNull() ?: return null
        val unit = t.substring(i).trim()
        if (unit.isEmpty() && num != 0f) return Length(num, "px") // unitless non-zero: treat like px (ignored)
        return Length(num, unit)
    }

    /** A length converted to em (em/rem as-is, px / 16, pt / 12); null for %, auto and unknown units. */
    fun emLength(v: String): Float? {
        val len = parseLength(v) ?: return null
        val em = when (len.unit) {
            "", "em", "rem" -> len.value
            "px" -> len.value / 16f
            "pt" -> len.value / 12f
            else -> return null
        }
        return if (em.isNaN()) null else em
    }
}
