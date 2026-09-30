package com.ggumtak.readeraplus.ui.kit

import android.app.Dialog
import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.AbsListView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import kotlin.math.abs

/*
 * Page-at-a-time lists for e-ink (T1-1). A fling paints every scroll frame (a smear of partial e-ink updates), so a
 * paged list never scrolls: a drag or fling becomes exactly one page jump, and the pager bar / page keys move it a
 * page at a time. Each move is one layout and one draw, the "3 / 27" indicator included.
 */

/** Pure page maths of [InkPager] (unit-tested). "Rows" are adapter positions. */
object PagerMath {
    /** Rows one page moves: the visible rows minus one (the last row, usually cut, becomes the next page's first). */
    fun step(visibleRows: Int): Int = (visibleRows - 1).coerceAtLeast(1)

    /** Pages of a [count]-row list that shows [fully] whole rows and moves [step] rows a page (at least 1). */
    fun total(count: Int, fully: Int, step: Int): Int {
        val f = fully.coerceAtLeast(1)
        if (count <= f) return 1
        val s = step.coerceAtLeast(1)
        return 1 + (count - f + s - 1) / s
    }

    /**
     * 1-based page shown with row [first] on top: 1 only at the top, the last page only at the end ([atEnd]: the
     * last row fully shown), a page in between otherwise (rows differ in height, so a page is an estimate).
     */
    fun page(first: Int, count: Int, fully: Int, step: Int, atEnd: Boolean): Int {
        val t = total(count, fully, step)
        if (atEnd) return t
        if (first <= 0) return 1
        val s = step.coerceAtLeast(1)
        val p = 1 + (first + s - 1) / s
        return if (t >= 3) p.coerceIn(2, t - 1) else p.coerceIn(1, t)
    }

    /** First row one page towards [dir] (+1 down, -1 up) from [first], clamped to a [count]-row list. */
    fun target(first: Int, dir: Int, step: Int, count: Int): Int =
        (first + dir.coerceIn(-1, 1) * step.coerceAtLeast(1)).coerceIn(0, (count - 1).coerceAtLeast(0))

    /** First row that shows row [index] as row [rowFromTop] (0 = the top row; [지금] uses 3: the 4th row). */
    fun firstFor(index: Int, rowFromTop: Int): Int = (index - rowFromTop.coerceAtLeast(0)).coerceAtLeast(0)

    /** "3 / 27" (empty for an empty list). */
    fun label(page: Int, total: Int): String = if (total <= 0) "" else "$page / $total"
}

/**
 * The pager bar under a paged list: [◀ 이전]  "3 / 27"  [다음 ▶], 44dp tall with a 1px top line. The buttons have no
 * pressed state (the page change is the feedback: one e-ink update per page) and turn gray at the ends. Created by
 * the caller, placed below the list (it sets its own 44dp LinearLayout params) and handed to [inkPaging].
 */
class InkPagerBar(context: Context) : LinearLayout(context) {
    internal val prev: TextView = button("◀ 이전")
    internal val next: TextView = button("다음 ▶")
    /** Fixed size (weight width, bar height): a new page number only redraws it, never re-lays out the bar. */
    internal val label: TextView = TextView(context).apply {
        textSize = 16f
        setTextColor(Ink.BLACK)
        gravity = Gravity.CENTER
        includeFontPadding = false
        maxLines = 1
    }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = LayerDrawable(arrayOf(ColorDrawable(Ink.WHITE), ColorDrawable(Ink.LINE))).apply {
            setLayerGravity(1, Gravity.TOP or Gravity.FILL_HORIZONTAL)
            setLayerHeight(1, 1)
        }
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, context.dp(HEIGHT_DP))
        addView(prev, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        addView(label, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        addView(next, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
    }

    private fun button(text: String): TextView = TextView(context).apply {
        this.text = text
        textSize = 16f
        setTextColor(Ink.BLACK)
        gravity = Gravity.CENTER
        includeFontPadding = false
        minWidth = context.dp(88)
        setPadding(context.dp(16), 0, context.dp(16), 0)
        contentDescription = if (text.startsWith("◀")) "이전 페이지" else "다음 페이지"
    }

    internal fun show(page: Int, total: Int, canPrev: Boolean, canNext: Boolean) {
        val t = PagerMath.label(page, total)
        if (label.text.toString() != t) label.text = t
        enable(prev, canPrev)
        enable(next, canNext)
    }

    /** No-ops (no redraw) when nothing changes: View.setEnabled and setTextColor both compare first. */
    private fun enable(v: TextView, on: Boolean) {
        v.isEnabled = on
        v.setTextColor(if (on) Ink.BLACK else Ink.DISABLED)
    }

    companion object {
        const val HEIGHT_DP = 44
    }
}

/**
 * A list paged a screen at a time ([inkPaging]). Main thread only.
 *
 * - A page is the visible rows − 1, moved with `setSelection(first ± page)`.
 * - A vertical drag or fling beyond the touch slop is consumed: the list never scrolls, and on `ACTION_UP` it becomes
 *   exactly one page jump (finger up = next page). Taps and long presses still reach the rows. Rows must not hold
 *   clickable children (the list would then see the drag first).
 * - The indicator is updated right after each `setSelection` (which never raises `onScrollStateChanged(IDLE)`) and
 *   again when the list lays out (data changes, a caller's own `setSelection`), in the same frame.
 *
 * The pager owns the list's OnTouchListener and OnScrollListener: use [onMoved] to follow the visible rows.
 */
class InkPager internal constructor(val list: ListView, private val bar: InkPagerBar) {
    /** Runs after every layout of the list (a page jump, a data change): the visible rows may be different. */
    var onMoved: (() -> Unit)? = null

    init {
        bar.prev.setOnClickListener { page(-1) }
        bar.next.setOnClickListener { page(1) }
        list.setOnTouchListener(DragToPage())
        list.setOnScrollListener(object : AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view: AbsListView, scrollState: Int) {}
            override fun onScroll(view: AbsListView, firstVisible: Int, visibleCount: Int, totalCount: Int) {
                update()
                onMoved?.invoke()
            }
        })
        update()
    }

    private val count: Int get() = list.adapter?.count ?: 0

    /** Rows shown completely (at least 1 while anything is shown). */
    private fun fullyVisible(): Int {
        val top = list.paddingTop
        val bottom = list.height - list.paddingBottom
        var n = 0
        for (i in 0 until list.childCount) {
            val c = list.getChildAt(i)
            if (c.top >= top && c.bottom <= bottom) n++
        }
        return n.coerceAtLeast(1)
    }

    private fun atStart(): Boolean =
        list.firstVisiblePosition <= 0 && (list.childCount == 0 || list.getChildAt(0).top >= list.paddingTop)

    private fun atEnd(): Boolean {
        val n = list.childCount
        if (n == 0) return true
        return list.firstVisiblePosition + n >= count && list.getChildAt(n - 1).bottom <= list.height - list.paddingBottom
    }

    /** One page towards [dir] (+1 next, -1 previous). False when already at that end (or nothing is laid out yet). */
    fun page(dir: Int): Boolean {
        val n = count
        if (n == 0 || list.childCount == 0 || dir == 0) return false
        if (if (dir > 0) atEnd() else atStart()) return false
        val first = list.firstVisiblePosition
        val step = PagerMath.step(list.childCount)
        val target = PagerMath.target(first, dir, step, n)
        list.setSelection(target)
        // The new page is laid out before the next draw: show its number with it (predicted; onScroll corrects it
        // during that layout, still before the draw).
        val fully = fullyVisible()
        val end = target + fully >= n
        bar.show(PagerMath.page(target, n, fully, step, end), PagerMath.total(n, fully, step), target > 0, !end)
        return true
    }

    /** Shows row [index] as row [rowFromTop] (0 = top; the TOC's [지금] uses 3), e.g. after a filter or a jump. */
    fun showRow(index: Int, rowFromTop: Int = 0) {
        if (count == 0) return
        list.setSelection(PagerMath.firstFor(index.coerceIn(0, count - 1), rowFromTop))
        update()
    }

    /** Re-reads the list's position into the indicator (the pager does this itself after every layout). */
    fun update() {
        val n = count
        if (n == 0) {
            bar.show(0, 0, canPrev = false, canNext = false)
            return
        }
        if (list.childCount == 0) return // not laid out yet: the first layout calls back
        val step = PagerMath.step(list.childCount)
        val fully = fullyVisible()
        val end = atEnd()
        bar.show(PagerMath.page(list.firstVisiblePosition, n, fully, step, end), PagerMath.total(n, fully, step), !atStart(), !end)
    }

    /** Drags / flings beyond the touch slop become one page jump; everything else goes to the list. */
    private inner class DragToPage : View.OnTouchListener {
        private val slop = ViewConfiguration.get(list.context).scaledTouchSlop
        private var downY = 0f
        private var dragging = false

        override fun onTouch(v: View, ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downY = ev.y
                    dragging = false
                    return false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (dragging) return true
                    // Checked before the list sees the move: it would start scrolling past the same slop.
                    if (abs(ev.y - downY) > slop) {
                        dragging = true
                        list.parent?.requestDisallowInterceptTouchEvent(true)
                        // The list saw the down: clear its pressed row and pending long press.
                        val cancel = MotionEvent.obtain(ev)
                        cancel.action = MotionEvent.ACTION_CANCEL
                        list.onTouchEvent(cancel)
                        cancel.recycle()
                        return true
                    }
                    return false
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragging) return false
                    dragging = false
                    page(if (ev.y < downY) 1 else -1)
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    val was = dragging
                    dragging = false
                    return was
                }
            }
            return dragging
        }
    }
}

/**
 * Pages this list a screen at a time with [bar] (see [InkPager]). Call once, after the adapter is set; the pager takes
 * over the list's touch and scroll listeners.
 */
fun ListView.inkPaging(bar: InkPagerBar): InkPager = InkPager(this, bar)

/**
 * Hardware page keys page [pager]'s list while this dialog has the focus (the dialog window gets the keys, not the
 * activity below). [direction] maps a key code to +1 (next page), -1 or 0 (not a page key: left alone). A page key's
 * DOWN and UP are both consumed, so the system volume panel never shows; a held key pages once (fresh presses only).
 * [skip] leaves a key to the dialog (e.g. while typing in a search field).
 */
fun Dialog.inkPagerKeys(pager: () -> InkPager?, direction: (Int) -> Int, skip: (KeyEvent) -> Boolean = { false }) {
    setOnKeyListener { _, keyCode, ev ->
        val dir = direction(keyCode)
        if (dir == 0 || skip(ev)) return@setOnKeyListener false
        if (ev.action == KeyEvent.ACTION_DOWN && ev.repeatCount == 0) pager()?.page(dir)
        true
    }
}
