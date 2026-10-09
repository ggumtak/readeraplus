package com.ggumtak.readeraplus.reader.extras

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.PopupWindow
import com.ggumtak.readeraplus.render.QuoteLook
import com.ggumtak.readeraplus.render.QuoteStyles
import com.ggumtak.readeraplus.settings.Settings
import com.ggumtak.readeraplus.ui.kit.Ink
import com.ggumtak.readeraplus.ui.kit.borderBox
import com.ggumtak.readeraplus.ui.kit.dp
import com.ggumtak.readeraplus.ui.kit.horizontal
import com.ggumtak.readeraplus.ui.kit.label
import com.ggumtak.readeraplus.ui.kit.lp
import com.ggumtak.readeraplus.ui.kit.pressableBackground
import com.ggumtak.readeraplus.ui.kit.vertical
import kotlin.math.roundToInt

/**
 * The quote-style palette (highlights.md §6.1, NOTES_SPEC §7.1 item 2): one row of 6 cells — the [QuoteSwatch] above
 * a 12 sp label (노랑 초록 파랑 빨강 보라 밑줄) — in a [borderBox] popup with no animation and no elevation. `current` is
 * ringed; a tap calls `onPick` and dismisses; an outside touch dismisses. Used by the selection popup, the 인용문 rows
 * of the contents dialog and the notes hub. Main thread.
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
        val cellW = PaletteGeometry.paletteCell(dm.widthPixels, dm.density)
        val row = paletteRow(ctx, cellW, current) { s, popup -> popup?.dismiss(); onPick(s) }
        val box = ctx.vertical { background = ctx.borderBox(radiusDp = 0f) }
        box.addView(row.view, lp())
        box.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val w = box.measuredWidth
        val h = box.measuredHeight
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
        val margin = ctx.dp(8)
        val gap = ctx.dp(4)
        val x = PaletteGeometry.popupX(a[0] - p[0] + anchor.width / 2f, w, parent.width.takeIf { it > 0 } ?: dm.widthPixels, margin)
        val y = PaletteGeometry.anchoredY(a[1] - p[1], a[1] - p[1] + anchor.height, h, gap, margin, parent.height.takeIf { it > 0 } ?: dm.heightPixels)
        val pw = PopupWindow(box, WRAP_CONTENT, WRAP_CONTENT, true).apply {
            animationStyle = 0
            elevation = 0f
            isOutsideTouchable = true
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }
        row.popup = pw
        val shown = runCatching { pw.showAtLocation(parent, Gravity.NO_GRAVITY, x, y) }.isSuccess
        if (!shown) return null
        return PanelRegistry.popup(ctx, pw)
    }

    /** A palette row: 6 cells of [cellW] px × 56 dp; [onTap] gets the style and the popup holding the row (if any). */
    internal class Row(val view: LinearLayout, val swatches: List<QuoteSwatch>) {
        var popup: PopupWindow? = null

        /** Moves the ring to [style] (null: none). */
        fun check(style: Int?) {
            for (i in swatches.indices) swatches[i].isChecked = style != null && QuoteStyles.of(style) == i
        }
    }

    internal fun paletteRow(ctx: Context, cellW: Int, current: Int?, onTap: (Int, PopupWindow?) -> Unit): Row {
        val ink = QuoteLook.ink()
        val row = ctx.horizontal()
        val swatches = ArrayList<QuoteSwatch>(QuoteStyles.COUNT)
        lateinit var holder: Row
        for (s in 0 until QuoteStyles.COUNT) {
            val swatch = QuoteSwatch(ctx, s, PaletteGeometry.POPUP_SWATCH_DP, ink)
            swatches += swatch
            val cell = ctx.vertical {
                gravity = Gravity.CENTER
                background = pressableBackground()
                contentDescription = QuoteStyles.label(s)
                setOnClickListener { onTap(s, holder.popup) }
            }
            cell.addView(swatch, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { gravity = Gravity.CENTER_HORIZONTAL })
            cell.addView(ctx.label(QuoteStyles.label(s), 12f, color = Ink.BLACK, maxLines = 1).apply { gravity = Gravity.CENTER })
            row.addView(cell, LinearLayout.LayoutParams(cellW, ctx.dp(PaletteGeometry.CELL_HEIGHT_DP)))
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
    const val POPUP_SWATCH_DP = 20
    const val ROW_SWATCH_DP = 12
    /** The checked ring: [RING_DP] wide, [RING_GAP_DP] outside the swatch. */
    const val RING_GAP_DP = 3f
    const val RING_DP = 2f

    /** Width of a full-width popup row: W − 16 dp. */
    fun rowWidth(screenW: Int, density: Float): Int = (screenW - (SIDE_GAP_DP * density).roundToInt()).coerceAtLeast(0)

    /** A selection-popup cell: (W − 16 dp) / 5. */
    fun selectionCell(screenW: Int, density: Float): Int = rowWidth(screenW, density) / SelectionActions.CELLS

    /** A cell of the palette row inside the selection popup: (W − 16 dp) / 6, so both rows are equally wide. */
    fun inlinePaletteCell(screenW: Int, density: Float): Int = rowWidth(screenW, density) / PALETTE_COLS

    /** A cell of the stand-alone palette: max(48 dp, (W − 16 dp) / 6). */
    fun paletteCell(screenW: Int, density: Float): Int =
        maxOf((MIN_CELL_DP * density).roundToInt(), rowWidth(screenW, density) / PALETTE_COLS)

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
