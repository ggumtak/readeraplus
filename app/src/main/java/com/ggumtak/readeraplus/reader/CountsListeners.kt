package com.ggumtak.readeraplus.reader

/**
 * The displays waiting for the exact page numbers ([ReaderHost.addCountsListener]). Main thread only. A listener is
 * held once however often it is added; [fire] runs a copy, so a listener may remove itself (or another) meanwhile, and
 * one that throws does not stop the rest.
 */
internal class CountsListeners {
    private val list = ArrayList<() -> Unit>(4)

    val isEmpty: Boolean get() = list.isEmpty()
    val size: Int get() = list.size

    fun add(l: () -> Unit) {
        if (l !in list) list.add(l)
    }

    fun remove(l: () -> Unit) {
        list.remove(l)
    }

    fun fire() {
        if (list.isEmpty()) return
        for (l in list.toTypedArray()) runCatching { l() }
    }
}
