package com.ggumtak.readeraplus.reader

import android.content.pm.ActivityInfo
import android.view.Surface
import org.junit.Assert.assertEquals
import org.junit.Test

class RotationLockTest {
    @Test
    fun constantsAreThePlatformValues() {
        assertEquals(Surface.ROTATION_0, RotationLock.ROTATION_0)
        assertEquals(Surface.ROTATION_90, RotationLock.ROTATION_90)
        assertEquals(Surface.ROTATION_180, RotationLock.ROTATION_180)
        assertEquals(Surface.ROTATION_270, RotationLock.ROTATION_270)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, RotationLock.LANDSCAPE)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, RotationLock.PORTRAIT)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE, RotationLock.REVERSE_LANDSCAPE)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT, RotationLock.REVERSE_PORTRAIT)
    }

    @Test
    fun aPhoneLocksTheWayItIsHeld() {
        // Natural portrait: tall at 0 / 180, wide at 90 / 270.
        assertEquals(RotationLock.PORTRAIT, RotationLock.forDisplay(Surface.ROTATION_0, landscapeNow = false))
        assertEquals(RotationLock.LANDSCAPE, RotationLock.forDisplay(Surface.ROTATION_90, landscapeNow = true))
        assertEquals(RotationLock.REVERSE_PORTRAIT, RotationLock.forDisplay(Surface.ROTATION_180, landscapeNow = false))
        // The regression: held the other way round, the lock must not flip the page (it was plain LANDSCAPE).
        assertEquals(RotationLock.REVERSE_LANDSCAPE, RotationLock.forDisplay(Surface.ROTATION_270, landscapeNow = true))
    }

    @Test
    fun aNaturallyWideDeviceLocksTheWayItIsHeld() {
        assertEquals(RotationLock.LANDSCAPE, RotationLock.forDisplay(Surface.ROTATION_0, landscapeNow = true))
        assertEquals(RotationLock.PORTRAIT, RotationLock.forDisplay(Surface.ROTATION_90, landscapeNow = false))
        assertEquals(RotationLock.REVERSE_LANDSCAPE, RotationLock.forDisplay(Surface.ROTATION_180, landscapeNow = true))
        assertEquals(RotationLock.REVERSE_PORTRAIT, RotationLock.forDisplay(Surface.ROTATION_270, landscapeNow = false))
    }

    @Test
    fun anUnknownRotationIsUpright() {
        assertEquals(RotationLock.PORTRAIT, RotationLock.forDisplay(-1, landscapeNow = false))
        assertEquals(RotationLock.LANDSCAPE, RotationLock.forDisplay(-1, landscapeNow = true))
    }
}
