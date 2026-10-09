package com.ggumtak.readeraplus.reader

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.ggumtak.readeraplus.data.ReaderPresence
import com.ggumtak.readeraplus.render.DeviceClass
import com.ggumtak.readeraplus.settings.Settings
import java.util.concurrent.atomic.AtomicBoolean
import android.provider.Settings as SystemSettings

/**
 * The device's own brightness setting (Settings.System.SCREEN_BRIGHTNESS), for front lights that ignore the window
 * override (brightness.md §3.2 with UI_SPEC §4.3 fixes 1–4). Opt-in (AppSettings.brightnessDevice), only with the
 * WRITE_SETTINGS special access.
 *
 * Global on purpose: the setting is the device's, as the UI says. The value and mode found before the first write are
 * committed to disk first. [restore] puts them back when the reader leaves (AppSettings.brightnessRestore), and
 * [restoreIfStale] repairs a crash on the next library start. A level the user set meanwhile is never overwritten
 * ([LightCurve.stillOurs]), and an automatic mode always comes back.
 *
 * State lives in the local `reader_light` prefs file (never Settings.raw(), so never in a JSON backup: a restored
 * "value to put back" would be stale).
 *
 * Threading: every read and write of the setting runs on ONE serial thread, so a restore is never overtaken by a
 * late drag write. The main thread only stores a float and posts a reused Runnable. No allocation, no binder call, and
 * at most one write per [MIN_GAP_MS] however fast the finger moves. The verdict/ask accessors are IO-thread calls.
 */
internal object DeviceLight {
    private const val TAG = "DeviceLight"
    private const val PREFS = "reader_light"
    private const val K_PENDING = "pending"
    private const val K_ORIG = "orig"
    private const val K_ORIG_MODE = "origMode"
    private const val K_STAMP = "stamp"
    private const val K_LAST = "last"
    private const val K_VERDICT = "verdict"
    private const val K_VERDICT_FP = "verdictFp"
    private const val K_ASKS = "asks"
    private const val MANUAL = SystemSettings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
    private const val KEY = SystemSettings.System.SCREEN_BRIGHTNESS
    private const val KEY_MODE = SystemSettings.System.SCREEN_BRIGHTNESS_MODE

    const val VERDICT_UNKNOWN=0; const val VERDICT_WINDOW=1; const val VERDICT_DEVICE=2; const val VERDICT_NONE=3
    /** ≤ 10 writes a second while dragging: each is a settings-file write in system_server. */
    const val MIN_GAP_MS=100L; const val ECHO_MS=1500L

    /** The permission is missing or went away (revoked in the system settings): the reader uses the window path. */
    @Volatile var noPermission=false
    /** The setting as last read or written (0..1 light, < 0 = unknown). Written on the light thread. */
    @Volatile var deviceOut=-1f

    /** Main-thread callbacks, set by the reader (Runnables: no allocation when they fire). */
    @Volatile var onExternal: Runnable?=null
    @Volatile var onNoPermission: Runnable?=null

    /** The original mode was automatic (phones): the "나가도 그대로 유지" subtitle says it comes back. */
    @Volatile var origAuto = false
        private set

    @Volatile private var ctx: Context? = null
    @Volatile private var handler: Handler? = null
    private val main by lazy { Handler(Looper.getMainLooper()) }
    @Volatile private var targetOut = Float.NaN
    private val queued = AtomicBoolean(false)
    @Volatile private var lastWriteAt = 0L
    @Volatile private var lastWritten = -1
    @Volatile private var restoreLevel = true
    @Volatile private var settleWanted = false
    private var modeForced = false // light thread only
    private var permChecked = false // light thread only (fix 3: once per enable)

    /** Stores the application context only (no IO): the reader calls it after its first page. */
    fun init(ctx: Context) { if (this.ctx == null) this.ctx = ctx.applicationContext }

    private fun h(): Handler = handler ?: synchronized(this) {
        handler ?: Handler(HandlerThread("reader-light").apply { start() }.looper).also { handler = it }
    }

    private fun prefs(c: Context): SharedPreferences = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---------------------------------------------------------------- writes (main thread posts, light thread works)

    /** Main thread, any rate. [out] = light 0..1 ([LightCurve.out] of the slider position). The latest value wins. */
    fun set(out: Float) {
        targetOut = out
        if (!queued.compareAndSet(false, true)) return
        val wait = (lastWriteAt + MIN_GAP_MS - SystemClock.uptimeMillis()).coerceAtLeast(0L)
        h().postDelayed(writeTask, wait)
    }

    private val writeTask = Runnable {
        queued.set(false)
        val out = targetOut
        val c = ctx
        if (!out.isNaN() && c != null) write(c, out)
        lastWriteAt = SystemClock.uptimeMillis()
        if (settleWanted) persistLast()
    }

    private fun write(c: Context, out: Float) {
        val cr = c.contentResolver
        try {
            if (!permChecked) {
                // Fix 3: no pending record without the permission (a restored or revoked switch).
                if (!SystemSettings.System.canWrite(c)) { lostPermission(); return }
                permChecked = true
            }
            val p = prefs(c)
            val level = LightCurve.level(out)
            if (!modeForced) {
                // A pending record from another install (auto backup) is stale: captured afresh.
                val pending = p.getBoolean(K_PENDING, false) && p.getLong(K_STAMP, 0L) == installStamp(c)
                if (LightPolicy.recordOriginal(pending)) {
                    // What to put back, on disk BEFORE the first write (commit): a crash right after still restores.
                    val mode = SystemSettings.System.getInt(cr, KEY_MODE, MANUAL)
                    p.edit()
                        .putBoolean(K_PENDING, true)
                        .putInt(K_ORIG, SystemSettings.System.getInt(cr, KEY, -1))
                        .putInt(K_ORIG_MODE, mode)
                        .putLong(K_STAMP, installStamp(c))
                        .putInt(K_LAST, level)
                        .commit()
                    origAuto = mode != MANUAL
                } else {
                    // A crashed reader's original is kept (C35/K13); only our new level is noted for fix 1.
                    origAuto = p.getInt(K_ORIG_MODE, MANUAL) != MANUAL
                    p.edit().putInt(K_LAST, level).apply()
                }
                // In automatic mode the value is ignored (phones). The Comet has no light sensor: already manual.
                if (SystemSettings.System.getInt(cr, KEY_MODE, MANUAL) != MANUAL) SystemSettings.System.putInt(cr, KEY_MODE, MANUAL)
                modeForced = true
            }
            if (level != lastWritten) {
                SystemSettings.System.putInt(cr, KEY, level)
                lastWritten = level
            }
            deviceOut = LightCurve.fraction(level)
            noPermission = false
        } catch (e: SecurityException) {
            lostPermission()
        } catch (t: Throwable) {
            Log.w(TAG, "write failed", t)
        }
    }

    private fun lostPermission() {
        noPermission = true
        permChecked = false
        onNoPermission?.let { main.post(it) }
    }

    /**
     * IO, after the first page: [origAuto] before the first write of this process (the pending original's mode, else
     * the device's current one, which the first write would record).
     */
    fun loadOrigAuto(ctx: Context) {
        readOrigAuto(ctx)?.let { origAuto = it } // unknown: the plain subtitle
    }

    /** IO: [loadOrigAuto]'s value without setting it (the settings page's subtitle); null when unknown. */
    fun readOrigAuto(ctx: Context): Boolean? = try {
        val p = prefs(ctx)
        val mode = if (p.getBoolean(K_PENDING, false)) p.getInt(K_ORIG_MODE, MANUAL)
        else SystemSettings.System.getInt(ctx.contentResolver, KEY_MODE, MANUAL)
        mode != MANUAL
    } catch (t: Throwable) {
        null
    }

    /**
     * Main thread, when a drag finishes: the level we end on is persisted (fix 1), after any queued write. Never per
     * drag write.
     */
    fun settle() {
        val hh = handler ?: return
        settleWanted = true
        if (!queued.get()) hh.post(persistTask)
    }

    private val persistTask = Runnable { persistLast() }

    private fun persistLast() {
        settleWanted = false
        val c = ctx ?: return
        val last = lastWritten
        if (last >= 0) prefs(c).edit().putInt(K_LAST, last).apply()
    }

    /** Puts back what the reader found (level and mode), after any queued write. Any thread. */
    fun restore() = restore(level = true)

    /**
     * [level] false ("나가도 그대로 유지"): keeps the reader's level but still brings an automatic mode back (fix 2).
     * Nothing happens when nothing is pending.
     */
    fun restore(level: Boolean) {
        val hh = handler ?: (if (ctx != null) h() else return)
        hh.removeCallbacks(writeTask)
        queued.set(false)
        targetOut = Float.NaN
        restoreLevel = level
        hh.post(restoreTask)
    }

    private val restoreTask = Runnable {
        val c = ctx ?: return@Runnable
        val p = prefs(c)
        var out = -1f
        if (p.getBoolean(K_PENDING, false)) {
            var done = true
            try {
                val cr = c.contentResolver
                val current = SystemSettings.System.getInt(cr, KEY, -1)
                val last = if (lastWritten >= 0) lastWritten else p.getInt(K_LAST, -1)
                val orig = p.getInt(K_ORIG, -1)
                val mode = p.getInt(K_ORIG_MODE, MANUAL)
                // A reinstall (auto backup restored this file) must not put back a months-old value.
                val stampOk = p.getLong(K_STAMP, 0L) == installStamp(c)
                val act = LightPolicy.restoreActions(stampOk, restoreLevel, orig, current, last, mode != MANUAL)
                if (act and LightPolicy.RESTORE_LEVEL != 0) SystemSettings.System.putInt(cr, KEY, orig)
                if (act and LightPolicy.RESTORE_MODE != 0) SystemSettings.System.putInt(cr, KEY_MODE, mode)
                val now = if (act and LightPolicy.RESTORE_LEVEL != 0) orig else current
                if (now >= 0) out = LightCurve.fraction(now, LightCurve.LEVEL_MIN, maxOf(LightCurve.LEVEL_MAX, now))
            } catch (e: SecurityException) {
                // Revoked meanwhile: keep the record, so a later restore (after a re-grant) still puts it back.
                done = false
            } catch (t: Throwable) {
                Log.w(TAG, "restore failed", t)
            }
            if (done) p.edit().putBoolean(K_PENDING, false).remove(K_LAST).commit()
        }
        lastWritten = -1
        modeForced = false
        permChecked = false
        settleWanted = false
        deviceOut = out
    }

    /**
     * Library start, IO, after its first list: a reader that crashed while in front left its value behind. The
     * level comes back only with "리더를 나가면 원래 밝기로" on and while the device still shows the reader's level.
     */
    fun restoreIfStale(ctx: Context) {
        init(ctx)
        if (ReaderPresence.inFront) return
        if (!prefs(ctx).getBoolean(K_PENDING, false)) return
        val level = try { Settings.app.brightnessRestore } catch (t: Throwable) { true }
        restore(level)
    }

    // ---------------------------------------------------------------- reads / external changes

    /** Re-reads the setting (after the observer fired, or to position the slider). Any thread. */
    fun refresh() { if (ctx != null) h().post(readTask) }

    /**
     * Main thread: [r] runs on main after the light thread's work queued so far (a [restore] and [refresh] posted
     * before it), so [deviceOut] is the device's level by then. False when nothing could be queued (not initialised).
     */
    fun postAfterRead(r: Runnable): Boolean {
        if (ctx == null) return false
        h().post { main.post(r) }
        return true
    }

    private val readTask = Runnable {
        val c = ctx ?: return@Runnable
        val v = try { SystemSettings.System.getInt(c.contentResolver, KEY, -1) } catch (t: Throwable) { -1 }
        if (v < 0) return@Runnable
        deviceOut = LightCurve.fraction(v, LightCurve.LEVEL_MIN, maxOf(LightCurve.LEVEL_MAX, v))
        val since = SystemClock.uptimeMillis() - lastWriteAt
        if (LightCurve.isExternal(v, lastWritten, since, queued.get(), ECHO_MS)) {
            // The user set the light in the device's own panel while reading: keep theirs, put nothing back on exit.
            // Their level becomes the one to put back; the original mode (fix 2) stays recorded.
            val p = prefs(c)
            if (p.getBoolean(K_PENDING, false)) p.edit().putInt(K_ORIG, v).putInt(K_LAST, v).apply()
            lastWritten = v
            onExternal?.let { main.post(it) }
        }
    }

    // ---------------------------------------------------------------- verdict (IO callers)

    /** The verdict for this firmware ([Build.FINGERPRINT]: an OTA asks again). IO thread. */
    fun verdict(ctx: Context): Int {
        val p = prefs(ctx)
        return if (p.getString(K_VERDICT_FP, null) == Build.FINGERPRINT) p.getInt(K_VERDICT, VERDICT_UNKNOWN) else VERDICT_UNKNOWN
    }

    /** IO thread. Also clears the unanswered-question count ("밝기 방식 다시 확인" passes UNKNOWN). */
    fun setVerdict(ctx: Context, v: Int) {
        prefs(ctx).edit().putInt(K_VERDICT, v).putString(K_VERDICT_FP, Build.FINGERPRINT).putInt(K_ASKS, 0).apply()
    }

    /** Sessions in which the question was shown but not answered; the reader stops asking after 3. IO thread. */
    fun asks(ctx: Context): Int = prefs(ctx).getInt(K_ASKS, 0)
    fun countAsk(ctx: Context) { prefs(ctx).edit().putInt(K_ASKS, asks(ctx) + 1).apply() }

    /**
     * True where the front light may ignore the window override, so the reader asks once after a drag. IO thread:
     * [DeviceClass] may run the vendor probe (fix 4: makers by MANUFACTURER/BRAND, never device codenames). A false
     * negative only skips the question; the option is always in the brightness panel.
     */
    fun looksEink(ctx: Context): Boolean = DeviceClass.cached(ctx) ?: DeviceClass.probe(ctx)

    @Suppress("DEPRECATION")
    private fun installStamp(c: Context): Long = try {
        c.packageManager.getPackageInfo(c.packageName, 0).firstInstallTime
    } catch (t: Throwable) {
        0L
    }
}
