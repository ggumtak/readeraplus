package com.ggumtak.readeraplus.reader

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.ggumtak.readeraplus.R
import com.ggumtak.readeraplus.format.DocPosition
import com.ggumtak.readeraplus.ui.kit.Ink
import com.ggumtak.readeraplus.ui.kit.borderBox
import com.ggumtak.readeraplus.ui.kit.dp
import com.ggumtak.readeraplus.ui.kit.hairline
import com.ggumtak.readeraplus.ui.kit.iconButton
import com.ggumtak.readeraplus.ui.kit.label
import com.ggumtak.readeraplus.ui.kit.pressableBackground

internal interface ReturnHost {                       // implemented by ReaderActivity (READER_A)
    val chromeVisible: Boolean
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
    fun saveReturnMark(text: String?)                 // IO write
    fun onReturnChanged()                             // host: chrome.setPinned(...), updateChipPosition()
}

/**
 * The pin = the book's return point (U §3): the state ([ReturnPoints]), the strip docked in the chrome's bottom bar
 * ([dock]) and the floating chip shown over the page after a remembered jump ([chip]). Both views start as empty
 * `GONE` frames; their contents are built on first use, so opening a book inflates nothing here before the first
 * page. Main thread only. Labels are rebuilt only when a page number changes: [bind] on an unchanged state allocates
 * nothing.
 */
internal class ReturnNav(private val ctx: Context, private val host: ReturnHost) {
    val dock: View = FrameLayout(ctx).apply { visibility = View.GONE }
    val chip: View = FrameLayout(ctx).apply { visibility = View.GONE }
    private val dockFrame = dock as FrameLayout
    private val chipFrame = chip as FrameLayout
    private val state = ReturnPoints()
    val pinned: Boolean get() = state.pinned

    // Dock views (built on the first bind with a non-empty state).
    private var left: TextView? = null
    private var centre: TextView? = null
    private var right: TextView? = null
    // Last bound dock state: -1 / null = nothing bound yet.
    private var leftPage = -1
    private var leftOnMark = false
    private var rightPage = -1
    private var shortForm = false
    private var fitRowW = -1
    private var leftFull = ""
    private var leftLink = ""
    private var rightShownBound = false
    private var leftShownBound = false
    /** reset() ran: a late restore() of the closed book is ignored until the next book shows a page or jumps. */
    private var closed = false
    private var rightFull = ""

    // Chip views (built on the first show).
    private var chipLabel: TextView? = null
    private var chipPage = -1
    private var chipOther = false
    /** PIN_FLOATS only: ✕ hid the floating pin until the next pin or jump. */
    private var pinChipHidden = false

    // Compound drawables, one instance per view (a drawable has one callback); created with their views.
    private var pinIcon: Drawable? = null
    private var leftChevron: Drawable? = null
    private var chipChevronLeft: Drawable? = null
    private var chipChevronRight: Drawable? = null

    /** The pinned mark is on the current page (pin icon state). */
    fun markOnScreen(): Boolean {
        val m = state.mark ?: return false
        return state.pinned && host.isOnCurrentPage(m)
    }

    /** Every remembered jump, called before the jump while [from] (the origin) is still the current page. */
    fun onJump(from: DocPosition) {
        notePage(from)
        val m = state.mark
        closed = false
        state.jumped(from, m != null && host.isOnCurrentPage(m))
        pinChipHidden = false
        host.onReturnChanged()
        refresh()
    }

    fun onManualTurn() {
        if (state.manualTurn()) setChipShown(false)
        refresh()
    }

    /** The chrome's pin: this page becomes the return point (moved here), or, on the pinned page, released. */
    fun onPinPressed() {
        val wasPinned = state.pinned
        val m = state.mark
        state.pin(host.currentPosition(), m != null && host.isOnCurrentPage(m))
        // "other = null if other is on this page" (U §3.2): the page test needs the host.
        val o = state.other
        if (state.pinned && o != null && host.isOnCurrentPage(o)) state.other = null
        pinChipHidden = false
        savePin(wasPinned)
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
        refresh()
    }

    /** The persisted pin (ReturnMarkCodec text), loaded after the first page. */
    fun restore(saved: String?) {
        if (closed) return
        val m = ReturnMarkCodec.decode(saved) ?: return
        if (state.pinned) return
        val sig = host.textSignature()
        val relocate = sig != null && m.sig != sig && (m.pos.section > 0 || m.pos.offset > 0)
        val pos = host.clampPosition(if (relocate) host.locateFraction(m.fraction) else m.pos)
        state.restorePinned(pos)
        // A pin placed by fraction is stored again under this parse, so the next open is exact.
        if (relocate) save(pos)
        host.onReturnChanged()
        refresh()
    }

    /** Before a reparse, with the OLD counts: the pinned mark's char fraction (NaN = no pin). */
    fun markFraction(): Float {
        val m = state.mark ?: return Float.NaN
        return if (state.pinned) host.charProgressOf(m) else Float.NaN
    }

    /** After a reparse: the pin moves by fraction ([exact] = EPUB with the same section count keeps it as is). */
    fun reparsed(fraction: Float, exact: Boolean) {
        val wasPinned = state.pinned
        val m = state.mark
        val p = when {
            !wasPinned || m == null || fraction.isNaN() -> null
            exact -> m
            else -> host.locateFraction(fraction.coerceIn(0f, 1f))
        }
        state.reparsed(p?.let(host::clampPosition))
        savePin(wasPinned)
        host.onReturnChanged()
        refresh()
    }

    /** The book closes: everything is forgotten and both views hide. */
    fun reset() {
        state.clear()
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

    private fun useMark() {
        val m = state.mark ?: return
        val here = host.currentPosition()
        val t = state.useMark(here, host.isOnCurrentPage(m)) ?: return
        notePage(here) // the place left becomes the other place
        jump(t)
    }

    private fun useOther() {
        val m = state.mark
        val here = host.currentPosition()
        val t = state.useOther(here, m != null && host.isOnCurrentPage(m)) ?: return
        notePage(here)
        jump(t)
    }

    /**
     * Jumps to [t] and rebinds the views when its page is already up. A jump that shows its page later leaves the
     * old page up meanwhile: the host binds the chip and the strip with the new page, in its frame (one e-ink update
     * per tap, not the chip vanishing over the old page first).
     */
    private fun jump(t: DocPosition) {
        if (!host.jumpToReturn(t)) return
        host.onReturnChanged()
        refresh()
    }

    /**
     * Asks for [pos]'s page while it is on screen, before a jump: the host remembers the exact page of a place whose
     * section is laid out ([ReturnPageMemo]), so the strip and the chip keep reading it after that section leaves the
     * layout cache (jumps made with the bars hidden bind nothing before the jump).
     */
    private fun notePage(pos: DocPosition) {
        host.globalPageOf(pos)
    }

    private fun clearAll() {
        val wasPinned = state.pinned
        state.clear()
        pinChipHidden = false
        savePin(wasPinned)
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

    /** Stores the pin after a change: the mark when pinned, null when a pin was released. */
    private fun savePin(wasPinned: Boolean) {
        val m = state.mark
        if (state.pinned && m != null) save(m) else if (wasPinned) host.saveReturnMark(null)
    }

    private fun save(pos: DocPosition) {
        host.saveReturnMark(ReturnMarkCodec.encode(pos, host.charProgressOf(pos), host.textSignature()))
    }

    // ------------------------------------------------------------------ dock

    private fun bindDock() {
        val mark = state.mark
        val other = state.other
        if (mark == null && other == null) {
            if (dock.visibility != View.GONE) dock.visibility = View.GONE
            return
        }
        ensureDock()
        val l = left!!
        val r = right!!
        var labelsChanged = false
        if (mark != null) {
            val page = host.globalPageOf(mark)
            val onMark = host.isOnCurrentPage(mark)
            if (page != leftPage || onMark != leftOnMark) {
                if (page != leftPage) {
                    leftFull = "$page$PAGE_WORD"
                    leftLink = "$leftFull$TO"
                }
                leftPage = page
                leftOnMark = onMark
                labelsChanged = true
                l.isClickable = !onMark
                l.setTextColor(if (onMark) Ink.GRAY else Ink.BLACK)
                l.contentDescription = if (onMark) ON_MARK_DESCRIPTION else leftLink
            }
            show(l, true)
        } else {
            show(l, false)
        }
        val rightShown = other != null && !host.isOnCurrentPage(other)
        if (rightShown) {
            val page = host.globalPageOf(other!!)
            if (page != rightPage) {
                rightPage = page
                rightFull = "$page$PAGE_WORD$TO"
                r.contentDescription = rightFull
                labelsChanged = true
            }
        }
        show(r, rightShown)
        val leftShown = mark != null
        if (rightShown != rightShownBound || leftShown != leftShownBound) {
            rightShownBound = rightShown
            leftShownBound = leftShown
            labelsChanged = true
        }
        val rowW = rowWidth()
        if (labelsChanged || rowW != fitRowW) {
            fitRowW = rowW
            shortForm = fitsShort(rowW)
            applyLeftText(l)
            applyRightText(r)
        }
        if (dock.visibility != View.VISIBLE) dock.visibility = View.VISIBLE
    }

    private fun applyLeftText(l: TextView) {
        if (leftPage < 0) return
        val text = when {
            shortForm -> leftPage.toString()
            leftOnMark -> leftFull
            else -> leftLink
        }
        if (l.text.toString() != text) l.text = text
        val start = if (leftOnMark) pinIcon else leftChevron
        if (l.compoundDrawablesRelative[0] !== start) l.setCompoundDrawablesRelative(start, null, null, null)
    }

    private fun applyRightText(r: TextView) {
        if (rightPage < 0) return
        val text = if (shortForm) rightPage.toString() else rightFull
        if (r.text.toString() != text) r.text = text
    }

    /** U §3.4 fit rule: both side labels at their full width (text + paddings + glyph) against the row. */
    private fun fitsShort(rowW: Int): Boolean {
        val l = left!!
        val r = right!!
        val glyph = ctx.dp(18) + ctx.dp(2)
        val lw = if (leftPage < 0 || l.visibility != View.VISIBLE) 0f
        else l.paint.measureText(if (leftOnMark) leftFull else leftLink) + l.paddingStart + l.paddingEnd + glyph
        val rw = if (rightPage < 0 || r.visibility != View.VISIBLE) 0f
        else r.paint.measureText(rightFull) + r.paddingStart + r.paddingEnd + glyph
        val c = centre!!
        val cw = maxOf(c.paint.measureText(CLEAR) + c.paddingStart + c.paddingEnd, ctx.dp(72).toFloat())
        return ChromeMath.stripShort(lw, cw, rw, rowW.toFloat(), ctx.dp(8).toFloat())
    }

    /** The bar's width: the dock's own once laid out, else the window's. */
    private fun rowWidth(): Int {
        val w = dock.width
        if (w > 0) return w
        val root = dock.rootView?.width ?: 0
        return if (root > 0) root else ctx.resources.displayMetrics.widthPixels
    }

    private fun ensureDock() {
        if (left != null) return
        val strip = FrameLayout(ctx)
        val l = stripText().apply {
            setPaddingRelative(ctx.dp(14), 0, ctx.dp(12), 0)
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            setOnClickListener { useMark() }
        }
        strip.addView(l, FrameLayout.LayoutParams(WRAP_CONTENT, MATCH_PARENT, Gravity.START or Gravity.CENTER_VERTICAL))
        val c = stripText().apply {
            text = CLEAR
            gravity = Gravity.CENTER
            minWidth = ctx.dp(72)
            setPaddingRelative(ctx.dp(16), 0, ctx.dp(16), 0)
            setOnClickListener { clearAll() }
        }
        strip.addView(c, FrameLayout.LayoutParams(WRAP_CONTENT, MATCH_PARENT, Gravity.CENTER))
        val r = stripText().apply {
            setPaddingRelative(ctx.dp(12), 0, ctx.dp(14), 0)
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            setCompoundDrawablesRelative(null, null, icon(R.drawable.ic_chevron_right, 18, Ink.BLACK), null)
            setOnClickListener { useOther() }
        }
        strip.addView(r, FrameLayout.LayoutParams(WRAP_CONTENT, MATCH_PARENT, Gravity.END or Gravity.CENTER_VERTICAL))
        val column = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        column.addView(strip, LinearLayout.LayoutParams(MATCH_PARENT, ctx.dp(48)))
        column.addView(View(ctx).apply { setBackgroundColor(Ink.LINE_LIGHT) }, LinearLayout.LayoutParams(MATCH_PARENT, 1).apply {
            marginStart = ctx.dp(20)
            marginEnd = ctx.dp(16)
        })
        dockFrame.addView(column, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        pinIcon = icon(R.drawable.ic_push_pin_fill, 16, Ink.GRAY)
        leftChevron = icon(R.drawable.ic_chevron_left, 18, Ink.BLACK)
        left = l
        centre = c
        right = r
    }

    private fun stripText(): TextView = ctx.label("", 15f, maxLines = 1).apply {
        typeface = Typeface.DEFAULT
        fontFeatureSettings = "tnum"
        compoundDrawablePadding = ctx.dp(2)
        background = pressableBackground()
    }

    // ------------------------------------------------------------------ chip

    /**
     * ★5: the chip shows iff the last remembered jump offers a place ([ReturnPoints.offer]), the chrome is hidden and
     * that place is off screen; with [PIN_FLOATS], also for the pinned mark until ✕.
     */
    private fun updateChip(chromeVisible: Boolean) {
        val other = state.offer == ReturnPoints.Chip.OTHER
        val target: DocPosition? = when (state.offer) {
            ReturnPoints.Chip.MARK -> state.mark
            ReturnPoints.Chip.OTHER -> state.other
            ReturnPoints.Chip.NONE -> if (PIN_FLOATS && state.pinned && !pinChipHidden) state.mark else null
        }
        val visible = target != null && ReturnPoints.chipVisible(
            if (PIN_FLOATS && state.offer == ReturnPoints.Chip.NONE) ReturnPoints.Chip.MARK else state.offer,
            chromeVisible, host.isOnCurrentPage(target),
        )
        if (!visible) {
            setChipShown(false)
            return
        }
        ensureChip()
        val page = host.globalPageOf(target!!)
        if (page != chipPage || other != chipOther) {
            chipPage = page
            chipOther = other
            val lbl = chipLabel!!
            val text = "$page$PAGE_WORD$TO"
            lbl.text = text
            lbl.contentDescription = text
            if (other) lbl.setCompoundDrawablesRelative(null, null, chipChevronRight, null)
            else lbl.setCompoundDrawablesRelative(chipChevronLeft, null, null, null)
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
            background = ctx.borderBox(strokeDp = 0f, radiusDp = 0f)   // 1 physical px (borderBox keeps ≥ 1 px)
            isClickable = true
        }
        val lbl = ctx.label("", 15f, maxLines = 1).apply {
            typeface = Typeface.DEFAULT
            fontFeatureSettings = "tnum"
            gravity = Gravity.CENTER_VERTICAL
            compoundDrawablePadding = ctx.dp(2)
            setPaddingRelative(ctx.dp(12), 0, ctx.dp(14), 0)
            background = pressableBackground()
            setOnClickListener { if (state.offer == ReturnPoints.Chip.OTHER) useOther() else useMark() }
        }
        box.addView(lbl, LinearLayout.LayoutParams(WRAP_CONTENT, ctx.dp(48)))
        box.addView(ctx.hairline(vertical = true))
        val close = ctx.iconButton(R.drawable.ic_close, CLOSE, sizeDp = 48) { closeChip() }
        box.addView(close)
        chipFrame.addView(box, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        chipChevronLeft = icon(R.drawable.ic_chevron_left, 18, Ink.BLACK)
        chipChevronRight = icon(R.drawable.ic_chevron_right, 18, Ink.BLACK)
        chipLabel = lbl
    }

    // ------------------------------------------------------------------ helpers

    private fun show(v: View, shown: Boolean) {
        val vis = if (shown) View.VISIBLE else View.GONE
        if (v.visibility != vis) v.visibility = vis
    }

    private fun icon(res: Int, sizeDp: Int, tint: Int): Drawable {
        val d = ctx.getDrawable(res)!!.mutate()
        val s = ctx.dp(sizeDp)
        d.setBounds(0, 0, s, s)
        d.setTintList(ColorStateList.valueOf(tint))
        return d
    }

    companion object {
        /** U §3.1 / §9 R13: true shows the pinned link on the page whenever the menu is hidden (one constant). */
        const val PIN_FLOATS = false
        /** "3쪽", "‹ 3쪽으로" (style guide 6: the unit after a number is 쪽, attached). */
        private const val PAGE_WORD = "쪽"
        private const val TO = "으로"
        private const val CLEAR = "지우기"
        private const val CLOSE = "닫기"
        private const val ON_MARK_DESCRIPTION = "지금 보는 페이지가 고정한 페이지입니다"
    }
}

/**
 * The return-point state machine (U §3.2, rules ★1–★5). Pure; positions are offsets, so page anchors don't matter.
 * [mark] is the pinned place or a temporary jump origin; [other] the second place; [offer] what the last remembered
 * jump offers back (the chip); [landed] = "only passing through" until the next manual turn, use, pin or clear.
 */
internal class ReturnPoints {
    enum class Chip { NONE, MARK, OTHER }
    var mark: DocPosition? = null; var pinned=false; var other: DocPosition?=null
    var offer=Chip.NONE; var turns=0; var chainOffer=Chip.NONE; var landed=false

    fun pin(here: DocPosition, onMark: Boolean) {
        if (pinned && onMark) {
            clear()
            return
        }
        val m = mark
        if (!pinned && m != null && !onMark) {
            other = m                                   // ★1 the temporary origin stays reachable as the other place
        } else if (other != null && other == here) {
            other = null
        }
        mark = here
        pinned = true
        offer = Chip.NONE
        landed = false
    }

    fun jumped(from: DocPosition, fromOnMark: Boolean) {
        if (landed) {                                   // ★3 a chain keeps its first origin
            offer = chainOffer
            turns = 0
            return
        }
        when {
            !pinned -> { mark = from; other = null; offer = Chip.MARK }
            fromOnMark -> offer = Chip.MARK             // ★4 `other` is kept
            else -> { other = from; offer = Chip.OTHER }
        }
        chainOffer = offer
        turns = 0
        landed = true
    }

    fun useMark(here: DocPosition, onMark: Boolean): DocPosition? {
        val m = mark
        if (m == null || onMark) return null
        other = here
        offer = Chip.NONE
        landed = false
        return m
    }

    fun useOther(here: DocPosition, onMark: Boolean): DocPosition? {
        val t = other ?: return null
        other = if (onMark) null else here
        offer = Chip.NONE
        landed = false
        return t
    }

    fun clear() {
        mark = null; pinned = false; other = null
        offer = Chip.NONE; turns = 0; chainOffer = Chip.NONE; landed = false
    }

    /** A manual turn; true when the chip must hide now (the second turn since the jump). */
    fun manualTurn(): Boolean {
        landed = false
        if (offer != Chip.NONE && ++turns >= 2) {
            offer = Chip.NONE
            return true
        }
        return false
    }

    /** ✕: the chip hides; the places stay (the strip still has them). */
    fun hideChip() {
        offer = Chip.NONE
    }

    fun restorePinned(pos: DocPosition) {
        if (pinned) return
        if (mark != null) {
            // The temporary origin becomes the other place; an offer of it (a jump made before the load) follows it.
            other = mark
            if (offer == Chip.MARK) offer = Chip.OTHER
            if (chainOffer == Chip.MARK) chainOffer = Chip.OTHER
        }
        mark = pos
        pinned = true
    }

    fun reparsed(p: DocPosition?) {
        other = null
        offer = Chip.NONE
        landed = false
        if (pinned && p != null) {
            mark = p
        } else {
            mark = null
            pinned = false
        }
    }

    companion object {
        /** ★5 The chip's visibility is derived, never stored. */
        fun chipVisible(offer: Chip, chromeVisible: Boolean, targetOnScreen: Boolean): Boolean =
            offer != Chip.NONE && !chromeVisible && !targetOnScreen
    }
}

/**
 * Exact in-section page indexes of the return places, per layout generation ([ReturnHost.globalPageOf]). BookSession
 * keeps only [BookSession.MAX_CACHED] sections laid out; once a place's section has left that cache its index would be a
 * char-proportional estimate, which lands a page short right after a chapter's heading page (CI 29 13g: page 3 =
 * s:1 o:210 read "2쪽으로" after two far seeks). Each place is remembered while its section is laid out and kept
 * until the layout changes. [SLOTS] places, least recently asked replaced first (the strip asks for the mark and the
 * other place on every bind, so the live ones stay). Pure; allocates nothing.
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
        const val SLOTS = 4
    }
}

/** The persisted pin (U §3.3): `"m1|<section>|<offset>|<charFraction>|<textSignature or empty>"`. Pure. */
internal object ReturnMarkCodec {
    class Mark(val pos: DocPosition, val fraction: Float, val sig: String?)
    private const val PREFIX = "m1"

    fun encode(pos: DocPosition, fraction: Float, sig: String?): String {
        val f = if (fraction.isNaN() || fraction.isInfinite()) 0f else fraction.coerceIn(0f, 1f)
        return "$PREFIX|${pos.section.coerceAtLeast(0)}|${pos.offset.coerceAtLeast(0)}|$f|${sig.orEmpty()}"
    }

    /**
     * Tolerant: null for a bad prefix, bad numbers, NaN, or a negative section or offset; the fraction is clamped to
     * 0..1. The signature is everything after the 4th bar (it may contain bars itself); empty = none (EPUB).
     */
    fun decode(text: String?): Mark? {
        if (text == null) return null
        val b1 = text.indexOf('|')
        if (b1 < 0 || text.substring(0, b1) != PREFIX) return null
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
        return Mark(DocPosition(section, offset), fraction.coerceIn(0f, 1f), sig)
    }
}
