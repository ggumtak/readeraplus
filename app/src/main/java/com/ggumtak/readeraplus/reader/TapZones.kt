package com.ggumtak.readeraplus.reader

import com.ggumtak.readeraplus.settings.AppSettings
import com.ggumtak.readeraplus.settings.TapAction
import com.ggumtak.readeraplus.settings.TapZoneMode

/** Special corners of the page checked before the zone grid. */
enum class Corner { NONE, TOP_LEFT, TOP_RIGHT }

/**
 * Pure mapping of a tap position to a [TapAction] (no Android types, unit-tested).
 * Coordinates are in view pixels, (0, 0) = top-left of the page view.
 */
object TapZones {
    /** Corner hot spots: 15% of the width × 10% of the height. */
    const val CORNER_W = 0.15f
    const val CORNER_H = 0.10f
    /** ALL_NEXT: width of the left strip that still goes back. */
    const val ALL_NEXT_PREV_STRIP = 0.12f
    /** TOP_BOTTOM: height share of the top (previous) and bottom (next) bands. */
    const val TOP_BOTTOM_BAND = 0.40f

    fun corner(x: Float, y: Float, w: Int, h: Int): Corner {
        if (w <= 0 || h <= 0) return Corner.NONE
        if (y >= h * CORNER_H) return Corner.NONE
        return when {
            x < w * CORNER_W -> Corner.TOP_LEFT
            x >= w * (1f - CORNER_W) -> Corner.TOP_RIGHT
            else -> Corner.NONE
        }
    }

    /** Row-major index (0..8) of the 3×3 cell containing (x, y). */
    fun cell(x: Float, y: Float, w: Int, h: Int): Int {
        val col = third(x, w)
        val row = third(y, h)
        return row * 3 + col
    }

    private fun third(v: Float, size: Int): Int {
        if (size <= 0) return 1
        val f = v / size
        return when {
            f < 1f / 3f -> 0
            f < 2f / 3f -> 1
            else -> 2
        }
    }

    fun actionAt(app: AppSettings, x: Float, y: Float, w: Int, h: Int): TapAction {
        val a = actionAt(app.tapZoneMode, app.customTapZones, x, y, w, h)
        return if (app.invertTaps) inverted(a) else a
    }

    /** Swaps the page/chapter direction of [a] ("탭 방향 반대로"). */
    fun inverted(a: TapAction): TapAction = when (a) {
        TapAction.NEXT -> TapAction.PREV
        TapAction.PREV -> TapAction.NEXT
        TapAction.NEXT_CHAPTER -> TapAction.PREV_CHAPTER
        TapAction.PREV_CHAPTER -> TapAction.NEXT_CHAPTER
        else -> a
    }

    /**
     * Action for a tap. CUSTOM grids without any MENU cell get MENU in the centre cell so the chrome can
     * always be reached by touch.
     */
    fun actionAt(mode: TapZoneMode, custom: List<TapAction>, x: Float, y: Float, w: Int, h: Int): TapAction {
        if (w <= 0 || h <= 0) return TapAction.NONE
        val fx = x / w
        val fy = y / h
        val c = cell(x, y, w, h)
        return when (mode) {
            TapZoneMode.LEFT_RIGHT -> when {
                c == 4 -> TapAction.MENU
                fx < 1f / 3f -> TapAction.PREV
                else -> TapAction.NEXT
            }
            TapZoneMode.ALL_NEXT -> when {
                c == 4 -> TapAction.MENU
                fx < ALL_NEXT_PREV_STRIP -> TapAction.PREV
                else -> TapAction.NEXT
            }
            TapZoneMode.ALL_PREV -> when {
                c == 4 -> TapAction.MENU
                fx >= 1f - ALL_NEXT_PREV_STRIP -> TapAction.NEXT
                else -> TapAction.PREV
            }
            TapZoneMode.TOP_BOTTOM -> when {
                fy < TOP_BOTTOM_BAND -> TapAction.PREV
                fy >= 1f - TOP_BOTTOM_BAND -> TapAction.NEXT
                fx >= 1f / 3f && fx < 2f / 3f -> TapAction.MENU
                else -> TapAction.NEXT
            }
            TapZoneMode.CUSTOM -> {
                val grid = if (custom.size == 9) custom else AppSettings().customTapZones
                if (c == 4 && TapAction.MENU !in grid) TapAction.MENU else grid[c]
            }
        }
    }
}
