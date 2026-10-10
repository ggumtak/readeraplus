package com.ggumtak.readeraplus.reader.extras

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupWindow
import com.ggumtak.readeraplus.render.DeviceClass
import com.ggumtak.readeraplus.render.QuoteLook
import com.ggumtak.readeraplus.render.QuoteStyles
import com.ggumtak.readeraplus.settings.Settings
import com.ggumtak.readeraplus.ui.kit.Ink
import com.ggumtak.readeraplus.ui.kit.borderBox
import com.ggumtak.readeraplus.ui.kit.dp
import com.ggumtak.readeraplus.ui.kit.dpF
import com.ggumtak.readeraplus.ui.kit.horizontal
import com.ggumtak.readeraplus.ui.kit.label
import com.ggumtak.readeraplus.ui.kit.pressableBackground
import com.ggumtak.readeraplus.ui.kit.vertical
import kotlin.math.roundToInt

/**
 * The quote-style palette (highlights.md §6.1, NOTES_SPEC §7.1 item 2): one row of 6 cells in RIDI's order (노랑 초록
 * 보라 파랑 빨강 밑줄, [QuoteStyles.paletteStyle]) in a [PopupCard] with no animation. On a colour screen a cell is the
 * [QuoteSwatch] dot alone (its name is the cell's description); in the ink look it is the grey sample above a 12 sp
 * label, since greys are told apart by their name. `current` is ringed; a tap calls `onPick` and dismisses; an outside
 * touch dismisses. Used by the contents dialog's 인용문 rows and the notes hub; the selection popup builds the same row
 * itself ([paletteRow]). Main thread.
 */
object QuotePalette {
    /**
     * Shows the palette over [anchor] (below it when there is no room above), centred on it and kept on screen.
     * [current] (a [QuoteStyles] id, or null) is ringed. Returns the popup (tracked by [PanelRegistry]) or null when
     * it could not be shown (no window yet).
     */
    fun show(anchor: View, current: Int?, onPick: (Int) -> Unit): PopupWindow? {
        val ctx = anchor.context
        val dm = ctx.resources.displayMetrics
        val eink = PopupCard.eink(ctx)
        val shadow = PopupCard.shadowRoom(ctx, eink)
        val cellW = PaletteGeometry.paletteCell(dm.widthPixels, dm.density, extraInset = (shadow - ctx.dp(8)).coerceAtLeast(0))
        val row = paletteRow(ctx, cellW, current) { s, popup -> popup?.dismiss(); onPick(s) }
        val built = PopupCard.build(ctx, eink, row.view)
        built.window.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val w = built.window.measuredWidth - 2 * built.pad
        val h = built.window.measuredHeight - 2 * built.pad
        // An anchor in a dialog parents to that dialog's window (else the palette would sit under it); an anchor in a
        // popup (a sub-window) to the activity's.
        val root = anchor.rootView
        val type = (root.layoutParams as? WindowManager.LayoutParams)?.type ?: -1
        val sub = type in WindowManager.LayoutParams.FIRST_SUB_WINDOW..WindowManager.LayoutParams.LAST_SUB_WINDOW
        val parent = if (sub) ctx.activity()?.window?.decorView ?: root else root
        val a = IntArray(2)
        val p = IntArray(2)
        anchor.getLocationOnScreen(a)
        parent.getLocationOnScreen(p)
        val margin = maxOf(ctx.dp(8), built.pad)
        val gap = ctx.dp(4)
        val x = PaletteGeometry.popupX(a[0] - p[0] + anchor.width / 2f, w, parent.width.takeIf { it > 0 } ?: dm.widthPixels, margin)
        val y = PaletteGeometry.anchoredY(a[1] - p[1], a[1] - p[1] + anchor.height, h, gap, margin, parent.height.takeIf { it > 0 } ?: dm.heightPixels)
        val pw = PopupWindow(built.window, WRAP_CONTENT, WRAP_CONTENT, true).apply {
            animationStyle = 0
            elevation = 0f
            isOutsideTouchable = true
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }
        row.popup = pw
        val shown = runCatching { pw.showAtLocation(parent, Gravity.NO_GRAVITY, x - built.pad, y - built.pad) }.isSuccess
        if (!shown) return null
        return PanelRegistry.popup(ctx, pw)
    }

    /** A palette row: 6 cells in [QuoteStyles.paletteStyle] order, [PaletteGeometry.CELL_HEIGHT_DP] tall; [onTap] gets the style and the popup holding the row (if any). */
    internal class Row(val view: LinearLayout, val swatches: List<QuoteSwatch>) {
        var popup: PopupWindow? = null

        /** Moves the ring to [style] (null: none). */
        fun check(style: Int?) {
            for (i in swatches.indices) swatches[i].isChecked = style != null && QuoteStyles.of(style) == QuoteStyles.paletteStyle(i)
        }
    }

    /**
     * The palette row. [cellW] > 0: that many px per cell; 0: the cells share the row's width equally (the row then
     * takes the width of what it is stacked with, see the selection popup). Colour mode: the dot only; ink look: the
     * grey sample and the style's name under it.
     */
    internal fun paletteRow(ctx: Context, cellW: Int, current: Int?, onTap: (Int, PopupWindow?) -> Unit): Row {
        val ink = QuoteLook.ink()
        val row = ctx.horizontal()
        val swatches = ArrayList<QuoteSwatch>(QuoteStyles.COUNT)
        lateinit var holder: Row
        for (pos in 0 until QuoteStyles.COUNT) {
            val s = QuoteStyles.paletteStyle(pos)
            val swatch = if (ink) QuoteSwatch(ctx, s, PaletteGeometry.POPUP_SWATCH_DP, true)
            else QuoteSwatch(ctx, s, PaletteGeometry.DOT_DP, false, tinted = true)
            swatches += swatch
            val cell = ctx.vertical {
                gravity = Gravity.CENTER
                background = pressableBackground()
                contentDescription = QuoteStyles.label(s)
                setOnClickListener { onTap(s, holder.popup) }
            }
            cell.addView(swatch, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { gravity = Gravity.CENTER_HORIZONTAL })
            if (ink) cell.addView(ctx.label(QuoteStyles.label(s), 12f, color = Ink.BLACK, maxLines = 1).apply { gravity = Gravity.CENTER })
            val height = ctx.dp(PaletteGeometry.CELL_HEIGHT_DP)
            row.addView(cell, if (cellW > 0) LinearLayout.LayoutParams(cellW, height) else LinearLayout.LayoutParams(0, height, 1f))
        }
        holder = Row(row, swatches)
        holder.check(current)
        return holder
    }

    private fun Context.activity(): Activity? {
        var c: Context? = this
        var depth = 0
        while (c != null && depth++ < 16) {
            if (c is Activity) return c
            c = (c as? ContextWrapper)?.baseContext
        }
        return null
    }
}

/**
 * The card the selection popup, its ⋯ menu and the stand-alone palette sit in. A phone gets RIDI's: white, a
 * [FRAME] hairline, a [RADIUS_DP] corner and a soft shadow ([ELEVATION_DP]); e-ink keeps the 1 px black square frame
 * and no shadow. The window around the card keeps [SHADOW_ROOM_DP] on every side for the shadow (none on e-ink), so a
 * popup's window position is the card's minus [Built.pad].
 */
internal object PopupCard {
    const val FRAME = 0xFFDADADA.toInt()
    const val RADIUS_DP = 2f
    const val ELEVATION_DP = 5f
    const val SHADOW_ROOM_DP = 10

    /** What [build] made: [window] goes to the PopupWindow, [card] is the visible box, [pad] the shadow room around it (px). */
    class Built(val window: View, val card: View, val pad: Int)

    /** The e-ink look (black frame, no shadow): the renderer's rule — a device that is not known to be a phone. */
    fun eink(ctx: Context): Boolean = DeviceClass.cached(ctx) != false

    /** The shadow room a window keeps around the card, px. */
    fun shadowRoom(ctx: Context, eink: Boolean): Int = if (eink) 0 else ctx.dp(SHADOW_ROOM_DP)

    fun build(ctx: Context, eink: Boolean, content: View): Built {
        val pad = shadowRoom(ctx, eink)
        val stroke = ctx.dp(1f).coerceAtLeast(1)
        val card = FrameLayout(ctx)
        if (eink) {
            card.background = ctx.borderBox(radiusDp = 0f)
        } else {
            card.background = GradientDrawable().apply {
                setColor(Ink.WHITE)
                setStroke(stroke, FRAME)
                cornerRadius = ctx.dpF(RADIUS_DP)
            }
            card.elevation = ctx.dpF(ELEVATION_DP)
            card.clipToOutline = true
        }
        // The frame is the background's stroke: children stay inside it.
        card.setPadding(stroke, stroke, stroke, stroke)
        card.addView(content, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        val window = FrameLayout(ctx).apply {
            clipChildren = false
            clipToPadding = false
            setPadding(pad, pad, pad, pad)
            addView(card, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        }
        return Built(window, card, pad)
    }

    /** A 1 px rule between a card's rows: black on e-ink, [FRAME] on a phone. */
    fun divider(ctx: Context, eink: Boolean): View = View(ctx).apply {
        setBackgroundColor(if (eink) Ink.LINE else FRAME)
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 1)
    }
}

/** The last style picked from a palette (raw pref `extras.quoteStyle`, default 노랑); new quotes use it. */
internal object LastQuoteStyle {
    const val KEY = "extras.quoteStyle"

    fun get(): Int = QuoteStyles.of(runCatching { Settings.raw().getInt(KEY, QuoteStyles.YELLOW) }.getOrDefault(QuoteStyles.YELLOW))

    fun set(style: Int) {
        runCatching { Settings.raw().edit().putInt(KEY, QuoteStyles.of(style)).apply() }
    }
}

/** Sizes and placement of the selection popup and the palette (pure, unit-tested; px unless named dp). */
internal object PaletteGeometry {
    /** Total side gap of a full-width popup row: 8 dp on each side. */
    const val SIDE_GAP_DP = 16
    const val CELL_HEIGHT_DP = 56
    const val MIN_CELL_DP = 48
    const val PALETTE_COLS = 6
    /** The ink sample's size parameter (a 30 × 20 dp sample, see [QuoteSwatch]). */
    const val POPUP_SWATCH_DP = 20
    const val ROW_SWATCH_DP = 12
    /** RIDI's colour dot: the filled circle's diameter. */
    const val DOT_DP = 26
    /** The checked ring: [RING_DP] wide, [RING_GAP_DP] outside the swatch. */
    const val RING_GAP_DP = 3f
    const val RING_DP = 2f
    /** The thin ring of a tinted dot: one white gap, then a line of the dot's own colour. */
    const val TINT_RING_GAP_DP = 2f
    const val TINT_RING_DP = 1.5f
    /** The most side padding a text cell of the selection popup gets (the cells shrink it to fit the screen). */
    const val CELL_PAD_MAX_DP = 16

    /** Width of a full-width popup row: W − 16 dp. */
    fun rowWidth(screenW: Int, density: Float): Int = (screenW - (SIDE_GAP_DP * density).roundToInt()).coerceAtLeast(0)

    /**
     * A cell of the stand-alone palette: max(48 dp, (W − 16 dp) / 6), the row narrower by [extraInset] on each side
     * (the shadow room a phone's card keeps).
     */
    fun paletteCell(screenW: Int, density: Float, extraInset: Int = 0): Int =
        maxOf((MIN_CELL_DP * density).roundToInt(), (rowWidth(screenW, density) - 2 * extraInset).coerceAtLeast(0) / PALETTE_COLS)

    /**
     * The side padding every cell of a row gets: the room left over by the cells' own widths [contentW] in [avail],
     * shared by both sides of every cell, within 0..[maxPad] (a row that does not fit is simply packed).
     */
    fun cellPad(contentW: IntArray, avail: Int, maxPad: Int): Int {
        if (contentW.isEmpty()) return 0
        var sum = 0L
        for (w in contentW) sum += w
        return ((avail - sum) / (2L * contentW.size)).coerceIn(0L, maxPad.toLong()).toInt()
    }

    /** Room a swatch keeps on each side for its checked ring. */
    fun ringInset(density: Float): Float = (RING_GAP_DP + RING_DP) * density

    /** Left of a [w]-wide popup centred on [centerX], kept [margin] inside a [screenW]-wide window. */
    fun popupX(centerX: Float, w: Int, screenW: Int, margin: Int): Int =
        (centerX - w / 2f).roundToInt().coerceIn(margin, (screenW - w - margin).coerceAtLeast(margin))

    /** Top of an [h]-tall popup over an anchor [anchorTop, anchorBottom): above it when it fits, else below it. */
    fun anchoredY(anchorTop: Int, anchorBottom: Int, h: Int, gap: Int, margin: Int, screenH: Int): Int {
        val above = anchorTop - gap - h
        if (above >= margin) return above
        val below = anchorBottom + gap
        return if (below + h <= screenH - margin) below else ((screenH - h) / 2).coerceAtLeast(margin)
    }

    /** Top of an [h]-tall menu under an anchor [anchorTop, anchorBottom): below it when it fits, else above it, else centred. */
    fun belowY(anchorTop: Int, anchorBottom: Int, h: Int, gap: Int, margin: Int, screenH: Int): Int {
        val below = anchorBottom + gap
        if (below + h <= screenH - margin) return below
        val above = anchorTop - gap - h
        return if (above >= margin) above else ((screenH - h) / 2).coerceAtLeast(margin)
    }

    /**
     * Top of the selection popup: [gap] above the selection's first line when it fits, else below its last line,
     * clear of the handles ([handle] tall); centred when neither fits.
     */
    fun selectionY(selTop: Float, selBottom: Float, h: Int, gap: Int, handle: Int, margin: Int, screenH: Int): Int {
        val above = (selTop - h - gap).toInt()
        if (above >= margin) return above
        val below = (selBottom + handle + gap).toInt()
        return if (below + h <= screenH - margin) below else ((screenH - h) / 2).coerceAtLeast(margin)
    }
}
