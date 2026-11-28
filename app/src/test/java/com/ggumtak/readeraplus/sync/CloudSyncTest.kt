package com.ggumtak.readeraplus.sync

import com.sun.net.httpserver.HttpServer
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.UUID

class CloudSyncTest {
    @Test fun twoDevicesDiscoverDownloadVerifyAndResumeTheSameBook() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val objects = mutableMapOf<String, Pair<ByteArray, String>>()
        var revision = 0
        server.createContext("/private/") { ex ->
            ex.use {
                if (ex.requestHeaders.getFirst("Authorization") != "Basic YTpi") {
                    ex.sendResponseHeaders(401, -1); return@use
                }
                val path = ex.requestURI.path
                val old = objects[path]
                if (ex.requestMethod == "PUT") {
                    val data = ex.requestBody.readBytes()
                    val tag = ex.requestHeaders.getFirst("If-Match")
                    val create = ex.requestHeaders.getFirst("If-None-Match") == "*"
                    if ((create && old != null) || (!create && (tag == null || tag != old?.second))) {
                        ex.sendResponseHeaders(412, -1)
                    } else {
                        objects[path] = data to "\"${++revision}\""
                        ex.sendResponseHeaders(201, -1)
                    }
                } else if (old == null) ex.sendResponseHeaders(404, -1)
                else {
                    ex.responseHeaders.add("ETag", old.second)
                    ex.sendResponseHeaders(200, old.first.size.toLong())
                    ex.responseBody.write(old.first)
                }
            }
        }
        server.start()
        val original = File.createTempFile("original", ".txt")
        val restored = File.createTempFile("restored", ".txt")
        try {
            val a = SyncAccount("http://127.0.0.1:${server.address.port}/private", "a", "b", "deviceA", true)
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
            server.stop(0); restored.delete(); original.delete()
        }
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
