package com.ggumtak.readeraplus.reader

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import com.ggumtak.readeraplus.data.Book
import com.ggumtak.readeraplus.data.BookCopies
import com.ggumtak.readeraplus.data.Library
import com.ggumtak.readeraplus.format.DocumentException
import java.io.File
import java.io.FileOutputStream

/**
 * Resolves what the reader should open from its intent: a library id, a file:// uri or a content:// uri
 * (real path when it can be derived and read, else a copy under getExternalFilesDir("books")). Blocking IO.
 */
internal object IntentFiles {
    private const val TAG = "IntentFiles"

    fun resolveBook(context: Context, intent: Intent): Book {
        val id = intent.getLongExtra(ReaderActivity.EXTRA_BOOK_ID, -1L)
        // The reason only (the panel's title says the book could not be opened); a second line is the URI.
        if (id > 0) return Library.book(id) ?: throw DocumentException("서재에 없는 책입니다")
        val uri = intent.data ?: throw DocumentException("열 파일이 없습니다")
        val file = fileFor(context, uri) ?: throw DocumentException("파일을 읽지 못했습니다\n$uri")
        return Library.addOrUpdateFile(file) ?: throw DocumentException("TXT·EPUB 파일만 열 수 있습니다")
    }

    fun fileFor(context: Context, uri: Uri): File? = when (uri.scheme?.lowercase()) {
        "file" -> uri.path?.let { File(it) }?.takeIf { it.isFile && it.canRead() }
        "content" -> realFile(context, uri) ?: copyToBooks(context, uri)
        null -> uri.path?.let { File(it) }?.takeIf { it.isFile && it.canRead() }
        else -> null
    }

    private fun realFile(context: Context, uri: Uri): File? {
        val (_, size) = nameAndSize(context, uri)
        val candidates = ArrayList<String>(3)
        try {
            if (DocumentsContract.isDocumentUri(context, uri)) {
                UriPaths.fromDocumentId(DocumentsContract.getDocumentId(uri))?.let { candidates += it }
            }
        } catch (t: Throwable) {
            Log.d(TAG, "document id failed", t)
        }
        dataColumn(context, uri)?.let { candidates += it }
        UriPaths.fromUriPath(uri.path)?.let { candidates += it }
        for (c in candidates) {
            val f = File(c)
            try {
                if (f.isFile && f.canRead() && (size <= 0 || f.length() == size)) return f
            } catch (_: SecurityException) {
            }
        }
        return null
    }

    private fun dataColumn(context: Context, uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf("_data"), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val i = c.getColumnIndex("_data")
                if (i >= 0) c.getString(i) else null
            } else {
                null
            }
        }
    } catch (t: Throwable) {
        null
    }

    private fun nameAndSize(context: Context, uri: Uri): Pair<String?, Long> = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val si = c.getColumnIndex(OpenableColumns.SIZE)
                    val name = if (ni >= 0) c.getString(ni) else null
                    val size = if (si >= 0 && !c.isNull(si)) c.getLong(si) else -1L
                    name to size
                } else {
                    null
                }
            } ?: (null to -1L)
    } catch (t: Throwable) {
        null to -1L
    }

    /** Copies the stream into the app's books folder (reusing an identical earlier copy). */
    private fun copyToBooks(context: Context, uri: Uri): File? {
        val (name, _) = nameAndSize(context, uri)
        val mime = try {
            context.contentResolver.getType(uri)
        } catch (t: Throwable) {
            null
        }
        val fallback = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: "book_${System.currentTimeMillis()}"
        val safe = UriPaths.safeFileName(name ?: fallback, mime, "book_${System.currentTimeMillis()}")
        val dir = context.getExternalFilesDir("books") ?: File(context.filesDir, "books")
        if (!dir.isDirectory && !dir.mkdirs()) return null
        // Written to a temp file first: an identical earlier copy is reused (a corrected edition of the same size
        // is not identical); otherwise the temp file is moved to the first free name, never over another book
        // (the library, its position and bookmarks point at that file).
        val tmp = File(dir, "${BookCopies.COPY_PREFIX}${System.nanoTime()}${BookCopies.PART_SUFFIX}")
        try {
            val input = context.contentResolver.openInputStream(uri) ?: return null
            input.use { ins -> FileOutputStream(tmp).use { out -> ins.copyTo(out, 64 * 1024) } }
            return BookCopies.settle(tmp, dir, safe)
        } catch (t: Throwable) {
            Log.w(TAG, "copy failed for $uri", t)
            tmp.delete()
            return null
        }
    }
}
