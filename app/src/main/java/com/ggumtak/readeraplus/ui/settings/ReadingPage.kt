package com.ggumtak.readeraplus.ui.settings

import android.view.View
import android.widget.LinearLayout
import com.ggumtak.readeraplus.engine.Align
import com.ggumtak.readeraplus.engine.LineBreakMode
import com.ggumtak.readeraplus.reader.extras.FontChooser
import com.ggumtak.readeraplus.reader.extras.Fmt
import com.ggumtak.readeraplus.reader.extras.ReadingSettingsPopup
import com.ggumtak.readeraplus.reader.extras.StyleChoice
import com.ggumtak.readeraplus.reader.extras.TxtEdits
import com.ggumtak.readeraplus.reader.extras.QuoteSwatch
import com.ggumtak.readeraplus.render.DeviceClass
import com.ggumtak.readeraplus.render.FontManager
import com.ggumtak.readeraplus.render.FontMath
import com.ggumtak.readeraplus.render.QuoteStyles
import com.ggumtak.readeraplus.settings.AppSettings
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
import com.ggumtak.readeraplus.ui.kit.dp
import com.ggumtak.readeraplus.ui.kit.horizontal
import com.ggumtak.readeraplus.ui.kit.prompt
import com.ggumtak.readeraplus.ui.kit.row
import com.ggumtak.readeraplus.ui.kit.showNoAnim
import com.ggumtak.readeraplus.ui.kit.stepperRow
import com.ggumtak.readeraplus.ui.kit.toast
import com.ggumtak.readeraplus.ui.kit.vertical
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "보기 설정": how the page looks, and nothing else (the quick sheet of the same name is its short form; "보기 설정 전체"
 * there opens this page). 스타일 (추천 스타일, 내 스타일), 글자 (글꼴, 글꼴 관리, 글자 크기, 굵기, 글자 간격), 문단 (줄 간격,
 * 문단 간격, 들여쓰기, 정렬, 줄바꿈), 여백 (여백 사용 and the margins), 색 (배경 색, 흑백 반전, 형광펜 색 표시), 페이지 나눔
 * (페이지 나눔, 외톨이 줄 방지) and EPUB 서식 (the two EPUB switches), then 기본값으로 되돌리기. What works the reader
 * rather than shapes the page has its own row on the main list (the user, 2026-11-20: the options felt scattered):
 * 상태 표시줄, the TXT cleanup, 넘기기·터치·키, and 가로 화면 beside 화면 방향 on 화면·밝기. Opened straight from the quick
 * sheet it ends with a way on to that list (뷰어 설정). A 배경 색 or 흑백 반전 change is a repaint only (no re-layout).
 *
 * Every row edits the GLOBAL [ReaderSettings] (all books) but 형광펜 색 표시, an [AppSettings] choice: the quick options'
 * seven rows edit the same fields with the same steps and ranges. The reader applies what changed once, when it comes
 * back to the front (one re-layout, the first character of the page kept).
 */
internal class ReadingPage(a: SettingsActivity) : SettingsPage(a, SettingsActivity.PAGE_READING, TITLE) {
    /** The settings this page's rows show: a change made elsewhere (글꼴 관리, a restore) rebuilds it in [onShown]. */
    private var shown: ReaderSettings? = null
    private var styleRow: View? = null
    private var userRow: View? = null
    private var themeRow: View? = null
    private var marginViews: Array<View> = emptyArray()
    private var swatches: LinearLayout? = null
    private var swatchInk: Boolean? = null
    /** Holds the 굵기 stepper: swapped when the font's own weight is known ([showWeight]). */
    private var weightHolder: LinearLayout? = null
    private var weightNatural = DEFAULT_NATURAL_WEIGHT
    private var weightMin = DEFAULT_MIN_WEIGHT

    override fun build(): View {
        val r = Settings.reader
        shown = r
        val body = ctx.pageBody()

        body.section("스타일")
        styleRow = ctx.valueRow("추천 스타일", styleLabel(r)) { chooseStyle() }.also(body::addView)
        userRow = ctx.valueRow(StyleChoice.USER_LABEL, userLabel(r)) { userStylesMenu() }.also(body::addView)

        body.section("글자")
        // The font's name and weights are read off the main thread (a user font is a file read in a cold process; the
        // weights scan /sdcard/Fonts and parse a font file). 굵기 shows "기본" for a regular font until they arrive.
        val fontRow = ctx.valueRow("글꼴", "…") { v -> chooseFont(v) }.also(body::addView)
        body.addView(ctx.navRow("글꼴 관리", null) { activity.push(SettingsActivity.PAGE_FONTS) })
        weightNatural = DEFAULT_NATURAL_WEIGHT
        weightMin = DEFAULT_MIN_WEIGHT
        body.addView(stepper("글자 크기", r.fontSizeSp, ReaderSettings.MIN_FONT_SP, ReaderSettings.MAX_FONT_SP, 0.5f, Fmt::number) { v ->
            edit { it.copy(fontSizeSp = v) }
        })
        // 굵기 counts steps from the font's own weight ("기본"); a static file can't get thinner than it is.
        val holder = ctx.vertical()
        weightHolder = holder
        holder.addView(weightStepper(weightNatural, weightMin))
        body.addView(holder)
        loadFont(r.fontId, fontRow)
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

        addMargins(body, r)
        addColors(body, r)
        addPage(body, r)
        addEpub(body, r)

        // Opened straight from the quick sheet (no main list below it): one way on to every other page (the main
        // list, under the name of the reader's button that opens it), under a header of its own.
        if (activity.isRoot(this)) {
            body.section("다른 설정")
            body.addView(ctx.navRow(MainPage.READER_TITLE, "상태 표시줄 · 넘기기·터치·키 · 화면·밝기 · 듣기") { activity.push(SettingsActivity.PAGE_MAIN) })
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
        }
    }

    // ---------------------------------------------------------------- 여백 · 색 · 페이지 (scroll SPEC §2.4, anchor §3.3 / §4.4)

    private fun addMargins(body: LinearLayout, r: ReaderSettings) {
        body.section("여백")
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
        // Top and bottom from the status bands, each "0" its own default (15 / 10 dp): one value moves both, by the step
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
        marginViews = arrayOf(side, vertical)
        for (v in marginViews) body.addView(v)
        updateMarginUi()
    }

    private fun addColors(body: LinearLayout, r: ReaderSettings) {
        body.section("색")
        themeRow = ctx.valueRow("배경 색", r.pageTheme.label) {
            val opts = R3Rows.PAGE_THEMES
            ctx.chooser("배경 색", opts.map { R3Rows.pageThemeChoice(it) }, opts.indexOf(Settings.reader.pageTheme)) { i ->
                // 흰색 also picks 리디바탕 (QuickFields.withTheme), as in the reader's 배경 row: the font rows change too.
                applyStyle { com.ggumtak.readeraplus.reader.extras.QuickFields.withTheme(it, opts[i]).copy(invert = it.invert) }
            }
        }.also(body::addView)
        body.addView(ctx.toggleRow("흑백 반전", null, r.invert) { v -> edit { it.copy(invert = v) } })
        addHighlightLook(body, Settings.app)
    }

    /** 형광펜 색 표시 (an app setting, NOTES §11): the choice and a strip of the six looks as drawn. */
    private fun addHighlightLook(body: LinearLayout, app: AppSettings) {
        val eink = DeviceClass.cached(ctx)
        var lookRow: View? = null
        lookRow = ctx.valueRow("형광펜 색 표시", R3Rows.highlightLook(app.highlightLook, eink)) {
            val opts = R3Rows.HL_LOOKS
            val e = DeviceClass.cached(ctx)
            ctx.chooser("형광펜 색 표시", opts.map { R3Rows.highlightLook(it, e) }, opts.indexOf(Settings.app.highlightLook)) { i ->
                if (Settings.app.highlightLook != opts[i]) editApp { it.copy(highlightLook = opts[i]) }
                lookRow?.setSummary(R3Rows.highlightLook(opts[i], e))
                fillSwatches(R3Rows.inkLook(opts[i], e))
            }
        }.also(body::addView)
        swatches = ctx.horizontal {
            setPadding(ctx.dp(16), 0, ctx.dp(16), ctx.dp(8))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }.also(body::addView)
        fillSwatches(R3Rows.inkLook(app.highlightLook, eink))
    }

    /** A static strip of the six quote looks (22 x 14 dp each) as they are drawn on the page. */
    private fun fillSwatches(ink: Boolean) {
        val strip = swatches ?: return
        if (swatchInk == ink && strip.childCount > 0) return
        swatchInk = ink
        strip.removeAllViews()
        // RIDI's order (the selection palette's): yellow, green, purple, blue, pink, underline.
        for (pos in 0 until QuoteStyles.COUNT) {
            val style = QuoteStyles.paletteStyle(pos)
            strip.addView(QuoteSwatch(ctx, style, SWATCH_W_DP, ink).apply { reserveRing = false }, LinearLayout.LayoutParams(ctx.dp(SWATCH_W_DP), ctx.dp(SWATCH_H_DP)).apply {
                if (pos > 0) leftMargin = ctx.dp(8)
            })
        }
    }

    private fun addPage(body: LinearLayout, r: ReaderSettings) {
        body.section("페이지 나눔")
        var breakRow: View? = null
        breakRow = ctx.valueRow("페이지 나눔", R3Rows.pageBreak(r.pageBreak)) {
            val opts = R3Rows.PAGE_BREAKS
            ctx.chooser("페이지 나눔", opts.map { R3Rows.pageBreakChoice(it) }, opts.indexOf(Settings.reader.pageBreak)) { i ->
                edit { it.copy(pageBreak = opts[i]) }
                breakRow?.setSummary(R3Rows.pageBreak(opts[i]))
            }
        }.also(body::addView)
        body.addView(ctx.toggleRow("외톨이 줄 방지", "문단의 첫 줄이나 마지막 줄만 페이지 끝에 남지 않게 합니다", r.widowOrphanControl) { v ->
            edit { it.copy(widowOrphanControl = v) }
        })
    }

    /** The two margin steppers show only while "여백 사용" is on (one update with the switch). */
    private fun updateMarginUi() {
        val on = Settings.reader.pageMargins
        for (v in marginViews) v.setShown(on)
    }

    // ---------------------------------------------------------------- EPUB 서식

    /** How an EPUB's own styling is taken (the TXT cleanup, which changes the text itself, is on the main list). */
    private fun addEpub(body: LinearLayout, r: ReaderSettings) {
        body.section("EPUB 서식")
        body.addView(ctx.toggleRow("글자 크기 통일", "책 속 글자 크기 지정 대신 위에서 정한 크기를 적용합니다", r.epubIgnoreBookSizes) { v ->
            edit { it.copy(epubIgnoreBookSizes = v) }
        })
        body.addView(ctx.toggleRow("책의 원래 서식 따르기", "EPUB에 담긴 정렬·여백·강조 스타일을 유지합니다", r.epubPublisherStyles) { v ->
            edit { it.copy(epubPublisherStyles = v) }
        })
    }

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
            loadFont(id, row)
        }
    }

    private fun fontName(id: String): String = runCatching { FontManager.font(id)?.name }.getOrNull() ?: id

    /** What the 글꼴 and 굵기 rows show for one font. */
    private class FontFacts(val name: String, val natural: Int, val min: Int)

    /**
     * Reads the name and the weights of font [fontId] off the main thread (a user font is a file read, the weights may
     * scan /sdcard/Fonts and parse a font file), then updates [nameRow] and the 굵기 stepper together. Skipped when
     * the page is gone or another font was chosen meanwhile (that choice loads its own).
     */
    private fun loadFont(fontId: String, nameRow: View) {
        activity.scope.launch {
            val facts = withContext(Dispatchers.IO) {
                val natural = FontManager.naturalWeight(fontId)
                val min = runCatching { FontManager.minWeight(fontId) }.getOrDefault(DEFAULT_MIN_WEIGHT).coerceAtMost(natural)
                FontFacts(fontName(fontId), natural, min)
            }
            if (activity.isFinishing || Settings.reader.fontId != fontId) return@launch
            nameRow.setSummary(facts.name)
            showWeight(facts.natural, facts.min)
        }
    }

    private fun weightStepper(natural: Int, min: Int): LinearLayout =
        stepper("굵기", Settings.reader.fontWeight.toFloat(), min.toFloat(), 900f, 50f, { Fmt.weight(it.toInt(), natural) }) { v ->
            edit { it.copy(fontWeight = v.toInt()) }
        }

    /** Replaces the 굵기 stepper when the font's weights differ from what it was built with (else no redraw). */
    private fun showWeight(natural: Int, min: Int) {
        val holder = weightHolder ?: return
        if (natural == weightNatural && min == weightMin) return
        weightNatural = natural
        weightMin = min
        holder.removeAllViews()
        holder.addView(weightStepper(natural, min))
    }

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
        /** The page's name: the quick sheet's, and the reader's 보기 설정 button's. */
        const val TITLE = "보기 설정"
        const val CUSTOM_STYLE = "직접 설정"
        /** The 추천 스타일 row for the defaults' own look, which no preset matches since 웹소설 became the 마루뷰어 page. */
        const val DEFAULT_STYLE = "기본"
        /** 굵기 until the font's weights are read: a regular font that can also go lighter (the default font's). */
        private const val DEFAULT_NATURAL_WEIGHT = FontMath.REGULAR
        private const val DEFAULT_MIN_WEIGHT = 100
        const val RESET_TITLE = "기본값으로 되돌리기"
        const val RESET_SUMMARY = "이 화면의 설정만 · 흑백 반전은 그대로"
        const val RESET_MESSAGE = "글꼴 · 글자 크기 · 간격 · 여백 · 배경 색 · 페이지 나눔 · EPUB 서식을 기본값으로 되돌릴까요?\n흑백 반전과 다른 화면의 설정(상태 표시줄 · 가로 화면 · TXT 정리)은 그대로 둡니다."
        private const val SWATCH_W_DP = 22
        private const val SWATCH_H_DP = 14
        private val ALIGNS = listOf(Align.LEFT, Align.JUSTIFY)
        private val BREAKS = listOf(LineBreakMode.WORD, LineBreakMode.CHAR)
    }
}

/** 보기 설정's 기본값으로 되돌리기. Pure, unit-tested. */
internal object ReadingDefaults {
    /**
     * [s] with this page's settings back to the defaults (배경 색 back to 흰 바탕 too). Kept: the TXT options (resetting
     * them would re-parse every TXT book on its next open), 흑백 반전 (보기 설정 → 색, kept on purpose: the message
     * says so) and what lives on other pages: 상태 표시줄 (the status slots, 진행 막대, 상태 글자 크기) and 화면·밝기's
     * 가로 화면.
     */
    fun reset(s: ReaderSettings): ReaderSettings = TxtEdits.withTxtFrom(ReaderSettings(), s).copy(
        invert = s.invert,
        landscapePages = s.landscapePages,
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
