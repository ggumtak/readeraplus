package com.ggumtak.readeraplus.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.ggumtak.readeraplus.settings.Settings
import java.util.UUID

/**
 * Fresh-install detection (S §3.3): this install's id (raw settings prefs, transient: never in our JSON backup) and
 * the restore offer's state. Android Auto Backup may copy the settings prefs, ids included, onto a new install;
 * [verify] notices that by the stored `firstInstallTime` and makes a fresh id, so a copied id never rotates (deletes)
 * the earlier install's backup files.
 */
object InstallState {
    const val KEY_INSTALL_ID="installId"; const val KEY_INSTALLED_AT="installId.at"; const val KEY_OFFER="restoreOffer.state"
    private const val TAG = "InstallState"
    internal const val OFFER_PENDING = "pending"
    internal const val OFFER_DONE = "done"

    private fun prefs(context: Context): SharedPreferences {
        Settings.init(context)
        return Settings.raw()
    }

    /**
     * Main-thread safe (the settings prefs are already loaded by the first Settings.app read). Idempotent: the first
     * call of this install decides — no installId yet → create one; offer = pending iff the settings prefs were empty
     * before this call, else done (an existing user updating, or Android Auto Backup restored the prefs).
     */
    fun ensure(context: Context) {
        val p = prefs(context)
        if (!p.getString(KEY_INSTALL_ID, null).isNullOrEmpty()) return
        synchronized(this) {
            if (!p.getString(KEY_INSTALL_ID, null).isNullOrEmpty()) return
            val pending = decide(p.all.keys)
            p.edit()
                .putString(KEY_INSTALL_ID, newId())
                .putLong(KEY_INSTALLED_AT, firstInstallTime(context))
                .putString(KEY_OFFER, if (pending) OFFER_PENDING else OFFER_DONE)
                .apply() // main thread: in memory at once, on disk in the background

        }
    }

    /**
     * Blocking (IO / backup thread, never the main thread): when KEY_INSTALLED_AT differs from this package's
     * firstInstallTime, the id was copied from another install by Android Auto Backup → replace it with a fresh id
     * (the offer state is left alone: a restored "done" is right, the data came back with it).
     */
    fun verify(context: Context) {
        val p = prefs(context)
        if (p.getString(KEY_INSTALL_ID, null).isNullOrEmpty()) {
            ensure(context)
            return
        }
        val first = firstInstallTime(context)
        synchronized(this) {
            val changes = verified(p.getString(KEY_INSTALL_ID, null), p.getLong(KEY_INSTALLED_AT, 0L), first, ::newId)
                ?: return
            val e = p.edit()
            for ((k, v) in changes) {
                when (v) {
                    is String -> e.putString(k, v)
                    is Long -> e.putLong(k, v)
                }
            }
            e.commit()
            Log.i(TAG, "installId replaced (copied from another install)")
        }
    }

    fun installId(context: Context): String = prefs(context).getString(KEY_INSTALL_ID, null) ?: ""

    fun id8(context: Context): String = installId(context).take(8)

    /** True also when no state exists yet (ensure has not run): unknown counts as pending. */
    fun offerPending(context: Context): Boolean = prefs(context).getString(KEY_OFFER, null) != OFFER_DONE

    /** commit(): the answer must survive a kill. */
    fun settleOffer(context: Context) {
        val p = prefs(context)
        if (p.getString(KEY_OFFER, null) == OFFER_DONE) return
        p.edit().putString(KEY_OFFER, OFFER_DONE).commit()
    }

    /** Pure (tests): true = offer pending (nothing of this install's own in the settings prefs yet). */
    internal fun decide(existingKeys: Collection<String>): Boolean = existingKeys.isEmpty()

    /**
     * Pure part of [verify]: the pref changes (key → value) that replace a copied id, or null when the id is this
     * install's. A stored install time that differs from [firstInstallTime] means the prefs came from another
     * install; [firstInstallTime] 0 (unknown) never replaces. [KEY_OFFER] is never among the changes.
     */
    internal fun verified(id: String?, storedAt: Long, firstInstallTime: Long, newId: () -> String): Map<String, Any>? {
        if (id.isNullOrEmpty() || firstInstallTime <= 0L || storedAt == firstInstallTime) return null
        return mapOf(KEY_INSTALL_ID to newId(), KEY_INSTALLED_AT to firstInstallTime)
    }

    /** 32 lower-case hex chars. */
    internal fun newId(): String = UUID.randomUUID().toString().replace("-", "")

    private fun firstInstallTime(context: Context): Long = try {
        context.packageManager.getPackageInfo(context.packageName, 0).firstInstallTime
    } catch (t: Throwable) {
        Log.w(TAG, "firstInstallTime unavailable: $t")
        0L
    }
}
