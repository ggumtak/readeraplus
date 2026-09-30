package com.ggumtak.readeraplus.ui.library

/**
 * Multi-select state of the library's book list (T1-13): a long-press starts it with that book checked, taps toggle
 * books, Back / [닫기] / a batch action end it. Holds book ids in the order they were checked. Plain state, no views
 * (JVM-testable); main thread only.
 */
internal class BookSelection {
    /** Selection mode is on (the toolbar shows "N권 선택"). It stays on with nothing checked until ended. */
    var active = false
        private set

    private val ids = LinkedHashSet<Long>()

    val size: Int get() = ids.size

    operator fun contains(id: Long): Boolean = active && id in ids

    /** Starts selection mode with [id] checked (a long-press). */
    fun start(id: Long) {
        active = true
        ids.clear()
        ids += id
    }

    /** Checks or unchecks [id]; returns whether it is now checked. No-op (false) outside selection mode. */
    fun toggle(id: Long): Boolean {
        if (!active) return false
        if (!ids.remove(id)) {
            ids += id
            return true
        }
        return false
    }

    /**
     * [전체]: checks every book of [shown]; when all of them are checked already it unchecks them instead. Returns
     * true when anything changed.
     */
    fun toggleAll(shown: List<Long>): Boolean {
        if (!active || shown.isEmpty()) return false
        if (ids.containsAll(shown)) ids.removeAll(shown.toSet()) else ids.addAll(shown)
        return true
    }

    /** Drops ids that are no longer listed (after a reload: moved away, trashed, filtered out). True when any went. */
    fun retain(shown: Set<Long>): Boolean = active && ids.retainAll(shown)

    /** The checked ids in check order (a copy: safe to hand to an IO job). */
    fun snapshot(): List<Long> = ids.toList()

    /** The only checked id when exactly one book is checked ([더보기]), else null. */
    fun single(): Long? = if (active && ids.size == 1) ids.first() else null

    fun end() {
        active = false
        ids.clear()
    }
}
