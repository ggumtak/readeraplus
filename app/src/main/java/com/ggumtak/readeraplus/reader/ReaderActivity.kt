package com.ggumtak.readeraplus.reader

import android.app.Activity
import android.content.Context
import android.content.Intent

/** CONTRACT STUB — reader core owner implements the activity; the companion API is fixed. */
class ReaderActivity : Activity() {
    companion object {
        const val EXTRA_BOOK_ID = "book_id"

        fun open(context: Context, bookId: Long) {
            context.startActivity(
                Intent(context, ReaderActivity::class.java)
                    .putExtra(EXTRA_BOOK_ID, bookId)
                    .addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION),
            )
        }
    }
}
