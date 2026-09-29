package com.ggumtak.readeraplus.format.txt

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/*
 * Byte -> char decoding for TXT files.
 *
 * Every decoder here guarantees that a newline code unit (LF or CR) decodes to exactly one '\n' / '\r' char and
 * is never swallowed by a malformed sequence. Byte line starts and char line starts therefore correspond 1:1,
 * which is what makes the byte-range index cache (decode only one section's bytes later) exact.
 *
 * UTF-8, CP949 and UTF-16 are decoded by hand-written loops (fast on ART, deterministic malformed-input
 * handling, no intermediate String). Any other charset name a user forces goes through java.nio.
 */

/** Decodes bytes of one encoding into chars. Implementations are stateless and thread-safe. */
internal abstract class TxtDecoder(
    /** Canonical name, stored in the index and reported as the book's encoding. */
    val name: String,
) {
    /** Bytes per newline code unit: 1 for ASCII-compatible encodings, 2 for UTF-16. */
    open val unitSize: Int get() = 1

    /** Whether [advance] is supported (needed to cut overlong lines into byte-addressable segments). */
    open val canAdvance: Boolean get() = true

    /** Upper bound of the chars [decode] produces for [byteLen] bytes. */
    open fun maxChars(byteLen: Int): Int = byteLen

    /** Decodes bytes[from, to) into [out] starting at [outPos]; returns the number of chars written. */
    abstract fun decode(bytes: ByteArray, from: Int, to: Int, out: CharArray, outPos: Int): Int

    /**
     * Byte position reached after decoding exactly [chars] chars starting at [from] (never past [to]).
     * Returns -1 when unsupported ([canAdvance] false).
     */
    abstract fun advance(bytes: ByteArray, from: Int, to: Int, chars: Int): Int

    /** Position of the next newline code unit with value [nl] at or after [from] (unit aligned), or -1. */
    open fun nextNewline(bytes: ByteArray, from: Int, to: Int, nl: Int): Int {
        val b = nl.toByte()
        var i = from
        while (i < to) {
            if (bytes[i] == b) return i
            i++
        }
        return -1
    }
}

/** Hand-written UTF-8 decoder (WHATWG-style maximal-subpart replacement with U+FFFD). */
internal object Utf8Decoder : TxtDecoder("UTF-8") {
    /** Result of [decodeDetect] when the input does not look like UTF-8. */
    const val NOT_UTF8 = -1

    override fun decode(bytes: ByteArray, from: Int, to: Int, out: CharArray, outPos: Int): Int =
        run(bytes, from, to, out, outPos, detect = false, truncatedOk = true)

    /**
     * Errors needed before detection may give up early. A CP949 file reaches it within its first ~200 Korean
     * characters; a UTF-8 file that merely starts with a short CP949 / garbage header does not, and is then
     * judged by the whole-file ratio.
     */
    private const val EARLY_ERRORS = 256

    /**
     * Decodes while judging whether the input is UTF-8. Returns [NOT_UTF8] as soon as errors dominate
     * (a CP949 file fails within its first few hundred bytes of Korean) and at the end if more than ~1 in 10
     * multi-byte sequences was invalid. A sequence cut by [to] is tolerated when [truncatedOk]. [out] may be
     * null for validation only.
     */
    fun decodeDetect(bytes: ByteArray, from: Int, to: Int, out: CharArray?, outPos: Int, truncatedOk: Boolean): Int =
        run(bytes, from, to, out, outPos, detect = true, truncatedOk = truncatedOk)

    private fun run(
        bytes: ByteArray, from: Int, to: Int, out: CharArray?, outPos: Int,
        detect: Boolean, truncatedOk: Boolean,
    ): Int {
        var i = from
        var o = outPos
        var errors = 0
        var multi = 0
        while (i < to) {
            val b = bytes[i].toInt()
            if (b >= 0) {
                if (out != null) out[o] = b.toChar()
                o++
                i++
                continue
            }
            val b0 = b and 0xFF
            var need: Int
            var cp: Int
            var lo = 0x80
            var hi = 0xBF
            when {
                b0 < 0xC2 -> { need = -1; cp = 0 }
                b0 < 0xE0 -> { need = 1; cp = b0 and 0x1F }
                b0 < 0xF0 -> {
                    need = 2; cp = b0 and 0x0F
                    if (b0 == 0xE0) lo = 0xA0 else if (b0 == 0xED) hi = 0x9F
                }
                b0 < 0xF5 -> {
                    need = 3; cp = b0 and 0x07
                    if (b0 == 0xF0) lo = 0x90 else if (b0 == 0xF4) hi = 0x8F
                }
                else -> { need = -1; cp = 0 }
            }
            if (need < 0) {
                if (out != null) out[o] = '�'
                o++
                i++
                errors++
                if (detect && errors > EARLY_ERRORS && errors > multi) return NOT_UTF8
                continue
            }
            var j = i + 1
            var k = 0
            var ok = true
            while (k < need) {
                if (j >= to) { ok = false; break }
                val c = bytes[j].toInt() and 0xFF
                if (c < lo || c > hi) { ok = false; break }
                cp = (cp shl 6) or (c and 0x3F)
                lo = 0x80
                hi = 0xBF
                j++
                k++
            }
            if (!ok) {
                // maximal subpart [i, j) -> one U+FFFD; the failing byte (possibly a newline) is re-read.
                if (out != null) out[o] = '�'
                o++
                if (!(j >= to && truncatedOk)) {
                    errors++
                    if (detect && errors > EARLY_ERRORS && errors > multi) return NOT_UTF8
                }
                i = j
                continue
            }
            multi++
            if (cp >= 0x10000) {
                if (out != null) {
                    out[o] = (0xD7C0 + (cp shr 10)).toChar()
                    out[o + 1] = (0xDC00 or (cp and 0x3FF)).toChar()
                }
                o += 2
            } else {
                if (out != null) out[o] = cp.toChar()
                o++
            }
            i = j
        }
        if (detect && errors > 0 && errors * 10 > multi) return NOT_UTF8
        return o - outPos
    }

    override fun advance(bytes: ByteArray, from: Int, to: Int, chars: Int): Int {
        var i = from
        var n = 0
        while (i < to && n < chars) {
            val b0 = bytes[i].toInt() and 0xFF
            if (b0 < 0x80) { i++; n++; continue }
            var need: Int
            var lo = 0x80
            var hi = 0xBF
            when {
                b0 < 0xC2 -> need = -1
                b0 < 0xE0 -> need = 1
                b0 < 0xF0 -> { need = 2; if (b0 == 0xE0) lo = 0xA0 else if (b0 == 0xED) hi = 0x9F }
                b0 < 0xF5 -> { need = 3; if (b0 == 0xF0) lo = 0x90 else if (b0 == 0xF4) hi = 0x8F }
                else -> need = -1
            }
            if (need < 0) { i++; n++; continue }
            var j = i + 1
            var k = 0
            var ok = true
            while (k < need) {
                if (j >= to) { ok = false; break }
                val c = bytes[j].toInt() and 0xFF
                if (c < lo || c > hi) { ok = false; break }
                lo = 0x80
                hi = 0xBF
                j++
                k++
            }
            val produced = if (ok && need == 3) 2 else 1
            if (n + produced > chars) break
            n += produced
            i = j
        }
        return i
    }
}

/**
 * CP949 (MS949 / Unified Hangul Code, superset of EUC-KR) via a 64K lookup table built once from the platform
 * charset. Invalid pairs become U+FFFD; an ASCII trail byte is never consumed by an invalid lead.
 */
internal class Cp949Decoder(private val table: CharArray) : TxtDecoder("MS949") {
    override fun decode(bytes: ByteArray, from: Int, to: Int, out: CharArray, outPos: Int): Int {
        val t = table
        var i = from
        var o = outPos
        while (i < to) {
            val b = bytes[i].toInt()
            if (b >= 0) {
                out[o++] = b.toChar()
                i++
                continue
            }
            val b0 = b and 0xFF
            if (i + 1 < to) {
                val b1 = bytes[i + 1].toInt() and 0xFF
                val c = t[(b0 shl 8) or b1]
                if (c != NO_CHAR) {
                    out[o++] = c
                    i += 2
                    continue
                }
                out[o++] = '�'
                i += if (b0 in 0x81..0xFE && b1 in 0x81..0xFE) 2 else 1
            } else {
                out[o++] = '�'
                i++
            }
        }
        return o - outPos
    }

    override fun advance(bytes: ByteArray, from: Int, to: Int, chars: Int): Int {
        val t = table
        var i = from
        var n = 0
        while (i < to && n < chars) {
            val b0 = bytes[i].toInt() and 0xFF
            if (b0 < 0x80 || i + 1 >= to) { i++; n++; continue }
            val b1 = bytes[i + 1].toInt() and 0xFF
            i += if (t[(b0 shl 8) or b1] != NO_CHAR || (b0 in 0x81..0xFE && b1 in 0x81..0xFE)) 2 else 1
            n++
        }
        return i
    }

    companion object {
        const val NO_CHAR = '￿'
    }
}

/** UTF-16 with a fixed byte order. Unpaired surrogates and a dangling odd byte become U+FFFD. */
internal class Utf16Decoder(private val bigEndian: Boolean) : TxtDecoder(if (bigEndian) "UTF-16BE" else "UTF-16LE") {
    override val unitSize: Int get() = 2

    override fun maxChars(byteLen: Int): Int = (byteLen + 1) / 2

    private fun unit(bytes: ByteArray, i: Int): Int {
        val a = bytes[i].toInt() and 0xFF
        val b = bytes[i + 1].toInt() and 0xFF
        return if (bigEndian) (a shl 8) or b else (b shl 8) or a
    }

    override fun decode(bytes: ByteArray, from: Int, to: Int, out: CharArray, outPos: Int): Int {
        var i = from
        var o = outPos
        while (i + 1 < to) {
            val u = unit(bytes, i)
            i += 2
            if (u in 0xD800..0xDFFF) {
                if (u <= 0xDBFF && i + 1 < to) {
                    val u2 = unit(bytes, i)
                    if (u2 in 0xDC00..0xDFFF) {
                        out[o++] = u.toChar()
                        out[o++] = u2.toChar()
                        i += 2
                        continue
                    }
                }
                out[o++] = '�'
                continue
            }
            out[o++] = u.toChar()
        }
        if (i < to) out[o++] = '�'
        return o - outPos
    }

    override fun advance(bytes: ByteArray, from: Int, to: Int, chars: Int): Int {
        var i = from
        var n = 0
        while (i + 1 < to && n < chars) {
            val u = unit(bytes, i)
            if (u in 0xD800..0xDBFF && i + 3 < to && unit(bytes, i + 2) in 0xDC00..0xDFFF) {
                if (n + 2 > chars) break
                i += 4
                n += 2
            } else {
                i += 2
                n++
            }
        }
        if (n < chars && i < to) i = to
        return i
    }

    override fun nextNewline(bytes: ByteArray, from: Int, to: Int, nl: Int): Int {
        val lo = nl.toByte()
        val zero: Byte = 0
        var i = from
        if (bigEndian) {
            while (i + 1 < to) {
                if (bytes[i + 1] == lo && bytes[i] == zero) return i
                i += 2
            }
        } else {
            while (i + 1 < to) {
                if (bytes[i] == lo && bytes[i + 1] == zero) return i
                i += 2
            }
        }
        return -1
    }
}

/** Any other ASCII-compatible charset through java.nio (only reachable by forcing an unusual encoding). */
internal class JavaCharsetDecoder(private val charset: Charset) : TxtDecoder(charset.name()) {
    override val canAdvance: Boolean get() = false

    override fun maxChars(byteLen: Int): Int {
        val per = try { charset.newDecoder().maxCharsPerByte() } catch (_: Exception) { 2f }
        val v = byteLen.toDouble() * per + 16
        return if (v > Int.MAX_VALUE - 64) Int.MAX_VALUE - 64 else v.toInt()
    }

    override fun decode(bytes: ByteArray, from: Int, to: Int, out: CharArray, outPos: Int): Int {
        val dec = charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
        val cb = CharBuffer.wrap(out, outPos, out.size - outPos)
        dec.decode(ByteBuffer.wrap(bytes, from, to - from), cb, true)
        dec.flush(cb)
        return cb.position() - outPos
    }

    override fun advance(bytes: ByteArray, from: Int, to: Int, chars: Int): Int = -1
}

/** A detected (or forced) decoder plus the number of BOM bytes to skip. */
internal class DetectedEncoding(val decoder: TxtDecoder, val bomLength: Int)

/** Encoding resolution and sniffing. */
internal object TxtCharsets {
    val UTF16LE: TxtDecoder = Utf16Decoder(bigEndian = false)
    val UTF16BE: TxtDecoder = Utf16Decoder(bigEndian = true)

    /** The platform CP949 charset: first supported of MS949, x-windows-949, windows-949, then EUC-KR. */
    val cp949Charset: Charset? by lazy {
        for (n in arrayOf("MS949", "x-windows-949", "windows-949", "EUC-KR")) {
            try {
                if (Charset.isSupported(n)) return@lazy Charset.forName(n)
            } catch (_: Exception) {
            }
        }
        null
    }

    /** CP949 decoder (table built on first use, ~24K pair decodes). Falls back to UTF-8 if no charset exists. */
    val cp949: TxtDecoder by lazy {
        val cs = cp949Charset
        val table = if (cs != null) buildCp949Table(cs) else null
        if (table != null) Cp949Decoder(table) else Utf8Decoder
    }

    private fun buildCp949Table(cs: Charset): CharArray? = try {
        val table = CharArray(65536) { Cp949Decoder.NO_CHAR }
        val pairs = (0xFE - 0x81 + 1) * (0xFE - 0x41 + 1)
        val src = ByteArray(pairs * 3)
        var k = 0
        for (lead in 0x81..0xFE) for (trail in 0x41..0xFE) {
            src[k++] = lead.toByte()
            src[k++] = trail.toByte()
            src[k++] = 0x0A
        }
        val dec = cs.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
        val cb = dec.decode(ByteBuffer.wrap(src))
        val chars = CharArray(cb.remaining())
        cb.get(chars)
        // Each pair is followed by LF; a valid pair yields exactly one char before the LF.
        var idx = 0
        var segStart = 0
        var ok = true
        for (p in chars.indices) {
            if (chars[p] != '\n') continue
            if (idx >= pairs) { ok = false; break }
            if (p - segStart == 1) {
                val c = chars[segStart]
                if (c != '�' && c != '\u001A' && c != Cp949Decoder.NO_CHAR && c >= '\u0080') {
                    val lead = 0x81 + idx / 190
                    val trail = 0x41 + idx % 190
                    table[(lead shl 8) or trail] = c
                }
            }
            idx++
            segStart = p + 1
        }
        if (!ok || idx != pairs) buildCp949TableSlow(cs) else table
    } catch (_: Exception) {
        null
    }

    /** Fallback: decode every pair on its own (only if the platform decoder resynchronised unexpectedly). */
    private fun buildCp949TableSlow(cs: Charset): CharArray {
        val table = CharArray(65536) { Cp949Decoder.NO_CHAR }
        val dec = cs.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val inBuf = ByteArray(2)
        val out = CharBuffer.allocate(4)
        for (lead in 0x81..0xFE) for (trail in 0x41..0xFE) {
            inBuf[0] = lead.toByte()
            inBuf[1] = trail.toByte()
            dec.reset()
            out.clear()
            val r = dec.decode(ByteBuffer.wrap(inBuf), out, true)
            if (r.isError) continue
            dec.flush(out)
            if (out.position() == 1) {
                val c = out.get(0)
                if (c != '�' && c >= '\u0080') table[(lead shl 8) or trail] = c
            }
        }
        return table
    }

    /**
     * Decoder for a user-chosen encoding name, or null if unknown. Korean names (EUC-KR, CP949, …) all map to
     * the CP949 decoder: it is a strict superset, so EUC-KR text decodes identically.
     */
    fun forName(name: String): TxtDecoder? {
        val n = name.trim()
        if (n.isEmpty()) return null
        when (n.uppercase().replace('_', '-')) {
            "UTF-8", "UTF8" -> return Utf8Decoder
            "MS949", "CP949", "X-WINDOWS-949", "WINDOWS-949", "EUC-KR", "EUCKR", "KS-C-5601-1987", "KSC5601",
            "KS-C-5601", "UHC", "X-IBM949", "IBM949" -> return cp949
            "UTF-16LE", "UTF16LE", "UTF-16-LE" -> return UTF16LE
            "UTF-16BE", "UTF16BE", "UTF-16-BE" -> return UTF16BE
        }
        return try {
            if (!Charset.isSupported(n)) return null
            val cs = Charset.forName(n)
            // Only ASCII-compatible charsets keep the byte-level newline guarantee.
            val nlBytes = "\n".toByteArray(cs)
            val aBytes = "A".toByteArray(cs)
            if (nlBytes.size == 1 && nlBytes[0].toInt() == 0x0A && aBytes.size == 1 && aBytes[0].toInt() == 0x41) {
                JavaCharsetDecoder(cs)
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    /** A byte order mark at [from]: UTF-8 (3 bytes), UTF-16LE/BE (2 bytes). */
    fun bom(bytes: ByteArray, from: Int, to: Int): DetectedEncoding? {
        val n = to - from
        if (n >= 3 && bytes[from] == 0xEF.toByte() && bytes[from + 1] == 0xBB.toByte() && bytes[from + 2] == 0xBF.toByte()) {
            return DetectedEncoding(Utf8Decoder, 3)
        }
        if (n >= 2) {
            val a = bytes[from].toInt() and 0xFF
            val b = bytes[from + 1].toInt() and 0xFF
            if (a == 0xFF && b == 0xFE) return DetectedEncoding(UTF16LE, 2)
            if (a == 0xFE && b == 0xFF) return DetectedEncoding(UTF16BE, 2)
        }
        return null
    }

    /**
     * UTF-16 without BOM: in the first 4 KB, NUL bytes concentrate on one parity (the high byte of ASCII
     * characters). Real UTF-8 / CP949 text contains no NULs at all.
     */
    fun utf16WithoutBom(bytes: ByteArray, from: Int, to: Int): TxtDecoder? {
        val end = minOf(to, from + 4096)
        val pairs = (end - from) / 2
        if (pairs < 4) return null
        var zeroEven = 0
        var zeroOdd = 0
        var i = from
        while (i + 1 < end) {
            if (bytes[i].toInt() == 0) zeroEven++
            if (bytes[i + 1].toInt() == 0) zeroOdd++
            i += 2
        }
        // '가' (U+AC00) has a NUL low byte, so the other parity is allowed a few NULs too.
        if (zeroOdd * 20 >= pairs && zeroEven * 4 <= zeroOdd) return UTF16LE
        if (zeroEven * 20 >= pairs && zeroOdd * 4 <= zeroEven) return UTF16BE
        return null
    }

    /**
     * Resolves a forced encoding name for data at [from]: returns the decoder and how many BOM bytes to skip
     * (a BOM is skipped when it belongs to the same encoding family), or null if the name is unknown.
     */
    fun forced(name: String, bytes: ByteArray, from: Int, to: Int): DetectedEncoding? {
        val upper = name.trim().uppercase()
        val b = bom(bytes, from, to)
        if (upper == "UTF-16" || upper == "UTF16") {
            if (b != null && b.decoder.unitSize == 2) return b
            return DetectedEncoding(utf16WithoutBom(bytes, from, to) ?: UTF16BE, 0)
        }
        val dec = forName(name) ?: return null
        val skip = if (b != null && b.decoder === dec) b.bomLength else 0
        return DetectedEncoding(dec, skip)
    }

    /**
     * Full sniff of a sample (preview): BOM, BOM-less UTF-16, UTF-8 validation (a sequence cut at [to] is
     * tolerated), else CP949.
     */
    fun sniff(bytes: ByteArray, from: Int, to: Int): DetectedEncoding {
        bom(bytes, from, to)?.let { return it }
        utf16WithoutBom(bytes, from, to)?.let { return DetectedEncoding(it, 0) }
        val r = Utf8Decoder.decodeDetect(bytes, from, to, null, 0, truncatedOk = true)
        return if (r != Utf8Decoder.NOT_UTF8) DetectedEncoding(Utf8Decoder, 0) else DetectedEncoding(cp949, 0)
    }

    /** Like [sniff] but returns only the name, without building the CP949 table (library scanning). */
    fun sniffName(bytes: ByteArray, from: Int, to: Int): String {
        bom(bytes, from, to)?.let { return it.decoder.name }
        utf16WithoutBom(bytes, from, to)?.let { return it.name }
        val r = Utf8Decoder.decodeDetect(bytes, from, to, null, 0, truncatedOk = true)
        return if (r != Utf8Decoder.NOT_UTF8) Utf8Decoder.name else CP949_NAME
    }

    /** Name reported for CP949 text (matches TxtDocuments.ENCODINGS). */
    const val CP949_NAME = "MS949"
}
