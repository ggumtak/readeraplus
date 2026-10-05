package com.ggumtak.readeraplus.reader

import com.ggumtak.readeraplus.data.BookPrefs
import com.ggumtak.readeraplus.format.DocPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** U §3.3: the return history in `book_prefs.return_mark`, and the old single pin read as a back list. */
class ReturnHistoryCodecTest {
    private val fractions = mapOf(
        DocPosition(0, 1000) to 0.25f,
        DocPosition(4, 50) to 0.5f,
        DocPosition(1, 200) to 0.125f,
    )
    private val fraction: (DocPosition) -> Float = { fractions[it] ?: 0f }

    @Test
    fun roundTrip() {
        val sig = "t1|v3|0,true,false,true,enc=UTF-8,re=5:a|b|c"
        val text = ReturnHistoryCodec.encode(
            listOf(DocPosition(0, 1000), DocPosition(4, 50)), listOf(DocPosition(1, 200)), fraction, sig,
        )
        assertEquals("h1|0,1000,250000;4,50,500000|1,200,125000|$sig", text)
        val h = ReturnHistoryCodec.decode(text)!!
        assertEquals(listOf(DocPosition(0, 1000), DocPosition(4, 50)), h.back.map { it.pos })
        assertEquals(listOf(0.25f, 0.5f), h.back.map { it.fraction })
        assertEquals(listOf(DocPosition(1, 200)), h.forward.map { it.pos })
        assertEquals(0.125f, h.forward.single().fraction, 0f)
        assertEquals(sig, h.sig)
        assertFalse(h.pinTop)
    }

    @Test
    fun thePinsMarkRidesOnBacksTop() {
        val back = listOf(DocPosition(0, 1000), DocPosition(4, 50))
        val text = ReturnHistoryCodec.encode(back, listOf(DocPosition(1, 200)), fraction, null, pinTop = true)
        assertEquals("h1|0,1000,250000;4,50,500000,p|1,200,125000|", text)
        val h = ReturnHistoryCodec.decode(text)!!
        assertTrue(h.pinTop)
        assertEquals(back, h.back.map { it.pos })
        assertEquals(0.5f, h.back.last().fraction, 0f)
        // Only back's top counts; a mark elsewhere is read and ignored.
        assertFalse(ReturnHistoryCodec.decode("h1|0,1000,250000,p;4,50,500000|1,200,125000,p|")!!.pinTop)
        assertNull(ReturnHistoryCodec.encode(emptyList(), emptyList(), fraction, null, pinTop = true))
        val ahead = listOf(DocPosition(1, 200))
        assertEquals("h1||1,200,125000|", ReturnHistoryCodec.encode(emptyList(), ahead, fraction, null, pinTop = true))
        // Any other 4th field is a malformed place.
        assertNull(ReturnHistoryCodec.decode("h1|1,2,3,q||"))
    }

    @Test
    fun oneSideAndAnEmptySignature() {
        val text = ReturnHistoryCodec.encode(emptyList(), listOf(DocPosition(3, 0)), { 0.5f }, null)
        assertEquals("h1||3,0,500000|", text)
        val h = ReturnHistoryCodec.decode(text)!!
        assertTrue(h.back.isEmpty())
        assertEquals(DocPosition(3, 0), h.forward.single().pos)
        assertNull(h.sig)                                     // EPUB
    }

    @Test
    fun anEmptyHistoryClearsTheColumn() {
        assertNull(ReturnHistoryCodec.encode(emptyList(), emptyList(), fraction, "sig"))
        assertNull(ReturnHistoryCodec.decode(null))
        assertNull(ReturnHistoryCodec.decode("h1|||"))
    }

    @Test
    fun anOldPinBecomesTheBackList() {
        val sig = "t1|v3|0,true,false,true,enc=UTF-8,re=5:a|b|c"
        val h = ReturnHistoryCodec.decode("m1|12|3456|0.25|$sig")!!
        assertEquals(listOf(DocPosition(12, 3456)), h.back.map { it.pos })
        assertEquals(0.25f, h.back.single().fraction, 0f)
        assertTrue(h.forward.isEmpty())
        assertEquals(sig, h.sig)
        assertTrue(h.pinTop)                                  // it was the user's pin
        assertNull(ReturnHistoryCodec.decode("m1|3|0|0.5|")!!.sig)
        // Clamped as before.
        assertEquals(1f, ReturnHistoryCodec.decode("m1|1|2|1.7|")!!.back.single().fraction, 0f)
        assertEquals(0f, ReturnHistoryCodec.decode("m1|1|2|-0.3|")!!.back.single().fraction, 0f)
        assertEquals(1f, ReturnHistoryCodec.decode("m1|1|2|Infinity|")!!.back.single().fraction, 0f)
    }

    @Test
    fun malformedTextIsRejected() {
        for (bad in listOf(
            "", "h1", "h1|", "h1||", "h2|1,2,3||", "H1|1,2,3||", "x|1,2,3||", "h1|a,b,c||", "h1|1,2||", "h1|-1,2,3||",
            "h1|1,-2,3||", "h1|1,2,3,4||",
            "m1", "m2|1|2|0.5|", "m1|1|2|0.5", "m1|1|2", "m1|a|2|0.5|", "m1|1|b|0.5|", "m1|1|2|c|", "m1|1|2|NaN|",
            "m1|-1|2|0.5|", "m1|1|-2|0.5|", "m1||2|0.5|", "M1|1|2|0.5|",
        )) {
            assertNull(bad, ReturnHistoryCodec.decode(bad))
        }
    }

    @Test
    fun aMalformedPlaceIsSkipped() {
        val h = ReturnHistoryCodec.decode("h1|1,2,3;x;4,5,6;7,-8,9|;;10,11,12|")!!
        assertEquals(listOf(DocPosition(1, 2), DocPosition(4, 5)), h.back.map { it.pos })
        assertEquals(listOf(DocPosition(10, 11)), h.forward.map { it.pos })
    }

    @Test
    fun fractionsAreClamped() {
        val h = ReturnHistoryCodec.decode("h1|1,2,2000000;3,4,-5||")!!
        assertEquals(listOf(1f, 0f), h.back.map { it.fraction })
        // Encoding never writes a value decode would reject.
        val start = listOf(DocPosition.START)
        assertEquals("h1|0,0,0||", ReturnHistoryCodec.encode(start, emptyList(), { Float.NaN }, null))
        assertEquals("h1|0,0,1000000||", ReturnHistoryCodec.encode(start, emptyList(), { 3f }, null))
        val negative = ReturnHistoryCodec.encode(listOf(DocPosition(0, -5)), emptyList(), { 0f }, null)
        assertNotNull(ReturnHistoryCodec.decode(negative))
    }

    @Test
    fun aFullHistoryFitsTheColumn() {
        // Both lists full of the longest places: well under the column's limit, nothing left out.
        val big = (0 until ReturnHistory.MAX).map { DocPosition(9999, 9_999_999 - it) }
        val text = ReturnHistoryCodec.encode(big, big, { 0.123456f }, "0123456789abcdef", pinTop = true)!!
        assertTrue(text.length <= BookPrefs.MAX_RETURN_MARK)
        val h = ReturnHistoryCodec.decode(text)!!
        assertTrue(h.pinTop)
        assertEquals(big, h.back.map { it.pos })
        assertEquals(big, h.forward.map { it.pos })
    }

    @Test
    fun anOverlongHistoryLeavesTheOldestPlacesOut() {
        val sig = "s".repeat(600)
        val back = (0 until ReturnHistory.MAX).map { DocPosition(9999, 9_999_000 + it) }
        val forward = (0 until ReturnHistory.MAX).map { DocPosition(8888, 8_888_000 + it) }
        val text = ReturnHistoryCodec.encode(back, forward, { 0.5f }, sig)!!
        assertTrue(text.length <= ReturnHistoryCodec.MAX_CHARS)
        val h = ReturnHistoryCodec.decode(text)!!
        assertEquals(back.takeLast(h.back.size), h.back.map { it.pos })      // the most recent stay
        assertEquals(forward.takeLast(h.forward.size), h.forward.map { it.pos }) // the nearest stay
        assertTrue(h.back.size + h.forward.size < 2 * ReturnHistory.MAX)
        assertTrue(h.back.isNotEmpty() && h.forward.isNotEmpty())
    }

    @Test
    fun decodeKeepsTheNewestPlacesOfALongList() {
        val list = (0 until 25).joinToString(";") { "0,$it,0" }
        val h = ReturnHistoryCodec.decode("h1|$list|$list|")!!
        assertEquals(ReturnHistory.MAX, h.back.size)
        assertEquals(DocPosition(0, 5), h.back.first().pos)
        assertEquals(DocPosition(0, 24), h.forward.last().pos)
    }
}
