package com.ggumtak.readeraplus.reader

/**
 * Counts page turns and decides when to flash a full e-ink refresh (pure, unit-tested).
 * [every] = 0 disables the periodic refresh; [onChapter] refreshes when a new chapter is shown.
 */
class EinkCadence(var every: Int = 0, var onChapter: Boolean = false) {
    private var turns = 0

    /** Call once per displayed page turn. Returns true when a full refresh should follow. */
    fun onTurn(chapterChanged: Boolean): Boolean {
        if (onChapter && chapterChanged) {
            turns = 0
            return true
        }
        if (every <= 0) return false
        turns++
        if (turns >= every) {
            turns = 0
            return true
        }
        return false
    }

    /** A manual refresh restarts the count. */
    fun reset() {
        turns = 0
    }
}

enum class SwipeDir { NONE, NEXT, PREV }

/** Meaning of a finished single-finger gesture on the page (no long-press, no brightness drag). */
enum class GestureEnd { NONE, TAP, NEXT, PREV }

/** Pure gesture math used by PageView (unit-tested). */
object Gestures {
    /**
     * Classifies a finished drag. Horizontal (right-to-left = next) when [horizontal] and |dx| dominates;
     * vertical (up = next) when [vertical] and |dy| dominates. Needs more than [minDistPx].
     */
    fun classify(dx: Float, dy: Float, minDistPx: Float, horizontal: Boolean, vertical: Boolean): SwipeDir {
        val ax = Math.abs(dx)
        val ay = Math.abs(dy)
        if (horizontal && ax > minDistPx && ax > ay) return if (dx < 0) SwipeDir.NEXT else SwipeDir.PREV
        if (vertical && ay > minDistPx && ay > ax) return if (dy < 0) SwipeDir.NEXT else SwipeDir.PREV
        return SwipeDir.NONE
    }

    /**
     * Decides what a released gesture means: a swipe when [classify] says so, else a tap when the finger never
     * strayed more than [tapSlopPx] from where it went down ([maxDistPx]) — e-ink users often move a little
     * while tapping, and a slightly sloppy tap must still turn the page — else nothing.
     */
    fun end(
        dx: Float,
        dy: Float,
        maxDistPx: Float,
        tapSlopPx: Float,
        minSwipePx: Float,
        horizontal: Boolean,
        vertical: Boolean,
    ): GestureEnd = when (classify(dx, dy, minSwipePx, horizontal, vertical)) {
        SwipeDir.NEXT -> GestureEnd.NEXT
        SwipeDir.PREV -> GestureEnd.PREV
        SwipeDir.NONE -> if (maxDistPx <= tapSlopPx) GestureEnd.TAP else GestureEnd.NONE
    }

    /** Brightness after dragging [dy] px (up = brighter) from [start]; a drag over 60% of [heightPx] = full range. */
    fun brightness(start: Float, dy: Float, heightPx: Int): Float {
        if (heightPx <= 0) return start.coerceIn(0f, 1f)
        val v = start.coerceIn(0f, 1f) - dy / (heightPx * 0.6f)
        return (Math.round(v * 100f) / 100f).coerceIn(0f, 1f)
    }

    /** True when the gesture starts on the brightness strip at the left edge. */
    fun inBrightnessStrip(x: Float, widthPx: Int): Boolean = widthPx > 0 && x < widthPx * 0.10f
}

/**
 * Saved reading positions are (section, offset) in the coordinates of the parse that produced them. A TXT parse
 * depends on global options (chapter detection, blank lines, replace rules, ...) and the book's encoding, so the
 * reader keeps, per book, the text signature of that parse ([LayoutKeys.textSignature]) and the position's char
 * fraction. When the book is opened under a different signature, the position is found again by that fraction
 * instead of reading stale coordinates (pure, unit-tested).
 */
object TextPositions {
    /** How far the stored char fraction may be from the library's progress before the library wins (it is newer). */
    const val MAX_DRIFT = 0.1f

    fun encode(signature: String, fraction: Float): String =
        signature + "|" + fraction.coerceIn(0f, 1f).let { if (it.isNaN()) 0f else it }

    /** (signature, fraction) or null for a missing / malformed value. */
    fun decode(value: String?): Pair<String, Float>? {
        if (value.isNullOrEmpty()) return null
        val bar = value.lastIndexOf('|')
        if (bar <= 0 || bar == value.length - 1) return null
        val f = value.substring(bar + 1).toFloatOrNull() ?: return null
        if (f.isNaN()) return null
        return value.substring(0, bar) to f.coerceIn(0f, 1f)
    }

    /**
     * Char fraction to reopen at when the saved position belongs to another parse, or null to use the saved
     * (section, offset) as is: no signature for this format ([current] null), nothing recorded yet, same parse, or
     * the very start (the start of any parse). A library progress far from the recorded fraction means the position
     * was changed elsewhere since (reset, restore): then that progress is used.
     */
    fun remapFraction(stored: String?, current: String?, section: Int, offset: Int, libraryProgress: Float): Float? {
        if (current == null) return null
        val s = decode(stored) ?: return null
        if (s.first == current) return null
        if (section <= 0 && offset <= 0) return null
        val p = if (libraryProgress.isNaN()) s.second else libraryProgress.coerceIn(0f, 1f)
        return if (Math.abs(p - s.second) <= MAX_DRIFT) s.second else p
    }
}
