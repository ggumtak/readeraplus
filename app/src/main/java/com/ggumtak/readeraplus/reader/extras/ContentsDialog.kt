package com.ggumtak.readeraplus.reader.extras

import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import com.ggumtak.readeraplus.R
import com.ggumtak.readeraplus.data.Bookmark
import com.ggumtak.readeraplus.data.Library
import com.ggumtak.readeraplus.data.Quote
import com.ggumtak.readeraplus.format.BookFormat
import com.ggumtak.readeraplus.format.DocPosition
import com.ggumtak.readeraplus.reader.ReaderHost
import com.ggumtak.readeraplus.render.Highlight
import com.ggumtak.readeraplus.render.HighlightKind
import com.ggumtak.readeraplus.ui.kit.Ink
import com.ggumtak.readeraplus.ui.kit.MenuItem
import com.ggumtak.readeraplus.ui.kit.confirm
import com.ggumtak.readeraplus.ui.kit.dp
import com.ggumtak.readeraplus.ui.kit.einkListView
import com.ggumtak.readeraplus.ui.kit.fullScreenDialog
import com.ggumtak.readeraplus.ui.kit.hairline
import com.ggumtak.readeraplus.ui.kit.horizontal
import com.ggumtak.readeraplus.ui.kit.label
import com.ggumtak.readeraplus.ui.kit.lp
import com.ggumtak.readeraplus.ui.kit.popupMenu
import com.ggumtak.readeraplus.ui.kit.pressableBackground
import com.ggumtak.readeraplus.ui.kit.toast
import com.ggumtak.readeraplus.ui.kit.vertical
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Full-screen 목차 · 북마크 · 인용문 dialog. */
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
    private val tabLabels = arrayOfNulls<TextView>(3)
    private val tabBars = arrayOfNulls<View>(3)
    private val tabViews = arrayOfNulls<View>(3)
    private var tab = initialTab.coerceIn(0, 2)
    private lateinit var shareAll: View
    private var quotes: List<Quote> = emptyList()

    fun show() {
        val root = ctx.vertical { setBackgroundColor(Ink.WHITE) }
        // toolbar
        val bar = ctx.horizontal { minimumHeight = ctx.dp(56); setPadding(ctx.dp(4), 0, ctx.dp(4), 0) }
        bar.addView(ctx.flatIcon(R.drawable.ic_arrow_back, "뒤로") { dialog.dismiss() })
        bar.addView(ctx.label(book.title, 19f, bold = true, maxLines = 1).apply { setPadding(ctx.dp(12), 0, ctx.dp(8), 0) }, lp(0, WRAP_CONTENT, 1f))
        shareAll = ctx.flatIcon(R.drawable.ic_share, "인용문 모두 공유") { shareAllQuotes() }.apply { visibility = View.GONE }
        bar.addView(shareAll)
        root.addView(bar, lp())
        // tabs
        val tabs = ctx.horizontal()
        listOf("목차", "북마크", "인용문").forEachIndexed { i, name ->
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
            tabs.addView(cell, lp(0, WRAP_CONTENT, 1f))
        }
        root.addView(tabs, lp())
        root.addView(ctx.hairline())
        root.addView(body, lp(MATCH_PARENT, 0, 1f))
        dialog = ctx.fullScreenDialog(root)
        dialog.setOnDismissListener { scope.cancel() }
        dialog.pageKeysScroll({ currentList() })
        select(tab)
        dialog.show()
        PanelRegistry.dialog(ctx, dialog)
    }

    /** The list of the selected tab (bookmarks / quotes wrap theirs in a FrameLayout), or null while loading. */
    private fun currentList(): ListView? {
        val v = tabViews[tab] ?: return null
        if (v is ListView) return v
        val g = v as? ViewGroup ?: return null
        for (i in 0 until g.childCount) (g.getChildAt(i) as? ListView)?.let { return it }
        return null
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
        tab = i
        for (k in 0..2) {
            val sel = k == i
            tabLabels[k]?.typeface = if (sel) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            tabLabels[k]?.setTextColor(if (sel) Ink.BLACK else Ink.GRAY)
            tabBars[k]?.setBackgroundColor(if (sel) Ink.BLACK else Color.TRANSPARENT)
        }
        val v = tabViews[i] ?: when (i) {
            0 -> buildToc()
            1 -> FrameLayout(ctx).also { loadBookmarks(it) }
            else -> FrameLayout(ctx).also { loadQuotes(it) }
        }.also { tabViews[i] = it }
        body.removeAllViews()
        body.addView(v, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        shareAll.visibility = if (i == 2 && quotes.isNotEmpty()) View.VISIBLE else View.GONE
    }

    private fun goAndClose(pos: DocPosition) {
        dialog.dismiss()
        if (!stale()) host.goTo(pos, remember = true)
    }

    // ------------------------------------------------------------------ 목차

    private fun buildToc(): View {
        val doc = host.document ?: return ctx.emptyMessage("문서를 여는 중입니다…")
        val toc = doc.toc
        if (toc.isEmpty()) {
            val hint = if (doc.format == BookFormat.TXT) "\n\n읽기 설정에서 '챕터 자동 인식'을 켜거나\n챕터 규칙(정규식)을 추가해 보세요" else ""
            return ctx.emptyMessage("이 문서에는 목차가 없습니다$hint")
        }
        val n = toc.size
        val secs = IntArray(n) { toc[it].section }
        // -1 = not resolved yet (anchor), shown with the section start meanwhile.
        val offs = IntArray(n) { if (toc[it].anchor == null) toc[it].offset.coerceAtLeast(0) else -1 }
        val labels = arrayOfNulls<String>(n)
        val here = host.currentPosition()
        var current = currentIndex(secs, offs, here)

        val list = ctx.einkListView()
        val adapter = object : BaseAdapter() {
            override fun getCount() = n
            override fun getItem(position: Int) = toc[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val row = (convertView as? LinearLayout) ?: tocRow()
                val e = toc[position]
                val marker = row.findViewWithTag<TextView>("marker")
                val title = row.findViewWithTag<TextView>("title")
                val page = row.findViewWithTag<TextView>("page")
                val cur = position == current
                row.setPadding(ctx.dp(8) + ctx.dp(16) * (e.level - 1).coerceIn(0, 6), 0, ctx.dp(16), 0)
                marker.visibility = if (cur) View.VISIBLE else View.INVISIBLE
                title.text = e.title.ifBlank { "(제목 없음)" }
                title.typeface = if (cur) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                val lbl = labels[position] ?: tocPageLabel(secs[position], offs[position].coerceAtLeast(0))
                    .also { if (offs[position] >= 0) labels[position] = it }
                page.text = lbl
                return row
            }
        }
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ ->
            val off = offs[position]
            if (off >= 0) {
                goAndClose(DocPosition(secs[position], off))
            } else {
                scope.launch {
                    val p = withContext(Dispatchers.Default) { runCatching { doc.resolveToc(toc[position]) }.getOrNull() }
                    goAndClose(p ?: DocPosition(secs[position], 0))
                }
            }
        }
        val initialFirst = if (current > 3) current - 3 else 0
        if (initialFirst > 0) list.setSelection(initialFirst)

        // Anchored entries (EPUB 'ch05.xhtml#toc_5') are resolved on demand, never all up front: resolving every
        // entry converts almost every section of the book when page counts came from the cache (nothing converted
        // yet). First the current section's entries (▶ marker) and the rows on screen, then whatever is scrolled
        // into view; one list refresh per batch, and only when a page label or the marker actually changed.
        if (offs.any { it < 0 }) {
            val requested = BooleanArray(n)
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
                        // Not scrolled by the user yet: keep the current chapter in view.
                        if (newCurrent >= 0 && list.firstVisiblePosition == initialFirst) {
                            val sel = if (newCurrent > 3) newCurrent - 3 else 0
                            if (sel != initialFirst) list.setSelection(sel)
                        }
                    }
                    if (changed) adapter.notifyDataSetChanged()
                    resolving = null
                    list.post(resolveVisible) // rows scrolled in meanwhile
                }
            }
            resolveVisible = Runnable {
                if (resolving != null || !scope.isActive) return@Runnable
                val first = list.firstVisiblePosition
                val last = if (list.childCount > 0) list.lastVisiblePosition else first + 20
                val want = IntList(32)
                for (i in (first - 2).coerceAtLeast(0)..(last + 6).coerceAtMost(n - 1)) {
                    if (offs[i] < 0 && !requested[i]) want.add(i)
                }
                if (want.size > 0) resolve(want.toArray())
            }
            val first = IntList(32)
            for (i in 0 until n) if (offs[i] < 0 && secs[i] == here.section) first.add(i)
            for (i in initialFirst until minOf(n, initialFirst + 24)) if (offs[i] < 0 && secs[i] != here.section) first.add(i)
            if (first.size > 0) resolve(first.toArray())
            list.setOnScrollListener(object : AbsListView.OnScrollListener {
                override fun onScrollStateChanged(view: AbsListView, scrollState: Int) {}
                override fun onScroll(view: AbsListView, firstVisible: Int, visibleCount: Int, totalCount: Int) {
                    list.removeCallbacks(resolveVisible)
                    list.postDelayed(resolveVisible, 150)
                }
            })
        }
        return list
    }

    private fun tocPageLabel(section: Int, offset: Int): String =
        PageLabel.pageOnly(runCatching { host.pageLabel(DocPosition(section, offset)) }.getOrNull())

    private fun currentIndex(secs: IntArray, offs: IntArray, here: DocPosition): Int {
        var best = -1
        for (i in secs.indices) {
            val s = secs[i]
            val o = offs[i].coerceAtLeast(0)
            if (s < here.section || (s == here.section && o <= here.offset)) best = i
        }
        return best
    }

    private fun tocRow(): LinearLayout = ctx.horizontal {
        minimumHeight = ctx.dp(52)
        background = pressableBackground()
        addView(ctx.label("▶", 12f).apply { tag = "marker"; setPadding(0, 0, ctx.dp(6), 0) })
        addView(ctx.label("", 17f, maxLines = 2).apply { tag = "title"; setPadding(0, ctx.dp(8), ctx.dp(8), ctx.dp(8)) }, lp(0, WRAP_CONTENT, 1f))
        addView(ctx.label("", 15f, color = Ink.GRAY).apply { tag = "page"; gravity = Gravity.END; minWidth = ctx.dp(44) })
    }

    // ------------------------------------------------------------------ 북마크

    private fun loadBookmarks(container: FrameLayout) {
        container.removeAllViews()
        container.addView(ctx.emptyMessage("불러오는 중…"))
        val bookId = book.id
        scope.launch {
            val list = withContext(Dispatchers.IO) { runCatching { Library.bookmarks(bookId) }.getOrDefault(emptyList()) }
                .sortedWith(compareBy({ it.section }, { it.offset }))
            tabLabels[1]?.text = if (list.isEmpty()) "북마크" else "북마크 ${list.size}"
            container.removeAllViews()
            if (list.isEmpty()) {
                container.addView(ctx.emptyMessage("북마크가 없습니다\n\n화면 오른쪽 위 모서리를 누르거나\n메뉴에서 북마크를 추가하세요"))
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
            container.addView(lv, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
    }

    private fun bookmarkMenu(anchor: View, b: Bookmark, container: FrameLayout) {
        if (stale()) return
        trackedMenu(anchor, listOf(
            MenuItem("이동", R.drawable.ic_bookmark) { goAndClose(DocPosition(b.section, b.offset)) },
            MenuItem("메모 편집", R.drawable.ic_edit) {
                ctx.multilinePrompt("북마크 메모", b.note, "메모", minLines = 3) { text ->
                    scope.launch {
                        withContext(Dispatchers.IO) { runCatching { Library.updateBookmarkNote(b.id, text.trim()) } }
                        loadBookmarks(container)
                    }
                }
            },
            MenuItem("삭제", R.drawable.ic_delete) {
                scope.launch {
                    withContext(Dispatchers.IO) { runCatching { Library.deleteBookmark(b.id) } }
                    loadBookmarks(container)
                }
            },
        ))
    }

    // ------------------------------------------------------------------ 인용문

    private fun loadQuotes(container: FrameLayout) {
        container.removeAllViews()
        container.addView(ctx.emptyMessage("불러오는 중…"))
        val bookId = book.id
        scope.launch {
            val loaded = withContext(Dispatchers.IO) { runCatching { Library.quotes(bookId) }.getOrNull() }
            if (loaded != null) QuoteCache.put(bookId, loaded)
            val list = loaded.orEmpty().sortedWith(compareBy({ it.section }, { it.start }))
            quotes = list
            tabLabels[2]?.text = if (list.isEmpty()) "인용문" else "인용문 ${list.size}"
            if (tab == 2) shareAll.visibility = if (list.isNotEmpty()) View.VISIBLE else View.GONE
            container.removeAllViews()
            if (list.isEmpty()) {
                container.addView(ctx.emptyMessage("인용문이 없습니다\n\n본문을 길게 눌러 문장을 선택한 뒤\n'인용'을 누르세요"))
                return@launch
            }
            val lv = ctx.einkListView()
            lv.adapter = object : BaseAdapter() {
                override fun getCount() = list.size
                override fun getItem(position: Int) = list[position]
                override fun getItemId(position: Int) = list[position].id
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val row = (convertView as? LinearLayout) ?: noteRow(4)
                    val q = list[position]
                    row.findViewWithTag<TextView>("text").text = q.text.trim()
                    val note = row.findViewWithTag<TextView>("note")
                    note.visibility = if (q.note.isBlank()) View.GONE else View.VISIBLE
                    note.text = "메모: ${q.note}"
                    row.findViewWithTag<TextView>("meta").text = "${pageOf(q.section, q.start)}쪽  ·  ${Fmt.dateTime(q.createdAt)}"
                    return row
                }
            }
            lv.setOnItemClickListener { _, _, position, _ -> goAndClose(DocPosition(list[position].section, list[position].start)) }
            lv.setOnItemLongClickListener { _, view, position, _ ->
                quoteMenu(view, list[position], container)
                true
            }
            container.addView(lv, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
    }

    private fun quoteMenu(anchor: View, q: Quote, container: FrameLayout) {
        if (stale()) return
        trackedMenu(anchor, listOf(
            MenuItem("복사", R.drawable.ic_content_copy) { TextActions.copy(ctx, q.text) },
            MenuItem("공유", R.drawable.ic_share) { TextActions.share(ctx, quoteShareText(q), book.title) },
            MenuItem("메모", R.drawable.ic_edit) {
                ctx.multilinePrompt("인용문 메모", q.note, "메모", minLines = 3) { text ->
                    scope.launch {
                        withContext(Dispatchers.IO) { runCatching { Library.updateQuoteNote(q.id, text.trim()) } }
                        loadQuotes(container)
                    }
                }
            },
            MenuItem("삭제", R.drawable.ic_delete) {
                ctx.confirm("인용문 삭제", "이 인용문을 삭제할까요?", "삭제") {
                    scope.launch {
                        val remaining = withContext(Dispatchers.IO) {
                            runCatching { Library.deleteQuote(q.id) }
                            runCatching { Library.quotes(book.id) }.getOrNull()
                        }
                        if (remaining != null && !stale()) refreshQuoteHighlights(host, q.section, remaining)
                        loadQuotes(container)
                    }
                }
            },
        ))
    }

    private fun quoteShareText(q: Quote): String {
        val sb = StringBuilder()
        sb.append('“').append(q.text.trim()).append('”')
        if (q.note.isNotBlank()) sb.append("\n메모: ").append(q.note.trim())
        sb.append("\n— ").append(book.title)
        if (book.author.isNotBlank()) sb.append(", ").append(book.author)
        return sb.toString()
    }

    private fun shareAllQuotes() {
        if (quotes.isEmpty()) {
            ctx.toast("인용문이 없습니다")
            return
        }
        val sb = StringBuilder()
        sb.append("《").append(book.title).append("》")
        if (book.author.isNotBlank()) sb.append(" — ").append(book.author)
        sb.append("\n인용문 ").append(quotes.size).append("개\n")
        for (q in quotes) {
            sb.append("\n“").append(q.text.trim()).append("”\n")
            sb.append("  (").append(pageOf(q.section, q.start)).append("쪽)\n")
            if (q.note.isNotBlank()) sb.append("  메모: ").append(q.note.trim()).append('\n')
        }
        // Keep well below the binder transaction limit.
        val text = if (sb.length > 200_000) sb.substring(0, 200_000) + "\n…" else sb.toString()
        TextActions.share(ctx, text, book.title)
    }

    // ------------------------------------------------------------------ rows

    private fun noteRow(textLines: Int): LinearLayout = ctx.vertical {
        background = pressableBackground()
        val inner = ctx.vertical { setPadding(ctx.dp(16), ctx.dp(12), ctx.dp(16), ctx.dp(12)) }
        inner.addView(ctx.label("", 16f, maxLines = textLines).apply { tag = "text"; setLineSpacing(0f, 1.2f) }, lp())
        inner.addView(ctx.label("", 14f, color = Ink.GRAY, maxLines = 3).apply { tag = "note"; setPadding(0, ctx.dp(6), 0, 0) }, lp())
        inner.addView(ctx.label("", 13f, color = Ink.GRAY).apply { tag = "meta"; setPadding(0, ctx.dp(6), 0, 0) }, lp())
        addView(inner, lp())
        addView(ctx.hairline())
    }

    private fun trackedMenu(anchor: View, items: List<MenuItem>) {
        PanelRegistry.popup(ctx, ctx.popupMenu(anchor, items))
    }

    private fun pageOf(section: Int, offset: Int): String =
        PageLabel.pageOnly(runCatching { host.pageLabel(DocPosition(section, offset)) }.getOrNull()).ifEmpty { "-" }

    companion object {
        /**
         * [all] is the book's complete, freshly loaded quote list: stores it in [QuoteCache] and re-sends the quote
         * highlights of [section] to the page (owner "quotes").
         */
        fun refreshQuoteHighlights(host: ReaderHost, section: Int, all: List<Quote>) {
            runCatching { QuoteCache.put(host.book.id, all) }
            val hl = all.filter { it.section == section }.map { Highlight(it.start, it.end, HighlightKind.QUOTE) }
            runCatching { host.setHighlights("quotes", section, hl) }
        }
    }
}
