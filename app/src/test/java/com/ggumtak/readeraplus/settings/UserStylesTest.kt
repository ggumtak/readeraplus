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
        val moved=UserStyles.fromJson(old)!!;assertEquals(40,moved.marginLeftDp);assertEquals(40,moved.marginTopDp)
        old.put("marginBase",40).put("marginBaseV",40)
        val deliberate=UserStyles.fromJson(old)!!;assertEquals(18,deliberate.marginLeftDp);assertEquals(16,deliberate.marginTopDp)
        assertEquals(40,UserStyles.toJson(deliberate).getInt("marginBaseV"))
    }

}
