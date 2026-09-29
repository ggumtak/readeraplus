package com.ggumtak.readeraplus.data

import android.provider.OpenableColumns
import com.ggumtak.readeraplus.format.BookFormat
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BookFileProviderTest {

    @Test
    fun uriShape() {
        val uri = BookFileProvider.uriFor("com.ggumtak.readeraplus", 42)
        assertEquals("content://com.ggumtak.readeraplus.files/book/42", uri.toString())
        assertEquals(42L, BookFileProvider.bookIdOf(uri.pathSegments))
    }

    @Test
    fun bookIdParsing() {
        assertEquals(7L, BookFileProvider.bookIdOf(listOf("book", "7")))
        assertNull(BookFileProvider.bookIdOf(listOf("book")))
        assertNull(BookFileProvider.bookIdOf(listOf("book", "x")))
        assertNull(BookFileProvider.bookIdOf(listOf("book", "0")))
        assertNull(BookFileProvider.bookIdOf(listOf("book", "-3")))
        assertNull(BookFileProvider.bookIdOf(listOf("other", "7")))
        assertNull(BookFileProvider.bookIdOf(listOf("book", "7", "extra")))
        assertNull(BookFileProvider.bookIdOf(emptyList()))
    }

    @Test
    fun mimeTypes() {
        assertEquals("application/epub+zip", BookFileProvider.mimeOf(BookFormat.EPUB))
        assertEquals("text/plain", BookFileProvider.mimeOf(BookFormat.TXT))
        assertNull(BookFileProvider.mimeOf(null))
    }

    @Test
    fun projectionColumns() {
        assertArrayEquals(
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            BookFileProvider.columnsFor(null),
        )
        assertArrayEquals(
            arrayOf(OpenableColumns.SIZE),
            BookFileProvider.columnsFor(arrayOf("_data", OpenableColumns.SIZE, OpenableColumns.SIZE)),
        )
        assertArrayEquals(emptyArray<String>(), BookFileProvider.columnsFor(arrayOf("mime_type")))
    }
}
