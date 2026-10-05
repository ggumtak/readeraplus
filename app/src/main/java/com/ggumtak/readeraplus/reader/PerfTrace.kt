package com.ggumtak.readeraplus.reader

/**
 * The text of the RAPerf DEBUG lines for measuring on the Comet ([ReaderPerf], DEVICE_CHECKLIST "속도 측정").
 * Pure and allocation-free into a builder with room, so the lines are JVM-tested; the reader calls them only with
 * the tag on.
 *
 * - "turn #<n> <input>: contact C ms, wait W ms, up+U ms, down+D ms, onDraw X ms": one per traced turn, right after
 *   its "turn N ms"
 * - "frame #<n>: total T ms (delay …, input …, anim …, layout …, draw …, sync …, cmd …, swap …, gpu …), done up+U ms,
 *   down+D ms": the FrameMetrics of that turn's frame ([FrameWatch]); "frame open: …" for a book's first page
 * - "open doc <TXT|EPUB> <how> T ms, B bytes, C chars, S sections": what Documents.open did
 * - "[open ]layout s:<section> g:<gen> load T ms C chars, typeset T ms P pages[, prefetch]": one per section layout
 * - "open <id>: onDraw X ms": the first page's draw, before the existing "open <id>: first page N ms"
 */
internal object PerfLines {
    /** What asked for a turn ([PageView.lastInputKind]). */
    const val INPUT_NONE = 0
    const val INPUT_TAP = 1
    const val INPUT_SWIPE = 2
    const val INPUT_WHEEL = 3
    const val INPUT_KEY = 4

    /** Durations of [frameLine] (ns, -1 = not reported on this API level), in this order: see [FRAME_LABELS]. */
    const val FRAME_PARTS = 9
    private val FRAME_LABELS = arrayOf("delay", "input", "anim", "layout", "draw", "sync", "cmd", "swap", "gpu")

    /** How long the finger (or key) was down before the event at [upAt]; -1 when the down time is unknown. */
    fun contactMs(downAt: Long, upAt: Long): Long = if (downAt <= 0L || upAt < downAt) -1L else upAt - downAt

    /** [nanos] as milliseconds with one decimal ("4.2", rounded half up); "-" when negative (not measured). */
    fun appendMs(sb: StringBuilder, nanos: Long): StringBuilder {
        if (nanos < 0L) return sb.append('-')
        val tenths = (nanos + 50_000L) / 100_000L
        return sb.append(tenths / 10L).append('.').append((tenths % 10L).toInt())
    }

    /**
     * Turn [id]: [kind] asked for it at [inputAt] (a touch's lift, a key's event time) after going down at [downAt]
     * (0 = no down: wheel, accessibility); the event reached the reader [waitMs] later (-1 = unknown; a volume key's
     * system hold shows here); its page finished drawing at [drawnAt] (uptime ms) after an onDraw of [drawNs].
     */
    fun turnLine(
        sb: StringBuilder, id: Int, kind: Int, inputAt: Long, downAt: Long, waitMs: Long, drawnAt: Long, drawNs: Long,
    ): StringBuilder {
        sb.append("turn #").append(id).append(' ').append(inputName(kind)).append(':')
        val down = contactMs(downAt, inputAt)
        if (down >= 0L) sb.append(if (kind == INPUT_KEY) " held " else " contact ").append(down).append(" ms,")
        if (waitMs >= 0L) sb.append(" wait ").append(waitMs).append(" ms,")
        sb.append(' ').append(anchorName(kind)).append('+').append(drawnAt - inputAt).append(" ms")
        if (down >= 0L) sb.append(", down+").append(drawnAt - downAt).append(" ms")
        if (drawNs >= 0L) appendMs(sb.append(", onDraw "), drawNs).append(" ms")
        return sb
    }

    /**
     * The FrameMetrics of turn [id]'s frame (0 = a book's first page): [parts] as in [FRAME_LABELS], [totalNs] from
     * the intended vsync to the frame's end, which was at [doneAt] (uptime ms: the buffer went to the system, the
     * panel's update follows), counted from [inputAt] and, when known, from the touch's or key's [downAt].
     */
    fun frameLine(
        sb: StringBuilder, id: Int, kind: Int, parts: LongArray, totalNs: Long, doneAt: Long, inputAt: Long,
        downAt: Long,
    ): StringBuilder {
        if (id == 0) sb.append("frame open: total ") else sb.append("frame #").append(id).append(": total ")
        appendMs(sb, totalNs).append(" ms (")
        var first = true
        for (i in 0 until minOf(FRAME_PARTS, parts.size)) {
            if (parts[i] < 0L) continue
            if (!first) sb.append(", ")
            first = false
            appendMs(sb.append(FRAME_LABELS[i]).append(' '), parts[i])
        }
        sb.append("), done ").append(if (id == 0) "open" else anchorName(kind))
        sb.append('+').append(doneAt - inputAt).append(" ms")
        if (id != 0 && contactMs(downAt, inputAt) >= 0L) sb.append(", down+").append(doneAt - downAt).append(" ms")
        return sb
    }

    /** Documents.open took [nanos] ([how]: TXT index / parse, EPUB plan / scan) for a book of [bytes] and [chars]. */
    fun docLine(
        sb: StringBuilder, format: String, how: String, nanos: Long, bytes: Long, chars: Long, exactChars: Boolean,
        sections: Int,
    ): StringBuilder {
        appendMs(sb.append("open doc ").append(format).append(' ').append(how).append(' '), nanos)
        sb.append(" ms, ").append(bytes).append(" bytes, ")
        if (!exactChars) sb.append('~')
        return sb.append(chars).append(" chars, ").append(sections).append(" sections")
    }

    /** One section layout: loadSection [loadNs] for [chars] chars, then Typesetter.layout [typesetNs] into [pages]. */
    fun layoutLine(
        sb: StringBuilder, open: Boolean, section: Int, gen: Int, loadNs: Long, chars: Int, typesetNs: Long, pages: Int,
        prefetch: Boolean,
    ): StringBuilder {
        if (open) sb.append("open ")
        appendMs(sb.append("layout s:").append(section).append(" g:").append(gen).append(" load "), loadNs)
        appendMs(sb.append(" ms ").append(chars).append(" chars, typeset "), typesetNs)
        sb.append(" ms ").append(pages).append(" pages")
        if (prefetch) sb.append(", prefetch")
        return sb
    }

    /** Book [bookId]'s first page drew in [drawNs] (its onDraw only). */
    fun openDrawLine(sb: StringBuilder, bookId: Long, drawNs: Long): StringBuilder =
        appendMs(sb.append("open ").append(bookId).append(": onDraw "), drawNs).append(" ms")

    private fun inputName(kind: Int): String = when (kind) {
        INPUT_TAP -> "tap"
        INPUT_SWIPE -> "swipe"
        INPUT_WHEEL -> "wheel"
        INPUT_KEY -> "key"
        else -> "other"
    }

    /** The event a "+N ms" counts from: a touch's lift, a key press, any other input. */
    private fun anchorName(kind: Int): String = when (kind) {
        INPUT_TAP, INPUT_SWIPE -> "up"
        INPUT_KEY -> "key"
        else -> "input"
    }
}

/**
 * Frames waiting for their FrameMetrics (RAPerf DEBUG, [FrameWatch]): the page view notes a traced frame by its vsync
 * time (`View.getDrawingTime`: the frame's Choreographer time in ms, which FrameMetrics reports as VSYNC_TIMESTAMP)
 * with the turn it draws; the metrics thread takes the entries of the frame whose vsync matches (±1 ms). [SLOTS]
 * entries in a ring: one that never matches (a frame without metrics) is overwritten later. Any thread; nothing is
 * allocated after construction.
 */
internal class FrameTrace {
    private val vsync = LongArray(SLOTS) { NONE }
    private val values = LongArray(SLOTS * FIELDS)
    private var next = 0

    /** The frame drawn at [vsyncMs] shows turn [id] (0 = a first page), input [kind] at [inputAt], down at [downAt]. */
    @Synchronized
    fun expect(vsyncMs: Long, id: Int, kind: Int, inputAt: Long, downAt: Long) {
        val i = next
        next = (next + 1) % SLOTS
        vsync[i] = vsyncMs
        val o = i * FIELDS
        values[o] = id.toLong()
        values[o + 1] = kind.toLong()
        values[o + 2] = inputAt
        values[o + 3] = downAt
    }

    /** Moves one entry of the frame drawn at [vsyncMs] into [out] (id, kind, inputAt, downAt); false when none. */
    @Synchronized
    fun take(vsyncMs: Long, out: LongArray): Boolean {
        for (i in 0 until SLOTS) {
            val v = vsync[i]
            if (v == NONE || v < vsyncMs - 1L || v > vsyncMs + 1L) continue
            vsync[i] = NONE
            System.arraycopy(values, i * FIELDS, out, 0, FIELDS)
            return true
        }
        return false
    }

    companion object {
        /** Longs per entry: id, kind, inputAt, downAt. */
        const val FIELDS = 4
        const val SLOTS = 4
        private const val NONE = Long.MIN_VALUE
    }
}

/** True for the first [take] only: something the reader does once per instance (reportFullyDrawn). Main thread. */
internal class OnceGate {
    private var done = false

    fun take(): Boolean {
        if (done) return false
        done = true
        return true
    }
}
