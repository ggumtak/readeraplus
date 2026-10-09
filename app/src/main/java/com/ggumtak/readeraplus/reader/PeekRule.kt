package com.ggumtak.readeraplus.reader

/**
 * N §6.2 "peek": a book opened at a note (or recreated while peeking) shows the note's page without moving the
 * reading position. While [active], the reader skips every position write (the scheduled and immediate saves, the TXT
 * text position, the pause / close save), so Back after a quick look leaves `pos_*`, `progress`, `last_read_at` and the
 * 읽고 있는 책 order exactly as they were. Pure: one boolean, checked once per save.
 */
internal class PeekRule {
    /** What happened in the reader; [ends] says whether it turns a peek into normal reading. */
    enum class Event {
        /** Tap, key, swipe or wheel turn that moved the page. */
        MANUAL_TURN,
        TTS_START,
        AUTO_TURN_START,
        /** TOC, search, go-to, seek, link, 다음 화 / 이전 화 (any explicit jump the user asked for). */
        USER_JUMP,
        /** The return strip or chip. */
        RETURN_POINT,
        /** The selection popup's "여기부터 듣기". */
        READ_HERE,
        /** Scroll mode: the first settle of a user scroll. */
        SCROLL_SETTLE,
        /** The anchor check moved the page to where the note's text is now (still the note, still a peek). */
        ANCHOR_MOVE,
        /** A resize, a settings change or a re-parse showing the same place again. */
        RELAYOUT,
        /** TTS following its own speech, auto turn's own turns. */
        FOLLOW,
    }

    var active = false
        private set

    /** An open at a note consumed its jump (§1.6.1, in the same message as the first page). */
    fun start() {
        active = true
    }

    /** onCreate of a recreated reader: `rp.peek` (PLAN C8). */
    fun restore(on: Boolean) {
        active = on
    }

    /** Applies [e]; true when this event ended a peek (normal saving resumes). */
    fun on(e: Event): Boolean {
        if (!active || !ends(e)) return false
        active = false
        return true
    }

    /** A position save may run (scheduled, immediate, text position, pause / close). */
    val savesPosition: Boolean get() = !active

    /** Another book, or the reader closed. */
    fun reset() {
        active = false
    }

    companion object {
        fun ends(e: Event): Boolean = when (e) {
            Event.MANUAL_TURN, Event.TTS_START, Event.AUTO_TURN_START, Event.USER_JUMP, Event.RETURN_POINT,
            Event.READ_HERE, Event.SCROLL_SETTLE -> true
            Event.ANCHOR_MOVE, Event.RELAYOUT, Event.FOLLOW -> false
        }
    }
}
