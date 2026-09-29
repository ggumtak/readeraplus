package com.ggumtak.readeraplus.reader

import com.ggumtak.readeraplus.format.DocPosition
import com.ggumtak.readeraplus.format.TocEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterIndexTest {
    private val toc = listOf(
        TocEntry("서장", 1, 0),
        TocEntry("1화 바람의 골목", 1, 1),
        TocEntry("2화 오래된 등대", 1, 3),
        TocEntry("2화 중간", 2, 3, 500),
        TocEntry("3화 파도 소리", 1, 5),
        TocEntry("범위 밖", 1, 99),
    )
    private val idx = ChapterIndex(toc, 8)

    @Test
    fun ignoresEntriesOutsideTheBook() {
        assertEquals(5, idx.size)
    }

    @Test
    fun indexAtFindsLastEntryAtOrBefore() {
        assertEquals(0, idx.indexAt(0, 0))
        assertEquals(0, idx.indexAt(0, 400))
        assertEquals(1, idx.indexAt(1, 0))
        assertEquals(1, idx.indexAt(2, 999))
        assertEquals(2, idx.indexAt(3, 499))
        assertEquals(3, idx.indexAt(3, 500))
        assertEquals(4, idx.indexAt(7, 0))
        assertEquals("2화 중간", idx.title(idx.indexAt(4, 10)))
    }

    @Test
    fun indexAtBeforeFirstEntry() {
        val late = ChapterIndex(listOf(TocEntry("1장", 1, 2)), 4)
        assertEquals(-1, late.indexAt(1, 50))
        assertEquals(0, late.indexAt(2, 0))
    }

    @Test
    fun nextAndPrevious() {
        assertEquals(1, idx.nextAfter(0, 0))
        assertEquals(3, idx.nextAfter(3, 0))
        assertEquals(4, idx.nextAfter(3, 500))
        assertEquals(-1, idx.nextAfter(5, 0))
        assertEquals(-1, idx.lastBefore(0, 0))
        assertEquals(2, idx.lastBefore(3, 500))
        assertEquals(3, idx.lastBefore(3, 501))
        assertEquals(DocPosition(3, 500), idx.position(3))
    }

    @Test
    fun unsortedTocStillWorks() {
        val u = ChapterIndex(listOf(TocEntry("나중", 1, 2), TocEntry("처음", 1, 0), TocEntry("가운데", 1, 1)), 3)
        assertEquals("처음", u.title(u.indexAt(0, 5)))
        assertEquals("가운데", u.title(u.indexAt(1, 5)))
        assertEquals("나중", u.title(u.nextAfter(1, 0)))
        assertEquals("가운데", u.title(u.lastBefore(2, 0)))
    }

    @Test
    fun anchorsResolveOncePerSection() {
        val e = ChapterIndex(
            listOf(
                TocEntry("본문", 1, 0, 0, null),
                TocEntry("절 1", 2, 0, 0, "s1"),
                TocEntry("절 2", 2, 0, 0, "s2"),
                TocEntry("다음 장", 1, 1, 0, "c2"),
            ),
            2,
        )
        assertEquals(2, e.indexAt(0, 0)) // unresolved anchors all sit at 0: the last wins
        assertTrue(e.resolveAnchors(0, mapOf("s1" to 120, "s2" to 480)))
        assertEquals(0, e.indexAt(0, 100))
        assertEquals(1, e.indexAt(0, 120))
        assertEquals(2, e.indexAt(0, 900))
        // already resolved: a second map is ignored
        assertFalse(e.resolveAnchors(0, mapOf("s1" to 5)))
        assertEquals(120, e.offset(1))
        // missing anchor keeps the entry offset
        assertFalse(e.resolveAnchors(1, emptyMap()))
        assertEquals(0, e.offset(3))
    }
}
