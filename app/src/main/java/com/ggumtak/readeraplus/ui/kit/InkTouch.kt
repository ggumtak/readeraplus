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
    var pager: ListPager? = null
    private val pageDrag = PageDrag(TapSlop.px(ViewConfiguration.get(context).scaledTouchSlop, resources.displayMetrics.density))
    var paged = false
        set(value) { if (field == value) return; field=value; pageDrag.cancel(); isFastScrollEnabled=!value; isFastScrollAlwaysVisible=!value }
    init {
        divider = null; dividerHeight = 0; overScrollMode = OVER_SCROLL_NEVER; isVerticalFadingEdgeEnabled = false
        selector = ColorDrawable(Color.TRANSPARENT); isScrollbarFadingEnabled = false; cacheColorHint = Color.TRANSPARENT
    }
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = if (paged) pageIntercept(ev, pageDrag) else guardDown(ev) { super.onInterceptTouchEvent(it) }
    override fun onTouchEvent(ev: MotionEvent): Boolean = if (paged) pageTouch(ev, pageDrag, pager) else guardDown(ev) { super.onTouchEvent(it) }
}
/** GridView twin of [InkListView] (same guard). */
open class InkGridView(context: Context) : GridView(context) {
    var pager: ListPager? = null
    private val pageDrag = PageDrag(TapSlop.px(ViewConfiguration.get(context).scaledTouchSlop, resources.displayMetrics.density), axisBoth=true)
    var paged = false
        set(value) { if (field == value) return; field=value; pageDrag.cancel(); isFastScrollEnabled=!value; isFastScrollAlwaysVisible=!value }
    init {
        overScrollMode = OVER_SCROLL_NEVER; isVerticalFadingEdgeEnabled = false
        selector = ColorDrawable(Color.TRANSPARENT); isScrollbarFadingEnabled = false
    }
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = if (paged) pageIntercept(ev, pageDrag) else guardDown(ev) { super.onInterceptTouchEvent(it) }
    override fun onTouchEvent(ev: MotionEvent): Boolean = if (paged) pageTouch(ev, pageDrag, pager) else guardDown(ev) { super.onTouchEvent(it) }
}

private fun pageIntercept(ev: MotionEvent, drag: PageDrag): Boolean = when (ev.actionMasked) {
    MotionEvent.ACTION_DOWN -> { drag.down(ev.x,ev.y); false }
    MotionEvent.ACTION_MOVE -> drag.move(ev.x,ev.y)
    MotionEvent.ACTION_CANCEL -> { drag.cancel(); false }
    else -> false
}
private fun pageTouch(ev: MotionEvent, drag: PageDrag, pager: ListPager?): Boolean {
    when (ev.actionMasked) {
        MotionEvent.ACTION_DOWN -> drag.down(ev.x,ev.y)
        MotionEvent.ACTION_MOVE -> drag.move(ev.x,ev.y)
        MotionEvent.ACTION_UP -> pager?.page(drag.up(ev.x,ev.y))
        MotionEvent.ACTION_CANCEL -> drag.cancel()
    }
    return true
}

/**
 * A tap stays a tap; any drag past [slop] is the list's (intercepted, so it never ends as a tap on a row) and makes
 * exactly one page decision on release. [axisBoth] (the grids) pages on the axis the drag first crossed the slop on (a
 * tie is vertical). A vertical-only list pages by where the finger lifts: mostly vertical and past the slop
 * ([TapSlop.releaseToList]), else nothing, so a swipe that starts sideways and turns up or down still pages.
 */
class PageDrag(private val slop: Float, private val axisBoth: Boolean=false) {
    private var x=0f; private var y=0f; private var active=false; private var horizontal=false
    var dragging=false; private set
    fun down(x: Float,y: Float) { this.x=x;this.y=y;active=true;dragging=false;horizontal=false }
    fun move(x: Float,y: Float): Boolean {
        if (!active) return false
        if (dragging) return true
        val dx=abs(x-this.x);val dy=abs(y-this.y)
        if (maxOf(dx,dy)>slop) { dragging=true;horizontal=axisBoth && dx>dy }
        return dragging
    }
    fun up(x: Float,y: Float): Int {
        move(x,y)
        val delta=when {
            axisBoth -> if (horizontal) this.x-x else this.y-y
            TapSlop.releaseToList(x-this.x,y-this.y,slop) -> this.y-y
            else -> 0f
        }
        val dir=if (!dragging || delta==0f) 0 else if (delta>0f) 1 else -1
        cancel();return dir
    }
    fun cancel() { active=false;dragging=false }
}
object PageFit {
    fun fit(listH: Int,minRowH: Int): Pair<Int,Int> {
        val h=listH.coerceAtLeast(0);val rows=(h/minRowH.coerceAtLeast(1)).coerceAtLeast(1)
        return rows to h/rows
    }
}
/**
 * 목록 넘기기: only 쪽 단위 (1) pages the library and the notes hub; 자동 (0, the default) and 스크롤 (2) scroll on
 * every device, e-ink included: the lists follow the finger and fling like the contents lists (user, 2026-10-04).
 */
object ListPaging { fun paged(setting: Int): Boolean = setting==1 }

/** Immediate list jumps: setSelection only, never smoothScroll or a fling. Main thread. */
class ListPager(val list: AbsListView,val bar: InkPagerBar,private val cols: Int=1) {
    var rowsPerPage=0
    var labelSuffix=""
    var onPaged: ((first: Int,last: Int)->Unit)?=null
    private val count: Int get()=list.adapter?.count ?: 0
    init {
        bindBar()
        list.setOnScrollListener(object : AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view: AbsListView,state: Int) {}
            override fun onScroll(view: AbsListView,first: Int,visible: Int,total: Int) {
                // A hidden bar (scroll mode) needs no label on every scroll frame; showing it lays the list out again.
                if (bar.visibility==View.VISIBLE) update()
                // Every move, scroll mode included: the hub's prefetch (N §9.7) keeps placeholders off screen.
                onPaged?.invoke(first,(first+visible-1).coerceAtLeast(first))
            }
        })
    }
    /** Points the bar's ◀ / ▶ / "3 / 27" at this pager (one bar can serve two lists; the shown one binds it). */
    fun bindBar() {
        bar.prev.setOnClickListener { page(-1) };bar.next.setOnClickListener { page(1) };bar.label.setOnClickListener { openNumPad() }
    }
    private fun fully(): Int {
        var n=0
        for (i in 0 until list.childCount) { val c=list.getChildAt(i);if (c.top>=list.paddingTop && c.bottom<=list.height-list.paddingBottom) n++ }
        return n.coerceAtLeast(1)
    }
    /**
     * Rows one page moves. Fixed rows: [rowsPerPage]. Measured (rows of unequal height): the whole rows on screen, so
     * the cut row, if any, leads the next page and an exactly filled page repeats nothing.
     */
    private fun step(): Int = if (rowsPerPage>0) rowsPerPage else {
        val c=cols.coerceAtLeast(1)
        (fully()/c).coerceAtLeast(1)*c
    }
    private fun end(): Boolean = count==0 || list.childCount>0 && list.lastVisiblePosition>=count-1 && list.getChildAt(list.childCount-1).bottom<=list.height-list.paddingBottom
    private fun total(): Int = if (rowsPerPage>0) ((count+rowsPerPage-1)/rowsPerPage).coerceAtLeast(1) else PagerMath.total(count,fully(),step())
    fun page(dir: Int): Boolean {
        if (dir==0 || count==0 || list.childCount==0 || dir>0 && end() || dir<0 && list.firstVisiblePosition==0) return false
        list.setSelection(PagerMath.target(list.firstVisiblePosition,dir,step(),count));update();return true
    }
    fun showRow(index: Int) { if (count>0) { list.setSelection(index.coerceIn(0,count-1));update() } }
    fun openNumPad() {
        val max=total()
        InkNumPad.show(list.context,"쪽 번호","1~$max",max.toString().length) { p ->
            if (p !in 1..max) "1~${max}쪽 사이로 입력하세요" else { showRow((p-1)*step());null }
        }
    }
    fun update() {
        val t=if (count==0) 0 else total()
        val p=if (t==0) 0 else if (rowsPerPage>0) 1+list.firstVisiblePosition/rowsPerPage else PagerMath.page(list.firstVisiblePosition,count,fully(),step(),end())
        bar.show(p,t,list.firstVisiblePosition>0,!end())
        val text=PagerMath.label(p,t)+labelSuffix
        if (bar.label.text!=text) bar.label.text=text
    }
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
