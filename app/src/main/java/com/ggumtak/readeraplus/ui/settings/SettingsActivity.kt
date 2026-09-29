package com.ggumtak.readeraplus.ui.settings

import android.app.Activity
import android.content.Context
import android.content.Intent

/** CONTRACT STUB — settings UI owner implements the activity; the companion API is fixed. */
class SettingsActivity : Activity() {
    companion object {
        /** [page]: null = main list, or one of PAGE_* to open a sub-page directly. */
        const val EXTRA_PAGE = "page"
        const val PAGE_PAGE_TURNING = "page_turning"
        const val PAGE_FONTS = "fonts"
        const val PAGE_TTS = "tts"

        fun open(context: Context, page: String? = null) {
            context.startActivity(
                Intent(context, SettingsActivity::class.java)
                    .putExtra(EXTRA_PAGE, page)
                    .addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION),
            )
        }
    }
}
