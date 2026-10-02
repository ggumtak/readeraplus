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
import android.widget.TextView
import com.ggumtak.readeraplus.R
import com.ggumtak.readeraplus.data.Library
import com.ggumtak.readeraplus.engine.Align
import com.ggumtak.readeraplus.engine.LineBreakMode
import com.ggumtak.readeraplus.format.BookFormat
import com.ggumtak.readeraplus.format.ParseOptions
import com.ggumtak.readeraplus.format.txt.TxtDocuments
import com.ggumtak.readeraplus.reader.ReaderFormat
import com.ggumtak.readeraplus.reader.ReaderHost
import com.ggumtak.readeraplus.reader.VolumeMode
import com.ggumtak.readeraplus.reader.KeyMap
import com.ggumtak.readeraplus.reader.withTxt
import com.ggumtak.readeraplus.render.Covers
import com.ggumtak.readeraplus.render.FontManager
import com.ggumtak.readeraplus.settings.ReaderSettings
import com.ggumtak.readeraplus.settings.Settings
import com.ggumtak.readeraplus.settings.StylePreset
import com.ggumtak.readeraplus.settings.TapZoneMode
import com.ggumtak.readeraplus.settings.UserStyle
import com.ggumtak.readeraplus.settings.UserStyles
import com.ggumtak.readeraplus.ui.kit.Ink
import com.ggumtak.readeraplus.ui.kit.alert
import com.ggumtak.readeraplus.ui.kit.borderBox
import com.ggumtak.readeraplus.ui.kit.confirm
import com.ggumtak.readeraplus.ui.kit.dp
import com.ggumtak.readeraplus.ui.kit.horizontal
import com.ggumtak.readeraplus.ui.kit.icon
import com.ggumtak.readeraplus.ui.kit.label
import com.ggumtak.readeraplus.ui.kit.lockWidthForValues
import com.ggumtak.readeraplus.ui.kit.lp
import com.ggumtak.readeraplus.ui.kit.pressableBackground
import com.ggumtak.readeraplus.ui.kit.prompt
import com.ggumtak.readeraplus.ui.kit.showNoAnim
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
 * Compact reading-settings popup for the ~6" 360×720 dp e-ink screen: the screen's width but 4 dp on each side
 * (≤ 420 dp, [PopupGeometry.width]), at most 55% of the height (scrolls), at the top right. The reader's bars are
 * hidden while it opens (unless pinned; then it sits under the top bar) so the lower half of the page stays in view as
 * the preview. Plain 36 dp rows split by 1px lines, the value and its "− +" buttons on the label's row, no card boxes,
 * black on white, no animations: the main section ([PopupGeometry.MAIN_ROWS] rows) is 360 dp, half the screen, and
 * needs no scrolling.
 *
 * Order: 스타일 ([웹소설] [전자책] [종이책] [내 스타일 ▾]; the matching one inverted), 글꼴, 글자 크기, 굵기, 줄 간격,
 * 문단 간격, 들여쓰기, 정렬, 줄바꿈, then a collapsed "더보기" with everything else (page turning, letter spacing,
 * margins, status bar, invert, TXT / EPUB options, 기본값 복원, 넘김·화면 설정).
 *
 * Every change but the TXT options is applied through [ReaderHost.applySettings] with the GLOBAL settings; steppers
 * are debounced (250 ms) so repeated taps cost one re-layout. The TXT options of a TXT book are this book's own
 * (T1-9, "TXT 파일 · 이 책에만 적용", through [TxtOverrideHost]): the rows show the effective values and a change
 * becomes the book's override after the reparse debounce (one re-parse of this book per burst of taps; the global
 * defaults and other books' indexes stay untouched). A host without [TxtOverrideHost] edits the global options.
 */
internal class ReadingSettingsPopup(private val host: ReaderHost, private val anchor: View) {
    private val ctx = host.activity
    /** The open book (the popup is only shown while one is open; host.book throws between books). */
    private val book = host.book
    private var cur: ReaderSettings = Settings.reader
    private val handler = Handler(Looper.getMainLooper())
    private var dirty = false
    private val applyRunnable = Runnable { flush() }
    /** Per-book TXT options (T1-9); null = the host has none: the TXT rows edit the global settings in [cur]. */
    private val txtHost: TxtOverrideHost? = host as? TxtOverrideHost
    /** The TXT options the rows show: the book's effective ones (per book), else [cur]'s. Only its TXT fields count. */
    private var txt: ReaderSettings = txtHost?.let { Settings.reader.withTxt(it.txtOverride) } ?: cur
    private var txtDirty = false
    private val txtRunnable = Runnable { flushTxt() }
    /** "모든 TXT 기본값으로 저장" / "이 책 설정 지우기": gray while the book has no TXT options of its own. */
    private val txtActionLabels = ArrayList<TextView>(2)
    private val scope = MainScope()
    private var popup: PopupWindow? = null
    private lateinit var scroll: MaxHeightScrollView
    private var popupWidth = 0
    /** The "스타일" toggles, re-marked after every change (a tweak can make the settings match / leave a preset). */
    private val presetButtons = ArrayList<Pair<StylePreset, TextView>>()
    /** "내 스타일 ▾": the matching saved style's name (inverted), else "내 스타일". */
    private var userButton: TextView? = null

    fun show() {
        current?.get()?.dismiss()
        current = WeakReference(this)
        val dm = ctx.resources.displayMetrics
        val root = anchor.rootView
        val screenW = root.width.takeIf { it > 0 } ?: dm.widthPixels
        val screenH = root.height.takeIf { it > 0 } ?: dm.heightPixels
        popupWidth = PopupGeometry.width(screenW, dm.density)
        // The popup is a live preview: every change re-lays out the page, so as much of the page as possible must
        // stay in view. Unless the bars are pinned (then the page is laid out between them and hiding them would
        // re-lay it out), the reader's bars are hidden and the popup takes the top bar's place, leaving the lower
        // half of the page visible instead of a strip between the popup and the bottom bar.
        val hideBars = !Settings.app.pinChrome
        var anchorBottom = ctx.dp(56)
        if (hideBars) {
            anchorBottom = Overlay.topInset(root)
        } else if (anchor.isAttachedToWindow && anchor.height > 0) {
            val loc = IntArray(2)
            anchor.getLocationInWindow(loc)
            anchorBottom = loc[1] + anchor.height
        }
        val place = PopupGeometry.settings(screenH, anchorBottom, dm.density)

        scroll = MaxHeightScrollView(ctx, place.height).apply { isVerticalScrollBarEnabled = true }
        scroll.addView(buildContent(), FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        val frame = FrameLayout(ctx).apply {
            background = ctx.borderBox()
            setPadding(1, 1, 1, 1)
            addView(scroll, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        val pw = PopupWindow(frame, popupWidth, WRAP_CONTENT, true).apply {
            animationStyle = 0
            elevation = 0f
            isOutsideTouchable = true
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setOnDismissListener {
                flush()
                flushTxt()
                scope.cancel()
                presetButtons.clear()
                userButton = null
                txtActionLabels.clear()
                if (current?.get() === this@ReadingSettingsPopup) current = null
            }
        }
        popup = pw
        try {
            // Same UI message as the popup: one e-ink update for both.
            if (hideBars) runCatching { host.setChromeVisible(false) }
            pw.showAtLocation(root, Gravity.TOP or Gravity.END, ctx.dp(4), place.top)
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
        refreshPresets()
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
        // Global settings only: with per-book TXT options the popup never edits the global ones, and they may have
        // changed underneath ("모든 TXT 기본값으로 저장"): always pass the saved ones on.
        host.applySettings(if (txtHost != null) TxtEdits.withTxtFrom(cur, Settings.reader) else cur)
    }

    /** A TXT option changed: this book's override after the reparse debounce ([immediate]: now), or the global one. */
    private fun updateTxt(next: ReaderSettings, immediate: Boolean = false) {
        if (TxtEdits.sameTxt(next, txt)) return
        txt = next
        if (txtHost == null) {
            update(TxtEdits.withTxtFrom(cur, next))
            return
        }
        txtDirty = true
        handler.removeCallbacks(txtRunnable)
        if (immediate) flushTxt() else handler.postDelayed(txtRunnable, PARSE_DEBOUNCE_MS)
        refreshTxtActions()
    }

    /** Hands the pending TXT change to the host: the override the effective values need (one re-parse, if any). */
    private fun flushTxt() {
        handler.removeCallbacks(txtRunnable)
        if (!txtDirty) return
        txtDirty = false
        val h = txtHost ?: return
        if (!sameBook()) return
        h.applyTxtOverride(TxtEdits.overrideFor(Settings.reader, txt))
    }

    /** Whether the book has TXT options of its own (pending ones included). */
    private fun hasOwnTxt(): Boolean {
        val h = txtHost ?: return false
        return TxtEdits.overrideFor(Settings.reader, txt) != null || (!txtDirty && h.txtOverride?.isEmpty == false)
    }

    private fun refreshTxtActions() {
        val on = hasOwnTxt()
        for (v in txtActionLabels) v.setTextColor(if (on) Ink.BLACK else Ink.GRAY)
    }

    /** The reader still shows the book this popup was opened for. */
    private fun sameBook(): Boolean = runCatching { host.book.id }.getOrNull() == book.id

    private fun rebuild() {
        val y = scroll.scrollY
        scroll.removeAllViews()
        scroll.addView(buildContent(), FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        scroll.post { scroll.scrollTo(0, y) }
    }

    /** One-tap style: applies [p]'s typography at once (one re-layout) and refreshes every row. */
    private fun applyPreset(p: StylePreset) {
        // A pending stepper change goes first, so the preset is applied on top of what the reader shows.
        flush()
        val next = p.applyTo(cur)
        if (next == cur) {
            refreshPresets()
            return
        }
        update(next)
        rebuild()
    }

    private fun refreshPresets() {
        if (presetButtons.isEmpty()) return
        val sel = StyleChoice.selected(cur)
        for ((p, v) in presetButtons) setCompactToggle(v, p == sel)
        userButton?.let { markUserButton(it, StyleChoice.selectedUser(cur, Settings.userStyles)) }
    }

    /** Applies saved style [u] (a pending stepper change first), like a preset: one re-layout. */
    private fun applyUserStyle(u: UserStyle) {
        flush()
        val next = u.applyTo(cur)
        if (next == cur) {
            refreshPresets()
            return
        }
        update(next)
        rebuild()
    }

    // ------------------------------------------------------------------ content

    private fun buildContent(): LinearLayout {
        presetButtons.clear()
        txtActionLabels.clear()
        val root = ctx.vertical { setBackgroundColor(Ink.WHITE) }
        root.addView(styleRow(), lp())
        addTypography(root)
        val more = ctx.vertical()
        root.addView(moreRow(more), lp())
        root.addView(more, lp())
        if (moreExpanded) fillMore(more) else more.visibility = View.GONE
        return root
    }

    private fun styleRow(): LinearLayout {
        val row = ctx.compactRow(topLine = false)
        row.addView(ctx.label("스타일", Compact.LABEL_SP, maxLines = 1).apply {
            setAutoSizeTextTypeUniformWithConfiguration(9, Compact.LABEL_SP.toInt(), 1, android.util.TypedValue.COMPLEX_UNIT_SP)
        }, lp(0, WRAP_CONTENT, 1f))
        val sel = StyleChoice.selected(cur)
        for (p in StylePreset.entries) {
            val b = ctx.compactToggle(p.label, p == sel) { applyPreset(p) }
            b.minWidth = ctx.dp(40)
            b.setAutoSizeTextTypeUniformWithConfiguration(9, Compact.TOGGLE_SP.toInt(), 1, android.util.TypedValue.COMPLEX_UNIT_SP)
            b.contentDescription = "${p.label}: ${p.description}"
            b.setOnLongClickListener { ctx.toast(p.description); true }
            presetButtons += p to b
            row.addView(b, LinearLayout.LayoutParams(ctx.dp(52), WRAP_CONTENT).apply { leftMargin = ctx.dp(4) })
        }
        // The saved styles are read (parsed) here, on the popup's first use: never on the reader's cold start.
        val mine = ctx.label(StyleChoice.USER_LABEL, Compact.TOGGLE_SP, maxLines = 1).apply {
            setAutoSizeTextTypeUniformWithConfiguration(9, Compact.TOGGLE_SP.toInt(), 1, android.util.TypedValue.COMPLEX_UNIT_SP)
            gravity = Gravity.CENTER
            minHeight = ctx.dp(28)
            setPadding(ctx.dp(6), 0, ctx.dp(2), 0)
            compoundDrawablePadding = 0
            ctx.getDrawable(R.drawable.ic_arrow_drop_down)?.mutate()?.let { d ->
                d.setBounds(0, 0, ctx.dp(18), ctx.dp(18))
                setCompoundDrawablesRelative(null, null, d, null)
            }
            contentDescription = "내 스타일: 저장한 스타일 고르기 · 저장 · 관리"
            setOnClickListener { userStylesMenu(row) }
        }
        userButton = mine
        markUserButton(mine, StyleChoice.selectedUser(cur, Settings.userStyles))
        // Fixed width: a long style name is cut ("…"), and choosing a style never moves the presets.
        row.addView(mine, LinearLayout.LayoutParams(ctx.dp(USER_BUTTON_DP), WRAP_CONTENT).apply { leftMargin = ctx.dp(4) })
        return row
    }

    private fun markUserButton(v: TextView, match: UserStyle?) {
        val label = StyleChoice.userLabel(match)
        if (v.text.toString() != label) v.text = label
        val selected = match != null
        if (v.isSelected == selected && v.background != null) return
        setCompactToggle(v, selected)
        v.compoundDrawablesRelative[2]?.setTint(if (selected) Ink.WHITE else Ink.BLACK)
    }

    // ------------------------------------------------------------------ 내 스타일 (T1-8)

    private fun userStylesMenu(row: View) {
        val list = Settings.userStyles
        val entries = ArrayList<ListEntry>(list.size + 2)
        for (u in list) entries += ListEntry(u.name, checked = u.matches(cur)) { applyUserStyle(u) }
        entries += ListEntry("현재 설정을 새 스타일로 저장…", action = true) { saveNewStyle() }
        if (list.isNotEmpty()) entries += ListEntry("관리…", action = true) { manageStyles() }
        list(row, entries)
    }

    private fun saveUserStyles(list: List<UserStyle>) {
        Settings.saveUserStyles(list)
        refreshPresets()
    }

    private fun saveNewStyle() {
        val list = Settings.userStyles
        if (list.size >= UserStyles.MAX) {
            ctx.toast("스타일은 ${UserStyles.MAX}개까지 저장할 수 있습니다. '관리…'에서 하나를 지운 뒤 저장하세요")
            return
        }
        val suggested = UserStyles.defaultName(list)
        ctx.prompt("새 스타일 이름", suggested, "${UserStyles.MAX_NAME}자까지") { text ->
            val now = Settings.userStyles
            val name = UserStyles.cleanName(text).ifEmpty { suggested }
            val style = UserStyle.from(name, cur)
            when {
                now.any { it.name == name } ->
                    ctx.confirm("같은 이름의 스타일", "'$name' 스타일을 현재 설정으로 덮어쓸까요?", "덮어쓰기") {
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
        PanelRegistry.dialog(
            ctx,
            ctx.alert().setTitle("내 스타일 관리")
                .setItems(list.map { it.name }.toTypedArray()) { _, which -> list.getOrNull(which)?.let { styleActions(it) } }
                .setNegativeButton("닫기", null)
                .showNoAnim(),
        )
    }

    private fun styleActions(u: UserStyle) {
        val acts = arrayOf("이름 바꾸기", "현재 설정으로 덮어쓰기", "삭제")
        PanelRegistry.dialog(
            ctx,
            ctx.alert().setTitle(u.name)
                .setItems(acts) { _, which ->
                    when (which) {
                        0 -> ctx.prompt("스타일 이름 바꾸기", u.name, "${UserStyles.MAX_NAME}자까지") { text ->
                            val next = StyleChoice.rename(Settings.userStyles, u.name, text)
                            if (next == null) ctx.toast("이름이 비었거나 이미 있는 이름입니다") else saveUserStyles(next)
                        }
                        1 -> ctx.confirm("현재 설정으로 덮어쓰기", "'${u.name}' 스타일을 지금 설정으로 바꿀까요?", "덮어쓰기") {
                            saveUserStyles(StyleChoice.put(Settings.userStyles, UserStyle.from(u.name, cur)))
                        }
                        else -> ctx.confirm("스타일 삭제", "'${u.name}' 스타일을 지울까요?", "삭제") {
                            saveUserStyles(StyleChoice.remove(Settings.userStyles, u.name))
                        }
                    }
                }
                .setNegativeButton("취소", null)
                .showNoAnim(),
        )
    }

    private fun addTypography(root: LinearLayout) {
        root.addView(dropdownRow("글꼴", fontName(cur.fontId)) { row, value ->
            FontChooser.show(ctx, cur.fontId, anchor = row, widthPx = listWidth(row), rightInsetPx = ctx.dp(8)) { id ->
                if (popup?.isShowing == true) {
                    update(cur.copy(fontId = id))
                    value.text = fontName(id)
                    value.typeface = fontTypeface(id)
                } else {
                    host.applySettings(Settings.reader.copy(fontId = id))
                }
            }
        }.also { r -> r.findViewWithTag<TextView>(VALUE_TAG)?.typeface = fontTypeface(cur.fontId) })

        root.addView(stepperRow("글자 크기", cur.fontSizeSp, ReaderSettings.MIN_FONT_SP, ReaderSettings.MAX_FONT_SP, 0.5f, Fmt::number) {
            update(cur.copy(fontSizeSp = it), debounce = true)
        })
        root.addView(stepperRow("굵기", cur.fontWeight.toFloat(), 100f, 900f, 50f, { Fmt.weight(it.toInt()) }) {
            update(cur.copy(fontWeight = it.toInt()), debounce = true)
        })
        root.addView(stepperRow("줄 간격", cur.lineHeightPct.toFloat(), 100f, 300f, 5f, { Fmt.pct(it.toInt()) }) {
            update(cur.copy(lineHeightPct = it.toInt()), debounce = true)
        })
        root.addView(stepperRow("문단 간격", cur.paragraphSpacingPct.toFloat(), 0f, 300f, 10f, { Fmt.pct(it.toInt()) }) {
            update(cur.copy(paragraphSpacingPct = it.toInt()), debounce = true)
        })
        root.addView(stepperRow("들여쓰기", cur.indentPct.toFloat(), 0f, 400f, 25f, { Fmt.em(it.toInt()) }) {
            update(cur.copy(indentPct = it.toInt()), debounce = true)
        })
        root.addView(segmentRow("정렬", listOf("왼쪽" to Align.LEFT, "양쪽" to Align.JUSTIFY), cur.align) {
            update(cur.copy(align = it))
        })
        root.addView(segmentRow("줄바꿈", listOf("어절" to LineBreakMode.WORD, "글자" to LineBreakMode.CHAR), cur.lineBreak) {
            update(cur.copy(lineBreak = it))
        })
    }

    /** "더보기 ▾" / "접기 ▴": shows the rest of the settings (built on first expand; remembered for the process). */
    private fun moreRow(more: LinearLayout): LinearLayout {
        lateinit var text: TextView
        lateinit var arrow: ImageView
        val row = ctx.compactRow {
            moreExpanded = !moreExpanded
            if (moreExpanded && more.childCount == 0) fillMore(more)
            more.visibility = if (moreExpanded) View.VISIBLE else View.GONE
            text.text = if (moreExpanded) "접기" else "더보기"
            arrow.setImageResource(if (moreExpanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more)
        }
        text = ctx.label(if (moreExpanded) "접기" else "더보기", Compact.LABEL_SP, bold = true)
        row.addView(text)
        val kinds = if (showTxt()) "TXT · EPUB" else "EPUB"
        row.addView(ctx.label("화면 터치 · 여백 · 상태 표시 · $kinds", Compact.SUMMARY_SP, color = Ink.GRAY, maxLines = 1).apply {
            setPadding(ctx.dp(10), 0, ctx.dp(4), 0)
        }, lp(0, WRAP_CONTENT, 1f))
        arrow = ctx.icon(if (moreExpanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more, 22)
        row.addView(arrow)
        return row
    }

    private fun fillMore(root: LinearLayout) {
        addPageTurning(root)
        root.addView(ctx.compactHeader("글자"), lp())
        root.addView(stepperRow("글자 간격", cur.letterSpacingPm.toFloat(), -100f, 200f, 10f, { Fmt.letterSpacing(it.toInt()) }) {
            update(cur.copy(letterSpacingPm = it.toInt()), debounce = true)
        })
        addPage(root)
        if (book.format == BookFormat.TXT) {
            addTxt(root)
            addEpub(root)
        } else {
            addEpub(root)
            if (showTxt()) addTxt(root)
        }
        addFooter(root)
    }

    /**
     * The TXT rows: always in a TXT book; in an EPUB only for a host without per-book TXT options (they would edit
     * nothing this book uses; the defaults have their own page, "TXT 기본 정리 설정").
     */
    private fun showTxt(): Boolean = book.format == BookFormat.TXT || txtHost == null

    private fun addPageTurning(root: LinearLayout) {
        val app = Settings.app
        root.addView(ctx.compactHeader("페이지 넘김"), lp())
        root.addView(dropdownRow("화면 터치", tapModeShort(app.tapZoneMode)) { row, value ->
            val mode = Settings.app.tapZoneMode
            list(row, TapZoneMode.entries.map { m ->
                ListEntry(tapModeLabel(m), checked = m == mode) {
                    Settings.saveApp(Settings.app.copy(tapZoneMode = m))
                    value.text = tapModeShort(m)
                    if (m == TapZoneMode.CUSTOM) SettingsActivity.open(ctx, SettingsActivity.PAGE_PAGE_TURNING)
                }
            })
        })
        root.addView(dropdownRow("볼륨 키", if (KeyMap.volumeBound(app)) "키 지정" else
            ReaderFormat.volumeModeShort(KeyMap.volumeMode(app))) { row, value ->
            if (KeyMap.volumeBound(Settings.app)) {
                ctx.toast("키 지정에서 볼륨 키 동작을 정했습니다")
                return@dropdownRow
            }
            val current = KeyMap.volumeMode(Settings.app)
            list(row, listOf(VolumeMode.DOWN_NEXT, VolumeMode.UP_NEXT, VolumeMode.OFF).map { mode ->
                ListEntry(ReaderFormat.volumeMode(mode), checked = mode == current) {
                    Settings.saveApp(KeyMap.withVolumeMode(Settings.app, mode))
                    value.text = ReaderFormat.volumeModeShort(mode)
                }
            })
        })
    }

    private fun addPage(root: LinearLayout) {
        root.addView(ctx.compactHeader("페이지"), lp())
        val marginH = stepperRow("좌우 여백", cur.marginLeftDp.toFloat(), 0f, 80f, 2f, { "${it.toInt()}dp" }) {
            update(cur.copy(marginLeftDp = it.toInt(), marginRightDp = it.toInt()), debounce = true)
        }
        val marginV = stepperRow("상하 여백", cur.marginTopDp.toFloat(), 0f, 80f, 2f, { "${it.toInt()}dp" }) {
            update(cur.copy(marginTopDp = it.toInt(), marginBottomDp = it.toInt()), debounce = true)
        }
        root.addView(switchRow("페이지 여백", cur.pageMargins) { v ->
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
        root.addView(switchRow("상단 챕터 제목", cur.showHeader) { v ->
            update(cur.copy(showHeader = v))
            statusVisible()
        })
        val footerItems = footerItems()
        root.addView(switchRow("하단 정보 표시", cur.showFooter) { v ->
            update(cur.copy(showFooter = v))
            footerItems.visibility = if (v) View.VISIBLE else View.GONE
            statusVisible()
        })
        footerItems.visibility = if (cur.showFooter) View.VISIBLE else View.GONE
        root.addView(footerItems, lp())
        statusSize = stepperRow("상태 표시 글자 크기", cur.statusFontSizeSp, 8f, 16f, 0.5f, Fmt::number) {
            update(cur.copy(statusFontSizeSp = it), debounce = true)
        }
        statusVisible()
        root.addView(statusSize)
        root.addView(switchRow("흑백 반전", cur.invert, "검은 바탕에 흰 글씨") { v -> update(cur.copy(invert = v)) })
        root.addView(switchRow("외톨이 줄 방지", cur.widowOrphanControl, "문단의 첫 줄/마지막 줄이 홀로 남지 않게") { v ->
            update(cur.copy(widowOrphanControl = v))
        })
    }

    private fun addTxt(root: LinearLayout) {
        val perBook = txtHost != null && book.format == BookFormat.TXT
        root.addView(ctx.compactHeader(if (perBook) "TXT 파일 · 이 책에만 적용" else "TXT 파일"), lp())
        if (book.format == BookFormat.TXT) {
            root.addView(dropdownRow(if (perBook) "인코딩" else "인코딩 (이 책)", encodingShort(book.encoding)) { row, _ ->
                val options = listOf("") + TxtDocuments.ENCODINGS
                list(row, options.map { enc ->
                    ListEntry(encodingLabel(enc), checked = enc == book.encoding) { changeEncoding(enc) }
                })
            })
        }
        root.addView(dropdownRow("빈 줄 처리", blankLabel(txt.txtBlankLines)) { row, value ->
            val modes = listOf(ParseOptions.BLANK_AUTO, ParseOptions.BLANK_REMOVE_ALL, ParseOptions.BLANK_COLLAPSE, ParseOptions.BLANK_KEEP)
            list(row, modes.map { m ->
                ListEntry(blankLabel(m), checked = m == txt.txtBlankLines) {
                    updateTxt(txt.copy(txtBlankLines = m))
                    value.text = blankLabel(m)
                }
            })
        })
        root.addView(switchRow("원본 들여쓰기 제거", txt.txtStripIndent, "파일의 앞 공백 대신 들여쓰기 설정 사용") { v ->
            updateTxt(txt.copy(txtStripIndent = v))
        })
        root.addView(segmentRow("끊어진 줄 합치기", listOf(joinLabel(1) to 1, joinLabel(2) to 2, joinLabel(0) to 0), txt.txtJoinWrappedLines) {
            updateTxt(txt.copy(txtJoinWrappedLines = it))
        })
        root.addView(switchRow("챕터 자동 인식", txt.txtDetectChapters, "목차 만들기 (1화, 제1장, 프롤로그 …)") { v ->
            updateTxt(txt.copy(txtDetectChapters = v))
        })
        root.addView(switchRow("챕터 제목 강조", txt.txtEmphasizeHeadings, "굵게 · 크게 · 가운데") { v ->
            updateTxt(txt.copy(txtEmphasizeHeadings = v))
        })
        root.addView(dropdownRow("챕터 규칙 (정규식)", txt.txtChapterRegex.ifBlank { "없음" }) { _, value ->
            ctx.prompt("챕터 규칙 (정규식)", txt.txtChapterRegex, "예: ^제\\s*\\d+\\s*화.*") { text ->
                val t = text.trim()
                val err = if (t.isEmpty()) null else runCatching { Regex(t) }.exceptionOrNull()
                if (err != null) {
                    ctx.toast(ErrorText.regex(err))
                } else {
                    updateTxt(txt.copy(txtChapterRegex = t))
                    value.text = t.ifBlank { "없음" }
                }
            }
        })
        root.addView(dropdownRow("치환 규칙", Fmt.rulesLabel(txt.txtReplaceRules)) { _, value ->
            // A pending stepper change goes first: the manager is a full-screen window over the page.
            flush()
            RulesDialog.show(ctx, if (perBook) "치환 규칙 · 이 책" else "치환 규칙", txt.txtReplaceRules) { text ->
                // Saved also when the popup went away meanwhile, as long as the same book is shown.
                if (!sameBook()) return@show
                updateTxt(txt.copy(txtReplaceRules = text.trimEnd()), immediate = true)
                value.text = Fmt.rulesLabel(txt.txtReplaceRules)
            }
        })
        if (perBook) {
            root.addView(actionRow("모든 TXT 기본값으로 저장", "이 책의 설정을 모든 TXT 파일에 사용") { saveTxtAsDefaults() })
            root.addView(actionRow("이 책 설정 지우기 (기본값 사용)", null) { clearBookTxt() })
            refreshTxtActions()
        }
    }

    /** "모든 TXT 기본값으로 저장": the effective options become the defaults; the book's own options are cleared. */
    private fun saveTxtAsDefaults() {
        val h = txtHost ?: return
        if (!hasOwnTxt()) {
            ctx.toast("이 책은 이미 기본값을 따릅니다")
            return
        }
        ctx.confirm(
            "모든 TXT 기본값으로 저장",
            "이 책의 TXT 정리 설정을 모든 TXT 파일의 기본값으로 저장할까요? 다른 TXT 책은 다음에 열 때 새 설정으로 다시 정리됩니다.",
            "저장",
        ) {
            if (popup?.isShowing != true || !sameBook()) return@confirm
            flushTxt()
            h.saveTxtAsDefaults()
            txt = Settings.reader.withTxt(h.txtOverride)
            refreshTxtActions()
        }
    }

    /** "이 책 설정 지우기 (기본값 사용)": the book follows the TXT defaults again (re-parsed when that differs). */
    private fun clearBookTxt() {
        val h = txtHost ?: return
        if (!hasOwnTxt()) {
            ctx.toast("이 책은 이미 기본값을 따릅니다")
            return
        }
        ctx.confirm("이 책 설정 지우기", "이 책에만 적용한 TXT 설정(치환 규칙 포함)을 지우고 기본값을 사용할까요?", "지우기") {
            if (popup?.isShowing != true || !sameBook()) return@confirm
            handler.removeCallbacks(txtRunnable)
            txtDirty = false
            h.applyTxtOverride(null)
            txt = Settings.reader
            rebuild()
        }
    }

    private fun addEpub(root: LinearLayout) {
        root.addView(ctx.compactHeader("EPUB 파일"), lp())
        root.addView(switchRow("출판사 스타일 사용", cur.epubPublisherStyles, "책에 지정된 정렬 · 여백 · 제목 크기") { v ->
            update(cur.copy(epubPublisherStyles = v))
        })
    }

    private fun addFooter(root: LinearLayout) {
        val row = ctx.horizontal {
            setPadding(ctx.dp(Compact.PAD_DP), ctx.dp(10), ctx.dp(Compact.PAD_DP), ctx.dp(10))
            background = ctx.compactRowBackground(pressable = false, topLine = true)
        }
        row.addView(footerButton("기본값 복원") {
            ctx.confirm("기본값 복원", "글꼴 · 간격 · 여백 · 상태 표시를 기본값으로 되돌릴까요? TXT 정리 설정은 그대로입니다.", "복원") {
                // TXT options stay: resetting them would re-parse every TXT book on its next open.
                update(TxtEdits.withTxtFrom(ReaderSettings(), cur))
                rebuild()
            }
        }, lp(0, WRAP_CONTENT, 1f).apply { rightMargin = ctx.dp(8) })
        row.addView(footerButton("넘김·화면 설정") {
            flush()
            flushTxt()
            popup?.dismiss()
            SettingsActivity.open(ctx, SettingsActivity.PAGE_PAGE_TURNING)
        }, lp(0, WRAP_CONTENT, 1f))
        root.addView(row, lp())
    }

    // ------------------------------------------------------------------ rows

    /** Label left, value (15 sp) + ▾ right; [onClick] receives the row (list anchor) and the value view. */
    private fun dropdownRow(title: String, value: String, onClick: (View, TextView) -> Unit): LinearLayout {
        lateinit var row: LinearLayout
        lateinit var valueView: TextView
        row = ctx.compactRow { onClick(row, valueView) }
        row.addView(ctx.compactLabelBlock(title), LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        valueView = ctx.label(value, Compact.VALUE_SP, maxLines = 1).apply {
            tag = VALUE_TAG
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(ctx.dp(10), 0, 0, 0)
        }
        row.addView(valueView, lp(0, WRAP_CONTENT, 1f))
        row.addView(ctx.icon(R.drawable.ic_arrow_drop_down, 22))
        return row
    }

    /** A tappable row with a bold action label (and an optional summary); no value. */
    private fun actionRow(title: String, summary: String?, onClick: () -> Unit): LinearLayout {
        val row = ctx.compactRow { onClick() }
        val block = ctx.compactLabelBlock(title, summary)
        (block as? TextView ?: (block as? LinearLayout)?.getChildAt(0) as? TextView)?.let { t ->
            t.typeface = android.graphics.Typeface.DEFAULT_BOLD
            txtActionLabels += t
        }
        row.addView(block, lp(0, WRAP_CONTENT, 1f))
        return row
    }

    /** Label (and optional 12 sp summary) left, on/off toggle right; the whole row toggles. */
    private fun switchRow(title: String, checked: Boolean, summary: String? = null, onChange: (Boolean) -> Unit): LinearLayout {
        val sw = EinkToggle(ctx, checked)
        val row = ctx.compactRow {
            sw.toggle()
            onChange(sw.isChecked)
        }
        row.addView(ctx.compactLabelBlock(title, summary), lp(0, WRAP_CONTENT, 1f))
        row.addView(sw, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = ctx.dp(10); rightMargin = ctx.dp(4) })
        return row
    }

    /** Label left, "−  value  +" right (36 dp buttons) on the same row. */
    private fun stepperRow(
        title: String,
        value: Float,
        min: Float,
        max: Float,
        step: Float,
        format: (Float) -> String,
        onChange: (Float) -> Unit,
    ): LinearLayout {
        var v = value
        val row = ctx.compactRow()
        row.addView(ctx.compactLabelBlock(title), lp(0, WRAP_CONTENT, 1f))
        val valueView = ctx.label(format(v), Compact.VALUE_SP, maxLines = 1).apply { gravity = Gravity.CENTER }
        // Fixed width for the widest possible value (at least the common column width): the − / + buttons stay put
        // while tapping repeatedly, and line up with the other rows' buttons.
        valueView.lockWidthForValues(min, max, step, format, minPx = ctx.dp(Compact.STEP_VALUE_DP))
        fun set(nv: Float) {
            val s = Fmt.stepFloat(nv, step, min, max)
            if (s == v) return
            v = s
            valueView.text = format(v)
            onChange(v)
        }
        row.addView(ctx.compactIcon(R.drawable.ic_do_not_disturb_on, "$title 줄이기") { set(v - step) })
        row.addView(valueView)
        row.addView(ctx.compactIcon(R.drawable.ic_add_circle, "$title 늘리기") { set(v + step) })
        return row
    }

    /** Label left, one inverted toggle per option right (single choice). */
    private fun <T> segmentRow(title: String, options: List<Pair<String, T>>, selected: T, onPick: (T) -> Unit): LinearLayout {
        val row = ctx.compactRow()
        row.addView(ctx.compactLabelBlock(title), lp(0, WRAP_CONTENT, 1f))
        val views = ArrayList<TextView>(options.size)
        options.forEachIndexed { i, (text, value) ->
            val b = ctx.compactToggle(text, value == selected) {
                views.forEachIndexed { j, t -> setCompactToggle(t, j == i) }
                onPick(value)
            }
            views += b
            row.addView(b, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = ctx.dp(4) })
        }
        return row
    }

    private fun footerItems(): LinearLayout {
        val c = ctx.vertical()
        fun item(text: String, checked: Boolean, onChange: (Boolean) -> ReaderSettings) {
            var on = checked
            val box = ctx.icon(if (on) R.drawable.ic_check_box else R.drawable.ic_check_box_outline_blank, 20)
            val r = ctx.compactRow {
                on = !on
                box.setImageResource(if (on) R.drawable.ic_check_box else R.drawable.ic_check_box_outline_blank)
                update(onChange(on))
            }
            r.setPadding(ctx.dp(Compact.PAD_DP + 12), r.paddingTop, r.paddingRight, r.paddingBottom)
            r.addView(box)
            r.addView(ctx.label(text, Compact.LABEL_SP).apply { setPadding(ctx.dp(10), 0, 0, 0) }, lp(0, WRAP_CONTENT, 1f))
            c.addView(r, lp())
        }
        item("쪽수 (12 / 3259)", cur.footerPage) { cur.copy(footerPage = it) }
        item("회차 (123/540화)", cur.footerEpisode) { cur.copy(footerEpisode = it) }
        item("챕터 남은 쪽수", cur.footerChapterLeft) { cur.copy(footerChapterLeft = it) }
        c.addView(timeLeftRow(), lp())
        item("진행률 (%)", cur.footerPercent) { cur.copy(footerPercent = it) }
        item("시계", cur.footerClock) { cur.copy(footerClock = it) }
        item("배터리", cur.footerBattery) { cur.copy(footerBattery = it) }
        return c
    }

    /** "남은 시간" (T1-7): [끔] [이 화] [책], indented like the status items above and below it. */
    private fun timeLeftRow(): LinearLayout {
        val options = listOf(
            "끔" to ReaderSettings.TIME_LEFT_OFF,
            "이 화" to ReaderSettings.TIME_LEFT_EPISODE,
            "책" to ReaderSettings.TIME_LEFT_BOOK,
        )
        val row = segmentRow("남은 시간", options, cur.footerTimeLeft) { update(cur.copy(footerTimeLeft = it)) }
        // Lines up with the check boxes' labels (12 dp indent + 20 dp box + 10 dp gap).
        row.setPadding(ctx.dp(Compact.PAD_DP + 12 + 20 + 10), row.paddingTop, row.paddingRight, row.paddingBottom)
        return row
    }

    private fun footerButton(text: String, onClick: (View) -> Unit): TextView = ctx.label(text, Compact.LABEL_SP, bold = true).apply {
        gravity = Gravity.CENTER
        minHeight = ctx.dp(36)
        setPadding(ctx.dp(8), 0, ctx.dp(8), 0)
        background = android.graphics.drawable.LayerDrawable(arrayOf(pressableBackground(Ink.WHITE), ctx.borderBox(Color.TRANSPARENT, radiusDp = 3f)))
        setOnClickListener(onClick)
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
            flushTxt()
            popup?.dismiss()
            // The host caches the Book (and its encoding); reopening the activity re-reads it from the library.
            ctx.toast("인코딩: ${encodingLabel(enc)} — 다시 여는 중…")
            if (!ctx.isFinishing) ctx.recreate()
        }
    }

    /** Drop-down lists are a little narrower than the popup and right-aligned 8 dp inside the row. */
    private fun listWidth(row: View): Int = (row.width - ctx.dp(16)).coerceAtLeast(ctx.dp(160)).coerceAtMost(popupWidth)

    private fun list(row: View, entries: List<ListEntry>) {
        CompactList.show(ctx, row, entries, listWidth(row), rightInsetPx = ctx.dp(8))
    }

    private fun fontName(id: String): String = runCatching { FontManager.font(id)?.name }.getOrNull() ?: id

    /** Same (id, weight) the page renderer uses, so this is normally a cache hit. */
    private fun fontTypeface(id: String): Typeface = runCatching { FontManager.typeface(id, cur.fontWeight) }.getOrNull() ?: Typeface.DEFAULT

    companion object {
        private const val DEBOUNCE_MS = 250L
        private const val PARSE_DEBOUNCE_MS = 600L
        private const val VALUE_TAG = "value"
        /** Width of "내 스타일 ▾" (a saved style's name, cut to fit). */
        private const val USER_BUTTON_DP = 96
        /** Weak: a popup left open when the reader is destroyed must not pin the activity. */
        private var current: WeakReference<ReadingSettingsPopup>? = null
        /** "더보기" open / closed, kept for the process (the next popup opens the same way). */
        private var moreExpanded = false

        fun tapModeLabel(m: TapZoneMode): String = when (m) {
            TapZoneMode.LEFT_RIGHT -> "좌우 (왼쪽 = 이전, 오른쪽 = 다음)"
            TapZoneMode.ALL_NEXT -> "어디든 다음 (왼쪽 끝 = 이전)"
            TapZoneMode.ALL_PREV -> "어디든 이전 (오른쪽 끝 = 다음)"
            TapZoneMode.TOP_BOTTOM -> "위아래 (위 = 이전, 아래 = 다음)"
            TapZoneMode.CUSTOM -> "사용자 지정 (3×3)"
        }

        /** Short form for the row value (the list shows [tapModeLabel]). */
        fun tapModeShort(m: TapZoneMode): String = when (m) {
            TapZoneMode.LEFT_RIGHT -> "좌우"
            TapZoneMode.ALL_NEXT -> "어디든 다음"
            TapZoneMode.ALL_PREV -> "어디든 이전"
            TapZoneMode.TOP_BOTTOM -> "위아래"
            TapZoneMode.CUSTOM -> "사용자 지정"
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

        /** The reader's one encoding wording (the error panel's chooser and the library use it too). */
        fun encodingLabel(enc: String): String = ReaderFormat.encodingLabel(enc.trim())

        /** Short form for the row value: the list label without its note ("CP949", not "MS949"). */
        fun encodingShort(enc: String): String = encodingLabel(enc).substringBefore(" (")
    }
}
