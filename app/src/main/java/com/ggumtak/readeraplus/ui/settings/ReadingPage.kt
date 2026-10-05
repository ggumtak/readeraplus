package com.ggumtak.readeraplus.ui.settings

import android.view.View
import android.widget.LinearLayout
import com.ggumtak.readeraplus.engine.Align
import com.ggumtak.readeraplus.engine.LineBreakMode
import com.ggumtak.readeraplus.format.BookFormat
import com.ggumtak.readeraplus.reader.extras.FontChooser
import com.ggumtak.readeraplus.reader.extras.Fmt
import com.ggumtak.readeraplus.reader.extras.ReadingSettingsPopup
import com.ggumtak.readeraplus.reader.extras.StatusUi
import com.ggumtak.readeraplus.reader.extras.StyleChoice
import com.ggumtak.readeraplus.reader.extras.TxtEdits
import com.ggumtak.readeraplus.render.FontManager
import com.ggumtak.readeraplus.settings.ReaderSettings
import com.ggumtak.readeraplus.settings.Settings
import com.ggumtak.readeraplus.settings.SideMargin
import com.ggumtak.readeraplus.settings.StylePreset
import com.ggumtak.readeraplus.settings.UserStyle
import com.ggumtak.readeraplus.settings.UserStyles
import com.ggumtak.readeraplus.settings.VerticalMargin
import com.ggumtak.readeraplus.ui.kit.alert
import com.ggumtak.readeraplus.ui.kit.chooser
import com.ggumtak.readeraplus.ui.kit.confirm
import com.ggumtak.readeraplus.ui.kit.prompt
import com.ggumtak.readeraplus.ui.kit.row
import com.ggumtak.readeraplus.ui.kit.showNoAnim
import com.ggumtak.readeraplus.ui.kit.stepperRow
import com.ggumtak.readeraplus.ui.kit.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "읽기 설정": every reading setting of the page in one place (the quick options' "전체 읽기 설정" opens it). 스타일
 * (추천 스타일, 내 스타일, 화면 색, 흑백 반전), 글자 (글꼴, 글자 크기, 굵기, 글자 간격), 문단 (줄 간격, 문단 간격, 들여쓰기,
 * 정렬, 줄바꿈), 여백·페이지 (여백 사용 and the margins, 페이지 나눔, 외톨이 줄 방지), 파일 (this book's TXT options, the
 * TXT defaults, EPUB 출판사 스타일) and 기타 (기본값으로 되돌리기; opened straight from the quick options, also the way on
 * to 넘기기·터치·키 and 화면·밝기). A 화면 색 or 흑백 반전 change is a repaint only (no re-layout).
 *
 * Every row edits the GLOBAL [ReaderSettings] (all books): the quick options' seven rows edit the same fields with the
 * same steps and ranges. The reader applies what changed once, when it comes back to the front (one re-layout, the
 * first character of the page kept).
 */
internal class ReadingPage(a: SettingsActivity) : SettingsPage(a, SettingsActivity.PAGE_READING, "읽기 설정") {
    /** The settings this page's rows show: a change made elsewhere (글꼴 관리, a restore) rebuilds it in [onShown]. */
    private var shown: ReaderSettings? = null
    private var styleRow: View? = null
    private var userRow: View? = null
    private var themeRow: View? = null
    private var marginViews: Array<View> = emptyArray()
    private var widowRow: View? = null
    private var bookTxtRow: View? = null

    override fun build(): View {
        val r = Settings.reader
        shown = r
        val body = ctx.pageBody()
        body.addView(ctx.note(SCOPE_NOTE))

        body.section("스타일")
        styleRow = ctx.valueRow("추천 스타일", styleLabel(r)) { chooseStyle() }.also(body::addView)
        userRow = ctx.valueRow(StyleChoice.USER_LABEL, userLabel(r)) { userStylesMenu() }.also(body::addView)
        themeRow = ctx.valueRow("화면 색", r.pageTheme.label) {
            val opts = R3Rows.PAGE_THEMES
            ctx.chooser("화면 색", opts.map { R3Rows.pageThemeChoice(it) }, opts.indexOf(Settings.reader.pageTheme)) { i ->
                edit { it.copy(pageTheme = opts[i]) }
            }
        }.also(body::addView)
        body.addView(ctx.toggleRow("흑백 반전", "검은 바탕에 흰 글자 · 화면 색보다 우선", r.invert) { v -> edit { it.copy(invert = v) } })

        body.section("글자")
        // The font's name is read off the main thread (a user font is a file read in a cold process).
        val fontRow = ctx.valueRow("글꼴", "…") { v -> chooseFont(v) }.also(body::addView)
        val fontId = r.fontId
        activity.scope.launch {
            val name = withContext(Dispatchers.IO) { fontName(fontId) }
            fontRow.setSummary(name)
        }
        body.addView(stepper("글자 크기", r.fontSizeSp, ReaderSettings.MIN_FONT_SP, ReaderSettings.MAX_FONT_SP, 0.5f, Fmt::number) { v ->
            edit { it.copy(fontSizeSp = v) }
        })
        body.addView(stepper("굵기", r.fontWeight.toFloat(), 100f, 900f, 50f, { Fmt.weight(it.toInt()) }) { v ->
            edit { it.copy(fontWeight = v.toInt()) }
        })
        body.addView(stepper("글자 간격", r.letterSpacingPm.toFloat(), -100f, 200f, 10f, { Fmt.letterSpacing(it.toInt()) }) { v ->
            edit { it.copy(letterSpacingPm = v.toInt()) }
        })

        body.section("문단")
        body.addView(stepper("줄 간격", r.lineHeightPct.toFloat(), 100f, 300f, 5f, { Fmt.pct(it.toInt()) }) { v ->
            edit { it.copy(lineHeightPct = v.toInt()) }
        })
        body.addView(stepper("문단 간격", r.paragraphSpacingPct.toFloat(), 0f, 300f, 10f, { Fmt.pct(it.toInt()) }) { v ->
            edit { it.copy(paragraphSpacingPct = v.toInt()) }
        })
        body.addView(stepper("들여쓰기", r.indentPct.toFloat(), 0f, 400f, 25f, { Fmt.em(it.toInt()) }) { v ->
            edit { it.copy(indentPct = v.toInt()) }
        })
        var alignRow: View? = null
        alignRow = ctx.valueRow("정렬", ReadingSettingsPopup.alignLabel(r.align)) {
            ctx.chooser("정렬", ALIGNS.map { ReadingSettingsPopup.alignLabel(it) }, ALIGNS.indexOf(Settings.reader.align)) { i ->
                edit { it.copy(align = ALIGNS[i]) }
                alignRow?.setSummary(ReadingSettingsPopup.alignLabel(ALIGNS[i]))
            }
        }.also(body::addView)
        var breakRow: View? = null
        breakRow = ctx.valueRow("줄바꿈", ReadingSettingsPopup.breakLabel(r.lineBreak)) {
            ctx.chooser("줄바꿈", BREAKS.map { ReadingSettingsPopup.breakChoice(it) }, BREAKS.indexOf(Settings.reader.lineBreak)) { i ->
                edit { it.copy(lineBreak = BREAKS[i]) }
                breakRow?.setSummary(ReadingSettingsPopup.breakLabel(BREAKS[i]))
            }
        }.also(body::addView)

        addPage(body, r)
        addFiles(body, r)

        // Opened straight from the quick options (no main list below it): one way on to every other page (the main
        // list, without its library rows while a book is open), under a header of its own.
        if (activity.isRoot(this)) {
            body.section("다른 설정")
            body.addView(ctx.navRow("모든 설정", "넘기기 · 화면 · e-ink · 듣기 · 사전") { activity.push(SettingsActivity.PAGE_MAIN) })
        }
        // Alone: the page's one destructive row.
        body.section("되돌리기")
        body.addView(ctx.row(RESET_TITLE, RESET_SUMMARY) { reset() })
        return ctx.pageScroll(body)
    }

    override fun onShown() {
        // 글꼴 관리 (a new reading font), a backup restore or the reader may have changed the settings meanwhile.
        if (Settings.reader != shown) {
            activity.rebuildTop()
            return
        }
        // 이 책의 TXT 정리 may have given the book its own options, or cleared them.
        OpenBook.info?.let { book -> bookTxtRow?.setSummary(bookTxtSummary(book)) }
    }

    // ---------------------------------------------------------------- 여백·페이지 (scroll SPEC §2.4, anchor §3.3 / §4.4)

    private fun addPage(body: LinearLayout, r: ReaderSettings) {
        body.section("여백·페이지")
        // The switch comes first: hiding the margins below it never moves it under the finger.
        body.addView(ctx.toggleRow("여백 사용", "끄면 여백을 최소로", r.pageMargins) { v ->
            edit { it.copy(pageMargins = v) }
            updateMarginUi()
        })
        // "0" = the default margin (S §2.4, A §3.3); stored values stay actual dp.
        val side = stepper(
            "좌우 여백",
            SideMargin.toUi(r.marginLeftDp).coerceIn(SideMargin.UI_MIN, SideMargin.UI_MAX).toFloat(),
            SideMargin.UI_MIN.toFloat(), SideMargin.UI_MAX.toFloat(), SideMargin.UI_STEP.toFloat(),
            { SideMargin.label(it.toInt()) },
        ) { v ->
            val dp = SideMargin.toDp(v.toInt())
            edit { it.copy(marginLeftDp = dp, marginRightDp = dp) }
        }
        // Top and bottom from the status bands, each "0" its own default (15 / 22 dp): one value moves both, by the step
        // from the value shown (VerticalMargin.step: a pair off the defaults' line never jumps).
        var verticalUi = VerticalMargin.toUi(r.marginTopDp, r.marginBottomDp)
        val vertical = stepper(
            "상하 여백",
            verticalUi.toFloat(),
            VerticalMargin.UI_MIN.toFloat(), VerticalMargin.UI_MAX.toFloat(), VerticalMargin.UI_STEP.toFloat(),
            { VerticalMargin.label(it.toInt()) },
        ) { v ->
            val from = verticalUi
            val to = v.toInt()
            verticalUi = to
            edit { val tb = VerticalMargin.step(it.marginTopDp, it.marginBottomDp, from, to); it.copy(marginTopDp = tb[0], marginBottomDp = tb[1]) }
        }
        val note = ctx.note(R3Rows.MARGIN_NOTE)
        marginViews = arrayOf(side, vertical, note)
        for (v in marginViews) body.addView(v)
        updateMarginUi()
        var breakRow: View? = null
        breakRow = ctx.valueRow("페이지 나눔", R3Rows.pageBreak(r.pageBreak)) {
            val opts = R3Rows.PAGE_BREAKS
            ctx.chooser("페이지 나눔", opts.map { R3Rows.pageBreakChoice(it) }, opts.indexOf(Settings.reader.pageBreak)) { i ->
                edit { it.copy(pageBreak = opts[i]) }
                breakRow?.setSummary(R3Rows.pageBreak(opts[i]))
                widowRow?.setSummary(StatusUi.widowSummary(opts[i]))
            }
        }.also(body::addView)
        widowRow = ctx.toggleRow("외톨이 줄 방지", StatusUi.widowSummary(r.pageBreak), r.widowOrphanControl) { v ->
            edit { it.copy(widowOrphanControl = v) }
        }.also(body::addView)
    }

    /** The two margin steppers and their note show only while "여백 사용" is on (one update with the switch). */
    private fun updateMarginUi() {
        val on = Settings.reader.pageMargins
        for (v in marginViews) v.setShown(on)
    }

    // ---------------------------------------------------------------- 파일

    private fun addFiles(body: LinearLayout, r: ReaderSettings) {
        body.section("파일")
        val book = OpenBook.info?.takeIf { it.format == BookFormat.TXT }
        if (book != null) {
            bookTxtRow = ctx.navRow(BookTxtPage.TITLE, bookTxtSummary(book)) { activity.push(SettingsActivity.PAGE_BOOK_TXT) }
                .also(body::addView)
        }
        body.addView(ctx.navRow(TxtDefaultsPage.TITLE, "따로 정하지 않은 모든 TXT 책") {
            activity.push(SettingsActivity.PAGE_TXT_DEFAULTS)
        })
        body.addView(ctx.toggleRow("EPUB 출판사 스타일", "책에 지정된 정렬 · 여백 · 제목 크기", r.epubPublisherStyles) { v ->
            edit { it.copy(epubPublisherStyles = v) }
        })
    }

    /** "따로 정함" while the open book has TXT options of its own, else "기본값 따름" (memory only). */
    private fun bookTxtSummary(book: OpenBook.Info): String = if (book.override != null) "따로 정함" else "기본값 따름"

    // ---------------------------------------------------------------- 스타일 · 내 스타일 (T1-8)

    private fun styleLabel(r: ReaderSettings): String =
        StyleChoice.selected(r)?.label ?: if (StyleChoice.isDefault(r)) DEFAULT_STYLE else CUSTOM_STYLE

    /** The matching saved style's name, else how many there are ("없음", "3개 저장됨"). */
    private fun userLabel(r: ReaderSettings): String {
        val list = Settings.userStyles
        return StyleChoice.selectedUser(r, list)?.name ?: if (list.isEmpty()) "없음" else "${list.size}개 저장됨"
    }

    /**
     * One tap: a preset's typography at once; the rows it changed are rebuilt. The 기본 button next to 취소 puts the
     * defaults' look back, so a preset can always be undone.
     */
    private fun chooseStyle() {
        val all = StylePreset.entries
        val sel = StyleChoice.selected(Settings.reader)?.let { all.indexOf(it) } ?: -1
        ctx.chooser("추천 스타일", all.map { "${it.label} (${it.description})" }, sel,
            extraButton = DEFAULT_STYLE, onExtra = { applyStyle { StyleChoice.applyDefault(it) } },
        ) { i -> applyStyle { all[i].applyTo(it) } }
    }

    private fun applyStyle(f: (ReaderSettings) -> ReaderSettings) {
        val next = f(Settings.reader)
        if (next != Settings.reader) {
            Settings.saveReader(next)
            activity.rebuildTop()
        }
    }

    private fun userStylesMenu() {
        val list = Settings.userStyles
        val cur = Settings.reader
        val labels = ArrayList<String>(list.size + 2)
        for (u in list) labels += if (u.matches(cur)) "✓ ${u.name}" else u.name
        labels += "현재 설정을 새 스타일로 저장…"
        if (list.isNotEmpty()) labels += "관리…"
        ctx.alert().setTitle(StyleChoice.USER_LABEL)
            .setItems(labels.toTypedArray()) { _, which ->
                when {
                    which < list.size -> applyStyle { list[which].applyTo(it) }
                    which == list.size -> saveNewStyle()
                    else -> manageStyles()
                }
            }
            .setNegativeButton("닫기", null)
            .showNoAnim()
    }

    private fun saveUserStyles(list: List<UserStyle>) {
        Settings.saveUserStyles(list)
        userRow?.setSummary(userLabel(Settings.reader))
    }

    private fun saveNewStyle() {
        val list = Settings.userStyles
        if (list.size >= UserStyles.MAX) {
            ctx.toast("스타일은 ${UserStyles.MAX}개까지 저장할 수 있습니다. ‘관리…’에서 하나를 지운 뒤 저장하세요")
            return
        }
        val suggested = UserStyles.defaultName(list)
        ctx.prompt("새 스타일 이름", suggested, "${UserStyles.MAX_NAME}자까지") { text ->
            val now = Settings.userStyles
            val name = UserStyles.cleanName(text).ifEmpty { suggested }
            val style = UserStyle.from(name, Settings.reader)
            when {
                now.any { it.name == name } ->
                    ctx.confirm("같은 이름의 스타일", "‘$name’ 스타일을 현재 설정으로 덮어쓸까요?", "덮어쓰기") {
                        saveUserStyles(StyleChoice.put(Settings.userStyles, style))
                    }
                !StyleChoice.canSave(now, name) -> ctx.toast("스타일은 ${UserStyles.MAX}개까지 저장할 수 있습니다")
                else -> saveUserStyles(StyleChoice.put(now, style))
            }
        }
    }

    /** "관리…": each saved style offers 이름 바꾸기 / 현재 설정으로 덮어쓰기 / 삭제. */
    private fun manageStyles() {
        val list = Settings.userStyles
        if (list.isEmpty()) return
        ctx.alert().setTitle("내 스타일 관리")
            .setItems(list.map { it.name }.toTypedArray()) { _, which -> list.getOrNull(which)?.let { styleActions(it) } }
            .setNegativeButton("닫기", null)
            .showNoAnim()
    }

    private fun styleActions(u: UserStyle) {
        val acts = arrayOf("이름 바꾸기", "현재 설정으로 덮어쓰기", "삭제")
        ctx.alert().setTitle(u.name)
            .setItems(acts) { _, which ->
                when (which) {
                    0 -> ctx.prompt("스타일 이름 바꾸기", u.name, "${UserStyles.MAX_NAME}자까지") { text ->
                        val next = StyleChoice.rename(Settings.userStyles, u.name, text)
                        if (next == null) ctx.toast("이름이 비었거나 이미 있는 이름입니다") else saveUserStyles(next)
                    }
                    1 -> ctx.confirm("현재 설정으로 덮어쓰기", "‘${u.name}’ 스타일을 현재 설정으로 덮어쓸까요?", "덮어쓰기") {
                        saveUserStyles(StyleChoice.put(Settings.userStyles, UserStyle.from(u.name, Settings.reader)))
                    }
                    else -> ctx.confirm("스타일 삭제", "‘${u.name}’ 스타일을 삭제할까요?", "삭제") {
                        saveUserStyles(StyleChoice.remove(Settings.userStyles, u.name))
                    }
                }
            }
            .setNegativeButton("취소", null)
            .showNoAnim()
    }

    // ---------------------------------------------------------------- 글꼴 · 기본값

    private fun chooseFont(row: View) {
        FontChooser.show(activity, Settings.reader.fontId) { id ->
            if (activity.isFinishing) return@show
            edit { it.copy(fontId = id) }
            row.setSummary(fontName(id))
        }
    }

    private fun fontName(id: String): String = runCatching { FontManager.font(id)?.name }.getOrNull() ?: id

    private fun reset() {
        ctx.confirm(RESET_TITLE, RESET_MESSAGE, "되돌리기") { applyStyle { ReadingDefaults.reset(it) } }
    }

    // ---------------------------------------------------------------- helpers

    /** Saves [f] applied to the latest settings; the rows already show the new value (no rebuild on return). */
    private inline fun edit(f: (ReaderSettings) -> ReaderSettings) {
        val next = f(Settings.reader)
        if (next != Settings.reader) Settings.saveReader(next)
        shown = Settings.reader
        styleRow?.setSummary(styleLabel(Settings.reader))
        userRow?.setSummary(userLabel(Settings.reader))
        themeRow?.setSummary(Settings.reader.pageTheme.label)
    }

    /** The kit's stepper ("<title> 줄이기" / "<title> 늘리기") with a value TalkBack speaks after a tap. */
    private fun stepper(title: String, value: Float, min: Float, max: Float, step: Float, format: (Float) -> String, onChange: (Float) -> Unit): LinearLayout =
        ctx.stepperRow(title, value.coerceIn(min, max), min, max, step, format, onChange).liveStepperValue()

    companion object {
        const val SCOPE_NOTE = "모든 책에 적용됩니다."
        const val CUSTOM_STYLE = "직접 설정"
        /** The 추천 스타일 row for the defaults' own look, which no preset matches since 웹소설 became the 마루뷰어 page. */
        const val DEFAULT_STYLE = "기본"
        const val RESET_TITLE = "기본값으로 되돌리기"
        const val RESET_SUMMARY = "흑백 반전 · TXT 정리는 그대로"
        const val RESET_MESSAGE = "글꼴 · 글자 크기 · 간격 · 여백 · 화면 색을 기본값으로 되돌릴까요?\n흑백 반전과 TXT 정리는 그대로 둡니다."
        private val ALIGNS = listOf(Align.LEFT, Align.JUSTIFY)
        private val BREAKS = listOf(LineBreakMode.WORD, LineBreakMode.CHAR)
    }
}

/** 읽기 설정's 기본값으로 되돌리기. Pure, unit-tested. */
internal object ReadingDefaults {
    /**
     * [s] with this page's settings back to the defaults (화면 색 back to 흰 바탕 too). Kept: the TXT options (resetting
     * them would re-parse every TXT book on its next open), 흑백 반전 (읽기 설정 → 스타일, kept on purpose: the message
     * says so) and what lives on 화면·밝기 (the status slots, 진행 막대, 상태 글자 크기).
     */
    fun reset(s: ReaderSettings): ReaderSettings = TxtEdits.withTxtFrom(ReaderSettings(), s).copy(
        invert = s.invert,
        headerLeft = s.headerLeft,
        headerCenter = s.headerCenter,
        headerRight = s.headerRight,
        footerLeft = s.footerLeft,
        footerCenter = s.footerCenter,
        footerRight = s.footerRight,
        progressBar = s.progressBar,
        statusFontSizeSp = s.statusFontSizeSp,
    )
}
