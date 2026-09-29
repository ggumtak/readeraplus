package com.ggumtak.readeraplus.data

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.ggumtak.readeraplus.format.BookFormat
import java.io.File
import java.io.FileNotFoundException

/**
 * Read-only provider so "파일 공유" can hand book files to other apps without AndroidX
 * FileProvider. URI: content://<applicationId>.files/book/<bookId>. Only files of library entries are served;
 * the provider is not exported, access comes from FLAG_GRANT_READ_URI_PERMISSION on the share intent.
 */
class BookFileProvider : ContentProvider() {
    companion object {
        /** Authority suffix after the application id (see AndroidManifest). */
        const val AUTHORITY_SUFFIX = ".files"
        internal const val PATH_BOOK = "book"
        internal const val MIME_EPUB = "application/epub+zip"
        internal const val MIME_TXT = "text/plain"

        fun uriFor(authorityPackage: String, bookId: Long): Uri = Uri.Builder()
            .scheme(ContentResolver.SCHEME_CONTENT)
            .authority(authorityPackage + AUTHORITY_SUFFIX)
            .appendPath(PATH_BOOK)
            .appendPath(bookId.toString())
            .build()

        /** Book id from the URI path segments (`book/<id>`), or null. */
        internal fun bookIdOf(segments: List<String>): Long? {
            if (segments.size != 2 || segments[0] != PATH_BOOK) return null
            return segments[1].toLongOrNull()?.takeIf { it > 0 }
        }

        internal fun mimeOf(format: BookFormat?): String? = when (format) {
            BookFormat.EPUB -> MIME_EPUB
            BookFormat.TXT -> MIME_TXT
            null -> null
        }

        /** The requested OpenableColumns we can answer (all of them when [projection] is null). */
        internal fun columnsFor(projection: Array<out String>?): Array<String> {
            if (projection == null) return arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
            val out = ArrayList<String>(2)
            for (c in projection) {
                if ((c == OpenableColumns.DISPLAY_NAME || c == OpenableColumns.SIZE) && c !in out) out += c
            }
            return out.toTypedArray()
        }
    }

    override fun onCreate(): Boolean = true

    private fun bookFor(uri: Uri): Book? {
        val ctx = context ?: return null
        val id = bookIdOf(uri.pathSegments ?: return null) ?: return null
        Library.init(ctx)
        return try {
            Library.book(id)
        } catch (_: Exception) {
            null
        }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        val book = bookFor(uri) ?: return null
        val cols = columnsFor(projection)
        val row = arrayOfNulls<Any>(cols.size)
        for (i in cols.indices) {
            row[i] = when (cols[i]) {
                OpenableColumns.DISPLAY_NAME -> book.fileName.ifEmpty { File(book.path).name }
                OpenableColumns.SIZE -> File(book.path).let { f -> if (f.isFile) f.length() else book.sizeBytes }
                else -> null
            }
        }
        return MatrixCursor(cols, 1).apply { addRow(row) }
    }

    override fun getType(uri: Uri): String? {
        val book = bookFor(uri) ?: return null
        return mimeOf(book.format)
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        if (mode.any { it == 'w' || it == 'a' || it == '+' }) throw FileNotFoundException("read-only: $mode")
        val book = bookFor(uri) ?: throw FileNotFoundException("no such book: $uri")
        val file = File(book.path)
        if (!file.isFile) throw FileNotFoundException("missing file: ${book.fileName}")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
