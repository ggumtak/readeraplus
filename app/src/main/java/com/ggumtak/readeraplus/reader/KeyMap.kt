package com.ggumtak.readeraplus.reader

import android.view.KeyEvent
import com.ggumtak.readeraplus.settings.AppSettings
import com.ggumtak.readeraplus.settings.EINK_MODE_FAST
import com.ggumtak.readeraplus.settings.KeyHold
import com.ggumtak.readeraplus.settings.TapAction

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

    /**
     * What [keyCode] does (T1-4), in resolution order: [AppSettings.keyBindings] (a binding on a volume key overrides
     * [AppSettings.volumeKeysTurn]; a key bound to [TapAction.NONE] is left to the system), then the learned
     * [AppSettings.nextPageKeys] / [AppSettings.prevPageKeys], then the built-in keys ([resolve]). [TapAction.NONE]:
     * not the reader's key. One map lookup per key event.
     */
    fun action(keyCode: Int, shift: Boolean, app: AppSettings): TapAction {
        if (app.keyBindings.isNotEmpty()) app.keyBindings[keyCode]?.let { return it }
        return when (resolve(keyCode, shift, app)) {
            KeyAction.NEXT -> TapAction.NEXT
            KeyAction.PREV -> TapAction.PREV
            KeyAction.MENU -> TapAction.MENU
            KeyAction.NONE -> TapAction.NONE
        }
    }

    /** True when the user assigned [keyCode] a job ("키 지정": a binding or a learned page key). */
    fun isAssigned(keyCode: Int, app: AppSettings): Boolean =
        keyCode in app.nextPageKeys || keyCode in app.prevPageKeys ||
            (app.keyBindings.isNotEmpty() && app.keyBindings.containsKey(keyCode))

    /**
     * True for a key the built-in map knows, whatever the settings (volume, page, arrow, space, media, menu keys).
     * An assigned key that is not one of them is a vendor function / fingerprint key or a remote's: those bounce and
     * get the learned keys' filter ([RepeatFilter.LEARNED_MS], fresh presses included).
     */
    fun isBuiltIn(keyCode: Int): Boolean =
        isVolumeKey(keyCode) || resolve(keyCode, false, BUILT_IN) != KeyAction.NONE

    /**
     * Auto-repeat pace of a held page key in "계속 넘기기" (T1-4): 150 ms in the FAST e-ink mode, else 250 ms (the
     * panel's own update time; a faster repeat overshoots). Fresh presses are never paced.
     */
    fun repeatMs(einkMode: Int): Long = if (einkMode == EINK_MODE_FAST) FAST_REPEAT_MS else REPEAT_MS

    const val REPEAT_MS = 250L
    const val FAST_REPEAT_MS = 150L

    private val BUILT_IN = AppSettings()

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
 * key pages at a readable pace on e-ink. First presses (repeatCount == 0) are always accepted unless
 * [throttleFreshPresses]: then every press closer than [minIntervalMs] to the last accepted one is dropped too
 * (for learned keys such as a fingerprint / function key that fires several separate presses per touch).
 */
class RepeatFilter(private val minIntervalMs: Long = 150, private val throttleFreshPresses: Boolean = false) {
    private var lastAccepted = Long.MIN_VALUE / 2

    fun accept(repeatCount: Int, timeMs: Long): Boolean {
        val fresh = repeatCount == 0 && !throttleFreshPresses
        if (fresh || timeMs - lastAccepted >= minIntervalMs) {
            lastAccepted = timeMs
            return true
        }
        return false
    }

    companion object {
        /** Auto-repeat paced, every fresh press counts (the reader's page keys are paced by [HeldKey] since R2). */
        const val NORMAL_MS = 150L
        /** Assigned vendor function / fingerprint / remote keys bounce, so one touch must turn one page. */
        const val LEARNED_MS = 300L
    }
}

/**
 * One page key held down (T1-4; pure, unit-tested, main thread only). The key-down turns the page at once (fresh
 * presses are never delayed); what the auto-repeat does then follows [KeyHold]:
 * - [KeyHold.REPEAT]: one more turn per repeat, at most every `repeatMs`;
 * - [KeyHold.CHAPTER] / [KeyHold.TEN]: [Step.HOLD] once, at the first repeat (≈ 0.5 s) — the caller moves relative to
 *   where the key went down, not to where the first turn already took the reader — then nothing until the key-up;
 * - [KeyHold.SINGLE]: nothing more.
 * Repeats of a press that was not taken ([cancel]) or of another key do nothing.
 */
class HeldKey {
    enum class Step { TURN, HOLD, NONE }

    private var code = NO_KEY
    private var holdDone = false
    private var lastTurnAt = 0L

    /** A key-down of a page key at [timeMs] (uptime ms) with its [repeatCount]. */
    fun down(keyCode: Int, repeatCount: Int, timeMs: Long, hold: KeyHold, repeatMs: Long): Step {
        if (repeatCount == 0) {
            code = keyCode
            holdDone = false
            lastTurnAt = timeMs
            return Step.TURN
        }
        if (keyCode != code) return Step.NONE
        if (hold == KeyHold.REPEAT) {
            if (timeMs - lastTurnAt < repeatMs) return Step.NONE
            lastTurnAt = timeMs
            return Step.TURN
        }
        if (holdDone) return Step.NONE
        holdDone = true
        return if (hold == KeyHold.SINGLE) Step.NONE else Step.HOLD
    }

    fun up(keyCode: Int) {
        if (keyCode == code) code = NO_KEY
    }

    /** The press was dropped (a bounce): its repeats do nothing either. */
    fun cancel() {
        code = NO_KEY
    }

    private companion object {
        const val NO_KEY = Int.MIN_VALUE
    }
}
