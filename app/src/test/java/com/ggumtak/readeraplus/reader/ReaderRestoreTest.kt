package com.ggumtak.readeraplus.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReaderRestoreTest {
    private val place = ReaderRestore.Place.from(7L, 3, 42, 1_000L)!!

    @Test fun noBookIdLeavesTheLaunchIntentInCharge() {
        assertNull(ReaderRestore.Place.from(-1L, 3, 42, 1_000L))
        assertNull(ReaderRestore.Place.from(0L, 3, 42, 1_000L))
    }

    @Test fun restoresTheBookThatWasShowingWithItsExactAnchor() {
        val start = ReaderRestore.start(place, 7L, 999L, false)!!
        assertEquals(3, start.section)
        assertEquals(42, start.offset)
        assertEquals(7L, place.bookId)
    }

    @Test fun anotherBookDoesNotUseTheOldSavedPlace() {
        assertNull(ReaderRestore.start(place, 8L, 999L, false))
    }

    @Test fun aBookSavedWhileLoadingUsesItsDatabasePosition() {
        val loading = ReaderRestore.Place.from(7L, -1, 0, 1_000L)!!
        assertNull(ReaderRestore.start(loading, 7L, 999L, false))
    }

    @Test fun aNewerBackgroundTtsPositionWins() {
        assertNull(ReaderRestore.start(place, 7L, 1_001L, false))
        assertEquals(42, ReaderRestore.start(place, 7L, 1_000L, false)!!.offset)
    }

    @Test fun aChangedTxtParseUsesTheFractionRemap() {
        assertNull(ReaderRestore.start(place, 7L, 999L, true))
    }

    @Test fun anUnusableTimestampUsesTheDatabasePosition() {
        val missingTime = ReaderRestore.Place.from(7L, 3, 42, 0L)!!
        assertNull(ReaderRestore.start(missingTime, 7L, 0L, false))
    }

    @Test fun negativeOffsetsAreClampedAtTheSavedStateBoundary() {
        assertEquals(0, ReaderRestore.Place.from(7L, 3, -9, 1_000L)!!.offset)
    }

    @Test fun aRestoredPeekKeepsThePeekedPageOverTheOlderRow() {
        // N §6.2 + R §10: peeking saved nothing, so the DB row is older than the instance state.
        val start = ReaderRestore.start(place, 7L, rowWrittenAt = 500L, remapped = false)!!
        assertEquals(3, start.section)
        assertEquals(42, start.offset)
    }
}
