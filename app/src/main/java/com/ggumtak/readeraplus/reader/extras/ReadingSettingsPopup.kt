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
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import com.ggumtak.readeraplus.R
import com.ggumtak.readeraplus.engine.Align
import com.ggumtak.readeraplus.engine.LineBreakMode
import com.ggumtak.readeraplus.format.ParseOptions
import com.ggumtak.readeraplus.reader.LayoutKeys
import com.ggumtak.readeraplus.reader.ReaderActivity
import com.ggumtak.readeraplus.reader.ReaderFormat
import com.ggumtak.readeraplus.reader.ReaderHost
import com.ggumtak.readeraplus.render.FontManager
import com.ggumtak.readeraplus.settings.ReaderSettings
import com.ggumtak.readeraplus.settings.Settings
import com.ggumtak.readeraplus.settings.SideMargin
import com.ggumtak.readeraplus.settings.VerticalMargin
import com.ggumtak.readeraplus.ui.kit.Ink
import com.ggumtak.readeraplus.ui.kit.borderBox
import com.ggumtak.readeraplus.ui.kit.dp
import com.ggumtak.readeraplus.ui.kit.horizontal
import com.ggumtak.readeraplus.ui.kit.icon
import com.ggumtak.readeraplus.ui.kit.label
import com.ggumtak.readeraplus.ui.kit.lockWidthForValues
import com.ggumtak.readeraplus.ui.kit.lp
import com.ggumtak.readeraplus.ui.kit.pressableBackground
import com.ggumtak.readeraplus.ui.kit.vertical
import com.ggumtak.readeraplus.ui.settings.SettingsActivity
import java.lang.ref.WeakReference

/**
 * The quick reading options (⚙) for the ~6" 360×720 dp e-ink screen: only what is changed while reading, with the
 * page in view as the preview. Centred, the screen's width but 8 dp on each side (≤ 400 dp, [PopupGeometry.width]),
 * 8 dp under the status bar; the reader's bars are hidden while it opens (one e-ink update with the popup), so the
 * lower part of the page stays visible. Black on white, no animations, no scrolling, nothing that expands:
 *
 * 전체 읽기 설정 › · [닫기] / 글자 크기 / 굵기 / 줄 간격 / 문단 간격 / 좌우 여백 / 상하 여백 (− value +, 48 dp buttons
 * on 48 dp rows) / 글꼴 (drop-down list) ([QUICK_HEIGHT_DP][PopupGeometry.QUICK_HEIGHT_DP] = 384 dp).
 *
 * Everything else (styles, the other spacings, page breaks, status bands, page turning, keys, the TXT and EPUB
 * options) lives in 설정 → 읽기 설정 ([SettingsActivity.PAGE_READING]), which "전체 읽기 설정" opens; these seven rows
 * are there too, with the same steps, ranges and values. Both edit the same GLOBAL [ReaderSettings] fields and reach
 * the page through the same path ([ReaderHost.applySettings] here, the reader's settings sync after 설정): steppers
 * are debounced (250 ms) so repeated taps cost one re-layout, the font applies at once. A change keeps the page's
 * first character.
 */
internal class ReadingSettingsPopup(private val host: ReaderHost, private val anchor: View) {
    private val ctx = host.activity
    /** What the rows show (the saved settings when the popup opened, with this popup's changes). */
    private var cur: ReaderSettings = Settings.reader
    private val handler = Handler(Looper.getMainLooper())
    private var dirty = false
    private val applyRunnable = Runnable { flush() }
    private var popup: PopupWindow? = null
    private var popupWidth = 0

    fun show() {
        current?.get()?.dismiss()
        current = WeakReference(this)
        val dm = ctx.resources.displayMetrics
        val root = anchor.rootView
        val screenW = root.width.takeIf { it > 0 } ?: dm.widthPixels
        val screenH = root.height.takeIf { it > 0 } ?: dm.heightPixels
        popupWidth = PopupGeometry.width(screenW, dm.density)
        // The popup is a live preview: every change re-lays out the page, so as much of the page as possible stays
        // in view. The reader's bars are hidden (pinned chrome is gone, U §2.6: the page never changes size for
        // them) and the popup sits 8 dp under the status bar, leaving the lower part of the page visible.
        val place = PopupGeometry.settings(screenH, Overlay.topInset(root), dm.density)

        // Scrolls only in a window too short for the seven rows (landscape phones, split screen); never on the Comet.
        val scroll = MaxHeightScrollView(ctx, place.height).apply { isVerticalScrollBarEnabled = true }
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
                if (current?.get() === this@ReadingSettingsPopup) current = null
            }
        }
        popup = pw
        try {
            // Same UI message as the popup: one e-ink update for both.
            runCatching { host.setChromeVisible(false) }
            pw.showAtLocation(root, Gravity.TOP or Gravity.CENTER_HORIZONTAL, 0, place.top)
            PanelRegistry.popup(ctx, pw)
        } catch (e: RuntimeException) {
            // BadTokenException / IllegalStateException: the reader window is going away.
            popup = null
            if (current?.get() === this) current = null
        }
    }

    fun dismiss() {
        popup?.dismiss()
    }

    // ------------------------------------------------------------------ state

    private fun update(new: ReaderSettings, debounce: Boolean = false) {
        if (new == cur) return
        cur = new
        dirty = true
        handler.removeCallbacks(applyRunnable)
        if (debounce) handler.postDelayed(applyRunnable, DEBOUNCE_MS) else flush()
    }

    /**
     * Hands the pending change to the reader: this popup's seven fields on top of the saved GLOBAL settings (never a
     * stale copy of the rest, which 설정 may have changed meanwhile; never this book's TXT options).
     */
    private fun flush() {
        handler.removeCallbacks(applyRunnable)
        if (!dirty) return
        dirty = false
        host.applySettings(QuickFields.onto(Settings.reader, cur))
    }

    /** 설정 → [page] through the reader, so its device light treats it as ours (U §4.2) and it knows the open book. */
    private fun openSettings(page: String) {
        (ctx as? ReaderActivity)?.openAppSettings(page) ?: SettingsActivity.open(ctx, page)
    }

    // ------------------------------------------------------------------ content

    private fun buildContent(): LinearLayout {
        val root = ctx.vertical { setBackgroundColor(Ink.WHITE) }
        root.addView(topBar(), lp())
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
        // The margins as on 읽기 설정: "0" = the default margin (sides −20..+60 around MaruViewer's 20 dp, top and
        // bottom −40..+40 around 40 dp), in steps of 2 (stored values stay dp). While 여백 사용 is off they show the
        // margin the page has (QuickFields.sideUi / verticalUi).
        root.addView(stepperRow(
            "좌우 여백",
            QuickFields.sideUi(cur).toFloat(),
            SideMargin.UI_MIN.toFloat(), SideMargin.UI_MAX.toFloat(), SideMargin.UI_STEP.toFloat(),
            { SideMargin.label(it.toInt()) },
        ) { update(QuickFields.withSide(cur, it.toInt()), debounce = true) })
        root.addView(stepperRow(
            "상하 여백",
            QuickFields.verticalUi(cur).toFloat(),
            VerticalMargin.UI_MIN.toFloat(), VerticalMargin.UI_MAX.toFloat(), VerticalMargin.UI_STEP.toFloat(),
            { VerticalMargin.label(it.toInt()) },
        ) { update(QuickFields.withVertical(cur, it.toInt()), debounce = true) })
        root.addView(fontRow())
        return root
    }

    /**
     * The top bar: "전체 읽기 설정 ›" on the left (설정 → 읽기 설정, after the pending change reached the page) and a
     * 48 dp 닫기 on the right.
     */
    private fun topBar(): LinearLayout {
        val row = ctx.compactRow(topLine = false)
        row.minimumHeight = ctx.dp(Compact.BAR_DP)
        row.setPadding(0, 0, ctx.dp(Compact.PAD_DP - 4), 0)
        val all = ctx.horizontal {
            setPadding(ctx.dp(Compact.PAD_DP), 0, ctx.dp(8), 0)
            background = pressableBackground()
            contentDescription = ALL_SETTINGS
            setOnClickListener {
                flush()
                popup?.dismiss()
                openSettings(SettingsActivity.PAGE_READING)
            }
        }
        all.addView(ctx.label(ALL_SETTINGS, Compact.LABEL_SP, bold = true, maxLines = 1))
        all.addView(ctx.icon(R.drawable.ic_chevron_right, 24))
        row.addView(all, lp(0, ctx.dp(Compact.BAR_DP), 1f))
        row.addView(ctx.compactIcon(R.drawable.ic_close, "닫기") { dismiss() })
        return row
    }

    /** 글꼴: the font's name in its own face; the drop-down list of fonts (with 글꼴 추가 / 글꼴 관리). */
    private fun fontRow(): LinearLayout {
        lateinit var row: LinearLayout
        lateinit var value: TextView
        row = ctx.compactRow {
            FontChooser.show(ctx, cur.fontId, anchor = row, widthPx = listWidth(row), rightInsetPx = ctx.dp(8)) { id ->
                if (popup?.isShowing == true) {
                    update(cur.copy(fontId = id))
                    value.text = fontName(id)
                    value.typeface = fontTypeface(id)
                } else {
                    host.applySettings(Settings.reader.copy(fontId = id))
                }
            }
        }
        row.addView(ctx.compactLabelBlock("글꼴"), LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        value = ctx.label(fontName(cur.fontId), Compact.VALUE_SP, maxLines = 1).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(ctx.dp(10), 0, 0, 0)
            typeface = fontTypeface(cur.fontId)
        }
        row.addView(value, lp(0, WRAP_CONTENT, 1f))
        row.addView(ctx.icon(R.drawable.ic_arrow_drop_down, 24))
        return row
    }

    // ------------------------------------------------------------------ rows

    /** Label left, "−  value  +" right (48 dp buttons, "<title> 줄이기" / "<title> 늘리기") on the same row. */
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

    /** Drop-down lists are a little narrower than the popup and right-aligned 8 dp inside the row. */
    private fun listWidth(row: View): Int = (row.width - ctx.dp(16)).coerceAtLeast(ctx.dp(160)).coerceAtMost(popupWidth)

    private fun fontName(id: String): String = runCatching { FontManager.font(id)?.name }.getOrNull() ?: id

    /** Same (id, weight) the page renderer uses, so this is normally a cache hit. */
    private fun fontTypeface(id: String): Typeface = runCatching { FontManager.typeface(id, cur.fontWeight) }.getOrNull() ?: Typeface.DEFAULT

    companion object {
        private const val DEBOUNCE_MS = 250L
        const val ALL_SETTINGS = "전체 읽기 설정"
        /** Weak: a popup left open when the reader is destroyed must not pin the activity. */
        private var current: WeakReference<ReadingSettingsPopup>? = null

        fun alignLabel(a: Align): String = if (a == Align.LEFT) "왼쪽 정렬" else "양쪽 정렬"

        /** The 줄바꿈 row's value: "단어 단위" / "글자 단위". */
        fun breakLabel(m: LineBreakMode): String = if (m == LineBreakMode.CHAR) "글자 단위" else "단어 단위"

        /** The 줄바꿈 chooser: each value with what it does. */
        fun breakChoice(m: LineBreakMode): String =
            if (m == LineBreakMode.CHAR) "${breakLabel(m)} (줄 끝을 맞춤)" else "${breakLabel(m)} (단어를 자르지 않음)"

        fun blankLabel(m: Int): String = when (m) {
            ParseOptions.BLANK_REMOVE_ALL -> "모두 지우기"
            ParseOptions.BLANK_COLLAPSE -> "한 줄로 줄이기"
            ParseOptions.BLANK_KEEP -> "그대로"
            else -> "자동"
        }

        /** The 끊어진 줄 합치기 row's value: "자동" / "항상" / "안 함". */
        fun joinLabel(m: Int): String = when (m) {
            0 -> "안 함"
            2 -> "항상"
            else -> "자동"
        }

        /** The 끊어진 줄 합치기 chooser: 자동 says what it joins. */
        fun joinChoice(m: Int): String = if (m == 1) "${joinLabel(m)} (끊긴 파일만)" else joinLabel(m)

        /** The reader's one encoding wording (the error panel's chooser and the library use it too). */
        fun encodingLabel(enc: String): String = ReaderFormat.encodingLabel(enc.trim())

        /** Short form for a row value: the list label without its note ("CP949", not "MS949"). */
        fun encodingShort(enc: String): String = encodingLabel(enc).substringBefore(" (")
    }
}

/**
 * The seven settings the quick options edit (글자 크기, 굵기, 줄 간격, 문단 간격, 좌우 여백, 상하 여백, 글꼴), the
 * margins with their "여백 사용" switch. Pure, unit-tested.
 */
internal object QuickFields {
    /** [base] with [src]'s seven quick settings; every other field (and the TXT options) stays [base]'s. */
    fun onto(base: ReaderSettings, src: ReaderSettings): ReaderSettings = base.copy(
        fontSizeSp = src.fontSizeSp,
        fontWeight = src.fontWeight,
        lineHeightPct = src.lineHeightPct,
        paragraphSpacingPct = src.paragraphSpacingPct,
        marginLeftDp = src.marginLeftDp,
        marginRightDp = src.marginRightDp,
        marginTopDp = src.marginTopDp,
        marginBottomDp = src.marginBottomDp,
        pageMargins = src.pageMargins,
        fontId = src.fontId,
    )

    /**
     * The 좌우 여백 stepper's value: the margin the page has, so while "여백 사용" is off the minimal margin
     * ([LayoutKeys.TINY_MARGIN_DP], "−16"; "−36" on the 상하 여백 stepper), not the stored one the page does not use.
     */
    fun sideUi(s: ReaderSettings): Int =
        SideMargin.toUi(if (s.pageMargins) s.marginLeftDp else LayoutKeys.TINY_MARGIN_DP).coerceIn(SideMargin.UI_MIN, SideMargin.UI_MAX)

    /** The 상하 여백 stepper's value, as [sideUi]. */
    fun verticalUi(s: ReaderSettings): Int =
        VerticalMargin.toUi(if (s.pageMargins) s.marginTopDp else LayoutKeys.TINY_MARGIN_DP)
            .coerceIn(VerticalMargin.UI_MIN, VerticalMargin.UI_MAX)

    /**
     * 좌우 여백 at stepper value [ui] ("0" = the default margin). A step while "여백 사용" is off turns it on: from the
     * minimal margin the stepper showed ([sideUi]), and the top and bottom keep the minimal margin they had (they would
     * jump to their stored values otherwise, an axis the user did not touch).
     */
    fun withSide(s: ReaderSettings, ui: Int): ReaderSettings {
        val dp = SideMargin.toDp(ui)
        val tb = if (s.pageMargins) null else LayoutKeys.TINY_MARGIN_DP
        return s.copy(
            marginLeftDp = dp, marginRightDp = dp,
            marginTopDp = tb ?: s.marginTopDp, marginBottomDp = tb ?: s.marginBottomDp,
            pageMargins = true,
        )
    }

    /** 상하 여백 at stepper value [ui], as [withSide] (the sides keep the minimal margin when it turns the margins on). */
    fun withVertical(s: ReaderSettings, ui: Int): ReaderSettings {
        val dp = VerticalMargin.toDp(ui)
        val lr = if (s.pageMargins) null else LayoutKeys.TINY_MARGIN_DP
        return s.copy(
            marginTopDp = dp, marginBottomDp = dp,
            marginLeftDp = lr ?: s.marginLeftDp, marginRightDp = lr ?: s.marginRightDp,
            pageMargins = true,
        )
    }
}
