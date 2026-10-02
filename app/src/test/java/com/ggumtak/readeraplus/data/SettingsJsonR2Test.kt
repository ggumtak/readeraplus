package com.ggumtak.readeraplus.data

import com.ggumtak.readeraplus.settings.StatusItem
import com.ggumtak.readeraplus.engine.Align
import com.ggumtak.readeraplus.settings.AppSettings
import com.ggumtak.readeraplus.settings.EINK_REFRESH_AUTO
import com.ggumtak.readeraplus.settings.EINK_REFRESH_GC16
import com.ggumtak.readeraplus.settings.KeyHold
import com.ggumtak.readeraplus.settings.LibraryListMode
import com.ggumtak.readeraplus.settings.ReaderSettings
import com.ggumtak.readeraplus.settings.Settings
import com.ggumtak.readeraplus.settings.TapAction
import com.ggumtak.readeraplus.settings.UserStyle
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

/** Backup mapping of the R2 settings fields and the saved styles. */
class SettingsJsonR2Test {
    private val app = AppSettings(
        keyBindings = mapOf(24 to TapAction.NEXT_CHAPTER, 25 to TapAction.NONE), keyHold = KeyHold.CHAPTER,
        longPressMs = 1000, einkRefreshMethod = EINK_REFRESH_GC16, einkFlashMs = 200, einkRefreshEveryNight = 3,
        einkFlashImages = true, autoMarkFinished = false, ttsSleepChapters = 1, ttsHighlight = false,
        ttsVoice = "ko-KR-voice", libraryListMode = LibraryListMode.COMPACT,
    )
    private val reader = ReaderSettings(footerLeft = StatusItem.EPISODE, footerCenter = StatusItem.TIME_LEFT_BOOK)

    @Test
    fun newFieldsRoundTrip() {
        assertEquals(app, SettingsJson.appFromJson(JSONObject(SettingsJson.appToJson(app).toString()), AppSettings()))
        assertEquals(reader, SettingsJson.readerFromJson(JSONObject(SettingsJson.readerToJson(reader).toString()), ReaderSettings()))
        // Key bindings travel in the prefs format.
        assertEquals("24:NEXT_CHAPTER,25:NONE", SettingsJson.appToJson(app).getString("a.keyBindings"))
    }

    @Test
    fun oldBackupsKeepTheNewFieldsOfTheDevice() {
        // A backup made before R2 has none of the new keys: the device's values stay.
        val old = SettingsJson.appToJson(AppSettings())
        for (k in listOf("a.keyBindings", "a.keyHold", "a.longPressMs", "a.einkRefreshMethod", "a.einkFlashMs",
            "a.einkRefreshEveryNight", "a.einkFlashImages", "a.autoMarkFinished", "a.ttsSleepChapters",
            "a.ttsHighlight", "a.ttsVoice")) old.remove(k)
        assertEquals(app.copy(libraryListMode = LibraryListMode.LIST), SettingsJson.appFromJson(old, app))
        val oldReader = SettingsJson.readerToJson(ReaderSettings()).apply { for (k in listOf("r.footerLeft","r.footerCenter")) remove(k) }
        assertEquals(reader, SettingsJson.readerFromJson(oldReader, reader))
    }

    @Test
    fun badValuesAreClampedOrIgnored() {
        val o = JSONObject()
            .put("a.keyBindings", "24:NEXT,oops,25:WARP")
            .put("a.keyHold", "FOREVER")
            .put("a.longPressMs", 10)
            .put("a.einkRefreshMethod", 9)
            .put("a.einkFlashMs", 99999)
            .put("a.einkRefreshEveryNight", -7)
            .put("a.ttsSleepChapters", 40)
            .put("a.libraryListMode", "COMPACT")
        val a = SettingsJson.appFromJson(o, AppSettings())
        assertEquals(mapOf(24 to TapAction.NEXT), a.keyBindings)
        assertEquals(KeyHold.REPEAT, a.keyHold)
        assertEquals(200, a.longPressMs)
        assertEquals(EINK_REFRESH_AUTO, a.einkRefreshMethod)
        assertEquals(1000, a.einkFlashMs)
        assertEquals(-1, a.einkRefreshEveryNight)
        assertEquals(9, a.ttsSleepChapters)
        assertEquals(LibraryListMode.COMPACT, a.libraryListMode)
        // A JSON object form of the bindings is accepted too; a non-string/non-object keeps the base.
        val obj = JSONObject().put("a.keyBindings", JSONObject().put("24", "TOC").put("x", "NEXT").put("25", 3))
        assertEquals(mapOf(24 to TapAction.TOC), SettingsJson.appFromJson(obj, AppSettings()).keyBindings)
        assertEquals(app.keyBindings, SettingsJson.appFromJson(JSONObject().put("a.keyBindings", 5), app).keyBindings)
        assertTrue(SettingsJson.readerFromJson(JSONObject().put("r.footerTimeLeft", 7), ReaderSettings()).shows(StatusItem.TIME_LEFT_BOOK))
    }

    @Test
    fun userStylesTravelTypedNotAsRawPrefs() {
        val styles = listOf(UserStyle.from("밤", ReaderSettings(fontSizeSp = 26f, align = Align.JUSTIFY)))
        val raw = mapOf<String, Any?>(
            Settings.KEY_USER_STYLES to "[{\"name\":\"stale\"}]",
            "a.futureFlag" to true,
        )
        val env = SettingsJson.settingsToJson(ReaderSettings(), AppSettings(), raw, styles)
        assertEquals(styles, SettingsJson.userStylesFromJson(JSONObject(env.toString())))
        // The raw JSON string is not copied into the app object (it would be restored behind Settings' cache).
        val appObj = env.getJSONObject("app")
        assertFalse(appObj.has(Settings.KEY_USER_STYLES))
        assertEquals(true, appObj.getBoolean("a.futureFlag"))
        // ... nor restored as an unmapped pref from a backup that has it.
        appObj.put(Settings.KEY_USER_STYLES, "[]")
        val current = mapOf<String, Any?>(Settings.KEY_USER_STYLES to "[]", "a.futureFlag" to false)
        val restored = SettingsJson.unmappedFromJson(appObj, SettingsJson.APP_PREFIX, current)
        assertEquals(listOf("a.futureFlag"), restored.map { it.key })
    }

    @Test
    fun backupsWithoutStylesLeaveTheDevicesStyles() {
        val env = SettingsJson.settingsToJson(ReaderSettings(), AppSettings(), emptyMap<String, Any?>())
        assertFalse(env.has(SettingsJson.USER_STYLES))
        assertNull(SettingsJson.userStylesFromJson(env))
        assertNull(SettingsJson.userStylesFromJson(null))
        // An explicit empty list restores "no styles".
        val none = SettingsJson.settingsToJson(ReaderSettings(), AppSettings(), emptyMap<String, Any?>(), emptyList())
        assertEquals(emptyList<UserStyle>(), SettingsJson.userStylesFromJson(none))
        // Malformed entries are skipped.
        val bad = JSONObject().put(SettingsJson.USER_STYLES, JSONArray().put(1).put(JSONObject().put("name", "ok")))
        assertEquals(listOf("ok"), SettingsJson.userStylesFromJson(bad)!!.map { it.name })
    }
}
