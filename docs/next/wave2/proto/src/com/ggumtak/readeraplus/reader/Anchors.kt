package com.ggumtak.readeraplus.reader

import com.ggumtak.readeraplus.engine.OBJECT_CHAR
import com.ggumtak.readeraplus.engine.SectionContent
import com.ggumtak.readeraplus.engine.SectionLayout

/**
 * U6: where one section of a layout generation is forced to start a page: the first char of the page being read.
 * Immutable; resolved identically on the layout and the count thread (pure).
 * [needle]: visible text at the anchor before a re-parse (TXT options), re-found near [offset] in the new text.
 */
class AnchorSpec(val section: Int, val offset: Int, val needle: String? = null) {
    /** The break offset in [content] (the needle's new place when found, else [offset]); -1 = no break. */
    fun resolve(content: SectionContent): Int {
        val len = content.length
        if (len <= 1) return -1
        val est = offset.coerceIn(0, len)
        val found = if (needle != null) TextRefind.find(content.text, est, needle) else -1
        val at = if (found >= 0) found else est
        return if (at in 1 until len) at else -1
    }
}

/** U6: page choices after an anchored relayout / open (pure). */
internal object AnchorMath {
    /** The page to show for [offset]: the one its anchor break opened when [l] was anchored there, else the one holding it. */
    fun pageFor(l: SectionLayout, offset: Int): Int =
        if (l.anchorPage >= 0 && l.anchorBreak == offset) l.anchorPage else l.pageForOffset(offset.coerceIn(0, l.content.length))
}

/** U6: finds the reading position again in a re-parsed text (whitespace-insensitive; pure). */
internal object TextRefind {
    /** Visible chars taken at the old anchor. */
    const val NEEDLE = 24
    /** Fewer visible chars than this (a section's last line) are not worth searching for. */
    const val MIN_NEEDLE = 6
    /** Chars searched on each side of the estimate. */
    const val WINDOW = 8192

    private fun skip(c: Char): Boolean = Character.isWhitespace(c) || Character.isSpaceChar(c) || c == OBJECT_CHAR

    /** Up to [NEEDLE] visible chars of [text] from [offset] (whitespace skipped), or null when fewer than [MIN_NEEDLE]. */
    fun snippet(text: String, offset: Int): String? {
        if (offset < 0 || offset >= text.length) return null
        val sb = StringBuilder(NEEDLE)
        var i = offset
        val stop = minOf(text.length, offset + NEEDLE * 8)
        while (i < stop && sb.length < NEEDLE) {
            val c = text[i]
            if (!skip(c)) sb.append(c)
            i++
        }
        return if (sb.length >= MIN_NEEDLE) sb.toString() else null
    }

    /** Offset (a visible char) where [needle] starts in [text] ignoring whitespace, nearest to [estimate]; -1 = absent. */
    fun find(text: String, estimate: Int, needle: String): Int {
        if (needle.isEmpty() || text.isEmpty()) return -1
        val e = estimate.coerceIn(0, text.length - 1)
        val first = needle[0]
        for (d in 0..WINDOW) {
            val a = e + d
            val b = e - d
            if (a >= text.length && b < 0) break
            if (a < text.length && text[a] == first && matches(text, a, needle)) return a
            if (d > 0 && b >= 0 && text[b] == first && matches(text, b, needle)) return b
        }
        return -1
    }

    private fun matches(text: String, at: Int, needle: String): Boolean {
        var i = at
        var j = 0
        while (j < needle.length) {
            if (i >= text.length) return false
            val c = text[i]
            if (skip(c)) {
                i++
                continue
            }
            if (c != needle[j]) return false
            i++
            j++
        }
        return true
    }
}

/** When BookSession writes page counts (excerpt: the U6 addition next to maskFailed). */
internal object CountSavesU6 {
    /** The anchored section's count is this generation's, not the un-anchored one the cache keys: keep the cache's own. */
    fun maskAnchor(arr: IntArray, section: Int, cached: Int) {
        if (section in arr.indices) arr[section] = if (cached >= 1) cached else -1
    }
}

/**
 * U6a: when new window insets may resize the page. Changes that arrive while the reader window has no focus (a dialog
 * or a popup took it, and with it the system bars) wait; once the window has had the focus back for [SETTLE_MS] the
 * insets then current are taken, normally the old ones again. Pure; main thread.
 */
internal class InsetsGate {
    private var applied: IntArray? = null
    private var pending: IntArray? = null

    val hasPending: Boolean get() = pending != null

    /**
     * New insets [i]. [settled]: the window has had the focus for at least [SETTLE_MS]. [forced]: nothing is shown
     * yet, a configuration / window-size change, or the fullscreen setting changed. Returns the insets to apply now.
     */
    fun offer(i: IntArray, settled: Boolean, forced: Boolean): IntArray? {
        val cur = applied
        if (cur != null && cur.contentEquals(i)) {
            pending = null
            return null
        }
        if (cur == null || forced || settled) {
            val c = i.copyOf()
            applied = c
            pending = null
            return c
        }
        pending = i.copyOf()
        return null
    }

    /** The focus has been back for [SETTLE_MS]: [now] = the window's insets at this moment. */
    fun settle(now: IntArray): IntArray? {
        if (pending == null) return null
        pending = null
        return offer(now, settled = true, forced = false)
    }

    companion object {
        const val SETTLE_MS = 400L
    }
}
