package com.ggumtak.readeraplus.reader

import android.content.Intent
import com.ggumtak.readeraplus.data.NoteKind
import com.ggumtak.readeraplus.data.NoteRow
import com.ggumtak.readeraplus.format.DocPosition

/** Primitive-only, one-shot open target. Reading is resolved without book IO. */
data class ReaderJump(val section: Int, val offset: Int, val end: Int = -1, val frac: Float = -1f,
                      val sig: String = "", val anchor: String = "") {
    fun put(i: Intent): Intent = i.putExtra(EXTRA_SECTION, section).putExtra(EXTRA_OFFSET, offset)
        .putExtra(EXTRA_END, end).putExtra(EXTRA_FRAC, frac).putExtra(EXTRA_SIG, sig).putExtra(EXTRA_ANCHOR, anchor)
    companion object {
        const val EXTRA_SECTION = "jump_section"; const val EXTRA_OFFSET = "jump_offset"; const val EXTRA_END = "jump_end"
        const val EXTRA_FRAC = "jump_frac"; const val EXTRA_SIG = "jump_sig"; const val EXTRA_ANCHOR = "jump_anchor"
        const val ANCHOR_MAX = 64
        fun from(i: Intent): ReaderJump? = try {
            if (!i.hasExtra(EXTRA_SECTION) && !i.hasExtra(EXTRA_FRAC)) null else
                sanitize(i.getIntExtra(EXTRA_SECTION, -1), i.getIntExtra(EXTRA_OFFSET, -1), i.getIntExtra(EXTRA_END, -1),
                    i.getFloatExtra(EXTRA_FRAC, -1f), i.getStringExtra(EXTRA_SIG), i.getStringExtra(EXTRA_ANCHOR))
        } catch (_: RuntimeException) { null }
        internal fun sanitize(section: Int, offset: Int, end: Int, frac: Float, sig: String?, anchor: String?): ReaderJump? {
            if (!frac.isFinite() || frac < -1f || frac > 1f) return null
            if (section < 0 || offset < 0) return null
            return ReaderJump(section, offset, if (end > offset) end else -1, frac, sig.orEmpty(), anchor.orEmpty().take(ANCHOR_MAX))
        }
        fun strip(i: Intent): Intent = Intent(i).apply {
            for (k in arrayOf(EXTRA_SECTION, EXTRA_OFFSET, EXTRA_END, EXTRA_FRAC, EXTRA_SIG, EXTRA_ANCHOR)) removeExtra(k)
        }
        fun of(row: NoteRow): ReaderJump = ReaderJump(row.section, row.start, row.end, row.frac, row.sig,
            (if (row.ref.kind == NoteKind.LOOKUP) row.word else row.body).take(ANCHOR_MAX))
        fun resolve(j: ReaderJump, currentSig: String?, sectionCount: Int, locate: (Float) -> DocPosition): DocPosition? {
            if (sectionCount <= 0) return null
            if (currentSig != null && j.sig.isNotEmpty() && j.sig != currentSig && j.frac in 0f..1f) return locate(j.frac)
            if (j.section in 0 until sectionCount && j.offset >= 0) return DocPosition(j.section, j.offset)
            return if (j.frac in 0f..1f) locate(j.frac) else null
        }
    }
}

object NoteSig { fun of(textSignature: String?, sizeBytes: Long): String = (textSignature ?: "e") + ":" + sizeBytes }

object JumpAnchor {
    private fun ignored(c: Char): Boolean = c.isWhitespace() || c == '\uFFFC'
    /** At most 24 significant characters; no substring, document scan or allocation on an open. */
    fun matches(text: CharSequence, offset: Int, anchor: String): Boolean {
        var t = offset.coerceAtLeast(0); var a = 0; var n = 0
        while (n < 24) {
            while (a < anchor.length && ignored(anchor[a])) a++
            if (a == anchor.length) return true
            while (t < text.length && ignored(text[t])) t++
            if (t >= text.length || text[t++] != anchor[a++]) return false
            n++
        }
        return true
    }

    /** [anchor] has something to compare (not only whitespace / object markers). */
    fun hasText(anchor: String): Boolean {
        for (c in anchor) if (!ignored(c)) return true
        return false
    }

    /**
     * N §6.4: the first offset of [text] (from [from]) where [anchor] [matches], whitespace and object markers
     * ignored; -1 when absent or when [anchor] has nothing to compare.
     */
    fun find(text: CharSequence, anchor: String, from: Int = 0): Int {
        var a = 0
        while (a < anchor.length && ignored(anchor[a])) a++
        if (a == anchor.length) return -1
        val first = anchor[a]
        for (i in from.coerceAtLeast(0) until text.length) {
            if (text[i] == first && matches(text, i, anchor)) return i
        }
        return -1
    }

    /** Sections the anchor check reads (N §6.4 [Δ] cap): at most this many after the one it starts from... */
    const val MAX_SECTIONS = 48
    /** ...and at most this many chars in all, whichever comes first. */
    const val MAX_CHARS = 3_000_000

    /**
     * The order of sections searched from [center]: the section itself, then outward (+1, −1, +2, −2, …, so ± 3 come
     * first), skipping the ends of a [count]-section book; at most [max] entries.
     */
    fun searchOrder(center: Int, count: Int, max: Int = MAX_SECTIONS): IntArray {
        if (count <= 0 || max <= 0) return IntArray(0)
        val c = center.coerceIn(0, count - 1)
        val out = IntArray(minOf(count, max))
        var n = 0
        out[n++] = c
        var d = 1
        while (n < out.size && (c + d < count || c - d >= 0)) {
            if (c + d < count) out[n++] = c + d
            if (n < out.size && c - d >= 0) out[n++] = c - d
            d++
        }
        return out
    }

    /**
     * N §6.4 step 2: searches [anchor] in the sections of [searchOrder] (texts from [load], null = unreadable, skipped)
     * until the first hit, [maxSections] sections or [maxChars] chars. [check] runs between sections (the job's
     * `ensureActive`). Returns the position found, or null.
     */
    fun search(
        center: Int, count: Int, anchor: String, load: (Int) -> CharSequence?, check: () -> Unit = {},
        maxSections: Int = MAX_SECTIONS, maxChars: Int = MAX_CHARS,
    ): DocPosition? {
        var chars = 0L
        for (sec in searchOrder(center, count, maxSections)) {
            check()
            val text = load(sec) ?: continue
            val at = find(text, anchor)
            if (at >= 0) return DocPosition(sec, at)
            chars += text.length
            if (chars >= maxChars) return null
        }
        return null
    }

    /**
     * PLAN K2: a quote is drawn on the page when it was made against this text ([sig] equals the session's [noteSig]),
     * or, for a legacy `''` or another sig, when its own [quoteText] is still found at [start] (≤ 24 visible chars).
     */
    fun quoteHolds(sig: String, noteSig: String?, text: CharSequence, start: Int, quoteText: String): Boolean =
        (sig.isNotEmpty() && sig == noteSig) || matches(text, start, quoteText)
}

/** N §6.5: how a note's place reads. */
object NotePlaceText {
    const val CHAPTER_MAX = 200

    /** A TOC title for [com.ggumtak.readeraplus.data.NotePlace.chapter]: whitespace runs as one space, trimmed, ≤ 200. */
    fun chapter(raw: String?): String {
        if (raw.isNullOrEmpty()) return ""
        val sb = StringBuilder(minOf(raw.length, CHAPTER_MAX))
        var space = false
        for (c in raw) {
            if (c.isWhitespace()) {
                space = sb.isNotEmpty()
                continue
            }
            if (space) {
                if (sb.length + 1 >= CHAPTER_MAX) break
                sb.append(' ')
                space = false
            }
            sb.append(c)
            if (sb.length >= CHAPTER_MAX) break
        }
        return sb.toString()
    }
}

/**
 * Optional reader capability for the TOC's 인용문 rows (PLAN K2): whether [quote]'s stored place no longer holds its
 * text, by the same result the page uses when known, else by the sig (non-empty and not the session's).
 */
interface QuotePlaceHost { fun quoteMoved(quote: com.ggumtak.readeraplus.data.Quote): Boolean }
