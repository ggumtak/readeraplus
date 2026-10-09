package com.ggumtak.readeraplus.format.epub

/** A manifest item. [path] is the resolved zip path (canonical when the entry exists). */
internal class ManifestItem(
    val id: String,
    val path: String,
    val mediaType: String,
    val properties: String,
) {
    val isImage: Boolean
        get() = mediaType.startsWith("image/") || (mediaType.isEmpty() && EpubPaths.isImagePath(path))

    val isHtml: Boolean
        get() = when {
            mediaType.isEmpty() -> EpubPaths.isHtmlPath(path)
            mediaType.contains("html") || mediaType == "application/x-dtbook+xml" ||
                mediaType == "text/x-oeb1-document" -> true
            mediaType == "application/xml" || mediaType == "text/xml" -> EpubPaths.isHtmlPath(path)
            else -> false
        }

    fun hasProperty(p: String): Boolean = properties.split(' ').any { it == p }
}

/** Parsed OPF package document (metadata, manifest, spine, cover hints). */
internal class EpubPackage(
    val opfPath: String,
    val title: String?,
    val authors: List<String>,
    val language: String?,
    val publisher: String?,
    val description: String?,
    val series: String?,
    val seriesIndex: Float?,
    val manifest: List<ManifestItem>,
    val spine: List<ManifestItem>,
    /** `<spine toc="...">` id. */
    val spineTocId: String?,
    /** EPUB2 `<meta name="cover" content="...">` value. */
    val coverMeta: String?,
    /** Guide reference of type "cover" (resolved path without fragment). */
    val guideCover: String?,
    /** `<itemref>`s in the spine, including those naming no manifest item (dropped from [spine]). */
    val spineRefCount: Int = spine.size,
) {
    val byId: Map<String, ManifestItem> = HashMap<String, ManifestItem>().also { m ->
        for (it in manifest) m.putIfAbsent(it.id, it)
    }
}

/** container.xml and OPF parsing with the tolerant tokenizer. */
internal object OpfParser {
    /** Full path of the first OPF rootfile in META-INF/container.xml, or null. */
    fun rootfile(containerXml: String): String? {
        val r = MarkupReader(containerXml)
        var first: String? = null
        while (true) {
            val t = r.next()
            if (t == MarkupReader.EOF) break
            if (t == MarkupReader.START && r.name == "rootfile") {
                val p = r.attr("full-path")?.trim()
                if (!p.isNullOrEmpty()) {
                    val mt = r.attr("media-type")?.trim()?.lowercase()
                    if (mt == null || mt == "application/oebps-package+xml") return p
                    if (first == null) first = p
                }
            }
        }
        return first
    }

    private class MetaEl(val name: String, val id: String?, val attrs: Map<String, String>) {
        val text = StringBuilder()
    }

    /**
     * Parses an OPF document located at [opfPath]. [locate] maps a resolved zip path to its canonical entry name
     * (or null when missing — the resolved path is kept then).
     */
    fun parse(xml: String, opfPath: String, locate: (String) -> String?): EpubPackage {
        val opfDir = EpubPaths.dirOf(opfPath)
        val r = MarkupReader(xml)
        val metas = ArrayList<MetaEl>()
        val manifest = ArrayList<ManifestItem>()
        val spineRefs = ArrayList<String>()
        var spineToc: String? = null
        var guideCover: String? = null
        var section = 0 // 1 metadata, 2 manifest, 3 spine, 4 guide
        val open = ArrayList<MetaEl>(2)
        var captureDepth = 0
        while (true) {
            when (r.next()) {
                MarkupReader.EOF -> break
                MarkupReader.START -> {
                    val n = r.name
                    if (captureDepth > 0) {
                        if (n in META_NAMES) {
                            // Another metadata element while capturing: the previous one was never closed
                            // (e.g. `<meta property="x">v` without `</meta>`). None of these names occur inside
                            // a metadata value, so stop capturing instead of swallowing the rest of the metadata.
                            open.clear()
                            captureDepth = 0
                        } else {
                            if (!r.selfClosing && n !in HTML_VOID) captureDepth++
                            // nested markup in a metadata value (e.g. XHTML inside dc:description)
                            if (n == "p" || n == "br" || n == "div" || n == "li") open.lastOrNull()?.text?.append('\n')
                            continue
                        }
                    }
                    when (n) {
                        "metadata", "dc-metadata", "x-metadata" -> section = 1
                        "manifest" -> section = 2
                        "spine" -> {
                            section = 3
                            spineToc = r.attr("toc")?.trim()?.takeIf { it.isNotEmpty() }
                        }
                        "guide" -> section = 4
                        "item" -> if (section == 2 || section == 0) {
                            val href = r.attr("href")?.trim()
                            if (!href.isNullOrEmpty() && !EpubPaths.hasScheme(href)) {
                                val resolved = EpubPaths.resolve(opfDir, href)
                                val path = locate(resolved) ?: locate(EpubPaths.resolve(opfDir, href, decode = false))
                                    ?: resolved
                                manifest.add(
                                    ManifestItem(
                                        id = r.attr("id")?.trim() ?: "",
                                        path = path,
                                        mediaType = r.attr("media-type")?.trim()?.lowercase() ?: "",
                                        properties = r.attr("properties")?.trim() ?: "",
                                    ),
                                )
                            }
                        }
                        "itemref" -> if (section == 3 || section == 0) {
                            val idref = r.attr("idref")?.trim()
                            if (!idref.isNullOrEmpty()) spineRefs.add(idref)
                        }
                        "reference" -> if (section == 4 && guideCover == null) {
                            val type = r.attr("type")?.trim()?.lowercase()
                            val href = r.attr("href")?.trim()
                            if (!href.isNullOrEmpty() && (type == "cover" || type == "other.ms-coverimage-standard" ||
                                    type == "coverimage")
                            ) {
                                val resolved = EpubPaths.resolve(opfDir, href)
                                guideCover = locate(resolved) ?: resolved
                            }
                        }
                        else -> if (section == 1 && n in META_NAMES) {
                            val attrs = HashMap<String, String>()
                            for (i in 0 until r.attributeCount) {
                                val an = r.attrName(i)
                                attrs[an.substringAfter(':')] = r.attrValue(i)
                                attrs[an] = r.attrValue(i)
                            }
                            val el = MetaEl(n, attrs["id"], attrs)
                            metas.add(el)
                            // EPUB2 `<meta name content>` carries its value in the attribute: treat it as void
                            // so an HTML-style unclosed one can't capture the following elements.
                            val epub2Meta = n == "meta" && attrs.containsKey("content")
                            if (!r.selfClosing && !epub2Meta) {
                                open.add(el)
                                captureDepth = 1
                            }
                        }
                    }
                }
                MarkupReader.TEXT -> if (captureDepth > 0) {
                    open.lastOrNull()?.text?.append(r.text())
                }
                MarkupReader.END -> {
                    if (captureDepth > 0) {
                        captureDepth--
                        if (captureDepth == 0) open.clear()
                        continue
                    }
                    when (r.name) {
                        "metadata", "manifest", "spine", "guide" -> section = 0
                    }
                }
            }
        }
        return build(opfPath, metas, manifest, spineRefs, spineToc, guideCover)
    }

    private val META_NAMES = hashSetOf(
        "title", "creator", "language", "publisher", "description", "meta", "contributor", "date", "identifier",
    )

    /** Void elements that may appear inside an escaped-HTML-free description (no end tag to balance). */
    private val HTML_VOID = hashSetOf("br", "img", "hr", "wbr")

    private fun build(
        opfPath: String,
        metas: List<MetaEl>,
        manifest: List<ManifestItem>,
        spineRefs: List<String>,
        spineToc: String?,
        guideCover: String?,
    ): EpubPackage {
        // EPUB3 refinements: id -> (property -> value)
        val refines = HashMap<String, HashMap<String, String>>()
        for (m in metas) {
            if (m.name != "meta") continue
            val target = m.attrs["refines"]?.trim()?.removePrefix("#") ?: continue
            val prop = m.attrs["property"]?.trim() ?: continue
            val v = m.text.toString().trim()
            if (v.isNotEmpty()) refines.getOrPut(target) { HashMap() }.putIfAbsent(prop, v)
        }
        fun valueOf(m: MetaEl) = Entities.collapse(m.text.toString())

        val titles = metas.filter { it.name == "title" }.map { it to valueOf(it) }.filter { it.second.isNotEmpty() }
        val title = (titles.firstOrNull { (m, _) -> m.id != null && refines[m.id]?.get("title-type") == "main" }
            ?: titles.firstOrNull())?.second

        val authors = LinkedHashSet<String>()
        for (m in metas) if (m.name == "creator") valueOf(m).takeIf { it.isNotEmpty() }?.let { authors.add(it) }

        val language = metas.firstOrNull { it.name == "language" }?.let { valueOf(it) }?.takeIf { it.isNotEmpty() }
        val publisher = metas.firstOrNull { it.name == "publisher" }?.let { valueOf(it) }?.takeIf { it.isNotEmpty() }
        val description = metas.firstOrNull { it.name == "description" }?.let { stripTags(it.text.toString()) }
            ?.takeIf { it.isNotEmpty() }

        var series: String? = null
        var seriesIndex: Float? = null
        var coverMeta: String? = null
        for (m in metas) {
            if (m.name != "meta") continue
            val name = m.attrs["name"]?.trim()?.lowercase() ?: continue
            val content = m.attrs["content"]?.trim() ?: continue
            when (name) {
                "calibre:series" -> if (series == null && content.isNotEmpty()) series = Entities.collapse(content)
                "calibre:series_index" -> if (seriesIndex == null) seriesIndex = content.toFloatOrNull()
                "cover" -> if (coverMeta == null && content.isNotEmpty()) coverMeta = content
            }
        }
        if (series == null) {
            val collections = metas.filter { it.name == "meta" && it.attrs["property"]?.trim() == "belongs-to-collection" }
            val pick = collections.firstOrNull { m -> m.id != null && refines[m.id]?.get("collection-type") == "series" }
                ?: collections.firstOrNull()
            if (pick != null) {
                val v = valueOf(pick)
                if (v.isNotEmpty()) {
                    series = v
                    seriesIndex = pick.id?.let { refines[it]?.get("group-position")?.trim()?.toFloatOrNull() }
                }
            }
        }
        if (series == null) seriesIndex = null

        val byId = HashMap<String, ManifestItem>()
        for (it in manifest) byId.putIfAbsent(it.id, it)
        val spine = ArrayList<ManifestItem>(spineRefs.size)
        for (ref in spineRefs) byId[ref]?.let { spine.add(it) }
        return EpubPackage(
            opfPath = opfPath,
            title = title,
            authors = authors.toList(),
            language = language,
            publisher = publisher,
            description = description,
            series = series,
            seriesIndex = seriesIndex,
            manifest = manifest,
            spine = spine,
            spineTocId = spineToc,
            coverMeta = coverMeta,
            guideCover = guideCover,
            spineRefCount = spineRefs.size,
        )
    }

    /**
     * Strips markup from a description (which is often escaped HTML): block tags and `<br>` become line
     * breaks, whitespace collapses within lines, at most one blank line in a row.
     */
    fun stripTags(raw: String): String = stripTags(raw, 0)

    private fun stripTags(raw: String, depth: Int): String {
        val decoded = if (raw.indexOf('<') >= 0 || raw.indexOf('&') >= 0) {
            val sb = StringBuilder(raw.length)
            val r = MarkupReader(raw)
            while (true) {
                when (r.next()) {
                    MarkupReader.EOF -> break
                    MarkupReader.TEXT -> {
                        val t = r.text()
                        // escaped markup inside a text node: decode once more
                        if (depth == 0 && t.indexOf('<') >= 0) sb.append(stripTags(t, 1)) else sb.append(t)
                    }
                    MarkupReader.START, MarkupReader.END -> when (r.name) {
                        "p", "br", "div", "li", "h1", "h2", "h3", "h4", "h5", "h6", "tr", "blockquote" -> sb.append('\n')
                    }
                }
            }
            sb.toString()
        } else {
            raw
        }
        val out = StringBuilder(decoded.length)
        for (line in decoded.split('\n')) {
            val l = Entities.collapse(line)
            if (l.isEmpty()) {
                if (out.isNotEmpty() && !out.endsWith("\n\n")) out.append('\n')
            } else {
                if (out.isNotEmpty() && !out.endsWith("\n")) out.append('\n')
                out.append(l)
            }
        }
        return out.toString().trim()
    }
}
