package com.ggumtak.readeraplus.reader.extras

import com.ggumtak.readeraplus.format.DocPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FormatTest {

    @Test
    fun fileSizes() {
        assertEquals("0 B", Fmt.fileSize(0))
        assertEquals("532 B", Fmt.fileSize(532))
        assertEquals("1.00 KB", Fmt.fileSize(1024))
        assertEquals("812 KB", Fmt.fileSize(812L * 1024))
        assertEquals("15.3 MB", Fmt.fileSize((15.3 * 1024 * 1024).toLong()))
        assertEquals("1.05 GB", Fmt.fileSize((1.05 * 1024 * 1024 * 1024).toLong()))
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
        assertEquals("1em", Fmt.em(100))
        assertEquals("1.25em", Fmt.em(125))
        assertEquals("0.5em", Fmt.em(50))
        assertEquals("0.05em", Fmt.em(5))
        assertEquals("기본", Fmt.letterSpacing(0))
        assertEquals("+2%", Fmt.letterSpacing(20))
        assertEquals("-1%", Fmt.letterSpacing(-10))
        assertEquals("+1.5%", Fmt.letterSpacing(15))
        assertEquals("400", Fmt.weight(400))
        assertEquals("700", Fmt.weight(700))
        assertEquals("450", Fmt.weight(450))
        assertEquals("1.0x", Fmt.rate(1f))
        assertEquals("1.3x", Fmt.rate(1.3f))
        assertEquals("끔", Fmt.minutes(0))
        assertEquals("30분", Fmt.minutes(30))
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
        assertEquals("없음", Fmt.rulesLabel(""))
        assertEquals("없음", Fmt.rulesLabel("# 주석만\n\n"))
        val rules = "# 광고 줄 지우기\n^\\s*광고.*$ => \n(\\S)\\.{3} => $1…\n잘못된[ => x\n화살표 없음\n => 빈 패턴"
        assertEquals("5개 규칙", Fmt.rulesLabel(rules))
        assertEquals(3, Fmt.invalidRuleCount(rules))
        assertEquals(0, Fmt.invalidRuleCount("a+ => b\n# c[ => d"))
    }

    @Test
    fun dates() {
        assertEquals("-", Fmt.dateTime(0))
        assertTrue(Fmt.dateTime(1_700_000_000_000L).matches(Regex("\\d{4}\\.\\d{2}\\.\\d{2} \\d{2}:\\d{2}")))
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
