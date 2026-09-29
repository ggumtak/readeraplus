package com.ggumtak.readeraplus.render

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Process
import android.text.TextPaint
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import com.ggumtak.readeraplus.engine.LineGeometry
import com.ggumtak.readeraplus.engine.LineInfo
import com.ggumtak.readeraplus.engine.RunStyle
import com.ggumtak.readeraplus.engine.SectionLayout
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * Draws a laid-out page. The page's content box is placed at (contentLeft, contentTop) in canvas coordinates.
 * Colours: black text on white, or white on black when settings.invert.
 *
 * Glyph positions come exclusively from [LineGeometry.charPositions]; text is drawn in segments split at
 * style changes and justification points, so selection/search/TTS geometry and drawing always agree.
 * Each line is copied once into a reusable char buffer and drawn with the char[] `drawTextRun`: the String
 * overload makes JNI copy (or pin) the whole section String for every segment.
 * After the first draw, drawing allocates nothing (paints, rects, position and char arrays, paths are reused).
 * Pages next to the drawn one get their images decoded on a background thread (see [preload]).
 * Use from one thread (the UI thread for the page view).
 */
class PageRenderer(context: Context, private val measurer: AndroidTextMeasurer, private val images: ImageCache?) {

    private val settings = measurer.settings
    private val density = context.resources.displayMetrics.density.let { if (it > 0f) it else 1f }
    private val invert = settings.invert
    private val fg = if (invert) Color.WHITE else Color.BLACK
    private val bg = if (invert) Color.BLACK else Color.WHITE
    private val onePx = 1f
    private val em = measurer.emPx

    private val statusPaint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        typeface = Typeface.SANS_SERIF
        textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            settings.statusFontSizeSp.let { if (it.isFinite() && it > 0f) it.coerceIn(6f, 40f) else 11f },
            context.resources.displayMetrics,
        )
        color = fg
        textLocale = Locale.KOREAN
    }
    private val statusAscent: Float
    private val statusDescent: Float

    private val fill = Paint().apply { style = Paint.Style.FILL }
    private val line = Paint().apply { style = Paint.Style.FILL }
    private val outline = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = onePx
    }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val ribbonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val rect = RectF()
    private val ribbon = Path()
    private var ribbonForWidth = -1

    private var xs = FloatArray(256)
    private var lineChars = CharArray(0)

    /** Page whose neighbours were last handed to the image prefetcher (identity + index). */
    private var prefetchedLayout: SectionLayout? = null
    private var prefetchedPage = -1

    private var headerSrc: String? = null
    private var headerAvail = -1f
    private var headerText: CharSequence = ""

    init {
        val fm = statusPaint.fontMetrics
        statusAscent = -fm.ascent
        statusDescent = fm.descent
        outline.color = fg
        line.color = fg
        ribbonPaint.color = fg
    }

    /** Draws page [pageIndex] of [layout] (background, status lines, highlights, text, images, ribbon). */
    fun draw(
        canvas: Canvas,
        layout: SectionLayout,
        pageIndex: Int,
        contentLeft: Float,
        contentTop: Float,
        viewWidth: Int,
        viewHeight: Int,
        decor: PageDecor,
    ) {
        canvas.drawColor(bg)
        val cw = layout.config.width.toFloat()
        val ch = layout.config.height.toFloat()
        drawStatus(canvas, decor, contentLeft, contentTop, cw, ch, viewHeight)
        if (pageIndex in 0 until layout.pages.size) {
            val page = layout.pages[pageIndex]
            val lines = page.lines
            if (decor.highlights.isNotEmpty()) drawHighlights(canvas, layout, lines, decor.highlights, contentLeft, contentTop)
            for (i in 0 until lines.size) drawLine(canvas, layout, lines[i], contentLeft, contentTop, cw)
        }
        if (decor.bookmarked) drawRibbon(canvas, viewWidth)
        if (images != null) prefetchNeighbours(layout, pageIndex)
    }

    /**
     * Decodes the images of [pageIndex] into the image cache (blocking). Call from a background thread before
     * showing an illustrated page so [draw] never decodes on the UI thread.
     */
    fun preload(layout: SectionLayout, pageIndex: Int) {
        val cache = images ?: return
        val page = layout.pages.getOrNull(pageIndex) ?: return
        for (ln in page.lines) {
            val img = ln.imageBlock ?: continue
            cache.get(img.src, imgW(ln), imgH(ln))
        }
    }

    /** True when page [pageIndex] has an image that is not decoded yet. */
    private fun needsDecode(cache: ImageCache, layout: SectionLayout, pageIndex: Int): Boolean {
        if (pageIndex < 0 || pageIndex >= layout.pages.size) return false
        val lines = layout.pages[pageIndex].lines
        for (i in 0 until lines.size) {
            val ln = lines[i]
            val img = ln.imageBlock ?: continue
            if (!cache.isKnownFailure(img.src, imgW(ln), imgH(ln)) && cache.peek(img.src, imgW(ln), imgH(ln)) == null) return true
        }
        return false
    }

    /** Decodes the images of the previous/next page in the background so turning to them never decodes here. */
    private fun prefetchNeighbours(layout: SectionLayout, pageIndex: Int) {
        val cache = images ?: return
        if (prefetchedLayout === layout && prefetchedPage == pageIndex) return
        prefetchedLayout = layout
        prefetchedPage = pageIndex
        val next = needsDecode(cache, layout, pageIndex + 1)
        val prev = needsDecode(cache, layout, pageIndex - 1)
        if (!next && !prev) return
        imagePrefetcher.submit {
            if (next) preload(layout, pageIndex + 1)
            if (prev) preload(layout, pageIndex - 1)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Status lines

    private fun drawStatus(canvas: Canvas, decor: PageDecor, left: Float, top: Float, cw: Float, ch: Float, viewHeight: Int) {
        val header = decor.header
        if (!header.isNullOrEmpty()) {
            val text = ellipsizedHeader(header, cw)
            val w = statusPaint.measureText(text, 0, text.length)
            val baseline = centredBaseline(0f, top)
            canvas.drawText(text, 0, text.length, left + (cw - w) / 2f, baseline, statusPaint)
        }
        val fl = decor.footerLeft
        val fr = decor.footerRight
        if (fl.isNullOrEmpty() && fr.isNullOrEmpty()) return
        val bandTop = top + ch
        val baseline = centredBaseline(bandTop, maxOf(bandTop, viewHeight.toFloat()))
        var rightW = 0f
        if (!fr.isNullOrEmpty()) {
            rightW = statusPaint.measureText(fr)
            canvas.drawText(fr, left + cw - rightW, baseline, statusPaint)
        }
        if (!fl.isNullOrEmpty()) {
            val avail = cw - rightW - (if (rightW > 0f) statusPaint.textSize else 0f)
            if (statusPaint.measureText(fl) <= avail) {
                canvas.drawText(fl, left, baseline, statusPaint)
            } else if (avail > 0f) {
                val t = TextUtils.ellipsize(fl, statusPaint, avail, TextUtils.TruncateAt.END)
                canvas.drawText(t, 0, t.length, left, baseline, statusPaint)
            }
        }
    }

    private fun centredBaseline(top: Float, bottom: Float): Float {
        val mid = (top + bottom) / 2f
        return mid + (statusAscent - statusDescent) / 2f
    }

    private fun ellipsizedHeader(header: String, avail: Float): CharSequence {
        if (avail == headerAvail && header == headerSrc) return headerText
        val t: CharSequence = if (statusPaint.measureText(header) <= avail) {
            header
        } else {
            TextUtils.ellipsize(header, statusPaint, avail.coerceAtLeast(0f), TextUtils.TruncateAt.END)
        }
        headerSrc = header
        headerAvail = avail
        headerText = t
        return t
    }

    // ---------------------------------------------------------------------------------------------
    // Highlights (under the text)

    private fun drawHighlights(
        canvas: Canvas,
        layout: SectionLayout,
        lines: List<LineInfo>,
        hs: List<Highlight>,
        left: Float,
        top: Float,
    ) {
        for (li in 0 until lines.size) {
            val ln = lines[li]
            val img = ln.imageBlock
            if (img != null) {
                for (k in 0 until hs.size) {
                    val h = hs[k]
                    if (h.start < img.start + 1 && h.end > img.start) {
                        rect.set(left + ln.x, top + ln.top, left + ln.x + ln.imageWidth, top + ln.top + ln.imageHeight)
                        rect.inset(-2f * onePx, -2f * onePx)
                        canvas.drawRect(rect, outline)
                    }
                }
                continue
            }
            if (ln.isRule || ln.end <= ln.start) continue
            var any = false
            for (k in 0 until hs.size) {
                val h = hs[k]
                if (h.start < ln.end && h.end > ln.start && h.end > h.start) { any = true; break }
            }
            if (!any) continue
            val right = positions(layout, ln)
            val s = ln.start
            for (k in 0 until hs.size) {
                val h = hs[k]
                val a = maxOf(h.start, s)
                val b = minOf(h.end, ln.end)
                if (a >= b) continue
                val xa = xs[a - s]
                val xb = if (b >= ln.end) right else xs[b - s]
                val l = left + xa
                val r = left + xb
                val t = top + ln.top
                val bt = top + ln.bottom
                when (h.kind) {
                    HighlightKind.QUOTE -> {
                        fillRect(canvas, l, t, r, bt, grey(0xD8))
                        val y = underlineY(top + ln.baseline, em)
                        canvas.drawRect(l, y, r, y + onePx, line)
                    }
                    HighlightKind.SELECTION -> fillRect(canvas, l, t, r, bt, grey(0xA8))
                    HighlightKind.SEARCH -> {
                        fillRect(canvas, l, t, r, bt, grey(0xC0))
                        rect.set(l + 0.5f, t + 0.5f, r - 0.5f, bt - 0.5f)
                        canvas.drawRect(rect, outline)
                    }
                    HighlightKind.TTS -> {
                        fillRect(canvas, l, t, r, bt, grey(0xE0))
                        val y = underlineY(top + ln.baseline, em)
                        canvas.drawRect(l, y, r, y + 2f * onePx, line)
                    }
                }
            }
        }
    }

    private fun grey(v: Int): Int {
        val g = if (invert) 255 - v else v
        return Color.rgb(g, g, g)
    }

    private fun fillRect(canvas: Canvas, l: Float, t: Float, r: Float, b: Float, color: Int) {
        fill.color = color
        canvas.drawRect(l, t, r, b, fill)
    }

    // ---------------------------------------------------------------------------------------------
    // Lines

    private fun positions(layout: SectionLayout, ln: LineInfo): Float {
        val n = ln.end - ln.start
        if (xs.size < n) xs = FloatArray(maxOf(n, xs.size * 2))
        return LineGeometry.charPositions(layout, ln, xs)
    }

    private fun imgW(ln: LineInfo): Int = Math.round(ln.imageWidth).coerceAtLeast(1)
    private fun imgH(ln: LineInfo): Int = Math.round(ln.imageHeight).coerceAtLeast(1)

    private fun drawLine(canvas: Canvas, layout: SectionLayout, ln: LineInfo, left: Float, top: Float, cw: Float) {
        val img = ln.imageBlock
        if (img != null) {
            rect.set(left + ln.x, top + ln.top, left + ln.x + ln.imageWidth, top + ln.top + ln.imageHeight)
            val bmp = images?.get(img.src, imgW(ln), imgH(ln))
            if (bmp != null) {
                canvas.drawBitmap(bmp, null, rect, bitmapPaint)
            } else {
                rect.inset(0.5f, 0.5f)
                canvas.drawRect(rect, outline)
            }
            return
        }
        if (ln.isRule) {
            val t = maxOf(1f, Math.round(density).toFloat())
            val y = Math.round(top + (ln.top + ln.bottom) / 2f - t / 2f).toFloat()
            canvas.drawRect(left + cw * 0.375f, y, left + cw * 0.625f, y + t, line)
            return
        }
        val text = layout.content.text
        val adv = layout.advances
        val s = ln.start
        val e = minOf(ln.end, text.length, adv.size)
        if (e <= s) return
        val right = positions(layout, ln)
        if (lineChars.size < e - s) lineChars = CharBuffers.grow(lineChars, e - s)
        text.toCharArray(lineChars, 0, s, e)
        val baseY = top + ln.baseline
        val runs = layout.content.styleRuns
        var r = TextSegments.firstRunAfter(runs, s)
        var pos = s
        while (pos < e) {
            val style: RunStyle
            val rangeEnd: Int
            if (r < runs.size && runs[r].start <= pos) {
                style = runs[r].style
                rangeEnd = minOf(runs[r].end, e)
            } else {
                style = RunStyle.PLAIN
                rangeEnd = if (r < runs.size) minOf(runs[r].start, e) else e
            }
            if (rangeEnd <= pos) break
            drawRange(canvas, text, adv, s, pos, rangeEnd, e, right, style, left, baseY)
            pos = rangeEnd
            while (r < runs.size && runs[r].end <= pos) r++
        }
    }

    private fun drawRange(
        canvas: Canvas,
        text: String,
        adv: FloatArray,
        lineStart: Int,
        a: Int,
        b: Int,
        lineEnd: Int,
        right: Float,
        style: RunStyle,
        left: Float,
        baseY: Float,
    ) {
        val paint = measurer.paintFor(style)
        paint.color = fg
        val shift = when {
            style.baselineShift > 0 -> -0.35f * em
            style.baselineShift < 0 -> 0.2f * em
            else -> 0f
        }
        val y = baseY + shift
        var p = a
        while (p < b) {
            val q = TextSegments.segmentEnd(text, adv, xs, lineStart, p, b)
            if (!TextSegments.isBlank(text, p, q)) {
                canvas.drawTextRun(lineChars, p - lineStart, q - p, a - lineStart, b - a, left + xs[p - lineStart], y, false, paint)
            }
            p = q
        }
        if (style.underline || style.strike || style.link != null) {
            val f = TextSegments.firstInk(text, a, b)
            if (f < 0) return
            val l = TextSegments.lastInk(text, a, b)
            val x0 = left + xs[f - lineStart]
            val x1 = left + if (l + 1 >= lineEnd) minOf(right, xs[l - lineStart] + adv[l]) else xs[l - lineStart] + adv[l]
            val size = paint.textSize
            val thick = maxOf(onePx, Math.round(size / 18f).toFloat())
            if (style.underline || style.link != null) {
                val uy = underlineY(y, size)
                canvas.drawRect(x0, uy, x1, uy + thick, line)
            }
            if (style.strike) {
                val sy = Math.round(y - size * 0.32f).toFloat()
                canvas.drawRect(x0, sy, x1, sy + thick, line)
            }
        }
    }

    private fun underlineY(baseline: Float, size: Float): Float = Math.round(baseline + maxOf(onePx * 2f, size * 0.12f)).toFloat()

    // ---------------------------------------------------------------------------------------------
    // Bookmark ribbon

    private fun drawRibbon(canvas: Canvas, viewWidth: Int) {
        if (ribbonForWidth != viewWidth) {
            val w = 14f * density
            val h = 24f * density
            val notch = 6f * density
            val r = viewWidth - 14f * density
            val l = r - w
            ribbon.reset()
            ribbon.moveTo(l, 0f)
            ribbon.lineTo(r, 0f)
            ribbon.lineTo(r, h)
            ribbon.lineTo((l + r) / 2f, h - notch)
            ribbon.lineTo(l, h)
            ribbon.close()
            ribbonForWidth = viewWidth
        }
        canvas.drawPath(ribbon, ribbonPaint)
    }
}

/** Background decoder for the images of neighbouring pages (one low-priority thread, latest request only). */
private val imagePrefetcher = LatestTaskRunner("page-image-prefetch")

/**
 * Runs submitted tasks on one daemon thread, keeping only the most recent pending task (unit-tested): when the
 * reader flips quickly, stale pages are skipped instead of queueing up decodes. Tasks must not throw (errors are
 * logged and swallowed).
 */
internal class LatestTaskRunner(name: String) {
    private val pending = AtomicReference<Runnable?>(null)
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, name).apply { isDaemon = true } }
    private val drainTask = Runnable { drain() }

    fun submit(task: Runnable) {
        if (pending.getAndSet(task) != null) return // a drain is already scheduled and will pick this one up
        try {
            executor.execute(drainTask)
        } catch (t: Throwable) {
            pending.set(null)
        }
    }

    private fun drain() {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
        } catch (t: Throwable) {
            // not fatal (and unavailable in JVM tests)
        }
        while (true) {
            val t = pending.getAndSet(null) ?: return
            try {
                t.run()
            } catch (e: Throwable) {
                try {
                    Log.w("PageRenderer", "background task failed", e)
                } catch (ignored: Throwable) {
                }
            }
        }
    }
}
