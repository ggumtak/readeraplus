package com.ggumtak.readeraplus.reader

import com.ggumtak.readeraplus.render.StatusSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Each allocation-free formatter must print exactly what its ReaderFormat twin prints. */
class StatusTextTest {
    private val buf = CharArray(StatusSlot.CAPACITY)

    private inline fun text(at: Int = 0, f: (CharArray, Int) -> Int): String {
        buf.fill('#')
        val n = f(buf, at)
        assertTrue("length $n", n <= buf.size)
        return String(buf, at, n - at)
    }

    @Test
    fun pageMatchesPageLabel() {
        for (page in 1..99_999) {
            for (total in intArrayOf(0, page - 1, page, page + 1, 3259, 99_999)) {
                assertEquals(ReaderFormat.pageLabel(page, total), text { b, at -> StatusText.page(b, at, page, total) })
            }
        }
    }

    @Test
    fun percentMatches() {
        for (p in 0..100) assertEquals("$p%", text { b, at -> StatusText.percent(b, at, p) })
    }

    @Test
    fun clockMatchesForEveryMinute() {
        for (m in 0 until 1440) {
            for (is24 in booleanArrayOf(true, false)) {
                assertEquals(ReaderFormat.clock(m / 60, m % 60, is24), text { b, at -> StatusText.clock(b, at, m, is24) })
            }
        }
        assertEquals("14:05", text { b, at -> StatusText.clock(b, at, 14 * 60 + 5, true) })
        assertEquals("2:05", text { b, at -> StatusText.clock(b, at, 14 * 60 + 5, false) })
        assertEquals("09:00", text { b, at -> StatusText.clock(b, at, 9 * 60, true) })
        assertEquals("12:00", text { b, at -> StatusText.clock(b, at, 0, false) })
    }

    @Test
    fun chapterLeftMatches() {
        for (p in -1..999) assertEquals(ReaderFormat.chapterLeft(p), text { b, at -> StatusText.chapterLeft(b, at, p) })
        assertEquals("챕터 5쪽 남음", text { b, at -> StatusText.chapterLeft(b, at, 5) })
        assertEquals("챕터 마지막 쪽", text { b, at -> StatusText.chapterLeft(b, at, 0) })
    }

    @Test
    fun episodeMatchesInBothModes() {
        for (numbered in booleanArrayOf(true, false)) {
            for (n in -1..130) {
                for (max in intArrayOf(-1, 0, 50, 540)) {
                    for (idx in intArrayOf(-1, 0, 86, 700)) {
                        for (count in intArrayOf(0, 612)) {
                            assertEquals(
                                ReaderFormat.episodeLabel(numbered, n, max, idx, count),
                                text { b, at -> StatusText.episode(b, at, numbered, n, max, idx, count) },
                            )
                        }
                    }
                }
            }
        }
        assertEquals("123/540화", text { b, at -> StatusText.episode(b, at, true, 123, 540, 5, 612) })
        assertEquals("87/612", text { b, at -> StatusText.episode(b, at, false, 123, 540, 86, 612) })
    }

    @Test
    fun timeLeftMatchesForEveryDuration() {
        for (minutes in -1..6000) {
            for (book in booleanArrayOf(true, false)) {
                assertEquals(ReaderFormat.timeLeft(book, minutes), text { b, at -> StatusText.timeLeft(b, at, book, minutes) })
            }
        }
        assertEquals("이 화 3분", text { b, at -> StatusText.timeLeft(b, at, false, 3) })
        assertEquals("책 7시간 20분", text { b, at -> StatusText.timeLeft(b, at, true, 440) })
        assertEquals("이 화 1분 미만", text { b, at -> StatusText.timeLeft(b, at, false, 0) })
    }

    @Test
    fun intPrintsEveryValue() {
        for (v in intArrayOf(0, 7, 9, 10, 99, 100, 12345, 999_999_999, 1_000_000_000, Int.MAX_VALUE, -1, -10, -12345, Int.MIN_VALUE)) {
            assertEquals(v.toString(), text { b, at -> StatusText.int(b, at, v) })
        }
    }

    @Test
    fun writesAtAnOffset() {
        val n = StatusText.page(buf, 5, 12, 3259)
        assertEquals(5 + "12 / 3259".length, n)
        assertEquals("12 / 3259", String(buf, 5, n - 5))
    }

    @Test
    fun theSlotBufferIsNeverExceeded() {
        // The longest values every formatter can print fit the 48-char slot buffer.
        val worst = listOf(
            text { b, at -> StatusText.page(b, at, Int.MAX_VALUE, Int.MAX_VALUE) },
            text { b, at -> StatusText.episode(b, at, true, Int.MAX_VALUE, Int.MAX_VALUE, 0, 0) },
            text { b, at -> StatusText.timeLeft(b, at, true, Int.MAX_VALUE) },
            text { b, at -> StatusText.chapterLeft(b, at, Int.MAX_VALUE) },
            text { b, at -> StatusText.clock(b, at, 1439, true) },
        )
        for (w in worst) assertTrue(w, w.length <= StatusSlot.CAPACITY && !w.contains('#'))
        // A buffer too small truncates instead of throwing.
        val tiny = CharArray(4)
        assertEquals(4, StatusText.page(tiny, 0, 12345, 99999))
        assertEquals("1234", String(tiny))
        assertEquals(4, StatusText.timeLeft(tiny, 2, true, 440))
    }
}
