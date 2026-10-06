package com.ggumtak.readeraplus.reader

import android.graphics.Canvas
import android.os.SystemClock
import android.view.ViewConfiguration
import android.widget.OverScroller
import com.ggumtak.readeraplus.engine.PageInfo
import com.ggumtak.readeraplus.engine.SectionLayout
import com.ggumtak.readeraplus.render.DeviceClass
import com.ggumtak.readeraplus.render.Highlight
import com.ggumtak.readeraplus.render.PageDecor
import com.ggumtak.readeraplus.render.PageRenderer
import com.ggumtak.readeraplus.settings.ScrollStyle
import com.ggumtak.readeraplus.settings.Settings
import kotlin.math.abs
import kotlin.math.round
import kotlin.math.roundToInt

internal enum class Motion { STEP, SMOOTH }

/**
 * Created only for scroll mode. Page commands (taps, keys, jumps) replace the viewport immediately on every device. A
 * SMOOTH drag follows the finger and a fast release keeps going with the platform's fling deceleration (like any
 * Android list; the user asked for it on 2026-10-04), one settle at its end; a touch stops it where it is.
 */
internal class ScrollReader(private val view: PageView, private val host: Host) : PageView.ScrollInput, StripSource {
    interface Host {
        fun session(): BookSession?; fun renderer(): PageRenderer?; fun geometry(): PageGeometry?
        fun decor(): PageDecor; fun highlights(section: Int): List<Highlight>; fun unitGap(section: Int): Float
        fun onTopPageChanged(section: Int, page: Int); fun onSettled(kind: SettleKind, movedPx: Float)
        fun onBlocked(section: Int)
    }
    private var currentMotion = Motion.STEP
    private var pendingMotion: Motion? = null
    var motion: Motion
        get() = currentMotion
        set(value) { if (session == null) { currentMotion = value; navigation.aligned = value == Motion.STEP }
            else pendingMotion = value }
    private var session: BookSession? = null
    private var generation: BookSession.Generation? = null
    private var renderer: PageRenderer? = null
    private var geometry: PageGeometry? = null
    private var decor = PageDecor()
    private var shownCount = 0
    private var frozen = false
    private var detached = false
    private var ink = true
    private var direction = 1
    private var top = -1L
    private var focus = -1
    /** A finger is down in a drag (from the drag's start to its release / cancel), also in STEP where nothing moves. */
    private var held = false
    private var virtual: VirtualPage? = null
    private var virtualDirty = true
    private var window = ScrollWindow()
    private var spare = ScrollWindow()
    private val sections = IntArray(8) { -1 }
    private val layouts = arrayOfNulls<SectionLayout>(8)
    private val gaps = FloatArray(8)
    private val quoteSections = IntArray(8) { -1 }
    private val quoteIndexes = arrayOfNulls<ScrollHighlights>(8)
    private var quoteNext = 0
    private var protectedFrom = -1
    private var protectedTo = -1
    private var blocked = -1
    private var deferredBlock = -1
    private var workPosted = false
    private var afterPosted = false
    private var drew = false
    private var probeStarted = false
    private var prefetchDirty = true
    private var imagesDirty = true
    private var frameVersion = 0L
    private var requestedVersion = -1L
    private var lastInvalidate = 0L
    private val imagePos = ScrollPos()
    private val imageLayouts = arrayOfNulls<SectionLayout>(ScrollMath.MAX_STRIPS * 3)
    private val imagePages = IntArray(imageLayouts.size)
    private var imageCount = 0
    /** The last frame drew a placeholder for a visible image (S §1.8: only that is redrawn when a decode lands). */
    private var lastMissing = false
    private val imageDone = Runnable { if (!frozen && !detached && lastMissing) view.invalidate() }
    private val work = Runnable {
        workPosted = false
        if (!frozen && !detached) navigation.continueWork()
    }
    private val afterDraw = Runnable {
        afterPosted = false
        if (!frozen && !detached && drew) {
            if (deferredBlock >= 0) { val s = deferredBlock; deferredBlock = -1; block(s) }
            if (!probeStarted) {
                probeStarted = true
                DeviceClass.probeAsync(view.context) { if (!detached) onDeviceClass() }
            }
            if (prefetchDirty) { prefetchDirty = false; prefetchLayouts() }
            if (imagesDirty) { imagesDirty = false; requestImages() }
        }
    }
    /**
     * SMOOTH momentum after a fast release: the platform deceleration, fed to the navigation as drag frames. Made at
     * the first fling (nothing new on the first-page path).
     */
    private val scroller by lazy(LazyThreadSafetyMode.NONE) { OverScroller(view.context) }
    /** A fling is moving the text (the drag stays open in the navigation until it ends). */
    var flinging = false
        private set
    private var flingY = 0
    private val flingTick = Runnable { stepFling() }
    private val navigation: ScrollNavigation = ScrollNavigation(this, object : ScrollNavigation.Events {
        override fun changed() { rebuildWindow(); updateTop(); invalidate(false) }
        override fun settled(kind: SettleKind, distance: Float) {
            focus = -1; virtual = null; virtualDirty = true
            updateTop()
            host.onSettled(kind, distance)
            decor = host.decor()
            prefetchDirty = true; imagesDirty = true
            protect(); invalidate(true)
        }
        override fun blocked(section: Int) { block(section) }
        override fun later() {
            protect(navigation.cursor.section)
            if (!workPosted && !frozen && !detached) { workPosted = true; view.post(work) }
        }
    })
    val pos: ScrollPos get() = navigation.pos
    override val sectionCount: Int get() = shownCount
    private val height: Float get() = navigation.height
    /**
     * The last move was a tap / key step (user, 2026-10-06: "탭을 눌렀을 때도 … 글씨가 잘리는 일은 없도록"): until the next
     * drag or fling the text ends at its last whole line, as in [Motion.STEP]; a drag shows the cut line again.
     */
    private var stepped = false
    private val clip: Float get() =
        if ((currentMotion == Motion.STEP || stepped) && window.wholeBottom > 0f) minOf(height, window.wholeBottom) else height
    override val live: Boolean get() = currentMotion == Motion.SMOOTH
    /** On e-ink a drag starts only past the tap slop, so a slightly moving tap still turns the page. */
    override val fineDrag: Boolean get() = live && !ink

    override fun layoutOf(section: Int): SectionLayout? {
        for (i in sections.indices) if (sections[i] == section) return layouts[i]
        window.layoutOf(section)?.let { return it }
        if (frozen || detached || section !in 0 until shownCount) return null
        val l = session?.peek(section) ?: return null
        remember(section, l)
        return l
    }
    override fun unitGap(section: Int): Float {
        for (i in sections.indices) if (sections[i] == section) return gaps[i]
        for (i in 0 until window.count) if (window.sections[i] == section && window.pages[i] == 0)
            return maxOf(0f, window.gaps[i] - (window.layouts[i]?.pages?.getOrNull(0)?.lead ?: 0f))
        return if (frozen || detached) 0f else host.unitGap(section)
    }
    private fun remember(section: Int, layout: SectionLayout) {
        var at = 0; var far = -1
        for (i in sections.indices) {
            if (sections[i] == section || sections[i] < 0) { at = i; break }
            val d = Math.abs(sections[i] - navigation.cursor.section)
            if (d > far) { far = d; at = i }
        }
        if (sections[at] != section || layouts[at] !== layout)
            gaps[at] = if (section == 0) 0f else maxOf(0f, host.unitGap(section))
        sections[at] = section; layouts[at] = layout
    }
    private fun refreshSlots() {
        val s = session ?: return
        for (i in sections.indices) if (sections[i] >= 0) {
            val l = s.peek(sections[i])
            if (l == null) { sections[i] = -1; layouts[i] = null; gaps[i] = 0f }
            else if (layouts[i] !== l) { layouts[i] = l; gaps[i] = host.unitGap(sections[i]) }
        }
        for (i in quoteSections.indices) if (quoteSections[i] >= 0 && s.peek(quoteSections[i]) == null) clearQuotes(i)
    }
    private fun clearQuotes(i: Int) { quoteSections[i] = -1; quoteIndexes[i] = null }
    private fun pageQuotes(section: Int, layout: SectionLayout, page: Int): List<Highlight> {
        var at = -1
        for (i in quoteSections.indices) if (quoteSections[i] == section) { at = i; break }
        if (at < 0) {
            at = quoteNext; quoteNext = (quoteNext + 1) % quoteSections.size
            quoteSections[at] = section
            val source = host.highlights(section)
            quoteIndexes[at] = if (source.isEmpty()) null else ScrollHighlights(source)
        }
        val p = layout.pages[page]
        return quoteIndexes[at]?.page(p.start, p.end) ?: emptyList()
    }
    private fun rebuildWindow() {
        val ct = geometry?.contentTop?.toFloat() ?: 0f
        spare.fill(this, pos, height, ct)
        var different = spare.count != window.count
        for (i in 0 until spare.count) {
            val l = spare.layouts[i]!!; val s = spare.sections[i]; val p = spare.pages[i]
            var old = -1
            for (j in 0 until window.count) if (window.sections[j] == s && window.pages[j] == p && window.layouts[j] === l) {
                old = j; break
            }
            if (old < 0) different = true
            spare.quotes[i] = if (old >= 0) window.quotes[old] else pageQuotes(s, l, p)
        }
        val old = window; window = spare; spare = old; spare.clear()
        protect()
        if (different) { frameVersion++; prefetchDirty = true; imagesDirty = true }
        if (window.blockedAt >= 0) block(window.blockedAt)
    }
    private fun protect(extra: Int = -1) {
        var first = if (window.first >= 0) window.first else pos.section
        var last = if (window.last >= 0) window.last else pos.section
        if (extra >= 0) { first = minOf(first, extra); last = maxOf(last, extra) }
        if (blocked >= 0) { first = minOf(first, blocked); last = maxOf(last, blocked) }
        if (first != protectedFrom || last != protectedTo) {
            protectedFrom = first; protectedTo = last; session?.touch(first, last)
        }
    }
    private fun block(section: Int) {
        if (section !in 0 until shownCount || frozen || detached) return
        if (!drew) { deferredBlock = section; return }
        if (blocked == section) return
        blocked = section
        protect(section) // The newly stored section must not evict itself when all four old entries are visible.
        host.onBlocked(section)
    }
    private fun updateTop() {
        val value = navigation.topPage
        if (top != value) {
            top = value; host.onTopPageChanged((value ushr 32).toInt(), value.toInt()); decor = host.decor()
        }
    }
    private fun invalidate(final: Boolean) {
        val now = SystemClock.uptimeMillis()
        if (final || !ink || now - lastInvalidate >= LIVE_INK_MS) { lastInvalidate = now; view.invalidate() }
    }
    fun showAt(section: Int, layout: SectionLayout, offset: Int, placement: Placement, kind: SettleKind): Long {
        stopMotion()
        val s = host.session() ?: return top
        val g = host.geometry() ?: return top
        val r = host.renderer() ?: return top
        session = s; generation = s.generation; shownCount = s.sectionCount
        geometry = g; renderer = r; decor = host.decor(); navigation.height = g.contentHeight.toFloat()
        frozen = false; detached = false; drew = false; blocked = -1; deferredBlock = -1
        protectedFrom = -1; protectedTo = -1; top = -1L
        for (i in sections.indices) { sections[i] = -1; layouts[i] = null }
        for (i in quoteSections.indices) clearQuotes(i)
        window.clear(); spare.clear(); remember(section, layout)
        navigation.place(section, layout, offset, placement, kind)
        if (window.count == 0) rebuildWindow()
        return top
    }
    fun step(next: Boolean): Step {
        if (frozen || detached || session == null) return Step.EDGE
        // A key or wheel step during a fling: the text stops where it is (one settle), then moves one screen from there.
        if (flinging) stopMotion()
        direction = if (next) 1 else -1
        stepped = true
        return navigation.step(next)
    }
    fun onSectionStored(section: Int, layout: SectionLayout) {
        if (frozen || detached || session?.generation !== generation) return
        refreshSlots()
        if (session?.peek(section) === layout) remember(section, layout)
        if (blocked == section) blocked = -1
        if (deferredBlock == section) deferredBlock = -1
        if (navigation.pending) {
            if (!workPosted) { workPosted = true; view.post(work) }
        } else {
            // S §1.8: only a frame that changed (a visible or blocked section) redraws; an off-window neighbour
            // prefetch (s ± 1 after the first page and after every settle) is not an e-ink update.
            val v = frameVersion
            rebuildWindow(); virtualDirty = true
            if (frameVersion != v) invalidate(true)
        }
    }
    fun onGenerationChanged() {
        stopMotion(); frozen = true
        view.removeCallbacks(afterDraw); afterPosted = false
        // The old geometry, renderer, decor and visible layouts remain one consistent frozen frame.
    }
    fun onHighlightsChanged(section: Int) {
        if (frozen || detached) return
        for (i in quoteSections.indices) if (quoteSections[i] == section) clearQuotes(i)
        for (i in 0 until window.count) if (window.sections[i] == section)
            window.quotes[i] = pageQuotes(section, window.layouts[i]!!, window.pages[i])
        onDecorChanged()
    }
    fun onDecorChanged() {
        if (!frozen && !detached) { renderer = host.renderer() ?: renderer; decor = host.decor(); invalidate(true) }
    }
    fun onTrimMemory() {
        if (frozen) return
        for (i in sections.indices) if (sections[i] < window.first || sections[i] > window.last) {
            sections[i] = -1; layouts[i] = null; gaps[i] = 0f
        }
        for (i in quoteSections.indices) if (quoteSections[i] < window.first || quoteSections[i] > window.last) clearQuotes(i)
        spare.clear(); imageLayouts.fill(null); imagesDirty = true; requestedVersion = -1L
    }
    fun anchor(): Long = navigation.anchor
    fun topPage(): Long = navigation.topPage
    fun atBookEnd(): Boolean = ScrollMath.atBookEnd(this, pos, height)
    fun virtualPage(): VirtualPage? {
        if (userMoving()) return virtual
        if (virtualDirty) {
            val s = if (focus >= 0) focus else (anchor() ushr 32).toInt()
            virtual = window.virtualPage(s, geometry?.contentTop?.toFloat() ?: 0f, clip); virtualDirty = false
        }
        return virtual
    }
    fun focusAt(y: Float): Boolean {
        if (userMoving() || frozen) return false
        val ct = geometry?.contentTop?.toFloat() ?: return false
        val section = window.focusAt(y - ct, ct, clip)
        if (section < 0) return false
        if (focus != section) { focus = section; virtualDirty = true }
        return virtualPage() != null
    }
    fun clearFocus() { if (focus >= 0) { focus = -1; virtualDirty = true } }
    /**
     * S §1.10: TTS reads a line wholly on screen in [section] (e.g. below a seam): that section is the virtual page
     * until the next settle. No redraw. False while the user moves, or when [section] is not on screen.
     */
    fun focusSection(section: Int): Boolean {
        if (userMoving() || frozen) return false
        var inWindow = false
        for (i in 0 until window.count) if (window.sections[i] == section) inWindow = true
        if (!inWindow) return false
        if (focus != section) { focus = section; virtualDirty = true }
        return true
    }
    fun lineWhollyVisible(section: Int, offset: Int): Boolean =
        window.whollyVisible(section, offset, geometry?.contentTop?.toFloat() ?: 0f, clip)
    fun visibleRanges(visit: (section: Int, start: Int, end: Int) -> Unit) {
        val ct = geometry?.contentTop?.toFloat() ?: return
        var s = -1; var start = 0; var end = 0
        for (i in 0 until window.count) {
            val l = window.layouts[i] ?: continue
            val shift = window.shift(i, ct)
            for (ln in l.pages[window.pages[i]].lines) {
                if (shift + ln.bottom <= 0f || shift + ln.top >= clip) continue
                if (s != window.sections[i]) {
                    if (s >= 0) visit(s, start, end)
                    s = window.sections[i]; start = ln.start
                }
                end = ln.end
            }
        }
        if (s >= 0) visit(s, start, end)
    }
    /** A step waits for its section: further steps are the host's to queue (S §1.10 turn / flushTurns). */
    val pending: Boolean get() = navigation.pending
    fun userMoving(): Boolean = navigation.moving || held || flinging
    fun detach() {
        stopMotion(); detached = true
        view.removeCallbacks(afterDraw); afterPosted = false
        window.clear(); spare.clear(); virtual = null
        layouts.fill(null); sections.fill(-1); imageLayouts.fill(null)
        for (i in quoteSections.indices) clearQuotes(i)
        session = null; generation = null; renderer = null; geometry = null
        if (view.scroll === this) view.scroll = null
    }
    fun onDeviceClass() {
        ink = DeviceClass.cached(view.context) ?: true
        motion = if (ScrollWiring.stepMotion(Settings.app.scrollStyle, ink)) Motion.STEP else Motion.SMOOTH
    }
    override fun onDown() {
        pendingMotion?.let {
            currentMotion = it; navigation.aligned = it == Motion.STEP; pendingMotion = null
        }
    }
    override fun isMoving(): Boolean = userMoving()
    override fun stopMotion(): Boolean {
        held = false
        view.removeCallbacks(work); workPosted = false
        val wasFlinging = flinging
        stopFling()
        return navigation.cancel() || wasFlinging
    }
    override fun beginDrag() { held = true }
    override fun dragBy(dy: Float) {
        if (!frozen && !detached && live) { stepped = false; direction = if (dy >= 0f) 1 else -1; navigation.drag(dy) }
    }
    override fun release(totalDy: Float, velocityY: Float) {
        held = false
        if (frozen || detached) return
        val min = ViewConfiguration.get(view.context).scaledMinimumFlingVelocity.toFloat()
        // E-ink: only a clear flick keeps going (a slow release just stops, without an extra 80 ms frame).
        val flingMin = if (ink) min * INK_FLING_FACTOR else min
        if (live && velocityY.isFinite() && abs(velocityY) >= flingMin) startFling(velocityY)
        else navigation.release(totalDy, velocityY, min)
    }
    /** Momentum from [velocity] px/s (positive = forward); the drag stays open until [stepFling] ends it. */
    private fun startFling(velocity: Float) {
        stopFling()
        scroller.fling(0, 0, 0, velocity.roundToInt(), 0, 0, Int.MIN_VALUE / 2, Int.MAX_VALUE / 2)
        flingY = 0
        flinging = true
        postFling()
    }
    /** Phones: every display frame; e-ink: the live-frame pace of a drag ([LIVE_INK_MS]). */
    private fun postFling() {
        if (ink) view.postDelayed(flingTick, LIVE_INK_MS) else view.postOnAnimation(flingTick)
    }
    private fun stepFling() {
        if (!flinging) return
        if (frozen || detached) { stopFling(); return }
        val running = scroller.computeScrollOffset()
        val y = scroller.currY
        val d = (y - flingY).toFloat()
        flingY = y
        if (d != 0f) {
            direction = if (d >= 0f) 1 else -1
            navigation.drag(d)
            // The book's end, or a section still loading: the fling stops there (loading never resumes it).
            if (navigation.lastMove == 0f && !navigation.pending) { endFling(); return }
        }
        if (running && !scroller.isFinished) postFling() else endFling()
    }
    private fun endFling() {
        stopFling()
        navigation.endFling()
    }
    private fun stopFling() {
        if (!flinging) return
        flinging = false
        scroller.forceFinished(true)
        view.removeCallbacks(flingTick)
    }
    override fun cancelDrag() { held = false; if (live) stopMotion() }
    override fun a11yStep(next: Boolean): Boolean {
        if (frozen || detached || session == null) return false
        view.accessibilityStep(next); return true
    }
    override fun computeScroll() { /* The fling runs on its own frame callbacks ([stepFling]). */ }
    override fun draw(canvas: Canvas, width: Int, height: Int): Boolean {
        val r = renderer ?: return false
        val g = geometry ?: return false
        val cl = g.contentLeft.toFloat(); val ct = g.contentTop.toFloat(); val cw = g.contentWidth.toFloat()
        r.drawChrome(canvas, decor, cl, ct, cw, width, height)
        val bottom = ct + clip
        val save = canvas.save(); canvas.clipRect(0f, ct, width.toFloat(), bottom)
        var missing = false
        for (i in 0 until window.count) {
            val l = window.layouts[i] ?: continue
            missing = r.drawBody(canvas, l, window.pages[i], cl,
                round(ct + window.tops[i] + window.gaps[i]), ct, bottom, window.quotes[i]) || missing
        }
        lastMissing = missing
        canvas.restoreToCount(save)
        r.drawOverlay(canvas, decor, cl, ct, cw, width)
        if (!frozen && !detached) {
            drew = true
            if (missing && requestedVersion != frameVersion) imagesDirty = true
            if ((prefetchDirty || imagesDirty || !probeStarted || deferredBlock >= 0) && !afterPosted) {
                afterPosted = true; view.post(afterDraw)
            }
        }
        return true
    }
    private fun prefetchLayouts() {
        val s = session ?: return
        if (window.first < 0 || navigation.pending || blocked >= 0) return
        var room = (BookSession.MAX_CACHED - (window.last - window.first + 1)).coerceAtLeast(0)
        for (side in 0..1) {
            val sign = if (side == 0) direction else -direction
            var section = if (sign > 0) window.last + 1 else window.first - 1
            while (room > 0 && section in 0 until shownCount) { s.prefetch(section); room--; section += sign }
        }
    }
    private fun addImage(l: SectionLayout, page: Int) {
        for (i in 0 until imageCount) if (imageLayouts[i] === l && imagePages[i] == page) return
        if (imageCount < imageLayouts.size) { imageLayouts[imageCount] = l; imagePages[imageCount++] = page }
    }
    private fun requestImages() {
        val r = renderer ?: return
        imageCount = 0; requestedVersion = frameVersion
        for (i in 0 until window.count) window.layouts[i]?.let { addImage(it, window.pages[i]) }
        for (side in 0..1) {
            imagePos.set(pos)
            ScrollMath.scrollBy(this, imagePos, if (side == 0) -height else height, height)
            ScrollMath.forEachVisible(this, imagePos, height) { _, l, p, _, _ -> addImage(l, p) }
        }
        // Once per visible-page change/settle, not once per missing-image frame. LatestTaskRunner batches it.
        r.prefetchPages(imageLayouts, imagePages, imageCount, imageDone)
        imageLayouts.fill(null)
    }
    companion object {
        /** E-ink live frames (a drag, a fling): at most one redraw per this many ms. */
        const val LIVE_INK_MS = 80L
        /** E-ink flings need this many times the platform's minimum fling velocity. */
        const val INK_FLING_FACTOR = 4f
    }
}
