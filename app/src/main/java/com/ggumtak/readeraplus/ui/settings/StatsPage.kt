package com.ggumtak.readeraplus.ui.settings

import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.ggumtak.readeraplus.R
import com.ggumtak.readeraplus.data.Book
import com.ggumtak.readeraplus.data.BookPrefs
import com.ggumtak.readeraplus.data.BookTotal
import com.ggumtak.readeraplus.data.Library
import com.ggumtak.readeraplus.data.LogTotals
import com.ggumtak.readeraplus.data.ReadingLog
import com.ggumtak.readeraplus.reader.ReaderActivity
import com.ggumtak.readeraplus.reader.ReaderFormat
import com.ggumtak.readeraplus.ui.kit.Ink
import com.ggumtak.readeraplus.ui.kit.dp
import com.ggumtak.readeraplus.ui.kit.horizontal
import com.ggumtak.readeraplus.ui.kit.icon
import com.ggumtak.readeraplus.ui.kit.label
import com.ggumtak.readeraplus.ui.kit.lp
import com.ggumtak.readeraplus.ui.kit.row
import com.ggumtak.readeraplus.ui.kit.toast
import com.ggumtak.readeraplus.ui.kit.vertical
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZoneId

/**
 * "읽기 기록" (T1-6): 읽은 시간 (오늘 / 이번 주 / 이번 달 / 올해, the streak and the reading speed), 최근 20주 (the
 * 잔디, [HeatmapView]), the books read most this month and 올해 다 읽은 책. The queries run on IO when the page opens (and
 * again after a book opened from here was read); the page is static: filled once, no fling, nothing redraws while
 * it sits. Opened from the library drawer and the main settings list ([SettingsActivity.PAGE_STATS]).
 */
internal class StatsPage(a: SettingsActivity) : SettingsPage(a, SettingsActivity.PAGE_STATS, "읽기 기록") {
    private lateinit var content: LinearLayout
    private var loadJob: Job? = null
    private var grid: HeatGrid? = null
    private var dayText: TextView? = null
    private var dayGen = 0
    /** A book was opened from this page: its reading is in the log when we come back. */
    private var reloadOnResume = false

    /** Everything the page shows, read in one go on IO. */
    private class Data(
        val periods: List<Pair<String, LogTotals>>,
        val grid: HeatGrid,
        val streak: Pair<Int, Int>,
        val cpm: Int?,
        val top: List<Pair<Book, BookTotal>>,
        val finished: List<Pair<Book, Long>>,
    )

    override fun build(): View {
        val body = ctx.pageBody()
        content = ctx.vertical().also(body::addView)
        content.addView(ctx.note("불러오는 중…"))
        load()
        return ctx.pageScroll(body, fling = false)
    }

    override fun onResume() {
        if (!reloadOnResume) return
        reloadOnResume = false
        load()
    }

    private fun load() {
        loadJob?.cancel()
        Library.init(activity.applicationContext)
        loadJob = activity.scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { query() } }
            result.onSuccess { fill(it) }.onFailure { t ->
                content.removeAllViews()
                content.addView(ctx.note(ErrorLines.withDetail("기록을 읽지 못했습니다", t)))
            }
        }
    }

    /** Blocking (IO): a handful of indexed queries over at most [ReadingStats.HISTORY_DAYS] days. */
    private fun query(): Data {
        val now = System.currentTimeMillis()
        val today = ReadingLog.day(now)
        val periods = listOf(
            "오늘" to ReadingLog.summary(today, today),
            "이번 주" to ReadingLog.summary(ReadingStats.weekStart(today), today),
            "이번 달" to ReadingLog.summary(ReadingStats.monthStart(today), today),
            "올해" to ReadingLog.summary(ReadingStats.yearStart(today), today),
        )
        val history = ReadingLog.days(ReadingLog.addDays(today, -(ReadingStats.HISTORY_DAYS - 1)), today)
        val week = ReadingLog.summary(ReadingLog.addDays(today, -6), today)
        val top = ArrayList<Pair<Book, BookTotal>>()
        for (t in ReadingLog.perBook(ReadingStats.monthStart(today), today)) {
            val b = Library.book(t.bookId) ?: continue
            top += b to t
            if (top.size == TOP_BOOKS) break
        }
        val yearStartMs = ReadingLog.date(ReadingStats.yearStart(today)).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val finished = BookPrefs.finishedBetween(yearStartMs, now + 1).mapNotNull { f -> Library.book(f.bookId)?.let { it to f.finishedAt } }
        return Data(
            periods = periods,
            grid = HeatmapModel.build(today, history),
            streak = ReadingStats.streaks(history, today),
            cpm = ReadingLog.speed(week.seconds, week.chars, ReadingLog.BOOK_MIN_SECONDS),
            top = top,
            finished = finished,
        )
    }

    /** Replaces the page content in one go (one e-ink update). */
    private fun fill(d: Data) {
        content.removeAllViews()
        grid = d.grid
        dayGen++

        content.section("읽은 시간")
        content.addView(summaryGrid(d.periods))
        content.addView(text(ReadingStats.streakLine(d.streak.first, d.streak.second)))
        content.addView(text(ReadingStats.speedLine(d.cpm)))

        content.section("최근 ${HeatmapModel.WEEKS}주")
        val heat = HeatmapView(ctx).apply {
            setPadding(ctx.dp(16), ctx.dp(4), ctx.dp(16), 0)
            setGrid(d.grid)
            onDayTap = { day -> showDay(day) }
        }
        content.addView(heat, lp())
        dayText = ctx.note("칸을 누르면 그날 기록이 보입니다.").also(content::addView)

        content.section("이번 달 많이 읽은 책")
        if (d.top.isEmpty()) {
            content.addView(ctx.note("이번 달 읽은 기록이 없습니다."))
        } else {
            for ((b, t) in d.top) {
                val sub = ReaderFormat.durationOfSeconds(t.seconds) + " · " + ReadingStats.progress(b.progress, b.haveRead)
                content.addView(ctx.row(b.title, sub, titleMaxLines = 2) { openBook(b) })
            }
        }

        content.section("올해 다 읽은 책")
        addFinished(d.finished)

        content.addView(ctx.note("한 쪽에 2초 넘게 머문 시간만, 쪽마다 5분까지 셉니다. 연속 기록은 하루 5분 이상 읽은 날만 셉니다."))
    }

    /** 오늘 | 이번 주 / 이번 달 | 올해: each with its time and "312쪽 · 18.2만 자". */
    private fun summaryGrid(periods: List<Pair<String, LogTotals>>): LinearLayout {
        val box = ctx.vertical { setPadding(ctx.dp(16), 0, ctx.dp(16), ctx.dp(4)) }
        for (pair in periods.chunked(2)) {
            val line = ctx.horizontal { gravity = Gravity.TOP }
            for ((name, t) in pair) {
                val cell = ctx.vertical { setPadding(0, ctx.dp(8), ctx.dp(8), ctx.dp(8)) }
                cell.addView(ctx.label(name, 14f, color = Ink.GRAY))
                cell.addView(ctx.label(ReadingStats.time(t), 20f, bold = true).apply { setPadding(0, ctx.dp(4), 0, 0) })
                val amount = ReadingStats.amount(t)
                if (amount.isNotEmpty()) cell.addView(ctx.label(amount, 14f, color = Ink.GRAY).apply { setPadding(0, ctx.dp(3), 0, 0) })
                line.addView(cell, lp(0, WRAP_CONTENT, 1f))
            }
            box.addView(line, lp())
        }
        return box
    }

    private fun text(s: String): TextView = ctx.label(s, 17f).apply { setPadding(ctx.dp(16), ctx.dp(4), ctx.dp(16), ctx.dp(4)) }

    /** "3권" under the section's header, tapped open to list them (title, finish date; a tap opens the book). */
    private fun addFinished(list: List<Pair<Book, Long>>) {
        if (list.isEmpty()) {
            content.addView(ctx.note("아직 없습니다."))
            return
        }
        val items = ctx.vertical { visibility = View.GONE }
        val chevron: ImageView = ctx.icon(R.drawable.ic_expand_more, 24)
        content.addView(ctx.row("${list.size}권", "목록 보기", chevron) { r ->
            val open = items.visibility != View.VISIBLE
            if (open && items.childCount == 0) {
                for ((b, at) in list) {
                    items.addView(ctx.row(b.title, ReadingStats.dayLabel(ReadingLog.day(at)) + " 다 읽음", titleMaxLines = 2) { openBook(b) })
                }
            }
            items.visibility = if (open) View.VISIBLE else View.GONE
            chevron.setImageResource(if (open) R.drawable.ic_expand_less else R.drawable.ic_expand_more)
            r.setSummary(if (open) "목록 접기" else "목록 보기")
        })
        content.addView(items, lp())
    }

    /** Fills the line under the heatmap for [day] (the titles come from one small query on IO). */
    private fun showDay(day: Int) {
        val g = grid ?: return
        val i = g.days.indexOf(day)
        val seconds = if (i >= 0) g.seconds[i] else 0L
        val gen = ++dayGen
        if (seconds <= 0) {
            dayText?.text = ReadingStats.dayLine(day, 0, emptyList())
            return
        }
        activity.scope.launch {
            val titles = withContext(Dispatchers.IO) {
                runCatching { ReadingLog.perBook(day, day).mapNotNull { Library.book(it.bookId)?.title } }.getOrDefault(emptyList())
            }
            if (gen == dayGen) dayText?.text = ReadingStats.dayLine(day, seconds, titles)
        }
    }

    private fun openBook(book: Book) {
        if (book.trashed) {
            ctx.toast("휴지통에 있는 책입니다 · 먼저 복원하세요")
            return
        }
        reloadOnResume = true
        ReaderActivity.open(activity, book.id)
    }

    override fun onDestroy() {
        loadJob?.cancel()
    }

    private companion object {
        /** "이번 달 많이 읽은 책": at most this many rows. */
        const val TOP_BOOKS = 10
    }
}
