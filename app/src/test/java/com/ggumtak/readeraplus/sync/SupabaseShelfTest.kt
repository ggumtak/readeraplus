package com.ggumtak.readeraplus.sync

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SupabaseShelfTest {
    private val uid = UUID.randomUUID()
    private val bytes = "첫 폰에서 읽던 책".toByteArray()
    private val hash = MessageDigest.getInstance("SHA-256").digest(bytes).hex()
    private val book = ShelfBook(hash, "읽던 책.txt", bytes.size.toLong())

    private fun runServer(replies: List<ByteArray>, block: (SupabaseShelf, List<String>, List<ByteArray>) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val paths = CopyOnWriteArrayList<String>()
        val bodies = CopyOnWriteArrayList<ByteArray>()
        server.createContext("/") { ex ->
            val index = paths.size
            paths.add(ex.requestURI.toString())
            bodies.add(ex.requestBody.use { it.readBytes() })
            val reply = replies.getOrElse(index) { "unexpected request".toByteArray() }
            val authorized = ex.requestHeaders.getFirst("Authorization") == "Bearer user" && ex.requestHeaders.getFirst("apikey") == "public"
            ex.sendResponseHeaders(if (authorized && index < replies.size) 200 else 403, reply.size.toLong())
            ex.responseBody.use { it.write(reply) }
        }
        server.start()
        try {
            val http = SupabaseHttp("http://127.0.0.1:${server.address.port}", "public")
            block(SupabaseShelf(http, uid, { "user" }), paths, bodies)
        } finally { server.stop(0) }
    }

    @Test fun uploadListAndFetchValidateTheSameBook() {
        val tmp = File.createTempFile("reader-supabase", ".txt")
        try {
            tmp.writeBytes(bytes)
            runServer(listOf("[]".toByteArray(), "{}".toByteArray(), byteArrayOf(),
                "[${book.json()}]".toByteArray(), "[]".toByteArray(), bytes, ByteArray(bytes.size))) { shelf, paths, bodies ->
                shelf.upload(book, tmp)
                assertArrayEquals(bytes, bodies[1])
                assertEquals("/storage/v1/object/readeraplus-books/$uid/$hash", paths[1])
                assertEquals(listOf(book), shelf.list())
                assertTrue(paths[4].contains("sha256=gt.$hash"))
                shelf.download(book, tmp)
                assertArrayEquals(bytes, tmp.readBytes())
                assertThrows(IllegalArgumentException::class.java) { shelf.download(book, tmp) }
            }
        } finally { tmp.delete() }
    }

    @Test fun staleRevisionIsRejectedAndMissingPositionIsDistinct() {
        val point = SyncRecord(UUID.randomUUID().toString(), SyncPoint(2, 10, .5f, ""))
        runServer(listOf("[]".toByteArray(), "true".toByteArray(), "false".toByteArray())) { shelf, _, bodies ->
            assertEquals(null to null, shelf.readProgress("pr1_$hash"))
            assertTrue(shelf.putProgress("pr1_$hash", point, null))
            assertFalse(shelf.putProgress("pr1_$hash", point, point.id))
            assertTrue(JSONObject(bodies[1].decodeToString()).isNull("p_expected"))
            assertEquals(point.id, JSONObject(bodies[2].decodeToString()).getString("p_expected"))
        }
    }
}
