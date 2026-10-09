package com.ggumtak.readeraplus.ui.settings

import com.ggumtak.readeraplus.settings.AppSettings
import com.ggumtak.readeraplus.settings.TapAction
import com.ggumtak.readeraplus.settings.TapZoneMode

/**
 * Geometry of the reader's tap zones for the settings preview (pure, unit-tested). Mirrors the reader rules
 * in docs/ARCHITECTURE.md: fractions of the page view, (0,0) = top-left.
 *
 * A mode is described as a non-uniform grid ([Grid]): the x/y breakpoints of all its zone edges, with one
 * action per grid cell. Adjacent cells with the same action form one visual region ([labels]).
 */
object TapZoneModel {
    /** Corner hot spots (bookmark top-right / invert top-left): 15% of the width × 10% of the height. */
    const val CORNER_W = 0.15f
    const val CORNER_H = 0.10f
    /** ALL_NEXT: width of the left strip that still goes back. */
    const val ALL_NEXT_STRIP = 0.12f
    /** TOP_BOTTOM: height of the top (previous) and bottom (next) bands. */
    const val TOP_BOTTOM_BAND = 0.40f

    private const val T1 = 1f / 3f
    private const val T2 = 2f / 3f

    /** Short Korean mode name: the radio rows and the main list's 넘기기·터치·키 summary (one source). */
    fun modeName(mode: TapZoneMode): String = when (mode) {
        TapZoneMode.LEFT_RIGHT -> "좌우 넘김"
        TapZoneMode.ALL_NEXT -> "어디든 다음"
        TapZoneMode.ALL_PREV -> "어디든 이전"
        TapZoneMode.TOP_BOTTOM -> "위아래 넘김"
        TapZoneMode.CUSTOM -> "직접 지정"
    }

    /** One-line description of a mode (the centre is the menu in every mode; the preview below shows it). */
    fun modeDescription(mode: TapZoneMode): String = when (mode) {
        TapZoneMode.LEFT_RIGHT -> "왼쪽 1/3은 이전, 나머지는 다음"
        TapZoneMode.ALL_NEXT -> "왼쪽 가장자리만 이전"
        TapZoneMode.ALL_PREV -> "오른쪽 가장자리만 다음"
        TapZoneMode.TOP_BOTTOM -> "위는 이전, 아래는 다음"
        TapZoneMode.CUSTOM -> "9칸에 동작을 직접 지정"
    }

    /** Compact label drawn inside the preview ('\n' = line break). */
    fun shortLabel(a: TapAction): String = when (a) {
        TapAction.NONE -> "없음"
        TapAction.NEXT -> "다음"
        TapAction.PREV -> "이전"
        TapAction.MENU -> "메뉴"
        TapAction.BOOKMARK -> "북마크"
        TapAction.TOC -> "목차"
        TapAction.SEARCH -> "검색"
        TapAction.SETTINGS -> "읽기\n설정"
        TapAction.TTS -> "듣기"
        TapAction.NEXT_CHAPTER -> "다음\n챕터"
        TapAction.PREV_CHAPTER -> "이전\n챕터"
        TapAction.REFRESH -> "새로\n고침"
        TapAction.INVERT -> "반전"
        TapAction.GOTO -> "페이지\n이동"
        TapAction.AUTO_TURN -> "자동\n넘김"
    }

    private val CELL_NAMES = arrayOf("왼쪽 위", "가운데 위", "오른쪽 위", "왼쪽 가운데", "가운데", "오른쪽 가운데", "왼쪽 아래", "가운데 아래", "오른쪽 아래")

    /** Position name of a row-major 3×3 cell. */
    fun cellName(index: Int): String = CELL_NAMES.getOrElse(index) { "칸 ${index + 1}" }

    /** Row-major 3×3 cell (0..8) at a fractional position. */
    fun cellAt(fx: Float, fy: Float): Int = third(fy) * 3 + third(fx)

    private fun third(f: Float): Int = when {
        f < T1 -> 0
        f < T2 -> 1
        else -> 2
    }

    /** The custom grid the reader actually uses: default when malformed; centre = MENU when no cell has MENU. */
    fun effectiveCustom(custom: List<TapAction>): List<TapAction> {
        val grid = if (custom.size == 9) custom else AppSettings().customTapZones
        return if (TapAction.MENU in grid) grid else grid.toMutableList().also { it[4] = TapAction.MENU }
    }

    /** True when the reader silently turns the centre cell into MENU (no MENU cell in the grid). */
    fun centreForcedToMenu(custom: List<TapAction>): Boolean =
        custom.size == 9 && TapAction.MENU !in custom

    /** Zone action at a fractional position (corners not included). */
    fun actionAt(mode: TapZoneMode, custom: List<TapAction>, fx: Float, fy: Float): TapAction {
        val c = cellAt(fx, fy)
        return when (mode) {
            TapZoneMode.LEFT_RIGHT -> when {
                c == 4 -> TapAction.MENU
                fx < T1 -> TapAction.PREV
                else -> TapAction.NEXT
            }
            TapZoneMode.ALL_NEXT -> when {
                c == 4 -> TapAction.MENU
                fx < ALL_NEXT_STRIP -> TapAction.PREV
                else -> TapAction.NEXT
            }
            TapZoneMode.ALL_PREV -> when {
                c == 4 -> TapAction.MENU
                fx >= 1f - ALL_NEXT_STRIP -> TapAction.NEXT
                else -> TapAction.PREV
            }
            TapZoneMode.TOP_BOTTOM -> when {
                fy < TOP_BOTTOM_BAND -> TapAction.PREV
                fy >= 1f - TOP_BOTTOM_BAND -> TapAction.NEXT
                fx >= T1 && fx < T2 -> TapAction.MENU
                else -> TapAction.NEXT
            }
            TapZoneMode.CUSTOM -> effectiveCustom(custom)[c]
        }
    }

    /** Non-uniform grid: [xs]/[ys] are ascending breakpoints from 0 to 1; [actions] is row-major. */
    class Grid(val xs: FloatArray, val ys: FloatArray, val actions: Array<TapAction>) {
        val cols: Int get() = xs.size - 1
        val rows: Int get() = ys.size - 1
        fun action(col: Int, row: Int): TapAction = actions[row * cols + col]
        fun area(col: Int, row: Int): Float = (xs[col + 1] - xs[col]) * (ys[row + 1] - ys[row])
        fun centerX(col: Int): Float = (xs[col] + xs[col + 1]) / 2f
        fun centerY(row: Int): Float = (ys[row] + ys[row + 1]) / 2f
    }

    fun grid(mode: TapZoneMode, custom: List<TapAction>, invert: Boolean = false): Grid {
        val xs = when (mode) {
            TapZoneMode.ALL_NEXT -> floatArrayOf(0f, ALL_NEXT_STRIP, T1, T2, 1f)
            TapZoneMode.ALL_PREV -> floatArrayOf(0f, T1, T2, 1f - ALL_NEXT_STRIP, 1f)
            else -> floatArrayOf(0f, T1, T2, 1f)
        }
        val ys = when (mode) {
            TapZoneMode.TOP_BOTTOM -> floatArrayOf(0f, TOP_BOTTOM_BAND, 1f - TOP_BOTTOM_BAND, 1f)
            else -> floatArrayOf(0f, T1, T2, 1f)
        }
        val cols = xs.size - 1
        val rows = ys.size - 1
        val actions = Array(cols * rows) { i ->
            val col = i % cols
            val row = i / cols
            val a = actionAt(mode, custom, (xs[col] + xs[col + 1]) / 2f, (ys[row] + ys[row + 1]) / 2f)
            if (invert) com.ggumtak.readeraplus.reader.TapZones.inverted(a) else a
        }
        return Grid(xs, ys, actions)
    }

    /** Where to draw a region label: grid cell (col, row) and the region's action. */
    class Label(val col: Int, val row: Int, val action: TapAction)

    /**
     * One label per connected region of equal actions (4-neighbourhood), placed in the region's largest cell;
     * ties go to the cell closest to the region's area-weighted centroid. With [perCell] every cell is labelled
     * (the 3×3 editor).
     */
    fun labels(g: Grid, perCell: Boolean): List<Label> {
        val n = g.cols * g.rows
        if (perCell) return List(n) { i -> Label(i % g.cols, i / g.cols, g.actions[i]) }
        val comp = IntArray(n) { -1 }
        val out = ArrayList<Label>()
        val stack = IntArray(n)
        var compId = 0
        for (start in 0 until n) {
            if (comp[start] >= 0) continue
            val a = g.actions[start]
            val members = ArrayList<Int>()
            var sp = 0
            stack[sp++] = start
            comp[start] = compId
            while (sp > 0) {
                val i = stack[--sp]
                members += i
                val c = i % g.cols
                val r = i / g.cols
                val nbrs = intArrayOf(
                    if (c > 0) i - 1 else -1,
                    if (c < g.cols - 1) i + 1 else -1,
                    if (r > 0) i - g.cols else -1,
                    if (r < g.rows - 1) i + g.cols else -1,
                )
                for (j in nbrs) {
                    if (j >= 0 && comp[j] < 0 && g.actions[j] == a) {
                        comp[j] = compId
                        stack[sp++] = j
                    }
                }
            }
            var areaSum = 0f
            var cx = 0f
            var cy = 0f
            for (i in members) {
                val ar = g.area(i % g.cols, i / g.cols)
                areaSum += ar
                cx += g.centerX(i % g.cols) * ar
                cy += g.centerY(i / g.cols) * ar
            }
            if (areaSum > 0f) {
                cx /= areaSum
                cy /= areaSum
            }
            var best = members[0]
            var bestArea = -1f
            var bestDist = Float.MAX_VALUE
            for (i in members.sorted()) {
                val col = i % g.cols
                val row = i / g.cols
                val ar = g.area(col, row)
                val dx = g.centerX(col) - cx
                val dy = g.centerY(row) - cy
                val d = dx * dx + dy * dy
                if (ar > bestArea + 1e-6f || (Math.abs(ar - bestArea) <= 1e-6f && d < bestDist - 1e-6f)) {
                    best = i
                    bestArea = ar
                    bestDist = d
                }
            }
            out += Label(best % g.cols, best / g.cols, a)
            compId++
        }
        return out
    }

    /** Custom grid with one cell changed. */
    fun withCell(custom: List<TapAction>, index: Int, action: TapAction): List<TapAction> {
        val base = if (custom.size == 9) custom.toMutableList() else AppSettings().customTapZones.toMutableList()
        if (index in 0..8) base[index] = action
        return base
    }

    /** Left and right columns swapped (left-handed use). */
    fun mirrored(custom: List<TapAction>): List<TapAction> {
        val g = if (custom.size == 9) custom else AppSettings().customTapZones
        return List(9) { i -> g[(i / 3) * 3 + (2 - i % 3)] }
    }

    /** Custom preset: every cell NEXT except a MENU centre. */
    val PRESET_ALL_NEXT: List<TapAction> = List(9) { if (it == 4) TapAction.MENU else TapAction.NEXT }

    /** Custom preset: top row PREV, bottom two rows NEXT, MENU centre (one-handed, thumb at the bottom). */
    val PRESET_TOP_PREV: List<TapAction> = List(9) { i ->
        when {
            i == 4 -> TapAction.MENU
            i < 3 -> TapAction.PREV
            else -> TapAction.NEXT
        }
    }
}
