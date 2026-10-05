package com.ggumtak.readeraplus.reader

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.VelocityTracker
import android.view.accessibility.AccessibilityNodeInfo
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
 * and left-edge brightness drags. Taps fire on ACTION_UP (no double-tap wait) and are never throttled: fast tapping,
 * also with two fingers in turn, delivers every tap ([TapDedup] only drops a duplicate report of one touch).
 */
@SuppressLint("ViewConstructor")
class PageView(context: Context, private val cb: Callbacks) : View(context) {

    /** A finger that went down while another one was already on the page. */
    private class ExtraTap(val id: Int, val x: Float, val y: Float, val downAt: Long) {
        var maxDist = 0f
    }

    /** Non-null only while the reader displays a scroll viewport. */
    var scroll: ScrollInput? = null
    interface ScrollInput {
        val live: Boolean
        /** A drag starts at the platform touch slop (else at the wider tap slop: e-ink taps are often a little sloppy). */
        val fineDrag: Boolean get() = live
        /** Apply a pending device/style choice only at the beginning of a new gesture. */
        fun onDown() {}
        /** A drag started: the finger is down until [release] / [cancelDrag] (also in STEP, where nothing moves). */
        fun beginDrag() {}
        fun isMoving(): Boolean; fun stopMotion(): Boolean; fun dragBy(dy: Float)
        fun release(totalDy: Float, velocityY: Float); fun cancelDrag()
        fun a11yStep(next: Boolean): Boolean; fun computeScroll()
        /** False when nothing of the body was painted (no renderer or geometry yet): not a first frame. */
        fun draw(canvas: Canvas, width: Int, height: Int): Boolean
    }

    interface Callbacks {
        fun onScrollStart() {}
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

    /** The next page replaces the frame at once: no fade, slide, curl or timed interpolation. */
    var frame: PageFrame? = null
    /** Colour shown before the first page is ready. */
    var blankColor: Int = Color.WHITE

    var swipeToTurn = true
    var verticalSwipe = false
    var brightnessSwipe = false
    var longPressEnabled = true
    /** How long a still finger makes a long-press (AppSettings.longPressMs, set by the reader). */
    var longPressMs = LONG_PRESS_MS

    /** Event time (uptime ms) of the last tap, swipe or wheel notch delivered to [cb]: where a turn's time starts. */
    var lastInputAt = 0L
        private set
    /** Down time of the touch behind [lastInputAt] (0 = none: wheel, accessibility) and what it was ([PerfLines]). */
    var lastInputDownAt = 0L
        private set
    var lastInputKind = PerfLines.INPUT_NONE
        private set
    /** Open being timed: book id and start (uptime ms) until the next draw ends (0 = none). See [ReaderPerf]. */
    private var openTraceId = 0L
    private var openTraceFrom = 0L
    /** Turn being timed: its input event time until the next draw ends (0 = none). See [ReaderPerf]. */
    private var turnTraceFrom = 0L
    /** That turn's down time, event wait (ms, -1 = unknown) and input kind for its "turn #n" line ([traceTurn]). */
    private var turnTraceDown = 0L
    private var turnTraceWait = -1L
    private var turnTraceKind = PerfLines.INPUT_NONE
    /** Turns logged so far: the n of "turn #n" and "frame #n". */
    private var turnSeq = 0
    private var perfText: StringBuilder? = null
    /** RAPerf DEBUG only: traced frames waiting for their FrameMetrics ([FrameWatch]); null otherwise. */
    internal var frameTrace: FrameTrace? = null

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val swipeMin = 60f * resources.displayMetrics.density
    /** A finger that strays up to this far is still a tap (larger than the long-press cancel slop). */
    private val tapSlop = maxOf(touchSlop * 2f, 20f * resources.displayMetrics.density)

    private var maxFling = 0f
    private var velocityTracker: VelocityTracker? = null
    private var scrollDragging = false
    private var scrollStopper = false
    private var scrollLastY = 0f

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
    /** Pointer id of the gesture's first finger (the one that can swipe, long-press or drag brightness). */
    private var primaryId = 0
    private var primaryDownAt = 0L
    /** Another finger touched during this gesture: the first finger can only end as a tap now. */
    private var multi = false
    /** A second finger landed during a drag: the rest of the gesture is ignored. */
    private var multiIgnored = false
    private val extraTaps = ArrayList<ExtraTap>(MAX_EXTRA_FINGERS)
    private val tapDedup = TapDedup()
    private var drawFailed = false
    private val errorPaint by lazy {
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 14f * resources.displayMetrics.scaledDensityCompat() }
    }

    private val longPress = Runnable {
        if (tracking && !moved && !multi && !brightnessDragging && longPressEnabled) {
            longPressFired = true
            if (cb.onLongPress(downX, downY)) {
                // The same finger may now drag to extend the selection: hand it the rest of the gesture.
                tracking = false
                toSelection = true
            }
        }
    }

    init {
        stateListAnimator = null
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

    var afterFirstFrame: Runnable? = null

    private fun finishFirstFrame() {
        if (drawFailed) return
        val task = afterFirstFrame ?: return
        afterFirstFrame = null
        post(task)
    }

    override fun onDraw(canvas: Canvas) {
        // RAPerf DEBUG: the draw of a traced open or turn is timed too (one static read on such a frame only).
        val drawFrom = if ((openTraceFrom != 0L || turnTraceFrom != 0L) && ReaderPerf.turns) System.nanoTime() else 0L
        val scrolling = scroll
        if (scrolling != null) {
            val painted = try {
                scrolling.draw(canvas, width, height).also { drawFailed = false }
            } catch (t: Throwable) {
                if (!drawFailed) Log.w(TAG, "scroll draw failed", t)
                drawFailed = true
                canvas.drawColor(Color.WHITE)
                canvas.drawText("페이지를 그리지 못했습니다", 12f * resources.displayMetrics.density,
                    errorPaint.textSize * 2f, errorPaint)
                false
            }
            if (painted) finishFirstFrame()
            if (openTraceFrom != 0L || turnTraceFrom != 0L) logTraces(drawFrom)
            return
        }
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
        finishFirstFrame()
        if (openTraceFrom != 0L || turnTraceFrom != 0L) logTraces(drawFrom)
    }

    /** The first page of a book is set: log how long the open took once it has been drawn ([ReaderPerf]). */
    fun traceOpen(bookId: Long, startedAt: Long) {
        openTraceId = bookId
        openTraceFrom = startedAt
    }

    /**
     * A turned page is set: log the time from [inputAt] (its input event) once it has been drawn ([ReaderPerf]), with
     * the touch's or key's [downAt] (0 = none), how long the event waited before the reader took it ([waitMs]) and its
     * [kind] ([PerfLines]). The reader calls it only with RAPerf DEBUG on.
     */
    fun traceTurn(inputAt: Long, downAt: Long = 0L, waitMs: Long = -1L, kind: Int = PerfLines.INPUT_NONE) {
        turnTraceFrom = inputAt
        turnTraceDown = downAt
        turnTraceWait = waitMs
        turnTraceKind = kind
    }

    /** [drawFrom]: System.nanoTime when this onDraw began (RAPerf DEBUG), else 0. */
    private fun logTraces(drawFrom: Long) {
        val now = SystemClock.uptimeMillis()
        val drawNs = if (drawFrom != 0L) System.nanoTime() - drawFrom else -1L
        if (openTraceFrom != 0L) {
            // Timed here, written after this frame (once per open): the first page is not kept waiting for the log.
            val id = openTraceId
            val from = openTraceFrom
            val ms = now - from
            openTraceFrom = 0L
            if (drawNs >= 0L) frameTrace?.expect(drawingTime, 0, PerfLines.INPUT_NONE, from, 0L)
            post {
                if (drawNs >= 0L) Log.d(ReaderPerf.TAG, PerfLines.openDrawLine(StringBuilder(40), id, drawNs).toString())
                Log.i(ReaderPerf.TAG, "open $id: first page $ms ms")
            }
        }
        if (turnTraceFrom != 0L) {
            Log.d(ReaderPerf.TAG, "turn ${now - turnTraceFrom} ms")
            // The same turn in detail ([traceTurn] is only called with RAPerf DEBUG); "frame #n" comes from FrameWatch.
            val n = ++turnSeq
            val sb = perfText ?: StringBuilder(160).also { perfText = it }
            sb.setLength(0)
            PerfLines.turnLine(sb, n, turnTraceKind, turnTraceFrom, turnTraceDown, turnTraceWait, now, drawNs)
            Log.d(ReaderPerf.TAG, sb.toString())
            frameTrace?.expect(drawingTime, n, turnTraceKind, turnTraceFrom, turnTraceDown)
            turnTraceFrom = 0L
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val scrolling = scroll
        if (scrolling != null) return onScrollTouch(ev, scrolling)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cb.onTouchStarted()
                extraTaps.clear()
                multiIgnored = false
                toSelection = cb.isSelectionActive()
                if (toSelection) {
                    cb.onSelectionTouch(ev)
                    return true
                }
                tracking = true
                moved = false
                multi = false
                longPressFired = false
                brightnessDragging = false
                primaryId = ev.getPointerId(0)
                primaryDownAt = ev.eventTime
                downX = ev.x
                downY = ev.y
                maxDist = 0f
                brightnessMode = brightnessSwipe && Gestures.inBrightnessStrip(ev.x, width)
                removeCallbacks(longPress)
                if (longPressEnabled) postDelayed(longPress, longPressMs)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (toSelection) {
                    cb.onSelectionTouch(ev)
                    return true
                }
                trackExtraTaps(ev)
                if (!tracking || longPressFired) return true
                val i = ev.findPointerIndex(primaryId)
                if (i < 0) return true
                val dx = ev.getX(i) - downX
                val dy = ev.getY(i) - downY
                maxDist = maxOf(maxDist, Math.abs(dx), Math.abs(dy))
                if (!moved && (Math.abs(dx) > touchSlop || Math.abs(dy) > touchSlop)) {
                    moved = true
                    removeCallbacks(longPress)
                    // The first real movement decides: a mostly vertical drag on the left strip adjusts
                    // brightness, anything else stays a page gesture (a swipe never turns into a drag later).
                    if (brightnessMode && !multi && Math.abs(dy) > Math.abs(dx)) {
                        brightnessDragging = true
                        brightnessFrom = cb.brightnessStart()
                    }
                }
                if (brightnessDragging) cb.onBrightness(Gestures.brightness(brightnessFrom, dy, height), false)
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (toSelection || multiIgnored) return true
                removeCallbacks(longPress)
                if (tracking && (moved || brightnessDragging || longPressFired)) {
                    // A second finger during a drag: multi-touch is never a page gesture.
                    finishBrightness(ev)
                    tracking = false
                    multiIgnored = true
                    extraTaps.clear()
                    return true
                }
                // Fast drumming with two fingers: the first finger may still end as a tap (never a swipe or a
                // long-press), and the new one is a tap candidate of its own.
                multi = true
                val i = ev.actionIndex
                if (extraTaps.size < MAX_EXTRA_FINGERS) {
                    extraTaps += ExtraTap(ev.getPointerId(i), ev.getX(i), ev.getY(i), ev.eventTime)
                }
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (toSelection || multiIgnored) return true
                val i = ev.actionIndex
                val id = ev.getPointerId(i)
                if (tracking && id == primaryId) {
                    finishPrimary(ev, i)
                } else {
                    finishExtraTap(ev, i, id)
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
                if (multiIgnored) {
                    multiIgnored = false
                    return true
                }
                val i = ev.actionIndex
                val id = ev.getPointerId(i)
                if (tracking && id == primaryId) finishPrimary(ev, i) else finishExtraTap(ev, i, id)
                tracking = false
                extraTaps.clear()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                if (toSelection) {
                    toSelection = false
                    cb.onSelectionTouch(ev)
                    return true
                }
                removeCallbacks(longPress)
                finishBrightness(ev)
                tracking = false
                multiIgnored = false
                extraTaps.clear()
                return true
            }
        }
        return true
    }

    /** Scroll gestures share the existing selection/brightness callbacks; paged gesture handling stays separate. */
    private fun onScrollTouch(ev: MotionEvent, input: ScrollInput): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            cb.onTouchStarted()
            removeCallbacks(longPress)
            extraTaps.clear()
            multiIgnored = false
            toSelection = cb.isSelectionActive()
            if (toSelection) { cb.onSelectionTouch(ev); return true }
            scrollStopper = input.stopMotion()
            input.onDown()
            if (maxFling <= 0f) maxFling = ViewConfiguration.get(context).scaledMaximumFlingVelocity.toFloat()
            val tracker = velocityTracker ?: VelocityTracker.obtain().also { velocityTracker = it }
            tracker.clear(); tracker.addMovement(ev)
            tracking = true; moved = false; multi = false; longPressFired = false
            scrollDragging = false; brightnessDragging = false
            primaryId = ev.getPointerId(0); primaryDownAt = ev.eventTime
            downX = ev.x; downY = ev.y; scrollLastY = downY; maxDist = 0f
            brightnessMode = brightnessSwipe && Gestures.inBrightnessStrip(ev.x, width)
            if (!scrollStopper && longPressEnabled) postDelayed(longPress, longPressMs)
            return true
        }
        if (toSelection) {
            cb.onSelectionTouch(ev)
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) toSelection = false
            return true
        }
        velocityTracker?.addMovement(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if (!tracking || multiIgnored || longPressFired) return true
                val index = ev.findPointerIndex(primaryId)
                if (index < 0) return true
                val x = ev.getX(index); val y = ev.getY(index)
                val dx = x - downX; val dy = y - downY
                maxDist = maxOf(maxDist, Math.abs(dx), Math.abs(dy))
                val slop = if (input.fineDrag) touchSlop else tapSlop
                if (!moved && maxDist > slop) {
                    moved = true
                    removeCallbacks(longPress)
                    if (ScrollMath.isVertical(dx, dy)) {
                        if (brightnessMode) { brightnessDragging = true; brightnessFrom = cb.brightnessStart() }
                        else { scrollDragging = true; input.beginDrag(); cb.onScrollStart() }
                    }
                    scrollLastY = y
                    if (brightnessDragging) cb.onBrightness(Gestures.brightness(brightnessFrom, dy, height), false)
                    return true
                }
                if (brightnessDragging) cb.onBrightness(Gestures.brightness(brightnessFrom, dy, height), false)
                else if (scrollDragging && input.live) input.dragBy(scrollLastY - y)
                scrollLastY = y
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                removeCallbacks(longPress)
                if (scrollDragging) input.cancelDrag()
                finishBrightness(ev)
                scrollDragging = false; tracking = false; multiIgnored = true
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                if (multiIgnored) { multiIgnored = false; tracking = false; return true }
                if (!tracking) return true
                tracking = false
                val index = ev.findPointerIndex(primaryId)
                val x = if (index >= 0) ev.getX(index) else ev.x
                val y = if (index >= 0) ev.getY(index) else ev.y
                if (brightnessDragging) finishBrightness(ev)
                else if (scrollDragging) {
                    scrollDragging = false
                    if (input.live) input.dragBy(scrollLastY - y)
                    val tracker = velocityTracker
                    tracker?.computeCurrentVelocity(1000, maxFling)
                    noteInput(PerfLines.INPUT_SWIPE, primaryDownAt, ev.eventTime)
                    input.release(downY - y, -(tracker?.getYVelocity(primaryId) ?: 0f))
                } else if (!scrollStopper && !longPressFired) {
                    val dx = x - downX; val dy = y - downY
                    maxDist = maxOf(maxDist, Math.abs(dx), Math.abs(dy))
                    when (Gestures.end(dx, dy, maxDist, tapSlop, swipeMin, swipeToTurn, false)) {
                        GestureEnd.NEXT -> swipe(SwipeDir.NEXT, primaryDownAt, ev.eventTime)
                        GestureEnd.PREV -> swipe(SwipeDir.PREV, primaryDownAt, ev.eventTime)
                        GestureEnd.TAP -> deliverTap(downX, downY, primaryDownAt, ev.eventTime)
                        GestureEnd.NONE -> {}
                    }
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                if (scrollDragging) input.cancelDrag()
                finishBrightness(ev)
                scrollDragging = false; tracking = false; multiIgnored = false
            }
        }
        return true
    }

    override fun computeScroll() { scroll?.computeScroll() }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        if (scroll != null) {
            info.isScrollable = true
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
        }
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        val input = scroll
        if (input != null) {
            if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) return input.a11yStep(true)
            if (action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) return input.a11yStep(false)
        }
        return super.performAccessibilityAction(action, arguments)
    }

    /** Use the same reader command as a tap/remote, including manual-turn and auto-turn bookkeeping. */
    internal fun accessibilityStep(next: Boolean): Boolean {
        noteInput(PerfLines.INPUT_NONE, 0L, SystemClock.uptimeMillis())
        cb.onWheel(next)
        return true
    }

    /** The first finger of the gesture lifted (pointer index [i] of [ev]): tap, swipe or the end of a drag. */
    private fun finishPrimary(ev: MotionEvent, i: Int) {
        removeCallbacks(longPress)
        if (!tracking) return
        tracking = false
        if (longPressFired) return
        if (brightnessDragging) {
            finishBrightness(ev)
            return
        }
        val dx = ev.getX(i) - downX
        val dy = ev.getY(i) - downY
        maxDist = maxOf(maxDist, Math.abs(dx), Math.abs(dy))
        if (multi) {
            // Another finger touched meanwhile: only a short, still touch counts (a tap), never a swipe.
            if (maxDist <= tapSlop && ev.eventTime - primaryDownAt < longPressMs) {
                deliverTap(downX, downY, primaryDownAt, ev.eventTime)
            }
            return
        }
        when (Gestures.end(dx, dy, maxDist, tapSlop, swipeMin, swipeToTurn, verticalSwipe)) {
            GestureEnd.NEXT -> swipe(SwipeDir.NEXT, primaryDownAt, ev.eventTime)
            GestureEnd.PREV -> swipe(SwipeDir.PREV, primaryDownAt, ev.eventTime)
            GestureEnd.TAP -> deliverTap(downX, downY, primaryDownAt, ev.eventTime)
            GestureEnd.NONE -> {}
        }
    }

    /** A finger that went down while another was on the page lifted: a tap when it was short and still. */
    private fun finishExtraTap(ev: MotionEvent, i: Int, id: Int) {
        val k = extraTaps.indexOfFirst { it.id == id }
        if (k < 0) return
        val t = extraTaps.removeAt(k)
        val d = maxOf(t.maxDist, Math.abs(ev.getX(i) - t.x), Math.abs(ev.getY(i) - t.y))
        if (d <= tapSlop && ev.eventTime - t.downAt < longPressMs) deliverTap(t.x, t.y, t.downAt, ev.eventTime)
    }

    private fun trackExtraTaps(ev: MotionEvent) {
        for (t in extraTaps) {
            val i = ev.findPointerIndex(t.id)
            if (i < 0) continue
            t.maxDist = maxOf(t.maxDist, Math.abs(ev.getX(i) - t.x), Math.abs(ev.getY(i) - t.y))
        }
    }

    private fun swipe(dir: SwipeDir, downTime: Long, upTime: Long) {
        noteInput(PerfLines.INPUT_SWIPE, downTime, upTime)
        cb.onSwipe(dir)
    }

    /** Every tap reaches the reader, however fast; only a duplicate report of the same touch is dropped. */
    private fun deliverTap(x: Float, y: Float, downTime: Long, upTime: Long) {
        if (!tapDedup.accept(x, y, upTime, tapSlop)) return
        noteInput(PerfLines.INPUT_TAP, downTime, upTime)
        cb.onTap(x, y)
    }

    /** The input about to reach [cb] (a turn's time starts at [at]; [downTime] = its finger's, 0 = none). */
    private fun noteInput(kind: Int, downTime: Long, at: Long) {
        lastInputAt = at
        lastInputDownAt = downTime
        lastInputKind = kind
    }

    override fun onGenericMotionEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_SCROLL && ev.isFromSource(InputDevice.SOURCE_CLASS_POINTER)) {
            val v = ev.getAxisValue(MotionEvent.AXIS_VSCROLL)
            if (v != 0f) {
                // One notch = one page; only a free-spinning wheel's burst (events < 60 ms apart) is thinned out, so
                // a wheel-emulating page-turner remote keeps up with fast clicks.
                if (ev.eventTime - lastWheelAt >= WHEEL_INTERVAL_MS) {
                    lastWheelAt = ev.eventTime
                    noteInput(PerfLines.INPUT_WHEEL, 0L, ev.eventTime)
                    cb.onWheel(v < 0f)
                }
                return true
            }
        }
        return super.onGenericMotionEvent(ev)
    }

    private var lastWheelAt = Long.MIN_VALUE / 2

    private fun finishBrightness(ev: MotionEvent) {
        if (!brightnessDragging) return
        brightnessDragging = false
        val i = ev.findPointerIndex(primaryId)
        val y = if (i >= 0) ev.getY(i) else ev.y
        cb.onBrightness(Gestures.brightness(brightnessFrom, y - downY, height), true)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(longPress)
        if (scrollDragging) scroll?.cancelDrag()
        scrollDragging = false; tracking = false
        velocityTracker?.recycle()
        velocityTracker = null
        super.onDetachedFromWindow()
    }

    companion object {
        private const val TAG = "PageView"
        /** The default long-press time ([longPressMs] follows AppSettings.longPressMs). */
        const val LONG_PRESS_MS = 500L
        /** Wheel notches closer than this are one burst of a free-spinning wheel (a remote's clicks are slower). */
        const val WHEEL_INTERVAL_MS = 60L
        private const val MAX_EXTRA_FINGERS = 4

        @Suppress("DEPRECATION")
        private fun android.util.DisplayMetrics.scaledDensityCompat(): Float = scaledDensity
    }
}

/**
 * Timing logs for comparing builds on the device (`adb logcat -s RAPerf`): "open <id>: first page N ms" (from
 * startOpen to the end of the first page's draw) always, at INFO; "turn N ms" (from the input event to the end of the
 * turned page's draw) only when `adb shell setprop log.tag.RAPerf DEBUG` was set before the app started, so a normal
 * turn pays one static read and allocates nothing. The same switch adds the Comet measuring lines of [PerfLines] (the
 * finger's contact time, a key's system hold, the turn frame's FrameMetrics via [FrameWatch], the open's steps).
 */
internal object ReaderPerf {
    const val TAG = "RAPerf"

    /** Read once, on the first user turn. */
    @JvmField
    val turns: Boolean = try {
        Log.isLoggable(TAG, Log.DEBUG)
    } catch (t: Throwable) {
        false
    }
}
