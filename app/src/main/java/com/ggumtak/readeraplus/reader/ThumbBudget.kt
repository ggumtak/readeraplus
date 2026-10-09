package com.ggumtak.readeraplus.reader

/**
 * Pure size of the thumbnail LRU (NOTES_SPEC §12.6; unit-tested): `min(8 MB, memoryClass MB / 32)` on e-ink,
 * `min(16 MB, …)` on phones. One Comet thumbnail (156 × 312 RGB_565) is ≈ 97 KB, so 8 MB ≈ 7 grid pages.
 */
internal object ThumbBudget {
    const val MB = 1024 * 1024
    const val EINK_CAP_MB = 8
    const val PHONE_CAP_MB = 16
    /** Floor for a bogus or tiny memory class: one grid page always fits. */
    const val MIN_BYTES = 2 * MB

    /** Bytes for an app with [memoryClassMb] (ActivityManager.memoryClass). */
    fun budget(memoryClassMb: Int, eink: Boolean): Int {
        val cap = (if (eink) EINK_CAP_MB else PHONE_CAP_MB) * MB
        val share = (memoryClassMb.coerceAtLeast(0).toLong() * MB / 32).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        return minOf(cap, share).coerceAtLeast(MIN_BYTES)
    }
}
