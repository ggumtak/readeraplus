package com.ggumtak.readeraplus.reader.extras

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.LinearLayout
import com.ggumtak.readeraplus.reader.ReaderHost
import com.ggumtak.readeraplus.reader.ThumbGridMath
import com.ggumtak.readeraplus.render.DeviceClass
import com.ggumtak.readeraplus.render.RibbonMath
import com.ggumtak.readeraplus.ui.kit.Ink
import com.ggumtak.readeraplus.ui.kit.InkNumPad
import com.ggumtak.readeraplus.ui.kit.InkPagerBar
import com.ggumtak.readeraplus.ui.kit.NumPadState
import com.ggumtak.readeraplus.ui.kit.PageDrag
import com.ggumtak.readeraplus.ui.kit.PageTarget
import com.ggumtak.readeraplus.ui.kit.TapSlop
import com.ggumtak.readeraplus.ui.kit.dp
import com.ggumtak.readeraplus.ui.kit.dpF
import com.ggumtak.readeraplus.ui.kit.lp
import com.ggumtak.readeraplus.ui.kit.sp
import com.ggumtak.readeraplus.ui.kit.vertical
import kotlin.math.roundToInt

/**
 * The contents dialog's 4th tab, 미리보기 (was 썸네일; NOTES_SPEC §12, library.md §3.1/3.4): a grid page of page
 * thumbnails ([ThumbGridView]) over an [InkPagerBar] ("1 / 272 · 11쪽"; a label tap opens the number pad, page N → its
 * grid page). Thumbnails come from the host's [PageThumbsHost]; a tap jumps with [PageJumpHost.goToPage] and closes the
 * dialog through [close]. Swipes (either axis), ◀ ▶ and the page keys ([page], a [PageTarget]) move a grid page.
 *
 * One e-ink update per grid page: the old grid stays until the host reports the new batch (complete, or partial after
 * 700 ms), then the cells, labels and the bar change in one frame. [prepare] does the same for the tab switch: the
 * dialog swaps its body (and the tab underline) in [prepare]'s callback. Phones (`progressive`) fill as cells finish.
 * Counting progress never redraws the grid: totals and labels refresh with the next batch. Main thread only.
 */
internal class ThumbsTab(private val host: ReaderHost, private val close: () -> Unit) : PageTarget {
    private val ctx: Context = host.activity
    private val thumbs = host as? PageThumbsHost
    /** E-ink waits for complete batches; phones fill progressively (≥ 100 ms apart). */
    private val progressive = DeviceClass.cached(ctx) != true
    private val main = Handler(Looper.getMainLooper())

    private val grid = ThumbGridView(ctx)
    private val bar = InkPagerBar(ctx)

    /** The tab's body: the grid over its pager bar. */
    val view: LinearLayout = ctx.vertical {
        setBackgroundColor(Ink.WHITE)
        addView(grid, lp(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        addView(bar)
    }

    private var geometry: ThumbGridMath.Grid? = null
    private var areaW = 0
    private var areaH = 0
    private var perPage = 1
    private var thumbWPx = 0
    private var thumbHPx = 0
    private var total = 0
    private var current = 0
    /** Grid page on screen (-1 before the first batch). */
    private var shown = -1
    /** Grid page requested last (the one the next batch belongs to). */
    private var wanted = -1
    private var onReady: (() -> Unit)? = null
    private var started = false
    private val readyFallback = Runnable { fireReady() }

    init {
        grid.contentDescription = "페이지 미리보기"
        grid.onTap = { cell -> open(cell) }
        grid.onPage = { dir -> page(dir) }
        grid.onSize = { w, h -> resized(w, h) }
        bar.prev.setOnClickListener { page(-1) }
        bar.next.setOnClickListener { page(1) }
        bar.label.setOnClickListener { askPage() }
        bar.show(0, 0, canPrev = false, canNext = false)
    }

    /**
     * Requests the grid page holding the current page for a dialog body of [bodyWidthPx] × [bodyHeightPx] (0 when the
     * body is not laid out yet: estimated from the screen) and calls [ready] once its first batch is shown (complete,
     * partial after 700 ms, or at once with placeholders on phones), so the tab switch and the thumbnails are one
     * update. Calling it again (the tab re-selected) re-requests the grid page on screen.
     */
    fun prepare(bodyWidthPx: Int, bodyHeightPx: Int, ready: () -> Unit) {
        onReady = ready
        started = true
        val t = thumbs
        if (t == null || t.thumbTotal() <= 0) {
            fireReady()
            return
        }
        val dm = ctx.resources.displayMetrics
        val w = if (bodyWidthPx > 0) bodyWidthPx else dm.widthPixels
        val h = (if (bodyHeightPx > 0) bodyHeightPx else dm.heightPixels - ctx.dp(CHROME_DP)) - ctx.dp(InkPagerBar.HEIGHT_DP)
        measure(w, h)
        total = t.thumbTotal()
        current = t.thumbCurrent()
        val gp = if (shown >= 0) shown else ThumbGridMath.gridPageOf(current, perPage)
        if (shown < 0) showPlaceholders(ThumbGridMath.clampGridPage(gp, total, perPage))
        main.removeCallbacks(readyFallback)
        main.postDelayed(readyFallback, READY_FALLBACK_MS)
        request(gp)
    }

    /** The tab is left or the dialog dismissed: the host stops rendering (and gives the reader its neighbours back). */
    fun stop() {
        started = false
        main.removeCallbacks(readyFallback)
        onReady = null
        wanted = -1
        thumbs?.cancelThumbs()
    }

    override fun page(dir: Int): Boolean {
        if (dir == 0 || geometry == null || shown < 0) return false
        val base = if (wanted >= 0) wanted else shown
        val target = base + if (dir > 0) 1 else -1
        if (target < 0 || target >= ThumbGridMath.gridPages(total, perPage)) return false
        request(target)
        return true
    }

    private fun request(gridPage: Int) {
        val t = thumbs ?: return
        if (!started || geometry == null || thumbWPx <= 0 || thumbHPx <= 0) return
        val tot = t.thumbTotal().takeIf { it > 0 } ?: total
        val gp = ThumbGridMath.clampGridPage(gridPage, tot, perPage)
        val first = ThumbGridMath.firstOf(gp, perPage)
        val count = ThumbGridMath.countOn(gp, tot, perPage)
        if (count <= 0) return
        wanted = gp
        t.requestThumbs(first, count, thumbWPx, thumbHPx, progressive) { batch ->
            if (started && wanted == gp) show(gp, batch)
        }
    }

    private fun show(gridPage: Int, batch: ThumbBatch) {
        total = batch.total.coerceAtLeast(1)
        current = batch.current
        shown = gridPage
        if (batch.complete) wanted = -1
        grid.setCells(batch.cells, current, geometry)
        updateBar()
        fireReady()
    }

    /** Numbered frames for [gridPage] before its first batch (only visible if the batch is late). */
    private fun showPlaceholders(gridPage: Int) {
        val first = ThumbGridMath.firstOf(gridPage, perPage)
        val n = ThumbGridMath.countOn(gridPage, total, perPage)
        grid.setCells(List(n) { ThumbCell(first + it, -1, 0, null, 0) }, current, geometry)
        shown = gridPage
        updateBar()
    }

    private fun updateBar() {
        val pages = ThumbGridMath.gridPages(total, perPage)
        bar.show(shown + 1, pages, canPrev = shown > 0, canNext = shown + 1 < pages)
        val text = "${shown + 1} / $pages · ${current}쪽"
        if (bar.label.text.toString() != text) bar.label.text = text
    }

    private fun fireReady() {
        main.removeCallbacks(readyFallback)
        val r = onReady ?: return
        onReady = null
        r()
    }

    private fun open(cell: ThumbCell) {
        if (cell.section < 0) return
        val jump = host as? PageJumpHost ?: return
        // The jump's layout request goes first: the dismiss's neighbour prefetch (cancelThumbs) must not get ahead of it
        // on the single layout thread.
        jump.goToPage(cell.section, cell.pageIndex, remember = true)
        close()
    }

    private fun askPage() {
        if (total <= 0 || geometry == null) return
        val max = total // the hint keeps the total of the moment it opens
        InkNumPad.show(ctx, "쪽 번호", "1–${max}쪽", NumPadState.lengthFor(max)) { p ->
            if (started) request(ThumbGridMath.gridPageOf(p.coerceIn(1, max), perPage))
            null
        }
    }

    /** Grid geometry for a [w] × [h] px grid area. True when it changed. */
    private fun measure(w: Int, h: Int): Boolean {
        if (w <= 0 || h <= 0) return false
        if (w == areaW && h == areaH && geometry != null) return false
        val d = ctx.resources.displayMetrics.density.takeIf { it > 0f } ?: 1f
        val g = ThumbGridMath.layout(w / d, h / d, thumbs?.thumbAspect() ?: 0f)
        areaW = w
        areaH = h
        val changed = g != geometry
        geometry = g
        perPage = ThumbGridMath.perPage(g)
        thumbWPx = (g.thumbW * d).roundToInt().coerceAtLeast(1)
        thumbHPx = (g.thumbH * d).roundToInt().coerceAtLeast(1)
        return changed
    }

    /** The grid's real size differs from the estimate: re-request the page holding the first page on screen. */
    private fun resized(w: Int, h: Int) {
        val before = if (shown >= 0) ThumbGridMath.firstOf(shown, perPage) else -1
        if (!measure(w, h) || !started || before < 0) return
        shown = ThumbGridMath.gridPageOf(before, perPage)
        request(shown)
    }

    companion object {
        /** Toolbar 56 + tabs 45 + hairline 1 (the dialog chrome above the body). */
        const val CHROME_DP = 102
        /** The host reports partial batches after 700 ms; this only guards a host that never calls back. */
        const val READY_FALLBACK_MS = 800L

        /** The tab can show thumbnails: the host supports them and a page is shown (else "책을 여는 중입니다…"). */
        fun available(host: ReaderHost): Boolean = ((host as? PageThumbsHost)?.thumbTotal() ?: 0) > 0
    }
}

/**
 * One View that draws every cell of a grid page (no adapter): the thumbnail bitmap (or a numbered frame while it
 * renders), a 1 px frame (the current page: 3 dp black), the 12 sp label (bold for the current page) and the marks —
 * bookmark ribbon top-right, ❝ / ✎ bottom-left. Drawing allocates nothing (labels are built in [setCells]). Taps
 * and swipes on either axis ([PageDrag] with `axisBoth`) go to [onTap] / [onPage]; no pressed state, no scrolling.
 */
internal class ThumbGridView(context: Context) : View(context) {
    var onTap: ((ThumbCell) -> Unit)? = null
    var onPage: ((Int) -> Unit)? = null
    var onSize: ((Int, Int) -> Unit)? = null

    private val density = resources.displayMetrics.density.takeIf { it > 0f } ?: 1f
    private var cells: List<ThumbCell> = emptyList()
    private var labels: Array<String> = emptyArray()
    private var current = 0
    private var grid: ThumbGridMath.Grid? = null

    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val blank = Paint().apply { style = Paint.Style.FILL; color = Ink.WHITE }
    private val frame = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 1f; color = Ink.LINE_LIGHT }
    private val currentFrame = Paint().apply { style = Paint.Style.STROKE; strokeWidth = context.dpF(3f); color = Ink.BLACK }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = context.sp(12f)
        color = Ink.BLACK
        textAlign = Paint.Align.CENTER
    }
    private val labelBold = Paint(label).apply { typeface = Typeface.DEFAULT_BOLD }
    private val number = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = context.sp(14f)
        color = Ink.GRAY
        textAlign = Paint.Align.CENTER
    }
    private val markText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = context.sp(11f)
        color = Ink.BLACK
        textAlign = Paint.Align.CENTER
    }
    private val chip = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Ink.WHITE }
    private val chipEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f; color = Ink.BLACK }
    /** E-ink (or not probed yet): the bookmark mark in black with a white edge; phones draw the page ribbon's blue. */
    private val eink = DeviceClass.cached(context) != false
    private val ribbonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = RibbonMath.color(eink, Ink.BLACK)
    }
    private val ribbonEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f; color = Ink.WHITE }
    private val labelBaseline: Float
    private val numberDrop: Float
    private val markDrop: Float
    private val rect = RectF()
    private val ribbon = Path()

    private val drag = PageDrag(TapSlop.px(ViewConfiguration.get(context).scaledTouchSlop, density), axisBoth = true)
    private var down = false
    private var downX = 0f
    private var downY = 0f

    init {
        val fm = label.fontMetrics
        labelBaseline = (ThumbGridMath.LABEL_DP * density - (fm.descent - fm.ascent)) / 2f - fm.ascent
        val nm = number.fontMetrics
        numberDrop = -(nm.ascent + nm.descent) / 2f
        val mm = markText.fontMetrics
        markDrop = -(mm.ascent + mm.descent) / 2f
        isClickable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    /** New cells (one grid page): one invalidate, so the whole grid changes in one update. */
    fun setCells(cells: List<ThumbCell>, current: Int, grid: ThumbGridMath.Grid?) {
        this.cells = cells
        this.current = current
        this.grid = grid
        labels = Array(cells.size) { cells[it].page.toString() }
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) onSize?.invoke(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Ink.WHITE)
        val g = grid ?: return
        val widthDp = width / density
        val n = minOf(cells.size, labels.size, g.cols * g.rows)
        val tw = (g.thumbW * density).roundToInt().toFloat()
        val th = (g.thumbH * density).roundToInt().toFloat()
        for (i in 0 until n) {
            val c = cells[i]
            val left = (ThumbGridMath.cellLeft(g, i % g.cols, widthDp) * density).roundToInt().toFloat()
            val top = (ThumbGridMath.cellTop(g, i / g.cols) * density).roundToInt().toFloat()
            rect.set(left, top, left + tw, top + th)
            val bmp: Bitmap? = c.bitmap
            if (bmp != null && !bmp.isRecycled) {
                canvas.drawBitmap(bmp, null, rect, bitmapPaint)
            } else {
                canvas.drawRect(rect, blank)
                canvas.drawText(labels[i], rect.centerX(), rect.centerY() + numberDrop, number)
            }
            val isCurrent = c.page == current
            if (isCurrent) {
                val half = currentFrame.strokeWidth / 2f
                rect.inset(-half, -half)
                canvas.drawRect(rect, currentFrame)
                rect.inset(half, half)
            } else {
                rect.inset(0.5f, 0.5f)
                canvas.drawRect(rect, frame)
                rect.inset(-0.5f, -0.5f)
            }
            drawMarks(canvas, c.marks, left, top, tw, th)
            canvas.drawText(labels[i], left + tw / 2f, top + th + labelBaseline, if (isCurrent) labelBold else label)
        }
    }

    private fun drawMarks(canvas: Canvas, marks: Int, left: Float, top: Float, w: Float, h: Float) {
        if (marks and ThumbCell.MARK_BOOKMARK != 0) {
            // The page's ribbon (RibbonMath: ReadEra's shape, blue on phones), scaled to the mark's width.
            val rw = RibbonMath.THUMB_WIDTH_DP * density
            val rh = rw * RibbonMath.HEIGHT_DP / RibbonMath.WIDTH_DP
            val r = left + w - rw * RibbonMath.RIGHT_DP / RibbonMath.WIDTH_DP
            val l = r - rw
            ribbon.reset()
            ribbon.moveTo(l, top)
            ribbon.lineTo(r, top)
            ribbon.lineTo(r, top + rh)
            ribbon.lineTo(l + rw / 2f, top + rh - rh * RibbonMath.NOTCH_FRACTION)
            ribbon.lineTo(l, top + rh)
            ribbon.close()
            canvas.drawPath(ribbon, ribbonPaint)
            if (eink) canvas.drawPath(ribbon, ribbonEdge)
        }
        val quote = marks and ThumbCell.MARK_QUOTE != 0
        val note = marks and ThumbCell.MARK_NOTE != 0
        if (!quote && !note) return
        val text = if (quote && note) QUOTE_NOTE else if (quote) QUOTE else NOTE
        val cw = (if (quote && note) 26f else 15f) * density
        val ch = 15f * density
        val l = left + 3f * density
        val b = top + h - 3f * density
        rect.set(l, b - ch, l + cw, b)
        val radius = 3f * density
        canvas.drawRoundRect(rect, radius, radius, chip)
        canvas.drawRoundRect(rect, radius, radius, chipEdge)
        canvas.drawText(text, rect.centerX(), rect.centerY() + markDrop, markText)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                down = true
                downX = event.x
                downY = event.y
                drag.down(event.x, event.y)
            }
            MotionEvent.ACTION_MOVE -> if (down && drag.move(event.x, event.y)) parent?.requestDisallowInterceptTouchEvent(true)
            MotionEvent.ACTION_UP -> {
                if (!down) return true
                down = false
                val moved = drag.move(event.x, event.y)
                val dir = drag.up(event.x, event.y)
                if (dir != 0) onPage?.invoke(dir)
                else if (!moved) cellAt(downX, downY)?.let { c ->
                    performClick()
                    onTap?.invoke(c)
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                down = false
                drag.cancel()
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    /** The cell whose thumbnail or label contains (x, y). */
    private fun cellAt(x: Float, y: Float): ThumbCell? {
        val g = grid ?: return null
        val widthDp = width / density
        val n = minOf(cells.size, g.cols * g.rows)
        val tw = g.thumbW * density
        val cellH = (g.thumbH + ThumbGridMath.LABEL_DP) * density
        for (i in 0 until n) {
            val left = ThumbGridMath.cellLeft(g, i % g.cols, widthDp) * density
            val top = ThumbGridMath.cellTop(g, i / g.cols) * density
            if (x >= left && x < left + tw && y >= top && y < top + cellH) return cells[i]
        }
        return null
    }

    private companion object {
        const val QUOTE = "❝"
        const val NOTE = "✎"
        const val QUOTE_NOTE = "❝✎"
    }
}
