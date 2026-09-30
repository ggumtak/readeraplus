package com.ggumtak.readeraplus.data

/**
 * Whether a reader screen is in front (set by ReaderActivity.onResume / onPause). Background library work (the
 * periodic auto-scan) checks it between directories and yields, so it never competes with opening or reading a book.
 */
object ReaderPresence {
    @Volatile
    @JvmStatic
    var inFront: Boolean = false
}
