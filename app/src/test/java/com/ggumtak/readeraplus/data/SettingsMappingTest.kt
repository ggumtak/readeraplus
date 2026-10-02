package com.ggumtak.readeraplus.data

import com.ggumtak.readeraplus.settings.AppSettings
import com.ggumtak.readeraplus.settings.ReaderSettings
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every settings field needs a typed backup mapping: a field restored only as a raw pref bypasses Settings'
 * in-memory cache, and the next save of the cached object writes the old value back over the restored one.
 */
class SettingsMappingTest {

    private fun fieldNames(cls: Class<*>): List<String> =
        cls.declaredFields.filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) }.map { it.name }

    @Test
    fun everyAppFieldIsMapped() {
        val keys = SettingsJson.appToJson(AppSettings()).keys().asSequence().toSet()
        val missing = fieldNames(AppSettings::class.java).map { "a.$it" }
            .filter { it !in keys && it !in SettingsJson.DROPPED_KEYS }
        assertTrue("unmapped app settings: $missing", missing.isEmpty())
    }

    @Test
    fun everyReaderFieldIsMapped() {
        val keys = SettingsJson.readerToJson(ReaderSettings()).keys().asSequence().toSet()
        val missing = fieldNames(ReaderSettings::class.java).map { "r.$it" }.filter { it !in keys }
        assertTrue("unmapped reader settings: $missing", missing.isEmpty())
    }

    @Test
    fun invertTapsAndBookmarkByTouchRoundTrip() {
        // Regression: both were missing from appToJson/appFromJson and were lost after a restore.
        val s = AppSettings(invertTaps = true, bookmarkByTouch = true)
        val json = SettingsJson.appToJson(s)
        assertEquals(true, json.getBoolean("a.invertTaps"))
        assertEquals(true, json.getBoolean("a.bookmarkByTouch"))
        assertEquals(s, SettingsJson.appFromJson(json, AppSettings()))
        val back = SettingsJson.appFromJson(JSONObject().put("a.invertTaps", false), s)
        assertEquals(false, back.invertTaps)
        assertEquals(true, back.bookmarkByTouch)
    }
}
