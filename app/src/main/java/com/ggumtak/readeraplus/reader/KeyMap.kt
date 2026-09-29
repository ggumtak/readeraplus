package com.ggumtak.readeraplus.reader

import android.view.KeyEvent
import com.ggumtak.readeraplus.settings.AppSettings

/** What a hardware key does in the reader. */
enum class KeyAction { NONE, NEXT, PREV, MENU }

/** Pure key → action mapping (unit-tested; only KeyEvent constants are used). */
object KeyMap {
    /**
     * Learned keys ([AppSettings.nextPageKeys] / [AppSettings.prevPageKeys]) win over the built-in map, so a
     * remote or the device's own page key can be re-assigned even when it reports e.g. VOLUME_UP.
     * Volume keys only turn pages when [AppSettings.volumeKeysTurn]; otherwise they return NONE (system volume).
     */
    fun resolve(keyCode: Int, shift: Boolean, app: AppSettings): KeyAction {
        if (keyCode in app.nextPageKeys) return KeyAction.NEXT
        if (keyCode in app.prevPageKeys) return KeyAction.PREV
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN -> when {
                !app.volumeKeysTurn -> KeyAction.NONE
                app.invertVolumeKeys -> KeyAction.PREV
                else -> KeyAction.NEXT
            }
            KeyEvent.KEYCODE_VOLUME_UP -> when {
                !app.volumeKeysTurn -> KeyAction.NONE
                app.invertVolumeKeys -> KeyAction.NEXT
                else -> KeyAction.PREV
            }
            KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
            -> KeyAction.NEXT
            KeyEvent.KEYCODE_SPACE -> if (shift) KeyAction.PREV else KeyAction.NEXT
            KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_REWIND,
            -> KeyAction.PREV
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            -> KeyAction.MENU
            else -> KeyAction.NONE
        }
    }

    fun isVolumeKey(keyCode: Int): Boolean =
        keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN

    /** Keys that should drive focus navigation instead of paging while the chrome is visible. */
    fun isFocusKey(keyCode: Int): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP,
        KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_TAB,
        -> true
        else -> false
    }
}

/**
 * Drops key auto-repeat that arrives faster than [minIntervalMs] after the last accepted press, so holding a
 * key pages at a readable pace on e-ink. First presses (repeatCount == 0) are always accepted.
 */
class RepeatFilter(private val minIntervalMs: Long = 150) {
    private var lastAccepted = Long.MIN_VALUE / 2

    fun accept(repeatCount: Int, timeMs: Long): Boolean {
        if (repeatCount == 0 || timeMs - lastAccepted >= minIntervalMs) {
            lastAccepted = timeMs
            return true
        }
        return false
    }
}
