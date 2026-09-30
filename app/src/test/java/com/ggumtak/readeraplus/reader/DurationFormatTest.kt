package com.ggumtak.readeraplus.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class DurationFormatTest {
    @Test
    fun wording() {
        assertEquals("1분 미만", ReaderFormat.duration(0))
        assertEquals("1분 미만", ReaderFormat.duration(-5))
        assertEquals("1분", ReaderFormat.duration(1))
        assertEquals("59분", ReaderFormat.duration(59))
        assertEquals("1시간", ReaderFormat.duration(60))
        assertEquals("1시간 1분", ReaderFormat.duration(61))
        assertEquals("4시간 12분", ReaderFormat.duration(4 * 60 + 12))
        assertEquals("9시간 59분", ReaderFormat.duration(9 * 60 + 59))
        // From 10 hours on the minutes are noise: "책 104시간".
        assertEquals("10시간", ReaderFormat.duration(10 * 60 + 30))
        assertEquals("104시간", ReaderFormat.duration(104 * 60 + 7))
    }

    @Test
    fun seconds() {
        assertEquals("1분 미만", ReaderFormat.durationOfSeconds(59))
        assertEquals("1분", ReaderFormat.durationOfSeconds(119))
        assertEquals("1시간 20분", ReaderFormat.durationOfSeconds(80 * 60L))
        assertEquals("1분 미만", ReaderFormat.durationOfSeconds(-1))
    }
}
