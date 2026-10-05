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
        assertFalse(r.shows(StatusItem.EPISODE))
        assertFalse(r.shows(StatusItem.TIME_LEFT_EPISODE) || r.shows(StatusItem.TIME_LEFT_BOOK))
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
        val r = ReaderSettings(footerLeft = StatusItem.EPISODE, footerCenter = StatusItem.TIME_LEFT_EPISODE)
        Settings.saveApp(a)
        Settings.saveReader(r)
        assertEquals("24:NEXT_CHAPTER,25:NONE,131:GOTO", p.map["a.keyBindings"])
        // A cold start: same store, empty caches.
        Settings.initForTest(p)
        assertEquals(a, Settings.app)
        assertEquals(r, Settings.reader)
    }

    @Test
    fun pageThemeIsStoredByName() {
        val p = fresh()
        // Prefs of the releases before 화면 색: the white page.
        assertEquals(PageTheme.PAPER, Settings.reader.pageTheme)
        Settings.saveReader(ReaderSettings(pageTheme = PageTheme.MARU))
        assertEquals("MARU", p.map["r.pageTheme"])
        Settings.initForTest(p)
        assertEquals(ReaderSettings(pageTheme = PageTheme.MARU), Settings.reader)
        // A name this build does not know (a newer build's theme): the white page.
        fresh(hashMapOf("r.pageTheme" to "SEPIA"))
        assertEquals(PageTheme.PAPER, Settings.reader.pageTheme)
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
        // Unknown actions (a newer build), bad codes and junk are skipped; the last entry for a key wins. Key code 0
        // (every unnamed key, as the 키 지정 dialog promises) survives a restart.
        assertEquals(
            mapOf(24 to TapAction.TOC, 0 to TapAction.NEXT, 92 to TapAction.NONE),
            Settings.decodeKeyBindings("24:NEXT, x:PREV,25:FLY,:NEXT,0:NEXT,-3:NEXT,92 : NONE,junk,24:TOC"),
        )
        val all = TapAction.entries.withIndex().associate { (i, a) -> (100 + i) to a }
        assertEquals(all, Settings.decodeKeyBindings(Settings.encodeKeyBindings(all)))
    }

    @Test fun r3DefaultsRoundTripAndUnknowns() {
        val p=fresh(); assertEquals(ReadMode.PAGED,Settings.app.readMode);assertEquals(ScrollStyle.AUTO,Settings.app.scrollStyle);assertTrue(Settings.app.autoBackup)
        val a=AppSettings(readMode=ReadMode.SCROLL,scrollStyle=ScrollStyle.STEP,autoBackup=false,brightnessDevice=true,brightnessRestore=false,highlightLook=2,listPaging=1,recordLookups=false,libraryListMode=LibraryListMode.COVERS)
        Settings.saveApp(a);Settings.initForTest(p);assertEquals(a,Settings.app)
        fresh(hashMapOf("a.readMode" to "X","a.scrollStyle" to "X","a.highlightLook" to 7,"a.listPaging" to -1))
        assertEquals(ReadMode.PAGED,Settings.app.readMode);assertEquals(ScrollStyle.AUTO,Settings.app.scrollStyle);assertEquals(0,Settings.app.highlightLook);assertEquals(0,Settings.app.listPaging)
    }
    @Test fun everyStatusItemInEverySlotRoundTrips() {
        val p=fresh()
        for (band in 0..1) for (pos in 0..2) for (item in StatusItem.entries) {
            val r=ReaderSettings().withSlot(band,pos,item)
            assertEquals(item,r.slot(band,pos));Settings.saveReader(r);Settings.initForTest(p);assertEquals(r,Settings.reader)
        }
    }
    @Test fun legacyMigrationReadsWithoutWritingThenCleansOnSave() {
        val raw=hashMapOf<String,Any?>("r.marginLeftDp" to 18,"r.marginRightDp" to 18,"r.marginTopDp" to 16,"r.marginBottomDp" to 16,
            "r.showFooter" to true,"r.footerPage" to false,"r.footerPercent" to true,"r.footerClock" to true,"r.footerBattery" to true,"a.pinChrome" to true,"reader.brightnessCollapsed" to true)
        val before=HashMap(raw);val p=fresh(raw);val r=Settings.reader
        assertEquals(20,r.marginLeftDp);assertEquals(StatusItem.PERCENT,r.footerLeft);assertEquals(StatusItem.CLOCK_BATTERY,r.footerRight);assertEquals(before,raw)
        // 16/16 is R2's untouched default: 40/40 from the edge, then counted from the bands these settings have (MaruViewer's
        // header 22 dp; footer items above the line 36 dp), so the text box stays 40 dp from both edges.
        assertEquals(listOf(18,4),listOf(r.marginTopDp,r.marginBottomDp))
        Settings.saveReader(r);Settings.saveApp(Settings.app)
        for (k in StatusMigration.LEGACY_KEYS) assertFalse(p.contains(k))
        assertTrue(p.contains(StatusMigration.MARKER_KEY));assertFalse(p.contains("a.pinChrome"));assertFalse(p.contains("reader.brightnessCollapsed"))
    }
    @Test fun untouchedFortyDpSidesBecomeMaruViewersTwenty() {
        // Saved by an R3 build: the marker holds 40, its "0". Read as 20/20 without writing; a save writes the new "0".
        val raw=hashMapOf<String,Any?>(SideMargin.KEY to 40,VerticalMargin.KEY to 40,MaruHeader.KEY to true,
            "r.marginLeftDp" to 40,"r.marginRightDp" to 40,"r.marginTopDp" to 40,"r.marginBottomDp" to 40)
        val before=HashMap(raw);val p=fresh(raw);val r=Settings.reader
        // Top and bottom (40/40 from the edge) are counted from the default bands: the new defaults 18/22, same text box.
        assertEquals(listOf(20,20,18,22),listOf(r.marginLeftDp,r.marginRightDp,r.marginTopDp,r.marginBottomDp));assertEquals(before,raw)
        Settings.saveReader(r);assertEquals(20,p.map[SideMargin.KEY]);assertEquals(20,p.map["r.marginLeftDp"])
        assertEquals(VerticalMargin.BANDS,p.map[VerticalMargin.KEY]);assertEquals(18,p.map["r.marginTopDp"])
        Settings.initForTest(p);assertEquals(r,Settings.reader)
        // Values the user changed under R3 stay; so does 40/40 saved by this build.
        fresh(hashMapOf(SideMargin.KEY to 40,"r.marginLeftDp" to 30,"r.marginRightDp" to 30));assertEquals(30,Settings.reader.marginLeftDp)
        fresh(hashMapOf(SideMargin.KEY to 40,"r.marginLeftDp" to 40,"r.marginRightDp" to 44));assertEquals(40,Settings.reader.marginLeftDp)
        val q=fresh();val forty=ReaderSettings(marginLeftDp=40,marginRightDp=40);Settings.saveReader(forty);Settings.initForTest(q);assertEquals(forty,Settings.reader)
    }
    @Test fun maruViewersHeaderComesOnceAndLaterChoicesStay() {
        // Prefs saved before the switch: the user's own header (and footer) slots.
        val raw=hashMapOf<String,Any?>("r.headerLeft" to "NONE","r.headerCenter" to "CHAPTER","r.headerRight" to "CHAPTER_PAGES_LEFT",
            "r.footerLeft" to "PERCENT","r.footerCenter" to "NONE","r.footerRight" to "NONE","r.progressBar" to false,"r.statusFontSizeSp" to 13f)
        val before=HashMap(raw);val p=fresh(raw);val r=Settings.reader
        assertEquals(listOf(StatusItem.CLOCK_BATTERY,StatusItem.BOOK_TITLE,StatusItem.PAGE),listOf(r.headerLeft,r.headerCenter,r.headerRight))
        // Footer, progress bar and size are the user's still; loading wrote nothing.
        assertEquals(StatusItem.PERCENT,r.footerLeft);assertFalse(r.progressBar);assertEquals(13f,r.statusFontSizeSp,0f);assertEquals(before,raw)
        Settings.saveReader(r);assertEquals(true,p.map[MaruHeader.KEY])
        // Once saved, the user's next choice stays.
        val mine=r.copy(headerLeft=StatusItem.NONE,headerRight=StatusItem.PERCENT);Settings.saveReader(mine)
        Settings.initForTest(p);assertEquals(mine,Settings.reader)
        // A fresh install has nothing to switch: the defaults.
        fresh();assertEquals(ReaderSettings(),Settings.reader)
    }
    @Test fun topAndBottomSavedFromTheEdgeMoveOnceOntoTheBands() {
        // The user's devices (2026-10-05): saved by the MaruViewer build, 40/40 under the U3 marker, its header and the
        // progress line. Read as the new defaults without writing: the text box stays where it was.
        val raw=hashMapOf<String,Any?>(SideMargin.KEY to 20,VerticalMargin.KEY to 40,MaruHeader.KEY to true,
            "r.marginLeftDp" to 20,"r.marginRightDp" to 20,"r.marginTopDp" to 40,"r.marginBottomDp" to 40,
            "r.headerLeft" to "CLOCK_BATTERY","r.headerCenter" to "BOOK_TITLE","r.headerRight" to "PAGE",
            "r.footerLeft" to "NONE","r.footerCenter" to "NONE","r.footerRight" to "NONE","r.progressBar" to true,"r.statusFontSizeSp" to 11f)
        val before=HashMap(raw);val p=fresh(raw)
        assertEquals(ReaderSettings(),Settings.reader);assertEquals(before,raw)
        // Read again before any save: the same (from the stored 40/40, never from a moved value).
        Settings.initForTest(p);assertEquals(ReaderSettings(),Settings.reader)
        // The next save writes the band marker; from then on the values are read as they are (18 stays 18, 0 stays 0).
        Settings.saveReader(Settings.reader);assertEquals(VerticalMargin.BANDS,p.map[VerticalMargin.KEY])
        Settings.initForTest(p);assertEquals(ReaderSettings(),Settings.reader)
        val zero=ReaderSettings(marginTopDp=0,marginBottomDp=0);Settings.saveReader(zero);Settings.initForTest(p);assertEquals(zero,Settings.reader)
        // The user's own values from the edge, with their own bands: no header, a footer with the line (36 dp).
        fresh(hashMapOf(VerticalMargin.KEY to 40,MaruHeader.KEY to true,"r.marginTopDp" to 30,"r.marginBottomDp" to 50,
            "r.headerLeft" to "NONE","r.headerCenter" to "NONE","r.headerRight" to "NONE","r.footerLeft" to "NONE","r.footerCenter" to "PAGE"))
        assertEquals(listOf(30,14),listOf(Settings.reader.marginTopDp,Settings.reader.marginBottomDp))
        // A margin smaller than its band stops at 0.
        fresh(hashMapOf(VerticalMargin.KEY to 40,MaruHeader.KEY to true,"r.marginTopDp" to 10,"r.marginBottomDp" to 10))
        assertEquals(listOf(0,0),listOf(Settings.reader.marginTopDp,Settings.reader.marginBottomDp))
        // Saved before MaruViewer's header (no header marker, all slots none): the header it gets now counts too.
        fresh(hashMapOf(VerticalMargin.KEY to 40,"r.marginTopDp" to 40,"r.marginBottomDp" to 40,
            "r.headerLeft" to "NONE","r.headerCenter" to "NONE","r.headerRight" to "NONE","r.footerLeft" to "NONE"))
        assertEquals(listOf(18,22),listOf(Settings.reader.marginTopDp,Settings.reader.marginBottomDp))
    }
    @Test fun deliberateMarginsAndPageBreakRoundTrip() {
        val p=fresh();val r=ReaderSettings(marginLeftDp=18,marginRightDp=18,marginTopDp=16,marginBottomDp=16,pageBreak=com.ggumtak.readeraplus.engine.PageBreakMode.PARAGRAPH)
        Settings.saveReader(r);Settings.initForTest(p);assertEquals(r,Settings.reader)
        fresh(hashMapOf("r.pageBreak" to "X"));assertEquals(com.ggumtak.readeraplus.engine.PageBreakMode.LINE,Settings.reader.pageBreak)
    }

}
