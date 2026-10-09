package com.ggumtak.readeraplus.settings

import com.ggumtak.readeraplus.engine.Align
import com.ggumtak.readeraplus.engine.LineBreakMode
import com.ggumtak.readeraplus.format.ParseOptions
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UserStylesTest {
    private val look = ReaderSettings(
        fontId = "user:MyFont.ttf", fontSizeSp = 23.5f, fontWeight = 650, lineHeightPct = 185, paragraphSpacingPct = 40,
        indentPct = 150, letterSpacingPm = -15, align = Align.JUSTIFY, lineBreak = LineBreakMode.CHAR,
        marginLeftDp = 10, marginRightDp = 12, marginTopDp = 20, marginBottomDp = 22, pageMargins = false,
        pageTheme = PageTheme.MARU,
    )

    @Test
    fun fromThenApplyRestoresTheLookOnly() {
        val u = UserStyle.from("밤", look)
        // Another state: different look AND different non-style fields.
        val other = ReaderSettings(invert = true, footerCenter = StatusItem.NONE, footerLeft = StatusItem.EPISODE, txtBlankLines = ParseOptions.BLANK_KEEP)
        val applied = u.applyTo(other)
        assertTrue(u.matches(applied))
        // The 화면 색 is part of a style (2026-10-04) ...
        assertEquals(PageTheme.MARU, applied.pageTheme)
        assertFalse(u.matches(applied.copy(pageTheme = PageTheme.PAPER)))
        // ... status bar, invert and TXT options are not.
        assertEquals(true, applied.invert)
        assertEquals(StatusItem.NONE, applied.footerCenter)
        assertEquals(StatusItem.EPISODE, applied.footerLeft)
        assertEquals(ParseOptions.BLANK_KEEP, applied.txtBlankLines)
        assertEquals(look.copy(invert = true, footerCenter = StatusItem.NONE, footerLeft = StatusItem.EPISODE, txtBlankLines = ParseOptions.BLANK_KEEP), applied)
        assertFalse(u.matches(other))
        assertFalse(u.matches(applied.copy(marginTopDp = 21)))
        assertTrue(u.matches(applied.copy(statusFontSizeSp = 13f)))
    }

    @Test
    fun presetLabelsAreTheNewNames() {
        assertEquals(listOf("웹소설", "전자책", "종이책"), StylePreset.entries.map { it.label })
        assertEquals(listOf("MARU", "RIDI", "BOOK"), StylePreset.entries.map { it.name })
        // Since 웹소설 is the MaruViewer page (2026-10-04) the defaults (the earlier 웹소설 on white) match no preset.
        assertFalse(StylePreset.entries.any { it.matches(ReaderSettings()) })
    }

    @Test
    fun jsonRoundTrip() {
        val list = listOf(UserStyle.from("밤 독서", look), UserStyle.from("낮", ReaderSettings()))
        assertEquals(list, UserStyles.fromJson(JSONArray(UserStyles.toJson(list).toString())))
        assertEquals(list, UserStyles.parse(UserStyles.toJson(list).toString()))
        assertEquals("MARU", UserStyles.toJson(list[0]).getString("pageTheme"))
    }

    @Test
    fun aStyleSavedBeforeTheThemesIsOnTheWhitePage() {
        val old = UserStyles.toJson(UserStyle.from("옛 스타일", look)).apply { remove("pageTheme") }
        val u = UserStyles.fromJson(old)!!
        assertEquals(PageTheme.PAPER, u.pageTheme)
        assertEquals(UserStyle.from("옛 스타일", look.copy(pageTheme = PageTheme.PAPER)), u)
        // A theme this build does not know reads as the white page too.
        assertEquals(PageTheme.PAPER, UserStyles.fromJson(old.put("pageTheme", "SEPIA"))!!.pageTheme)
    }

    @Test
    fun parseIsTolerant() {
        assertEquals(emptyList<UserStyle>(), UserStyles.parse(null))
        assertEquals(emptyList<UserStyle>(), UserStyles.parse(""))
        assertEquals(emptyList<UserStyle>(), UserStyles.parse("{\"name\":\"x\"}"))
        assertEquals(emptyList<UserStyle>(), UserStyles.parse("nonsense"))
        val a = JSONArray()
            .put(JSONObject().put("name", "  ")) // no usable name
            .put("string entry")
            .put(JSONObject().put("name", "최소")) // everything else defaulted
            .put(JSONObject().put("name", "최소")) // duplicate name dropped
            .put(JSONObject().put("name", "범위").put("fontSizeSp", 500).put("fontWeight", 5).put("align", "SIDEWAYS")
                .put("lineHeightPct", "wide").put("pageMargins", "yes").put("fontId", ""))
        val out = UserStyles.fromJson(a)
        assertEquals(listOf("최소", "범위"), out.map { it.name })
        assertEquals(UserStyle.from("최소", ReaderSettings()), out[0])
        val r = out[1]
        assertEquals(ReaderSettings.MAX_FONT_SP, r.fontSizeSp)
        assertEquals(100, r.fontWeight)
        assertEquals(ReaderSettings().align, r.align)
        assertEquals(ReaderSettings().lineHeightPct, r.lineHeightPct)
        assertEquals(ReaderSettings().pageMargins, r.pageMargins)
        assertEquals(ReaderSettings().fontId, r.fontId)
    }

    @Test
    fun atMostFiveComeBack() {
        val a = JSONArray()
        for (i in 1..8) a.put(UserStyles.toJson(UserStyle.from("s$i", ReaderSettings(fontSizeSp = 10f + i))))
        assertEquals((1..5).map { "s$it" }, UserStyles.fromJson(a).map { it.name })
        assertEquals(5, UserStyles.toJson((1..8).map { UserStyle.from("t$it", look) }).length())
    }

    @Test
    fun names() {
        assertEquals("내 스타일 1", UserStyles.defaultName(emptyList()))
        val two = listOf(UserStyle.from("내 스타일 1", look), UserStyle.from("내 스타일 3", look))
        assertEquals("내 스타일 2", UserStyles.defaultName(two))
        assertEquals("열두글자까지만보여요", UserStyles.cleanName("  열두글자까지만보여요 "))
        assertEquals(UserStyles.MAX_NAME, UserStyles.cleanName("가".repeat(20)).length)
        assertEquals("두 줄", UserStyles.cleanName("두\n줄"))
        // A cut never splits a surrogate pair.
        val emoji = "가".repeat(11) + "😀"
        assertEquals("가".repeat(11), UserStyles.cleanName(emoji))
        assertNull(UserStyles.fromJson(JSONObject().put("name", "\n")))
    }

    @Test fun oldAndDeliberateMarginMarkers() {
        val old=JSONObject().put("name","old").put("marginLeftDp",18).put("marginRightDp",18).put("marginTopDp",16).put("marginBottomDp",16)
        // R2's 16/16 = 40/40 from the edge, counted from the default bands of its time (a style has no status bar; 11 sp:
        // 22 / 18 dp): 18/22, which the 13 sp default settings take as 15/22, the same text box.
        val moved=UserStyles.fromJson(old)!!;assertEquals(20,moved.marginLeftDp);assertEquals(20,moved.marginRightDp)
        // The bottom then 12 dp less once (2026-10-06: its "0" moved from 22 to 10 dp).
        assertEquals(listOf(18,10),listOf(moved.marginTopDp,moved.marginBottomDp));assertTrue(moved.elevenSpBands)
        assertEquals(listOf(15,10),moved.applyTo(ReaderSettings()).let { listOf(it.marginTopDp,it.marginBottomDp) })
        old.put("marginBase",40).put("marginBaseV",40)
        // 16/16 chosen under U3, from the edge: inside the default bands (22 / 18 dp), so 0/0 now.
        val deliberate=UserStyles.fromJson(old)!!;assertEquals(18,deliberate.marginLeftDp)
        assertEquals(listOf(0,0),listOf(deliberate.marginTopDp,deliberate.marginBottomDp))
        assertEquals(listOf(0,0),deliberate.applyTo(ReaderSettings()).let { listOf(it.marginTopDp,it.marginBottomDp) })
        assertEquals(20,UserStyles.toJson(deliberate).getInt("marginBase"))
        assertEquals(VerticalMargin.BANDS,UserStyles.toJson(deliberate).getInt("marginBaseV"))
        // Still a style of the 11 sp bands when written again; one saved by this build says it is not.
        assertFalse(UserStyles.toJson(deliberate).has(MaruSize.STYLE_KEY))
        // Saved by this build: read as they are.
        assertEquals(deliberate,UserStyles.fromJson(UserStyles.toJson(deliberate))!!)
        val tight=UserStyle.from("t", ReaderSettings(marginTopDp=4,marginBottomDp=6))
        assertTrue(UserStyles.toJson(tight).getBoolean(MaruSize.STYLE_KEY))
        assertEquals(tight,UserStyles.fromJson(UserStyles.toJson(tight))!!)
    }

    @Test fun aStyleSavedFromTheElevenSpBandsKeepsItsTextBox() {
        // Saved from the bands before MaruViewer's status size (marker 2, no "statusSizeV"): its margins count from the
        // 11 sp bands and are kept as saved. Applied at the 13 sp default (where the prefs moved once), the margins lose
        // what those bands grew, as the prefs' did: the header 22 → 25 dp, so the default 18/22 of that build is today's
        // 15/22, the same text box; footer items 36 → 39 dp, so their bottom gives 3 dp back too.
        fun tb(s: ReaderSettings) = listOf(s.marginTopDp, s.marginBottomDp)
        val d=ReaderSettings()
        val before=JSONObject().put("name","b").put("marginBase",20).put("marginBaseV",VerticalMargin.BANDS_V1)
            .put("marginLeftDp",20).put("marginRightDp",20).put("marginTopDp",18).put("marginBottomDp",22)
        val moved=UserStyles.fromJson(before)!!
        // Its bottom also comes 12 dp closer once (the 22 dp "0" of marker 2 became 10 dp on 2026-10-06).
        assertTrue(moved.elevenSpBands);assertEquals(listOf(18,10),listOf(moved.marginTopDp,moved.marginBottomDp))
        assertEquals(listOf(15,10),tb(moved.applyTo(d)));assertTrue(moved.matches(d))
        assertEquals(VerticalMargin.EDGE_DP,StatusBands.headerDp(d)+moved.applyTo(d).marginTopDp)
        assertEquals(listOf(15,7),tb(moved.applyTo(d.withSlot(1,1,StatusItem.PAGE))))
        // At any other size the bands are those the style was saved with: its margins as they are (a size the user
        // chose; 11 sp kept with 여백 사용 off, MaruSize.applyTo, or restored from a backup).
        assertEquals(listOf(18,10),tb(moved.applyTo(d.copy(statusFontSizeSp=MaruSize.OLD_SP))))
        assertEquals(listOf(18,10),tb(moved.applyTo(d.copy(statusFontSizeSp=12f))))
        assertTrue(moved.matches(d.copy(statusFontSizeSp=12f,marginTopDp=18)))
        // Other values; a top smaller than the growth stops at 0.
        assertEquals(listOf(27,18),tb(UserStyles.fromJson(JSONObject(before.toString()).put("marginTopDp",30).put("marginBottomDp",30))!!.applyTo(d)))
        assertEquals(0,UserStyles.fromJson(JSONObject(before.toString()).put("marginTopDp",2))!!.applyTo(d).marginTopDp)
        // Written again by this build (another style saved, a rename, a backup) it stays one of the 11 sp bands: nothing
        // moves twice. Saved again from the page it made, it is one of this build's.
        assertEquals(moved,UserStyles.fromJson(UserStyles.toJson(moved))!!)
        assertEquals(moved.copy(name="c"),UserStyles.fromJson(UserStyles.toJson(moved.copy(name="c")))!!)
        val again=UserStyle.from("b",moved.applyTo(d));assertFalse(again.elevenSpBands);assertEquals(listOf(15,10),tb(again.applyTo(d)))
        // No top or bottom saved at all: today's defaults, nothing to convert.
        assertFalse(UserStyles.fromJson(JSONObject().put("name","n"))!!.elevenSpBands)
    }

    @Test fun aStyleSavedWithTheFortyDpDefaultGetsMaruViewersSides() {
        // Saved by an R3 build ("marginBase" 40) with the untouched 40 dp sides: MaruViewer's 20 dp now. Top and bottom
        // (40/40 from the edge) are counted from the default bands of their time (11 sp), which the 13 sp default
        // settings take as the new defaults: the same text box.
        val r3=JSONObject().put("name","r3").put("marginBase",40).put("marginBaseV",40)
            .put("marginLeftDp",40).put("marginRightDp",40).put("marginTopDp",40).put("marginBottomDp",40)
        val moved=UserStyles.fromJson(r3)!!
        assertEquals(listOf(20,20,18,10),listOf(moved.marginLeftDp,moved.marginRightDp,moved.marginTopDp,moved.marginBottomDp))
        assertEquals(listOf(15,10),moved.applyTo(ReaderSettings()).let { listOf(it.marginTopDp,it.marginBottomDp) })
        // A side margin the user changed stays, as do unequal sides.
        assertEquals(30,UserStyles.fromJson(JSONObject(r3.toString()).put("marginLeftDp",30).put("marginRightDp",30))!!.marginLeftDp)
        assertEquals(40,UserStyles.fromJson(JSONObject(r3.toString()).put("marginRightDp",36))!!.marginLeftDp)
        // Saved by this build: 40/40 is a choice and round-trips.
        val chosen=UserStyle.from("40", ReaderSettings(marginLeftDp=40,marginRightDp=40))
        assertEquals(chosen,UserStyles.fromJson(UserStyles.toJson(chosen))!!)
        assertEquals(chosen,UserStyles.parse(UserStyles.toJson(listOf(chosen)).toString()).single())
    }

}
