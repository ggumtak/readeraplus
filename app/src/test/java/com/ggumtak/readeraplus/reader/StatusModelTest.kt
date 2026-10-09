package com.ggumtak.readeraplus.reader

import com.ggumtak.readeraplus.AllocCounter
import com.ggumtak.readeraplus.render.StatusSlot
import com.ggumtak.readeraplus.settings.ReaderSettings
import com.ggumtak.readeraplus.settings.StatusItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusModelTest {
    private val track = 600

    private fun inputs() = StatusInputs().apply {
        page = 12; total = 3259; percent = 34; bar = 0.25f
        chapterTitle = "제3화 비밀"; bookTitle = "책 제목"
        chapterPage = 2; chapterPages = 32; minutesEpisode = 3; minutesBook = 440
        epNumbered = true; epNumber = 123; epMax = 540; tocIndex = 86; tocCount = 612
        minuteOfDay = 14 * 60 + 5; is24 = true; battery = 80
    }

    private fun chars(s: StatusSlot) = String(s.chars, 0, s.length)

    @Test
    fun onlyTheShownItemsAreFormatted() {
        val m = StatusModel()
        val s = ReaderSettings(headerLeft = StatusItem.NONE, headerCenter = StatusItem.CHAPTER, headerRight = StatusItem.NONE,
            footerLeft = StatusItem.PAGE, footerRight = StatusItem.BATTERY, progressBar = false)
        assertTrue(m.update(s, inputs(), track))
        val d = m.decor
        assertEquals("제3화 비밀", d.header.center.text)
        assertFalse(d.header.center.keepEnd)                       // a chapter title is shortened at its end
        assertTrue(d.header.left.isEmpty && d.header.right.isEmpty)
        assertEquals("12 / 3259", chars(d.footer.left))
        assertTrue(d.footer.center.isEmpty)
        assertEquals("", chars(d.footer.right))
        assertEquals(80, d.footer.right.battery)
        assertFalse(d.footer.right.batteryFirst)
        assertEquals("80", String(d.footer.right.batteryChars, 0, d.footer.right.batteryLength))
        assertFalse(d.lane)
        assertEquals(-1f, d.progress, 0f)
    }

    @Test
    fun everyItemInEverySlot() {
        val expected = mapOf(
            StatusItem.CHAPTER to "제3화 비밀", StatusItem.BOOK_TITLE to "책 제목", StatusItem.PAGE to "12 / 3259",
            StatusItem.PERCENT to "34%", StatusItem.CHAPTER_PAGES_LEFT to "2 / 32", StatusItem.EPISODE to "123/540화",
            StatusItem.TIME_LEFT_EPISODE to "챕터 3분", StatusItem.TIME_LEFT_BOOK to "책 7시간 20분", StatusItem.CLOCK to "14:05",
        )
        for (band in 0..1) for (pos in 0..2) for ((item, text) in expected) {
            val m = StatusModel()
            val s = ReaderSettings(headerCenter = StatusItem.NONE).withSlot(band, pos, item)
            m.update(s, inputs(), track)
            val slot = slotOf(m, band, pos)
            assertEquals("$item", text, slot.text ?: chars(slot))
            assertEquals(-1, slot.battery)
        }
    }

    private fun slotOf(m: StatusModel, band: Int, pos: Int): StatusSlot {
        val b = if (band == 0) m.decor.header else m.decor.footer
        return when (pos) { 0 -> b.left; 1 -> b.center; else -> b.right }
    }

    @Test
    fun unknownInputsLeaveTheSlotEmpty() {
        val m = StatusModel()
        val s = ReaderSettings(
            headerLeft = StatusItem.PAGE, headerCenter = StatusItem.CLOCK, headerRight = StatusItem.BATTERY,
            footerLeft = StatusItem.CHAPTER_PAGES_LEFT, footerCenter = StatusItem.EPISODE, footerRight = StatusItem.TIME_LEFT_BOOK,
        )
        m.update(s, StatusInputs(), track)
        val d = m.decor
        assertTrue(d.header.isEmpty)
        assertTrue(d.footer.isEmpty)
    }

    @Test
    fun identicalInputsChangeNothing() {
        val m = StatusModel()
        val s = ReaderSettings(footerLeft = StatusItem.PAGE, footerCenter = StatusItem.CLOCK, footerRight = StatusItem.BATTERY)
        val inp = inputs()
        assertTrue(m.update(s, inp, track))
        val v = m.decor.version
        assertFalse(m.update(s, inp, track))
        assertFalse(m.update(s, inputs(), track))
        assertEquals(v, m.decor.version)
    }

    @Test
    fun aChangedMinuteBatteryPageOrDotIsAChange() {
        val m = StatusModel()
        val s = ReaderSettings(footerLeft = StatusItem.PAGE, footerCenter = StatusItem.CLOCK, footerRight = StatusItem.BATTERY)
        val inp = inputs()
        m.update(s, inp, track)
        var v = m.decor.version
        inp.minuteOfDay++
        assertTrue(m.update(s, inp, track)); assertEquals(++v, m.decor.version)
        inp.battery = 79
        assertTrue(m.update(s, inp, track)); assertEquals(++v, m.decor.version)
        inp.page = 13
        assertTrue(m.update(s, inp, track)); assertEquals(++v, m.decor.version)
        inp.bar = 0.5f
        assertTrue(m.update(s, inp, track)); assertEquals(++v, m.decor.version)
        assertEquals(0.5f, m.decor.progress, 0f)
    }

    @Test
    fun aDotMoveUnderOnePixelIsNoChange() {
        val m = StatusModel()
        val s = ReaderSettings()
        val inp = inputs().apply { bar = 0.5f }
        m.update(s, inp, 100)
        inp.bar = 0.501f                                          // 50.1 px → still 50
        assertFalse(m.update(s, inp, 100))
        inp.bar = 0.51f                                           // 51 px
        assertTrue(m.update(s, inp, 100))
    }

    @Test
    fun theDefaultHeaderIsMaruViewersLine() {
        // Battery icon and clock on the left, the book title in the middle, the page on the right; no footer text.
        val m = StatusModel()
        val s = ReaderSettings()
        assertEquals(StatusItem.CLOCK_BATTERY, s.headerLeft)
        assertEquals(StatusItem.BOOK_TITLE, s.headerCenter)
        assertEquals(StatusItem.PAGE, s.headerRight)
        assertFalse(s.hasFooterText)
        assertTrue(s.progressBar)
        assertTrue(m.update(s, inputs(), track))
        val h = m.decor.header
        // MaruViewer's corner: the icon first, its bars the level in 25 % steps (80 % → four), no number; then the time.
        assertEquals("14:05", chars(h.left))
        assertEquals(100, h.left.battery)
        assertTrue(h.left.batteryFirst)
        assertEquals(0, h.left.batteryLength)
        // The book title keeps its end when it is shortened ("…능을 전혀 안숨김 1-246").
        assertEquals("책 제목", h.center.text)
        assertTrue(h.center.keepEnd)
        assertEquals("12 / 3259", chars(h.right))
        assertTrue(m.decor.footer.isEmpty)
        // A level within the same step changes nothing (no repaint, no e-ink update); the next step does: 76 % keeps
        // four bars, 75 % has three, 25 % one (the red one on phones), 0 % still one. The clock unknown: the icon alone.
        val inp = inputs()
        m.update(s, inp, track)
        inp.battery = 76
        assertFalse(m.update(s, inp, track))
        inp.battery = 75
        assertTrue(m.update(s, inp, track))
        assertEquals(75, h.left.battery)
        inp.battery = 51
        assertFalse(m.update(s, inp, track))
        inp.battery = 25
        assertTrue(m.update(s, inp, track))
        assertEquals(25, h.left.battery)
        inp.battery = 0
        assertFalse(m.update(s, inp, track))
        assertEquals(25, h.left.battery)
        inp.battery = 79
        assertTrue(m.update(s, inp, track))
        assertEquals(100, h.left.battery)
        inp.minuteOfDay = -1
        assertTrue(m.update(s, inp, track))
        assertEquals(0, h.left.length)
        assertTrue(h.left.batteryFirst && !h.left.isEmpty)
        // No battery reading: the time alone, no icon.
        inp.minuteOfDay = 9 * 60; inp.battery = -1
        assertTrue(m.update(s, inp, track))
        assertEquals("09:00", chars(h.left))
        assertEquals(-1, h.left.battery)
        assertFalse(h.left.batteryFirst)
        // The same title moved from 책 제목 to 챕터 제목 changes how it is shortened: a change.
        val same = inputs().apply { chapterTitle = bookTitle }
        m.update(s, same, track)
        assertTrue(m.update(s.copy(headerCenter = StatusItem.CHAPTER), same, track))
        assertFalse(h.center.keepEnd)
    }

    @Test
    fun hiddenItemsDoNotCauseChanges() {
        val m = StatusModel()
        val s = ReaderSettings(headerLeft = StatusItem.NONE, headerCenter = StatusItem.CHAPTER, headerRight = StatusItem.NONE,
            progressBar = false)                                  // header chapter only
        val inp = inputs()
        m.update(s, inp, track)
        inp.minuteOfDay++; inp.battery--; inp.page++; inp.bar = 0.9f; inp.percent++
        assertFalse(m.update(s, inp, track))
    }

    @Test
    fun chapterStartsHereBlanksTheChapterOnly() {
        val m = StatusModel()
        val s = ReaderSettings(headerCenter = StatusItem.CHAPTER, headerRight = StatusItem.BOOK_TITLE)
        val inp = inputs()
        m.update(s, inp, track)
        inp.chapterStartsHere = true
        assertTrue(m.update(s, inp, track))
        assertTrue(m.decor.header.center.isEmpty)
        assertEquals("책 제목", m.decor.header.right.text)
        // The chooser's sample still names the chapter.
        assertEquals("제3화 비밀", m.sample(StatusItem.CHAPTER, inp))
    }

    @Test
    fun laneFollowsTheSettingEvenWithoutAPosition() {
        val m = StatusModel()
        val inp = inputs().apply { bar = -1f }
        m.update(ReaderSettings(progressBar = true), inp, track)
        assertTrue(m.decor.lane)
        assertEquals(-1f, m.decor.progress, 0f)
        assertTrue(m.update(ReaderSettings(progressBar = false), inp, track))
        assertFalse(m.decor.lane)
        inp.bar = 0.3f
        assertFalse(m.update(ReaderSettings(progressBar = false), inp, track))   // no lane: the bar is not drawn
        assertEquals(-1f, m.decor.progress, 0f)
        assertTrue(m.update(ReaderSettings(progressBar = true), inp, track))
        assertEquals(0.3f, m.decor.progress, 0f)
    }

    @Test
    fun theDisplaysCornersGoWithTheDecor() {
        // The header's side insets clear the display's rounded corners: a new corner is a change, the same one is not.
        val m = StatusModel()
        val inp = inputs()
        val s = ReaderSettings()
        m.update(s, inp, track)
        assertEquals(-1, m.decor.cornerLeft.radius)                       // unknown until the window reports them
        val corners = intArrayOf(132, 132, 132, 132, 132, 132)
        val v = m.decor.version
        assertTrue(m.update(s, inp, track, corners = corners))
        assertEquals(v + 1, m.decor.version)
        assertEquals(132, m.decor.cornerRight.centreIn)
        assertFalse(m.update(s, inp, track, corners = corners))
        corners[5] = 22                                                   // the bars shown: the view starts lower
        assertTrue(m.update(s, inp, track, corners = corners))
        assertEquals(22, m.decor.cornerRight.centreY)
        assertTrue(m.update(s, inp, track))
        assertEquals(-1, m.decor.cornerRight.radius)
    }

    @Test
    fun theHeaderStartsBelowACutoutBand() {
        // S25 fullscreen: the geometry's 87 px camera band goes with the decor (the header draws inside it, its band
        // stays reserved below it).
        val m = StatusModel()
        val inp = inputs()
        val s = ReaderSettings()
        m.update(s, inp, track)
        assertEquals(0, m.decor.top)
        val v = m.decor.version
        assertTrue(m.update(s, inp, track, top = 87))
        assertEquals(87, m.decor.top)
        assertEquals(v + 1, m.decor.version)
        assertFalse(m.update(s, inp, track, top = 87))
        assertTrue(m.update(s, inp, track, top = 0))
        assertEquals(0, m.decor.top)
    }

    @Test
    fun slotChangesBetweenItemsAreChanges() {
        val m = StatusModel()
        val inp = inputs()
        m.update(ReaderSettings(footerLeft = StatusItem.PAGE), inp, track)
        assertTrue(m.update(ReaderSettings(footerLeft = StatusItem.CLOCK), inp, track))
        assertEquals("14:05", chars(m.decor.footer.left))
        assertTrue(m.update(ReaderSettings(footerLeft = StatusItem.CLOCK_BATTERY), inp, track))
        assertEquals(100, m.decor.footer.left.battery)                      // 80 % in the icon's steps: four bars
        assertTrue(m.update(ReaderSettings(footerLeft = StatusItem.NONE), inp, track))
        assertTrue(m.decor.footer.left.isEmpty)
    }

    @Test
    fun pageNumbersWaitForTheCount() {
        val m = StatusModel()
        val inp = inputs().apply { pages = StatusInputs.PAGES_COUNTING }
        assertEquals("쪽수 계산 중", m.sample(StatusItem.PAGE, inp))
        assertEquals("쪽수 계산 중", m.sample(StatusItem.CHAPTER_PAGES_LEFT, inp))
        // Not page numbers: unchanged while counting.
        assertEquals("34%", m.sample(StatusItem.PERCENT, inp))
        assertEquals("123/540화", m.sample(StatusItem.EPISODE, inp))
        inp.pages = StatusInputs.PAGES_FAILED
        assertEquals("쪽수 확인 불가", m.sample(StatusItem.PAGE, inp))
        inp.pages = StatusInputs.PAGES_EXACT
        assertEquals("12 / 3259", m.sample(StatusItem.PAGE, inp))
        assertEquals("2 / 32", m.sample(StatusItem.CHAPTER_PAGES_LEFT, inp))
        // Without a TOC the chapter page stays empty, counted or not.
        inp.pages = StatusInputs.PAGES_COUNTING
        inp.chapterPage = -1
        assertNull(m.sample(StatusItem.CHAPTER_PAGES_LEFT, inp))
        // The status line changes once, when the count completes.
        val s = ReaderSettings(headerCenter = StatusItem.PAGE)
        m.update(s, inp, track)
        inp.page = 13
        assertFalse(m.update(s, inp, track))
        inp.pages = StatusInputs.PAGES_EXACT
        assertTrue(m.update(s, inp, track))
    }

    @Test
    fun pendingTextShowsOnlyInThePageSlotWhenBothItemsAreShown() {
        val m = StatusModel()
        val inp = inputs().apply { pages = StatusInputs.PAGES_COUNTING }
        val both = ReaderSettings(headerLeft = StatusItem.NONE, headerCenter = StatusItem.PAGE, headerRight = StatusItem.NONE, footerCenter = StatusItem.CHAPTER_PAGES_LEFT)
        m.update(both, inp, track)
        assertEquals("쪽수 계산 중", chars(slotOf(m, 0, 1)))
        assertTrue(slotOf(m, 1, 1).isEmpty)
        // A failed count reads the same way.
        inp.pages = StatusInputs.PAGES_FAILED
        m.update(both, inp, track)
        assertEquals("쪽수 확인 불가", chars(slotOf(m, 0, 1)))
        assertTrue(slotOf(m, 1, 1).isEmpty)
        // Counted: both show their numbers again.
        inp.pages = StatusInputs.PAGES_EXACT
        assertTrue(m.update(both, inp, track))
        assertEquals("12 / 3259", chars(slotOf(m, 0, 1)))
        assertEquals("2 / 32", chars(slotOf(m, 1, 1)))
        // Either item alone keeps its pending text.
        inp.pages = StatusInputs.PAGES_COUNTING
        val none = ReaderSettings(headerLeft = StatusItem.NONE, headerCenter = StatusItem.NONE, headerRight = StatusItem.NONE)
        m.update(none.copy(footerCenter = StatusItem.CHAPTER_PAGES_LEFT), inp, track)
        assertEquals("쪽수 계산 중", chars(slotOf(m, 1, 1)))
        m.update(none.copy(headerCenter = StatusItem.PAGE), inp, track)
        assertEquals("쪽수 계산 중", chars(slotOf(m, 0, 1)))
        // The slot chooser still describes the item by itself.
        assertEquals("쪽수 계산 중", m.sample(StatusItem.CHAPTER_PAGES_LEFT, inp))
    }

    @Test
    fun pendingWithBothItemsAllocatesNothing() {
        if (!AllocCounter.supported) return
        val m = StatusModel()
        val s = ReaderSettings(headerCenter = StatusItem.PAGE, footerCenter = StatusItem.CHAPTER_PAGES_LEFT)
        val a = inputs().apply { pages = StatusInputs.PAGES_COUNTING }
        val b = inputs().apply { pages = StatusInputs.PAGES_FAILED }
        var changes = 0
        val loop = { for (i in 0 until 10_000) if (m.update(s, if (i % 2 == 0) a else b, track)) changes++ }
        repeat(3) { loop() }
        // The least of three runs: a JIT recompile or deopt landing inside one run is not the code allocating.
        val bytes = (1..3).minOf { AllocCounter.measure(loop)!! }
        assertEquals("10 000 updates allocated $bytes bytes", 0L, bytes)
    }

    @Test
    fun chapterPageNeedsATocPage() {
        val m = StatusModel()
        val inp = inputs().apply { chapterPage = -1; chapterPages = -1 }
        assertNull(m.sample(StatusItem.CHAPTER_PAGES_LEFT, inp))
        inp.chapterPage = 32
        assertEquals("32 / 32", m.sample(StatusItem.CHAPTER_PAGES_LEFT, inp))     // the chapter's last page
        inp.chapterPage = 1; inp.chapterPages = 1
        assertEquals("1 / 1", m.sample(StatusItem.CHAPTER_PAGES_LEFT, inp))
    }

    @Test
    fun chapterPageOfTheGlobalPage() {
        val inp = StatusInputs()
        inp.setChapterPage(cur = 41, first = 40, next = 72)
        assertEquals(2, inp.chapterPage)
        assertEquals(32, inp.chapterPages)
        inp.setChapterPage(cur = 71, first = 40, next = 72)                      // the page before the next chapter
        assertEquals(32, inp.chapterPage)
        assertEquals(32, inp.chapterPages)
        inp.setChapterPage(cur = 3, first = 1, next = 6)                          // front matter: from page 1
        assertEquals(3, inp.chapterPage)
        assertEquals(5, inp.chapterPages)
        inp.setChapterPage(cur = 9, first = 10, next = 12)                        // estimates disagree: never page 0
        assertEquals(1, inp.chapterPage)
        assertEquals(2, inp.chapterPages)
        inp.setChapterPage(cur = 15, first = 10, next = 12)                       // nor past the chapter's last page
        assertEquals(6, inp.chapterPage)
        assertEquals(6, inp.chapterPages)
    }

    @Test
    fun samples() {
        val m = StatusModel()
        val inp = inputs()
        assertNull(m.sample(StatusItem.NONE, inp))
        assertEquals("책 제목", m.sample(StatusItem.BOOK_TITLE, inp))
        assertEquals("12 / 3259", m.sample(StatusItem.PAGE, inp))
        assertEquals("34%", m.sample(StatusItem.PERCENT, inp))
        assertEquals("2 / 32", m.sample(StatusItem.CHAPTER_PAGES_LEFT, inp))
        assertEquals("123/540화", m.sample(StatusItem.EPISODE, inp))
        assertEquals("챕터 3분", m.sample(StatusItem.TIME_LEFT_EPISODE, inp))
        assertEquals("책 7시간 20분", m.sample(StatusItem.TIME_LEFT_BOOK, inp))
        assertEquals("14:05", m.sample(StatusItem.CLOCK, inp))
        assertEquals("80", m.sample(StatusItem.BATTERY, inp))
        // The battery icon has no text: the sample is the time, like the item's example.
        assertEquals("14:05", m.sample(StatusItem.CLOCK_BATTERY, inp))
        assertEquals(StatusItem.CLOCK_BATTERY.example, m.sample(StatusItem.CLOCK_BATTERY, inp))
        assertNull(m.sample(StatusItem.PAGE, StatusInputs()))
        assertNull(m.sample(StatusItem.CLOCK_BATTERY, StatusInputs()))
        assertNull(m.sample(StatusItem.CLOCK_BATTERY, StatusInputs().apply { battery = 80 }))
    }

    @Test
    fun cornerClockOnATwelveHourPhoneReadsLikeMaruViewer() {
        val m = StatusModel()
        val inp = inputs().apply { minuteOfDay = 8 * 60 + 53; is24 = false }
        m.update(ReaderSettings(), inp, track)                    // the default header: icon · clock | title | page
        val left = m.decor.header.left
        assertEquals("오전 08:53", chars(left))
        assertTrue(left.batteryFirst)
        assertEquals(100, left.battery)
        assertEquals("오전 08:53", m.sample(StatusItem.CLOCK_BATTERY, inp))
        // The plain clock keeps its short form.
        assertEquals("8:53", m.sample(StatusItem.CLOCK, inp))
    }

    @Test
    fun updatesAllocateNothingAfterWarmUp() {
        if (!AllocCounter.supported) return
        val m = StatusModel()
        val s = ReaderSettings(
            headerLeft = StatusItem.EPISODE, headerCenter = StatusItem.CHAPTER, headerRight = StatusItem.TIME_LEFT_BOOK,
            footerLeft = StatusItem.PAGE, footerCenter = StatusItem.CHAPTER_PAGES_LEFT, footerRight = StatusItem.CLOCK_BATTERY,
        )
        val a = inputs()
        val b = inputs().apply {
            page = 13; percent = 35; bar = 0.26f; chapterPage = 3; minutesBook = 439; minuteOfDay = 14 * 60 + 6
            battery = 79; epNumber = 124; chapterStartsHere = true
        }
        // Warm-up: the same loop as measured (the first pass also pays for interpreter/OSR transitions).
        var changes = 0
        val loop = { for (i in 0 until 10_000) if (m.update(s, if (i % 2 == 0) a else b, track)) changes++ }
        repeat(3) { loop() }
        changes = 0
        // The least of three runs: a JIT recompile or deopt landing inside one run is not the code allocating.
        val bytes = (1..3).minOf { AllocCounter.measure(loop)!! }
        assertEquals(30_000, changes)                             // every call of the three runs changed something visible
        assertEquals("10 000 updates allocated $bytes bytes", 0L, bytes)
    }

    @Test
    fun chargingIsAChangeAndReachesTheBatterySlot() {
        val m = StatusModel()
        val s = ReaderSettings(headerLeft = StatusItem.CLOCK_BATTERY, headerCenter = StatusItem.NONE, headerRight = StatusItem.NONE)
        val plain = inputs()
        assertTrue(m.update(s, plain, track))
        assertFalse(m.decor.header.left.charging)
        val plugged = inputs().apply { charging = true }
        assertTrue(m.update(s, plugged, track))
        assertTrue(m.decor.header.left.charging)
        assertFalse(m.update(s, plugged, track)) // the same state again: nothing to redraw
        // No battery known: no bolt either.
        val none = inputs().apply { charging = true; battery = -1 }
        m.update(s, none, track)
        assertFalse(m.decor.header.left.charging)
    }
}
