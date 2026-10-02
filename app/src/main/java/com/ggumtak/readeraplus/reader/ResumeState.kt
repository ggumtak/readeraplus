package com.ggumtak.readeraplus.reader

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle

/**
 * U1: "the reader is showing a book" across process death.
 * - Set after the first page of a book ([opened]).
 * - Cleared only when the reader is finished ([clear]: Back, 닫기, 서재로, 휴지통, task removal, finishAffinity).
 * When Android later restarts the task from its root (recents after a force-stop, an in-place update or a silent
 * crash), LibraryActivity finds the marker and reopens the book.
 * - Separate prefs file: never the settings prefs (the scroll SPEC's empty-prefs install check, §3.3), and writes
 *   happen only after the first page.
 * - An Auto Backup copy restored on another phone is harmless: the id and the file are validated before use.
 */
object ResumeState {
    private const val PREFS = "reader_resume"
    private const val K_BOOK = "bookId"
    private const val K_TRIES = "tries"
    /** Library-started resumes in a row with no normal reader pause in between; a crash loop stops after this. */
    const val MAX_TRIES = 2

    @Volatile private var prefs: SharedPreferences? = null

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
    fun opened(bookId: Long) {
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

    /** The reader was finished, or the marked book is gone. */
    fun clear() {
        val p = prefs ?: return
        if (p.contains(K_BOOK) || p.contains(K_TRIES)) p.edit().clear().apply()
    }
}
