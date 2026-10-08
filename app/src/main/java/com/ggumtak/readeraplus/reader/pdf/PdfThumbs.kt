package com.ggumtak.readeraplus.reader.pdf

import android.app.Activity
import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.ggumtak.readeraplus.R
import com.ggumtak.readeraplus.ui.kit.Ink
import com.ggumtak.readeraplus.ui.kit.dp
import com.ggumtak.readeraplus.ui.kit.frameLp
import com.ggumtak.readeraplus.ui.kit.fullScreenDialog
import com.ggumtak.readeraplus.ui.kit.label
import com.ggumtak.readeraplus.ui.kit.lp
import com.ggumtak.readeraplus.ui.kit.toolbar
import com.ggumtak.readeraplus.ui.kit.vertical
import java.util.concurrent.ConcurrentHashMap

/**
 * Full-screen grid of page thumbnails ("쪽 목록") with a "책갈피만" filter. Tapping a cell calls [onPick] and closes.
 * Thumbnails are requested lazily for visible cells only.
 */
internal class PdfThumbs(
    private val activity: Activity,
    private val title: String,
    private val pageCount: Int,
    private val current: Int,                 // 0-based page on screen: highlighted and scrolled into view on open
    private val bookmarks: () -> IntArray,    // sorted 0-based bookmarked pages (read on the main thread)
    /**
     * Renders page [page] to fit inside w×h px. [wanted] is polled ON THE RENDER THREAD right before rendering:
     * return false to skip (the cell scrolled away / the dialog closed). [done] is called ON THE MAIN THREAD with the
     * bitmap, or null (skipped / failed).
     */
    private val requestThumb: (page: Int, w: Int, h: Int, wanted: () -> Boolean, done: (Bitmap?) -> Unit) -> Unit,
    private val onPick: (page: Int) -> Unit,
) {
    private class Holder(
        val cell: LinearLayout,
        val frame: FrameLayout,
        val image: ImageView,
        val mark: ImageView,
        val number: TextView,
    ) {
        /** Page this cell is bound to (-1: none). Main thread only. */
        var page = -1
    }

    /** One in-flight thumbnail request; [skipped] is set on the render thread when the cell was not wanted anymore. */
    private class Req {
        @Volatile var skipped = false
    }

    private val cache = object : LruCache<Int, Bitmap>(CACHE_KB) {
        override fun sizeOf(key: Int, value: Bitmap): Int = (value.byteCount / 1024).coerceAtLeast(1)
    }

    /** Pages whose cell is currently bound (visible); read from the render thread through `wanted`. */
    private val bound = ConcurrentHashMap<Int, Boolean>()

    /** Pages with a request in flight. Main thread only. */
    private val pending = HashSet<Int>()

    @Volatile private var closed = false

    private var onlyMarks = false
    private var marks = IntArray(0)
    private var shown = IntArray(0) // page numbers listed in the filtered view; empty = all pages in order

    private lateinit var grid: GridView
    private lateinit var empty: TextView
    private lateinit var tabAll: TextView
    private lateinit var tabMarks: TextView
    private lateinit var adapter: ThumbAdapter

    private var cellW = 0
    private var cellH = 0
    private var frameW = 0
    private var frameH = 0

    fun show() {
        val ctx = activity
        marks = bookmarks()
        val cols = (ctx.resources.displayMetrics.widthPixels / ctx.dp(110)).coerceAtLeast(3)
        cellW = ctx.resources.displayMetrics.widthPixels / cols
        val pad = ctx.dp(6)
        frameW = cellW - 2 * pad
        frameH = (frameW * 1.4f).toInt()
        cellH = pad + frameH + ctx.dp(4) + ctx.dp(20) + pad

        lateinit var dialog: Dialog
        val root = ctx.vertical { setBackgroundColor(Ink.WHITE) }
        root.addView(ctx.toolbar(title, R.drawable.ic_close, "닫기", onNav = { dialog.dismiss() }), lp())

        tabAll = tab("모든 쪽") { setFilter(false) }
        tabMarks = tab("책갈피") { setFilter(true) }
        val tabs = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(ctx.dp(12), ctx.dp(8), ctx.dp(12), ctx.dp(8))
            addView(tabAll, lp(0, ctx.dp(40), 1f))
            addView(tabMarks, lp(0, ctx.dp(40), 1f))
        }
        root.addView(tabs, lp())

        adapter = ThumbAdapter()
        grid = GridView(ctx).apply {
            numColumns = cols
            columnWidth = cellW
            horizontalSpacing = 0
            verticalSpacing = 0
            stretchMode = GridView.NO_STRETCH
            selector = ColorDrawable(Color.TRANSPARENT)
            cacheColorHint = Color.TRANSPARENT
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalFadingEdgeEnabled = false
            isScrollbarFadingEnabled = false
            isFastScrollEnabled = false
            setRecyclerListener { v ->
                val h = v.tag as? Holder ?: return@setRecyclerListener
                if (h.page >= 0) bound.remove(h.page)
                h.page = -1
            }
            setOnItemClickListener { _, _, position, _ ->
                val page = pageAt(position)
                dialog.dismiss()
                onPick(page)
            }
        }
        empty = ctx.label("책갈피한 쪽이 없습니다. 보는 쪽에서 책갈피 버튼을 누르세요.", 16f, color = Ink.GRAY).apply {
            gravity = Gravity.CENTER
            setPadding(ctx.dp(32), ctx.dp(32), ctx.dp(32), ctx.dp(32))
            visibility = View.GONE
        }
        val body = FrameLayout(ctx)
        body.addView(grid, body.frameLp(MATCH_PARENT, MATCH_PARENT))
        body.addView(empty, body.frameLp(MATCH_PARENT, MATCH_PARENT))
        root.addView(body, lp(MATCH_PARENT, 0, 1f))

        dialog = ctx.fullScreenDialog(root)
        dialog.setOnDismissListener {
            closed = true
            bound.clear()
            pending.clear()
            cache.evictAll()
        }
        updateTabs()
        grid.adapter = adapter
        grid.setSelection(current.coerceIn(0, (pageCount - 1).coerceAtLeast(0)))
        dialog.show()
    }

    private fun tab(text: String, onClick: () -> Unit): TextView = activity.label(text, 16f, bold = true).apply {
        gravity = Gravity.CENTER
        setOnClickListener { onClick() }
    }

    private fun updateTabs() {
        styleTab(tabAll, !onlyMarks)
        styleTab(tabMarks, onlyMarks)
    }

    private fun styleTab(t: TextView, selected: Boolean) {
        t.setTextColor(if (selected) Ink.WHITE else Ink.BLACK)
        t.background = GradientDrawable().apply {
            setColor(if (selected) Ink.BLACK else Ink.WHITE)
            setStroke(activity.dp(1).coerceAtLeast(1), Ink.LINE)
        }
    }

    private fun setFilter(only: Boolean) {
        if (only == onlyMarks) return
        onlyMarks = only
        marks = bookmarks()
        shown = if (only) marks else IntArray(0)
        updateTabs()
        bound.clear()
        adapter.notifyDataSetChanged()
        val none = only && marks.isEmpty()
        empty.visibility = if (none) View.VISIBLE else View.GONE
        grid.visibility = if (none) View.GONE else View.VISIBLE
        val sel = if (only) marks.indexOf(current).coerceAtLeast(0) else current.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
        grid.setSelection(sel)
    }

    private fun pageAt(position: Int): Int = if (onlyMarks) shown[position] else position

    private fun isMarked(page: Int): Boolean = java.util.Arrays.binarySearch(marks, page) >= 0

    private fun newHolder(): Holder {
        val ctx = activity
        val pad = ctx.dp(6)
        val border = ctx.dp(2)
        val image = ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        val mark = ImageView(ctx).apply {
            setImageResource(R.drawable.ic_bookmark_fill)
            imageTintList = ColorStateList.valueOf(Ink.BLACK)
            setBackgroundColor(Ink.WHITE)
            visibility = View.GONE
        }
        val frame = FrameLayout(ctx).apply {
            setPadding(border, border, border, border)
            addView(image, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(mark, FrameLayout.LayoutParams(ctx.dp(22), ctx.dp(22), Gravity.TOP or Gravity.END))
        }
        val number = ctx.label("", 14f, maxLines = 1).apply {
            gravity = Gravity.CENTER
        }
        val cell = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            layoutParams = AbsListView.LayoutParams(cellW, cellH)
            addView(frame, LinearLayout.LayoutParams(frameW, frameH))
            addView(number, LinearLayout.LayoutParams(frameW, ctx.dp(20)).apply { topMargin = ctx.dp(4) })
        }
        return Holder(cell, frame, image, mark, number).also { cell.tag = it }
    }

    private fun bind(h: Holder, page: Int) {
        if (h.page != page) {
            if (h.page >= 0) bound.remove(h.page)
            h.page = page
        }
        bound[page] = true
        val isCurrent = page == current
        h.frame.background = GradientDrawable().apply {
            setColor(Ink.WHITE)
            setStroke(activity.dp(if (isCurrent) 2 else 1).coerceAtLeast(1), if (isCurrent) Ink.BLACK else Ink.LINE_LIGHT)
        }
        h.number.text = (page + 1).toString()
        h.number.typeface = if (isCurrent) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
        h.mark.visibility = if (isMarked(page)) View.VISIBLE else View.GONE
        val cached = cache.get(page)
        if (cached != null) {
            h.image.setImageBitmap(cached)
        } else {
            h.image.setImageDrawable(null)
            request(h, page)
        }
    }

    private fun request(h: Holder, page: Int) {
        if (closed || !pending.add(page)) return
        val req = Req()
        val w = (frameW - 2 * activity.dp(2)).coerceAtLeast(1)
        val ht = (frameH - 2 * activity.dp(2)).coerceAtLeast(1)
        requestThumb(
            page, w, ht,
            {
                val ok = !closed && bound.containsKey(page)
                if (!ok) req.skipped = true
                ok
            },
            { bmp -> onThumb(h, page, req, bmp) },
        )
    }

    private fun onThumb(h: Holder, page: Int, req: Req, bmp: Bitmap?) {
        pending.remove(page)
        if (closed) return
        if (bmp != null) {
            cache.put(page, bmp)
            if (h.page == page) h.image.setImageBitmap(bmp)
        } else if (req.skipped && h.page == page && bound.containsKey(page)) {
            // The cell scrolled away and came back before this skipped request was reported: ask again.
            request(h, page)
        }
    }

    private inner class ThumbAdapter : BaseAdapter() {
        override fun getCount(): Int = if (onlyMarks) shown.size else pageCount
        override fun getItem(position: Int): Any = pageAt(position)
        override fun getItemId(position: Int): Long = pageAt(position).toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val h = (convertView?.tag as? Holder) ?: newHolder()
            bind(h, pageAt(position))
            return h.cell
        }
    }

    private companion object {
        const val CACHE_KB = 16 * 1024
    }
}
