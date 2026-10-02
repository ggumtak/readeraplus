package com.ggumtak.readeraplus.reader

/** Holds transient system bars shown by a dialog until the reader's window has settled. */
internal class InsetsGate {
    private var applied: IntArray? = null
    private var pending: IntArray? = null
    val hasPending: Boolean get() = pending != null

    fun offer(insets: IntArray, settled: Boolean, forced: Boolean): IntArray? {
        if (applied?.contentEquals(insets) == true) {
            pending = null
            return null
        }
        if (applied == null || forced || settled) {
            val copy = insets.copyOf()
            applied = copy
            pending = null
            return copy
        }
        pending = insets.copyOf()
        return null
    }

    fun settle(now: IntArray): IntArray? {
        if (pending == null) return null
        pending = null
        return offer(now, settled = true, forced = false)
    }

    companion object { const val SETTLE_MS = 400L }
}
