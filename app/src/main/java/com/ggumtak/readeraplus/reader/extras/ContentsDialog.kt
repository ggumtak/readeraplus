package com.ggumtak.readeraplus.reader.extras

import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
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
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Full-screen 목차 · 북마크 · 인용문 dialog. */
internal class ContentsDialog(private val host: ReaderHost, initialTab: Int) {
    private val ctx = host.activity
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
        bar.addView(ctx.label(host.book.title, 19f, bold = true, maxLines = 1).apply { setPadding(ctx.dp(12), 0, ctx.dp(8), 0) }, lp(0, WRAP_CONTENT, 1f))
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
        select(tab)
        dialog.show()
    }

    private fun select(i: Int) {
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
        host.goTo(pos, remember = true)
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
                val lbl = labels[position] ?: PageLabel.pageOnly(
                    runCatching { host.pageLabel(DocPosition(secs[position], offs[position].coerceAtLeast(0))) }.getOrNull(),
                ).also { if (offs[position] >= 0) labels[position] = it }
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
        if (current > 3) list.setSelection(current - 3)

        // Resolve anchored entries progressively (EPUB), refreshing the list at most every 300 ms.
        if (offs.any { it < 0 }) {
            scope.launch {
                var last = SystemClock.uptimeMillis()
                val pending = ArrayList<IntArray>()
                withContext(Dispatchers.Default) {
                    for (i in 0 until n) {
                        if (!isActive) break
                        if (offs[i] >= 0) continue
                        val p = runCatching { doc.resolveToc(toc[i]) }.getOrNull() ?: DocPosition(secs[i], 0)
                        synchronized(pending) { pending.add(intArrayOf(i, p.section, p.offset)) }
                        val now = SystemClock.uptimeMillis()
                        if (now - last >= 300 || i == n - 1) {
                            last = now
                            withContext(Dispatchers.Main) { applyResolved(pending, secs, offs, labels) }
                        }
                    }
                }
                applyResolved(pending, secs, offs, labels)
                val newCurrent = currentIndex(secs, offs, here)
                if (newCurrent != current) current = newCurrent
                adapter.notifyDataSetChanged()
            }
        }
        return list
    }

    private fun applyResolved(pending: ArrayList<IntArray>, secs: IntArray, offs: IntArray, labels: Array<String?>) {
        val batch = synchronized(pending) { ArrayList(pending).also { pending.clear() } }
        if (batch.isEmpty()) return
        for (r in batch) {
            secs[r[0]] = r[1]
            offs[r[0]] = r[2]
            labels[r[0]] = null
        }
        ((tabViews[0] as? ListView)?.adapter as? BaseAdapter)?.notifyDataSetChanged()
    }

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
        val bookId = host.book.id
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
        ctx.popupMenu(anchor, listOf(
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
        val bookId = host.book.id
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
        ctx.popupMenu(anchor, listOf(
            MenuItem("복사", R.drawable.ic_content_copy) { TextActions.copy(ctx, q.text) },
            MenuItem("공유", R.drawable.ic_share) { TextActions.share(ctx, quoteShareText(q), host.book.title) },
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
                            runCatching { Library.quotes(host.book.id) }.getOrNull()
                        }
                        if (remaining != null) refreshQuoteHighlights(host, q.section, remaining)
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
        sb.append("\n— ").append(host.book.title)
        if (host.book.author.isNotBlank()) sb.append(", ").append(host.book.author)
        return sb.toString()
    }

    private fun shareAllQuotes() {
        if (quotes.isEmpty()) {
            ctx.toast("인용문이 없습니다")
            return
        }
        val sb = StringBuilder()
        sb.append("《").append(host.book.title).append("》")
        if (host.book.author.isNotBlank()) sb.append(" — ").append(host.book.author)
        sb.append("\n인용문 ").append(quotes.size).append("개\n")
        for (q in quotes) {
            sb.append("\n“").append(q.text.trim()).append("”\n")
            sb.append("  (").append(pageOf(q.section, q.start)).append("쪽)\n")
            if (q.note.isNotBlank()) sb.append("  메모: ").append(q.note.trim()).append('\n')
        }
        // Keep well below the binder transaction limit.
        val text = if (sb.length > 200_000) sb.substring(0, 200_000) + "\n…" else sb.toString()
        TextActions.share(ctx, text, host.book.title)
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
