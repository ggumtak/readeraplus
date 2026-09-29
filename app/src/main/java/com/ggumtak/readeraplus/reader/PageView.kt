package com.ggumtak.readeraplus.reader

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.ggumtak.readeraplus.engine.SectionLayout
import com.ggumtak.readeraplus.render.PageDecor
import com.ggumtak.readeraplus.render.PageRenderer

/** Everything needed to draw one page; immutable so a stale frame keeps drawing consistently during relayout. */
class PageFrame(
    val renderer: PageRenderer,
    val layout: SectionLayout,
    val pageIndex: Int,
    /** Content box origin inside the view (px). */
    val left: Float,
    val top: Float,
    val decor: PageDecor,
)

/**
 * The page surface. Draws [frame] with its PageRenderer and turns raw touches into taps, swipes, long-presses
 * and left-edge brightness drags. Taps fire on ACTION_UP (no double-tap wait) with a 200 ms debounce.
 */
@SuppressLint("ViewConstructor")
class PageView(context: Context, private val cb: Callbacks) : View(context) {

    interface Callbacks {
        /** Any touch started (stops auto page turn, keeps the screen on). */
        fun onTouchStarted()
        fun isSelectionActive(): Boolean
        fun onSelectionTouch(ev: MotionEvent): Boolean
        fun onTap(x: Float, y: Float)
        fun onSwipe(dir: SwipeDir)
        /** Returns true when the long-press started a selection. */
        fun onLongPress(x: Float, y: Float): Boolean
        fun brightnessStart(): Float
        fun onBrightness(value: Float, done: Boolean)
        fun onViewSizeChanged(w: Int, h: Int)
        /** Mouse wheel / wheel-emulating page-turner remote: [next] = scrolled down. */
        fun onWheel(next: Boolean)
    }

    var frame: PageFrame? = null
    /** Colour shown before the first page is ready. */
    var blankColor: Int = Color.WHITE

    var swipeToTurn = true
    var verticalSwipe = false
    var brightnessSwipe = false
    var longPressEnabled = true

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val swipeMin = 60f * resources.displayMetrics.density
    /** A finger that strays up to this far is still a tap (larger than the long-press cancel slop). */
    private val tapSlop = maxOf(touchSlop * 2f, 20f * resources.displayMetrics.density)

    private var downX = 0f
    private var downY = 0f
    private var maxDist = 0f
    private var tracking = false
    private var moved = false
    /** The rest of the current gesture belongs to the selection controller. */
    private var toSelection = false
    /** The long-press timer ran for this gesture (whether or not it selected something): not a tap any more. */
    private var longPressFired = false
    private var brightnessMode = false
    private var brightnessDragging = false
    private var brightnessFrom = 0f
    private var lastTapAt = 0L
    private var drawFailed = false
    private val errorPaint by lazy {
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 14f * resources.displayMetrics.scaledDensityCompat() }
    }

    private val longPress = Runnable {
        if (tracking && !moved && !brightnessDragging && longPressEnabled) {
            longPressFired = true
            if (cb.onLongPress(downX, downY)) {
                // The same finger may now drag to extend the selection: hand it the rest of the gesture.
                tracking = false
                toSelection = true
            }
        }
    }

    init {
        isHapticFeedbackEnabled = false
        isSoundEffectsEnabled = false
        isFocusable = false
        defaultFocusHighlightEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        cb.onViewSizeChanged(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        val f = frame
        if (f == null) {
            canvas.drawColor(blankColor)
            return
        }
        try {
            f.renderer.draw(canvas, f.layout, f.pageIndex, f.left, f.top, width, height, f.decor)
            drawFailed = false
        } catch (t: Throwable) {
            if (!drawFailed) Log.w(TAG, "page draw failed", t)
            drawFailed = true
            canvas.drawColor(Color.WHITE)
            canvas.drawText("페이지를 그리지 못했습니다: ${t.javaClass.simpleName}", f.left, f.top + errorPaint.textSize * 2, errorPaint)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cb.onTouchStarted()
                toSelection = cb.isSelectionActive()
                if (toSelection) {
                    cb.onSelectionTouch(ev)
                    return true
                }
                tracking = true
                moved = false
                longPressFired = false
                brightnessDragging = false
                downX = ev.x
                downY = ev.y
                maxDist = 0f
                brightnessMode = brightnessSwipe && Gestures.inBrightnessStrip(ev.x, width)
                removeCallbacks(longPress)
                if (longPressEnabled) postDelayed(longPress, LONG_PRESS_MS)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (toSelection) {
                    cb.onSelectionTouch(ev)
                    return true
                }
                if (!tracking || longPressFired) return true
                val dx = ev.x - downX
                val dy = ev.y - downY
                maxDist = maxOf(maxDist, Math.abs(dx), Math.abs(dy))
                if (!moved && (Math.abs(dx) > touchSlop || Math.abs(dy) > touchSlop)) {
                    moved = true
                    removeCallbacks(longPress)
                    // The first real movement decides: a mostly vertical drag on the left strip adjusts
                    // brightness, anything else stays a page gesture (a swipe never turns into a drag later).
                    if (brightnessMode && Math.abs(dy) > Math.abs(dx)) {
                        brightnessDragging = true
                        brightnessFrom = cb.brightnessStart()
                    }
                }
                if (brightnessDragging) cb.onBrightness(Gestures.brightness(brightnessFrom, dy, height), false)
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // Multi-touch is never a page gesture.
                if (!toSelection) {
                    removeCallbacks(longPress)
                    finishBrightness(ev.y)
                    tracking = false
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (toSelection) {
                    toSelection = false
                    cb.onSelectionTouch(ev)
                    return true
                }
                removeCallbacks(longPress)
                if (!tracking) return true
                tracking = false
                if (longPressFired) return true
                if (brightnessDragging) {
                    finishBrightness(ev.y)
                    return true
                }
                val dx = ev.x - downX
                val dy = ev.y - downY
                maxDist = maxOf(maxDist, Math.abs(dx), Math.abs(dy))
                when (Gestures.end(dx, dy, maxDist, tapSlop, swipeMin, swipeToTurn, verticalSwipe)) {
                    GestureEnd.NEXT -> cb.onSwipe(SwipeDir.NEXT)
                    GestureEnd.PREV -> cb.onSwipe(SwipeDir.PREV)
                    GestureEnd.TAP -> {
                        val now = ev.eventTime
                        if (now - lastTapAt >= TAP_DEBOUNCE_MS) {
                            lastTapAt = now
                            cb.onTap(downX, downY)
                        }
                    }
                    GestureEnd.NONE -> {}
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                if (toSelection) {
                    toSelection = false
                    cb.onSelectionTouch(ev)
                    return true
                }
                removeCallbacks(longPress)
                finishBrightness(ev.y)
                tracking = false
                return true
            }
        }
        return true
    }

    override fun onGenericMotionEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_SCROLL && ev.isFromSource(InputDevice.SOURCE_CLASS_POINTER)) {
            val v = ev.getAxisValue(MotionEvent.AXIS_VSCROLL)
            if (v != 0f) {
                // One notch = one page; a fast spin fires many events, pace them like key repeat.
                if (ev.eventTime - lastWheelAt >= WHEEL_INTERVAL_MS) {
                    lastWheelAt = ev.eventTime
                    cb.onWheel(v < 0f)
                }
                return true
            }
        }
        return super.onGenericMotionEvent(ev)
    }

    private var lastWheelAt = Long.MIN_VALUE / 2

    private fun finishBrightness(y: Float) {
        if (!brightnessDragging) return
        brightnessDragging = false
        cb.onBrightness(Gestures.brightness(brightnessFrom, y - downY, height), true)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(longPress)
        super.onDetachedFromWindow()
    }

    companion object {
        private const val TAG = "PageView"
        const val LONG_PRESS_MS = 500L
        const val TAP_DEBOUNCE_MS = 200L
        const val WHEEL_INTERVAL_MS = 150L

        @Suppress("DEPRECATION")
        private fun android.util.DisplayMetrics.scaledDensityCompat(): Float = scaledDensity
    }
}
