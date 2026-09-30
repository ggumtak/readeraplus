package com.ggumtak.readeraplus.reader.extras

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.SeekBar
import android.widget.TextView
import com.ggumtak.readeraplus.R
import com.ggumtak.readeraplus.data.Library
import com.ggumtak.readeraplus.engine.Align
import com.ggumtak.readeraplus.engine.LineBreakMode
import com.ggumtak.readeraplus.format.BookFormat
import com.ggumtak.readeraplus.format.ParseOptions
import com.ggumtak.readeraplus.format.txt.TxtDocuments
import com.ggumtak.readeraplus.reader.ReaderHost
import com.ggumtak.readeraplus.render.Covers
import com.ggumtak.readeraplus.render.FontManager
import com.ggumtak.readeraplus.settings.ReaderSettings
import com.ggumtak.readeraplus.settings.Settings
import com.ggumtak.readeraplus.settings.TapZoneMode
import com.ggumtak.readeraplus.ui.kit.Ink
import com.ggumtak.readeraplus.ui.kit.MenuItem
import com.ggumtak.readeraplus.ui.kit.borderBox
import com.ggumtak.readeraplus.ui.kit.confirm
import com.ggumtak.readeraplus.ui.kit.dp
import com.ggumtak.readeraplus.ui.kit.hairline
import com.ggumtak.readeraplus.ui.kit.horizontal
import com.ggumtak.readeraplus.ui.kit.icon
import com.ggumtak.readeraplus.ui.kit.label
import com.ggumtak.readeraplus.ui.kit.lp
import com.ggumtak.readeraplus.ui.kit.popupMenu
import com.ggumtak.readeraplus.ui.kit.pressableBackground
import com.ggumtak.readeraplus.ui.kit.prompt
import com.ggumtak.readeraplus.ui.kit.toast
import com.ggumtak.readeraplus.ui.kit.vertical
import com.ggumtak.readeraplus.ui.settings.SettingsActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference

/**
 * ReadEra-style reading settings popup (cards with a small title and a big value / stepper / switch),
 * black on white. Every change is applied immediately through [ReaderHost.applySettings]; steppers are debounced
 * (250 ms) so repeated taps cost one re-layout.
 */
internal class ReadingSettingsPopup(private val host: ReaderHost, private val anchor: View) {
    private val ctx = host.activity
    /** The open book (the popup is only shown while one is open; host.book throws between books). */
    private val book = host.book
    private var cur: ReaderSettings = Settings.reader
    private val handler = Handler(Looper.getMainLooper())
    private var dirty = false
    private val applyRunnable = Runnable { flush() }
    private val scope = MainScope()
    private var popup: PopupWindow? = null
    private lateinit var scroll: MaxHeightScrollView

    fun show() {
        current?.get()?.dismiss()
        current = WeakReference(this)
        val dm = ctx.resources.displayMetrics
        val width = minOf((dm.widthPixels * 0.88f).toInt(), ctx.dp(420))
        val loc = IntArray(2)
        var y = ctx.dp(56)
        if (anchor.isAttachedToWindow && anchor.height > 0) {
            anchor.getLocationInWindow(loc)
            y = loc[1] + anchor.height
        }
        val maxH = minOf((dm.heightPixels * 0.75f).toInt(), dm.heightPixels - y - ctx.dp(8)).coerceAtLeast(ctx.dp(240))
        if (y + maxH > dm.heightPixels) y = (dm.heightPixels - maxH).coerceAtLeast(0)

        scroll = MaxHeightScrollView(ctx, maxH).apply { isVerticalScrollBarEnabled = true }
        scroll.addView(buildContent(), FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        val frame = FrameLayout(ctx).apply {
            background = ctx.borderBox(radiusDp = 4f)
            setPadding(1, 1, 1, 1)
            addView(scroll, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        val pw = PopupWindow(frame, width, WRAP_CONTENT, true).apply {
            animationStyle = 0
            elevation = 0f
            isOutsideTouchable = true
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setOnDismissListener {
                flush()
                scope.cancel()
                if (current?.get() === this@ReadingSettingsPopup) current = null
            }
        }
        popup = pw
        try {
            pw.showAtLocation(anchor.rootView, Gravity.TOP or Gravity.END, ctx.dp(6), y)
            PanelRegistry.popup(ctx, pw)
        } catch (e: RuntimeException) {
            // BadTokenException / IllegalStateException: the reader window is going away.
            popup = null
            scope.cancel()
            if (current?.get() === this) current = null
        }
    }

    fun dismiss() {
        popup?.dismiss()
    }

    // ------------------------------------------------------------------ state

    private fun update(new: ReaderSettings, debounce: Boolean = false) {
        if (new == cur) return
        // A parse-option change makes the reader re-open (re-parse) the whole document, which can't be cancelled
        // midway: coalesce quick successive toggles into one re-open instead of starting several in parallel.
        val reparse = new.parseOptions() != cur.parseOptions()
        cur = new
        dirty = true
        handler.removeCallbacks(applyRunnable)
        when {
            reparse -> handler.postDelayed(applyRunnable, PARSE_DEBOUNCE_MS)
            debounce -> handler.postDelayed(applyRunnable, DEBOUNCE_MS)
            else -> flush()
        }
    }

    private fun flush() {
        handler.removeCallbacks(applyRunnable)
        if (!dirty) return
        dirty = false
        host.applySettings(cur)
    }

    private fun rebuild() {
        val y = scroll.scrollY
        scroll.removeAllViews()
        scroll.addView(buildContent(), FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        scroll.post { scroll.scrollTo(0, y) }
    }

    // ------------------------------------------------------------------ content

    private fun buildContent(): LinearLayout {
        val root = ctx.vertical { setPadding(0, 0, 0, ctx.dp(8)); setBackgroundColor(Ink.WHITE) }
        root.addView(ctx.label("읽기 설정 · EPUB, TXT", 16f, bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(ctx.dp(12), ctx.dp(12), ctx.dp(12), ctx.dp(6))
        }, lp())

        addPageTurning(root)
        addTypography(root)
        addPage(root)
        val isTxt = book.format == BookFormat.TXT
        if (isTxt) {
            addTxt(root)
            addEpub(root)
        } else {
            addEpub(root)
            addTxt(root)
        }
        addFooter(root)
        return root
    }

    private fun groupHeader(root: LinearLayout, text: String) {
        root.addView(ctx.label(text, 14f, bold = true).apply { setPadding(ctx.dp(14), ctx.dp(14), ctx.dp(14), ctx.dp(2)) }, lp())
    }

    private fun addPageTurning(root: LinearLayout) {
        val app = Settings.app
        root.addView(dropdownCard("화면 터치 (페이지 넘김)", tapModeLabel(app.tapZoneMode)) { anchorView, value ->
            val mode = Settings.app.tapZoneMode
            menu(anchorView, TapZoneMode.entries.map { m ->
                MenuItem(tapModeLabel(m), checked = m == mode) {
                    Settings.saveApp(Settings.app.copy(tapZoneMode = m))
                    value.text = tapModeLabel(m)
                    if (m == TapZoneMode.CUSTOM) SettingsActivity.open(ctx, SettingsActivity.PAGE_PAGE_TURNING)
                }
            }, widthDp = 280)
        })
        root.addView(switchCard("볼륨 키로 페이지 넘김", app.volumeKeysTurn) { v ->
            Settings.saveApp(Settings.app.copy(volumeKeysTurn = v))
        })
    }

    private fun addTypography(root: LinearLayout) {
        groupHeader(root, "글꼴")
        root.addView(dropdownCard("폰트 페이스", fontName(cur.fontId)) { _, value ->
            FontChooser.show(ctx, cur.fontId) { id ->
                if (popup?.isShowing == true) {
                    update(cur.copy(fontId = id))
                    value.text = fontName(id)
                    value.typeface = fontTypeface(id)
                } else {
                    host.applySettings(Settings.reader.copy(fontId = id))
                }
            }
        }.also { card -> card.findViewWithTag<TextView>("value")?.typeface = fontTypeface(cur.fontId) })

        root.addView(stepperCard("폰트 크기", cur.fontSizeSp, ReaderSettings.MIN_FONT_SP, ReaderSettings.MAX_FONT_SP, 0.5f, Fmt::number) {
            update(cur.copy(fontSizeSp = it), debounce = true)
        })
        root.addView(weightCard())
        root.addView(stepperCard("줄 간격", cur.lineHeightPct.toFloat(), 100f, 300f, 5f, { Fmt.pct(it.toInt()) }) {
            update(cur.copy(lineHeightPct = it.toInt()), debounce = true)
        })
        root.addView(stepperCard("문단 간격", cur.paragraphSpacingPct.toFloat(), 0f, 300f, 10f, { Fmt.pct(it.toInt()) }) {
            update(cur.copy(paragraphSpacingPct = it.toInt()), debounce = true)
        })
        root.addView(stepperCard("들여쓰기", cur.indentPct.toFloat(), 0f, 400f, 25f, { Fmt.em(it.toInt()) }) {
            update(cur.copy(indentPct = it.toInt()), debounce = true)
        })
        root.addView(stepperCard("글자 간격", cur.letterSpacingPm.toFloat(), -100f, 200f, 10f, { Fmt.letterSpacing(it.toInt()) }) {
            update(cur.copy(letterSpacingPm = it.toInt()), debounce = true)
        })
        root.addView(dropdownCard("글자 정렬", alignLabel(cur.align)) { a, value ->
            menu(a, listOf(Align.JUSTIFY, Align.LEFT).map { al ->
                MenuItem(alignLabel(al), checked = al == cur.align) {
                    update(cur.copy(align = al))
                    value.text = alignLabel(al)
                }
            }, widthDp = 260)
        })
        root.addView(dropdownCard("줄바꿈", breakLabel(cur.lineBreak)) { a, value ->
            menu(a, listOf(LineBreakMode.WORD, LineBreakMode.CHAR).map { m ->
                MenuItem(breakLabel(m), checked = m == cur.lineBreak) {
                    update(cur.copy(lineBreak = m))
                    value.text = breakLabel(m)
                }
            }, widthDp = 280)
        })
    }

    private fun addPage(root: LinearLayout) {
        groupHeader(root, "페이지")
        val marginH = stepperCard("좌우 여백", cur.marginLeftDp.toFloat(), 0f, 80f, 2f, { "${it.toInt()}dp" }) {
            update(cur.copy(marginLeftDp = it.toInt(), marginRightDp = it.toInt()), debounce = true)
        }
        val marginV = stepperCard("상하 여백", cur.marginTopDp.toFloat(), 0f, 80f, 2f, { "${it.toInt()}dp" }) {
            update(cur.copy(marginTopDp = it.toInt(), marginBottomDp = it.toInt()), debounce = true)
        }
        root.addView(switchCard("페이지 여백", cur.pageMargins) { v ->
            update(cur.copy(pageMargins = v))
            marginH.visibility = if (v) View.VISIBLE else View.GONE
            marginV.visibility = if (v) View.VISIBLE else View.GONE
        })
        marginH.visibility = if (cur.pageMargins) View.VISIBLE else View.GONE
        marginV.visibility = if (cur.pageMargins) View.VISIBLE else View.GONE
        root.addView(marginH)
        root.addView(marginV)

        lateinit var statusSize: View
        fun statusVisible() {
            statusSize.visibility = if (cur.showHeader || cur.showFooter) View.VISIBLE else View.GONE
        }
        root.addView(switchCard("상단 챕터 제목", cur.showHeader) { v ->
            update(cur.copy(showHeader = v))
            statusVisible()
        })
        val footerItems = footerItemsCard()
        root.addView(switchCard("하단 정보 표시", cur.showFooter) { v ->
            update(cur.copy(showFooter = v))
            footerItems.visibility = if (v) View.VISIBLE else View.GONE
            statusVisible()
        })
        footerItems.visibility = if (cur.showFooter) View.VISIBLE else View.GONE
        root.addView(footerItems)
        statusSize = stepperCard("상태 표시 글자 크기", cur.statusFontSizeSp, 8f, 16f, 0.5f, Fmt::number) {
            update(cur.copy(statusFontSizeSp = it), debounce = true)
        }
        statusVisible()
        root.addView(statusSize)
        root.addView(switchCard("흑백 반전", cur.invert, "검은 바탕에 흰 글씨") { v -> update(cur.copy(invert = v)) })
        root.addView(switchCard("외톨이 줄 방지", cur.widowOrphanControl, "문단의 첫 줄/마지막 줄이 홀로 남지 않게") { v ->
            update(cur.copy(widowOrphanControl = v))
        })
    }

    private fun addTxt(root: LinearLayout) {
        groupHeader(root, "TXT 파일")
        if (book.format == BookFormat.TXT) {
            root.addView(dropdownCard("인코딩 (이 책)", encodingLabel(book.encoding)) { a, _ ->
                val options = listOf("") + TxtDocuments.ENCODINGS
                menu(a, options.map { enc ->
                    MenuItem(encodingLabel(enc), checked = enc == book.encoding) { changeEncoding(enc) }
                }, widthDp = 240)
            })
        }
        root.addView(dropdownCard("빈 줄 처리", blankLabel(cur.txtBlankLines)) { a, value ->
            val modes = listOf(ParseOptions.BLANK_AUTO, ParseOptions.BLANK_REMOVE_ALL, ParseOptions.BLANK_COLLAPSE, ParseOptions.BLANK_KEEP)
            menu(a, modes.map { m ->
                MenuItem(blankLabel(m), checked = m == cur.txtBlankLines) {
                    update(cur.copy(txtBlankLines = m))
                    value.text = blankLabel(m)
                }
            }, widthDp = 280)
        })
        root.addView(switchCard("원본 들여쓰기 제거", cur.txtStripIndent, "파일의 앞 공백 대신 들여쓰기 설정 사용") { v ->
            update(cur.copy(txtStripIndent = v))
        })
        root.addView(dropdownCard("끊어진 줄 합치기", joinLabel(cur.txtJoinWrappedLines)) { a, value ->
            menu(a, listOf(1, 2, 0).map { m ->
                MenuItem(joinLabel(m), checked = m == cur.txtJoinWrappedLines) {
                    update(cur.copy(txtJoinWrappedLines = m))
                    value.text = joinLabel(m)
                }
            }, widthDp = 240)
        })
        root.addView(switchCard("챕터 자동 인식", cur.txtDetectChapters, "목차 만들기 (1화, 제1장, 프롤로그 …)") { v ->
            update(cur.copy(txtDetectChapters = v))
        })
        root.addView(switchCard("챕터 제목 강조", cur.txtEmphasizeHeadings, "굵게 · 크게 · 가운데") { v ->
            update(cur.copy(txtEmphasizeHeadings = v))
        })
        root.addView(dropdownCard("챕터 규칙 (정규식)", cur.txtChapterRegex.ifBlank { "없음" }) { _, value ->
            ctx.prompt("챕터 규칙 (정규식)", cur.txtChapterRegex, "예: ^제\\s*\\d+\\s*화.*") { text ->
                val t = text.trim()
                val err = if (t.isEmpty()) null else runCatching { Regex(t) }.exceptionOrNull()
                if (err != null) {
                    ctx.toast("정규식 오류: ${err.message?.lineSequence()?.firstOrNull() ?: ""}")
                } else {
                    update(cur.copy(txtChapterRegex = t))
                    value.text = t.ifBlank { "없음" }
                }
            }
        })
        root.addView(dropdownCard("치환 규칙", Fmt.rulesLabel(cur.txtReplaceRules)) { _, value ->
            ctx.multilinePrompt(
                "치환 규칙",
                cur.txtReplaceRules,
                "패턴 => 바꿀 내용",
                minLines = 5,
                message = "한 줄에 하나씩 '정규식 => 바꿀 내용'. #으로 시작하는 줄은 주석입니다.",
            ) { text ->
                val bad = Fmt.invalidRuleCount(text)
                update(cur.copy(txtReplaceRules = text.trimEnd()))
                value.text = Fmt.rulesLabel(text)
                if (bad > 0) ctx.toast("잘못된 규칙 ${bad}개는 무시됩니다")
            }
        })
    }

    private fun addEpub(root: LinearLayout) {
        groupHeader(root, "EPUB 파일")
        root.addView(switchCard("출판사 스타일 사용", cur.epubPublisherStyles, "책에 지정된 정렬 · 여백 · 제목 크기") { v ->
            update(cur.copy(epubPublisherStyles = v))
        })
    }

    private fun addFooter(root: LinearLayout) {
        val row = ctx.horizontal { setPadding(ctx.dp(8), ctx.dp(14), ctx.dp(8), ctx.dp(4)) }
        row.addView(ctx.outlineButton("기본값 복원") {
            ctx.confirm("기본값 복원", "읽기 설정을 모두 기본값으로 되돌릴까요?", "복원") {
                update(ReaderSettings())
                rebuild()
            }
        }, lp(0, WRAP_CONTENT, 1f).apply { rightMargin = ctx.dp(8) })
        row.addView(ctx.outlineButton("일반 설정") {
            flush()
            popup?.dismiss()
            SettingsActivity.open(ctx, SettingsActivity.PAGE_PAGE_TURNING)
        }, lp(0, WRAP_CONTENT, 1f))
        root.addView(row, lp())
    }

    // ------------------------------------------------------------------ cards

    /** Title + big value + ▼; [onClick] receives the card and the value TextView. */
    private fun dropdownCard(title: String, value: String, onClick: (View, TextView) -> Unit): LinearLayout {
        val c = ctx.card()
        c.addView(ctx.cardTitle(title))
        val valueView = ctx.label(value, 18f, maxLines = 2).apply { tag = "value" }
        val row = ctx.horizontal { minimumHeight = ctx.dp(40) }
        row.addView(valueView, lp(0, WRAP_CONTENT, 1f))
        row.addView(ctx.icon(R.drawable.ic_arrow_drop_down, 28))
        c.addView(row, lp())
        c.background = android.graphics.drawable.LayerDrawable(arrayOf(pressableBackground(Ink.WHITE), ctx.borderBox(Color.TRANSPARENT, radiusDp = 3f)))
        c.setOnClickListener { onClick(row, valueView) }
        return c
    }

    private fun switchCard(title: String, checked: Boolean, summary: String? = null, onChange: (Boolean) -> Unit): LinearLayout {
        val c = ctx.card()
        val row = ctx.horizontal { minimumHeight = ctx.dp(40) }
        val texts = ctx.vertical()
        texts.addView(ctx.label(title, 17f))
        if (summary != null) texts.addView(ctx.label(summary, 13f, color = Ink.GRAY).apply { setPadding(0, ctx.dp(2), 0, 0) })
        row.addView(texts, lp(0, WRAP_CONTENT, 1f))
        val sw = EinkToggle(ctx, checked)
        row.addView(sw, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = ctx.dp(12); rightMargin = ctx.dp(4) })
        c.addView(row, lp())
        c.background = android.graphics.drawable.LayerDrawable(arrayOf(pressableBackground(Ink.WHITE), ctx.borderBox(Color.TRANSPARENT, radiusDp = 3f)))
        c.setOnClickListener {
            sw.toggle()
            onChange(sw.isChecked)
        }
        return c
    }

    /** "⊖   value   ⊕" across the card, like ReadEra. */
    private fun stepperCard(
        title: String,
        value: Float,
        min: Float,
        max: Float,
        step: Float,
        format: (Float) -> String,
        onChange: (Float) -> Unit,
    ): LinearLayout {
        var v = value
        val c = ctx.card()
        c.addView(ctx.cardTitle(title))
        val valueView = ctx.label(format(v), 18f).apply { gravity = Gravity.CENTER }
        fun set(nv: Float) {
            val s = Fmt.stepFloat(nv, step, min, max)
            if (s == v) return
            v = s
            valueView.text = format(v)
            onChange(v)
        }
        val row = ctx.horizontal()
        row.addView(ctx.flatIcon(R.drawable.ic_do_not_disturb_on, "$title 줄이기") { set(v - step) })
        row.addView(valueView, lp(0, WRAP_CONTENT, 1f))
        row.addView(ctx.flatIcon(R.drawable.ic_add_circle, "$title 늘리기") { set(v + step) })
        c.addView(row, lp())
        return c
    }

    private fun weightCard(): LinearLayout {
        val c = ctx.card()
        val head = ctx.horizontal()
        head.addView(ctx.cardTitle("폰트 굵기"), lp(0, WRAP_CONTENT, 1f))
        val valueView = ctx.label(Fmt.weight(cur.fontWeight), 14f)
        head.addView(valueView)
        c.addView(head, lp())
        val bar = SeekBar(ctx).einkStyle()
        bar.max = 16
        bar.progress = ((cur.fontWeight - 100) / 50).coerceIn(0, 16)
        fun commit(w: Int, debounce: Boolean) {
            valueView.text = Fmt.weight(w)
            update(cur.copy(fontWeight = w), debounce)
        }
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                if (fromUser) valueView.text = Fmt.weight(100 + p * 50)
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) = commit(100 + s.progress * 50, false)
        })
        val row = ctx.horizontal()
        row.addView(ctx.flatIcon(R.drawable.ic_do_not_disturb_on, "가늘게") {
            bar.progress = (bar.progress - 1).coerceAtLeast(0)
            commit(100 + bar.progress * 50, true)
        })
        row.addView(bar, lp(0, WRAP_CONTENT, 1f))
        row.addView(ctx.flatIcon(R.drawable.ic_add_circle, "굵게") {
            bar.progress = (bar.progress + 1).coerceAtMost(16)
            commit(100 + bar.progress * 50, true)
        })
        c.addView(row, lp())
        return c
    }

    private fun footerItemsCard(): LinearLayout {
        val c = ctx.card()
        c.addView(ctx.cardTitle("하단 정보 항목"))
        fun item(text: String, checked: Boolean, onChange: (Boolean) -> ReaderSettings) {
            var on = checked
            val box = ctx.icon(if (on) R.drawable.ic_check_box else R.drawable.ic_check_box_outline_blank, 24)
            val r = ctx.horizontal {
                minimumHeight = ctx.dp(48)
                background = pressableBackground()
                addView(box)
                addView(ctx.label(text, 16f).apply { setPadding(ctx.dp(12), 0, 0, 0) }, lp(0, WRAP_CONTENT, 1f))
                setOnClickListener {
                    on = !on
                    box.setImageResource(if (on) R.drawable.ic_check_box else R.drawable.ic_check_box_outline_blank)
                    update(onChange(on))
                }
            }
            c.addView(r, lp())
        }
        item("쪽수 (12 / 3259)", cur.footerPage) { cur.copy(footerPage = it) }
        item("챕터 남은 쪽수", cur.footerChapterLeft) { cur.copy(footerChapterLeft = it) }
        item("진행률 (%)", cur.footerPercent) { cur.copy(footerPercent = it) }
        item("시계", cur.footerClock) { cur.copy(footerClock = it) }
        item("배터리", cur.footerBattery) { cur.copy(footerBattery = it) }
        return c
    }

    // ------------------------------------------------------------------ actions

    private fun changeEncoding(enc: String) {
        if (enc == book.encoding) return
        // The reader moved on to another book (or none) underneath this popup.
        if (runCatching { host.book.id }.getOrNull() != book.id) {
            popup?.dismiss()
            return
        }
        flush()
        val bookId = book.id
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { Library.setEncoding(bookId, enc) }.isSuccess.also { saved ->
                    // The TXT thumbnail is a rendering of the first page: redraw it with the new encoding.
                    if (saved) runCatching { Covers.invalidate(ctx.applicationContext, bookId) }
                }
            }
            if (!ok) {
                ctx.toast("인코딩을 저장하지 못했습니다")
                return@launch
            }
            popup?.dismiss()
            // The host caches the Book (and its encoding); reopening the activity re-reads it from the library.
            ctx.toast("인코딩: ${encodingLabel(enc)} — 다시 여는 중…")
            if (!ctx.isFinishing) ctx.recreate()
        }
    }

    /** Popup menus are tracked for [ReaderPanels.dismissAll] (they outlive this popup otherwise). */
    private fun menu(anchor: View, items: List<MenuItem>, widthDp: Int) {
        PanelRegistry.popup(ctx, ctx.popupMenu(anchor, items, widthDp))
    }

    private fun fontName(id: String): String = runCatching { FontManager.font(id)?.name }.getOrNull() ?: id

    /** Same (id, weight) the page renderer uses, so this is normally a cache hit. */
    private fun fontTypeface(id: String): Typeface = runCatching { FontManager.typeface(id, cur.fontWeight) }.getOrNull() ?: Typeface.DEFAULT

    companion object {
        private const val DEBOUNCE_MS = 250L
        private const val PARSE_DEBOUNCE_MS = 600L
        /** Weak: a popup left open when the reader is destroyed must not pin the activity. */
        private var current: WeakReference<ReadingSettingsPopup>? = null

        fun tapModeLabel(m: TapZoneMode): String = when (m) {
            TapZoneMode.LEFT_RIGHT -> "좌우 (왼쪽 = 이전, 오른쪽 = 다음)"
            TapZoneMode.ALL_NEXT -> "어디든 다음 (왼쪽 끝 = 이전)"
            TapZoneMode.ALL_PREV -> "어디든 이전 (오른쪽 끝 = 다음)"
            TapZoneMode.TOP_BOTTOM -> "위아래 (위 = 이전, 아래 = 다음)"
            TapZoneMode.CUSTOM -> "사용자 지정 (3×3)"
        }

        fun alignLabel(a: Align): String = if (a == Align.LEFT) "왼쪽 정렬" else "양쪽 정렬"

        fun breakLabel(m: LineBreakMode): String = if (m == LineBreakMode.CHAR) "글자 단위" else "어절 단위 (단어 유지)"

        fun blankLabel(m: Int): String = when (m) {
            ParseOptions.BLANK_REMOVE_ALL -> "모두 제거"
            ParseOptions.BLANK_COLLAPSE -> "여러 줄을 하나로"
            ParseOptions.BLANK_KEEP -> "그대로 유지"
            else -> "자동"
        }

        fun joinLabel(m: Int): String = when (m) {
            0 -> "끄기"
            2 -> "항상"
            else -> "자동"
        }

        fun encodingLabel(enc: String): String = when {
            enc.isBlank() -> "자동 감지"
            enc.equals("MS949", true) -> "MS949 (CP949 · 한글 완성형 확장)"
            else -> enc
        }
    }
}
