package com.ggumtak.readeraplus.reader.pdf

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import com.ggumtak.readeraplus.ui.kit.Ink
import kotlin.math.abs

/**
 * One PDF page, fitted inside the view (minus padding), with pinch / double-tap zoom and drag panning.
 *
 * Two bitmaps are drawn: the whole page rendered at the fitted size ([setPage]; scaled up while zooming), and,
 * once a zoomed viewport has settled, a view-sized "detail" bitmap rendered at full resolution for exactly that
 * viewport ([setDetail]). The view reports taps, swipes and viewport changes to its [host] and never renders
 * anything itself. Main thread only; [onDraw] allocates nothing.
 */
internal class PdfPageView(context: Context) : View(context) {

    interface Host {
        /** A single tap at view x [x] (the host maps it to a tap zone). */
        fun onPageTap(x: Float)
        /** A horizontal fling on the unzoomed page: +1 = next page, -1 = previous. */
        fun onPageSwipe(dir: Int)
        /** Zoom or pan changed (the host asks for a new detail bitmap once it settles). */
        fun onViewportChanged()
    }

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

    /** Current page index (-1 = none yet). */
    var page = -1
        private set
    private var pageW = 0
    private var pageH = 0
    private var base: Bitmap? = null
    private var detail: Bitmap? = null
    private var detailFor: Viewport? = null

    /** Zoom over the fitted size (1 = whole page visible). Kept across page turns. */
    var zoom = 1f
        private set
    /** Page's top-left in content-area coordinates (padding excluded). */
    private var offX = 0f
    private var offY = 0f

    private val dst = RectF()
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val pagePaint = Paint().apply { color = Ink.WHITE }
    private val edgePaint = Paint().apply {
        color = Ink.LINE_LIGHT
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }

    /** Called with the bitmap the view no longer draws, so the host can reuse it for the next detail render. */
    var onDetailDropped: ((Bitmap) -> Unit)? = null

    val zoomed: Boolean get() = zoom > 1.001f
    private val areaW: Int get() = (width - paddingLeft - paddingRight).coerceAtLeast(0)
    private val areaH: Int get() = (height - paddingTop - paddingBottom).coerceAtLeast(0)
    private val scale: Float get() = PdfMath.fitScale(pageW, pageH, areaW, areaH) * zoom

    init {
        setBackgroundColor(Ink.WHITE)
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
            offY = if (fromEnd) Float.NEGATIVE_INFINITY else Float.POSITIVE_INFINITY
        }
        clampOffsets()
        invalidate()
    }

    /** Shows nothing (the document closed): drops the page, its bitmaps and the zoom. */
    fun clear() {
        dropDetail()
        base = null
        page = -1
        pageW = 0
        pageH = 0
        zoom = 1f
        offX = 0f
        offY = 0f
        invalidate()
    }

    /** Back to the whole page (a new document, or the view's size changed). */
    fun resetZoom() {
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

    /** The viewport a detail bitmap is needed for now, or null (not zoomed, nothing shown, or already up to date). */
    fun detailNeeded(): Viewport? {
        if (!zoomed || base == null || page < 0 || areaW <= 0 || areaH <= 0) return null
        val v = Viewport(page, scale, offX, offY, areaW, areaH)
        val d = detailFor
        return if (d != null && same(d, v)) null else v
    }

    /** A detail bitmap rendered for [v]; ignored (handed back) when the viewport moved on meanwhile. */
    fun setDetail(v: Viewport, bitmap: Bitmap) {
        val now = detailNeeded()
        if (now == null || !same(now, v)) {
            onDetailDropped?.invoke(bitmap)
            return
        }
        dropDetail()
        detail = bitmap
        detailFor = v
        invalidate()
    }

    /**
     * One reading step down ([dir] +1) or up (-1) inside a zoomed page. False when the page is not zoomed or is
     * already at that edge (the caller turns the page instead).
     */
    fun step(dir: Int): Boolean {
        if (!zoomed || page < 0) return false
        val next = PdfMath.stepOffset(offY, pageH * scale, areaH.toFloat(), dir)
        if (next.isNaN()) return false
        offY = next
        viewportChanged()
        return true
    }

    private fun dropDetail() {
        val d = detail ?: return
        detail = null
        detailFor = null
        onDetailDropped?.invoke(d)
    }

    private fun same(a: Viewport, b: Viewport): Boolean = sameViewport(a, b)

    companion object {
        /** Whether a detail bitmap rendered for [a] is right for [b] (sub-pixel differences ignored). */
        fun sameViewport(a: Viewport, b: Viewport): Boolean =
            a.page == b.page && a.width == b.width && a.height == b.height &&
            abs(a.scale - b.scale) < 1e-4f && abs(a.left - b.left) < 0.5f && abs(a.top - b.top) < 0.5f

        /** Slowest horizontal fling (dp per second) that turns the page. */
        const val MIN_FLING_PX_PER_S = 400f
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
        dropDetail()
        clampOffsets()
    }

    override fun onDraw(canvas: Canvas) {
        val b = base ?: return
        if (pageW <= 0 || pageH <= 0) return
        val d = detail
        val s = scale
        if (!zoomed && abs(b.width - pageW * s) <= 1f && abs(b.height - pageH * s) <= 1f) {
            // The fitted bitmap 1:1 on whole pixels, centred: no resampling blur on text.
            val l = (paddingLeft + (areaW - b.width) / 2).toFloat()
            val t = (paddingTop + (areaH - b.height) / 2).toFloat()
            dst.set(l, t, l + b.width, t + b.height)
            canvas.drawRect(dst, pagePaint)
            canvas.drawBitmap(b, l, t, null)
        } else {
            val l = paddingLeft + offX
            val t = paddingTop + offY
            dst.set(l, t, l + pageW * s, t + pageH * s)
            canvas.drawRect(dst, pagePaint)
            if (d != null) {
                canvas.drawBitmap(d, paddingLeft.toFloat(), paddingTop.toFloat(), null)
            } else {
                canvas.drawBitmap(b, null, dst, bitmapPaint)
            }
        }
        canvas.drawRect(dst, edgePaint)
    }

    // ------------------------------------------------------------------------------------------- touch

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            zoomTo(zoom * d.scaleFactor, d.focusX - paddingLeft, d.focusY - paddingTop)
            return true
        }
    }).apply { isQuickScaleEnabled = false }

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        // Side taps turn pages at once (no wait for a possible double tap); only the middle, where a double tap
        // zooms, waits to know which it is.
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (PdfMath.tapZone(e.x, width) != 0) host?.onPageTap(e.x)
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (PdfMath.tapZone(e.x, width) == 0) host?.onPageTap(e.x)
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (PdfMath.tapZone(e.x, width) != 0) {
                // Quick taps on a side are two page turns.
                host?.onPageTap(e.x)
                return true
            }
            val target = if (zoomed) PdfMath.MIN_ZOOM else PdfMath.DOUBLE_TAP_ZOOM
            zoomTo(target, e.x - paddingLeft, e.y - paddingTop)
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (!zoomed || scaleDetector.isInProgress) return false
            offX -= dx
            offY -= dy
            viewportChanged()
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (zoomed || scaleDetector.isInProgress) return false
            if (abs(vx) < abs(vy) * 1.5f || abs(vx) < MIN_FLING_PX_PER_S * resources.displayMetrics.density) return false
            host?.onPageSwipe(if (vx < 0) 1 else -1)
            return true
        }
    })

    private fun zoomTo(z: Float, focusX: Float, focusY: Float) {
        val nz = PdfMath.clampZoom(z)
        if (nz == zoom) return
        offX = PdfMath.zoomOffset(offX, focusX, zoom, nz)
        offY = PdfMath.zoomOffset(offY, focusY, zoom, nz)
        zoom = nz
        viewportChanged()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestures.onTouchEvent(event)
        return true
    }
}
