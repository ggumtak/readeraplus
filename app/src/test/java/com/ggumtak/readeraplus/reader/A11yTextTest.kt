package com.ggumtak.readeraplus.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class A11yTextTest {
    @Test fun rangeIsCutFromTheSectionText() {
        assertEquals("둘째 쪽", A11yText.page("첫째 쪽\n둘째 쪽\n셋째 쪽", 5, 9))
    }

    @Test fun rangeIsClampedAndEmptyReadsEmpty() {
        assertEquals("abc", A11yText.page("abc", -5, 99))
        assertEquals("", A11yText.page("abc", 2, 2))
        assertEquals("", A11yText.page("abc", 3, 1))
        assertEquals("", A11yText.page("", 0, 10))
    }

    @Test fun layoutMarksAreDropped() {
        assertEquals("하이픈\n다음", A11yText.page("하­이픈 다­음", 0, 8))
        assertEquals("앞 뒤", A11yText.page("앞 ￼뒤", 0, 4))
    }

    @Test fun pictureOnlyPageReadsPicture() {
        assertEquals(A11yText.PICTURE, A11yText.page("￼\n", 0, 2))
        assertEquals("", A11yText.page("  \n", 0, 3))
    }

    @Test fun longRangeIsCut() {
        val text = "가".repeat(A11yText.MAX_CHARS + 500)
        assertEquals(A11yText.MAX_CHARS, A11yText.page(text, 0, text.length).length)
    }
}
