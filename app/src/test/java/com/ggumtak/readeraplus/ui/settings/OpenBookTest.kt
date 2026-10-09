package com.ggumtak.readeraplus.ui.settings

import com.ggumtak.readeraplus.data.TxtOverride
import com.ggumtak.readeraplus.format.BookFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenBookTest {
    private fun info(id: Long) = OpenBook.Info(id, "책 $id", BookFormat.TXT, "", null)

    @Test
    fun editsAreTakenOnceByTheirOwnBook() {
        OpenBook.open(info(7))
        OpenBook.overrideEdited(7, TxtOverride(blankLines = 2))
        OpenBook.encodingEdited(7, "MS949")
        assertEquals(TxtOverride(blankLines = 2), OpenBook.info?.override)
        assertEquals("MS949", OpenBook.info?.encoding)
        assertNull(OpenBook.take(8))
        val e = OpenBook.take(7)
        assertNotNull(e)
        assertTrue(e!!.overrideChanged)
        assertEquals(TxtOverride(blankLines = 2), e.override)
        assertEquals("MS949", e.encoding)
        assertNull(OpenBook.take(7))
    }

    @Test
    fun clearingTheOverrideIsAnEditAndAnEmptyOneIsNone() {
        OpenBook.open(info(3))
        OpenBook.overrideEdited(3, TxtOverride())
        val e = OpenBook.take(3)!!
        assertTrue(e.overrideChanged)
        assertNull(e.override)
        assertNull(e.encoding)
    }

    @Test
    fun settingsFromElsewhereKeepPendingEditsAnotherBookDropsThem() {
        OpenBook.open(info(5))
        OpenBook.overrideEdited(5, TxtOverride(stripIndent = true))
        // 설정 opened from the library: no book, but the reader of book 5 still gets its edits.
        OpenBook.open(null)
        assertNull(OpenBook.info)
        assertNotNull(OpenBook.take(5))
        // A reader of another book opening 설정 drops what nobody took.
        OpenBook.open(info(5))
        OpenBook.overrideEdited(5, TxtOverride(stripIndent = true))
        OpenBook.open(info(6))
        assertNull(OpenBook.take(5))
        assertFalse(OpenBook.take(6) != null)
    }
}
