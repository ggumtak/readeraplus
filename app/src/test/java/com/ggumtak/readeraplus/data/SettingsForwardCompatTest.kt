package com.ggumtak.readeraplus.data

import com.ggumtak.readeraplus.settings.AppSettings
import com.ggumtak.readeraplus.settings.ReaderSettings
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Settings fields the typed backup mapping doesn't name (added to the settings contract later, e.g. the
 * reader's tap inversion / pinned menu switches) must survive a restore and travel with a backup.
 */
class SettingsForwardCompatTest {

    /** A copy of [default] with every Boolean constructor property flipped (built reflectively, test-only). */
    private fun <T : Any> flipBooleans(default: T): T {
        val cls = default.javaClass
        val ctor = cls.constructors.filter { !it.isSynthetic }.maxByOrNull { it.parameterCount }!!
        val fields = cls.declaredFields.filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) }
        require(fields.size == ctor.parameterCount) { "unexpected shape of ${cls.simpleName}" }
        val args = fields.map { f ->
            f.isAccessible = true
            val v = f.get(default)
            if (v is Boolean) !v else v
        }
        @Suppress("UNCHECKED_CAST")
        return ctor.newInstance(*args.toTypedArray()) as T
    }

    @Test
    fun restoreKeepsFieldsTheMapperDoesNotName() {
        // Regression: appFromJson built a fresh AppSettings, resetting unnamed fields to their defaults.
        val base = flipBooleans(AppSettings())
        assertNotEquals(AppSettings(), base)
        assertEquals(base, SettingsJson.appFromJson(JSONObject(), base))
        val rb = flipBooleans(ReaderSettings())
        assertEquals(rb, SettingsJson.readerFromJson(JSONObject(), rb))
    }

    @Test
    fun unmappedRawFieldsAreExported() {
        val raw = mapOf(
            "a.futureFlag" to true, "a.futureInt" to 7, "a.futureFloat" to 0.5f, "a.futureSet" to setOf("b", "a"),
            "a.tapZoneMode" to "BOGUS", "r.futureText" to "x", "a." to true, "reader.brightness" to 0.3f,
            "a.nullish" to null,
        )
        val json = SettingsJson.settingsToJson(ReaderSettings(), AppSettings(), raw)
        val app = json.getJSONObject("app")
        assertEquals(true, app.getBoolean("a.futureFlag"))
        assertEquals(7, app.getInt("a.futureInt"))
        assertEquals(0.5, app.getDouble("a.futureFloat"), 1e-9)
        assertEquals(JSONArray(listOf("a", "b")).toString(), app.getJSONArray("a.futureSet").toString())
        // Typed values win over raw ones; other prefixes and the bare prefix are not copied.
        assertEquals(AppSettings().tapZoneMode.name, app.getString("a.tapZoneMode"))
        assertFalse(app.has("a."))
        assertFalse(app.has("a.nullish"))
        assertEquals("x", json.getJSONObject("reader").getString("r.futureText"))
        assertFalse(app.has("reader.brightness"))
    }

    @Test
    fun unmappedValuesAreTypedLikeTheDevice() {
        val o = JSONObject()
            .put("a.flag", true)
            .put("a.count", 5)
            .put("a.float", 2)
            .put("a.long", 1234567890123L)
            .put("a.set", JSONArray(listOf("x", "y")))
            .put("a.wrongType", "text")
            .put("a.fraction", 2.5)
            .put("a.notOnDevice", true)
            .put("a.tapZoneMode", "ALL_NEXT")
            .put("r.otherPrefix", true)
        val current = mapOf(
            "a.flag" to false, "a.count" to 1, "a.float" to 1.5f, "a.long" to 1L, "a.set" to setOf("z"),
            "a.wrongType" to true, "a.fraction" to 3, "a.tapZoneMode" to "LEFT_RIGHT", "r.otherPrefix" to false,
        )
        val got = SettingsJson.unmappedFromJson(o, SettingsJson.APP_PREFIX, current)
        assertEquals(
            listOf(
                RawPref("a.count", SettingsJson.TYPE_INT, 5),
                RawPref("a.flag", SettingsJson.TYPE_BOOL, true),
                RawPref("a.float", SettingsJson.TYPE_FLOAT, 2f),
                RawPref("a.long", SettingsJson.TYPE_LONG, 1234567890123L),
                RawPref("a.set", SettingsJson.TYPE_SET, setOf("x", "y")),
            ),
            got,
        )
        assertTrue(SettingsJson.unmappedFromJson(null, SettingsJson.APP_PREFIX, current).isEmpty())
    }

    @Test
    fun exportThenRestoreOfAnUnmappedField() {
        val raw = mapOf<String, Any>("a.pinLike" to true, "r.newMode" to 2)
        val json = SettingsJson.settingsToJson(ReaderSettings(), AppSettings(), raw)
        val device = mapOf<String, Any>("a.pinLike" to false, "r.newMode" to 0)
        assertEquals(
            listOf(RawPref("a.pinLike", SettingsJson.TYPE_BOOL, true)),
            SettingsJson.unmappedFromJson(json.getJSONObject("app"), SettingsJson.APP_PREFIX, device),
        )
        assertEquals(
            listOf(RawPref("r.newMode", SettingsJson.TYPE_INT, 2)),
            SettingsJson.unmappedFromJson(json.getJSONObject("reader"), SettingsJson.READER_PREFIX, device),
        )
    }

    @Test
    fun deviceLocalPermissionFlagsDoNotTravel() {
        assertTrue(SettingsJson.isTransient("library.permPanelHidden"))
        assertTrue(SettingsJson.isTransient("library.legacyPermAsked"))
        assertTrue(SettingsJson.isTransient("lastScanAt"))
        assertFalse(SettingsJson.isTransient("library.shelf"))
        val (values, _) = SettingsJson.otherToJson(mapOf("library.permPanelHidden" to true, "library.shelf" to "ALL"))
        assertFalse(values.has("library.permPanelHidden"))
        assertTrue(values.has("library.shelf"))
    }
}
