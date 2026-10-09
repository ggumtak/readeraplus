package com.ggumtak.readeraplus.reader.extras

import java.text.Collator
import java.util.Locale

/**
 * Pure. The entries of the chooser before "웹 검색": the Naver Dictionary app first, then the PROCESS_TEXT apps by
 * label (always the same order), minus the ones the user hid (`extras.lookupHidden`, seeded once by label).
 */
internal object LookupList {
    /** The key of the Naver Dictionary app entry; app entries are keyed "package/Activity". */
    const val NAVERDIC_KEY = "naverdic"
    const val NAVERDIC_PKG = "com.nhn.android.naverdic"
    const val NAVERDIC_LABEL = "네이버 사전"

    /** Labels hidden the first time the list is used (Latin names compare case-insensitively). */
    private val SEED_LABELS = listOf("ChatGPT", "Grok", "Copilot", "삼성 패스")

    /** One entry: its [key], the [label] it is listed and sorted by, and the [app] name when it differs. */
    data class Entry(val key: String, val label: String, val app: String = "")

    /** [naver] (when installed) first, then [apps] by label (Korean collation; the key breaks a tie). */
    fun order(naver: Entry?, apps: List<Entry>): List<Entry> {
        val collator = Collator.getInstance(Locale.KOREAN)
        val sorted = apps.sortedWith { a, b ->
            val c = collator.compare(a.label, b.label)
            if (c != 0) c else a.key.compareTo(b.key)
        }
        return if (naver == null) sorted else listOf(naver) + sorted
    }

    /** The entries whose key is not in [hidden], in order. */
    fun visible(entries: List<Entry>, hidden: Set<String>): List<Entry> = entries.filter { it.key !in hidden }

    /** The keys of the entries whose label holds one of the seeded names. */
    fun seed(entries: List<Entry>): Set<String> =
        entries.filter { e -> SEED_LABELS.any { e.label.contains(it, ignoreCase = true) } }.map { it.key }.toSet()

    /** The hidden keys: the [stored] set, or (nothing stored yet) the [seed] of [entries]. */
    fun hidden(stored: Set<String>?, entries: List<Entry>): Set<String> = stored ?: seed(entries)

    /** "label (app)" when the app name adds something, else the label. */
    fun title(e: Entry): String = if (e.app.isNotEmpty() && e.app != e.label) "${e.label} (${e.app})" else e.label
}
