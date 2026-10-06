package com.ggumtak.readeraplus.reader.extras

/*
 * Pure parts of a selection that runs over several pages of one section (no android.*; unit-tested). The selection
 * itself is (section, start, end) in the section's text; what is drawn is only its part on the page shown
 * ([SelectionSpan]). Dragging a handle (or the finger of the long press) into the top or bottom edge zone of the
 * text area and holding it there turns a page ([EdgeZone], [EdgeDwell]).
 */

/** How long a handle must rest in an edge zone before the page turns; the next turn waits for that page to show. */
internal const val EDGE_DWELL_MS = 500L

/** Height of the edge zones at the top and bottom of the text area. */
internal const val EDGE_ZONE_DP = 48

/** A selection against the page that is shown. [pageStart] until [pageEnd] is the page's text, end excluded. */
internal object SelectionSpan {
    /** First selected char on the page (the selection's own start when that is on it). */
    fun start(selStart: Int, pageStart: Int): Int = maxOf(selStart, pageStart)

    /** End of the selected part on the page. */
    fun end(selEnd: Int, pageEnd: Int): Int = minOf(selEnd, pageEnd)

    /** Some selected char is on the page. */
    fun visible(selStart: Int, selEnd: Int, pageStart: Int, pageEnd: Int): Boolean =
        pageEnd > pageStart && end(selEnd, pageEnd) > start(selStart, pageStart)

    /** The start handle belongs on the page: the selection begins here, not on an earlier page. */
    fun startShown(selStart: Int, selEnd: Int, pageStart: Int, pageEnd: Int): Boolean =
        visible(selStart, selEnd, pageStart, pageEnd) && selStart >= pageStart

    /** The end handle belongs on the page: the selection ends here, not on a later page. */
    fun endShown(selStart: Int, selEnd: Int, pageStart: Int, pageEnd: Int): Boolean =
        visible(selStart, selEnd, pageStart, pageEnd) && selEnd <= pageEnd

    /**
     * A page [next] / back exists inside the section: the text goes on after the page (or begins before it). At a
     * section's first or last page the extension ends (the next section is another selection's ground).
     */
    fun canTurn(next: Boolean, pageStart: Int, pageEnd: Int, textLength: Int): Boolean =
        if (next) pageEnd < textLength else pageStart > 0
}

/** The two checks around a dwell's turn that need no android: where the finger counts as dragging, and what landed. */
internal object EdgeGuard {
    /**
     * The long press's finger has started to drag: it moved more than [slop] from where the press was made, or the
     * selection already grew past the word picked ([grew]). A finger that only rests (a long press near an edge)
     * never arms the dwell.
     */
    fun pressDragged(pressX: Float, pressY: Float, x: Float, y: Float, slop: Float, grew: Boolean): Boolean =
        grew || kotlin.math.hypot(x - pressX, y - pressY) > slop

    /**
     * The page shown after an edge turn is the one asked for: it starts after the page the turn left when the turn
     * was forward, before it when backward. Any other page change (a turn from elsewhere, a relayout) is not it.
     */
    fun turnLanded(next: Boolean, startBefore: Int, startNow: Int): Boolean =
        if (next) startNow > startBefore else startNow < startBefore
}

/** Which edge of the text area a held point is in. */
internal enum class Zone { NONE, TOP, BOTTOM }

internal object EdgeZone {
    /**
     * The zone of height [zonePx] that [y] (view px) is in, for a text area from [top] to [bottom]; a point beyond
     * the area (in a margin) counts. [allowNext] / [allowPrev] switch BOTTOM / TOP on: the end handle only turns
     * forward, the start handle back, the long press's finger both. The zones never take more than a third of the
     * area each, so a very short box keeps a middle.
     */
    fun of(y: Float, top: Float, bottom: Float, zonePx: Float, allowNext: Boolean, allowPrev: Boolean): Zone {
        if (bottom <= top) return Zone.NONE
        val z = minOf(zonePx, (bottom - top) / 3f)
        return when {
            allowNext && y >= bottom - z -> Zone.BOTTOM
            allowPrev && y <= top + z -> Zone.TOP
            else -> Zone.NONE
        }
    }
}

/**
 * The dwell state machine: IDLE → ARMED (a zone is held; the host runs a [EDGE_DWELL_MS] timer) → TURNING (the page
 * turn was asked for; nothing arms until it shows) → IDLE. Time is the host's: it starts the timer on [Action.ARM],
 * stops it on [Action.CANCEL], and calls [fire] when it runs out.
 */
internal class EdgeDwell {
    enum class Action { NONE, ARM, KEEP, CANCEL }

    private enum class State { IDLE, ARMED, TURNING }

    private var state = State.IDLE
    private var zone = Zone.NONE

    val armed: Boolean get() = state == State.ARMED
    val turning: Boolean get() = state == State.TURNING

    /** The held point is in [z] now (NONE = outside both zones, or one that may not turn). */
    fun update(z: Zone): Action = when (state) {
        State.TURNING -> Action.NONE
        State.IDLE -> if (z == Zone.NONE) Action.NONE else arm(z)
        State.ARMED -> when {
            z == Zone.NONE -> {
                state = State.IDLE
                zone = Zone.NONE
                Action.CANCEL
            }
            z == zone -> Action.KEEP
            else -> arm(z)
        }
    }

    private fun arm(z: Zone): Action {
        state = State.ARMED
        zone = z
        return Action.ARM
    }

    /** The timer ran out: the zone to turn toward, or null when nothing was armed (a stale timer). */
    fun fire(): Zone? {
        if (state != State.ARMED) return null
        state = State.TURNING
        return zone
    }

    /** The turned page is shown (or the turn failed / timed out): arming is possible again. True if one was pending. */
    fun landed(): Boolean {
        val was = state == State.TURNING
        if (was) {
            state = State.IDLE
            zone = Zone.NONE
        }
        return was
    }

    /**
     * The pointer went up: an armed dwell is dropped, but a turn already asked for stays outstanding (its page may
     * still show, and keeps the selection). True when one stays.
     */
    fun release(): Boolean {
        if (state == State.ARMED) {
            state = State.IDLE
            zone = Zone.NONE
        }
        return state == State.TURNING
    }

    /** A dialog, a boundary, disposal, a new touch: back to idle whatever the state. */
    fun cancel() {
        state = State.IDLE
        zone = Zone.NONE
    }
}
