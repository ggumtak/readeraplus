package com.ggumtak.readeraplus.reader.pdf

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import kotlin.math.floor

/**
 * The stroke being drawn, rasterized into small tiles instead of one view-sized bitmap: a changed bitmap is
 * uploaded to the GPU whole on the next frame, so each pen sample re-uploads only the one or two tiles it touched
 * (a quarter megabyte) rather than the whole screen. Tiles are created on first touch and kept for the next stroke.
 * Main thread only; nothing allocated per sample once the tiles exist.
 */
internal class LiveTiles(private val tile: Int = 256) {
    private var cols = 0
    private var rows = 0
    private var bitmaps: Array<Bitmap?> = emptyArray()
    private var canvases: Array<Canvas?> = emptyArray()
    private var used = BooleanArray(0)

    /** Ready for a new stroke on a w×h view: the tiles of the last stroke cleared (or a new grid). */
    fun reset(w: Int, h: Int) {
        val c = (w + tile - 1) / tile
        val r = (h + tile - 1) / tile
        if (c != cols || r != rows) {
            cols = c
            rows = r
            bitmaps = arrayOfNulls(c * r)
            canvases = arrayOfNulls(c * r)
            used = BooleanArray(c * r)
            return
        }
        for (i in used.indices) {
            if (used[i]) {
                bitmaps[i]?.eraseColor(0)
                used[i] = false
            }
        }
    }

    /** Draws [path] with [paint] into the tiles under its bounds l, t, r, b (view px, stroke width included). */
    fun drawPath(path: Path, paint: Paint, l: Float, t: Float, r: Float, b: Float) {
        if (cols == 0) return
        val c0 = col(l)
        val c1 = col(r)
        val r0 = row(t)
        val r1 = row(b)
        if (c0 > c1 || r0 > r1) return
        for (ry in r0..r1) {
            for (cx in c0..c1) {
                val cv = canvasAt(ry * cols + cx) ?: continue
                cv.save()
                cv.translate(-(cx * tile).toFloat(), -(ry * tile).toFloat())
                cv.drawPath(path, paint)
                cv.restore()
            }
        }
    }

    /** Draws a circle into the tiles under it. */
    fun drawCircle(x: Float, y: Float, radius: Float, paint: Paint) {
        if (cols == 0) return
        val c0 = col(x - radius)
        val c1 = col(x + radius)
        val r0 = row(y - radius)
        val r1 = row(y + radius)
        if (c0 > c1 || r0 > r1) return
        for (ry in r0..r1) {
            for (cx in c0..c1) {
                val cv = canvasAt(ry * cols + cx) ?: continue
                cv.drawCircle(x - cx * tile, y - ry * tile, radius, paint)
            }
        }
    }

    /** Draws the touched tiles at their places with [paint] (a blend mode for the highlighter, a filter in 어둡게). */
    fun drawTo(canvas: Canvas, paint: Paint?) {
        for (i in used.indices) {
            if (!used[i]) continue
            val bmp = bitmaps[i] ?: continue
            canvas.drawBitmap(bmp, ((i % cols) * tile).toFloat(), ((i / cols) * tile).toFloat(), paint)
        }
    }

    /** Frees every tile (the tools were put away, the document closed). */
    fun release() {
        cols = 0
        rows = 0
        bitmaps = emptyArray()
        canvases = emptyArray()
        used = BooleanArray(0)
    }

    private fun col(x: Float): Int = floor(x / tile).toInt().coerceIn(0, maxOf(0, cols - 1))
    private fun row(y: Float): Int = floor(y / tile).toInt().coerceIn(0, maxOf(0, rows - 1))

    private fun canvasAt(i: Int): Canvas? {
        if (i < 0 || i >= used.size) return null
        var c = canvases[i]
        if (c == null) {
            val bmp = try {
                Bitmap.createBitmap(tile, tile, Bitmap.Config.ARGB_8888)
            } catch (oom: OutOfMemoryError) {
                return null
            }
            bitmaps[i] = bmp
            c = Canvas(bmp)
            canvases[i] = c
        }
        used[i] = true
        return c
    }
}
