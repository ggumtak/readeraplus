package com.ggumtak.readeraplus.reader

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The in-process half of [ResumeState]: a reader the system finished (an outside launch cleared the task) is reopened
 * by the library that launch started; a reader the user closed, or one that is simply still open, is not.
 */
class ResumeInterruptTest {
    private val readerA = Any()
    private val readerB = Any()

    @After fun reset() = ResumeState.clear()

    @Test fun aReaderTheSystemFinishedIsReopenedOnce() {
        ResumeState.opened(7L, readerA)
        ResumeState.dropped(readerA, now = 1_000L)          // its onPause with isFinishing, no finish() of its own
        assertEquals(7L, ResumeState.takeInterrupted(now = 1_100L))
        assertEquals(-1L, ResumeState.takeInterrupted(now = 1_100L))
        ResumeState.dropped(readerA, now = 1_200L)          // its onDestroy later: nothing more
        assertEquals(-1L, ResumeState.takeInterrupted(now = 1_300L))
    }

    @Test fun aReaderStillOpenIsNotReopened() {
        // The library started on top of a live reader (am start -n, a reader opened from a file manager): no drop.
        ResumeState.opened(7L, readerA)
        assertEquals(-1L, ResumeState.takeInterrupted(now = 1_000L))
    }

    @Test fun aReaderTheUserClosedIsNotReopened() {
        ResumeState.opened(7L, readerA)
        ResumeState.clear()                                 // finish(): Back, 닫기, 서재로 …
        ResumeState.dropped(readerA, now = 1_000L)
        assertEquals(-1L, ResumeState.takeInterrupted(now = 1_000L))
    }

    @Test fun anOldDropIsForgotten() {
        ResumeState.opened(7L, readerA)
        ResumeState.dropped(readerA, now = 1_000L)
        assertEquals(-1L, ResumeState.takeInterrupted(now = 60_000L))
    }

    @Test fun aDropJustAfterTheLibraryStartedStillReopens() {
        ResumeState.opened(7L, readerA)
        var reopened = -1L
        ResumeState.awaitDrop(now = 1_000L) { reopened = it }
        ResumeState.dropped(readerA, now = 1_400L)
        assertEquals(7L, reopened)
        assertEquals(-1L, ResumeState.takeInterrupted(now = 1_500L))
    }

    @Test fun aLateDropAfterTheWaitOrAfterTheLibraryLeftDoesNotReopen() {
        ResumeState.opened(7L, readerA)
        var reopened = -1L
        ResumeState.awaitDrop(now = 1_000L) { reopened = it }
        ResumeState.dropped(readerA, now = 9_000L)
        assertEquals(-1L, reopened)
        ResumeState.opened(8L, readerB)
        ResumeState.awaitDrop(now = 10_000L) { reopened = it }
        ResumeState.stopWaiting()                           // library onPause
        ResumeState.dropped(readerB, now = 10_100L)
        assertEquals(-1L, reopened)
    }

    @Test fun theOldInstanceFinishingAfterTheReopenedOneLeavesItAlone() {
        ResumeState.opened(7L, readerA)
        ResumeState.dropped(readerA, now = 1_000L)
        assertEquals(7L, ResumeState.takeInterrupted(now = 1_100L)) // the library reopens the book …
        ResumeState.opened(7L, readerB)                            // … a new reader shows it …
        ResumeState.dropped(readerA, now = 1_200L)                  // … the old one's onDestroy changes nothing
        assertEquals(-1L, ResumeState.takeInterrupted(now = 1_300L))
        ResumeState.dropped(readerB, now = 1_400L)                  // the new one can still be dropped
        assertEquals(7L, ResumeState.takeInterrupted(now = 1_500L))
    }

    @Test fun noReaderNoWait() {
        var reopened = -1L
        ResumeState.awaitDrop(now = 1_000L) { reopened = it }
        ResumeState.dropped(readerA, now = 1_100L)
        assertEquals(-1L, reopened)
        assertEquals(-1L, ResumeState.takeInterrupted(now = 1_200L))
    }

    @Test fun aFirstPageDrawnAfterTheUserClosedIsUndoneAtDestroy() {
        ResumeState.opened(7L, readerA)
        ResumeState.clear()                                 // finish() while loading …
        ResumeState.opened(7L, readerA)                     // … (guarded in afterOpen, but if it ever happens)
        ResumeState.closed(readerA)                         // onDestroy of the user-closed reader
        ResumeState.dropped(readerA, now = 1_000L)
        assertEquals(-1L, ResumeState.takeInterrupted(now = 1_100L))
    }

    @Test fun closingAnOldReaderLeavesANewerOneAlone() {
        ResumeState.opened(7L, readerA)
        ResumeState.opened(8L, readerB)
        ResumeState.closed(readerA)
        ResumeState.dropped(readerB, now = 1_000L)
        assertEquals(8L, ResumeState.takeInterrupted(now = 1_100L))
    }

    @Test fun aReaderDestroyedForMemoryIsReopenedWhenALaunchClearedItsRecord() {
        // "Don't keep activities" / low memory: HOME destroyed the reader without finishing; a later CLEAR_TOP launch
        // finishes the record with no instance (no callback): the library start still finds the book.
        ResumeState.opened(7L, readerA)
        ResumeState.detached(readerA)
        assertEquals(7L, ResumeState.takeInterrupted(now = 99_000L))
        assertEquals(-1L, ResumeState.takeInterrupted(now = 99_000L))
    }

    @Test fun aRecreatedReaderIsLiveAgain() {
        ResumeState.opened(7L, readerA)
        ResumeState.detached(readerA)
        ResumeState.opened(7L, readerB)                     // the record came back (normal return): a new instance
        assertEquals(-1L, ResumeState.takeInterrupted(now = 1_000L))
    }
}
