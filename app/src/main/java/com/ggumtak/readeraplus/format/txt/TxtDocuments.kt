package com.ggumtak.readeraplus.format.txt

import com.ggumtak.readeraplus.format.BookDocument
import com.ggumtak.readeraplus.format.DocMeta
import com.ggumtak.readeraplus.format.DocumentException
import com.ggumtak.readeraplus.format.ParseOptions
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * TXT entry point. The first open of a file decodes it completely (off the main thread), detects chapters and
 * stores a byte-range index under `Documents.cacheDir/txtindex/`; later opens read only that index and each
 * section is decoded from its own byte range on demand.
 */
object TxtDocuments {
    /** Larger files are refused instead of risking an out-of-memory crash. */
    private const val MAX_FILE_BYTES = 128L * 1024 * 1024

    /**
     * Error reasons, shown under "책을 열 수 없습니다" and in toasts: the reason only (no file name, no period). The
     * size limit is [MAX_FILE_BYTES].
     */
    private val TOO_LARGE = "파일이 너무 큽니다 (${MAX_FILE_BYTES / (1024 * 1024)}MB까지)"
    internal const val READ_FAILED = "파일을 읽지 못했습니다"
    private const val SNIFF_BYTES = 64 * 1024

    /**
     * Version of what [open] makes of the same file and options (section split, char offsets). It changes exactly when
     * the TXT index does (`TxtIndexStore.VERSION`, at most once per release; 4 = author notes stay in their episode,
     * A5). Saved (section, offset) coordinates belong to one version, so the reader's text signature
     * (`LayoutKeys.textSignature`) should include it.
     */
    internal const val PARSE_VERSION = TxtIndexStore.VERSION

    /** Paths [open] is parsing in full right now, with the number of such opens (two may overlap). */
    private val building = ConcurrentHashMap<String, Int>()

    /**
     * Opens [file] (blocking: IO + parsing; never on the main thread).
     * @throws DocumentException when the file can't be read or is too large.
     */
    fun open(file: File, options: ParseOptions): BookDocument {
        if (!file.isFile) throw DocumentException("파일을 찾을 수 없습니다")
        val length = file.length()
        if (length > MAX_FILE_BYTES) throw DocumentException(TOO_LARGE)
        val key = TxtIndexStore.key(file, options)
        val cached = TxtIndexStore.load(key, length)
        if (cached != null) return TxtBook(file, cached, options)
        val path = file.path
        building.merge(path, 1) { a, b -> a + b }
        val index = try {
            val bytes = readAll(file, length)
            val parsed = TxtParser.parse(bytes, bytes.size, options)
            val idx = parsed.toIndex(key)
            if (bytes.size.toLong() == length && file.length() == length) TxtIndexStore.save(idx)
            idx
        } catch (e: IOException) {
            throw DocumentException(READ_FAILED, e)
        } catch (e: OutOfMemoryError) {
            throw DocumentException("메모리가 부족합니다", e)
        } catch (e: RuntimeException) {
            // never expected; keeps the "throws DocumentException" contract for callers that only catch that
            throw DocumentException(READ_FAILED, e)
        } finally {
            building.computeIfPresent(path) { _, n -> if (n > 1) n - 1 else null }
        }
        return TxtBook(file, index, options, parsed = true)
    }

    /**
     * R2 (A5): true while [open] is parsing [path] in full because it has no usable index (a first open, or the first
     * open after a `TxtIndexStore.VERSION` bump or an option change). The reader's delayed loading text (shown after
     * 300 ms) then reads "목차를 만드는 중…" instead of "불러오는 중…" for files over 4 MB. Reading it costs nothing on
     * the open path ([open] marks the path in a concurrent map only when it has to parse). [path] as given to [open]
     * (`File(path)`). Any thread. Owner: FORMAT.
     */
    fun isBuildingIndex(path: String): Boolean =
        building.isNotEmpty() && (building.containsKey(path) || building.containsKey(File(path).path))

    /** Title from the file name; encoding sniffed from the first 64 KB. Never throws for unreadable files. */
    fun readMeta(file: File): DocMeta {
        val title = file.nameWithoutExtension.ifEmpty { file.name }
        val encoding = try {
            val head = readHead(file, SNIFF_BYTES)
            TxtCharsets.sniffName(head.bytes, 0, head.length)
        } catch (_: Exception) {
            null
        }
        return DocMeta(title = title, encoding = encoding)
    }

    /**
     * First paragraphs of the file for thumbnails: decodes only the beginning (<= 64 KB), normalises like
     * open() and returns at most [maxChars] chars, paragraphs separated by '\n'.
     */
    fun preview(file: File, maxChars: Int = 1500, encoding: String = ""): String {
        if (maxChars <= 0) return ""
        return try {
            val head = readHead(file, SNIFF_BYTES)
            previewOf(head.bytes, head.length, head.eof, maxChars, encoding)
        } catch (_: Exception) {
            ""
        } catch (_: OutOfMemoryError) {
            ""
        }
    }

    /** Encodings offered in "인코딩 변경" (Java charset names). */
    val ENCODINGS: List<String> = listOf("UTF-8", "MS949", "EUC-KR", "UTF-16LE", "UTF-16BE")

    // ---------------------------------------------------------------- internals

    internal fun previewOf(bytes: ByteArray, len: Int, eof: Boolean, maxChars: Int, encoding: String): String {
        val det = (if (encoding.isNotBlank()) TxtCharsets.forced(encoding, bytes, 0, len) else null)
            ?: TxtCharsets.sniff(bytes, 0, len)
        val dec = det.decoder
        val from = minOf(det.bomLength, len)
        val nl = TxtParser.detectNewline(dec, bytes, from, len)
        var end = len
        if (!eof) {
            val cut = TxtParser.lastLineEnd(dec, bytes, from, len, nl)
            if (cut > from) end = cut
        }
        val decoded = TxtParser.decodeRange(dec, bytes, from, end, nl)
        val defaults = ParseOptions()
        val lines = LineTable.build(
            decoded.chars, decoded.length, nl.toChar(),
            LineConfig(defaults.txtStripIndent, null, segment = dec.canAdvance), null,
        )
        val d = TxtParser.decide(lines, defaults)
        val paras = ParaList(lines.count / 2 + 8)
        TxtParagraphs.walk(lines, 0, lines.count, d, paras)
        val idx = IntArray(paras.n)
        val n = TxtParagraphs.select(lines, paras, 0, paras.n, null, idx)
        val text = TxtParagraphs.build(lines, paras, idx, n, false).text
        return if (text.length <= maxChars) text else text.substring(0, safeCut(text, maxChars))
    }

    private fun safeCut(s: String, at: Int): Int =
        if (at > 0 && at < s.length && Character.isLowSurrogate(s[at]) && Character.isHighSurrogate(s[at - 1])) at - 1 else at

    internal class Head(val bytes: ByteArray, val length: Int, val eof: Boolean)

    internal fun readHead(file: File, max: Int): Head {
        FileInputStream(file).use { inp ->
            val buf = ByteArray(max)
            var n = 0
            while (n < max) {
                val r = inp.read(buf, n, max - n)
                if (r < 0) return Head(buf, n, true)
                n += r
            }
            return Head(buf, n, inp.read() < 0)
        }
    }

    /** Reads the whole file (tolerates the file growing or shrinking while being read). */
    private fun readAll(file: File, expected: Long): ByteArray {
        FileInputStream(file).use { inp ->
            var buf = ByteArray(expected.toInt().coerceAtLeast(0))
            var n = 0
            while (true) {
                if (n == buf.size) {
                    val b = inp.read()
                    if (b < 0) break
                    if (buf.size >= MAX_FILE_BYTES) throw DocumentException(TOO_LARGE)
                    buf = buf.copyOf(maxOf(4096, buf.size + (buf.size shr 1)))
                    buf[n++] = b.toByte()
                    continue
                }
                val r = inp.read(buf, n, buf.size - n)
                if (r < 0) break
                n += r
            }
            return if (n == buf.size) buf else buf.copyOf(n)
        }
    }
}
