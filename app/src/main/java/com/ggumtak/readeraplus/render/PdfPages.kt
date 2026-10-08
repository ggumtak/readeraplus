package com.ggumtak.readeraplus.render

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Point
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.graphics.pdf.PdfRendererPreV
import android.graphics.pdf.RenderParams
import android.graphics.pdf.content.PdfPageTextContent
import android.graphics.pdf.models.selection.SelectionBoundary
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.ext.SdkExtensions
import android.util.Log
import com.ggumtak.readeraplus.format.DocumentException
import java.io.Closeable
import java.io.File
import java.io.IOException

/** Text of a PDF page region and its rectangles, in page points (1/72 in, origin top-left). */
class PdfText(val text: String, val rects: List<RectF>)

/**
 * A PDF opened with the platform renderer (no dependency). Blocking and NOT thread-safe: every call of one instance
 * must come from the same single background thread (the viewer's render thread, or the cover worker that opened it).
 * Page sizes are in PDF points and are read once per page, on use.
 *
 * Text (selection, search) needs the platform's PDF text API: [PdfRenderer] from Android 15, or [PdfRendererPreV]
 * on Android 12–14 with the PDF system module (SDK extension S ≥ 13, delivered by Google Play system updates).
 * [canReadText] says whether this device has it; text calls return null / empty without it.
 */
class PdfPages private constructor(private val fd: ParcelFileDescriptor, private val backend: Backend) : Closeable {
    val pageCount: Int = backend.pageCount
    /** The device can read text layers (selection, search). A scanned PDF still has no text. */
    val canReadText: Boolean get() = backend.hasText
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
        val wh = backend.size(index)
        widths[index] = (wh ushr 32).toInt().coerceAtLeast(1)
        heights[index] = (wh and 0xFFFFFFFFL).toInt().coerceAtLeast(1)
    }

    /** The whole page [index] scaled into all of [target] (an ARGB_8888 bitmap), on white. */
    fun renderWhole(index: Int, target: Bitmap) {
        target.eraseColor(Color.WHITE)
        val m = Matrix()
        m.setScale(target.width.toFloat() / pageWidth(index), target.height.toFloat() / pageHeight(index))
        backend.render(index, target, null, m)
    }

    /**
     * Part of page [index] drawn by [transform] (page points → [target] pixels) inside [clip] (within [target]'s
     * bounds). The caller prepares [target]'s pixels (white under the page, transparent elsewhere).
     */
    fun renderPart(index: Int, target: Bitmap, clip: Rect, transform: Matrix) {
        backend.render(index, target, clip, transform)
    }

    /** The page's text with one entry per text run the platform reports (usually lines), or null without text. */
    fun textRuns(index: Int): List<PdfText>? = if (!backend.hasText) null else safely { backend.allText(index) }

    /** Text from the character at (x0, y0) to the one at (x1, y1) in reading order (page points), or null. */
    fun selectBetween(index: Int, x0: Float, y0: Float, x1: Float, y1: Float): PdfText? =
        if (!backend.hasText) null else safely { backend.select(index, x0, y0, x1, y1) }

    /** Rectangles (page points) of each match of [query] on page [index]; empty without text or matches. */
    fun search(index: Int, query: String): List<List<RectF>> =
        if (!backend.hasText || query.isBlank()) emptyList() else safely { backend.search(index, query) } ?: emptyList()

    override fun close() {
        if (closed) return
        closed = true
        try {
            backend.close()
        } finally {
            fd.close()
        }
    }

    /** A text call that fails (incl. a missing method of an older PDF module) answers null; out of memory still throws. */
    private inline fun <T> safely(block: () -> T): T? = try {
        block()
    } catch (oom: OutOfMemoryError) {
        throw oom
    } catch (t: Throwable) {
        Log.w(TAG, "text call failed", t)
        null
    }

    /** What the two platform renderers have in common. */
    private interface Backend : Closeable {
        val pageCount: Int
        val hasText: Boolean
        /** Width (high 32 bits) and height (low 32 bits) in points. */
        fun size(index: Int): Long
        fun render(index: Int, target: Bitmap, clip: Rect?, transform: Matrix)
        fun allText(index: Int): List<PdfText>
        fun select(index: Int, x0: Float, y0: Float, x1: Float, y1: Float): PdfText?
        fun search(index: Int, query: String): List<List<RectF>>
    }

    /** [PdfRenderer]: rendering everywhere, text from Android 15. */
    private class Platform(private val r: PdfRenderer) : Backend {
        override val pageCount: Int = r.pageCount
        override val hasText: Boolean = Build.VERSION.SDK_INT >= 35

        override fun size(index: Int): Long = r.openPage(index).use { pack(it.width, it.height) }

        override fun render(index: Int, target: Bitmap, clip: Rect?, transform: Matrix) {
            r.openPage(index).use { it.render(target, clip, transform, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) }
        }

        @TargetApi(35)
        override fun allText(index: Int): List<PdfText> = r.openPage(index).use { p ->
            val contents = p.textContents
            if (contents.isEmpty()) return emptyList()
            // Index boundaries over the whole page: each run comes with its rectangles.
            val length = contents.sumOf { it.text.length }
            if (length <= 0) return emptyList()
            // Without rectangles from the selection (it may refuse the end index), the plain runs still give text.
            val sel = try {
                p.selectContent(SelectionBoundary(0), SelectionBoundary(length - 1))
            } catch (e: Exception) {
                null
            }
            runs(sel?.selectedTextContents?.takeIf { it.isNotEmpty() } ?: contents)
        }

        @TargetApi(35)
        override fun select(index: Int, x0: Float, y0: Float, x1: Float, y1: Float): PdfText? = r.openPage(index).use { p ->
            val sel = p.selectContent(boundary(x0, y0), boundary(x1, y1)) ?: return null
            joined(sel.selectedTextContents)
        }

        @TargetApi(35)
        override fun search(index: Int, query: String): List<List<RectF>> = r.openPage(index).use { p ->
            p.searchText(query).map { m -> m.bounds.map { RectF(it) } }
        }

        override fun close() = r.close()
    }

    /** [PdfRendererPreV]: Android 12–14 with the PDF system module; rendering and text. */
    @SuppressLint("NewApi")
    private class PreV(private val r: PdfRendererPreV) : Backend {
        override val pageCount: Int = r.pageCount
        override val hasText: Boolean = true
        private val params = RenderParams.Builder(RenderParams.RENDER_MODE_FOR_DISPLAY).build()

        override fun size(index: Int): Long = r.openPage(index).use { pack(it.width, it.height) }

        override fun render(index: Int, target: Bitmap, clip: Rect?, transform: Matrix) {
            r.openPage(index).use { it.render(target, clip, transform, params) }
        }

        override fun allText(index: Int): List<PdfText> = r.openPage(index).use { p ->
            val contents = p.textContents
            if (contents.isEmpty()) return emptyList()
            val length = contents.sumOf { it.text.length }
            if (length <= 0) return emptyList()
            // Without rectangles from the selection (it may refuse the end index), the plain runs still give text.
            val sel = try {
                p.selectContent(SelectionBoundary(0), SelectionBoundary(length - 1))
            } catch (e: Exception) {
                null
            }
            runs(sel?.selectedTextContents?.takeIf { it.isNotEmpty() } ?: contents)
        }

        override fun select(index: Int, x0: Float, y0: Float, x1: Float, y1: Float): PdfText? = r.openPage(index).use { p ->
            val sel = p.selectContent(boundary(x0, y0), boundary(x1, y1)) ?: return null
            joined(sel.selectedTextContents)
        }

        override fun search(index: Int, query: String): List<List<RectF>> = r.openPage(index).use { p ->
            p.searchText(query).map { m -> m.bounds.map { RectF(it) } }
        }

        override fun close() = r.close()
    }

    companion object {
        private const val TAG = "PdfPages"

        private fun pack(w: Int, h: Int): Long = (w.toLong() shl 32) or (h.toLong() and 0xFFFFFFFFL)

        private fun boundary(x: Float, y: Float) = SelectionBoundary(Point(x.toInt(), y.toInt()))

        private fun runs(contents: List<PdfPageTextContent>): List<PdfText> =
            contents.filter { it.text.isNotEmpty() }.map { c -> PdfText(c.text, c.bounds.map { RectF(it) }) }

        private fun joined(contents: List<PdfPageTextContent>): PdfText? {
            val text = contents.joinToString("") { it.text }.trim()
            if (text.isEmpty()) return null
            return PdfText(text, contents.flatMap { c -> c.bounds.map { RectF(it) } })
        }

        /** Whether this device has the PDF text API on Android 12–14 (the PDF system module, extension S ≥ 13). */
        private fun hasPreV(): Boolean = Build.VERSION.SDK_INT in 31..34 && try {
            SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 13
        } catch (t: Throwable) {
            false
        }

        /**
         * Opens [file] (blocking). Throws [DocumentException] with a user-facing message when it can't be shown.
         * [withText] = false always uses the plain renderer (covers: no text needed).
         */
        fun open(file: File, withText: Boolean = true): PdfPages {
            val fd = try {
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            } catch (e: IOException) {
                throw DocumentException("파일을 읽을 수 없습니다.\n${file.path}", e)
            }
            val backend: Backend = try {
                if (withText && hasPreV()) {
                    // A broken PDF system module must not cost the page view: fall back to the plain renderer.
                    try {
                        openPreV(fd)
                    } catch (e: SecurityException) {
                        throw e
                    } catch (t: Throwable) {
                        Log.w(TAG, "PdfRendererPreV failed, plain renderer instead", t)
                        Platform(PdfRenderer(fd))
                    }
                } else {
                    Platform(PdfRenderer(fd))
                }
            } catch (e: SecurityException) {
                fd.close()
                throw DocumentException("암호가 걸린 PDF는 열 수 없습니다.", e)
            } catch (t: Throwable) {
                fd.close()
                throw DocumentException("PDF 파일을 열 수 없습니다 (손상되었거나 지원하지 않는 형식).", t)
            }
            val pages = PdfPages(fd, backend)
            if (pages.pageCount <= 0) {
                pages.close()
                throw DocumentException("쪽이 없는 PDF입니다.")
            }
            return pages
        }

        @SuppressLint("NewApi")
        private fun openPreV(fd: ParcelFileDescriptor): Backend = PreV(PdfRendererPreV(fd))
    }
}
