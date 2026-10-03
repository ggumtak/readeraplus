package com.ggumtak.readeraplus.data

/**
 * Pure argument rules of the notes writes in [Library] (N §5.1), JVM-tested: what a quote style or a note place is
 * stored as, and which reader-computed places a backfill may write.
 */
internal object NoteWrites {
    /** Longest stored note signature (`NoteSig.of`: "e:<size>" / "<text signature>:<size>"). */
    const val SIG_MAX = 100

    /** A QuoteStyles id clamped to 0..[DataLimits.QUOTE_STYLE_MAX]. */
    fun style(style: Int): Int = style.coerceIn(0, DataLimits.QUOTE_STYLE_MAX)

    /** A char fraction as stored: 0..1, or -1 for unknown (negative or NaN). */
    fun frac(f: Float): Float = if (f.isNaN() || f < 0f) -1f else f.coerceAtMost(1f)

    /**
     * [p] as stored: chapter cleaned to one line of at most [DataLimits.CHAPTER] chars, fraction per [frac], sig
     * trimmed to [SIG_MAX]. Null = [NotePlace.UNKNOWN].
     */
    fun place(p: NotePlace?): NotePlace {
        if (p == null) return NotePlace.UNKNOWN
        return NotePlace(MetaInfo.clean(p.chapter, DataLimits.CHAPTER), frac(p.frac), MetaInfo.truncate(p.sig.trim(), SIG_MAX))
    }

    /**
     * The backfill entries worth writing (note id → stored place): positive ids with a known fraction. The UPDATE
     * itself only touches rows still unknown (`frac < 0`).
     */
    fun backfill(places: Map<Long, NotePlace>): List<Pair<Long, NotePlace>> {
        if (places.isEmpty()) return emptyList()
        val out = ArrayList<Pair<Long, NotePlace>>(places.size)
        for ((id, p) in places) {
            if (id <= 0) continue
            val s = place(p)
            if (s.frac < 0f) continue
            out += id to s
        }
        return out
    }
}
