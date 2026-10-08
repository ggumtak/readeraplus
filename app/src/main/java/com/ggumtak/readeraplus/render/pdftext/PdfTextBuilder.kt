package com.ggumtak.readeraplus.render.pdftext

/**
 * Collects glyphs in content order and turns them into lines: a new line starts when the baseline jumps by more than
 * half the font size or the pen goes back by more than one font size; a gap above a quarter of the font size becomes
 * one space. Coordinates are page points with the origin at the top-left.
 */
internal class PageTextBuilder(private val pageW: Float, private val pageH: Float) {
    private val sb = StringBuilder()
    private var boxes = FloatArray(2048)
    private val starts = IntList()
    private var lineStart = 0
    private var has = false
    private var lastBase = 0f
    private var lastL = 0f
    private var lastR = 0f

    private fun norm(c: Char): Char = when {
        c.code < 32 || c.code == 0x7F -> '\u0000'
        c == ' ' -> ' '
        c == '﻿' || c == '​' || c == '‌' || c == '‍' || c == '­' -> '\u0000'
        else -> c
    }

    private fun ensure(chars: Int) {
        if (chars * 4 > boxes.size) boxes = boxes.copyOf(maxOf(chars * 4, boxes.size * 2))
    }

    private fun put(c: Char, l: Float, t: Float, r: Float, b: Float) {
        val i = sb.length
        ensure(i + 1)
        val o = i * 4
        boxes[o] = l
        boxes[o + 1] = t
        boxes[o + 2] = r
        boxes[o + 3] = b
        sb.append(c)
    }

    private fun putZeroWidth(c: Char) {
        val p = (sb.length - 1) * 4
        val x = boxes[p + 2]
        put(c, x, boxes[p + 1], x, boxes[p + 3])
    }

    private fun trimTrailingSpaces() {
        while (sb.length > lineStart && sb[sb.length - 1] == ' ') sb.setLength(sb.length - 1)
    }

    /** Adds one glyph whose text is [s] and whose box (page space) is [l],[t],[r],[b]; [base] is the baseline y. */
    fun glyph(text: String, l: Float, t: Float, r: Float, b: Float, base: Float, fs: Float) {
        val s = expandLigatures(text)
        if (l.isNaN() || t.isNaN() || r.isNaN() || b.isNaN() || base.isNaN() || fs.isNaN() || fs <= 0f) return
        if (r < 0f || l > pageW || b < 0f || t > pageH) return
        var kept = 0
        var first = '\u0000'
        var allSpace = true
        for (k in 0 until s.length) {
            val c = norm(s[k])
            if (c == '\u0000') continue
            if (kept == 0) first = c
            kept++
            if (c != ' ') allSpace = false
        }
        if (kept == 0) return
        var newLine = false
        if (has) newLine = Math.abs(base - lastBase) > 0.5f * fs || l < lastL - fs
        if (allSpace && (!has || newLine || sb.length == lineStart)) return
        if (!has) {
            starts.add(0)
            lineStart = 0
        } else if (newLine) {
            trimTrailingSpaces()
            if (sb.length > lineStart) {
                putZeroWidth('\n')
                lineStart = sb.length
                starts.add(lineStart)
            }
        } else if (l - lastR > 0.25f * fs && sb.isNotEmpty() && sb[sb.length - 1] != ' ' && first != ' ') {
            putZeroWidth(' ')
        }
        ensure(sb.length + kept)
        val w = r - l
        var j = 0
        for (k in 0 until s.length) {
            val c = norm(s[k])
            if (c == '\u0000') continue
            val cl = if (j == 0) l else l + w * j / kept
            val cr = if (j == kept - 1) r else l + w * (j + 1) / kept
            put(c, cl, t, cr, b)
            j++
        }
        lastBase = base
        lastL = l
        lastR = r
        has = true
    }

    private fun expandLigatures(s: String): String {
        var i = 0
        while (i < s.length && s[i].code !in 0xFB00..0xFB06) i++
        if (i == s.length) return s
        val out = StringBuilder(s.length + 3)
        for (c in s) {
            when (c.code) {
                0xFB00 -> out.append("ff")
                0xFB01 -> out.append("fi")
                0xFB02 -> out.append("fl")
                0xFB03 -> out.append("ffi")
                0xFB04 -> out.append("ffl")
                0xFB05, 0xFB06 -> out.append("st")
                else -> out.append(c)
            }
        }
        return out.toString()
    }

    fun build(): PageGlyphs {
        trimTrailingSpaces()
        if (sb.isEmpty()) return PageGlyphs.EMPTY
        return PageGlyphs(sb.toString(), boxes.copyOf(sb.length * 4), starts.a.copyOf(starts.size))
    }
}
