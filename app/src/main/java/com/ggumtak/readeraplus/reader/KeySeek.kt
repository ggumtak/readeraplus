package com.ggumtak.readeraplus.reader

/**
 * Seek bar steps made without a drag (D-pad left / right, TalkBack's scroll forward / backward): `AbsSeekBar` reports
 * them through `onProgressChanged(fromUser = true)` alone, with no start / stop tracking. [ReaderChrome] turns a burst
 * of them into what a drag does: the first step starts a seek from the value before it ([step]: `onSeekStart`, then
 * `onSeekPreview(from)`), and one jump is made when the steps settle, [SETTLE_MS] after the last ([settle]:
 * `onSeekDone`), so a held key does not turn the book once per step. Pure (the timer is the caller's), unit-tested.
 */
internal class KeySeek {
    /** A burst was started and has not settled, been taken over by a drag or been cancelled. */
    var pending = false
        private set

    /** The progress the burst started from (meaningful while [pending]). */
    var from = -1
        private set

    /**
     * A step away from [previous] (the bar's value before it). True when it starts a burst: the caller then sends
     * `onSeekStart` and `onSeekPreview(previous)`. Either way the caller restarts the settle wait.
     */
    fun step(previous: Int): Boolean {
        if (pending) return false
        pending = true
        from = previous
        return true
    }

    /**
     * The settle wait ran out. True when the caller commits the bar's value (`onSeekDone`); false when no burst is
     * pending, or a touch drag is [touching] the bar now (its own start / done decide where the book goes).
     */
    fun settle(touching: Boolean): Boolean {
        val commit = pending && !touching
        pending = false
        return commit
    }

    /** The bar was hidden or detached, or a drag took over: nothing is committed. */
    fun cancel() {
        pending = false
    }

    companion object {
        /** Quiet time after the last step before the one jump. */
        const val SETTLE_MS = 250L
    }
}
