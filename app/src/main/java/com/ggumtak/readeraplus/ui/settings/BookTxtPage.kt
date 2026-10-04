package com.ggumtak.readeraplus.ui.settings

import android.view.View
import com.ggumtak.readeraplus.data.BookPrefs
import com.ggumtak.readeraplus.data.Library
import com.ggumtak.readeraplus.data.TxtOverride
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
 * "이 책의 TXT 정리" (T1-9): the TXT options of the book the reader has open ([OpenBook]), reached from the main list
 * and 읽기 설정. The rows show the book's effective values (`Settings.reader.withTxt(override)`, the TXT defaults
 * page's wording); a change becomes the book's override ([TxtEdits.overrideFor]), saved at once (BookPrefs, in order)
 * and handed to the reader, which re-parses the book once when it is back in front, however many rows changed. A new
 * 인코딩 is saved in the library and makes the reader re-open the book. The global defaults and other books are never
 * touched, but for "모든 TXT 책에 적용". While the book follows the defaults, the 기본값 section gives way to a note.
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
    /** The 기본값 section (its header and both rows), shown while the book has options of its own. */
    private val ownViews = ArrayList<View>(3)
    /** "이 책은 TXT 정리 기본값을 따릅니다.", shown instead of [ownViews]. */
    private var followsNote: View? = null
    private var headingsRow: View? = null
    private var regexRow: View? = null

    override fun build(): View {
        val body = ctx.pageBody()
        body.addView(ctx.note("이 책에만 적용됩니다. 책으로 돌아가면 한 번 다시 정리합니다."))

        body.section("본문")
        var encRow: View? = null
        encRow = ctx.valueRow("인코딩", ReadingSettingsPopup.encodingShort(book.encoding)) {
            val options = listOf("") + TxtDocuments.ENCODINGS
            val sel = options.indexOf(book.encoding.trim()).coerceAtLeast(0)
            ctx.chooser("인코딩", options.map { ReadingSettingsPopup.encodingLabel(it) }, sel) { i ->
                changeEncoding(options[i]) { encRow?.setSummary(ReadingSettingsPopup.encodingShort(options[i])) }
            }
        }.also(body::addView)
        var blankRow: View? = null
        blankRow = ctx.valueRow("빈 줄 처리", ReadingSettingsPopup.blankLabel(txt.txtBlankLines)) {
            val sel = TxtDefaultsPage.BLANK_MODES.indexOf(txt.txtBlankLines).coerceAtLeast(0)
            ctx.chooser("빈 줄 처리", TxtDefaultsPage.BLANK_MODES.map { ReadingSettingsPopup.blankLabel(it) }, sel) { i ->
                update(txt.copy(txtBlankLines = TxtDefaultsPage.BLANK_MODES[i]))
                blankRow?.setSummary(ReadingSettingsPopup.blankLabel(TxtDefaultsPage.BLANK_MODES[i]))
            }
        }.also(body::addView)
        body.addView(ctx.toggleRow("줄 앞 공백 지우기", "읽기 설정의 들여쓰기를 씁니다", txt.txtStripIndent) { v ->
            update(txt.copy(txtStripIndent = v))
        })
        var joinRow: View? = null
        joinRow = ctx.valueRow("끊어진 줄 합치기", ReadingSettingsPopup.joinLabel(txt.txtJoinWrappedLines)) {
            val sel = TxtDefaultsPage.JOIN_MODES.indexOf(txt.txtJoinWrappedLines).coerceAtLeast(0)
            ctx.chooser("끊어진 줄 합치기", TxtDefaultsPage.JOIN_MODES.map { ReadingSettingsPopup.joinChoice(it) }, sel) { i ->
                update(txt.copy(txtJoinWrappedLines = TxtDefaultsPage.JOIN_MODES[i]))
                joinRow?.setSummary(ReadingSettingsPopup.joinLabel(TxtDefaultsPage.JOIN_MODES[i]))
            }
        }.also(body::addView)
        var rulesRow: View? = null
        rulesRow = ctx.navRow("바꾸기 규칙", Fmt.rulesLabel(txt.txtReplaceRules)) {
            RulesDialog.show(activity, "바꾸기 규칙 · 이 책", txt.txtReplaceRules) { text ->
                update(txt.copy(txtReplaceRules = text.trimEnd()))
                rulesRow?.setSummary(Fmt.rulesLabel(txt.txtReplaceRules))
            }
        }.also(body::addView)

        body.section("챕터")
        body.addView(ctx.toggleRow("챕터 자동 인식", "목차 만들기 (1화, 제1장, 프롤로그 …)", txt.txtDetectChapters) { v ->
            update(txt.copy(txtDetectChapters = v))
            updateChapterUi()
        })
        // The parser uses the heading look and the rule only while detection is on: hidden otherwise.
        headingsRow = ctx.toggleRow("챕터 제목 강조", "굵게 · 크게 · 가운데", txt.txtEmphasizeHeadings) { v ->
            update(txt.copy(txtEmphasizeHeadings = v))
        }.also(body::addView)
        regexRow = ctx.valueRow("챕터 규칙 (정규식)", TxtDefaultsPage.regexLabel(txt.txtChapterRegex)) { editRegex(txt.txtChapterRegex) }
            .oneLineSummary().also(body::addView)
        updateChapterUi()

        // Both rows are about the defaults: the section shows only while the book has options of its own.
        ownViews.clear()
        ownViews += body.section("기본값")
        ownViews += ctx.row(SAVE_DEFAULTS, "이 설정을 TXT 정리 기본값으로 저장") { saveAsDefaults() }.also(body::addView)
        ownViews += ctx.row(CLEAR_OWN, "바꾸기 규칙 포함 · 기본값을 따릅니다") { clearOwn() }.also(body::addView)
        followsNote = ctx.note("이 책은 TXT 정리 기본값을 따릅니다.").also(body::addView)
        refreshActions()
        return ctx.pageScroll(body)
    }

    private fun updateChapterUi() {
        val on = txt.txtDetectChapters
        headingsRow?.setShown(on)
        regexRow?.setShown(on)
    }

    /** The rule prompt; a rule that does not compile is shown, then the prompt opens again with the typed text. */
    private fun editRegex(initial: String) {
        ctx.prompt("챕터 규칙 (정규식)", initial, "예: ^제\\s*\\d+\\s*화.*") { text ->
            val t = text.trim()
            val err = if (t.isEmpty()) null else runCatching { Regex(t) }.exceptionOrNull()
            if (err != null) {
                ctx.toast(ErrorText.regex(err))
                editRegex(text)
                return@prompt
            }
            update(txt.copy(txtChapterRegex = t))
            regexRow?.setSummary(TxtDefaultsPage.regexLabel(t))
        }
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

    /** The 기본값 section while the book has TXT options of its own, else the note (same pass as the change). */
    private fun refreshActions() {
        val own = override != null
        for (v in ownViews) v.setShown(own)
        followsNote?.setShown(!own)
    }

    /** The effective options become the defaults; this book reads the same, so nothing is re-parsed. */
    private fun saveAsDefaults() {
        ctx.confirm(
            SAVE_DEFAULTS,
            "이 책의 TXT 정리를 TXT 정리 기본값으로 저장할까요? 다른 TXT 책은 다음에 열 때 다시 정리합니다.",
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
        ctx.confirm(CLEAR_OWN, "이 책에만 적용한 TXT 정리(바꾸기 규칙 포함)를 지울까요? 이 책은 기본값을 따릅니다.", "지우기") {
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
        const val SAVE_DEFAULTS = "모든 TXT 책에 적용"
        const val CLEAR_OWN = "이 책 설정 지우기"
    }
}
