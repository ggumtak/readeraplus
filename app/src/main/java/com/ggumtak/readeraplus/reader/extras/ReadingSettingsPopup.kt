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
import com.ggumtak.readeraplus.reader.ReaderActivity
import com.ggumtak.readeraplus.reader.ReaderFormat
import com.ggumtak.readeraplus.reader.ReaderHost
import com.ggumtak.readeraplus.render.FontManager
import com.ggumtak.readeraplus.settings.ReadMode
import com.ggumtak.readeraplus.settings.ReaderSettings
import com.ggumtak.readeraplus.settings.Settings
import com.ggumtak.readeraplus.settings.TapZoneMode
import com.ggumtak.readeraplus.ui.kit.Ink
import com.ggumtak.readeraplus.ui.kit.borderBox
import com.ggumtak.readeraplus.ui.kit.dp
import com.ggumtak.readeraplus.ui.kit.icon
import com.ggumtak.readeraplus.ui.kit.label
import com.ggumtak.readeraplus.ui.kit.lockWidthForValues
import com.ggumtak.readeraplus.ui.kit.lp
import com.ggumtak.readeraplus.ui.kit.vertical
import com.ggumtak.readeraplus.ui.settings.SettingsActivity
import java.lang.ref.WeakReference

/**
 * The quick reading options (⚙) for the ~6" 360×720 dp e-ink screen: only what is changed while reading, with the
 * page in view as the preview. Centred, the screen's width but 8 dp on each side (≤ 400 dp, [PopupGeometry.width]),
 * 8 dp under the status bar; the reader's bars are hidden while it opens (one e-ink update with the popup), so the
 * lower part of the page stays visible. Black on white, no animations, no scrolling, nothing that expands:
 *
 * 읽기 설정 · 모든 책에 적용 · [닫기] / 글자 크기 / 굵기 / 줄 간격 / 문단 간격 (− value +, 48 dp buttons on 56 dp rows) /
 * 글꼴 (drop-down list) / 전체 읽기 설정 › ([QUICK_HEIGHT_DP][PopupGeometry.QUICK_HEIGHT_DP] = 376 dp).
 *
 * Everything else (styles, the other spacings, margins, page breaks, status bands, page turning, keys, the TXT and
 * EPUB options) lives in 설정 → 읽기 설정 ([SettingsActivity.PAGE_READING]), which "전체 읽기 설정" opens; these five
 * rows are there too. Both edit the same GLOBAL [ReaderSettings] fields and reach the page through the same path
 * ([ReaderHost.applySettings] here, the reader's settings sync after 설정): steppers are debounced (250 ms) so
 * repeated taps cost one re-layout, the font applies at once. A change keeps the page's first character.
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

        // Scrolls only in a window too short for the five rows (landscape phones, split screen); never on the Comet.
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
     * Hands the pending change to the reader: this popup's five fields on top of the saved GLOBAL settings (never a
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
        root.addView(titleRow(), lp())
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
        root.addView(fontRow())
        root.addView(allSettingsRow(), lp())
        return root
    }

    /** "읽기 설정" and the scope ("모든 책에 적용": these are the global settings), with a 48 dp 닫기 button. */
    private fun titleRow(): LinearLayout {
        val row = ctx.compactRow(topLine = false)
        row.minimumHeight = ctx.dp(Compact.BAR_DP)
        row.addView(ctx.label("읽기 설정", Compact.LABEL_SP, bold = true, maxLines = 1))
        row.addView(ctx.label(SCOPE_TEXT, Compact.SUMMARY_SP, color = Ink.GRAY, maxLines = 1).apply {
            setPadding(ctx.dp(10), 0, 0, 0)
        }, lp(0, WRAP_CONTENT, 1f))
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

    /** "전체 읽기 설정 ›": 설정 → 읽기 설정, after the pending change reached the page. */
    private fun allSettingsRow(): LinearLayout {
        val row = ctx.compactRow(strongLine = true) {
            flush()
            popup?.dismiss()
            openSettings(SettingsActivity.PAGE_READING)
        }
        row.minimumHeight = ctx.dp(Compact.BAR_DP)
        row.contentDescription = ALL_SETTINGS
        row.addView(ctx.label(ALL_SETTINGS, Compact.LABEL_SP, bold = true, maxLines = 1), lp(0, WRAP_CONTENT, 1f))
        row.addView(ctx.icon(R.drawable.ic_chevron_right, 24))
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
        const val SCOPE_TEXT = "모든 책에 적용"
        const val ALL_SETTINGS = "전체 읽기 설정"
        /** Weak: a popup left open when the reader is destroyed must not pin the activity. */
        private var current: WeakReference<ReadingSettingsPopup>? = null

        fun tapModeLabel(m: TapZoneMode): String = when (m) {
            TapZoneMode.LEFT_RIGHT -> "좌우 (왼쪽 = 이전, 오른쪽 = 다음)"
            TapZoneMode.ALL_NEXT -> "어디든 다음 (왼쪽 끝 = 이전)"
            TapZoneMode.ALL_PREV -> "어디든 이전 (오른쪽 끝 = 다음)"
            TapZoneMode.TOP_BOTTOM -> "위아래 (위 = 이전, 아래 = 다음)"
            TapZoneMode.CUSTOM -> "사용자 지정 (3×3)"
        }

        /** Short form for a row value (the list shows [tapModeLabel]). */
        fun tapModeShort(m: TapZoneMode): String = when (m) {
            TapZoneMode.LEFT_RIGHT -> "좌우"
            TapZoneMode.ALL_NEXT -> "어디든 다음"
            TapZoneMode.ALL_PREV -> "어디든 이전"
            TapZoneMode.TOP_BOTTOM -> "위아래"
            TapZoneMode.CUSTOM -> "사용자 지정"
        }

        /** The 넘기는 방식 list entries (S §1.2); the row value is [ReadMode.label]. */
        fun readModeLabel(m: ReadMode): String = when (m) {
            ReadMode.PAGED -> "페이지 넘김 (기본)"
            ReadMode.SCROLL -> "스크롤 (위아래로 내려 읽기)"
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

        /** Short form for a row value: the list label without its note ("CP949", not "MS949"). */
        fun encodingShort(enc: String): String = encodingLabel(enc).substringBefore(" (")
    }
}

/** The five fields the quick options edit (글자 크기, 굵기, 줄 간격, 문단 간격, 글꼴). Pure, unit-tested. */
internal object QuickFields {
    /** [base] with [src]'s five quick fields; every other field (and the TXT options) stays [base]'s. */
    fun onto(base: ReaderSettings, src: ReaderSettings): ReaderSettings = base.copy(
        fontSizeSp = src.fontSizeSp,
        fontWeight = src.fontWeight,
        lineHeightPct = src.lineHeightPct,
        paragraphSpacingPct = src.paragraphSpacingPct,
        fontId = src.fontId,
    )
}
