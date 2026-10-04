package com.ggumtak.readeraplus.reader

import android.util.Log
import android.view.View
import com.ggumtak.readeraplus.R
import com.ggumtak.readeraplus.data.Book
import com.ggumtak.readeraplus.data.Library
import com.ggumtak.readeraplus.data.NotesTab
import com.ggumtak.readeraplus.data.Shelf
import com.ggumtak.readeraplus.reader.extras.ReaderPanels
import com.ggumtak.readeraplus.ui.kit.MenuItem
import com.ggumtak.readeraplus.ui.kit.alert
import com.ggumtak.readeraplus.ui.kit.chooser
import com.ggumtak.readeraplus.ui.kit.popupMenu
import com.ggumtak.readeraplus.ui.kit.prompt
import com.ggumtak.readeraplus.ui.kit.showNoAnim
import com.ggumtak.readeraplus.ui.kit.toast
import com.ggumtak.readeraplus.ui.library.LibraryText
import com.ggumtak.readeraplus.ui.notes.NotesActivity
import com.ggumtak.readeraplus.ui.settings.SettingsFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MENU_TAG = "ReaderMenus"

/**
 * The reader's ⋮ menu (ReadEra order, B/W), in four groups parted by a black rule: moving (페이지 이동 · 페이지
 * 미리보기 · (narrow) 북마크 추가/삭제) | the view (스크롤로 보기 (페이지로 보기) · 자동 넘김 (자동 스크롤) · 화면
 * 새로고침) | this book (독서 노트 · 책 정보 · 내 리뷰 · 즐겨찾기·컬렉션) | 설정. At most 11 rows (≤ 536 dp: the rules
 * are inside the rows), so it fits the Comet's 720 dp without scrolling. Sharing the file and the trash are the
 * library's book menu's.
 */
internal fun ReaderActivity.showOverflowMenu(anchor: View) {
    // Captured now: every action below works on this book even if another one is opened meanwhile.
    val bk = currentBookOrNull() ?: return
    val bookmarked = isCurrentPageBookmarked()
    val items = listOfNotNull(
        MenuItem("페이지 이동", R.drawable.ic_find_in_page) { guarded { ReaderPanels.showGoTo(this) } },
        if (!scrollMode) MenuItem("페이지 미리보기", R.drawable.ic_grid_view) { guarded { ReaderPanels.showContents(this, 3) } } else null,
        // C22 (narrow): only while the top bar is too narrow for its bookmark button (U §2.2 width guard).
        if (!bookmarkInChrome()) {
            MenuItem(if (bookmarked) "북마크 삭제" else "북마크 추가", R.drawable.ic_bookmark_add) { toggleBookmark() }
        } else null,
        // S §1.2: the quick way into scroll mode and always the way back.
        MenuItem(ScrollWiring.modeItem(scrollMode), if (scrollMode) R.drawable.ic_auto_stories else R.drawable.ic_view_list, groupStart = true) {
            toggleReadMode()
        },
        MenuItem(ScrollWiring.autoItem(scrollMode, autoTurnOn), R.drawable.ic_timer) { toggleAutoTurn() },
        // After the popup has disappeared from the panel, or its outline stays as ghosting.
        MenuItem("화면 새로고침", R.drawable.ic_refresh) { refreshAfterDraw(POPUP_GONE_MS) },
        // N §6.7: this book's notes in the hub; a note tapped there comes back through onNewIntent (N §6.3).
        MenuItem("독서 노트", R.drawable.ic_format_quote, groupStart = true) { guarded { NotesActivity.open(this, NotesTab.ALL, bk.id) } },
        MenuItem("책 정보", R.drawable.ic_info) { guarded { ReaderPanels.showDocumentInfo(this, bk, document) } },
        MenuItem("내 리뷰", R.drawable.ic_rate_review) { guarded { ReaderPanels.showReview(this) } },
        MenuItem("즐겨찾기·컬렉션", R.drawable.ic_playlist_add) { showAddMenu(anchor, bk) },
        MenuItem("설정", R.drawable.ic_settings, groupStart = true) { guarded { openAppSettings() } },
    )
    guarded { popupMenu(anchor, items, widthDp = 260) }
}

private const val POPUP_GONE_MS = 120L

/** "즐겨찾기·컬렉션": the library book menu's shelf toggles (its own labels) and the collections. */
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
            MenuItem(LibraryText.flagMenuLabel(Shelf.FAVORITES, b.favorite), if (b.favorite) R.drawable.ic_star_fill else R.drawable.ic_star) {
                flag(shelfMessage(Shelf.FAVORITES, !b.favorite)) { Library.setFavorite(id, !b.favorite) }
            },
            MenuItem(LibraryText.flagMenuLabel(Shelf.TO_READ, b.toRead), if (b.toRead) R.drawable.ic_schedule_fill else R.drawable.ic_schedule) {
                flag(shelfMessage(Shelf.TO_READ, !b.toRead)) { Library.setToRead(id, !b.toRead) }
            },
            MenuItem(LibraryText.flagMenuLabel(Shelf.HAVE_READ, b.haveRead), if (b.haveRead) R.drawable.ic_done_all_fill else R.drawable.ic_done_all) {
                flag(shelfMessage(Shelf.HAVE_READ, !b.haveRead)) { Library.setHaveRead(id, !b.haveRead) }
            },
            MenuItem("컬렉션에 추가…", R.drawable.ic_library_books) { showCollections(id) },
        )
        // The anchor (⋮ in the chrome) must still be in a window, or PopupWindow throws BadTokenException.
        if (!isFinishing && !isDestroyed && anchor.isAttachedToWindow) guarded { popupMenu(anchor, items, widthDp = 240) }
    }
}

/**
 * "읽을 책에 추가했습니다" / "다 읽은 책에서 뺐습니다" / "즐겨찾기에 추가했습니다" (에 / 에서 follow any noun, so the
 * particles are fixed).
 */
internal fun shelfMessage(shelf: Shelf, added: Boolean): String =
    if (added) "${shelf.label}에 추가했습니다" else "${shelf.label}에서 뺐습니다"

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
                prompt("새 컬렉션", hint = "컬렉션 이름", ok = "만들기") { name ->
                    val n = name.trim()
                    if (n.isNotEmpty()) {
                        ReaderIo.launch {
                            val c = Library.createCollection(n)
                            Library.setInCollection(id, c.id, true)
                        }
                        toast("‘$n’에 추가했습니다")
                    }
                }
            }
            .showNoAnim()
    }
}

/** Long-press on the rotation button: pick a fixed orientation or follow the sensor (설정's 화면 방향 choices). */
internal fun ReaderActivity.showOrientationChooser() {
    val options = SettingsFormat.ORIENTATIONS
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
