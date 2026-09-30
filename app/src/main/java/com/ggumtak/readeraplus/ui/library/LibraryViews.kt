package com.ggumtak.readeraplus.ui.library

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.ggumtak.readeraplus.R
import com.ggumtak.readeraplus.data.Book
import com.ggumtak.readeraplus.data.Shelf
import com.ggumtak.readeraplus.data.ShelfGroup
import com.ggumtak.readeraplus.ui.kit.Ink
import com.ggumtak.readeraplus.ui.kit.borderBox
import com.ggumtak.readeraplus.ui.kit.dp
import com.ggumtak.readeraplus.ui.kit.dpF
import com.ggumtak.readeraplus.ui.kit.horizontal
import com.ggumtak.readeraplus.ui.kit.iconButton
import com.ggumtak.readeraplus.ui.kit.label
import com.ggumtak.readeraplus.ui.kit.lp
import com.ggumtak.readeraplus.ui.kit.pressableBackground
import com.ggumtak.readeraplus.ui.kit.vertical

/** A book with its display strings precomputed off the main thread. */
internal class BookRow(
    val book: Book,
    val title: String,
    val meta: String,
    val percent: String,
    val inCollection: Boolean,
) {
    val opened: Boolean get() = book.lastReadAt > 0

    /** "새 책" / "완독" in place of a card's or cell's progress line (unopened books only), else null. */
    val tag: String? get() = if (opened) null else LibraryText.statusTag(false, book.haveRead)

    /** Second line of a compact row; built on its first bind (only the compact view shows it, a few rows at a time). */
    private var compact: String? = null

    fun compactLine(): String = compact ?: LibraryText.compactLine(book.author, opened, book.haveRead, percent) {
        LibraryText.ago(book.lastReadAt, System.currentTimeMillis())
    }.also { compact = it }

    companion object {
        fun of(book: Book, inCollection: Boolean): BookRow = BookRow(
            book = book,
            title = book.title.ifBlank { book.fileName.substringBeforeLast('.') },
            meta = LibraryText.metaLine(book.format.label, book.sizeBytes),
            percent = LibraryText.percent(book.progress, book.lastReadAt > 0),
            inCollection = inCollection,
        )
    }
}

/** Which flag a card button toggles. */
internal enum class BookFlag { FAVORITE, TO_READ, HAVE_READ }

/** Card callbacks (implemented by the activity). */
internal interface BookActions {
    /** Multi-select state (T1-13) the items draw as check boxes. */
    val selection: BookSelection
    /** Tap on a book: open it (its menu in the trash), or check / uncheck it while selecting. */
    fun tap(row: BookRow, anchor: View)
    /** Long-press on a book: starts multi-select with it checked (the trash keeps its book menu). */
    fun longPress(row: BookRow, anchor: View)
    fun showBookMenu(row: BookRow, anchor: View)
    fun toggleFlag(row: BookRow, flag: BookFlag)
    fun showCollections(book: Book)
}

/** A bound book item that draws the multi-select state. */
internal interface SelectableHolder {
    /**
     * Brings the check box (and the controls hidden while selecting) in line with [BookActions.selection]. Views
     * already in the right state are not touched, so a toggle repaints one check box, not the list.
     */
    fun showSelection()
}

/** Box icon for a check state (static vectors: the platform check marks animate, a run of e-ink frames). */
internal fun checkIcon(checked: Boolean): Int = if (checked) R.drawable.ic_check_box else R.drawable.ic_check_box_outline_blank

/** Sets the visibility unless the view already has it (no relayout request, no repaint for a no-op). */
private fun View.show(visibility: Int) {
    if (this.visibility != visibility) this.visibility = visibility
}

/**
 * Multi-select check box of one item: a white bordered badge when drawn over a cover (readable on a dark one),
 * plain at the end of a compact row. Hidden outside selection mode.
 */
internal class CheckMark(ctx: Context, badge: Boolean) {
    val view: ImageView = ImageView(ctx).apply {
        imageTintList = ColorStateList.valueOf(Ink.BLACK)
        scaleType = ImageView.ScaleType.CENTER
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        if (badge) {
            background = ctx.borderBox()
            val p = ctx.dp(2)
            setPadding(p, p, p, p)
        }
        visibility = View.GONE
    }
    private var shownRes = 0

    /** [checked] null = not selecting (hidden). */
    fun set(checked: Boolean?) {
        if (checked == null) {
            view.show(View.GONE)
            return
        }
        val res = checkIcon(checked)
        if (res != shownRes) {
            view.setImageResource(res)
            shownRes = res
        }
        view.show(View.VISIBLE)
    }
}

/** Selection state of [row] for [CheckMark.set]: null outside selection mode. */
private fun BookSelection.stateOf(row: BookRow?): Boolean? = if (!active || row == null) null else row.book.id in this

/** Thin reading-progress line: grey track, black filled part, a dot at the position and at both ends. */
internal class ProgressLineView(context: Context) : View(context) {
    private val track = Paint().apply { color = Ink.GRAY; strokeWidth = context.dpF(1f).coerceAtLeast(1f) }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Ink.BLACK
        strokeWidth = context.dpF(3f)
        strokeCap = Paint.Cap.ROUND
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ink.BLACK; style = Paint.Style.FILL }
    private val endR = context.dpF(2.5f)
    private val posR = context.dpF(4.5f)

    private var progress = 0f
    private var opened = false

    fun set(progress: Float, opened: Boolean) {
        val p = progress.coerceIn(0f, 1f)
        if (p == this.progress && opened == this.opened) return
        this.progress = p
        this.opened = opened
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val cy = height / 2f
        val left = posR
        val right = width - posR
        if (right <= left) return
        canvas.drawLine(left, cy, right, cy, track)
        canvas.drawCircle(left, cy, endR, dot)
        canvas.drawCircle(right, cy, endR, dot)
        if (opened) {
            val x = left + (right - left) * progress
            if (x > left) canvas.drawLine(left, cy, x, cy, fill)
            canvas.drawCircle(x, cy, posR, dot)
        }
    }
}

/** Placeholder/background of a cover: white box with a 1px black border. */
internal fun Context.coverBox() = borderBox()

/** Card-like button background: bordered white, grey while pressed (no ripple). */
internal fun Context.buttonBackground() = StateListDrawable().apply {
    addState(intArrayOf(android.R.attr.state_pressed), borderBox(Ink.PRESSED))
    addState(intArrayOf(), borderBox())
}

/** Bordered text button with a 48dp touch height. */
internal fun Context.textButton(text: String, onClick: (View) -> Unit): TextView = label(text, 16f, bold = true).apply {
    gravity = Gravity.CENTER
    minHeight = dp(48)
    minWidth = dp(96)
    setPadding(dp(16), dp(8), dp(16), dp(8))
    background = buttonBackground()
    setOnClickListener(onClick)
}

/** A cover image with the multi-select badge in its top-left corner. */
private fun Context.coverFrame(cover: ImageView, check: CheckMark, w: Int, h: Int): FrameLayout = FrameLayout(this).apply {
    addView(cover, FrameLayout.LayoutParams(w, h))
    val m = dp(4)
    addView(check.view, FrameLayout.LayoutParams(dp(28), dp(28), Gravity.TOP or Gravity.START).apply { setMargins(m, m, m, m) })
}

// ---------------------------------------------------------------------------------------------- list card

/**
 * List-mode book card (ReadEra layout, black & white): cover left; title, author, "TXT, 3.4MB", progress (or "새 책")
 * and the five action buttons on the right. While selecting, the cover carries a check box and the buttons hide
 * (invisible: the card keeps its height).
 */
internal class BookCardHolder(private val ctx: Context, private val actions: BookActions) : SelectableHolder {
    val coverW = ctx.dp(96)
    val coverH = ctx.dp(136)

    val root: LinearLayout
    private val cover: ImageView
    private val check = CheckMark(ctx, badge = true)
    private val title: TextView
    private val author: TextView
    private val meta: TextView
    private val progress: ProgressLineView
    private val tag: TextView
    private val percent: TextView
    private val fav: ImageButton
    private val toRead: ImageButton
    private val haveRead: ImageButton
    private val coll: ImageButton
    private val more: ImageButton
    private var row: BookRow? = null

    init {
        root = LinearLayout(ctx).apply {
            layoutParams = AbsListView.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            setPadding(ctx.dp(8), ctx.dp(4), ctx.dp(8), ctx.dp(4))
        }
        val card = ctx.horizontal {
            gravity = Gravity.TOP
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), ctx.borderBox(Ink.PRESSED))
                addState(intArrayOf(), ctx.borderBox())
            }
            setPadding(ctx.dp(8), ctx.dp(8), ctx.dp(4), ctx.dp(4))
            setOnClickListener { row?.let { actions.tap(it, more) } }
            setOnLongClickListener { row?.let { actions.longPress(it, more) }; true }
        }
        cover = ImageView(ctx).apply {
            background = ctx.coverBox()
            setPadding(1, 1, 1, 1)
            scaleType = ImageView.ScaleType.CENTER_CROP
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        card.addView(ctx.coverFrame(cover, check, coverW, coverH), LinearLayout.LayoutParams(coverW, coverH))

        val col = ctx.vertical { setPadding(ctx.dp(12), 0, 0, 0) }
        title = ctx.label("", 18f, bold = true, maxLines = 3).apply { setLineSpacing(0f, 1.1f) }
        author = ctx.label("", 14f, color = Ink.GRAY, maxLines = 1).apply { setPadding(0, ctx.dp(4), 0, 0) }
        meta = ctx.label("", 14f, color = Ink.GRAY, maxLines = 1).apply { setPadding(0, ctx.dp(3), 0, 0) }
        col.addView(title, lp())
        col.addView(author, lp())
        col.addView(meta, lp())
        col.addView(View(ctx), lp(MATCH_PARENT, 0, 1f).apply { height = 0; topMargin = ctx.dp(6) })

        val progRow = ctx.horizontal {
            setPadding(0, 0, ctx.dp(8), 0)
            minimumHeight = ctx.dp(16)
        }
        progress = ProgressLineView(ctx)
        progRow.addView(progress, lp(0, ctx.dp(14), 1f))
        // "새 책" / "완독" instead of an empty track for a book never opened (A13).
        tag = ctx.label("", 13f, bold = true, maxLines = 1).apply { visibility = View.GONE }
        progRow.addView(tag, lp(0, WRAP_CONTENT, 1f))
        percent = ctx.label("", 13f).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            minWidth = ctx.dp(40)
        }
        progRow.addView(percent, lp(WRAP_CONTENT, WRAP_CONTENT))
        col.addView(progRow, lp())

        val actionsRow = ctx.horizontal()
        fun btn(res: Int, desc: String, onClick: (BookRow) -> Unit): ImageButton =
            ctx.iconButton(res, desc) { row?.let(onClick) }.apply {
                isFocusable = false
                layoutParams = LinearLayout.LayoutParams(0, ctx.dp(48), 1f)
            }
        fav = btn(R.drawable.ic_star, Shelf.FAVORITES.label) { actions.toggleFlag(it, BookFlag.FAVORITE) }
        toRead = btn(R.drawable.ic_schedule, Shelf.TO_READ.label) { actions.toggleFlag(it, BookFlag.TO_READ) }
        haveRead = btn(R.drawable.ic_done_all, Shelf.HAVE_READ.label) { actions.toggleFlag(it, BookFlag.HAVE_READ) }
        coll = btn(R.drawable.ic_library_books, "컬렉션") { actions.showCollections(it.book) }
        more = btn(R.drawable.ic_more_vert, "더보기") { r -> actions.showBookMenu(r, moreAnchor()) }
        listOf(fav, toRead, haveRead, coll, more).forEach { actionsRow.addView(it) }
        col.addView(actionsRow, lp())

        card.addView(col, lp(0, MATCH_PARENT, 1f))
        root.addView(card, lp())
        // The text column must be at least as tall as the cover so the actions sit at the card bottom.
        col.minimumHeight = coverH
        root.tag = this
    }

    private fun moreAnchor(): View = more

    fun bind(r: BookRow) {
        row = r
        val b = r.book
        title.text = r.title
        author.text = b.author
        author.visibility = if (b.author.isBlank()) View.GONE else View.VISIBLE
        meta.text = r.meta
        val t = r.tag
        if (t == null) {
            progress.set(b.progress, r.opened)
            progress.show(View.VISIBLE)
            tag.show(View.GONE)
        } else {
            if (tag.text.toString() != t) tag.text = t
            tag.show(View.VISIBLE)
            progress.show(View.GONE)
        }
        percent.text = r.percent
        setIcon(fav, if (b.favorite) R.drawable.ic_star_fill else R.drawable.ic_star)
        setIcon(toRead, if (b.toRead) R.drawable.ic_schedule_fill else R.drawable.ic_schedule)
        setIcon(haveRead, if (b.haveRead) R.drawable.ic_done_all_fill else R.drawable.ic_done_all)
        setIcon(coll, if (r.inCollection) R.drawable.ic_library_books_fill else R.drawable.ic_library_books)
        showSelection()
        CoverLoader.bind(ctx, cover, b, coverW - 2, coverH - 2)
    }

    override fun showSelection() {
        val state = actions.selection.stateOf(row)
        check.set(state)
        val selecting = state != null
        // Flags stay hidden on trashed books; every button hides while selecting (a tap then checks the card).
        val flags = if (selecting || row?.book?.trashed == true) View.INVISIBLE else View.VISIBLE
        fav.show(flags)
        toRead.show(flags)
        haveRead.show(flags)
        coll.show(flags)
        more.show(if (selecting) View.INVISIBLE else View.VISIBLE)
    }

    /** Icon currently set per action button (avoids re-inflating vector drawables on every bind). */
    private val shownIcons = HashMap<ImageButton, Int>(8)

    private fun setIcon(button: ImageButton, res: Int) {
        if (shownIcons[button] == res) return
        button.setImageResource(res)
        shownIcons[button] = res
    }
}

// ---------------------------------------------------------------------------------------------- compact row

/**
 * "간단히" row (T1-13): 56dp, the title (16sp bold, one line) over "작가 · 34% · 3일 전" (13sp grey), and ⋮ for the book
 * menu. No cover and no flag buttons, so about twelve books fit on the Comet's screen. While selecting, a check box
 * takes the ⋮'s place.
 */
internal class CompactRowHolder(private val ctx: Context, private val actions: BookActions) : SelectableHolder {
    val root: LinearLayout
    private val title: TextView
    private val sub: TextView
    private val more: ImageButton
    private val check = CheckMark(ctx, badge = false)
    private var row: BookRow? = null

    init {
        root = ctx.vertical { layoutParams = AbsListView.LayoutParams(MATCH_PARENT, WRAP_CONTENT) }
        val line = ctx.horizontal {
            minimumHeight = ctx.dp(56)
            setPadding(ctx.dp(16), ctx.dp(6), 0, ctx.dp(6))
            background = pressableBackground()
            setOnClickListener { row?.let { actions.tap(it, more) } }
            setOnLongClickListener { row?.let { actions.longPress(it, more) }; true }
        }
        val texts = ctx.vertical()
        title = ctx.label("", 16f, bold = true, maxLines = 1)
        sub = ctx.label("", 13f, color = Ink.GRAY, maxLines = 1).apply { setPadding(0, ctx.dp(4), 0, 0) }
        texts.addView(title, lp())
        texts.addView(sub, lp())
        line.addView(texts, lp(0, WRAP_CONTENT, 1f))
        // ⋮ and the check box share one 48dp slot, so entering selection mode doesn't move the text.
        val slot = FrameLayout(ctx)
        more = ctx.iconButton(R.drawable.ic_more_vert, "더보기") { v -> row?.let { actions.showBookMenu(it, v) } }.apply {
            isFocusable = false
        }
        slot.addView(more, FrameLayout.LayoutParams(ctx.dp(48), ctx.dp(48)))
        slot.addView(check.view, FrameLayout.LayoutParams(ctx.dp(48), ctx.dp(48)))
        line.addView(slot, LinearLayout.LayoutParams(ctx.dp(48), ctx.dp(48)))
        root.addView(line, lp())
        root.addView(View(ctx).apply { setBackgroundColor(Ink.LINE) }, LinearLayout.LayoutParams(MATCH_PARENT, 1).apply {
            leftMargin = ctx.dp(16)
        })
        root.tag = this
    }

    fun bind(r: BookRow) {
        row = r
        title.text = r.title
        sub.text = r.compactLine()
        showSelection()
    }

    override fun showSelection() {
        val state = actions.selection.stateOf(row)
        check.set(state)
        more.show(if (state != null) View.INVISIBLE else View.VISIBLE)
    }
}

// ---------------------------------------------------------------------------------------------- grid cell

/** Grid ("표지") cell: cover (with the check badge while selecting), a thin progress line or "새 책", 2-line title. */
internal class GridCellHolder(private val ctx: Context, private val actions: BookActions, cellWidth: Int) : SelectableHolder {
    private val coverW = (cellWidth - ctx.dp(8)).coerceAtLeast(ctx.dp(48))
    private val coverH = coverW * 136 / 96
    val root: LinearLayout
    private val cover: ImageView
    private val check = CheckMark(ctx, badge = true)
    private val title: TextView
    private val progress: ProgressLineView
    private val tag: TextView
    private var row: BookRow? = null

    init {
        root = ctx.vertical {
            layoutParams = AbsListView.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(ctx.dp(4), ctx.dp(4), ctx.dp(4), ctx.dp(6))
            background = pressableBackground()
            setOnClickListener { v -> row?.let { actions.tap(it, v) } }
            setOnLongClickListener { v -> row?.let { actions.longPress(it, v) }; true }
        }
        cover = ImageView(ctx).apply {
            background = ctx.coverBox()
            setPadding(1, 1, 1, 1)
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        root.addView(ctx.coverFrame(cover, check, coverW, coverH), LinearLayout.LayoutParams(coverW, coverH))
        // The progress line and the "새 책" tag share one fixed-height slot: the cells keep one height.
        val slot = FrameLayout(ctx)
        progress = ProgressLineView(ctx)
        slot.addView(progress, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        tag = ctx.label("", 11f, bold = true, maxLines = 1).apply {
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        slot.addView(tag, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.addView(slot, LinearLayout.LayoutParams(coverW, ctx.dp(16)))
        title = ctx.label("", 13f, bold = true, maxLines = 2).apply { gravity = Gravity.CENTER_HORIZONTAL }
        root.addView(title, LinearLayout.LayoutParams(coverW, WRAP_CONTENT))
        root.tag = this
    }

    fun bind(r: BookRow) {
        row = r
        title.text = r.title
        val t = r.tag
        if (t == null) {
            progress.set(r.book.progress, r.opened)
            progress.show(View.VISIBLE)
            tag.show(View.GONE)
        } else {
            if (tag.text.toString() != t) tag.text = t
            tag.show(View.VISIBLE)
            progress.show(View.INVISIBLE)
        }
        showSelection()
        CoverLoader.bind(ctx, cover, r.book, coverW - 2, coverH - 2)
    }

    override fun showSelection() {
        check.set(actions.selection.stateOf(row))
    }
}

// ---------------------------------------------------------------------------------------------- adapters

/** Rows of one book view; [submit] replaces them (the activity skips it when nothing changed). */
internal abstract class BookAdapter : BaseAdapter() {
    var rows: List<BookRow> = emptyList()
        private set

    fun submit(newRows: List<BookRow>) {
        rows = newRows
        notifyDataSetChanged()
    }

    override fun getCount(): Int = rows.size
    override fun getItem(position: Int): Any = rows[position]
    override fun getItemId(position: Int): Long = rows[position].book.id
    override fun hasStableIds(): Boolean = true
}

internal class BookListAdapter(private val ctx: Context, private val actions: BookActions) : BookAdapter() {
    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val holder = (convertView?.tag as? BookCardHolder) ?: BookCardHolder(ctx, actions)
        holder.bind(rows[position])
        return holder.root
    }
}

/** "간단히" rows (T1-13). */
internal class CompactListAdapter(private val ctx: Context, private val actions: BookActions) : BookAdapter() {
    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val holder = (convertView?.tag as? CompactRowHolder) ?: CompactRowHolder(ctx, actions)
        holder.bind(rows[position])
        return holder.root
    }
}

internal class BookGridAdapter(private val ctx: Context, private val actions: BookActions) : BookAdapter() {
    var cellWidth: Int = 0

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val holder = (convertView?.tag as? GridCellHolder) ?: GridCellHolder(ctx, actions, cellWidth)
        holder.bind(rows[position])
        return holder.root
    }
}

/** Rows of a grouped shelf (작가 / 시리즈 / 컬렉션 / 형식 / 폴더): icon, name (+ folder path), count. */
internal class GroupAdapter(
    private val ctx: Context,
    private val onOpen: (ShelfGroup) -> Unit,
    private val onLongPress: (ShelfGroup) -> Boolean,
) : BaseAdapter() {
    var shelf: Shelf = Shelf.AUTHORS
    var groups: List<ShelfGroup> = emptyList()
        private set

    fun submit(shelf: Shelf, newGroups: List<ShelfGroup>) {
        this.shelf = shelf
        groups = newGroups
        notifyDataSetChanged()
    }

    private class Holder(val root: LinearLayout, val icon: ImageView, val name: TextView, val sub: TextView, val count: TextView) {
        var group: ShelfGroup? = null
        /** Icon currently set (vector drawables are not re-inflated on every bind). */
        var iconRes = 0
    }

    override fun getCount(): Int = groups.size
    override fun getItem(position: Int): Any = groups[position]
    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val h = (convertView?.tag as? Holder) ?: createHolder()
        val g = groups[position]
        h.group = g
        val res = groupIcon(shelf)
        if (h.iconRes != res) {
            h.icon.setImageResource(res)
            h.iconRes = res
        }
        h.name.text = LibraryText.groupTitle(shelf, g)
        val sub = LibraryText.groupSubtitle(shelf, g)
        if (sub != null) {
            h.sub.text = sub
            h.sub.visibility = View.VISIBLE
        } else {
            h.sub.visibility = View.GONE
        }
        h.count.text = g.count.toString()
        return h.root
    }

    private fun createHolder(): Holder {
        val outer = ctx.vertical { layoutParams = AbsListView.LayoutParams(MATCH_PARENT, WRAP_CONTENT) }
        val row = ctx.horizontal {
            minimumHeight = ctx.dp(60)
            setPadding(ctx.dp(16), ctx.dp(8), ctx.dp(16), ctx.dp(8))
            background = pressableBackground()
        }
        val ic = ImageView(ctx).apply {
            imageTintList = ColorStateList.valueOf(Ink.BLACK)
            layoutParams = LinearLayout.LayoutParams(ctx.dp(24), ctx.dp(24)).apply { rightMargin = ctx.dp(20) }
        }
        row.addView(ic)
        val texts = ctx.vertical()
        val name = ctx.label("", 17f, maxLines = 2)
        val sub = ctx.label("", 13f, color = Ink.GRAY, maxLines = 2).apply { setPadding(0, ctx.dp(2), 0, 0) }
        texts.addView(name, lp())
        texts.addView(sub, lp())
        row.addView(texts, lp(0, WRAP_CONTENT, 1f))
        val count = ctx.label("", 15f, color = Ink.GRAY).apply { setPadding(ctx.dp(12), 0, 0, 0) }
        row.addView(count)
        outer.addView(row, lp())
        outer.addView(View(ctx).apply { setBackgroundColor(Ink.LINE) }, LinearLayout.LayoutParams(MATCH_PARENT, 1).apply {
            leftMargin = ctx.dp(16)
        })
        val h = Holder(outer, ic, name, sub, count)
        row.setOnClickListener { h.group?.let(onOpen) }
        row.setOnLongClickListener { h.group?.let(onLongPress) ?: false }
        outer.tag = h
        return h
    }

    companion object {
        fun groupIcon(shelf: Shelf): Int = when (shelf) {
            Shelf.AUTHORS -> R.drawable.ic_person
            Shelf.SERIES -> R.drawable.ic_sell
            Shelf.COLLECTIONS -> R.drawable.ic_library_books
            Shelf.FORMATS -> R.drawable.ic_layers
            else -> R.drawable.ic_folder
        }
    }
}

/** Bold label helper used by the drawer header. */
internal fun TextView.bold(on: Boolean) {
    typeface = if (on) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
}
