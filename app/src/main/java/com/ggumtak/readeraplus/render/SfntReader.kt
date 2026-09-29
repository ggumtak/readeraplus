package com.ggumtak.readeraplus.render

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.Charset

/** Random read access to font bytes (a file or an in-memory array). */
internal interface SfntSource {
    /** Total size in bytes. */
    val size: Long

    /** Reads exactly [len] bytes at [pos] into dst[off, off + len). Returns false when out of range or on IO error. */
    fun read(pos: Long, dst: ByteArray, off: Int, len: Int): Boolean
}

internal class ByteArraySfntSource(private val bytes: ByteArray) : SfntSource {
    override val size: Long get() = bytes.size.toLong()

    override fun read(pos: Long, dst: ByteArray, off: Int, len: Int): Boolean {
        if (pos < 0 || len < 0 || off < 0 || off + len > dst.size || pos + len > bytes.size) return false
        System.arraycopy(bytes, pos.toInt(), dst, off, len)
        return true
    }
}

internal class FileSfntSource(file: File) : SfntSource, Closeable {
    private val raf = RandomAccessFile(file, "r")
    override val size: Long = raf.length()

    override fun read(pos: Long, dst: ByteArray, off: Int, len: Int): Boolean {
        if (pos < 0 || len < 0 || off < 0 || off + len > dst.size || pos + len > size) return false
        return try {
            raf.seek(pos)
            raf.readFully(dst, off, len)
            true
        } catch (e: Exception) {
            false
        }
    }

    override fun close() {
        try {
            raf.close()
        } catch (ignored: Exception) {
        }
    }
}

/** What the reader needs to know about a font file (first face of a collection). */
internal class SfntInfo(
    /** Family name: nameID 16 (typographic family) or 1, Korean record preferred. */
    val family: String?,
    /** Matching subfamily (nameID 17 or 2), e.g. "Bold". */
    val style: String?,
    /** Has an 'fvar' table with a 'wght' axis. */
    val variable: Boolean,
    val wghtMin: Float = 0f,
    val wghtDefault: Float = 0f,
    val wghtMax: Float = 0f,
    /** OS/2 usWeightClass (0 when unknown). */
    val weightClass: Int = 0,
    /** OS/2 fsSelection italic bit. */
    val italic: Boolean = false,
    /** From OS/2 PANOSE: true = sans serif, false = serif, null = unknown. */
    val sansHint: Boolean? = null,
    /** Number of faces (1, or the TTC font count). */
    val faceCount: Int = 1,
) {
    /** Human-readable name: family plus the subfamily unless it is a regular style. Null when nameless. */
    val displayName: String?
        get() {
            val f = family ?: return null
            val s = style ?: return f
            if (FontFiles.isRegularStyleName(s)) return f
            if (f.endsWith(s, ignoreCase = true)) return f
            return "$f $s"
        }
}

/**
 * Minimal, defensive reader for TrueType/OpenType (sfnt) headers: table directory, `name`, `fvar`, `OS/2`.
 * Pure Kotlin, reads only the few tables it needs, never throws on malformed input (returns null instead).
 */
internal object SfntReader {
    const val TAG_TRUE_TYPE = 0x00010000L
    const val TAG_OTTO = 0x4F54544FL // 'OTTO'
    const val TAG_TRUE = 0x74727565L // 'true'
    const val TAG_TTCF = 0x74746366L // 'ttcf'

    private const val TAG_NAME = 0x6E616D65L // 'name'
    private const val TAG_FVAR = 0x66766172L // 'fvar'
    private const val TAG_OS2 = 0x4F532F32L // 'OS/2'
    private const val TAG_WGHT = 0x77676874L // 'wght'

    private const val MAX_TABLES = 1024
    private const val MAX_NAME_TABLE = 1 shl 20
    private const val MAX_FVAR_TABLE = 64 * 1024

    private const val LANG_KO_WIN = 0x0412
    private const val LANG_EN_WIN = 0x0409
    private const val LANG_KO_MAC = 23

    /** True when [b] starts with an sfnt signature we can load (TrueType, CFF OpenType, Apple 'true', TTC). */
    fun looksLikeSfnt(b: ByteArray, len: Int = b.size): Boolean {
        if (len < 4 || b.size < 4) return false
        val tag = u32(b, 0)
        return tag == TAG_TRUE_TYPE || tag == TAG_OTTO || tag == TAG_TRUE || tag == TAG_TTCF
    }

    /** Parses [file]; null if it is not a readable sfnt font. */
    fun parse(file: File): SfntInfo? = try {
        FileSfntSource(file).use { parse(it) }
    } catch (e: Exception) {
        null
    }

    /** Parses the first face of [src]; null if the data is not a usable sfnt font. Never throws. */
    fun parse(src: SfntSource): SfntInfo? = try {
        parseInternal(src)
    } catch (e: Exception) {
        null
    } catch (e: OutOfMemoryError) {
        null
    }

    private fun parseInternal(src: SfntSource): SfntInfo? {
        val hdr = ByteArray(12)
        if (!src.read(0, hdr, 0, 12)) return null
        var tag = u32(hdr, 0)
        var base = 0L
        var faces = 1
        if (tag == TAG_TTCF) {
            val n = u32(hdr, 8)
            if (n < 1 || n > 4096) return null
            faces = n.toInt()
            val off = ByteArray(4)
            if (!src.read(12, off, 0, 4)) return null
            base = u32(off, 0)
            if (!src.read(base, hdr, 0, 12)) return null
            tag = u32(hdr, 0)
        }
        if (tag != TAG_TRUE_TYPE && tag != TAG_OTTO && tag != TAG_TRUE) return null
        val numTables = u16(hdr, 4)
        if (numTables < 1 || numTables > MAX_TABLES) return null
        val dir = ByteArray(numTables * 16)
        if (!src.read(base + 12, dir, 0, dir.size)) return null

        var nameOff = -1L
        var nameLen = 0L
        var fvarOff = -1L
        var fvarLen = 0L
        var os2Off = -1L
        var os2Len = 0L
        for (i in 0 until numTables) {
            val r = i * 16
            val t = u32(dir, r)
            val o = u32(dir, r + 8)
            val l = u32(dir, r + 12)
            if (o + l > src.size) continue
            when (t) {
                TAG_NAME -> { nameOff = o; nameLen = l }
                TAG_FVAR -> { fvarOff = o; fvarLen = l }
                TAG_OS2 -> { os2Off = o; os2Len = l }
            }
        }

        var family: String? = null
        var style: String? = null
        if (nameOff >= 0 && nameLen >= 6) {
            val n = minOf(nameLen, MAX_NAME_TABLE.toLong()).toInt()
            val tbl = ByteArray(n)
            if (src.read(nameOff, tbl, 0, n)) {
                val names = NameTable(tbl)
                val fam16 = names.best(16)
                val fam1 = names.best(1)
                // Prefer the better language; within the same language prefer the typographic family (16).
                val useTypo = fam16 != null && (fam1 == null || fam16.score >= fam1.score)
                if (useTypo) {
                    family = fam16!!.text
                    style = (names.bestInLanguage(17, fam16.langScore) ?: names.best(17)
                        ?: names.bestInLanguage(2, fam16.langScore) ?: names.best(2))?.text
                } else if (fam1 != null) {
                    family = fam1.text
                    style = (names.bestInLanguage(2, fam1.langScore) ?: names.best(2))?.text
                }
            }
        }

        var variable = false
        var wMin = 0f
        var wDef = 0f
        var wMax = 0f
        if (fvarOff >= 0 && fvarLen >= 16) {
            val n = minOf(fvarLen, MAX_FVAR_TABLE.toLong()).toInt()
            val tbl = ByteArray(n)
            if (src.read(fvarOff, tbl, 0, n)) {
                val axesOffset = u16(tbl, 4)
                val axisCount = u16(tbl, 8)
                val axisSize = u16(tbl, 10)
                if (axisSize >= 20) {
                    for (a in 0 until axisCount) {
                        val p = axesOffset + a * axisSize
                        if (p + 20 > n) break
                        if (u32(tbl, p) == TAG_WGHT) {
                            wMin = fixed(tbl, p + 4)
                            wDef = fixed(tbl, p + 8)
                            wMax = fixed(tbl, p + 12)
                            variable = wMax > wMin
                            break
                        }
                    }
                }
            }
        }

        var weightClass = 0
        var italic = false
        var sans: Boolean? = null
        if (os2Off >= 0 && os2Len >= 6) {
            val n = minOf(os2Len, 128L).toInt()
            val tbl = ByteArray(n)
            if (src.read(os2Off, tbl, 0, n)) {
                weightClass = u16(tbl, 4)
                if (n >= 34) {
                    val familyType = tbl[32].toInt() and 0xFF
                    val serifStyle = tbl[33].toInt() and 0xFF
                    if (familyType == 2) {
                        sans = when (serifStyle) {
                            in 11..15 -> true
                            in 2..10 -> false
                            else -> null
                        }
                    }
                }
                if (n >= 64) italic = (u16(tbl, 62) and 1) != 0
            }
        }
        return SfntInfo(family, style, variable, wMin, wDef, wMax, weightClass, italic, sans, faces)
    }

    private class NameRec(val text: String, val score: Int, val langScore: Int)

    /** Decoded view over a `name` table: best record per nameID by language/platform preference. */
    private class NameTable(private val t: ByteArray) {
        private val count: Int
        private val stringBase: Int

        init {
            val c = u16(t, 2)
            count = minOf(c, (t.size - 6) / 12).coerceAtLeast(0)
            stringBase = u16(t, 4)
        }

        fun best(nameId: Int): NameRec? = find(nameId, -1)

        fun bestInLanguage(nameId: Int, langScore: Int): NameRec? = find(nameId, langScore)

        private fun find(nameId: Int, onlyLang: Int): NameRec? {
            var best: NameRec? = null
            for (i in 0 until count) {
                val r = 6 + i * 12
                if (u16(t, r + 6) != nameId) continue
                val platform = u16(t, r)
                val encoding = u16(t, r + 2)
                val language = u16(t, r + 4)
                val len = u16(t, r + 8)
                val off = stringBase + u16(t, r + 10)
                if (len == 0 || off + len > t.size) continue
                val lang = langScore(platform, language)
                if (onlyLang >= 0 && lang != onlyLang) continue
                val score = lang * 4 + platformScore(platform)
                if (best != null && score <= best.score) continue
                val s = decode(platform, encoding, off, len) ?: continue
                val clean = clean(s)
                if (clean.isEmpty()) continue
                best = NameRec(clean, score, lang)
            }
            return best
        }

        private fun decode(platform: Int, encoding: Int, off: Int, len: Int): String? = when (platform) {
            0 -> utf16be(off, len)
            3 -> if (encoding == 0 || encoding == 1 || encoding == 10) utf16be(off, len) else null
            1 -> when (encoding) {
                0 -> macRoman(off, len)
                3 -> koreanCharset()?.let { String(t, off, len, it) }
                else -> null
            }
            else -> null
        }

        private fun utf16be(off: Int, len: Int): String {
            val n = len / 2
            val ch = CharArray(n)
            for (i in 0 until n) ch[i] = (((t[off + 2 * i].toInt() and 0xFF) shl 8) or (t[off + 2 * i + 1].toInt() and 0xFF)).toChar()
            return String(ch)
        }

        /** ASCII subset of Mac Roman; other bytes map through Latin-1 (good enough for family names). */
        private fun macRoman(off: Int, len: Int): String {
            val ch = CharArray(len)
            for (i in 0 until len) ch[i] = (t[off + i].toInt() and 0xFF).toChar()
            return String(ch)
        }
    }

    private fun langScore(platform: Int, language: Int): Int = when (platform) {
        3 -> when (language) {
            LANG_KO_WIN -> 3
            LANG_EN_WIN -> 2
            else -> 1
        }
        1 -> when (language) {
            LANG_KO_MAC -> 3
            0 -> 2
            else -> 1
        }
        else -> 1
    }

    private fun platformScore(platform: Int): Int = when (platform) {
        3 -> 3
        0 -> 2
        1 -> 1
        else -> 0
    }

    private fun clean(s: String): String {
        var hasBad = false
        for (c in s) if (c < ' ' || c == '�') { hasBad = true; break }
        val r = if (!hasBad) s else buildString(s.length) { for (c in s) if (c >= ' ' && c != '�') append(c) }
        return r.trim()
    }

    @Volatile private var korean: Charset? = null
    @Volatile private var koreanLooked = false

    /** CP949 for Mac-Korean name records; never Charset.forName("CP949") (wrong table on Android ICU). */
    private fun koreanCharset(): Charset? {
        if (koreanLooked) return korean
        for (n in arrayOf("MS949", "x-windows-949", "windows-949", "EUC-KR")) {
            try {
                if (Charset.isSupported(n)) {
                    korean = Charset.forName(n)
                    break
                }
            } catch (ignored: Exception) {
            }
        }
        koreanLooked = true
        return korean
    }

    internal fun u16(b: ByteArray, p: Int): Int {
        if (p < 0 || p + 2 > b.size) return 0
        return ((b[p].toInt() and 0xFF) shl 8) or (b[p + 1].toInt() and 0xFF)
    }

    internal fun u32(b: ByteArray, p: Int): Long {
        if (p < 0 || p + 4 > b.size) return 0L
        return ((b[p].toLong() and 0xFF) shl 24) or ((b[p + 1].toLong() and 0xFF) shl 16) or
            ((b[p + 2].toLong() and 0xFF) shl 8) or (b[p + 3].toLong() and 0xFF)
    }

    /** 16.16 fixed point. */
    private fun fixed(b: ByteArray, p: Int): Float = u32(b, p).toInt() / 65536f
}
