package com.ggumtak.readeraplus.data

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.nio.file.Files
import java.text.Normalizer
import kotlin.random.Random

private fun part(disposition: String, body: ByteArray, type: String? = "text/plain") =
    ("Content-Disposition: $disposition\r\n" + (type?.let { "Content-Type: $it\r\n" } ?: "") + "\r\n")
        .toByteArray(Charsets.UTF_8) to body

private fun multipart(boundary: String, parts: List<Pair<ByteArray, ByteArray>>, preamble: String = ""): ByteArray {
    val out = ByteArrayOutputStream()
    out.write(preamble.toByteArray())
    for ((head, body) in parts) {
        out.write("--$boundary\r\n".toByteArray())
        out.write(head)
        out.write(body)
        out.write("\r\n".toByteArray())
    }
    out.write("--$boundary--\r\n".toByteArray())
    return out.toByteArray()
}

/**
 * Wi-Fi 전송 (T1-12) without sockets: the access code, the name sanitiser, the request and multipart parsers (with a
 * fuzz run over random chunking and boundary-like data) and whole HTTP exchanges on byte streams.
 */
class LanUploadTest {

    // ---- access code ----

    @Test
    fun codeAlphabetIsUnambiguous() {
        val a = LanCode.ALPHABET
        assertEquals(31, a.length)
        assertEquals(31, a.toSet().size)
        for (c in "01oil") assertFalse("$c", c in a)
        assertTrue(a.all { it in '2'..'9' || it in 'a'..'z' })
        assertEquals(4, LanCode.LENGTH)
    }

    @Test
    fun codeUsesTheRandomSource() {
        var i = 0
        assertEquals("2345", LanCode.generate { bound -> assertEquals(31, bound); i++ })
        assertEquals("zzzz", LanCode.generate { it - 1 })
        // A misbehaving source is clamped, never an index error.
        assertEquals("2222", LanCode.generate { -5 })
        val r = Random(7)
        repeat(200) {
            val c = LanCode.generate { r.nextInt(it) }
            assertEquals(4, c.length)
            assertTrue(c, c.all { it in LanCode.ALPHABET })
        }
    }

    // ---- names ----

    @Test
    fun sanitizeKeepsKoreanAndDropsPaths() {
        assertEquals("소설A 1-100화.txt", LanNames.sanitize("소설A 1-100화.txt"))
        assertEquals("소설.TXT", LanNames.sanitize("C:\\Users\\me\\Desktop\\소설.TXT"))
        assertEquals("passwd.txt", LanNames.sanitize("../../etc/passwd.txt"))
        assertEquals("책.epub", LanNames.sanitize("/sdcard/Download/책.epub"))
        assertEquals("소설 A.txt", LanNames.sanitize("  소설 A .txt "))
        assertEquals("abcd.epub", LanNames.sanitize("a<b>:c|d?*\".epub"))
        assertEquals("hidden.txt", LanNames.sanitize(".hidden.txt"))
        assertEquals("a b.txt", LanNames.sanitize("a\u0000\n b.txt"))
    }

    @Test
    fun sanitizeRefusesWhatIsNotABook() {
        assertNull(LanNames.sanitize("a.pdf"))
        assertNull(LanNames.sanitize("noext"))
        assertNull(LanNames.sanitize(".."))
        assertNull(LanNames.sanitize("..txt"))
        assertNull(LanNames.sanitize(".txt"))
        assertNull(LanNames.sanitize("   .epub"))
        assertNull(LanNames.sanitize("dir/"))
        assertNull(LanNames.sanitize("소설.txt."))
        // A right-to-left override can't make "…txt.exe" look like a text file.
        assertNull(LanNames.sanitize("evil\u202Etxt.exe"))
        assertEquals("eviltxt.epub", LanNames.sanitize("evil\u202Etxt.epub"))
    }

    @Test
    fun sanitizeComposesHangulAndCapsTheLength() {
        val nfd = Normalizer.normalize("한글 소설.txt", Normalizer.Form.NFD)
        assertEquals("한글 소설.txt", LanNames.sanitize(nfd))
        val long = LanNames.sanitize("가".repeat(300) + ".epub")!!
        assertTrue(long.endsWith(".epub"))
        assertTrue(long.toByteArray(Charsets.UTF_8).size <= 200)
        assertEquals(("가".repeat(65) + ".epub"), long) // 65 × 3 + 5 = 200 bytes
        // Surrogate pairs are never split.
        val emoji = LanNames.sanitize("\uD83D\uDCD6".repeat(100) + ".txt")!!
        assertTrue(emoji.toByteArray(Charsets.UTF_8).size <= 200)
        assertFalse(emoji.removeSuffix(".txt").last().isHighSurrogate())
    }

    @Test
    fun uniqueNumbersCollisions() {
        val taken = mutableSetOf("소설.txt", "소설 (2).txt")
        assertEquals("새 책.txt", LanNames.unique("새 책.txt") { it in taken })
        assertEquals("소설 (3).txt", LanNames.unique("소설.txt") { it in taken })
        taken += "소설 (3).txt"
        assertEquals("소설 (4).txt", LanNames.unique("소설.txt") { it in taken })
        assertEquals("소설 (2) (2).txt", LanNames.unique("소설 (2).txt") { it in taken })
    }

    @Test
    fun contentDispositionFileNames() {
        assertEquals("소설.txt", LanNames.fromContentDisposition("form-data; name=\"file\"; filename=\"소설.txt\""))
        assertEquals("a\"b.txt", LanNames.fromContentDisposition("form-data; name=\"file\"; filename=\"a%22b.txt\""))
        assertEquals("a\nb.txt", LanNames.fromContentDisposition("form-data; filename=\"a%0Ab.txt\""))
        assertEquals(
            "소설.txt",
            LanNames.fromContentDisposition(
                "form-data; name=\"file\"; filename*=UTF-8''%EC%86%8C%EC%84%A4.txt; filename=\"x.txt\"",
            ),
        )
        assertEquals("x.txt", LanNames.fromContentDisposition("form-data; filename=\"x.txt\"; filename*=UTF-8''%E"))
        assertEquals("é.txt", LanNames.fromContentDisposition("form-data; filename*=iso-8859-1'fr'%E9.txt"))
        assertNull(LanNames.fromContentDisposition("form-data; name=\"note\""))
        assertEquals("", LanNames.fromContentDisposition("form-data; name=\"file\"; filename=\"\""))
        assertEquals("semi;colon.txt", LanNames.fromContentDisposition("form-data; filename=\"semi;colon.txt\""))
        assertEquals("q\"uote.txt", LanNames.fromContentDisposition("form-data; filename=\"q\\\"uote.txt\""))
        assertEquals("bare.txt", LanNames.fromContentDisposition("form-data; FILENAME=bare.txt"))
    }

    @Test
    fun displayNamesAreShort() {
        assertEquals("소설.pdf", LanNames.display("C:\\x\\소설.pdf"))
        assertEquals(60, LanNames.display("가".repeat(100)).length)
    }

    // ---- HTTP head ----

    @Test
    fun requestLines() {
        assertEquals("GET" to "/k7m3", LanHttp.requestLine("GET /k7m3 HTTP/1.1"))
        assertEquals("POST" to "/k7m3/upload", LanHttp.requestLine("POST /k7m3/upload?x=1 HTTP/1.1"))
        assertEquals("GET" to "/", LanHttp.requestLine("GET /#top HTTP/1.0"))
        for (bad in listOf(
            "", "GET", "GET /", "get / HTTP/1.1", "GET k7m3 HTTP/1.1", "GET / HTTP/2", "GET  / HTTP/1.1",
            "GET http://x/ HTTP/1.1", "GET / HTTP/1.1 extra", "G3T / HTTP/1.1",
        )) {
            assertNull(bad, LanHttp.requestLine(bad))
        }
    }

    @Test
    fun boundaries() {
        assertEquals(
            "----WebKitFormBoundary7MA4YWxkTrZu0gW",
            LanHttp.boundary("multipart/form-data; boundary=----WebKitFormBoundary7MA4YWxkTrZu0gW"),
        )
        assertEquals("a b", LanHttp.boundary("Multipart/Form-Data; charset=utf-8; BOUNDARY=\"a b\""))
        assertEquals("x", LanHttp.boundary("multipart/form-data;boundary=x"))
        assertNull(LanHttp.boundary("text/plain; boundary=x"))
        assertNull(LanHttp.boundary("multipart/form-data"))
        assertNull(LanHttp.boundary("multipart/form-data; boundary="))
        assertNull(LanHttp.boundary("multipart/form-data; boundary=" + "b".repeat(71)))
        assertNull(LanHttp.boundary("multipart/form-data; boundary=\"a\u0001\""))
    }

    @Test
    fun routes() {
        val c = "k7m3"
        assertEquals(LanHttp.Route.PAGE, LanHttp.route("GET", "/k7m3", c))
        assertEquals(LanHttp.Route.PAGE, LanHttp.route("GET", "/K7M3/", c))
        assertEquals(LanHttp.Route.PAGE, LanHttp.route("HEAD", "/k7m3", c))
        assertEquals(LanHttp.Route.UPLOAD, LanHttp.route("POST", "/k7m3/upload", c))
        assertEquals(LanHttp.Route.WRONG_METHOD, LanHttp.route("GET", "/k7m3/upload", c))
        assertEquals(LanHttp.Route.WRONG_METHOD, LanHttp.route("POST", "/k7m3", c))
        assertEquals(LanHttp.Route.NOT_FOUND, LanHttp.route("GET", "/k7m3/other", c))
        assertEquals(LanHttp.Route.NOT_FOUND, LanHttp.route("GET", "/favicon.ico", c))
        assertEquals(LanHttp.Route.NOT_FOUND, LanHttp.route("GET", "/apple-touch-icon-precomposed.png", c))
        assertEquals(LanHttp.Route.WRONG, LanHttp.route("GET", "/", c))
        assertEquals(LanHttp.Route.WRONG, LanHttp.route("GET", "/k7m4", c))
        assertEquals(LanHttp.Route.WRONG, LanHttp.route("POST", "/k7m4/upload", c))
        assertEquals(LanHttp.Route.WRONG, LanHttp.route("GET", "/k7m3x", c))
    }

    @Test
    fun readHeadParsesAndLeavesTheBody() {
        val raw = "POST /k7m3/upload HTTP/1.1\r\nHost: 192.168.0.23:8080\r\nContent-Type: multipart/form-data; " +
            "boundary=b\r\nContent-Length: 5\r\nX-A: 1\r\nx-a: 2\r\n\r\nHELLO"
        val input = ByteArrayInputStream(raw.toByteArray(Charsets.ISO_8859_1))
        val head = LanHttp.readHead(input)!!
        assertEquals("POST", head.method)
        assertEquals("/k7m3/upload", head.path)
        assertEquals("b", LanHttp.boundary(head.header("Content-Type")!!))
        assertEquals(5L, head.contentLength())
        assertEquals("1, 2", head.header("x-a"))
        assertEquals("HELLO", String(input.readBytes()))
        // Bare LF line ends are tolerated.
        assertNotNull(LanHttp.readHead(ByteArrayInputStream("GET / HTTP/1.1\nA: b\n\n".toByteArray())))
    }

    @Test
    fun readHeadRejectsMalformedHeads() {
        fun head(s: String) = LanHttp.readHead(ByteArrayInputStream(s.toByteArray(Charsets.ISO_8859_1)))
        assertNull(head(""))
        assertNull(head("GET / HTTP/1.1\r\nHost: x\r\n")) // ends before the blank line
        assertNull(head("GET / HTTP/1.1\r\nNoColon\r\n\r\n"))
        assertNull(head("GET / HTTP/1.1\r\n: empty name\r\n\r\n"))
        assertNull(head("GET / HTTP/1.1\r\nA: " + "x".repeat(17 * 1024) + "\r\n\r\n"))
        assertNull(head("GET / HTTP/1.1\r\n" + (1..65).joinToString("") { "H$it: v\r\n" } + "\r\n"))
    }

    @Test
    fun contentLengths() {
        fun len(v: String) = LanHttp.Head("POST", "/", mapOf("content-length" to v)).contentLength()
        assertEquals(0L, len("0"))
        assertEquals(209_715_200L, len(" 209715200 "))
        assertNull(len("-1"))
        assertNull(len("12a"))
        assertNull(len("1, 2"))
        assertNull(len(""))
        assertNull(len("9".repeat(16)))
        assertNull(LanHttp.Head("POST", "/", emptyMap()).contentLength())
    }

    @Test
    fun partHeadersAreUtf8WithACp949Fallback() {
        assertEquals("filename=\"소설.txt\"", LanHttp.decodeHeader("filename=\"소설.txt\"".toByteArray(Charsets.UTF_8)))
        assertEquals("plain", LanHttp.decodeHeader("plain".toByteArray()))
        val cp949 = com.ggumtak.readeraplus.format.txt.TxtCharsets.cp949Charset
        if (cp949 != null) assertEquals("소설.txt", LanHttp.decodeHeader("소설.txt".toByteArray(cp949)))
    }

    // ---- guard ----

    @Test
    fun tenWrongPathsBlockForThirtySeconds() {
        val g = LanGuard()
        repeat(9) { g.onWrong(1_000) }
        assertFalse(g.blocked(1_000))
        g.onWrong(1_000)
        assertTrue(g.blocked(1_000))
        assertTrue(g.blocked(30_999))
        assertFalse(g.blocked(31_000))
        // The count starts again after a block.
        repeat(9) { g.onWrong(40_000) }
        assertFalse(g.blocked(40_000))
        g.onRight()
        repeat(9) { g.onWrong(40_000) }
        assertFalse(g.blocked(40_000))
    }

    // ---- multipart ----

    /** Returns at most a few bytes per read: every delimiter gets split across reads somewhere. */
    private class Trickle(private val data: ByteArray, private val random: Random, private val max: Int) : InputStream() {
        private var pos = 0
        override fun read(): Int = if (pos < data.size) data[pos++].toInt() and 0xff else -1
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (pos >= data.size) return -1
            val n = minOf(len, data.size - pos, 1 + random.nextInt(max))
            System.arraycopy(data, pos, b, off, n)
            pos += n
            return n
        }
    }

    private fun readAll(reader: MultipartReader): List<Pair<Map<String, String>, ByteArray>> {
        assertTrue(reader.start())
        val out = ArrayList<Pair<Map<String, String>, ByteArray>>()
        while (true) {
            val h = reader.nextPart() ?: break
            val body = ByteArrayOutputStream()
            reader.copyBody(body, Long.MAX_VALUE)
            out += h to body.toByteArray()
        }
        return out
    }

    @Test
    fun multipartReadsFilesAndFields() {
        val b = "----WebKitFormBoundaryAbC123"
        val text = "첫 줄\r\n둘째 줄\r\n--not a boundary\r\n".toByteArray()
        val body = multipart(
            b,
            listOf(
                part("form-data; name=\"note\"", "hello".toByteArray(), null),
                part("form-data; name=\"file\"; filename=\"소설.txt\"", text),
                part("form-data; name=\"file\"; filename=\"빈.txt\"", ByteArray(0)),
            ),
            preamble = "ignored preamble\r\n",
        )
        val parts = readAll(MultipartReader(ByteArrayInputStream(body), b))
        assertEquals(3, parts.size)
        assertEquals("form-data; name=\"note\"", parts[0].first["content-disposition"])
        assertArrayEquals("hello".toByteArray(), parts[0].second)
        assertEquals("소설.txt", LanNames.fromContentDisposition(parts[1].first["content-disposition"]!!))
        assertEquals("text/plain", parts[1].first["content-type"])
        assertArrayEquals(text, parts[1].second)
        assertEquals(0, parts[2].second.size)
    }

    @Test
    fun multipartFuzzOverChunkingAndBoundaryLikeData() {
        val random = Random(20260930)
        repeat(300) { round ->
            val boundary = "b" + (1..(1 + random.nextInt(40))).map { "ab-_'0123456789XYZ"[random.nextInt(18)] }
                .joinToString("")
            val delimiter = "\r\n--$boundary".toByteArray()
            val files = (0 until 1 + random.nextInt(3)).map {
                val out = ByteArrayOutputStream()
                repeat(random.nextInt(40)) {
                    when (random.nextInt(5)) {
                        // Near-misses: every proper prefix of the delimiter, CRLF runs, dashes, random bytes.
                        // Each near-miss ends in a 0 byte, which no delimiter contains: pieces can't join into one.
                        0 -> out.write(delimiter.copyOf(random.nextInt(delimiter.size)) + 0.toByte())
                        1 -> out.write("\r\n\r\n--\u0000".toByteArray())
                        2 -> out.write("x--$boundary\u0000".toByteArray()) // no leading CRLF: data, not a delimiter
                        else -> out.write(random.nextBytes(random.nextInt(200)))
                    }
                }
                out.toByteArray()
            }
            val body = multipart(boundary, files.mapIndexed { i, f -> part("form-data; name=\"f$i\"; filename=\"$i.txt\"", f) })
            val stream = if (round % 3 == 0) ByteArrayInputStream(body) else Trickle(body, random, 1 + random.nextInt(9))
            val parts = readAll(MultipartReader(stream, boundary, bufferSize = if (round % 2 == 0) 16 else 64 * 1024))
            assertEquals("round $round", files.size, parts.size)
            for (i in files.indices) assertArrayEquals("round $round part $i", files[i], parts[i].second)
        }
    }

    @Test
    fun multipartLimitsAndDamage() {
        val b = "xyz"
        val body = multipart(b, listOf(part("form-data; name=\"file\"; filename=\"a.txt\"", ByteArray(100) { 'a'.code.toByte() })))
        val reader = MultipartReader(ByteArrayInputStream(body), b)
        assertTrue(reader.start())
        assertNotNull(reader.nextPart())
        val sink = ByteArrayOutputStream()
        try {
            reader.copyBody(sink, 99)
            fail("over the limit")
        } catch (e: LanExchange.TooLarge) {
            assertTrue(sink.size() <= 99)
        }
        // No closing boundary: an error, not a silently short file.
        val cut = body.copyOf(body.size - 12)
        val r2 = MultipartReader(ByteArrayInputStream(cut), b)
        assertTrue(r2.start())
        r2.nextPart()
        try {
            r2.copyBody(ByteArrayOutputStream(), Long.MAX_VALUE)
            fail("truncated")
        } catch (e: IOException) {
            // expected
        }
        // No boundary at all.
        assertFalse(MultipartReader(ByteArrayInputStream("just text".toByteArray()), b).start())
        assertFalse(MultipartReader(ByteArrayInputStream(ByteArray(0)), b).start())
        // Transport padding after a boundary is allowed.
        val padded = "--$b  \r\nContent-Disposition: form-data; name=\"a\"\r\n\r\nv\r\n--$b--".toByteArray()
        val r3 = MultipartReader(ByteArrayInputStream(padded), b)
        assertEquals(1, readAll(r3).size)
    }

    @Test
    fun limitedStreamStopsAtTheLength() {
        val s = LimitedInputStream(ByteArrayInputStream("abcdef".toByteArray()), 4)
        assertEquals("abcd", String(s.readBytes()))
        assertEquals(-1, s.read())
        val short = LimitedInputStream(ByteArrayInputStream("ab".toByteArray()), 4)
        try {
            short.readBytes()
            fail("ended early")
        } catch (e: IOException) {
            // expected
        }
    }

    // ---- address and page ----

    @Test
    fun wifiAddressChoice() {
        fun ip(vararg b: Int) = InetAddress.getByAddress(ByteArray(b.size) { b[it].toByte() })
        val v6 = InetAddress.getByAddress(ByteArray(16).also { it[0] = 0xfe.toByte(); it[1] = 0x80.toByte(); it[15] = 1 })
        val all = listOf(
            "rmnet_data0" to ip(10, 23, 4, 5),
            "tun0" to ip(10, 8, 0, 2),
            "rndis0" to ip(192, 168, 42, 129),
            "wlan1" to ip(172, 16, 0, 9),
            "wlan0" to v6,
            "wlan0" to ip(192, 168, 0, 23),
            "lo" to ip(127, 0, 0, 1),
        )
        assertEquals("192.168.0.23", LanNet.pick(all)!!.hostAddress)
        assertEquals("172.16.0.9", LanNet.pick(all.filterNot { it.first == "wlan0" })!!.hostAddress)
        assertNull(LanNet.pick(all.filterNot { it.first.startsWith("wlan") }))
        assertNull(LanNet.pick(listOf("wlan0" to ip(8, 8, 8, 8))))
        assertNull(LanNet.pick(emptyList()))
    }

    @Test
    fun uploadPageIsSelfContained() {
        val html = LanPage.HTML
        assertTrue(html.contains("<meta charset=\"utf-8\">"))
        assertTrue(html.contains("리더플러스로 책 보내기"))
        // The one warning first, before the drop zone.
        assertTrue(html.indexOf("‘Wi-Fi로 책 받기’ 화면을 열어 두세요") in 0 until html.indexOf("<div id=\"drop\">"))
        assertTrue(html.contains("'/upload'"))
        assertTrue(html.contains("M=${LanUpload.MAX_FILE_BYTES}"))
        assertFalse(html.contains("http://") || html.contains("https://") || html.contains("src="))
        val script = html.substringAfter("<script>").substringBefore("</script>")
        assertTrue("script ${script.toByteArray().size} bytes", script.toByteArray().size < 3 * 1024)
        assertEquals("받았습니다", LanPage.received("a.txt", "a.txt"))
        assertEquals("받았습니다 · a (2).txt 이름으로 저장했습니다", LanPage.received("a.txt", "a (2).txt"))
    }

    // ---- whole exchanges ----

    private val dirs = ArrayList<File>()

    @After
    fun cleanUp() {
        for (d in dirs) d.deleteRecursively()
    }

    private class Recorder : LanExchange.Events {
        val received = ArrayList<File>()
        val errors = ArrayList<String>()
        override fun received(file: File, book: Book?) {
            received += file
        }

        val files = ArrayList<String?>()
        override fun error(message: String, file: String?) {
            errors += message
            files += file
        }
    }

    private class Harness(val dir: File, val clock: LongArray, val events: Recorder, val added: MutableList<File>) {
        val exchange = LanExchange(dir, "k7m3", { clock[0] }, { false }, { added += it; null }, events)

        /** The response: status code and body text ("" for none); [raw] = the whole request. */
        fun send(raw: ByteArray): Pair<Int, String> {
            val out = ByteArrayOutputStream()
            exchange.handle(ByteArrayInputStream(raw), out)
            val text = String(out.toByteArray(), Charsets.UTF_8)
            if (text.isEmpty()) return 0 to ""
            val last = text.split("\r\n\r\n")
            val status = text.lines().last { it.startsWith("HTTP/1.1 ") && !it.startsWith("HTTP/1.1 100") }
            return status.split(' ')[1].toInt() to last.last()
        }

        fun get(path: String, method: String = "GET") = send("$method $path HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())

        fun upload(
            parts: List<Pair<ByteArray, ByteArray>>,
            boundary: String = "----b0und",
            length: Int? = null,
            extra: String = "",
            cut: Int = 0,
        ): Pair<Int, String> {
            val body = multipart(boundary, parts)
            val head = "POST /k7m3/upload HTTP/1.1\r\nHost: x\r\n" +
                "Content-Type: multipart/form-data; boundary=$boundary\r\n" +
                "Content-Length: ${length ?: body.size}\r\n$extra\r\n"
            return send(head.toByteArray(Charsets.ISO_8859_1) + body.copyOf(body.size - cut))
        }
    }

    private fun harness(): Harness {
        val dir = Files.createTempDirectory("lan").toFile()
        dirs += dir
        return Harness(dir, longArrayOf(1_000), Recorder(), ArrayList())
    }

    private fun file(name: String, text: String) = part("form-data; name=\"file\"; filename=\"$name\"", text.toByteArray())

    private fun names(dir: File) = dir.list()!!.sorted()

    @Test
    fun servesThePage() {
        val h = harness()
        val (status, body) = h.get("/k7m3")
        assertEquals(200, status)
        assertTrue(body.contains("리더플러스로 책 보내기"))
        assertEquals(200, h.get("/K7M3/").first)
        val head = h.get("/k7m3", method = "HEAD")
        assertEquals(200 to "", head)
        assertEquals(405, h.get("/k7m3/upload").first)
        assertEquals(400, h.send("garbage\r\n\r\n".toByteArray()).first)
    }

    // File names on disk are ASCII below: a JVM without a UTF-8 file-name encoding (POSIX locale) stores Korean
    // names as "?". Android always uses UTF-8; koreanNamesAreStoredAsSent covers them where the JVM can.

    @Test
    fun receivesAFileAndAddsItToTheLibrary() {
        val h = harness()
        val (status, body) = h.upload(listOf(file("novel A 1-100.txt", "본문 텍스트\r\n둘째 줄")))
        assertEquals(200, status)
        assertEquals("받았습니다", body)
        assertEquals(listOf("novel A 1-100.txt"), names(h.dir))
        assertEquals("본문 텍스트\r\n둘째 줄", File(h.dir, "novel A 1-100.txt").readText())
        assertEquals(listOf(File(h.dir, "novel A 1-100.txt")), h.events.received)
        assertEquals(h.events.received, h.added)
        assertTrue(h.events.errors.isEmpty())
    }

    @Test
    fun koreanNamesAreStoredAsSent() {
        assumeTrue(System.getProperty("sun.jnu.encoding").equals("UTF-8", ignoreCase = true))
        val h = harness()
        assertEquals(200 to "받았습니다", h.upload(listOf(file("소설A 1-100화.txt", "본문"))))
        assertEquals(200, h.upload(listOf(part("form-data; name=\"file\"; filename*=UTF-8''%EC%B1%85.epub", "PK".toByteArray()))).first)
        assertEquals(
            200 to "받았습니다 · 소설A 1-100화 (2).txt 이름으로 저장했습니다",
            h.upload(listOf(file("소설A 1-100화.txt", "다시"))),
        )
        assertEquals(listOf("소설A 1-100화 (2).txt", "소설A 1-100화.txt", "책.epub"), names(h.dir))
    }

    @Test
    fun aSecondFileOfTheSameNameIsNumbered() {
        val h = harness()
        h.upload(listOf(file("novel.txt", "one")))
        val (status, body) = h.upload(listOf(file("novel.txt", "two")))
        assertEquals(200, status)
        assertEquals("받았습니다 · novel (2).txt 이름으로 저장했습니다", body)
        assertEquals(listOf("novel (2).txt", "novel.txt"), names(h.dir))
        assertEquals("one", File(h.dir, "novel.txt").readText())
        assertEquals("two", File(h.dir, "novel (2).txt").readText())
    }

    @Test
    fun severalFilesAndAFieldInOneRequest() {
        val h = harness()
        val (status, body) = h.upload(
            listOf(
                part("form-data; name=\"note\"", "hi".toByteArray(), null),
                file("a.txt", "A"),
                part("form-data; name=\"file\"; filename*=UTF-8''b%6F%6Fk.epub; filename=\"x.epub\"", "PK".toByteArray()),
                part("form-data; name=\"file\"; filename=\"\"", ByteArray(0), "application/octet-stream"),
            ),
        )
        assertEquals(200, status)
        assertEquals("받았습니다 (2개)", body)
        assertEquals(listOf("a.txt", "book.epub"), names(h.dir))
    }

    @Test
    fun refusesOtherTypesAndEmptyFiles() {
        val h = harness()
        val (status, body) = h.upload(listOf(file("그림.pdf", "x")))
        assertEquals(415, status)
        // One file per request (the page): the reason only, the page shows the name before it.
        assertEquals(LanExchange.WRONG_TYPE, body)
        assertEquals(listOf(LanExchange.WRONG_TYPE), h.events.errors)
        assertEquals(listOf<String?>("그림.pdf"), h.events.files)
        val (s2, b2) = h.upload(listOf(file("빈.txt", "")))
        assertEquals(415, s2)
        assertEquals(LanExchange.EMPTY, b2)
        assertEquals("빈.txt", h.events.files.last())
        assertTrue(names(h.dir).isEmpty())
        // One good file and one refused: 200 with the problem listed as "name · reason".
        val (s3, b3) = h.upload(listOf(file("good.txt", "ok"), file("나쁜.exe", "x")))
        assertEquals(200, s3)
        assertEquals("받았습니다 (1개)\n나쁜.exe · ${LanExchange.WRONG_TYPE}", b3)
        assertEquals(listOf("good.txt"), names(h.dir))
        // Two refused in one request: one line each.
        val (s4, b4) = h.upload(listOf(file("a.pdf", "x"), file("b.txt", "")))
        assertEquals(415, s4)
        assertEquals("a.pdf · ${LanExchange.WRONG_TYPE}\nb.txt · ${LanExchange.EMPTY}", b4)
    }

    @Test
    fun pathTricksStayInTheFolder() {
        val h = harness()
        assertEquals(200, h.upload(listOf(file("../../evil.txt", "x"))).first)
        assertEquals(listOf("evil.txt"), names(h.dir))
        assertFalse(File(h.dir.parentFile, "evil.txt").exists())
    }

    @Test
    fun aBrokenUploadLeavesNothingBehind() {
        val h = harness()
        // The connection ends before the announced length: no file, no temp file, an error for the page.
        val (status, body) = h.upload(listOf(file("반쪽.txt", "x".repeat(5000))), cut = 3000)
        assertEquals(400, status)
        assertEquals(LanExchange.CUT_OFF, body)
        assertTrue(names(h.dir).isEmpty())
        assertEquals(listOf(body), h.events.errors)
        // The request failed as a whole: no file is named.
        assertEquals(listOf<String?>(null), h.events.files)
    }

    @Test
    fun aFolderThatCannotBeWrittenIsNotBlamedOnTheConnection() {
        // The destination is a plain file: the temp file can't be created.
        val base = harness()
        val notADir = File(base.dir, "plain").apply { writeText("x") }
        val h = Harness(notADir, longArrayOf(1_000), Recorder(), ArrayList())
        val (status, body) = h.upload(listOf(file("a.txt", "hello")))
        assertEquals(500, status)
        assertEquals(LanExchange.DISK_FAILED, body)
        assertEquals(listOf(body), h.events.errors)
        assertTrue(h.added.isEmpty())
        assertEquals(listOf("plain"), names(base.dir))
    }

    @Test
    fun refusesBadRequestsEarly() {
        val h = harness()
        val tooBig = h.upload(listOf(file("a.txt", "x")), length = (LanUpload.MAX_FILE_BYTES + 65 * 1024 + 1).toInt())
        assertEquals(413 to LanExchange.TOO_LARGE, tooBig)
        assertEquals(listOf(LanExchange.TOO_LARGE), h.events.errors)
        val noLength = h.send("POST /k7m3/upload HTTP/1.1\r\nContent-Type: multipart/form-data; boundary=b\r\n\r\n".toByteArray())
        assertEquals(411, noLength.first)
        val chunked = h.send(
            ("POST /k7m3/upload HTTP/1.1\r\nTransfer-Encoding: chunked\r\nContent-Length: 3\r\n" +
                "Content-Type: multipart/form-data; boundary=b\r\n\r\nabc").toByteArray(),
        )
        assertEquals(411, chunked.first)
        val notMultipart = h.send("POST /k7m3/upload HTTP/1.1\r\nContent-Type: text/plain\r\nContent-Length: 3\r\n\r\nabc".toByteArray())
        assertEquals(400, notMultipart.first)
        assertTrue(names(h.dir).isEmpty())
    }

    @Test
    fun expectContinueIsAnswered() {
        val h = harness()
        val out = ByteArrayOutputStream()
        val body = multipart("bb", listOf(file("a.txt", "A")))
        val raw = ("POST /k7m3/upload HTTP/1.1\r\nContent-Type: multipart/form-data; boundary=bb\r\n" +
            "Content-Length: ${body.size}\r\nExpect: 100-continue\r\n\r\n").toByteArray() + body
        h.exchange.handle(ByteArrayInputStream(raw), out)
        val text = String(out.toByteArray())
        assertTrue(text, text.startsWith("HTTP/1.1 100 Continue\r\n\r\nHTTP/1.1 200 OK\r\n"))
    }

    @Test
    fun aTrickledHeadIsCutOffAtItsDeadline() {
        val h = harness()
        // One byte per read, 500 ms apart, never ending the request line: answered 400 once HEAD_MS has passed,
        // not after 16 KB (≈ days at one byte per socket timeout).
        var reads = 0
        val trickle = object : InputStream() {
            override fun read(): Int {
                h.clock[0] += 500
                reads++
                return 'G'.code
            }
        }
        val out = ByteArrayOutputStream()
        h.exchange.handle(trickle, out)
        assertTrue(String(out.toByteArray()).startsWith("HTTP/1.1 400 "))
        assertTrue("$reads", reads <= LanExchange.HEAD_MS / 500 + 1)
        // A socket whose short reads time out is retried until the same deadline, then answered alike.
        var timeouts = 0
        val quiet = object : InputStream() {
            override fun read(): Int {
                h.clock[0] += 1_000
                timeouts++
                throw SocketTimeoutException("Read timed out")
            }
        }
        val out2 = ByteArrayOutputStream()
        h.exchange.handle(quiet, out2)
        assertTrue(String(out2.toByteArray()).startsWith("HTTP/1.1 400 "))
        assertTrue("$timeouts", timeouts in 2..LanExchange.HEAD_MS / 1_000 + 1)
        // Without a deadline a timeout is the caller's (no endless retry).
        try {
            LanHttp.readHead(quiet)
            fail()
        } catch (e: SocketTimeoutException) {
        }
        // Slow wrong requests are no guesses at the code.
        assertFalse(h.exchange.ignoring())
    }

    @Test
    fun onlyAnUploadsBodyGetsTheLongTimeout() {
        val h = harness()
        var calls = 0
        fun send(raw: ByteArray) = h.exchange.handle(ByteArrayInputStream(raw), ByteArrayOutputStream()) { calls++ }
        send("GET /k7m3 HTTP/1.1\r\n\r\n".toByteArray())
        send("GET /zzzz HTTP/1.1\r\n\r\n".toByteArray())
        assertEquals(0, calls)
        val body = multipart("bb", listOf(file("a.txt", "A")))
        send(
            ("POST /k7m3/upload HTTP/1.1\r\nContent-Type: multipart/form-data; boundary=bb\r\n" +
                "Content-Length: ${body.size}\r\n\r\n").toByteArray() + body,
        )
        assertEquals(1, calls)
        assertEquals(listOf("a.txt"), names(h.dir))
    }

    @Test
    fun wrongPathsAreRateLimited() {
        val h = harness()
        // Browser housekeeping never counts.
        repeat(20) { assertEquals(404, h.get("/favicon.ico").first) }
        repeat(9) { assertEquals(404, h.get("/0000").first) }
        assertFalse(h.exchange.ignoring())
        assertEquals(404, h.get("/zzzz").first)
        assertTrue(h.exchange.ignoring())
        // Ignored: not even the right code gets an answer.
        assertEquals(0 to "", h.get("/k7m3"))
        h.clock[0] += 30_000
        assertFalse(h.exchange.ignoring())
        assertEquals(200, h.get("/k7m3").first)
        // The right code resets the count.
        repeat(9) { h.get("/aaaa") }
        h.get("/k7m3")
        repeat(9) { h.get("/aaaa") }
        assertFalse(h.exchange.ignoring())
    }

    @Test
    fun staleTempFilesAreRemoved() {
        val h = harness()
        val stale = File(h.dir, "${LanExchange.TEMP_PREFIX}123${LanExchange.TEMP_SUFFIX}")
        stale.writeText("x")
        stale.setLastModified(System.currentTimeMillis() - 2 * 60 * 60 * 1000L)
        // An upload still being written is left alone.
        File(h.dir, "${LanExchange.TEMP_PREFIX}456${LanExchange.TEMP_SUFFIX}").writeText("x")
        File(h.dir, "keep.txt").writeText("x")
        File(h.dir, ".upload-note.txt").writeText("x")
        h.exchange.removeStaleTemps()
        assertEquals(listOf(".upload-456.part", ".upload-note.txt", "keep.txt"), names(h.dir))
    }
}
