package com.ggumtak.readeraplus.ui.library

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Process
import com.ggumtak.readeraplus.data.FileScanner
import com.ggumtak.readeraplus.settings.Settings
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Process-wide background jobs of the library (storage scan and SAF folder import), so a recreated
 * activity doesn't start a second scan and can show the running one's progress. Listeners are called on
 * the main thread.
 */
internal object LibraryJobs {
    /** Raw pref: time of the last completed scan (ms). */
    const val PREF_LAST_SCAN = "lastScanAt"

    /** Status-row updates during a scan: each one is an e-ink refresh, so at most once a second. */
    private const val PROGRESS_INTERVAL_MS = 1000L

    interface Listener {
        /** Progress or state changed (status row). */
        fun onJobProgress()
        /** A job finished: reload the list. [message] is a short user-facing summary or null. */
        fun onJobDone(message: String?)
    }

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<Listener>()

    @Volatile var scanning = false
        private set
    @Volatile var scanFound = 0
        private set
    @Volatile var importing = false
        private set
    @Volatile var imported = 0
        private set
    @Volatile var importTotal = 0
        private set

    fun addListener(l: Listener) { listeners.addIfAbsent(l) }
    fun removeListener(l: Listener) { listeners.remove(l) }

    fun status(): String? = LibraryText.statusText(scanning, scanFound, importing, imported, importTotal)

    private fun progress() = main.post { listeners.forEach { it.onJobProgress() } }
    private fun done(msg: String?) = main.post { listeners.forEach { it.onJobDone(msg) } }

    /** Starts a scan unless one is running. [announce] = report the result as a message. */
    fun startScan(context: Context, announce: Boolean): Boolean {
        synchronized(this) {
            if (scanning) return false
            scanning = true
            scanFound = 0
        }
        val app = context.applicationContext
        progress()
        background("library-scan") {
            var msg: String? = null
            try {
                var lastPost = 0L
                val total = FileScanner.scan(app) { n ->
                    scanFound = n
                    val now = System.currentTimeMillis()
                    if (now - lastPost >= PROGRESS_INTERVAL_MS) {
                        lastPost = now
                        progress()
                    }
                }
                Settings.raw().edit().putLong(PREF_LAST_SCAN, System.currentTimeMillis()).apply()
                if (announce) msg = "스캔 완료: 문서 ${total}개"
            } catch (t: Throwable) {
                msg = "스캔 실패: ${t.message ?: t.javaClass.simpleName}"
            } finally {
                scanning = false
            }
            done(msg)
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
        background("library-import") {
            var msg: String?
            try {
                msg = work { d, t ->
                    imported = d
                    importTotal = t
                    progress()
                }
            } catch (t: Throwable) {
                msg = "가져오기 실패: ${t.message ?: t.javaClass.simpleName}"
            } finally {
                importing = false
            }
            done(msg)
        }
        return true
    }

    private fun background(name: String, block: () -> Unit) {
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            block()
        }, name).apply { isDaemon = true }.start()
    }
}
