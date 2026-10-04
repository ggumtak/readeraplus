package com.ggumtak.readeraplus.reader

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.os.SystemClock

/**
 * U1: "the reader is showing a book" across process death.
 * - Set after the first page of a book ([opened]).
 * - Cleared only when the reader is finished ([clear]: Back, 닫기, 서재로, 휴지통, task removal, finishAffinity).
 * When Android later restarts the task from its root (recents after a force-stop, an in-place update or a silent
 * crash), LibraryActivity finds the marker and reopens the book.
 * - Separate prefs file: never the settings prefs (the scroll SPEC's empty-prefs install check, §3.3), and writes
 *   happen only after the first page.
 * - An Auto Backup copy restored on another phone is harmless: the id and the file are validated before use.
 *
 * In this process it also catches a reader finished by the system, not by the user ([dropped]): an e-reader's task
 * manager or launcher may bring the app back with a launcher intent that clears the task above the library
 * (CLEAR_TOP / CLEAR_TASK), which finishes the reader the user was reading. The library started by that launch reopens
 * the book at once ([takeInterrupted], or [awaitDrop] when the reader's finish arrives after the library's start).
 * User report on the Comet, 2026-10-04: going back through the task manager showed the library.
 */
object ResumeState {
    private const val PREFS = "reader_resume"
    private const val K_BOOK = "bookId"
    private const val K_TRIES = "tries"
    /** Library-started resumes in a row with no normal reader pause in between; a crash loop stops after this. */
    const val MAX_TRIES = 2

    @Volatile private var prefs: SharedPreferences? = null

    /** In-process: the book the live reader shows (after its first page) until the user closes it; -1 = none. */
    @Volatile private var liveBook = -1L
    /** The reader instance that set [liveBook] (an older instance still finishing must not touch it). */
    @Volatile private var liveOwner: Any? = null
    /** Book of the reader the system just finished ([dropped]), -1 = none; taken once, within [DROPPED_MS]. */
    @Volatile private var droppedBook = -1L
    @Volatile private var droppedAt = 0L
    /** A library start waiting ([awaitDrop]) for a drop that may arrive just after it, and since when. */
    private var dropWaiter: ((Long) -> Unit)? = null
    private var waitingSince = 0L
    /** A dropped reader counts as interrupted for this long; a library start waits this long for a late drop. */
    private const val DROPPED_MS = 3_000L

    /** Activities created in this process (any class). The library resumes only as the first one. */
    @Volatile var activitiesCreated = 0
        private set

    /** App.onCreate: starts the prefs load off the main thread and counts activity creations. */
    fun init(app: Application) {
        if (prefs == null) prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            // Dispatched from Activity.onCreate's super call: inside LibraryActivity.onCreate the count includes it.
            override fun onActivityCreated(a: Activity, b: Bundle?) { activitiesCreated++ }
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }

    class Pending(val bookId: Long, val tries: Int)

    fun pending(): Pending? {
        val p = prefs ?: return null
        val id = p.getLong(K_BOOK, -1L)
        return if (id > 0) Pending(id, p.getInt(K_TRIES, 0)) else null
    }

    /** Reader, after the first page of [bookId] (afterOpen). Keeps the try count: only a normal pause resets it. */
    fun opened(bookId: Long, owner: Any) {
        liveBook = bookId
        liveOwner = owner
        val p = prefs ?: return
        if (p.getLong(K_BOOK, -1L) != bookId) p.edit().putLong(K_BOOK, bookId).apply()
    }

    /** Reader.onPause with a book shown: a normal pause ends any crash streak. */
    fun paused() {
        val p = prefs ?: return
        if (p.getInt(K_TRIES, 0) != 0) p.edit().putInt(K_TRIES, 0).apply()
    }

    /** Library, IO thread, right before it starts the reader (commit: a crash just after must still count it). */
    fun noteAttempt() {
        val p = prefs ?: return
        p.edit().putInt(K_TRIES, p.getInt(K_TRIES, 0) + 1).commit()
    }

    /** The reader was finished by the user (Back, 닫기, 서재로, 휴지통 …), or the marked book is gone. */
    fun clear() {
        liveBook = -1L
        liveOwner = null
        droppedBook = -1L
        dropWaiter = null
        val p = prefs ?: return
        if (p.contains(K_BOOK) || p.contains(K_TRIES)) p.edit().clear().apply()
    }

    /**
     * Reader [owner] is being finished by the system, not by the user (its onPause or onDestroy with isFinishing and
     * no finish() of its own: an outside launch cleared the task, or the task was removed). The persisted marker goes
     * as before (a later cold start shows the library); this process keeps the book for a library started by that same
     * launch ([takeInterrupted], or a waiting [awaitDrop]). Main thread.
     */
    fun dropped(owner: Any, now: Long = SystemClock.uptimeMillis()) {
        if (liveOwner !== owner) {
            // A newer reader owns the marker now (the reopened one): leave it. Nothing live: just the marker.
            if (liveOwner == null) clearMarker()
            return
        }
        val id = liveBook
        liveBook = -1L
        liveOwner = null
        clearMarker()
        val waiter = dropWaiter?.takeIf { now - waitingSince <= DROPPED_MS }
        dropWaiter = null
        if (waiter != null) {
            waiter(id)
        } else {
            droppedBook = id
            droppedAt = now
        }
    }

    /** Library start or new intent: the book of a reader the system finished just now, else -1. Taken once. */
    fun takeInterrupted(now: Long = SystemClock.uptimeMillis()): Long {
        val id = droppedBook
        droppedBook = -1L
        return if (id > 0 && now - droppedAt <= DROPPED_MS) id else -1L
    }

    /**
     * Library start or new intent with nothing to take: a reader finished by the same launch may report only after
     * the library started (its destroy is delivered later). [reopen] runs once if that happens within [DROPPED_MS].
     * Only while a reader is live (else nothing can drop). Main thread.
     */
    fun awaitDrop(now: Long = SystemClock.uptimeMillis(), reopen: (Long) -> Unit) {
        if (liveBook <= 0) return
        dropWaiter = reopen
        waitingSince = now
    }

    /** The library left the foreground: a late drop no longer reopens anything from it. */
    fun stopWaiting() {
        dropWaiter = null
    }

    private fun clearMarker() {
        val p = prefs ?: return
        if (p.contains(K_BOOK) || p.contains(K_TRIES)) p.edit().clear().apply()
    }
}
