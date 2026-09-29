package com.ggumtak.readeraplus.reader

import com.ggumtak.readeraplus.format.DocPosition
import com.ggumtak.readeraplus.format.TocEntry

/**
 * TOC positions for header titles, chapter jumps and "pages left in chapter" (pure; main thread only).
 *
 * EPUB entries with an anchor start at [TocEntry.offset] and are refined with [resolveAnchors] once the
 * section's content is known (layout or background counting). Entries pointing outside the book are ignored.
 * Queries are linear scans (TOCs have at most a few thousand entries) and tolerate unsorted TOCs.
 */
class ChapterIndex(entries: List<TocEntry>, sectionCount: Int) {
    private val valid = entries.filter { it.section in 0 until sectionCount }
    val size: Int = valid.size
    private val titles = Array(size) { valid[it].title }
    private val sections = IntArray(size) { valid[it].section }
    private val offsets = IntArray(size) { valid[it].offset.coerceAtLeast(0) }
    private val anchors = Array(size) { valid[it].anchor?.takeIf { a -> a.isNotEmpty() } }

    fun title(i: Int): String = titles[i]
    fun section(i: Int): Int = sections[i]
    fun offset(i: Int): Int = offsets[i]
    fun position(i: Int): DocPosition = DocPosition(sections[i], offsets[i])

    /** Sets the offsets of [section]'s anchored entries from its anchor map. Returns true if any moved. */
    fun resolveAnchors(section: Int, anchorMap: Map<String, Int>): Boolean {
        var changed = false
        for (i in 0 until size) {
            if (sections[i] != section) continue
            val a = anchors[i] ?: continue
            val off = anchorMap[a]
            anchors[i] = null // resolved (or unresolvable): don't look again
            if (off != null && off >= 0 && off != offsets[i]) {
                offsets[i] = off
                changed = true
            }
        }
        return changed
    }

    /** Index of the entry the position (section, offset) belongs to: the last entry at or before it; -1 if none. */
    fun indexAt(section: Int, offset: Int): Int {
        val target = pack(section, offset)
        var best = -1
        var bestPos = Long.MIN_VALUE
        for (i in 0 until size) {
            val p = pack(sections[i], offsets[i])
            if (p <= target && p >= bestPos) {
                best = i
                bestPos = p
            }
        }
        return best
    }

    /** First entry strictly after (section, offset); -1 if none. */
    fun nextAfter(section: Int, offset: Int): Int {
        val target = pack(section, offset)
        var best = -1
        var bestPos = Long.MAX_VALUE
        for (i in 0 until size) {
            val p = pack(sections[i], offsets[i])
            if (p > target && p < bestPos) {
                best = i
                bestPos = p
            }
        }
        return best
    }

    /** Last entry strictly before (section, offset); -1 if none. */
    fun lastBefore(section: Int, offset: Int): Int {
        val target = pack(section, offset)
        var best = -1
        var bestPos = Long.MIN_VALUE
        for (i in 0 until size) {
            val p = pack(sections[i], offsets[i])
            if (p < target && p > bestPos) {
                best = i
                bestPos = p
            }
        }
        return best
    }

    private fun pack(section: Int, offset: Int): Long = (section.toLong() shl 32) or (offset.toLong() and 0xFFFFFFFFL)
}
