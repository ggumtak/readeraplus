package com.ggumtak.readeraplus.ui.settings

import android.view.View
import com.ggumtak.readeraplus.format.ParseOptions
import com.ggumtak.readeraplus.format.txt.HeadingRule
import com.ggumtak.readeraplus.reader.extras.Fmt
import com.ggumtak.readeraplus.reader.extras.ReadingSettingsPopup
import com.ggumtak.readeraplus.reader.extras.RulesDialog
import com.ggumtak.readeraplus.settings.Settings
import com.ggumtak.readeraplus.ui.kit.chooser

/**
 * "TXT 정리 기본값" (T1-9): the global TXT options ([com.ggumtak.readeraplus.settings.ReaderSettings] `txt*`) that
 * every TXT book without its own settings uses: 본문 (blank lines, leading spaces, broken lines, the replacement rules
 * in the shared manager [RulesDialog]) and 챕터 (detection; the heading look and the rule only while it is on). The
 * rows and their wording are those of "이 책의 TXT 정리" ([BookTxtPage], the labels of [ReadingSettingsPopup]'s
 * companion). A change here re-parses the TXT books that follow the defaults the next time each opens (their index key
 * changes); books with their own settings keep theirs and their cached index.
 */
internal class TxtDefaultsPage(a: SettingsActivity) : SettingsPage(a, SettingsActivity.PAGE_TXT_DEFAULTS, TITLE) {
    private var blankRow: View? = null
    private var joinRow: View? = null
    private var regexRow: View? = null
    private var rulesRow: View? = null
    private var stripRow: View? = null
    private var detectRow: View? = null
    private var headingsRow: View? = null

    override fun build(): View {
        val r = Settings.reader
        val body = ctx.pageBody()
        body.addView(ctx.note("따로 정하지 않은 TXT 책에 쓰입니다. 바꾸면 다음에 열 때 한 번 다시 정리합니다."))

        body.section("본문")
        blankRow = ctx.valueRow("빈 줄 처리", ReadingSettingsPopup.blankLabel(r.txtBlankLines)) {
            val sel = BLANK_MODES.indexOf(Settings.reader.txtBlankLines).coerceAtLeast(0)
            ctx.chooser("빈 줄 처리", BLANK_MODES.map { ReadingSettingsPopup.blankLabel(it) }, sel) { i ->
                if (Settings.reader.txtBlankLines != BLANK_MODES[i]) editReader { it.copy(txtBlankLines = BLANK_MODES[i]) }
                blankRow?.setSummary(ReadingSettingsPopup.blankLabel(BLANK_MODES[i]))
            }
        }.also(body::addView)
        stripRow = ctx.toggleRow("줄 앞 공백 지우기", "읽기 설정의 들여쓰기를 씁니다", r.txtStripIndent) { v ->
            editReader { it.copy(txtStripIndent = v) }
        }.also(body::addView)
        joinRow = ctx.valueRow("끊어진 줄 합치기", ReadingSettingsPopup.joinLabel(r.txtJoinWrappedLines)) {
            val sel = JOIN_MODES.indexOf(Settings.reader.txtJoinWrappedLines).coerceAtLeast(0)
            ctx.chooser("끊어진 줄 합치기", JOIN_MODES.map { ReadingSettingsPopup.joinChoice(it) }, sel) { i ->
                if (Settings.reader.txtJoinWrappedLines != JOIN_MODES[i]) editReader { it.copy(txtJoinWrappedLines = JOIN_MODES[i]) }
                joinRow?.setSummary(ReadingSettingsPopup.joinLabel(JOIN_MODES[i]))
            }
        }.also(body::addView)
        rulesRow = ctx.navRow("바꾸기 규칙", Fmt.rulesLabel(r.txtReplaceRules)) { editRules() }.also(body::addView)

        body.section("챕터")
        detectRow = ctx.toggleRow("챕터 자동 인식", "목차 만들기 (1화, 제1장, 프롤로그 …)", r.txtDetectChapters) { v ->
            editReader { it.copy(txtDetectChapters = v) }
            updateChapterUi()
        }.also(body::addView)
        // The parser uses the heading look and the rule only while detection is on: hidden otherwise.
        headingsRow = ctx.toggleRow("챕터 제목 강조", "굵게 · 크게 · 가운데", r.txtEmphasizeHeadings) { v ->
            editReader { it.copy(txtEmphasizeHeadings = v) }
        }.also(body::addView)
        regexRow = ctx.valueRow(HeadingRuleDialog.TITLE, regexLabel(r.txtChapterRegex)) { editRegex() }
            .oneLineSummary().also(body::addView)
        updateChapterUi()
        return ctx.pageScroll(body)
    }

    override fun onShown() {
        // "이 책의 TXT 정리 → 모든 TXT 책에 적용" may have changed the defaults meanwhile.
        val r = Settings.reader
        blankRow?.setSummary(ReadingSettingsPopup.blankLabel(r.txtBlankLines))
        joinRow?.setSummary(ReadingSettingsPopup.joinLabel(r.txtJoinWrappedLines))
        regexRow?.setSummary(regexLabel(r.txtChapterRegex))
        rulesRow?.setSummary(Fmt.rulesLabel(r.txtReplaceRules))
        stripRow?.setToggleChecked(r.txtStripIndent)
        detectRow?.setToggleChecked(r.txtDetectChapters)
        headingsRow?.setToggleChecked(r.txtEmphasizeHeadings)
        updateChapterUi()
    }

    private fun updateChapterUi() {
        val on = Settings.reader.txtDetectChapters
        headingsRow?.setShown(on)
        regexRow?.setShown(on)
    }

    /** The rule editor (간단 패턴 / 정규식); the rule is for every book that follows these defaults. */
    private fun editRegex() {
        HeadingRuleDialog.show(ctx, Settings.reader.txtChapterRegex, perBook = false) { t, _ ->
            if (t != Settings.reader.txtChapterRegex) editReader { it.copy(txtChapterRegex = t) }
            regexRow?.setSummary(regexLabel(t))
        }
    }

    private fun editRules() {
        RulesDialog.show(activity, "바꾸기 규칙 · TXT 기본값", Settings.reader.txtReplaceRules) { text ->
            val t = text.trimEnd()
            if (t != Settings.reader.txtReplaceRules) editReader { it.copy(txtReplaceRules = t) }
            rulesRow?.setSummary(Fmt.rulesLabel(t))
        }
    }

    companion object {
        const val TITLE = "TXT 정리 기본값"

        /** The 챕터 제목 규칙 row's value: the rule itself, or what applies without one. */
        fun regexLabel(regex: String): String = HeadingRule.label(regex)

        /** The popup's order of the blank-line modes. */
        val BLANK_MODES = listOf(ParseOptions.BLANK_AUTO, ParseOptions.BLANK_REMOVE_ALL, ParseOptions.BLANK_COLLAPSE, ParseOptions.BLANK_KEEP)
        /** 자동 / 항상 / 안 함, the popup's segment order. */
        val JOIN_MODES = listOf(1, 2, 0)
    }
}
