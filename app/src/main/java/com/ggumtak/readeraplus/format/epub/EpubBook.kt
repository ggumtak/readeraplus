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
import java.io.IOException

/**
 * An opened EPUB: one section per spine item, except that oversized items (whole-book files from TXT→EPUB
 * converters) are split into several sections (see [EpubSplit]). Opening parses container.xml, the OPF and the
 * TOC (nav / NCX), and scans the text of items above [EpubSplit.SCAN_MIN_BYTES] to fix their part count;
 * content documents are converted on demand (last [CACHE_SIZE] sections kept, plus the last [ITEM_CACHE_SIZE]
 * converted split items so their parts are cut without converting again) and the cover loads on request. A poor
 * TOC (under 2 entries, or entries only at item starts) is completed with the chapters the TXT rules detect in the
 * scanned text ([EpubHeadings]).
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
        if (it.isEmpty()) throw DocumentException("내용이 없는 책입니다")
    }

    /**
     * Spine entries the book lists but doesn't hold (no manifest item, or no file in the zip): they are skipped, and the
     * reader says so once (a partly copied or broken EPUB used to open silently with chapters missing).
     */
    val missingSpineItems: Int = missingSpine(zip, pkg)

    /** Canonical zip path → first spine index. */
    private val spineIndex = HashMap<String, Int>(spine.size * 2).also { m ->
        for ((i, it) in spine.withIndex()) m.putIfAbsent(it.path, i)
    }

    /**
     * TOC targets as (spine item, fragment), parsed while opening (off the main thread); [planSections] adds the
     * detected chapter headings when the book's own TOC is poor.
     */
    private var tocRefs: List<TocRef> = try {
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

    /**
     * Detected chapter headings per spine item (null: none; [EpubHeadings]). They never take part in the section
     * split: the cuts are those of the book without them. They are marked in the item after it is cut.
     */
    private val itemHeads = arrayOfNulls<Array<EpubHeadings.Head>>(spine.size)

    /** A fresh plan, staged once the headings' sections are known ([tocEntries]); null: nothing to stage. */
    private var stagedKey: String? = null
    private var stagedPlan: EpubPlanCache.Plan? = null

    /** The plan came from [EpubPlanCache] (no item was scanned while opening; tests / diagnostics). */
    internal var planFromCache = false
        private set

    /** Items above [EpubSplit.SCAN_MIN_BYTES] whose text this open scanned (0: none that big, or a cached plan). */
    internal var scannedItems = 0
        private set

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

    /** [head]: index of the detected heading in [itemHeads] of [item] (-1: a TOC entry of the book). */
    private class TocRef(val title: String, val level: Int, val item: Int, val frag: String?, val head: Int = -1)

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
     * Fixes the part count of every spine item. Items above [EpubSplit.SCAN_MIN_BYTES] are read (whole documents
     * that big are rare outside converter output); their text scan also places the TOC anchors. When the TOC is poor
     * ([detectWanted]) the same scan collects block texts, also of smaller items, and the TXT chapter rules look for
     * headings in them ([EpubHeadings]): each becomes a synthetic TOC entry. They do not move the split: part counts
     * and cuts (hence every saved position) are exactly those of the book without detection. The result for the scanned items is cached per file ([EpubPlanCache], A12-1):
     * looked up only when an item that big (or a good deal of smaller text) was scanned, and a fresh plan is staged
     * for writing after the first page, never written here.
     */
    private fun planSections() {
        var wanted: Array<HashSet<String>?>? = null
        for (r in tocRefs) {
            val f = r.frag ?: continue
            if (f.isEmpty()) continue
            val w = wanted ?: arrayOfNulls<HashSet<String>>(spine.size).also { wanted = it }
            (w[r.item] ?: HashSet<String>().also { w[r.item] = it }).add(f)
        }
        val detect = detectWanted()
        // With a title entry or one entry per file, a smaller item can still hold many chapters: from this size on.
        val detectMin = if (tocRefs.size < 2) 1L else DETECT_ITEM_MIN_BYTES
        var scan = IntArray(0)
        var nScan = 0
        var nBig = 0
        var smallBytes = 0L
        for ((i, item) in spine.withIndex()) {
            if (item.isImage) {
                itemChars[i] = IMAGE_APPROX_CHARS
                continue
            }
            val sz = zip.size(item.path)
            itemChars[i] = if (sz < 0) UNKNOWN_APPROX_CHARS else maxOf(1L, sz / 3).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            if (sz > EpubSplit.SCAN_MIN_BYTES) {
                nBig++
            } else {
                if (!detect || !item.isHtml || sz < detectMin || smallBytes + sz > DETECT_SMALL_BUDGET) continue
                smallBytes += sz
            }
            if (nScan == scan.size) scan = scan.copyOf(maxOf(4, nScan * 2))
            scan[nScan++] = i
        }
        if (nScan == 0) return
        val useCache = nBig > 0 || smallBytes > EpubSplit.SCAN_MIN_BYTES
        val key = if (useCache && EpubPlanCache.dir() != null) EpubPlanCache.key(file, if (detect) detectSignature() else "") else null
        val anchors = if (key != null) EpubPlanCache.anchorHash(scan, nScan, wanted) else 0L
        if (key != null) {
            EpubPlanCache.load(key)?.let {
                if (usePlan(it, scan, nScan, anchors)) {
                    addHeadings()
                    return
                }
            }
        }

        scannedItems = nBig
        val chars = IntArray(nScan)
        val scans = arrayOfNulls<EpubSplit.Scan>(nScan)
        var complete = true
        for (k in 0 until nScan) {
            val i = scan[k]
            val r = scanItem(spine[i].path, wanted?.get(i) ?: emptySet<String>(), if (detect) EpubSplit.Lines() else null)
            if (r == null) {
                complete = false // maybe out of memory this time: not cached, the next open scans again
                continue
            }
            scans[k] = r
            chars[k] = r.chars
        }
        if (detect) {
            val found = EpubHeadings.detect(Array(nScan) { scans[it]?.lines }, chars, options.txtChapterRegex)
            val allStart = tocRefs.all { it.frag.isNullOrEmpty() }
            if (EpubHeadings.replacesToc(tocRefs.size, allStart, found.total)) {
                // The headings stay out of the split: part count and cuts are those of a book without them.
                for (k in 0 until nScan) found.heads[k]?.let { itemHeads[scan[k]] = it }
            }
        }
        val frags = arrayOfNulls<Map<String, Int>>(nScan)
        for (k in 0 until nScan) {
            val r = scans[k] ?: continue
            val i = scan[k]
            // an item that was scanned for its headings only is no split candidate: it stays one section
            if (zip.size(spine[i].path) <= EpubSplit.SCAN_MIN_BYTES) continue
            val n = EpubSplit.partsFor(r.chars)
            if (n <= 1) continue
            parts[i] = n
            itemChars[i] = r.chars
            fragParts[i] = EpubSplit.assign(r, n).also { frags[k] = it }
        }
        if (complete && key != null) {
            val items = scan.copyOf(nScan)
            stagedKey = key
            stagedPlan = EpubPlanCache.Plan(
                spine.size, anchors, items, IntArray(nScan) { parts[items[it]] }, chars, frags, Array(nScan) { itemHeads[items[it]] },
            )
        }
        addHeadings()
    }

    /**
     * Chapter headings are looked for: the reader detects chapters (EPUB too) and the book's TOC may be poor: fewer
     * than 2 entries, or only entries at the start of a spine item (a title entry, one entry per file). A TOC with an
     * anchor inside an item is the book's own; the headings found are weighed against it afterwards
     * ([EpubHeadings.replacesToc]).
     */
    private fun detectWanted(): Boolean =
        options.txtDetectChapters && (tocRefs.size < 2 || tocRefs.all { it.frag.isNullOrEmpty() })

    /** The options that decide which headings are found: part of the plan's key. */
    private fun detectSignature(): String = options.txtChapterRegex.length.toString() + ":" + options.txtChapterRegex

    /** Applies a cached [plan] when it covers exactly the [n] scanned items [items]; false leaves everything as is. */
    private fun usePlan(plan: EpubPlanCache.Plan, items: IntArray, n: Int, anchors: Long): Boolean {
        if (plan.spineSize != spine.size || plan.anchors != anchors || plan.items.size != n) return false
        for (k in 0 until n) if (plan.items[k] != items[k]) return false
        for (k in 0 until n) {
            val i = items[k]
            plan.heads.getOrNull(k)?.let { itemHeads[i] = it }
            val p = plan.parts[k]
            if (p <= 1) continue
            parts[i] = p
            itemChars[i] = plan.chars[k]
            fragParts[i] = HashMap(plan.frags[k] ?: emptyMap())
        }
        planFromCache = true
        return true
    }

    /**
     * Adds a TOC entry for every detected heading, after the book's own entries of its item (a title entry stays
     * first); the entries of later items follow their headings. Every heading carries its synthetic anchor.
     */
    private fun addHeadings() {
        var any = false
        for (h in itemHeads) if (h != null) any = true
        if (!any) return
        val out = ArrayList<TocRef>(tocRefs.size + 64)
        var next = 0 // first spine item whose headings are not yet in [out]
        fun headingsBefore(item: Int) {
            while (next < item) {
                itemHeads[next]?.let { h -> for (j in h.indices) out.add(TocRef(h[j].title, 1, next, EpubHeadings.id(j), j)) }
                next++
            }
        }
        for (r in tocRefs) {
            headingsBefore(r.item)
            out.add(r)
        }
        headingsBefore(spine.size)
        tocRefs = out
    }

    private fun scanItem(path: String, wanted: Set<String>, lines: EpubSplit.Lines?): EpubSplit.Scan? = try {
        zip.read(path)?.let { EpubSplit.scan(EpubText.decode(it), wanted, lines) }
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
     * part's content can still resolve it should the converter find it). A detected heading has the exact place the
     * cut gave it ([placeHeadings]).
     */
    private fun tocEntries(): List<TocEntry> {
        val out = ArrayList<TocEntry>(tocRefs.size)
        val lastPart = IntArray(spine.size)
        for (r in tocRefs) {
            if (r.head >= 0) {
                val h = placeHeadings(r.item)[r.head]
                out.add(TocEntry(r.title, r.level, firstSection[r.item] + h.part.coerceIn(0, parts[r.item] - 1), h.offset, r.frag))
                continue
            }
            var part = 0
            val m = fragParts[r.item]
            val f = r.frag
            if (m != null && !f.isNullOrEmpty()) {
                part = m[f] ?: lastPart[r.item].also { m[f] = it }
                lastPart[r.item] = part
            }
            out.add(TocEntry(r.title, r.level, firstSection[r.item] + part, 0, f))
        }
        val key = stagedKey
        val plan = stagedPlan
        if (key != null && plan != null) EpubPlanCache.stage(key, plan) // with the headings' places
        stagedKey = null
        stagedPlan = null
        return out
    }

    /**
     * The detected headings of [item] with their place in the sections ([EpubHeadings.Head.part] / `offset`). A cached
     * plan has them; else a split item is converted and cut now (the cut it would get anyway for its first page, kept
     * in the item cache) and each heading is looked up in it. The headings take no part in the cut, so a heading may
     * sit at either side of a cut: it belongs to the part that holds its anchor. A heading its block was not found
     * for stays where the heading before it is. An item of one section needs no conversion: its headings are in
     * section 0 and their offsets come with the anchors, as a book's own anchored entries do.
     */
    private fun placeHeadings(item: Int): Array<EpubHeadings.Head> {
        val heads = itemHeads[item]!!
        if (heads.all { it.part >= 0 }) return heads
        if (parts[item] == 1) {
            for (h in heads) {
                h.part = 0
                h.offset = 0
            }
            return heads
        }
        val split = try {
            splitItem(item)
        } catch (_: DocumentException) {
            null
        } catch (_: IOException) {
            null
        }
        var part = 0
        var offset = 0
        for ((j, h) in heads.withIndex()) {
            val at = split?.content?.anchors?.get(EpubHeadings.id(j))
            if (at != null) {
                part = split.cuts.locate(at)
                offset = split.cuts.local(part, at)
            }
            h.part = part
            h.offset = offset
        }
        return heads
    }

    // ================================================================ sections

    override fun loadSection(index: Int): SectionContent {
        if (index < 0 || index >= sectionCount) return SectionContent.EMPTY
        synchronized(cache) { cache[index]?.let { return it } }
        val item = itemOfSection[index]
        if (parts[item] == 1) {
            synchronized(itemLocks[item]) {
                synchronized(cache) { cache[index]?.let { return it } }
                val content = withHeadings(item, convert(item))
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
            val plain = convert(item)
            // cut first: the headings' look (heading style, page break) must not move a cut
            val cuts = cutItem(item, plain)
            val whole = withHeadings(item, plain)
            val s = SplitItem(whole, cuts)
            synchronized(cache) {
                itemCache[item] = s
                anchorCache[item] = whole.anchors
                cutCache[item] = cuts
            }
            return s
        }
    }

    /** [content] of [item] with its detected headings marked (the text and so every offset stay the same). */
    private fun withHeadings(item: Int, content: SectionContent): SectionContent {
        val heads = itemHeads[item] ?: return content
        return try {
            EpubHeadings.apply(content, heads, options.txtEmphasizeHeadings)
        } catch (_: RuntimeException) {
            content
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
        return try {
            if (parts[item] == 1) loadSection(firstSection[item]).anchors else splitItem(item).content.anchors
        } catch (_: DocumentException) { // unreadable item (nothing cached): the link / TOC entry lands at its start
            emptyMap()
        } catch (_: IOException) {
            emptyMap()
        }
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

    /**
     * Parsed while opening (off the main thread): readers touch it right after open, on the UI thread. Declared after
     * everything a conversion uses: placing the detected headings converts and cuts their (split) item once.
     */
    override val toc: List<TocEntry> = tocEntries()

    private fun convert(index: Int): SectionContent {
        conversions++ // only under itemLocks[index]; an approximate count across items is fine
        val item = spine[index]
        if (item.isImage) {
            return SectionContent(OBJECT_CHAR.toString(), listOf(ImageBlock(0, item.path)))
        }
        return try {
            // The section's own file must be readable: a failure throws (DocumentException / IOException, which no
            // caller caches) instead of becoming an empty page that the caches and the page counter would keep.
            val bytes = zip.readOrThrow(item.path)
            XhtmlConverter(options.epubPublisherStyles, resources, options.epubIgnoreBookSizes).convert(EpubText.decode(bytes), item.path)
        } catch (_: RuntimeException) {
            errorSection()
        } catch (_: StackOverflowError) {
            errorSection()
        } catch (_: OutOfMemoryError) { // a pathological multi-MB single document: its buffers are local
            errorSection()
        }
    }

    private fun errorSection(): SectionContent {
        val msg = "(이 부분을 표시하지 못했습니다)"
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
        /** With a TOC of entries at item starts only, items from this size on are searched for chapter headings. */
        private const val DETECT_ITEM_MIN_BYTES = 48 * 1024L
        /** Most bytes of items below [EpubSplit.SCAN_MIN_BYTES] read for chapter detection in one open. */
        private const val DETECT_SMALL_BUDGET = 8L * 1024 * 1024
        private const val TITLE_PEEK_BYTES = 8 * 1024
        private const val TITLE_SCAN_BYTES = 24 * 1024

        /** See [missingSpineItems]. */
        internal fun missingSpine(zip: EpubZip, pkg: EpubPackage): Int =
            (pkg.spineRefCount - pkg.spine.size).coerceAtLeast(0) +
                pkg.spine.count { (it.isHtml || it.isImage) && zip.find(it.path) == null }

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
                throw DocumentException(EpubDocuments.MALFORMED, e)
            } catch (e: StackOverflowError) {
                zip.close()
                throw DocumentException(EpubDocuments.MALFORMED, e)
            }
        }
    }
}
