package com.ggumtak.readeraplus.ui.library

/**
 * When [CoverLoader] may decode a thumbnail again after a failure (pure, unit-tested). `Covers.thumbnail` gives a
 * placeholder for a book without a cover or an unreadable file, so its null is always transient (out of memory, a
 * placeholder that could not be drawn either): such a key is retried, but not again within [WAIT_MS], so rows rebound
 * while scrolling do not decode it over and over.
 */
internal object CoverRetry {
    /** Quiet time after a failed thumbnail before the same key is decoded again. */
    const val WAIT_MS = 30_000L
    /** Failure times kept at most; beyond that the expired ones go, and if none has expired, all of them. */
    const val MAX_ENTRIES = 256

    /**
     * True when a key that last failed at [lastFailedAt] (`elapsedRealtime`, or null = never) may be decoded at [now].
     * A clock that went back (a time before the failure) allows it rather than block the key for good.
     */
    fun mayTry(lastFailedAt: Long?, now: Long, waitMs: Long): Boolean {
        if (lastFailedAt == null) return true
        val elapsed = now - lastFailedAt
        return elapsed < 0 || elapsed >= waitMs
    }

    /** Keeps [failedAt] at most [max] entries: drops the expired ones, and everything when that is not enough. */
    fun prune(failedAt: MutableMap<String, Long>, now: Long, waitMs: Long, max: Int) {
        if (failedAt.size <= max) return
        failedAt.entries.removeAll { mayTry(it.value, now, waitMs) }
        if (failedAt.size > max) failedAt.clear()
    }
}
