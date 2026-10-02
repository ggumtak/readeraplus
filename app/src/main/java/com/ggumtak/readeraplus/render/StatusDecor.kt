package com.ggumtak.readeraplus.render

/** A status slot keeps fixed numeric buffers and title references; updates allocate nothing. */
class StatusSlot {
    @JvmField val chars = CharArray(CAPACITY)
    @JvmField var length = 0
    @JvmField var text: String? = null
    @JvmField var battery = -1
    @JvmField val batteryChars = CharArray(3)
    @JvmField var batteryLength = 0
    val isEmpty: Boolean get() = length == 0 && text == null && battery < 0

    fun set(src: CharArray, n: Int, battery: Int): Boolean {
        val count = n.coerceIn(0, minOf(src.size, CAPACITY))
        val level = if (battery < 0) -1 else battery.coerceAtMost(100)
        var changed = text != null || length != count || this.battery != level
        for (i in 0 until count) if (chars[i] != src[i]) changed = true
        src.copyInto(chars, 0, 0, count)
        length = count; text = null; this.battery = level
        batteryLength = when { level < 0 -> 0; level < 10 -> 1; level < 100 -> 2; else -> 3 }
        var value = level
        for (i in batteryLength - 1 downTo 0) { batteryChars[i] = ('0'.code + value % 10).toChar(); value /= 10 }
        return changed
    }

    fun setText(t: String?): Boolean {
        val title = t?.takeIf { it.isNotEmpty() }
        val changed = text != title || length != 0 || battery >= 0
        text = title; length = 0; battery = -1; batteryLength = 0
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
class StatusDecor {
    @JvmField val header = StatusBand()
    @JvmField val footer = StatusBand()
    @JvmField var lane = false
    @JvmField var progress = -1f
    @JvmField var version = 0
}
