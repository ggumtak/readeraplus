package com.ggumtak.readeraplus.data

/**
 * The search key scan's rows in memory (N §5.3.7), as parallel arrays: kind, row id, book id, sort time, section,
 * offset, has-memo and style (−1 when not a quote). Pure: everything the hub needs while a search is active (counts,
 * style counts, both date orders, book spans, the book list) is derived from one scan, so a tab or colour change costs
 * no query. ≤ 20k rows × 8 values ≈ 0.6 MB.
 *
 * Immutable after [Builder.build]; the derived orders are computed on demand (IO thread) and are not cached here.
 */
internal class NotesKeys private constructor(
    val size: Int,
    private val k: IntArray,
    private val id: LongArray,
    private val b: LongArray,
    private val t: LongArray,
    private val s: IntArray,
    private val o: IntArray,
    private val n: BooleanArray,
    private val st: IntArray,
) {
    fun kind(i: Int): Int = k[i]
    fun id(i: Int): Long = id[i]
    fun book(i: Int): Long = b[i]
    fun time(i: Int): Long = t[i]
    fun section(i: Int): Int = s[i]
    fun offset(i: Int): Int = o[i]
    fun memo(i: Int): Boolean = n[i]
    fun style(i: Int): Int = st[i]

    /** Packed [NoteRef] of row [i]. */
    fun packed(i: Int): Long = (k[i].toLong() shl 56) or (id[i] and 0x00FF_FFFF_FFFF_FFFFL)

    /** Whether row [i] is listed by [q]'s tab (and colour chip on the 인용문 tab: the stored style, as `q.style = ?`). */
    fun matches(i: Int, q: NotesQuery): Boolean = when (q.tab) {
        NotesTab.ALL -> true
        NotesTab.QUOTES -> k[i] == KIND_QUOTE && (q.style == null || st[i] == q.style)
        NotesTab.MEMOS -> (k[i] == KIND_QUOTE || k[i] == KIND_BOOKMARK) && n[i]
        NotesTab.BOOKMARKS -> k[i] == KIND_BOOKMARK
        NotesTab.REVIEWS -> k[i] == KIND_REVIEW
        NotesTab.WORDS -> k[i] == KIND_LOOKUP
    }

    /** Rows of [q]'s tab in the scan's order. */
    fun filter(q: NotesQuery): IntArray {
        var c = 0
        for (i in 0 until size) if (matches(i, q)) c++
        val out = IntArray(c)
        var j = 0
        for (i in 0 until size) if (matches(i, q)) out[j++] = i
        return out
    }

    /**
     * Counts as the SQL counts give them: quotes, memos (quotes and bookmarks with a memo: the counts statement's
     * relabelled kind 5), bookmarks, reviews, words. [style] narrows the quote count like the colour chip.
     */
    fun counts(style: Int? = null): NotesCounts {
        var quotes = 0; var memos = 0; var marks = 0; var reviews = 0; var words = 0
        for (i in 0 until size) {
            when (k[i]) {
                KIND_QUOTE -> { if (style == null || st[i] == style) quotes++; if (n[i]) memos++ }
                KIND_BOOKMARK -> { marks++; if (n[i]) memos++ }
                KIND_REVIEW -> reviews++
                KIND_LOOKUP -> words++
            }
        }
        return NotesCounts(quotes, memos, marks, reviews, words)
    }

    /** Quote count per style 0..[DataLimits.QUOTE_STYLE_MAX] (unknown ids count as 0, as they draw). */
    fun styleCounts(): IntArray {
        val out = IntArray(DataLimits.QUOTE_STYLE_MAX + 1)
        for (i in 0 until size) if (k[i] == KIND_QUOTE) out[styleOf(st[i])]++
        return out
    }

    /**
     * [rows] sorted in a date order: NEWEST = t DESC, k, id DESC; OLDEST = t ASC, k, id ASC (the SQL multi-arm order;
     * within one kind it equals the single-arm `t, id` order).
     */
    fun dateOrder(rows: IntArray, oldest: Boolean): IntArray {
        val boxed = rows.toTypedArray()
        java.util.Arrays.sort(boxed) { x, y ->
            var c = t[x].compareTo(t[y])
            if (c != 0) return@sort if (oldest) c else -c
            c = k[x].compareTo(k[y])
            if (c != 0) return@sort c
            c = id[x].compareTo(id[y])
            if (oldest) c else -c
        }
        return IntArray(boxed.size) { boxed[it] }
    }

    /** Row count per book of [rows]: (book id → count), in first-seen order. */
    fun bookCounts(rows: IntArray): LinkedHashMap<Long, Int> {
        val m = LinkedHashMap<Long, Int>()
        for (i in rows) m[b[i]] = (m[b[i]] ?: 0) + 1
        return m
    }

    /**
     * [rows] in book order: books as [bookOrder] lists them (books not in it go last, by id), inside a book by
     * reading order `s, o, k, id` (the SQL book page order; reviews have s = o = −1, so they come first).
     */
    fun bookOrder(rows: IntArray, bookOrder: List<Long>): IntArray {
        val rank = HashMap<Long, Int>(bookOrder.size * 2)
        for (i in bookOrder.indices) rank[bookOrder[i]] = i
        val boxed = rows.toTypedArray()
        java.util.Arrays.sort(boxed) { x, y ->
            val rx = rank[b[x]] ?: Int.MAX_VALUE
            val ry = rank[b[y]] ?: Int.MAX_VALUE
            var c = rx.compareTo(ry)
            if (c == 0 && rx == Int.MAX_VALUE) c = b[x].compareTo(b[y])
            if (c == 0) c = s[x].compareTo(s[y])
            if (c == 0) c = o[x].compareTo(o[y])
            if (c == 0) c = k[x].compareTo(k[y])
            if (c == 0) c = id[x].compareTo(id[y])
            c
        }
        return IntArray(boxed.size) { boxed[it] }
    }

    /** Appends rows one by one (cursor order), then [build]. */
    class Builder(capacity: Int = 64) {
        private var size = 0
        private var k = IntArray(capacity); private var id = LongArray(capacity); private var b = LongArray(capacity)
        private var t = LongArray(capacity); private var s = IntArray(capacity); private var o = IntArray(capacity)
        private var n = BooleanArray(capacity); private var st = IntArray(capacity)

        fun add(kind: Int, rowId: Long, book: Long, time: Long, section: Int, offset: Int, memo: Boolean, style: Int): Builder {
            if (size == k.size) grow()
            k[size] = kind; id[size] = rowId; b[size] = book; t[size] = time
            s[size] = section; o[size] = offset; n[size] = memo; st[size] = style
            size++
            return this
        }

        private fun grow() {
            val c = maxOf(16, k.size * 2)
            k = k.copyOf(c); id = id.copyOf(c); b = b.copyOf(c); t = t.copyOf(c)
            s = s.copyOf(c); o = o.copyOf(c); n = n.copyOf(c); st = st.copyOf(c)
        }

        fun build(): NotesKeys = NotesKeys(
            size, k.copyOf(size), id.copyOf(size), b.copyOf(size), t.copyOf(size),
            s.copyOf(size), o.copyOf(size), n.copyOf(size), st.copyOf(size),
        )
    }

    companion object {
        const val KIND_QUOTE = 1
        const val KIND_BOOKMARK = 2
        const val KIND_REVIEW = 3
        const val KIND_LOOKUP = 4

        /** Stored style → counted style: 0..QUOTE_STYLE_MAX, anything else counts as 0. */
        fun styleOf(stored: Int): Int = if (stored in 0..DataLimits.QUOTE_STYLE_MAX) stored else 0

        val EMPTY: NotesKeys = Builder(0).build()
    }
}
