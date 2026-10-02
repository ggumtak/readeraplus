package com.ggumtak.readeraplus.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.ggumtak.readeraplus.format.txt.TxtCharsets
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue

/**
 * "Wi-Fi로 책 받기" (T1-12): a tiny HTTP server on the Wi-Fi address that takes .txt / .epub uploads from a browser
 * on the same network and adds them to the library.
 *
 * Owner: DATA. User: SETTINGS (`ui/settings/WifiTransferPage.kt`), which calls [start] when the page is shown and the
 * activity resumed and [stop] on page pop / onPause, and keeps the screen on while the page is open. Nothing runs
 * while the page is closed: no service, no background thread, no port.
 *
 * Behaviour:
 * - `java.net.ServerSocket` bound to the site-local IPv4 address of a `wlan*` interface (never 0.0.0.0, so it is not
 *   reachable over USB tethering or a VPN), port 8080, else 8081–8089. One accept thread plus one handler thread,
 *   `Connection: close`, 30 s socket timeouts. A connection that sends nothing (a browser's spare pre-connection) is
 *   dropped as soon as another one waits, and a request head must arrive within 10 s however slowly its bytes come
 *   ([LanExchange.HEAD_MS]), so neither can hold the single handler.
 * - Access code: [LanCode.LENGTH] chars from [LanCode.ALPHABET] (31 unambiguous chars: no 0/o/1/l/i), random per
 *   start. Routes: `GET /<code>` → an inline Korean upload page (multi-file input, drop zone, < 3 KB of plain JS with
 *   XHR progress in the browser); `POST /<code>/upload` → multipart body streamed to disk (never buffered whole);
 *   anything else → 404. After 10 wrong paths every request is ignored for 30 s ([LanGuard]).
 * - Files: `.txt` / `.epub` only, at most [MAX_FILE_BYTES]; names from `filename*=` (RFC 5987) or `filename=`
 *   decoded as UTF-8, sanitised ([LanNames]); a collision becomes "이름 (2).txt". Written to a hidden temp name and
 *   renamed when complete; a failed upload leaves nothing behind.
 * - Each finished file: `Library.addOrUpdateFile` on the handler thread, then [Listener.onReceived].
 *
 * Threading: [start] / [stop] on the main thread ([start] binds a socket: fast, no DNS). Listener callbacks are posted
 * to the main thread, and never after [stop].
 */
class LanUpload(private val context: Context, private val destDir: File, private val listener: Listener) {

    /** Events for the page. Always called on the main thread. */
    interface Listener {
        /** [file] was received and stored; [book] is its library entry, or null when adding it failed. */
        fun onReceived(file: File, book: Book?)

        /** An upload was refused or failed; [message] is user text ("200MB보다 큰 파일은 받을 수 없습니다", …). */
        fun onError(message: String)
    }

    /** A running server: [url] for the browser, e.g. "http://192.168.0.23:8080/k7m3"; [code] is its path part. */
    data class Result(val url: String, val code: String)

    /** Why the last [start] returned null (user text, e.g. "Wi-Fi에 연결되어 있지 않습니다"), else null. */
    var lastError: String? = null
        private set

    private val main = Handler(Looper.getMainLooper())

    @Volatile private var server: Server? = null

    /** True between a successful [start] and [stop]. */
    val isRunning: Boolean
        get() = server != null

    /** Starts the server (idempotent: returns the running one's result). Null when no Wi-Fi address or no free port. */
    fun start(): Result? {
        server?.let { return it.result }
        lastError = null
        Library.init(context)
        val address = LanNet.wifiAddress()
        if (address == null) {
            lastError = NO_WIFI
            return null
        }
        var socket: ServerSocket? = null
        for (port in PORTS) {
            val s = ServerSocket()
            try {
                s.reuseAddress = true
                s.bind(InetSocketAddress(address, port), BACKLOG)
                socket = s
                break
            } catch (e: IOException) {
                closeQuietly(s)
            }
        }
        if (socket == null) {
            lastError = NO_PORT
            return null
        }
        val code = LanCode.generate { random.nextInt(it) }
        val result = Result("http://${address.hostAddress}:${socket.localPort}/$code", code)
        val srv = Server(socket, code, result)
        server = srv
        srv.begin()
        return result
    }

    /** Stops accepting, closes the sockets and ends both threads; an upload in progress is abandoned. Idempotent. */
    fun stop() {
        val s = server ?: return
        server = null
        s.shutdown()
    }

    /** One running server: its socket, its two threads and the connection being handled. */
    private inner class Server(private val socket: ServerSocket, code: String, val result: Result) {
        @Volatile private var stopped = false
        @Volatile private var current: Socket? = null
        private val queue = ArrayBlockingQueue<Socket>(QUEUE)
        private val acceptThread = Thread({ acceptLoop() }, "lan-upload-accept")
        private val handlerThread = Thread({ handleLoop() }, "lan-upload-handler")
        private val exchange = LanExchange(
            destDir = destDir,
            code = code,
            now = SystemClock::elapsedRealtime,
            stopped = { stopped },
            addToLibrary = { f ->
                try {
                    Library.addOrUpdateFile(f)
                } catch (t: Throwable) {
                    Log.w(TAG, "library add failed: $t")
                    null
                }
            },
            events = object : LanExchange.Events {
                override fun received(file: File, book: Book?) = post { listener.onReceived(file, book) }
                override fun error(message: String) = post { listener.onError(message) }
            },
        )

        fun begin() {
            acceptThread.isDaemon = true
            handlerThread.isDaemon = true
            acceptThread.start()
            handlerThread.start()
        }

        fun shutdown() {
            stopped = true
            closeQuietly(socket)
            closeQuietly(current)
            handlerThread.interrupt()
            drainQueue()
        }

        private fun drainQueue() {
            while (true) closeQuietly(queue.poll() ?: return)
        }

        private fun acceptLoop() {
            while (!stopped) {
                val s = try {
                    socket.accept()
                } catch (e: IOException) {
                    break
                }
                if (stopped || !queue.offer(s)) closeQuietly(s)
            }
        }

        private fun handleLoop() {
            exchange.removeStaleTemps()
            while (!stopped) {
                val s = try {
                    queue.take()
                } catch (e: InterruptedException) {
                    break
                }
                current = s
                try {
                    if (!stopped) handle(s)
                } catch (t: Throwable) {
                    if (!stopped) Log.w(TAG, "request failed: $t")
                } finally {
                    current = null
                    closeQuietly(s)
                }
            }
            drainQueue()
        }

        /** Posts [block] to the main thread unless this server was stopped by then. */
        private fun post(block: () -> Unit) {
            main.post { if (server === this) block() }
        }

        private fun handle(s: Socket) {
            if (exchange.ignoring()) return
            s.tcpNoDelay = true
            s.soTimeout = POLL_MS
            val input = BufferedInputStream(s.getInputStream(), BUFFER)
            if (!awaitRequest(input)) return
            // The head is read in POLL_MS reads against its deadline; only an upload's body gets the long timeout.
            exchange.handle(input, BufferedOutputStream(s.getOutputStream(), 8 * 1024)) { s.soTimeout = TIMEOUT_MS }
        }

        /**
         * Waits (up to [TIMEOUT_MS]) for the first byte of a request without consuming it. Gives up at once when
         * another connection is waiting: a browser's unused pre-connection must not block the only handler.
         */
        private fun awaitRequest(input: BufferedInputStream): Boolean {
            val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
            while (!stopped) {
                try {
                    input.mark(1)
                    if (input.read() < 0) return false
                    input.reset()
                    return true
                } catch (e: SocketTimeoutException) {
                    if (queue.isNotEmpty() || SystemClock.elapsedRealtime() >= deadline) return false
                }
            }
            return false
        }
    }

    companion object {
        private const val TAG = "LanUpload"

        /** Largest accepted file. */
        const val MAX_FILE_BYTES = 200L * 1024 * 1024

        /** Ports tried in order. */
        val PORTS: IntRange = 8080..8089

        private const val BACKLOG = 8
        private const val QUEUE = 8
        private const val TIMEOUT_MS = 30_000
        /** How often a quiet connection checks whether another one is waiting. */
        private const val POLL_MS = 1_000
        private const val BUFFER = 64 * 1024

        internal const val NO_WIFI = "Wi-Fi에 연결되어 있지 않습니다"
        internal const val NO_PORT = "전송에 쓸 포트를 열지 못했습니다 (8080–8089 모두 사용 중)"

        private val random by lazy { SecureRandom() }

        /**
         * Where received books go: `<primary storage>/Books` with all-files access (the scanner covers it by
         * default), else the app's `getExternalFilesDir("books")`. Created when missing. Blocking (IO).
         */
        fun destinationDir(context: Context): File {
            if (FileScanner.hasAllFilesAccess(context)) {
                val shared = File(Library.primaryRoot(), "Books")
                try {
                    if ((shared.isDirectory || shared.mkdirs()) && shared.canWrite()) return shared
                } catch (t: Throwable) {
                    Log.w(TAG, "shared Books folder unusable: $t")
                }
            }
            val own = try {
                context.getExternalFilesDir("books")
            } catch (_: Throwable) {
                null
            } ?: File(context.filesDir, "books")
            own.mkdirs()
            return own
        }

        private fun closeQuietly(c: Closeable?) {
            try {
                c?.close()
            } catch (_: Throwable) {
            }
        }
    }
}

/**
 * One HTTP exchange of the upload server: request head → route → page, upload or 404, on plain streams (no socket,
 * no Android: the handler thread passes the connection's streams; tests pass byte arrays). Not thread-safe: the
 * server's single handler thread is its only caller.
 */
internal class LanExchange(
    private val destDir: File,
    private val code: String,
    private val now: () -> Long,
    private val stopped: () -> Boolean,
    private val addToLibrary: (File) -> Book?,
    private val events: Events,
    private val guard: LanGuard = LanGuard(),
) {
    /** What the page learns (called on the handler thread; the server posts them to the main thread). */
    interface Events {
        fun received(file: File, book: Book?)
        fun error(message: String)
    }

    /** Thrown by [MultipartReader.copyBody] beyond its limit. */
    class TooLarge : IOException("part too large")

    /** Writing the received file failed (the folder, not the connection): the page says so instead of "연결이 끊겼습니다". */
    private class DiskError(cause: IOException) : IOException(cause.message, cause)

    /** The temp file's stream with its failures marked as [DiskError]. */
    private class DiskOutput(private val out: OutputStream) : OutputStream() {
        override fun write(b: Int) = disk { out.write(b) }
        override fun write(b: ByteArray, off: Int, len: Int) = disk { out.write(b, off, len) }
        override fun flush() = disk { out.flush() }
        override fun close() = disk { out.close() }

        private inline fun disk(block: () -> Unit) {
            try {
                block()
            } catch (e: DiskError) {
                throw e
            } catch (e: IOException) {
                throw DiskError(e)
            }
        }
    }

    /** True while too many wrong paths make the server ignore every request (the connection is just closed). */
    fun ignoring(): Boolean = guard.blocked(now())

    /**
     * Reads one request from [input] and answers it on [out] (nothing at all while [ignoring]). The head must arrive
     * within [HEAD_MS] (400 otherwise); [beforeBody] runs before an upload's body is read (the server lengthens the
     * socket's read timeout there).
     */
    fun handle(input: InputStream, out: OutputStream, beforeBody: () -> Unit = {}) {
        if (ignoring()) return
        val deadline = now() + HEAD_MS
        val head = LanHttp.readHead(input) { now() >= deadline }
        if (head == null) {
            respond(out, 400, TEXT, BAD_REQUEST)
            return
        }
        when (LanHttp.route(head.method, head.path, code)) {
            LanHttp.Route.PAGE -> {
                guard.onRight()
                respond(out, 200, HTML, LanPage.HTML, bodyless = head.method == "HEAD")
            }
            LanHttp.Route.UPLOAD -> {
                guard.onRight()
                beforeBody()
                upload(head, input, out)
            }
            LanHttp.Route.WRONG_METHOD -> respond(out, 405, TEXT, "허용되지 않는 요청입니다")
            LanHttp.Route.NOT_FOUND -> respond(out, 404, TEXT, NOT_FOUND)
            LanHttp.Route.WRONG -> {
                guard.onWrong(now())
                respond(out, 404, TEXT, NOT_FOUND)
            }
        }
    }

    private fun upload(head: LanHttp.Head, input: InputStream, out: OutputStream) {
        val length = head.contentLength()
        if (length == null || head.header("transfer-encoding") != null) {
            respond(out, 411, TEXT, "파일 크기를 알 수 없는 요청입니다")
            return
        }
        if (length > LanUpload.MAX_FILE_BYTES + MAX_OVERHEAD_BYTES) {
            events.error(TOO_LARGE)
            respondEarly(out, input, length, 413, TOO_LARGE)
            return
        }
        val boundary = LanHttp.boundary(head.header("content-type") ?: "")
        if (boundary == null) {
            respondEarly(out, input, length, 400, BAD_REQUEST)
            return
        }
        destDir.mkdirs()
        val free = destDir.usableSpace
        if (free in 0 until length + SPACE_MARGIN_BYTES) {
            events.error(NO_SPACE)
            respondEarly(out, input, length, 507, NO_SPACE)
            return
        }
        if (head.header("expect")?.equals("100-continue", ignoreCase = true) == true) {
            out.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
            out.flush()
        }
        val body = LimitedInputStream(input, length)
        val parts = MultipartReader(body, boundary)
        /** (sanitised name asked for, name stored). */
        val saved = ArrayList<Pair<String, String>>(1)
        val problems = ArrayList<String>(1)
        try {
            if (!parts.start()) {
                respond(out, 400, TEXT, BAD_REQUEST)
                return
            }
            var count = 0
            while (!stopped()) {
                val headers = parts.nextPart() ?: break
                if (++count > MAX_PARTS) throw IOException("too many parts")
                val raw = headers["content-disposition"]?.let(LanNames::fromContentDisposition)
                if (raw.isNullOrEmpty()) {
                    // A plain form field, or a file input left empty.
                    parts.copyBody(null, MAX_FIELD_BYTES)
                    continue
                }
                val name = LanNames.sanitize(raw)
                if (name == null) {
                    parts.copyBody(null, LanUpload.MAX_FILE_BYTES)
                    problems += "$WRONG_TYPE: ${LanNames.display(raw)}"
                    continue
                }
                val file = receive(parts, name, problems) ?: continue
                saved += name to file.name
                events.received(file, addToLibrary(file))
            }
            body.skipRest()
        } catch (e: TooLarge) {
            events.error(TOO_LARGE)
            respond(out, 413, TEXT, TOO_LARGE)
            return
        } catch (e: IOException) {
            if (stopped()) return
            val (status, message) = when {
                isNoSpace(e) -> 507 to NO_SPACE
                e is DiskError -> 500 to "$FAILED (저장하지 못했습니다)"
                else -> 400 to "$FAILED (연결이 끊겼습니다)"
            }
            events.error(message)
            // The browser may still read this if only the rest of its body was lost.
            respond(out, status, TEXT, message)
            return
        }
        for (p in problems) events.error(p)
        when {
            problems.isEmpty() && saved.size == 1 -> respond(out, 200, TEXT, LanPage.received(saved[0].first, saved[0].second))
            problems.isEmpty() -> respond(out, 200, TEXT, "받았습니다 (${saved.size}개)")
            saved.isEmpty() -> respond(out, 415, TEXT, problems.joinToString("\n"))
            else -> respond(out, 200, TEXT, "받았습니다 (${saved.size}개)\n" + problems.joinToString("\n"))
        }
    }

    /** Streams the current part to [name] in [destDir] (temp file, then renamed); null (and a problem) on refusal. */
    private fun receive(parts: MultipartReader, name: String, problems: MutableList<String>): File? {
        val temp = File(destDir, "$TEMP_PREFIX${System.nanoTime()}$TEMP_SUFFIX")
        var done = false
        try {
            val fo = try {
                FileOutputStream(temp)
            } catch (e: IOException) {
                throw DiskError(e)
            }
            val size = DiskOutput(fo).use { parts.copyBody(it, LanUpload.MAX_FILE_BYTES) }
            if (size == 0L) {
                problems += "$EMPTY: ${LanNames.display(name)}"
                return null
            }
            val target = File(destDir, LanNames.unique(name) { File(destDir, it).exists() })
            if (!temp.renameTo(target)) throw DiskError(IOException("rename failed"))
            done = true
            return target
        } finally {
            if (!done) temp.delete()
        }
    }

    /** Temp files an earlier run left behind (process killed mid-upload): only one server writes here at a time. */
    fun removeStaleTemps() {
        try {
            destDir.list()?.forEach { n ->
                if (n.startsWith(TEMP_PREFIX) && n.endsWith(TEMP_SUFFIX)) File(destDir, n).delete()
            }
        } catch (t: Throwable) {
            // Best effort: a leftover hidden temp file costs only its space.
        }
    }

    companion object {
        /**
         * Time for a whole request head (a browser sends it at once). Before the access code is known, a peer
         * trickling one byte per read timeout must not hold the only handler thread for days.
         */
        internal const val HEAD_MS = 10_000L

        /** Multipart headers and boundaries around one file: generous. */
        private const val MAX_OVERHEAD_BYTES = 64L * 1024
        private const val MAX_FIELD_BYTES = 64L * 1024
        private const val MAX_PARTS = 64
        private const val SPACE_MARGIN_BYTES = 8L * 1024 * 1024
        /** Bytes read and dropped so a browser can see an early error response; beyond it the connection is cut. */
        private const val EARLY_DRAIN_BYTES = 1L * 1024 * 1024
        internal const val TEMP_PREFIX = ".upload-"
        internal const val TEMP_SUFFIX = ".part"
        private const val TEXT = "text/plain; charset=utf-8"
        private const val HTML = "text/html; charset=utf-8"

        internal const val TOO_LARGE = "200MB보다 큰 파일은 받을 수 없습니다"
        internal const val WRONG_TYPE = "TXT·EPUB 파일만 받을 수 있습니다"
        internal const val NO_SPACE = "저장 공간이 부족합니다"
        internal const val EMPTY = "빈 파일은 받을 수 없습니다"
        internal const val FAILED = "파일을 받지 못했습니다"
        private const val BAD_REQUEST = "잘못된 요청입니다"
        private const val NOT_FOUND = "찾을 수 없습니다"

        private val STATUS = mapOf(
            200 to "OK", 400 to "Bad Request", 404 to "Not Found", 405 to "Method Not Allowed", 411 to "Length Required",
            413 to "Payload Too Large", 415 to "Unsupported Media Type", 500 to "Internal Server Error",
            507 to "Insufficient Storage",
        )

        private fun respond(out: OutputStream, status: Int, type: String, body: String, bodyless: Boolean = false) {
            val bytes = body.toByteArray(Charsets.UTF_8)
            val head = "HTTP/1.1 $status ${STATUS[status] ?: "Error"}\r\n" +
                "Content-Type: $type\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Cache-Control: no-store\r\n" +
                "X-Content-Type-Options: nosniff\r\n" +
                "Connection: close\r\n\r\n"
            try {
                out.write(head.toByteArray(Charsets.ISO_8859_1))
                if (!bodyless) out.write(bytes)
                out.flush()
            } catch (e: IOException) {
                // The client went away: nothing to tell it.
            }
        }

        /**
         * An error answer before the body was read. A browser that is still sending may miss a response written
         * while it sends, so a small rest of the body (≤ [EARLY_DRAIN_BYTES]) is read and dropped first.
         */
        private fun respondEarly(out: OutputStream, input: InputStream, length: Long, status: Int, message: String) {
            if (length <= EARLY_DRAIN_BYTES) {
                try {
                    LimitedInputStream(input, length).skipRest()
                } catch (e: IOException) {
                    return
                }
            }
            respond(out, status, TEXT, message)
        }

        private fun isNoSpace(t: Throwable): Boolean {
            var c: Throwable? = t
            var depth = 0
            while (c != null && depth++ < 8) {
                val m = c.message
                if (m != null && (m.contains("ENOSPC") || m.contains("No space left", ignoreCase = true))) return true
                c = c.cause
            }
            return false
        }
    }
}

/** Access codes (pure). */
internal object LanCode {
    /** 31 chars without 0, o, 1, l, i (unambiguous when read off the screen and typed into a browser). */
    const val ALPHABET = "23456789abcdefghjkmnpqrstuvwxyz"
    const val LENGTH = 4

    /** A random code of [LENGTH] chars from [ALPHABET] ([random] returns 0 until bound, exclusive). */
    fun generate(random: (Int) -> Int): String {
        val c = CharArray(LENGTH)
        for (i in c.indices) c[i] = ALPHABET[random(ALPHABET.length).coerceIn(0, ALPHABET.length - 1)]
        return String(c)
    }
}

/**
 * "10 wrong paths → ignore every request for 30 s" (pure; the handler thread is its only user). A request for the
 * right code resets the count: only someone who knows the code can make one.
 */
internal class LanGuard(private val limit: Int = 10, private val blockMs: Long = 30_000) {
    private var wrong = 0
    private var blockedUntil = Long.MIN_VALUE

    fun blocked(now: Long): Boolean = now < blockedUntil

    fun onWrong(now: Long) {
        if (++wrong >= limit) {
            wrong = 0
            blockedUntil = now + blockMs
        }
    }

    fun onRight() {
        wrong = 0
    }
}

/** File names received over the LAN (pure). */
internal object LanNames {
    private const val MAX_NAME_BYTES = 200
    private val ALLOWED_EXTENSIONS = setOf("txt", "epub")
    private const val FORBIDDEN = "\\/:*?\"<>|"

    /**
     * A safe file name: path components stripped (both separators), `..`, control chars and `\ / : * ? " < > |`
     * removed, whitespace trimmed, Korean kept, at most 200 UTF-8 bytes (extension kept); null when nothing usable
     * is left or the extension isn't .txt / .epub (case-insensitive). Also NFC (macOS browsers send decomposed
     * Hangul), without bidi controls (no "txt.exe" spoofing) and without leading dots (no hidden files).
     */
    fun sanitize(raw: String): String? {
        val last = maxOf(raw.lastIndexOf('/'), raw.lastIndexOf('\\'))
        val nfc = LibrarySql.nfc(raw.substring(last + 1))
        val sb = StringBuilder(nfc.length)
        for (c in nfc) {
            if (c < ' ' || c == '\u007f' || FORBIDDEN.indexOf(c) >= 0 || isInvisibleControl(c)) continue
            sb.append(c)
        }
        val s = sb.toString().trim()
        val dot = s.lastIndexOf('.')
        if (dot < 0) return null
        val ext = s.substring(dot + 1).trim()
        if (ext.lowercase(Locale.ROOT) !in ALLOWED_EXTENSIONS) return null
        val base = s.substring(0, dot).trim().trimStart('.', ' ').trimEnd('.', ' ')
        if (base.isEmpty()) return null
        val room = MAX_NAME_BYTES - 1 - ext.toByteArray(Charsets.UTF_8).size
        return "${fitUtf8(base, room).trimEnd('.', ' ')}.$ext"
    }

    /** [name], or "이름 (2).ext", "이름 (3).ext", … — the first that [exists] says is free. */
    fun unique(name: String, exists: (String) -> Boolean): String {
        if (!exists(name)) return name
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        for (n in 2..9_999) {
            val candidate = "$base ($n)$ext"
            if (!exists(candidate)) return candidate
        }
        return "$base (${System.currentTimeMillis()})$ext"
    }

    /**
     * The file name of a multipart part's `Content-Disposition` header value: `filename*=UTF-8''…` (percent-decoded)
     * wins over `filename="…"`, whose raw bytes are UTF-8 (what browsers send). Null when neither is present; ""
     * for an empty file input. `filename=` also undoes the browsers' `%22` / `%0D` / `%0A` escapes (HTML spec).
     */
    fun fromContentDisposition(header: String): String? {
        var plain: String? = null
        var extended: String? = null
        for (p in LanHttp.params(header)) {
            when (p.first) {
                "filename*" -> extended = decodeExtended(p.second) ?: extended
                "filename" -> plain = p.second
            }
        }
        if (!extended.isNullOrEmpty()) return extended
        return plain?.replace("%22", "\"")?.replace("%0D", "\r")?.replace("%0A", "\n")
    }

    /** A name as shown in a message: without the path, cut to 60 chars. */
    fun display(raw: String): String {
        val last = maxOf(raw.lastIndexOf('/'), raw.lastIndexOf('\\'))
        return MetaInfo.clean(raw.substring(last + 1), 60)
    }

    /** RFC 5987 `charset'language'percent-encoded` → text; null when malformed. */
    private fun decodeExtended(v: String): String? {
        val first = v.indexOf('\'')
        val second = if (first < 0) -1 else v.indexOf('\'', first + 1)
        if (second < 0) return null
        val charset = v.substring(0, first).trim()
        val bytes = ByteArrayOutputStream(v.length)
        var i = second + 1
        while (i < v.length) {
            val c = v[i]
            if (c == '%') {
                if (i + 2 >= v.length) return null
                val h = Character.digit(v[i + 1], 16)
                val l = Character.digit(v[i + 2], 16)
                if (h < 0 || l < 0) return null
                bytes.write(h * 16 + l)
                i += 3
            } else {
                if (c.code > 0x7f) return null
                bytes.write(c.code)
                i++
            }
        }
        val cs = if (charset.equals("iso-8859-1", ignoreCase = true)) Charsets.ISO_8859_1 else Charsets.UTF_8
        return String(bytes.toByteArray(), cs)
    }

    private fun isInvisibleControl(c: Char): Boolean =
        c == '‎' || c == '‏' || c in '‪'..'‮' || c in '⁦'..'⁩' || c == '﻿'

    /** The longest prefix of [s] of at most [maxBytes] UTF-8 bytes, never splitting a surrogate pair. */
    private fun fitUtf8(s: String, maxBytes: Int): String {
        var bytes = 0
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val n = when {
                cp < 0x80 -> 1
                cp < 0x800 -> 2
                cp < 0x10000 -> 3
                else -> 4
            }
            if (bytes + n > maxBytes) break
            bytes += n
            i += Character.charCount(cp)
        }
        return s.substring(0, i)
    }
}

/** HTTP/1.1 request head and multipart parsing (pure, streaming; fuzz-tested by DATA). */
internal object LanHttp {
    private const val MAX_HEAD_BYTES = 16 * 1024
    private const val MAX_HEADERS = 64
    private const val MAX_BOUNDARY = 70

    /** A parsed request head: header names lower-cased, repeated headers joined with ", ". */
    class Head(val method: String, val path: String, val headers: Map<String, String>) {
        fun header(name: String): String? = headers[name.lowercase(Locale.ROOT)]

        /** `Content-Length` as a number, or null when absent or not a plain non-negative integer. */
        fun contentLength(): Long? {
            val v = header("content-length")?.trim() ?: return null
            if (v.isEmpty() || v.length > 15 || !v.all { it in '0'..'9' }) return null
            return v.toLong()
        }
    }

    enum class Route { PAGE, UPLOAD, WRONG_METHOD, NOT_FOUND, WRONG }

    /** Method and path of a request line ("POST /k7m3/upload HTTP/1.1"), or null when malformed. */
    fun requestLine(line: String): Pair<String, String>? {
        val parts = line.split(' ')
        if (parts.size != 3) return null
        val method = parts[0]
        val target = parts[1]
        if (method.isEmpty() || method.length > 16 || !method.all { it in 'A'..'Z' }) return null
        if (!parts[2].startsWith("HTTP/1.")) return null
        if (!target.startsWith("/")) return null
        return method to target.substringBefore('?').substringBefore('#')
    }

    /** The boundary of a `Content-Type: multipart/form-data; boundary=…` value (quotes removed), or null. */
    fun boundary(contentType: String): String? {
        val type = contentType.substringBefore(';').trim()
        if (!type.equals("multipart/form-data", ignoreCase = true)) return null
        val b = params(contentType).firstOrNull { it.first == "boundary" }?.second ?: return null
        if (b.isEmpty() || b.length > MAX_BOUNDARY || b.any { it < ' ' || it.code > 0x7e }) return null
        return b
    }

    /**
     * Where [method] + [path] go for access [code] (case-insensitive: "/K7M3" works too). Browser housekeeping
     * requests (`/favicon.ico`, `/robots.txt`, `/apple-touch-icon…`) are plain 404s that don't count as guesses.
     */
    fun route(method: String, path: String, code: String): Route {
        val p = path.lowercase(Locale.ROOT)
        val root = "/" + code.lowercase(Locale.ROOT)
        return when {
            p == root || p == "$root/" -> if (method == "GET" || method == "HEAD") Route.PAGE else Route.WRONG_METHOD
            p == "$root/upload" -> if (method == "POST") Route.UPLOAD else Route.WRONG_METHOD
            p.startsWith("$root/") -> Route.NOT_FOUND
            p == "/favicon.ico" || p == "/robots.txt" || p.startsWith("/apple-touch-icon") -> Route.NOT_FOUND
            else -> Route.WRONG
        }
    }

    /**
     * Reads a request head (request line and headers up to the empty line, at most 16 KB) from [input], leaving
     * the body unread. Null when malformed, too large, the stream ends early or [expired] turns true (checked before
     * every byte; with it, a read that times out is retried until then).
     */
    fun readHead(input: InputStream, expired: (() -> Boolean)? = null): Head? {
        val budget = intArrayOf(MAX_HEAD_BYTES)
        val first = readLine(input, budget, expired) ?: return null
        val (method, path) = requestLine(first) ?: return null
        val headers = LinkedHashMap<String, String>()
        var count = 0
        while (true) {
            val line = readLine(input, budget, expired) ?: return null
            if (line.isEmpty()) break
            if (++count > MAX_HEADERS) return null
            val colon = line.indexOf(':')
            if (colon <= 0) return null
            val name = line.substring(0, colon).trim().lowercase(Locale.ROOT)
            val value = line.substring(colon + 1).trim()
            headers[name] = headers[name]?.let { "$it, $value" } ?: value
        }
        return Head(method, path, headers)
    }

    /** One CRLF (or bare LF) terminated line as Latin-1, charged to [budget]; null at EOF, over budget or [expired]. */
    private fun readLine(input: InputStream, budget: IntArray, expired: (() -> Boolean)?): String? {
        val sb = StringBuilder(64)
        while (true) {
            if (expired != null && expired()) return null
            val b = try {
                input.read()
            } catch (e: SocketTimeoutException) {
                if (expired == null) throw e
                continue
            }
            if (b < 0) return null
            if (--budget[0] < 0) return null
            if (b == '\n'.code) {
                if (sb.isNotEmpty() && sb[sb.length - 1] == '\r') sb.setLength(sb.length - 1)
                return sb.toString()
            }
            sb.append(b.toChar())
        }
    }

    /**
     * `key=value` parameters of a header value such as `form-data; name="file"; filename="a.txt"` (keys lower-cased,
     * quoted values unquoted with `\"` / `\\` escapes). The first segment (the value itself) is skipped.
     */
    fun params(header: String): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>(4)
        var i = header.indexOf(';')
        if (i < 0) return out
        val n = header.length
        while (i < n) {
            i++ // past ';'
            while (i < n && header[i].isWhitespace()) i++
            val keyStart = i
            while (i < n && header[i] != '=' && header[i] != ';') i++
            val key = header.substring(keyStart, i).trim().lowercase(Locale.ROOT)
            if (i >= n || header[i] == ';') {
                continue
            }
            i++ // past '='
            while (i < n && header[i].isWhitespace()) i++
            val value: String
            if (i < n && header[i] == '"') {
                val sb = StringBuilder()
                i++
                while (i < n && header[i] != '"') {
                    if (header[i] == '\\' && i + 1 < n) i++
                    sb.append(header[i])
                    i++
                }
                i++ // past the closing quote (or the end)
                value = sb.toString()
                while (i < n && header[i] != ';') i++
            } else {
                val start = i
                while (i < n && header[i] != ';') i++
                value = header.substring(start, i).trim()
            }
            if (key.isNotEmpty()) out += key to value
        }
        return out
    }

    /**
     * Header bytes of a multipart part as text: UTF-8 (what browsers send); bytes that aren't valid UTF-8 are read
     * as CP949 (old Korean Windows browsers), else Latin-1.
     */
    fun decodeHeader(bytes: ByteArray): String {
        if (bytes.all { it >= 0 }) return String(bytes, Charsets.ISO_8859_1)
        strict(Charsets.UTF_8, bytes)?.let { return it }
        TxtCharsets.cp949Charset?.let { cs -> strict(cs, bytes)?.let { return it } }
        return String(bytes, Charsets.ISO_8859_1)
    }

    private fun strict(cs: Charset, bytes: ByteArray): String? = try {
        cs.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (e: CharacterCodingException) {
        null
    }
}

/** Reads at most [length] bytes of [input] (a request body), then reports the end. */
internal class LimitedInputStream(private val input: InputStream, length: Long) : InputStream() {
    private var left = length

    override fun read(): Int {
        if (left <= 0) return -1
        val b = input.read()
        if (b < 0) throw IOException("body ended early")
        left--
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (left <= 0) return -1
        if (len == 0) return 0
        val n = input.read(b, off, minOf(len.toLong(), left).toInt())
        if (n < 0) throw IOException("body ended early")
        left -= n
        return n
    }

    /** Reads and drops what is left. */
    fun skipRest() {
        val scratch = ByteArray(8 * 1024)
        while (read(scratch, 0, scratch.size) >= 0) Unit
    }
}

/**
 * A streaming `multipart/form-data` reader: part bodies are copied through a fixed buffer, never held whole. The
 * first boundary is matched like the others by starting the buffer with a virtual CRLF. Pure (any InputStream).
 */
internal class MultipartReader(private val input: InputStream, boundary: String, bufferSize: Int = 64 * 1024) {
    private val delimiter = ("\r\n--$boundary").toByteArray(Charsets.ISO_8859_1)
    private val buf = ByteArray(maxOf(bufferSize, delimiter.size * 4))
    private var pos = 0
    private var lim = 2
    private var eof = false
    private var inBody = false
    private var done = false

    init {
        buf[0] = '\r'.code.toByte()
        buf[1] = '\n'.code.toByte()
    }

    /** Skips the preamble through the first boundary. False when there is none (or the body is empty). */
    fun start(): Boolean = try {
        copyUntilDelimiter(null, Long.MAX_VALUE)
        afterDelimiter()
        true
    } catch (e: TooShort) {
        false
    }

    /**
     * Headers of the next part (names lower-cased, values as sent), or null after the closing boundary. The unread
     * rest of the previous part is skipped.
     */
    fun nextPart(): Map<String, String>? {
        if (inBody) copyBody(null, Long.MAX_VALUE)
        if (done) return null
        val headers = LinkedHashMap<String, String>()
        var budget = MAX_PART_HEAD
        while (true) {
            val line = readLine(budget) ?: throw IOException("part headers ended early")
            budget -= line.size + 2
            if (line.isEmpty()) break
            if (headers.size >= MAX_PART_HEADERS) throw IOException("too many part headers")
            val text = LanHttp.decodeHeader(line)
            val colon = text.indexOf(':')
            if (colon <= 0) throw IOException("malformed part header")
            headers[text.substring(0, colon).trim().lowercase(Locale.ROOT)] = text.substring(colon + 1).trim()
        }
        inBody = true
        return headers
    }

    /**
     * Copies the current part's body to [out] (null = drop it) and moves past its boundary; returns its size.
     * Throws [LanExchange.TooLarge] as soon as the body exceeds [limit] bytes (nothing beyond it is written).
     */
    fun copyBody(out: OutputStream?, limit: Long): Long {
        check(inBody) { "no part" }
        val n = copyUntilDelimiter(out, limit)
        inBody = false
        afterDelimiter()
        return n
    }

    private class TooShort : IOException("multipart body ended before its boundary")

    private fun copyUntilDelimiter(out: OutputStream?, limit: Long): Long {
        var total = 0L
        val d = delimiter.size
        while (true) {
            if (lim - pos < d && !eof) {
                fill()
                continue
            }
            val at = indexOfDelimiter(pos, lim)
            if (at >= 0) {
                total = emit(out, pos, at - pos, total, limit)
                pos = at + d
                return total
            }
            if (eof) throw TooShort()
            // Everything but a tail that could still begin a delimiter.
            val safe = lim - pos - (d - 1)
            if (safe > 0) {
                total = emit(out, pos, safe, total, limit)
                pos += safe
            }
            fill()
        }
    }

    private fun emit(out: OutputStream?, from: Int, len: Int, total: Long, limit: Long): Long {
        if (len <= 0) return total
        if (total + len > limit) throw LanExchange.TooLarge()
        out?.write(buf, from, len)
        return total + len
    }

    /** After a delimiter: "--" ends the body; optional spaces/tabs then CRLF start the next part. */
    private fun afterDelimiter() {
        val a = readByte()
        val b = readByte()
        if (a == '-'.code && b == '-'.code) {
            done = true
            return
        }
        var c = a
        var next = b
        while (c == ' '.code || c == '\t'.code) {
            c = next
            next = readByte()
        }
        if (c != '\r'.code || next != '\n'.code) throw IOException("malformed boundary line")
    }

    private fun readByte(): Int {
        while (pos >= lim) {
            if (eof) throw IOException("multipart body ended early")
            fill()
        }
        return buf[pos++].toInt() and 0xff
    }

    /** One CRLF-terminated line of raw bytes (without the CRLF), or null over [budget] bytes. */
    private fun readLine(budget: Int): ByteArray? {
        val line = ByteArrayOutputStream(128)
        while (true) {
            val b = readByte()
            if (b == '\n'.code) {
                val bytes = line.toByteArray()
                return if (bytes.isNotEmpty() && bytes[bytes.size - 1] == '\r'.code.toByte()) {
                    bytes.copyOf(bytes.size - 1)
                } else {
                    bytes
                }
            }
            line.write(b)
            if (line.size() > budget) return null
        }
    }

    /** Moves the unread bytes to the front and reads once more; sets [eof] at the end of the stream. */
    private fun fill() {
        if (pos > 0) {
            System.arraycopy(buf, pos, buf, 0, lim - pos)
            lim -= pos
            pos = 0
        }
        if (lim == buf.size) return
        val n = input.read(buf, lim, buf.size - lim)
        if (n < 0) eof = true else lim += n
    }

    private fun indexOfDelimiter(from: Int, to: Int): Int {
        val d = delimiter
        val first = d[0]
        val last = to - d.size
        var i = from
        while (i <= last) {
            if (buf[i] == first) {
                var k = 1
                while (k < d.size && buf[i + k] == d[k]) k++
                if (k == d.size) return i
            }
            i++
        }
        return -1
    }

    private companion object {
        const val MAX_PART_HEAD = 8 * 1024
        const val MAX_PART_HEADERS = 16
    }
}

/** The Wi-Fi address to bind (pure choice over the interfaces' addresses, plus the platform enumeration). */
internal object LanNet {
    /** The site-local IPv4 address of a `wlan*` interface (wlan0 first), or null. */
    fun pick(addresses: List<Pair<String, InetAddress>>): Inet4Address? =
        addresses.asSequence()
            .filter { (name, a) -> name.startsWith("wlan") && a is Inet4Address && a.isSiteLocalAddress && !a.isLoopbackAddress }
            .sortedBy { it.first }
            .map { it.second as Inet4Address }
            .firstOrNull()

    /** The current Wi-Fi address of this device, or null (Wi-Fi off / not connected). */
    fun wifiAddress(): Inet4Address? {
        val list = ArrayList<Pair<String, InetAddress>>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (ni in interfaces) {
                try {
                    if (!ni.isUp || ni.isLoopback) continue
                } catch (e: SocketException) {
                    continue
                }
                for (a in ni.inetAddresses) list += ni.name to a
            }
        } catch (t: Throwable) {
            return null
        }
        return pick(list)
    }
}

/** The upload page served at `GET /<code>` (Korean, UTF-8, plain JS with XHR progress; no external resources). */
internal object LanPage {
    /**
     * The answer to a successful single-file upload, shown after the file name: [saved] is the stored name, which
     * differs from [asked] when that one was taken ("이름 (2).txt").
     */
    fun received(asked: String, saved: String): String =
        if (asked == saved) "받았습니다" else "받았습니다 · $saved 이름으로 저장했습니다"

    val HTML: String = """<!doctype html>
<html lang="ko"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<link rel="icon" href="data:,">
<title>리더플러스로 책 보내기</title>
<style>
body{font-family:sans-serif;max-width:560px;margin:24px auto;padding:0 16px;color:#000;background:#fff;line-height:1.5}
h1{font-size:22px}
#drop{border:2px dashed #000;padding:28px 12px;text-align:center;margin:16px 0}
#drop.on{background:#eee}
ul{padding-left:20px}
li{margin:6px 0;word-break:break-all}
.err{color:#b00020}
small{color:#555}
</style></head><body>
<h1>리더플러스로 책 보내기</h1>
<p>TXT·EPUB 파일을 고르거나 아래 상자에 끌어다 놓으세요.</p>
<div id="drop">여기에 파일을 끌어다 놓기<br><br><input type="file" id="f" multiple accept=".txt,.epub"></div>
<ul id="list"></ul>
<p><small>파일 하나에 200MB까지 보낼 수 있습니다. 다 받을 때까지 기기에서 ‘Wi-Fi로 책 받기’ 화면을 열어 두세요.</small></p>
<script>
var q=[],busy=0,L=document.getElementById('list'),M=${LanUpload.MAX_FILE_BYTES},
U=location.pathname.replace(/\/+${'$'}/,'')+'/upload';
function show(li,f,t,e){li.textContent=f.name+' · '+t;li.className=e?'err':'';}
function add(fs){for(var i=0;i<fs.length;i++){var li=document.createElement('li');L.appendChild(li);
show(li,fs[i],'기다리는 중');q.push([fs[i],li]);}next();}
function next(){if(busy||!q.length)return;var t=q.shift(),f=t[0],li=t[1];
if(!/\.(txt|epub)${'$'}/i.test(f.name)){show(li,f,'TXT·EPUB 파일만 보낼 수 있습니다',1);return next();}
if(f.size>M){show(li,f,'200MB보다 큰 파일은 보낼 수 없습니다',1);return next();}
busy=1;var x=new XMLHttpRequest(),d=new FormData();d.append('file',f,f.name);x.open('POST',U);
x.upload.onprogress=function(e){if(e.lengthComputable)show(li,f,Math.floor(e.loaded*100/e.total)+'%');};
x.onload=function(){show(li,f,x.responseText||('오류 '+x.status),x.status!=200);busy=0;next();};
x.onerror=function(){show(li,f,'보내지 못했습니다 (연결이 끊겼습니다)',1);busy=0;next();};
x.send(d);}
var I=document.getElementById('f');I.onchange=function(){add(I.files);I.value='';};
var D=document.getElementById('drop');
D.ondragover=function(e){e.preventDefault();D.className='on';};
D.ondragleave=function(){D.className='';};
D.ondrop=function(e){e.preventDefault();D.className='';add(e.dataTransfer.files);};
</script></body></html>
"""
}
