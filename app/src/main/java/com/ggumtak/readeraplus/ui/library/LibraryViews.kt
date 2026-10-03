package com.ggumtak.readeraplus.ui.library

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
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
import com.ggumtak.readeraplus.settings.LibraryListMode
import com.ggumtak.readeraplus.ui.kit.Ink
import com.ggumtak.readeraplus.ui.kit.CardButton
import com.ggumtak.readeraplus.ui.kit.borderBox
import com.ggumtak.readeraplus.ui.kit.dp
import com.ggumtak.readeraplus.ui.kit.dpF
import com.ggumtak.readeraplus.ui.kit.horizontal
import com.ggumtak.readeraplus.ui.kit.label
import com.ggumtak.readeraplus.ui.kit.lp
import com.ggumtak.readeraplus.ui.kit.pressableBackground
import com.ggumtak.readeraplus.ui.kit.vertical

/** A book with its display strings precomputed off the main thread (`reload()` on IO; no per-row DB call). */
internal class BookRow(
    val book: Book,
    val title: String,
    /** "TXT, 3.4MB · 3일 전" / "… · 시리즈명 3" / "… · 파일 없음" ([LibraryText.metaLine]). */
    val meta: String,
    val percent: String,
    val inCollection: Boolean,
) {
    val opened: Boolean get() = book.lastReadAt > 0

    /** Never opened: "새 책" stands in for the percent (전체), the progress line (grids) or ends the 요약 meta. */
    val isNew: Boolean = book.lastReadAt <= 0

    /** "새 책" / "완독" in place of a card's or cell's progress line (unopened books only), else null. */
    val tag: String? get() = if (opened) null else LibraryText.statusTag(false, book.haveRead)

    /** The 요약 meta line; built on its first bind (only that view shows it, a few rows at a time) and kept. */
    private var compact: String? = null

    fun compactMeta(): String = compact ?: LibraryText.compactMeta(
        book.author, book.format.label, book.sizeBytes, book.favorite, opened, book.haveRead, book.toRead,
        missing = book.trashed && book.missingAt > 0,
    ).also { compact = it }

    /** Second line of the old 간단히 row ("작가 · 34% · 3일 전"); kept for callers that want the reading time. */
    fun compactLine(): String = LibraryText.compactLine(book.author, opened, book.haveRead, percent) {
        LibraryText.ago(book.lastReadAt, System.currentTimeMillis())
    }

    companion object {
        fun of(book: Book, inCollection: Boolean, now: Long = System.currentTimeMillis()): BookRow = BookRow(
            book = book,
            title = book.title.ifBlank { book.fileName.substringBeforeLast('.') },
            meta = LibraryText.metaLine(
                book.format.label, book.sizeBytes, book.series, book.seriesIndex,
                LibraryText.lastRead(now, book.lastReadAt), missing = book.trashed && book.missingAt > 0,
            ),
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
    /** E-ink screen (`DeviceClass.cached == true`): card buttons get no pressed state (NOTES_SPEC §3.3). */
    val eink: Boolean
    /** The lists page instead of scrolling (요약 rows are then 80 dp at least, else 88 dp). */
    val paged: Boolean
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

/** Canonical cover bitmap size in px (every view binds this one size: one memory key and disk file per book). */
internal fun Context.canonCoverW(): Int = dp(LibraryGridMath.CANON_W_DP) - 2
internal fun Context.canonCoverH(): Int = dp(LibraryGridMath.CANON_H_DP) - 2

/**
 * Multi-select mark of one cover (NOTES_SPEC §10.2): a 20 dp check box on white at the cover's top-left and, when
 * checked, a 2 dp black frame around the cover. Hidden outside selection mode. No grey fill (it dithers on e-ink).
 */
internal class CheckMark(ctx: Context, private val frame: FrameLayout) {
    val view: ImageView = ImageView(ctx).apply {
        imageTintList = ColorStateList.valueOf(Ink.BLACK)
        scaleType = ImageView.ScaleType.FIT_CENTER
        setBackgroundColor(Ink.WHITE)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        visibility = View.GONE
    }
    private val ring = GradientDrawable().apply {
        setColor(Color.TRANSPARENT)
        setStroke(ctx.dp(2), Ink.BLACK)
    }
    private var shownRes = 0
    private var framed = false

    /** [checked] null = not selecting (hidden). */
    fun set(checked: Boolean?) {
        val f = checked == true
        if (f != framed) {
            framed = f
            frame.foreground = if (f) ring else null
        }
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

/**
 * Thin reading-progress line: grey track, black filled part, a dot at the position and at both ends. The dot and
 * fill scale with the view's height (≤ 3.5 dp dot, ≤ 2 dp fill), so the 4 dp and 6 dp grid lines keep whole dots.
 */
internal class ProgressLineView(context: Context) : View(context) {
    private val track = Paint().apply { color = Ink.GRAY; strokeWidth = context.dpF(1f).coerceAtLeast(1f) }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Ink.BLACK
        strokeWidth = context.dpF(2f)
        strokeCap = Paint.Cap.ROUND
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ink.BLACK; style = Paint.Style.FILL }
    private val maxPosR = context.dpF(3.5f)
    private val maxFill = context.dpF(2f)
    private var endR = context.dpF(2f)
    private var posR = maxPosR

    private var progress = 0f
    private var opened = false

    fun set(progress: Float, opened: Boolean) {
        val p = progress.coerceIn(0f, 1f)
        if (p == this.progress && opened == this.opened) return
        this.progress = p
        this.opened = opened
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        posR = minOf(maxPosR, h / 2f).coerceAtLeast(1f)
        endR = posR * 0.6f
        fill.strokeWidth = minOf(maxFill, posR)
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

/** A cover image (1 px border, the canonical bitmap scaled down with CENTER_CROP) in a frame for the selection mark. */
private fun Context.coverImage(): ImageView = ImageView(this).apply {
    background = coverBox()
    setPadding(1, 1, 1, 1)
    scaleType = ImageView.ScaleType.CENTER_CROP
    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
}

/** [cover] sized [w]×[h] with the selection mark's check box at its top-left. */
private fun Context.coverFrame(cover: ImageView, w: Int, h: Int): Pair<FrameLayout, CheckMark> {
    val f = FrameLayout(this)
    f.addView(cover, FrameLayout.LayoutParams(w, h))
    val check = CheckMark(this, f)
    val m = dp(3)
    f.addView(check.view, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.TOP or Gravity.START).apply { setMargins(m, m, m, m) })
    return f to check
}

/** A row separator: 1 px [color], inset [insetDp] on both sides (on the left only when [startOnly]). */
private fun Context.separator(color: Int, insetDp: Int, startOnly: Boolean = false): Pair<View, LinearLayout.LayoutParams> =
    View(this).apply { setBackgroundColor(color) } to LinearLayout.LayoutParams(MATCH_PARENT, 1).apply {
        leftMargin = dp(insetDp)
        if (!startOnly) rightMargin = dp(insetDp)
    }

/** A card button; on e-ink without a pressed state (the menu or the icon is the feedback: one update per tap). */
private fun Context.cardButton(actions: BookActions, res: Int, desc: String, onClick: (View) -> Unit): CardButton =
    CardButton(this, res, desc, onClick).apply {
        isFocusable = false
        if (actions.eink) background = null
    }

// ---------------------------------------------------------------------------------------------- list card

/**
 * 전체 card (ReadEra layout, black & white; NOTES_SPEC §10.2, UI_SPEC polish 14): cover 96×136 with its 1 px border,
 * then title (≤ 3 lines), author (GONE if blank), "TXT, 3.4MB · 3일 전", progress line + "34%" (or "새 책") and the five
 * action buttons. No card border: padding (10, 10, 6, 10) dp, a 1 px LINE_LIGHT separator inset 8 dp, pressed =
 * PRESSED fill. While selecting, the cover carries the mark and the buttons hide (invisible: the card keeps its height).
 */
internal class BookCardHolder(private val ctx: Context, private val actions: BookActions) : SelectableHolder {
    val coverW = ctx.dp(LibraryGridMath.CANON_W_DP)
    val coverH = ctx.dp(LibraryGridMath.CANON_H_DP)

    val root: LinearLayout
    private val cover: ImageView
    private val check: CheckMark
    private val title: TextView
    private val author: TextView
    private val meta: TextView
    private val progress: ProgressLineView
    private val percent: TextView
    private val fav: ImageButton
    private val toRead: ImageButton
    private val haveRead: ImageButton
    private val coll: ImageButton
    private val more: ImageButton
    private var row: BookRow? = null

    init {
        root = ctx.vertical { layoutParams = AbsListView.LayoutParams(MATCH_PARENT, WRAP_CONTENT) }
        val card = ctx.horizontal {
            gravity = Gravity.TOP
            background = pressableBackground()
            setPadding(ctx.dp(10), ctx.dp(10), ctx.dp(6), ctx.dp(10))
            setOnClickListener { row?.let { actions.tap(it, more) } }
            setOnLongClickListener { row?.let { actions.longPress(it, more) }; true }
        }
        cover = ctx.coverImage()
        val (frame, mark) = ctx.coverFrame(cover, coverW, coverH)
        check = mark
        card.addView(frame, LinearLayout.LayoutParams(coverW, coverH))

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
        // "34%", or "새 책" / "완독" in place of the empty percent of a book never opened.
        percent = ctx.label("", 13f, maxLines = 1).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            minWidth = ctx.dp(40)
            setPadding(ctx.dp(6), 0, 0, 0)
        }
        progRow.addView(percent, lp(WRAP_CONTENT, WRAP_CONTENT))
        col.addView(progRow, lp())

        val actionsRow = ctx.horizontal()
        fun btn(res: Int, desc: String, onClick: (BookRow) -> Unit): ImageButton =
            ctx.cardButton(actions, res, desc) { row?.let(onClick) }.apply {
                layoutParams = LinearLayout.LayoutParams(0, ctx.dp(48), 1f)
            }
        fav = btn(R.drawable.ic_star, Shelf.FAVORITES.label) { actions.toggleFlag(it, BookFlag.FAVORITE) }
        toRead = btn(R.drawable.ic_schedule, Shelf.TO_READ.label) { actions.toggleFlag(it, BookFlag.TO_READ) }
        haveRead = btn(R.drawable.ic_done_all, Shelf.HAVE_READ.label) { actions.toggleFlag(it, BookFlag.HAVE_READ) }
        coll = btn(R.drawable.ic_library_books, "컬렉션") { actions.showCollections(it.book) }
        more = btn(R.drawable.ic_more_vert, "책 메뉴") { r -> actions.showBookMenu(r, more) }
        listOf(fav, toRead, haveRead, coll, more).forEach { actionsRow.addView(it) }
        col.addView(actionsRow, lp())

        card.addView(col, lp(0, MATCH_PARENT, 1f))
        root.addView(card, lp())
        val (line, lineLp) = ctx.separator(Ink.LINE_LIGHT, 8)
        root.addView(line, lineLp)
        // The text column must be at least as tall as the cover so the actions sit at the card bottom.
        col.minimumHeight = coverH
        root.tag = this
    }

    fun bind(r: BookRow) {
        row = r
        val b = r.book
        title.text = r.title
        author.text = b.author
        author.show(if (b.author.isBlank()) View.GONE else View.VISIBLE)
        meta.text = r.meta
        progress.set(b.progress, r.opened)
        percent.text = r.tag ?: r.percent
        setIcon(fav, if (b.favorite) R.drawable.ic_star_fill else R.drawable.ic_star)
        setIcon(toRead, if (b.toRead) R.drawable.ic_schedule_fill else R.drawable.ic_schedule)
        setIcon(haveRead, if (b.haveRead) R.drawable.ic_done_all_fill else R.drawable.ic_done_all)
        setIcon(coll, if (r.inCollection) R.drawable.ic_library_books_fill else R.drawable.ic_library_books)
        showSelection()
        CoverLoader.bind(ctx, cover, b, ctx.canonCoverW(), ctx.canonCoverH())
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
 * 요약 row (NOTES_SPEC §10.2, library.md §2.4): a 48×68 cover (the canonical bitmap at 0.5×), the title (16 sp bold,
 * ≤ 2 lines), "★ 작가 · TXT 3.4MB · 다 읽음" (13 sp grey), a 10 dp progress line + "34%", and ⋮ (48 dp × the full row
 * height). At least 80 dp tall when paged, 88 dp when scrolling. While selecting, the cover carries the mark and ⋮
 * hides.
 */
internal class CompactRowHolder(private val ctx: Context, private val actions: BookActions) : SelectableHolder {
    val root: LinearLayout
    private val line: LinearLayout
    private val cover: ImageView
    private val check: CheckMark
    private val title: TextView
    private val sub: TextView
    private val progress: ProgressLineView
    private val percent: TextView
    private val more: ImageButton
    private var row: BookRow? = null
    private var minH = 0

    init {
        root = ctx.vertical { layoutParams = AbsListView.LayoutParams(MATCH_PARENT, WRAP_CONTENT) }
        line = ctx.horizontal {
            gravity = Gravity.CENTER_VERTICAL
            // No end padding: the list's 12 dp paddingEnd keeps ⋮ clear of the fast scroller's strip.
            setPadding(ctx.dp(12), 0, 0, 0)
            background = pressableBackground()
            setOnClickListener { row?.let { actions.tap(it, more) } }
            setOnLongClickListener { row?.let { actions.longPress(it, more) }; true }
        }
        cover = ctx.coverImage()
        val w = ctx.dp(48)
        val h = ctx.dp(68)
        val (frame, mark) = ctx.coverFrame(cover, w, h)
        check = mark
        line.addView(frame, LinearLayout.LayoutParams(w, h).apply { topMargin = ctx.dp(10); bottomMargin = ctx.dp(10) })
        val texts = ctx.vertical { setPadding(ctx.dp(12), ctx.dp(10), 0, ctx.dp(10)) }
        title = ctx.label("", 16f, bold = true, maxLines = 2)
        sub = ctx.label("", 13f, color = Ink.GRAY, maxLines = 1).apply { setPadding(0, ctx.dp(3), 0, 0) }
        texts.addView(title, lp())
        texts.addView(sub, lp())
        val progRow = ctx.horizontal { setPadding(0, ctx.dp(4), 0, 0) }
        progress = ProgressLineView(ctx)
        progRow.addView(progress, lp(0, ctx.dp(10), 1f))
        percent = ctx.label("", 12f, maxLines = 1).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            minWidth = ctx.dp(36)
        }
        progRow.addView(percent, lp(WRAP_CONTENT, WRAP_CONTENT))
        texts.addView(progRow, lp())
        line.addView(texts, lp(0, WRAP_CONTENT, 1f))
        more = ctx.cardButton(actions, R.drawable.ic_more_vert, "책 메뉴") { v -> row?.let { actions.showBookMenu(it, v) } }
        line.addView(more, LinearLayout.LayoutParams(ctx.dp(48), MATCH_PARENT))
        root.addView(line, lp())
        val (sep, sepLp) = ctx.separator(Ink.LINE, 12, startOnly = true)
        root.addView(sep, sepLp)
        root.tag = this
    }

    fun bind(r: BookRow) {
        row = r
        val h = ctx.dp(if (actions.paged) 80 else 88)
        if (h != minH) {
            minH = h
            line.minimumHeight = h
        }
        title.text = r.title
        sub.text = r.compactMeta()
        progress.set(r.book.progress, r.opened)
        percent.text = r.percent
        showSelection()
        CoverLoader.bind(ctx, cover, r.book, ctx.canonCoverW(), ctx.canonCoverH())
    }

    override fun showSelection() {
        val state = actions.selection.stateOf(row)
        check.set(state)
        more.show(if (state != null) View.INVISIBLE else View.VISIBLE)
    }
}

// ---------------------------------------------------------------------------------------------- grid cell

/**
 * A cell of 썸네일 ([LibraryListMode.GRID]: cover 96×136, 6 dp line, 12 sp title on 2 lines) or 그리드
 * ([LibraryListMode.COVERS]: cover 76×108, 4 dp line, 11 sp title on 1 line) — [LibraryGridMath.cell]. The progress
 * line, or "새 책" (10 sp grey), sits in a fixed slot and the title has a fixed line count, so every cell has the
 * exact height the adapter gives it. Tap opens, long press starts multi-select.
 */
internal class GridCellHolder(private val ctx: Context, private val actions: BookActions, val mode: LibraryListMode) : SelectableHolder {
    private val spec = LibraryGridMath.cell(mode)
    val root: LinearLayout
    private val cover: ImageView
    private val check: CheckMark
    private val title: TextView
    private val progress: ProgressLineView
    private val tag: TextView
    private var row: BookRow? = null
    private var cellH = -1

    init {
        root = ctx.vertical {
            layoutParams = AbsListView.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, ctx.dp(2), 0, 0)
            background = pressableBackground()
            setOnClickListener { v -> row?.let { actions.tap(it, v) } }
            setOnLongClickListener { v -> row?.let { actions.longPress(it, v) }; true }
        }
        val cw = ctx.dp(spec.coverWDp)
        val ch = ctx.dp(spec.coverHDp)
        cover = ctx.coverImage()
        val (frame, mark) = ctx.coverFrame(cover, cw, ch)
        check = mark
        root.addView(frame, LinearLayout.LayoutParams(cw, ch))
        // The progress line and the "새 책" tag share one fixed slot: the cells keep one height.
        val slot = FrameLayout(ctx)
        progress = ProgressLineView(ctx)
        slot.addView(progress, FrameLayout.LayoutParams(MATCH_PARENT, ctx.dp(spec.slotDp), Gravity.CENTER_VERTICAL))
        tag = ctx.label("", 10f, color = Ink.GRAY, maxLines = 1).apply {
            gravity = Gravity.CENTER
            includeFontPadding = false
            visibility = View.GONE
        }
        slot.addView(tag, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.addView(slot, LinearLayout.LayoutParams(cw, ctx.dp(if (spec.titleLines == 1) 10 else 12)).apply {
            topMargin = ctx.dp(2)
        })
        title = ctx.label("", spec.titleSp, bold = true).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            includeFontPadding = false
            setLines(spec.titleLines)
            ellipsize = TextUtils.TruncateAt.END
        }
        root.addView(title, LinearLayout.LayoutParams(cw, WRAP_CONTENT).apply { topMargin = ctx.dp(2) })
        root.tag = this
    }

    /** The cell's exact height in px ([LibraryGridMath.Cell.heightDp], or the page-fitted one). */
    fun setHeight(h: Int) {
        if (h == cellH) return
        cellH = h
        root.layoutParams = AbsListView.LayoutParams(MATCH_PARENT, h)
    }

    fun bind(r: BookRow, height: Int) {
        row = r
        setHeight(height)
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
        CoverLoader.bind(ctx, cover, r.book, ctx.canonCoverW(), ctx.canonCoverH())
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

/** 요약 rows. */
internal class CompactListAdapter(private val ctx: Context, private val actions: BookActions) : BookAdapter() {
    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val holder = (convertView?.tag as? CompactRowHolder) ?: CompactRowHolder(ctx, actions)
        holder.bind(rows[position])
        return holder.root
    }
}

/**
 * 썸네일 and 그리드 in the one GridView: [mode] picks the cell recipe (a recycled holder of the other mode is not
 * reused), [cellHeight] the exact cell height (px).
 */
internal class BookGridAdapter(private val ctx: Context, private val actions: BookActions) : BookAdapter() {
    var mode: LibraryListMode = LibraryListMode.GRID
    var cellHeight: Int = 0

    override fun getViewTypeCount(): Int = 2
    override fun getItemViewType(position: Int): Int = if (mode == LibraryListMode.COVERS) 1 else 0

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val holder = (convertView?.tag as? GridCellHolder)?.takeIf { it.mode == mode } ?: GridCellHolder(ctx, actions, mode)
        holder.bind(rows[position], if (cellHeight > 0) cellHeight else ctx.dp(LibraryGridMath.cell(mode).heightDp))
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

