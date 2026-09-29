package com.ggumtak.readeraplus.format.epub

import com.ggumtak.readeraplus.engine.Align
import com.ggumtak.readeraplus.engine.BlockStyle
import com.ggumtak.readeraplus.engine.ImageBlock
import com.ggumtak.readeraplus.engine.OBJECT_CHAR
import com.ggumtak.readeraplus.engine.ParagraphBlock
import com.ggumtak.readeraplus.engine.SectionContent
import com.ggumtak.readeraplus.format.BookDocument
import com.ggumtak.readeraplus.format.BookFormat
import com.ggumtak.readeraplus.format.DocMeta
import com.ggumtak.readeraplus.format.DocPosition
import com.ggumtak.readeraplus.format.DocumentException
import com.ggumtak.readeraplus.format.ParseOptions
import com.ggumtak.readeraplus.format.SectionInfo
import com.ggumtak.readeraplus.format.TocEntry
import java.io.File

/**
 * An opened EPUB: one section per spine item, converted on demand (last [CACHE_SIZE] kept). Opening parses
 * container.xml, the OPF and the TOC (nav / NCX); content documents and the cover load on request.
 * Thread-safe: sections may be loaded concurrently (the same section is converted once).
 */
internal class EpubBook private constructor(
    override val file: File,
    private val zip: EpubZip,
    private val pkg: EpubPackage,
    private val options: ParseOptions,
) : BookDocument {
    override val format: BookFormat get() = BookFormat.EPUB

    override val meta: DocMeta = EpubDocuments.metaOf(pkg, file)

    /** Spine items that can be displayed (XHTML or image), in reading order. */
    private val spine: List<ManifestItem> = selectSpine()

    /** Canonical zip path → first spine index. */
    private val spineIndex = HashMap<String, Int>(spine.size * 2).also { m ->
        for ((i, it) in spine.withIndex()) m.putIfAbsent(it.path, i)
    }

    override val sections: List<SectionInfo> = spine.map { item ->
        val approx = if (item.isImage) IMAGE_APPROX_CHARS else {
            val sz = zip.size(item.path)
            if (sz < 0) UNKNOWN_APPROX_CHARS else maxOf(1L, sz / 3).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }
        SectionInfo(null, approx)
    }

    /** Parsed while opening (off the main thread): readers touch it right after open, on the UI thread. */
    override val toc: List<TocEntry> = try {
        buildToc()
    } catch (_: Exception) {
        emptyList()
    } catch (_: StackOverflowError) {
        emptyList()
    }

    private val cache: LinkedHashMap<Int, SectionContent> = object : LinkedHashMap<Int, SectionContent>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, SectionContent>?): Boolean =
            size > CACHE_SIZE
    }
    /** Section index → its anchors (small maps, kept for every converted section; guarded by [cache]). */
    private val anchorCache = HashMap<Int, Map<String, Int>>()
    private val sectionLocks = Array(spine.size) { Any() }
    private val cssCache = HashMap<String, List<CssSheet>>()

    private val resources = object : XhtmlResources {
        override fun imagePath(path: String): String? = zip.find(path)
        override fun styleSheets(path: String): List<CssSheet> = loadCss(path, 0)
    }

    private val coverPath: String? by lazy {
        try {
            findCover()
        } catch (_: Exception) {
            null
        }
    }

    /** Canonical zip paths of the sections (tests / diagnostics). */
    internal val spinePaths: List<String> get() = spine.map { it.path }

    /** Detected cover entry (tests / diagnostics). */
    internal val coverEntry: String? get() = coverPath

    private fun selectSpine(): List<ManifestItem> {
        fun usable(it: ManifestItem) = (it.isHtml || it.isImage) && zip.find(it.path) != null
        var items = pkg.spine.filter(::usable)
        if (items.isEmpty()) items = pkg.manifest.filter { it.isHtml && !it.hasProperty("nav") && usable(it) }
        if (items.isEmpty()) items = EpubDocuments.htmlEntries(zip).map { ManifestItem(it, it, "", "") }
        if (items.isEmpty()) throw DocumentException("표시할 내용이 없는 EPUB입니다: ${file.name}")
        return items.map { item ->
            val canonical = zip.find(item.path) ?: item.path
            if (canonical == item.path) item else ManifestItem(item.id, canonical, item.mediaType, item.properties)
        }
    }

    // ================================================================ sections

    override fun loadSection(index: Int): SectionContent {
        if (index < 0 || index >= spine.size) return SectionContent.EMPTY
        synchronized(cache) { cache[index]?.let { return it } }
        synchronized(sectionLocks[index]) {
            synchronized(cache) { cache[index]?.let { return it } }
            val content = convert(index)
            synchronized(cache) {
                cache[index] = content
                anchorCache[index] = content.anchors
            }
            return content
        }
    }

    /**
     * Anchor offsets of [index] without re-converting it when it was converted before: the TOC dialog resolves
     * every entry (and page counting loads every section), while the 4-entry content LRU keeps evicting.
     */
    private fun anchorsOf(index: Int): Map<String, Int> {
        synchronized(cache) { anchorCache[index]?.let { return it } }
        return loadSection(index).anchors
    }

    /** Number of section conversions performed (tests / diagnostics). */
    @Volatile internal var conversions = 0
        private set

    private fun convert(index: Int): SectionContent {
        conversions++ // only under sectionLocks[index]; an approximate count across sections is fine
        val item = spine[index]
        if (item.isImage) {
            return SectionContent(OBJECT_CHAR.toString(), listOf(ImageBlock(0, item.path)))
        }
        return try {
            val bytes = zip.read(item.path) ?: return SectionContent.EMPTY
            XhtmlConverter(options.epubPublisherStyles, resources).convert(EpubText.decode(bytes), item.path)
        } catch (_: RuntimeException) {
            errorSection()
        } catch (_: StackOverflowError) {
            errorSection()
        } catch (_: OutOfMemoryError) { // a pathological multi-MB single document: its buffers are local
            errorSection()
        }
    }

    private fun errorSection(): SectionContent {
        val msg = "(이 부분을 표시할 수 없습니다)"
        return SectionContent(msg, listOf(ParagraphBlock(0, msg.length, BlockStyle(align = Align.CENTER, indent = false))))
    }

    private fun loadCss(path: String, depth: Int): List<CssSheet> {
        val canonical = zip.find(path) ?: return emptyList()
        synchronized(cssCache) { cssCache[canonical]?.let { return it } }
        val bytes = zip.read(canonical, MAX_CSS_BYTES) ?: return emptyList()
        val sheet = CssParser.parse(EpubText.decodeCss(bytes))
        val result = if (sheet.imports.isEmpty() || depth >= 3) {
            listOf(sheet)
        } else {
            val out = ArrayList<CssSheet>()
            val dir = EpubPaths.dirOf(canonical)
            for (href in sheet.imports) {
                if (EpubPaths.hasScheme(href)) continue
                val p = EpubPaths.resolve(dir, href)
                if (p == canonical) continue
                out.addAll(loadCss(p, depth + 1))
            }
            out.add(sheet)
            out
        }
        synchronized(cssCache) { cssCache[canonical] = result }
        return result
    }

    // ================================================================ images

    override fun loadImage(src: String): ByteArray? {
        if (src.isEmpty() || EpubPaths.hasScheme(src)) return null
        val name = zip.find(src) ?: zip.find(EpubPaths.normalize(src)) ?: zip.find(EpubPaths.resolve("", src))
            ?: return null
        return zip.read(name)
    }

    override fun coverImage(): ByteArray? = coverPath?.let { zip.read(it) }

    private fun findCover(): String? {
        fun image(path: String?): String? {
            if (path.isNullOrEmpty()) return null
            val c = zip.find(path) ?: return null
            val item = pkg.manifest.firstOrNull { it.path == c }
            return if (item?.isImage == true || EpubPaths.isImagePath(c)) c else null
        }
        fun firstImageOf(docPath: String?): String? {
            if (docPath.isNullOrEmpty()) return null
            val c = zip.find(docPath) ?: return null
            if (EpubPaths.isImagePath(c)) return c
            val bytes = zip.read(c) ?: return null
            val src = EpubTocParser.firstImage(EpubText.decode(bytes)) ?: return null
            if (EpubPaths.hasScheme(src)) return null
            return image(EpubPaths.resolve(EpubPaths.dirOf(c), src))
        }
        // 1. EPUB3 cover-image property
        pkg.manifest.firstOrNull { it.hasProperty("cover-image") }?.let { m -> image(m.path)?.let { return it } }
        // 2. EPUB2 <meta name="cover" content="id"> (sometimes a path instead of an id)
        pkg.coverMeta?.let { cm ->
            pkg.byId[cm]?.let { m ->
                image(m.path)?.let { return it }
                if (m.isHtml) firstImageOf(m.path)?.let { return it }
            }
            image(EpubPaths.resolve(EpubPaths.dirOf(pkg.opfPath), cm))?.let { return it }
        }
        // 3. guide reference type="cover"
        pkg.guideCover?.let { g -> (image(g) ?: firstImageOf(g))?.let { return it } }
        // 4. an image whose id or file name mentions "cover"
        val named = pkg.manifest.filter { m ->
            m.isImage && (m.id.contains("cover", ignoreCase = true) ||
                m.path.substringAfterLast('/').contains("cover", ignoreCase = true))
        }
        for (m in named.sortedBy { if (it.mediaType.contains("svg")) 1 else 0 }) image(m.path)?.let { return it }
        // 5. first image of the first spine item
        val first = spine.firstOrNull() ?: return null
        return if (first.isImage) image(first.path) else firstImageOf(first.path)
    }

    // ================================================================ navigation

    override fun resolveLink(fromSection: Int, href: String): DocPosition? {
        val h = href.trim()
        if (h.isEmpty() || EpubPaths.hasScheme(h)) return null
        val frag = EpubPaths.fragment(h)
        val pathPart = EpubPaths.stripFragment(h)
        val section = if (pathPart.isEmpty()) {
            if (fromSection !in spine.indices) return null
            fromSection
        } else {
            val base = if (fromSection in spine.indices) EpubPaths.dirOf(spine[fromSection].path) else ""
            spineIndexOf(base, h) ?: return null
        }
        if (frag == null) return DocPosition(section, 0)
        val off = anchorsOf(section)[frag]
            ?: return if (section == fromSection && pathPart.isEmpty()) null else DocPosition(section, 0)
        return DocPosition(section, off)
    }

    override fun resolveToc(entry: TocEntry): DocPosition {
        if (spine.isEmpty()) return DocPosition.START
        val s = entry.section.coerceIn(0, spine.size - 1)
        val a = entry.anchor
        if (a.isNullOrEmpty()) return DocPosition(s, maxOf(0, entry.offset))
        val off = anchorsOf(s)[a] ?: maxOf(0, entry.offset)
        return DocPosition(s, off)
    }

    /** Spine index of [href] (fragment ignored) relative to [baseDir], or null if not in the spine. */
    private fun spineIndexOf(baseDir: String, href: String): Int? {
        val p = EpubPaths.resolve(baseDir, href)
        val c = zip.find(p) ?: zip.find(EpubPaths.resolve(baseDir, href, decode = false)) ?: p
        return spineIndex[c]
    }

    /** EPUB3 nav, else NCX (also when the nav exists but none of its links reach the spine), else headings. */
    private fun buildToc(): List<TocEntry> {
        val nav = pkg.manifest.firstOrNull { it.hasProperty("nav") }
        if (nav != null) {
            zip.read(nav.path)?.let { bytes ->
                val out = resolveRaw(EpubTocParser.parseNav(EpubText.decode(bytes)), EpubPaths.dirOf(nav.path))
                if (out.isNotEmpty()) return out
            }
        }
        val ncx = pkg.spineTocId?.let { pkg.byId[it] }?.takeIf { zip.find(it.path) != null }
            ?: pkg.manifest.firstOrNull { it.mediaType == "application/x-dtbncx+xml" && zip.find(it.path) != null }
            ?: pkg.manifest.firstOrNull { EpubPaths.extension(it.path) == "ncx" && zip.find(it.path) != null }
        val ncxPath = ncx?.path ?: zip.names.firstOrNull { EpubPaths.extension(it) == "ncx" }
        if (ncxPath != null) {
            zip.read(ncxPath)?.let { bytes ->
                val base = EpubPaths.dirOf(zip.find(ncxPath) ?: ncxPath)
                val out = resolveRaw(EpubTocParser.parseNcx(EpubText.decode(bytes)), base)
                if (out.isNotEmpty()) return out
            }
        }
        return fallbackToc()
    }

    private fun resolveRaw(raw: List<RawTocEntry>, base: String): List<TocEntry> {
        val out = ArrayList<TocEntry>(raw.size)
        for (e in raw) {
            if (EpubPaths.hasScheme(e.href)) continue
            val section = spineIndexOf(base, e.href) ?: continue
            out.add(TocEntry(e.title, e.level, section, 0, EpubPaths.fragment(e.href)))
        }
        return out
    }

    /** One entry per spine item with a heading or distinctive `<title>` (scans only each file's head). */
    private fun fallbackToc(): List<TocEntry> {
        val out = ArrayList<TocEntry>()
        var prev: String? = null
        for ((i, item) in spine.withIndex()) {
            if (!item.isHtml) continue
            val bytes = zip.readPrefix(item.path, TITLE_SCAN_BYTES) ?: continue
            val t = EpubTocParser.scanTitle(EpubText.decode(bytes), pkg.title) ?: continue
            if (t == prev) continue
            prev = t
            out.add(TocEntry(t, 1, i, 0, null))
        }
        return out
    }

    override fun close() {
        zip.close()
        synchronized(cache) { cache.clear() }
    }

    companion object {
        const val CACHE_SIZE = 4
        private const val IMAGE_APPROX_CHARS = 500
        private const val UNKNOWN_APPROX_CHARS = 2000
        private const val MAX_CSS_BYTES = 4 * 1024 * 1024
        private const val TITLE_SCAN_BYTES = 24 * 1024

        /** Opens [file]; the zip is closed again if anything fails. */
        fun open(file: File, options: ParseOptions): EpubBook {
            val zip = EpubZip.open(file)
            try {
                EpubDocuments.checkDrm(zip)
                val pkg = EpubDocuments.loadPackage(zip) ?: EpubDocuments.fallbackPackage(zip)
                return EpubBook(file, zip, pkg, options)
            } catch (e: DocumentException) {
                zip.close()
                throw e
            } catch (e: Exception) {
                zip.close()
                throw DocumentException("EPUB을 해석할 수 없습니다: ${file.name}", e)
            } catch (e: StackOverflowError) {
                zip.close()
                throw DocumentException("EPUB을 해석할 수 없습니다: ${file.name}", e)
            }
        }
    }
}
