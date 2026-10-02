package com.ggumtak.readeraplus.data

import android.util.Log
import org.json.JSONObject

/**
 * TXT options for one book only (T1-9), stored as JSON in `book_prefs.txt_override`. Each field mirrors the
 * [com.ggumtak.readeraplus.settings.ReaderSettings] field of the same meaning (`txtBlankLines` → [blankLines], …);
 * null = "use the global default". The encoding is not here: it already is per book (`books.encoding`).
 *
 * Merged with the global settings in exactly one place, `reader/TxtOverrides.kt` (`ReaderSettings.withTxt`).
 * Pure and immutable; the JSON codec is tolerant (unknown keys ignored, wrongly typed values dropped, numbers
 * clamped to the ranges ReaderSettings allows).
 */
data class TxtOverride(
    /** `ReaderSettings.txtBlankLines`: one of `ParseOptions.BLANK_*` (0..3). */
    val blankLines: Int? = null,
    /** `ReaderSettings.txtStripIndent`. */
    val stripIndent: Boolean? = null,
    /** `ReaderSettings.txtJoinWrappedLines`: 0 off, 1 auto, 2 always. */
    val joinWrapped: Int? = null,
    /** `ReaderSettings.txtDetectChapters`. */
    val detectChapters: Boolean? = null,
    /** `ReaderSettings.txtChapterRegex` ("" = none, which differs from null = the global value). */
    val chapterRegex: String? = null,
    /** `ReaderSettings.txtEmphasizeHeadings`. */
    val emphasizeHeadings: Boolean? = null,
    /** `ReaderSettings.txtReplaceRules` (the whole rule text, `pattern => replacement` lines). */
    val replaceRules: String? = null,
) {
    /** True when every field follows the global defaults (such an override is stored as NULL). */
    val isEmpty: Boolean
        get() = blankLines == null && stripIndent == null && joinWrapped == null && detectChapters == null &&
            chapterRegex == null && emphasizeHeadings == null && replaceRules == null

    /** JSON object text with only the non-null fields ("{}" when [isEmpty]). */
    fun toJson(): String {
        val o = JSONObject()
        blankLines?.let { o.put(K_BLANK, it) }
        stripIndent?.let { o.put(K_STRIP, it) }
        joinWrapped?.let { o.put(K_JOIN, it) }
        detectChapters?.let { o.put(K_DETECT, it) }
        chapterRegex?.let { o.put(K_REGEX, it) }
        emphasizeHeadings?.let { o.put(K_EMPHASIZE, it) }
        replaceRules?.let { o.put(K_RULES, it) }
        return o.toString()
    }

    companion object {
        private const val K_BLANK = "blankLines"
        private const val K_STRIP = "stripIndent"
        private const val K_JOIN = "joinWrapped"
        private const val K_DETECT = "detectChapters"
        private const val K_REGEX = "chapterRegex"
        private const val K_EMPHASIZE = "emphasizeHeadings"
        private const val K_RULES = "replaceRules"

        /** Parses [toJson] text; null for null / blank / malformed text or an override without any field. */
        fun fromJson(text: String?): TxtOverride? {
            if (text.isNullOrBlank()) return null
            val o = try {
                JSONObject(text)
            } catch (_: Exception) {
                return null
            }
            val t = TxtOverride(
                blankLines = int(o, K_BLANK)?.coerceIn(0, 3),
                stripIndent = o.opt(K_STRIP) as? Boolean,
                joinWrapped = int(o, K_JOIN)?.coerceIn(0, 2),
                detectChapters = o.opt(K_DETECT) as? Boolean,
                chapterRegex = o.opt(K_REGEX) as? String,
                emphasizeHeadings = o.opt(K_EMPHASIZE) as? Boolean,
                replaceRules = o.opt(K_RULES) as? String,
            )
            return if (t.isEmpty) null else t
        }

        private fun int(o: JSONObject, key: String): Int? {
            val n = o.opt(key) as? Number ?: return null
            val d = n.toDouble()
            return if (d.isFinite() && d == Math.rint(d)) d.toInt() else null
        }
    }
}

/** A finished book (T1-2 / T1-6): [finishedAt] is epoch millis. */
data class FinishedBook(val bookId: Long, val finishedAt: Long)

/**
 * Per-book preferences in `book_prefs` (library DB v2, see LibrarySchema.CREATE_BOOK_PREFS): at most one row per
 * book, created on the first write and deleted with the book (`Library.deleteBookRows`) and exported by the backup
 * (keyed by path, like bookmarks). A row that no longer holds anything is dropped.
 *
 * Owner: DATA. Users: READER_A (open path, end panel), EXTRAS_TOOLS (popup through TxtOverrideHost), SETTINGS
 * (statistics). Every function is blocking (Dispatchers.IO), thread-safe and never throws for a missing row.
 * [txtOverride] runs on the open path: one primary-key read on the connection `Library` already has open.
 *
 * Writes (SQLite 3.18 has no UPSERT): `UPDATE` the column and, when no row changed, `INSERT` a row for a book that
 * still exists, in one transaction. A finish time counts only while the book is marked have_read: clearing that flag
 * clears it (`Library.setHaveRead`), and the reads ignore a leftover one.
 */
object BookPrefs {
    fun returnMark(bookId: Long): String? = null // R3 stub (owner: DA-C)
    fun setReturnMark(bookId: Long, value: String?) {} // R3 stub (owner: DA-C)

    private const val TAG = "BookPrefs"

    /** Longest stored override JSON (chars): the row must fit a 2 MB CursorWindow with room to spare. */
    internal const val MAX_OVERRIDE_CHARS = 200_000
    internal const val MAX_EPISODE_LABEL = 40

    /** This book's TXT override, or null when it follows the global TXT settings (no row, NULL or malformed JSON). */
    fun txtOverride(bookId: Long): TxtOverride? {
        val text = Library.db().queryFirst(LibrarySql.SELECT_TXT_OVERRIDE, args(bookId)) {
            if (it.isNull(0)) null else it.getString(0)
        } ?: return null
        return TxtOverride.fromJson(text)
    }

    /** Stores [o] for [bookId]; null or an empty override clears it (NULL), keeping the row's other columns. */
    fun setTxtOverride(bookId: Long, o: TxtOverride?) {
        val json = overrideJson(o)
        if (json != null && json.length > MAX_OVERRIDE_CHARS) {
            Log.w(TAG, "override of book $bookId too large (${json.length} chars): not stored")
            return
        }
        write(bookId, LibrarySql.SET_PREFS_TXT, LibrarySql.INSERT_PREFS_TXT, json, clears = json == null)
    }

    /** When [bookId] was finished (epoch millis), or 0 when it isn't marked finished. */
    fun finishedAt(bookId: Long): Long =
        Library.db().queryFirst(LibrarySql.SELECT_FINISHED_AT, args(bookId)) { it.getLong(0) }?.coerceAtLeast(0L) ?: 0L

    /** Marks [bookId] finished at [t] (epoch millis); 0 clears the mark (the end panel's 완독 toggle, "읽은 기록 초기화"). */
    fun setFinishedAt(bookId: Long, t: Long) {
        val v = t.coerceAtLeast(0L)
        write(bookId, LibrarySql.SET_PREFS_FINISHED, LibrarySql.INSERT_PREFS_FINISHED, v, clears = v == 0L)
    }

    /** Books finished in [fromMs, toMs) (epoch millis), newest first — "올해 다 읽은 책 N권". Excludes trashed books. */
    fun finishedBetween(fromMs: Long, toMs: Long): List<FinishedBook> {
        if (fromMs >= toMs) return emptyList()
        return Library.db().queryList(LibrarySql.SELECT_FINISHED_BETWEEN, args(fromMs, toMs)) {
            FinishedBook(it.getLong(0), it.getLong(1))
        }
    }

    /** The file-name episode badge (T2-13, e.g. "123/540화"); null clears it. */
    fun setEpisodeLabel(bookId: Long, label: String?) {
        val v = label?.let { MetaInfo.clean(it, MAX_EPISODE_LABEL) }?.ifEmpty { null }
        write(bookId, LibrarySql.SET_PREFS_EPISODE, LibrarySql.INSERT_PREFS_EPISODE, v, clears = v == null)
    }

    /** The stored text of [o]: null for null / empty (the column's NULL = follow the global settings). */
    internal fun overrideJson(o: TxtOverride?): String? = if (o == null || o.isEmpty) null else o.toJson()

    /**
     * One-column write: [update] (args: value, book id) and, when no row changed, [insert] (same args) unless the
     * value [clears] the column (no row = nothing stored already). A cleared column may leave an empty row: pruned.
     */
    private fun write(bookId: Long, update: String, insert: String, value: Any?, clears: Boolean) {
        if (bookId <= 0) return
        Library.db().inTransaction {
            if (exec(update, value, bookId) > 0) {
                if (clears) exec(LibrarySql.PRUNE_BOOK_PREFS, bookId)
            } else if (!clears) {
                insertRow(insert, value, bookId)
            }
        }
    }
}
