package com.ggumtak.readeraplus.reader.extras

import com.ggumtak.readeraplus.engine.OBJECT_CHAR

/*
 * Pure text helpers for the reader extras (search, TTS sentence splitting, utterance ids).
 * No android.* imports: unit-tested on the JVM.
 */

/** Growable primitive int list (no boxing in hot loops). */
internal class IntList(capacity: Int = 16) {
    private var a = IntArray(capacity.coerceAtLeast(4))
    var size = 0
        private set

    fun add(v: Int) {
        if (size == a.size) a = a.copyOf(a.size * 2)
        a[size++] = v
    }

    operator fun get(i: Int): Int = a[i]

    fun toArray(): IntArray = a.copyOf(size)
}

/** Case-insensitive (Latin) substring search and result snippets. */
internal object TextSearch {

    /**
     * Calls [onHit] with the start offset of every non-overlapping occurrence of [query] in [text] starting at
     * [from]; stops early when [onHit] returns false. Latin letters match case-insensitively.
     * Returns the number of hits reported.
     */
    inline fun scan(text: String, query: String, from: Int = 0, onHit: (Int) -> Boolean): Int {
        val m = query.length
        if (m == 0) return 0
        val q0 = query[0]
        val up = q0.uppercaseChar()
        val low = q0.lowercaseChar()
        val last = text.length - m
        var i = from.coerceAtLeast(0)
        var count = 0
        while (i <= last) {
            val c = text[i]
            if ((c == q0 || c == up || c == low) && text.regionMatches(i, query, 0, m, ignoreCase = true)) {
                count++
                if (!onHit(i)) return count
                i += m
            } else {
                i++
            }
        }
        return count
    }

    /** A one-line result snippet; [hitStart, hitEnd) is the match inside [text]. */
    class Snippet(val text: String, val hitStart: Int, val hitEnd: Int)

    /**
     * Context of [radius] chars around text[start, end): line breaks / tabs / object chars become spaces,
     * "…" marks cut ends. Never splits a surrogate pair.
     */
    fun snippet(text: String, start: Int, end: Int, radius: Int = 30): Snippet {
        val n = text.length
        val hs = start.coerceIn(0, n)
        val he = end.coerceIn(hs, n)
        var s = (hs - radius).coerceAtLeast(0)
        var e = (he + radius).coerceAtMost(n)
        if (s in 1 until hs && Character.isLowSurrogate(text[s])) s++
        if (e in (he + 1) until n && Character.isHighSurrogate(text[e - 1])) e--
        val sb = StringBuilder(e - s + 2)
        val lead = if (s > 0) 1 else 0
        if (lead == 1) sb.append('…')
        for (i in s until e) sb.append(visible(text[i]))
        if (e < n) sb.append('…')
        return Snippet(sb.toString(), lead + (hs - s), lead + (he - s))
    }

    private fun visible(c: Char): Char =
        if (c == '\n' || c == '\r' || c == '\t' || c == OBJECT_CHAR) ' ' else c
}

/**
 * Splits section text into speakable sentences for TTS.
 * A sentence ends at `. ! ? … 。 ？ ！ ‥` (plus any following terminal punctuation / closing quotes and brackets)
 * when followed by whitespace or the end of the range, and always at '\n'. Long sentences are cut into chunks of
 * at most [MAX_CHUNK] chars at the last space. Ranges without any letter or digit (scene-break marks, images)
 * are skipped.
 */
internal object SentenceSplitter {
    const val MAX_CHUNK = 300

    /** Returns packed ranges `[s0, e0, s1, e1, ...]` inside text[from, to). */
    fun split(text: String, from: Int = 0, to: Int = text.length, maxChunk: Int = MAX_CHUNK): IntArray {
        val end = to.coerceAtMost(text.length)
        val out = IntList(64)
        var st = from.coerceIn(0, end)
        var i = st
        while (i < end) {
            val c = text[i]
            if (c == '\n') {
                emit(text, st, i, maxChunk, out)
                st = i + 1
                i = st
                continue
            }
            if (isTerminal(c)) {
                var j = i + 1
                while (j < end && (isTerminal(text[j]) || isClosing(text[j]))) j++
                if (j >= end || isSpace(text[j])) {
                    emit(text, st, j, maxChunk, out)
                    st = j
                }
                i = j
                continue
            }
            i++
        }
        emit(text, st, end, maxChunk, out)
        return out.toArray()
    }

    fun isTerminal(c: Char): Boolean =
        c == '.' || c == '!' || c == '?' || c == '…' || c == '。' || c == '？' || c == '！' || c == '‥'

    fun isClosing(c: Char): Boolean = when (c) {
        '"', '\'', '”', '’', '」', '』', ')', ']', '}', '》', '〉', '】', '〕', '）', '~', '～' -> true
        else -> false
    }

    fun isSpace(c: Char): Boolean = Character.isWhitespace(c) || c == ' ' || c == '　'

    private fun emit(text: String, start: Int, end: Int, maxChunk: Int, out: IntList) {
        var s = start
        var e = end
        while (s < e && isSpace(text[s])) s++
        while (e > s && isSpace(text[e - 1])) e--
        while (e - s > maxChunk) {
            var cut = s + maxChunk
            var k = cut
            while (k > s + maxChunk / 3 && !isSpace(text[k - 1])) k--
            if (k > s + maxChunk / 3) cut = k
            if (Character.isHighSurrogate(text[cut - 1]) && cut < e && cut - 1 > s) cut--
            addIfSpeakable(text, s, cut, out)
            s = cut
            while (s < e && isSpace(text[s])) s++
        }
        addIfSpeakable(text, s, e, out)
    }

    private fun addIfSpeakable(text: String, s: Int, e0: Int, out: IntList) {
        var e = e0
        while (e > s && isSpace(text[e - 1])) e--
        if (e <= s) return
        for (k in s until e) {
            if (Character.isLetterOrDigit(text[k])) {
                out.add(s)
                out.add(e)
                return
            }
        }
    }
}

/** Utterance ids "sec:start:end:serial:gen" (spec prefix "sec:start:end" + queue bookkeeping). */
internal object UtteranceId {
    fun make(section: Int, start: Int, end: Int, serial: Int, gen: Int): String = "$section:$start:$end:$serial:$gen"

    /** Returns [section, start, end, serial, gen] or null when [id] is not ours. */
    fun parse(id: String?): IntArray? {
        if (id == null) return null
        val out = IntArray(5)
        var field = 0
        var v = 0
        var digits = 0
        for (c in id) {
            if (c == ':') {
                if (digits == 0 || field >= 4) return null
                out[field++] = v
                v = 0
                digits = 0
            } else if (c in '0'..'9') {
                if (digits >= 10) return null
                v = v * 10 + (c - '0')
                digits++
            } else {
                return null
            }
        }
        if (digits == 0 || field != 4) return null
        out[4] = v
        return out
    }
}
