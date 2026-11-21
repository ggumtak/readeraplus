package com.ggumtak.readeraplus.reader

/**
 * The orientation the rotation button locks to: the way the screen is held right now. Pure, unit-tested.
 *
 * The configuration only says portrait or landscape; locking to plain 가로 while the device is held the other way round
 * (rotation 270 on a phone) turned the page upside down under the finger. The display's rotation says which of the
 * four it is, counted from the device's natural orientation: portrait on a phone or the Comet, landscape on some
 * tablets (known from the rotation and the current shape together).
 */
internal object RotationLock {
    // android.view.Surface.ROTATION_* and android.content.pm.ActivityInfo.SCREEN_ORIENTATION_* (platform constants).
    const val ROTATION_0 = 0
    const val ROTATION_90 = 1
    const val ROTATION_180 = 2
    const val ROTATION_270 = 3
    const val LANDSCAPE = 0
    const val PORTRAIT = 1
    const val REVERSE_LANDSCAPE = 8
    const val REVERSE_PORTRAIT = 9

    /** The `SCREEN_ORIENTATION_*` of a screen at [rotation] that is [landscapeNow] wide; an unknown rotation counts as 0. */
    fun forDisplay(rotation: Int, landscapeNow: Boolean): Int {
        val quarter = rotation == ROTATION_90 || rotation == ROTATION_270
        // Natural portrait: upright (0 / 180) it is tall, on its side (90 / 270) it is wide.
        val naturalPortrait = quarter == landscapeNow
        return if (naturalPortrait) {
            when (rotation) {
                ROTATION_90 -> LANDSCAPE
                ROTATION_180 -> REVERSE_PORTRAIT
                ROTATION_270 -> REVERSE_LANDSCAPE
                else -> PORTRAIT
            }
        } else {
            when (rotation) {
                ROTATION_90 -> PORTRAIT
                ROTATION_180 -> REVERSE_LANDSCAPE
                ROTATION_270 -> REVERSE_PORTRAIT
                else -> LANDSCAPE
            }
        }
    }
}
