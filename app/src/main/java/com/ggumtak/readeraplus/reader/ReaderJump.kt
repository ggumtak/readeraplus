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
}
