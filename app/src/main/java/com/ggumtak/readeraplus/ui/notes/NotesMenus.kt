package com.ggumtak.readeraplus.ui.notes

import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.ggumtak.readeraplus.R
import com.ggumtak.readeraplus.data.Library
import com.ggumtak.readeraplus.data.Lookups
import com.ggumtak.readeraplus.data.NoteBook
import com.ggumtak.readeraplus.data.NoteKind
import com.ggumtak.readeraplus.data.NoteRef
import com.ggumtak.readeraplus.data.NoteRow
import com.ggumtak.readeraplus.data.Notes
import com.ggumtak.readeraplus.data.NotesExport
import com.ggumtak.readeraplus.data.NotesOrder
import com.ggumtak.readeraplus.data.NotesTab
import com.ggumtak.readeraplus.reader.ReaderActivity
import com.ggumtak.readeraplus.reader.ReaderJump
import com.ggumtak.readeraplus.reader.extras.QuotePalette
import com.ggumtak.readeraplus.reader.extras.QuoteSwatch
import com.ggumtak.readeraplus.reader.extras.TextActions
import com.ggumtak.readeraplus.reader.extras.multilinePrompt
import com.ggumtak.readeraplus.render.QuoteLook
import com.ggumtak.readeraplus.render.QuoteStyles
import com.ggumtak.readeraplus.settings.Settings
import com.ggumtak.readeraplus.ui.kit.Ink
import com.ggumtak.readeraplus.ui.kit.InkListView
import com.ggumtak.readeraplus.ui.kit.InkPagerBar
import com.ggumtak.readeraplus.ui.kit.ListPager
import com.ggumtak.readeraplus.ui.kit.ListPaging
import com.ggumtak.readeraplus.ui.kit.MenuItem
import com.ggumtak.readeraplus.ui.kit.alert
import com.ggumtak.readeraplus.ui.kit.chooser
import com.ggumtak.readeraplus.ui.kit.confirm
import com.ggumtak.readeraplus.ui.kit.dp
import com.ggumtak.readeraplus.ui.kit.fullScreenDialog
import com.ggumtak.readeraplus.ui.kit.horizontal
import com.ggumtak.readeraplus.ui.kit.InkEditText
import com.ggumtak.readeraplus.ui.kit.inkCursor
import com.ggumtak.readeraplus.ui.kit.label
import com.ggumtak.readeraplus.ui.kit.lp
import com.ggumtak.readeraplus.ui.kit.popupMenu
import com.ggumtak.readeraplus.ui.kit.pressableBackground
import com.ggumtak.readeraplus.ui.kit.showNoAnim
import com.ggumtak.readeraplus.ui.kit.toast
import com.ggumtak.readeraplus.ui.kit.toolbar
import com.ggumtak.readeraplus.ui.kit.vertical
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.StringWriter

/**
 * The hub's menus and dialogs (N §9.3–§9.5, §9.10): row menus, 전체 보기, editors, delete confirmations, the book,
 * order and colour choosers, the overflow and selection menus, share and the export format chooser. Every database or
 * file call runs on IO through the activity's scope; dialogs have no animation.
 */
internal class NotesMenus(private val a: NotesActivity) {

    // ============================================================================================ row menus

    fun rowMenu(row: NoteRow, anchor: View) {
        val items = ArrayList<MenuItem>()
        when (row.ref.kind) {
            NoteKind.QUOTE -> {
                items += MenuItem("책에서 보기", R.drawable.ic_open_in_new) { openAt(row) }
                if (row.bodyCut || row.body.count { it == '\n' } >= 4 || row.body.length > 160) {
                    items += MenuItem("전체 보기") { fullView(row) }
                }
                items += MenuItem("복사") { withFull(row) { b, n -> TextActions.copy(a, NotesText.shareQuote(b, n, null, null)) } }
                items += MenuItem("공유", R.drawable.ic_share) { share(row) }
                items += MenuItem("메모 편집") { editNote(row) }
                items += MenuItem("색 바꾸기", R.drawable.ic_ink_highlighter) { recolour(row, anchor) }
                items += MenuItem("선택") { a.enterSelection(row) }
                items += MenuItem("삭제", R.drawable.ic_delete) { delete(row) }
            }
            NoteKind.BOOKMARK -> {
                items += MenuItem("책에서 보기", R.drawable.ic_open_in_new) { openAt(row) }
                items += MenuItem("메모 편집") { editNote(row) }
                items += MenuItem("복사") { withFull(row) { b, n -> TextActions.copy(a, bookmarkText(row, b, n)) } }
                items += MenuItem("공유", R.drawable.ic_share) { share(row) }
                items += MenuItem("선택") { a.enterSelection(row) }
                items += MenuItem("삭제", R.drawable.ic_delete) { delete(row) }
            }
            NoteKind.REVIEW -> {
                items += MenuItem("리뷰 편집") { editNote(row) }
                items += MenuItem("책 열기", R.drawable.ic_open_in_new) { openBook(row) }
                items += MenuItem("복사") { withFull(row) { b, _ -> TextActions.copy(a, b.trim()) } }
                items += MenuItem("공유", R.drawable.ic_share) { share(row) }
                items += MenuItem("선택") { a.enterSelection(row) }
                items += MenuItem("리뷰 지우기", R.drawable.ic_delete) { delete(row) }
            }
            NoteKind.LOOKUP -> {
                items += MenuItem("다시 찾기", R.drawable.ic_translate) { lookUp(row) }
                items += MenuItem("문맥 보기", R.drawable.ic_open_in_new) { openAt(row) }
                items += MenuItem("웹 검색", R.drawable.ic_search) { TextActions.webSearch(a, row.word.trim()) }
                items += MenuItem("뜻 메모") { editNote(row) }
                items += MenuItem("복사") { TextActions.copy(a, row.word.trim()) }
                items += MenuItem("선택") { a.enterSelection(row) }
                items += MenuItem("삭제", R.drawable.ic_delete) { delete(row) }
                if (a.q.wordsOnce && row.wordCount > 1) {
                    items += MenuItem("모든 기록 보기") { a.setWordsOnce(false, text = row.word.trim()) }
                }
            }
        }
        a.popupMenu(anchor, items)
    }

    /** Tap, 책에서 보기, 문맥 보기: the book file is checked on IO; a missing file keeps the notes and says so. */
    fun openAt(row: NoteRow) = open(row, ReaderJump.of(row))

    private fun openBook(row: NoteRow) = open(row, null)

    private fun open(row: NoteRow, jump: ReaderJump?) {
        val known = a.book(row.bookId)
        a.scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val path = known?.path ?: Library.book(row.bookId)?.path
                    path != null && File(path).isFile
                }.getOrDefault(false)
            }
            if (a.isDestroyed) return@launch
            if (!ok) {
                a.toast("책 파일을 찾을 수 없습니다 (노트는 남아 있습니다)")
                return@launch
            }
            ReaderActivity.open(a, row.bookId, jump)
        }
    }

    /** [다시 찾기]: the dictionary chooser; records nothing. */
    fun lookUp(row: NoteRow) = TextActions.lookUp(a, row.word.trim())

    /** The untruncated (body, note) of [row] when it was cut, else the row's own texts. */
    private fun withFull(row: NoteRow, then: (String, String) -> Unit) {
        if (!row.bodyCut && !row.noteCut) {
            then(row.body, row.note)
            return
        }
        a.scope.launch {
            val full = withContext(Dispatchers.IO) { runCatching { Notes.fullText(row.ref) }.getOrNull() }
            if (a.isDestroyed) return@launch
            then(full?.first ?: row.body, full?.second ?: row.note)
        }
    }

    /** 전체 보기: the whole quote in a scrolling dialog with [복사] [공유] [닫기]. */
    private fun fullView(row: NoteRow) = withFull(row) { body, note ->
        val text = a.label(body.trim() + if (note.isBlank()) "" else "\n\n메모  " + note.trim(), 16f).apply {
            setLineSpacing(0f, 1.25f)
            setTextIsSelectable(true)
            setPadding(a.dp(24), a.dp(8), a.dp(24), a.dp(8))
        }
        val scroll = ScrollView(a).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalFadingEdgeEnabled = false
            addView(text)
        }
        val book = a.book(row.bookId)
        a.alert().setTitle(book?.title ?: NoteKind.QUOTE.label).setView(scroll)
            .setPositiveButton("닫기", null)
            .setNeutralButton("복사") { _, _ -> TextActions.copy(a, NotesText.shareQuote(body, note, null, null)) }
            .setNegativeButton("공유") { _, _ -> TextActions.share(a, NotesText.shareQuote(body, note, book?.title, book?.author)) }
            .showNoAnim()
    }

    private fun bookmarkText(row: NoteRow, body: String, note: String): String =
        NotesText.shareBookmark(a.book(row.bookId)?.title, NotesText.place(row.chapter, row.frac), body, note)

    private fun share(row: NoteRow) = withFull(row) { body, note ->
        val book = a.book(row.bookId)
        val text = when (row.ref.kind) {
            NoteKind.QUOTE -> NotesText.shareQuote(body, note, book?.title, book?.author)
            NoteKind.BOOKMARK -> bookmarkText(row, body, note)
            NoteKind.REVIEW -> NotesText.shareReview(body, book?.title, book?.author)
            NoteKind.LOOKUP -> NotesText.shareWord(row.word, body, book?.title)
        }
        TextActions.share(a, text)
    }

    /** 메모 편집 / 뜻 메모 / 리뷰 편집 with the full text; the list reloads after the write. */
    fun editNote(row: NoteRow) = withFull(row) { body, note ->
        val id = row.ref.id
        when (row.ref.kind) {
            NoteKind.QUOTE -> a.multilinePrompt("인용문 메모", note, "메모") { t -> a.write({ Library.updateQuoteNote(id, t.trim()) }) }
            NoteKind.BOOKMARK -> a.multilinePrompt("북마크 메모", note, "메모") { t -> a.write({ Library.updateBookmarkNote(id, t.trim()) }) }
            NoteKind.LOOKUP -> a.multilinePrompt("뜻 메모", note, "뜻") { t -> a.write({ Lookups.setNote(id, t.trim()) }) }
            NoteKind.REVIEW -> a.multilinePrompt("리뷰", body, "리뷰", minLines = 6) { t -> a.write({ Library.setReview(id, t.trim()) }) }
        }
    }

    private fun delete(row: NoteRow) {
        val id = row.ref.id
        val title = a.book(row.bookId)?.title.orEmpty()
        val done = { a.toast(NotesText.deletedToast(1)) }
        when (row.ref.kind) {
            NoteKind.QUOTE -> a.confirm("인용문 삭제", "이 인용문을 삭제할까요?", "삭제") { a.write({ Library.deleteQuotes(listOf(id)) }, done) }
            NoteKind.BOOKMARK -> a.confirm("북마크 삭제", "이 북마크를 삭제할까요?", "삭제") { a.write({ Library.deleteBookmarks(listOf(id)) }, done) }
            NoteKind.REVIEW -> a.confirm("리뷰 지우기", "‘$title’의 리뷰를 지울까요?", "지우기") { a.write({ Library.clearReviews(listOf(id)) }, done) }
            NoteKind.LOOKUP -> a.confirm("단어 기록 삭제", "‘${NotesText.wordTitle(row.word)}’ 기록을 삭제할까요?", "삭제") {
                a.write({ Lookups.delete(listOf(id)) }, done)
            }
        }
    }

    /** Swatch tap or 색 바꾸기: the quote palette (a chooser of the style labels when it can't show). */
    fun recolour(row: NoteRow, anchor: View) {
        if (row.ref.kind != NoteKind.QUOTE) return
        val id = row.ref.id
        pickStyle(anchor, row.style) { s -> if (s != row.style) a.write({ Library.updateQuoteStyle(id, s) }) }
    }

    private fun pickStyle(anchor: View, current: Int?, onPick: (Int) -> Unit) {
        if (QuotePalette.show(anchor, current, onPick) != null) return
        val labels = (0 until QuoteStyles.COUNT).map { QuoteStyles.label(it) }
        a.chooser("색", labels, current?.let { QuoteStyles.of(it) } ?: -1) { onPick(it) }
    }

    // ============================================================================================ overflow

    fun overflow(anchor: View) {
        val items = ArrayList<MenuItem>()
        items += MenuItem("정렬…", R.drawable.ic_sort) { orderChooser() }
        items += MenuItem("책 선택…", R.drawable.ic_filter_list) { bookChooser() }
        items += MenuItem("여러 개 선택", R.drawable.ic_check_box) { a.enterSelection(null) }
        items += MenuItem("내보내기…", R.drawable.ic_upload) { exportChooser(null) }
        items += MenuItem("공유", R.drawable.ic_share) { shareList(null) }
        if (a.q.tab == NotesTab.WORDS) {
            val once = Settings.raw().getBoolean(NotesActivity.PREF_WORDS_ONCE, false)
            items += MenuItem("같은 단어 한 번만", checked = once) { a.setWordsOnce(!once) }
        }
        val rec = Settings.app.recordLookups
        items += MenuItem("찾아본 단어 기록", checked = rec) { a.setRecordLookups(!rec) }
        a.popupMenu(anchor, items, widthDp = 260)
    }

    fun orderChooser() {
        val orders = NotesOrder.entries
        a.chooser("정렬", orders.map { it.label }, orders.indexOf(a.q.order)) { a.setOrder(orders[it]) }
    }

    fun selectionOverflow(anchor: View) {
        a.popupMenu(anchor, listOf(
            MenuItem("모두 선택") { a.selectAll() },
            MenuItem("선택 해제") { a.clearSelection() },
        ))
    }

    // ============================================================================================ selection actions

    private fun selectedRefs(): List<NoteRef> = a.selected.mapNotNull { NoteRef.unpack(it) }

    fun deleteSelected() {
        val refs = selectedRefs()
        if (refs.isEmpty()) return
        val reviews = refs.any { it.kind == NoteKind.REVIEW }
        val n = refs.size
        a.confirm("노트 삭제", NotesText.deleteSelectedMessage(n, reviews), "삭제") {
            a.write({
                val by = refs.groupBy({ it.kind }, { it.id })
                by[NoteKind.QUOTE]?.let { Library.deleteQuotes(it) }
                by[NoteKind.BOOKMARK]?.let { Library.deleteBookmarks(it) }
                by[NoteKind.REVIEW]?.let { Library.clearReviews(it) }
                by[NoteKind.LOOKUP]?.let { Lookups.delete(it) }
            }) {
                a.toast(NotesText.deletedToast(n))
                a.endSelectionOnReload()
            }
        }
    }

    fun recolourSelected(anchor: View) {
        val ids = selectedRefs().filter { it.kind == NoteKind.QUOTE }.map { it.id }
        if (ids.isEmpty()) return
        pickStyle(anchor, null) { s -> a.write({ Library.setQuoteStyles(ids, s) }) { a.toast(NotesText.recolouredToast(ids.size)) } }
    }

    // ============================================================================================ share and export

    /** 공유: the TXT export of [refs] (null = the list) through ACTION_SEND, capped (N §5.7 [Δ]). */
    fun shareList(refs: LongArray?) {
        val q0 = a.q
        a.scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val w = StringWriter()
                    val n = Notes.export(refs, q0, NotesExport.Format.TXT, w, System.currentTimeMillis())
                    n to NotesText.shareCap(w.toString(), n, TextActions.SHARE_MAX_CHARS)
                }.getOrNull()
            }
            if (a.isDestroyed || r == null) return@launch
            val (n, capped) = r
            if (n == 0) return@launch
            if (capped.second) a.toast("노트가 많아 앞부분만 공유합니다")
            TextActions.share(a, capped.first)
        }
    }

    fun exportChooser(refs: LongArray?) {
        val formats = listOf(NotesExport.Format.MARKDOWN, NotesExport.Format.TXT)
        a.chooser("내보내기 형식", listOf("Markdown (.md) · 메모 앱·옵시디언", "텍스트 (.txt)"), -1) { a.startExport(formats[it], refs) }
    }

    // ============================================================================================ choosers

    /** 모든 색 chip: "모든 색 · n", then one row per style with notes (swatch + label + count). */
    fun styleChooser() {
        val q0 = a.q.copy(style = null)
        a.scope.launch {
            val counts = withContext(Dispatchers.IO) { runCatching { Notes.styleCounts(q0) }.getOrNull() } ?: return@launch
            if (a.isDestroyed) return@launch
            val styles = ArrayList<Int?>()
            styles += null
            for (s in counts.indices) if (counts[s] > 0) styles += s
            val col = a.vertical { setPadding(0, a.dp(4), 0, a.dp(4)) }
            val dialog = a.alert().setTitle("색").setView(ScrollView(a).apply { addView(col) }).setNegativeButton("취소", null).create()
            val ink = QuoteLook.ink()
            for (s in styles) {
                val n = if (s == null) counts.sum() else counts[s]
                val r = a.horizontal {
                    minimumHeight = a.dp(48)
                    setPadding(a.dp(24), 0, a.dp(24), 0)
                    background = pressableBackground()
                    setOnClickListener { dialog.dismiss(); a.setStyle(s) }
                }
                if (s != null) r.addView(QuoteSwatch(a, s, 14, ink), LinearLayout.LayoutParams(a.dp(20), a.dp(20)).apply { rightMargin = a.dp(12) })
                val text = if (s == null) "모든 색 · $n" else QuoteStyles.label(s)
                r.addView(a.label(text, 17f, bold = s == a.q.style), lp(0, WRAP_CONTENT, 1f))
                if (s != null) r.addView(a.label(n.toString(), 15f, color = Ink.GRAY))
                col.addView(r, lp())
            }
            dialog.window?.setWindowAnimations(0)
            dialog.show()
        }
    }

    /** 책 선택: a full-screen list (paged with 쪽 단위) of the books with notes under the tab, filtered by title. */
    fun bookChooser() {
        val q0 = a.q.copy(bookId = null)
        a.scope.launch {
            val books = withContext(Dispatchers.IO) { runCatching { Notes.books(q0) }.getOrNull() } ?: return@launch
            if (a.isDestroyed) return@launch
            showBookChooser(books)
        }
    }

    private fun showBookChooser(books: List<NoteBook>) {
        val root = a.vertical { setBackgroundColor(Ink.WHITE) }
        var dialog: android.app.Dialog? = null
        root.addView(a.toolbar("책 선택", R.drawable.ic_arrow_back, onNav = { dialog?.dismiss() }), lp())
        val filter = InkEditText(a).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            hint = "책 제목"
            setSingleLine(true)
            setTextColor(Ink.BLACK)
            inkCursor(singleLine = true)
        }
        root.addView(FrameLayout(a).apply { setPadding(a.dp(16), a.dp(4), a.dp(16), a.dp(4)); addView(filter) }, lp())
        val list = InkListView(a)
        val total = books.sumOf { it.count }
        val adapter = BookAdapter(books, total) { b -> dialog?.dismiss(); a.setBook(b?.id) }
        list.adapter = adapter
        root.addView(list, lp(MATCH_PARENT, 0, 1f))
        val bar = InkPagerBar(a)
        root.addView(bar)
        val pager = ListPager(list, bar)
        val paged = ListPaging.paged(Settings.app.listPaging)
        list.paged = paged
        if (paged) list.pager = pager else bar.visibility = View.GONE
        filter.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                adapter.filter(s?.toString().orEmpty())
                list.setSelection(0)
                pager.update()
            }
        })
        dialog = a.fullScreenDialog(root).also { it.show() }
    }

    private inner class BookAdapter(private val all: List<NoteBook>, private val total: Int, private val onPick: (NoteBook?) -> Unit) : BaseAdapter() {
        private var shown: List<NoteBook> = all

        fun filter(text: String) {
            val t = text.trim()
            shown = if (t.isEmpty()) all else all.filter { it.title.contains(t, ignoreCase = true) }
            notifyDataSetChanged()
        }

        override fun getCount(): Int = shown.size + 1
        override fun getItem(position: Int): NoteBook? = if (position == 0) null else shown[position - 1]
        override fun getItemId(position: Int): Long = getItem(position)?.id ?: -1L

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = (convertView as? LinearLayout) ?: a.horizontal {
                minimumHeight = a.dp(56)
                setPadding(a.dp(16), a.dp(8), a.dp(16), a.dp(8))
                background = pressableBackground()
                val texts = a.vertical()
                texts.addView(a.label("", 16f, maxLines = 2), lp())
                texts.addView(a.label("", 13f, color = Ink.GRAY, maxLines = 1), lp())
                addView(texts, lp(0, WRAP_CONTENT, 1f))
                addView(a.label("", 15f, color = Ink.GRAY).apply { gravity = Gravity.END; setPadding(a.dp(12), 0, 0, 0) })
            }
            val texts = row.getChildAt(0) as LinearLayout
            val title = texts.getChildAt(0) as TextView
            val author = texts.getChildAt(1) as TextView
            val count = row.getChildAt(1) as TextView
            val b = getItem(position)
            if (b == null) {
                title.text = "모든 책 · $total"
                author.visibility = View.GONE
                count.text = ""
            } else {
                val suffix = if (b.missing) " (파일 없음)" else if (b.trashed) " (휴지통)" else ""
                title.text = "《${b.title}》$suffix"
                author.text = b.author
                author.visibility = if (b.author.isBlank()) View.GONE else View.VISIBLE
                count.text = b.count.toString()
            }
            val selected = b?.id == a.q.bookId
            title.typeface = if (selected) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
            row.setOnClickListener { onPick(b) }
            return row
        }
    }
}
