package com.ggumtak.readeraplus.reader.extras

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.EditText
import android.widget.TextView
import com.ggumtak.readeraplus.data.Book
import com.ggumtak.readeraplus.data.Library
import com.ggumtak.readeraplus.format.BookDocument
import com.ggumtak.readeraplus.format.BookFormat
import com.ggumtak.readeraplus.format.DocMeta
import com.ggumtak.readeraplus.format.DocPosition
import com.ggumtak.readeraplus.format.Documents
import com.ggumtak.readeraplus.reader.ReaderHost
import com.ggumtak.readeraplus.render.Covers
import com.ggumtak.readeraplus.ui.kit.Ink
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

/** "내 리뷰", "문서 속성" and "페이지 이동" dialogs. */
internal object InfoDialogs {
    /** How long a key line of 문서 속성 reads "… · 복사했습니다" after a long press copied its value. */
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

    // ------------------------------------------------------------------ 문서 속성

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
        field("읽은 시간", Fmt.duration(book.readingSeconds))
        if (document != null) {
            field("목차 항목 수", "${document.toc.size}개")
            var chars = 0L
            for (s in document.sections) chars += s.approxChars.coerceAtLeast(0)
            field("분량", "약 ${String.format("%,d", chars)}자 · 섹션 ${document.sections.size}개")
        }
        if (book.review.isNotBlank()) field("내 리뷰", book.review)
        meta?.description?.let { d -> field("설명", Fmt.plainText(d)) }

        PanelRegistry.dialog(activity, activity.alert().setTitle("문서 속성")
            .setView(activity.einkScroll(box))
            .setPositiveButton("닫기", null)
            .setNeutralButton("편집") { _, _ -> editMeta(activity, book) }
            .showNoAnim())
    }

    private fun editMeta(activity: Activity, book: Book) {
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
        val dialog = activity.alert().setTitle("문서 정보 편집")
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
                }
            }
            .setNegativeButton("취소", null)
            .showNoAnim()
        PanelRegistry.dialog(activity, dialog)
    }

    // ------------------------------------------------------------------ 페이지 이동

    fun goTo(host: ReaderHost) {
        val ctx = host.activity
        val doc = host.document
        if (doc == null) {
            ctx.toast("문서를 여는 중입니다")
            return
        }
        val chars = IntArray(doc.sections.size) { doc.sections[it].approxChars }
        val here = host.currentPosition()
        val jump = host as? PageJumpHost
        val parsed = PageLabel.parse(runCatching { host.pageLabel(here) }.getOrNull())
        val pagesKnown = runCatching { host.totalPagesKnown() }.getOrDefault(false) && parsed.total > 0
        val total = parsed.total
        // The footer's own measure when the host has it (so "현재 N%" reads exactly like the footer); otherwise
        // page-based once counts are complete (EPUB approxChars are only a size estimate), char-based before that.
        val fraction = jump?.let { j -> runCatching { j.progressFraction() }.getOrNull()?.takeIf { !it.isNaN() } }
            ?: if (pagesKnown && parsed.page > 0) parsed.page.toFloat() / total else PageLabel.fractionOf(chars, here)
        var percentMode = !pagesKnown

        val box = ctx.vertical { setPadding(ctx.dp(24), ctx.dp(8), ctx.dp(24), 0) }
        val info = ctx.label(
            GoToText.info(parsed.page, total, fraction, pagesKnown),
            14f,
            color = Ink.GRAY,
        ).apply { setLineSpacing(0f, 1.2f) }
        box.addView(info, lp())

        val toggle = ctx.horizontal { setPadding(0, ctx.dp(12), 0, ctx.dp(4)) }
        val pageBtn = segment(ctx, "쪽 번호")
        val pctBtn = segment(ctx, "%")
        toggle.addView(pageBtn, lp(0, WRAP_CONTENT, 1f))
        toggle.addView(pctBtn, lp(0, WRAP_CONTENT, 1f).apply { leftMargin = ctx.dp(8) })
        box.addView(toggle, lp())

        val edit = EditText(ctx).apply {
            setSingleLine(true)
            setTextColor(Ink.BLACK)
            textSize = 22f
            gravity = Gravity.CENTER
            inkCursor(singleLine = true)
        }
        box.addView(edit, lp())

        fun render() {
            setSegment(pageBtn, !percentMode, enabled = pagesKnown)
            setSegment(pctBtn, percentMode, enabled = true)
            if (percentMode) {
                edit.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                edit.hint = "0 – 100 %"
            } else {
                edit.inputType = InputType.TYPE_CLASS_NUMBER
                edit.hint = "쪽 번호 (1 – $total)"
            }
            edit.setText("")
        }
        pageBtn.setOnClickListener { if (pagesKnown) { percentMode = false; render() } }
        pctBtn.setOnClickListener { percentMode = true; render() }
        render()

        val dialog = ctx.alert().setTitle("페이지 이동")
            .setView(box)
            .setPositiveButton("이동", null)
            .setNegativeButton("취소", null)
            .create()
        dialog.window?.setWindowAnimations(0)
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.setOnShowListener {
            edit.requestFocus()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val raw = edit.text.toString().trim().replace(',', '.')
                val v = raw.toFloatOrNull()
                when {
                    v == null -> ctx.toast("숫자를 입력하세요")
                    percentMode && (v < 0f || v > 100f) -> ctx.toast("0 – 100 사이의 값을 입력하세요")
                    percentMode && jump != null -> {
                        // Same measure as the footer: typing 52 lands on the page whose footer reads 52%.
                        dialog.dismiss()
                        jump.goToProgress(v / 100f)
                    }
                    percentMode && pagesKnown -> {
                        dialog.dismiss()
                        goToPage(host, chars, PageLabel.pageForPercent(v, total), total)
                    }
                    percentMode -> {
                        dialog.dismiss()
                        host.goTo(PageLabel.positionForFraction(chars, v / 100f), remember = true)
                    }
                    v.toInt() < 1 || (total > 0 && v.toInt() > total) -> ctx.toast("1 – $total 사이의 쪽 번호를 입력하세요")
                    else -> {
                        dialog.dismiss()
                        goToPage(host, chars, v.toInt(), total)
                    }
                }
            }
        }
        edit.setOnEditorActionListener { _, _, _ ->
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.performClick()
            true
        }
        dialog.show()
        PanelRegistry.dialog(ctx, dialog)
    }

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
