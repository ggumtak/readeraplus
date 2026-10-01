package com.ggumtak.readeraplus.ui.settings

import com.ggumtak.readeraplus.reader.KeyAction
import com.ggumtak.readeraplus.reader.KeyMap
import com.ggumtak.readeraplus.settings.AppSettings
import com.ggumtak.readeraplus.settings.TapAction
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The key test in 넘김·화면 설정 describes a key with [KeyNames.readerEffectLabel], a copy of the reader's resolution
 * order ([KeyMap.action]). This keeps the copy from drifting: every key code, under settings that exercise each rule.
 */
class KeyEffectParityTest {
    private val settings = listOf(
        AppSettings(),
        AppSettings(volumeKeysTurn = false),
        AppSettings(invertVolumeKeys = true),
        AppSettings(nextPageKeys = setOf(24, 131), prevPageKeys = setOf(25, 62)),
        AppSettings(
            keyBindings = mapOf(24 to TapAction.NEXT_CHAPTER, 93 to TapAction.NONE, 131 to TapAction.TOC),
            nextPageKeys = setOf(132),
        ),
    )

    @Test
    fun keyTestDescribesWhatTheReaderDoes() {
        for (app in settings) for (code in 0..320) for (shift in listOf(false, true)) {
            val bound = app.keyBindings[code]
            val expected = when {
                bound == TapAction.NONE -> "시스템에 맡김"
                bound != null -> KeyActions.label(bound)
                else -> when (KeyMap.resolve(code, shift, app)) {
                    KeyAction.NEXT -> KeyNames.Effect.NEXT.label
                    KeyAction.PREV -> KeyNames.Effect.PREV.label
                    KeyAction.MENU -> KeyNames.Effect.MENU.label
                    KeyAction.NONE ->
                        if (KeyMap.isVolumeKey(code)) KeyNames.Effect.VOLUME.label else KeyNames.Effect.NONE.label
                }
            }
            assertEquals("key $code shift=$shift $app", expected, KeyNames.readerEffectLabel(code, shift, app))
            // The reader's own answer agrees on bindings.
            if (bound != null) assertEquals(bound, KeyMap.action(code, shift, app))
        }
    }
}
