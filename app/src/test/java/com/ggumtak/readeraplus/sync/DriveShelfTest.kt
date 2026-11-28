package com.ggumtak.readeraplus.sync

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.Closeable
import java.io.DataInputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

class DriveShelfTest {
    private data class Reply(val status: Int, val data: ByteArray) {
        constructor(json: JSONObject) : this(200, json.toString().toByteArray())
    }

    private class DriveStub(private val replies: List<Reply>) : Closeable {
        private val server = ServerSocket(0, 12, InetAddress.getByName("127.0.0.1"))
        val requests = CopyOnWriteArrayList<String>()
        val bodies = CopyOnWriteArrayList<ByteArray>()
        val shelf = DriveShelf({ "test-user" }, origin = "http://127.0.0.1:${server.localPort}")
        private val handler = thread(isDaemon = true) {
            for (reply in replies) {
                val socket = try { server.accept() } catch (_: java.net.SocketException) { return@thread }
                socket.use {
                    val input = DataInputStream(socket.getInputStream().buffered())
                    @Suppress("DEPRECATION") fun line() = input.readLine().orEmpty()
                    val request = StringBuilder(URLDecoder.decode(line(), "UTF-8"))
                    var size = 0
                    while (true) {
                        val h = line()
                        if (h.isEmpty()) break
                        request.append('\n').append(h)
                        if (h.startsWith("Content-Length:", true)) size = h.substringAfter(':').trim().toInt()
                    }
                    val bytes = ByteArray(size)
                    input.readFully(bytes)
                    bodies.add(bytes)
                    requests.add(request.toString())
                    val out = socket.getOutputStream()
                    out.write("HTTP/1.1 ${reply.status} Result\r\nContent-Length: ${reply.data.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    out.write(reply.data)
                }
            }
        }
        override fun close() { server.close(); handler.join(3000) }
    }

    private fun page(vararg files: JSONObject) = JSONObject().put("items", JSONArray(files.toList()))
    private fun file(id: String) = JSONObject().put("id", id).put("createdDate", "2026-01-01T00:00:00Z")
    private val empty = Reply(page())

    @Test fun discoversBooksAcrossPagesAndVerifiesDownloadedContent() {
        val bytes = "다음 폰에서 이어 읽기".toByteArray()
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).hex()
        val book = ShelfBook(hash, "책.txt", bytes.size.toLong())
        val f = file("bookId").put("title", book.name).put("fileSize", book.size.toString())
            .put("properties", JSONArray().put(JSONObject().put("key", "prKey").put("value", hash)))
        val found = Reply(page(f))
        val tmp = File.createTempFile("drive-test", ".txt")
        try {
            DriveStub(listOf(Reply(page().put("nextPageToken", "next")), found, found, Reply(200, bytes),
                found, Reply(200, ByteArray(bytes.size)))).use { test ->
                assertEquals(listOf(book), test.shelf.list())
                test.shelf.download(book, tmp)
                assertArrayEquals(bytes, tmp.readBytes())
                assertThrows(IllegalArgumentException::class.java) { test.shelf.download(book, tmp) }
                assertTrue(test.requests[1].contains("pageToken=next"))
                assertTrue(test.requests[3].startsWith("GET /drive/v2/files/bookId?alt=media"))
                assertTrue(test.requests.all { it.contains("Bearer test-user") })
            }
        } finally { tmp.delete() }
    }

    @Test fun sendsBookAndFilenameTogetherAndChecksPositionRevision() {
        val input = File.createTempFile("drive-source", ".txt")
        try {
            input.writeText("동기화 원본")
            val book = ShelfBook(MessageDigest.getInstance("SHA-256").digest(input.readBytes()).hex(), "동기화.txt", input.length())
            val record = SyncRecord(UUID.randomUUID().toString(), SyncPoint(1, 37, .4f, ""))
            val tag = "\"v1\""
            val pos = file("posId").put("etag", tag).put("description", record.encode())
            val revision = Reply(page(pos))
            DriveStub(listOf(empty, Reply(page(file("folderId"))), Reply(JSONObject().put("fileSize", input.length())),
                revision, revision, Reply(412, byteArrayOf()))).use { test ->
                test.shelf.upload(book, input)
                val sent = test.bodies[2].toString(Charsets.UTF_8)
                for (expected in listOf(book.name, book.hash, "folderId", input.readText())) assertTrue(sent.contains(expected))
                assertEquals(record to tag, test.shelf.readProgress("doc"))
                assertFalse(test.shelf.putProgress("doc", record, tag))
                assertTrue(test.requests.last().contains("If-Match: $tag", ignoreCase = true))
            }
        } finally { input.delete() }
    }
}
