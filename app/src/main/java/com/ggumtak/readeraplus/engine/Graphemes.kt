package com.ggumtak.readeraplus.engine

/*
 * Grapheme-cluster bounds for selections (pure, no android.*): a selection that ends at `o + 1` can cut a surrogate
 * pair, an emoji sequence or a base + combining mark in half. Same rules as the typesetter's line breaking
 * (TypesetPass.graphemeOk: never break before a mark or after a ZWJ), here as a forward scan from a cluster's start.
 */

private const val ZWJ = 0x200D

/**
 * End (exclusive) of the grapheme cluster that starts at [o]: the code point there (a surrogate pair counts as one),
 * then every following combining mark, variation selector (FE00-FE0F, E0100-E01EF), emoji skin-tone modifier
 * (1F3FB-1F3FF) and ZWJ plus the code point it joins; two regional indicators (a flag) stay together. Never
 * beyond `text.length`; an [o] at or past the end returns the length, a negative one is read as 0.
 */
fun clusterEnd(text: CharSequence, o: Int): Int {
    val n = text.length
    if (o >= n) return n
    var i = o.coerceAtLeast(0)
    val first = Character.codePointAt(text, i)
    i += Character.charCount(first)
    if (isRegionalIndicator(first) && i < n && isRegionalIndicator(Character.codePointAt(text, i))) i += 2
    while (i < n) {
        val cp = Character.codePointAt(text, i)
        if (cp == ZWJ) {
            i++
            if (i < n) i += Character.charCount(Character.codePointAt(text, i))
        } else if (isExtender(cp)) {
            i += Character.charCount(cp)
        } else {
            break
        }
    }
    return i
}

/**
 * Start of the grapheme cluster that holds the char at [o] (the counterpart of [clusterEnd], same rules): an [o] in
 * the middle of a surrogate pair, after a base letter's combining mark, behind a ZWJ or on the second regional
 * indicator of a flag moves back to the cluster's first char. Clamped to 0 .. `text.length`; an [o] at or past the
 * end returns the length.
 */
fun clusterStart(text: CharSequence, o: Int): Int {
    val n = text.length
    if (o >= n) return n
    var i = o.coerceAtLeast(0)
    if (i > 0 && Character.isLowSurrogate(text[i]) && Character.isHighSurrogate(text[i - 1])) i--
    // Back to a code point that starts a cluster by itself: no extender, not joined by a ZWJ before it.
    while (i > 0) {
        val cp = Character.codePointAt(text, i)
        val prev = Character.codePointBefore(text, i)
        if (isExtender(cp) || cp == ZWJ || prev == ZWJ) {
            i -= Character.charCount(prev)
        } else if (isRegionalIndicator(cp) && isRegionalIndicator(prev) && riRunBefore(text, i) % 2 == 1) {
            i -= Character.charCount(prev)
        } else {
            break
        }
    }
    // Forward from there with clusterEnd's own rules, so both sides always agree on where the clusters are.
    while (true) {
        val e = clusterEnd(text, i)
        if (e > o || e <= i) return i
        i = e
    }
}

/** How many regional indicators stand directly before [i]. */
private fun riRunBefore(text: CharSequence, i: Int): Int {
    var n = 0
    var k = i
    while (k > 0) {
        val cp = Character.codePointBefore(text, k)
        if (!isRegionalIndicator(cp)) break
        n++
        k -= Character.charCount(cp)
    }
    return n
}

private fun isRegionalIndicator(cp: Int): Boolean = cp in 0x1F1E6..0x1F1FF

private fun isExtender(cp: Int): Boolean {
    if (cp in 0xFE00..0xFE0F || cp in 0xE0100..0xE01EF || cp in 0x1F3FB..0x1F3FF) return true
    return when (Character.getType(cp).toByte()) {
        Character.NON_SPACING_MARK, Character.COMBINING_SPACING_MARK, Character.ENCLOSING_MARK -> true
        else -> false
    }
}
