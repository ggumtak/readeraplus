package com.ggumtak.readeraplus.ui.library

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.ScrollView
import com.ggumtak.readeraplus.R
import com.ggumtak.readeraplus.data.Book
import com.ggumtak.readeraplus.data.BookCollection
import com.ggumtak.readeraplus.data.BookFileProvider
import com.ggumtak.readeraplus.data.Library
import com.ggumtak.readeraplus.data.LibraryQuery
import com.ggumtak.readeraplus.data.Notes
import com.ggumtak.readeraplus.data.NotesTab
import com.ggumtak.readeraplus.data.Shelf
import com.ggumtak.readeraplus.data.ShelfGroup
import com.ggumtak.readeraplus.format.BookFormat
import com.ggumtak.readeraplus.format.txt.TxtDocuments
import com.ggumtak.readeraplus.reader.extras.ReaderPanels
import com.ggumtak.readeraplus.render.Covers
import com.ggumtak.readeraplus.settings.LibraryListMode
import com.ggumtak.readeraplus.settings.LibrarySort
import com.ggumtak.readeraplus.ui.kit.Ink
import com.ggumtak.readeraplus.ui.kit.MenuItem
import com.ggumtak.readeraplus.ui.kit.alert
import com.ggumtak.readeraplus.ui.kit.chooser
import com.ggumtak.readeraplus.ui.kit.confirm
import com.ggumtak.readeraplus.ui.kit.dp
import com.ggumtak.readeraplus.ui.kit.horizontal
import com.ggumtak.readeraplus.ui.kit.icon
import com.ggumtak.readeraplus.ui.kit.label
import com.ggumtak.readeraplus.ui.kit.lp
import com.ggumtak.readeraplus.ui.kit.popupMenu
import com.ggumtak.readeraplus.ui.kit.prompt
import com.ggumtak.readeraplus.ui.kit.showNoAnim
import com.ggumtak.readeraplus.ui.kit.toast
import com.ggumtak.readeraplus.ui.kit.vertical
import com.ggumtak.readeraplus.ui.notes.NotesActivity
import com.ggumtak.readeraplus.ui.settings.ErrorLines
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * Book menu, collections and trash dialogs of the library. All database work goes through [io] (Dispatchers.IO)
 * or the activity's serial write lane ([LibraryActivity.queueWrite]) and reports failures as a toast; the list is
 * refreshed through LibraryActivity.changed().
 */

/** Runs [work] on IO, then [done] on the main thread; failures become a toast: [errorPrefix] and the reason. */
internal fun <T> LibraryActivity.io(errorPrefix: String, work: () -> T, done: (T) -> Unit) {
    scope.launch {
        val r = withContext(Dispatchers.IO) { runCatching(work) }
        r.onSuccess(done).onFailure { toast(ErrorLines.line(errorPrefix, it)) }
    }
}

internal fun LibraryActivity.confirmDialog(title: String, message: String, ok: String, onOk: () -> Unit) =
    confirm(title, message, ok, onOk)

// ------------------------------------------------------------------------------------------------ book menu

/**
 * The single-book menu (⋮ on a card or compact row, [더보기] while selecting, a long-press in the trash). [onPick] runs
 * before the chosen item's action (the selection toolbar ends selection mode there). [flags]: offer the shelf flags
 * (by default only where no card flag buttons show: 간단히 and the 표지 views). No 읽기: a tap opens the book, and 책 정보
 * edits the title and author.
 */
internal fun LibraryActivity.bookMenu(
    row: BookRow,
    anchor: View,
    flags: Boolean = listMode != LibraryListMode.LIST,
    onPick: (() -> Unit)? = null,
) {
    val b = row.book
    val items = ArrayList<MenuItem>()
    fun item(label: String, icon: Int, action: () -> Unit) {
        items += MenuItem(label, icon) { onPick?.invoke(); action() }
    }
    if (b.trashed) {
        item("복원", R.drawable.ic_restore_from_trash) { setTrashed(b, false) }
        item("책 정보", R.drawable.ic_info) { documentInfo(b) }
        item("독서 노트", R.drawable.ic_format_quote) { NotesActivity.open(this, NotesTab.ALL, b.id) }
        item("영구 삭제", R.drawable.ic_delete_forever) { confirmDelete(b) }
    } else {
        if (flags) {
            item(LibraryText.flagMenuLabel(Shelf.FAVORITES, b.favorite), if (b.favorite) R.drawable.ic_star_fill else R.drawable.ic_star) {
                toggleFlag(row, BookFlag.FAVORITE)
            }
            item(LibraryText.flagMenuLabel(Shelf.TO_READ, b.toRead), if (b.toRead) R.drawable.ic_schedule_fill else R.drawable.ic_schedule) {
                toggleFlag(row, BookFlag.TO_READ)
            }
            item(LibraryText.flagMenuLabel(Shelf.HAVE_READ, b.haveRead), if (b.haveRead) R.drawable.ic_done_all_fill else R.drawable.ic_done_all) {
                toggleFlag(row, BookFlag.HAVE_READ)
            }
        }
        item("컬렉션에 추가", R.drawable.ic_library_books) { collectionsDialog(b) }
        item("독서 노트", R.drawable.ic_format_quote) { NotesActivity.open(this, NotesTab.ALL, b.id) }
        item("책 정보", R.drawable.ic_info) { documentInfo(b) }
        item("파일 공유", R.drawable.ic_share) { shareBook(b) }
        if (b.format == BookFormat.TXT) item("인코딩 바꾸기", R.drawable.ic_text_fields) { chooseEncoding(b) }
        item("읽은 위치 초기화", R.drawable.ic_autorenew) { confirmReset(b) }
        item("휴지통으로 옮기기", R.drawable.ic_delete) { setTrashed(b, true) }
    }
    popupMenu(anchor, items, 240)
}

private fun LibraryActivity.documentInfo(b: Book) {
    // The dialog (reader extras) can edit title/author: refresh when this window gets focus back.
    refreshOnFocus = true
    try {
        ReaderPanels.showDocumentInfo(this, b, null)
    } catch (t: Throwable) {
        refreshOnFocus = false
        toast(ErrorLines.line("책 정보를 열 수 없습니다", t))
    }
}

private fun LibraryActivity.setTrashed(b: Book, value: Boolean) {
    io("저장하지 못했습니다", { Library.setTrashed(b.id, value) }) {
        toast(if (value) LibraryText.trashedMessage(1) else "복원했습니다")
        changed()
    }
}

private fun LibraryActivity.shareBook(b: Book) {
    try {
        val uri = BookFileProvider.uriFor(packageName, b.id)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = LibraryText.mimeFor(b.fileName)
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TITLE, b.title)
            clipData = ClipData.newRawUri(b.fileName, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, "파일 공유").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    } catch (e: ActivityNotFoundException) {
        toast("공유할 앱이 없습니다")
    } catch (t: Throwable) {
        toast(ErrorLines.line("공유할 수 없습니다", t))
    }
}

private fun LibraryActivity.chooseEncoding(b: Book) {
    val options = listOf("") + TxtDocuments.ENCODINGS
    val labels = options.map { LibraryText.encodingLabel(it) }
    val current = if (b.encoding.isBlank()) 0 else options.indexOfFirst { it.equals(b.encoding, ignoreCase = true) }.coerceAtLeast(0)
    chooser("인코딩", labels, current) { i ->
        val enc = options[i]
        if (enc.equals(b.encoding, ignoreCase = true)) return@chooser
        val app = applicationContext
        io("저장하지 못했습니다", {
            Library.setEncoding(b.id, enc)
            Covers.invalidate(app, b.id)
        }) {
            CoverLoader.forget(b.id)
            toast("인코딩: ${labels[i]}")
            changed()
        }
    }
}

private fun LibraryActivity.confirmReset(b: Book) {
    confirm("읽은 위치 초기화", "‘${b.title}’의 읽은 위치를 초기화할까요?", "초기화") {
        io("초기화하지 못했습니다", { Library.resetProgress(b.id) }) { changed() }
    }
}

/** Checked/unchecked box without the platform's animated check mark; switches instantly (no fade). */
private fun Context.staticCheckDrawable(): Drawable = StateListDrawable().apply {
    getDrawable(checkIcon(true))?.let { addState(intArrayOf(android.R.attr.state_checked), it) }
    getDrawable(checkIcon(false))?.let { addState(intArrayOf(), it) }
}

private fun Context.deleteFileCheckBox(): CheckBox = CheckBox(this).apply {
    text = "파일도 삭제 (되돌릴 수 없음)"
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
    setTextColor(Ink.BLACK)
    buttonDrawable = staticCheckDrawable()
    buttonTintList = ColorStateList.valueOf(Ink.BLACK)
    background = null // no (borderless ripple) touch feedback: the box itself changes
    minimumHeight = dp(48)
    setPaddingRelative(dp(12), dp(8), 0, dp(8)) // gap between the box and the text
}

/**
 * Checkbox rows for a dialog ([checked] is edited in place, then [onToggle] gets the row): static box icons and no
 * press highlight, so a tap repaints one icon instead of animating a check mark and a row ripple the way
 * setMultiChoiceItems does.
 */
private fun Context.checkList(labels: List<String>, checked: BooleanArray, onToggle: (Int) -> Unit): View {
    val col = vertical { setPadding(0, dp(4), 0, dp(4)) }
    labels.forEachIndexed { i, text ->
        val box = icon(checkIcon(checked[i]), 24)
        val row = horizontal {
            minimumHeight = dp(48)
            setPadding(dp(20), 0, dp(20), 0)
            addView(box)
            addView(label(text, 17f, maxLines = 2).apply { setPadding(dp(16), dp(8), 0, dp(8)) }, lp(0, WRAP_CONTENT, 1f))
            setOnClickListener {
                checked[i] = !checked[i]
                box.setImageResource(checkIcon(checked[i]))
                onToggle(i)
            }
        }
        col.addView(row, lp())
    }
    return ScrollView(this).apply {
        overScrollMode = View.OVER_SCROLL_NEVER
        isScrollbarFadingEnabled = false
        addView(col, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }
}

/** Counts notes on IO, then [then] on main; a failed count shows the question without the notes line (0). */
private fun LibraryActivity.notesCountThen(count: () -> Int, then: (Int) -> Unit) {
    scope.launch {
        val n = withContext(Dispatchers.IO) { runCatching(count).getOrDefault(0) }
        if (!isFinishing && !isDestroyed) then(n)
    }
}

/**
 * "영구 삭제" of one book. The book's notes are counted first (IO): when it has some, the question says so and offers
 * [독서 노트] (the hub filtered to this book) to export them first (NOTES_SPEC §10.1).
 */
private fun LibraryActivity.confirmDelete(b: Book) {
    notesCountThen({ Notes.countForBooks(listOf(b.id)) }) { notes ->
        val cb = deleteFileCheckBox()
        val box = FrameLayout(this).apply { setPadding(dp(20), dp(4), dp(20), 0); addView(cb) }
        val d = alert().setTitle("영구 삭제")
            .setMessage(LibraryText.deleteMessage(b.title, notes))
            .setView(box)
            .setPositiveButton("삭제") { _, _ ->
                val deleteFile = cb.isChecked
                val app = applicationContext
                io("삭제하지 못했습니다", {
                    Library.remove(b.id, deleteFile)
                    Covers.invalidate(app, b.id)
                }) {
                    CoverLoader.forget(b.id)
                    changed(collections = true)
                }
            }
            .setNegativeButton("취소", null)
        if (notes > 0) d.setNeutralButton("독서 노트") { _, _ -> NotesActivity.open(this, NotesTab.ALL, b.id) }
        d.showNoAnim()
    }
}

/**
 * "휴지통 비우기": like [confirmDelete], for every trashed book ([독서 노트] opens the hub unfiltered). Only the books
 * counted here are deleted: one trashed while the question is open keeps its notes ([Library.emptyTrash]).
 */
internal fun LibraryActivity.confirmEmptyTrash() {
    var counted: List<Long>? = null
    notesCountThen({
        val ids = Library.books(LibraryQuery(Shelf.TRASH, null, ""), LibrarySort.RECENT).map { it.id }
        counted = ids
        if (ids.isEmpty()) 0 else Notes.countForBooks(ids)
    }) { notes ->
        val ids = counted
        val cb = deleteFileCheckBox()
        val box = FrameLayout(this).apply { setPadding(dp(20), dp(4), dp(20), 0); addView(cb) }
        val d = alert().setTitle("휴지통 비우기")
            .setMessage(LibraryText.emptyTrashMessage(notes, ids?.size ?: 0))
            .setView(box)
            .setPositiveButton("비우기") { _, _ ->
                val deleteFiles = cb.isChecked
                io("비우지 못했습니다", {
                    // The trash could not be listed: nothing was counted, so nothing is deleted.
                    Library.emptyTrash(ids ?: error("trash not listed"), deleteFiles)
                }) {
                    toast("휴지통을 비웠습니다")
                    changed(collections = true)
                }
            }
            .setNegativeButton("취소", null)
        if (notes > 0) d.setNeutralButton("독서 노트") { _, _ -> NotesActivity.open(this) }
        d.showNoAnim()
    }
}

// ------------------------------------------------------------------------------------------------ collections

/**
 * The collections of [b] as a check list. A tap applies at once and [닫기] closes, like the reader's dialog (A8: one
 * behaviour, no confirmation step); the card icons are refreshed when the dialog closes.
 */
internal fun LibraryActivity.collectionsDialog(b: Book) {
    io("컬렉션을 불러오지 못했습니다", { Library.collections() to Library.collectionsOf(b.id) }) { (colls, member) ->
        val checked = BooleanArray(colls.size) { colls[it].id in member }
        var changedAny = false
        val builder = alert().setTitle("컬렉션")
        if (colls.isEmpty()) {
            builder.setMessage("아직 컬렉션이 없습니다.")
        } else {
            builder.setView(checkList(colls.map { it.name }, checked) { i ->
                changedAny = true
                val cid = colls[i].id
                val on = checked[i]
                // Serial lane: quick taps on one row are stored in tap order. No reload behind the open dialog.
                queueWrite("저장하지 못했습니다", reload = false) { Library.setInCollection(b.id, cid, on) }
            })
        }
        builder.setPositiveButton("닫기", null)
        builder.setNeutralButton("새 컬렉션") { _, _ ->
            newCollection { created ->
                queueWrite("저장하지 못했습니다", done = { collectionsDialog(b) }) { Library.setInCollection(b.id, created.id, true) }
            }
        }
        val dialog = builder.showNoAnim()
        dialog.setOnDismissListener {
            if (changedAny) {
                changedAny = false
                changed(collections = true, afterWrites = true)
            }
        }
    }
}

/**
 * Collection picker of the multi-select [컬렉션]: a tap on a name picks it and closes; [새 컬렉션] makes one and
 * picks it; [닫기] cancels. [onPick] runs on the main thread.
 */
internal fun LibraryActivity.pickCollection(onPick: (BookCollection) -> Unit) {
    io("컬렉션을 불러오지 못했습니다", { Library.collections() }) { colls ->
        val builder = alert().setTitle("컬렉션에 추가")
        if (colls.isEmpty()) {
            builder.setMessage("아직 컬렉션이 없습니다.")
        } else {
            builder.setItems(colls.map { it.name }.toTypedArray()) { _, which -> onPick(colls[which]) }
        }
        builder.setPositiveButton("닫기", null)
        builder.setNeutralButton("새 컬렉션") { _, _ -> newCollection { created -> onPick(created) } }
        builder.showNoAnim()
    }
}

/** Asks for a name and creates a collection; [then] gets the created collection. */
internal fun LibraryActivity.newCollection(then: ((BookCollection) -> Unit)?) {
    prompt("새 컬렉션", hint = "컬렉션 이름", ok = "만들기") { raw ->
        val name = raw.trim()
        if (name.isEmpty()) {
            toast("이름을 입력하세요")
            return@prompt
        }
        io("컬렉션을 만들지 못했습니다", { Library.createCollection(name) }) { c ->
            changed(collections = true)
            then?.invoke(c)
        }
    }
}

/** Long press on a collection row: rename / delete. */
internal fun LibraryActivity.collectionGroupMenu(g: ShelfGroup) {
    val id = g.key.toLongOrNull() ?: return
    alert().setTitle(g.label)
        .setItems(arrayOf("이름 바꾸기", "삭제")) { _, which ->
            if (which == 0) {
                prompt("이름 바꾸기", initial = g.label, hint = "컬렉션 이름", ok = "바꾸기") { raw ->
                    val name = raw.trim()
                    if (name.isEmpty() || name == g.label) return@prompt
                    io("이름을 바꾸지 못했습니다 (같은 이름의 컬렉션이 있을 수 있습니다)", { Library.renameCollection(id, name) }) {
                        changed(collections = true)
                    }
                }
            } else {
                confirm("컬렉션 삭제", "‘${g.label}’ 컬렉션을 삭제할까요? 책은 그대로 남습니다.", "삭제") {
                    io("삭제하지 못했습니다", { Library.deleteCollection(id) }) { changed(collections = true) }
                }
            }
        }
        .setNegativeButton("취소", null)
        .showNoAnim()
}

// ------------------------------------------------------------------------------------------------ permission

/** Copies the adb permission command (shown when the system settings page can't be opened). */
internal fun LibraryActivity.showNoPermissionScreen() {
    alert().setTitle("권한 화면을 열 수 없습니다")
        .setMessage(
            "이 기기에서는 ‘모든 파일 접근’ 설정 화면을 열 수 없습니다.\n\n" +
                "· ‘스캔 폴더 추가’로 책 폴더를 고르면 그 폴더의 책을 가져올 수 있습니다.\n" +
                "· PC에 연결해 다음 명령으로 권한을 줄 수도 있습니다.\n\n" + LibraryText.ADB_HINT,
        )
        .setPositiveButton("스캔 폴더 추가") { _, _ -> pickTree() }
        .setNeutralButton("명령 복사") { _, _ ->
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            cm?.setPrimaryClip(ClipData.newPlainText("adb", LibraryText.ADB_HINT))
            toast("명령을 복사했습니다")
        }
        .setNegativeButton("닫기", null)
        .showNoAnim()
}
