package com.ggumtak.readeraplus.reader.pdf

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
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
    private val gap = context.dp(16).toFloat()

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
        /** Around the page: light grey, so the page's white edge shows. */
        private const val BACKGROUND = 0xFFE6E6E6.toInt()

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

    private fun drawCurrent(canvas: Canvas, b: Bitmap) {
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
            val d = detail
            if (d != null && slide == 0f) {
                canvas.drawBitmap(d, paddingLeft.toFloat(), paddingTop.toFloat(), null)
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
            pinched = true
            zoomAnim?.cancel()
            scroller.forceFinished(true)
            stepTargetY = Float.NaN
            if (dragging || slide != 0f) {
                dragging = false
                settle(0)
            }
            return true
        }

        override fun onScale(d: ScaleGestureDetector): Boolean {
            zoomTo(zoom * d.scaleFactor, d.focusX - paddingLeft, d.focusY - paddingTop)
            return true
        }

        override fun onScaleEnd(d: ScaleGestureDetector) {
            // Smaller than the fitted page: spring back.
            if (zoom < PdfMath.MIN_ZOOM) animateZoom(PdfMath.MIN_ZOOM, d.focusX - paddingLeft, d.focusY - paddingTop)
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
            animateZoom(target, e.x - paddingLeft, e.y - paddingTop)
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (scaleDetector.isInProgress || zoomAnim != null) return false
            if (zoomed) {
                offX -= dx
                offY -= dy
                viewportChanged()
                return true
            }
            if (!swipeEnabled || areaW <= 0) return false
            if (!dragging) {
                // Mostly horizontal: the page starts to slide.
                if (abs(dx) <= abs(dy)) return false
                dragging = true
                flingDir = 0
                slideAnim?.cancel()
            }
            val hasPrev = prevImg?.index == page - 1
            val hasNext = nextImg?.index == page + 1
            slide = PdfMath.dragSlide(slide, -dx, slideFull, hasPrev, hasNext)
            invalidate()
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (scaleDetector.isInProgress || zoomAnim != null) return false
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
            if (dragging && abs(vx) >= MIN_FLING_DP_PER_S * resources.displayMetrics.density) {
                flingDir = if (vx < 0) 1 else -1
            }
            return dragging
        }
    })

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            // A touch catches a moving page: inertia stops; a sliding page is picked up where it is.
            if (!scroller.isFinished) {
                scroller.forceFinished(true)
                host?.onViewportChanged()
            }
            pinched = false
            if (slideAnim != null) {
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
