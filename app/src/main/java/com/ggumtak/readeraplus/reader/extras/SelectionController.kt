package com.ggumtak.readeraplus.reader.extras

import android.view.MotionEvent
import com.ggumtak.readeraplus.reader.ReaderHost

/**
 * CONTRACT STUB — long-press text selection with handles and an action popup
 * (copy, quote, note, share, search, dictionary/translate, web search).
 */
class SelectionController(private val host: ReaderHost) {
    val isActive: Boolean get() = TODO("reader-extras")
    /** Starts a selection at the word under view coordinates (x, y). Returns true if something was selected. */
    fun startAt(x: Float, y: Float): Boolean = TODO("reader-extras")
    /** Touch events while active (handle dragging; a tap outside clears). Returns true if consumed. */
    fun onTouchEvent(ev: MotionEvent): Boolean = TODO("reader-extras")
    fun clear(): Unit = TODO("reader-extras")
    /** Called by the host after page changes / relayout so handles and popup follow or close. */
    fun onPageChanged(): Unit = TODO("reader-extras")
}
