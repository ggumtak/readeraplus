package com.ggumtak.readeraplus.reader

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import com.ggumtak.readeraplus.data.Book
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
    private const val MAX_COPIES = 50

    fun resolveBook(context: Context, intent: Intent): Book {
        val id = intent.getLongExtra(ReaderActivity.EXTRA_BOOK_ID, -1L)
        if (id > 0) return Library.book(id) ?: throw DocumentException("서재에서 책을 찾을 수 없습니다.")
        val uri = intent.data ?: throw DocumentException("열 파일이 지정되지 않았습니다.")
        val file = fileFor(context, uri) ?: throw DocumentException("파일을 읽을 수 없습니다.\n$uri")
        return Library.addOrUpdateFile(file) ?: throw DocumentException("지원하지 않는 파일입니다: ${file.name}")
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
        val (name, size) = nameAndSize(context, uri)
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
        // Reuse an earlier copy only when its size is known to match; never overwrite a same-named copy of a
        // different book (the library, its position and bookmarks point at that file).
        val (name0, reuse) = UriPaths.copyTarget(safe, size, MAX_COPIES) { lengthIn(dir, it) }
        if (reuse) return File(dir, name0)
        var target = File(dir, name0)
        val tmp = File(dir, "${target.name}.part")
        try {
            val input = context.contentResolver.openInputStream(uri) ?: return null
            input.use { ins -> FileOutputStream(tmp).use { out -> ins.copyTo(out, 64 * 1024) } }
            // Another import may have taken the name while the stream was copied: rename() would replace it.
            if (target.exists()) target = File(dir, UriPaths.copyTarget(safe, -1L, 0) { lengthIn(dir, it) }.first)
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = false)
                tmp.delete()
            }
            return target
        } catch (t: Throwable) {
            Log.w(TAG, "copy failed for $uri", t)
            tmp.delete()
            return null
        }
    }

    /** Length of [name] in [dir]: null when free, -1 when taken by something that is not a file. */
    private fun lengthIn(dir: File, name: String): Long? {
        val f = File(dir, name)
        return when {
            !f.exists() -> null
            f.isFile -> f.length()
            else -> -1L
        }
    }
}
