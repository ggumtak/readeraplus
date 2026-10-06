package com.ggumtak.readeraplus.ui.library

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.ggumtak.readeraplus.data.Book
import com.ggumtak.readeraplus.data.BookCopies
import com.ggumtak.readeraplus.data.Library
import java.io.File
import java.io.FileOutputStream

/**
 * Bringing documents picked through the Storage Access Framework into the library (blocking; IO thread).
 * A document that is readable in place (all-files access, or a `primary:` path we can read) is added by
 * path — no copy; otherwise it is copied into the app's `books` folder under its display name (an existing
 * copy with identical content is reused).
 */
internal object LibraryImport {
    private const val MIN_TXT_BYTES = 1024L
    private const val MAX_TREE_DEPTH = 12

    /** Where copied books live: external app files ("Android/data/…/files/books") or internal files. */
    fun booksDir(context: Context): File {
        val dir = context.getExternalFilesDir("books") ?: File(context.filesDir, "books")
        if (!dir.isDirectory) dir.mkdirs()
        return dir
    }

    private fun primaryRoot(): String =
        try {
            Environment.getExternalStorageDirectory().absolutePath
        } catch (t: Throwable) {
            "/storage/emulated/0"
        }

    /** Real, readable file behind [uri] when one can be derived; else null. */
    fun readableFile(context: Context, uri: Uri): File? {
        val path: String? = try {
            when {
                uri.scheme == "file" -> uri.path
                DocumentsContract.isDocumentUri(context, uri) ->
                    LibraryText.docIdToPath(uri.authority, DocumentsContract.getDocumentId(uri), primaryRoot())
                else -> dataColumn(context, uri)
            }
        } catch (t: Throwable) {
            null
        }
        val f = path?.let { File(it) } ?: return null
        return try {
            if (f.isFile && f.canRead() && LibraryText.isBookName(f.name)) f else null
        } catch (t: SecurityException) {
            null
        }
    }

    @Suppress("DEPRECATION")
    private fun dataColumn(context: Context, uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(android.provider.MediaStore.MediaColumns.DATA), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (t: Throwable) {
        null
    }

    private class NameSize(val name: String?, val size: Long)

    private fun nameAndSize(context: Context, uri: Uri): NameSize {
        var name: String? = null
        var size = -1L
        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    if (!c.isNull(0)) name = c.getString(0)
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
        } catch (t: Throwable) {
            // provider without OpenableColumns
        }
        if (name == null) name = uri.lastPathSegment?.substringAfterLast('/')
        return NameSize(name, size)
    }

    /**
     * Adds one picked document; returns the library entry or null when the type is unsupported or reading
     * failed. [knownName]/[knownSize] skip the provider query (tree import already has them).
     */
    fun importDocument(context: Context, uri: Uri, knownName: String? = null, knownSize: Long = -1, knownMime: String? = null): Book? {
        readableFile(context, uri)?.let { return Library.addOrUpdateFile(it) }
        val ns = if (knownName != null) NameSize(knownName, knownSize) else nameAndSize(context, uri)
        val mime = knownMime ?: try { context.contentResolver.getType(uri) } catch (t: Throwable) { null }
        val fileName = LibraryText.importFileName(ns.name, mime) ?: return null
        val dir = booksDir(context)
        val part = File(dir, ".import-${System.nanoTime()}.part")
        val target = try {
            val input = context.contentResolver.openInputStream(uri) ?: return null
            input.use { ins ->
                FileOutputStream(part).use { out ->
                    val buf = ByteArray(128 * 1024)
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                    }
                }
            }
            // An identical earlier copy is reused; a same-named different book (even of equal size) gets a new name.
            BookCopies.settle(part, dir, fileName)
        } catch (t: Throwable) {
            part.delete()
            throw t
        }
        return Library.addOrUpdateFile(target)
    }

    private class Doc(val id: String, val name: String, val mime: String?, val size: Long)

    /**
     * Imports every EPUB/TXT below the SAF tree [tree] (recursively, hidden folders skipped).
     * Returns the number of documents added.
     */
    fun importTree(context: Context, tree: Uri, progress: (Int, Int) -> Unit): Int {
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        val found = ArrayList<Doc>()
        val stack = ArrayDeque<Pair<String, Int>>()
        stack.addLast(rootId to 0)
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        while (stack.isNotEmpty()) {
            val (docId, depth) = stack.removeLast()
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
            try {
                context.contentResolver.query(children, cols, null, null, null)?.use { c ->
                    while (c.moveToNext()) {
                        val id = c.getString(0) ?: continue
                        val name = c.getString(1) ?: continue
                        val mime = c.getString(2)
                        val size = if (c.isNull(3)) -1L else c.getLong(3)
                        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                            if (!name.startsWith(".") && depth < MAX_TREE_DEPTH) stack.addLast(id to depth + 1)
                        } else if (LibraryText.isBookName(name)) {
                            val isTxt = name.endsWith(".txt", ignoreCase = true)
                            if (!isTxt || size < 0 || size >= MIN_TXT_BYTES) found += Doc(id, name, mime, size)
                        }
                    }
                }
            } catch (t: Throwable) {
                // unreadable folder: skip
            }
        }
        var added = 0
        progress(0, found.size)
        found.forEachIndexed { i, d ->
            try {
                val uri = DocumentsContract.buildDocumentUriUsingTree(tree, d.id)
                if (importDocument(context, uri, d.name, d.size, d.mime) != null) added++
            } catch (t: Throwable) {
                // skip broken file
            }
            progress(i + 1, found.size)
        }
        return added
    }
}
