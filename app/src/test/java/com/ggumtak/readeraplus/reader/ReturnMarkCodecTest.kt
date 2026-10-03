package com.ggumtak.readeraplus.reader

import com.ggumtak.readeraplus.format.DocPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ReturnMarkCodecTest {

    @Test
    fun roundTrip() {
        val sig = "t1|v3|0,true,false,true,enc=UTF-8,re=5:a|b|c"
        val text = ReturnMarkCodec.encode(DocPosition(12, 3456), 0.25f, sig)
        assertEquals("m1|12|3456|0.25|$sig", text)
        val m = ReturnMarkCodec.decode(text)!!
        assertEquals(DocPosition(12, 3456), m.pos)
        assertEquals(0.25f, m.fraction, 0f)
        assertEquals(sig, m.sig)
    }

    @Test
    fun emptySignatureIsEpub() {
        val text = ReturnMarkCodec.encode(DocPosition(3, 0), 0.5f, null)
        assertEquals("m1|3|0|0.5|", text)
        val m = ReturnMarkCodec.decode(text)!!
        assertNull(m.sig)
        assertEquals(DocPosition(3, 0), m.pos)
    }

    @Test
    fun malformedTextIsRejected() {
        for (bad in listOf(
            null, "", "m1", "m2|1|2|0.5|", "x|1|2|0.5|", "m1|1|2|0.5", "m1|1|2", "m1|a|2|0.5|", "m1|1|b|0.5|",
            "m1|1|2|c|", "m1|1|2|NaN|", "m1|-1|2|0.5|", "m1|1|-2|0.5|", "m1||2|0.5|", "M1|1|2|0.5|",
        )) {
            assertNull(bad, ReturnMarkCodec.decode(bad))
        }
    }

    @Test
    fun fractionIsClamped() {
        assertEquals(1f, ReturnMarkCodec.decode("m1|1|2|1.7|")!!.fraction, 0f)
        assertEquals(0f, ReturnMarkCodec.decode("m1|1|2|-0.3|")!!.fraction, 0f)
        assertEquals(1f, ReturnMarkCodec.decode("m1|1|2|Infinity|")!!.fraction, 0f)
        // Encoding never writes a value decode would reject.
        assertNotNull(ReturnMarkCodec.decode(ReturnMarkCodec.encode(DocPosition(0, 0), Float.NaN, null)))
        assertEquals(1f, ReturnMarkCodec.decode(ReturnMarkCodec.encode(DocPosition(0, 0), 3f, null))!!.fraction, 0f)
    }
}
