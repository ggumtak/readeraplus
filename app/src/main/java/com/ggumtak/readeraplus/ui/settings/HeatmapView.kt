package com.ggumtak.readeraplus.ui.settings

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import com.ggumtak.readeraplus.ui.kit.Ink
import com.ggumtak.readeraplus.ui.kit.dpF
import com.ggumtak.readeraplus.ui.kit.sp

/**
 * The 잔디 of "읽기 기록" (T1-6): [HeatmapModel.WEEKS] × 7 cells (13 dp, 3 dp gaps; smaller when the width is short),
 * month labels above and the legend "적게 □ ▦ ▩ ■ 많이" below. It only draws: no animation, no scrolling, and a
 * draw happens only when the data or the selected cell changes. A tap on a drawn cell calls [onDayTap] with its day
 * (the page fills one text line below; no popup) and outlines that cell.
 */
internal class HeatmapView(context: Context) : View(context) {
    var onDayTap: ((Int) -> Unit)? = null

    private var grid: HeatGrid? = null
    private var selected = -1

    private val cellMax = context.dpF(13f)
    private val gap = context.dpF(3f)
    private val labelH = context.dpF(18f)
    private val legendH = context.dpF(26f)
    private val line = context.dpF(1f).coerceAtLeast(1f)
    private var cell = cellMax

    private val fill = Paint().apply { style = Paint.Style.FILL }
    private val outline = Paint().apply { style = Paint.Style.STROKE; strokeWidth = line; color = OUTLINE }
    private val mark = Paint().apply { style = Paint.Style.STROKE; strokeWidth = context.dpF(2f); color = Ink.BLACK }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ink.GRAY; textSize = context.sp(12f) }
    private val rect = RectF()

    init {
        contentDescription = "읽기 잔디: 최근 ${HeatmapModel.WEEKS}주"
    }

    fun setGrid(g: HeatGrid) {
        grid = g
        selected = -1
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val want = (HeatmapModel.WEEKS * cellMax + (HeatmapModel.WEEKS - 1) * gap).toInt() + paddingLeft + paddingRight
        val w = resolveSize(want, widthMeasureSpec)
        val avail = (w - paddingLeft - paddingRight).toFloat()
        cell = ((avail - (HeatmapModel.WEEKS - 1) * gap) / HeatmapModel.WEEKS).coerceIn(cellMax / 2f, cellMax)
        val h = paddingTop + labelH + 7 * cell + 6 * gap + legendH + paddingBottom
        setMeasuredDimension(w, h.toInt())
    }

    private fun cellLeft(column: Int): Float = paddingLeft + column * (cell + gap)

    private fun cellTop(row: Int): Float = paddingTop + labelH + row * (cell + gap)

    override fun onDraw(canvas: Canvas) {
        val g = grid ?: return
        val baseline = paddingTop + labelH - context.dpF(5f)
        for ((column, label) in g.months) canvas.drawText(label, cellLeft(column), baseline, text)
        for (column in 0 until HeatmapModel.WEEKS) {
            for (row in 0 until 7) {
                val i = g.index(column, row)
                if (g.days[i] == 0) continue
                rect.set(cellLeft(column), cellTop(row), cellLeft(column) + cell, cellTop(row) + cell)
                drawCell(canvas, HeatmapModel.level(g.seconds[i]))
                if (i == selected) canvas.drawRect(rect, mark)
            }
        }
        // Legend under the grid's right edge: 적게 □ ▦ ▩ ■ 많이.
        val right = cellLeft(HeatmapModel.WEEKS - 1) + cell
        val top = cellTop(7) - gap + context.dpF(8f)
        val more = "많이"
        val less = "적게"
        val y = top + cell - context.dpF(1f)
        var x = right - text.measureText(more)
        canvas.drawText(more, x, y, text)
        x -= context.dpF(6f)
        for (level in 3 downTo 0) {
            x -= cell
            rect.set(x, top, x + cell, top + cell)
            drawCell(canvas, level)
            x -= gap
        }
        x -= context.dpF(3f) + text.measureText(less)
        canvas.drawText(less, x, y, text)
    }

    private fun drawCell(canvas: Canvas, level: Int) {
        fill.color = LEVELS[level]
        canvas.drawRect(rect, fill)
        if (level == 0) {
            val h = line / 2f
            canvas.drawRect(rect.left + h, rect.top + h, rect.right - h, rect.bottom - h, outline)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val g = grid ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> return true
            MotionEvent.ACTION_UP -> {
                val i = hit(event.x, event.y) ?: return true
                if (g.days[i] == 0) return true
                if (i != selected) {
                    selected = i
                    invalidate()
                }
                performClick()
                onDayTap?.invoke(g.days[i])
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    /** Cell index under (x, y), gaps counted to the cell on their left / above (a tap is never lost). */
    private fun hit(x: Float, y: Float): Int? {
        val column = ((x - paddingLeft) / (cell + gap)).toInt()
        val row = ((y - paddingTop - labelH) / (cell + gap)).toInt()
        if (x < paddingLeft || y < paddingTop + labelH || column !in 0 until HeatmapModel.WEEKS || row !in 0 until 7) return null
        return column * 7 + row
    }

    private companion object {
        /** No reading (white, outlined), under 15 min, under 60 min, 60 min or more. */
        val LEVELS = intArrayOf(Ink.WHITE, 0xFFC0C0C0.toInt(), 0xFF707070.toInt(), Ink.BLACK)
        const val OUTLINE = 0xFF999999.toInt()
    }
}
