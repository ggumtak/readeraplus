package com.ggumtak.readeraplus.ui.kit

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.AbsListView
import android.widget.GridView
import android.widget.ImageButton
import android.widget.ListView
import kotlin.math.abs

/** Pure (JVM-tested): what x an always-visible right-side platform fast scroller may see for an ACTION_DOWN. */
object FastScrollGuard {
    /** The strip the scroller keeps: its ≤ 8 dp thumb/track plus slack; equals the library list's 12 dp end gutter. */
    const val GRAB_DP = 12f
    /** The platform grab zone (48 dp minimum touch target) + 8 dp for OEM variation. */
    const val ZONE_DP = 56f
    /**
     * Unchanged outside the zone and on the strip; inside the zone but left of the strip → just left of the zone.
     * [Δ] [insetEndPx] = the scroller container's right inset (paddingEnd for INSIDE_* scrollbar styles, else 0): the
     * platform zone is then `x ≥ width − inset − 48 dp`, and without it the 8 dp OEM slack is used up by the padding.
     */
    fun shieldedX(x: Float, width: Int, density: Float, insetEndPx: Int = 0): Float {
        if (width <= 0) return x
        val grabLeft = width - insetEndPx - GRAB_DP * density
        val zoneLeft = width - insetEndPx - ZONE_DP * density
        return if (x >= grabLeft || x < zoneLeft) x else zoneLeft - 1f
    }
}

/** Pure: the reader's tap tolerance (PageView.tapSlop) for list buttons. */
object TapSlop {
    fun px(touchSlop: Int, density: Float): Float = maxOf(touchSlop * 2f, 20f * density)
    /** A press that began on a card button becomes the list's gesture: mostly vertical and beyond [slop]. */
    fun releaseToList(dx: Float, dy: Float, slop: Float): Boolean = abs(dy) > slop && abs(dy) >= abs(dx)
}

/**
 * ListView for e-ink whose rows may hold buttons. Same defaults as `einkListView()`. When the platform fast scroller
 * is on, it only gets touches that start on its own strip ([FastScrollGuard]); the rows (and their ⋮) get the rest.
 */
open class InkListView(context: Context) : ListView(context) {
    init {
        divider = null; dividerHeight = 0; overScrollMode = OVER_SCROLL_NEVER; isVerticalFadingEdgeEnabled = false
        selector = ColorDrawable(Color.TRANSPARENT); isScrollbarFadingEnabled = false; cacheColorHint = Color.TRANSPARENT
    }
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = guardDown(ev) { super.onInterceptTouchEvent(it) }
    override fun onTouchEvent(ev: MotionEvent): Boolean = guardDown(ev) { super.onTouchEvent(it) }
}
/** GridView twin of [InkListView] (same guard). */
open class InkGridView(context: Context) : GridView(context) {
    init {
        overScrollMode = OVER_SCROLL_NEVER; isVerticalFadingEdgeEnabled = false
        selector = ColorDrawable(Color.TRANSPARENT); isScrollbarFadingEnabled = false
    }
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = guardDown(ev) { super.onInterceptTouchEvent(it) }
    override fun onTouchEvent(ev: MotionEvent): Boolean = guardDown(ev) { super.onTouchEvent(it) }
}

internal inline fun AbsListView.guardDown(ev: MotionEvent, sup: (MotionEvent) -> Boolean): Boolean {
    // [Δ] Qualified: an extension function does not see the receiver's static Java members (compile error otherwise).
    if (!isFastScrollEnabled || ev.actionMasked != MotionEvent.ACTION_DOWN || layoutDirection == View.LAYOUT_DIRECTION_RTL) {
        return sup(ev)
    }
    // [Δ] The platform scroller's container excludes the padding for INSIDE_* scrollbar styles (FastScroller
    // .updateContainerRect), which moves its grab zone left by paddingEnd.
    val inset = if (scrollBarStyle == View.SCROLLBARS_INSIDE_OVERLAY || scrollBarStyle == View.SCROLLBARS_INSIDE_INSET) paddingEnd else 0
    val x = FastScrollGuard.shieldedX(ev.x, width, resources.displayMetrics.density, inset)
    if (x == ev.x) return sup(ev)
    val copy = MotionEvent.obtain(ev).apply { setLocation(x, ev.y) }
    try { return sup(copy) } finally { copy.recycle() }
}

/** Card action button: a press that starts on it stays a tap until the finger moves [TapSlop] away; no long-press toast. */
class CardButton(context: Context, iconRes: Int, description: String, onClick: (View) -> Unit) : ImageButton(context) {
    private val slop = TapSlop.px(ViewConfiguration.get(context).scaledTouchSlop, resources.displayMetrics.density)
    private var downX = 0f; private var downY = 0f; private var guarding = false
    init {
        setImageResource(iconRes); contentDescription = description
        imageTintList = ColorStateList.valueOf(Ink.BLACK); background = pressableBackground()
        scaleType = ScaleType.CENTER; isFocusable = false; isLongClickable = false
        setOnClickListener(onClick)
    }
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; guarding = true; parent?.requestDisallowInterceptTouchEvent(true) }
            MotionEvent.ACTION_MOVE -> if (guarding && TapSlop.releaseToList(e.x - downX, e.y - downY, slop)) {
                guarding = false; parent?.requestDisallowInterceptTouchEvent(false)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> guarding = false
        }
        return super.onTouchEvent(e)
    }
}
