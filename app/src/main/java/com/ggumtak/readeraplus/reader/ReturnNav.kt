package com.ggumtak.readeraplus.reader

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import com.ggumtak.readeraplus.R
import com.ggumtak.readeraplus.data.BookPrefs
import com.ggumtak.readeraplus.format.DocPosition
import com.ggumtak.readeraplus.render.ChromePalette
import com.ggumtak.readeraplus.render.PagePalette
import com.ggumtak.readeraplus.ui.kit.dp
import com.ggumtak.readeraplus.ui.kit.iconButton
import com.ggumtak.readeraplus.ui.kit.label
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

internal interface ReturnHost {                       // implemented by ReaderActivity (READER_A)
    val chromeVisible: Boolean
    /** A jump is on its way (a layout or an image preload): the page on screen is about to change. */
    val navigating: Boolean
    fun currentPosition(): DocPosition                // paged: page start; scroll: top line
    fun isOnCurrentPage(pos: DocPosition): Boolean    // paged: on the page; scroll: in the visible range
    fun globalPageOf(pos: DocPosition): Int           // 1-based; estimate until counted (never "~")
    /**
     * Jump without creating a return point; the chrome stays as it is. Scroll mode: top-line placement. True when
     * the new page is already shown; false when it shows later (a layout or an image preload), with its own bind.
     */
    fun jumpToReturn(pos: DocPosition): Boolean
    fun charProgressOf(pos: DocPosition): Float       // counts.charProgress
    fun clampPosition(pos: DocPosition): DocPosition = pos
    fun locateFraction(f: Float): DocPosition         // counts.locateFraction
    fun textSignature(): String?                      // LayoutKeys.textSignature(...) for TXT, null for EPUB
    fun saveReturnMark(text: String?)                 // IO write of the history (ReturnHistoryCodec text; null clears)
    fun onReturnChanged()                             // host: chrome.setPinned(...), updateChipPosition()
}

/**
 * The book's return history (U §3), as ReadEra's: the state ([ReturnHistory], browser-style back and forward lists),
 * the history row docked in the chrome's bottom bar right above its panel ([dock]: on the page colour, three equal
 * columns — "‹ N쪽으로" (back), 지우기, "M쪽으로 ›" (forward) — a side without a place INVISIBLE, so 지우기 never moves)
 * and the floating chip shown over the page after a remembered jump ([chip]). The pin of the bottom bar saves this page
 * on the back list. Both views start as empty `GONE` frames; their contents are built on first use, so opening a book
 * inflates nothing here before the first page. Colours follow the chrome's look ([setLook]). Main thread only. Labels
 * are rebuilt only when a page number changes: [bind] on an unchanged state allocates nothing. A tap on the row or the
 * chip while a jump is on its way is ignored, and a use whose page could not be shown is undone ([onJumpFailed]).
 */
internal class ReturnNav(private val ctx: Context, private val host: ReturnHost) {
    val dock: View = FrameLayout(ctx).apply { visibility = View.GONE }
    val chip: View = FrameLayout(ctx).apply { visibility = View.GONE }
    private val dockFrame = dock as FrameLayout
    private val chipFrame = chip as FrameLayout
    private val state = ReturnHistory()
    /** The history before the last use whose page shows later; put back by [onJumpFailed] while [undo]. */
    private val before = ReturnHistory()
    private var undo = false
    /** "Is on the current page", made once: the row asks it on every page shown. */
    private val onScreen: (DocPosition) -> Boolean = { host.isOnCurrentPage(it) }

    /** The chrome's colours ([ChromePalette]): the dock on the page colour, the chip on the surface. */
    private var look = ChromePalette.DEFAULT

    /**
     * The stored history was applied ([restore]) or given up by 지우기: from then on every change is stored. A change
     * made before (a jump while the stored text is still being read) waits ([dirty]) and is stored with it, so it
     * never overwrites the stored history with this session's places alone.
     */
    private var loaded = false
    private var dirty = false

    // Dock views (built on the first bind with a place to offer).
    private var dockRow: LinearLayout? = null
    private var left: TextView? = null
    private var centre: TextView? = null
    private var right: TextView? = null
    // Last bound dock state: -1 = nothing bound yet.
    private var leftPage = -1
    private var rightPage = -1
    private var shortForm = false
    private var fitRowW = -1
    private var leftFull = ""
    private var rightFull = ""
    private var rightShownBound = false
    private var leftShownBound = false
    /** reset() ran: a late restore() of the closed book is ignored until the next book shows a page or jumps. */
    private var closed = false

    // Chip views (built on the first show).
    private var chipBox: LinearLayout? = null
    private var chipLine: View? = null
    private var chipClose: ImageButton? = null
    private var chipLabel: TextView? = null
    private var chipPage = -1
    /** PIN_FLOATS only: ✕ hid the floating link until the next pin or jump. */
    private var pinChipHidden = false

    // Compound drawables, one instance per view (a drawable has one callback); created with their views.
    private var leftChevron: Drawable? = null
    private var rightChevron: Drawable? = null
    private var chipChevron: Drawable? = null

    /**
     * The chrome's colours for [page] on a device of class [eink] (as `ReaderChrome.setLook`). Views built so far are
     * recoloured at once (the dock is only drawn with the chrome up; the chip only shows over a page that is being
     * redrawn for the same change); views built later take the look themselves.
     */
    fun setLook(page: PagePalette, eink: Boolean?) {
        val k = ChromePalette.of(page, eink)
        if (k === look) return
        look = k
        if (left != null) paintDock()
        if (chipLabel != null) paintChip()
    }

    /** The pin icon: filled while this page is the place the pin saved last (a tap then removes it, 고정 해제). */
    fun pinnedHere(): Boolean = state.pinnedHere(onScreen)

    /** Every remembered jump, called before the jump while [from] (the origin) is still the current page. */
    fun onJump(from: DocPosition) {
        notePage(from)
        closed = false
        undo = false
        if (state.jumped(from, onScreen)) save()
        pinChipHidden = false
        // With the menu up, the row waits for the new page's bindChrome: bound now, it would change over the old page
        // (two e-ink updates for one seek when the page shows later). The chip is under the menu, and a jump leaves
        // the pin as it is (its origin is pushed, or it is the top already).
        if (host.chromeVisible) return
        host.onReturnChanged()
        refresh()
    }

    /**
     * A manual turn (two after a jump retire the chip). [deferView]: the turn's page shows later (a picture being
     * decoded, a section laid out); the views then follow in that page's own frame, through the host's [bind].
     */
    fun onManualTurn(deferView: Boolean = false) {
        val retired = state.manualTurn()
        if (deferView) return
        if (retired) setChipShown(false)
        refresh()
    }

    /** The chrome's pin: this page is saved as the newest place to go back to, or, when it already is, released. */
    fun onPinPressed() {
        val here = host.currentPosition()
        notePage(here)
        undo = false
        state.pin(here, onScreen)
        pinChipHidden = false
        save()
        host.onReturnChanged()
        refresh()
    }

    /** Hides the chip VIEW (the offer is kept: closing the menu brings it back) and binds the strip. */
    fun onChromeShown() {
        setChipShown(false)
        bindDock()
    }

    /** Hides the strip with the chrome and shows the chip iff it has an offer whose place is off screen. */
    fun onChromeHidden() {
        updateChip(false)
    }

    /**
     * Binds the views to the current page: the strip while the chrome is visible, otherwise the chip. Cheap and
     * allocation-free when nothing changed, so the host may call it on every page shown.
     */
    fun bind() {
        closed = false
        // A page shown with no jump on its way: a return jump landed (or was replaced), nothing left to undo.
        if (undo && !host.navigating) undo = false
        refresh()
    }

    /**
     * The jump the reader was making could not lay its page out (the old page stays): a use of the row or the chip
     * made for it is undone, so its place is not lost. The host binds the views afterwards.
     */
    fun onJumpFailed() {
        if (!undo) return
        undo = false
        state.copyFrom(before)
        save()
    }

    /**
     * [section] was just laid out (BookSession's onSectionStored): its places note their exact page now, so their
     * labels stay exact once it leaves the layout cache (restored places, places after a relayout). Main thread.
     */
    fun onSectionLaidOut(section: Int) {
        notePlaces(state.back, section)
        notePlaces(state.forward, section)
    }

    /**
     * The persisted history (ReturnHistoryCodec text, or an old single pin), loaded after the first page. Places a
     * TXT re-parse with other options moved are found again by their fraction and stored again under this parse.
     */
    fun restore(saved: String?) {
        if (closed || loaded) return
        loaded = true
        undo = false
        var relocated = false
        val h = ReturnHistoryCodec.decode(saved)
        if (h != null) {
            val sig = host.textSignature()
            val place = { p: ReturnHistoryCodec.Place ->
                val relocate = sig != null && h.sig != sig && (p.pos.section > 0 || p.pos.offset > 0)
                if (relocate) relocated = true
                host.clampPosition(if (relocate) host.locateFraction(p.fraction) else p.pos)
            }
            state.restore(h.back.map(place), h.forward.map(place), h.pinTop)
            notePlaces(state.back, -1)
            notePlaces(state.forward, -1)
        }
        // This session's changes, and places found by fraction (stored again: the next open is exact), go with it.
        if (dirty || relocated) save()
        dirty = false
        host.onReturnChanged()
        refresh()
    }

    /** Before a reparse, with the OLD counts: each place's char fraction, the back list then the forward list. */
    fun fractions(): FloatArray {
        val b = state.back
        val f = state.forward
        return FloatArray(b.size + f.size) { i -> host.charProgressOf(if (i < b.size) b[i] else f[i - b.size]) }
    }

    /**
     * After a reparse: every place moves by its [fractions] entry, as the pin always did ([exact] = EPUB with the same
     * section count keeps them as they are); a place without one is dropped.
     */
    fun reparsed(fractions: FloatArray, exact: Boolean) {
        undo = false
        state.reparsed { i, p ->
            val f = fractions.getOrElse(i) { Float.NaN }
            when {
                exact -> p
                f.isNaN() -> null
                else -> host.locateFraction(f.coerceIn(0f, 1f))
            }?.let(host::clampPosition)
        }
        save()
        host.onReturnChanged()
        refresh()
    }

    /** The book closes: everything is forgotten and both views hide. */
    fun reset() {
        state.clear()
        undo = false
        loaded = false
        dirty = false
        pinChipHidden = false
        setChipShown(false)
        if (dock.visibility != View.GONE) dock.visibility = View.GONE
        leftPage = -1
        rightPage = -1
        chipPage = -1
        rightShownBound = false
        leftShownBound = false
        closed = true
    }

    // ------------------------------------------------------------------ actions

    /** The row's left item: back's newest place off this page; the place left becomes the nearest one forward. */
    private fun useBack() = use { here -> state.goBack(here, onScreen) }

    /** The row's right item: forward's newest place off this page; the place left becomes the newest one back. */
    private fun useForward() = use { here -> state.goForward(here, onScreen) }

    /** The chip: its place (★3 the chain's first origin), as that many ‹ taps would go. */
    private fun useChip() = use { here -> state.chipBack(here, onScreen) }

    /**
     * One use of the history from this page, then the jump. Ignored while a jump is on its way: the views still
     * describe the page on screen, and a second tap would take another place before the first one is reached.
     */
    private inline fun use(step: (DocPosition) -> DocPosition?) {
        if (host.navigating) return
        val here = host.currentPosition()
        notePage(here)
        before.copyFrom(state)
        val t = step(here) ?: return
        save()
        undo = !jump(t)
    }

    /**
     * Jumps to [t] and rebinds the views when its page is already up (true). A jump that shows its page later leaves
     * the old page up meanwhile: the host binds the chip and the strip with the new page, in its frame (one e-ink
     * update per tap, not the chip vanishing over the old page first).
     */
    private fun jump(t: DocPosition): Boolean {
        if (!host.jumpToReturn(t)) return false
        host.onReturnChanged()
        refresh()
        return true
    }

    /**
     * Asks for [pos]'s page while it is on screen, before it becomes a place of the history: the host remembers the
     * exact page of a place whose section is laid out ([ReturnPageMemo]), so the strip and the chip keep reading it
     * after that section leaves the layout cache (jumps made with the bars hidden bind nothing before the jump).
     */
    private fun notePage(pos: DocPosition) {
        host.globalPageOf(pos)
    }

    /** [notePage] for the places of [list] in [section] (-1: all of them). */
    private fun notePlaces(list: List<DocPosition>, section: Int) {
        for (i in list.indices) {
            val p = list[i]
            if (section < 0 || p.section == section) notePage(p)
        }
    }

    /** 지우기: both lists empty, also in storage (a stored history still being read is given up). */
    private fun clearAll() {
        state.clear()
        undo = false
        pinChipHidden = false
        loaded = true
        dirty = false
        save()
        host.onReturnChanged()
        refresh()
    }

    private fun closeChip() {
        state.hideChip()
        pinChipHidden = true
        setChipShown(false)
        host.onReturnChanged()
    }

    private fun refresh() {
        if (host.chromeVisible) {
            setChipShown(false)
            bindDock()
        } else {
            updateChip(false)
        }
    }

    /** Stores both lists (null once they are empty); before the stored history is loaded, only notes the change. */
    private fun save() {
        if (!loaded) {
            dirty = true
            return
        }
        val sig = host.textSignature()
        val text = ReturnHistoryCodec.encode(state.back, state.forward, host::charProgressOf, sig, state.pinTop)
        host.saveReturnMark(text)
    }

    // ------------------------------------------------------------------ dock

    private fun bindDock() {
        val back = state.leftPlace(onScreen)
        val forward = state.rightPlace(onScreen)
        if (back == null && forward == null) {
            if (dock.visibility != View.GONE) dock.visibility = View.GONE
            return
        }
        ensureDock()
        val l = left!!
        val r = right!!
        var labelsChanged = false
        if (back != null) {
            val page = host.globalPageOf(back)
            if (page != leftPage) {
                leftPage = page
                leftFull = if (page > 0) ReturnHistory.label(page) else ReturnHistory.BACK_UNCOUNTED
                l.contentDescription = leftFull
                labelsChanged = true
            }
        }
        show(l, back != null)
        if (forward != null) {
            val page = host.globalPageOf(forward)
            if (page != rightPage) {
                rightPage = page
                rightFull = if (page > 0) ReturnHistory.label(page) else ReturnHistory.FORWARD_UNCOUNTED
                r.contentDescription = rightFull
                labelsChanged = true
            }
        }
        show(r, forward != null)
        val leftShown = back != null
        val rightShown = forward != null
        if (rightShown != rightShownBound || leftShown != leftShownBound) {
            rightShownBound = rightShown
            leftShownBound = leftShown
            labelsChanged = true
        }
        val rowW = rowWidth()
        if (labelsChanged || rowW != fitRowW) {
            fitRowW = rowW
            shortForm = fitsShort(rowW)
            applyText(l, leftPage, leftFull)
            applyText(r, rightPage, rightFull)
        }
        if (dock.visibility != View.VISIBLE) dock.visibility = View.VISIBLE
    }

    /** A side label: "N쪽으로", or "N" in the short form (its content description keeps the full label). */
    private fun applyText(v: TextView, page: Int, full: String) {
        if (page < 0) return
        val text = if (shortForm && page > 0) page.toString() else full
        if (v.text.toString() != text) v.text = text
    }

    /** U §3.4 fit rule: each side label at its full width (text + paddings + glyph) against its third of the row. */
    private fun fitsShort(rowW: Int): Boolean {
        val l = left!!
        val r = right!!
        val glyph = ctx.dp(GLYPH_DP) + ctx.dp(2)
        val lw = if (leftPage < 0 || l.visibility != View.VISIBLE) 0f
        else l.paint.measureText(leftFull) + l.paddingStart + l.paddingEnd + glyph
        val rw = if (rightPage < 0 || r.visibility != View.VISIBLE) 0f
        else r.paint.measureText(rightFull) + r.paddingStart + r.paddingEnd + glyph
        return ChromeMath.stripShort(lw, rw, rowW.toFloat())
    }

    /** The bar's width: the dock's own once laid out, else the window's. */
    private fun rowWidth(): Int {
        val w = dock.width
        if (w > 0) return w
        val root = dock.rootView?.width ?: 0
        return if (root > 0) root else ctx.resources.displayMetrics.widthPixels
    }

    /**
     * The row: three fixed columns (weight 1 each), each holding its label at its own width and the row's full 48 dp
     * height, so a touch target and a pressed rect hug the words (지우기 is never a third of the row). The side glyphs
     * sit on the bars' icon columns (a 16 dp glyph 20 dp from the edge: centred 28 dp in, like ← and ⏮), 지우기 on the
     * page label's axis. The text sits low in the box ([ChromeMath.HISTORY_TEXT_TOP_DP] of top padding): 19 dp above
     * the panel, as ReadEra's; the box, the touch target, keeps its 48 dp.
     */
    private fun ensureDock() {
        if (left != null) return
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val drop = ctx.dp(ChromeMath.HISTORY_TEXT_TOP_DP)
        val l = stripText().apply {
            setPaddingRelative(ctx.dp(20), drop, ctx.dp(4), 0)
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            setOnClickListener { useBack() }
        }
        row.addView(column(l, Gravity.START), LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
        val c = stripText().apply {
            text = CLEAR
            gravity = Gravity.CENTER
            minWidth = ctx.dp(72)
            setPadding(ctx.dp(16), drop, ctx.dp(16), 0)
            setOnClickListener { clearAll() }
        }
        row.addView(column(c, Gravity.CENTER), LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
        val r = stripText().apply {
            setPaddingRelative(ctx.dp(4), drop, ctx.dp(20), 0)
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            setOnClickListener { useForward() }
        }
        row.addView(column(r, Gravity.END), LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
        dockFrame.addView(row, FrameLayout.LayoutParams(MATCH_PARENT, ctx.dp(ChromeMath.HISTORY_ROW_DP)))
        leftChevron = icon(R.drawable.ic_chevron_left, GLYPH_DP)
        rightChevron = icon(R.drawable.ic_chevron_right, GLYPH_DP)
        l.setCompoundDrawablesRelative(leftChevron, null, null, null)
        r.setCompoundDrawablesRelative(null, null, rightChevron, null)
        dockRow = row
        left = l
        centre = c
        right = r
        paintDock()
    }

    /** One third of the row holding [label] at its own width, placed by [gravity] (the column itself never moves). */
    private fun column(label: TextView, gravity: Int): FrameLayout = FrameLayout(ctx).apply {
        addView(label, FrameLayout.LayoutParams(WRAP_CONTENT, MATCH_PARENT, gravity or Gravity.CENTER_VERTICAL))
    }

    /** 14 sp regular, tabular digits: below the title (18 sp bold) and the page label (17 sp bold), U §2.1. */
    private fun stripText(): TextView = ctx.label("", 14f, maxLines = 1).apply {
        typeface = Typeface.DEFAULT
        fontFeatureSettings = "tnum"
        compoundDrawablePadding = ctx.dp(2)
    }

    /** The row on the page colour (on e-ink with a light 1 px line on top, where no shadow sets it off), in [hist]. */
    private fun paintDock() {
        val k = look
        val row = dockRow ?: return
        row.background = if (k.eink) {
            LayerDrawable(arrayOf(ColorDrawable(k.page), ColorDrawable(k.divider))).apply {
                setLayerGravity(1, Gravity.TOP or Gravity.FILL_HORIZONTAL)
                setLayerHeight(1, 1)
            }
        } else {
            ColorDrawable(k.page)
        }
        // The pressed rect starts where the text's padding does, so it stays centred on the words.
        val drop = ctx.dp(ChromeMath.HISTORY_TEXT_TOP_DP)
        for (t in listOf(left!!, centre!!, right!!)) {
            t.setTextColor(k.hist)
            t.background = ctx.chromePressed(k, 8f, drop)
        }
        val ink = ColorStateList.valueOf(k.hist)
        leftChevron?.setTintList(ink)
        rightChevron?.setTintList(ink)
    }

    // ------------------------------------------------------------------ chip

    /**
     * ★5: the chip shows iff the last remembered jump offers its way back ([ReturnHistory.offer]: the chain's first
     * origin, [ReturnHistory.chipPlace]), the chrome is hidden and that place is off screen; with [PIN_FLOATS], also
     * whenever there is a place to go back to, until ✕.
     */
    private fun updateChip(chromeVisible: Boolean) {
        val target = state.chipPlace()
        val offered = state.offer || (PIN_FLOATS && !pinChipHidden)
        if (target == null || !ReturnHistory.chipVisible(offered, chromeVisible, host.isOnCurrentPage(target))) {
            setChipShown(false)
            return
        }
        ensureChip()
        val page = host.globalPageOf(target)
        if (page != chipPage) {
            chipPage = page
            val lbl = chipLabel!!
            val text = if (page > 0) ReturnHistory.label(page) else ReturnHistory.BACK_UNCOUNTED
            lbl.text = text
            lbl.contentDescription = text
        }
        setChipShown(true)
    }

    private fun setChipShown(shown: Boolean) {
        val v = if (shown) View.VISIBLE else View.GONE
        if (chip.visibility != v) chip.visibility = v
    }

    private fun ensureChip() {
        if (chipLabel != null) return
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
        }
        val lbl = ctx.label("", 15f, maxLines = 1).apply {
            typeface = Typeface.DEFAULT
            fontFeatureSettings = "tnum"
            gravity = Gravity.CENTER_VERTICAL
            compoundDrawablePadding = ctx.dp(2)
            setPaddingRelative(ctx.dp(12), 0, ctx.dp(14), 0)
            setOnClickListener { useChip() }
        }
        box.addView(lbl, LinearLayout.LayoutParams(WRAP_CONTENT, ctx.dp(48)))
        val line = View(ctx)
        box.addView(line, LinearLayout.LayoutParams(1, MATCH_PARENT))
        val close = ctx.iconButton(R.drawable.ic_close, CLOSE, sizeDp = 48) { closeChip() }
        box.addView(close)
        chipFrame.addView(box, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        chipChevron = icon(R.drawable.ic_chevron_left, 18)
        lbl.setCompoundDrawablesRelative(chipChevron, null, null, null)
        chipBox = box
        chipLine = line
        chipClose = close
        chipLabel = lbl
        paintChip()
    }

    /**
     * The chip on the bars' surface over the page text: a 1 px box (the edge on e-ink; the track colour on a phone,
     * where the divider would melt into the page), text-coloured.
     */
    private fun paintChip() {
        val k = look
        val stroke = if (k.eink) k.edge else k.track
        chipBox?.background = GradientDrawable().apply {
            setColor(k.surface)
            setStroke(1, stroke)   // 1 physical px
        }
        chipLine?.setBackgroundColor(stroke)
        chipLabel?.let {
            it.setTextColor(k.text)
            it.background = ctx.chromePressed(k, 0f)
        }
        chipClose?.let {
            it.imageTintList = ColorStateList.valueOf(k.text)
            it.background = ctx.chromeIconBackground(k, false)
        }
        chipChevron?.setTintList(ColorStateList.valueOf(k.text))
    }

    // ------------------------------------------------------------------ helpers

    /** A side label of the row: INVISIBLE, not GONE, when it has no place, so 지우기 and the other side stay put. */
    private fun show(v: View, shown: Boolean) {
        val vis = if (shown) View.VISIBLE else View.INVISIBLE
        if (v.visibility != vis) v.visibility = vis
    }

    /** A compound drawable of [sizeDp]; [paintDock] / [paintChip] tint it. */
    private fun icon(res: Int, sizeDp: Int): Drawable {
        val d = ctx.getDrawable(res)!!.mutate()
        val s = ctx.dp(sizeDp)
        d.setBounds(0, 0, s, s)
        return d
    }

    companion object {
        /** U §3.1 / §9 R13: true shows "‹ N쪽으로" on the page whenever the menu is hidden (one constant). */
        const val PIN_FLOATS = false
        private const val CLEAR = "지우기"
        private const val CLOSE = "닫기"
        /** The row's chevrons (the bars' icons are 24 dp: the row reads below the panel). */
        private const val GLYPH_DP = 16
    }
}

/**
 * The return history (U §3.2), browser-style like ReadEra's row "‹ 1 페이지로 | 지우기 | 150 페이지로 ›": [back] holds the
 * places to go back to, the most recent last; [forward] the places gone back from, the nearest last. Both keep [MAX]
 * places (the oldest go first). Every remembered jump pushes its origin on [back] and empties [forward]; going back or
 * forward moves the place left to the other list. The row offers each list's newest place that is not on this page
 * ([leftPlace], [rightPlace]), so a pin or a jump origin on this page never hides an older place. [offer] = the chip
 * offers [chipPlace]; [landed] = a chain of remembered jumps is open (from a jump until the next manual turn, use, pin
 * or clear): ★3 the chip keeps offering the chain's first origin, while the row steps back one place at a time.
 * Pure; positions are offsets, so page anchors don't matter. "The same page" is the host's question: [here] = "is on
 * the current page", and every place pushed is the current one, so a push is skipped when the list's top is [here].
 * The queries the row asks on every page shown allocate nothing.
 */
internal class ReturnHistory {
    private val backList = ArrayList<DocPosition>()
    private val forwardList = ArrayList<DocPosition>()
    val back: List<DocPosition> get() = backList
    val forward: List<DocPosition> get() = forwardList
    var offer = false; var turns = 0; var landed = false
    /** ★3 The index in [back] of the open chain's first origin (the chip's place while [offer]); -1 = none. */
    private var chainBase = -1
    /** back's top was saved by the pin and has not been replaced since: the icon fills on its page ([pinnedHere]). */
    var pinTop = false
        private set

    /**
     * A remembered jump (TOC, search, bookmark, go-to, seek bar, link, note) from [from], still the current page: it
     * becomes back's top (unless the top is on this page already) and the forward list is cut, as in a browser. The
     * first jump since the last manual turn, use, pin or clear starts a chain, whose origin the chip offers until it
     * hides (★3); the chip is offered again (also after ✕) and counts its turns from here. True when a list changed.
     */
    fun jumped(from: DocPosition, here: (DocPosition) -> Boolean): Boolean {
        val changed = push(backList, from, here) || forwardList.isNotEmpty()
        forwardList.clear()
        if (!landed || chainBase < 0 || chainBase >= backList.size) chainBase = backList.size - 1
        landed = true
        offer = true
        turns = 0
        return changed
    }

    /**
     * The left item at [at]: back's newest place not on this page ([leftPlace]), null when there is none. The places
     * above it are on this page (a pin, a jump's origin): they go with [at], which becomes forward's top.
     */
    fun goBack(at: DocPosition, here: (DocPosition) -> Boolean): DocPosition? {
        val i = lastOff(backList, here)
        return if (i < 0) null else backTo(i, at, here)
    }

    /**
     * The chip at [at]: back to [chipPlace] as that many ‹ taps would ([at] and the places passed become the places
     * ahead, the nearest last); null when there is none.
     */
    fun chipBack(at: DocPosition, here: (DocPosition) -> Boolean): DocPosition? {
        val i = chipIndex()
        return if (i < 0) null else backTo(i, at, here)
    }

    /** The right item at [at]: forward's newest place off this page, null when there is none; [at] becomes back's top. */
    fun goForward(at: DocPosition, here: (DocPosition) -> Boolean): DocPosition? {
        val i = lastOff(forwardList, here)
        if (i < 0) return null
        val t = forwardList[i]
        cut(forwardList, i)
        push(backList, at, here)
        endChain()
        return t
    }

    /**
     * The chrome's pin at [at] (this page): saved as back's top, the forward list kept (less its places on this page,
     * which the pin stands for now). A jump's origin already on top here becomes the pin. When the pin is filled
     * ([pinnedHere]), that place is removed instead (고정 해제).
     */
    fun pin(at: DocPosition, here: (DocPosition) -> Boolean) {
        if (pinnedHere(here)) {
            backList.removeAt(backList.size - 1)
            pinTop = false
        } else {
            push(backList, at, here)
            pinTop = true
            while (forwardList.isNotEmpty() && here(forwardList[forwardList.size - 1])) {
                forwardList.removeAt(forwardList.size - 1)
            }
        }
        endChain()
    }

    /** The pin icon's state: back's top was saved by the pin and is on this page. */
    fun pinnedHere(here: (DocPosition) -> Boolean): Boolean =
        pinTop && backList.isNotEmpty() && here(backList[backList.size - 1])

    /** The row's left item: back's newest place not on this page (the row never offers where you are). */
    fun leftPlace(here: (DocPosition) -> Boolean): DocPosition? = backList.getOrNull(lastOff(backList, here))

    /** The row's right item: forward's newest place not on this page. */
    fun rightPlace(here: (DocPosition) -> Boolean): DocPosition? = forwardList.getOrNull(lastOff(forwardList, here))

    /** The row shows (with the menu) iff one of its sides does; 지우기 alone is never shown. */
    fun rowShown(here: (DocPosition) -> Boolean): Boolean = leftPlace(here) != null || rightPlace(here) != null

    /** ★3 The chip's place: while it is offered, the open chain's first origin; otherwise back's top. */
    fun chipPlace(): DocPosition? = backList.getOrNull(chipIndex())

    /** 지우기: both lists empty, the chip hides. */
    fun clear() {
        backList.clear()
        forwardList.clear()
        pinTop = false
        turns = 0
        endChain()
    }

    /** A manual turn; true when the chip must hide now (the second turn since the jump). */
    fun manualTurn(): Boolean {
        landed = false
        if (offer && ++turns >= 2) {
            offer = false
            return true
        }
        return false
    }

    /** ✕: the chip hides; the places stay (the row still has them). */
    fun hideChip() {
        offer = false
    }

    /**
     * The stored lists, loaded after the first page ([storedPin]: the stored back's top is a pin). Places saved or
     * jumped from before they arrived stay on top: the stored back list goes under them, and the stored forward list is
     * kept only while this session has no places yet (a jump made meanwhile would have cut it).
     */
    fun restore(storedBack: List<DocPosition>, storedForward: List<DocPosition>, storedPin: Boolean = false) {
        val session = backList.isNotEmpty() || forwardList.isNotEmpty()
        val sessionBack = backList.size
        // The place this session first jumped from may be the stored top already (reopened there): kept once.
        val dup = storedBack.isNotEmpty() && storedBack.last() == backList.firstOrNull()
        val under = if (dup) storedBack.subList(0, storedBack.size - 1) else storedBack
        backList.addAll(0, under)
        if (chainBase >= 0) chainBase += under.size
        if (sessionBack == 0 || (dup && sessionBack == 1)) pinTop = pinTop || storedPin
        if (!session) forwardList.addAll(storedForward)
        trimBack()
        trim(forwardList)
    }

    /**
     * After a re-parse: each place goes where [map] puts it, by its index in back then forward order (null drops it);
     * a place that lands on the one before it is dropped too. The chip's offer goes, as the chain does; the pin stays
     * while its place does.
     */
    fun reparsed(map: (Int, DocPosition) -> DocPosition?) {
        val n = backList.size
        if (!remap(backList, 0, map)) pinTop = false
        remap(forwardList, n, map)
        turns = 0
        endChain()
    }

    /** This history becomes a copy of [o] (a use is undone when its page could not be shown). */
    fun copyFrom(o: ReturnHistory) {
        backList.clear()
        backList.addAll(o.backList)
        forwardList.clear()
        forwardList.addAll(o.forwardList)
        offer = o.offer; turns = o.turns; landed = o.landed; chainBase = o.chainBase; pinTop = o.pinTop
    }

    private fun chipIndex(): Int =
        if (offer && chainBase >= 0 && chainBase < backList.size) chainBase else backList.size - 1

    /** Back to back's place [i]: [at] and the places above it (less those on this page) go ahead, the nearest last. */
    private fun backTo(i: Int, at: DocPosition, here: (DocPosition) -> Boolean): DocPosition {
        val t = backList[i]
        push(forwardList, at, here)
        for (k in backList.size - 1 downTo i + 1) {
            val p = backList[k]
            if (!here(p) && forwardList.lastOrNull() != p) forwardList.add(p)
        }
        trim(forwardList)
        cut(backList, i)
        pinTop = false
        endChain()
        return t
    }

    private fun endChain() {
        offer = false
        landed = false
        chainBase = -1
    }

    /** Pushes [p] (the current place) unless the list's top is on this page already; true when it did. */
    private fun push(list: ArrayList<DocPosition>, p: DocPosition, here: (DocPosition) -> Boolean): Boolean {
        if (list.isNotEmpty() && here(list[list.size - 1])) return false
        list.add(p)
        if (list === backList) {
            pinTop = false
            trimBack()
        } else {
            trim(list)
        }
        return true
    }

    /** The oldest go first; the chain's origin, once gone, is its oldest place still kept. */
    private fun trimBack() {
        while (backList.size > MAX) {
            backList.removeAt(0)
            if (chainBase > 0) chainBase--
        }
    }

    /** True when the list's last place was kept (mapped, possibly onto the one before it). */
    private fun remap(list: ArrayList<DocPosition>, base: Int, map: (Int, DocPosition) -> DocPosition?): Boolean {
        val old = ArrayList(list)
        list.clear()
        var lastKept = false
        for (i in old.indices) {
            val q = map(base + i, old[i])
            lastKept = q != null
            if (q != null && list.lastOrNull() != q) list.add(q)
        }
        return lastKept
    }

    companion object {
        /** Places kept per list: a browser-like stack, small enough to store in one `book_prefs` value. */
        const val MAX = 20

        /** ★5 The chip's visibility is derived, never stored. */
        fun chipVisible(offer: Boolean, chromeVisible: Boolean, targetOnScreen: Boolean): Boolean =
            offer && !chromeVisible && !targetOnScreen

        /** "3쪽으로" (the row and the chip add the chevron; style guide 6: the unit after a number is 쪽, attached). */
        /** The left label and the chip while the pages are counted (no estimated page number). */
        const val BACK_UNCOUNTED = "돌아가기"
        /** The right label while the pages are counted. */
        const val FORWARD_UNCOUNTED = "앞으로"

        fun label(page: Int): String = "${page}쪽으로"

        /** The index of the list's newest place off this page, -1 when there is none (no iterator: asked per page). */
        private fun lastOff(list: List<DocPosition>, here: (DocPosition) -> Boolean): Int {
            var i = list.size - 1
            while (i >= 0 && here(list[i])) i--
            return i
        }

        /** Drops the list's places from [i] on. */
        private fun cut(list: ArrayList<DocPosition>, i: Int) {
            while (list.size > i) list.removeAt(list.size - 1)
        }

        private fun trim(list: ArrayList<DocPosition>) {
            while (list.size > MAX) list.removeAt(0)
        }
    }
}

/**
 * Exact in-section page indexes of the return places, per layout generation ([ReturnHost.globalPageOf]). BookSession
 * keeps only [BookSession.MAX_CACHED] sections laid out; once a place's section has left that cache its index would be
 * a char-proportional estimate, which lands a page short right after a chapter's heading page (CI 29 13g: page 3 =
 * s:1 o:210 read "2쪽으로" after two far seeks). Each place is remembered while its section is laid out (on screen
 * before it becomes a place, when restored, and whenever its section is laid out again: [ReturnNav.onSectionLaidOut])
 * and kept until the layout changes. [SLOTS] covers every place of both lists plus the page being left; the least
 * recently asked is replaced first. Pure; allocates nothing (a linear scan of [SLOTS]).
 */
internal class ReturnPageMemo {
    private val sec = IntArray(SLOTS) { -1 }
    private val off = IntArray(SLOTS)
    private val idx = IntArray(SLOTS)
    private val used = IntArray(SLOTS)
    private var clock = 0
    private var gen: Any? = null

    /**
     * The page index of ([section], [offset]) inside its section in [generation]: [exact] when it is known (>= 0, the
     * section is laid out), which is remembered; else the remembered index; -1 when there is none (estimate it).
     */
    fun resolve(generation: Any?, section: Int, offset: Int, exact: Int): Int {
        if (gen !== generation) {
            sec.fill(-1)
            used.fill(0)
            clock = 0
            gen = generation
        }
        var slot = -1
        for (i in 0 until SLOTS) if (sec[i] == section && off[i] == offset) { slot = i; break }
        if (exact < 0 && slot < 0) return -1
        if (slot < 0) {
            slot = 0 // a free slot, else the least recently asked
            for (i in 0 until SLOTS) {
                if (sec[i] < 0) { slot = i; break }
                if (used[i] < used[slot]) slot = i
            }
            sec[slot] = section
            off[slot] = offset
        }
        if (exact >= 0) idx[slot] = exact
        used[slot] = ++clock
        return idx[slot]
    }

    companion object {
        const val SLOTS = 2 * ReturnHistory.MAX + 2
    }
}

/**
 * The history's writes ([ReturnHost.saveReturnMark]), one at a time in the order they were made: [ReaderIo] is a pool,
 * where a quick "‹" then "›" could store the older text last. Process-wide, so a write outlives the activity.
 */
internal object ReturnWrites {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    fun launch(block: () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (t: Throwable) {
                Log.w("ReturnWrites", "history write failed", t)
            }
        }
    }
}

/**
 * The persisted history (U §3.3), the text of `book_prefs.return_mark`: `"h1|<back>|<forward>|<textSignature or
 * empty>"`, each list oldest first (back: the most recent last; forward: the nearest last) as `;`-joined places
 * `<section>,<offset>,<char fraction in millionths>`; back's top ends in `,p` when the pin saved it. The fraction
 * places a TXT entry again after a re-parse with other options. An old single pin, `"m1|<section>|<offset>|<charFraction>|
 * <textSignature or empty>"`, reads as a back list of that one pinned place. Pure.
 */
internal object ReturnHistoryCodec {
    class Place(val pos: DocPosition, val fraction: Float, val pin: Boolean = false)
    class Saved(val back: List<Place>, val forward: List<Place>, val sig: String?) {
        /** Back's top was saved by the pin (ReturnHistory.pinTop). */
        val pinTop: Boolean get() = back.lastOrNull()?.pin == true
    }

    private const val PREFIX = "h1"
    private const val OLD_PREFIX = "m1"
    private const val PPM = 1_000_000
    /** The longest value `book_prefs.return_mark` takes; the oldest places are left out until the text fits. */
    const val MAX_CHARS = BookPrefs.MAX_RETURN_MARK

    /**
     * The text of both lists ([fraction] = the char progress of a place; [pinTop] marks back's top as the pin's), or
     * null when both are empty (clears it).
     */
    fun encode(
        back: List<DocPosition>,
        forward: List<DocPosition>,
        fraction: (DocPosition) -> Float,
        sig: String?,
        pinTop: Boolean = false,
    ): String? {
        val b = back.mapTo(ArrayList()) { place(it, fraction(it)) }
        if (pinTop && b.isNotEmpty()) b[b.size - 1] += ",p"
        val f = forward.mapTo(ArrayList()) { place(it, fraction(it)) }
        val fixed = PREFIX.length + 3 + sig.orEmpty().length
        while (b.isNotEmpty() || f.isNotEmpty()) {
            if (fixed + b.sumOf { it.length + 1 } + f.sumOf { it.length + 1 } <= MAX_CHARS) break
            if (b.size >= f.size) b.removeAt(0) else f.removeAt(0)
        }
        if (b.isEmpty() && f.isEmpty()) return null
        return "$PREFIX|${b.joinToString(";")}|${f.joinToString(";")}|${sig.orEmpty()}"
    }

    /**
     * Tolerant: null for null, a bad prefix or shape, or no valid place; a malformed place (bad numbers, a negative
     * section or offset, a 4th field other than the pin's `p`) is skipped; fractions are clamped to 0..1, each list to
     * its newest [ReturnHistory.MAX]. The signature is everything after the 3rd bar (empty = none, EPUB). "m1" text is
     * an old single pin.
     */
    fun decode(text: String?): Saved? {
        if (text == null) return null
        if (text.startsWith("$OLD_PREFIX|")) return decodePin(text)
        if (!text.startsWith("$PREFIX|")) return null
        val b1 = PREFIX.length
        val b2 = text.indexOf('|', b1 + 1)
        if (b2 < 0) return null
        val b3 = text.indexOf('|', b2 + 1)
        if (b3 < 0) return null
        val back = places(text.substring(b1 + 1, b2))
        val forward = places(text.substring(b2 + 1, b3))
        if (back.isEmpty() && forward.isEmpty()) return null
        return Saved(back, forward, text.substring(b3 + 1).takeIf { it.isNotEmpty() })
    }

    private fun place(pos: DocPosition, fraction: Float): String {
        val f = if (fraction.isNaN() || fraction.isInfinite()) 0f else fraction.coerceIn(0f, 1f)
        return "${pos.section.coerceAtLeast(0)},${pos.offset.coerceAtLeast(0)},${(f * PPM).roundToInt()}"
    }

    private fun places(list: String): List<Place> {
        if (list.isEmpty()) return emptyList()
        val out = ArrayList<Place>()
        for (item in list.split(';')) {
            val parts = item.split(',')
            if (parts.size != 3 && !(parts.size == 4 && parts[3] == "p")) continue
            val section = parts[0].toIntOrNull() ?: continue
            val offset = parts[1].toIntOrNull() ?: continue
            val ppm = parts[2].toIntOrNull() ?: continue
            if (section < 0 || offset < 0) continue
            out.add(Place(DocPosition(section, offset), ppm.coerceIn(0, PPM) / PPM.toFloat(), parts.size == 4))
        }
        return if (out.size > ReturnHistory.MAX) out.subList(out.size - ReturnHistory.MAX, out.size).toList() else out
    }

    /**
     * The old single pin (until 2026-10-05): null for bad numbers, NaN, or a negative section or offset; the fraction
     * is clamped to 0..1. The signature is everything after the 4th bar (it may contain bars itself).
     */
    private fun decodePin(text: String): Saved? {
        val b1 = OLD_PREFIX.length
        val b2 = text.indexOf('|', b1 + 1)
        if (b2 < 0) return null
        val b3 = text.indexOf('|', b2 + 1)
        if (b3 < 0) return null
        val b4 = text.indexOf('|', b3 + 1)
        if (b4 < 0) return null
        val section = text.substring(b1 + 1, b2).toIntOrNull() ?: return null
        val offset = text.substring(b2 + 1, b3).toIntOrNull() ?: return null
        val fraction = text.substring(b3 + 1, b4).toFloatOrNull() ?: return null
        if (section < 0 || offset < 0 || fraction.isNaN()) return null
        val sig = text.substring(b4 + 1).takeIf { it.isNotEmpty() }
        val pin = Place(DocPosition(section, offset), fraction.coerceIn(0f, 1f), pin = true)
        return Saved(listOf(pin), emptyList(), sig)
    }
}
