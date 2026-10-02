package com.ggumtak.readeraplus.ui.library

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryStartTest {
    private fun mode(
        action: String? = Intent.ACTION_MAIN, flags: Int = Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY,
        first: Boolean = true, id: Long = 7L, tries: Int = 0, restored: Boolean = false, openLast: Boolean = false,
    ) = LibraryText.startMode(openLast, restored, action, flags, first, id, tries)

    @Test fun recentsRestoresAnUnclosedBookEvenWithOpenLastOff() {
        assertEquals(LibraryText.StartMode.RESUME, mode())
    }

    @Test fun launcherAlsoRestoresAnUnclosedBook() {
        assertEquals(LibraryText.StartMode.RESUME, mode(flags = 0))
    }

    @Test fun rootRecreationRestoresTheBook() {
        assertEquals(LibraryText.StartMode.RESUME, mode(action = null, restored = true))
    }

    @Test fun returningToAnExistingLibraryDoesNotReopenTheReader() {
        assertEquals(LibraryText.StartMode.LIBRARY, mode(first = false))
    }

    @Test fun explicitInAppLibraryNavigationDoesNotReopenTheBook() {
        assertEquals(LibraryText.StartMode.LIBRARY, mode(action = null))
    }

    @Test fun anExternalViewIntentIsLeftToItsOwnFlow() {
        assertEquals(LibraryText.StartMode.LIBRARY, mode(action = Intent.ACTION_VIEW))
    }

    @Test fun theSecondConsecutiveFailedResumeEndsTheCrashLoop() {
        assertEquals(LibraryText.StartMode.RESUME, mode(tries = 1))
        assertEquals(LibraryText.StartMode.LIBRARY, mode(tries = 2))
        assertEquals(LibraryText.StartMode.LIBRARY, mode(tries = 3))
    }

    @Test fun aClosedBookLeavesTheLibraryShowing() {
        assertEquals(LibraryText.StartMode.LIBRARY, mode(id = -1L))
    }

    @Test fun openLastKeepsItsExistingFreshLauncherSemantics() {
        assertEquals(LibraryText.StartMode.OPEN_LAST, mode(flags = 0, id = -1L, openLast = true))
        assertEquals(LibraryText.StartMode.LIBRARY, mode(id = -1L, openLast = true))
    }
}
