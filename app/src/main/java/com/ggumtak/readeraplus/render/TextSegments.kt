package com.ggumtak.readeraplus.render

import com.ggumtak.readeraplus.engine.OBJECT_CHAR
import com.ggumtak.readeraplus.engine.StyleRun

/**
 * Pure helpers that split a laid-out line into draw calls (unit-tested against LineGeometry).
 *
 * A segment is a run of chars whose glyphs can be drawn by one drawText call starting at the x of its first
 * char: every visible char's x (from LineGeometry.charPositions) equals the segment start plus the natural
 * advances before it. Justification extra therefore always starts a new segment, while zero-advance chars
 * (combining marks, low surrogates, ZWJ) stay with the cluster they belong to. A stray [OBJECT_CHAR] (measured
 * as 0 but drawn by fonts as a box) is always a segment of its own, and blank, so it is never drawn.
 */
internal object TextSegments {
    private const val EPS = 0.001f

    /**
     * End (exclusive) of the segment starting at [p], limited to [limit]. [xs] holds the char x positions of
     * the line starting at [lineStart] (xs[i - lineStart]); [adv] the section's advances.
     */
    fun segmentEnd(text: String, adv: FloatArray, xs: FloatArray, lineStart: Int, p: Int, limit: Int): Int {
        if (text[p] == OBJECT_CHAR) return p + 1
        var nat = xs[p - lineStart] + adv[p]
        var q = p + 1
        while (q < limit) {
            if (text[q] == OBJECT_CHAR) break
            val a = adv[q]
            if (a > 0f && !Character.isLowSurrogate(text[q])) {
                val d = xs[q - lineStart] - nat
                if (d > EPS || d < -EPS) break
            }
            nat += a
            q++
        }
        return q
    }

    /** True when text[s, e) has nothing to draw (spaces, nbsp, ideographic space, zero-width chars). */
    fun isBlank(text: String, s: Int, e: Int): Boolean {
        for (i in s until e) if (!isBlankChar(text[i])) return false
        return true
    }

    fun isBlankChar(c: Char): Boolean = when (c) {
        ' ', ' ', '　', '\t', '​', '‌', '‍', '⁠', '﻿', ' ', ' ',
        '\n', '\r', '­', OBJECT_CHAR -> true
        else -> c in ' '..' '
    }

    /** Index of the first style run whose end is after [offset] (runs sorted, non-overlapping); runs.size if none. */
    fun firstRunAfter(runs: List<StyleRun>, offset: Int): Int {
        var lo = 0
        var hi = runs.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (runs[mid].end <= offset) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** First index in [s, e) that is not blank, or -1. */
    fun firstInk(text: String, s: Int, e: Int): Int {
        for (i in s until e) if (!isBlankChar(text[i])) return i
        return -1
    }

    /** Last index in [s, e) that is not blank, or -1. */
    fun lastInk(text: String, s: Int, e: Int): Int {
        var i = e - 1
        while (i >= s) {
            if (!isBlankChar(text[i])) return i
            i--
        }
        return -1
    }
}

/** Reusable char buffers handed to Paint/Canvas char[] APIs (unit-tested). */
internal object CharBuffers {
    /**
     * Smallest buffer ever allocated: 16 KB, above ART's 12 KB large-object threshold, so the array is never moved
     * by the GC and JNI can read it in place (no copy, no waiting for a moving GC to finish).
     */
    const val MIN_CHARS = 8192

    /** New buffer holding at least [n] chars (grows geometrically; contents are not preserved). */
    fun grow(current: CharArray, n: Int): CharArray = CharArray(capacity(current.size, n))

    /** Capacity for a buffer of [currentSize] that must hold [n] chars: doubling, at least [MIN_CHARS], no overflow. */
    fun capacity(currentSize: Int, n: Int): Int {
        var cap = maxOf(MIN_CHARS, currentSize)
        while (cap < n) cap = if (cap > Int.MAX_VALUE / 2) n else cap * 2
        return cap
    }

    /** Zeroes the advance of every paragraph separator and image placeholder among chars[0, n). */
    fun zeroInvisible(chars: CharArray, n: Int, out: FloatArray, outOffset: Int) {
        for (i in 0 until n) {
            val c = chars[i]
            if (c == '\n' || c == OBJECT_CHAR) out[outOffset + i] = 0f
        }
    }
}
