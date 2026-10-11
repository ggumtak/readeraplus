package com.ggumtak.readeraplus

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The last crash, kept on disk so the next launch can show it (no logcat on a user's phone): the uncaught exception's
 * stack trace, the app version and the time. One file, replaced by every crash, deleted once shown.
 */
object CrashLog {
    private const val FILE = "last_crash.txt"
    private const val MAX_CHARS = 16_000

    fun install(ctx: Context) {
        val file = File(ctx.filesDir, FILE)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching { file.writeText(format(thread.name, e)) }
            previous?.uncaughtException(thread, e)
        }
    }

    /** Set once this process has looked: a crash of this process ends it, so only an earlier run leaves a file. */
    @Volatile private var taken = false

    /**
     * The crash recorded by an earlier run, removed from disk; null when there is none. Only the first call of a
     * process touches the disk (the library calls this on every resume).
     */
    fun take(ctx: Context): String? {
        if (taken) return null
        taken = true
        val file = File(ctx.filesDir, FILE)
        if (!file.exists()) return null
        val text = runCatching { file.readText() }.getOrNull()
        file.delete()
        return text?.takeIf { it.isNotBlank() }
    }

    internal fun format(thread: String, e: Throwable): String {
        val sw = StringWriter()
        e.printStackTrace(PrintWriter(sw))
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date())
        val head = "ReaderaPlus ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · $time · thread $thread\n"
        return (head + sw).take(MAX_CHARS)
    }
}
