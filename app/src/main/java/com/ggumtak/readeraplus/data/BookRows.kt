package com.ggumtak.readeraplus.data

import android.database.Cursor
import com.ggumtak.readeraplus.format.BookFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Cursor → model mapping for the column lists in [LibrarySql]. */
internal object BookRows {

    /** Reads a row selected with [LibrarySql.BOOK_COLUMNS] (columns 0 until 23). */
    fun book(c: Cursor): Book {
        val fileName = c.getString(2) ?: ""
        return Book(
            id = c.getLong(0),
            path = c.getString(1) ?: "",
            fileName = fileName,
            title = (c.getString(3) ?: "").ifEmpty { fileName.substringBeforeLast('.', fileName) },
            author = c.getString(4) ?: "",
            series = if (c.isNull(5)) null else c.getString(5),
            seriesIndex = if (c.isNull(6)) null else c.getFloat(6),
            format = format(c.getString(7), fileName),
            sizeBytes = c.getLong(8),
            modifiedAt = c.getLong(9),
            addedAt = c.getLong(10),
            lastReadAt = c.getLong(11),
            posSection = c.getInt(12),
            posOffset = c.getInt(13),
            progress = c.getFloat(14),
            favorite = c.getInt(15) != 0,
            toRead = c.getInt(16) != 0,
            haveRead = c.getInt(17) != 0,
            trashed = c.getInt(18) != 0,
            review = c.getString(19) ?: "",
            encoding = c.getString(20) ?: "",
            language = if (c.isNull(21)) null else c.getString(21),
            readingSeconds = c.getLong(22),
        )
    }

    /** Stored format name → enum; falls back to the file extension, then TXT. */
    fun format(stored: String?, fileName: String): BookFormat =
        BookFormat.entries.firstOrNull { it.name == stored }
            ?: BookFormat.forFile(fileName)
            ?: BookFormat.TXT

    /** Row of [LibrarySql.SELECT_BOOKMARKS]. */
    fun bookmark(c: Cursor): Bookmark = Bookmark(
        id = c.getLong(0),
        bookId = c.getLong(1),
        section = c.getInt(2),
        offset = c.getInt(3),
        snippet = c.getString(4) ?: "",
        createdAt = c.getLong(5),
        note = c.getString(6) ?: "",
    )

    /** Row of [LibrarySql.SELECT_QUOTES]. */
    fun quote(c: Cursor): Quote = Quote(
        id = c.getLong(0),
        bookId = c.getLong(1),
        section = c.getInt(2),
        start = c.getInt(3),
        end = c.getInt(4),
        text = c.getString(5) ?: "",
        note = c.getString(6) ?: "",
        createdAt = c.getLong(7),
    )

    /** Row of [LibrarySql.SELECT_COLLECTIONS] (4 columns) or a by-name/by-id select (3 columns). */
    fun collection(c: Cursor): BookCollection = BookCollection(
        id = c.getLong(0),
        name = c.getString(1) ?: "",
        createdAt = c.getLong(2),
        bookCount = if (c.columnCount > 3) c.getInt(3) else 0,
    )
}

/**
 * Page-count arrays ⇄ BLOB (little-endian int32 per section). Since R2 (A2) an array may be partial: [UNKNOWN]
 * marks a section not counted yet (the reader's `PageCounts.setKnown` takes only the entries ≥ 0).
 */
internal object PageCountCodec {
    /** A section whose pages were not counted yet. */
    const val UNKNOWN = -1

    fun encode(counts: IntArray): ByteArray {
        val buf = ByteBuffer.allocate(counts.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (v in counts) buf.putInt(v)
        return buf.array()
    }

    /** Null when the blob is malformed (length not a multiple of 4, or a value below [UNKNOWN]). */
    fun decode(blob: ByteArray?): IntArray? {
        if (blob == null || blob.size % 4 != 0) return null
        val buf = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN)
        val out = IntArray(blob.size / 4)
        for (i in out.indices) {
            val v = buf.getInt()
            if (v < UNKNOWN) return null
            out[i] = v
        }
        return out
    }
}
