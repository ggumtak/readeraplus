package com.ggumtak.readeraplus.ui.settings

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.ggumtak.readeraplus.data.BookPrefs
import com.ggumtak.readeraplus.data.Library
import com.ggumtak.readeraplus.data.TxtOverride
import com.ggumtak.readeraplus.format.ParseOptions
import com.ggumtak.readeraplus.format.txt.TxtDocuments
import com.ggumtak.readeraplus.reader.extras.ErrorText
import com.ggumtak.readeraplus.reader.extras.Fmt
import com.ggumtak.readeraplus.reader.extras.ReadingSettingsPopup
import com.ggumtak.readeraplus.reader.extras.RulesDialog
import com.ggumtak.readeraplus.reader.extras.TxtEdits
import com.ggumtak.readeraplus.reader.ReaderIo
import com.ggumtak.readeraplus.reader.withTxt
import com.ggumtak.readeraplus.render.Covers
import com.ggumtak.readeraplus.settings.ReaderSettings
import com.ggumtak.readeraplus.settings.Settings
import com.ggumtak.readeraplus.ui.kit.Ink
import com.ggumtak.readeraplus.ui.kit.chooser
import com.ggumtak.readeraplus.ui.kit.confirm
import com.ggumtak.readeraplus.ui.kit.prompt
import com.ggumtak.readeraplus.ui.kit.row
import com.ggumtak.readeraplus.ui.kit.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "이 책의 TXT 정리" (T1-9): the TXT options of the book the reader has open ([OpenBook]), reached from 읽기 설정. The
 * rows show the book's effective values (`Settings.reader.withTxt(override)`, the TXT defaults page's wording); a
 * change becomes the book's override ([TxtEdits.overrideFor]), saved at once (BookPrefs, in order) and handed to the
 * reader, which re-parses the book once when it is back in front, however many rows changed. A new 인코딩 is saved
 * in the library and makes the reader re-open the book. The global defaults and other books are never touched, but
 * for "모든 TXT 기본값으로 저장".
 */
internal class BookTxtPage(a: SettingsActivity) : SettingsPage(a, SettingsActivity.PAGE_BOOK_TXT, TITLE) {
    /** Set when the page is created (SettingsActivity only creates it for a TXT book). */
    private val book: OpenBook.Info = checkNotNull(OpenBook.info)
    /** The book's own TXT options as this page last set them (starts as the reader's). */
    private var override: TxtOverride? = book.override
    /** The effective TXT options the rows show (only its TXT fields count). */
    private var txt: ReaderSettings = Settings.reader.withTxt(override)
    /** The last BookPrefs write: each waits for the one before it, so the last change is the one stored. */
    private var saveJob: Job? = null
    /** "모든 TXT 기본값으로 저장" and "이 책 설정 지우기 (기본값 사용)". */
    private val actionRows = ArrayList<View>(2)

    override fun build(): View {
        val body = ctx.pageBody()
        body.addView(ctx.note("'${book.title}'에만 적용됩니다. 책으로 돌아가면 바뀐 설정으로 한 번 다시 정리합니다 (긴 책은 조금 걸립니다)."))

        body.section("파일")
        var encRow: View? = null
        encRow = ctx.valueRow("인코딩", ReadingSettingsPopup.encodingShort(book.encoding)) {
            val options = listOf("") + TxtDocuments.ENCODINGS
            val sel = options.indexOf(book.encoding.trim()).coerceAtLeast(0)
            ctx.chooser("인코딩", options.map { ReadingSettingsPopup.encodingLabel(it) }, sel) { i ->
                changeEncoding(options[i]) { encRow?.setSummary(ReadingSettingsPopup.encodingShort(options[i])) }
            }
        }.also(body::addView)

        body.section("줄과 문단")
        var blankRow: View? = null
        blankRow = ctx.valueRow("빈 줄 처리", ReadingSettingsPopup.blankLabel(txt.txtBlankLines)) {
            val sel = BLANK_MODES.indexOf(txt.txtBlankLines).coerceAtLeast(0)
            ctx.chooser("빈 줄 처리", BLANK_MODES.map { ReadingSettingsPopup.blankLabel(it) }, sel) { i ->
                update(txt.copy(txtBlankLines = BLANK_MODES[i]))
                blankRow?.setSummary(ReadingSettingsPopup.blankLabel(BLANK_MODES[i]))
            }
        }.also(body::addView)
        body.addView(ctx.toggleRow("원본 들여쓰기 제거", "파일의 앞 공백 대신 들여쓰기 설정 사용", txt.txtStripIndent) { v ->
            update(txt.copy(txtStripIndent = v))
        })
        var joinRow: View? = null
        joinRow = ctx.valueRow("끊어진 줄 합치기", ReadingSettingsPopup.joinLabel(txt.txtJoinWrappedLines)) {
            val sel = JOIN_MODES.indexOf(txt.txtJoinWrappedLines).coerceAtLeast(0)
            ctx.chooser("끊어진 줄 합치기", JOIN_MODES.map { ReadingSettingsPopup.joinLabel(it) }, sel) { i ->
                update(txt.copy(txtJoinWrappedLines = JOIN_MODES[i]))
                joinRow?.setSummary(ReadingSettingsPopup.joinLabel(JOIN_MODES[i]))
            }
        }.also(body::addView)

        body.section("챕터")
        body.addView(ctx.toggleRow("챕터 자동 인식", "목차 만들기 (1화, 제1장, 프롤로그 …)", txt.txtDetectChapters) { v ->
            update(txt.copy(txtDetectChapters = v))
        })
        body.addView(ctx.toggleRow("챕터 제목 강조", "굵게 · 크게 · 가운데", txt.txtEmphasizeHeadings) { v ->
            update(txt.copy(txtEmphasizeHeadings = v))
        })
        var regexRow: View? = null
        regexRow = ctx.valueRow("챕터 규칙 (정규식)", txt.txtChapterRegex.ifBlank { "없음" }) {
            ctx.prompt("챕터 규칙 (정규식)", txt.txtChapterRegex, "예: ^제\\s*\\d+\\s*화.*") { text ->
                val t = text.trim()
                val err = if (t.isEmpty()) null else runCatching { Regex(t) }.exceptionOrNull()
                if (err != null) {
                    ctx.toast(ErrorText.regex(err))
                    return@prompt
                }
                update(txt.copy(txtChapterRegex = t))
                regexRow?.setSummary(t.ifBlank { "없음" })
            }
        }.also(body::addView)

        body.section("치환 규칙")
        var rulesRow: View? = null
        rulesRow = ctx.navRow("치환 규칙", Fmt.rulesLabel(txt.txtReplaceRules)) {
            RulesDialog.show(activity, "치환 규칙 · 이 책", txt.txtReplaceRules) { text ->
                update(txt.copy(txtReplaceRules = text.trimEnd()))
                rulesRow?.setSummary(Fmt.rulesLabel(txt.txtReplaceRules))
            }
        }.also(body::addView)

        body.section("기본값")
        actionRows.clear()
        actionRows += ctx.row(SAVE_DEFAULTS, "이 책의 설정을 모든 TXT 파일에 사용") { saveAsDefaults() }.also(body::addView)
        actionRows += ctx.row(CLEAR_OWN, null) { clearOwn() }.also(body::addView)
        refreshActions()
        return ctx.pageScroll(body)
    }

    /** A TXT option changed: the override it needs, saved and handed to the reader (re-parsed when it is back). */
    private fun update(next: ReaderSettings) {
        if (TxtEdits.sameTxt(next, txt)) return
        txt = next
        setOverride(TxtEdits.overrideFor(Settings.reader, txt))
    }

    private fun setOverride(o: TxtOverride?) {
        override = o?.takeUnless { it.isEmpty }
        OpenBook.overrideEdited(book.id, override)
        val id = book.id
        val v = override
        val before = saveJob
        // Process scope: the write outlives this page (Back, rotation); each waits for the one before it.
        saveJob = ReaderIo.launch {
            before?.join()
            BookPrefs.setTxtOverride(id, v)
        }
        refreshActions()
    }

    /** Whether the book has TXT options of its own. */
    private fun hasOwn(): Boolean = override != null

    /** "모든 TXT 기본값으로 저장" / "이 책 설정 지우기": gray titles while the book has no TXT options of its own. */
    private fun refreshActions() {
        val color = if (hasOwn()) Ink.BLACK else Ink.GRAY
        for (r in actionRows) titleOf(r)?.let { if (it.currentTextColor != color) it.setTextColor(color) }
    }

    /** The title of a kit [row] (its first text, the summary has the "summary" tag). */
    private fun titleOf(v: View): TextView? {
        if (v is TextView && v.tag != "summary") return v
        if (v is ViewGroup) for (i in 0 until v.childCount) titleOf(v.getChildAt(i))?.let { return it }
        return null
    }

    /** The effective options become the defaults; this book reads the same, so nothing is re-parsed. */
    private fun saveAsDefaults() {
        if (!hasOwn()) {
            ctx.toast("이 책은 이미 기본값을 따릅니다")
            return
        }
        ctx.confirm(
            "모든 TXT 기본값으로 저장",
            "이 책의 TXT 정리 설정을 모든 TXT 파일의 기본값으로 저장할까요? 다른 TXT 책은 다음에 열 때 새 설정으로 다시 정리됩니다.",
            "저장",
        ) {
            val global = Settings.reader.withTxt(override)
            if (global != Settings.reader) Settings.saveReader(global)
            setOverride(null)
            txt = Settings.reader
            activity.rebuildTop()
        }
    }

    /** The book follows the TXT defaults again (re-parsed when that differs). */
    private fun clearOwn() {
        if (!hasOwn()) {
            ctx.toast("이 책은 이미 기본값을 따릅니다")
            return
        }
        ctx.confirm("이 책 설정 지우기", "이 책에만 적용한 TXT 설정(치환 규칙 포함)을 지우고 기본값을 사용할까요?", "지우기") {
            setOverride(null)
            txt = Settings.reader
            activity.rebuildTop()
        }
    }

    /**
     * Saves the encoding in the library (the thumbnail, a rendering of the first page, is redrawn); the reader
     * re-opens the book when it is back. The reader is told at once (it saves the encoding again before re-opening),
     * so a quick Back never leaves it reading with the old one.
     */
    private fun changeEncoding(enc: String, onSaved: () -> Unit) {
        if (enc == book.encoding.trim()) return
        val id = book.id
        val app = activity.applicationContext
        OpenBook.encodingEdited(id, enc)
        onSaved()
        activity.scope.launch {
            val ok = withContext(NonCancellable + Dispatchers.IO) {
                runCatching { Library.setEncoding(id, enc) }.isSuccess.also { saved ->
                    if (saved) runCatching { Covers.invalidate(app, id) }
                }
            }
            if (!ok && !activity.isDestroyed) ctx.toast("인코딩을 저장하지 못했습니다")
        }
    }

    companion object {
        const val TITLE = "이 책의 TXT 정리"
        const val SAVE_DEFAULTS = "모든 TXT 기본값으로 저장"
        const val CLEAR_OWN = "이 책 설정 지우기 (기본값 사용)"
        /** The popup's order of the blank-line modes. */
        private val BLANK_MODES = listOf(ParseOptions.BLANK_AUTO, ParseOptions.BLANK_REMOVE_ALL, ParseOptions.BLANK_COLLAPSE, ParseOptions.BLANK_KEEP)
        /** 자동 / 항상 / 끄기. */
        private val JOIN_MODES = listOf(1, 2, 0)
    }
}
