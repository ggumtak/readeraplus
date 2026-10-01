package com.ggumtak.readeraplus.ui.settings

import android.view.View
import com.ggumtak.readeraplus.format.ParseOptions
import com.ggumtak.readeraplus.reader.extras.ErrorText
import com.ggumtak.readeraplus.reader.extras.Fmt
import com.ggumtak.readeraplus.reader.extras.ReadingSettingsPopup
import com.ggumtak.readeraplus.reader.extras.RulesDialog
import com.ggumtak.readeraplus.settings.Settings
import com.ggumtak.readeraplus.ui.kit.chooser
import com.ggumtak.readeraplus.ui.kit.prompt
import com.ggumtak.readeraplus.ui.kit.toast

/**
 * "TXT 기본 정리 설정" (T1-9): the global TXT options ([com.ggumtak.readeraplus.settings.ReaderSettings] `txt*`) that
 * every TXT book without its own settings uses. The rows and their wording are the reading-settings popup's TXT rows
 * (its labels are reused); the replacement rules open the shared manager ([RulesDialog]). A change here re-parses
 * the TXT books that follow the defaults the next time each opens (their index key changes); books with their own
 * settings ("이 책에만 적용") keep theirs and their cached index.
 */
internal class TxtDefaultsPage(a: SettingsActivity) : SettingsPage(a, SettingsActivity.PAGE_TXT_DEFAULTS, "TXT 기본 정리 설정") {
    private var blankRow: View? = null
    private var joinRow: View? = null
    private var regexRow: View? = null
    private var rulesRow: View? = null

    override fun build(): View {
        val r = Settings.reader
        val body = ctx.pageBody()
        body.addView(ctx.note(
            "책마다 따로 정하지 않은 모든 TXT 책에 쓰입니다. 한 권만 바꾸려면 읽는 중에 읽기 설정의 'TXT 파일 · 이 책에만 적용'을 쓰세요. " +
                "여기서 바꾸면 TXT 책을 다음에 열 때 한 번 다시 정리하므로 조금 느리게 열립니다.",
        ))

        body.section("줄과 문단")
        blankRow = ctx.valueRow("빈 줄 처리", ReadingSettingsPopup.blankLabel(r.txtBlankLines)) {
            val sel = BLANK_MODES.indexOf(Settings.reader.txtBlankLines).coerceAtLeast(0)
            ctx.chooser("빈 줄 처리", BLANK_MODES.map { ReadingSettingsPopup.blankLabel(it) }, sel) { i ->
                if (Settings.reader.txtBlankLines != BLANK_MODES[i]) editReader { it.copy(txtBlankLines = BLANK_MODES[i]) }
                blankRow?.setSummary(ReadingSettingsPopup.blankLabel(BLANK_MODES[i]))
            }
        }.also(body::addView)
        body.addView(ctx.toggleRow("원본 들여쓰기 제거", "파일의 앞 공백 대신 들여쓰기 설정 사용", r.txtStripIndent) { v ->
            editReader { it.copy(txtStripIndent = v) }
        })
        joinRow = ctx.valueRow("끊어진 줄 합치기", ReadingSettingsPopup.joinLabel(r.txtJoinWrappedLines)) {
            val sel = JOIN_MODES.indexOf(Settings.reader.txtJoinWrappedLines).coerceAtLeast(0)
            ctx.chooser("끊어진 줄 합치기", JOIN_MODES.map { ReadingSettingsPopup.joinLabel(it) }, sel) { i ->
                if (Settings.reader.txtJoinWrappedLines != JOIN_MODES[i]) editReader { it.copy(txtJoinWrappedLines = JOIN_MODES[i]) }
                joinRow?.setSummary(ReadingSettingsPopup.joinLabel(JOIN_MODES[i]))
            }
        }.also(body::addView)
        body.addView(ctx.note("줄 합치기 '자동'은 한 문장이 여러 줄로 끊겨 저장된 파일만 이어 붙입니다."))

        body.section("챕터")
        body.addView(ctx.toggleRow("챕터 자동 인식", "목차 만들기 (1화, 제1장, 프롤로그 …)", r.txtDetectChapters) { v ->
            editReader { it.copy(txtDetectChapters = v) }
        })
        body.addView(ctx.toggleRow("챕터 제목 강조", "굵게 · 크게 · 가운데", r.txtEmphasizeHeadings) { v ->
            editReader { it.copy(txtEmphasizeHeadings = v) }
        })
        regexRow = ctx.valueRow("챕터 규칙 (정규식)", r.txtChapterRegex.ifBlank { "없음" }) { editRegex() }.also(body::addView)

        body.section("치환 규칙")
        rulesRow = ctx.navRow("치환 규칙", Fmt.rulesLabel(r.txtReplaceRules)) { editRules() }.also(body::addView)
        body.addView(ctx.note("광고 문구 · 반복되는 머리말 같은 것을 지우거나 바꿉니다. 규칙은 원본 파일의 한 줄 안에서 적용됩니다."))
        return ctx.pageScroll(body)
    }

    override fun onShown() {
        // The popup's "모든 TXT 기본값으로 저장" may have changed the defaults meanwhile.
        val r = Settings.reader
        blankRow?.setSummary(ReadingSettingsPopup.blankLabel(r.txtBlankLines))
        joinRow?.setSummary(ReadingSettingsPopup.joinLabel(r.txtJoinWrappedLines))
        regexRow?.setSummary(r.txtChapterRegex.ifBlank { "없음" })
        rulesRow?.setSummary(Fmt.rulesLabel(r.txtReplaceRules))
    }

    private fun editRegex() {
        val current = Settings.reader.txtChapterRegex
        ctx.prompt("챕터 규칙 (정규식)", current, "예: ^제\\s*\\d+\\s*화.*") { text ->
            val t = text.trim()
            val err = if (t.isEmpty()) null else runCatching { Regex(t) }.exceptionOrNull()
            if (err != null) {
                ctx.toast(ErrorText.regex(err))
                return@prompt
            }
            if (t != Settings.reader.txtChapterRegex) editReader { it.copy(txtChapterRegex = t) }
            regexRow?.setSummary(t.ifBlank { "없음" })
        }
    }

    private fun editRules() {
        RulesDialog.show(activity, "치환 규칙 · 모든 TXT 기본값", Settings.reader.txtReplaceRules) { text ->
            val t = text.trimEnd()
            if (t != Settings.reader.txtReplaceRules) editReader { it.copy(txtReplaceRules = t) }
            rulesRow?.setSummary(Fmt.rulesLabel(t))
        }
    }

    private companion object {
        /** The popup's order of the blank-line modes. */
        val BLANK_MODES = listOf(ParseOptions.BLANK_AUTO, ParseOptions.BLANK_REMOVE_ALL, ParseOptions.BLANK_COLLAPSE, ParseOptions.BLANK_KEEP)
        /** 자동 / 항상 / 끄기, the popup's segment order. */
        val JOIN_MODES = listOf(1, 2, 0)
    }
}
