package com.ggumtak.readeraplus.sync

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.UUID

/** Drive v2 exposes a file ETag for optimistic position updates. Private properties identify our files across devices. */
internal class DriveShelf(
    token: () -> String,
    private val allow: () -> Boolean = { true },
    private val origin: String = "https://www.googleapis.com",
) : FileShelf {
    private val bearer by lazy(token)
    private val fields = "id,title,createdDate,etag,description,fileSize,properties(key,value,visibility)"
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun id(file: JSONObject) = file.getString("id").also { require(it.matches(Regex("[A-Za-z0-9_-]+"))) }
    private fun connection(path: String, method: String): HttpURLConnection {
        check(allow())
        return (URL(origin + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            instanceFollowRedirects = false
            connectTimeout = 6000
            readTimeout = 30000
            useCaches = false
            setRequestProperty("Authorization", "Bearer $bearer")
            setRequestProperty("Accept-Encoding", "identity")
        }
    }

    private fun json(c: HttpURLConnection): JSONObject {
        if (c.responseCode !in 200..299) throw SyncHttpError(c.responseCode)
        val text = java.io.ByteArrayOutputStream()
        c.inputStream.use { input ->
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                require(text.size() + n <= 4 * 1024 * 1024)
                text.write(buf, 0, n)
            }
        }
        return JSONObject(text.toString("UTF-8"))
    }

    private fun property(key: String, value: String) =
        JSONObject().put("key", key).put("value", value).put("visibility", "PRIVATE")

    private fun search(kind: String, key: String? = null): List<JSONObject> {
        require(kind in setOf("root", "places", "book", "position"))
        require(key == null || key.matches(Regex("[A-Za-z0-9_]+")))
        fun has(k: String, v: String) = "properties has { key='$k' and value='$v' and visibility='PRIVATE' }"
        var query = "trashed = false and 'me' in owners and " + has("pureReader", kind)
        if (key != null) query += " and " + has("prKey", key)
        val result = mutableListOf<JSONObject>()
        val seen = mutableSetOf<String>()
        var page = ""
        do {
            check(seen.add(page))
            val path = "/drive/v2/files?maxResults=100&q=" + enc(query) +
                "&fields=" + enc("nextPageToken,items($fields)") + (if (page.isEmpty()) "" else "&pageToken=" + enc(page))
            val c = connection(path, "GET")
            val reply = try { json(c) } finally { c.disconnect() }
            val items = reply.optJSONArray("items") ?: JSONArray()
            for (i in 0 until items.length()) result.add(items.getJSONObject(i))
            page = reply.optString("nextPageToken")
        } while (page.isNotEmpty())
        // Drive permits duplicate names and concurrent creates. Choose the earliest committed object consistently.
        return result.sortedWith(compareBy({ it.getString("createdDate") }, { id(it) }))
    }

    private fun metadata(title: String, kind: String, key: String? = null, parent: String? = null): JSONObject {
        val props = JSONArray().put(property("pureReader", kind))
        if (key != null) props.put(property("prKey", key))
        return JSONObject().put("title", title).put("properties", props).also { obj ->
            if (parent != null) obj.put("parents", JSONArray().put(JSONObject().put("id", parent)))
        }
    }

    private fun send(obj: JSONObject, fileId: String? = null, etag: String? = null): JSONObject {
        val path = "/drive/v2/files" + (fileId?.let { "/$it" } ?: "") + "?fields=" + enc(fields)
        val c = connection(path, if (fileId == null) "POST" else "PUT")
        try {
            if (etag != null) c.setRequestProperty("If-Match", etag)
            val bytes = obj.toString().toByteArray(Charsets.UTF_8)
            c.doOutput = true
            c.setFixedLengthStreamingMode(bytes.size)
            c.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            c.outputStream.use { it.write(bytes) }
            return json(c)
        } finally { c.disconnect() }
    }

    private fun folder(kind: String): String {
        search(kind).firstOrNull()?.let { return id(it) }
        val obj = if (kind == "root") metadata("PureReader", kind)
            else metadata("읽던 위치", kind, parent = folder("root"))
        val created = send(obj.put("mimeType", "application/vnd.google-apps.folder"))
        return id(search(kind).firstOrNull() ?: created)
    }

    fun connect() { folder("root") }

    private fun key(file: JSONObject): String {
        val props = file.getJSONArray("properties")
        for (i in 0 until props.length()) {
            val p = props.getJSONObject(i)
            if (p.getString("key") == "prKey") return p.getString("value")
        }
        error("Missing Drive book key")
    }

    override fun list(): List<ShelfBook> = search("book").mapNotNull { f ->
        // A user can rename/delete a Drive file independently. Ignore individual unsupported entries.
        runCatching { ShelfBook(key(f), f.getString("title"), f.getLong("fileSize")) }.getOrNull()
    }.distinctBy { it.hash }

    override fun upload(book: ShelfBook, input: File) {
        if (search("book", book.hash).any { it.optLong("fileSize") == book.size }) return
        require(input.length() == book.size)
        val meta = metadata(book.name, "book", book.hash, folder("root"))
        val mime = when (book.name.substringAfterLast('.').lowercase()) {
            "pdf" -> "application/pdf"
            "epub" -> "application/epub+zip"
            else -> "text/plain"
        }
        val boundary = "purereader-" + UUID.randomUUID()
        val start = ("--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n" +
            meta.put("mimeType", mime).toString() + "\r\n--$boundary\r\nContent-Type: $mime\r\n\r\n").toByteArray(Charsets.UTF_8)
        val end = "\r\n--$boundary--\r\n".toByteArray(Charsets.US_ASCII)
        val c = connection("/upload/drive/v2/files?uploadType=multipart&fields=id,fileSize", "POST")
        try {
            c.doOutput = true
            c.setFixedLengthStreamingMode(start.size + book.size + end.size)
            c.setRequestProperty("Content-Type", "multipart/related; boundary=$boundary")
            c.outputStream.use { out ->
                out.write(start)
                input.inputStream().use { source ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        check(allow())
                        val n = source.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                    }
                }
                out.write(end)
            }
            check(json(c).getLong("fileSize") == book.size)
        } finally { c.disconnect() }
    }

    override fun download(book: ShelfBook, target: File) {
        val f = search("book", book.hash).first { it.optLong("fileSize") == book.size }
        val c = connection("/drive/v2/files/${id(f)}?alt=media", "GET")
        try {
            if (c.responseCode != 200) throw SyncHttpError(c.responseCode)
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            c.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        check(allow())
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        require(total <= book.size)
                        digest.update(buf, 0, n)
                        output.write(buf, 0, n)
                    }
                }
            }
            require(total == book.size && digest.digest().hex() == book.hash)
        } finally { c.disconnect() }
    }

    override fun readProgress(doc: String): Pair<SyncRecord?, String?> {
        val f = search("position", doc).firstOrNull() ?: return null to null
        return SyncRecord.decode(f.getString("description")) to f.getString("etag")
    }

    override fun putProgress(doc: String, record: SyncRecord, expected: String?): Boolean {
        val old = search("position", doc).firstOrNull()
        if (old?.getString("etag") != expected) return false
        val title = "$doc.json"
        val obj = if (old == null) metadata(title, "position", doc, folder("places"))
            else JSONObject().put("title", title)
        obj.put("description", record.encode())
        try {
            val updated = send(obj, old?.let(::id), expected)
            if (old != null) return true
            val canonical = search("position", doc).firstOrNull() ?: return false
            return id(canonical) == id(updated)
        } catch (e: SyncHttpError) {
            if (e.status == 412 || e.status == 404) return false
            throw e
        }
    }
}
