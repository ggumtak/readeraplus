package com.ggumtak.readeraplus.data

import com.ggumtak.readeraplus.settings.StatusItem
import com.ggumtak.readeraplus.engine.Align
import com.ggumtak.readeraplus.engine.LineBreakMode
import com.ggumtak.readeraplus.settings.AppSettings
import com.ggumtak.readeraplus.settings.BrightnessEncoding
import com.ggumtak.readeraplus.settings.LibraryListMode
import com.ggumtak.readeraplus.settings.LibrarySort
import com.ggumtak.readeraplus.settings.PageTheme
import com.ggumtak.readeraplus.settings.ReaderSettings
import com.ggumtak.readeraplus.settings.TapAction
import com.ggumtak.readeraplus.settings.TapZoneMode
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsJsonTest {

    private val reader = ReaderSettings(
        fontId = "user:MyFont.ttf", fontSizeSp = 23.5f, fontWeight = 550, lineHeightPct = 185, paragraphSpacingPct = 80,
        indentPct = 150, letterSpacingPm = -20, align = Align.LEFT, lineBreak = LineBreakMode.CHAR, marginLeftDp = 10,
        marginRightDp = 12, marginTopDp = 20, marginBottomDp = 22, pageMargins = false, invert = true, headerCenter = StatusItem.NONE,
        footerCenter = StatusItem.CHAPTER_PAGES_LEFT, statusFontSizeSp = 12.5f, widowOrphanControl = false, txtBlankLines = 2,
        txtStripIndent = false, txtJoinWrappedLines = 0, txtDetectChapters = false, txtChapterRegex = "^제\\d+장$",
        txtEmphasizeHeadings = false, txtReplaceRules = "a => b\n# c", epubPublisherStyles = false,
        epubIgnoreBookSizes = false,
    )

    private val app = AppSettings(
        tapZoneMode = TapZoneMode.CUSTOM,
        customTapZones = List(9) { if (it % 2 == 0) TapAction.NEXT else TapAction.TOC },
        swipeToTurn = false, verticalSwipe = true, volumeKeysTurn = false, invertVolumeKeys = true,
        nextPageKeys = setOf(92, 93), prevPageKeys = setOf(94), longPressSelect = false, bookmarkByTouch = false,
        invertByTouch = true, fullscreen = false, keepScreenOn = false, brightnessSwipe = true, openLastOnStart = true,
        einkRefreshEvery = 6, einkRefreshOnChapter = true, autoTurnSeconds = 45, ttsRate = 1.25f, ttsPitch = 0.9f,
        ttsSleepMinutes = 30, webSearchUrl = "https://search.naver.com/search.naver?query=%s",
        librarySort = LibrarySort.TITLE, libraryListMode = LibraryListMode.GRID,
        scanFolders = setOf("/storage/emulated/0/Books", "/storage/emulated/0/Download"),
        excludedFolders = setOf("/storage/emulated/0/Books/old"), orientationLock = 1, brightness = 0.4f,
    )

    @Test
    fun readerRoundTrip() {
        val json = JSONObject(SettingsJson.readerToJson(reader).toString())
        assertEquals(reader, SettingsJson.readerFromJson(json, ReaderSettings()))
    }

    @Test
    fun appRoundTrip() {
        val json = JSONObject(SettingsJson.appToJson(app).toString())
        assertEquals(app, SettingsJson.appFromJson(json, AppSettings()))
    }

    @Test
    fun missingFieldsKeepBase() {
        assertEquals(reader, SettingsJson.readerFromJson(JSONObject(), reader))
        assertEquals(app, SettingsJson.appFromJson(JSONObject(), app))
    }

    @Test
    fun pageThemeTravelsAndOldBackupsAreOnTheWhitePage() {
        val maru = reader.copy(pageTheme = PageTheme.MARU)
        val json = JSONObject(SettingsJson.readerToJson(maru).toString())
        assertEquals("MARU", json.getString("r.pageTheme"))
        assertEquals(maru, SettingsJson.readerFromJson(json, ReaderSettings()))
        // A backup made before 화면 색 was taken on the white page: it restores 흰 바탕 even over a 마루뷰어 device.
        json.remove("r.pageTheme")
        assertEquals(maru.copy(pageTheme = PageTheme.PAPER), SettingsJson.readerFromJson(json, maru))
        // A theme this build does not know: 흰 바탕 too.
        assertEquals(PageTheme.PAPER, SettingsJson.readerFromJson(json.put("r.pageTheme", "SEPIA"), maru).pageTheme)
        assertEquals(PageTheme.MARU, SettingsJson.readerFromJson(json.put("r.pageTheme", "maru"), reader).pageTheme)
    }

    @Test
    fun badValuesAreClampedOrIgnored() {
        val r = SettingsJson.readerFromJson(
            JSONObject()
                .put("r.fontSizeSp", 999)
                .put("r.fontWeight", "heavy")
                .put("r.align", "SIDEWAYS")
                .put("r.lineBreak", "char")
                .put("r.fontId", "  ")
                .put("r.txtBlankLines", 17)
                .put("r.invert", "true")
                .put("r.marginLeftDp", -4),
            ReaderSettings(),
        )
        val defaults = ReaderSettings()
        assertEquals(ReaderSettings.MAX_FONT_SP, r.fontSizeSp, 0f)
        assertEquals(defaults.fontWeight, r.fontWeight)
        assertEquals(defaults.align, r.align)
        assertEquals(LineBreakMode.CHAR, r.lineBreak)
        assertEquals(defaults.fontId, r.fontId)
        assertEquals(3, r.txtBlankLines)
        assertTrue(r.invert)
        assertEquals(0, r.marginLeftDp)

        val a = SettingsJson.appFromJson(
            JSONObject()
                .put("a.customTapZones", "NEXT,PREV") // wrong size
                .put("a.nextPageKeys", JSONArray().put(24).put("25").put("x").put(1.5))
                .put("a.brightness", -7)
                .put("a.ttsRate", 100)
                .put("a.webSearchUrl", ""),
            AppSettings(),
        )
        assertEquals(AppSettings().customTapZones, a.customTapZones)
        assertEquals(setOf(24, 25), a.nextPageKeys)
        assertEquals(-1f, a.brightness, 0f)
        assertEquals(4f, a.ttsRate, 0f)
        assertEquals(AppSettings().webSearchUrl, a.webSearchUrl)

        val zones = SettingsJson.appFromJson(
            JSONObject().put("a.customTapZones", JSONArray(List(9) { "MENU" })),
            AppSettings(),
        ).customTapZones
        assertEquals(List(9) { TapAction.MENU }, zones)
    }

    @Test
    fun otherPrefsKeepTypesAndSkipSettingsAndTransientKeys() {
        val raw = mapOf<String, Any?>(
            "r.fontId" to "x", "a.fullscreen" to true,
            "lastScanAt" to 5L, "lastBackupAt" to 6L, "cacheEpoch" to 7L,
            "libraryShelf" to "FAVORITES", "brightnessCollapsed" to true, "someInt" to 3, "someLong" to 5_000_000_000L,
            "smallLong" to 5L, "someFloat" to 0.5f, "nanFloat" to Float.NaN, "tags" to setOf("b", "a"), "nul" to null,
        )
        val (values, types) = SettingsJson.otherToJson(raw)
        val restored = SettingsJson.otherFromJson(JSONObject(values.toString()), JSONObject(types.toString()))
            .associateBy { it.key }
        assertEquals(setOf("libraryShelf", "brightnessCollapsed", "someInt", "someLong", "smallLong", "someFloat", "tags"), restored.keys)
        assertEquals(RawPref("libraryShelf", SettingsJson.TYPE_STRING, "FAVORITES"), restored["libraryShelf"])
        assertEquals(RawPref("brightnessCollapsed", SettingsJson.TYPE_BOOL, true), restored["brightnessCollapsed"])
        assertEquals(RawPref("someInt", SettingsJson.TYPE_INT, 3), restored["someInt"])
        assertEquals(RawPref("someLong", SettingsJson.TYPE_LONG, 5_000_000_000L), restored["someLong"])
        // A Long that fits in an Int must come back as a Long (its declared type), not an Int.
        assertEquals(RawPref("smallLong", SettingsJson.TYPE_LONG, 5L), restored["smallLong"])
        assertEquals(RawPref("someFloat", SettingsJson.TYPE_FLOAT, 0.5f), restored["someFloat"])
        assertEquals(RawPref("tags", SettingsJson.TYPE_SET, setOf("a", "b")), restored["tags"])
    }

    @Test
    fun otherPrefsWithoutTypesAreInferredAndMismatchesDropped() {
        val values = JSONObject("""{"i": 4, "f": 1.5, "s": "x", "b": false, "set": ["p"], "r.x": 1, "lastScanAt": 9, "obj": {}}""")
        val out = SettingsJson.otherFromJson(values, null).associateBy { it.key }
        assertEquals(SettingsJson.TYPE_INT, out["i"]!!.type)
        assertEquals(SettingsJson.TYPE_FLOAT, out["f"]!!.type)
        assertEquals(SettingsJson.TYPE_STRING, out["s"]!!.type)
        assertEquals(SettingsJson.TYPE_BOOL, out["b"]!!.type)
        assertEquals(SettingsJson.TYPE_SET, out["set"]!!.type)
        assertFalse(out.containsKey("r.x"))
        assertFalse(out.containsKey("lastScanAt"))
        assertFalse(out.containsKey("obj"))

        val typed = SettingsJson.otherFromJson(
            JSONObject("""{"i": "not a number", "b": 1, "l": 2.5}"""),
            JSONObject("""{"i": "int", "b": "bool", "l": "long"}"""),
        )
        assertTrue(typed.isEmpty())
    }

    @Test
    fun settingsEnvelope() {
        val o = SettingsJson.settingsToJson(reader, app, mapOf("x" to 1))
        assertTrue(o.has("reader") && o.has("app") && o.has("other") && o.has("otherTypes"))
        assertEquals("int", o.getJSONObject("otherTypes").getString("x"))
    }

    @Test
    fun brightnessTravelsWithItsEncodingMarker() {
        val json = JSONObject(SettingsJson.appToJson(AppSettings(brightness = 0.4f)).toString())
        assertEquals(BrightnessEncoding.VERSION, json.getInt(BrightnessEncoding.KEY_VERSION))
        // With the marker: used as is (never converted twice), whatever the device's own state.
        assertEquals(0.4f, SettingsJson.appFromJson(json, AppSettings(brightness = 0.9f, brightnessDevice = true)).brightness, 0f)
        assertEquals(0.4f, SettingsJson.appFromJson(json, AppSettings(brightness = 0.9f)).brightness, 0f)
        // Export, restore, export again: stable.
        val again = SettingsJson.appToJson(SettingsJson.appFromJson(json, AppSettings()))
        assertEquals(0.4, again.getDouble("a.brightness"), 1e-6)
    }

    @Test
    fun legacyBackupBrightnessIsConvertedOnceByTheFlagInEffect() {
        val pos = Math.sqrt((0.25 - 0.01) / 0.99).toFloat()
        // No marker, no flag (builds that wrote no marker never exported it): the restoring device's flag decides.
        // Window path (flag off): linear light, restored as its position.
        val legacy = JSONObject().put("a.brightness", 0.25)
        assertEquals(pos, SettingsJson.appFromJson(legacy, AppSettings()).brightness, 1e-6f)
        // Device path (flag on): already a position. The flag itself is not restored.
        val restored = SettingsJson.appFromJson(legacy, AppSettings(brightnessDevice = true))
        assertEquals(0.25f, restored.brightness, 0f)
        assertTrue(restored.brightnessDevice)
        // A backup that carries its own device flag wins over the device's.
        val device = JSONObject().put("a.brightness", 0.25).put("a.brightnessDevice", true)
        assertEquals(0.25f, SettingsJson.appFromJson(device, AppSettings()).brightness, 0f)
        val window = JSONObject().put("a.brightness", 0.25).put("a.brightnessDevice", false)
        assertEquals(pos, SettingsJson.appFromJson(window, AppSettings(brightnessDevice = true)).brightness, 1e-6f)
        // Auto stays; a missing value keeps the device's own.
        assertEquals(-1f, SettingsJson.appFromJson(JSONObject().put("a.brightness", -1), AppSettings(brightness = 0.7f)).brightness, 0f)
        assertEquals(0.7f, SettingsJson.appFromJson(JSONObject(), AppSettings(brightness = 0.7f)).brightness, 0f)
    }

    @Test
    fun backupMarkerIsReadByItsVersionValue() {
        val v = BrightnessEncoding.KEY_VERSION
        val pos = Math.sqrt((0.25 - 0.01) / 0.99).toFloat()
        // An older version is legacy; a newer unknown one is the current meaning, made safe.
        assertEquals(pos, SettingsJson.appFromJson(JSONObject().put("a.brightness", 0.25).put(v, 1), AppSettings()).brightness, 1e-6f)
        assertEquals(0.25f, SettingsJson.appFromJson(JSONObject().put("a.brightness", 0.25).put(v, 3), AppSettings()).brightness, 0f)
        assertEquals(1f, SettingsJson.appFromJson(JSONObject().put("a.brightness", 4).put(v, 2), AppSettings()).brightness, 0f)
        assertEquals(-1f, SettingsJson.appFromJson(JSONObject().put("a.brightness", -0.2).put(v, 2), AppSettings(brightness = 0.7f)).brightness, 0f)
    }

    @Test
    fun brightnessMarkerIsAMappedKeyNotARawEntry() {
        val raw = mapOf(BrightnessEncoding.KEY_VERSION to 2, "a.brightness" to 0.4f)
        val a = SettingsJson.settingsToJson(ReaderSettings(), AppSettings(brightness = 0.4f), raw).getJSONObject("app")
        assertEquals(2, a.getInt(BrightnessEncoding.KEY_VERSION))
        assertTrue(SettingsJson.unmappedFromJson(a, SettingsJson.APP_PREFIX, raw).isEmpty())
        // Exported from the typed settings even when the raw prefs have no marker yet (never saved since the update).
        assertEquals(2, SettingsJson.settingsToJson(ReaderSettings(), AppSettings(), emptyMap<String, Any?>()).getJSONObject("app").getInt(BrightnessEncoding.KEY_VERSION))
    }
}
