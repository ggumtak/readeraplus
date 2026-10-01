package com.ggumtak.readeraplus.reader.extras

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.EditText
import android.widget.TextView
import com.ggumtak.readeraplus.data.Book
import com.ggumtak.readeraplus.data.Library
import com.ggumtak.readeraplus.format.BookDocument
import com.ggumtak.readeraplus.format.BookFormat
import com.ggumtak.readeraplus.format.DocMeta
import com.ggumtak.readeraplus.format.DocPosition
import com.ggumtak.readeraplus.format.Documents
import com.ggumtak.readeraplus.reader.ReaderFormat
import com.ggumtak.readeraplus.reader.ReaderHost
import com.ggumtak.readeraplus.render.Covers
import com.ggumtak.readeraplus.ui.kit.Ink
import com.ggumtak.readeraplus.ui.kit.InkNumPad
import com.ggumtak.readeraplus.ui.kit.NumPadState
import com.ggumtak.readeraplus.ui.kit.alert
import com.ggumtak.readeraplus.ui.kit.dp
import com.ggumtak.readeraplus.ui.kit.horizontal
import com.ggumtak.readeraplus.ui.kit.inkCursor
import com.ggumtak.readeraplus.ui.kit.label
import com.ggumtak.readeraplus.ui.kit.lp
import com.ggumtak.readeraplus.ui.kit.showNoAnim
import com.ggumtak.readeraplus.ui.kit.toast
import com.ggumtak.readeraplus.ui.kit.vertical
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/** "내 리뷰", "책 정보" (+ "책 정보 편집") and "페이지 이동" dialogs. */
internal object InfoDialogs {
    /** How long a key line of 책 정보 reads "… · 복사했습니다" after a long press copied its value. */
    private const val COPIED_NOTE_MS = 1500L

    // ------------------------------------------------------------------ 내 리뷰

    fun review(host: ReaderHost) {
        val ctx = host.activity
        val book = host.book
        val bookId = book.id
        val scope = MainScope()
        PanelRegistry.job(ctx, scope.launch {
            // host.book is a snapshot; the saved review may be newer.
            val saved = withContext(Dispatchers.IO) { runCatching { Library.book(bookId)?.review }.getOrNull() } ?: book.review
            scope.cancel()
            if (ctx.isFinishing || ctx.isDestroyed) return@launch
            ctx.multilinePrompt(
                "내 리뷰",
                saved,
                "이 책에 대한 생각을 적어 보세요",
                minLines = 6,
                neutral = if (saved.isNotBlank()) "지우기" to { saveReview(ctx, bookId, "") } else null,
            ) { text -> saveReview(ctx, bookId, text.trim()) }
        })
    }

    private fun saveReview(ctx: Activity, bookId: Long, text: String) {
        val scope = MainScope()
        scope.launch {
            val ok = withContext(Dispatchers.IO) { runCatching { Library.setReview(bookId, text) }.isSuccess }
            scope.cancel()
            ctx.toast(if (!ok) "리뷰를 저장하지 못했습니다" else if (text.isEmpty()) "리뷰를 지웠습니다" else "리뷰를 저장했습니다")
        }
    }

    // ------------------------------------------------------------------ 책 정보

    fun documentInfo(activity: Activity, book: Book, document: BookDocument?) {
        val scope = MainScope()
        PanelRegistry.job(activity, scope.launch {
            // The caller's Book is a snapshot (the reader's is from when the book was opened: stale progress,
            // reading time, review). Re-read it, and — from the library — the cheap metadata (OPF / encoding sniff).
            val (fresh, meta) = withContext(Dispatchers.IO) {
                val b = runCatching { Library.book(book.id) }.getOrNull() ?: book
                val m = document?.meta ?: runCatching { Documents.readMeta(File(b.path)) }.getOrNull()
                b to m
            }
            scope.cancel()
            if (activity.isFinishing || activity.isDestroyed) return@launch
            showInfo(activity, fresh, document, meta)
        })
    }

    private fun showInfo(activity: Activity, book: Book, document: BookDocument?, meta: DocMeta?) {
        val box = activity.vertical { setPadding(activity.dp(24), activity.dp(8), activity.dp(24), activity.dp(8)) }
        fun field(key: String, value: String?) {
            if (value.isNullOrBlank()) return
            val keyLabel = activity.label(key, 13f, bold = true, color = Ink.GRAY).apply { setPadding(0, activity.dp(10), 0, activity.dp(2)) }
            box.addView(keyLabel)
            val restoreKey = Runnable { keyLabel.text = key }
            // Not selectable text: a full-width selectable label started a selection from a long press on blank paper.
            // The label wraps its text, and a long press on it copies the whole value. The confirmation is the key
            // line itself, for a moment (a toast over a dialog is the platform's fading one; Android 13+ also shows
            // its own clipboard notice).
            box.addView(activity.label(value, 16f).apply {
                setLineSpacing(0f, 1.15f)
                setOnLongClickListener {
                    TextActions.copy(activity, value, confirm = false)
                    keyLabel.removeCallbacks(restoreKey)
                    keyLabel.text = "$key · 복사했습니다"
                    keyLabel.postDelayed(restoreKey, COPIED_NOTE_MS)
                    true
                }
            }, lp(WRAP_CONTENT, WRAP_CONTENT))
        }
        field("제목", book.title)
        field("작가", book.author.ifBlank { meta?.authors?.joinToString(", ") ?: "" }.ifBlank { "알 수 없음" })
        val series = book.series ?: meta?.series
        if (!series.isNullOrBlank()) {
            val idx = book.seriesIndex ?: meta?.seriesIndex
            field("시리즈", if (idx != null) "$series #${Fmt.number(idx)}" else series)
        }
        field("형식", book.format.label)
        field("크기", Fmt.fileSize(book.sizeBytes))
        field("경로", book.path)
        if (book.format == BookFormat.TXT) {
            val detected = meta?.encoding
            field("인코딩", when {
                book.encoding.isNotBlank() -> "${book.encoding} (직접 지정)"
                detected != null -> "$detected (자동 감지)"
                else -> "자동 감지"
            })
        }
        field("언어", book.language ?: meta?.language)
        field("출판사", meta?.publisher)
        field("추가한 날짜", Fmt.dateTime(book.addedAt))
        field("마지막으로 읽은 날짜", if (book.lastReadAt > 0) Fmt.dateTime(book.lastReadAt) else "읽지 않음")
        field("진행률", Fmt.percent(book.progress))
        field("읽은 시간", if (book.readingSeconds > 0) ReaderFormat.durationOfSeconds(book.readingSeconds) else "없음")
        if (document != null) {
            field("목차 항목 수", "${document.toc.size}개")
            var chars = 0L
            for (s in document.sections) chars += s.approxChars.coerceAtLeast(0)
            // The reader showing this book knows the reading speed and the position (T1-7).
            val insights = (activity as? BookInsightsHost)
                ?.takeIf { runCatching { (activity as ReaderHost).book.id == book.id }.getOrDefault(false) }
            field("분량", InfoText.volume(chars, insights?.let { runCatching { it.charsPerMinute() }.getOrNull() }))
            if (insights != null) {
                val bookMin = runCatching { insights.minutesLeft(true) }.getOrNull()
                val episodeMin = runCatching { insights.minutesLeft(false) }.getOrNull()
                field("남은 시간", InfoText.timeLeft(bookMin, episodeMin))
            }
        }
        if (book.review.isNotBlank()) field("내 리뷰", book.review)
        meta?.description?.let { d -> field("설명", Fmt.plainText(d)) }

        PanelRegistry.dialog(activity, activity.alert().setTitle("책 정보")
            .setView(activity.einkScroll(box))
            .setPositiveButton("닫기", null)
            .setNeutralButton("편집") { _, _ -> editMeta(activity, book) }
            .showNoAnim())
    }

    /** The metadata editor ([ReaderPanels.editBookInfo]); [onSaved] runs on the main thread after a successful save. */
    fun editMeta(activity: Activity, book: Book, onSaved: (() -> Unit)? = null) {
        val box = activity.vertical { setPadding(activity.dp(24), activity.dp(8), activity.dp(24), 0) }
        fun input(label: String, value: String, type: Int = InputType.TYPE_CLASS_TEXT): EditText {
            box.addView(activity.label(label, 13f, bold = true, color = Ink.GRAY).apply { setPadding(0, activity.dp(10), 0, 0) })
            val e = EditText(activity).apply {
                setText(value)
                inputType = type
                setSingleLine(true)
                setTextColor(Ink.BLACK)
                // Opens with the book's value: the caret shows once the user taps into it (no blind edits mid-word).
                inkCursor(singleLine = false)
            }
            box.addView(e, lp())
            return e
        }
        val title = input("제목", book.title)
        val author = input("작가", book.author)
        val series = input("시리즈", book.series.orEmpty())
        val index = input("시리즈 번호", book.seriesIndex?.let { Fmt.number(it) }.orEmpty(), InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        val dialog = activity.alert().setTitle("책 정보 편집")
            .setView(activity.einkScroll(box))
            .setPositiveButton("저장") { _, _ ->
                val t = title.text.toString().trim().ifEmpty { book.title }
                val a = author.text.toString().trim()
                val s = series.text.toString().trim().ifEmpty { null }
                val i = index.text.toString().trim().replace(',', '.').toFloatOrNull()
                val scope = MainScope()
                scope.launch {
                    val ok = withContext(Dispatchers.IO) {
                        runCatching {
                            Library.updateMeta(book.id, t, a, s, i)
                            runCatching { Covers.invalidate(activity.applicationContext, book.id) }
                        }.isSuccess
                    }
                    scope.cancel()
                    activity.toast(if (ok) "저장했습니다" else "저장하지 못했습니다")
                    if (ok) onSaved?.invoke()
                }
            }
            .setNegativeButton("취소", null)
            .showNoAnim()
        PanelRegistry.dialog(activity, dialog)
    }

    // ------------------------------------------------------------------ 페이지 이동

    /**
     * 페이지 이동: segments [페이지] [%] [화] over a number pad (no system keyboard). [페이지] needs the page count
     * complete; [화] a TOC whose titles mostly carry episode numbers (the episodes are waited for briefly, like the
     * TOC, so the dialog opens complete).
     */
    fun goTo(host: ReaderHost) {
        val ctx = host.activity
        val doc = host.document
        if (doc == null) {
            ctx.toast("책을 여는 중입니다")
            return
        }
        EpisodeWait.run(host, EpisodeWait.PANEL_WAIT_MS) { e, timedOut ->
            if (ctx.isFinishing || ctx.isDestroyed || host.document !== doc) return@run
            val d = GoToDialog(host, doc, e)
            d.show()
            if (timedOut) (host as? BookInsightsHost)?.episodes { late -> d.episodesArrived(late) }
        }
    }

    private class GoToDialog(private val host: ReaderHost, private val doc: BookDocument, private var episodes: Episodes?) {
        private val ctx = host.activity
        private val chars = IntArray(doc.sections.size) { doc.sections[it].approxChars }
        private val here = host.currentPosition()
        private val jump = host as? PageJumpHost
        private val parsed = PageLabel.parse(runCatching { host.pageLabel(here) }.getOrNull())
        private val pagesKnown = runCatching { host.totalPagesKnown() }.getOrDefault(false) && parsed.total > 0
        private val total = parsed.total
        // The footer's own measure when the host has it (so "현재 N%" reads exactly like the footer); otherwise
        // page-based once counts are complete (EPUB approxChars are only a size estimate), char-based before that.
        private val fraction = jump?.let { j -> runCatching { j.progressFraction() }.getOrNull()?.takeIf { !it.isNaN() } }
            ?: if (pagesKnown && parsed.page > 0) parsed.page.toFloat() / total else PageLabel.fractionOf(chars, here)
        private var mode = if (pagesKnown) MODE_PAGE else MODE_PERCENT
        private val pad = InkNumPad(ctx)
        private val segments = arrayOfNulls<TextView>(3)
        private lateinit var dialog: AlertDialog

        fun show() {
            val box = ctx.vertical { setPadding(ctx.dp(20), ctx.dp(8), ctx.dp(20), 0) }
            box.addView(ctx.label(GoToText.info(parsed.page, total, fraction, pagesKnown), 14f, color = Ink.GRAY).apply {
                setLineSpacing(0f, 1.2f)
                setPadding(ctx.dp(4), 0, ctx.dp(4), 0)
            }, lp())
            val row = ctx.horizontal { setPadding(0, ctx.dp(12), 0, ctx.dp(8)) }
            listOf("페이지", "%", "화").forEachIndexed { k, name ->
                val v = segment(ctx, name).apply { setOnClickListener { select(k) } }
                segments[k] = v
                row.addView(v, lp(0, WRAP_CONTENT, 1f).apply { if (k > 0) leftMargin = ctx.dp(8) })
            }
            box.addView(row, lp())
            box.addView(pad, lp())
            pad.onEnter = { v -> enter(v) }
            render(resetPad = true)
            dialog = ctx.alert().setTitle("페이지 이동")
                .setView(ctx.einkScroll(box))
                .setNegativeButton("취소", null)
                .create()
            dialog.setOnKeyListener { _, keyCode, ev -> pad.handleKey(keyCode, ev) }
            dialog.window?.setWindowAnimations(0)
            dialog.show()
            PanelRegistry.dialog(ctx, dialog)
        }

        /** Episodes that arrived after the dialog showed: [화] becomes available. */
        fun episodesArrived(e: Episodes?) {
            if (e == null || episodes != null || !dialog.isShowing) return
            episodes = e
            render(resetPad = false)
        }

        private fun enabled(m: Int): Boolean = when (m) {
            MODE_PAGE -> pagesKnown
            MODE_PERCENT -> true
            else -> episodes?.usableForJump == true
        }

        private fun select(m: Int) {
            if (m == mode || !enabled(m)) return
            mode = m
            render(resetPad = true)
        }

        private fun render(resetPad: Boolean) {
            for (k in 0..2) segments[k]?.let { setSegment(it, k == mode, enabled(k)) }
            if (!resetPad) return
            when (mode) {
                MODE_PAGE -> pad.reset(NumPadState.lengthFor(total), "1–${total}쪽")
                MODE_PERCENT -> pad.reset(3, "0–100%")
                else -> {
                    val e = episodes ?: return
                    val cur = e.numberAt(ContentsDialog.currentIndex(doc, here))
                    pad.reset(NumPadState.lengthFor(e.maxNumber), TocText.episodeHint(e.minNumber, e.maxNumber, cur))
                }
            }
        }

        private fun enter(v: Int?) {
            if (host.document !== doc) {
                dialog.dismiss()
                return
            }
            if (v == null) {
                pad.showError("숫자를 입력하세요")
                return
            }
            when (mode) {
                MODE_PAGE -> {
                    // "Page 4000" of 3259 pages is the last page: no error round-trip on e-ink.
                    dialog.dismiss()
                    goToPage(host, chars, v.coerceIn(1, total.coerceAtLeast(1)), total)
                }
                MODE_PERCENT -> {
                    val p = v.coerceIn(0, 100).toFloat()
                    dialog.dismiss()
                    when {
                        // Same measure as the footer: typing 52 lands on the page whose footer reads 52%.
                        jump != null -> jump.goToProgress(p / 100f)
                        pagesKnown -> goToPage(host, chars, PageLabel.pageForPercent(p, total), total)
                        else -> host.goTo(PageLabel.positionForFraction(chars, p / 100f), remember = true)
                    }
                }
                else -> {
                    val e = episodes ?: return
                    val i = e.find(v)
                    if (i < 0) {
                        pad.showError(TocText.missing(v))
                        return
                    }
                    dialog.dismiss()
                    val found = e.numbers[i]
                    goToEntry(host, doc, i) { if (found != v) ContentsDialog.noteAfterJump(host, TocText.jumped(v, found)) }
                }
            }
        }
    }

    /**
     * Goes to TOC entry [index] of [doc] (an EPUB anchor is resolved off the main thread first) if [doc] is still the
     * open document, remembering the old position ("돌아가기"); [then] runs after the jump.
     */
    private fun goToEntry(host: ReaderHost, doc: BookDocument, index: Int, then: () -> Unit) {
        val e = doc.toc.getOrNull(index) ?: return
        if (e.section !in doc.sections.indices) return
        if (e.anchor == null) {
            host.goTo(DocPosition(e.section, e.offset.coerceAtLeast(0)), remember = true)
            then()
            return
        }
        val scope = MainScope()
        PanelRegistry.job(host.activity, scope.launch {
            val p = withContext(Dispatchers.Default) { runCatching { doc.resolveToc(e) }.getOrNull() } ?: DocPosition(e.section, 0)
            scope.cancel()
            if (host.document !== doc || host.activity.isDestroyed) return@launch
            host.goTo(p, remember = true)
            then()
        })
    }

    /** Segments of 페이지 이동, left to right; 2 is [화]. */
    private const val MODE_PAGE = 0
    private const val MODE_PERCENT = 1

    private fun segment(ctx: Activity, text: String): TextView = ctx.label(text, 16f, bold = true).apply {
        gravity = Gravity.CENTER
        minHeight = ctx.dp(44)
    }

    private fun setSegment(v: TextView, selected: Boolean, enabled: Boolean) {
        val ctx = v.context
        v.isEnabled = enabled
        v.background = ctx.borderBox(if (selected) Ink.BLACK else Ink.WHITE, radiusDp = 3f)
        v.setTextColor(if (selected) Ink.WHITE else if (enabled) Ink.BLACK else Ink.DISABLED)
        v.typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    private fun android.content.Context.borderBox(fill: Int, radiusDp: Float) =
        android.graphics.drawable.GradientDrawable().apply {
            setColor(fill)
            setStroke(dp(1f).coerceAtLeast(1), Ink.LINE)
            cornerRadius = radiusDp * resources.displayMetrics.density
        }

    /**
     * Jumps to global page [page] (1-based). The host exposes only pageLabel(pos), so the target section is found
     * by binary search over section start pages, and the page index inside it follows from the (complete) counts.
     * A [PageJumpHost] shows that page directly, drawn once. Otherwise the page is exact when the section is the
     * one on screen, else estimated and corrected once the layout arrives (two draws).
     */
    private fun goToPage(host: ReaderHost, chars: IntArray, page: Int, total: Int) {
        val n = chars.size
        if (n == 0) return
        fun startPage(s: Int): Int = PageLabel.parse(runCatching { host.pageLabel(DocPosition(s, 0)) }.getOrNull()).page
        val target = PageLabel.pageTarget(n, page, total) { startPage(it) }
        val sec = target.section
        val k = target.index
        val pagesInSec = target.pagesInSection

        if (host is PageJumpHost) {
            host.goToPage(sec, k, remember = true)
            return
        }

        val layout = host.currentLayout
        if (layout != null && host.currentPosition().section == sec && layout.pageCount > 0) {
            val p = layout.pages[k.coerceAtMost(layout.pageCount - 1)]
            host.goTo(DocPosition(sec, p.start), remember = true)
            return
        }
        val approx = PageLabel.approxOffset(k, pagesInSec, chars[sec])
        host.goTo(DocPosition(sec, approx), remember = true)
        // Correct to the exact page start when the section's layout is ready (polls briefly, gives up after 3 s).
        val handler = Handler(Looper.getMainLooper())
        var tries = 0
        val check = object : Runnable {
            override fun run() {
                if (host.activity.isDestroyed) return
                val l = host.currentLayout
                val cur = host.currentPosition()
                if (l != null && cur.section == sec && l.pageCount > 0) {
                    val landed = l.pageForOffset(approx)
                    if (host.currentPageIndex == landed) {
                        val target = k.coerceAtMost(l.pageCount - 1)
                        if (target != landed) host.goTo(DocPosition(sec, l.pages[target].start), remember = false)
                    }
                    return
                }
                if (++tries < 30) handler.postDelayed(this, 100)
            }
        }
        handler.postDelayed(check, 50)
    }
}

/** Texts of 책 정보 (pure, unit-tested). */
internal object InfoText {
    /**
     * "약 312만 자 · 예상 약 104시간": the book's length ("약 9,600자" under 10,000, "약 1.6만 자" under 100,000) and,
     * with a reading speed ([charsPerMinute], T1-7), the time to read it all. Null for an empty book.
     */
    fun volume(chars: Long, charsPerMinute: Int?): String? {
        if (chars <= 0L) return null
        val size = when {
            chars < 10_000L -> "약 ${String.format(Locale.US, "%,d", (chars + 50) / 100 * 100)}자"
            chars < 100_000L -> {
                val tenths = (chars + 500) / 1000
                "약 ${tenths / 10}${if (tenths % 10 != 0L) ".${tenths % 10}" else ""}만 자"
            }
            else -> "약 ${(chars + 5_000) / 10_000}만 자"
        }
        if (charsPerMinute == null || charsPerMinute <= 0) return size
        return "$size · 예상 ${approx(ReaderFormat.minutesFor(chars, charsPerMinute))}"
    }

    /** "약 7시간 20분 (이 화 3분)" from the book's and the episode's minutes left; null when the book's is unknown. */
    fun timeLeft(bookMinutes: Int?, episodeMinutes: Int?): String? {
        if (bookMinutes == null) return null
        val episode = episodeMinutes?.let { " (이 화 ${ReaderFormat.duration(it)})" }.orEmpty()
        return approx(bookMinutes) + episode
    }

    /** "약 104시간", but "1분 미만" (never "약 1분 미만"). */
    private fun approx(minutes: Int): String =
        if (minutes < 1) ReaderFormat.duration(minutes) else "약 ${ReaderFormat.duration(minutes)}"
}
