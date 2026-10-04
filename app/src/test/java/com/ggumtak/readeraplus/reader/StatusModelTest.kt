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
        val s = ReaderSettings(footerLeft = StatusItem.PAGE, footerRight = StatusItem.CLOCK_BATTERY, progressBar = false)
        assertTrue(m.update(s, inputs(), track))
        val d = m.decor
        assertEquals("제3화 비밀", d.header.center.text)               // default header: the chapter title
        assertTrue(d.header.left.isEmpty && d.header.right.isEmpty)
        assertEquals("12 / 3259", chars(d.footer.left))
        assertTrue(d.footer.center.isEmpty)
        assertEquals("14:05", chars(d.footer.right))
        assertEquals(80, d.footer.right.battery)
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
    fun hiddenItemsDoNotCauseChanges() {
        val m = StatusModel()
        val s = ReaderSettings(progressBar = false)               // header chapter only
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
    fun slotChangesBetweenItemsAreChanges() {
        val m = StatusModel()
        val inp = inputs()
        m.update(ReaderSettings(footerLeft = StatusItem.PAGE), inp, track)
        assertTrue(m.update(ReaderSettings(footerLeft = StatusItem.CLOCK), inp, track))
        assertEquals("14:05", chars(m.decor.footer.left))
        assertTrue(m.update(ReaderSettings(footerLeft = StatusItem.CLOCK_BATTERY), inp, track))
        assertEquals(80, m.decor.footer.left.battery)
        assertTrue(m.update(ReaderSettings(footerLeft = StatusItem.NONE), inp, track))
        assertTrue(m.decor.footer.left.isEmpty)
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
        assertEquals("14:05 · 80", m.sample(StatusItem.CLOCK_BATTERY, inp))
        assertNull(m.sample(StatusItem.PAGE, StatusInputs()))
        assertNull(m.sample(StatusItem.CLOCK_BATTERY, StatusInputs()))
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
        val bytes = AllocCounter.measure(loop)!!
        assertEquals(10_000, changes)                             // every call changed something visible
        assertEquals("10 000 updates allocated $bytes bytes", 0L, bytes)
    }
}
