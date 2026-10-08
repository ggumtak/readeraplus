package com.ggumtak.readeraplus.render

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.ggumtak.readeraplus.format.DocumentException
import java.io.Closeable
import java.io.File
import java.io.IOException

/**
 * A PDF opened with the platform's [PdfRenderer] (no dependency, no text layer). Blocking and NOT thread-safe:
 * every call of one instance must come from the same single background thread (the viewer's render thread, or
 * the cover worker that opened it). Page sizes are in PDF points (1/72 in) and are read once per page, on use.
 */
class PdfPages private constructor(private val fd: ParcelFileDescriptor, private val renderer: PdfRenderer) : Closeable {
    val pageCount: Int = renderer.pageCount
    /** Width and height of each page in points, 0 until read. */
    private val widths = IntArray(pageCount)
    private val heights = IntArray(pageCount)
    private var closed = false

    /** Width of page [index] in points (opens the page once to read it). */
    fun pageWidth(index: Int): Int {
        ensureSize(index)
        return widths[index]
    }

    /** Height of page [index] in points (opens the page once to read it). */
    fun pageHeight(index: Int): Int {
        ensureSize(index)
        return heights[index]
    }

    private fun ensureSize(index: Int) {
        if (widths[index] > 0) return
        renderer.openPage(index).use { p ->
            widths[index] = p.width.coerceAtLeast(1)
            heights[index] = p.height.coerceAtLeast(1)
        }
    }

    /** The whole page [index] scaled into all of [target] (an ARGB_8888 bitmap), on white. */
    fun renderWhole(index: Int, target: Bitmap) {
        target.eraseColor(Color.WHITE)
        renderer.openPage(index).use { p ->
            if (widths[index] == 0) {
                widths[index] = p.width.coerceAtLeast(1)
                heights[index] = p.height.coerceAtLeast(1)
            }
            p.render(target, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        }
    }

    /**
     * Part of page [index] drawn by [transform] (page points → [target] pixels) inside [clip] (within [target]'s
     * bounds). The caller prepares [target]'s pixels (white under the page, transparent elsewhere).
     */
    fun renderPart(index: Int, target: Bitmap, clip: Rect, transform: Matrix) {
        renderer.openPage(index).use { p ->
            p.render(target, clip, transform, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            renderer.close()
        } finally {
            fd.close()
        }
    }

    companion object {
        /** Opens [file] (blocking). Throws [DocumentException] with a user-facing message when it can't be shown. */
        fun open(file: File): PdfPages {
            val fd = try {
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            } catch (e: IOException) {
                throw DocumentException("파일을 읽을 수 없습니다.\n${file.path}", e)
            }
            val renderer = try {
                PdfRenderer(fd)
            } catch (e: SecurityException) {
                fd.close()
                throw DocumentException("암호가 걸린 PDF는 열 수 없습니다.", e)
            } catch (t: Throwable) {
                fd.close()
                throw DocumentException("PDF 파일을 열 수 없습니다 (손상되었거나 지원하지 않는 형식).", t)
            }
            val pages = PdfPages(fd, renderer)
            if (pages.pageCount <= 0) {
                pages.close()
                throw DocumentException("쪽이 없는 PDF입니다.")
            }
            return pages
        }
    }
}
