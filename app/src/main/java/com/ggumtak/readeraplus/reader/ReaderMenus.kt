package com.ggumtak.readeraplus.reader

import android.content.Intent
import android.content.pm.ActivityInfo
import android.util.Log
import android.view.View
import com.ggumtak.readeraplus.R
import com.ggumtak.readeraplus.data.Book
import com.ggumtak.readeraplus.data.BookFileProvider
import com.ggumtak.readeraplus.data.Library
import com.ggumtak.readeraplus.format.BookFormat
import com.ggumtak.readeraplus.reader.extras.ReaderPanels
import com.ggumtak.readeraplus.ui.kit.MenuItem
import com.ggumtak.readeraplus.ui.kit.alert
import com.ggumtak.readeraplus.ui.kit.chooser
import com.ggumtak.readeraplus.ui.kit.confirm
import com.ggumtak.readeraplus.ui.kit.popupMenu
import com.ggumtak.readeraplus.ui.kit.prompt
import com.ggumtak.readeraplus.ui.kit.showNoAnim
import com.ggumtak.readeraplus.ui.kit.toast
import com.ggumtak.readeraplus.ui.settings.SettingsActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MENU_TAG = "ReaderMenus"

/** The reader's ⋮ menu (ReadEra order, B/W). */
internal fun ReaderActivity.showOverflowMenu(anchor: View) {
    // Captured now: every action below works on this book even if another one is opened meanwhile.
    val bk = currentBookOrNull() ?: return
    val bookmarked = isCurrentPageBookmarked()
    val items = listOf(
        MenuItem("페이지 이동", R.drawable.ic_find_in_page) { guarded { ReaderPanels.showGoTo(this) } },
        MenuItem(if (bookmarked) "북마크 삭제" else "북마크 추가", R.drawable.ic_bookmark_add) { toggleBookmark() },
        MenuItem(if (autoTurnOn) "자동 넘김 끄기" else "자동 넘김 켜기", R.drawable.ic_timer) { toggleAutoTurn() },
        // After the popup has disappeared from the panel, or its outline stays as ghosting.
        MenuItem("화면 새로고침", R.drawable.ic_refresh) { refreshAfterDraw(POPUP_GONE_MS) },
        MenuItem("내 리뷰", R.drawable.ic_rate_review) { guarded { ReaderPanels.showReview(this) } },
        MenuItem("…에 추가", R.drawable.ic_playlist_add) { showAddMenu(anchor, bk) },
        MenuItem("문서 속성", R.drawable.ic_info) { guarded { ReaderPanels.showDocumentInfo(this, bk, document) } },
        MenuItem("파일 공유", R.drawable.ic_share) { shareBookFile(bk) },
        MenuItem("휴지통으로 이동", R.drawable.ic_delete) { confirmTrash(bk) },
        MenuItem("일반 설정", R.drawable.ic_settings) { guarded { SettingsActivity.open(this) } },
    )
    guarded { popupMenu(anchor, items, widthDp = 260) }
}

private const val POPUP_GONE_MS = 120L

/** "…에 추가": favourites / to read / have read toggles and collections. */
private fun ReaderActivity.showAddMenu(anchor: View, book: Book) {
    val id = book.id
    scope.launch {
        val b = withContext(Dispatchers.IO) {
            try {
                Library.book(id)
            } catch (t: Throwable) {
                null
            }
        } ?: book
        val items = listOf(
            MenuItem("즐겨찾기", if (b.favorite) R.drawable.ic_star_fill else R.drawable.ic_star) {
                flag(if (b.favorite) "즐겨찾기에서 뺐습니다" else "즐겨찾기에 추가했습니다") { Library.setFavorite(id, !b.favorite) }
            },
            MenuItem("읽을 문서", if (b.toRead) R.drawable.ic_schedule_fill else R.drawable.ic_schedule) {
                flag(if (b.toRead) "읽을 문서에서 뺐습니다" else "읽을 문서에 추가했습니다") { Library.setToRead(id, !b.toRead) }
            },
            MenuItem("읽던 문서", if (b.haveRead) R.drawable.ic_done_all_fill else R.drawable.ic_done_all) {
                flag(if (b.haveRead) "읽던 문서에서 뺐습니다" else "읽던 문서에 추가했습니다") { Library.setHaveRead(id, !b.haveRead) }
            },
            MenuItem("컬렉션…", R.drawable.ic_library_books) { showCollections(id) },
        )
        // The anchor (⋮ in the chrome) must still be in a window, or PopupWindow throws BadTokenException.
        if (!isFinishing && !isDestroyed && anchor.isAttachedToWindow) guarded { popupMenu(anchor, items, widthDp = 240) }
    }
}

private fun ReaderActivity.flag(message: String, write: () -> Unit) {
    ReaderIo.launch { write() }
    toast(message)
}

private fun ReaderActivity.showCollections(id: Long) {
    scope.launch {
        val data = withContext(Dispatchers.IO) {
            try {
                Library.collections() to Library.collectionsOf(id)
            } catch (t: Throwable) {
                Log.w(MENU_TAG, "collections failed", t)
                null
            }
        } ?: return@launch
        if (isFinishing || isDestroyed) return@launch
        val (all, mine) = data
        val b = alert().setTitle("컬렉션")
        if (all.isEmpty()) {
            b.setMessage("아직 컬렉션이 없습니다.")
        } else {
            val names = all.map { it.name }.toTypedArray()
            val checked = BooleanArray(all.size) { all[it].id in mine }
            b.setMultiChoiceItems(names, checked) { _, which, isChecked ->
                val cid = all[which].id
                ReaderIo.launch { Library.setInCollection(id, cid, isChecked) }
            }
        }
        b.setPositiveButton("닫기", null)
            .setNeutralButton("새 컬렉션") { _, _ ->
                prompt("새 컬렉션", hint = "컬렉션 이름") { name ->
                    val n = name.trim()
                    if (n.isNotEmpty()) {
                        ReaderIo.launch {
                            val c = Library.createCollection(n)
                            Library.setInCollection(id, c.id, true)
                        }
                        toast("'$n'에 추가했습니다")
                    }
                }
            }
            .showNoAnim()
    }
}

private fun ReaderActivity.shareBookFile(book: Book) {
    try {
        val uri = BookFileProvider.uriFor(packageName, book.id)
        val mime = if (book.format == BookFormat.EPUB) "application/epub+zip" else "text/plain"
        val send = Intent(Intent.ACTION_SEND)
            .setType(mime)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_TITLE, book.fileName)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, "파일 공유").addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION))
    } catch (t: Throwable) {
        Log.w(MENU_TAG, "share failed", t)
        toast("파일을 공유할 수 없습니다")
    }
}

private fun ReaderActivity.confirmTrash(book: Book) {
    val id = book.id
    confirm("휴지통으로 이동", "'${book.title}'을(를) 휴지통으로 옮길까요?", ok = "이동") {
        scope.launch {
            // Finish only after the write, so the library's onResume reload no longer lists the book.
            val ok = withContext(Dispatchers.IO) {
                try {
                    Library.setTrashed(id, true)
                    true
                } catch (t: Throwable) {
                    Log.w(MENU_TAG, "trash failed", t)
                    false
                }
            }
            if (ok) finish() else toast("휴지통으로 옮기지 못했습니다")
        }
    }
}

/** Long-press on the rotation button: pick a fixed orientation or follow the sensor. */
internal fun ReaderActivity.showOrientationChooser() {
    val options = listOf(
        "자동 회전" to ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
        "세로" to ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
        "가로" to ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
        "세로 (뒤집힘)" to ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT,
        "가로 (뒤집힘)" to ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE,
    )
    val selected = options.indexOfFirst { it.second == app.orientationLock }.coerceAtLeast(0)
    chooser("화면 방향", options.map { it.first }, selected) { which -> setOrientationLock(options[which].second) }
}

private inline fun ReaderActivity.guarded(block: () -> Unit) {
    try {
        block()
    } catch (t: Throwable) {
        Log.w(MENU_TAG, "menu action failed", t)
    }
}
