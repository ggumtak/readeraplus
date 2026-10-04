package com.ggumtak.readeraplus.reader.extras

import com.ggumtak.readeraplus.data.TxtOverride
import com.ggumtak.readeraplus.engine.Align
import com.ggumtak.readeraplus.format.ParseOptions
import com.ggumtak.readeraplus.reader.withTxt
import com.ggumtak.readeraplus.settings.PageTheme
import com.ggumtak.readeraplus.settings.ReaderSettings
import com.ggumtak.readeraplus.settings.StylePreset
import com.ggumtak.readeraplus.settings.UserStyle
import com.ggumtak.readeraplus.settings.UserStyles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The reading-settings popup's R2 state: saved styles (T1-8), per-book TXT options (T1-9), particles, TTS choices. */
class PopupStateTest {

    // ------------------------------------------------------------------ 내 스타일 (T1-8)

    private fun style(name: String, s: ReaderSettings) = UserStyle.from(name, s)

    @Test
    fun userStyleButtonShowsTheMatchingStyle() {
        val night = ReaderSettings(fontSizeSp = 26f, lineHeightPct = 220, marginLeftDp = 30, marginRightDp = 30)
        val maru = StylePreset.MARU.applyTo(ReaderSettings())
        val list = listOf(style("밤", night), style("TXT", maru))
        assertEquals("밤", StyleChoice.selectedUser(night, list)?.name)
        assertEquals("밤", StyleChoice.userLabel(StyleChoice.selectedUser(night, list)))
        // Not the typography: status bar / invert / TXT options don't matter.
        assertEquals("밤", StyleChoice.selectedUser(night.copy(invert = true, txtStripIndent = false), list)?.name)
        // One step away from every style: the button reads "내 스타일".
        val off = night.copy(fontSizeSp = 26.5f)
        assertNull(StyleChoice.selectedUser(off, list))
        assertEquals("내 스타일", StyleChoice.userLabel(null))
        // A preset and a saved style may both match.
        assertEquals(StylePreset.MARU, StyleChoice.selected(maru))
        assertEquals("TXT", StyleChoice.selectedUser(maru, list)?.name)
        // A saved style carries its 화면 색: the same typography on another page colour is not that style.
        assertNull(StyleChoice.selectedUser(maru.copy(pageTheme = PageTheme.PAPER), list))
        assertEquals(PageTheme.MARU, list[1].applyTo(ReaderSettings()).pageTheme)
    }

    @Test
    fun savingKeepsFiveUniqueNames() {
        var list = emptyList<UserStyle>()
        for (i in 1..UserStyles.MAX) {
            val name = UserStyles.defaultName(list)
            assertTrue(StyleChoice.canSave(list, name))
            list = StyleChoice.put(list, style(name, ReaderSettings(fontSizeSp = 10f + i)))
        }
        assertEquals((1..5).map { "내 스타일 $it" }, list.map { it.name })
        // Full: a new name can't be saved, the same name replaces in place.
        assertFalse(StyleChoice.canSave(list, "새 이름"))
        assertTrue(StyleChoice.canSave(list, "내 스타일 3"))
        val replaced = StyleChoice.put(list, style("내 스타일 3", ReaderSettings(fontSizeSp = 40f)))
        assertEquals(5, replaced.size)
        assertEquals(40f, replaced[2].fontSizeSp)
        assertEquals("내 스타일 3", replaced[2].name)
        // Never more than MAX even when asked.
        assertEquals(5, StyleChoice.put(list, style("여섯", ReaderSettings())).size)
    }

    @Test
    fun renameAndRemove() {
        val list = listOf(style("가", ReaderSettings()), style("나", ReaderSettings(fontSizeSp = 30f)))
        assertEquals(listOf("다", "나"), StyleChoice.rename(list, "가", "  다 ")?.map { it.name })
        assertNull(StyleChoice.rename(list, "가", "나"))
        assertNull(StyleChoice.rename(list, "가", "   "))
        // Same name again is fine (nothing changes).
        assertEquals(listOf("가", "나"), StyleChoice.rename(list, "가", "가")?.map { it.name })
        // Cut to 12 chars like every saved name.
        assertEquals("열두글자를넘는아주긴이름", StyleChoice.rename(list, "가", "열두글자를넘는아주긴이름입니다")!!.first().name)
        assertEquals(listOf("나"), StyleChoice.remove(list, "가").map { it.name })
        assertEquals(list, StyleChoice.remove(list, "없음"))
    }

    // ------------------------------------------------------------------ TXT 파일 · 이 책에만 적용 (T1-9)

    private val global = ReaderSettings(
        fontSizeSp = 22f, align = Align.JUSTIFY, txtBlankLines = ParseOptions.BLANK_AUTO, txtStripIndent = true,
        txtJoinWrappedLines = 1, txtDetectChapters = true, txtChapterRegex = "", txtEmphasizeHeadings = true,
        txtReplaceRules = "광고 =>",
    )

    @Test
    fun overrideHoldsOnlyWhatDiffersFromTheDefaults() {
        assertNull(TxtEdits.overrideFor(global, global))
        // Typography is never part of it.
        assertNull(TxtEdits.overrideFor(global, global.copy(fontSizeSp = 30f, invert = true)))
        val eff = global.copy(txtStripIndent = false, txtReplaceRules = "광고 =>\n^.*공지.*$ =>")
        val o = TxtEdits.overrideFor(global, eff)!!
        assertEquals(TxtOverride(stripIndent = false, replaceRules = "광고 =>\n^.*공지.*$ =>"), o)
        // What the popup shows is exactly what the book then reads with.
        assertTrue(TxtEdits.sameTxt(eff, global.withTxt(o)))
        // Back to the default value: that option follows the defaults again.
        assertEquals(TxtOverride(replaceRules = "광고 =>\n^.*공지.*$ =>"), TxtEdits.overrideFor(global, eff.copy(txtStripIndent = true)))
    }

    @Test
    fun everyTxtOptionRoundTripsThroughTheOverride() {
        val eff = global.copy(
            txtBlankLines = ParseOptions.BLANK_KEEP, txtStripIndent = false, txtJoinWrappedLines = 0, txtDetectChapters = false,
            txtChapterRegex = "^제\\d+화", txtEmphasizeHeadings = false, txtReplaceRules = "",
        )
        val o = TxtEdits.overrideFor(global, eff)!!
        assertEquals(eff, global.withTxt(o).copy(fontSizeSp = eff.fontSizeSp))
        // "" rules for this book differ from the default rules: kept as "" (not null).
        assertEquals("", o.replaceRules)
    }

    @Test
    fun withTxtFromCopiesOnlyTheTxtOptions() {
        val typed = ReaderSettings(fontSizeSp = 30f, invert = true)
        val merged = TxtEdits.withTxtFrom(typed, global)
        assertEquals(30f, merged.fontSizeSp)
        assertTrue(merged.invert)
        assertTrue(TxtEdits.sameTxt(merged, global))
        // Nothing to copy: the same object.
        assertSame(global, TxtEdits.withTxtFrom(global, global.copy(fontSizeSp = 11f)))
        // "기본값 복원" keeps the TXT options.
        assertTrue(TxtEdits.sameTxt(TxtEdits.withTxtFrom(ReaderSettings(), global), global))
    }

    @Test
    fun appendRuleAddsOneLineOnce() {
        assertEquals("a =>", TxtEdits.appendRule("", "a =>"))
        assertEquals("a =>", TxtEdits.appendRule("  \n", "a =>"))
        assertEquals("x => y\na =>", TxtEdits.appendRule("x => y\n\n", "a =>"))
        assertEquals("x => y\na =>", TxtEdits.appendRule("x => y\na =>", "a =>"))
    }

    // ------------------------------------------------------------------ particles

    @Test
    fun particlesFollowTheLastSound() {
        assertEquals("가", Josa.iGa("광고"))
        assertEquals("이", Josa.iGa("공지"+"문"))
        assertEquals("이", Josa.iGa("책"))
        assertEquals("가", Josa.iGa("무단 전재 금지 ."))
        assertEquals("이", Josa.iGa("무단 전재 금지 문장!"))
        assertEquals("이", Josa.iGa("1"))
        assertEquals("가", Josa.iGa("2"))
        assertEquals("이(가)", Josa.iGa("ABC"))
        assertEquals("이(가)", Josa.iGa("…"))
        assertEquals("을", Josa.eulReul("책"))
        assertEquals("를", Josa.eulReul("스타일"+"러"))
    }

    // ------------------------------------------------------------------ TTS voices and sleep timer (A13, T1-11)

    private fun v(name: String, lang: String, language: String, q: Int = 400, net: Boolean = false, missing: Boolean = false) =
        VoiceChoice.Info(name, lang, language, q, net, missing)

    @Test
    fun koreanVoicesFirstWithReadableNames() {
        val voices = listOf(
            v("en-us-x-sfg-local", "en", "영어"),
            v("ko-kr-x-kod-network", "ko", "한국어", q = 400, net = true),
            v("ko-kr-x-koc-local", "ko", "한국어", q = 300),
            v("ja-jp-x-htm-local", "ja", "일본어"),
            v("ko-kr-x-ism-local", "ko", "한국어", q = 500, missing = true),
        )
        val ko = VoiceChoice.list(voices, bookLang = "ko")
        assertEquals(listOf("ko-kr-x-ism-local", "ko-kr-x-koc-local", "ko-kr-x-kod-network"), ko.map { it.first.name })
        assertEquals("한국어 · 목소리 1 (고음질, 오프라인, 설치 필요)", ko[0].second)
        assertEquals("한국어 · 목소리 2 (보통 음질, 오프라인)", ko[1].second)
        assertEquals("한국어 · 목소리 3 (고음질, 온라인)", ko[2].second)
        // An English book: Korean first, then English (numbered per language).
        val en = VoiceChoice.list(voices, bookLang = "EN")
        assertEquals(listOf("ko", "ko", "ko", "en"), en.map { it.first.lang })
        assertEquals("영어 · 목소리 1 (고음질, 오프라인)", en[3].second)
        // No Korean or book voice at all: every voice.
        assertEquals(2, VoiceChoice.list(voices.filter { it.lang != "ko" }, bookLang = "de").size)
        assertEquals("저음질", VoiceChoice.quality(200))
    }

    @Test
    fun sleepTimerChoices() {
        assertEquals(listOf("끔", "15분", "30분", "45분", "1시간", "1시간 30분", "이 화 끝까지", "2화 끝까지"), SleepChoice.OPTIONS.map { it.label })
        assertEquals(0, SleepChoice.indexOf(0, 0))
        assertEquals(2, SleepChoice.indexOf(30, 0))
        // Episodes win over minutes.
        assertEquals(6, SleepChoice.indexOf(30, 1))
        assertEquals(7, SleepChoice.indexOf(0, 2))
        // An older build's 10 / 120 minutes: no option checked.
        assertEquals(-1, SleepChoice.indexOf(10, 0))
        assertEquals("30분", SleepChoice.summary(30, 0))
        assertEquals("이 화 끝까지", SleepChoice.summary(30, 1))
        assertEquals("2화 끝까지", SleepChoice.summary(0, 2))
        assertEquals("끔", SleepChoice.summary(0, 0))
    }

    @Test
    fun sleepNoteOnTheControlBar() {
        assertEquals("", SleepChoice.barNote(0L, 0))
        assertEquals("1분 후 멈춤", SleepChoice.barNote(1L, 0))
        assertEquals("1분 후 멈춤", SleepChoice.barNote(60_000L, 0))
        assertEquals("2분 후 멈춤", SleepChoice.barNote(60_001L, 0))
        assertEquals("30분 후 멈춤", SleepChoice.barNote(30 * 60_000L, 0))
        assertEquals("이 화 끝나면 멈춤", SleepChoice.barNote(0L, 1))
        assertEquals("다음 화 끝나면 멈춤", SleepChoice.barNote(0L, 2))
    }
}
