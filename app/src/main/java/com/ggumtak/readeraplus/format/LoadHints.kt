package com.ggumtak.readeraplus.format

/**
 * Per-thread hints for [BookDocument] loading. A thread that walks the whole book in the background (page counting)
 * marks itself with [markBackground]; a document can then keep such bulk loads from evicting the sections and
 * converted items the reader is using from its bounded caches. Unmarked threads (the reader's layout, open) load as
 * usual.
 */
object LoadHints {
    private val background = ThreadLocal<Boolean>()

    /** Marks the current thread as a background bulk loader for the rest of its life. */
    fun markBackground() {
        background.set(true)
    }

    /** True on a thread marked by [markBackground]. */
    val isBackground: Boolean
        get() = background.get() == true

    /** Runs [block] on the current thread marked as a background loader, restoring the prior state afterwards. */
    fun <T> asBackground(block: () -> T): T {
        if (isBackground) return block()
        background.set(true)
        return try {
            block()
        } finally {
            background.remove()
        }
    }
}
