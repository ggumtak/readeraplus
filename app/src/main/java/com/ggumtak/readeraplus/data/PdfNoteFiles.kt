package com.ggumtak.readeraplus.data

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * The PDF viewer's per-book notes files, as the rest of the app (library removal, backup) sees them: ink strokes and
 * bookmarks live in `filesDir/pdf_notes/<bookId>.json`, written by `PdfNotesStore` (reader/pdf; same path rule, an
 * empty notes object deletes the file). Everything here is blocking file IO (IO thread) and never throws.
 *
 * The functions come in two forms: with a [Context] (the app's files dir) and with an explicit `dir` (unit tests).
 */
internal object PdfNoteFiles {
    const val DIR = "pdf_notes"

    /** One book's notes text carried by a backup, in chars: a larger file stays out of the backup (it stays on the device). */
    const val MAX_BACKUP_BOOK_CHARS = 8_000_000

    /**
     * All notes texts of one backup file together, in chars: the viewer writes ASCII (about 1 byte a char), and even
     * 3 bytes a char stays below the restore's 64 MB guard ([Backup.MAX_BYTES]).
     */
    const val MAX_BACKUP_TOTAL_CHARS = 20_000_000

    fun dir(context: Context): File = File(context.filesDir, DIR)

    fun file(dir: File, bookId: Long): File = File(dir, "$bookId.json")

    fun file(context: Context, bookId: Long): File = file(dir(context), bookId)

    fun exists(context: Context, bookId: Long): Boolean = exists(dir(context), bookId)

    fun exists(dir: File, bookId: Long): Boolean = try {
        file(dir, bookId).isFile
    } catch (e: Exception) {
        false
    }

    /** Deletes the book's notes file (best effort; a missing file is fine). */
    fun delete(context: Context, bookId: Long) = delete(dir(context), bookId)

    fun delete(dir: File, bookId: Long) {
        try {
            val f = file(dir, bookId)
            if (f.exists()) f.delete()
        } catch (e: Exception) {
            // best effort
        }
    }

    /** The notes text; null when there is no file, it is blank, unreadable or longer than [maxChars]. */
    fun readText(context: Context, bookId: Long, maxChars: Int = Int.MAX_VALUE): String? =
        readText(dir(context), bookId, maxChars)

    fun readText(dir: File, bookId: Long, maxChars: Int = Int.MAX_VALUE): String? = try {
        val f = file(dir, bookId)
        // Bytes >= chars: a file longer than the cap in bytes is skipped before it is read into memory.
        if (!f.isFile || f.length() > maxChars) {
            null
        } else {
            f.readText(Charsets.UTF_8).takeIf { it.isNotBlank() && it.length <= maxChars }
        }
    } catch (e: Exception) {
        null
    }

    /**
     * Writes [text] as the book's notes file atomically (temp file + rename, creating the folder). The temp name
     * differs from the viewer's own (`<id>.json.tmp`), so a write of the viewer never meets this one's file.
     * Returns false on any IO failure.
     */
    fun writeText(context: Context, bookId: Long, text: String): Boolean = writeText(dir(context), bookId, text)

    fun writeText(dir: File, bookId: Long, text: String): Boolean {
        val tmp = File(dir, "$bookId.json.restore.tmp")
        return try {
            if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) return false
            val target = file(dir, bookId)
            tmp.writeText(text, Charsets.UTF_8)
            if (tmp.renameTo(target)) {
                true
            } else {
                target.delete()
                val ok = tmp.renameTo(target)
                if (!ok) tmp.delete()
                ok
            }
        } catch (e: Exception) {
            try {
                tmp.delete()
            } catch (_: Exception) {
            }
            false
        }
    }

    /**
     * Pure: whether [text] is a notes object holding something — at least one bookmark or one stroke (the shape
     * `PdfNotes.toJson` writes: `{"v":1,"bookmarks":[…],"pages":{"<page>":[strokes]}}`). Not JSON, or an empty
     * object, is nothing: the viewer reads such a file as no notes at all.
     */
    fun hasContent(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        val o = try {
            JSONObject(text)
        } catch (e: Exception) {
            return false
        }
        if ((o.optJSONArray("bookmarks")?.length() ?: 0) > 0) return true
        val pages = o.optJSONObject("pages") ?: return false
        val keys = pages.keys()
        while (keys.hasNext()) {
            if ((pages.optJSONArray(keys.next())?.length() ?: 0) > 0) return true
        }
        return false
    }

    /**
     * Pure: whether a restore writes the backup's notes [backup] over the device's file [local] (null = none). A
     * restore never deletes: notes already on the device stay (the device's own strokes are the newer ones in the
     * cases that matter — same device, or a device the user kept drawing on); the backup's fill in only where the
     * device has nothing, a corrupt or empty file included (the viewer reads those as no notes).
     */
    fun shouldRestore(local: String?, backup: String?): Boolean = hasContent(backup) && !hasContent(local)

    /**
     * Restores the backup's notes [text] of the book whose id on this device is [bookId] ([shouldRestore]); returns
     * whether the file was written.
     */
    fun restore(context: Context, bookId: Long, text: String): Boolean = restore(dir(context), bookId, text)

    fun restore(dir: File, bookId: Long, text: String): Boolean =
        shouldRestore(readText(dir, bookId), text) && writeText(dir, bookId, text)
}
