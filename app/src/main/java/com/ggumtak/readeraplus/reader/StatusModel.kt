package com.ggumtak.readeraplus.reader

import com.ggumtak.readeraplus.render.StatusDecor
import com.ggumtak.readeraplus.settings.ReaderSettings
import com.ggumtak.readeraplus.settings.StatusItem

/** Inputs of one status update for the page on screen. Reused, primitives and existing references only. */
internal class StatusInputs {
    @JvmField var page = 0; @JvmField var total = 0              // globalPage / counts.total()
    @JvmField var percent = 0                                     // ReaderFormat.percent(progress())
    @JvmField var bar = -1f                                       // char progress of the page start; last page = 1; -1 = off
    @JvmField var chapterTitle: String? = null; @JvmField var bookTitle: String? = null
    @JvmField var chapterStartsHere = false                       // the page begins the chapter: CHAPTER draws nothing (§6 P1-16)
    @JvmField var chapterPagesLeft = -1
    @JvmField var minutesEpisode = -1; @JvmField var minutesBook = -1
    @JvmField var epNumbered = false; @JvmField var epNumber = -1; @JvmField var epMax = -1
    @JvmField var tocIndex = -1; @JvmField var tocCount = 0
    @JvmField var minuteOfDay = -1; @JvmField var is24 = true
    @JvmField var battery = -1
}
internal class StatusModel {
    val decor = StatusDecor()
    /** Fills decor for the slots of [s]. Zero allocation. True when anything drawn changed (then decor.version++). */
    fun update(s: ReaderSettings, inp: StatusInputs, trackPx: Int): Boolean { // R3 stub (owner: RU)
        decor.header.left.clear(); decor.header.center.clear(); decor.header.right.clear(); decor.footer.left.clear(); decor.footer.center.clear(); decor.footer.right.clear(); decor.lane=false; decor.progress=-1f; return false
    }
    /** One-off String of [item] for the slot chooser (allocates; never on a turn). */
    fun sample(item: StatusItem, inp: StatusInputs): String? = null // R3 stub (owner: RU)
}
/** Allocation-free formatters; output identical to their ReaderFormat twins (tested). Return the new length. */
internal object StatusText {
    fun page(buf: CharArray, at: Int, page: Int, total: Int): Int = TODO("owner: RU")        // "12 / 3259" (total ≥ page)
    fun percent(buf: CharArray, at: Int, p: Int): Int = TODO("owner: RU")                    // "34%"
    fun clock(buf: CharArray, at: Int, minuteOfDay: Int, is24: Boolean): Int = TODO("owner: RU")  // "14:05" / "2:05"
    fun chapterLeft(buf: CharArray, at: Int, pages: Int): Int = TODO("owner: RU")            // "챕터 5쪽 남음" / "챕터 마지막 쪽"
    fun episode(buf: CharArray, at: Int, numbered: Boolean, n: Int, max: Int, idx: Int, count: Int): Int = TODO("owner: RU")  // "123/540화" / "87/612"
    fun timeLeft(buf: CharArray, at: Int, book: Boolean, minutes: Int): Int = TODO("owner: RU")   // "이 화 3분" / "책 7시간 20분" / "… 1분 미만"
    fun int(buf: CharArray, at: Int, v: Int): Int = TODO("owner: RU")
}
