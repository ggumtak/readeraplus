package com.ggumtak.readeraplus.ui.settings

import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.EditText
import android.widget.TextView
import com.ggumtak.readeraplus.format.txt.HeadingRule
import com.ggumtak.readeraplus.reader.extras.ErrorText
import com.ggumtak.readeraplus.reader.extras.einkScroll
import com.ggumtak.readeraplus.ui.kit.Ink
import com.ggumtak.readeraplus.ui.kit.alert
import com.ggumtak.readeraplus.ui.kit.borderBox
import com.ggumtak.readeraplus.ui.kit.dp
import com.ggumtak.readeraplus.ui.kit.horizontal
import com.ggumtak.readeraplus.ui.kit.inkCursor
import com.ggumtak.readeraplus.ui.kit.keepAll
import com.ggumtak.readeraplus.ui.kit.label
import com.ggumtak.readeraplus.ui.kit.lp
import com.ggumtak.readeraplus.ui.kit.showNoAnim
import com.ggumtak.readeraplus.ui.kit.toast
import com.ggumtak.readeraplus.ui.kit.vertical

/**
 * The "챕터 제목 규칙" editor shared by the TXT defaults page and a book's TXT page: a two-way choice between an easy
 * pattern (간단 패턴, [HeadingRule]) and a regex, one text field and a help note. The rule is added to the built-in
 * ones. The defaults page offers 저장 (and 지우기 while a rule exists); a book's page offers 이 책만 / 모든 책 (and 지우기
 * as a button in the body, since a dialog bar holds three buttons). A regex that does not compile is shown, then the
 * dialog opens again with the typed text; a blank text clears the rule.
 */
internal object HeadingRuleDialog {
    const val TITLE = "챕터 제목 규칙"

    private const val COMMON_HELP = "기본 규칙(제N화 · N화 · 프롤로그 등)에 더해 인식합니다."
    private const val SIMPLE_HELP = "N = 숫자, * = 아무 글자, 띄어쓰기는 있어도 없어도 됩니다. 여러 개는 | 로 구분. " +
        "예: < N >  ·  N화 *  ·  제N장*"
    private const val REGEX_HELP = "예: ^제\\s*\\d+\\s*화.*"
    private const val SIMPLE_HINT = "예: < N >"
    private const val REGEX_HINT = "정규식"

    /**
     * Shows the editor for the [stored] rule. [perBook]: the dialog of a book (이 책만 / 모든 책) rather than the
     * defaults (저장). [onSave] gets the new stored rule ("" = none) and whether it is meant for every book.
     */
    fun show(ctx: Context, stored: String, perBook: Boolean, onSave: (stored: String, allBooks: Boolean) -> Unit) {
        val regex = stored.isNotBlank() && !HeadingRule.isSimple(stored)
        open(ctx, HeadingRule.simpleText(stored), regex, stored.isNotBlank(), perBook, onSave)
    }

    private fun open(
        ctx: Context, text: String, startRegex: Boolean, hasRule: Boolean, perBook: Boolean,
        onSave: (String, Boolean) -> Unit,
    ) {
        var regex = startRegex
        val box = ctx.vertical { setPadding(ctx.dp(20), ctx.dp(4), ctx.dp(20), ctx.dp(4)) }
        val modes = ctx.horizontal { setPadding(0, ctx.dp(8), 0, 0) }
        val simpleTab = segment(ctx, "간단 패턴")
        val regexTab = segment(ctx, "정규식")
        modes.addView(simpleTab, lp(0, WRAP_CONTENT, 1f))
        modes.addView(regexTab, lp(0, WRAP_CONTENT, 1f).apply { leftMargin = ctx.dp(6) })
        box.addView(modes, lp())
        val edit = EditText(ctx).apply {
            setText(text)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            isSingleLine = true
            setTextColor(Ink.BLACK)
            setSelection(text.length)
            inkCursor(singleLine = false)
        }
        box.addView(edit, lp().apply { topMargin = ctx.dp(8) })
        val help = ctx.label("", 14f, color = Ink.GRAY).keepAll().apply { setPadding(0, ctx.dp(8), 0, 0) }
        box.addView(help, lp())
        box.addView(ctx.label(COMMON_HELP, 14f, color = Ink.GRAY).keepAll().apply { setPadding(0, ctx.dp(6), 0, ctx.dp(4)) }, lp())
        fun refresh() {
            setSegment(simpleTab, !regex)
            setSegment(regexTab, regex)
            edit.hint = if (regex) REGEX_HINT else SIMPLE_HINT
            help.text = if (regex) REGEX_HELP else SIMPLE_HELP
        }
        simpleTab.setOnClickListener { regex = false; refresh() }
        regexTab.setOnClickListener { regex = true; refresh() }
        refresh()

        lateinit var dialog: AlertDialog
        // The result of the field: the stored rule, or null after telling why a regex does not compile.
        fun result(): String? {
            val t = edit.text.toString()
            if (!regex) {
                if (!HeadingRule.looksLikeRegex(t)) return HeadingRule.simple(t)
                // A regex typed as an easy pattern would never match: offered again as a regex.
                ctx.toast("정규식으로 보여 정규식으로 바꿨습니다. 맞으면 다시 저장하세요")
                open(ctx, t, true, hasRule, perBook, onSave)
                return null
            }
            val r = t.trim()
            val err = if (r.isEmpty()) null else runCatching { Regex(r) }.exceptionOrNull()
            if (err == null) return r
            ctx.toast(ErrorText.regex(err))
            open(ctx, t, true, hasRule, perBook, onSave)
            return null
        }
        if (perBook && hasRule) {
            box.addView(ctx.label("지우기", 16f, bold = true).apply {
                gravity = Gravity.CENTER
                minHeight = ctx.dp(48)
                background = ctx.borderBox(radiusDp = 3f)
                setOnClickListener {
                    dialog.dismiss()
                    onSave("", false)
                }
            }, lp().apply { topMargin = ctx.dp(8) })
        }
        val b = ctx.alert().setTitle(TITLE).setView(ctx.einkScroll(box))
            .setNegativeButton("취소", null)
        if (perBook) {
            b.setPositiveButton("이 책만") { _, _ -> result()?.let { onSave(it, false) } }
                .setNeutralButton("모든 책") { _, _ -> result()?.let { onSave(it, true) } }
        } else {
            b.setPositiveButton("저장") { _, _ -> result()?.let { onSave(it, true) } }
            if (hasRule) b.setNeutralButton("지우기") { _, _ -> onSave("", true) }
        }
        dialog = b.showNoAnim()
    }

    private fun segment(ctx: Context, text: String): TextView = ctx.label(text, 16f, bold = true).apply {
        gravity = Gravity.CENTER
        minHeight = ctx.dp(48)
    }

    private fun setSegment(v: TextView, selected: Boolean) {
        v.background = v.context.borderBox(if (selected) Ink.BLACK else Ink.WHITE, radiusDp = 3f)
        v.setTextColor(if (selected) Ink.WHITE else Ink.BLACK)
        v.typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }
}
