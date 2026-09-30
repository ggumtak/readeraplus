package com.ggumtak.readeraplus.data

import android.database.MatrixCursor
import com.ggumtak.readeraplus.format.BookFormat
import com.ggumtak.readeraplus.format.DocMeta
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RowsAndMetaTest {

    private val columns = LibrarySql.BOOK_COLUMNS.split(',').map { it.trim() }.toTypedArray()

    private fun bookRow(vararg overrides: Pair<String, Any?>): Array<Any?> {
        val base = linkedMapOf<String, Any?>(
            "id" to 7L, "path" to "/storage/emulated/0/Books/소설 1권.txt", "file_name" to "소설 1권.txt",
            "title" to "소설 1권", "author" to "김작가", "series" to "소설", "series_index" to 1.5,
            "format" to "TXT", "size" to 123456L, "mtime" to 1_700_000_000_000L, "added_at" to 1_700_000_001_000L,
            "last_read_at" to 1_700_000_002_000L, "pos_section" to 3L, "pos_offset" to 456L, "progress" to 0.25,
            "favorite" to 1L, "to_read" to 0L, "have_read" to 1L, "trashed" to 0L, "review" to "좋음",
            "encoding" to "MS949", "language" to "ko", "reading_seconds" to 3600L,
        )
        for ((k, v) in overrides) base[k] = v
        return columns.map { base[it] }.toTypedArray()
    }

    private fun cursorOf(row: Array<Any?>): MatrixCursor = MatrixCursor(columns).apply {
        addRow(row)
        moveToFirst()
    }

    @Test
    fun bookRowMapsEveryColumn() {
        val b = BookRows.book(cursorOf(bookRow()))
        assertEquals(7L, b.id)
        assertEquals("/storage/emulated/0/Books/소설 1권.txt", b.path)
        assertEquals("소설 1권.txt", b.fileName)
        assertEquals("소설 1권", b.title)
        assertEquals("김작가", b.author)
        assertEquals("소설", b.series)
        assertEquals(1.5f, b.seriesIndex!!, 0f)
        assertEquals(BookFormat.TXT, b.format)
        assertEquals(123456L, b.sizeBytes)
        assertEquals(1_700_000_000_000L, b.modifiedAt)
        assertEquals(1_700_000_001_000L, b.addedAt)
        assertEquals(1_700_000_002_000L, b.lastReadAt)
        assertEquals(3, b.posSection)
        assertEquals(456, b.posOffset)
        assertEquals(0.25f, b.progress, 0f)
        assertEquals(true, b.favorite)
        assertEquals(false, b.toRead)
        assertEquals(true, b.haveRead)
        assertEquals(false, b.trashed)
        assertEquals("좋음", b.review)
        assertEquals("MS949", b.encoding)
        assertEquals("ko", b.language)
        assertEquals(3600L, b.readingSeconds)
        assertEquals("/storage/emulated/0/Books", b.folder)
    }

    @Test
    fun bookRowNullsAndFallbacks() {
        val b = BookRows.book(
            cursorOf(
                bookRow(
                    "series" to null, "series_index" to null, "language" to null, "title" to "",
                    "format" to "BOGUS", "file_name" to "책.epub", "review" to null,
                ),
            ),
        )
        assertNull(b.series)
        assertNull(b.seriesIndex)
        assertNull(b.language)
        assertEquals("책", b.title)
        assertEquals(BookFormat.EPUB, b.format)
        assertEquals("", b.review)
        assertEquals(BookFormat.TXT, BookRows.format(null, "noext"))
    }

    @Test
    fun bookmarkQuoteCollectionRows() {
        val bm = MatrixCursor(arrayOf("id", "book_id", "section", "char_offset", "snippet", "created_at", "note")).apply {
            addRow(arrayOf<Any?>(1L, 2L, 3L, 4L, "첫 문장", 5L, "메모"))
            moveToFirst()
        }
        assertEquals(Bookmark(1, 2, 3, 4, "첫 문장", 5, "메모"), BookRows.bookmark(bm))

        val q = MatrixCursor(arrayOf("id", "book_id", "section", "start_offset", "end_offset", "quote_text", "note", "created_at")).apply {
            addRow(arrayOf<Any?>(1L, 2L, 3L, 10L, 20L, "인용", null, 9L))
            moveToFirst()
        }
        assertEquals(Quote(1, 2, 3, 10, 20, "인용", "", 9), BookRows.quote(q))

        val c4 = MatrixCursor(arrayOf("id", "name", "created_at", "n")).apply {
            addRow(arrayOf<Any?>(5L, "모음", 6L, 3L))
            moveToFirst()
        }
        assertEquals(BookCollection(5, "모음", 6, 3), BookRows.collection(c4))
        val c3 = MatrixCursor(arrayOf("id", "name", "created_at")).apply {
            addRow(arrayOf<Any?>(5L, "모음", 6L))
            moveToFirst()
        }
        assertEquals(BookCollection(5, "모음", 6, 0), BookRows.collection(c3))
    }

    @Test
    fun pageCountCodecRoundTripAndValidation() {
        val a = intArrayOf(1, 2, 300, 0, Int.MAX_VALUE)
        assertArrayEquals(a, PageCountCodec.decode(PageCountCodec.encode(a)))
        assertArrayEquals(IntArray(0), PageCountCodec.decode(PageCountCodec.encode(IntArray(0))))
        assertNull(PageCountCodec.decode(ByteArray(5)))
        assertNull(PageCountCodec.decode(null))
        // R2 (A2): partial counts keep -1 for sections not counted yet; anything lower is corrupt.
        val partial = intArrayOf(3, -1, 0, -1)
        assertArrayEquals(partial, PageCountCodec.decode(PageCountCodec.encode(partial)))
        assertEquals(-1, PageCountCodec.UNKNOWN)
        assertNull(PageCountCodec.decode(PageCountCodec.encode(intArrayOf(3, -2))))
        assertNull(PageCountCodec.decode(PageCountCodec.encode(intArrayOf(Int.MIN_VALUE))))
        // Little-endian layout is part of the stored format.
        assertArrayEquals(byteArrayOf(1, 0, 0, 0, 0, 1, 0, 0), PageCountCodec.encode(intArrayOf(1, 256)))
    }

    @Test
    fun metaInfoCleaning() {
        val m = MetaInfo.from(
            DocMeta(
                title = "  긴\n제목   입니다 ",
                authors = listOf(" 가 ", "", "나", "가"),
                series = "  ",
                seriesIndex = 2f,
                language = " ko ",
            ),
            "file.epub",
        )
        assertEquals("긴 제목 입니다", m.title)
        assertEquals("가, 나", m.author)
        assertNull(m.series)
        assertNull(m.seriesIndex)
        assertEquals("ko", m.language)

        val s = MetaInfo.from(DocMeta(title = "", series = "시리즈", seriesIndex = Float.NaN), "소설 3권.txt")
        assertEquals("소설 3권", s.title)
        assertEquals("시리즈", s.series)
        assertNull(s.seriesIndex)

        val n = MetaInfo.from(null, "archive.tar.txt")
        assertEquals(MetaInfo("archive.tar", "", null, null, null), n)
        assertEquals(".txt", MetaInfo.titleFromFileName(".txt"))
        assertEquals("a", MetaInfo.titleFromFileName("/x/y/a.epub"))
        assertEquals(500, MetaInfo.from(DocMeta("가".repeat(900)), "a.txt").title.length)
        val nfd = java.text.Normalizer.normalize("한글", java.text.Normalizer.Form.NFD)
        assertEquals("한글", MetaInfo.clean(nfd, 10))
    }

    @Test
    fun truncateKeepsSurrogatePairsWhole() {
        val emoji = "ab\uD83D\uDE00c"
        assertEquals("ab", MetaInfo.truncate(emoji, 3))
        assertEquals("ab\uD83D\uDE00", MetaInfo.truncate(emoji, 4))
        assertEquals(emoji, MetaInfo.truncate(emoji, 10))
        assertEquals("", MetaInfo.truncate(emoji, 0))
    }
}
