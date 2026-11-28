package com.ggumtak.readeraplus.sync

import android.content.Context
import com.ggumtak.readeraplus.data.Book
import com.ggumtak.readeraplus.data.BookCopies
import com.ggumtak.readeraplus.data.LanNames
import com.ggumtak.readeraplus.data.Library
import com.ggumtak.readeraplus.ui.library.LibraryImport
import java.io.File
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.concurrent.Executors

/** File transfers use a separate IO lane: opening a book never waits for an upload of another book. */
internal object CloudBooks {
    private val uploads = Executors.newSingleThreadExecutor()
    private fun prefs(c: Context) = c.getSharedPreferences("reading_sync", Context.MODE_PRIVATE)
    fun isDav(c: Context) = prefs(c).getBoolean("dav", false)
    fun enabled(c: Context) = prefs(c).getBoolean("files", false)
    fun setEnabled(c: Context, value: Boolean) = prefs(c).edit().putBoolean("files", value).apply()
    fun status(c: Context): String = prefs(c).getString("fileStatus", "").orEmpty()

    private fun account(c: Context): SyncAccount = checkNotNull(AccountVault.read(c)).also {
        check(it.dav && it.device == prefs(c).getString("account", null) && enabled(c))
    }

    fun onOpen(c: Context, book: Book, hash: String, owner: String) {
        if (!enabled(c)) return
        val p = prefs(c)
        p.edit().putString("path.$hash", book.path).apply()
        if (p.getBoolean("uploaded.$hash", false)) return
        uploads.execute {
            if (!enabled(c) || owner != p.getString("account", null) || p.getBoolean("uploaded.$hash", false)) return@execute
            val file = File(book.path)
            try {
                val name = LanNames.sanitize(book.fileName) ?: return@execute
                val cloud = DavShelf(account(c)) { enabled(c) && p.getString("account", null) == owner }
                val item = ShelfBook(hash, name, file.length())
                val snapshot = File.createTempFile("sync-upload-", ".part", c.cacheDir)
                try {
                    val digest = MessageDigest.getInstance("SHA-256")
                    DigestInputStream(file.inputStream(), digest).use { input ->
                        snapshot.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
                    }
                    check(digest.digest().hex() == hash && snapshot.length() == item.size)
                    if (!enabled(c) || p.getString("account", null) != owner) return@execute
                    cloud.upload(item, snapshot)
                } finally { snapshot.delete() }
                if (owner == p.getString("account", null)) p.edit().putBoolean("uploaded.$hash", true)
                    .putString("fileStatus", "$name · 업로드 완료").apply()
            } catch (_: Exception) {
                if (owner == p.getString("account", null)) p.edit().putString("fileStatus", "책 파일을 보내지 못했습니다. 연결 후 책을 다시 열면 재시도합니다.").apply()
            }
        }
    }

    fun list(c: Context): List<ShelfBook> = DavShelf(account(c)).list()

    private fun matches(file: File, item: ShelfBook): Boolean {
        if (!file.isFile || file.length() != item.size) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().hex() == item.hash
    }

    fun fetch(c: Context, item: ShelfBook): Book {
        val a = account(c)
        val p = prefs(c)
        val dir = LibraryImport.booksDir(c)
        val known = p.getString("path.${item.hash}", null)?.let(::File)
        val existing = listOfNotNull(known, File(dir, item.name)).firstOrNull { matches(it, item) }
        val file = existing ?: run {
            val free = dir.usableSpace
            check(free == 0L || item.size <= free - 8L * 1024 * 1024) { "저장 공간이 부족합니다." }
            val part = File.createTempFile(BookCopies.IMPORT_PREFIX, BookCopies.PART_SUFFIX, dir)
            try {
                DavShelf(a) { enabled(c) && p.getString("account", null) == a.device }.download(item, part)
                check(account(c).device == a.device)
                BookCopies.settle(part, dir, item.name)
            } finally { part.delete() }
        }
        check(account(c).device == a.device)
        val book = checkNotNull(Library.addOrUpdateFile(file))
        p.edit().putString("path.${item.hash}", file.absolutePath).putBoolean("uploaded.${item.hash}", true).apply()
        return book
    }
}
