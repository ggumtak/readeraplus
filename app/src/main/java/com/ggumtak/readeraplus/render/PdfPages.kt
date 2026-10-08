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
import com.ggumtak.readeraplus.render.pdftext.PageGlyphs
import com.ggumtak.readeraplus.render.pdftext.PageGlyphsOps
import com.ggumtak.readeraplus.render.pdftext.PdfTextReader
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
 * Text (selection, search) comes from the platform's PDF text API where there is one: [PdfRenderer] from Android 15,
 * or [PdfRendererPreV] on Android 12–14 with the PDF system module (SDK extension S ≥ 13, from Google Play system
 * updates). Elsewhere the app's own reader ([PdfTextReader]) reads the text layer. [canReadText] says whether either
 * works for this file; text calls return null / empty without it.
 */
class PdfPages private constructor(
    private val fd: ParcelFileDescriptor,
    private val backend: Backend,
    private val file: File,
    withText: Boolean,
) : Closeable {
    val pageCount: Int = backend.pageCount
    /** The app's text reader, opened on the first text call (a broken file's recovery scan never delays page one). */
    private var ownState = if (withText && !backend.hasText) OWN_UNOPENED else OWN_NONE
    private var ownReader: PdfTextReader? = null
    /**
     * Text layers can be read (selection, search). A scanned PDF still has no text; with the app's own reader this
     * is a promise until the first text call, after which a file it can't read turns it false.
     */
    val canReadText: Boolean get() = backend.hasText || ownState != OWN_NONE

    private val own: PdfTextReader?
        get() {
            if (ownState == OWN_UNOPENED) {
                ownState = OWN_NONE
                ownReader = openOwn()
                if (ownReader != null) ownState = OWN_OPEN
            }
            return ownReader
        }

    private fun openOwn(): PdfTextReader? = try {
        val r = PdfTextReader.open(file)
        if (r.pageCount == backend.pageCount) r else {
            // Another page tree than the renderer's: its text would land on the wrong pages.
            Log.w(TAG, "own text reader: ${r.pageCount} pages, renderer ${backend.pageCount}; no text")
            r.close()
            null
        }
    } catch (oom: OutOfMemoryError) {
        null
    } catch (t: Throwable) {
        Log.w(TAG, "own text reader failed: ${t.message}")
        null
    }
    /** The last pages read by [own] (a lasso reads the lines, then selects on the same page). */
    private var ownPage = -1
    private var ownGlyphs: PageGlyphs = PageGlyphs.EMPTY
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

    /** The page's text with one entry per text run (usually lines), or null without text. */
    fun textRuns(index: Int): List<PdfText>? = when {
        backend.hasText -> safely { backend.allText(index) }
        own != null -> safely {
            val g = ownText(index)
            if (g.text.isEmpty()) return@safely emptyList()
            val sx = ownScaleX(index)
            val sy = ownScaleY(index)
            PageGlyphsOps.lineBoxes(g).map { PdfText(g.text.substring(it.start, it.end), listOf(rect(it.box, sx, sy))) }
        }
        else -> null
    }

    /** Text from the character at (x0, y0) to the one at (x1, y1) in reading order (page points), or null. */
    fun selectBetween(index: Int, x0: Float, y0: Float, x1: Float, y1: Float): PdfText? = when {
        backend.hasText -> safely { backend.select(index, x0, y0, x1, y1) }
        own != null -> safely {
            val g = ownText(index)
            if (g.text.isEmpty()) return@safely null
            val sx = ownScaleX(index)
            val sy = ownScaleY(index)
            PageGlyphsOps.select(g, x0 / sx, y0 / sy, x1 / sx, y1 / sy)
                ?.let { sel -> PdfText(sel.text, sel.boxes.map { rect(it, sx, sy) }) }
        }
        else -> null
    }

    /** Rectangles (page points) of each match of [query] on page [index]; empty without text or matches. */
    fun search(index: Int, query: String): List<List<RectF>> = when {
        query.isBlank() -> emptyList()
        backend.hasText -> safely { backend.search(index, query) } ?: emptyList()
        own != null -> safely {
            // Most pages have no match: the renderer's page (for the scale) is opened only for those that do.
            val found = PageGlyphsOps.search(ownText(index), query)
            if (found.isEmpty()) return@safely emptyList()
            val sx = ownScaleX(index)
            val sy = ownScaleY(index)
            found.map { m -> m.map { rect(it, sx, sy) } }
        } ?: emptyList()
        else -> emptyList()
    }

    private fun ownText(index: Int): PageGlyphs {
        val r = ownReader ?: return PageGlyphs.EMPTY
        if (index != ownPage) {
            ownGlyphs = PageGlyphs.EMPTY
            ownPage = -1
            ownGlyphs = r.page(index)
            ownPage = index
        }
        return ownGlyphs
    }

    // The app's reader measures the page itself; its points are scaled onto the renderer's should the two differ.
    private fun ownScaleX(index: Int): Float {
        val w = ownReader?.pageSize(index)?.get(0) ?: return 1f
        return if (w > 0f) pageWidth(index) / w else 1f
    }

    private fun ownScaleY(index: Int): Float {
        val h = ownReader?.pageSize(index)?.get(1) ?: return 1f
        return if (h > 0f) pageHeight(index) / h else 1f
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            backend.close()
        } finally {
            try {
                ownReader?.close()
            } finally {
                fd.close()
            }
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
        private const val OWN_NONE = 0
        private const val OWN_UNOPENED = 1
        private const val OWN_OPEN = 2

        private fun pack(w: Int, h: Int): Long = (w.toLong() shl 32) or (h.toLong() and 0xFFFFFFFFL)

        private fun boundary(x: Float, y: Float) = SelectionBoundary(Point(x.toInt(), y.toInt()))

        private fun rect(b: FloatArray, sx: Float, sy: Float) = RectF(b[0] * sx, b[1] * sy, b[2] * sx, b[3] * sy)

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
            val pages = PdfPages(fd, backend, file, withText)
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
