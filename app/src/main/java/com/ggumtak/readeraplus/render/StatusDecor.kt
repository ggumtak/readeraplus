package com.ggumtak.readeraplus.render

/** A status slot keeps fixed numeric buffers and title references; updates allocate nothing. */
class StatusSlot {
    @JvmField val chars = CharArray(CAPACITY)
    @JvmField var length = 0
    @JvmField var text: String? = null
    @JvmField var battery = -1
    @JvmField val batteryChars = CharArray(3)
    @JvmField var batteryLength = 0
    /**
     * The battery icon comes first and has no number (MaruViewer's corner, `StatusItem.CLOCK_BATTERY`); [battery] then
     * holds the level in its bars' steps (`BatteryMath.stepLevel`), so only a step changes the slot.
     */
    @JvmField var batteryFirst = false
    /** The phone is charging: a lightning bolt in the battery icon. */
    @JvmField var charging = false
    /** A [text] too wide for its slot is shortened at its start, keeping the end (`StatusItem.keepsEnd`). */
    @JvmField var keepEnd = false
    val isEmpty: Boolean get() = length == 0 && text == null && battery < 0

    fun set(src: CharArray, n: Int, battery: Int, batteryFirst: Boolean = false, charging: Boolean = false): Boolean {
        val count = n.coerceIn(0, minOf(src.size, CAPACITY))
        val level = if (battery < 0) -1 else battery.coerceAtMost(100)
        val first = batteryFirst && level >= 0
        val charge = charging && level >= 0
        var changed = text != null || length != count || this.battery != level || this.batteryFirst != first ||
            this.charging != charge
        for (i in 0 until count) if (chars[i] != src[i]) changed = true
        src.copyInto(chars, 0, 0, count)
        length = count; text = null; this.battery = level; this.batteryFirst = first; this.charging = charge; keepEnd = false
        batteryLength = when { level < 0 || first -> 0; level < 10 -> 1; level < 100 -> 2; else -> 3 }
        var value = level
        for (i in batteryLength - 1 downTo 0) { batteryChars[i] = ('0'.code + value % 10).toChar(); value /= 10 }
        return changed
    }

    fun setText(t: String?, keepEnd: Boolean = false): Boolean {
        val title = t?.takeIf { it.isNotEmpty() }
        val end = keepEnd && title != null
        val changed = text != title || length != 0 || battery >= 0 || this.keepEnd != end
        text = title; length = 0; battery = -1; batteryLength = 0; batteryFirst = false; charging = false; this.keepEnd = end
        return changed
    }

    fun clear(): Boolean = setText(null)
    companion object { const val CAPACITY = 48 }
}
class StatusBand {
    @JvmField val left = StatusSlot()
    @JvmField val center = StatusSlot()
    @JvmField val right = StatusSlot()
    val isEmpty: Boolean get() = left.isEmpty && center.isEmpty && right.isEmpty
}
/**
 * A display's rounded top corner on one side of the page view, in px (`WindowInsets.getRoundedCorner`, API 31+, made
 * relative to the view: `InsetSplit.pageCorners`): what the header's side inset clears (`StatusFit.sideInset`).
 */
class StatusCorner {
    /** The corner's radius; 0 = a square corner (none reported), < 0 = unknown (before API 31). */
    @JvmField var radius = -1
    /** How far the corner's centre lies inside the page view's side (its left or right edge). */
    @JvmField var centreIn = 0
    /** How far the corner's centre lies below the page view's top. */
    @JvmField var centreY = 0

    fun set(radius: Int, centreIn: Int, centreY: Int): Boolean {
        val changed = this.radius != radius || this.centreIn != centreIn || this.centreY != centreY
        this.radius = radius; this.centreIn = centreIn; this.centreY = centreY
        return changed
    }
}
class StatusDecor {
    @JvmField val header = StatusBand()
    @JvmField val footer = StatusBand()
    /**
     * Px of the page view's top above the header's band: a display cutout's (`PageGeometry.cutoutTop`), else 0. The
     * header is drawn inside it, at the very top as MaruViewer draws it (`StatusFit.headerBaseline`); its band stays
     * reserved below it.
     */
    @JvmField var top = 0
    /** The display's rounded top-left and top-right corners: the header's side insets (`StatusFit.sideInset`). */
    @JvmField val cornerLeft = StatusCorner()
    @JvmField val cornerRight = StatusCorner()
    @JvmField var lane = false
    @JvmField var progress = -1f
    @JvmField var version = 0
}
