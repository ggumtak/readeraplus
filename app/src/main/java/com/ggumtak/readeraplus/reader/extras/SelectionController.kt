package com.ggumtak.readeraplus.reader.extras

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupWindow
import com.ggumtak.readeraplus.R
import com.ggumtak.readeraplus.data.Library
import com.ggumtak.readeraplus.data.Lookups
import com.ggumtak.readeraplus.data.NotePlace
import com.ggumtak.readeraplus.data.Quote
import com.ggumtak.readeraplus.data.TxtOverride
import com.ggumtak.readeraplus.engine.LineGeometry
import com.ggumtak.readeraplus.engine.LineInfo
import com.ggumtak.readeraplus.engine.OBJECT_CHAR
import com.ggumtak.readeraplus.engine.PageInfo
import com.ggumtak.readeraplus.engine.RectPx
import com.ggumtak.readeraplus.engine.SectionLayout
import com.ggumtak.readeraplus.engine.clusterEnd
import com.ggumtak.readeraplus.engine.clusterStart
import com.ggumtak.readeraplus.format.BookFormat
import com.ggumtak.readeraplus.format.DocPosition
import com.ggumtak.readeraplus.reader.LayoutKeys
import com.ggumtak.readeraplus.reader.ReaderHost
import com.ggumtak.readeraplus.reader.ReaderIo
import com.ggumtak.readeraplus.reader.ShownPage
import com.ggumtak.readeraplus.render.AndroidTextMeasurer
import com.ggumtak.readeraplus.render.Highlight
import com.ggumtak.readeraplus.render.HighlightKind
import com.ggumtak.readeraplus.render.PagePalette
import com.ggumtak.readeraplus.render.QuoteLook
import com.ggumtak.readeraplus.render.QuoteStyles
import com.ggumtak.readeraplus.settings.Settings
import com.ggumtak.readeraplus.ui.kit.Ink
import com.ggumtak.readeraplus.ui.kit.MenuItem
import com.ggumtak.readeraplus.ui.kit.alert
import com.ggumtak.readeraplus.ui.kit.borderBox
import com.ggumtak.readeraplus.ui.kit.confirm
import com.ggumtak.readeraplus.ui.kit.dp
import com.ggumtak.readeraplus.ui.kit.dpF
import com.ggumtak.readeraplus.ui.kit.hairline
import com.ggumtak.readeraplus.ui.kit.horizontal
import com.ggumtak.readeraplus.ui.kit.icon
import com.ggumtak.readeraplus.ui.kit.label
import com.ggumtak.readeraplus.ui.kit.lp
import com.ggumtak.readeraplus.ui.kit.popupMenu
import com.ggumtak.readeraplus.ui.kit.pressableBackground
import com.ggumtak.readeraplus.ui.kit.showNoAnim
import com.ggumtak.readeraplus.ui.kit.sp
import com.ggumtak.readeraplus.ui.kit.toast
import com.ggumtak.readeraplus.ui.kit.vertical
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.hypot

/**
 * Long-press text selection with two draggable handles and an action popup: one row of 복사 · 인용 · 메모 · 사전·번역 ·
 * ⋮ ([SelectionActions]; over an existing quote a colour row above 복사 · 메모 · 인용 삭제 · 사전·번역 · ⋮), the ⋮ menu
 * holding 색 골라 인용… · 공유 · 문단 선택 · 책에서 검색 · 웹 검색 · 여기부터 듣기 and, in a TXT book, 문구 지우기.
 * The selection is (section, start, end) in the section's text, whatever page shows: only its part on the page shown
 * is drawn and gets handles ([SelectionSpan]); highlight owner "selection". It grows over pages of ONE section: a
 * handle (or the long press's finger) held in the top / bottom zone of the text area for [EDGE_DWELL_MS] turns one
 * page ([EdgeDwell]) and keeps extending there; any other page change clears it. A second long press while a
 * selection shows waits [AppSettings.longPressMs][com.ggumtak.readeraplus.settings.AppSettings.longPressMs], like the
 * page's own.
 */
class SelectionController(private val host: ReaderHost) {
    // Resolved lazily: the host may construct this before its own properties are initialised.
    private val ctx get() = host.activity
    private val scope = MainScope()
    private val slop by lazy { ViewConfiguration.get(host.activity).scaledTouchSlop }

    private var active = false
    private var section = -1
    private var selStart = 0
    private var selEnd = 0
    /** Text of [section] (Copy / Quote / Note read it, not the page shown). */
    private var sectionText: String? = null
    /** The word picked by the long press: dragging the same finger extends from it in both directions. */
    private var anchorStart = 0
    private var anchorEnd = 0
    /** The gesture that started the selection (long press) is still going: its MOVE/UP are ours. */
    private var fromLongPress = false
    private var tapCandidate = false
    private var downX = 0f
    private var downY = 0f
    /** Where the long press was made, and whether its finger has dragged since (only then may it arm the edge dwell). */
    private var pressX = 0f
    private var pressY = 0f
    private var pressDragged = false

    /** The handle being dragged, with where its finger is (screen px) and the grab offset to its anchor. */
    private var dragHandle: HandleView? = null
    private var dragRawX = 0f
    private var dragRawY = 0f
    private var dragGrabDx = 0f
    private var dragGrabDy = 0f
    /** The held point in page view px: the long press's finger, or the dragged handle's hit point. */
    private var dragX = 0f
    private var dragY = 0f
    private val dragging: Boolean get() = fromLongPress || dragHandle != null

    /** Edge dwell ([EdgeDwell]): the page turn a held point in an edge zone asks for, and the wait for its page. */
    private val dwell = EdgeDwell()
    private val dwellFire = Runnable { fireDwell() }
    /** An edge turn was asked for and its page is not shown yet: its [onPageChanged] extends instead of clearing. */
    private var turnPending = false
    private var turnLayout: SectionLayout? = null
    /** The direction asked for and the start of the page it left: the page that lands must be the one after / before it. */
    private var turnNext = false
    private var turnPageStart = 0
    private val turnTimeout = Runnable {
        turnPending = false
        turnLayout = null
        dwell.landed()
        armDwell()
    }

    private var startHandle: HandleView? = null
    private var endHandle: HandleView? = null
    private var actions: PopupWindow? = null
    /** The ⋮ menu and the colour palette opened from [actions]. */
    private var menu: PopupWindow? = null
    private var palette: PopupWindow? = null
    /** The 인용 cell of [actions] (the palette's anchor) and the palette row over an existing quote. */
    private var quoteCell: View? = null
    private var paletteRow: QuotePalette.Row? = null
    private var overflowIds: List<SelectionActions.Id> = emptyList()
    private var editingQuote: Quote? = null
    /** Bumped per palette-row tap: only the latest recolour's IO result is applied. */
    private var recolourGen = 0

    /** Quotes of the book come from [QuoteCache] (shared with the contents dialog); reloaded once per controller. */
    private var quotesRequested = false
    private val main = Handler(Looper.getMainLooper())
    /**
     * A second long press while a selection is shown starts a new selection there (drag extends it). Off the text
     * ([glyphAt] finds no glyph) [startAt] does nothing: the selection stays, and with [tapCandidate] cleared the
     * finger's UP is not taken for "a tap outside clears".
     */
    private val reselect = Runnable {
        if (active && tapCandidate) {
            tapCandidate = false
            startAt(downX, downY)
        }
    }

    private val knownOrigin = FloatArray(2)
    private var originKey: Any? = null
    private var originX = 0f
    private var originY = 0f

    /** Optional "read aloud from here" hook (defaults to the TtsController created for the same host). */
    var onReadAloud: ((DocPosition) -> Unit)? = null

    /**
     * The glyph a long press at view coordinates (x, y) is on, or -1 on a margin, the leading between lines, a space
     * or the blank end of a short line (set by the host; null = no check, the char [ReaderHost.hitTest] snaps to).
     */
    var glyphAt: ((Float, Float) -> Int)? = null

    val isActive: Boolean get() = active

    /**
     * Starts a selection at the word under view coordinates (x, y). Returns true if something was selected. Only
     * long presses call this (the host's and [reselect]). The word is the one of the glyph [glyphAt] finds (within
     * its slop, never a space next to it); where it finds none nothing happens, and a selection already shown stays.
     */
    fun startAt(x: Float, y: Float): Boolean {
        val find = glyphAt
        val off = if (find != null) find(x, y) else host.hitTest(x, y)
        // The page under the finger: in a landscape spread either of the two (its section may be the next one's).
        val sec = host.hitSection(x)
        val under = host.shownPages().firstOrNull { it.section == sec && off >= it.page.start && off < it.page.end } ?: return false
        val layout = under.layout
        val text = layout.content.text
        if (off < 0 || off >= text.length) return false
        ensureQuotes()

        // Only a quote the page draws (K2): one whose place changed is not drawn here, and a press on its old range
        // selects the word pressed, not that hidden quote.
        val sig = sessionSig(sec)
        val q = QuoteCache.get(host.book.id)?.firstOrNull {
            off >= it.start && off < it.end && QuoteHighlights.drawn(it, sec, sig) { c -> ContentsDialog.anchorMatch(host, c) }
        }
        var s: Int
        var e: Int
        if (q != null) {
            s = q.start
            e = q.end
        } else {
            val packed = runCatching { LineGeometry.wordAt(text, off) }.getOrDefault((off.toLong() shl 32) or clusterEnd(text, off).toLong())
            s = (packed ushr 32).toInt()
            e = (packed and 0xFFFFFFFFL).toInt()
        }
        s = s.coerceIn(0, text.length)
        e = e.coerceIn(s, text.length)
        if (e <= s) return false
        if (e - s == 1 && (text[s] == OBJECT_CHAR || SentenceSplitter.isSpace(text[s]))) return false

        // Only now that there is a new word: a press that selects nothing keeps the current selection.
        if (active) clear()
        section = sec
        sectionText = text
        selStart = s
        selEnd = e
        anchorStart = s
        anchorEnd = e
        editingQuote = q
        active = true
        fromLongPress = true
        pressX = x
        pressY = y
        pressDragged = false
        tapCandidate = false
        updateHighlight()
        showHandles()
        showActions()
        return true
    }

    /** Touch events while active (handle dragging; a tap outside clears). Returns true if consumed. */
    fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!active) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                fromLongPress = false
                tapCandidate = true
                downX = ev.x
                downY = ev.y
                cancelDwell()
                main.removeCallbacks(reselect)
                main.postDelayed(reselect, longPressMs())
            }
            MotionEvent.ACTION_MOVE -> {
                if (fromLongPress) {
                    hideActions()
                    dragX = ev.x
                    dragY = ev.y
                    extendTo(ev.x, ev.y)
                    // A finger resting where the press was made (maybe in an edge zone) turns nothing.
                    if (!pressDragged) {
                        pressDragged = EdgeGuard.pressDragged(pressX, pressY, ev.x, ev.y, slop.toFloat(), selStart < anchorStart || selEnd > anchorEnd)
                    }
                    if (pressDragged) armDwell()
                } else if (tapCandidate && hypot(ev.x - downX, ev.y - downY) > slop) {
                    tapCandidate = false
                    main.removeCallbacks(reselect)
                }
            }
            MotionEvent.ACTION_UP -> {
                main.removeCallbacks(reselect)
                releaseDwell()
                if (fromLongPress) {
                    fromLongPress = false
                    showActions()
                } else if (tapCandidate) {
                    clear()
                }
                tapCandidate = false
            }
            MotionEvent.ACTION_CANCEL -> {
                main.removeCallbacks(reselect)
                releaseDwell()
                if (fromLongPress) showActions()
                fromLongPress = false
                tapCandidate = false
            }
        }
        return true
    }

    fun clear() {
        val wasActive = active
        active = false
        fromLongPress = false
        tapCandidate = false
        dragHandle = null
        cancelDwell()
        main.removeCallbacks(reselect)
        editingQuote = null
        hideActions()
        startHandle?.let { (it.parent as? ViewGroup)?.removeView(it) }
        endHandle?.let { (it.parent as? ViewGroup)?.removeView(it) }
        startHandle = null
        endHandle = null
        if (wasActive && section >= 0) runCatching { host.setHighlights("selection", section, emptyList()) }
        section = -1
        sectionText = null
    }

    /**
     * Called by the host after page changes / relayout so handles and popup follow or close. Every page change clears
     * the selection except the one an edge dwell asked for ([fireDwell]): that page, still of the same layout, keeps
     * it and goes on extending under the finger.
     */
    fun onPageChanged() {
        if (!active) return
        if (turnPending && edgeTurnLanded()) return
        clear()
    }

    /** The page an edge dwell turned to is shown. False when what is shown is not the same layout any more. */
    private fun edgeTurnLanded(): Boolean {
        val before = turnLayout
        val next = turnNext
        val startBefore = turnPageStart
        turnPending = false
        turnLayout = null
        main.removeCallbacks(turnTimeout)
        // Scroll: a settle gives the virtual page back to the anchor's section; the selection's section stays in focus.
        host.holdSection(section)
        val now = host.currentLayout
        // No layout at all (scroll: the section has no whole line on screen) hides the handles; another one is a
        // relayout or a seam: the selection goes.
        if (before == null || (now != null && now !== before)) return false
        // Some other page change (a turn from elsewhere) is not the one asked for: the selection goes.
        host.currentPage?.let { if (!EdgeGuard.turnLanded(next, startBefore, it.start)) return false }
        dwell.landed()
        showHandles()
        if (dragging) {
            refreshDrag()
            armDwell()
        } else {
            showActions()
        }
        return true
    }

    // ------------------------------------------------------------------ selection geometry

    /** The pages on screen that belong to the selection's section: one, or in a landscape spread up to two. */
    private fun sectionPages(): List<ShownPage> = host.shownPages().filter { it.section == section }

    /** The chars of the selection's section on screen: [start] to [end] of [layout] (a spread: both pages joined). */
    private class Span(val layout: SectionLayout, val start: Int, val end: Int)

    private fun validSpan(): Span? {
        val pages = sectionPages()
        val first = pages.firstOrNull() ?: return null
        return Span(first.layout, pages.minOf { it.page.start }, pages.maxOf { it.page.end })
    }

    /** The selection can turn pages only from the section of the page the host calls current (a spread's left page). */
    private fun onCurrentSection(): Boolean = host.currentPosition().section == section

    /** The press's drag: the word picked grows to the char under (x, y) on the page shown (clusters stay whole). */
    private fun extendTo(x: Float, y: Float) {
        val span = validSpan() ?: return
        val off = host.hitTest(x, y)
        if (off < 0 || host.hitSection(x) != section) return
        val text = span.layout.content.text
        val o = off.coerceIn(span.start, span.end - 1)
        val s = minOf(anchorStart, clusterStart(text, o))
        val e = maxOf(anchorEnd, clusterEnd(text, o))
        setRange(s, e)
    }

    /** The held finger again, after a turn: the new page under it gets the extension. */
    private fun refreshDrag() {
        if (fromLongPress) extendTo(dragX, dragY) else if (dragHandle != null) moveDraggedHandle()
    }

    /** The dragged handle's hit point (its anchor moved up into the glyph band) to a char; the selection follows. */
    private fun moveDraggedHandle() {
        val h = dragHandle ?: return
        val span = validSpan() ?: return
        val pv = host.pageView
        val px = dragRawX + dragGrabDx - pv.left - pv.translationX
        val py = dragRawY + dragGrabDy - pv.top - pv.translationY - h.lineHalf
        dragX = px
        dragY = py
        val off = host.hitTest(px, py)
        if (off < 0 || host.hitSection(px) != section) return
        val text = span.layout.content.text
        val o = off.coerceIn(span.start, span.end - 1)
        if (h.start) setRange(clusterStart(text, o.coerceAtMost(selEnd - 1)), selEnd)
        else setRange(selStart, maxOf(clusterEnd(text, o), clusterEnd(text, selStart)))
    }

    // ------------------------------------------------------------------ edge dwell

    /** The zone the held point is in, NONE where the page may not turn that way (a handle's other way, a section's end). */
    private fun zoneNow(): Zone {
        if (!dragging) return Zone.NONE
        val page = validSpan() ?: return Zone.NONE
        if (!onCurrentSection()) return Zone.NONE
        val len = sectionText?.length ?: return Zone.NONE
        val v = host.pageView
        val g = LayoutKeys.geometry(Settings.reader, v.width, v.height, ctx.resources.displayMetrics.density, host.pageCutoutTop,
            emPx = AndroidTextMeasurer.emPxFor(ctx, Settings.reader.fontSizeSp),
            naturalLinePx = AndroidTextMeasurer.naturalLinePxFor(ctx, Settings.reader))
        val h = dragHandle
        val allowNext = (h == null || !h.start) && SelectionSpan.canTurn(true, page.start, page.end, len)
        val allowPrev = (h == null || h.start) && SelectionSpan.canTurn(false, page.start, page.end, len)
        return EdgeZone.of(dragY, g.contentTop.toFloat(), (g.contentTop + g.contentHeight).toFloat(),
            ctx.dpF(EDGE_ZONE_DP.toFloat()), allowNext, allowPrev)
    }

    /** The held point moved (or a page showed): starts, keeps or drops the dwell timer. */
    private fun armDwell() {
        when (dwell.update(zoneNow())) {
            EdgeDwell.Action.ARM -> {
                main.removeCallbacks(dwellFire)
                main.postDelayed(dwellFire, EDGE_DWELL_MS)
            }
            EdgeDwell.Action.CANCEL -> main.removeCallbacks(dwellFire)
            else -> {}
        }
    }

    /** Pointer up / cancel, clear, a boundary: no timer, no page waited for. */
    private fun cancelDwell() {
        dwell.cancel()
        main.removeCallbacks(dwellFire)
        main.removeCallbacks(turnTimeout)
        turnPending = false
        turnLayout = null
    }

    /**
     * The pointer went up: no timer, but a turn already asked for stays outstanding, so a page that shows after the
     * finger left still keeps the selection ([turnTimeout] ends the wait).
     */
    private fun releaseDwell() {
        main.removeCallbacks(dwellFire)
        if (!dwell.release()) cancelDwell()
    }

    /**
     * The dwell ran out: one page toward the zone through the host's own turn (no animation; not a manual turn for the
     * return chip, and no peek ends: a note peek stays a peek, nothing is saved while it holds). [turnPending] lets
     * its [onPageChanged] keep the selection; the next dwell is armed only when that page shows.
     */
    private fun fireDwell() {
        val zone = dwell.fire() ?: return
        val next = zone == Zone.BOTTOM
        val page = validSpan()?.takeIf { onCurrentSection() }
        val len = sectionText?.length ?: 0
        // A dialog took the window's focus, the finger is gone, or the section has no page that way.
        if (!active || !dragging || page == null || !ctx.hasWindowFocus() || !SelectionSpan.canTurn(next, page.start, page.end, len)) {
            dwell.cancel()
            return
        }
        // A step still waiting for its section (scroll), or a jump: one more turn would queue behind it and the
        // timeouts would flush several screens at once. Wait another dwell instead.
        if (!host.canTurnNow()) {
            dwell.landed()
            armDwell()
            return
        }
        turnPending = true
        turnLayout = host.currentLayout
        turnNext = next
        // The page the host calls current (a spread's left one) is what turnLanded compares after the turn.
        turnPageStart = host.currentPage?.start ?: page.start
        main.removeCallbacks(turnTimeout)
        main.postDelayed(turnTimeout, EDGE_TURN_WAIT_MS)
        val ok = runCatching { if (next) host.nextPage() else host.prevPage() }.getOrDefault(false)
        if (!ok && turnPending) {
            // Nothing turned (a page still on its way): the finger is still there, so the dwell starts over.
            turnPending = false
            turnLayout = null
            main.removeCallbacks(turnTimeout)
            dwell.landed()
            armDwell()
        }
    }

    private fun setRange(s: Int, e: Int) {
        if (s == selStart && e == selEnd) return
        if (e <= s) return
        selStart = s
        selEnd = e
        if (editingQuote != null && (s != editingQuote?.start || e != editingQuote?.end)) editingQuote = null
        updateHighlight()
        showHandles()
    }

    /**
     * The selection fill; none while the selection is exactly an existing quote, so its real colour shows (the
     * handles mark its ends). Dragging a handle off the quote brings the fill back.
     */
    private fun updateHighlight() {
        val q = editingQuote
        val list = if (q != null && q.start == selStart && q.end == selEnd) emptyList()
        else listOf(Highlight(selStart, selEnd, HighlightKind.SELECTION))
        runCatching { host.setHighlights("selection", section, list) }
    }

    /**
     * View-space offset of the page content box (see [OriginCalibrator]). A successful calibration is cached per
     * layout config and view size; the margin-formula fallback is never cached (another page may calibrate).
     */
    private fun origin(layout: SectionLayout, page: PageInfo) {
        // A landscape spread: the host knows the box (the calibration below would also see the other column).
        if (host.pageOrigin(knownOrigin)) {
            originX = knownOrigin[0]
            originY = knownOrigin[1]
            originKey = null
            return
        }
        val v = host.pageView
        val key = listOf(layout.config, v.width, v.height, v.paddingLeft, v.paddingTop)
        if (key == originKey) return
        val boxes = page.lines.map { OriginCalibrator.LineBox(it.start, it.end, it.top, it.bottom) }
        val dy = OriginCalibrator.calibrateY(boxes, v.height, v.width * 0.3f) { px, py -> host.hitTest(px, py) }
        var dx = Float.NaN
        if (!dy.isNaN()) {
            val line = page.lines.filter { it.imageBlock == null && !it.isRule && it.end - it.start >= 2 }.maxByOrNull { it.end - it.start }
            if (line != null) {
                val lefts = FloatArray(line.end - line.start)
                runCatching { LineGeometry.charPositions(layout, line, lefts) }.onSuccess {
                    val py = (line.top + line.bottom) / 2f + dy
                    dx = OriginCalibrator.calibrateX(line.start, line.end, lefts, layout.advances, v.width, py) { px, qy -> host.hitTest(px, qy) }
                }
            }
        }
        // Fallback: the reader's own content box (the status bands and the margins from the settings, A §2.5).
        val g = LayoutKeys.geometry(Settings.reader, v.width, v.height, ctx.resources.displayMetrics.density, host.pageCutoutTop,
            emPx = AndroidTextMeasurer.emPxFor(ctx, Settings.reader.fontSizeSp),
            naturalLinePx = AndroidTextMeasurer.naturalLinePxFor(ctx, Settings.reader))
        originX = if (!dx.isNaN()) dx else SelectionOrigin.fallbackX(g, v.paddingLeft)
        originY = if (!dy.isNaN()) dy else SelectionOrigin.fallbackY(g, v.paddingTop)
        originKey = if (!dx.isNaN() && !dy.isNaN()) key else null
    }

    /** The part of the selection on [page] as line rects, or null when none of it is on the page. */
    private fun visibleRects(layout: SectionLayout, page: PageInfo): List<RectPx>? {
        if (!SelectionSpan.visible(selStart, selEnd, page.start, page.end)) return null
        val vs = SelectionSpan.start(selStart, page.start)
        val ve = SelectionSpan.end(selEnd, page.end)
        return runCatching { LineGeometry.rangeRects(layout, page, vs, ve) }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    /** A handle whose end is not on the page shown is hidden (the one being dragged keeps getting its touches). */
    private fun hideHandles() {
        startHandle?.visibility = View.INVISIBLE
        endHandle?.visibility = View.INVISIBLE
    }

    private fun showHandles() {
        val parent = Overlay.parentOf(host) ?: return
        // The pages on screen that hold some of the selection: its start is on the first, its end on the last (one
        // page, or two of a landscape spread).
        var firstPage: ShownPage? = null
        var lastPage: ShownPage? = null
        var firstRects: List<RectPx>? = null
        var lastRects: List<RectPx>? = null
        for (sp in sectionPages()) {
            val r = visibleRects(sp.layout, sp.page) ?: continue
            if (firstPage == null) {
                firstPage = sp
                firstRects = r
            }
            lastPage = sp
            lastRects = r
        }
        if (firstPage == null || lastPage == null || firstRects == null || lastRects == null) {
            // Nothing of the selection is on this page: the handles and the popup wait for a page that has it.
            hideHandles()
            hideActions()
            return
        }
        origin(firstPage.layout, firstPage.page)
        val pv = host.pageView
        val baseX = pv.left + pv.translationX + originX
        val baseY = pv.top + pv.translationY + originY
        val first = firstRects.first()
        val last = lastRects.last()
        val sh = startHandle ?: HandleView(ctx, start = true).also { h ->
            startHandle = h
            parent.addView(h, FrameLayout.LayoutParams(h.sizePx, h.sizePx))
            h.setOnTouchListener(HandleDrag(h))
        }
        val eh = endHandle ?: HandleView(ctx, start = false).also { h ->
            endHandle = h
            parent.addView(h, FrameLayout.LayoutParams(h.sizePx, h.sizePx))
            h.setOnTouchListener(HandleDrag(h))
        }
        // Anchored under the letters (the glyph band), not under the line box's blank leading.
        sh.place(baseX + firstPage.dx + first.left, baseY + HandleAnchor.bottom(firstPage.layout, firstPage.page, first),
            HandleAnchor.halfHeight(firstPage.layout, firstPage.page, first))
        eh.place(baseX + lastPage.dx + last.right, baseY + HandleAnchor.bottom(lastPage.layout, lastPage.page, last),
            HandleAnchor.halfHeight(lastPage.layout, lastPage.page, last))
        sh.visibility = if (SelectionSpan.startShown(selStart, selEnd, firstPage.page.start, firstPage.page.end)) View.VISIBLE else View.INVISIBLE
        eh.visibility = if (SelectionSpan.endShown(selStart, selEnd, lastPage.page.start, lastPage.page.end)) View.VISIBLE else View.INVISIBLE
    }

    /** Drags a handle; the hit point is the handle's anchor moved up into the middle of the glyph band. */
    private inner class HandleDrag(private val h: HandleView) : View.OnTouchListener {
        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragHandle = h
                    dragRawX = ev.rawX
                    dragRawY = ev.rawY
                    dragGrabDx = h.anchorX - ev.rawX
                    dragGrabDy = h.anchorY - ev.rawY
                    cancelDwell()
                    hideActions()
                    // The handle's own touch stream: the page view sees none of it, so no tap or swipe is recognised.
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (dragHandle !== h) return true
                    dragRawX = ev.rawX
                    dragRawY = ev.rawY
                    moveDraggedHandle()
                    armDwell()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragHandle === h) dragHandle = null
                    cancelDwell()
                    showActions()
                }
            }
            return true
        }
    }

    /** "길게 누르기 시간" (AppSettings.longPressMs), clamped like the page's own long press. */
    private fun longPressMs(): Long = runCatching { Settings.app.longPressMs }.getOrDefault(DEFAULT_LONG_PRESS_MS)
        .coerceIn(MIN_LONG_PRESS_MS, MAX_LONG_PRESS_MS).toLong()

    // ------------------------------------------------------------------ action popup

    /** Runs the action [id] of the popup ([SelectionActions]); [anchor] is its cell (⋮ anchors its menu there). */
    private fun perform(id: SelectionActions.Id, anchor: View?) {
        when (id) {
            SelectionActions.Id.COPY -> copy()
            SelectionActions.Id.QUOTE -> quoteNow(LastQuoteStyle.get())
            SelectionActions.Id.NOTE -> noteThenQuote()
            SelectionActions.Id.EDIT_NOTE -> editingQuote?.let { editQuoteNote(it) }
            SelectionActions.Id.DELETE_QUOTE -> editingQuote?.let { deleteQuote(it) }
            SelectionActions.Id.LOOKUP -> lookUp()
            SelectionActions.Id.MORE -> if (anchor != null) showOverflow(anchor)
            SelectionActions.Id.PICK_STYLE -> pickStyleThenQuote()
            SelectionActions.Id.SHARE -> share()
            SelectionActions.Id.PARAGRAPH -> selectParagraph()
            SelectionActions.Id.SEARCH -> searchInBook()
            SelectionActions.Id.WEB_SEARCH -> webSearch()
            SelectionActions.Id.READ_ALOUD -> readAloud()
            SelectionActions.Id.DELETE_PHRASE ->
                // Rules apply per source line: a selection across a line break can't become one (T1-10).
                if (selectionIsOneLine()) deletePhrase() else ctx.toast("여러 줄은 한 번에 지울 수 없습니다 · 한 줄 안에서 고르세요")
        }
    }

    private fun iconOf(id: SelectionActions.Id): Int = when (id) {
        SelectionActions.Id.COPY -> R.drawable.ic_content_copy
        SelectionActions.Id.QUOTE -> R.drawable.ic_format_quote
        SelectionActions.Id.NOTE, SelectionActions.Id.EDIT_NOTE -> R.drawable.ic_sticky_note_2
        SelectionActions.Id.DELETE_QUOTE -> R.drawable.ic_delete
        SelectionActions.Id.LOOKUP -> R.drawable.ic_translate
        SelectionActions.Id.MORE -> R.drawable.ic_more_vert
        SelectionActions.Id.PICK_STYLE -> R.drawable.ic_ink_highlighter
        SelectionActions.Id.SHARE -> R.drawable.ic_share
        SelectionActions.Id.PARAGRAPH -> R.drawable.ic_select_all
        SelectionActions.Id.SEARCH -> R.drawable.ic_search
        SelectionActions.Id.WEB_SEARCH -> R.drawable.ic_travel_explore
        SelectionActions.Id.READ_ALOUD -> R.drawable.ic_volume_up
        SelectionActions.Id.DELETE_PHRASE -> R.drawable.ic_delete_forever
    }

    private fun actionIds(): List<SelectionActions.Id> = SelectionActions.ids(
        existingQuote = editingQuote != null,
        readAloud = onReadAloud != null || TtsRegistry.get(host) != null,
        txt = host is TxtOverrideHost && runCatching { host.book.format }.getOrNull() == BookFormat.TXT,
    )

    /**
     * The popup (UI_SPEC polish 13, NOTES_SPEC §7.1): one row of 5 cells, (W − 16 dp) / 5 wide and 56 dp tall with
     * 13 sp labels, in a square [borderBox]; the 인용 cell shows the last style's swatch with a "▾" (long press: the
     * palette). Over an existing quote a palette row (current style ringed) sits above the row.
     */
    private fun showActions() {
        if (!active) return
        // Anchored to the part of the selection on the page(s) shown.
        val onScreen = sectionPages()
        val rects = ArrayList<RectPx>()
        for (sp in onScreen) visibleRects(sp.layout, sp.page)?.let { rects += it }
        val anchorPage = onScreen.firstOrNull() ?: return
        if (rects.isEmpty()) return
        origin(anchorPage.layout, anchorPage.page)
        hideActions()

        val dm = ctx.resources.displayMetrics
        val cellW = PaletteGeometry.selectionCell(dm.widthPixels, dm.density)
        val box = ctx.vertical { background = ctx.borderBox(radiusDp = 0f) }
        val q = editingQuote
        if (q != null) {
            val row = QuotePalette.paletteRow(ctx, PaletteGeometry.inlinePaletteCell(dm.widthPixels, dm.density), q.style) { s, _ -> recolour(s) }
            paletteRow = row
            box.addView(row.view, lp())
            box.addView(ctx.hairline(), lp(MATCH_PARENT, 1))
        }
        val (primary, overflow) = SelectionActions.split(actionIds())
        overflowIds = overflow
        val row = ctx.horizontal()
        for (id in primary) {
            val cell = ctx.vertical {
                gravity = Gravity.CENTER
                background = pressableBackground()
                contentDescription = id.label
            }
            cell.setOnClickListener { perform(id, cell) }
            if (id == SelectionActions.Id.QUOTE) {
                quoteCell = cell
                cell.setOnLongClickListener { pickStyleThenQuote(); true }
                cell.addView(quoteIcon(), LinearLayout.LayoutParams(ctx.dp(ICON_BOX_DP), ctx.dp(ICON_BOX_DP)))
            } else {
                cell.addView(FrameLayout(ctx).apply {
                    addView(ctx.icon(iconOf(id), 24), FrameLayout.LayoutParams(ctx.dp(24), ctx.dp(24), Gravity.CENTER))
                }, LinearLayout.LayoutParams(ctx.dp(ICON_BOX_DP), ctx.dp(ICON_BOX_DP)))
            }
            cell.addView(ctx.label(id.label, LABEL_SP, maxLines = 1).apply {
                gravity = Gravity.CENTER
                setPadding(ctx.dp(2), ctx.dp(2), ctx.dp(2), 0)
                // A longer label shrinks to the cell instead of being cut ("사전·번역" on a narrow phone).
                setAutoSizeTextTypeUniformWithConfiguration(10, LABEL_SP.toInt(), 1, android.util.TypedValue.COMPLEX_UNIT_SP)
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            row.addView(cell, LinearLayout.LayoutParams(cellW, ctx.dp(PaletteGeometry.CELL_HEIGHT_DP)))
        }
        box.addView(row, lp())

        box.measure(
            View.MeasureSpec.makeMeasureSpec(dm.widthPixels, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val w = box.measuredWidth
        val h = box.measuredHeight
        val loc = IntArray(2)
        host.pageView.getLocationInWindow(loc)
        var top = Float.MAX_VALUE
        var bottom = 0f
        for (r in rects) {
            top = minOf(top, r.top)
            bottom = maxOf(bottom, r.bottom)
        }
        val margin = ctx.dp(8)
        val x = ((dm.widthPixels - w) / 2).coerceAtLeast(0)
        val y = PaletteGeometry.selectionY(loc[1] + originY + top, loc[1] + originY + bottom, h, ctx.dp(10),
            startHandle?.sizePx ?: ctx.dp(40), margin, dm.heightPixels)
        val pw = PopupWindow(box, WRAP_CONTENT, WRAP_CONTENT, false).apply {
            animationStyle = 0
            elevation = 0f
            isTouchable = true
            isOutsideTouchable = false
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }
        runCatching { pw.showAtLocation(host.pageView, Gravity.NO_GRAVITY, x, y) }
        actions = pw
    }

    /** The 인용 cell's icon: the last style's swatch with a 9 sp "▾" at its lower right (long press = colours). */
    private fun quoteIcon(): View = FrameLayout(ctx).apply {
        addView(QuoteSwatch(ctx, LastQuoteStyle.get(), PaletteGeometry.POPUP_SWATCH_DP, QuoteLook.ink()).apply { reserveRing = false },
            FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER))
        addView(ctx.label("▾", 9f, maxLines = 1).apply { includeFontPadding = false },
            FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.END or Gravity.BOTTOM))
    }

    /** ⋮: the rest of the actions in a [popupMenu] under the cell. */
    private fun showOverflow(anchor: View) {
        val ids = overflowIds
        if (ids.isEmpty()) return
        hideMenus()
        val items = ids.map { id -> MenuItem(id.label, iconOf(id)) { if (active) perform(id, null) } }
        menu = PanelRegistry.popup(ctx, ctx.popupMenu(anchor, items, widthDp = 220))
    }

    private fun hideActions() {
        hideMenus()
        actions?.let { runCatching { it.dismiss() } }
        actions = null
        quoteCell = null
        paletteRow = null
    }

    /** Closes the ⋮ menu and the colour palette opened from the popup. */
    private fun hideMenus() {
        menu?.let { runCatching { it.dismiss() } }
        menu = null
        palette?.let { runCatching { it.dismiss() } }
        palette = null
    }

    // ------------------------------------------------------------------ actions

    private fun selectedText(): String {
        val text = sectionText ?: return ""
        val s = selStart.coerceIn(0, text.length)
        val e = selEnd.coerceIn(s, text.length)
        val sb = StringBuilder(e - s)
        for (i in s until e) {
            val c = text[i]
            if (c != OBJECT_CHAR) sb.append(c)
        }
        return sb.toString().trim()
    }

    private fun copy() {
        val t = selectedText()
        clear()
        if (t.isNotEmpty()) TextActions.copy(ctx, t)
    }

    private fun share() {
        val t = selectedText()
        clear()
        if (t.isEmpty()) return
        val by = host.book.author.takeIf { it.isNotBlank() }?.let { ", $it" } ?: ""
        TextActions.share(ctx, "“$t”\n— ${host.book.title}$by", host.book.title)
    }

    private fun searchInBook() {
        val t = selectedText().replace('\n', ' ')
        clear()
        if (t.isNotEmpty()) ReaderPanels.showSearch(host, t.take(100))
    }

    private fun lookUp() {
        val t = selectedText()
        val snap = lookupSnapshot(t)
        clear()
        if (t.isNotEmpty()) TextActions.lookUp(ctx, t) { via, app -> recordLookup(snap, via, app) }
    }

    private fun webSearch() {
        val t = selectedText().replace('\n', ' ')
        val snap = lookupSnapshot(t)
        clear()
        if (t.isNotEmpty()) TextActions.webSearch(ctx, t.take(200)) { recordLookup(snap, Lookups.VIA_WEB, TextActions.webSearchHost()) }
    }

    /** What a lookup of the selection records (hub.md §6.4); taken before [clear]. Null when nothing is selected. */
    private fun lookupSnapshot(selected: String): LookupSnapshot? {
        if (!active || section < 0 || selected.isEmpty()) return null
        val text = sectionText ?: return null
        val bookId = runCatching { host.book.id }.getOrNull() ?: return null
        val context = runCatching { LookupContext.sentence(text, selStart, selEnd) }.getOrDefault("")
        return LookupSnapshot(bookId, LookupSnapshot.word(selected), section, selStart, selEnd, context, placeOf(selStart))
    }

    /** Silently records a lookup the user went through with (never a cancelled chooser), when "찾아본 단어 기록" is on. */
    private fun recordLookup(snap: LookupSnapshot?, via: Int, app: String) {
        if (snap == null || !runCatching { Settings.app.recordLookups }.getOrDefault(true)) return
        ReaderIo.launch {
            Lookups.record(snap.bookId, snap.word, snap.section, snap.start, snap.end, snap.context, snap.place, via, app)
        }
    }

    /** Chapter, fraction and parse signature of [offset] in the selection's section (NotePlaceHost; main thread). */
    private fun placeOf(offset: Int): NotePlace? =
        (host as? NotePlaceHost)?.let { h -> runCatching { h.notePlace(DocPosition(section, offset)) }.getOrNull() }

    /** The selection holds no line break (a paragraph end in the laid-out text). */
    private fun selectionIsOneLine(): Boolean {
        val text = sectionText ?: return false
        val s = selStart.coerceIn(0, text.length)
        val e = selEnd.coerceIn(s, text.length)
        for (i in s until e) if (text[i] == '\n' || text[i] == '\r') return false
        return true
    }

    /**
     * "이 문구 지우기" (T1-10): a replacement rule for this book only (TxtOverrideHost) that deletes the whole source line
     * ("줄 전체 지우기") or just the phrase ("이 문구만"), then one re-parse of the book. Afterwards the section shown is
     * checked once: rules match source lines before wrapped lines are joined and spaces tidied, so a phrase shaped
     * differently in the file may survive — the user is told so.
     */
    private fun deletePhrase() {
        val h = host as? TxtOverrideHost ?: return
        if (!selectionIsOneLine()) return
        val phrase = selectedText()
        val bookId = runCatching { host.book.id }.getOrNull() ?: return
        clear()
        if (phrase.isEmpty()) return
        val shown = if (phrase.length > PHRASE_SHOWN) phrase.take(PHRASE_SHOWN) + "…" else phrase
        val d = ctx.alert().setTitle("이 문구 지우기")
            .setMessage("‘$shown’${Josa.eulReul(phrase)} 이 책에서 지웁니다.\n‘바꾸기 규칙’에서 되돌릴 수 있습니다.")
            .setPositiveButton("줄 전체 지우기") { _, _ -> addPhraseRule(h, bookId, phrase, wholeLine = true) }
            .setNeutralButton("이 문구만") { _, _ -> addPhraseRule(h, bookId, phrase, wholeLine = false) }
            .setNegativeButton("취소", null)
            .showNoAnim()
        PanelRegistry.dialog(ctx, d)
    }

    private fun addPhraseRule(h: TxtOverrideHost, bookId: Long, phrase: String, wholeLine: Boolean) {
        if (runCatching { host.book.id }.getOrNull() != bookId) return
        val rule = RuleLiteral.build(phrase, wholeLine) ?: return
        val o = h.txtOverride ?: TxtOverride()
        val rules = o.replaceRules ?: Settings.reader.txtReplaceRules
        val next = TxtEdits.appendRule(rules, rule)
        if (next == rules) {
            ctx.toast("이미 같은 규칙이 있습니다")
            return
        }
        h.applyTxtOverride(o.copy(replaceRules = next)) {
            if (runCatching { host.book.id }.getOrNull() != bookId) return@applyTxtOverride
            val text = host.currentLayout?.content?.text ?: return@applyTxtOverride
            if (text.indexOf(phrase) >= 0) {
                ctx.toast("원문과 모양이 달라 지우지 못했습니다")
            } else {
                ctx.toast("지웠습니다 · ‘바꾸기 규칙’에서 되돌릴 수 있습니다")
            }
        }
    }

    private fun readAloud() {
        val pos = DocPosition(section, selStart)
        clear()
        val cb = onReadAloud
        if (cb != null) cb(pos) else TtsRegistry.get(host)?.startFrom(pos)
    }

    /** The whole paragraphs the selection touches, in the section's text (not cut at the page's edges). */
    private fun selectParagraph() {
        val text = sectionText ?: return
        var s = selStart.coerceIn(0, text.length)
        while (s > 0 && text[s - 1] != '\n') s--
        var e = selEnd.coerceIn(s, text.length)
        while (e < text.length && text[e] != '\n') e++
        setRange(s, e)
        showActions()
    }

    /** What a quote is made of, taken while the selection is valid (the page may change before it is saved). */
    private class QuoteSnapshot(val bookId: Long, val section: Int, val start: Int, val end: Int, val text: String, val place: NotePlace?)

    /** The current selection as a quote, or null when there is none (or nothing selectable in it). */
    private fun snapshot(): QuoteSnapshot? {
        if (!active || section < 0 || sectionText == null) return null
        val t = selectedText()
        if (t.isEmpty()) return null
        val bookId = runCatching { host.book.id }.getOrNull() ?: return null
        return QuoteSnapshot(bookId, section, selStart, selEnd, t, placeOf(selStart))
    }

    /** 인용 (tap, or a palette pick): the selection becomes a quote in [style]; no success toast. */
    private fun quoteNow(style: Int) {
        val snap = snapshot()
        clear()
        if (snap != null) saveQuote(snap, "", style)
    }

    /** Long-press 인용 / ⋮ "색 골라 인용…": the palette at the 인용 cell; a pick quotes in that style and remembers it. */
    private fun pickStyleThenQuote() {
        val anchor = quoteCell ?: return
        if (!active) return
        hideMenus()
        palette = QuotePalette.show(anchor, LastQuoteStyle.get()) { s ->
            palette = null
            LastQuoteStyle.set(s)
            if (active) quoteNow(s)
        }
    }

    /**
     * Saves [q] in [style]. The page shows the new quote at once — in the same main-thread message as the [clear]
     * that came before, so quoting costs ONE e-ink update: the "quotes" list gets a copy of [QuoteCache] plus the new
     * one, in database order, and the insert + reload then find the page decor unchanged and draw nothing. A failed
     * insert takes it away again (one update) with "저장하지 못했습니다"
     * and then calls [onFailed] (the note prompt opens again with the typed text).
     */
    private fun saveQuote(q: QuoteSnapshot, note: String, style: Int, onFailed: (() -> Unit)? = null) {
        val before = QuoteCache.get(q.bookId)
        val pending = Quote(id = Long.MAX_VALUE, bookId = q.bookId, section = q.section, start = q.start, end = q.end,
            text = q.text, note = note, createdAt = 0L, style = style)
        // Without the book's list (its load failed or is still running) the reload paints instead: an optimistic list
        // of the new quote alone would hide the section's other quotes until then.
        if (before != null && sameBook(q.bookId)) applyQuoteHighlights(q.section, QuoteHighlights.withAdded(before, pending))
        scope.launch {
            var added: Quote? = null
            val all = withContext(Dispatchers.IO) {
                added = runCatching { Library.addQuote(q.bookId, q.section, q.start, q.end, q.text, note, style, q.place) }.getOrNull()
                if (added == null) null else runCatching { Library.quotes(q.bookId) }.getOrNull()
            }
            val saved = added
            if (saved == null) {
                if (before != null && sameBook(q.bookId)) applyQuoteHighlights(q.section, QuoteCache.get(q.bookId) ?: before ?: emptyList())
                ctx.toast("저장하지 못했습니다")
                if (onFailed != null && !ctx.isFinishing && !ctx.isDestroyed) onFailed()
                return@launch
            }
            // The reload failed: the cache still gets the row the insert returned, but only on top of a list it knew.
            // Without one, a cache of the new quote alone would hide the older ones: leave it unset (the next press
            // loads it again) and draw nothing here; the quote is saved all the same.
            val list = all ?: (QuoteCache.get(q.bookId) ?: before)?.let { QuoteHighlights.withAdded(it, saved.copy(style = style)) }
            if (list == null) {
                quotesRequested = false
                return@launch
            }
            QuoteCache.put(q.bookId, list)
            // Only repaint when the same book is still open (the quote itself is saved either way).
            if (sameBook(q.bookId)) applyQuoteHighlights(q.section, list)
        }
    }

    /**
     * A palette-row tap over an existing quote: the page and the ring change at once (one partial update; the popup
     * stays open), then the style is saved on IO; a failed save puts the old style back with a message. Remembered as
     * the last style. No confirm: recolouring is harmless and reversible.
     */
    private fun recolour(style: Int) {
        val q = editingQuote ?: return
        if (QuoteStyles.of(style) == QuoteStyles.of(q.style)) return
        val bookId = q.bookId
        val gen = ++recolourGen
        LastQuoteStyle.set(style)
        val updated = q.copy(style = style)
        editingQuote = updated
        paletteRow?.check(style)
        val before = QuoteCache.get(bookId)
        if (before != null) {
            val next = QuoteHighlights.withStyle(before, q.id, style)
            QuoteCache.put(bookId, next)
            if (sameBook(bookId)) applyQuoteHighlights(q.section, next)
        }
        scope.launch {
            val all = withContext(Dispatchers.IO) {
                runCatching {
                    Library.updateQuoteStyle(q.id, style)
                    Library.quotes(bookId)
                }.getOrNull()
            }
            // A later tap owns the page, the cache and the ring now; its own reload settles them.
            if (gen != recolourGen) return@launch
            if (all == null) {
                val cur = QuoteCache.get(bookId)
                if (cur != null && cur.firstOrNull { it.id == q.id }?.style == style) {
                    val back = QuoteHighlights.withStyle(cur, q.id, q.style)
                    QuoteCache.put(bookId, back)
                    if (sameBook(bookId)) applyQuoteHighlights(q.section, back)
                }
                if (editingQuote?.id == q.id) {
                    editingQuote = editingQuote?.copy(style = q.style)
                    paletteRow?.check(q.style)
                }
                ctx.toast("저장하지 못했습니다")
                return@launch
            }
            QuoteCache.put(bookId, all)
            if (sameBook(bookId)) applyQuoteHighlights(q.section, all)
            all.firstOrNull { it.id == q.id }?.let { fresh -> if (editingQuote?.id == q.id) editingQuote = fresh }
        }
    }

    private fun sameBook(bookId: Long): Boolean = runCatching { host.book.id }.getOrNull() == bookId

    /** The session's parse signature at [section] (NotePlaceHost), null without places. */
    private fun sessionSig(section: Int): String? =
        (host as? NotePlaceHost)?.let { h -> runCatching { h.notePlace(DocPosition(section, 0)) }.getOrNull()?.sig }

    /** The section's "quotes" highlights from [all] (with their styles), as the reader builds them. Main thread. */
    private fun applyQuoteHighlights(section: Int, all: List<Quote>) {
        val sig = sessionSig(section)
        runCatching { host.setHighlights("quotes", section, QuoteHighlights.forSection(all, section, sig) { ContentsDialog.anchorMatch(host, it) }) }
    }

    private fun noteThenQuote() {
        // Snapshot now: TTS or a relayout may turn the page (and clear the selection) while the user types.
        val snap = snapshot()
        if (snap == null) {
            clear()
            return
        }
        hideActions()
        promptQuoteNote(snap, "")
    }

    /** The memo prompt of a new quote; a failed save opens it again with the typed note (once per submit, no duplicates). */
    private fun promptQuoteNote(snap: QuoteSnapshot, initial: String) {
        var submitted = false
        ctx.multilinePrompt("형광펜 메모", initial, "메모", minLines = 3) { note ->
            if (submitted) return@multilinePrompt
            submitted = true
            if (active && section == snap.section && selStart == snap.start && selEnd == snap.end) clear()
            saveQuote(snap, note.trim(), LastQuoteStyle.get()) { promptQuoteNote(snap, note) }
        }
    }

    /** The memo editor of [q]; a failed save says so and opens again with the typed text (never lost silently). */
    private fun editQuoteNote(q: Quote, initial: String = q.note, fromRetry: Boolean = false) {
        // A retry comes after the user went on: a selection made meanwhile stays.
        if (!fromRetry) clear()
        ctx.multilinePrompt("형광펜 메모", initial, "메모", minLines = 3) { note ->
            scope.launch {
                val (ok, all) = withContext(Dispatchers.IO) {
                    runCatching { Library.updateQuoteNote(q.id, note.trim()) }.isSuccess to
                        runCatching { Library.quotes(q.bookId) }.getOrNull()
                }
                if (all != null) QuoteCache.put(q.bookId, all)
                if (!ok && !ctx.isFinishing && !ctx.isDestroyed) {
                    ctx.toast("저장하지 못했습니다")
                    editQuoteNote(q, note, fromRetry = true)
                }
            }
        }
    }

    private fun deleteQuote(q: Quote) {
        clear()
        ctx.confirm("형광펜 삭제", "이 형광펜을 삭제할까요?", "삭제") {
            scope.launch {
                val (ok, all) = withContext(Dispatchers.IO) {
                    runCatching { Library.deleteQuote(q.id) }.isSuccess to
                        runCatching { Library.quotes(q.bookId) }.getOrNull()
                }
                if (!ok && !ctx.isFinishing && !ctx.isDestroyed) ctx.toast("삭제하지 못했습니다")
                if (all != null) {
                    QuoteCache.put(q.bookId, all)
                    if (sameBook(q.bookId)) applyQuoteHighlights(q.section, all)
                }
            }
        }
    }

    /**
     * Makes sure [QuoteCache] holds this book's quotes: the reader fills it with the rows it loads when the book opens
     * (A12-4: one quote query per open), so the controller queries only when it is still empty (e.g. that load
     * failed), once, off the main thread. Edits made through this module keep the cache current afterwards.
     */
    private fun ensureQuotes() {
        if (quotesRequested) return
        val bookId = runCatching { host.book.id }.getOrNull() ?: return
        quotesRequested = true
        if (QuoteCache.get(bookId) != null) return
        scope.launch {
            val list = withContext(Dispatchers.IO) { runCatching { Library.quotes(bookId) }.getOrNull() }
            if (list != null) QuoteCache.put(bookId, list) else quotesRequested = false
        }
    }

    private companion object {
        /** How long an edge turn may take to show before the dwell starts over (a section or picture being loaded). */
        const val EDGE_TURN_WAIT_MS = 1500L
        const val DEFAULT_LONG_PRESS_MS = 500
        const val MIN_LONG_PRESS_MS = 200
        const val MAX_LONG_PRESS_MS = 2000
        /** Longest phrase quoted in the "이 문구 지우기" dialog (the rule holds all of it). */
        const val PHRASE_SHOWN = 40
        /** The icon area of a popup cell (fits the 인용 cell's swatch and its "▾"). */
        const val ICON_BOX_DP = 30
        const val LABEL_SP = 13f
    }
}

/**
 * Selection handle: a teardrop in the page's text colour ([PagePalette]: black, or white on the inverted page) whose
 * sharp corner ([anchorX], [anchorY], parent coordinates) touches the selection's start (bottom-left) or end
 * (bottom-right) corner, at the bottom of the letters ([HandleAnchor]). The view is larger than the drawing (touch
 * target).
 */
@SuppressLint("ViewConstructor")
internal class HandleView(context: Context, val start: Boolean) : View(context) {
    val sizePx = context.dp(44)
    private val r = context.dpF(10f)
    private val ax = if (start) sizePx / 2f + r / 2f else sizePx / 2f - r / 2f
    private val ay = context.dpF(1f)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ink.BLACK; style = Paint.Style.FILL }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ink.WHITE; style = Paint.Style.STROKE; strokeWidth = context.dpF(1.5f) }
    private val path = Path()

    var anchorX = 0f
        private set
    var anchorY = 0f
        private set
    var lineHalf = 0f
        private set

    init {
        isClickable = true
        val cx = if (start) ax - r else ax + r
        val cy = ay + r
        path.addCircle(cx, cy, r, Path.Direction.CW)
        if (start) path.addRect(cx, ay, ax, cy, Path.Direction.CW) else path.addRect(ax, ay, cx, cy, Path.Direction.CW)
    }

    /** Places the handle so its corner sits at ([x], [y]) in parent coordinates; [halfLine] is the drag lift. */
    fun place(x: Float, y: Float, halfLine: Float) {
        anchorX = x
        anchorY = y
        lineHalf = halfLine
        translationX = x - ax - left
        translationY = y - ay - top
    }

    override fun onDraw(canvas: Canvas) {
        // Page colours: black handles on the white page, white handles (black outline) on the inverted page, light
        // grey ones (dark grey outline) on the 마루뷰어 page.
        val palette = runCatching { PagePalette.of(Settings.reader) }.getOrDefault(PagePalette.PAPER)
        paint.color = HandleColors.fill(palette)
        outline.color = HandleColors.outline(palette)
        canvas.drawPath(path, outline)
        canvas.drawPath(path, paint)
    }
}

/**
 * Where a selection handle sits on a [LineGeometry.rangeRects] rect (pure; unit-tested): the bottom of the glyph
 * band of the text line the rect was cut from (the band PageRenderer fills, kept inside the line box), lifted by half
 * the band while dragging so the hit point stays on that line. An image rect (its box is the picture) keeps the box.
 */
internal object HandleAnchor {
    /** The text line of [page] that [r] was cut from; null for an image (or when no line matches). */
    fun lineOf(page: PageInfo, r: RectPx): LineInfo? {
        val lines = page.lines
        for (i in lines.indices) {
            val ln = lines[i]
            if (ln.top != r.top || ln.bottom != r.bottom) continue
            if (ln.imageBlock != null) return null
            if (!ln.isRule && ln.end > ln.start) return ln
        }
        return null
    }

    /** Anchor y (content-box px): the glyph band bottom of a text line, the box bottom of an image. */
    fun bottom(layout: SectionLayout, page: PageInfo, r: RectPx): Float {
        val ln = lineOf(page, r) ?: return r.bottom
        return LineGeometry.bandBottom(layout, ln)
    }

    /** How far a dragged handle lifts its hit point above the anchor: half the glyph band (half the box for an image). */
    fun halfHeight(layout: SectionLayout, page: PageInfo, r: RectPx): Float {
        val ln = lineOf(page, r) ?: return (r.bottom - r.top) / 2f
        return (LineGeometry.bandBottom(layout, ln) - LineGeometry.bandTop(layout, ln)) / 2f
    }
}

/** Selection handle colours for the page's colour scheme (pure; unit-tested). */
internal object HandleColors {
    /** Fill: the page's text colour (what PageRenderer draws the text in). */
    fun fill(palette: PagePalette): Int = palette.text

    /** Outline: the page's background colour, so the handle stands out over text. */
    fun outline(palette: PagePalette): Int = palette.background
}

/**
 * Lets the selection popup reach the TtsController of the same reader (constructed by ReaderActivity).
 * Values are weak too: a TtsController references its host, so a strong value would keep the WeakHashMap key
 * (the activity) reachable forever if release() were ever skipped.
 */
internal object TtsRegistry {
    private val map = java.util.WeakHashMap<ReaderHost, java.lang.ref.WeakReference<TtsController>>()

    fun put(host: ReaderHost, c: TtsController) {
        map[host] = java.lang.ref.WeakReference(c)
    }

    fun remove(host: ReaderHost, c: TtsController) {
        if (map[host]?.get() === c) map.remove(host)
    }

    fun get(host: ReaderHost): TtsController? = map[host]?.get()
}
