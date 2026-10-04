package com.ggumtak.readeraplus.reader.extras

import android.app.AlertDialog
import android.app.Dialog
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.HorizontalScrollView
import com.ggumtak.readeraplus.ui.kit.PageTarget
import com.ggumtak.readeraplus.reader.ReaderJump
import android.widget.ListView
import android.widget.TextView
import com.ggumtak.readeraplus.R
import com.ggumtak.readeraplus.data.Bookmark
import com.ggumtak.readeraplus.data.Library
import com.ggumtak.readeraplus.data.NotesTab
import com.ggumtak.readeraplus.data.Quote
import com.ggumtak.readeraplus.format.BookDocument
import com.ggumtak.readeraplus.format.BookFormat
import com.ggumtak.readeraplus.format.DocPosition
import com.ggumtak.readeraplus.reader.ReaderFormat
import com.ggumtak.readeraplus.reader.JumpAnchor
import com.ggumtak.readeraplus.reader.ReaderHost
import com.ggumtak.readeraplus.render.Highlight
import com.ggumtak.readeraplus.render.HighlightKind
import com.ggumtak.readeraplus.render.QuoteLook
import com.ggumtak.readeraplus.render.QuoteStyles
import com.ggumtak.readeraplus.settings.AppSettings
import com.ggumtak.readeraplus.settings.Settings
import com.ggumtak.readeraplus.settings.TapAction
import com.ggumtak.readeraplus.ui.kit.Ink
import com.ggumtak.readeraplus.ui.kit.InkNumPad
import com.ggumtak.readeraplus.ui.kit.InkPager
import com.ggumtak.readeraplus.ui.kit.InkPagerBar
import com.ggumtak.readeraplus.ui.kit.MenuItem
import com.ggumtak.readeraplus.ui.kit.ToolbarAction
import com.ggumtak.readeraplus.ui.kit.NumPadState
import com.ggumtak.readeraplus.ui.kit.alert
import com.ggumtak.readeraplus.ui.kit.borderBox
import com.ggumtak.readeraplus.ui.kit.confirm
import com.ggumtak.readeraplus.ui.kit.dp
import com.ggumtak.readeraplus.ui.kit.einkListView
import com.ggumtak.readeraplus.ui.kit.fullScreenDialog
import com.ggumtak.readeraplus.ui.kit.hairline
import com.ggumtak.readeraplus.ui.kit.horizontal
import com.ggumtak.readeraplus.ui.kit.inkPaging
import com.ggumtak.readeraplus.ui.kit.label
import com.ggumtak.readeraplus.ui.kit.lp
import com.ggumtak.readeraplus.ui.kit.popupMenu
import com.ggumtak.readeraplus.ui.kit.pressableBackground
import com.ggumtak.readeraplus.ui.kit.prompt
import com.ggumtak.readeraplus.ui.kit.showNoAnim
import com.ggumtak.readeraplus.ui.kit.toast
import com.ggumtak.readeraplus.ui.kit.toolbar
import com.ggumtak.readeraplus.ui.kit.vertical
import com.ggumtak.readeraplus.ui.library.LibraryText
import com.ggumtak.readeraplus.ui.notes.NotesActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Full-screen 목차 · 북마크 · 인용문 dialog. Every list is paged a screen at a time ([InkPager]: pager bar, page keys,
 * a drag is one page jump). The TOC tab (T1-1) has a header — "540화 · 지금 123화" with [지금] [화 번호] [검색], the
 * time left (T1-7) and, for a confidently numbered TOC, "빠진 화 3개 · 중복 1개 ›" — and marks the entries before the
 * current one in gray.
 */
internal class ContentsDialog(private val host: ReaderHost, initialTab: Int) {
    private val ctx = host.activity
    /**
     * The book / document this dialog was opened for (only while one is open). host.book throws between books, and
     * after another book is opened a stale row must not act on it: see [stale].
     */
    private val book = host.book
    private val doc0 = host.document
    private val scope = MainScope()
    private lateinit var dialog: Dialog
    private val body = FrameLayout(ctx)
    private val tabLabels = arrayOfNulls<TextView>(4)
    private val tabBars = arrayOfNulls<View>(4)
    private val tabViews = arrayOfNulls<View>(4)
    /** The tabs' list pagers (null while a tab loads or is empty): the page keys move the selected one. */
    private val pagers = arrayOfNulls<PageTarget>(4)
    private var tab = initialTab.coerceIn(0, 3).let { if (it == 3 && !thumbsShown()) 0 else it }
    private var thumbsTab: ThumbsTab? = null
    private var onFirstShown: (() -> Unit)? = null
    private fun thumbsShown(): Boolean = (host as? PageThumbsHost)?.thumbnailsShown == true
    private lateinit var shareAll: View
    /** "모든 책의 노트" (북마크 and 인용문 tabs): the notes hub. */
    private lateinit var hubLink: View
    /** The book's quotes in reading order, as last loaded. */
    private var allQuotes: List<Quote> = emptyList()
    /** The quotes listed: [allQuotes] through the chip filter ([quoteFilter]); 모두 공유 shares these. */
    private var quotes: List<Quote> = emptyList()
    /** The selected chip's style, [QuoteRows.ALL] for 전체 (in memory only, never persisted). */
    private var quoteFilter = QuoteRows.ALL
    /** The open session's NoteSig ([NotePlaceHost]), null without one: decides "· 위치 바뀜". */
    private var sessionSig: String? = null
    /** Where the last touch went down on a quote row (row coordinates): a click there opens the palette. */
    private var quoteDownRow: View? = null
    private var quoteDownX = -1f
    /** Uptime of that DOWN: only a click right after it (a tap) uses it, not a later key or accessibility click. */
    private var quoteDownAt = 0L
    /** Episode numbers of the book's TOC (BookInsightsHost), null while unknown or without a TOC. */
    private var episodes: Episodes? = null
    /** The TOC tab once built (its header is filled in when the episodes arrive late). */
    private var tocTab: TocTab? = null

    fun show() {
        val insights = host as? BookInsightsHost
        if (doc0 == null || doc0.toc.isEmpty() || insights == null) {
            open()
            return
        }
        // The TOC header shows the episode numbers: a first parse (≈ 20 ms) is waited for briefly, so the dialog opens
        // complete in one e-ink update; a slower one shows the plain header and fills it in when done.
        EpisodeWait.run(host, if (tab == 0) EpisodeWait.PANEL_WAIT_MS else 0L) { e, timedOut ->
            if (ctx.isFinishing || ctx.isDestroyed || stale()) return@run
            episodes = e
            open()
            if (timedOut) insights.episodes { late -> if (dialog.isShowing && !stale()) applyEpisodes(late) }
        }
    }

    private fun open() {
        val root = ctx.vertical { setBackgroundColor(Ink.WHITE) }
        // toolbar: the kit's (title 20 sp bold, U polish 10)
        val bar = ctx.toolbar(book.title, R.drawable.ic_arrow_back, onNav = { dialog.dismiss() }, actions = listOf(
            ToolbarAction(R.drawable.ic_open_in_new, "모든 책의 노트") { openHub() },
            ToolbarAction(R.drawable.ic_share, "인용문 모두 공유") { shareAllQuotes() },
        ))
        val actions = bar.getChildAt(0) as ViewGroup
        hubLink = actions.getChildAt(actions.childCount - 2).apply { visibility = View.GONE }
        shareAll = actions.getChildAt(actions.childCount - 1).apply { visibility = View.GONE }
        root.addView(bar, lp())
        // tabs
        val tabs = ctx.horizontal()
        listOf("목차", "북마크", "인용문", "썸네일").forEachIndexed { i, name ->
            val cell = ctx.vertical {
                gravity = Gravity.CENTER_HORIZONTAL
                background = pressableBackground()
                setOnClickListener { select(i) }
            }
            val t = ctx.label(name, 16f).apply {
                gravity = Gravity.CENTER
                setPadding(0, ctx.dp(12), 0, ctx.dp(10))
            }
            val underline = View(ctx)
            cell.addView(t, lp())
            cell.addView(underline, LinearLayout.LayoutParams(ctx.dp(64), ctx.dp(3)))
            tabLabels[i] = t
            tabBars[i] = underline
            if (i == 3 && !thumbsShown()) cell.visibility = View.GONE
            tabs.addView(cell, lp(ctx.dp(90), WRAP_CONTENT))
        }
        root.addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; isFillViewport = true; addView(tabs) }, lp())
        root.addView(ctx.hairline())
        root.addView(body, lp(MATCH_PARENT, 0, 1f))
        dialog = ctx.fullScreenDialog(root)
        dialog.setOnDismissListener { thumbsTab?.stop(); onFirstShown = null; scope.cancel() }
        dialog.setOnKeyListener { _, code, event ->
            if (code == KeyEvent.KEYCODE_BACK && tab == 0 && tocTab?.isFiltering == true) {
                if (event.action == KeyEvent.ACTION_UP) tocTab?.clearFilterIfAny()
                true
            } else {
                val dir = ListKeys.direction(code, Settings.app)
                if (dir == 0) false else {
                    if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) pagers[tab]?.page(dir)
                    true
                }
            }
        }
        if (tab == 3 && ThumbsTab.available(host)) {
            onFirstShown = { if (!stale() && !ctx.isFinishing && !ctx.isDestroyed) { dialog.show(); PanelRegistry.dialog(ctx, dialog) } }
            select(tab)
        } else {
            select(tab)
            dialog.show()
            PanelRegistry.dialog(ctx, dialog)
        }
    }

    /**
     * The reader moved on (another book, no book, or this book's document re-opened): the rows no longer apply.
     * Opened while the document was still loading ([doc0] == null), the bookmark and quote rows stay valid.
     */
    private fun stale(): Boolean {
        val current = runCatching { host.book }.getOrNull() ?: return true
        if (current.id != book.id) return true
        return doc0 != null && host.document !== doc0
    }

    private fun select(i: Int) {
        if (stale()) {
            dialog.dismiss()
            return
        }
        if (tab == 3 && i != 3) thumbsTab?.stop()
        tab = i
        if (i == 3 && ThumbsTab.available(host)) {
            val t = thumbsTab ?: ThumbsTab(host) { dialog.dismiss() }.also { thumbsTab = it; tabViews[3] = it.view; pagers[3] = it }
            t.prepare(body.width, body.height) {
                if (tab == 3 && !stale()) {
                    showTabBody(3, t.view)
                    val ready = onFirstShown; onFirstShown = null; ready?.invoke()
                }
            }
            return
        }
        val v = tabViews[i] ?: when (i) {
            0 -> buildToc()
            1 -> FrameLayout(ctx).also { loadBookmarks(it) }
            2 -> FrameLayout(ctx).also { loadQuotes(it) }
            else -> ctx.label("책을 여는 중입니다…", 16f).apply { gravity = Gravity.CENTER }
        }.also { tabViews[i] = it }
        showTabBody(i, v)
    }

    private fun showTabBody(i: Int, v: View) {
        for (k in 0..3) {
            val sel = k == i
            tabLabels[k]?.typeface = if (sel) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            tabLabels[k]?.setTextColor(if (sel) Ink.BLACK else Ink.GRAY)
            tabBars[k]?.setBackgroundColor(if (sel) Ink.BLACK else Color.TRANSPARENT)
        }
        body.removeAllViews()
        body.addView(v, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        shareAll.visibility = if (i == 2 && quotes.isNotEmpty()) View.VISIBLE else View.GONE
        hubLink.visibility = if (i == 1 || i == 2) View.VISIBLE else View.GONE
    }

    /** "모든 책의 노트": the hub on the matching tab; the dialog goes (the reader reloads its notes on return). */
    private fun openHub() {
        val notesTab = if (tab == 1) NotesTab.BOOKMARKS else NotesTab.QUOTES
        dialog.dismiss()
        runCatching { NotesActivity.open(ctx, notesTab) }
    }

    private fun goAndClose(pos: DocPosition) {
        dialog.dismiss()
        if (!stale()) host.goTo(pos, remember = true)
    }

    /** A list with its pager bar below it (the bar is the list's page indicator and ◀ / ▶ buttons). */
    private fun pagedList(tabIndex: Int, list: ListView): LinearLayout {
        val col = ctx.vertical()
        col.addView(list, lp(MATCH_PARENT, 0, 1f))
        val bar = InkPagerBar(ctx)
        col.addView(bar)
        pagers[tabIndex] = list.inkPaging(bar)
        return col
    }

    // ------------------------------------------------------------------ 목차

    private fun buildToc(): View {
        val doc = host.document ?: return ctx.emptyMessage("책을 여는 중입니다…")
        if (doc.toc.isEmpty()) {
            val hint = if (doc.format == BookFormat.TXT) "\n\n읽기 설정에서 '챕터 자동 인식'을 켜거나\n챕터 규칙(정규식)을 추가해 보세요" else ""
            return ctx.emptyMessage("이 책에는 목차가 없습니다$hint")
        }
        return TocTab(doc).also { tocTab = it }.view
    }

    /** Episodes that arrived after the dialog showed (a slow first parse). */
    private fun applyEpisodes(e: Episodes?) {
        if (e == null || episodes != null) return
        episodes = e
        tocTab?.episodesChanged()
    }

    /** The TOC tab: header, entry list (paged) and its state. */
    private inner class TocTab(private val doc: BookDocument) {
        private val toc = doc.toc
        private val n = toc.size
        private val secs = IntArray(n) { toc[it].section }
        /** -1 = not resolved yet (anchor), shown with the section start meanwhile. */
        private val offs = IntArray(n) { if (toc[it].anchor == null) toc[it].offset.coerceAtLeast(0) else -1 }
        private val labels = arrayOfNulls<String>(n)
        private val here = host.currentPosition()
        private var current = currentIndex(secs, offs, here)
        /** TOC indices listed while a title filter is on (null = every entry). */
        private var rows: IntArray? = null
        private var query = ""
        private var normTitles: Array<String>? = null

        private val list = ctx.einkListView()
        private val summary = ctx.label("", 15f, maxLines = 1)
        private val nowBtn = headerButton("지금") { pager.showRow(current.coerceAtLeast(0), CURRENT_ROW) }
        private val numBtn = headerButton("화 번호") { askEpisode() }
        private val searchBtn = headerButton("검색") { askFilter() }
        private val allBtn = headerButton("전체 보기") { clearFilter() }
        private val timeLine = infoLine(Ink.GRAY)
        private val gapsLine = infoLine(Ink.BLACK).apply {
            paintFlags = paintFlags or Paint.UNDERLINE_TEXT_FLAG
            setOnClickListener { episodes?.let { showGaps(it) } }
        }
        private val noMatch = ctx.emptyMessage("")
        private val adapter = object : BaseAdapter() {
            override fun getCount() = rows?.size ?: n
            override fun getItem(position: Int) = toc[indexAt(position)]
            override fun getItemId(position: Int) = indexAt(position).toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val row = (convertView as? LinearLayout) ?: tocRow()
                val i = indexAt(position)
                val e = toc[i]
                val marker = row.findViewWithTag<TextView>("marker")
                val title = row.findViewWithTag<TextView>("title")
                val page = row.findViewWithTag<TextView>("page")
                val cur = i == current
                row.setPadding(ctx.dp(8) + ctx.dp(16) * (e.level - 1).coerceIn(0, 6), 0, ctx.dp(16), 0)
                marker.visibility = if (cur) View.VISIBLE else View.INVISIBLE
                title.text = e.title.ifBlank { "(제목 없음)" }
                title.typeface = if (cur) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                // Read episodes (before the current one) in gray.
                title.setTextColor(if (current >= 0 && i < current) Ink.GRAY else Ink.BLACK)
                val lbl = labels[i] ?: tocPageLabel(secs[i], offs[i].coerceAtLeast(0))
                    .also { if (offs[i] >= 0) labels[i] = it }
                page.text = lbl
                return row
            }
        }
        val view: View
        val pager: InkPager

        init {
            val col = ctx.vertical()
            val head = ctx.horizontal { setPadding(ctx.dp(16), 0, ctx.dp(10), 0) }
            head.addView(summary, lp(0, WRAP_CONTENT, 1f))
            for (b in listOf(nowBtn, numBtn, searchBtn, allBtn)) {
                head.addView(b, lp(WRAP_CONTENT, MATCH_PARENT).apply { leftMargin = ctx.dp(6) })
            }
            allBtn.visibility = View.GONE
            col.addView(head, lp(MATCH_PARENT, ctx.dp(HEADER_DP)))
            col.addView(timeLine, lp(MATCH_PARENT, ctx.dp(INFO_DP)))
            col.addView(gapsLine, lp(MATCH_PARENT, ctx.dp(40)))
            col.addView(ctx.hairline())
            val frame = FrameLayout(ctx)
            frame.addView(list, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            noMatch.visibility = View.GONE
            frame.addView(noMatch, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP))
            col.addView(frame, lp(MATCH_PARENT, 0, 1f))
            list.adapter = adapter
            list.setOnItemClickListener { _, _, position, _ -> openEntry(indexAt(position)) }
            val bar = InkPagerBar(ctx)
            col.addView(bar)
            pager = list.inkPaging(bar)
            pagers[0] = pager
            view = col

            // The time left is read once: the page does not move while the dialog shows.
            val insights = host as? BookInsightsHost
            val time = insights?.let {
                TocText.timeLeft(runCatching { it.minutesLeft(false) }.getOrNull(), runCatching { it.minutesLeft(true) }.getOrNull())
            }
            timeLine.text = time.orEmpty()
            timeLine.visibility = if (time != null) View.VISIBLE else View.GONE
            renderHeader()
            if (current > CURRENT_ROW) pager.showRow(current, CURRENT_ROW)
            startResolving()
        }

        private fun indexAt(position: Int): Int = rows?.get(position) ?: position

        /** Summary, buttons and the gaps line for the current episodes / filter. */
        private fun renderHeader() {
            val e = episodes
            val usable = e != null && e.usableForJump
            val filtering = rows != null
            summary.text = when {
                filtering -> TocText.filterSummary(query, rows?.size ?: 0)
                usable && e != null -> TocText.summary(n, current, e.maxNumber, if (current >= 0) e.numberAt(current) else -1)
                else -> TocText.summary(n, current, -1, -1)
            }
            nowBtn.visibility = if (filtering) View.GONE else View.VISIBLE
            numBtn.visibility = if (!filtering && usable) View.VISIBLE else View.GONE
            allBtn.visibility = if (filtering) View.VISIBLE else View.GONE
            val gaps = if (e != null && e.confident) TocText.gapsLine(e.gaps().size, e.dupes().size) else null
            gapsLine.text = gaps.orEmpty()
            gapsLine.visibility = if (gaps != null) View.VISIBLE else View.GONE
        }

        fun episodesChanged() {
            renderHeader()
        }

        private fun openEntry(i: Int, then: (() -> Unit)? = null) {
            val off = offs[i]
            if (off >= 0) {
                goAndClose(DocPosition(secs[i], off))
                then?.invoke()
            } else {
                scope.launch {
                    val p = withContext(Dispatchers.Default) { runCatching { doc.resolveToc(toc[i]) }.getOrNull() }
                    goAndClose(p ?: DocPosition(secs[i], 0))
                    then?.invoke()
                }
            }
        }

        /** [화 번호]: the number pad, then the entry (or the next higher number, said so after the jump). */
        private fun askEpisode() {
            val e = episodes ?: return
            val lo = e.minNumber.coerceAtLeast(0)
            val hi = e.maxNumber
            val pad = InkNumPad.show(ctx, "이동할 화 번호", "$lo–${hi}화", NumPadState.lengthFor(hi)) { v ->
                val i = e.find(v)
                if (i < 0 || stale()) {
                    TocText.missing(v)
                } else {
                    val found = e.numbers[i]
                    openEntry(i) { if (found != v) noteAfterJump(host, TocText.jumped(v, found)) }
                    null
                }
            }
            PanelRegistry.dialog(ctx, pad)
        }

        /** [검색]: titles containing the words, ignoring case and spaces (the system keyboard: Hangul needs it). */
        private fun askFilter() {
            ctx.prompt("목차 검색", query, "제목에 들어간 말") { q -> applyFilter(q) }
        }

        private fun applyFilter(raw: String) {
            val q = raw.trim()
            val norm = TocText.normalize(q)
            if (norm.isEmpty()) {
                clearFilter()
                return
            }
            val titles = normTitles ?: Array(n) { TocText.normalize(toc[it].title) }.also { normTitles = it }
            val out = IntList(64)
            for (i in 0 until n) if (titles[i].contains(norm)) out.add(i)
            query = q
            rows = out.toArray()
            noMatch.text = TocText.noMatch(q)
            noMatch.visibility = if (out.size == 0) View.VISIBLE else View.GONE
            renderHeader()
            adapter.notifyDataSetChanged()
            pager.showRow(0)
        }

        private fun clearFilter(showCurrent: Boolean = true) {
            if (rows == null) return
            rows = null
            query = ""
            noMatch.visibility = View.GONE
            renderHeader()
            adapter.notifyDataSetChanged()
            if (showCurrent) pager.showRow(current.coerceAtLeast(0), CURRENT_ROW)
        }

        val isFiltering: Boolean get() = rows != null

        fun clearFilterIfAny(): Boolean {
            if (rows == null) return false
            clearFilter()
            return true
        }

        /** Shows TOC entry [i] as the 4th row (a number of the gaps dialog). */
        private fun reveal(i: Int) {
            if (i < 0) return
            clearFilter(showCurrent = false)
            pager.showRow(i, CURRENT_ROW)
        }

        /** "빠진 화: 57, 120, 121 / 중복: 88화 (2번)"; a number shows its place in the list (the next entry for a gap). */
        private fun showGaps(e: Episodes) {
            val gaps = e.gaps()
            val dupes = e.dupes()
            var shown: AlertDialog? = null
            val sb = SpannableStringBuilder()
            fun link(text: String, target: Int) {
                val start = sb.length
                sb.append(text)
                sb.setSpan(object : ClickableSpan() {
                    override fun onClick(widget: View) {
                        shown?.dismiss()
                        reveal(e.find(target))
                    }

                    override fun updateDrawState(ds: TextPaint) {
                        ds.color = Ink.BLACK
                        ds.isUnderlineText = true
                    }
                }, start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            fun heading(text: String) {
                val start = sb.length
                sb.append(text)
                sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.append('\n')
            }
            if (gaps.isNotEmpty()) {
                heading("빠진 화 ${gaps.size}개")
                TocText.runs(gaps).forEachIndexed { k, r ->
                    if (k > 0) sb.append(", ")
                    link(TocText.runLabel(r), r.first)
                }
            }
            if (dupes.isNotEmpty()) {
                if (sb.isNotEmpty()) sb.append("\n\n")
                heading("중복 ${dupes.size}개")
                var k = 0
                for ((num, times) in dupes) {
                    if (k++ > 0) sb.append(", ")
                    link("${num}화 (${times}번)", num)
                }
            }
            val noteStart = sb.length
            sb.append("\n\n번호를 누르면 목차에서 그 자리를 보여 줍니다")
            sb.setSpan(ForegroundColorSpan(Ink.GRAY), noteStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            val text = ctx.label(sb, 16f).apply {
                setLineSpacing(0f, 1.35f)
                movementMethod = LinkMovementMethod.getInstance()
                highlightColor = Color.TRANSPARENT
            }
            val box = ctx.vertical { setPadding(ctx.dp(24), ctx.dp(8), ctx.dp(24), ctx.dp(8)) }
            box.addView(text, lp())
            val d = ctx.alert().setTitle("빠진 화 · 중복")
                .setView(ctx.einkScroll(box))
                .setPositiveButton("닫기", null)
                .showNoAnim()
            shown = d
            PanelRegistry.dialog(ctx, d)
        }

        /**
         * Anchored entries (EPUB 'ch05.xhtml#toc_5') are resolved on demand, never all up front: resolving every
         * entry converts almost every section of the book when page counts came from the cache (nothing converted
         * yet). First the current section's entries (▶ marker) and the rows on screen, then whatever is paged into
         * view; one list refresh per batch, and only when a page label or the marker actually changed.
         */
        private fun startResolving() {
            if (offs.none { it < 0 }) return
            val requested = BooleanArray(n)
            val initialFirst = if (current > CURRENT_ROW) current - CURRENT_ROW else 0
            var resolving: Job? = null
            lateinit var resolveVisible: Runnable
            fun resolve(indices: IntArray) {
                for (i in indices) requested[i] = true
                resolving = scope.launch {
                    val found = withContext(Dispatchers.Default) {
                        val out = arrayOfNulls<DocPosition>(indices.size)
                        for (k in indices.indices) {
                            if (!isActive) break
                            val i = indices[k]
                            out[k] = runCatching { doc.resolveToc(toc[i]) }.getOrNull() ?: DocPosition(secs[i], 0)
                        }
                        out
                    }
                    var changed = false
                    for (k in indices.indices) {
                        val p = found[k] ?: continue
                        val i = indices[k]
                        val before = labels[i] ?: tocPageLabel(secs[i], 0)
                        secs[i] = p.section
                        offs[i] = p.offset
                        labels[i] = null
                        if (tocPageLabel(p.section, p.offset) != before) changed = true
                    }
                    val newCurrent = currentIndex(secs, offs, here)
                    if (newCurrent != current) {
                        current = newCurrent
                        changed = true
                        renderHeader()
                        // Not paged by the user yet: keep the current chapter in view.
                        if (newCurrent >= 0 && rows == null && list.firstVisiblePosition == initialFirst) {
                            val sel = if (newCurrent > CURRENT_ROW) newCurrent - CURRENT_ROW else 0
                            if (sel != initialFirst) pager.showRow(newCurrent, CURRENT_ROW)
                        }
                    }
                    if (changed) adapter.notifyDataSetChanged()
                    resolving = null
                    list.post(resolveVisible) // rows paged in meanwhile
                }
            }
            resolveVisible = Runnable {
                if (resolving != null || !scope.isActive) return@Runnable
                val count = adapter.count
                if (count == 0) return@Runnable
                val first = list.firstVisiblePosition
                val last = if (list.childCount > 0) list.lastVisiblePosition else first + 20
                val want = IntList(32)
                for (p in (first - 2).coerceAtLeast(0)..(last + 6).coerceAtMost(count - 1)) {
                    val i = indexAt(p)
                    if (offs[i] < 0 && !requested[i]) want.add(i)
                }
                if (want.size > 0) resolve(want.toArray())
            }
            val first = IntList(32)
            for (i in 0 until n) if (offs[i] < 0 && secs[i] == here.section) first.add(i)
            for (i in initialFirst until minOf(n, initialFirst + 24)) if (offs[i] < 0 && secs[i] != here.section) first.add(i)
            if (first.size > 0) resolve(first.toArray())
            pager.onMoved = {
                list.removeCallbacks(resolveVisible)
                list.postDelayed(resolveVisible, 150)
            }
        }
    }

    private fun headerButton(text: String, onClick: () -> Unit): TextView = ctx.label(text, 14f).apply {
        gravity = Gravity.CENTER
        setPadding(ctx.dp(10), 0, ctx.dp(10), 0)
        // No pressed state: what the button does is the feedback (one e-ink update).
        background = ctx.borderBox(radiusDp = 3f)
        setOnClickListener { onClick() }
    }

    private fun infoLine(color: Int): TextView = ctx.label("", 14f, color = color, maxLines = 1).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(ctx.dp(16), 0, ctx.dp(16), 0)
    }

    private fun tocPageLabel(section: Int, offset: Int): String =
        PageLabel.pageOnly(runCatching { host.pageLabel(DocPosition(section, offset)) }.getOrNull())

    private fun tocRow(): LinearLayout = ctx.horizontal {
        minimumHeight = ctx.dp(52)
        background = pressableBackground()
        addView(ctx.label("▶", 12f).apply { tag = "marker"; setPadding(0, 0, ctx.dp(6), 0) })
        addView(ctx.label("", 17f, maxLines = 2).apply { tag = "title"; setPadding(0, ctx.dp(8), ctx.dp(8), ctx.dp(8)) }, lp(0, WRAP_CONTENT, 1f))
        addView(ctx.label("", 15f, color = Ink.GRAY).apply { tag = "page"; gravity = Gravity.END; minWidth = ctx.dp(44) })
    }

    // ------------------------------------------------------------------ 북마크

    private fun loadBookmarks(container: FrameLayout) {
        val keep = (pagers[1] as? InkPager)?.list?.firstVisiblePosition ?: 0
        pagers[1] = null
        container.removeAllViews()
        container.addView(ctx.emptyMessage("불러오는 중…"))
        val bookId = book.id
        scope.launch {
            val loaded = withContext(Dispatchers.IO) { runCatching { Library.bookmarks(bookId) }.getOrNull() }
            container.removeAllViews()
            if (loaded == null) {
                // Not "none": the user's bookmarks are still there. The tab label stays as it was.
                container.addView(retryMessage("북마크를 불러오지 못했습니다\n\n눌러서 다시 시도") { loadBookmarks(container) })
                return@launch
            }
            val list = loaded.sortedWith(compareBy({ it.section }, { it.offset }))
            tabLabels[1]?.text = if (list.isEmpty()) "북마크" else "북마크 ${list.size}"
            if (list.isEmpty()) {
                container.addView(ctx.emptyMessage(TocText.noBookmarks(Settings.app.bookmarkByTouch)))
                return@launch
            }
            val lv = ctx.einkListView()
            lv.adapter = object : BaseAdapter() {
                override fun getCount() = list.size
                override fun getItem(position: Int) = list[position]
                override fun getItemId(position: Int) = list[position].id
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val row = (convertView as? LinearLayout) ?: noteRow(2)
                    val b = list[position]
                    row.findViewWithTag<TextView>("text").text = b.snippet.replace('\n', ' ').trim().ifEmpty { "(내용 없음)" }
                    val note = row.findViewWithTag<TextView>("note")
                    note.visibility = if (b.note.isBlank()) View.GONE else View.VISIBLE
                    note.text = "메모: ${b.note}"
                    row.findViewWithTag<TextView>("meta").text =
                        "${pageOf(b.section, b.offset)}쪽  ·  ${Fmt.dateTime(b.createdAt)}"
                    return row
                }
            }
            lv.setOnItemClickListener { _, _, position, _ -> goAndClose(DocPosition(list[position].section, list[position].offset)) }
            lv.setOnItemLongClickListener { _, view, position, _ ->
                bookmarkMenu(view, list[position], container)
                true
            }
            container.addView(pagedList(1, lv), FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            // Reloaded after an edit: stay where the user was.
            if (keep > 0) (pagers[1] as? InkPager)?.showRow(keep)
        }
    }

    private fun bookmarkMenu(anchor: View, b: Bookmark, container: FrameLayout) {
        if (stale()) return
        trackedMenu(anchor, listOf(
            MenuItem("이동", R.drawable.ic_bookmark) { goAndClose(DocPosition(b.section, b.offset)) },
            MenuItem("메모 편집", R.drawable.ic_edit) { editBookmarkNote(b, b.note, container) },
            MenuItem("삭제", R.drawable.ic_delete) {
                scope.launch {
                    val ok = withContext(Dispatchers.IO) { runCatching { Library.deleteBookmark(b.id) }.isSuccess }
                    if (!ok) failed("삭제하지 못했습니다")
                    loadBookmarks(container)
                }
            },
        ))
    }

    /** The bookmark's memo editor; a failed save says so and opens again with the typed text (never lost). */
    private fun editBookmarkNote(b: Bookmark, initial: String, container: FrameLayout) {
        ctx.multilinePrompt("북마크 메모", initial, "메모", minLines = 3) { text ->
            scope.launch {
                val ok = withContext(Dispatchers.IO) { runCatching { Library.updateBookmarkNote(b.id, text.trim()) }.isSuccess }
                loadBookmarks(container)
                if (!ok && failed("저장하지 못했습니다")) editBookmarkNote(b, text, container)
            }
        }
    }

    /** A failed write's toast while the reader is alive (true then). */
    private fun failed(text: String): Boolean {
        if (ctx.isFinishing || ctx.isDestroyed) return false
        ctx.toast(text)
        return true
    }

    // ------------------------------------------------------------------ 인용문

    private fun loadQuotes(container: FrameLayout) {
        val keep = (pagers[2] as? InkPager)?.list?.firstVisiblePosition ?: 0
        pagers[2] = null
        container.removeAllViews()
        container.addView(ctx.emptyMessage("불러오는 중…"))
        val bookId = book.id
        scope.launch {
            val loaded = withContext(Dispatchers.IO) { runCatching { Library.quotes(bookId) }.getOrNull() }
            if (loaded == null) {
                // Not "none" (the user would think the quotes lost): the rows and the cache stay as they were; no
                // list is shown, so there is nothing to 모두 공유.
                quotes = emptyList()
                if (tab == 2) shareAll.visibility = View.GONE
                container.removeAllViews()
                container.addView(retryMessage("인용문을 불러오지 못했습니다\n\n눌러서 다시 시도") { loadQuotes(container) })
                return@launch
            }
            QuoteCache.put(bookId, loaded)
            setQuotes(loaded)
            showQuotes(container, keep)
        }
    }

    /** A failed load's message: a tap loads again. */
    private fun retryMessage(text: String, retry: () -> Unit): TextView =
        ctx.emptyMessage(text).apply { setOnClickListener { retry() } }

    /** New quote rows (a load or a recolour): reading order, the session sig, the filter kept while it applies. */
    private fun setQuotes(all: List<Quote>) {
        allQuotes = all.sortedWith(compareBy({ it.section }, { it.start }))
        sessionSig = sessionSig(host)
        quoteFilter = QuoteRows.keepFilter(quoteFilter, QuoteRows.styleCounts(allQuotes))
    }

    /** Builds the tab from [allQuotes] in one pass (one e-ink update): chips, list and pager, starting at row [keep]. */
    private fun showQuotes(container: FrameLayout, keep: Int) {
        val all = allQuotes
        val list = QuoteRows.filter(all, quoteFilter)
        quotes = list
        pagers[2] = null
        tabLabels[2]?.text = QuoteRows.tabLabel(list.size, all.size, quoteFilter != QuoteRows.ALL)
        if (tab == 2) shareAll.visibility = if (list.isNotEmpty()) View.VISIBLE else View.GONE
        container.removeAllViews()
        if (all.isEmpty()) {
            container.addView(ctx.emptyMessage("인용문이 없습니다\n\n본문을 길게 눌러 문장을 선택한 뒤\n'인용'을 누르세요"))
            return
        }
        val ink = QuoteLook.ink()
        val lv = ctx.einkListView()
        lv.adapter = object : BaseAdapter() {
            override fun getCount() = list.size
            override fun getItem(position: Int) = list[position]
            override fun getItemId(position: Int) = list[position].id
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val row = (convertView as? LinearLayout) ?: quoteRow(ink)
                bindQuote(row, list[position])
                return row
            }
        }
        // The swatch column is not a clickable child (InkPager's drag must see every DOWN): the row's one click
        // decides by where the touch went down.
        lv.setOnItemClickListener { _, view, position, _ ->
            val q = list.getOrNull(position) ?: return@setOnItemClickListener
            val tap = quoteDownRow === view && SystemClock.uptimeMillis() - quoteDownAt < TAP_CLICK_MS
            val x = if (tap) quoteDownX else -1f
            quoteDownRow = null
            if (QuoteRows.inSwatchColumn(x, view.width, ctx.dp(QuoteRows.SWATCH_COLUMN_DP))) recolour(swatchAnchor(view), q, container)
            else openQuote(q)
        }
        lv.setOnItemLongClickListener { _, view, position, _ ->
            quoteDownRow = null
            list.getOrNull(position)?.let { quoteMenu(view, it, container) }
            true
        }
        val col = ctx.vertical()
        if (QuoteRows.showChips(QuoteRows.styleCounts(all))) {
            col.addView(chipRow(all, container), lp(MATCH_PARENT, WRAP_CONTENT))
            col.addView(ctx.hairline())
        }
        col.addView(pagedList(2, lv), lp(MATCH_PARENT, 0, 1f))
        container.addView(col, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        if (keep > 0) (pagers[2] as? InkPager)?.showRow(keep)
    }

    /** `[전체 12] [● 5] [● 3] [가̲ 4]`: a tap filters in memory (no query) and rebuilds the list from the top. */
    private fun chipRow(all: List<Quote>, container: FrameLayout): View {
        val counts = QuoteRows.styleCounts(all)
        val row = ctx.horizontal { setPadding(ctx.dp(12), ctx.dp(8), ctx.dp(12), ctx.dp(8)) }
        val many = QuoteRows.chips(counts).size >= 4
        fun add(style: Int, chip: LinearLayout) {
            val selected = style == quoteFilter
            chip.background = ctx.borderBox(fill = if (selected) Ink.BLACK else Ink.WHITE, radiusDp = 3f)
            for (k in 0 until chip.childCount) (chip.getChildAt(k) as? TextView)?.setTextColor(if (selected) Ink.WHITE else Ink.BLACK)
            chip.setOnClickListener {
                if (quoteFilter == style || stale()) return@setOnClickListener
                quoteFilter = style
                showQuotes(container, 0)
            }
            // Up to 4 chips keep their width; more share the row (all six styles fit 360 dp without a scroll).
            val params = if (many) lp(0, ctx.dp(CHIP_DP), 1f) else lp(WRAP_CONTENT, ctx.dp(CHIP_DP))
            if (row.childCount > 0) params.leftMargin = ctx.dp(if (many) 4 else 6)
            if (many) chip.setPadding(ctx.dp(4), 0, ctx.dp(4), 0)
            row.addView(chip, params)
        }
        add(QuoteRows.ALL, chip().apply { addView(ctx.label(QuoteRows.allChip(all.size), 14f, maxLines = 1)) })
        val ink = QuoteLook.ink()
        for ((style, n) in QuoteRows.chips(counts)) {
            add(style, chip().apply {
                contentDescription = "${QuoteStyles.label(style)} $n"
                addView(QuoteSwatch(ctx, style, ROW_SWATCH_DP, ink))
                addView(ctx.label("$n", 14f, maxLines = 1).apply { setPadding(ctx.dp(if (many) 3 else 6), 0, 0, 0) })
            })
        }
        return row
    }

    private fun chip(): LinearLayout = ctx.horizontal {
        gravity = Gravity.CENTER
        minimumWidth = ctx.dp(44)
        setPadding(ctx.dp(12), 0, ctx.dp(12), 0)
    }

    private fun swatchAnchor(row: View): View = row.findViewWithTag("swatchCol") ?: row

    /** A quote whose place changed goes by its fraction (the offsets point at other text); others go to the offset. */
    private fun openQuote(q: Quote) {
        val notes = host as? NoteJumpHost
        if (placeChanged(q) && notes != null) {
            dialog.dismiss()
            if (!stale()) notes.openNote(ReaderJump(q.section, q.start, q.end, q.frac, q.sig, q.text.take(ReaderJump.ANCHOR_MAX)))
        } else goAndClose(DocPosition(q.section, q.start))
    }

    /** "· 위치 바뀜" (PLAN K2): the reader's rule, with the anchor checked when the quote's section is shown. */
    private fun placeChanged(q: Quote): Boolean =
        QuoteRows.placeChanged(q.sig, sessionSig, if (sessionSig != null && q.sig != sessionSig) anchorMatch(host, q) else null)

    /**
     * The palette at [anchor] (the row's swatch column) → IO `updateQuoteStyle` + reload → the page's highlights of
     * that section (only while the book is still the open one) → the tab rebuilt in place. No confirm.
     */
    private fun recolour(anchor: View, q: Quote, container: FrameLayout) {
        if (stale()) return
        QuotePalette.show(anchor, QuoteStyles.of(q.style)) { s ->
            if (s == q.style || stale()) return@show
            val keep = (pagers[2] as? InkPager)?.list?.firstVisiblePosition ?: 0
            // Not [scope]: closing the dialog mid-write must still recolour the page and the cache.
            MainScope().launch {
                val all = withContext(Dispatchers.IO) {
                    runCatching {
                        Library.updateQuoteStyle(q.id, s)
                        Library.quotes(book.id)
                    }.getOrNull()
                }
                if (all == null) {
                    if (!ctx.isFinishing && !ctx.isDestroyed) ctx.toast("색을 바꾸지 못했습니다")
                    return@launch
                }
                // The last-used style follows every palette pick, a recolour included (N §7.1.3).
                runCatching { Settings.raw().edit().putInt(PREF_QUOTE_STYLE, s).apply() }
                if (!stale()) refreshQuoteHighlights(host, q.section, all) else QuoteCache.put(book.id, all)
                if (!dialog.isShowing) return@launch
                setQuotes(all)
                showQuotes(container, keep)
            }
        }
    }

    private fun quoteMenu(anchor: View, q: Quote, container: FrameLayout) {
        if (stale()) return
        trackedMenu(anchor, listOf(
            MenuItem("복사", R.drawable.ic_content_copy) { TextActions.copy(ctx, q.text) },
            MenuItem("공유", R.drawable.ic_share) { TextActions.share(ctx, quoteShareText(q), book.title) },
            MenuItem("색 바꾸기", R.drawable.ic_ink_highlighter) { recolour(swatchAnchor(anchor), q, container) },
            MenuItem("메모", R.drawable.ic_edit) { editQuoteNote(q, q.note, container) },
            MenuItem("삭제", R.drawable.ic_delete) {
                ctx.confirm("인용문 삭제", "이 인용문을 삭제할까요?", "삭제") {
                    scope.launch {
                        val (ok, remaining) = withContext(Dispatchers.IO) {
                            runCatching { Library.deleteQuote(q.id) }.isSuccess to
                                runCatching { Library.quotes(book.id) }.getOrNull()
                        }
                        if (!ok) failed("삭제하지 못했습니다")
                        if (remaining != null && !stale()) refreshQuoteHighlights(host, q.section, remaining)
                        loadQuotes(container)
                    }
                }
            },
        ))
    }

    /** The quote's memo editor; a failed save says so and opens again with the typed text (never lost). */
    private fun editQuoteNote(q: Quote, initial: String, container: FrameLayout) {
        ctx.multilinePrompt("인용문 메모", initial, "메모", minLines = 3) { text ->
            scope.launch {
                val ok = withContext(Dispatchers.IO) { runCatching { Library.updateQuoteNote(q.id, text.trim()) }.isSuccess }
                loadQuotes(container)
                if (!ok && failed("저장하지 못했습니다")) editQuoteNote(q, text, container)
            }
        }
    }

    private fun quoteShareText(q: Quote): String {
        val sb = StringBuilder()
        sb.append('“').append(q.text.trim()).append('”')
        if (q.note.isNotBlank()) sb.append("\n메모: ").append(q.note.trim())
        sb.append("\n— ").append(book.title)
        if (book.author.isNotBlank()) sb.append(", ").append(book.author)
        return sb.toString()
    }

    /** 모두 공유 of the listed (chip-filtered) quotes, tagged "[초록]" when they use ≥ 2 styles. */
    private fun shareAllQuotes() {
        if (quotes.isEmpty()) {
            ctx.toast("인용문이 없습니다")
            return
        }
        val text = QuoteRows.shareAll(book.title, book.author, quotes, { pageOf(it.section, it.start) }, TextActions.SHARE_MAX_CHARS)
        TextActions.share(ctx, text, book.title)
    }

    // ------------------------------------------------------------------ rows

    private fun noteRow(textLines: Int): LinearLayout = ctx.vertical {
        background = pressableBackground()
        addView(noteTexts(textLines, ctx.dp(16)), lp())
        addView(ctx.hairline())
    }

    private fun noteTexts(textLines: Int, padRight: Int): LinearLayout {
        val inner = ctx.vertical { setPadding(ctx.dp(16), ctx.dp(12), padRight, ctx.dp(12)) }
        inner.addView(ctx.label("", 16f, maxLines = textLines).apply { tag = "text"; setLineSpacing(0f, 1.2f) }, lp())
        inner.addView(ctx.label("", 14f, color = Ink.GRAY, maxLines = 3).apply { tag = "note"; setPadding(0, ctx.dp(6), 0, 0) }, lp())
        inner.addView(ctx.label("", 13f, color = Ink.GRAY).apply { tag = "meta"; setPadding(0, ctx.dp(6), 0, 0) }, lp())
        return inner
    }

    /**
     * `[text / 메모 / meta (weight 1)] [swatch column 48 dp]`: the swatch at the top, beside the first text line. The
     * column is a plain (not clickable) view; the row's touch listener only records where the touch went down.
     */
    private fun quoteRow(ink: Boolean): LinearLayout = ctx.vertical {
        background = pressableBackground()
        val line = ctx.horizontal { gravity = Gravity.TOP }
        line.addView(noteTexts(4, 0), lp(0, WRAP_CONTENT, 1f))
        val column = FrameLayout(ctx).apply { tag = "swatchCol"; contentDescription = "색 바꾸기" }
        column.addView(QuoteSwatch(ctx, QuoteStyles.YELLOW, ROW_SWATCH_DP, ink).apply { tag = "swatch" },
            FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = ctx.dp(SWATCH_TOP_DP) })
        line.addView(column, lp(ctx.dp(QuoteRows.SWATCH_COLUMN_DP), MATCH_PARENT))
        addView(line, lp())
        addView(ctx.hairline())
        setOnTouchListener { v, e ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                quoteDownRow = v
                quoteDownX = e.x
                quoteDownAt = e.downTime
            }
            false
        }
    }

    private fun bindQuote(row: LinearLayout, q: Quote) {
        row.findViewWithTag<TextView>("text").text = q.text.trim()
        val note = row.findViewWithTag<TextView>("note")
        note.visibility = if (q.note.isBlank()) View.GONE else View.VISIBLE
        note.text = "메모: ${q.note}"
        val meta = "${pageOf(q.section, q.start)}쪽  ·  ${Fmt.dateTime(q.createdAt)}"
        row.findViewWithTag<TextView>("meta").text = if (placeChanged(q)) meta + QuoteRows.STALE_SUFFIX else meta
        val swatch = row.findViewWithTag<QuoteSwatch>("swatch")
        val style = QuoteStyles.of(q.style)
        if (swatch.style != style) {
            swatch.style = style
            swatch.invalidate()
        }
    }

    private fun trackedMenu(anchor: View, items: List<MenuItem>) {
        PanelRegistry.popup(ctx, ctx.popupMenu(anchor, items))
    }

    private fun pageOf(section: Int, offset: Int): String =
        PageLabel.pageOnly(runCatching { host.pageLabel(DocPosition(section, offset)) }.getOrNull()).ifEmpty { "-" }

    companion object {
        /** [지금] and a jump show the entry as the 4th row: the three above give context. */
        const val CURRENT_ROW = 3
        const val HEADER_DP = 40
        const val INFO_DP = 26
        /** A message after a jump waits for the dialog to go (the reader's window gets the focus back). */
        const val NOTE_DELAY_MS = 300L
        /** Filter chips are 44 dp tall (N §7.2). */
        const val CHIP_DP = 44
        /** A row click this soon after its DOWN came from that touch (a tap is released before the long press). */
        const val TAP_CLICK_MS = 1_500L
        /** The last-used quote style (N §7.1.3; the selection popup's default). */
        const val PREF_QUOTE_STYLE = "extras.quoteStyle"
        /** Swatches in rows and chips: a 12 dp dot (QuoteSwatch draws the ink sample at its own row size). */
        const val ROW_SWATCH_DP = 12
        /** The row swatch's top: level with the first 16 sp text line under the row's 12 dp padding. */
        const val SWATCH_TOP_DP = 16

        /**
         * [all] is the book's complete, freshly loaded quote list: stores it in [QuoteCache] and re-sends the quote
         * highlights of [section] to the page (owner "quotes").
         */
        fun refreshQuoteHighlights(host: ReaderHost, section: Int, all: List<Quote>) {
            runCatching { QuoteCache.put(host.book.id, all) }
            // A quote whose place changed is not drawn (its offsets point at other text): the reader's rule (PLAN K2).
            val sig = sessionSig(host)
            val hl = QuoteHighlights.forSection(all, section, sig) { anchorMatch(host, it) }
            runCatching { host.setHighlights("quotes", section, hl) }
        }

        /** The open session's NoteSig ("" for EPUB) through [NotePlaceHost]; null when the host has no places. */
        fun sessionSig(host: ReaderHost): String? =
            (host as? NotePlaceHost)?.let { h -> runCatching { h.notePlace(host.currentPosition()).sig }.getOrNull() }

        /**
         * Whether [q]'s text is still at its offset (`JumpAnchor.matches`, ≤ 24 visible chars): the reader's own check
         * on any section it has laid out ([NotePlaceHost.quoteAnchorMatch], the set the page draws), else when its
         * section is the one laid out; null when that text is not at hand.
         */
        fun anchorMatch(host: ReaderHost, q: Quote): Boolean? {
            (host as? NotePlaceHost)?.let { h -> runCatching { h.quoteAnchorMatch(q) }.getOrNull()?.let { return it } }
            val layout = runCatching { host.currentLayout?.takeIf { host.currentPosition().section == q.section } }.getOrNull()
                ?: return null
            val text = layout.content.text
            if (q.start < 0 || q.start >= text.length) return false
            return JumpAnchor.matches(text, q.start, q.text)
        }

        /**
         * A message after a jump from a dialog, once the reader has the focus back: a toast while a dialog has the
         * focus is the platform's fading one (an e-ink smear), the in-window box needs the reader's window.
         */
        fun noteAfterJump(host: ReaderHost, text: String) {
            val ctx = host.activity
            host.pageView.postDelayed({ if (!ctx.isFinishing && !ctx.isDestroyed) ctx.toast(text) }, NOTE_DELAY_MS)
        }

        /** [currentIndex] over [doc]'s TOC, anchored entries taken at their section start (the go-to dialog). */
        fun currentIndex(doc: BookDocument, here: DocPosition): Int {
            val toc = doc.toc
            return currentIndex(IntArray(toc.size) { toc[it].section }, IntArray(toc.size) { if (toc[it].anchor == null) toc[it].offset else 0 }, here)
        }

        /** Index of the TOC entry the reading position [here] is in: the last one at or before it; -1 if none. */
        fun currentIndex(secs: IntArray, offs: IntArray, here: DocPosition): Int {
            var best = -1
            for (i in secs.indices) {
                val s = secs[i]
                val o = offs[i].coerceAtLeast(0)
                if (s < here.section || (s == here.section && o <= here.offset)) best = i
            }
            return best
        }
    }
}

/**
 * Waits a moment for the open book's [Episodes] before a panel shows (T1-1), so it opens complete: one e-ink update
 * instead of a header that changes right after. Main thread only.
 */
internal object EpisodeWait {
    /** Longest wait for a first parse before the panel shows without it (≈ 20 ms for 2,000 titles). */
    const val PANEL_WAIT_MS = 200L

    private var pending: Job? = null

    /**
     * Runs [ready] once with the episodes — at once when already parsed — or, after [waitMs], with null and
     * `timedOut` = true (the caller then asks [BookInsightsHost.episodes] again to fill in a late result). A host
     * without [BookInsightsHost] gets `ready(null, false)` at once. Closing the book ([ReaderPanels.dismissAll]) drops
     * a pending wait; a second request while one waits is dropped (a double tap opens one panel).
     */
    fun run(host: ReaderHost, waitMs: Long, ready: (episodes: Episodes?, timedOut: Boolean) -> Unit) {
        if (pending?.isActive == true) return
        val insights = host as? BookInsightsHost
        if (insights == null) {
            ready(null, false)
            return
        }
        val job = Job()
        val handler = Handler(Looper.getMainLooper())
        var sync = true
        var arrived = false
        var syncResult: Episodes? = null
        val timeout = Runnable {
            if (!job.isActive) return@Runnable
            job.complete()
            ready(null, true)
        }
        insights.episodes { e ->
            if (arrived) return@episodes
            arrived = true
            if (sync) {
                syncResult = e
                return@episodes
            }
            handler.removeCallbacks(timeout)
            // Posted: the host's listener catches exceptions, and a failing panel must not be silent.
            handler.post {
                if (!job.isActive) return@post
                job.complete()
                ready(e, false)
            }
        }
        sync = false
        if (arrived) {
            ready(syncResult, false)
            return
        }
        pending = job
        PanelRegistry.job(host.activity, job)
        if (waitMs <= 0L) handler.post(timeout) else handler.postDelayed(timeout, waitMs)
    }
}

/** Hardware page keys of the paged lists (TOC, 북마크, 인용문, search results). Pure; unit-tested. */
internal object ListKeys {
    /**
     * +1 next page, -1 previous, 0 not a page key. A key bound to 다음 / 이전 페이지 (or 화) pages; a key bound to
     * "없음(시스템에 맡김)" stays the system's; otherwise the volume keys (when they turn pages), PAGE_UP / DOWN and the
     * learned page keys, like the library. BACK / ESCAPE / HOME are never taken.
     */
    fun direction(keyCode: Int, app: AppSettings): Int {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE || keyCode == KeyEvent.KEYCODE_HOME) return 0
        when (if (app.keyBindings.isEmpty()) null else app.keyBindings[keyCode]) {
            TapAction.NEXT, TapAction.NEXT_CHAPTER -> return 1
            TapAction.PREV, TapAction.PREV_CHAPTER -> return -1
            TapAction.NONE -> return 0
            else -> {}
        }
        return LibraryText.keyDirection(keyCode, app.volumeKeysTurn, app.invertVolumeKeys, app.nextPageKeys, app.prevPageKeys)
    }
}

/** Texts of the TOC tab (header, filter, gaps dialog, jump notes). Pure; unit-tested. */
internal object TocText {
    /**
     * Header summary: "540화 · 지금 123화" when the episodes are usable ([maxNumber] ≥ 0; [currentNumber] -1 leaves out
     * "지금"), else "목차 612개 · 지금 87번째" ([current] = TOC index, -1 before the first entry: "목차 612개").
     */
    fun summary(count: Int, current: Int, maxNumber: Int, currentNumber: Int): String = when {
        maxNumber >= 0 && currentNumber >= 0 -> "${maxNumber}화 · 지금 ${currentNumber}화"
        maxNumber >= 0 -> "${maxNumber}화"
        current >= 0 -> "목차 ${count}개 · 지금 ${current + 1}번째"
        else -> "목차 ${count}개"
    }

    /** "남은 시간  이 화 3분 · 책 7시간" (either part may be missing); null when both are unknown. */
    fun timeLeft(episodeMinutes: Int?, bookMinutes: Int?): String? {
        val parts = ArrayList<String>(2)
        if (episodeMinutes != null) parts += "이 화 ${ReaderFormat.duration(episodeMinutes)}"
        if (bookMinutes != null) parts += "책 ${ReaderFormat.duration(bookMinutes)}"
        return if (parts.isEmpty()) null else "남은 시간  " + parts.joinToString(" · ")
    }

    /** "빠진 화 3개 · 중복 1개 ›" (either part alone), or null when there is nothing to report. */
    fun gapsLine(gaps: Int, dupes: Int): String? {
        val parts = ArrayList<String>(2)
        if (gaps > 0) parts += "빠진 화 ${gaps}개"
        if (dupes > 0) parts += "중복 ${dupes}개"
        return if (parts.isEmpty()) null else parts.joinToString(" · ") + " ›"
    }

    /** "'외전' 12개" while the list is filtered. */
    fun filterSummary(query: String, count: Int): String = "‘$query’ ${count}개"

    /** "'외전'이 들어간 제목이 없습니다". */
    fun noMatch(query: String): String = "‘$query’${subjectParticle(query)} 들어간 제목이 없습니다"

    /** Lowercase without any whitespace: titles are matched ignoring case and spaces. */
    fun normalize(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) if (!Character.isWhitespace(c) && c != '　' && c != ' ') sb.append(c.lowercaseChar())
        return sb.toString()
    }

    /** Ascending [nums] as runs: 3 or more consecutive numbers make one range ("120–125"); pairs stay two numbers. */
    fun runs(nums: List<Int>): List<IntRange> {
        val out = ArrayList<IntRange>()
        var i = 0
        while (i < nums.size) {
            var j = i
            while (j + 1 < nums.size && nums[j + 1] == nums[j] + 1) j++
            if (j - i >= 2) {
                out += nums[i]..nums[j]
            } else {
                for (k in i..j) out += nums[k]..nums[k]
            }
            i = j + 1
        }
        return out
    }

    /** "57" or "120–125". */
    fun runLabel(r: IntRange): String = if (r.first == r.last) "${r.first}" else "${r.first}–${r.last}"

    /** The go-to dialog's [화] hint: "1–540화 · 지금 123화" ([current] -1: without "지금"). */
    fun episodeHint(min: Int, max: Int, current: Int): String =
        "${min.coerceAtLeast(0)}–${max}화" + if (current >= 0) " · 지금 ${current}화" else ""

    /** After a jump to the next higher episode: "57화가 없어 58화로 이동했습니다". */
    fun jumped(asked: Int, found: Int): String = "${asked}화가 없어 ${found}화로 이동했습니다"

    /** No such episode and none above it: "541화가 없습니다". */
    fun missing(n: Int): String = "${n}화가 없습니다"

    /** The bookmark tab's empty text; the corner tap is mentioned only when it is on ("북마크 모서리 터치"). */
    fun noBookmarks(byTouch: Boolean): String =
        if (byTouch) "북마크가 없습니다\n\n화면 오른쪽 위 모서리를 누르거나\n메뉴에서 북마크를 추가하세요"
        else "북마크가 없습니다\n\n메뉴에서 북마크를 추가하세요"

    /**
     * 이 / 가 after [word]: by the final consonant of a last Hangul syllable, or of a last digit as read in Korean
     * (영 일 삼 육 칠 팔 end in one); "이(가)" for anything else.
     */
    fun subjectParticle(word: String): String {
        val c = word.trimEnd().lastOrNull() ?: return "이(가)"
        return when {
            c in '가'..'힣' -> if ((c - '가') % 28 != 0) "이" else "가"
            c in '0'..'9' -> if (c in "013678") "이" else "가"
            else -> "이(가)"
        }
    }
}
