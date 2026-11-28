package com.ggumtak.readeraplus.sync

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID

internal class SupabaseHttp(private val origin: String, private val publicKey: String) {
    fun open(path: String, method: String, token: String? = null): HttpURLConnection =
        (URL(origin + path).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            requestMethod = method
            connectTimeout = 5000
            readTimeout = 30000
            useCaches = false
            setRequestProperty("apikey", publicKey)
            if (token != null) setRequestProperty("Authorization", "Bearer $token")
        }

    fun request(path: String, method: String = "GET", token: String? = null, body: JSONObject? = null,
                prefer: String? = null): String {
        val c = open(path, method, token)
        try {
            if (prefer != null) c.setRequestProperty("Prefer", prefer)
            if (body != null) {
                val bytes = body.toString().toByteArray(Charsets.UTF_8)
                c.doOutput = true
                c.setFixedLengthStreamingMode(bytes.size)
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(bytes) }
            }
            if (c.responseCode !in 200..299) throw SyncHttpError(c.responseCode)
            return c.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(4096)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    check(out.size() + n <= 1024 * 1024)
                    out.write(buf, 0, n)
                }
                out.toString("UTF-8")
            }
        } finally { c.disconnect() }
    }
}

internal class SupabaseShelf(
    private val http: SupabaseHttp, private val userId: UUID, private val token: () -> String,
    private val allow: () -> Boolean = { true },
) : FileShelf {
    private val books = "/rest/v1/reader_books"
    private val positions = "/rest/v1/reader_positions"
    private val fields = "select=sha256,name,size&owner_id=eq.$userId"
    private fun path(book: ShelfBook) = "readeraplus-books/$userId/${book.hash}"

    fun checkConnection() {
        http.request("$books?$fields&limit=1", token = token())
        http.request("$positions?select=revision&limit=1", token = token())
    }

    override fun list(): List<ShelfBook> {
        val result = mutableListOf<ShelfBook>()
        var cursor = ""
        while (true) {
            val page = JSONArray(http.request("$books?$fields&order=sha256.asc&limit=100$cursor", token = token()))
            if (page.length() == 0) return result
            for (i in 0 until page.length()) {
                val row = page.getJSONObject(i)
                result.add(ShelfBook(row.getString("sha256"), row.getString("name"), row.getLong("size")))
            }
            cursor = "&sha256=gt." + result.last().hash
        }
    }

    override fun upload(book: ShelfBook, input: File) {
        require(input.length() == book.size)
        val known = JSONArray(http.request("$books?$fields&sha256=eq.${book.hash}", token = token()))
        if (known.length() > 0) { check(known.getJSONObject(0).getLong("size") == book.size); return }
        val c = http.open("/storage/v1/object/" + path(book), "POST", token())
        try {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/octet-stream")
            c.setFixedLengthStreamingMode(book.size)
            c.outputStream.use { output -> input.inputStream().use { source -> transfer(source, output, book) } }
            if (c.responseCode !in 200..299) {
                // A second phone or an interrupted catalog write may have already stored the object.
                if (c.responseCode !in listOf(400, 409)) throw SyncHttpError(c.responseCode)
                readObject(book, null)
            }
        } finally { c.disconnect() }
        check(allow())
        http.request("$books?on_conflict=owner_id,sha256", "POST", token(), book.json(), "resolution=ignore-duplicates,return=minimal")
    }

    private fun transfer(source: InputStream, output: OutputStream?, book: ShelfBook) {
        val digest = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        var size = 0L
        while (true) {
            check(allow())
            val n = source.read(buf)
            if (n < 0) break
            size += n
            require(size <= book.size)
            digest.update(buf, 0, n)
            output?.write(buf, 0, n)
        }
        require(size == book.size && digest.digest().hex() == book.hash)
    }

    private fun readObject(book: ShelfBook, output: OutputStream?) {
        val c = http.open("/storage/v1/object/authenticated/" + path(book), "GET", token())
        try {
            if (c.responseCode != 200) throw SyncHttpError(c.responseCode)
            c.inputStream.use { transfer(it, output, book) }
        } finally { c.disconnect() }
    }

    override fun download(book: ShelfBook, target: File) {
        target.outputStream().use { readObject(book, it) }
    }

    private fun positionPath(doc: String): String {
        require(doc.matches(Regex("pr1_[0-9a-f]{64}")))
        return "$positions?owner_id=eq.$userId&document=eq.$doc"
    }

    override fun readProgress(doc: String): Pair<SyncRecord?, String?> {
        val rows = JSONArray(http.request(positionPath(doc) + "&select=record,revision", token = token()))
        val row = rows.optJSONObject(0) ?: return null to null
        val record = SyncRecord.decode(row.getJSONObject("record").toString())
        check(record.id == row.getString("revision"))
        return record to record.id
    }

    override fun putProgress(doc: String, record: SyncRecord, expected: String?): Boolean {
        require(doc.matches(Regex("pr1_[0-9a-f]{64}")))
        val body = JSONObject().put("p_document", doc).put("p_revision", record.id)
            .put("p_record", JSONObject(record.encode())).put("p_expected", expected ?: JSONObject.NULL)
        return http.request("/rest/v1/rpc/reader_save_position", "POST", token(), body).trim().toBooleanStrict()
    }
}
