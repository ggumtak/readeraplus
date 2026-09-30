package com.ggumtak.readeraplus.settings

import android.content.SharedPreferences
import com.ggumtak.readeraplus.engine.Align
import com.ggumtak.readeraplus.engine.LineBreakMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Settings persistence against an in-memory SharedPreferences (R2 fields, backward compatibility, lazy styles). */
class SettingsStoreTest {

    /** Map-backed prefs that also records which keys were read. */
    private class FakePrefs(val map: MutableMap<String, Any?> = HashMap()) : SharedPreferences {
        val reads = ArrayList<String>()

        override fun getAll(): MutableMap<String, *> = HashMap(map)
        override fun getString(key: String, defValue: String?): String? { reads += key; return map[key] as? String ?: defValue }
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? {
            reads += key
            return (map[key] as? Set<String>)?.toMutableSet() ?: defValues
        }
        override fun getInt(key: String, defValue: Int): Int { reads += key; return map[key] as? Int ?: defValue }
        override fun getLong(key: String, defValue: Long): Long { reads += key; return map[key] as? Long ?: defValue }
        override fun getFloat(key: String, defValue: Float): Float { reads += key; return map[key] as? Float ?: defValue }
        override fun getBoolean(key: String, defValue: Boolean): Boolean { reads += key; return map[key] as? Boolean ?: defValue }
        override fun contains(key: String): Boolean = map.containsKey(key)
        override fun edit(): SharedPreferences.Editor = Ed()
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        inner class Ed : SharedPreferences.Editor {
            private val pending = HashMap<String, Any?>()
            private val removed = HashSet<String>()
            private var clear = false
            override fun putString(key: String, value: String?) = apply { pending[key] = value }
            override fun putStringSet(key: String, values: MutableSet<String>?) = apply { pending[key] = values?.toSet() }
            override fun putInt(key: String, value: Int) = apply { pending[key] = value }
            override fun putLong(key: String, value: Long) = apply { pending[key] = value }
            override fun putFloat(key: String, value: Float) = apply { pending[key] = value }
            override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }
            override fun remove(key: String) = apply { removed += key }
            override fun clear() = apply { clear = true }
            override fun commit(): Boolean { apply(); return true }
            override fun apply() {
                if (clear) map.clear()
                removed.forEach { map.remove(it) }
                map.putAll(pending)
            }
        }
    }

    private fun fresh(map: MutableMap<String, Any?> = HashMap()): FakePrefs = FakePrefs(map).also { Settings.initForTest(it) }

    @Test
    fun missingKeysGiveTheDefaults() {
        // Prefs of the previous release: none of the R2 keys exist.
        fresh(hashMapOf("a.tapZoneMode" to "CUSTOM", "a.einkRefreshEvery" to 5, "r.fontSizeSp" to 24f))
        val a = Settings.app
        val d = AppSettings()
        assertEquals(TapZoneMode.CUSTOM, a.tapZoneMode)
        assertEquals(5, a.einkRefreshEvery)
        assertEquals(d.copy(tapZoneMode = TapZoneMode.CUSTOM, einkRefreshEvery = 5), a)
        assertEquals(500, a.longPressMs)
        assertEquals(emptyMap<Int, TapAction>(), a.keyBindings)
        assertEquals(KeyHold.REPEAT, a.keyHold)
        assertEquals(EINK_REFRESH_AUTO, a.einkRefreshMethod)
        assertEquals(100, a.einkFlashMs)
        assertEquals(-1, a.einkRefreshEveryNight)
        assertFalse(a.einkFlashImages)
        assertTrue(a.autoMarkFinished)
        assertEquals(0, a.ttsSleepChapters)
        assertTrue(a.ttsHighlight)
        assertEquals("", a.ttsVoice)
        val r = Settings.reader
        assertEquals(ReaderSettings(fontSizeSp = 24f), r)
        assertFalse(r.footerEpisode)
        assertEquals(ReaderSettings.TIME_LEFT_OFF, r.footerTimeLeft)
    }

    @Test
    fun unknownEnumNamesGiveTheDefaults() {
        fresh(hashMapOf("a.keyHold" to "SOMETIMES", "a.libraryListMode" to "CAROUSEL", "a.tapZoneMode" to "X"))
        assertEquals(KeyHold.REPEAT, Settings.app.keyHold)
        assertEquals(LibraryListMode.LIST, Settings.app.libraryListMode)
        assertEquals(TapZoneMode.LEFT_RIGHT, Settings.app.tapZoneMode)
    }

    @Test
    fun everyNewFieldRoundTrips() {
        val p = fresh()
        val a = AppSettings(
            keyBindings = mapOf(24 to TapAction.NEXT_CHAPTER, 25 to TapAction.NONE, 131 to TapAction.GOTO),
            keyHold = KeyHold.TEN, longPressMs = 700, einkRefreshMethod = EINK_REFRESH_CLEAN, einkFlashMs = 350,
            einkRefreshEveryNight = 5, einkFlashImages = true, autoMarkFinished = false, ttsSleepChapters = 2,
            ttsHighlight = false, ttsVoice = "ko-kr-x-kod-local", libraryListMode = LibraryListMode.COMPACT,
            customTapZones = List(9) { if (it == 4) TapAction.AUTO_TURN else TapAction.NEXT },
        )
        val r = ReaderSettings(footerEpisode = true, footerTimeLeft = ReaderSettings.TIME_LEFT_EPISODE)
        Settings.saveApp(a)
        Settings.saveReader(r)
        assertEquals("24:NEXT_CHAPTER,25:NONE,131:GOTO", p.map["a.keyBindings"])
        // A cold start: same store, empty caches.
        Settings.initForTest(p)
        assertEquals(a, Settings.app)
        assertEquals(r, Settings.reader)
    }

    @Test
    fun ttsVoiceFallsBackToTheLegacyKey() {
        fresh(hashMapOf("extras.ttsVoice" to "old-voice"))
        assertEquals("old-voice", Settings.app.ttsVoice)
        fresh(hashMapOf("extras.ttsVoice" to "old-voice", "a.ttsVoice" to ""))
        assertEquals("", Settings.app.ttsVoice)
    }

    @Test
    fun userStylesAreParsedLazilyNotByLoadApp() {
        val style = UserStyle.from("밤 독서", ReaderSettings(fontSizeSp = 26f, align = Align.JUSTIFY, lineBreak = LineBreakMode.CHAR))
        val p = fresh()
        Settings.saveUserStyles(listOf(style))
        Settings.initForTest(p)
        p.reads.clear()
        Settings.app
        Settings.reader
        assertFalse("loadApp must not read the styles' JSON", Settings.KEY_USER_STYLES in p.reads)
        assertEquals(listOf(style), Settings.userStyles)
        assertTrue(Settings.KEY_USER_STYLES in p.reads)
        // Cached after the first access.
        p.reads.clear()
        Settings.userStyles
        assertFalse(Settings.KEY_USER_STYLES in p.reads)
    }

    @Test
    fun saveUserStylesKeepsAtMostFiveAndEmptyRemovesTheKey() {
        val p = fresh()
        val many = (1..7).map { UserStyle.from("s$it", ReaderSettings(fontSizeSp = 10f + it)) }
        Settings.saveUserStyles(many)
        assertEquals(UserStyles.MAX, Settings.userStyles.size)
        Settings.initForTest(p)
        assertEquals(many.take(UserStyles.MAX), Settings.userStyles)
        Settings.saveUserStyles(emptyList())
        assertFalse(p.map.containsKey(Settings.KEY_USER_STYLES))
        Settings.initForTest(p)
        assertEquals(emptyList<UserStyle>(), Settings.userStyles)
    }

    @Test
    fun malformedStylesJsonReadsAsNoStyles() {
        fresh(hashMapOf(Settings.KEY_USER_STYLES to "{broken"))
        assertEquals(emptyList<UserStyle>(), Settings.userStyles)
    }

    @Test
    fun keyBindingsCodec() {
        assertEquals("", Settings.encodeKeyBindings(emptyMap()))
        assertEquals("24:NEXT,25:PREV", Settings.encodeKeyBindings(linkedMapOf(25 to TapAction.PREV, 24 to TapAction.NEXT)))
        assertEquals(mapOf(24 to TapAction.NEXT, 25 to TapAction.PREV), Settings.decodeKeyBindings("24:NEXT,25:PREV"))
        assertEquals(emptyMap<Int, TapAction>(), Settings.decodeKeyBindings(null))
        assertEquals(emptyMap<Int, TapAction>(), Settings.decodeKeyBindings("  "))
        // Unknown actions (a newer build), bad codes and junk are skipped; the last entry for a key wins.
        assertEquals(
            mapOf(24 to TapAction.TOC, 92 to TapAction.NONE),
            Settings.decodeKeyBindings("24:NEXT, x:PREV,25:FLY,:NEXT,0:NEXT,-3:NEXT,92 : NONE,junk,24:TOC"),
        )
        val all = TapAction.entries.withIndex().associate { (i, a) -> (100 + i) to a }
        assertEquals(all, Settings.decodeKeyBindings(Settings.encodeKeyBindings(all)))
    }
}
