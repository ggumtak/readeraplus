package com.ggumtak.readeraplus.ui.library

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.text.InputType
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ScrollView
import com.ggumtak.readeraplus.BuildConfig
import com.ggumtak.readeraplus.R
import com.ggumtak.readeraplus.data.Book
import com.ggumtak.readeraplus.data.BookCollection
import com.ggumtak.readeraplus.data.BookFileProvider
import com.ggumtak.readeraplus.data.Library
import com.ggumtak.readeraplus.data.ShelfGroup
import com.ggumtak.readeraplus.format.BookFormat
import com.ggumtak.readeraplus.format.txt.TxtDocuments
import com.ggumtak.readeraplus.reader.extras.ReaderPanels
import com.ggumtak.readeraplus.render.Covers
import com.ggumtak.readeraplus.render.Eink
import com.ggumtak.readeraplus.settings.LibraryListMode
import com.ggumtak.readeraplus.ui.kit.Ink
import com.ggumtak.readeraplus.ui.kit.MenuItem
import com.ggumtak.readeraplus.ui.kit.alert
import com.ggumtak.readeraplus.ui.kit.chooser
import com.ggumtak.readeraplus.ui.kit.confirm
import com.ggumtak.readeraplus.ui.kit.dp
import com.ggumtak.readeraplus.ui.kit.horizontal
import com.ggumtak.readeraplus.ui.kit.icon
import com.ggumtak.readeraplus.ui.kit.inkCursor
import com.ggumtak.readeraplus.ui.kit.label
import com.ggumtak.readeraplus.ui.kit.lp
import com.ggumtak.readeraplus.ui.kit.popupMenu
import com.ggumtak.readeraplus.ui.kit.prompt
import com.ggumtak.readeraplus.ui.kit.showNoAnim
import com.ggumtak.readeraplus.ui.kit.toast
import com.ggumtak.readeraplus.ui.kit.vertical
import com.ggumtak.readeraplus.ui.settings.ErrorLines
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * Book menu, collections, trash and about dialogs of the library. All database work goes through [io]
 * (Dispatchers.IO) and reports failures as a toast; the list is refreshed through LibraryActivity.changed().
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

internal fun LibraryActivity.bookMenu(row: BookRow, anchor: View) {
    val b = row.book
    val items = ArrayList<MenuItem>()
    if (b.trashed) {
        items += MenuItem("복원", R.drawable.ic_restore_from_trash) { setTrashed(b, false) }
        items += MenuItem("문서 속성", R.drawable.ic_info) { documentInfo(b) }
        items += MenuItem("영구 삭제", R.drawable.ic_delete_forever) { confirmDelete(b) }
    } else {
        items += MenuItem("읽기", R.drawable.ic_menu_book) { openBook(b) }
        if (listMode == LibraryListMode.GRID) {
            // Grid cells have no action row: offer the flags here.
            items += MenuItem(if (b.favorite) "즐겨찾기 해제" else "즐겨찾기", if (b.favorite) R.drawable.ic_star_fill else R.drawable.ic_star) {
                toggleFlag(row, BookFlag.FAVORITE)
            }
            items += MenuItem(if (b.toRead) "읽을 문서 해제" else "읽을 문서", if (b.toRead) R.drawable.ic_schedule_fill else R.drawable.ic_schedule) {
                toggleFlag(row, BookFlag.TO_READ)
            }
            items += MenuItem(if (b.haveRead) "읽던 문서 해제" else "읽던 문서", if (b.haveRead) R.drawable.ic_done_all_fill else R.drawable.ic_done_all) {
                toggleFlag(row, BookFlag.HAVE_READ)
            }
        }
        items += MenuItem("문서 속성", R.drawable.ic_info) { documentInfo(b) }
        items += MenuItem("파일 공유", R.drawable.ic_share) { shareBook(b) }
        items += MenuItem("컬렉션에 추가", R.drawable.ic_library_books) { collectionsDialog(b) }
        items += MenuItem("제목/작가 편집", R.drawable.ic_edit) { editMeta(b) }
        if (b.format == BookFormat.TXT) items += MenuItem("인코딩 변경", R.drawable.ic_text_fields) { chooseEncoding(b) }
        items += MenuItem("읽은 기록 초기화", R.drawable.ic_history) { confirmReset(b) }
        items += MenuItem("휴지통으로 이동", R.drawable.ic_delete) { setTrashed(b, true) }
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
        toast(ErrorLines.line("문서 속성을 열 수 없습니다", t))
    }
}

private fun LibraryActivity.setTrashed(b: Book, value: Boolean) {
    io("저장하지 못했습니다", { Library.setTrashed(b.id, value) }) {
        toast(if (value) "휴지통으로 이동했습니다" else "복원했습니다")
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

private fun Context.formField(parent: android.widget.LinearLayout, title: String, value: String, numeric: Boolean = false): EditText {
    parent.addView(label(title, 13f, color = Ink.GRAY).apply { setPadding(0, dp(10), 0, 0) }, lp())
    val e = EditText(this).apply {
        setText(value)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        setTextColor(Ink.BLACK)
        inputType = if (numeric) InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        setSingleLine(true)
        // Opens with the book's value: the caret shows once the user taps into it, so a fix mid-word is not blind.
        inkCursor(singleLine = false)
    }
    parent.addView(e, lp())
    return e
}

private fun LibraryActivity.editMeta(b: Book) {
    val box = vertical { setPadding(dp(20), dp(4), dp(20), dp(4)) }
    val title = formField(box, "제목", b.title)
    val author = formField(box, "작가", b.author)
    val series = formField(box, "시리즈", b.series ?: "")
    val index = formField(box, "시리즈 번호", b.seriesIndex?.let { if (it == it.toLong().toFloat()) it.toLong().toString() else it.toString() } ?: "", numeric = true)
    val scroll = ScrollView(this).apply { addView(box, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)) }
    alert().setTitle("제목/작가 편집").setView(scroll)
        .setPositiveButton("저장") { _, _ ->
            val t = title.text.toString().trim().ifEmpty { b.title }
            val a = author.text.toString().trim()
            val s = series.text.toString().trim().ifEmpty { null }
            val i = if (s == null) null else index.text.toString().trim().replace(',', '.').toFloatOrNull()
            val app = applicationContext
            io("저장하지 못했습니다", {
                Library.updateMeta(b.id, t, a, s, i)
                Covers.invalidate(app, b.id)
            }) {
                CoverLoader.forget(b.id)
                changed()
            }
        }
        .setNegativeButton("취소", null)
        .showNoAnim()
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
    confirm("읽은 기록 초기화", "‘${b.title}’의 읽은 위치와 진행률을 지웁니다.", "초기화") {
        io("초기화하지 못했습니다", { Library.resetProgress(b.id) }) { changed() }
    }
}

/** Box icon for a checkbox state (static vectors: the platform check marks animate, a run of e-ink frames). */
private fun checkIcon(checked: Boolean): Int = if (checked) R.drawable.ic_check_box else R.drawable.ic_check_box_outline_blank

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
 * Checkbox rows for a dialog ([checked] is edited in place): static box icons and no press highlight, so a tap
 * repaints one icon instead of animating a check mark and a row ripple the way setMultiChoiceItems does.
 */
private fun Context.checkList(labels: List<String>, checked: BooleanArray): View {
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

private fun LibraryActivity.confirmDelete(b: Book) {
    val cb = deleteFileCheckBox()
    val box = FrameLayout(this).apply { setPadding(dp(20), dp(4), dp(20), 0); addView(cb) }
    alert().setTitle("영구 삭제")
        .setMessage("‘${b.title}’을(를) 서재에서 삭제합니다. 북마크와 인용문도 함께 지워집니다.")
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
        .showNoAnim()
}

internal fun LibraryActivity.confirmEmptyTrash() {
    val cb = deleteFileCheckBox()
    val box = FrameLayout(this).apply { setPadding(dp(20), dp(4), dp(20), 0); addView(cb) }
    alert().setTitle("휴지통 비우기")
        .setMessage("휴지통의 모든 문서를 서재에서 삭제합니다.")
        .setView(box)
        .setPositiveButton("비우기") { _, _ ->
            val deleteFiles = cb.isChecked
            io("비우지 못했습니다", { Library.emptyTrash(deleteFiles) }) {
                toast("휴지통을 비웠습니다")
                changed(collections = true)
            }
        }
        .setNegativeButton("취소", null)
        .showNoAnim()
}

// ------------------------------------------------------------------------------------------------ collections

/** Checkbox list of collections for [b] + "새 컬렉션". */
internal fun LibraryActivity.collectionsDialog(b: Book) {
    io("컬렉션을 불러오지 못했습니다", { Library.collections() to Library.collectionsOf(b.id) }) { (colls, member) ->
        val checked = BooleanArray(colls.size) { colls[it].id in member }
        val builder = alert().setTitle("컬렉션")
        if (colls.isEmpty()) {
            builder.setMessage("컬렉션이 없습니다. ‘새 컬렉션’으로 만드세요.")
        } else {
            builder.setView(checkList(colls.map { it.name }, checked))
        }
        builder.setPositiveButton("확인") { _, _ ->
            val changes = colls.indices.filter { (colls[it].id in member) != checked[it] }
            if (changes.isEmpty()) return@setPositiveButton
            io("저장하지 못했습니다", {
                changes.forEach { i -> Library.setInCollection(b.id, colls[i].id, checked[i]) }
            }) { changed(collections = true) }
        }
        builder.setNeutralButton("새 컬렉션") { _, _ ->
            // Keep the choices made so far, then add the book to the new collection and reopen the list.
            val changes = colls.indices.filter { (colls[it].id in member) != checked[it] }
            newCollection { created ->
                io("저장하지 못했습니다", {
                    changes.forEach { i -> Library.setInCollection(b.id, colls[i].id, checked[i]) }
                    Library.setInCollection(b.id, created.id, true)
                }) {
                    changed(collections = true)
                    collectionsDialog(b)
                }
            }
        }
        builder.setNegativeButton("취소", null)
        builder.showNoAnim()
    }
}

/** Asks for a name and creates a collection; [then] gets the created collection. */
internal fun LibraryActivity.newCollection(then: ((BookCollection) -> Unit)?) {
    prompt("새 컬렉션", hint = "컬렉션 이름") { raw ->
        val name = raw.trim()
        if (name.isEmpty()) {
            toast("이름을 입력하세요")
            return@prompt
        }
        io("만들지 못했습니다 (같은 이름이 있을 수 있습니다)", { Library.createCollection(name) }) { c ->
            changed(collections = true)
            then?.invoke(c)
        }
    }
}

/** Long press on a collection row: rename / delete. */
internal fun LibraryActivity.collectionGroupMenu(g: ShelfGroup) {
    val id = g.key.toLongOrNull() ?: return
    alert().setTitle(g.label)
        .setItems(arrayOf("이름 변경", "삭제")) { _, which ->
            if (which == 0) {
                prompt("이름 변경", initial = g.label, hint = "컬렉션 이름") { raw ->
                    val name = raw.trim()
                    if (name.isEmpty() || name == g.label) return@prompt
                    io("이름을 바꾸지 못했습니다", { Library.renameCollection(id, name) }) { changed(collections = true) }
                }
            } else {
                confirm("컬렉션 삭제", "‘${g.label}’ 컬렉션을 삭제합니다. 문서는 삭제되지 않습니다.", "삭제") {
                    io("삭제하지 못했습니다", { Library.deleteCollection(id) }) { changed(collections = true) }
                }
            }
        }
        .setNegativeButton("취소", null)
        .showNoAnim()
}

// ------------------------------------------------------------------------------------------------ about

internal fun LibraryActivity.showAbout() {
    val dm = resources.displayMetrics
    val vendor = try { Eink.vendorName() } catch (t: Throwable) { null }
    val msg = buildString {
        append("버전 ").append(BuildConfig.VERSION_NAME).append("\n\n")
        append("e-ink 전자책 리더기를 위해 만든 가볍고 빠른 TXT·EPUB 리더입니다.\n")
        append("이노스페이스원 코멧 같은 전자잉크 기기에 맞춰 흑백 · 애니메이션 없는 화면으로 최적화했습니다.\n\n")
        append("기기: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
            .append(" (Android ").append(Build.VERSION.RELEASE).append(")\n")
        append("화면: ").append(dm.widthPixels).append('×').append(dm.heightPixels).append("px, ")
            .append(dm.densityDpi).append("dpi\n")
        append("전자잉크 제어: ").append(vendor ?: "일반 (제조사 API 없음)")
    }
    alert().setTitle(getString(R.string.app_name))
        .setMessage(msg)
        .setPositiveButton("닫기", null)
        .setNeutralButton("라이선스") { _, _ -> showLicenses() }
        .showNoAnim()
}

private fun LibraryActivity.showLicenses() {
    val app = applicationContext
    io("라이선스를 읽지 못했습니다", { readLicenses(app) }) { text ->
        val tv = label(text, 13f).apply {
            setPadding(dp(20), dp(12), dp(20), dp(12))
            setLineSpacing(0f, 1.15f)
            setTextIsSelectable(false)
        }
        val scroll = ScrollView(this).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            isScrollbarFadingEnabled = false
            addView(tv, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        alert().setTitle("오픈소스 라이선스").setView(scroll).setPositiveButton("닫기", null).showNoAnim()
    }
}

private const val LICENSE_DIR = "fonts/licenses"

/** FONTS.txt first, then every other license file, then the icon notice (blocking). */
private fun readLicenses(context: Context): String {
    val am = context.assets
    val sb = StringBuilder()
    fun read(name: String): String? = try {
        am.open("$LICENSE_DIR/$name").bufferedReader(Charsets.UTF_8).use { it.readText() }
    } catch (t: Throwable) {
        null
    }
    read("FONTS.txt")?.let { sb.append(it.trimEnd()).append("\n\n") }
    val others = try { am.list(LICENSE_DIR)?.sorted().orEmpty() } catch (t: Throwable) { emptyList() }
    for (name in others) {
        if (name == "FONTS.txt") continue
        val body = read(name) ?: continue
        sb.append("──────── ").append(name).append(" ────────\n\n").append(body.trimEnd()).append("\n\n")
    }
    sb.append("──────── 아이콘 ────────\n\n")
    sb.append("Material Symbols © Google, Apache License 2.0\nhttps://www.apache.org/licenses/LICENSE-2.0\n")
    return sb.toString()
}

/** Copies the adb permission command (shown when the system settings page can't be opened). */
internal fun LibraryActivity.showNoPermissionScreen() {
    alert().setTitle("권한 화면을 열 수 없습니다")
        .setMessage(
            "이 기기에서는 ‘모든 파일 접근’ 설정 화면을 열 수 없습니다.\n\n" +
                "• ‘폴더 추가’로 책 폴더를 고르면 그 폴더의 책을 가져올 수 있습니다.\n" +
                "• PC에 연결해 다음 명령으로 권한을 줄 수도 있습니다:\n\n" + LibraryText.ADB_HINT,
        )
        .setPositiveButton("폴더 추가") { _, _ -> pickTree() }
        .setNeutralButton("명령 복사") { _, _ ->
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            cm?.setPrimaryClip(ClipData.newPlainText("adb", LibraryText.ADB_HINT))
            toast("명령을 복사했습니다")
        }
        .setNegativeButton("닫기", null)
        .showNoAnim()
}
