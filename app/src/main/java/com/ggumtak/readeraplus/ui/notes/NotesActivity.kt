package com.ggumtak.readeraplus.ui.notes

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.ggumtak.readeraplus.data.NotesTab
import com.ggumtak.readeraplus.reader.extras.emptyMessage

class NotesActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContentView(emptyMessage("준비 중")) }
    companion object {
        const val EXTRA_TAB="notes_tab"; const val EXTRA_BOOK_ID="notes_book"
        fun open(ctx: Context, tab: NotesTab?=null, bookId: Long=-1L) {
            ctx.startActivity(Intent(ctx,NotesActivity::class.java).apply {
                if (ctx !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (tab!=null) putExtra(EXTRA_TAB,tab.name)
                putExtra(EXTRA_BOOK_ID,bookId)
            })
        }
    }
}
