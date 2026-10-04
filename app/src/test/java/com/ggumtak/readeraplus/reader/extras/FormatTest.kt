package com.ggumtak.readeraplus.reader.extras

import com.ggumtak.readeraplus.format.DocPosition
import com.ggumtak.readeraplus.ui.settings.SettingsFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FormatTest {

    @Test
    fun fileSizes() {
        // 책 정보 says a size as the library card does (one wording in the app, 2026-10-04).
        assertEquals("0B", Fmt.fileSize(0))
        assertEquals("532B", Fmt.fileSize(532))
        assertEquals("1KB", Fmt.fileSize(1024))
        assertEquals("812KB", Fmt.fileSize(812L * 1024))
        assertEquals("3.4MB", Fmt.fileSize((3.4 * 1024 * 1024).toLong()))
        assertEquals("15MB", Fmt.fileSize((15.3 * 1024 * 1024).toLong()))
        for (n in longArrayOf(0, 532, 1024, 3_565_158, 16_043_212, 1L shl 31)) {
            assertEquals(com.ggumtak.readeraplus.ui.library.LibraryText.formatSize(n), Fmt.fileSize(n))
        }
    }

    @Test
    fun durations() {
        assertEquals("0분", Fmt.duration(0))
        assertEquals("1분 미만", Fmt.duration(30))
        assertEquals("45분", Fmt.duration(45 * 60 + 10))
        assertEquals("3시간 12분", Fmt.duration(3 * 3600 + 12 * 60))
        assertEquals("1시간 0분", Fmt.duration(3600))
    }

    @Test
    fun percents() {
        assertEquals("0%", Fmt.percent(0f))
        assertEquals("34%", Fmt.percent(0.34f))
        assertEquals("0.4%", Fmt.percent(0.004f))
        assertEquals("34.5%", Fmt.percent(0.345f))
        assertEquals("100%", Fmt.percent(1f))
        assertEquals("100%", Fmt.percent(1.5f))
    }

    @Test
    fun numbersAndUnits() {
        assertEquals("20", Fmt.number(20f))
        assertEquals("20.5", Fmt.number(20.5f))
        assertEquals("20.5", Fmt.number(20.499998f))
        assertEquals("170%", Fmt.pct(170))
        assertEquals("없음", Fmt.em(0))
        assertEquals("1자", Fmt.em(100))
        assertEquals("1.25자", Fmt.em(125))
        assertEquals("0.5자", Fmt.em(50))
        assertEquals("0.05자", Fmt.em(5))
        assertEquals("기본", Fmt.letterSpacing(0))
        assertEquals("+2%", Fmt.letterSpacing(20))
        assertEquals("\u22121%", Fmt.letterSpacing(-10))
        assertEquals("+1.5%", Fmt.letterSpacing(15))
        assertEquals("400", Fmt.weight(400))
        assertEquals("700", Fmt.weight(700))
        assertEquals("450", Fmt.weight(450))
        assertEquals("1.0x", Fmt.rate(1f))
        assertEquals("1.3x", Fmt.rate(1.3f))
        assertEquals("끔", Fmt.minutes(0))
        assertEquals("30분", Fmt.minutes(30))
        // The settings page's wording (SettingsFormat.sleep).
        assertEquals("1시간", Fmt.minutes(60))
        assertEquals("1시간 30분", Fmt.minutes(90))
    }

    @Test
    fun stepping() {
        assertEquals(175, Fmt.stepInt(173, 5, 100, 300))
        assertEquals(300, Fmt.stepInt(305, 5, 100, 300))
        assertEquals(100, Fmt.stepInt(20, 5, 100, 300))
        assertEquals(20.5f, Fmt.stepFloat(20.4f, 0.5f, 8f, 60f), 0f)
        assertEquals(60f, Fmt.stepFloat(61f, 0.5f, 8f, 60f), 0f)
        assertEquals(1.1f, Fmt.stepFloat(1.0f + 0.1f, 0.1f, 0.5f, 3f), 0f)
        var v = 8f
        repeat(104) { v = Fmt.stepFloat(v + 0.5f, 0.5f, 8f, 60f) }
        assertEquals(60f, v, 0f)
    }

    @Test
    fun plainTextStripsTags() {
        assertEquals("첫 줄\n둘째 & 셋", Fmt.plainText("<p>첫   줄</p>\n<p>둘째 &amp; 셋</p>"))
    }

    @Test
    fun replaceRules() {
        assertEquals("없음 · 광고 문구 등 지우기", Fmt.rulesLabel(""))
        assertEquals("없음 · 광고 문구 등 지우기", Fmt.rulesLabel("# 주석만\n\n"))
        val rules = "# 광고 줄 지우기\n^\\s*광고.*$ => \n(\\S)\\.{3} => $1…\n잘못된[ => x\n화살표 없음\n => 빈 패턴"
        // The rules the parser applies (as RuleList.enabledCount): comments, "=> 빈 패턴" and the line without an arrow
        // are not rules; the invalid regex is counted (it is reported as invalid separately).
        assertEquals("3개 켜짐", Fmt.rulesLabel(rules))
        assertEquals("1개 켜짐", Fmt.rulesLabel("## 이름\na => b\n#- c => d"))
        assertEquals(3, Fmt.invalidRuleCount(rules))
        assertEquals(0, Fmt.invalidRuleCount("a+ => b\n# c[ => d"))
    }

    @Test
    fun dates() {
        assertEquals("-", Fmt.dateTime(0))
        assertEquals("-", Fmt.date(0))
        // The app's one wording (style guide 9): never "2026.09.30".
        val now = System.currentTimeMillis()
        assertEquals(SettingsFormat.dateTime(now), Fmt.dateTime(now))
        assertEquals(SettingsFormat.date(now), Fmt.date(now))
        assertTrue(Fmt.date(now).matches(Regex("\\d{1,2}월 \\d{1,2}일")))
        assertTrue(Fmt.dateTime(1_700_000_000_000L).matches(Regex("2023년 11월 1[45]일 \\d{2}:\\d{2}")))
    }

    // ------------------------------------------------------------------ page labels

    @Test
    fun parsesPageLabels() {
        val a = PageLabel.parse("12 / 3259")
        assertEquals(12, a.page)
        assertEquals(3259, a.total)
        assertEquals(false, a.estimated)
        val b = PageLabel.parse("~12 / ~3,260")
        assertEquals(12, b.page)
        assertEquals(3260, b.total)
        assertEquals(true, b.estimated)
        val c = PageLabel.parse("7")
        assertEquals(7, c.page)
        assertEquals(-1, c.total)
        assertEquals(-1, PageLabel.parse("").page)
        assertEquals(-1, PageLabel.parse(null).page)
        assertEquals("12", PageLabel.pageOnly("12 / 3259"))
        // No "~" is ever shown: estimated page numbers are plain.
        assertEquals("12", PageLabel.pageOnly("~12 / ~3260"))
        assertEquals("12", PageLabel.pageOnly("12 / ~3260"))
        assertEquals("", PageLabel.pageOnly(null))
    }

    @Test
    fun fractionMapping() {
        val chars = intArrayOf(100, 300, 0, 600)
        assertEquals(DocPosition(0, 0), PageLabel.positionForFraction(chars, 0f))
        assertEquals(DocPosition(1, 0), PageLabel.positionForFraction(chars, 0.1f))
        assertEquals(DocPosition(1, 200), PageLabel.positionForFraction(chars, 0.3f))
        assertEquals(DocPosition(3, 100), PageLabel.positionForFraction(chars, 0.5f))
        assertEquals(DocPosition(3, 599), PageLabel.positionForFraction(chars, 1f))
        assertEquals(DocPosition(3, 599), PageLabel.positionForFraction(chars, 2f))
        assertEquals(DocPosition.START, PageLabel.positionForFraction(IntArray(0), 0.5f))
        assertEquals(0.3f, PageLabel.fractionOf(chars, DocPosition(1, 200)), 1e-6f)
        assertEquals(0.5f, PageLabel.fractionOf(chars, PageLabel.positionForFraction(chars, 0.5f)), 1e-3f)
        assertEquals(0f, PageLabel.fractionOf(IntArray(0), DocPosition(0, 5)), 0f)
    }

    @Test
    fun sectionForPage() {
        // section start pages: 1, 4, 4 (empty-ish), 10, 25
        val starts = intArrayOf(1, 4, 5, 10, 25)
        assertEquals(0, PageLabel.sectionForPage(starts.size, 1) { starts[it] })
        assertEquals(0, PageLabel.sectionForPage(starts.size, 3) { starts[it] })
        assertEquals(1, PageLabel.sectionForPage(starts.size, 4) { starts[it] })
        assertEquals(3, PageLabel.sectionForPage(starts.size, 24) { starts[it] })
        assertEquals(4, PageLabel.sectionForPage(starts.size, 99) { starts[it] })
        var calls = 0
        val many = IntArray(10_000) { it * 3 + 1 }
        assertEquals(5000, PageLabel.sectionForPage(many.size, 15_001) { calls++; many[it] })
        assertTrue(calls <= 15)
    }

    @Test
    fun approxOffsets() {
        assertEquals(0, PageLabel.approxOffset(0, 10, 1000))
        assertEquals(500, PageLabel.approxOffset(5, 10, 1000))
        assertEquals(900, PageLabel.approxOffset(20, 10, 1000))
        assertEquals(0, PageLabel.approxOffset(3, 1, 1000))
        assertEquals(0, PageLabel.approxOffset(3, 10, 0))
    }
}
