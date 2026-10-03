package com.ggumtak.readeraplus.data

/**
 * The restore merge rules of N §5.6 (pure; [Backup.import] runs the SQL). A restore is a **union: nothing is ever
 * deleted**, and where both sides hold a value the newer side wins:
 * - position, `trashed`, `to_read`, `have_read`, `encoding`: the device's stay when it read the book later than the
 *   backup ([deviceNewer]); `favorite` = OR; the review by its write time;
 * - quotes match on `section:start:end`, then on the same text under another place signature (two devices with other
 *   TXT options store the same quote at other offsets); bookmarks on `section,offset`; a match only fills what the
 *   device lacks (style onto 0, place onto `frac < 0`, note onto empty);
 * - lookups are deduplicated on (word key, section, start, createdAt);
 * - a backup book with notes whose file is not here becomes a placeholder (trashed, missing) holding them.
 */
internal object BackupMerge {

    /** The device row a backup entry is merged into. */
    data class BookState(
        val lastReadAt: Long = 0,
        val favorite: Boolean = false,
        val toRead: Boolean = false,
        val haveRead: Boolean = false,
        val trashed: Boolean = false,
        val review: String = "",
        val reviewAt: Long = 0,
        val encoding: String = "",
        val missingAt: Long = 0,
    )

    /** What the restore writes to the book row; [applyPosition] = also write the backup's position. */
    data class BookResult(
        val favorite: Boolean,
        val toRead: Boolean,
        val haveRead: Boolean,
        val trashed: Boolean,
        val review: String,
        val reviewAt: Long,
        val encoding: String,
        val missingAt: Long,
        val applyPosition: Boolean,
        val deviceNewer: Boolean,
    )

    /** This device read the book later than the backup did: its position and reading flags stay. */
    fun deviceNewer(currentLastReadAt: Long, backupLastReadAt: Long): Boolean = currentLastReadAt > backupLastReadAt

    /** Position newer-wins: the backup's applies when it is newer, or the device never read the book. */
    fun appliesPosition(currentLastReadAt: Long, backupLastReadAt: Long): Boolean =
        backupLastReadAt > currentLastReadAt || currentLastReadAt == 0L

    /**
     * The merged book row. [fileFound] = the resolver matched the entry to a file present on this device: a backup
     * entry trashed because its file was missing (`missingAt > 0`) comes back out of the trash then.
     */
    fun book(cur: BookState, b: BackupBook, fileFound: Boolean): BookResult {
        val deviceNewer = deviceNewer(cur.lastReadAt, b.lastReadAt)
        val haveRead = if (deviceNewer) cur.haveRead else b.haveRead
        val toRead = (if (deviceNewer) cur.toRead else b.toRead) && !haveRead
        val encoding = if (deviceNewer) cur.encoding else b.encoding
        val userTrashedHere = cur.trashed && cur.missingAt == 0L
        var trashed: Boolean
        var missingAt: Long
        if (deviceNewer) {
            trashed = cur.trashed
            missingAt = cur.missingAt
        } else {
            trashed = b.trashed
            // A user's trash (missing_at 0) must never become revivable by the scanner.
            missingAt = if (!b.trashed) 0L else if (userTrashedHere) 0L else b.missingAt
        }
        if (b.missingAt > 0 && fileFound && !(deviceNewer && userTrashedHere)) {
            trashed = false
            missingAt = 0L
        }
        if (!trashed) missingAt = 0L
        val takeReview = b.review.isNotBlank() && (cur.review.isEmpty() || b.reviewAt > cur.reviewAt)
        return BookResult(
            favorite = cur.favorite || b.favorite,
            toRead = toRead,
            haveRead = haveRead,
            trashed = trashed,
            review = if (takeReview) b.review else cur.review,
            reviewAt = if (takeReview) maxOf(cur.reviewAt, b.reviewAt) else cur.reviewAt,
            encoding = encoding,
            missingAt = missingAt,
            applyPosition = appliesPosition(cur.lastReadAt, b.lastReadAt),
            deviceNewer = deviceNewer,
        )
    }

    /** A backup book holding at least one note (quote, bookmark, review or lookup). */
    fun hasNotes(b: BackupBook): Boolean =
        b.quotes.isNotEmpty() || b.bookmarks.isNotEmpty() || b.review.isNotBlank() || b.lookups.isNotEmpty()

    /** An unresolved backup book becomes a placeholder only when it carries notes (and a path to find it by). */
    fun needsPlaceholder(b: BackupBook): Boolean = b.path.isNotEmpty() && hasNotes(b)

    // ---- quotes ----

    /** A fill of an existing quote; null fields stay as they are. */
    data class QuoteFill(val id: Long, val style: Int?, val place: NotePlace?, val note: String?)

    class QuotePlan(val inserts: List<BackupQuote>, val fills: List<QuoteFill>)

    fun quotes(existing: List<Quote>, incoming: List<BackupQuote>): QuotePlan {
        // Entries: an existing row (its fill accumulates) or an insert (index into inserts).
        class Entry(val id: Long, var style: Int, var frac: Float, var note: String, val text: String, val sig: String,
                    val insert: Int, var filled: QuoteFill?)
        val inserts = ArrayList<BackupQuote>()
        val byKey = HashMap<String, Entry>()
        val byText = HashMap<String, MutableList<Entry>>()
        val fills = LinkedHashMap<Long, QuoteFill>()
        fun index(key: String, e: Entry) {
            byKey.putIfAbsent(key, e)
            byText.getOrPut(e.text) { ArrayList(1) } += e
        }
        for (q in existing) {
            index(quoteKey(q.section, q.start, q.end), Entry(q.id, q.style, q.frac, q.note, q.text, q.sig, -1, null))
        }
        for (q in incoming) {
            val key = quoteKey(q.section, q.start, q.end)
            val e = byKey[key] ?: byText[q.text]?.firstOrNull { it.sig != q.sig }
            if (e == null) {
                inserts += q
                index(key, Entry(-1, q.style, q.frac, q.note, q.text, q.sig, inserts.size - 1, null))
                continue
            }
            val style = if (e.style == 0 && q.style != 0) q.style else null
            val place = if (e.frac < 0f && q.frac >= 0f) NotePlace(q.chapter, q.frac, q.sig) else null
            val note = if (e.note.isEmpty() && q.note.isNotEmpty()) q.note else null
            if (style == null && place == null && note == null) continue
            if (style != null) e.style = style
            if (place != null) e.frac = place.frac
            if (note != null) e.note = note
            if (e.insert >= 0) {
                val cur = inserts[e.insert]
                inserts[e.insert] = cur.copy(
                    style = style ?: cur.style,
                    chapter = place?.chapter ?: cur.chapter, frac = place?.frac ?: cur.frac, sig = place?.sig ?: cur.sig,
                    note = note ?: cur.note,
                )
            } else {
                val prev = fills[e.id]
                fills[e.id] = QuoteFill(e.id, style ?: prev?.style, place ?: prev?.place, note ?: prev?.note)
            }
        }
        return QuotePlan(inserts, fills.values.toList())
    }

    fun quoteKey(section: Int, start: Int, end: Int): String = "$section:$start:$end"

    // ---- bookmarks ----

    data class BookmarkFill(val id: Long, val place: NotePlace?, val note: String?)

    class BookmarkPlan(val inserts: List<BackupBookmark>, val fills: List<BookmarkFill>)

    fun bookmarks(existing: List<Bookmark>, incoming: List<BackupBookmark>): BookmarkPlan {
        class Entry(val id: Long, var frac: Float, var note: String, val insert: Int)
        val inserts = ArrayList<BackupBookmark>()
        val byKey = HashMap<Long, Entry>()
        val fills = LinkedHashMap<Long, BookmarkFill>()
        for (m in existing) byKey.putIfAbsent(bookmarkKey(m.section, m.offset), Entry(m.id, m.frac, m.note, -1))
        for (m in incoming) {
            val key = bookmarkKey(m.section, m.offset)
            val e = byKey[key]
            if (e == null) {
                inserts += m
                byKey[key] = Entry(-1, m.frac, m.note, inserts.size - 1)
                continue
            }
            val place = if (e.frac < 0f && m.frac >= 0f) NotePlace(m.chapter, m.frac, m.sig) else null
            val note = if (e.note.isEmpty() && m.note.isNotEmpty()) m.note else null
            if (place == null && note == null) continue
            if (place != null) e.frac = place.frac
            if (note != null) e.note = note
            if (e.insert >= 0) {
                val cur = inserts[e.insert]
                inserts[e.insert] = cur.copy(
                    chapter = place?.chapter ?: cur.chapter, frac = place?.frac ?: cur.frac, sig = place?.sig ?: cur.sig,
                    note = note ?: cur.note,
                )
            } else {
                val prev = fills[e.id]
                fills[e.id] = BookmarkFill(e.id, place ?: prev?.place, note ?: prev?.note)
            }
        }
        return BookmarkPlan(inserts, fills.values.toList())
    }

    fun bookmarkKey(section: Int, offset: Int): Long = section.toLong() shl 32 or (offset.toLong() and 0xffffffffL)

    // ---- lookups ----

    fun lookupKey(wordKey: String, section: Int, start: Int, createdAt: Long): String =
        "$wordKey\u0000$section\u0000$start\u0000$createdAt"

    /**
     * The lookups to insert, each with its word key: those whose (word key, section, start, createdAt) is neither in
     * [existingKeys] ([lookupKey] of the device's rows) nor earlier in [incoming].
     */
    fun lookups(existingKeys: Set<String>, incoming: List<BackupLookup>, wordKey: (String) -> String):
        List<Pair<BackupLookup, String>> {
        val seen = HashSet(existingKeys)
        val out = ArrayList<Pair<BackupLookup, String>>()
        for (l in incoming) {
            val k = wordKey(l.word)
            if (seen.add(lookupKey(k, l.section, l.start, l.createdAt))) out += l to k
        }
        return out
    }
}
