package com.ggumtak.readeraplus.reader

import android.annotation.TargetApi
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.os.Build
import com.ggumtak.readeraplus.R
import com.ggumtak.readeraplus.ui.library.LibraryActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * The launcher icon's long-press shortcuts: the books last opened here, newest first (the user, 2026-10-11: features
 * other readers have that make daily use easier). Each opens the library without its open-last start, then the book
 * on top, so Back returns to the library. Published off the main thread (binder calls), once per opened book; the
 * launcher keeps at most [MAX] and drops the oldest. A book removed since then opens the reader's error panel, and
 * leaves the menu as other books are opened.
 */
internal object ReaderShortcuts {
    const val MAX = 3
    /** What launchers show under the icon menu; longer titles are cut with an ellipsis. */
    const val LONG_LABEL_MAX = 25
    const val SHORT_LABEL_MAX = 10
    /** Not MAIN: the library opened under the book must not start its own open-last. */
    private const val ACTION_LIBRARY = "com.ggumtak.readeraplus.action.LIBRARY"
    private const val ACTION_READ = "com.ggumtak.readeraplus.action.READ"

    /** [title] cut to [max] chars (an ellipsis replacing the end), blank → "책". Pure. */
    fun label(title: String, max: Int): String {
        val t = title.trim().replace(Regex("\\s+"), " ").ifEmpty { "책" }
        if (t.length <= max) return t
        return t.substring(0, (max - 1).coerceAtLeast(1)).trimEnd() + "…"
    }

    fun id(bookId: Long): String = "book_$bookId"

    /** Puts [bookId] first among the shortcuts. Never throws; does nothing before Android 7.1. */
    fun remember(context: Context, bookId: Long, title: String, scope: CoroutineScope) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1 || bookId <= 0) return
        val app = context.applicationContext
        scope.launch(Dispatchers.IO) { runCatching { push(app, bookId, title) } }
    }

    @TargetApi(Build.VERSION_CODES.N_MR1)
    private fun push(context: Context, bookId: Long, title: String) {
        val sm = context.getSystemService(ShortcutManager::class.java) ?: return
        val library = Intent(ACTION_LIBRARY).setClass(context, LibraryActivity::class.java)
        val read = Intent(ACTION_READ).setClass(context, ReaderActivity::class.java)
            .putExtra(ReaderActivity.EXTRA_BOOK_ID, bookId)
        val info = ShortcutInfo.Builder(context, id(bookId))
            .setShortLabel(label(title, SHORT_LABEL_MAX))
            .setLongLabel(label(title, LONG_LABEL_MAX))
            .setIcon(Icon.createWithResource(context, R.mipmap.ic_launcher))
            .setIntents(arrayOf(library, read))
            .setRank(0)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            sm.pushDynamicShortcut(info)
            // pushDynamicShortcut keeps up to the launcher's limit; ours is MAX.
            val extra = sm.dynamicShortcuts.sortedBy { it.rank }.drop(MAX).map { it.id }
            if (extra.isNotEmpty()) sm.removeDynamicShortcuts(extra)
        } else {
            // Not a re-publish of the copies dynamicShortcuts returns: those come without their icons.
            sm.addDynamicShortcuts(listOf(info))
            val extra = sm.dynamicShortcuts.filter { it.id != info.id }.sortedBy { it.rank }.drop(MAX - 1).map { it.id }
            if (extra.isNotEmpty()) sm.removeDynamicShortcuts(extra)
        }
    }
}
