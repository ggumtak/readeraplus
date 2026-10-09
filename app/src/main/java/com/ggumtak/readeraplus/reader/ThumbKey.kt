package com.ggumtak.readeraplus.reader

/**
 * Thumbnail LRU key (NOTES_SPEC §12.6; unit-tested). [genId] covers layout changes (settings, rotation, TXT
 * re-parse); [paintVersion] the repaint-only ones (day/night, colours, weight stroke) that keep the generation;
 * [decorVersion] is the section's quote/bookmark version; [quoteLook] is `QuoteLook.generation` (ink vs colour fills).
 */
internal data class ThumbKey(
    val genId: Int,
    val section: Int,
    val pageIndex: Int,
    val wPx: Int,
    val hPx: Int,
    val decorVersion: Int,
    val paintVersion: Int,
    val quoteLook: Int,
)
