package com.ggumtak.readeraplus.sync

import java.io.DataInputStream
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.UUID

class CloudSyncTest {
    @Test fun twoDevicesDiscoverDownloadVerifyAndResumeTheSameBook() {
        val server = ServerSocket().apply { bind(InetSocketAddress("127.0.0.1", 0)) }
        val objects = java.util.concurrent.ConcurrentHashMap<String, Pair<ByteArray, String>>()
        var revision = 0
        val thread = thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: java.net.SocketException) { break }
                socket.use {
                    val input = DataInputStream(socket.getInputStream().buffered())
                    @Suppress("DEPRECATION")
                    fun line(): String = input.readLine().orEmpty()
                    val request = line().split(' ')
                    val method = request.first()
                    val path = request[1]
                    val headers = mutableMapOf<String, String>()
                    while (true) {
                        val header = line()
                        if (header.isEmpty()) break
                        headers[header.substringBefore(':').lowercase()] = header.substringAfter(':').trim()
                    }
                    val data = ByteArray(headers["content-length"]?.toInt() ?: 0)
                    input.readFully(data)
                    val old = objects[path]
                    when {
                        headers["authorization"] != "Basic YTpi" -> respond(socket, 401)
                        method == "PUT" -> {
                            val tag = headers["if-match"]
                            val create = headers["if-none-match"] == "*"
                            if ((create && old != null) || (!create && (tag == null || tag != old?.second))) {
                                respond(socket, 412)
                            } else {
                                objects[path] = data to "\"${++revision}\""
                                respond(socket, 201)
                            }
                        }
                        old == null -> respond(socket, 404)
                        else -> respond(socket, 200, old.first, old.second, method == "HEAD")
                    }
                }
            }
        }
        val original = File.createTempFile("original", ".txt")
        val restored = File.createTempFile("restored", ".txt")
        try {
            val a = SyncAccount("http://127.0.0.1:${server.localPort}/private", "a", "b", "deviceA", true)
            val first = DavShelf(a)
            val second = DavShelf(a.copy(device = "deviceB"))
            first.connect()
            original.writeText("두 번째 기기에서 같은 문장으로 이어 읽기.".repeat(2000))
            val hash = MessageDigest.getInstance("SHA-256").digest(original.readBytes()).hex()
            val book = ShelfBook(hash, "새 책.txt", original.length())
            first.upload(book, original)
            first.upload(book, original)
            assertEquals(listOf(book), second.list())
            second.download(book, restored)
            assertArrayEquals(original.readBytes(), restored.readBytes())
            val doc = "pr1_$hash"
            val place = SyncRecord(UUID.randomUUID().toString(), SyncPoint(2, 23, .25f, "abc"))
            assertTrue(first.putProgress(doc, place, null))
            val read = second.readProgress(doc)
            assertEquals(place, read.first)
            val later = SyncRecord(UUID.randomUUID().toString(), SyncPoint(0, 12, .04f, "abc"))
            assertTrue(second.putProgress(doc, later, read.second))
            assertFalse(first.putProgress(doc, place, read.second))
            assertEquals(later, first.readProgress(doc).first)
            objects["/private/" + book.objectName] = ByteArray(book.size.toInt()) to "\"broken\""
            assertThrows(IllegalArgumentException::class.java) { second.download(book, restored) }
            assertThrows(SyncHttpError::class.java) { DavShelf(a.copy(key = "wrong")).list() }
        } finally {
            server.close(); thread.join(2000); restored.delete(); original.delete()
        }
    }

    private fun respond(socket: Socket, status: Int, body: ByteArray = byteArrayOf(), tag: String? = null, head: Boolean = false) {
        val headers = buildString {
            append("HTTP/1.1 $status Result\r\nConnection: close\r\nContent-Length: ${body.size}\r\n")
            if (tag != null) append("ETag: $tag\r\n")
            append("\r\n")
        }
        val output = socket.getOutputStream()
        output.write(headers.toByteArray(Charsets.US_ASCII))
        if (!head) output.write(body)
        output.flush()
    }

    @Test fun offlineEditsRequireTheirBaseOrAnAlreadyAppliedRecord() {
        assertEquals(Replay.SEND, replay("first", "edit", "first"))
        assertEquals(Replay.KEEP_REMOTE, replay("first", "edit", "other"))
        assertEquals(Replay.ACKNOWLEDGE, replay("first", "edit", "edit"))
        assertEquals(Replay.SEND, replay("", "edit", ""))
    }

    @Test fun authenticatedEndpointsAndCloudNamesAreValidated() {
        for (url in listOf("http://example.org", "https://u:p@example.org", "https://example.org/?token=x")) {
            assertThrows(IllegalArgumentException::class.java) { serverUrl(url) }
        }
        val hash = "f".repeat(64)
        for (name in listOf("../book.pdf", "book.apk", "a/b.epub")) {
            assertThrows(IllegalArgumentException::class.java) { ShelfBook(hash, name, 12) }
        }
        assertThrows(IllegalArgumentException::class.java) { SyncRecord.decode("""{"v":2}""") }
    }
}
