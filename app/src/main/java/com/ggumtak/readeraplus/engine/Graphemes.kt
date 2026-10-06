package com.ggumtak.readeraplus.engine

/*
 * Grapheme-cluster end for selections (pure, no android.*): a selection that ends at `o + 1` can cut a surrogate
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

private fun isRegionalIndicator(cp: Int): Boolean = cp in 0x1F1E6..0x1F1FF

private fun isExtender(cp: Int): Boolean {
    if (cp in 0xFE00..0xFE0F || cp in 0xE0100..0xE01EF || cp in 0x1F3FB..0x1F3FF) return true
    return when (Character.getType(cp).toByte()) {
        Character.NON_SPACING_MARK, Character.COMBINING_SPACING_MARK, Character.ENCLOSING_MARK -> true
        else -> false
    }
}
