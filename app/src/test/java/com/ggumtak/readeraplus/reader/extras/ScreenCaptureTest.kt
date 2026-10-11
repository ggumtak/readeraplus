package com.ggumtak.readeraplus.reader.extras

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.TimeZone

class ScreenCaptureTest {

    @Test
    fun fileName_isStampedInTheGivenZone() {
        // 2026-10-10 03:04:05 UTC.
        val millis = 1_791_601_445_000L
        assertEquals("ReaderaPlus_20261010_030405.png", ScreenCapture.fileName(millis, TimeZone.getTimeZone("UTC")))
        assertEquals("ReaderaPlus_20261010_120405.png", ScreenCapture.fileName(millis, TimeZone.getTimeZone("Asia/Seoul")))
    }

    @Test
    fun fileName_epochAndZeroPadding() {
        assertEquals("ReaderaPlus_19700101_000000.png", ScreenCapture.fileName(0L, TimeZone.getTimeZone("UTC")))
        assertEquals("ReaderaPlus_19700101_090000.png", ScreenCapture.fileName(0L, TimeZone.getTimeZone("Asia/Seoul")))
    }
}
