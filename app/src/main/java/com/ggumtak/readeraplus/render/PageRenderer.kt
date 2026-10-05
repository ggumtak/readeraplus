package com.ggumtak.readeraplus.render

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.text.TextPaint
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import com.ggumtak.readeraplus.engine.LineGeometry
import com.ggumtak.readeraplus.engine.LineInfo
import com.ggumtak.readeraplus.engine.RunStyle
import com.ggumtak.readeraplus.engine.SectionLayout
import com.ggumtak.readeraplus.settings.StatusBands
import java.util.Collections
import java.util.IdentityHashMap
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * Draws a laid-out page. The page's content box is placed at (contentLeft, contentTop) in canvas coordinates.
 * Colours come from the settings' [PagePalette]: black text on white, white on black when settings.invert (pictures
 * then drawn inverted too), or a theme's own (마루뷰어: light text with a short shadow on dark grey, gold status lines).
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
    private val palette = PagePalette.of(settings)
    private val fg = palette.text
    private val bg = palette.background
    /** Text shadow in px (radius 0 = none); the radius is Paint.setShadowLayer's, not the blur ([PagePalette.radiusForSigma]). */
    private val shadowRadius = palette.shadowRadiusPx(density)
    private val shadowDx = palette.shadowDxDp * density
    private val shadowDy = palette.shadowDyDp * density
    private val onePx = 1f
    private val em = measurer.emPx

    private val statusPaint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        typeface = Typeface.SANS_SERIF
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, StatusBands.statusSp(settings), context.resources.displayMetrics)
        color = palette.status
        textLocale = Locale.KOREAN
        fontFeatureSettings = "tnum"
    }
    private val statusAscent: Float
    private val statusDescent: Float
    /** Ink of the status text's tallest glyphs ([StatusFit.INK_SAMPLE]) around the baseline: top (negative) and bottom. */
    private val statusInkTop: Float
    private val statusInkBottom: Float
    /** Vertical middle of the status digits relative to the baseline (negative = above it): the battery icon's centre. */
    private val digitMiddle: Float
    /** The bands' glyph box ([StatusBands.glyphDp]) and the progress lane, in px. */
    private val statusGlyphPx = StatusFit.glyphPx(settings, density).toFloat()
    private val lanePx = StatusFit.lanePx(density).toFloat()
    private val bandCache = arrayOf(StatusDrawCache(), StatusDrawCache())
    private val slotGeometry = FloatArray(12)
    private val slotNatural = FloatArray(3)
    private val slotWidths = FloatArray(3)
    private val slotLabelWidth = FloatArray(6)
    private val slotText = arrayOfNulls<CharSequence>(6)
    private val slotSource = arrayOfNulls<String>(6)
    private val slotAvail = FloatArray(6) { Float.NaN }
    private val slotSize = FloatArray(6) { Float.NaN }
    private val slotKeepEnd = BooleanArray(6)
    private val quoteFill = Array(QuoteStyles.COUNT) { Paint().apply { style = Paint.Style.FILL } }
    private val quoteHasFill = BooleanArray(QuoteStyles.COUNT)
    private val quoteLine = IntArray(QuoteStyles.COUNT)
    private var lookGen = Int.MIN_VALUE
    private var lookThumb = false
    /** Dedicated thumbnail renderers suppress quote strokes that disappear at small scales. */
    var thumbnail = false
    private val t1 = maxOf(1f, Math.round(0.5f * density).toFloat())
    private val t2 = maxOf(2f, Math.round(density).toFloat())
    private val dashOn = maxOf(1f, Math.round(3f * density).toFloat())
    private val dashPeriod = dashOn + maxOf(1f, Math.round(2f * density).toFloat())
    private val main by lazy { Handler(Looper.getMainLooper()) }

    private val fill = Paint().apply { style = Paint.Style.FILL }
    private val line = Paint().apply { style = Paint.Style.FILL }
    private val outline = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = onePx
    }
    /** The status lines' battery icon and progress line, in the palette's status colour. */
    private val statusLine = Paint().apply { style = Paint.Style.FILL }
    private val statusOutline = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = onePx
    }
    /** MaruViewer's icon-first battery ([BatteryMath.firstStroke]: 2 px on the S25, 1 px on the Comet). */
    private val statusOutlineFirst = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = BatteryMath.firstStroke(density)
    }
    private val statusDot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    /** Pictures; in night mode through the shared inverting filter (T1-3f): no white box glaring on a black page. */
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG).apply {
        if (palette.invertImages) colorFilter = nightImageFilter
    }
    private val ribbonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    /** Background-coloured edge that keeps the ribbon apart from glyphs it touches (tiny margins, no header). */
    private val ribbonHalo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * onePx
        strokeJoin = Paint.Join.ROUND
    }
    private val rect = RectF()
    private val ribbon = Path()
    private var ribbonForWidth = -1
    private var ribbonForHeight = -1f
    private var ribbonForTop = -1f

    private var xs = FloatArray(256)
    private var lineChars = CharArray(0)

    /** Page whose neighbours were last handed to the image prefetcher (identity + index). */
    private var prefetchedLayout: SectionLayout? = null
    private var prefetchedPage = -1
    /** Failed image lines are remembered without constructing cache-key Strings on scroll frames. */
    private val failedImages = ConcurrentHashMap.newKeySet<LineInfo>()
    /** The measurer's paints that carry the text shadow (set once per paint, see [shadow]). */
    private val shadowed: MutableSet<TextPaint> = Collections.newSetFromMap(IdentityHashMap<TextPaint, Boolean>())

    init {
        // Settings and density are fixed per renderer: the status size is fitted to the bands' glyph box once, here.
        val ink = Rect()
        statusPaint.getTextBounds(StatusFit.INK_SAMPLE, 0, StatusFit.INK_SAMPLE.length, ink)
        if (ink.height() > 0) {
            statusPaint.textSize = StatusFit.fitTextPx(statusPaint.textSize, ink.height().toFloat(), statusGlyphPx)
            statusPaint.getTextBounds(StatusFit.INK_SAMPLE, 0, StatusFit.INK_SAMPLE.length, ink)
        }
        val fm = statusPaint.fontMetrics
        statusAscent = -fm.ascent
        statusDescent = fm.descent
        statusInkTop = if (ink.height() > 0) ink.top.toFloat() else fm.ascent
        statusInkBottom = if (ink.height() > 0) ink.bottom.toFloat() else fm.descent
        val digit = Rect()
        statusPaint.getTextBounds("0", 0, 1, digit)
        digitMiddle = if (digit.height() > 0) (digit.top + digit.bottom) / 2f else -0.36f * statusPaint.textSize
        outline.color = fg
        line.color = fg
        ribbonPaint.color = fg
        ribbonHalo.color = bg
        statusLine.color = palette.status
        statusOutline.color = palette.status
        statusOutlineFirst.color = palette.status
        statusDot.color = palette.status
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
        val ribbonTop = ribbonTop(decor)
        val ribbonH = if (decor.bookmarked) RibbonMath.height(density, contentTop - ribbonTop, contentLeft + cw, viewWidth) else 0f
        drawStatus(canvas, decor, contentLeft, contentTop, cw, viewWidth, viewHeight, ribbonH)
        if (pageIndex in 0 until layout.pages.size) {
            val page = layout.pages[pageIndex]
            val lines = page.lines
            if (decor.highlights.isNotEmpty()) drawHighlights(canvas, layout, lines, decor.highlights, contentLeft, contentTop)
            for (i in 0 until lines.size) drawLine(canvas, layout, lines[i], contentLeft, contentTop, cw)
        }
        if (decor.bookmarked) drawRibbon(canvas, viewWidth, ribbonH, ribbonTop)
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
            if (cache.get(img.src, imgW(ln), imgH(ln)) == null && cache.isKnownFailure(img.src, imgW(ln), imgH(ln)))
                failedImages.add(ln)
        }
    }

    /** True when page [pageIndex] has an image that is not decoded yet. */
    private fun needsDecode(cache: ImageCache, layout: SectionLayout, pageIndex: Int): Boolean {
        if (pageIndex < 0 || pageIndex >= layout.pages.size) return false
        val lines = layout.pages[pageIndex].lines
        for (i in 0 until lines.size) {
            val ln = lines[i]
            val img = ln.imageBlock ?: continue
            if (failedImages.contains(ln)) continue
            if (cache.isKnownFailure(img.src, imgW(ln), imgH(ln))) failedImages.add(ln)
            else if (cache.peek(img.src, imgW(ln), imgH(ln)) == null) return true
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

    /**
     * The page under scrolling text: paper and the status bands, in their own places above and below the text box (the
     * geometry made room for them), so the scrolled text clipped to the box never runs under them.
     */
    fun drawChrome(canvas: Canvas, decor: PageDecor, contentLeft: Float, contentTop: Float, contentWidth: Float,
                   viewWidth: Int, viewHeight: Int) {
        canvas.drawColor(bg)
        val h = if (decor.bookmarked) RibbonMath.height(density, contentTop - ribbonTop(decor), contentLeft + contentWidth, viewWidth) else 0f
        drawStatus(canvas, decor, contentLeft, contentTop, contentWidth, viewWidth, viewHeight, h)
    }

    /** Scroll frames only peek at decoded images; a background batch fills missing ones. */
    fun drawBody(canvas: Canvas, layout: SectionLayout, pageIndex: Int, left: Float, top: Float,
                 clipTop: Float, clipBottom: Float, highlights: List<Highlight>): Boolean {
        val page = layout.pages.getOrNull(pageIndex) ?: return false
        val lines = page.lines
        var first = 0
        while (first < lines.size && top + lines[first].bottom <= clipTop) first++
        var end = first
        while (end < lines.size && top + lines[end].top < clipBottom) end++
        if (end <= first) return false
        val save = canvas.save()
        canvas.clipRect(0f, clipTop, canvas.width.toFloat(), clipBottom)
        if (highlights.isNotEmpty()) drawHighlightRange(canvas, layout, lines, highlights, left, top, first, end)
        var missing = false
        for (i in first until end) {
            val ln = lines[i]
            if (ln.imageBlock != null) missing = drawImagePeek(canvas, ln, left, top) || missing
            else drawLine(canvas, layout, ln, left, top, layout.config.width.toFloat())
        }
        canvas.restoreToCount(save)
        return missing
    }

    private fun drawImagePeek(canvas: Canvas, ln: LineInfo, left: Float, top: Float): Boolean {
        val img = ln.imageBlock ?: return false
        rect.set(left + ln.x, top + ln.top, left + ln.x + ln.imageWidth, top + ln.bottom)
        val bmp = images?.peek(img.src, imgW(ln), imgH(ln))
        if (bmp != null) { canvas.drawBitmap(bmp, null, rect, bitmapPaint); return false }
        rect.inset(0.5f, 0.5f)
        canvas.drawRect(rect, outline)
        return images != null && !failedImages.contains(ln)
    }

    fun drawOverlay(canvas: Canvas, decor: PageDecor, contentLeft: Float, contentTop: Float, contentWidth: Float, viewWidth: Int) {
        if (!decor.bookmarked) return
        val top = ribbonTop(decor)
        drawRibbon(canvas, viewWidth, RibbonMath.height(density, contentTop - top, contentLeft + contentWidth, viewWidth), top)
    }

    /** One latest-pending batch; copy callers' reusable slot arrays before submitting. */
    fun prefetchPages(layouts: Array<SectionLayout?>, pages: IntArray, count: Int, done: Runnable?) {
        val cache = images ?: return
        val n = minOf(count, layouts.size, pages.size).coerceAtLeast(0)
        var any = false
        for (i in 0 until n) {
            val l = layouts[i] ?: continue
            if (needsDecode(cache, l, pages[i])) { any = true; break }
        }
        if (!any) return
        val ls = layouts.copyOf(n)
        val ps = pages.copyOf(n)
        imagePrefetcher.submit {
            var decoded = false
            for (i in 0 until n) {
                val l = ls[i] ?: continue
                if (!needsDecode(cache, l, ps[i])) continue
                preload(l, ps[i]); decoded = true
            }
            if (decoded && done != null) main.post(done)
        }
    }

    /**
     * The status bands in their own places at the screen's edges (StatusFit; the text box starts and ends a margin away
     * from them), at the chosen size: the header at the top, or below [StatusDecor.top] (a display cutout's band) centred
     * between it and the text box, the footer and the progress line above the bottom edge gap. [top] / [cw]: the text
     * box, whose column the slots share.
     */
    private fun drawStatus(canvas: Canvas, decor: PageDecor, left: Float, top: Float, cw: Float,
                           viewWidth: Int, viewHeight: Int, ribbonH: Float) {
        val st = decor.status ?: return
        val ts = statusPaint.textSize
        if (!st.header.isEmpty) {
            val baseline = StatusFit.headerBaseline(st.top.toFloat(), top, statusAscent, statusDescent, statusInkTop,
                statusInkBottom, statusGlyphPx, density)
            // The ribbon's place at the header's right end is kept on every page (bookmarked or not), so toggling the
            // bookmark moves only the right slot, by that much, and never re-fits the slots (or the title). The ribbon
            // hangs from the cutout's band (ribbonTop): both measured from there.
            val reserve = RibbonMath.headerInset(density, left + cw, viewWidth,
                RibbonMath.height(density, top - st.top, left + cw, viewWidth), baseline + statusInkTop - st.top)
            drawBand(canvas, st, st.header, left, cw, baseline, 0, ts, reserve, if (ribbonH > 0f) reserve else 0f)
        }
        if (!st.footer.isEmpty) drawBand(canvas, st, st.footer, left, cw, StatusFit.footerBaseline(viewHeight.toFloat(),
            st.lane, statusDescent, statusInkTop, statusInkBottom, statusGlyphPx, density), 1, ts, 0f, 0f)
        if (st.lane) drawProgress(canvas, st.progress, viewWidth, StatusFit.laneBottomPx(viewHeight, density), lanePx)
    }

    private fun slot(b: StatusBand, i: Int): StatusSlot = when (i) { 0 -> b.left; 1 -> b.center; else -> b.right }

    private fun natural(s: StatusSlot, paint: TextPaint, index: Int): Float {
        val text = s.text
        val label = if (text != null) paint.measureText(text, 0, text.length)
            else if (s.length > 0) paint.measureText(s.chars, 0, s.length) else 0f
        slotLabelWidth[index] = label
        if (s.battery < 0) return label
        val ts = paint.textSize
        val digits = if (s.batteryLength > 0) BatteryMath.gap(ts) + paint.measureText(s.batteryChars, 0, s.batteryLength) else 0f
        return label + BatteryMath.iconWidth(ts, s.batteryFirst) + digits + if (label > 0f) BatteryMath.labelGap(ts) else 0f
    }

    /**
     * [reserve]: px at the band's right end kept for the bookmark ribbon (StatusMath.allocate); [shift]: how far the
     * right slot moves in on this page (the reserve on a bookmarked page, else 0). Only [reserve] re-fits the slots.
     */
    private fun drawBand(canvas: Canvas, status: StatusDecor, band: StatusBand, x: Float, w: Float,
                         baseline: Float, bi: Int, ts: Float, reserve: Float, shift: Float) {
        val paint = statusPaint
        val start = bi * 3
        if (bandCache[bi].changed(status, w, reserve, ts)) {
            for (i in 0..2) slotNatural[i] = natural(slot(band, i), paint, start + i)
            StatusMath.allocate(w, maxOf(ts, 8f * density), slotNatural[0], slotNatural[1], slotNatural[2],
                band.left.text != null, band.center.text != null, band.right.text != null, 3f * ts, slotWidths, reserve)
            for (i in 0..2) {
                val index = start + i
                val width = slotWidths[i]
                slotGeometry[index * 2] = when (i) { 0 -> 0f; 1 -> (w - width) / 2f; else -> w - width }
                slotGeometry[index * 2 + 1] = width
                val s = slot(band, i)
                val src = s.text
                if (slotSource[index] !== src || slotAvail[index] != width || slotSize[index] != ts || slotKeepEnd[index] != s.keepEnd) {
                    slotSource[index] = src; slotAvail[index] = width; slotSize[index] = ts; slotKeepEnd[index] = s.keepEnd
                    slotText[index] = if (src == null || width <= 0f) null
                        else if (slotNatural[i] <= width) src
                        else TextUtils.ellipsize(src, paint, width,
                            if (s.keepEnd) TextUtils.TruncateAt.START else TextUtils.TruncateAt.END)
                }
            }
        }
        for (i in 0..2) {
            val index = start + i
            if (slotGeometry[index * 2 + 1] <= 0f) continue
            val s = slot(band, i)
            var sx = x + slotGeometry[index * 2] - if (i == 2) shift else 0f
            if (s.battery >= 0 && s.batteryFirst) {
                // MaruViewer's corner: the icon, then the time.
                drawBattery(canvas, s, sx, baseline, paint)
                sx += BatteryMath.iconWidth(ts, true) + BatteryMath.labelGap(ts)
            }
            val text = slotText[index]
            if (s.text != null && text != null) canvas.drawText(text, 0, text.length, sx, baseline, paint)
            else if (s.length > 0) canvas.drawText(s.chars, 0, s.length, sx, baseline, paint)
            if (s.battery >= 0 && !s.batteryFirst) drawBattery(canvas, s, sx + slotLabelWidth[index] +
                if (slotLabelWidth[index] > 0f) BatteryMath.labelGap(ts) else 0f, baseline, paint)
        }
    }

    /**
     * Fixed char buffers supply battery digits (none for an icon first): no String conversion on a frame. An icon first
     * (MaruViewer's corner) is wider, with a heavier outline: it stands for the number it does not show.
     */
    private fun drawBattery(canvas: Canvas, slot: StatusSlot, x: Float, baseline: Float, paint: TextPaint) {
        val ts = paint.textSize
        val first = slot.batteryFirst
        val stroke = if (first) statusOutlineFirst else statusOutline
        val sw = stroke.strokeWidth
        val bodyLeft = Math.round(x).toFloat()
        val bodyRight = bodyLeft + BatteryMath.bodyWidth(ts, first)
        val bodyH = BatteryMath.bodyHeight(ts, first)
        val bodyTop = Math.round(baseline + digitMiddle * ts / statusPaint.textSize - bodyH / 2f).toFloat()
        val bodyBottom = bodyTop + bodyH
        rect.set(bodyLeft + sw / 2f, bodyTop + sw / 2f, bodyRight - sw / 2f, bodyBottom - sw / 2f)
        canvas.drawRect(rect, stroke)
        val nubH = BatteryMath.nubHeight(ts)
        val nubTop = bodyTop + Math.round((bodyH - nubH) / 2f)
        val nubRight = bodyRight + BatteryMath.nubWidth(ts, first)
        canvas.drawRect(bodyRight, nubTop, nubRight, nubTop + nubH, statusLine)
        if (slot.batteryLength > 0) canvas.drawText(slot.batteryChars, 0, slot.batteryLength, nubRight + BatteryMath.gap(ts), baseline, paint)
        // One px of paper inside the outline, then the level.
        val inset = sw + 1f
        val inL = bodyLeft + inset
        val fillR = BatteryMath.fillRight(inL, bodyRight - inset, slot.battery)
        if (fillR > inL && bodyBottom - inset > bodyTop + inset) canvas.drawRect(inL, bodyTop + inset, fillR, bodyBottom - inset, statusLine)
    }

    private fun drawProgress(canvas: Canvas, fraction: Float, viewW: Int, viewH: Int, lane: Float) {
        val y = ProgressMath.yc(viewH, lane, density).toFloat()
        val x0 = ProgressMath.x0(viewW, density).toFloat()
        val x1 = ProgressMath.x1(viewW, density).toFloat()
        val r = ProgressMath.rCap(lane, density)
        canvas.drawRect(x0, y, x1, y + 1f, statusLine)
        canvas.drawCircle(x0, y + 0.5f, r, statusDot)
        canvas.drawCircle(x1, y + 0.5f, r, statusDot)
        if (fraction >= 0f && fraction.isFinite())
            canvas.drawCircle(ProgressMath.dotX(fraction, viewW, lane, density), y + 0.5f,
                ProgressMath.rDot(lane, density), statusDot)
    }

    // ---------------------------------------------------------------------------------------------
    // Highlights (under the text)

    private fun syncLook() {
        val gen = QuoteLook.generation
        if (gen == lookGen && lookThumb == thumbnail) return
        lookGen = gen; lookThumb = thumbnail
        val ink = QuoteLook.ink()
        for (s in 0 until QuoteStyles.COUNT) {
            if (ink) {
                val value = if (thumbnail) QuoteStyles.thumbGrey(s) else QuoteStyles.inkGrey(s)
                quoteHasFill[s] = value >= 0
                if (value >= 0) quoteFill[s].color = grey(value)
                quoteLine[s] = if (thumbnail) QuoteStyles.LINE_NONE else QuoteStyles.inkLine(s)
            } else {
                val color = QuoteStyles.colorFill(s, palette.dark)
                quoteHasFill[s] = color != 0
                if (color != 0) quoteFill[s].color = color
                quoteLine[s] = if (thumbnail) QuoteStyles.LINE_NONE else QuoteStyles.colorLine(s)
            }
        }
    }

    private fun drawHighlights(canvas: Canvas, layout: SectionLayout, lines: List<LineInfo>,
                               hs: List<Highlight>, left: Float, top: Float) {
        drawHighlightRange(canvas, layout, lines, hs, left, top, 0, lines.size)
    }

    /** Fill every overlapping range before drawing any stroke, so selection never hides a quote underline. */
    private fun drawHighlightRange(canvas: Canvas, layout: SectionLayout, lines: List<LineInfo>,
                                   hs: List<Highlight>, left: Float, top: Float, first: Int, end: Int) {
        syncLook()
        for (li in first until end) {
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
            val t = top + LineGeometry.bandTop(layout, ln)
            val bt = top + LineGeometry.bandBottom(layout, ln)
            val uy = underlineY(top + ln.baseline, em)
            for (pass in 0..1) for (k in 0 until hs.size) {
                val h = hs[k]
                val a = maxOf(h.start, s)
                val b = minOf(h.end, ln.end)
                if (a >= b) continue
                val l = left + xs[a - s]
                val r = left + if (b >= ln.end) right else xs[b - s]
                if (pass == 0) {
                    when (h.kind) {
                        HighlightKind.QUOTE -> {
                            val style = QuoteStyles.of(h.style)
                            if (quoteHasFill[style]) canvas.drawRect(l, t, r, bt, quoteFill[style])
                        }
                        HighlightKind.SELECTION -> fillRect(canvas, l, t, r, bt, grey(0xAA))
                        HighlightKind.SEARCH -> fillRect(canvas, l, t, r, bt, grey(0xBB))
                        HighlightKind.TTS -> fillRect(canvas, l, t, r, bt, grey(0xEE))
                    }
                } else when (h.kind) {
                    HighlightKind.QUOTE -> drawQuoteLine(canvas, quoteLine[QuoteStyles.of(h.style)], l, t, r, bt, uy)
                    HighlightKind.SEARCH -> {
                        rect.set(l + 0.5f, t + 0.5f, r - 0.5f, bt - 0.5f)
                        canvas.drawRect(rect, outline)
                    }
                    HighlightKind.TTS -> canvas.drawRect(l, uy, r, uy + 2f * onePx, line)
                    HighlightKind.SELECTION -> {}
                }
            }
        }
    }

    private fun drawQuoteLine(canvas: Canvas, kind: Int, l: Float, t: Float, r: Float, b: Float, uy: Float) {
        when (kind) {
            QuoteStyles.LINE_THIN -> canvas.drawRect(l, uy, r, uy + t1, line)
            QuoteStyles.LINE_THICK -> canvas.drawRect(l, uy, r, uy + t2, line)
            QuoteStyles.LINE_DASHED -> {
                var x = DashMath.firstDash(l, dashPeriod)
                while (x < r) {
                    val a = maxOf(x, l)
                    val end = minOf(x + dashOn, r)
                    if (end > a) canvas.drawRect(a, uy, end, uy + t2, line)
                    x += dashPeriod
                }
            }
            QuoteStyles.LINE_BOX -> {
                rect.set(l + 0.5f, t + 0.5f, r - 0.5f, b - 0.5f)
                canvas.drawRect(rect, outline)
            }
        }
    }

    private fun grey(v: Int): Int = palette.grey(v)

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
        if (shadowRadius > 0f) shadow(paint)
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

    /**
     * Gives [paint] the palette's text shadow once: setShadowLayer is a JNI call that builds a new native blur every
     * time. The measurer's paints belong to this renderer, so only a palette with a shadow ever sets one. Small
     * thumbnails ([thumbnail]) go without it: under a pixel at their scale, it would only cost a blurred pass per glyph.
     */
    private fun shadow(paint: TextPaint) {
        if (!thumbnail) {
            if (shadowed.add(paint)) paint.setShadowLayer(shadowRadius, shadowDx, shadowDy, palette.shadowColor)
        } else if (shadowed.remove(paint)) {
            paint.clearShadowLayer()
        }
    }

    private fun underlineY(baseline: Float, size: Float): Float = Math.round(baseline + maxOf(onePx * 2f, size * 0.12f)).toFloat()

    // ---------------------------------------------------------------------------------------------
    // Bookmark ribbon

    /**
     * The ribbon hangs from the page view's top, or from the bottom of a display cutout's band ([StatusDecor.top]: the
     * S25's camera hole in fullscreen), where the installed build hung it and where the bookmark's tap corner starts.
     */
    private fun ribbonTop(decor: PageDecor): Float = (decor.status?.top ?: 0).toFloat()

    private fun drawRibbon(canvas: Canvas, viewWidth: Int, h: Float, top: Float) {
        if (ribbonForWidth != viewWidth || ribbonForHeight != h || ribbonForTop != top) {
            val l = RibbonMath.left(viewWidth, density)
            val r = l + RibbonMath.WIDTH_DP * density
            val notch = h * RibbonMath.NOTCH_FRACTION
            ribbon.reset()
            ribbon.moveTo(l, top)
            ribbon.lineTo(r, top)
            ribbon.lineTo(r, top + h)
            ribbon.lineTo((l + r) / 2f, top + h - notch)
            ribbon.lineTo(l, top + h)
            ribbon.close()
            ribbonForWidth = viewWidth
            ribbonForHeight = h
            ribbonForTop = top
        }
        canvas.drawPath(ribbon, ribbonHalo)
        canvas.drawPath(ribbon, ribbonPaint)
    }
}

/**
 * Bookmark ribbon geometry (px; pure, unit-tested). The ribbon hangs from the top edge, or from the bottom of a display
 * cutout's band (the S25's camera hole in fullscreen: every top and height here is then measured from that band's
 * bottom), [RIGHT_DP] from the view's right edge. It keeps its full height only where that stays above the text column;
 * otherwise it shrinks to the band above the text (never below [MIN_HEIGHT_DP]). A header that would run under it keeps
 * [headerInset] free at its right end (StatusMath.allocate's reserve) on every page, so a bookmark moves only its right
 * slot.
 */
internal object RibbonMath {
    const val WIDTH_DP = 14f
    const val HEIGHT_DP = 24f
    const val MIN_HEIGHT_DP = 12f
    const val RIGHT_DP = 14f
    /** Clearance kept between the ribbon and text. */
    const val GAP_DP = 3f
    const val NOTCH_FRACTION = 0.25f

    fun left(viewWidth: Int, density: Float): Float = viewWidth - (RIGHT_DP + WIDTH_DP) * density

    /** Ribbon height for a text column whose top is [contentTop] and right edge [contentRight]. */
    fun height(density: Float, contentTop: Float, contentRight: Float, viewWidth: Int): Float {
        val full = HEIGHT_DP * density
        if (contentRight <= left(viewWidth, density) - GAP_DP * density) return full
        return (contentTop - GAP_DP * density).coerceIn(MIN_HEIGHT_DP * density, full)
    }

    /**
     * Width the header (on a column ending at [contentRight]) keeps free at its right end so its glyphs, whose top is
     * at [glyphTop], stay clear of a ribbon of height [ribbonH]; 0 when they cannot meet.
     */
    fun headerInset(density: Float, contentRight: Float, viewWidth: Int, ribbonH: Float, glyphTop: Float): Float {
        if (!(ribbonH > 0f) || glyphTop >= ribbonH + GAP_DP * density) return 0f
        return (contentRight - (left(viewWidth, density) - GAP_DP * density)).coerceAtLeast(0f)
    }
}

/**
 * Status battery icon geometry in px from the status text size `ts` (pure, unit-tested): a 0.9 × 0.5 ts outline with a
 * 0.08 × 0.25 ts nub, 0.25 ts before the digits, 0.5 ts from the slot's text; sizes are whole px so the 1 px lines stay
 * crisp on e-ink. The icon that comes first and has no number (`first`, MaruViewer's corner: `StatusItem.CLOCK_BATTERY`)
 * is MaruViewer's shape: 1.75 × 0.6 ts with a 0.1 ts nub and a [firstStroke] outline.
 */
internal object BatteryMath {
    fun bodyWidth(ts: Float, first: Boolean = false): Float =
        if (first) maxOf(10f, Math.round(1.75f * ts).toFloat()) else maxOf(6f, Math.round(0.9f * ts).toFloat())

    fun bodyHeight(ts: Float, first: Boolean = false): Float =
        if (first) maxOf(6f, Math.round(0.6f * ts).toFloat()) else maxOf(5f, Math.round(0.5f * ts).toFloat())

    fun nubWidth(ts: Float, first: Boolean = false): Float =
        maxOf(1f, Math.round((if (first) 0.1f else 0.08f) * ts).toFloat())

    fun nubHeight(ts: Float): Float = maxOf(1f, Math.round(0.25f * ts).toFloat())

    /** The first icon's outline: 0.6 dp in whole px (2 px at density 3, 1 px at 2: crisp on e-ink). */
    fun firstStroke(density: Float): Float = maxOf(1f, Math.round(0.6f * density).toFloat())

    fun gap(ts: Float): Float = 0.25f * ts

    /** Body and nub: the icon's whole width. */
    fun iconWidth(ts: Float, first: Boolean = false): Float = bodyWidth(ts, first) + nubWidth(ts, first)

    /** Between the icon (with its number) and a slot's text, on either side of it. */
    fun labelGap(ts: Float): Float = 0.5f * ts

    /**
     * Right edge of the level fill spanning [inLeft, inRight) for [level] percent: [inLeft] (no fill) at 0 or when there
     * is no room, at least 1 px for any level above 0, [inRight] at 100.
     */
    fun fillRight(inLeft: Float, inRight: Float, level: Int): Float {
        val lv = level.coerceIn(0, 100)
        if (lv == 0 || !(inRight - inLeft >= 1f)) return inLeft
        return maxOf(inLeft + 1f, Math.round(inLeft + (inRight - inLeft) * lv / 100f).toFloat()).coerceAtMost(inRight)
    }
}

/**
 * The night-mode picture filter (T1-3f): RGB inverted, alpha kept. One instance for every renderer; created on the
 * first inverted page (a native object: never at class load).
 */
private val nightImageFilter: ColorMatrixColorFilter by lazy {
    ColorMatrixColorFilter(
        ColorMatrix(
            floatArrayOf(
                -1f, 0f, 0f, 0f, 255f,
                0f, -1f, 0f, 0f, 255f,
                0f, 0f, -1f, 0f, 255f,
                0f, 0f, 0f, 1f, 0f,
            ),
        ),
    )
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
