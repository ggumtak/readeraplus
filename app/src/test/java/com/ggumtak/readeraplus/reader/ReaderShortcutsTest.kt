package com.ggumtak.readeraplus.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderShortcutsTest {
    @Test
    fun labelsFitAndCollapseSpaces() {
        assertEquals("회귀수선전", ReaderShortcuts.label("회귀수선전", 10))
        assertEquals("회귀 수선전", ReaderShortcuts.label("  회귀\n  수선전 ", 10))
        assertEquals("전지적 독자 시…", ReaderShortcuts.label("전지적 독자 시점 1부", 9))
        assertEquals(9, ReaderShortcuts.label("전지적 독자 시점 1부", 9).length)
        assertEquals("책", ReaderShortcuts.label("   ", 10))
    }

    @Test
    fun idsAreStablePerBook() {
        assertEquals("book_42", ReaderShortcuts.id(42))
    }
}
