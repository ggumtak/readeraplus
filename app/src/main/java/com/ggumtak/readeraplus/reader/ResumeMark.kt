package com.ggumtak.readeraplus.reader

/**
 * "여기부터" on reopening a book (the user, 2026-10-11): the place reading resumes at is marked like a note jump (the
 * OWNER_JUMP highlight), so the eye finds it after a break. Shown with the first page, in the same e-ink update, and
 * cleared by the first turn or scroll like any jump mark. Pure.
 */
internal object ResumeMark {
    /** A break shorter than this needs no mark (turning the screen off for a moment). */
    const val MIN_AWAY_MS = 30 * 60 * 1000L
    /** The mark covers at most this many characters: the start of the line reading goes on from. */
    const val MAX_CHARS = 40

    /** Whether a book last read at [lastReadAt] and reopened at [now] on (section, offset) gets the mark. */
    fun wanted(lastReadAt: Long, now: Long, section: Int, offset: Int): Boolean =
        lastReadAt > 0 && now - lastReadAt >= MIN_AWAY_MS && (section > 0 || offset > 0)

    /**
     * The marked range of [text] from [offset]: leading line breaks and spaces skipped, then up to the end of that
     * line, at most [MAX_CHARS]. Packed as `start shl 32 or end`; 0 when there is nothing to mark.
     */
    fun range(text: CharSequence, offset: Int): Long {
        var s = offset.coerceIn(0, text.length)
        while (s < text.length && text[s].isWhitespace()) s++
        if (s >= text.length) return 0L
        var e = s
        val cap = minOf(text.length, s + MAX_CHARS)
        while (e < cap && text[e] != '\n') e++
        if (e <= s) return 0L
        return (s.toLong() shl 32) or e.toLong()
    }

    fun start(packed: Long): Int = (packed ushr 32).toInt()
    fun end(packed: Long): Int = (packed and 0xFFFFFFFFL).toInt()
}
