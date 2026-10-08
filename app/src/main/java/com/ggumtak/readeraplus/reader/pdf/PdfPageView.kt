package com.ggumtak.readeraplus.reader.pdf

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.ColorMatrixColorFilter
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.os.Build
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.OverScroller
import com.ggumtak.readeraplus.ui.kit.Ink
import com.ggumtak.readeraplus.ui.kit.dp
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/**
 * One PDF page, fitted inside the view (minus padding), with phone-style motion: pinch zoom that follows the
 * fingers (springing back from below the fitted size), an animated double-tap zoom, drag panning with inertia, and
 * page turns that slide — the page follows a horizontal drag with its neighbour coming in, and taps / keys slide
 * too ([animateTurn]). The PDF viewer is for phones, so unlike the e-ink text reader it animates.
 *
 * Two bitmaps are drawn for the page: the whole page rendered at the fitted size ([setPage]; scaled while zooming),
 * and, once a zoomed viewport has settled, a view-sized "detail" bitmap rendered at full resolution for exactly that
 * viewport ([setDetail]). Neighbour pages for the slide come from [setNeighbors]. The view reports taps, finished
 * slides and viewport changes to its [host] and never renders anything itself. Main thread only; [onDraw] allocates
 * nothing.
 */
internal class PdfPageView(context: Context) : View(context) {

    interface Host {
        /** A single tap at view x [x] (the host maps it to a tap zone). */
        fun onPageTap(x: Float)
        /** A page slide ended on the neighbour page [image]: the host makes it the current page. */
        fun onPageSettled(image: PageImage)
        /** Zoom or pan changed (the host asks for a new detail bitmap once it settles). */
        fun onViewportChanged()
        /** Strokes of [page] changed (drawn, erased): the host saves the notes. */
        fun onInkChanged(page: Int)
        /** A selection loop was drawn on [page]: [poly] = x, y pairs in page points. The loop stays shown until [clearLasso]. */
        fun onLasso(page: Int, poly: FloatArray)
    }

    /** A page's fitted bitmap: page [index] of [w]×[h] points. */
    open class PageImage(val index: Int, val w: Int, val h: Int, val bitmap: Bitmap)

    /** What a detail bitmap was rendered for: page, scale (px per point) and the page's top-left in the bitmap. */
    class Viewport(
        @JvmField val page: Int,
        @JvmField val scale: Float,
        @JvmField val left: Float,
        @JvmField val top: Float,
        @JvmField val width: Int,
        @JvmField val height: Int,
    )

    var host: Host? = null
    /** Horizontal drags slide to the neighbour pages (설정 "밀어서 넘기기"). */
    var swipeEnabled = true
    /**
     * Fingers a page-turning swipe takes: 1, or 2 (one finger then never turns by dragging; two fingers swipe, and
     * a two-finger touch becomes a pinch only once the fingers clearly spread or close). With a drawing tool on,
     * swiping always takes two fingers.
     */
    var swipeFingers = 1

    /** Current page index (-1 = none yet). */
    var page = -1
        private set
    private var pageW = 0
    private var pageH = 0
    private var base: Bitmap? = null
    private var detail: Bitmap? = null
    private var detailFor: Viewport? = null
    private var prevImg: PageImage? = null
    private var nextImg: PageImage? = null

    /** Zoom over the fitted size (1 = whole page visible). Kept across page turns. */
    var zoom = 1f
        private set
    /** Page's top-left in content-area coordinates (padding excluded). */
    private var offX = 0f
    private var offY = 0f

    /** Horizontal shift of the unzoomed page while it slides: < 0 = the next page comes in from the right. */
    private var slide = 0f
    private var dragging = false
    /** Direction of a fling during the current drag (+1 = toward the next page), 0 = none. */
    private var flingDir = 0
    private var slideAnim: ValueAnimator? = null
    private var zoomAnim: ValueAnimator? = null
    private val scroller = OverScroller(context)
    /** Exact (float) end of a running reading step: the scroller works in whole pixels. NaN = none. */
    private var stepTargetY = Float.NaN
    /** The running slide is a page turn (taps / keys / a released drag), not a return to rest. */
    private var slideTurns = false
    /** A pinch happened during the current touch: lifting the fingers never turns the page. */
    private var pinched = false
    /** What the current two-finger gesture is: [TWO_UNDECIDED] until the fingers show it. */
    private var twoIntent = TWO_PINCH
    private var twoScale = 1f
    private var twoDx = 0f
    private var twoDy = 0f
    private var lastFocusX = 0f
    private var lastFocusY = 0f
    private val swipeSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop * 2f
    private val gap = context.dp(16).toFloat()

    private val dst = RectF()
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val pagePaint = Paint().apply { color = Ink.WHITE }
    private val edgePaint = Paint().apply {
        color = 0xFF3A3A3A.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }

    // ------------------------------------------------------------------------------------------- annotations

    /** The book's annotations, drawn over the pages and edited by the pen tools; null until loaded. */
    var notes: PdfNotes? = null
        set(v) {
            field = v
            inkPaths.clear()
            invalidate()
        }

    /** What a single finger or pen does: [MODE_NONE] = reading (a stylus still draws a selection loop). */
    var mode = MODE_NONE
        set(v) {
            if (field == v) return
            cancelInk()
            field = v
            // The live tiles are for drawing tools only (a stylus loop while reading draws a path).
            if (v == MODE_NONE || v == MODE_ERASER || v == MODE_LASSO) tiles.release()
        }
    var penColor = 0xFF000000.toInt()
    /** Opaque: highlights multiply onto the page, so the text under them stays black. */
    var highlighterColor = 0xFFFFF176.toInt()
    /** Full widths of the tools, in page points (they zoom with the page). */
    var penWidth = 1.4f
    var highlighterWidth = 11f
    /** The pen follows stylus pressure (a finger always draws at full width). */
    var penPressure = true
    /** With a tool on, a finger draws too; false: only a stylus draws, fingers move, zoom and turn pages. */
    var fingerDraws = true

    /** Page colour: [PdfPrefs.TONE_NORMAL], dark (inverted) or sepia; the ink keeps its own colours. */
    var tone = PdfPrefs.TONE_NORMAL
        set(v) {
            if (field == v) return
            field = v
            val filter = when (v) {
                PdfPrefs.TONE_DARK -> ColorMatrixColorFilter(
                    floatArrayOf(-1f, 0f, 0f, 0f, 255f, 0f, -1f, 0f, 0f, 255f, 0f, 0f, -1f, 0f, 255f, 0f, 0f, 0f, 1f, 0f),
                )
                PdfPrefs.TONE_SEPIA -> ColorMatrixColorFilter(
                    floatArrayOf(0.95f, 0f, 0f, 0f, 12f, 0f, 0.88f, 0f, 0f, 6f, 0f, 0f, 0.72f, 0f, 0f, 0f, 0f, 0f, 1f, 0f),
                )
                else -> null
            }
            bitmapPaint.colorFilter = filter
            pagePaint.colorFilter = filter
            plainPaint.colorFilter = filter
            // On the dark page the ink turns light too, keeping its hue (black → white, red → light red).
            val inkFilter = if (v == PdfPrefs.TONE_DARK) ColorMatrixColorFilter(INK_LIGHTNESS_INVERT) else null
            inkPaint.colorFilter = inkFilter
            inkFillPaint.colorFilter = inkFilter
            layerPaint.colorFilter = inkFilter
            // The live layer holds the ink's own colours and gets the filter once, when drawn (tail likewise).
            tailPaint.colorFilter = inkFilter
            // A dark page would swallow a multiplied highlight: highlights are laid over it, half transparent.
            val dark = v == PdfPrefs.TONE_DARK
            highlightBlend(highlightPaint, !dark)
            highlightBlend(layerMultiply, !dark)
            layerMultiply.alpha = if (dark) 0x80 else 0xFF
            highlightAlpha = if (dark) 0x80 else 0xFF
            invalidate()
        }
    private var highlightAlpha = 0xFF
    /** Bitmaps drawn 1:1 (fitted page, detail): no filtering, only the page colour filter. */
    private val plainPaint = Paint()

    /**
     * A stylus has touched this view: from then on fingers only move, zoom and turn pages (palm rejection, as in
     * Flexcil), whatever [fingerDraws] says.
     */
    private var stylusSeen = false
    /** Whether a finger touch with a tool on draws (else it navigates). */
    private val fingerTools: Boolean get() = mode != MODE_NONE && fingerDraws && !stylusSeen
    /** The pointer the stroke follows (the pen, or the drawing finger), by id: contacts come and go around it. */
    private var inkPointerId = -1
    /** The last raw pressure of the stroke: a lift often reports 0, which must not thin the line's end. */
    private var lastRawPressure = 1f

    /** Steadied pressure per point of the stroke being drawn (when [inkPressure]). */
    private var inkQ = FloatArray(256)
    private var inkPressure = false
    private var lastQ = Float.NaN
    /**
     * The stroke being drawn, rasterized segment by segment into a view-sized layer as the points arrive: each frame
     * then costs one bitmap draw however long the stroke gets (a growing Path would be redrawn whole every frame).
     */
    private val tiles = LiveTiles()
    private var liveOn = false
    private val liveSeg = Path()
    private var lastVx = 0f
    private var lastVy = 0f
    private var midVx = 0f
    private var midVy = 0f
    private var lastHalf = 0f
    private var midHalf = 0f
    private val liveStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val liveFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    /** The pen line's tail, drawn straight on the view: the 어둡게 ink filter applies here, never in the tiles. */
    private val tailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val layerPaint = Paint()
    private val layerMultiply = Paint().also { highlightBlend(it, true) }
    /** Points the platform predicts the pen will reach next (view px), drawn ahead of the real line (Android 14+). */
    /** The MotionPredictor (Android 14+), false when the device has none, null until the first stroke. */
    internal var predictorSlot: Any? = null
    private val predicted = FloatArray(MAX_PREDICTED * 2)
    private var predictedCount = 0
    private val predictPath = Path()
    private var eraserX = Float.NaN
    private var eraserY = Float.NaN
    private val eraserPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFF757575.toInt()
        strokeWidth = context.dp(1).toFloat()
    }
    private val inkFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    /** The page on screen is bookmarked: a ribbon at its top-right corner. */
    var bookmarked = false
        set(v) {
            if (field == v) return
            field = v
            invalidate()
        }

    /** The tool of the touch in progress (MODE_NONE = not drawing). */
    private var inkGesture = MODE_NONE
    /** The last touch drew (its taps are not page taps). */
    private var inkTouched = false
    /** The touch drawing now started with a stylus: other contacts (a resting palm) are ignored until it ends. */
    private var inkStylus = false
    private var inkPage = -1
    private var inkPts = FloatArray(512)
    private var inkCount = 0
    private var inkErased = false
    /** The stroke or loop being drawn, in page points. */
    private val livePath = Path()
    private var lassoShown = false
    private val viewPath = Path()
    private val toView = Matrix()
    private val inkPaths = HashMap<InkStroke, Path>()
    private var selPage = -1
    private var selRects: List<RectF> = emptyList()
    private var findPage = -1
    private var findRects: List<RectF> = emptyList()
    private val markRect = RectF()
    private val ribbon = Path()

    private val inkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }.also { highlightBlend(it, true) }
    private val lassoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFF1E6FD9.toInt()
        strokeWidth = context.dp(2).toFloat()
        pathEffect = DashPathEffect(floatArrayOf(context.dp(6).toFloat(), context.dp(4).toFloat()), 0f)
    }
    private val selectionPaint = Paint().apply { color = 0x553D8BFF }
    private val findPaint = Paint().apply { color = 0x66FF9800 }
    private val ribbonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFD32F2F.toInt() }

    /** Highlights the selected text (page points) on [page]; an empty list clears it. */
    fun setSelectionMarks(page: Int, rects: List<RectF>) {
        selPage = if (rects.isEmpty()) -1 else page
        selRects = rects
        invalidate()
    }

    /** Marks the search matches (page points) on [page]; an empty list clears them. */
    fun setSearchMarks(page: Int, rects: List<RectF>) {
        findPage = if (rects.isEmpty()) -1 else page
        findRects = rects
        invalidate()
    }

    /** Hides the selection loop kept after [Host.onLasso]. */
    fun clearLasso() {
        if (!lassoShown) return
        lassoShown = false
        livePath.rewind()
        invalidate()
    }

    /** Annotations changed outside the view (undo, highlight from a selection): redraw them. */
    fun inkChanged() {
        inkPaths.clear()
        invalidate()
    }

    /** Called with the bitmap the view no longer draws, so the host can reuse it for the next detail render. */
    var onDetailDropped: ((Bitmap) -> Unit)? = null

    val zoomed: Boolean get() = zoom > 1.001f
    /** Screen pixels per page point at the current zoom (tool previews at true size). */
    val pxPerPoint: Float get() = scale.takeIf { it > 0f } ?: 1f
    private val areaW: Int get() = (width - paddingLeft - paddingRight).coerceAtLeast(0)
    private val areaH: Int get() = (height - paddingTop - paddingBottom).coerceAtLeast(0)
    private val scale: Float get() = PdfMath.fitScale(pageW, pageH, areaW, areaH) * zoom
    /** One page step of a slide: the page width plus the gap between pages. */
    private val slideFull: Float get() = areaW + gap

    init {
        setBackgroundColor(BACKGROUND)
        isFocusable = false
    }

    /**
     * Shows page [index] of [w]×[h] points with its fitted bitmap [bitmap]. The zoom stays; a zoomed page starts at
     * its top ([fromEnd] = false) or, when reached by going back, at its bottom; the horizontal position is kept.
     */
    fun setPage(index: Int, w: Int, h: Int, bitmap: Bitmap, fromEnd: Boolean) {
        val samePage = index == page && w == pageW && h == pageH
        page = index
        pageW = w
        pageH = h
        base = bitmap
        dropDetail()
        if (!samePage) {
            cancelInk()
            clearLasso()
            if (inkPaths.size > MAX_CACHED_PATHS) inkPaths.clear()
            stopSlide()
            scroller.forceFinished(true)
            offY = if (fromEnd) Float.NEGATIVE_INFINITY else Float.POSITIVE_INFINITY
        }
        clampOffsets()
        invalidate()
    }

    /** The fitted bitmaps of the pages before and after the current one (null = none, or not rendered yet). */
    fun setNeighbors(prev: PageImage?, next: PageImage?) {
        prevImg = prev
        nextImg = next
        if (slide != 0f) invalidate()
    }

    /** Shows nothing (the document closed): drops the page, its bitmaps and the zoom. */
    fun clear() {
        stopMotion()
        cancelInk()
        tiles.release()
        clearLasso()
        inkPaths.clear()
        selPage = -1
        findPage = -1
        bookmarked = false
        dropDetail()
        base = null
        prevImg = null
        nextImg = null
        page = -1
        pageW = 0
        pageH = 0
        zoom = 1f
        offX = 0f
        offY = 0f
        invalidate()
    }

    /** Back to the whole page (a new document, a page jump). */
    fun resetZoom() {
        zoomAnim?.cancel()
        scroller.forceFinished(true)
        zoom = 1f
        dropDetail()
        clampOffsets()
        invalidate()
    }

    /** The page area changed without a size change (padding): re-clamps the view of the page. */
    fun refit() {
        dropDetail()
        clampOffsets()
        invalidate()
    }

    /** The viewport a detail bitmap is needed for now, or null (not zoomed, moving, or already up to date). */
    fun detailNeeded(): Viewport? {
        if (!zoomed || base == null || page < 0 || areaW <= 0 || areaH <= 0) return null
        if (zoomAnim != null || !scroller.isFinished) return null
        val v = Viewport(page, scale, offX, offY, areaW, areaH)
        val d = detailFor
        return if (d != null && sameViewport(d, v)) null else v
    }

    /** A detail bitmap rendered for [v]; ignored (handed back) when the viewport moved on meanwhile. */
    fun setDetail(v: Viewport, bitmap: Bitmap) {
        val now = detailNeeded()
        if (now == null || !sameViewport(now, v)) {
            onDetailDropped?.invoke(bitmap)
            return
        }
        dropDetail()
        detail = bitmap
        detailFor = v
        invalidate()
    }

    /**
     * One reading step down ([dir] +1) or up (-1) inside a zoomed page, scrolled smoothly. False when the page is not
     * zoomed or is already at that edge (the caller turns the page instead).
     */
    fun step(dir: Int): Boolean {
        if (!zoomed || page < 0) return false
        if (!scroller.isFinished) {
            // A step during a step: jump to where the running one ends, then step on from there.
            scroller.abortAnimation()
            offX = scroller.finalX.toFloat()
            offY = if (stepTargetY.isNaN()) scroller.finalY.toFloat() else stepTargetY
            stepTargetY = Float.NaN
            clampOffsets()
        }
        val next = PdfMath.stepOffset(offY, pageH * scale, areaH.toFloat(), dir)
        if (next.isNaN()) return false
        val dy = next - offY
        if (abs(dy) < 1f) {
            offY = next
            viewportChanged()
            return true
        }
        stepTargetY = next
        scroller.startScroll(offX.toInt(), offY.toInt(), 0, dy.toInt(), PdfMath.animMs(dy, areaH.toFloat()).toInt())
        postInvalidateOnAnimation()
        return true
    }

    /**
     * Slides to the next ([dir] +1) or previous (-1) page; the host hears [Host.onPageSettled] when it is there.
     * False (nothing done) when zoomed or that page's bitmap is not ready: the host then turns without the slide.
     */
    fun animateTurn(dir: Int): Boolean {
        if (zoomed || page < 0 || dragging || areaW <= 0) return false
        val img = if (dir > 0) nextImg else prevImg
        if (img == null || img.index != page + dir) return false
        settle(dir)
        return true
    }

    /** Ends every running animation at its end state (a slide in progress lands on its page first). */
    fun finishMotion() {
        slideAnim?.end()
        zoomAnim?.end()
        if (!scroller.isFinished) {
            scroller.abortAnimation()
            offX = scroller.finalX.toFloat()
            offY = if (stepTargetY.isNaN()) scroller.finalY.toFloat() else stepTargetY
            stepTargetY = Float.NaN
            viewportChanged()
        }
    }

    private fun stopMotion() {
        stopSlide()
        zoomAnim?.cancel()
        scroller.forceFinished(true)
        stepTargetY = Float.NaN
        // A spring-back cut short must not leave the page smaller than fitted.
        if (zoom < PdfMath.MIN_ZOOM) {
            zoom = PdfMath.MIN_ZOOM
            clampOffsets()
        }
    }

    private fun stopSlide() {
        slideAnim?.cancel()
        slide = 0f
        dragging = false
    }

    private fun dropDetail() {
        val d = detail ?: return
        detail = null
        detailFor = null
        onDetailDropped?.invoke(d)
    }

    companion object {
        /**
         * Multiply blending for highlights (the text under them stays black). Never PorterDuff MULTIPLY: it multiplies
         * alpha too, so the transparent pixels around the line in a live tile cleared the page there (black squares).
         * BlendMode.MULTIPLY (Android 10+) leaves the page alone where the highlight is transparent; before it,
         * DARKEN, which composites alpha the same way and looks alike on paper. [on] false: plain source-over.
         */
        fun highlightBlend(p: Paint, on: Boolean) {
            if (Build.VERSION.SDK_INT >= 29) {
                p.blendMode = if (on) BlendMode.MULTIPLY else null
            } else {
                p.xfermode = if (on) PorterDuffXfermode(PorterDuff.Mode.DARKEN) else null
            }
        }

        const val MODE_NONE = -1
        const val MODE_PEN = InkTool.PEN
        const val MODE_HIGHLIGHTER = InkTool.HIGHLIGHTER
        const val MODE_ERASER = 2
        const val MODE_LASSO = 3

        /** c' = c + 255 − 2·luma(c): inverts lightness, keeps hue (black ↔ white, red → pink). */
        private val INK_LIGHTNESS_INVERT = floatArrayOf(
            1f - 2f * 0.299f, -2f * 0.587f, -2f * 0.114f, 0f, 255f,
            -2f * 0.299f, 1f - 2f * 0.587f, -2f * 0.114f, 0f, 255f,
            -2f * 0.299f, -2f * 0.587f, 1f - 2f * 0.114f, 0f, 255f,
            0f, 0f, 0f, 1f, 0f,
        )

        private const val TWO_UNDECIDED = 0
        private const val TWO_PINCH = 1
        private const val TWO_SWIPE = 2
        /** How far two fingers must spread or close (zoom ratio) before the touch counts as a pinch. */
        private const val PINCH_DECIDE = 0.12f

        private const val ERASER_RADIUS_DP = 10
        /** Length of one filled step of a live pressure line (px). */
        private const val LIVE_STEP_PX = 2f
        /** Predicted points drawn ahead of the pen at most. */
        const val MAX_PREDICTED = 8
        /** Cached stroke paths kept across page turns before the cache is dropped. */
        private const val MAX_CACHED_PATHS = 400

        /** Around the page: near black, like Flexcil (the page stands out; nothing to read there). */
        private const val BACKGROUND = 0xFF141414.toInt()

        /** Slowest fling (dp per second) that turns the page. */
        private const val MIN_FLING_DP_PER_S = 400f

        /** Whether a detail bitmap rendered for [a] is right for [b] (sub-pixel differences ignored). */
        fun sameViewport(a: Viewport, b: Viewport): Boolean =
            a.page == b.page && a.width == b.width && a.height == b.height &&
                abs(a.scale - b.scale) < 1e-4f && abs(a.left - b.left) < 0.5f && abs(a.top - b.top) < 0.5f
    }

    private fun clampOffsets() {
        if (pageW <= 0 || pageH <= 0) return
        val s = scale
        offX = PdfMath.clampOffset(offX, pageW * s, areaW.toFloat())
        offY = PdfMath.clampOffset(offY, pageH * s, areaH.toFloat())
    }

    private fun viewportChanged() {
        clampOffsets()
        if (detailFor != null && detailNeeded() != null) dropDetail()
        invalidate()
        host?.onViewportChanged()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        stopMotion()
        dropDetail()
        clampOffsets()
    }

    override fun onDetachedFromWindow() {
        stopMotion()
        super.onDetachedFromWindow()
    }

    // ------------------------------------------------------------------------------------------- drawing

    override fun computeScroll() {
        if (!scroller.computeScrollOffset()) return
        offX = scroller.currX.toFloat()
        offY = scroller.currY.toFloat()
        if (scroller.isFinished && !stepTargetY.isNaN()) offY = stepTargetY
        if (scroller.isFinished) stepTargetY = Float.NaN
        clampOffsets()
        if (detailFor != null) dropDetail()
        postInvalidateOnAnimation()
        // Settled: the sharp render of where it stopped.
        if (scroller.isFinished) host?.onViewportChanged()
    }

    override fun onDraw(canvas: Canvas) {
        val b = base ?: return
        if (pageW <= 0 || pageH <= 0) return
        if (slide == 0f) {
            drawCurrent(canvas, b)
            return
        }
        canvas.save()
        canvas.translate(slide, 0f)
        drawCurrent(canvas, b)
        canvas.restore()
        val n = if (slide < 0f) nextImg else prevImg
        if (n != null) drawFitted(canvas, n, slide + (if (slide < 0f) slideFull else -slideFull))
    }

    /** Whether the fitted bitmap is drawn 1:1 at a whole-pixel origin (unzoomed, matching size). */
    private fun oneToOne(b: Bitmap, s: Float): Boolean =
        !zoomed && abs(b.width - pageW * s) <= 1f && abs(b.height - pageH * s) <= 1f

    /** View x of the current page's left edge (slide excluded). */
    private fun originX(b: Bitmap, s: Float): Float =
        if (oneToOne(b, s)) (paddingLeft + (areaW - b.width) / 2).toFloat() else paddingLeft + offX

    /** View y of the current page's top edge. */
    private fun originY(b: Bitmap, s: Float): Float =
        if (oneToOne(b, s)) (paddingTop + (areaH - b.height) / 2).toFloat() else paddingTop + offY

    private fun drawCurrent(canvas: Canvas, b: Bitmap) {
        drawPage(canvas, b)
        val s = scale
        val ox = originX(b, s)
        val oy = originY(b, s)
        drawInk(canvas, page, ox, oy, s)
        if (findPage == page) drawMarks(canvas, findRects, findPaint, ox, oy, s)
        if (selPage == page) drawMarks(canvas, selRects, selectionPaint, ox, oy, s)
        if (liveOn) {
            // The highlighter layer is drawn opaque and multiplied onto the page as a whole (no darker overlaps).
            tiles.drawTo(canvas, if (inkGesture == MODE_HIGHLIGHTER) layerMultiply else layerPaint)
            if (inkGesture == MODE_PEN) drawTail(canvas)
        } else if (inkGesture == MODE_LASSO || lassoShown) {
            toView.setScale(s, s)
            toView.postTranslate(ox, oy)
            livePath.transform(toView, viewPath)
            canvas.drawPath(viewPath, lassoPaint)
        }
        if (inkGesture == MODE_ERASER && !eraserX.isNaN()) {
            canvas.drawCircle(eraserX, eraserY, context.dp(ERASER_RADIUS_DP).toFloat(), eraserPaint)
        }
        if (bookmarked) drawRibbon(canvas, ox + pageW * s, oy)
    }

    /**
     * The pen line's tail, redrawn each frame and never stored: from the last drawn midpoint to the last real point
     * (the layer stops half a segment short), then on through the predicted points (Android 14+).
     */
    private fun drawTail(canvas: Canvas) {
        predictPath.rewind()
        predictPath.moveTo(midVx, midVy)
        predictPath.lineTo(lastVx, lastVy)
        for (i in 0 until predictedCount) predictPath.lineTo(predicted[i * 2], predicted[i * 2 + 1])
        tailPaint.color = penColor
        tailPaint.strokeWidth = maxOf(1f, lastHalf * 2f)
        canvas.drawPath(predictPath, tailPaint)
    }

    private fun drawPage(canvas: Canvas, b: Bitmap) {
        val s = scale
        if (oneToOne(b, s)) {
            // The fitted bitmap 1:1 on whole pixels, centred: no resampling blur on text.
            val l = (paddingLeft + (areaW - b.width) / 2).toFloat()
            val t = (paddingTop + (areaH - b.height) / 2).toFloat()
            dst.set(l, t, l + b.width, t + b.height)
            canvas.drawRect(dst, pagePaint)
            canvas.drawBitmap(b, l, t, plainPaint)
        } else {
            val l = paddingLeft + offX
            val t = paddingTop + offY
            dst.set(l, t, l + pageW * s, t + pageH * s)
            canvas.drawRect(dst, pagePaint)
            val d = detail
            if (d != null && slide == 0f) {
                canvas.drawBitmap(d, paddingLeft.toFloat(), paddingTop.toFloat(), plainPaint)
            } else {
                canvas.drawBitmap(b, null, dst, bitmapPaint)
            }
        }
        canvas.drawRect(dst, edgePaint)
    }

    /** A neighbour page, fitted and centred in the page area shifted by [dx]. */
    private fun drawFitted(canvas: Canvas, img: PageImage, dx: Float) {
        val fit = PdfMath.fitScale(img.w, img.h, areaW, areaH)
        val w = img.w * fit
        val h = img.h * fit
        val l = paddingLeft + dx + (areaW - w) / 2f
        val t = paddingTop + (areaH - h) / 2f
        dst.set(l, t, l + w, t + h)
        canvas.drawRect(dst, pagePaint)
        canvas.drawBitmap(img.bitmap, null, dst, bitmapPaint)
        canvas.drawRect(dst, edgePaint)
        drawInk(canvas, img.index, l, t, fit)
    }

    /** The strokes of [index], page points mapped by origin ([ox], [oy]) and scale [s]. */
    private fun drawInk(canvas: Canvas, index: Int, ox: Float, oy: Float, s: Float) {
        val list = notes?.strokes(index) ?: return
        if (list.isEmpty()) return
        canvas.save()
        canvas.translate(ox, oy)
        canvas.scale(s, s)
        // Highlights under the pen lines: a highlight never tints the ink drawn over it.
        for (pass in 0..1) {
            for (i in list.indices) {
                val st = list[i]
                if ((st.tool == InkTool.HIGHLIGHTER) != (pass == 0)) continue
                val path = inkPaths.getOrPut(st) { pathOf(st) }
                if (st.pressures != null) {
                    inkFillPaint.color = st.color
                    canvas.drawPath(path, inkFillPaint)
                } else {
                    canvas.drawPath(path, strokePaint(st.tool, st.color, st.width))
                }
            }
        }
        canvas.restore()
    }

    private fun strokePaint(tool: Int, color: Int, width: Float): Paint {
        val p = if (tool == InkTool.HIGHLIGHTER) highlightPaint else inkPaint
        p.color = color
        if (tool == InkTool.HIGHLIGHTER) p.alpha = highlightAlpha
        p.strokeWidth = width
        return p
    }

    private fun drawMarks(canvas: Canvas, rects: List<RectF>, paint: Paint, ox: Float, oy: Float, s: Float) {
        for (r in rects) {
            markRect.set(ox + r.left * s, oy + r.top * s, ox + r.right * s, oy + r.bottom * s)
            canvas.drawRect(markRect, paint)
        }
    }

    /** A small ribbon hanging from the page's top edge, left of its right edge [right]. */
    private fun drawRibbon(canvas: Canvas, right: Float, top: Float) {
        val w = context.dp(12).toFloat()
        val h = context.dp(20).toFloat()
        val l = right - context.dp(12) - w
        val t = maxOf(top, paddingTop.toFloat())
        ribbon.rewind()
        ribbon.moveTo(l, t)
        ribbon.lineTo(l + w, t)
        ribbon.lineTo(l + w, t + h)
        ribbon.lineTo(l + w / 2f, t + h - w / 2f)
        ribbon.lineTo(l, t + h)
        ribbon.close()
        canvas.drawPath(ribbon, ribbonPaint)
    }

    // ------------------------------------------------------------------------------------------- page slide

    /** Animates the slide to the page in [dir] (+1 next, -1 previous) or back to rest (0). */
    private fun settle(wanted: Int) {
        slideAnim?.cancel()
        // The page it lands on, fixed now: the neighbours may be replaced while it slides.
        val img = if (wanted > 0) nextImg else if (wanted < 0) prevImg else null
        val dir = if (img != null && img.index == page + wanted) wanted else 0
        slideTurns = dir != 0
        val target = -dir * slideFull
        val anim = ValueAnimator.ofFloat(slide, target)
        anim.duration = PdfMath.animMs(target - slide, slideFull)
        anim.interpolator = DecelerateInterpolator()
        anim.addUpdateListener {
            slide = it.animatedValue as Float
            invalidate()
        }
        anim.addListener(object : AnimatorListenerAdapter() {
            private var canceled = false

            override fun onAnimationCancel(animation: Animator) {
                canceled = true
            }

            override fun onAnimationEnd(animation: Animator) {
                if (slideAnim === anim) {
                    slideAnim = null
                    slideTurns = false
                }
                if (canceled) return
                slide = 0f
                invalidate()
                // The neighbour is now exactly where the current page was: the host swaps it in.
                if (dir != 0 && img != null) host?.onPageSettled(img)
            }
        })
        slideAnim = anim
        anim.start()
    }

    /** Moves the sliding page by a finger movement of [dx] px. */
    private fun slideBy(dx: Float) {
        val hasPrev = prevImg?.index == page - 1
        val hasNext = nextImg?.index == page + 1
        slide = PdfMath.dragSlide(slide, dx, slideFull, hasPrev, hasNext)
        invalidate()
    }

    /** Whether a two-finger touch starting now may be a page swipe (else it is a pinch at once). */
    private fun twoFingerSwipe(): Boolean =
        swipeEnabled && !zoomed && areaW > 0 && page >= 0 && !dragging && slide == 0f &&
            (swipeFingers == 2 || fingerTools)

    // ------------------------------------------------------------------------------------------- zoom

    /** Zooms to [z] around the focus (content-area px) at once: pinch. */
    private fun zoomTo(z: Float, focusX: Float, focusY: Float) {
        val nz = z.coerceIn(PdfMath.PINCH_MIN_ZOOM, PdfMath.MAX_ZOOM)
        if (nz == zoom) return
        offX = PdfMath.zoomOffset(offX, focusX, zoom, nz)
        offY = PdfMath.zoomOffset(offY, focusY, zoom, nz)
        zoom = nz
        viewportChanged()
    }

    /** Animates to zoom [z], keeping the point under the focus (content-area px) in place where the edges allow. */
    private fun animateZoom(z: Float, focusX: Float, focusY: Float) {
        zoomAnim?.cancel()
        scroller.forceFinished(true)
        val z0 = zoom
        val x0 = offX
        val y0 = offY
        val z1 = PdfMath.clampZoom(z)
        // Where the page ends up: the focus-keeping offsets at the target zoom, clamped to the edges.
        val s1 = PdfMath.fitScale(pageW, pageH, areaW, areaH) * z1
        val x1 = PdfMath.clampOffset(PdfMath.zoomOffset(x0, focusX, z0, z1), pageW * s1, areaW.toFloat())
        val y1 = PdfMath.clampOffset(PdfMath.zoomOffset(y0, focusY, z0, z1), pageH * s1, areaH.toFloat())
        dropDetail()
        val anim = ValueAnimator.ofFloat(0f, 1f)
        anim.duration = PdfMath.MAX_ANIM_MS
        anim.interpolator = DecelerateInterpolator()
        anim.addUpdateListener {
            val f = it.animatedValue as Float
            zoom = z0 + (z1 - z0) * f
            offX = x0 + (x1 - x0) * f
            offY = y0 + (y1 - y0) * f
            invalidate()
        }
        anim.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (zoomAnim === anim) zoomAnim = null
                // Canceled or ended: settle on a valid view either way.
                viewportChanged()
            }
        })
        zoomAnim = anim
        anim.start()
    }

    // ------------------------------------------------------------------------------------------- touch

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(d: ScaleGestureDetector): Boolean {
            cancelInk()
            zoomAnim?.cancel()
            scroller.forceFinished(true)
            stepTargetY = Float.NaN
            lastFocusX = d.focusX
            lastFocusY = d.focusY
            twoScale = 1f
            twoDx = 0f
            twoDy = 0f
            twoIntent = if (twoFingerSwipe()) TWO_UNDECIDED else TWO_PINCH
            if (twoIntent == TWO_PINCH) {
                pinched = true
                if (dragging || slide != 0f) {
                    dragging = false
                    settle(0)
                }
            }
            return true
        }

        override fun onScale(d: ScaleGestureDetector): Boolean {
            val fx = d.focusX
            val fy = d.focusY
            val ddx = fx - lastFocusX
            val ddy = fy - lastFocusY
            lastFocusX = fx
            lastFocusY = fy
            when (twoIntent) {
                TWO_UNDECIDED -> {
                    twoScale *= d.scaleFactor
                    twoDx += ddx
                    twoDy += ddy
                    if (abs(twoScale - 1f) > PINCH_DECIDE) {
                        twoIntent = TWO_PINCH
                        pinched = true
                        zoomTo(zoom * twoScale, fx - paddingLeft, fy - paddingTop)
                    } else if (abs(twoDx) > swipeSlop && abs(twoDx) > abs(twoDy)) {
                        twoIntent = TWO_SWIPE
                        dragging = true
                        flingDir = 0
                        slideAnim?.cancel()
                        slideBy(twoDx)
                    }
                }
                TWO_SWIPE -> slideBy(ddx)
                else -> {
                    zoomTo(zoom * d.scaleFactor, fx - paddingLeft, fy - paddingTop)
                    // Two fingers also move the zoomed page.
                    if (zoomed && (ddx != 0f || ddy != 0f)) {
                        offX += ddx
                        offY += ddy
                        viewportChanged()
                    }
                }
            }
            return true
        }

        override fun onScaleEnd(d: ScaleGestureDetector) {
            // Smaller than the fitted page: spring back.
            if (twoIntent == TWO_PINCH && zoom < PdfMath.MIN_ZOOM) {
                animateZoom(PdfMath.MIN_ZOOM, d.focusX - paddingLeft, d.focusY - paddingTop)
            }
        }
    }).apply {
        isQuickScaleEnabled = false
        // A stylus with its button held draws a loop; it never zooms.
        isStylusScaleEnabled = false
    }

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        // Side taps turn pages at once (no wait for a possible double tap); only the middle, where a double tap
        // zooms, waits to know which it is.
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (fingerTools || inkTouched) return true
            if (PdfMath.tapZone(e.x, width) != 0) host?.onPageTap(e.x)
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (fingerTools || inkTouched) return true
            if (PdfMath.tapZone(e.x, width) == 0) host?.onPageTap(e.x)
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (fingerTools || inkTouched) return true
            if (PdfMath.tapZone(e.x, width) != 0) {
                // Quick taps on a side are two page turns.
                host?.onPageTap(e.x)
                return true
            }
            val target = if (zoomed) PdfMath.MIN_ZOOM else PdfMath.DOUBLE_TAP_ZOOM
            animateZoom(target, e.x - paddingLeft, e.y - paddingTop)
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (inkTouched) return false
            if (scaleDetector.isInProgress || zoomAnim != null || inkGesture != MODE_NONE) return false
            // A two-finger swipe goes on with the finger left on the glass.
            if (dragging) {
                slideBy(-dx)
                return true
            }
            // With a tool, one finger draws: moving the page takes two.
            if (fingerTools && e2.pointerCount < 2) return false
            if (zoomed) {
                offX -= dx
                offY -= dy
                viewportChanged()
                return true
            }
            if (!swipeEnabled || areaW <= 0 || fingerTools || swipeFingers != 1) return false
            // Mostly horizontal: the page starts to slide.
            if (abs(dx) <= abs(dy)) return false
            dragging = true
            flingDir = 0
            slideAnim?.cancel()
            slideBy(-dx)
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            // The end of a stroke or loop is not a fling of the page.
            if (inkTouched) return false
            if (dragging && abs(vx) >= MIN_FLING_DP_PER_S * resources.displayMetrics.density) {
                flingDir = if (vx < 0) 1 else -1
                return true
            }
            if (scaleDetector.isInProgress || zoomAnim != null || inkGesture != MODE_NONE || fingerTools) return false
            if (zoomed) {
                // Whole-pixel bounds just past the float edges; clampOffsets lands exactly on them.
                val minX = floor(PdfMath.clampOffset(Float.NEGATIVE_INFINITY, pageW * scale, areaW.toFloat())).toInt()
                val minY = floor(PdfMath.clampOffset(Float.NEGATIVE_INFINITY, pageH * scale, areaH.toFloat())).toInt()
                val maxX = ceil(PdfMath.clampOffset(Float.POSITIVE_INFINITY, pageW * scale, areaW.toFloat())).toInt()
                val maxY = ceil(PdfMath.clampOffset(Float.POSITIVE_INFINITY, pageH * scale, areaH.toFloat())).toInt()
                scroller.fling(offX.toInt(), offY.toInt(), vx.toInt(), vy.toInt(), minX, maxX, minY, maxY)
                postInvalidateOnAnimation()
                return true
            }
            return dragging
        }
    })

    // ------------------------------------------------------------------------------------------- drawing input

    /**
     * The tool a touch by pointer [index] of [e] uses: the pen's eraser end erases; a stylus draws a loop while
     * reading.
     */
    private fun inkToolFor(e: MotionEvent, index: Int = 0): Int {
        if (page < 0 || base == null || pageW <= 0) return MODE_NONE
        val type = e.getToolType(index)
        val stylus = type == MotionEvent.TOOL_TYPE_STYLUS
        val tool = when {
            type == MotionEvent.TOOL_TYPE_ERASER -> MODE_ERASER
            mode != MODE_NONE && (stylus || (fingerDraws && !stylusSeen)) -> mode
            stylus -> MODE_LASSO
            else -> MODE_NONE
        }
        // Strokes need the notes loaded; the loop does not.
        if ((tool == MODE_PEN || tool == MODE_HIGHLIGHTER || tool == MODE_ERASER) && notes == null) return MODE_NONE
        return tool
    }

    /** Starts the stroke of [inkGesture] with pointer [index] of [e] (the first contact, or a pen after a palm). */
    private fun startInk(e: MotionEvent, index: Int) {
        // Every pen sample as it comes, not batched to the next frame: the line keeps up with the pen.
        requestUnbufferedDispatch(e)
        scroller.forceFinished(true)
        zoomAnim?.end()
        clearLasso()
        inkPointerId = e.getPointerId(index)
        inkPage = page
        inkCount = 0
        inkErased = false
        livePath.rewind()
        lastQ = Float.NaN
        lastRawPressure = 1f
        inkPressure = inkGesture == MODE_PEN && penPressure && e.getToolType(index) == MotionEvent.TOOL_TYPE_STYLUS
        predictedCount = 0
        if (inkGesture == MODE_PEN || inkGesture == MODE_HIGHLIGHTER) startLive()
        if (Build.VERSION.SDK_INT >= 34 && e.pointerCount == 1) Prediction.record(this, e)
        // One eraser drag is one undo step.
        if (inkGesture == MODE_ERASER) notes?.beginGroup()
        addInkPoint(e.getX(index), e.getY(index), e.getPressure(index), first = true)
    }

    /** The samples of the stroke's pointer in [e] (batched history first), up to its current position. */
    private fun addInkSamples(e: MotionEvent) {
        val i = e.findPointerIndex(inkPointerId)
        if (i < 0) return
        for (h in 0 until e.historySize) {
            addInkPoint(e.getHistoricalX(i, h), e.getHistoricalY(i, h), e.getHistoricalPressure(i, h), first = false)
        }
        // The lift's own position counts (a quick stroke would lose its end); its pressure is often 0.
        val p = e.getPressure(i)
        val lifting = e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_POINTER_UP
        addInkPoint(e.getX(i), e.getY(i), if (lifting && p <= 0f) lastRawPressure else p, first = false)
    }

    private fun handleInk(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> startInk(e, 0)
            MotionEvent.ACTION_MOVE -> {
                if (inkPage != page) return
                addInkSamples(e)
                // The platform predicts one pointer: only while the stroke's is the only contact.
                if (liveOn && Build.VERSION.SDK_INT >= 34 && e.pointerCount == 1) {
                    Prediction.record(this, e)
                    predictedCount = Prediction.predict(this, e, predicted)
                }
            }
            MotionEvent.ACTION_UP -> {
                if (inkPage == page && e.getPointerId(0) == inkPointerId) addInkSamples(e)
                finishInk()
            }
            MotionEvent.ACTION_CANCEL -> cancelInk()
        }
    }

    /** The touch so far stops being a gesture (a pen took over from a palm): detectors and a dragged page let go. */
    private fun abandonGesture(e: MotionEvent) {
        val c = MotionEvent.obtain(e)
        c.action = MotionEvent.ACTION_CANCEL
        scaleDetector.onTouchEvent(c)
        gestures.onTouchEvent(c)
        c.recycle()
        if (dragging || slide != 0f) {
            dragging = false
            flingDir = 0
            settle(0)
        }
    }

    /** View point (x, y) with pen [pressure], appended in page points (or erased at, for the eraser). */
    private fun addInkPoint(x: Float, y: Float, pressure: Float, first: Boolean) {
        val b = base ?: return
        val s = scale
        if (s <= 0f) return
        val px = (x - slide - originX(b, s)) / s
        val py = (y - originY(b, s)) / s
        lastRawPressure = if (pressure > 0f) pressure else lastRawPressure
        if (inkGesture == MODE_ERASER) {
            eraserX = x
            eraserY = y
            val n = notes ?: return
            if (n.eraseAt(page, px, py, context.dp(ERASER_RADIUS_DP) / s)) inkErased = true
            invalidate()
            return
        }
        if (inkCount * 2 + 2 > inkPts.size) inkPts = inkPts.copyOf(inkPts.size * 2)
        if (inkCount + 1 > inkQ.size) inkQ = inkQ.copyOf(inkQ.size * 2)
        inkPts[inkCount * 2] = px
        inkPts[inkCount * 2 + 1] = py
        val q = if (inkPressure) InkShape.steady(lastQ, pressure) else 1f
        lastQ = q
        inkQ[inkCount] = q
        inkCount++
        if (liveOn) {
            val half = if (inkPressure) InkShape.widthAt(penWidth, q) * s / 2f else widthOf(inkGesture) * s / 2f
            liveSegment(x, y, half, first)
        } else {
            if (first) livePath.moveTo(px, py) else livePath.lineTo(px, py)
        }
        invalidate()
    }

    /** Clears the live tiles (kept between strokes) for a new stroke. */
    private fun startLive() {
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return
        tiles.reset(w, h)
        liveOn = true
        val color = if (inkGesture == MODE_PEN) penColor else highlighterColor
        liveStroke.color = color
        liveFill.color = color
    }

    /**
     * Draws the stroke from the last point to view point (x, y) of half-width [half] into the live layer: a
     * quadratic curve between the midpoints of the last segments (smooth, no corners at the samples), as a round
     * line for constant width or as a run of filled steps for pressure.
     */
    private fun liveSegment(x: Float, y: Float, half: Float, first: Boolean) {
        if (first) {
            lastVx = x
            lastVy = y
            midVx = x
            midVy = y
            lastHalf = half
            midHalf = half
            tiles.drawCircle(x, y, maxOf(half, 0.5f), liveFill)
            return
        }
        val mx = (lastVx + x) / 2f
        val my = (lastVy + y) / 2f
        val mh = (lastHalf + half) / 2f
        if (!inkPressure) {
            liveStroke.strokeWidth = maxOf(1f, half * 2f)
            liveSeg.rewind()
            liveSeg.moveTo(midVx, midVy)
            liveSeg.quadTo(lastVx, lastVy, mx, my)
            val pad = half + 2f
            tiles.drawPath(
                liveSeg, liveStroke,
                minOf(midVx, lastVx, mx) - pad, minOf(midVy, lastVy, my) - pad,
                maxOf(midVx, lastVx, mx) + pad, maxOf(midVy, lastVy, my) + pad,
            )
        } else {
            // Walk the curve in short steps; each step is a quad between the two widths plus a round joint.
            val len = kotlin.math.hypot(mx - midVx, my - midVy) + kotlin.math.hypot(lastVx - midVx, lastVy - midVy)
            val steps = (len / LIVE_STEP_PX).toInt().coerceIn(1, 16)
            var px = midVx
            var py = midVy
            var ph = midHalf
            for (k in 1..steps) {
                val t = k.toFloat() / steps
                val u = 1f - t
                val qx = u * u * midVx + 2f * u * t * lastVx + t * t * mx
                val qy = u * u * midVy + 2f * u * t * lastVy + t * t * my
                val qh = midHalf + (mh - midHalf) * t
                liveStep(px, py, ph, qx, qy, qh)
                px = qx
                py = qy
                ph = qh
            }
        }
        lastVx = x
        lastVy = y
        lastHalf = half
        midVx = mx
        midVy = my
        midHalf = mh
    }

    /** A filled step from (x0, y0) of half-width h0 to (x1, y1) of half-width h1, with a round end. */
    private fun liveStep(x0: Float, y0: Float, h0: Float, x1: Float, y1: Float, h1: Float) {
        val dx = x1 - x0
        val dy = y1 - y0
        val len = kotlin.math.hypot(dx, dy)
        if (len > 0.01f) {
            val nx = -dy / len
            val ny = dx / len
            liveSeg.rewind()
            liveSeg.moveTo(x0 + nx * h0, y0 + ny * h0)
            liveSeg.lineTo(x1 + nx * h1, y1 + ny * h1)
            liveSeg.lineTo(x1 - nx * h1, y1 - ny * h1)
            liveSeg.lineTo(x0 - nx * h0, y0 - ny * h0)
            liveSeg.close()
            val pad = maxOf(h0, h1) + 2f
            tiles.drawPath(liveSeg, liveFill, minOf(x0, x1) - pad, minOf(y0, y1) - pad, maxOf(x0, x1) + pad, maxOf(y0, y1) + pad)
        }
        tiles.drawCircle(x1, y1, h1, liveFill)
    }

    /** The live layer ends with its stroke (the stored stroke is drawn from the notes from now on). */
    private fun endLive() {
        liveOn = false
        predictedCount = 0
    }

    private fun finishInk() {
        val tool = inkGesture
        inkGesture = MODE_NONE
        val p = inkPage
        if (p != page || p < 0) {
            endLive()
            if (tool == MODE_ERASER) notes?.endGroup()
            livePath.rewind()
            invalidate()
            return
        }
        when (tool) {
            MODE_PEN, MODE_HIGHLIGHTER -> {
                val n = notes
                if (n != null && inkCount > 0) {
                    // Points closer than half a screen pixel add nothing.
                    val (pts, q) = InkShape.simplify(inkPts, if (inkPressure) inkQ else null, inkCount, 0.5f / scale)
                    val color = if (tool == MODE_PEN) penColor else highlighterColor
                    n.add(p, InkStroke(tool, color, widthOf(tool), pts, q))
                    host?.onInkChanged(p)
                }
                endLive()
                livePath.rewind()
            }
            MODE_ERASER -> {
                notes?.endGroup()
                eraserX = Float.NaN
                if (inkErased) {
                    inkPaths.clear()
                    host?.onInkChanged(p)
                }
            }
            MODE_LASSO -> {
                val poly = inkPts.copyOf(inkCount * 2)
                if (PdfLasso.isSelection(poly, inkCount)) {
                    livePath.close()
                    lassoShown = true
                    host?.onLasso(p, poly)
                } else {
                    livePath.rewind()
                }
            }
        }
        inkCount = 0
        invalidate()
    }

    /** Drops the stroke or loop being drawn (erasing done so far stays). */
    private fun cancelInk() {
        if (inkGesture == MODE_NONE) return
        endLive()
        eraserX = Float.NaN
        if (inkGesture == MODE_ERASER) {
            notes?.endGroup()
            if (inkErased) {
                inkPaths.clear()
                host?.onInkChanged(inkPage)
            }
        }
        inkGesture = MODE_NONE
        inkCount = 0
        if (!lassoShown) livePath.rewind()
        invalidate()
    }

    private fun widthOf(tool: Int): Float = if (tool == MODE_HIGHLIGHTER) highlighterWidth else penWidth

    /**
     * The drawable path of a stored stroke, built once: a pressure stroke is its filled outline, others the centre
     * line; both as quadratic curves through the midpoints of their segments (smooth, like the live line).
     */
    private fun pathOf(st: InkStroke): Path {
        val q = st.pressures
        if (q != null) return smoothClosed(InkShape.outline(st.points, q, st.width))
        val path = Path()
        val pts = st.points
        path.moveTo(pts[0], pts[1])
        val n = pts.size / 2
        if (n < 2) {
            // A dot: a zero-length line drawn with round caps.
            path.lineTo(pts[0] + 0.01f, pts[1])
            return path
        }
        for (i in 1 until n - 1) {
            val mx = (pts[i * 2] + pts[i * 2 + 2]) / 2f
            val my = (pts[i * 2 + 1] + pts[i * 2 + 3]) / 2f
            path.quadTo(pts[i * 2], pts[i * 2 + 1], mx, my)
        }
        path.lineTo(pts[(n - 1) * 2], pts[(n - 1) * 2 + 1])
        return path
    }

    /** A closed polygon (x, y pairs) as quadratic curves through its edge midpoints. */
    private fun smoothClosed(poly: FloatArray): Path {
        val path = Path()
        val n = poly.size / 2
        if (n < 3) return path
        path.moveTo((poly[(n - 1) * 2] + poly[0]) / 2f, (poly[(n - 1) * 2 + 1] + poly[1]) / 2f)
        for (i in 0 until n) {
            val j = (i + 1) % n
            path.quadTo(poly[i * 2], poly[i * 2 + 1], (poly[i * 2] + poly[j * 2]) / 2f, (poly[i * 2 + 1] + poly[j * 2 + 1]) / 2f)
        }
        path.close()
        return path
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            val type = event.getToolType(0)
            val pen = type == MotionEvent.TOOL_TYPE_STYLUS || type == MotionEvent.TOOL_TYPE_ERASER
            if (pen) stylusSeen = true
            // A page still sliding lands first, so the stroke goes on the page that stays.
            if (slideAnim != null && inkToolFor(event) != MODE_NONE) slideAnim?.end()
            inkGesture = inkToolFor(event)
            inkTouched = inkGesture != MODE_NONE
            inkStylus = inkTouched && pen
        } else if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN && !inkStylus) {
            // The pen comes down while a palm or finger already touches: the pen wins, the rest is ignored.
            val i = event.actionIndex
            val type = event.getToolType(i)
            if (type == MotionEvent.TOOL_TYPE_STYLUS || type == MotionEvent.TOOL_TYPE_ERASER) {
                stylusSeen = true
                val tool = inkToolFor(event, i)
                if (tool != MODE_NONE) {
                    cancelInk()
                    abandonGesture(event)
                    slideAnim?.end()
                    inkGesture = tool
                    inkTouched = true
                    inkStylus = true
                    startInk(event, i)
                    return true
                }
            }
        }
        if (inkStylus) {
            // Palm rejection: only the pen (by pointer id) counts; a palm or finger never zooms, pans or cancels.
            when (event.actionMasked) {
                MotionEvent.ACTION_POINTER_DOWN -> {}
                MotionEvent.ACTION_POINTER_UP ->
                    if (inkGesture != MODE_NONE && event.getPointerId(event.actionIndex) == inkPointerId) {
                        if (inkPage == page) addInkSamples(event)
                        finishInk()
                    }
                else -> if (inkGesture != MODE_NONE) handleInk(event)
            }
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) inkStylus = false
            return true
        }
        if (inkGesture != MODE_NONE) {
            if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
                // A second finger: zoom / move instead; the stroke so far is dropped.
                cancelInk()
            } else {
                handleInk(event)
            }
        }
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            // A touch catches a moving page: inertia stops; a sliding page is picked up where it is.
            if (!scroller.isFinished) {
                scroller.forceFinished(true)
                host?.onViewportChanged()
            }
            pinched = false
            twoIntent = TWO_PINCH
            if (slideAnim != null && !inkTouched) {
                if (slideTurns) {
                    // A page turn in flight lands first: quick taps each turn a page.
                    slideAnim?.end()
                } else {
                    // A page springing back is picked up where it is.
                    slideAnim?.cancel()
                    dragging = true
                    flingDir = 0
                }
            }
        }
        scaleDetector.onTouchEvent(event)
        gestures.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging || slide != 0f) {
                dragging = false
                val hasPrev = prevImg?.index == page - 1
                val hasNext = nextImg?.index == page + 1
                val dir = if (event.actionMasked == MotionEvent.ACTION_CANCEL || pinched) {
                    0
                } else {
                    PdfMath.settleDir(slide, slideFull, flingDir, hasPrev, hasNext)
                }
                flingDir = 0
                settle(dir)
            }
        }
        return true
    }
}

/** Pen motion prediction (Android 14+), kept apart so older systems never load MotionPredictor. */
@android.annotation.TargetApi(34)
private object Prediction {
    /** How far ahead the line is drawn: about one frame of a 60 Hz screen plus touch latency. */
    private const val AHEAD_NS = 24_000_000L

    fun record(view: PdfPageView, e: MotionEvent) {
        val p = predictorOf(view, e) ?: return
        try {
            p.record(e)
        } catch (_: IllegalArgumentException) {
        }
    }

    /** Fills [out] with predicted view points (x, y pairs); returns how many. */
    fun predict(view: PdfPageView, e: MotionEvent, out: FloatArray): Int {
        val p = predictorOf(view, e) ?: return 0
        val next = try {
            p.predict(e.eventTimeNanos + AHEAD_NS)
        } catch (_: IllegalArgumentException) {
            null
        } ?: return 0
        var n = 0
        val max = out.size / 2
        for (h in 0 until next.historySize) {
            if (n >= max) break
            out[n * 2] = next.getHistoricalX(0, h)
            out[n * 2 + 1] = next.getHistoricalY(0, h)
            n++
        }
        if (n < max) {
            out[n * 2] = next.x
            out[n * 2 + 1] = next.y
            n++
        }
        next.recycle()
        return n
    }

    private fun predictorOf(view: PdfPageView, e: MotionEvent): android.view.MotionPredictor? {
        val existing = view.predictorSlot as? android.view.MotionPredictor
        if (existing != null) return existing
        if (view.predictorSlot == false) return null
        val p = android.view.MotionPredictor(view.context)
        if (!p.isPredictionAvailable(e.deviceId, e.source)) {
            view.predictorSlot = false
            return null
        }
        view.predictorSlot = p
        return p
    }
}
