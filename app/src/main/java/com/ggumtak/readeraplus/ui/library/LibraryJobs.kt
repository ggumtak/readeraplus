package com.ggumtak.readeraplus.ui.library

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import com.ggumtak.readeraplus.data.FileScanner
import com.ggumtak.readeraplus.data.ReaderPresence
import com.ggumtak.readeraplus.settings.Settings
import com.ggumtak.readeraplus.ui.settings.ErrorLines
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Process-wide background jobs of the library (storage scan and SAF folder import), so a recreated
 * activity doesn't start a second scan and can show the running one's progress. Listeners are called on
 * the main thread.
 */
internal object LibraryJobs {
    /** Raw pref: time of the last completed scan (ms). */
    const val PREF_LAST_SCAN = "lastScanAt"

    /** Status-row updates during a scan or import: each one is an e-ink refresh, so at most once a second. */
    private const val PROGRESS_INTERVAL_MS = 1000L

    interface Listener {
        /** Progress or state changed (status row). */
        fun onJobProgress()
        /**
         * A job finished: reload the list when [rowsChanged] (a scan that found nothing new leaves the rows as they
         * were). [message] is a short user-facing summary or null.
         */
        fun onJobDone(message: String?, rowsChanged: Boolean)
    }

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<Listener>()

    @Volatile var scanning = false
        private set
    @Volatile var scanFound = 0
        private set
    /** The running scan is the periodic one: it stops as soon as a reader comes to the front. */
    @Volatile private var scanYields = false
    /** The running scan reports its result (a manual request that finds the periodic scan running sets it too). */
    @Volatile private var scanAnnounce = false
    @Volatile var importing = false
        private set
    @Volatile var imported = 0
        private set
    @Volatile var importTotal = 0
        private set

    fun addListener(l: Listener) { listeners.addIfAbsent(l) }
    fun removeListener(l: Listener) { listeners.remove(l) }

    /**
     * The status strip's text, or null. The periodic rescan runs silently (it starts when the library is idle, and a
     * strip that appears, counts once a second and goes would be several e-ink updates of an idle screen); it shows
     * once a manual request takes it over.
     */
    fun status(): String? =
        LibraryText.statusText(scanning && !(scanYields && !scanAnnounce), scanFound, importing, imported, importTotal)

    private fun progress() = main.post { listeners.forEach { it.onJobProgress() } }
    private fun done(msg: String?, rowsChanged: Boolean = true) =
        main.post { listeners.forEach { it.onJobDone(msg, rowsChanged) } }

    /**
     * Starts a scan unless one is running. [announce] = report the result as a message. [periodic] = the automatic
     * rescan: it stops (between directories) while a reader is in front ([ReaderPresence]) so it never competes with
     * opening or reading a book, and leaves the last-scan time alone so the next idle library visit starts it again.
     * The periodic scan is not shown in the status strip ([status]). A manual request while it runs returns false but
     * takes that scan over (it no longer stops, shows in the strip, and reports its result).
     */
    fun startScan(context: Context, announce: Boolean, periodic: Boolean = false): Boolean {
        synchronized(this) {
            if (scanning) {
                if (!periodic) {
                    scanYields = false
                    if (announce) scanAnnounce = true
                    progress() // a silent periodic scan now shows in the status strip
                }
                return false
            }
            scanning = true
            scanFound = 0
            scanYields = periodic
            scanAnnounce = announce
        }
        val app = context.applicationContext
        progress()
        val throttle = ProgressThrottle(PROGRESS_INTERVAL_MS, SystemClock.elapsedRealtime())
        background("library-scan") {
            var msg: String? = null
            var stopped = false
            var rowsChanged = true
            try {
                val total = FileScanner.scan(
                    app,
                    stopWhen = { scanYields && ReaderPresence.inFront },
                    synced = { rowsChanged = it },
                ) { n ->
                    scanFound = n
                    if (throttle.ready(SystemClock.elapsedRealtime())) progress()
                }
                Settings.raw().edit().putLong(PREF_LAST_SCAN, System.currentTimeMillis()).apply()
                if (scanAnnounce) msg = LibraryText.scanDoneMessage(total)
            } catch (_: FileScanner.Stopped) {
                stopped = true
            } catch (t: Throwable) {
                msg = ErrorLines.line("스캔 실패", t)
            } finally {
                scanning = false
            }
            // Stopped for the reader: only the status strip changes. A "done" would reload the library's list, a
            // query racing the book that is opening (the library reloads on its next onResume anyway).
            if (stopped) progress() else done(msg, rowsChanged)
        }
        return true
    }

    /**
     * Runs an import job ([work] gets a progress callback (done, total)). Returns false when another import
     * is still running.
     */
    fun startImport(work: (progress: (Int, Int) -> Unit) -> String?): Boolean {
        synchronized(this) {
            if (importing) return false
            importing = true
            imported = 0
            importTotal = 0
        }
        progress()
        // Files that are skipped or already known go by in milliseconds: one post per file would repaint the strip
        // dozens of times a second. The final count needs no post: onJobDone refreshes the strip.
        val throttle = ProgressThrottle(PROGRESS_INTERVAL_MS, SystemClock.elapsedRealtime())
        background("library-import") {
            var msg: String?
            try {
                msg = work { d, t ->
                    imported = d
                    importTotal = t
                    if (throttle.ready(SystemClock.elapsedRealtime())) progress()
                }
            } catch (t: Throwable) {
                msg = ErrorLines.line("가져오기 실패", t)
            } finally {
                importing = false
            }
            done(msg)
        }
        return true
    }

    /** Runs [block] on a new thread at background priority, so the scheduler favours the UI and a book being opened. */
    private fun background(name: String, block: () -> Unit) {
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            block()
        }, name).apply { isDaemon = true }.start()
    }
}

/**
 * Rate limit for progress posts of one background job (confined to that job's thread): [ready] is true at most once
 * per [intervalMs], counted from [lastPostAt] (the post made when the job started). A clock that went backwards
 * lets the next post through.
 */
internal class ProgressThrottle(private val intervalMs: Long, private var lastPostAt: Long) {
    fun ready(now: Long): Boolean {
        if (now >= lastPostAt && now - lastPostAt < intervalMs) return false
        lastPostAt = now
        return true
    }
}
