package com.ggumtak.readeraplus.reader

import com.ggumtak.readeraplus.data.TxtOverride
import com.ggumtak.readeraplus.settings.ReaderSettings

/**
 * The effective settings of a book with TXT options of its own (T1-9): this (the global settings) with every
 * non-null field of [o] in place of the global TXT option. Null or an empty override returns this very object.
 *
 * The only place the global settings and a book's override are merged: the open path (`startOpen`), the relayout
 * check in onResume / the settings listener and the reading-settings popup all go through it, so the effective
 * settings of a book compare equal wherever they were computed (no relayout loop). Pure; any thread.
 */
fun ReaderSettings.withTxt(o: TxtOverride?): ReaderSettings {
    if (o == null || o.isEmpty) return this
    val merged = copy(
        txtBlankLines = o.blankLines ?: txtBlankLines,
        txtStripIndent = o.stripIndent ?: txtStripIndent,
        txtJoinWrappedLines = o.joinWrapped ?: txtJoinWrappedLines,
        txtDetectChapters = o.detectChapters ?: txtDetectChapters,
        txtChapterRegex = o.chapterRegex ?: txtChapterRegex,
        txtEmphasizeHeadings = o.emphasizeHeadings ?: txtEmphasizeHeadings,
        txtReplaceRules = o.replaceRules ?: txtReplaceRules,
    )
    // Same values as the global settings: hand back the same instance (cheap identity checks stay true).
    return if (merged == this) this else merged
}
