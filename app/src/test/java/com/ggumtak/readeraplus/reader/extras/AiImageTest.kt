package com.ggumtak.readeraplus.reader.extras

import org.junit.Assert.assertEquals
import org.junit.Test

class AiImageTest {
    @Test
    fun sampleSizeKeepsTheLongEdgeAtLeastTheTarget() {
        assertEquals(1, AiImage.sampleSize(1200, 800))
        assertEquals(1, AiImage.sampleSize(3000, 2000))
        assertEquals(2, AiImage.sampleSize(4000, 3000))
        assertEquals(2, AiImage.sampleSize(3000, 4032))
        assertEquals(4, AiImage.sampleSize(8000, 6000))
    }

    @Test
    fun fitScalesDownOnlyAndKeepsTheAspect() {
        assertEquals(1200 to 800, AiImage.fit(1200, 800))
        assertEquals(1568 to 1045, AiImage.fit(3000, 2000))
        assertEquals(1176 to 1568, AiImage.fit(1500, 2000))
        assertEquals(1568 to 1, AiImage.fit(100000, 10))
    }

    @Test
    fun attachScriptCallsThePageHook() {
        assertEquals("window.readerAttach&&readerAttach(\"QUJD\")", AiImage.attachScript("QUJD"))
        assertEquals("window.readerAttach&&readerAttach(null)", AiImage.attachScript(null))
    }
}
