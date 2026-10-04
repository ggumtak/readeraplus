package com.ggumtak.readeraplus.ui.notes

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.ggumtak.readeraplus.R
import com.ggumtak.readeraplus.data.NoteBook
import com.ggumtak.readeraplus.data.NoteKind
import com.ggumtak.readeraplus.data.NoteRow
import com.ggumtak.readeraplus.data.NotesPage
import com.ggumtak.readeraplus.data.NotesTab
import com.ggumtak.readeraplus.reader.extras.QuoteSwatch
import com.ggumtak.readeraplus.render.DeviceClass
import com.ggumtak.readeraplus.render.QuoteLook
import com.ggumtak.readeraplus.ui.kit.CardButton
import com.ggumtak.readeraplus.ui.kit.Ink
import com.ggumtak.readeraplus.ui.kit.dp
import com.ggumtak.readeraplus.ui.kit.horizontal
import com.ggumtak.readeraplus.ui.kit.icon
import com.ggumtak.readeraplus.ui.kit.label
import com.ggumtak.readeraplus.ui.kit.lp
import com.ggumtak.readeraplus.ui.kit.pressableBackground
import com.ggumtak.readeraplus.ui.kit.vertical

/**
 * The hub's windowed adapter (N §9.7): [getCount] is the query's count, rows come from [window] pages, and a row whose
 * page is not loaded yet binds a 64 dp "불러오는 중…" placeholder and asks [Callbacks.requestPage] for it. One holder
 * class serves every kind (the parts per kind are shown or hidden). Main thread only.
 */
internal class NotesAdapter(private val ctx: Context, private val window: NotesWindow<NotesPage>, private val cb: Callbacks) : BaseAdapter() {

    interface Callbacks {
        fun requestPage(page: Int)
        val tab: NotesTab
        /** The book filter is on, or the list is grouped by book: the meta line leaves the book out. */
        val bookImplied: Boolean
        val byBook: Boolean
        val selecting: Boolean
        fun isSelected(row: NoteRow): Boolean
        fun book(id: Long): NoteBook?
        fun onMenu(row: NoteRow, anchor: View)
        fun onSwatch(row: NoteRow, anchor: View)
        fun onLookUp(row: NoteRow)
        fun onBookHeader(book: NoteBook)
        /** A tap on the row: open, edit, or toggle while selecting. */
        fun onRowTap(row: NoteRow, position: Int)
    }

    override fun getCount(): Int = window.count
    override fun getItem(position: Int): NoteRow? = rowAt(position)
    override fun getItemId(position: Int): Long = rowAt(position)?.ref?.packed() ?: -1L - position
    override fun hasStableIds(): Boolean = false

    fun rowAt(position: Int): NoteRow? {
        val page = window.pageFor(position) ?: return null
        return page.rows.getOrNull(window.indexIn(position))
    }

    private fun previous(position: Int, page: NotesPage): NoteRow? {
        val i = window.indexIn(position)
        return if (i > 0) page.rows.getOrNull(i - 1) else page.before
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val h = (convertView?.tag as? Holder) ?: Holder(ctx, eink)
        val page = window.pageFor(position)
        val row = page?.rows?.getOrNull(window.indexIn(position))
        if (page == null || row == null) {
            if (page == null) cb.requestPage(window.pageOf(position))
            h.placeholder()
        } else {
            bind(h, position, page, row)
        }
        return h.root
    }

    private fun bind(h: Holder, position: Int, page: NotesPage, row: NoteRow) {
        val now = System.currentTimeMillis()
        val tab = cb.tab
        h.loading.visibility = View.GONE
        h.body.visibility = View.VISIBLE
        h.divider.visibility = View.VISIBLE
        bindHeader(h, position, page, row, now)

        h.check.visibility = if (cb.selecting) View.VISIBLE else View.GONE
        if (cb.selecting) h.check.setImageResource(if (cb.isSelected(row)) R.drawable.ic_check_box else R.drawable.ic_check_box_outline_blank)
        h.content.setPadding(if (cb.selecting) 0 else ctx.dp(16), ctx.dp(12), 0, ctx.dp(12))

        val book = cb.book(row.bookId)
        val kind = row.ref.kind
        val memoTab = tab == NotesTab.MEMOS
        h.titleLine.visibility = View.GONE
        h.tag.visibility = View.GONE
        h.mark.visibility = View.GONE
        h.badge.visibility = View.GONE
        h.secondary.visibility = View.GONE
        h.note.visibility = View.GONE
        h.swatchCell.visibility = View.GONE
        h.lookUp.visibility = View.GONE
        h.menu.setOnClickListener { cb.onMenu(row, it) }
        // The row's own listeners, like the library cards: a paged list (e-ink) consumes its touches for paging and
        // never runs the ListView's item click; a vertical drag past the slop still becomes the list's (PageDrag).
        // The whole item takes them, as the item click did: the body, and a day header above it (CI 34: a tap on the
        // first row's "오늘" header did nothing). A book header keeps its own (it opens that book's notes).
        val tap = View.OnClickListener { cb.onRowTap(row, position) }
        val press = View.OnLongClickListener { v -> cb.onMenu(row, v); true }
        h.root.setOnClickListener(tap)
        h.root.setOnLongClickListener(press)
        h.body.setOnClickListener(tap)
        h.body.setOnLongClickListener(press)

        when (kind) {
            NoteKind.QUOTE, NoteKind.BOOKMARK -> {
                if (kind == NoteKind.QUOTE) {
                    h.swatchCell.visibility = View.VISIBLE
                    h.swatch.style = row.style
                    h.swatch.invalidate()
                    h.swatchCell.setOnClickListener { cb.onSwatch(row, it) }
                }
                if (memoTab && row.note.isNotBlank()) {
                    text(h.text, row.note, 16f, Ink.BLACK, 4)
                    if (row.body.isNotBlank()) {
                        h.secondary.visibility = View.VISIBLE
                        text(h.secondary, "“" + row.body.trim() + "”", 14f, Ink.GRAY, 2)
                    }
                } else if (kind == NoteKind.QUOTE) {
                    text(h.text, "“" + row.body.trim() + "”", 16f, Ink.BLACK, 4)
                    noteLine(h, "메모", row.note)
                } else {
                    // Bookmark: icon + title (the note's first line, else the chapter), the snippet under it.
                    val firstLine = row.note.trim().lineSequence().firstOrNull()?.trim().orEmpty()
                    h.titleLine.visibility = View.VISIBLE
                    h.mark.visibility = View.VISIBLE
                    text(h.title, firstLine.ifEmpty { row.chapter.ifBlank { NoteKind.BOOKMARK.label } }, 16f, Ink.BLACK, 1, bold = true)
                    text(h.text, row.body.trim(), 15f, Ink.GRAY, 2)
                    val rest = row.note.trim().substringAfter('\n', "").trim()
                    noteLine(h, "메모", rest)
                }
            }
            NoteKind.REVIEW -> {
                h.titleLine.visibility = View.VISIBLE
                h.tag.visibility = View.VISIBLE
                text(h.title, book?.title ?: "(삭제된 책)", 16f, Ink.BLACK, 1, bold = true)
                text(h.text, row.body.trim(), 15f, Ink.BLACK, 6)
            }
            NoteKind.LOOKUP -> {
                h.titleLine.visibility = View.VISIBLE
                text(h.title, NotesText.wordTitle(row.word), 18f, Ink.BLACK, 1, bold = true)
                val badge = NotesText.countBadge(row.wordCount)
                if (badge.isNotEmpty()) {
                    h.badge.visibility = View.VISIBLE
                    h.badge.text = badge
                }
                h.lookUp.visibility = View.VISIBLE
                h.lookUp.setOnClickListener { cb.onLookUp(row) }
                val sentence = row.body.trim()
                val r = NotesText.boldRange(sentence, row.word)
                val s: CharSequence = if (r == null) sentence else SpannableString(sentence).apply {
                    setSpan(StyleSpan(Typeface.BOLD), r.first, r.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                text(h.text, s, 15f, Ink.BLACK, 3)
                noteLine(h, "뜻", row.note)
            }
        }
        h.text.visibility = if (h.text.text.isNullOrEmpty()) View.GONE else View.VISIBLE
        h.meta.text = meta(row, book, tab, now)
    }

    private fun meta(row: NoteRow, book: NoteBook?, tab: NotesTab, now: Long): String {
        val kind = row.ref.kind
        val time = NotesText.time(row.time, now)
        if (kind == NoteKind.REVIEW) return NotesText.meta(kind.label, NotesText.percent(row.frac)?.let { "$it%" }, time)
        val kindLabel = if (tab == NotesTab.ALL) kind.label else null
        val bookPart = if (cb.bookImplied) null else NotesText.bookPart(book?.title, book?.trashed == true, book?.missing == true)
        val app = if (kind == NoteKind.LOOKUP) row.app else null
        return NotesText.meta(kindLabel, bookPart, NotesText.place(row.chapter, row.frac), app, time)
    }

    private fun bindHeader(h: Holder, position: Int, page: NotesPage, row: NoteRow, now: Long) {
        if (cb.byBook) {
            val i = window.indexIn(position)
            val first = page.firstOfBook.getOrElse(i) { position == 0 }
            val book = cb.book(row.bookId)
            if (!first) {
                h.header.visibility = View.GONE
                return
            }
            h.header.visibility = View.VISIBLE
            h.header.minimumHeight = ctx.dp(40)
            text(h.headerTitle, NotesText.bookHeader(book?.title ?: "(삭제된 책)", book?.count ?: 0), 15f, Ink.BLACK, 1, bold = true)
            h.headerAuthor.visibility = if (book?.author.isNullOrBlank()) View.GONE else View.VISIBLE
            h.headerAuthor.text = book?.author.orEmpty()
            if (book != null && !cb.selecting) h.header.setOnClickListener { cb.onBookHeader(book) }
            else h.header.setOnClickListener(null)
            h.header.isClickable = book != null && !cb.selecting
            return
        }
        val prev = previous(position, page)
        val day = NotesText.dayKey(row.time)
        if (prev != null && NotesText.dayKey(prev.time) == day) {
            h.header.visibility = View.GONE
            return
        }
        h.header.visibility = View.VISIBLE
        h.header.minimumHeight = ctx.dp(32)
        text(h.headerTitle, NotesText.dayHeader(row.time, now), 14f, Ink.BLACK, 1, bold = true)
        h.headerAuthor.visibility = View.GONE
        h.header.setOnClickListener(null)
        h.header.isClickable = false
    }

    private fun noteLine(h: Holder, labelText: String, note: String) {
        if (note.isBlank()) return
        h.note.visibility = View.VISIBLE
        val s = SpannableString(labelText + "  " + note.trim())
        s.setSpan(StyleSpan(Typeface.BOLD), 0, labelText.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        text(h.note, s, 14f, Ink.BLACK, 3)
    }

    private fun text(v: TextView, s: CharSequence, sizeSp: Float, color: Int, lines: Int, bold: Boolean = false) {
        v.text = s
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        v.setTextColor(color)
        v.maxLines = lines
        v.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    /** One row view: day/book header, checkbox column, content, right column (swatch or 다시 찾기, then ⋮). */
    /** A Comet: the row's buttons show no pressed state ([Holder]). */
    private val eink = DeviceClass.cached(ctx) == true

    /**
     * One row's views. [eink]: ⋮, 다시 찾기 and the swatch drop the pressed background, as the library's card buttons
     * do (N §3.3 [Δ]): the menu or the change is the feedback, one e-ink update per tap instead of three.
     */
    private class Holder(ctx: Context, eink: Boolean) {
        val root: LinearLayout = ctx.vertical()
        val header: LinearLayout = ctx.horizontal {
            setPadding(ctx.dp(16), 0, ctx.dp(16), 0)
            background = android.graphics.drawable.LayerDrawable(arrayOf(
                android.graphics.drawable.ColorDrawable(Ink.WHITE), android.graphics.drawable.ColorDrawable(Ink.LINE_LIGHT),
            )).apply {
                setLayerGravity(1, Gravity.BOTTOM or Gravity.FILL_HORIZONTAL)
                setLayerHeight(1, 1)
            }
            isFocusable = false
        }
        val headerTitle: TextView = ctx.label("", 14f, bold = true, maxLines = 1)
        val headerAuthor: TextView = ctx.label("", 13f, color = Ink.GRAY, maxLines = 1).apply { setPadding(ctx.dp(12), 0, 0, 0) }
        val loading: TextView = ctx.label("불러오는 중…", 14f, color = Ink.GRAY).apply {
            gravity = Gravity.CENTER_VERTICAL
            minHeight = ctx.dp(64)
            setPadding(ctx.dp(16), 0, ctx.dp(16), 0)
        }
        val body: LinearLayout = ctx.horizontal { gravity = Gravity.TOP; background = pressableBackground() }
        val check: ImageView = ctx.icon(R.drawable.ic_check_box_outline_blank, 24).apply {
            scaleType = ImageView.ScaleType.CENTER
            layoutParams = LinearLayout.LayoutParams(ctx.dp(40), ctx.dp(48))
        }
        val content: LinearLayout = ctx.vertical()
        val titleLine: LinearLayout = ctx.horizontal()
        val tag: TextView = ctx.label(NoteKind.REVIEW.label, 13f, bold = true).apply { setPadding(0, 0, ctx.dp(8), 0) }
        val mark: ImageView = ctx.icon(R.drawable.ic_bookmark, 16).apply {
            (layoutParams as LinearLayout.LayoutParams).rightMargin = ctx.dp(6)
        }
        val title: TextView = ctx.label("", 16f, bold = true, maxLines = 1)
        val badge: TextView = ctx.label("", 12f, color = Ink.GRAY, maxLines = 1).apply { setPadding(ctx.dp(8), 0, 0, 0) }
        val text: TextView = ctx.label("", 16f, maxLines = 4).apply { setLineSpacing(0f, 1.2f) }
        val secondary: TextView = ctx.label("", 14f, color = Ink.GRAY, maxLines = 2).apply { setPadding(0, ctx.dp(4), 0, 0) }
        val note: TextView = ctx.label("", 14f, maxLines = 3).apply { setPadding(0, ctx.dp(6), 0, 0) }
        val meta: TextView = ctx.label("", 13f, color = Ink.GRAY, maxLines = 1).apply { setPadding(0, ctx.dp(6), 0, 0) }
        val right: LinearLayout = ctx.vertical()
        val swatch = QuoteSwatch(ctx, 0, 12, QuoteLook.ink())
        val swatchCell: FrameLayout = FrameLayout(ctx).apply {
            contentDescription = "색 바꾸기"
            background = pressableBackground()
            isFocusable = false
            addView(swatch, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER))
        }
        val lookUp = CardButton(ctx, R.drawable.ic_translate, "다시 찾기") {}
        val menu = CardButton(ctx, R.drawable.ic_more_vert, "노트 메뉴") {}
        val divider: View = View(ctx).apply { setBackgroundColor(Ink.LINE_LIGHT) }

        init {
            header.addView(headerTitle, lp(0, WRAP_CONTENT, 1f))
            header.addView(headerAuthor, lp(WRAP_CONTENT, WRAP_CONTENT))
            titleLine.addView(tag, lp(WRAP_CONTENT, WRAP_CONTENT))
            titleLine.addView(mark)
            titleLine.addView(title, lp(0, WRAP_CONTENT, 1f).apply { weight = 0f; width = WRAP_CONTENT })
            titleLine.addView(badge, lp(WRAP_CONTENT, WRAP_CONTENT))
            content.addView(titleLine, lp().apply { bottomMargin = ctx.dp(4) })
            content.addView(text, lp())
            content.addView(secondary, lp())
            content.addView(note, lp())
            content.addView(meta, lp())
            right.addView(swatchCell, LinearLayout.LayoutParams(ctx.dp(48), ctx.dp(40)))
            right.addView(lookUp, LinearLayout.LayoutParams(ctx.dp(48), ctx.dp(48)))
            right.addView(menu, LinearLayout.LayoutParams(ctx.dp(48), ctx.dp(48)))
            body.addView(check)
            body.addView(content, lp(0, WRAP_CONTENT, 1f))
            body.addView(right, lp(ctx.dp(48), WRAP_CONTENT))
            root.addView(header, lp())
            root.addView(loading, lp())
            root.addView(body, lp())
            root.addView(divider, LinearLayout.LayoutParams(MATCH_PARENT, 1).apply { leftMargin = ctx.dp(16) })
            title.isSingleLine = true
            if (eink) {
                lookUp.background = null
                menu.background = null
                swatchCell.background = null
            }
            root.tag = this
        }

        fun placeholder() {
            header.visibility = View.GONE
            body.visibility = View.GONE
            divider.visibility = View.GONE
            loading.visibility = View.VISIBLE
            // A row still loading takes no tap (a recycled view must not open the row it showed before).
            root.setOnClickListener(null)
            root.setOnLongClickListener(null)
            root.isClickable = false
            root.isLongClickable = false
        }
    }
}
