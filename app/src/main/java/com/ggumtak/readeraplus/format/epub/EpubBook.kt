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
 * An opened EPUB: one section per spine item, except that oversized items (whole-book files from TXT→EPUB
 * converters) are split into several sections (see [EpubSplit]). Opening parses container.xml, the OPF and the
 * TOC (nav / NCX), and scans the text of items above [EpubSplit.SCAN_MIN_BYTES] to fix their part count;
 * content documents are converted on demand (last [CACHE_SIZE] sections kept, plus the last [ITEM_CACHE_SIZE]
 * converted split items so their parts are cut without converting again) and the cover loads on request.
 * Thread-safe: sections may be loaded concurrently (the same spine item is converted once).
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
    private val spine: List<ManifestItem> = selectSpine(zip, pkg).also {
        if (it.isEmpty()) throw DocumentException("표시할 내용이 없는 EPUB입니다: ${file.name}")
    }

    /** Canonical zip path → first spine index. */
    private val spineIndex = HashMap<String, Int>(spine.size * 2).also { m ->
        for ((i, it) in spine.withIndex()) m.putIfAbsent(it.path, i)
    }

    /** TOC targets as (spine item, fragment), parsed while opening (off the main thread). */
    private val tocRefs: List<TocRef> = try {
        buildToc()
    } catch (_: Exception) {
        emptyList()
    } catch (_: StackOverflowError) {
        emptyList()
    }

    /** Sections per spine item (1 unless the item is split). */
    private val parts = IntArray(spine.size) { 1 }

    /** Split items: TOC fragment → part it was assigned to (every TOC entry's section is derived from it). */
    private val fragParts = arrayOfNulls<HashMap<String, Int>>(spine.size)

    /** Estimated chars per spine item. */
    private val itemChars = IntArray(spine.size)

    init {
        planSections()
    }

    /** First section of each spine item; the last element is the section count. */
    private val firstSection = IntArray(spine.size + 1).also { a ->
        for (i in spine.indices) a[i + 1] = a[i] + parts[i]
    }

    private val sectionCount = firstSection[spine.size]

    /** Spine item of each section. */
    private val itemOfSection = IntArray(sectionCount).also { a ->
        for (i in spine.indices) for (s in firstSection[i] until firstSection[i + 1]) a[s] = i
    }

    override val sections: List<SectionInfo> = List(sectionCount) { s ->
        val item = itemOfSection[s]
        SectionInfo(null, maxOf(1, itemChars[item] / parts[item]))
    }

    /** Parsed while opening (off the main thread): readers touch it right after open, on the UI thread. */
    override val toc: List<TocEntry> = tocEntries()

    private val cache: LinkedHashMap<Int, SectionContent> = object : LinkedHashMap<Int, SectionContent>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, SectionContent>?): Boolean =
            size > CACHE_SIZE
    }
    /** Converted split items by spine index (guarded by [cache]). */
    private val itemCache: LinkedHashMap<Int, SplitItem> = object : LinkedHashMap<Int, SplitItem>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, SplitItem>?): Boolean =
            size > ITEM_CACHE_SIZE
    }
    /** Spine index → its item-wide anchors (small maps, kept for every converted item; guarded by [cache]). */
    private val anchorCache = HashMap<Int, Map<String, Int>>()
    /** Spine index → part ranges of a split item (kept for every converted split item; guarded by [cache]). */
    private val cutCache = HashMap<Int, EpubSplit.Cuts>()
    private val itemLocks = Array(spine.size) { Any() }
    private val cssCache = HashMap<String, List<CssSheet>>()

    private class TocRef(val title: String, val level: Int, val item: Int, val frag: String?)

    private class SplitItem(val content: SectionContent, val cuts: EpubSplit.Cuts)

    private val resources = object : XhtmlResources {
        override fun imagePath(path: String): String? = zip.find(path)
        override fun styleSheets(path: String): List<CssSheet> = loadCss(path, 0)
    }

    private val coverPath: String? by lazy {
        try {
            findCover(zip, pkg, spine.firstOrNull())
        } catch (_: Exception) {
            null
        }
    }

    /** Canonical zip paths of the spine items (tests / diagnostics). */
    internal val spinePaths: List<String> get() = spine.map { it.path }

    /** Sections of each spine item (tests / diagnostics). */
    internal val partCounts: List<Int> get() = parts.toList()

    /** Detected cover entry (tests / diagnostics). */
    internal val coverEntry: String? get() = coverPath

    // ================================================================ section map

    /**
     * Fixes the part count of every spine item. Only items above [EpubSplit.SCAN_MIN_BYTES] are read (whole
     * documents that big are rare outside converter output); their text scan also places the TOC anchors.
     */
    private fun planSections() {
        var wanted: Array<HashSet<String>?>? = null
        for (r in tocRefs) {
            val f = r.frag ?: continue
            if (f.isEmpty()) continue
            val w = wanted ?: arrayOfNulls<HashSet<String>>(spine.size).also { wanted = it }
            (w[r.item] ?: HashSet<String>().also { w[r.item] = it }).add(f)
        }
        for ((i, item) in spine.withIndex()) {
            if (item.isImage) {
                itemChars[i] = IMAGE_APPROX_CHARS
                continue
            }
            val sz = zip.size(item.path)
            itemChars[i] = if (sz < 0) UNKNOWN_APPROX_CHARS else maxOf(1L, sz / 3).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            if (sz <= EpubSplit.SCAN_MIN_BYTES) continue
            val scan = scanItem(item.path, wanted?.get(i) ?: emptySet<String>()) ?: continue
            val n = EpubSplit.partsFor(scan.chars)
            if (n <= 1) continue
            parts[i] = n
            itemChars[i] = scan.chars
            fragParts[i] = EpubSplit.assign(scan, n)
        }
    }

    private fun scanItem(path: String, wanted: Set<String>): EpubSplit.Scan? = try {
        zip.read(path)?.let { EpubSplit.scan(EpubText.decode(it), wanted) }
    } catch (_: RuntimeException) {
        null
    } catch (_: StackOverflowError) {
        null
    } catch (_: OutOfMemoryError) {
        null
    }

    /**
     * TOC entries with their sections. In a split item an anchored entry takes the part the scan put its anchor
     * in; an anchor the scan did not find stays with the entry before it (its part is recorded as well, so the
     * part's content can still resolve it should the converter find it).
     */
    private fun tocEntries(): List<TocEntry> {
        val out = ArrayList<TocEntry>(tocRefs.size)
        val lastPart = IntArray(spine.size)
        for (r in tocRefs) {
            var part = 0
            val m = fragParts[r.item]
            val f = r.frag
            if (m != null && !f.isNullOrEmpty()) {
                part = m[f] ?: lastPart[r.item].also { m[f] = it }
                lastPart[r.item] = part
            }
            out.add(TocEntry(r.title, r.level, firstSection[r.item] + part, 0, f))
        }
        return out
    }

    // ================================================================ sections

    override fun loadSection(index: Int): SectionContent {
        if (index < 0 || index >= sectionCount) return SectionContent.EMPTY
        synchronized(cache) { cache[index]?.let { return it } }
        val item = itemOfSection[index]
        if (parts[item] == 1) {
            synchronized(itemLocks[item]) {
                synchronized(cache) { cache[index]?.let { return it } }
                val content = convert(item)
                synchronized(cache) {
                    cache[index] = content
                    anchorCache[item] = content.anchors
                }
                return content
            }
        }
        val split = splitItem(item)
        val part = index - firstSection[item]
        val content = try {
            EpubSplit.slice(split.content, split.cuts, part, partAnchors(item, part, split.content))
        } catch (_: RuntimeException) {
            errorSection()
        }
        synchronized(cache) {
            cache[index]?.let { return it }
            cache[index] = content
        }
        return content
    }

    /** Whole-item offsets of the TOC anchors assigned to [part] of split item [item]. */
    private fun partAnchors(item: Int, part: Int, whole: SectionContent): Map<String, Int>? {
        val m = fragParts[item] ?: return null
        var out: HashMap<String, Int>? = null
        for ((f, p) in m) {
            if (p != part) continue
            val v = whole.anchors[f] ?: continue
            (out ?: HashMap<String, Int>().also { out = it })[f] = v
        }
        return out
    }

    /** Converted split item [item] with its part ranges (converted once; the last few are kept). */
    private fun splitItem(item: Int): SplitItem {
        synchronized(cache) { itemCache[item]?.let { return it } }
        synchronized(itemLocks[item]) {
            synchronized(cache) { itemCache[item]?.let { return it } }
            val whole = convert(item)
            val cuts = cutItem(item, whole)
            val s = SplitItem(whole, cuts)
            synchronized(cache) {
                itemCache[item] = s
                anchorCache[item] = whole.anchors
                cutCache[item] = cuts
            }
            return s
        }
    }

    private fun cutItem(item: Int, whole: SectionContent): EpubSplit.Cuts {
        val n = parts[item]
        return try {
            val m = fragParts[item]
            val offs = ArrayList<Int>()
            val ps = ArrayList<Int>()
            if (m != null) {
                for ((f, p) in m) {
                    val v = whole.anchors[f] ?: continue
                    offs.add(v)
                    ps.add(p)
                }
            }
            EpubSplit.cut(whole, n, offs.toIntArray(), ps.toIntArray())
        } catch (_: RuntimeException) {
            EpubSplit.trivial(whole.length, n)
        }
    }

    /**
     * Item-wide anchor offsets of spine item [item] without re-converting it when it was converted before: the
     * TOC dialog resolves every entry (and page counting loads every section), while the content LRUs evict.
     */
    private fun anchorsOf(item: Int): Map<String, Int> {
        synchronized(cache) { anchorCache[item]?.let { return it } }
        return if (parts[item] == 1) loadSection(firstSection[item]).anchors else splitItem(item).content.anchors
    }

    /** Section position of item-wide offset [off] in spine item [item]. */
    private fun locate(item: Int, off: Int): DocPosition {
        if (parts[item] == 1) return DocPosition(firstSection[item], off)
        val cuts = synchronized(cache) { cutCache[item] } ?: splitItem(item).cuts
        val k = cuts.locate(off)
        return DocPosition(firstSection[item] + k, cuts.local(k, off))
    }

    /** Number of spine item conversions performed (tests / diagnostics). */
    @Volatile internal var conversions = 0
        private set

    private fun convert(index: Int): SectionContent {
        conversions++ // only under itemLocks[index]; an approximate count across items is fine
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

    // ================================================================ navigation

    override fun resolveLink(fromSection: Int, href: String): DocPosition? {
        val h = href.trim()
        if (h.isEmpty() || EpubPaths.hasScheme(h)) return null
        val frag = EpubPaths.fragment(h)
        val pathPart = EpubPaths.stripFragment(h)
        val fromItem = if (fromSection in 0 until sectionCount) itemOfSection[fromSection] else -1
        val item = if (pathPart.isEmpty()) {
            if (fromItem < 0) return null
            fromItem
        } else {
            val base = if (fromItem >= 0) EpubPaths.dirOf(spine[fromItem].path) else ""
            spineIndexOf(base, h) ?: return null
        }
        if (frag == null) return DocPosition(firstSection[item], 0)
        val off = anchorsOf(item)[frag]
            ?: return if (pathPart.isEmpty()) null else DocPosition(firstSection[item], 0)
        return locate(item, off)
    }

    override fun resolveToc(entry: TocEntry): DocPosition {
        if (sectionCount == 0) return DocPosition.START
        val s = entry.section.coerceIn(0, sectionCount - 1)
        val a = entry.anchor
        if (a.isNullOrEmpty()) return DocPosition(s, maxOf(0, entry.offset))
        val item = itemOfSection[s]
        val off = anchorsOf(item)[a] ?: return DocPosition(s, maxOf(0, entry.offset))
        return locate(item, off)
    }

    /** Spine index of [href] (fragment ignored) relative to [baseDir], or null if not in the spine. */
    private fun spineIndexOf(baseDir: String, href: String): Int? {
        val p = EpubPaths.resolve(baseDir, href)
        val c = zip.find(p) ?: zip.find(EpubPaths.resolve(baseDir, href, decode = false)) ?: p
        return spineIndex[c]
    }

    /** EPUB3 nav, else NCX (also when the nav exists but none of its links reach the spine), else headings. */
    private fun buildToc(): List<TocRef> {
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

    private fun resolveRaw(raw: List<RawTocEntry>, base: String): List<TocRef> {
        val out = ArrayList<TocRef>(raw.size)
        for (e in raw) {
            if (EpubPaths.hasScheme(e.href)) continue
            val item = spineIndexOf(base, e.href) ?: continue
            out.add(TocRef(e.title, e.level, item, EpubPaths.fragment(e.href)))
        }
        return out
    }

    /**
     * One entry per spine item with a heading or distinctive `<title>`. Scans only each file's head: a short
     * prefix first, the longer one only when the short one cannot decide (no complete heading in it).
     */
    private fun fallbackToc(): List<TocRef> {
        val out = ArrayList<TocRef>()
        var prev: String? = null
        for ((i, item) in spine.withIndex()) {
            if (!item.isHtml) continue
            val t = scanTitleOf(item.path) ?: continue
            if (t == prev) continue
            prev = t
            out.add(TocRef(t, 1, i, null))
        }
        return out
    }

    private fun scanTitleOf(path: String): String? {
        val size = zip.size(path)
        val head = zip.readPrefix(path, TITLE_PEEK_BYTES) ?: return null
        val truncated = size < 0 || size > head.size
        val t = EpubTocParser.scanTitle(EpubText.decode(head), pkg.title, truncated)
        if (t !== EpubTocParser.UNDECIDED) return t
        val bytes = zip.readPrefix(path, TITLE_SCAN_BYTES) ?: return null
        return EpubTocParser.scanTitle(EpubText.decode(bytes), pkg.title)
    }

    override fun close() {
        zip.close()
        synchronized(cache) {
            cache.clear()
            itemCache.clear()
        }
    }

    companion object {
        const val CACHE_SIZE = 4
        /** Whole converted split items kept (a reader, its prefetch and page counting share one or two). */
        const val ITEM_CACHE_SIZE = 2
        private const val IMAGE_APPROX_CHARS = 500
        private const val UNKNOWN_APPROX_CHARS = 2000
        private const val MAX_CSS_BYTES = 4 * 1024 * 1024
        private const val TITLE_PEEK_BYTES = 8 * 1024
        private const val TITLE_SCAN_BYTES = 24 * 1024

        /** Displayable spine items (XHTML or image) in reading order, with canonical zip paths; may be empty. */
        internal fun selectSpine(zip: EpubZip, pkg: EpubPackage): List<ManifestItem> {
            fun usable(it: ManifestItem) = (it.isHtml || it.isImage) && zip.find(it.path) != null
            var items = pkg.spine.filter(::usable)
            if (items.isEmpty()) items = pkg.manifest.filter { it.isHtml && !it.hasProperty("nav") && usable(it) }
            if (items.isEmpty()) items = EpubDocuments.htmlEntries(zip).map { ManifestItem(it, it, "", "") }
            return items.map { item ->
                val canonical = zip.find(item.path) ?: item.path
                if (canonical == item.path) item else ManifestItem(item.id, canonical, item.mediaType, item.properties)
            }
        }

        /**
         * Cover image entry: EPUB3 cover-image, EPUB2 meta cover, guide cover, an image named "cover", else the
         * first image of [first] (the first spine item). Reads at most one or two small documents.
         */
        internal fun findCover(zip: EpubZip, pkg: EpubPackage, first: ManifestItem?): String? {
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
            if (first == null) return null
            return if (first.isImage) image(first.path) else firstImageOf(first.path)
        }

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
