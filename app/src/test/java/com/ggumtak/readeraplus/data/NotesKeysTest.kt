package com.ggumtak.readeraplus.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Random

/**
 * The search key scan in memory (N §5.3.7): date orders, counts with memo relabelling, the colour filter and book
 * spans, each checked against a direct reference of what the SQL statements return (ORDER BY t DESC, k, id DESC /
 * t ASC, k, id ASC; `GROUP BY k` with memos as kind 5; `q.style = ?`; reading order `s, o, k, id`).
 */
class NotesKeysTest {

    private class Row(val k: Int, val id: Long, val b: Long, val t: Long, val s: Int, val o: Int, val n: Boolean, val st: Int)

    /** The seeded fixture: 4 kinds over 6 books, colliding times, memos, styles incl. an unknown stored id. */
    private val rows: List<Row> = run {
        val r = Random(7)
        val out = ArrayList<Row>()
        var id = 1L
        repeat(400) {
            val k = 1 + r.nextInt(4)
            val book = 1L + r.nextInt(6)
            val (s, o) = if (k == 3) -1 to -1 else r.nextInt(5) to r.nextInt(1000)
            val memo = (k == 1 || k == 2) && r.nextInt(3) == 0
            val st = if (k == 1) (if (r.nextInt(40) == 0) 20 else r.nextInt(6)) else -1
            out += Row(k, if (k == 3) book else id++, book, 1000L + r.nextInt(50), s, o, memo, st)
        }
        // One review per book at most.
        out.filter { it.k != 3 } + out.filter { it.k == 3 }.distinctBy { it.b }
    }

    private val keys: NotesKeys = NotesKeys.Builder(4).also { b ->
        for (x in rows) b.add(x.k, x.id, x.b, x.t, x.s, x.o, x.n, x.st)
    }.build()

    private fun inTab(x: Row, q: NotesQuery): Boolean = when (q.tab) {
        NotesTab.ALL -> true
        NotesTab.QUOTES -> x.k == 1 && (q.style == null || x.st == q.style)
        NotesTab.MEMOS -> x.k <= 2 && x.n
        NotesTab.BOOKMARKS -> x.k == 2
        NotesTab.REVIEWS -> x.k == 3
        NotesTab.WORDS -> x.k == 4
    }

    private fun refs(order: IntArray): List<Long> = order.map { keys.packed(it) }
    private fun packed(x: Row) = (x.k.toLong() shl 56) or x.id

    @Test
    fun builderKeepsEveryRow() {
        assertEquals(rows.size, keys.size)
        for (i in rows.indices) {
            assertEquals(rows[i].id, keys.id(i)); assertEquals(rows[i].st, keys.style(i)); assertEquals(rows[i].n, keys.memo(i))
        }
        assertEquals(NoteRef(NoteKind.LOOKUP, 9L).packed(), NotesKeys.Builder().add(4, 9L, 1, 1, 0, 0, false, -1).build().packed(0))
    }

    @Test
    fun dateOrdersEqualTheSqlOrder() {
        for (tab in NotesTab.entries) for (style in listOf(null, 2)) {
            val q = NotesQuery(tab = tab, style = if (tab == NotesTab.QUOTES) style else null)
            val sel = rows.filter { inTab(it, q) }
            val newest = sel.sortedWith(compareByDescending<Row> { it.t }.thenBy { it.k }.thenByDescending { it.id }).map(::packed)
            val oldest = sel.sortedWith(compareBy<Row> { it.t }.thenBy { it.k }.thenBy { it.id }).map(::packed)
            val f = keys.filter(q)
            assertEquals("$tab", newest, refs(keys.dateOrder(f, false)))
            assertEquals("$tab", oldest, refs(keys.dateOrder(f, true)))
        }
    }

    @Test
    fun countsRelabelMemos() {
        val c = keys.counts()
        assertEquals(rows.count { it.k == 1 }, c.quotes)
        assertEquals(rows.count { it.k == 2 }, c.bookmarks)
        assertEquals(rows.count { it.k == 3 }, c.reviews)
        assertEquals(rows.count { it.k == 4 }, c.words)
        // GROUP BY k over Q, M, R, L and QM/MM relabelled 5.
        assertEquals(rows.count { it.k <= 2 && it.n }, c.memos)
        assertEquals(rows.size, c.all)
        for (tab in NotesTab.entries) assertEquals("$tab", keys.filter(NotesQuery(tab = tab)).size, c.of(tab))
        assertEquals(rows.count { it.k == 1 && it.st == 3 }, keys.counts(3).quotes)
        assertEquals(c.memos, keys.counts(3).memos)
    }

    @Test
    fun styleFilterAndStyleCounts() {
        val sc = keys.styleCounts()
        assertEquals(DataLimits.QUOTE_STYLE_MAX + 1, sc.size)
        for (st in 1..5) assertEquals(rows.count { it.k == 1 && it.st == st }, sc[st])
        // An unknown stored id (20) counts as 0, as it draws.
        assertEquals(rows.count { it.k == 1 && (it.st == 0 || it.st == 20) }, sc[0])
        val q = NotesQuery(tab = NotesTab.QUOTES, style = 4)
        assertEquals(rows.count { it.k == 1 && it.st == 4 }, keys.filter(q).size)
        assertEquals(rows.count { it.k == 1 }, keys.filter(NotesQuery(tab = NotesTab.QUOTES)).size)
    }

    @Test
    fun bookOrderAndSpans() {
        val q = NotesQuery(tab = NotesTab.ALL, order = NotesOrder.BOOK_TITLE)
        val f = keys.filter(q)
        val counts = keys.bookCounts(f)
        assertEquals(rows.groupingBy { it.b }.eachCount(), counts.toMap())
        val bookOrder = listOf(4L, 2L, 6L, 1L, 5L, 3L)
        val expect = rows.sortedWith(
            compareBy<Row> { bookOrder.indexOf(it.b) }.thenBy { it.s }.thenBy { it.o }.thenBy { it.k }.thenBy { it.id },
        ).map(::packed)
        val order = keys.bookOrder(f, bookOrder)
        assertEquals(expect, refs(order))
        // Reviews (s = o = -1) open their book.
        val prefix = BookSpans.prefix(IntArray(bookOrder.size) { counts[bookOrder[it]] ?: 0 })
        for (bi in bookOrder.indices) {
            if (prefix[bi + 1] == prefix[bi]) continue
            val firstRow = order[prefix[bi]]
            assertEquals(bookOrder[bi], keys.book(firstRow))
            if (rows.any { it.k == 3 && it.b == bookOrder[bi] }) assertEquals(3, keys.kind(firstRow))
            val v = BookSpans.locate(prefix, prefix[bi])
            assertEquals(bi.toLong(), v ushr 32)
        }
        // Books missing from the list go last.
        val partial = keys.bookOrder(f, listOf(3L))
        assertEquals(3L, keys.book(partial[0]))
        assertEquals(rows.size, partial.size)
    }

    @Test
    fun emptyKeys() {
        assertEquals(0, NotesKeys.EMPTY.size)
        assertArrayEquals(IntArray(0), NotesKeys.EMPTY.filter(NotesQuery()))
        assertEquals(NotesCounts(0, 0, 0, 0, 0), NotesKeys.EMPTY.counts())
    }
}
