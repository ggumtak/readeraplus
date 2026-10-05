package com.ggumtak.readeraplus.render

/** Width allocation for three slots centred on the body column; fixed numbers are never shortened. */
internal object StatusMath {
    /**
     * Widths of the left, centre and right slots of a band [w] wide into [out]. [reserveRight]: px at the band's right
     * end kept for the bookmark ribbon whether or not it shows, so no width changes when the bookmark is toggled. The
     * right slot is then drawn [reserveRight] further in on a bookmarked page (flush with the band's end otherwise), and
     * the centre, still centred on the whole band, and the left slot keep clear of that place.
     */
    fun allocate(w: Float, gap: Float, nl: Float, nc: Float, nr: Float,
                 el: Boolean, ec: Boolean, er: Boolean, minElastic: Float, out: FloatArray, reserveRight: Float = 0f) {
        val width = maxOf(0f, w)
        val g = maxOf(0f, gap)
        val res = minOf(maxOf(0f, reserveRight), width)
        var l = maxOf(0f, nl)
        var c = maxOf(0f, nc)
        var r = maxOf(0f, nr)
        if (c > 0f) {
            val side = maxOf(if (el) 0f else l, (if (er) 0f else r) + res)
            c = if (ec) minOf(c, maxOf(0f, width - 2f * (side + g))) else if (c <= width - 2f * res) c else 0f
            if (ec && c < minOf(nc, minElastic)) c = 0f
        }
        if (c > 0f) {
            val room = maxOf(0f, (width - c) / 2f - g)
            val roomRight = maxOf(0f, room - res)
            l = if (el) minOf(l, room) else if (l <= room) l else 0f
            r = if (er) minOf(r, roomRight) else if (r <= roomRight) r else 0f
        } else {
            // Without a centre the two sides share the band less the ribbon's place.
            val avail = width - res
            val between = if (l > 0f && r > 0f) g else 0f
            if (l + r + between > avail) {
                if (el && er) {
                    val room = maxOf(0f, avail - between)
                    val half = room / 2f
                    when {
                        l <= half -> r = minOf(r, room - l)
                        r <= half -> l = minOf(l, room - r)
                        else -> { l = half; r = half }
                    }
                } else if (el) l = minOf(l, maxOf(0f, avail - between - r))
                else if (er) r = minOf(r, maxOf(0f, avail - between - l))
                else l = 0f
            }
            if (!el && l > avail) l = 0f
            if (!er && r > avail) r = 0f
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
