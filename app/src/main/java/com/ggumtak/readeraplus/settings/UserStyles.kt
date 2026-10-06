package com.ggumtak.readeraplus.settings

import com.ggumtak.readeraplus.engine.Align
import com.ggumtak.readeraplus.engine.LineBreakMode
import org.json.JSONArray
import org.json.JSONObject

/**
 * A saved style ("내 스타일", T1-8): the typography of [ReaderSettings] plus the page margins and the 화면 색
 * ([pageTheme]). It never carries the status bar, invert or TXT / EPUB options, so applying one changes only how the
 * page looks (one re-layout, like a built-in [StylePreset]). Immutable; the list is stored by
 * [Settings.saveUserStyles].
 */
data class UserStyle(
    /** Shown on the style button and in the chooser; at most [UserStyles.MAX_NAME] chars. */
    val name: String,
    val fontId: String,
    val fontSizeSp: Float,
    val fontWeight: Int,
    val lineHeightPct: Int,
    val paragraphSpacingPct: Int,
    val indentPct: Int,
    val letterSpacingPm: Int,
    val align: Align,
    val lineBreak: LineBreakMode,
    val marginLeftDp: Int,
    val marginRightDp: Int,
    val marginTopDp: Int,
    val marginBottomDp: Int,
    val pageMargins: Boolean,
    /** 화면 색. A style saved before the themes has none and reads [PageTheme.PAPER], the page it was saved on. */
    val pageTheme: PageTheme = PageTheme.PAPER,
    /**
     * Saved before MaruViewer's status size (no [MaruSize.STYLE_KEY]): [marginTopDp] / [marginBottomDp] count from the
     * 11 sp bands. [applyTo] takes the 13 sp bands' growth off them only on settings at that default size (as the prefs'
     * one-time move did), never at another size (one the user chose, an 11 sp kept with 여백 사용 off or restored from a
     * backup): the text box stays where the style put it before. Kept when written again, so nothing moves twice.
     */
    val elevenSpBands: Boolean = false,
) {
    /** [s] with this style's fields; everything else of [s] is kept. */
    fun applyTo(s: ReaderSettings): ReaderSettings {
        val out = fields(s)
        return if (elevenSpBands && out.statusFontSizeSp == StatusBands.DEFAULT_SP)
            MaruSize.keepBox(out.copy(statusFontSizeSp = MaruSize.OLD_SP), out) else out
    }

    private fun fields(s: ReaderSettings): ReaderSettings = s.copy(
        fontId = fontId,
        fontSizeSp = fontSizeSp,
        fontWeight = fontWeight,
        lineHeightPct = lineHeightPct,
        paragraphSpacingPct = paragraphSpacingPct,
        indentPct = indentPct,
        letterSpacingPm = letterSpacingPm,
        align = align,
        lineBreak = lineBreak,
        marginLeftDp = marginLeftDp,
        marginRightDp = marginRightDp,
        marginTopDp = marginTopDp,
        marginBottomDp = marginBottomDp,
        pageMargins = pageMargins,
        pageTheme = pageTheme,
    )

    /** True when [s] currently looks exactly like this style (the style button then shows its name, inverted). */
    fun matches(s: ReaderSettings): Boolean = applyTo(s) == s

    companion object {
        /** The style fields of [s], saved under [name]. */
        fun from(name: String, s: ReaderSettings): UserStyle = UserStyle(
            name = name,
            fontId = s.fontId,
            fontSizeSp = s.fontSizeSp,
            fontWeight = s.fontWeight,
            lineHeightPct = s.lineHeightPct,
            paragraphSpacingPct = s.paragraphSpacingPct,
            indentPct = s.indentPct,
            letterSpacingPm = s.letterSpacingPm,
            align = s.align,
            lineBreak = s.lineBreak,
            marginLeftDp = s.marginLeftDp,
            marginRightDp = s.marginRightDp,
            marginTopDp = s.marginTopDp,
            marginBottomDp = s.marginBottomDp,
            pageMargins = s.pageMargins,
            pageTheme = s.pageTheme,
        )
    }
}

/**
 * Limits and the JSON form of the saved styles (pure, org.json). The same codec serves the prefs value
 * (`a.userStyles`, see [Settings.userStyles]) and the backup file (`SettingsJson`). Decoding is tolerant: a
 * malformed entry is skipped, values are clamped to the ranges the settings screens allow, missing fields take
 * the [ReaderSettings] defaults, and never more than [MAX] styles come back.
 */
object UserStyles {
    /** At most this many saved styles. */
    const val MAX = 5

    /** Longest style name (chars). */
    const val MAX_NAME = 12

    /** "내 스타일 N" with the smallest N ≥ 1 not used by [existing]. */
    fun defaultName(existing: List<UserStyle>): String {
        val used = existing.mapTo(HashSet()) { it.name }
        var n = 1
        while ("내 스타일 $n" in used) n++
        return "내 스타일 $n"
    }

    /** Trimmed, line breaks as spaces, cut to [MAX_NAME] chars (surrogate-safe); "" stays "". */
    fun cleanName(name: String): String {
        val t = name.replace('\n', ' ').replace('\r', ' ').trim()
        if (t.length <= MAX_NAME) return t
        var end = MAX_NAME
        if (Character.isHighSurrogate(t[end - 1])) end--
        return t.substring(0, end).trim()
    }

    fun toJson(list: List<UserStyle>): JSONArray {
        val a = JSONArray()
        for (u in list.take(MAX)) a.put(toJson(u))
        return a
    }

    fun toJson(u: UserStyle): JSONObject = JSONObject()
        .put(SideMargin.STYLE_KEY, SideMargin.ZERO_DP)
        .put(VerticalMargin.STYLE_KEY, VerticalMargin.BANDS)
        .apply { if (!u.elevenSpBands) put(MaruSize.STYLE_KEY, true) }
        .put("name", u.name)
        .put("fontId", u.fontId)
        .put("fontSizeSp", u.fontSizeSp.toDouble())
        .put("fontWeight", u.fontWeight)
        .put("lineHeightPct", u.lineHeightPct)
        .put("paragraphSpacingPct", u.paragraphSpacingPct)
        .put("indentPct", u.indentPct)
        .put("letterSpacingPm", u.letterSpacingPm)
        .put("align", u.align.name)
        .put("lineBreak", u.lineBreak.name)
        .put("marginLeftDp", u.marginLeftDp)
        .put("marginRightDp", u.marginRightDp)
        .put("marginTopDp", u.marginTopDp)
        .put("marginBottomDp", u.marginBottomDp)
        .put("pageMargins", u.pageMargins)
        .put("pageTheme", u.pageTheme.name)

    /** Styles from the prefs string; empty for null / blank / malformed text. */
    fun parse(text: String?): List<UserStyle> {
        if (text.isNullOrBlank()) return emptyList()
        val a = try {
            JSONArray(text)
        } catch (_: Exception) {
            return emptyList()
        }
        return fromJson(a)
    }

    /** Styles from a JSON array (unnamed or non-object entries skipped, duplicates by name dropped, ≤ [MAX]). */
    fun fromJson(a: JSONArray): List<UserStyle> {
        val out = ArrayList<UserStyle>(minOf(a.length(), MAX))
        val names = HashSet<String>()
        for (i in 0 until a.length()) {
            if (out.size >= MAX) break
            val o = a.optJSONObject(i) ?: continue
            val u = fromJson(o) ?: continue
            if (names.add(u.name)) out += u
        }
        return out
    }

    /** One style, or null when it has no usable name. */
    fun fromJson(o: JSONObject): UserStyle? {
        val name = cleanName(str(o, "name", ""))
        if (name.isEmpty()) return null
        val d = ReaderSettings()
        val sideBase = if (o.has(SideMargin.STYLE_KEY)) int(o, SideMargin.STYLE_KEY, -1) else null
        val sideLegacy = SideMargin.isLegacyDefault(sideBase, int(o, "marginLeftDp", d.marginLeftDp), int(o, "marginRightDp", d.marginRightDp))
        val verticalBase = if (o.has(VerticalMargin.STYLE_KEY)) int(o, VerticalMargin.STYLE_KEY, -1) else null
        val verticalLegacy = VerticalMargin.isLegacyDefault(verticalBase != null, int(o, "marginTopDp", d.marginTopDp), int(o, "marginBottomDp", d.marginBottomDp))
        // A style saved before MaruViewer's status size (no MaruSize.STYLE_KEY) counts from the 11 sp default bands, as
        // it did then; UserStyle.applyTo takes the 13 sp bands' growth off at the size it is applied with. One with no
        // top/bottom at all has nothing to convert: today's defaults. Top/bottom saved from the screen's edge: a style
        // carries no status bar, so they are counted from those default bands (the user's own on both devices:
        // MaruViewer's header, the progress line), where the text box stays put.
        val elevenSp = !o.has(MaruSize.STYLE_KEY) && (o.has("marginTopDp") || o.has("marginBottomDp"))
        val bands = if (elevenSp) d.copy(statusFontSizeSp = MaruSize.OLD_SP) else d
        val edge = VerticalMargin.countsFromEdge(verticalBase)
        fun top(dp: Int): Int = if (edge && o.has("marginTopDp")) VerticalMargin.topFromEdge(dp, bands) else dp
        // Saved before the bottom's "0" moved from 22 to 10 dp (2026-10-06): 12 dp less, as in Settings.
        val shift = VerticalMargin.needsBottomShift(verticalBase)
        fun bottom(dp: Int): Int {
            val counted = if (edge && o.has("marginBottomDp")) VerticalMargin.bottomFromEdge(dp, bands) else dp
            return if (shift && o.has("marginBottomDp")) VerticalMargin.shiftBottom(counted) else counted
        }
        // A missing top/bottom: the defaults on those bands (the top 40 dp from the edge, the bottom 10 dp over its band).
        val topDefault = VerticalMargin.EDGE_DP - StatusBands.headerDp(bands)
        val bottomDefault = VerticalMargin.BOTTOM_ZERO_DP
        return UserStyle(
            name = name,
            fontId = str(o, "fontId", d.fontId).trim().ifEmpty { d.fontId },
            fontSizeSp = float(o, "fontSizeSp", d.fontSizeSp).coerceIn(ReaderSettings.MIN_FONT_SP, ReaderSettings.MAX_FONT_SP),
            fontWeight = int(o, "fontWeight", d.fontWeight).coerceIn(100, 900),
            lineHeightPct = int(o, "lineHeightPct", d.lineHeightPct).coerceIn(50, 500),
            paragraphSpacingPct = int(o, "paragraphSpacingPct", d.paragraphSpacingPct).coerceIn(0, 1000),
            indentPct = int(o, "indentPct", d.indentPct).coerceIn(0, 1000),
            letterSpacingPm = int(o, "letterSpacingPm", d.letterSpacingPm).coerceIn(-500, 1000),
            align = Align.entries.firstOrNull { it.name == o.optString("align") } ?: d.align,
            lineBreak = LineBreakMode.entries.firstOrNull { it.name == o.optString("lineBreak") } ?: d.lineBreak,
            marginLeftDp = if (sideLegacy) SideMargin.ZERO_DP else int(o, "marginLeftDp", d.marginLeftDp).coerceIn(0, 300),
            marginRightDp = if (sideLegacy) SideMargin.ZERO_DP else int(o, "marginRightDp", d.marginRightDp).coerceIn(0, 300),
            marginTopDp = top(if (verticalLegacy) VerticalMargin.EDGE_DP else int(o, "marginTopDp", topDefault).coerceIn(0, 300)),
            marginBottomDp = bottom(if (verticalLegacy) VerticalMargin.EDGE_DP else int(o, "marginBottomDp", bottomDefault).coerceIn(0, 300)),
            pageMargins = (o.opt("pageMargins") as? Boolean) ?: d.pageMargins,
            pageTheme = PageTheme.entries.firstOrNull { it.name == o.optString("pageTheme") } ?: d.pageTheme,
            elevenSpBands = elevenSp,
        )
    }

    private fun str(o: JSONObject, key: String, def: String): String = (o.opt(key) as? String) ?: def

    private fun int(o: JSONObject, key: String, def: Int): Int {
        val n = o.opt(key) as? Number ?: return def
        val d = n.toDouble()
        return if (d.isFinite()) d.toLong().coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt() else def
    }

    private fun float(o: JSONObject, key: String, def: Float): Float {
        val f = (o.opt(key) as? Number)?.toFloat() ?: return def
        return if (f.isFinite()) f else def
    }
}
