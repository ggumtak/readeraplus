package com.ggumtak.readeraplus.data

import android.database.Cursor
import android.util.Log
import com.ggumtak.readeraplus.BuildConfig
import java.util.TimeZone

/**
 * The notes hub's read side (N §5.3): quotes, bookmarks, reviews and lookups as one list. Every call is blocking, runs
 * on IO and is thread-safe. Lists are read in two phases: narrow keys ([NotesSql]: `k, id, b, t, s, o`) for a page,
 * then at most four primary-key detail statements with `substr` (never a whole quote, memo or review).
 *
 * While a search is active one key scan per query ([NotesKeys]) serves counts, colour counts, both orders and the
 * book list. Every cache is keyed by [generation] (`Library.notesGen` plus this module's own lookup writes), so a
 * write anywhere drops them all.
 *
 * Timings go to logcat tag `RANotes` in debug builds (N §5.8 budgets; `DebugSeed` fills a 10,000-note fixture).
 */
object Notes {
    const val PAGE_ROWS = 50; const val BODY_CHARS = 600; const val NOTE_CHARS = 400

    /** Logcat tag of the timing lines (debug builds). */
    const val TAG = "RANotes"

    /** Bumped by [Lookups] writes (Library's [Library.notesGen] covers every other write). */
    @Volatile private var localGen = 0L
    private val genLock = Any()

    /** Change counter of everything the hub shows: changes after every committed write. Cheap; any thread. */
    fun generation(): Long = Library.notesGen + localGen

    internal fun bumpLocal() {
        synchronized(genLock) { localGen++ }
    }

    // ---- caches (all keyed by generation()) ----

    private class GenCache<K : Any, V : Any>(private val max: Int) {
        private var gen = Long.MIN_VALUE
        private val map = object : LinkedHashMap<K, V>(max * 2, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean = size > max
        }

        @Synchronized fun get(gen: Long, key: K): V? {
            if (gen != this.gen) { map.clear(); this.gen = gen; return null }
            return map[key]
        }

        @Synchronized fun put(gen: Long, key: K, value: V) {
            if (gen != this.gen) { map.clear(); this.gen = gen }
            map[key] = value
        }
    }

    private inline fun <K : Any, V : Any> GenCache<K, V>.getOrLoad(key: K, load: () -> V): V {
        val g = generation()
        get(g, key)?.let { return it }
        val v = load()
        put(g, key, v)
        return v
    }

    private data class CountsKey(val bookId: Long?, val style: Int?, val wordsOnce: Boolean, val text: String)
    private data class KeysKey(val text: String, val bookId: Long?, val wordsOnce: Boolean)
    private data class BooksKey(val tab: NotesTab, val order: NotesOrder, val bookId: Long?, val text: String, val style: Int?, val wordsOnce: Boolean)
    private data class StyleKey(val bookId: Long?, val text: String, val wordsOnce: Boolean)
    private data class OrderKey(val keys: NotesKeys, val tab: NotesTab, val order: NotesOrder, val style: Int?, val books: List<Long>?)

    private val countsCache = GenCache<CountsKey, NotesCounts>(8)
    private val keysCache = GenCache<KeysKey, NotesKeys>(2)
    private val booksCache = GenCache<BooksKey, List<NoteBook>>(6)
    private val styleCache = GenCache<StyleKey, IntArray>(4)
    private val drawerCache = GenCache<Unit, IntArray>(1)
    private val orderCache = GenCache<OrderKey, IntArray>(2)
    /** The NoteBook title map: book id → info (count 0), filled for ids not yet in it. */
    private val infoCache = GenCache<Long, NoteBook>(4096)

    // ---- API ----

    /** Tab counts of [q] (tab and order don't change them; the colour chip narrows the 인용문 count). */
    fun counts(q: NotesQuery): NotesCounts {
        val t0 = System.nanoTime()
        val style = NotesSql.styleFilter(q)
        val text = q.text.trim()
        val r = countsCache.getOrLoad(CountsKey(q.bookId, style, q.wordsOnce, text)) {
            if (text.isEmpty()) {
                val s = NotesSql.counts(q.bookId, style, q.wordsOnce)
                Library.db().queryFirst(s.sql, s.args.ifEmptyNull()) { c ->
                    NotesCounts(c.getInt(0), c.getInt(4) + c.getInt(5), c.getInt(1), c.getInt(2), c.getInt(3))
                } ?: NotesCounts(0, 0, 0, 0, 0)
            } else {
                keys(q).counts(style)
            }
        }
        perf("counts", t0, 15)
        return r
    }

    /** Quote count per style 0..[DataLimits.QUOTE_STYLE_MAX] under [q]'s book and search (not its colour chip). */
    fun styleCounts(q: NotesQuery): IntArray {
        val text = q.text.trim()
        val r = styleCache.getOrLoad(StyleKey(q.bookId, text, if (text.isEmpty()) false else q.wordsOnce)) {
            if (text.isEmpty()) {
                val out = IntArray(DataLimits.QUOTE_STYLE_MAX + 1)
                val s = NotesSql.styleCounts(q.bookId)
                Library.db().queryList(s.sql, s.args.ifEmptyNull()) { c ->
                    out[NotesKeys.styleOf(c.getInt(0))] += c.getInt(1)
                }
                out
            } else {
                keys(q).styleCounts()
            }
        }
        return r.copyOf()
    }

    /** Books with notes under [q], sorted per its order (natural title for the date orders). */
    fun books(q: NotesQuery): List<NoteBook> {
        val t0 = System.nanoTime()
        val text = q.text.trim()
        val style = NotesSql.styleFilter(q)
        val r = booksCache.getOrLoad(BooksKey(q.tab, if (q.order == NotesOrder.BOOK_RECENT) q.order else NotesOrder.BOOK_TITLE, q.bookId, text, style, q.wordsOnce)) {
            val list = if (text.isEmpty()) {
                val s = NotesSql.books(q)
                Library.db().queryList(s.sql, s.args.ifEmptyNull()) { c ->
                    val id = c.getLong(0)
                    if (c.isNull(1)) NotesExport.deletedBook(id, c.getInt(7))
                    else NoteBook(id, c.getString(1) ?: "", c.getString(2) ?: "", c.getString(3) ?: "",
                        c.getInt(4) != 0, c.getLong(5) > 0 && c.getInt(4) != 0, c.getLong(6), c.getInt(7))
                }
            } else {
                val k = keys(q)
                val counts = k.bookCounts(k.filter(q))
                val info = bookInfo(counts.keys)
                counts.map { (id, n) -> (info[id] ?: NotesExport.deletedBook(id, 0)).copy(count = n) }
            }
            sortBooks(list, q.order)
        }
        perf("books", t0, 50)
        return r
    }

    /**
     * Page [index] of [q] ([PAGE_ROWS] rows; `before` = the previous page's last row for `index > 0`). [books] is the
     * list [books] returned for [q] (required for book orders; loaded when null). `firstOfBook[i]` marks a row whose
     * book differs from the row before it (book orders only; date orders get all false).
     */
    fun page(q: NotesQuery, index: Int, books: List<NoteBook>?): NotesPage {
        val t0 = System.nanoTime()
        val keys: List<Key> = when {
            q.text.isNotBlank() -> {
                val order = ordered(q, books)
                val from = NotesSql.pageOffset(index)
                val to = minOf(order.size, from + NotesSql.pageLimit(index))
                val k = keys(q)
                if (from >= to) emptyList() else List(to - from) { i -> k.key(order[from + i]) }
            }
            !q.order.byBook -> readKeys(NotesSql.datePage(q, index))
            else -> bookPageKeys(q, index, books ?: books(q))
        }
        val rows = details(keys, false)
        val before = if (index > 0 && rows.isNotEmpty() && keys.isNotEmpty() && rows[0].ref == keys[0].ref) rows[0] else null
        // The first key of a page p > 0 is the previous page's last row; when it vanished meanwhile, rows has no copy.
        val list = if (before != null) rows.subList(1, rows.size) else rows
        val first = BooleanArray(list.size)
        if (q.order.byBook) {
            var prev = before?.bookId ?: Long.MIN_VALUE
            for (i in list.indices) {
                first[i] = list[i].bookId != prev
                prev = list[i].bookId
            }
        }
        perf(if (q.text.isNotBlank()) "search page $index" else "page $index", t0, if (q.text.isNotBlank()) 150 else if (index <= 20) 40 else 80)
        return NotesPage(index, ArrayList(list), before, first)
    }

    /** Packed [NoteRef] of every row of [q], in its order (select all, export). */
    fun refs(q: NotesQuery): LongArray {
        if (q.text.isBlank() && !q.order.byBook) {
            val s = NotesSql.dateAll(q)
            val out = LongArrayList(256)
            Library.db().queryList(s.sql, s.args.ifEmptyNull()) { c -> out.add((c.getLong(0) shl 56) or (c.getLong(1) and ID_MASK)) }
            return out.toArray()
        }
        val k = keys(q)
        val order = ordered(q, null)
        return LongArray(order.size) { k.packed(order[it]) }
    }

    /** Untruncated (body, note) of one note; null when it is gone. */
    fun fullText(ref: NoteRef): Pair<String, String>? =
        Library.db().queryFirst(NotesSql.fullText(ref.kind), args(ref.id)) { c -> (c.getString(0) ?: "") to (c.getString(1) ?: "") }

    /** Drawer badge: `[quotes + bookmarks + reviews, lookups]`. */
    fun drawerCounts(): IntArray {
        val t0 = System.nanoTime()
        val r = drawerCache.getOrLoad(Unit) {
            Library.db().queryFirst(NotesSql.DRAWER_COUNTS, null) { c -> intArrayOf(c.getInt(0), c.getInt(1)) } ?: IntArray(2)
        }
        perf("drawerCounts", t0, 5)
        return r.copyOf()
    }

    /** Notes (quotes, bookmarks, reviews, lookups) of [bookIds]: the delete / empty-trash warnings. */
    fun countForBooks(bookIds: Collection<Long>): Int {
        if (bookIds.isEmpty()) return 0
        val ids = bookIds.distinct()
        var sum = 0
        var i = 0
        while (i < ids.size) {
            val s = NotesSql.countForBooks(ids.subList(i, minOf(ids.size, i + NotesSql.COUNT_CHUNK)))
            sum += Library.db().queryFirst(s.sql, s.args) { it.getInt(0) } ?: 0
            i += NotesSql.COUNT_CHUNK
        }
        return sum
    }

    /**
     * Writes [refs] (a selection; null = every row of [q]) as [format] to [out], one book's full texts in memory at a
     * time. Returns the number of notes written (= the header counts).
     */
    fun export(refs: LongArray?, q: NotesQuery, format: NotesExport.Format, out: java.io.Writer, now: Long): Int {
        val t0 = System.nanoTime()
        val plan = exportPlan(refs, q)
        val n = NotesExport.render(plan.chunks(), plan.summary, NotesExport.scope(q, plan.scopeBooks, refs?.let { plan.summary.items }, format),
            format, NotesExport.WriterSink(out), now, TimeZone.getDefault())
        perf("export $n", t0, 3000)
        return n
    }

    /**
     * The 공유 text of [refs] (null = every row of [q]): TXT, at most `TextActions.SHARE_MAX_CHARS` chars, cut at an
     * item boundary with "…(나머지 N개는 '내보내기'로 저장하세요)". `cut` → the caller toasts "노트가 많아 앞부분만
     * 공유합니다".
     */
    fun share(refs: LongArray?, q: NotesQuery, now: Long): NotesShare {
        val plan = exportPlan(refs, q)
        val s = NotesExport.share(plan.chunks(), plan.summary,
            NotesExport.scope(q, plan.scopeBooks, refs?.let { plan.summary.items }, NotesExport.Format.TXT), now)
        return NotesShare(s.text, s.written, s.cut)
    }

    // ---- keys ----

    /** One key row: kind code, row id, book id. */
    internal class Key(val k: Int, val id: Long, val b: Long) {
        val ref: NoteRef get() = NoteRef.unpack((k.toLong() shl 56) or (id and ID_MASK)) ?: NoteRef(NoteKind.QUOTE, id)
    }

    private fun NotesKeys.key(i: Int) = Key(kind(i), id(i), book(i))

    private fun readKeys(s: SqlQuery): List<Key> =
        Library.db().queryList(s.sql, s.args.ifEmptyNull()) { c -> Key(c.getInt(0), c.getLong(1), c.getLong(2)) }

    /** The key scan of [q]'s book + search (+ wordsOnce), cached: one statement per search text. */
    private fun keys(q: NotesQuery): NotesKeys {
        val text = q.text.trim()
        return keysCache.getOrLoad(KeysKey(text, q.bookId, q.wordsOnce)) {
            val t0 = System.nanoTime()
            val s = NotesSql.scan(NotesQuery(bookId = q.bookId, text = text, wordsOnce = q.wordsOnce))
            val b = NotesKeys.Builder(256)
            val c = Library.db().rawQuery(s.sql, s.args.ifEmptyNull())
            try {
                while (c.moveToNext()) {
                    b.add(c.getInt(0), c.getLong(1), c.getLong(2), c.getLong(3), c.getInt(4), c.getInt(5), c.getInt(6) != 0, c.getInt(7))
                }
            } finally {
                c.close()
            }
            val k = b.build()
            perf("key scan ${k.size}", t0, 100)
            k
        }
    }

    /** Row indexes of the key scan in [q]'s tab and order. */
    private fun ordered(q: NotesQuery, books: List<NoteBook>?): IntArray {
        val k = keys(q)
        val bookIds = if (q.order.byBook) (books ?: books(q)).map { it.id } else null
        return orderCache.getOrLoad(OrderKey(k, q.tab, q.order, NotesSql.styleFilter(q), bookIds)) {
            val rows = k.filter(q.copy(style = NotesSql.styleFilter(q)))
            if (bookIds != null) k.bookOrder(rows, bookIds) else k.dateOrder(rows, q.order == NotesOrder.OLDEST)
        }
    }

    /** Keys of a book-order page: one statement over the page's books (≤ 51), offset = the first book's inner row. */
    private fun bookPageKeys(q: NotesQuery, index: Int, books: List<NoteBook>): List<Key> {
        if (books.isEmpty()) return emptyList()
        val prefix = BookSpans.prefix(IntArray(books.size) { books[it].count })
        val total = prefix[books.size]
        val from = NotesSql.pageOffset(index)
        if (from >= total) return emptyList()
        val last = minOf(total, from + NotesSql.pageLimit(index)) - 1
        val a = BookSpans.locate(prefix, from)
        val z = BookSpans.locate(prefix, last)
        if (a < 0 || z < 0) return emptyList()
        val first = (a ushr 32).toInt()
        val lastBook = (z ushr 32).toInt()
        val ids = ArrayList<Long>(lastBook - first + 1)
        for (i in first..lastBook) ids += books[i].id
        return readKeys(NotesSql.bookPage(q, ids, last - from + 1, (a and 0xFFFF_FFFFL).toInt()))
    }

    // ---- details ----

    /** Rows for [keys] in key order (gone rows are skipped). [full] = untruncated texts (export). */
    private fun details(keys: List<Key>, full: Boolean): List<NoteRow> {
        if (keys.isEmpty()) return emptyList()
        val ids = Array(5) { ArrayList<Long>() }
        for (k in keys) if (k.k in 1..4) ids[k.k] += k.id
        val found = HashMap<Long, NoteRow>(keys.size * 2)
        val db = Library.db()
        for (kind in NoteKind.entries) {
            val list = ids[kind.code]
            var i = 0
            while (i < list.size) {
                val chunk = list.subList(i, minOf(list.size, i + NotesSql.DETAIL_CHUNK))
                val s = when (kind) {
                    NoteKind.QUOTE -> NotesSql.quoteDetails(chunk, full)
                    NoteKind.BOOKMARK -> NotesSql.bookmarkDetails(chunk, full)
                    NoteKind.REVIEW -> NotesSql.reviewDetails(chunk, full)
                    NoteKind.LOOKUP -> NotesSql.lookupDetails(chunk, full)
                }
                db.queryList(s.sql, s.args) { c ->
                    val r = row(kind, c, full)
                    found[r.ref.packed()] = r
                }
                i += NotesSql.DETAIL_CHUNK
            }
        }
        val out = ArrayList<NoteRow>(keys.size)
        for (k in keys) found[(k.k.toLong() shl 56) or (k.id and ID_MASK)]?.let { out += it }
        return out
    }

    private fun row(kind: NoteKind, c: Cursor, full: Boolean): NoteRow = when (kind) {
        NoteKind.QUOTE -> {
            val body = c.getString(5) ?: ""; val note = c.getString(6) ?: ""
            NoteRow(NoteRef(kind, c.getLong(0)), c.getLong(1), c.getInt(2), c.getInt(3), c.getInt(4),
                cut(body, BODY_CHARS, full), !full && over(body, BODY_CHARS), cut(note, NOTE_CHARS, full), !full && over(note, NOTE_CHARS),
                "", 0, c.getInt(7), 0, "", c.getString(8) ?: "", c.getFloat(9), c.getString(10) ?: "", c.getLong(11))
        }
        NoteKind.BOOKMARK -> {
            val body = c.getString(4) ?: ""; val note = c.getString(5) ?: ""
            val off = c.getInt(3)
            NoteRow(NoteRef(kind, c.getLong(0)), c.getLong(1), c.getInt(2), off, off,
                cut(body, BODY_CHARS, full), !full && over(body, BODY_CHARS), cut(note, NOTE_CHARS, full), !full && over(note, NOTE_CHARS),
                "", 0, 0, 0, "", c.getString(6) ?: "", c.getFloat(7), c.getString(8) ?: "", c.getLong(9))
        }
        NoteKind.REVIEW -> {
            val id = c.getLong(0); val body = c.getString(1) ?: ""
            val at = c.getLong(2)
            NoteRow(NoteRef(kind, id), id, -1, -1, -1,
                cut(body, BODY_CHARS, full), !full && over(body, BODY_CHARS), "", false,
                "", 0, 0, 0, "", "", c.getFloat(4), "", if (at > 0) at else c.getLong(3))
        }
        NoteKind.LOOKUP -> {
            val body = c.getString(6) ?: ""; val note = c.getString(7) ?: ""
            NoteRow(NoteRef(kind, c.getLong(0)), c.getLong(1), c.getInt(3), c.getInt(4), c.getInt(5),
                cut(body, BODY_CHARS, full), !full && over(body, BODY_CHARS), cut(note, NOTE_CHARS, full), !full && over(note, NOTE_CHARS),
                c.getString(2) ?: "", maxOf(1, c.getInt(14)), 0, c.getInt(11), c.getString(12) ?: "",
                c.getString(8) ?: "", c.getFloat(9), c.getString(10) ?: "", c.getLong(13))
        }
    }

    /** Whether [s] (a `substr(…, 1, max + 1)`) holds more than [max] characters (code points, as SQLite counts). */
    internal fun over(s: String, max: Int): Boolean = s.length > max && s.codePointCount(0, s.length) > max

    /** [s] cut to [max] code points (never splitting a surrogate pair); as is when [full]. */
    internal fun cut(s: String, max: Int, full: Boolean = false): String =
        if (full || !over(s, max)) s else s.substring(0, s.offsetByCodePoints(0, max))

    // ---- books ----

    /** Title map entries for [ids] (book info with count 0); ids no book has are left out. */
    private fun bookInfo(ids: Collection<Long>): Map<Long, NoteBook> {
        val g = generation()
        val out = HashMap<Long, NoteBook>(ids.size * 2)
        val missing = ArrayList<Long>()
        for (id in ids) {
            val b = infoCache.get(g, id)
            if (b != null) out[id] = b else missing += id
        }
        var i = 0
        while (i < missing.size) {
            val s = NotesSql.bookInfo(missing.subList(i, minOf(missing.size, i + NotesSql.COUNT_CHUNK)))
            Library.db().queryList(s.sql, s.args) { c ->
                val trashed = c.getInt(4) != 0
                val b = NoteBook(c.getLong(0), c.getString(1) ?: "", c.getString(2) ?: "", c.getString(3) ?: "",
                    trashed, trashed && c.getLong(5) > 0, c.getLong(6), 0)
                infoCache.put(g, b.id, b)
                out[b.id] = b
            }
            i += NotesSql.COUNT_CHUNK
        }
        return out
    }

    /** BOOK_RECENT: last read first, then natural title; every other order: natural title ("2권" before "10권"). */
    internal fun sortBooks(list: List<NoteBook>, order: NotesOrder): List<NoteBook> = list.sortedWith { a, b ->
        var c = if (order == NotesOrder.BOOK_RECENT) b.lastReadAt.compareTo(a.lastReadAt) else 0
        if (c == 0) c = NaturalOrder.compare(a.title, b.title)
        if (c == 0) c = a.id.compareTo(b.id)
        c
    }

    // ---- export ----

    private class ExportRow(val kind: NoteKind, val id: Long, val book: Long, val section: Int, val start: Int, val memo: Boolean, val style: Int)

    private class ExportPlan(
        val summary: NotesExport.Summary, val scopeBooks: List<NoteBook>,
        private val books: List<NoteBook>, private val rows: Map<Long, List<ExportRow>>,
    ) {
        /** Book by book: full texts of one book at a time, in reading order. */
        fun chunks(): Sequence<Pair<NoteBook, List<NoteRow>>> = sequence {
            for (b in books) {
                val list = rows[b.id] ?: continue
                val full = details(list.map { Key(it.kind.code, it.id, it.book) }, true)
                if (full.isNotEmpty()) yield(b to full)
            }
        }
    }

    /**
     * Export pre-pass: book, place, memo flag and style of every ref (primary-key reads, nothing long), so the header
     * counts and the colour-tag decision are known before the first row is written.
     */
    private fun exportPlan(refs: LongArray?, q: NotesQuery): ExportPlan {
        val all = refs ?: refs(q)
        val ids = Array(5) { ArrayList<Long>() }
        for (v in all) NoteRef.unpack(v)?.let { ids[it.kind.code] += it.id }
        val found = ArrayList<ExportRow>(all.size)
        val db = Library.db()
        for (kind in NoteKind.entries) {
            val list = ids[kind.code]
            var i = 0
            while (i < list.size) {
                val s = NotesSql.exportKeys(kind, list.subList(i, minOf(list.size, i + NotesSql.COUNT_CHUNK)))
                db.queryList(s.sql, s.args) { c ->
                    found += ExportRow(kind, c.getLong(0), c.getLong(1), c.getInt(2), c.getInt(3), c.getInt(4) != 0, c.getInt(5))
                }
                i += NotesSql.COUNT_CHUNK
            }
        }
        val byBook = LinkedHashMap<Long, MutableList<ExportRow>>()
        for (r in found) byBook.getOrPut(r.book) { ArrayList() } += r
        val info = bookInfo(byBook.keys + listOfNotNull(q.bookId))
        val books = sortBooks(byBook.map { (id, list) -> (info[id] ?: NotesExport.deletedBook(id, 0)).copy(count = list.size) },
            if (q.order == NotesOrder.BOOK_RECENT) NotesOrder.BOOK_RECENT else NotesOrder.BOOK_TITLE)
        val order = Comparator<ExportRow> { a, b ->
            var c = NotesExport.kindRank(a.kind).compareTo(NotesExport.kindRank(b.kind))
            if (c == 0) c = a.section.compareTo(b.section)
            if (c == 0) c = a.start.compareTo(b.start)
            if (c == 0) c = a.id.compareTo(b.id)
            c
        }
        val sorted = HashMap<Long, List<ExportRow>>(byBook.size * 2)
        for ((id, list) in byBook) sorted[id] = list.sortedWith(order)
        var quotes = 0; var memos = 0; var marks = 0; var reviews = 0; var words = 0; var styles = 0
        for (r in found) when (r.kind) {
            NoteKind.QUOTE -> { quotes++; if (r.memo) memos++; styles = styles or (1 shl com.ggumtak.readeraplus.render.QuoteStyles.of(r.style)) }
            NoteKind.BOOKMARK -> { marks++; if (r.memo) memos++ }
            NoteKind.REVIEW -> reviews++
            NoteKind.LOOKUP -> words++
        }
        val summary = NotesExport.Summary(books.size, NotesCounts(quotes, memos, marks, reviews, words), Integer.bitCount(styles) >= 2)
        val scopeBooks = q.bookId?.let { id -> listOfNotNull(info[id]) } ?: emptyList()
        return ExportPlan(summary, scopeBooks, books, sorted)
    }

    // ---- misc ----

    private const val ID_MASK = 0x00FF_FFFF_FFFF_FFFFL

    private fun Array<String>.ifEmptyNull(): Array<String>? = if (isEmpty()) null else this

    /** `RANotes` timing line (debug builds); a warning when over [budgetMs]. */
    private fun perf(label: String, t0: Long, budgetMs: Long) {
        if (!BuildConfig.DEBUG) return
        val ms = (System.nanoTime() - t0) / 1_000_000
        try {
            if (ms > budgetMs) Log.w(TAG, "$label $ms ms (budget $budgetMs)") else Log.d(TAG, "$label $ms ms")
        } catch (_: Throwable) {
        }
    }

    private class LongArrayList(cap: Int) {
        private var a = LongArray(cap); private var n = 0
        fun add(v: Long) { if (n == a.size) a = a.copyOf(a.size * 2 + 8); a[n++] = v }
        fun toArray(): LongArray = a.copyOf(n)
    }
}

class NotesPage(val index: Int, val rows: List<NoteRow>, val before: NoteRow?, val firstOfBook: BooleanArray)

/** The 공유 text ([Notes.share]): [count] notes; [cut] = the rest was left out (toast "노트가 많아 앞부분만 공유합니다"). */
class NotesShare(val text: String, val count: Int, val cut: Boolean)

/** Prefix sums of per-book row counts for book-order paging. Pure. */
internal object BookSpans {
    /** `prefix[i]` = rows before book i; `prefix[n]` = all rows. */
    fun prefix(counts: IntArray): IntArray {
        val p = IntArray(counts.size + 1)
        for (i in counts.indices) p[i + 1] = p[i] + maxOf(0, counts[i])
        return p
    }

    /** Book index and inner row of global [row], packed `book shl 32 | inner`; −1 when out of range. Empty books are skipped. */
    fun locate(prefix: IntArray, row: Int): Long {
        val n = prefix.size - 1
        if (n <= 0 || row < 0 || row >= prefix[n]) return -1L
        var lo = 0
        var hi = n - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (prefix[mid] <= row) lo = mid else hi = mid - 1
        }
        return (lo.toLong() shl 32) or (row - prefix[lo]).toLong()
    }
}

/** Page maths of the hub's windowed list (pure). */
internal object NotesPaging {
    fun pageOf(row: Int): Int = if (row <= 0) 0 else row / Notes.PAGE_ROWS
    fun pageCount(total: Int): Int = if (total <= 0) 0 else (total + Notes.PAGE_ROWS - 1) / Notes.PAGE_ROWS
    fun firstRow(page: Int): Int = page * Notes.PAGE_ROWS
    /** Rows of [page] out of [total]. */
    fun rowsOf(page: Int, total: Int): Int = (total - firstRow(page)).coerceIn(0, Notes.PAGE_ROWS)

    /**
     * Pages to load for visible rows [first]..[last] of [total]: the visible pages first, then the next page, then the
     * previous one (prefetch), each once and inside 0 until pageCount.
     */
    fun prefetch(first: Int, last: Int, total: Int): IntArray {
        val count = pageCount(total)
        if (count == 0) return IntArray(0)
        val a = pageOf(first.coerceIn(0, total - 1))
        val z = pageOf(last.coerceIn(0, total - 1)).coerceAtLeast(a)
        val out = ArrayList<Int>(z - a + 3)
        for (p in a..z) out += p
        if (z + 1 < count) out += z + 1
        if (a - 1 >= 0) out += a - 1
        return out.toIntArray()
    }
}
