package com.ggumtak.readeraplus.render

/** Width allocation for three slots centred on the body column; fixed numbers are never shortened. */
internal object StatusMath {
    fun allocate(w: Float, gap: Float, nl: Float, nc: Float, nr: Float,
                 el: Boolean, ec: Boolean, er: Boolean, minElastic: Float, out: FloatArray) {
        val width = maxOf(0f, w)
        val g = maxOf(0f, gap)
        var l = maxOf(0f, nl)
        var c = maxOf(0f, nc)
        var r = maxOf(0f, nr)
        if (c > 0f) {
            val side = maxOf(if (el) 0f else l, if (er) 0f else r)
            c = if (ec) minOf(c, maxOf(0f, width - 2f * (side + g))) else if (c <= width) c else 0f
            if (ec && c < minOf(nc, minElastic)) c = 0f
        }
        if (c > 0f) {
            val room = maxOf(0f, (width - c) / 2f - g)
            l = if (el) minOf(l, room) else if (l <= room) l else 0f
            r = if (er) minOf(r, room) else if (r <= room) r else 0f
        } else {
            val between = if (l > 0f && r > 0f) g else 0f
            if (l + r + between > width) {
                if (el && er) {
                    val room = maxOf(0f, width - between)
                    val half = room / 2f
                    when {
                        l <= half -> r = minOf(r, room - l)
                        r <= half -> l = minOf(l, room - r)
                        else -> { l = half; r = half }
                    }
                } else if (el) l = minOf(l, maxOf(0f, width - between - r))
                else if (er) r = minOf(r, maxOf(0f, width - between - l))
                else l = 0f
            }
            if (!el && l > width) l = 0f
            if (!er && r > width) r = 0f
        }
        if (el && l < minOf(nl, minElastic)) l = 0f
        if (er && r < minOf(nr, minElastic)) r = 0f
        out[0] = l; out[1] = c; out[2] = r
    }
}

/** Geometry/version key only: Android Paint/Canvas stay out of JVM cache tests. */
internal class StatusDrawCache {
    private var owner: StatusDecor? = null
    private var version = Int.MIN_VALUE
    private var width = Float.NaN
    private var inset = Float.NaN
    private var size = Float.NaN
    fun changed(status: StatusDecor, w: Float, i: Float, ts: Float): Boolean {
        if (owner === status && version == status.version && width == w && inset == i && size == ts) return false
        owner = status; version = status.version; width = w; inset = i; size = ts
        return true
    }
}
