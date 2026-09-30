package com.ggumtak.readeraplus.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Test doubles of the Bigme firmware classes (static members, as on the device). */
internal object FakeModeTable {
    const val EINK_GC16_MODE = 4
    const val EINK_CLEAN_MODE = 196
    const val EINK_NEGATIVE_MODE = -2147483471
    const val EINK_NAMED_MODE = "gc16"
}

internal class FakeInstanceModes {
    @JvmField val EINK_GC16_MODE = 9
}

internal object FakeCleanOn {
    var writes = 0

    @JvmStatic fun isAutoCleanCheckEnable(): Boolean = true

    @JvmStatic fun getCleanFrequency(): Int = 10

    @JvmStatic fun setAutoCleanEnable(on: Boolean) {
        writes++
    }

    @JvmStatic fun setCleanFrequency(n: Int) {
        writes++
    }
}

internal object FakeCleanOffNoFrequency {
    @JvmStatic fun isAutoCleanCheckEnable(): Boolean = false
}

internal object FakeCleanFrequencyThrows {
    @JvmStatic fun isAutoCleanCheckEnable(): Boolean = true

    @JvmStatic fun getCleanFrequency(): Int = throw IllegalStateException("no service")
}

internal object FakeCleanFrequencyOnly {
    @JvmStatic fun getCleanFrequency(): Int = 5
}

internal class FakeCleanNotStatic {
    fun isAutoCleanCheckEnable(): Boolean = true
}

class RefreshCallsTest {

    @Test
    fun voidAndOtherResultsAreSentNotConfirmed() {
        // Bigme forceGlobalRefresh(int) is static void: returning normally proves nothing about the panel.
        assertEquals(RefreshCalls.SENT, RefreshCalls.outcome(Void.TYPE, null))
        assertEquals(RefreshCalls.SENT, RefreshCalls.outcome(Integer.TYPE, 0))
        assertEquals(RefreshCalls.SENT, RefreshCalls.outcome(Integer.TYPE, -1))
        assertEquals(RefreshCalls.SENT, RefreshCalls.outcome(Any::class.java, null))
    }

    @Test
    fun booleanResultsAreAVerdict() {
        assertEquals(RefreshCalls.DONE, RefreshCalls.outcome(java.lang.Boolean.TYPE, true))
        assertEquals(RefreshCalls.FAILED, RefreshCalls.outcome(java.lang.Boolean.TYPE, false))
        assertEquals(RefreshCalls.DONE, RefreshCalls.outcome(java.lang.Boolean::class.java, java.lang.Boolean.TRUE))
        assertEquals(RefreshCalls.FAILED, RefreshCalls.outcome(java.lang.Boolean::class.java, null))
    }

    @Test
    fun flashLengthKeepsTheChoicesAndClampsTheRest() {
        assertEquals(100L, RefreshCalls.flashLength(100))
        assertEquals(200L, RefreshCalls.flashLength(200))
        assertEquals(350L, RefreshCalls.flashLength(350))
        assertEquals(RefreshCalls.MIN_FLASH_MS.toLong(), RefreshCalls.flashLength(0))
        assertEquals(RefreshCalls.MIN_FLASH_MS.toLong(), RefreshCalls.flashLength(-100))
        assertEquals(RefreshCalls.MAX_FLASH_MS.toLong(), RefreshCalls.flashLength(60_000))
    }

    @Test
    fun flashFrameIsWhiteOnlyOverDarkBackgrounds() {
        assertTrue(RefreshCalls.isDark(0xFF000000.toInt()))
        assertTrue(RefreshCalls.isDark(0xFF202020.toInt()))
        assertTrue(RefreshCalls.isDark(0xFF7F7F7F.toInt()))
        assertFalse(RefreshCalls.isDark(0xFF808080.toInt()))
        assertFalse(RefreshCalls.isDark(0xFFFFFFFF.toInt()))
        // Transparent or mostly transparent: not a page background.
        assertFalse(RefreshCalls.isDark(0x00000000))
        assertFalse(RefreshCalls.isDark(0x40000000))
    }

    @Test
    fun modeNumbersComeFromTheFirmwareTableWhenItHasThem() {
        val t = FakeModeTable::class.java
        assertEquals(4, RefreshCalls.modeConstant(t, "EINK_GC16_MODE", Eink.XRZ_GC16))
        assertEquals(196, RefreshCalls.modeConstant(t, "EINK_CLEAN_MODE", Eink.XRZ_CLEAN))
    }

    @Test
    fun modeNumbersFallBackWithoutAUsableConstant() {
        val t = FakeModeTable::class.java
        assertEquals(176, RefreshCalls.modeConstant(null, "EINK_CLEAN_MODE", 176))
        assertEquals(176, RefreshCalls.modeConstant(t, "EINK_MISSING_MODE", 176))
        assertEquals(176, RefreshCalls.modeConstant(t, "EINK_NEGATIVE_MODE", 176))
        assertEquals(176, RefreshCalls.modeConstant(t, "EINK_NAMED_MODE", 176))
        assertEquals(4, RefreshCalls.modeConstant(FakeInstanceModes::class.java, "EINK_GC16_MODE", 4))
    }

    @Test
    fun deviceCleanInfoReadsBothGetters() {
        assertEquals(DeviceCleanInfo(true, 10), Eink.readCleanInfo(FakeCleanOn::class.java))
    }

    @Test
    fun deviceCleanInfoNeverWrites() {
        FakeCleanOn.writes = 0
        Eink.readCleanInfo(FakeCleanOn::class.java)
        assertEquals(0, FakeCleanOn.writes)
    }

    @Test
    fun unknownFrequencyIsMinusOne() {
        assertEquals(DeviceCleanInfo(false, -1), Eink.readCleanInfo(FakeCleanOffNoFrequency::class.java))
        assertEquals(DeviceCleanInfo(true, -1), Eink.readCleanInfo(FakeCleanFrequencyThrows::class.java))
    }

    @Test
    fun withoutTheEnableGetterThereIsNoInfo() {
        assertNull(Eink.readCleanInfo(FakeCleanFrequencyOnly::class.java))
        assertNull(Eink.readCleanInfo(FakeCleanNotStatic::class.java))
        assertNull(Eink.readCleanInfo(String::class.java))
    }

    @Test
    fun noVendorApiOffTheDevice() {
        // No xrz framework on the JVM: nothing to read, nothing callable, and asking twice is the cached answer.
        assertNull(Eink.deviceCleanInfo())
        assertNull(Eink.deviceCleanInfo())
        assertFalse(Eink.hasXrzRefresh())
    }
}
