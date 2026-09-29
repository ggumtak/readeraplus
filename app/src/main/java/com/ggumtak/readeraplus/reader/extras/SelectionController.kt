package com.ggumtak.readeraplus.reader.extras

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupWindow
import com.ggumtak.readeraplus.R
import com.ggumtak.readeraplus.data.Library
import com.ggumtak.readeraplus.data.Quote
import com.ggumtak.readeraplus.engine.LineGeometry
import com.ggumtak.readeraplus.engine.OBJECT_CHAR
import com.ggumtak.readeraplus.engine.PageInfo
import com.ggumtak.readeraplus.engine.SectionLayout
import com.ggumtak.readeraplus.format.DocPosition
import com.ggumtak.readeraplus.reader.ReaderHost
import com.ggumtak.readeraplus.render.Highlight
import com.ggumtak.readeraplus.render.HighlightKind
import com.ggumtak.readeraplus.settings.Settings
import com.ggumtak.readeraplus.ui.kit.Ink
import com.ggumtak.readeraplus.ui.kit.borderBox
import com.ggumtak.readeraplus.ui.kit.confirm
import com.ggumtak.readeraplus.ui.kit.dp
import com.ggumtak.readeraplus.ui.kit.dpF
import com.ggumtak.readeraplus.ui.kit.horizontal
import com.ggumtak.readeraplus.ui.kit.icon
import com.ggumtak.readeraplus.ui.kit.label
import com.ggumtak.readeraplus.ui.kit.lp
import com.ggumtak.readeraplus.ui.kit.pressableBackground
import com.ggumtak.readeraplus.ui.kit.sp
import com.ggumtak.readeraplus.ui.kit.toast
import com.ggumtak.readeraplus.ui.kit.vertical
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.hypot

/**
 * Long-press text selection with two draggable handles and an action popup
 * (copy, quote, note, share, search, dictionary/translate, web search, read aloud).
 * The selection is limited to the current page of the current section; highlight owner "selection".
 */
class SelectionController(private val host: ReaderHost) {
    // Resolved lazily: the host may construct this before its own properties are initialised.
    private val ctx get() = host.activity
    private val scope = MainScope()
    private val slop by lazy { ViewConfiguration.get(host.activity).scaledTouchSlop }

    private var active = false
    private var section = -1
    private var selStart = 0
    private var selEnd = 0
    /** The word picked by the long press: dragging the same finger extends from it in both directions. */
    private var anchorStart = 0
    private var anchorEnd = 0
    /** The gesture that started the selection (long press) is still going: its MOVE/UP are ours. */
    private var fromLongPress = false
    private var tapCandidate = false
    private var downX = 0f
    private var downY = 0f

    private var startHandle: HandleView? = null
    private var endHandle: HandleView? = null
    private var actions: PopupWindow? = null
    private var editingQuote: Quote? = null

    /** Quotes of the book come from [QuoteCache] (shared with the contents dialog); reloaded once per controller. */
    private var quotesRequested = false
    private val main = Handler(Looper.getMainLooper())
    /** A second long press while a selection is shown starts a new selection there (drag extends it). */
    private val reselect = Runnable {
        if (active && tapCandidate) {
            tapCandidate = false
            startAt(downX, downY)
        }
    }

    private var originKey: Any? = null
    private var originX = 0f
    private var originY = 0f

    /** Optional "read aloud from here" hook (defaults to the TtsController created for the same host). */
    var onReadAloud: ((DocPosition) -> Unit)? = null

    val isActive: Boolean get() = active

    /** Starts a selection at the word under view coordinates (x, y). Returns true if something was selected. */
    fun startAt(x: Float, y: Float): Boolean {
        val layout = host.currentLayout ?: return false
        val page = host.currentPage ?: return false
        val off = host.hitTest(x, y)
        val text = layout.content.text
        if (off < 0 || off >= text.length || off < page.start || off >= page.end) return false
        if (active) clear()
        val sec = host.currentPosition().section
        ensureQuotes()

        val q = QuoteCache.get(host.book.id)?.firstOrNull { it.section == sec && off >= it.start && off < it.end }
        var s: Int
        var e: Int
        if (q != null) {
            s = q.start
            e = q.end
        } else {
            val packed = runCatching { LineGeometry.wordAt(text, off) }.getOrDefault((off.toLong() shl 32) or (off + 1).toLong())
            s = (packed ushr 32).toInt()
            e = (packed and 0xFFFFFFFFL).toInt()
        }
        s = s.coerceIn(page.start, page.end)
        e = e.coerceIn(s, page.end)
        if (e <= s) return false
        if (e - s == 1 && (text[s] == OBJECT_CHAR || SentenceSplitter.isSpace(text[s]))) return false

        section = sec
        selStart = s
        selEnd = e
        anchorStart = s
        anchorEnd = e
        editingQuote = q
        active = true
        fromLongPress = true
        tapCandidate = false
        updateHighlight()
        showHandles()
        showActions()
        return true
    }

    /** Touch events while active (handle dragging; a tap outside clears). Returns true if consumed. */
    fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!active) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                fromLongPress = false
                tapCandidate = true
                downX = ev.x
                downY = ev.y
                main.removeCallbacks(reselect)
                main.postDelayed(reselect, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> {
                if (fromLongPress) {
                    hideActions()
                    extendTo(ev.x, ev.y)
                } else if (tapCandidate && hypot(ev.x - downX, ev.y - downY) > slop) {
                    tapCandidate = false
                    main.removeCallbacks(reselect)
                }
            }
            MotionEvent.ACTION_UP -> {
                main.removeCallbacks(reselect)
                if (fromLongPress) {
                    fromLongPress = false
                    showActions()
                } else if (tapCandidate) {
                    clear()
                }
                tapCandidate = false
            }
            MotionEvent.ACTION_CANCEL -> {
                main.removeCallbacks(reselect)
                if (fromLongPress) showActions()
                fromLongPress = false
                tapCandidate = false
            }
        }
        return true
    }

    fun clear() {
        val wasActive = active
        active = false
        fromLongPress = false
        tapCandidate = false
        main.removeCallbacks(reselect)
        editingQuote = null
        hideActions()
        startHandle?.let { (it.parent as? ViewGroup)?.removeView(it) }
        endHandle?.let { (it.parent as? ViewGroup)?.removeView(it) }
        startHandle = null
        endHandle = null
        if (wasActive && section >= 0) runCatching { host.setHighlights("selection", section, emptyList()) }
        section = -1
    }

    /** Called by the host after page changes / relayout so handles and popup follow or close. */
    fun onPageChanged() {
        if (active) clear()
    }

    // ------------------------------------------------------------------ selection geometry

    private fun validPage(): Pair<SectionLayout, PageInfo>? {
        val layout = host.currentLayout ?: return null
        val page = host.currentPage ?: return null
        if (host.currentPosition().section != section) return null
        return layout to page
    }

    private fun extendTo(x: Float, y: Float) {
        val (_, page) = validPage() ?: return
        val off = host.hitTest(x, y)
        if (off < 0) return
        val o = off.coerceIn(page.start, page.end - 1)
        val s = minOf(anchorStart, o)
        val e = maxOf(anchorEnd, o + 1).coerceAtMost(page.end)
        setRange(s, e)
    }

    private fun setRange(s: Int, e: Int) {
        if (s == selStart && e == selEnd) return
        if (e <= s) return
        selStart = s
        selEnd = e
        if (editingQuote != null && (s != editingQuote?.start || e != editingQuote?.end)) editingQuote = null
        updateHighlight()
        showHandles()
    }

    private fun updateHighlight() {
        runCatching { host.setHighlights("selection", section, listOf(Highlight(selStart, selEnd, HighlightKind.SELECTION))) }
    }

    /**
     * View-space offset of the page content box (see [OriginCalibrator]). A successful calibration is cached per
     * layout config and view size; the margin-formula fallback is never cached (another page may calibrate).
     */
    private fun origin(layout: SectionLayout, page: PageInfo) {
        val v = host.pageView
        val key = listOf(layout.config, v.width, v.height, v.paddingLeft, v.paddingTop)
        if (key == originKey) return
        val boxes = page.lines.map { OriginCalibrator.LineBox(it.start, it.end, it.top, it.bottom) }
        val dy = OriginCalibrator.calibrateY(boxes, v.height, v.width * 0.3f) { px, py -> host.hitTest(px, py) }
        var dx = Float.NaN
        if (!dy.isNaN()) {
            val line = page.lines.filter { it.imageBlock == null && !it.isRule && it.end - it.start >= 2 }.maxByOrNull { it.end - it.start }
            if (line != null) {
                val lefts = FloatArray(line.end - line.start)
                runCatching { LineGeometry.charPositions(layout, line, lefts) }.onSuccess {
                    val py = (line.top + line.bottom) / 2f + dy
                    dx = OriginCalibrator.calibrateX(line.start, line.end, lefts, layout.advances, v.width, py) { px, qy -> host.hitTest(px, qy) }
                }
            }
        }
        val s = Settings.reader
        originX = if (!dx.isNaN()) dx else v.paddingLeft + if (s.pageMargins) ctx.dpF(s.marginLeftDp.toFloat()) else ctx.dpF(4f)
        originY = if (!dy.isNaN()) dy else v.paddingTop + (if (s.pageMargins) ctx.dpF(s.marginTopDp.toFloat()) else ctx.dpF(4f)) +
            if (s.showHeader) ctx.sp(s.statusFontSizeSp) * 2.2f else 0f
        originKey = if (!dx.isNaN() && !dy.isNaN()) key else null
    }

    private fun showHandles() {
        val (layout, page) = validPage() ?: return
        val parent = Overlay.parentOf(host) ?: return
        val rects = runCatching { LineGeometry.rangeRects(layout, page, selStart, selEnd) }.getOrNull()
        if (rects.isNullOrEmpty()) return
        origin(layout, page)
        val pv = host.pageView
        val baseX = pv.left + pv.translationX + originX
        val baseY = pv.top + pv.translationY + originY
        val first = rects.first()
        val last = rects.last()
        val sh = startHandle ?: HandleView(ctx, start = true).also { h ->
            startHandle = h
            parent.addView(h, FrameLayout.LayoutParams(h.sizePx, h.sizePx))
            h.setOnTouchListener(HandleDrag(h))
        }
        val eh = endHandle ?: HandleView(ctx, start = false).also { h ->
            endHandle = h
            parent.addView(h, FrameLayout.LayoutParams(h.sizePx, h.sizePx))
            h.setOnTouchListener(HandleDrag(h))
        }
        sh.place(baseX + first.left, baseY + first.bottom, (first.bottom - first.top) / 2f)
        eh.place(baseX + last.right, baseY + last.bottom, (last.bottom - last.top) / 2f)
    }

    /** Drags a handle; the hit point is the handle's anchor moved up into the middle of the text line. */
    private inner class HandleDrag(private val h: HandleView) : View.OnTouchListener {
        private var grabDx = 0f
        private var grabDy = 0f

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    grabDx = h.anchorX - ev.rawX
                    grabDy = h.anchorY - ev.rawY
                    hideActions()
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_MOVE -> {
                    val (_, page) = validPage() ?: return true
                    val pv = host.pageView
                    val px = ev.rawX + grabDx - pv.left - pv.translationX
                    val py = ev.rawY + grabDy - pv.top - pv.translationY - h.lineHalf
                    val off = host.hitTest(px, py)
                    if (off >= 0) {
                        val o = off.coerceIn(page.start, page.end - 1)
                        if (h.start) setRange(o.coerceAtMost(selEnd - 1), selEnd) else setRange(selStart, (o + 1).coerceAtLeast(selStart + 1))
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> showActions()
            }
            return true
        }
    }

    // ------------------------------------------------------------------ action popup

    private class Action(val label: String, val icon: Int, val run: () -> Unit)

    private fun actionList(): List<Action> {
        val list = ArrayList<Action>(10)
        list += Action("복사", R.drawable.ic_content_copy) { copy() }
        val q = editingQuote
        if (q == null) {
            list += Action("인용", R.drawable.ic_format_quote) { saveQuote("") }
            list += Action("메모", R.drawable.ic_sticky_note_2) { noteThenQuote() }
        } else {
            list += Action("메모", R.drawable.ic_sticky_note_2) { editQuoteNote(q) }
            list += Action("인용 삭제", R.drawable.ic_delete) { deleteQuote(q) }
        }
        list += Action("공유", R.drawable.ic_share) { share() }
        list += Action("문단", R.drawable.ic_select_all) { selectParagraph() }
        list += Action("검색", R.drawable.ic_search) { searchInBook() }
        list += Action("사전·번역", R.drawable.ic_translate) { lookUp() }
        list += Action("웹 검색", R.drawable.ic_travel_explore) { webSearch() }
        if (onReadAloud != null || TtsRegistry.get(host) != null) list += Action("여기서 읽기", R.drawable.ic_volume_up) { readAloud() }
        return list
    }

    private fun showActions() {
        if (!active) return
        val (layout, page) = validPage() ?: return
        val rects = runCatching { LineGeometry.rangeRects(layout, page, selStart, selEnd) }.getOrNull()
        if (rects.isNullOrEmpty()) return
        origin(layout, page)
        hideActions()

        val cols = 5
        val acts = actionList()
        val grid = ctx.vertical {
            background = ctx.borderBox(radiusDp = 4f)
            setPadding(ctx.dp(4), ctx.dp(4), ctx.dp(4), ctx.dp(4))
        }
        var row: LinearLayout? = null
        acts.forEachIndexed { i, a ->
            if (i % cols == 0) row = ctx.horizontal().also { grid.addView(it, lp()) }
            val cell = ctx.vertical {
                gravity = Gravity.CENTER_HORIZONTAL
                background = pressableBackground()
                setPadding(ctx.dp(2), ctx.dp(8), ctx.dp(2), ctx.dp(6))
                minimumWidth = ctx.dp(62)
                setOnClickListener { a.run() }
            }
            cell.addView(ctx.icon(a.icon, 24))
            cell.addView(ctx.label(a.label, 12f, maxLines = 1).apply { gravity = Gravity.CENTER; setPadding(0, ctx.dp(4), 0, 0) })
            row?.addView(cell, LinearLayout.LayoutParams(ctx.dp(62), WRAP_CONTENT))
        }

        val dm = ctx.resources.displayMetrics
        grid.measure(
            View.MeasureSpec.makeMeasureSpec(dm.widthPixels, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val w = grid.measuredWidth
        val h = grid.measuredHeight
        val loc = IntArray(2)
        host.pageView.getLocationInWindow(loc)
        var top = Float.MAX_VALUE
        var bottom = 0f
        var left = Float.MAX_VALUE
        var right = 0f
        for (r in rects) {
            top = minOf(top, r.top)
            bottom = maxOf(bottom, r.bottom)
            left = minOf(left, r.left)
            right = maxOf(right, r.right)
        }
        val winTop = loc[1] + originY + top
        val winBottom = loc[1] + originY + bottom
        val centerX = loc[0] + originX + (left + right) / 2f
        val margin = ctx.dp(8)
        val x = (centerX - w / 2f).toInt().coerceIn(margin, (dm.widthPixels - w - margin).coerceAtLeast(margin))
        var y = (winTop - h - ctx.dp(10)).toInt()
        if (y < margin) {
            y = (winBottom + (startHandle?.sizePx ?: ctx.dp(40)) + ctx.dp(4)).toInt()
            if (y + h > dm.heightPixels - margin) y = ((dm.heightPixels - h) / 2).coerceAtLeast(margin)
        }
        val pw = PopupWindow(grid, WRAP_CONTENT, WRAP_CONTENT, false).apply {
            animationStyle = 0
            elevation = 0f
            isTouchable = true
            isOutsideTouchable = false
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }
        runCatching { pw.showAtLocation(host.pageView, Gravity.NO_GRAVITY, x, y) }
        actions = pw
    }

    private fun hideActions() {
        actions?.let { runCatching { it.dismiss() } }
        actions = null
    }

    // ------------------------------------------------------------------ actions

    private fun selectedText(): String {
        val layout = host.currentLayout ?: return ""
        val text = layout.content.text
        val s = selStart.coerceIn(0, text.length)
        val e = selEnd.coerceIn(s, text.length)
        val sb = StringBuilder(e - s)
        for (i in s until e) {
            val c = text[i]
            if (c != OBJECT_CHAR) sb.append(c)
        }
        return sb.toString().trim()
    }

    private fun copy() {
        val t = selectedText()
        clear()
        if (t.isNotEmpty()) TextActions.copy(ctx, t)
    }

    private fun share() {
        val t = selectedText()
        clear()
        if (t.isEmpty()) return
        val by = host.book.author.takeIf { it.isNotBlank() }?.let { ", $it" } ?: ""
        TextActions.share(ctx, "“$t”\n— ${host.book.title}$by", host.book.title)
    }

    private fun searchInBook() {
        val t = selectedText().replace('\n', ' ')
        clear()
        if (t.isNotEmpty()) ReaderPanels.showSearch(host, t.take(100))
    }

    private fun lookUp() {
        val t = selectedText()
        clear()
        if (t.isNotEmpty()) TextActions.lookUp(ctx, t)
    }

    private fun webSearch() {
        val t = selectedText().replace('\n', ' ')
        clear()
        if (t.isNotEmpty()) TextActions.webSearch(ctx, t.take(200))
    }

    private fun readAloud() {
        val pos = DocPosition(section, selStart)
        clear()
        val cb = onReadAloud
        if (cb != null) cb(pos) else TtsRegistry.get(host)?.startFrom(pos)
    }

    private fun selectParagraph() {
        val (layout, page) = validPage() ?: return
        val text = layout.content.text
        var s = selStart
        while (s > page.start && text[s - 1] != '\n') s--
        var e = selEnd
        while (e < page.end && text[e] != '\n') e++
        setRange(s, e)
        showActions()
    }

    private fun saveQuote(note: String) {
        val t = selectedText()
        if (t.isEmpty()) {
            clear()
            return
        }
        val sec = section
        val s = selStart
        val e = selEnd
        val bookId = host.book.id
        clear()
        scope.launch {
            val all = withContext(Dispatchers.IO) {
                runCatching { Library.addQuote(bookId, sec, s, e, t, note) }
                runCatching { Library.quotes(bookId) }.getOrNull()
            }
            if (all != null) {
                ContentsDialog.refreshQuoteHighlights(host, sec, all)
                ctx.toast("인용문에 저장했습니다")
            } else {
                ctx.toast("저장하지 못했습니다")
            }
        }
    }

    private fun noteThenQuote() {
        hideActions()
        ctx.multilinePrompt("메모", "", "선택한 문장에 대한 메모", minLines = 3) { note -> saveQuote(note.trim()) }
    }

    private fun editQuoteNote(q: Quote) {
        clear()
        ctx.multilinePrompt("인용문 메모", q.note, "메모", minLines = 3) { note ->
            scope.launch {
                val all = withContext(Dispatchers.IO) {
                    runCatching { Library.updateQuoteNote(q.id, note.trim()) }
                    runCatching { Library.quotes(host.book.id) }.getOrNull()
                }
                if (all != null) QuoteCache.put(host.book.id, all)
            }
        }
    }

    private fun deleteQuote(q: Quote) {
        clear()
        ctx.confirm("인용문 삭제", "이 인용문을 삭제할까요?", "삭제") {
            scope.launch {
                val all = withContext(Dispatchers.IO) {
                    runCatching { Library.deleteQuote(q.id) }
                    runCatching { Library.quotes(host.book.id) }.getOrNull()
                }
                if (all != null) ContentsDialog.refreshQuoteHighlights(host, q.section, all)
            }
        }
    }

    /**
     * Refreshes [QuoteCache] once per controller (i.e. per opened book), off the main thread. Until it arrives the
     * cached list (if any) is used; edits made through this module keep the cache current afterwards.
     */
    private fun ensureQuotes() {
        if (quotesRequested) return
        val bookId = runCatching { host.book.id }.getOrNull() ?: return
        quotesRequested = true
        scope.launch {
            val list = withContext(Dispatchers.IO) { runCatching { Library.quotes(bookId) }.getOrNull() }
            if (list != null) QuoteCache.put(bookId, list) else quotesRequested = false
        }
    }
}

/**
 * Selection handle: a black teardrop whose sharp corner ([anchorX], [anchorY], parent coordinates) touches the
 * selection's start (bottom-left) or end (bottom-right) corner. The view is larger than the drawing (touch target).
 */
@SuppressLint("ViewConstructor")
internal class HandleView(context: Context, val start: Boolean) : View(context) {
    val sizePx = context.dp(44)
    private val r = context.dpF(10f)
    private val ax = if (start) sizePx / 2f + r / 2f else sizePx / 2f - r / 2f
    private val ay = context.dpF(1f)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ink.BLACK; style = Paint.Style.FILL }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ink.WHITE; style = Paint.Style.STROKE; strokeWidth = context.dpF(1.5f) }
    private val path = Path()

    var anchorX = 0f
        private set
    var anchorY = 0f
        private set
    var lineHalf = 0f
        private set

    init {
        isClickable = true
        val cx = if (start) ax - r else ax + r
        val cy = ay + r
        path.addCircle(cx, cy, r, Path.Direction.CW)
        if (start) path.addRect(cx, ay, ax, cy, Path.Direction.CW) else path.addRect(ax, ay, cx, cy, Path.Direction.CW)
    }

    /** Places the handle so its corner sits at ([x], [y]) in parent coordinates. */
    fun place(x: Float, y: Float, halfLine: Float) {
        anchorX = x
        anchorY = y
        lineHalf = halfLine
        translationX = x - ax - left
        translationY = y - ay - top
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawPath(path, outline)
        canvas.drawPath(path, paint)
    }
}

/**
 * Lets the selection popup reach the TtsController of the same reader (constructed by ReaderActivity).
 * Values are weak too: a TtsController references its host, so a strong value would keep the WeakHashMap key
 * (the activity) reachable forever if release() were ever skipped.
 */
internal object TtsRegistry {
    private val map = java.util.WeakHashMap<ReaderHost, java.lang.ref.WeakReference<TtsController>>()

    fun put(host: ReaderHost, c: TtsController) {
        map[host] = java.lang.ref.WeakReference(c)
    }

    fun remove(host: ReaderHost, c: TtsController) {
        if (map[host]?.get() === c) map.remove(host)
    }

    fun get(host: ReaderHost): TtsController? = map[host]?.get()
}
