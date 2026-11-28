package com.ggumtak.readeraplus.sync

import com.ggumtak.readeraplus.data.LanNames
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Base64

internal data class ShelfBook(val hash: String, val name: String, val size: Long) {
    init {
        require(hash.matches(Regex("[0-9a-f]{64}")) && LanNames.sanitize(name) == name && size > 0)
    }
    val objectName get() = "PureReader-book-$hash." + name.substringAfterLast('.').lowercase()
    fun json(): JSONObject = JSONObject().put("sha256", hash).put("name", name).put("size", size)
}

internal class DavShelf(private val account: SyncAccount, private val allowTransfer: () -> Boolean = { true }) {
    private val catalog = "PureReader-shelf-v1.json"
    private data class Manifest(val books: List<ShelfBook>, val etag: String?)
    private fun open(name: String, method: String) =
        (URL(account.server + "/" + name).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            requestMethod = method
            useCaches = false
            connectTimeout = 3500
            readTimeout = if (name.startsWith("PureReader-book-")) 30000 else 4000
            setRequestProperty("Authorization", "Basic " + Base64.getEncoder().encodeToString(
                (account.user + ":" + account.key).toByteArray(Charsets.UTF_8)))
            setRequestProperty("Accept-Encoding", "identity")
        }

    private fun readJson(name: String, limit: Int): Pair<JSONObject?, String?> {
        val conn = open(name, "GET")
        try {
            if (conn.responseCode == 404) return null to null
            if (conn.responseCode != 200) throw SyncHttpError(conn.responseCode)
            val bytes = java.io.ByteArrayOutputStream()
            conn.inputStream.use { input ->
                val buf = ByteArray(8192)
                while (true) {
                    val count = input.read(buf)
                    if (count < 0) break
                    require(bytes.size() + count <= limit)
                    bytes.write(buf, 0, count)
                }
            }
            return JSONObject(bytes.toString("UTF-8")) to conn.getHeaderField("ETag")
        } finally { conn.disconnect() }
    }

    /** Every mutable object write requires its prior strong ETag, including the first creation. */
    private fun put(name: String, input: InputStream, length: Long, etag: String?, mime: String): Boolean {
        val conn = open(name, "PUT")
        try {
            if (etag == null) conn.setRequestProperty("If-None-Match", "*")
            else {
                require(etag.startsWith('"') && etag.endsWith('"'))
                conn.setRequestProperty("If-Match", etag)
            }
            conn.doOutput = true
            conn.setFixedLengthStreamingMode(length)
            conn.setRequestProperty("Content-Type", mime)
            check(allowTransfer())
            conn.outputStream.use { out ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    check(allowTransfer())
                    val count = input.read(buffer)
                    if (count < 0) break
                    out.write(buffer, 0, count)
                }
            }
            if (conn.responseCode == 412) return false
            if (conn.responseCode !in 200..299) throw SyncHttpError(conn.responseCode)
            return true
        } finally { conn.disconnect() }
    }

    private fun putJson(name: String, value: JSONObject, etag: String?): Boolean {
        val data = value.toString().toByteArray(Charsets.UTF_8)
        return put(name, data.inputStream(), data.size.toLong(), etag, "application/json")
    }

    private fun manifest(): Manifest {
        val (json, tag) = readJson(catalog, 4 * 1024 * 1024)
        if (json == null) return Manifest(emptyList(), null)
        check(tag != null && tag.startsWith('"') && tag.endsWith('"')) { "Strong ETag required" }
        require(json.getInt("v") == 1)
        val list = json.getJSONArray("books")
        return Manifest(List(list.length()) { index ->
            val entry = list.getJSONObject(index)
            ShelfBook(entry.getString("sha256"), entry.getString("name"), entry.getLong("size"))
        }, tag)
    }

    fun list(): List<ShelfBook> = manifest().books

    fun connect() {
        val current = manifest()
        if (current.etag == null) {
            // A missing catalog is normal on a new account. Verify write access before saving credentials.
            putJson(catalog, JSONObject().put("v", 1).put("books", JSONArray()), null)
        }
        if (current.etag == null) check(manifest().etag != null)
    }

    fun readProgress(doc: String): Pair<SyncRecord?, String?> {
        val (json, tag) = readJson("PureReader-position-$doc.json", 16384)
        return json?.let { SyncRecord.decode(it.toString()) } to tag
    }

    fun putProgress(doc: String, record: SyncRecord, expected: String?): Boolean =
        putJson("PureReader-position-$doc.json", JSONObject(record.encode()), expected)

    fun upload(book: ShelfBook, input: File) {
        var snapshot = manifest()
        if (snapshot.books.any { it.hash == book.hash }) return
        require(input.length() == book.size)
        val head = open(book.objectName, "HEAD")
        val exists = try {
            when (head.responseCode) {
                200 -> { require(head.contentLengthLong == book.size); true }
                404, 405 -> false
                else -> throw SyncHttpError(head.responseCode)
            }
        } finally { head.disconnect() }
        if (!exists) input.inputStream().use { source ->
            put(book.objectName, source, book.size, null, "application/octet-stream")
        }
        repeat(5) {
            if (snapshot.books.any { it.hash == book.hash }) return
            val entries = JSONArray()
            snapshot.books.forEach { entries.put(it.json()) }
            entries.put(book.json())
            val value = JSONObject().put("v", 1).put("books", entries)
            require(value.toString().toByteArray(Charsets.UTF_8).size <= 4 * 1024 * 1024)
            if (putJson(catalog, value, snapshot.etag)) return
            snapshot = manifest()
        }
        throw IOException("Catalog changed")
    }

    fun download(book: ShelfBook, target: File) {
        val conn = open(book.objectName, "GET")
        try {
            if (conn.responseCode != 200) throw SyncHttpError(conn.responseCode)
            val length = conn.contentLengthLong
            require(length < 0 || length == book.size)
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            conn.inputStream.use { input ->
                target.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        check(allowTransfer())
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        require(total <= book.size)
                        digest.update(buf, 0, n)
                        out.write(buf, 0, n)
                    }
                }
            }
            require(total == book.size && digest.digest().hex() == book.hash)
        } finally { conn.disconnect() }
    }
}
