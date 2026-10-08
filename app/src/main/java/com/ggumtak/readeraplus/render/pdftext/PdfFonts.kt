package com.ggumtak.readeraplus.render.pdftext

import java.nio.charset.Charset

/**
 * A parsed CMap: ToUnicode data (bfchar/bfrange) and/or an encoding CMap (codespace, cidrange/cidchar).
 * Codes are the big-endian integer value of their bytes.
 */
internal class CMap {
    var wmode = 0
    var useCMap: String? = null

    private val cs = IntList()
    private val cidLo = IntList()
    private val cidHi = IntList()
    private val cidVal = IntList()
    private val bfLo = IntList()
    private val bfHi = IntList()
    private val bfDst = ArrayList<String>()
    private val bfChars = IntObjMap<String>()
    private var sortedCid = false
    private var sortedBf = false

    val codespaceCount: Int get() = cs.size / 3
    val hasCid: Boolean get() = cidLo.size > 0
    val hasUnicode: Boolean get() = bfChars.size > 0 || bfLo.size > 0

    fun parse(data: ByteArray) {
        val lx = PdfLexer(ArraySrc(data), 0, data.size, null)
        var lastName: String? = null
        var nameBeforeNum: String? = null
        var lastNum = 0
        var guard = 0
        while (guard++ < 5_000_000) {
            when (lx.next()) {
                T_EOF -> break
                T_NAME -> lastName = lx.nameVal
                T_NUM -> {
                    nameBeforeNum = lastName
                    lastNum = lx.numVal.toInt()
                }
                T_KW -> when (lx.kwString()) {
                    "begincodespacerange" -> readCodespace(lx)
                    "begincidrange" -> readCidRange(lx)
                    "begincidchar" -> readCidChar(lx)
                    "beginbfchar" -> readBfChar(lx)
                    "beginbfrange" -> readBfRange(lx)
                    "usecmap" -> useCMap = lastName
                    "def" -> if (nameBeforeNum == "WMode") wmode = lastNum
                    else -> {}
                }
                else -> {}
            }
        }
        sortCid()
        sortBf()
    }

    private fun value(b: ByteArray): Int {
        var v = 0
        for (i in 0 until minOf(4, b.size)) v = (v shl 8) or (b[i].toInt() and 0xFF)
        return v
    }

    private fun readCodespace(lx: PdfLexer) {
        while (true) {
            if (lx.next() != T_STR) return
            val lo = lx.strVal
            if (lx.next() != T_STR) return
            val hi = lx.strVal
            if (lo.isEmpty() || lo.size > 4) continue
            cs.add(value(lo))
            cs.add(value(hi))
            cs.add(lo.size)
        }
    }

    private fun readCidRange(lx: PdfLexer) {
        while (true) {
            if (lx.next() != T_STR) return
            val lo = value(lx.strVal)
            if (lx.next() != T_STR) return
            val hi = value(lx.strVal)
            if (lx.next() != T_NUM) return
            cidLo.add(lo)
            cidHi.add(hi)
            cidVal.add(lx.numVal.toInt())
        }
    }

    private fun readCidChar(lx: PdfLexer) {
        while (true) {
            if (lx.next() != T_STR) return
            val c = value(lx.strVal)
            if (lx.next() != T_NUM) return
            cidLo.add(c)
            cidHi.add(c)
            cidVal.add(lx.numVal.toInt())
        }
    }

    private fun utf16(b: ByteArray): String {
        if (b.size == 1) return String(charArrayOf((b[0].toInt() and 0xFF).toChar()))
        val n = b.size / 2
        val ch = CharArray(n)
        for (i in 0 until n) ch[i] = (((b[2 * i].toInt() and 0xFF) shl 8) or (b[2 * i + 1].toInt() and 0xFF)).toChar()
        return String(ch)
    }

    private fun readBfChar(lx: PdfLexer) {
        while (true) {
            if (lx.next() != T_STR) return
            val c = value(lx.strVal)
            val t = lx.next()
            if (t == T_STR) bfChars[c] = utf16(lx.strVal) else if (t != T_NAME) return
        }
    }

    private fun readBfRange(lx: PdfLexer) {
        while (true) {
            if (lx.next() != T_STR) return
            val lo = value(lx.strVal)
            if (lx.next() != T_STR) return
            val hi = value(lx.strVal)
            when (lx.next()) {
                T_STR -> if (hi >= lo) {
                    bfLo.add(lo)
                    bfHi.add(hi)
                    bfDst.add(utf16(lx.strVal))
                }
                T_ARR_OPEN -> {
                    var c = lo
                    while (true) {
                        val t = lx.next()
                        if (t == T_ARR_CLOSE || t == T_EOF) break
                        if (t == T_STR && c <= hi && c - lo < 65536) bfChars[c++] = utf16(lx.strVal)
                    }
                }
                else -> return
            }
        }
    }

    private fun sortCid() {
        val n = cidLo.size
        if (n < 2) return
        val order = sortOrder(cidLo.a, n)
        val lo = IntArray(n)
        val hi = IntArray(n)
        val v = IntArray(n)
        for (i in 0 until n) {
            lo[i] = cidLo.a[order[i]]
            hi[i] = cidHi.a[order[i]]
            v[i] = cidVal.a[order[i]]
        }
        cidLo.a = lo
        cidHi.a = hi
        cidVal.a = v
        sortedCid = true
    }

    private fun sortBf() {
        val n = bfLo.size
        if (n < 2) return
        val order = sortOrder(bfLo.a, n)
        val lo = IntArray(n)
        val hi = IntArray(n)
        val d = ArrayList<String>(n)
        for (i in 0 until n) {
            lo[i] = bfLo.a[order[i]]
            hi[i] = bfHi.a[order[i]]
            d.add(bfDst[order[i]])
        }
        bfLo.a = lo
        bfHi.a = hi
        bfDst.clear()
        bfDst.addAll(d)
        sortedBf = true
    }

    private fun sortOrder(keys: IntArray, n: Int): IntArray {
        val packed = LongArray(n) { (keys[it].toLong() shl 32) or it.toLong() }
        packed.sort()
        return IntArray(n) { (packed[it] and 0xFFFFFFFFL).toInt() }
    }

    /** Index of the range containing [code] in the sorted (lo, hi) arrays, or -1. */
    private fun findRange(lo: IntArray, hi: IntArray, n: Int, code: Int): Int {
        var a = 0
        var b = n - 1
        var idx = -1
        while (a <= b) {
            val mid = (a + b) ushr 1
            if (lo[mid] <= code) {
                idx = mid
                a = mid + 1
            } else {
                b = mid - 1
            }
        }
        var steps = 0
        while (idx >= 0 && steps < 8) {
            if (hi[idx] >= code) return idx
            idx--
            steps++
        }
        return -1
    }

    /** CID for [code] or -1. */
    fun cid(code: Int): Int {
        val n = cidLo.size
        if (n == 0) return -1
        val i = findRange(cidLo.a, cidHi.a, n, code)
        return if (i < 0) -1 else cidVal.a[i] + (code - cidLo.a[i])
    }

    /** Unicode text for [code] from bfchar/bfrange, or null. */
    fun unicode(code: Int): String? {
        bfChars[code]?.let { return it }
        val n = bfLo.size
        if (n == 0) return null
        val i = findRange(bfLo.a, bfHi.a, n, code)
        if (i < 0) return null
        val d = bfDst[i]
        val off = code - bfLo.a[i]
        if (off == 0 || d.isEmpty()) return d
        val last = d[d.length - 1].code + off
        if (last > 0xFFFF) return null
        return d.substring(0, d.length - 1) + last.toChar()
    }

    /**
     * Length in bytes of the code starting at [i] by the codespace ranges (0 when no range matches).
     * Per spec each byte must lie within the corresponding bytes of lo..hi.
     */
    fun matchCodeLen(b: ByteArray, i: Int, n: Int): Int {
        var k = 0
        var minLen = 5
        while (k < cs.size) {
            val len = cs.a[k + 2]
            if (len < minLen) minLen = len
            if (i + len <= n) {
                val lo = cs.a[k]
                val hi = cs.a[k + 1]
                var ok = true
                for (j in 0 until len) {
                    val sh = 8 * (len - 1 - j)
                    val x = b[i + j].toInt() and 0xFF
                    if (x < ((lo ushr sh) and 0xFF) || x > ((hi ushr sh) and 0xFF)) {
                        ok = false
                        break
                    }
                }
                if (ok) return len
            }
            k += 3
        }
        return if (minLen == 5) 0 else -minLen
    }
}

/** A font as the text extractor needs it: code reading, advance widths (em) and Unicode. */
internal abstract class PdfFont {
    @JvmField var ascent = DEFAULT_ASCENT
    @JvmField var descent = DEFAULT_DESCENT
    @JvmField var vertical = false

    /** Length in bytes of the code returned by the last [readCode]. */
    @JvmField var codeLen = 1

    abstract fun readCode(b: ByteArray, i: Int, n: Int): Int

    /** Horizontal advance of [code] in text-space units per unit of font size (i.e. width/1000). */
    abstract fun width(code: Int): Float

    /** Unicode text of [code]; null when the font gives none. */
    abstract fun unicode(code: Int): String?

    companion object {
        const val DEFAULT_ASCENT = 0.8f
        const val DEFAULT_DESCENT = -0.2f
    }
}

internal class SimpleFont(private val widths: FloatArray, private val uni: Array<String?>) : PdfFont() {
    override fun readCode(b: ByteArray, i: Int, n: Int): Int {
        codeLen = 1
        return b[i].toInt() and 0xFF
    }

    override fun width(code: Int): Float = widths[code and 0xFF]
    override fun unicode(code: Int): String? = uni[code and 0xFF]
}

/** Composite (Type0) font. */
internal class Type0Font(
    private val mode: Int,
    private val encCMap: CMap?,
    private val toUni: CMap?,
    private val charset: Charset?,
    private val lead: Int,
    private val dw: Float,
    private val wSingle: IntFloatMap,
    private val wRangeLo: IntArray,
    private val wRangeHi: IntArray,
    private val wRangeVal: FloatArray,
) : PdfFont() {
    private val uniCache = IntObjMap<String>()

    override fun readCode(b: ByteArray, i: Int, n: Int): Int {
        when (mode) {
            MODE_CMAP -> {
                val cm = encCMap!!
                var len = cm.matchCodeLen(b, i, n)
                if (len == 0) len = 2
                if (len < 0) len = -len
                len = minOf(len, n - i)
                codeLen = len
                var v = 0
                for (k in 0 until len) v = (v shl 8) or (b[i + k].toInt() and 0xFF)
                return v
            }
            MODE_CHARSET -> {
                val c = b[i].toInt() and 0xFF
                if (i + 1 < n && isLead(c)) {
                    val l = if (lead == LEAD_EUCJP && c == 0x8F && i + 2 < n) 3 else 2
                    codeLen = l
                    var v = 0
                    for (k in 0 until l) v = (v shl 8) or (b[i + k].toInt() and 0xFF)
                    return v
                }
                codeLen = 1
                return c
            }
            else -> {
                if (i + 1 >= n) {
                    codeLen = 1
                    return b[i].toInt() and 0xFF
                }
                val hi = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
                if (mode == MODE_UTF16 && hi in 0xD800..0xDBFF && i + 3 < n) {
                    val lo = ((b[i + 2].toInt() and 0xFF) shl 8) or (b[i + 3].toInt() and 0xFF)
                    if (lo in 0xDC00..0xDFFF) {
                        codeLen = 4
                        return (hi shl 16) or lo
                    }
                }
                codeLen = 2
                return hi
            }
        }
    }

    private fun isLead(c: Int): Boolean = when (lead) {
        LEAD_SJIS -> c in 0x81..0x9F || c in 0xE0..0xFC
        LEAD_EUCJP -> c == 0x8E || c == 0x8F || c in 0xA1..0xFE
        else -> c in 0x81..0xFE
    }

    private fun cidOf(code: Int): Int = when (mode) {
        MODE_CMAP -> encCMap!!.let { cm ->
            val c = cm.cid(code)
            if (c >= 0) c else if (cm.hasCid) 0 else code
        }
        MODE_IDENTITY, MODE_UNKNOWN -> code
        else -> -1
    }

    override fun width(code: Int): Float {
        val cid = cidOf(code)
        if (cid < 0) return dw
        val s = wSingle[cid]
        if (!s.isNaN()) return s
        for (k in wRangeLo.indices) if (cid >= wRangeLo[k] && cid <= wRangeHi[k]) return wRangeVal[k]
        return dw
    }

    override fun unicode(code: Int): String? {
        uniCache[code]?.let { return it.ifEmpty { null } }
        var s: String? = toUni?.unicode(code)
        if (s == null) {
            s = when (mode) {
                MODE_UCS2, MODE_UTF16 -> if ((code ushr 16) != 0) {
                    String(charArrayOf((code ushr 16).toChar(), (code and 0xFFFF).toChar()))
                } else if (code !in 0xD800..0xDFFF) {
                    PdfEncodings.cpString(code)
                } else {
                    null
                }
                MODE_CHARSET -> decodeCharset(code)
                else -> null
            }
        }
        uniCache[code] = s ?: ""
        return s
    }

    private fun decodeCharset(code: Int): String? {
        val cs = charset ?: return null
        val bytes = when {
            code > 0xFFFF -> byteArrayOf((code ushr 16).toByte(), (code ushr 8).toByte(), code.toByte())
            code > 0xFF -> byteArrayOf((code ushr 8).toByte(), code.toByte())
            else -> byteArrayOf(code.toByte())
        }
        val r = String(bytes, cs)
        return if (r.isEmpty() || r.indexOf('�') >= 0) null else r
    }

    companion object {
        const val MODE_IDENTITY = 0
        const val MODE_CMAP = 1
        const val MODE_UCS2 = 2
        const val MODE_UTF16 = 3
        const val MODE_CHARSET = 4
        const val MODE_UNKNOWN = 5
        const val LEAD_GENERIC = 0
        const val LEAD_SJIS = 1
        const val LEAD_EUCJP = 2
    }
}

internal object PdfFontLoader {
    private val HELVETICA = intArrayOf(
        278, 278, 355, 556, 556, 889, 667, 191, 333, 333, 389, 584, 278, 333, 278, 278,
        556, 556, 556, 556, 556, 556, 556, 556, 556, 556, 278, 278, 584, 584, 584, 556,
        1015, 667, 667, 722, 722, 667, 611, 778, 722, 278, 500, 667, 556, 833, 722, 778,
        667, 778, 722, 667, 611, 722, 667, 944, 667, 667, 611, 278, 278, 278, 469, 556,
        333, 556, 556, 500, 556, 556, 278, 556, 556, 222, 222, 500, 222, 833, 556, 556,
        556, 556, 333, 500, 278, 556, 500, 722, 500, 500, 500, 334, 260, 334, 584,
    )
    private val TIMES = intArrayOf(
        250, 333, 408, 500, 500, 833, 778, 180, 333, 333, 500, 564, 250, 333, 250, 278,
        500, 500, 500, 500, 500, 500, 500, 500, 500, 500, 278, 278, 564, 564, 564, 444,
        921, 722, 667, 667, 722, 611, 556, 722, 722, 333, 389, 722, 611, 889, 722, 722,
        556, 722, 667, 556, 611, 722, 722, 944, 722, 722, 611, 333, 278, 333, 469, 500,
        333, 444, 500, 444, 500, 444, 333, 500, 500, 278, 278, 500, 278, 778, 500, 500,
        500, 500, 333, 389, 278, 500, 500, 722, 500, 500, 444, 480, 200, 480, 541,
    )

    /** Loads [d] (a /Type /Font dictionary). Throws on structural problems; callers skip the font. */
    fun load(d: PdfDict): PdfFont {
        val sub = d.name("Subtype")
        return if (sub == "Type0") loadType0(d) else loadSimple(d, sub)
    }

    private fun sane(asc: Double?, desc: Double?): Boolean {
        if (asc == null || desc == null) return false
        val a = asc / 1000
        val b = desc / 1000
        return a > 0.3 && a <= 1.5 && b <= 0.0 && b >= -0.8 && a - b >= 0.4
    }

    private fun applyDescriptor(f: PdfFont, fd: PdfDict?) {
        if (fd == null) return
        val asc = fd.num("Ascent")
        val desc = fd.num("Descent")
        if (sane(asc, desc)) {
            f.ascent = (asc!! / 1000).toFloat()
            f.descent = (desc!! / 1000).toFloat()
        }
    }

    private fun loadSimple(d: PdfDict, sub: String?): PdfFont {
        val fd = d.dict("FontDescriptor")
        val type3 = sub == "Type3"
        var scale = 0.001
        if (type3) {
            val fm = d.arr("FontMatrix")
            val a = fm?.num(0)
            if (a != null && a > 0) scale = a
        }
        val baseFont = d.name("BaseFont") ?: ""
        val widths = FloatArray(256)
        val wArr = d.arr("Widths")
        val missing = fd?.num("MissingWidth")
        val def = when {
            missing != null && missing > 0 -> (missing * scale).toFloat()
            else -> 0.5f
        }
        if (wArr != null) {
            java.util.Arrays.fill(widths, def)
            val first = d.int("FirstChar") ?: 0
            for (k in 0 until minOf(wArr.size, 256)) {
                val c = first + k
                val w = wArr.num(k)
                if (c in 0..255 && w != null) widths[c] = (w * scale).toFloat()
            }
        } else {
            val courier = baseFont.contains("Courier")
            val table = when {
                courier -> null
                baseFont.contains("Times") -> TIMES
                baseFont.contains("Helvetica") || baseFont.contains("Arial") -> HELVETICA
                else -> null
            }
            for (c in 0..255) {
                widths[c] = when {
                    courier -> 0.6f
                    table != null && c in 32..126 -> table[c - 32] / 1000f
                    else -> def
                }
            }
        }

        // encoding
        val enc = d["Encoding"]
        var baseName: String? = null
        var diffs: PdfArray? = null
        if (enc is PdfName) {
            baseName = enc.v
        } else if (enc is PdfDict) {
            baseName = enc.name("BaseEncoding")
            diffs = enc.arr("Differences")
        }
        val flags = fd?.int("Flags") ?: 0
        val symbolic = (flags and 4) != 0 && (flags and 32) == 0
        val basecp: IntArray? = PdfEncodings.byName(baseName) ?: when {
            baseName != null -> null
            type3 || symbolic -> null
            sub == "TrueType" -> PdfEncodings.winAnsi
            else -> PdfEncodings.standard
        }
        val names = arrayOfNulls<String>(256)
        if (diffs != null) {
            var code = 0
            for (k in 0 until diffs.size) {
                val e = diffs[k]
                if (e is PdfName) {
                    if (code in 0..255) names[code] = e.v
                    code++
                } else {
                    numOf(e)?.let { code = it.toInt() }
                }
            }
        }
        var toUni: CMap? = null
        val tu = d.stream("ToUnicode")
        if (tu != null) {
            try {
                toUni = CMap().also { it.parse(PdfFilters.decode(tu)) }
            } catch (_: PdfFormatException) {
                toUni = null
            }
        }
        val uni = arrayOfNulls<String>(256)
        for (c in 0..255) {
            var s: String? = toUni?.unicode(c)
            if (s.isNullOrEmpty()) {
                val nm = names[c]
                s = if (nm != null) {
                    PdfEncodings.nameToUnicode(nm)
                } else if (basecp != null) {
                    val cp = basecp[c]
                    if (cp != 0) PdfEncodings.cpString(cp) else null
                } else if (c in 0x20..0x7E || c in 0xA0..0xFF) {
                    PdfEncodings.cpString(c)
                } else {
                    null
                }
            }
            uni[c] = s
        }
        val f = SimpleFont(widths, uni)
        if (!type3) applyDescriptor(f, fd)
        return f
    }

    private fun loadType0(d: PdfDict): PdfFont {
        val desc = (d.arr("DescendantFonts")?.get(0) as? PdfDict) ?: throw PdfFormatException("no descendant font")
        var mode = Type0Font.MODE_IDENTITY
        var encCMap: CMap? = null
        var charset: Charset? = null
        var lead = Type0Font.LEAD_GENERIC
        var vertical = false
        when (val enc = d["Encoding"]) {
            is PdfName -> {
                val n = enc.v
                vertical = n.endsWith("-V")
                when {
                    n == "Identity-H" || n == "Identity-V" -> mode = Type0Font.MODE_IDENTITY
                    n.startsWith("Uni") && n.contains("-UCS2-") -> mode = Type0Font.MODE_UCS2
                    n.startsWith("Uni") && n.contains("-UTF16-") -> mode = Type0Font.MODE_UTF16
                    else -> {
                        val cs = charsetFor(n)
                        if (cs != null) {
                            mode = Type0Font.MODE_CHARSET
                            charset = cs.first
                            lead = cs.second
                        } else {
                            mode = Type0Font.MODE_UNKNOWN
                        }
                    }
                }
            }
            is PdfStream -> {
                val cm = CMap()
                cm.parse(PdfFilters.decode(enc))
                encCMap = cm
                vertical = cm.wmode == 1 || enc.dict.int("WMode") == 1
                mode = if (cm.codespaceCount > 0) Type0Font.MODE_CMAP else Type0Font.MODE_IDENTITY
            }
            else -> {}
        }
        var toUni: CMap? = null
        val tu = d.stream("ToUnicode")
        if (tu != null) {
            try {
                toUni = CMap().also { it.parse(PdfFilters.decode(tu)) }
            } catch (_: PdfFormatException) {
                toUni = null
            }
        }
        val dw = ((desc.num("DW") ?: 1000.0) / 1000.0).toFloat()
        val single = IntFloatMap()
        val rLo = IntList()
        val rHi = IntList()
        val rVal = ArrayList<Float>()
        val w = desc.arr("W")
        if (w != null) {
            var i = 0
            var guard = 0
            while (i < w.size && guard++ < 1_000_000) {
                val c = w.num(i)?.toInt() ?: break
                if (i + 1 >= w.size) break
                val nx = w[i + 1]
                if (nx is PdfArray) {
                    for (j in 0 until nx.size) nx.num(j)?.let { single[c + j] = (it / 1000.0).toFloat() }
                    i += 2
                } else {
                    val c2 = numOf(nx)?.toInt() ?: break
                    val wd = w.num(i + 2) ?: break
                    val v = (wd / 1000.0).toFloat()
                    if (c2 >= c && c2 - c <= 64) {
                        for (k in c..c2) single[k] = v
                    } else if (c2 >= c) {
                        rLo.add(c)
                        rHi.add(c2)
                        rVal.add(v)
                    }
                    i += 3
                }
            }
        }
        val f = Type0Font(
            mode, encCMap, toUni, charset, lead, dw, single,
            rLo.a.copyOf(rLo.size), rHi.a.copyOf(rHi.size), rVal.toFloatArray(),
        )
        f.vertical = vertical
        applyDescriptor(f, desc.dict("FontDescriptor") ?: d.dict("FontDescriptor"))
        return f
    }

    private fun charsetFor(name: String): Pair<Charset, Int>? {
        val (cs, lead) = when {
            name.startsWith("KSCms-UHC") -> listOf("x-windows-949", "EUC-KR") to Type0Font.LEAD_GENERIC
            name.startsWith("KSC-EUC") || name.startsWith("KSCpc-EUC") -> listOf("EUC-KR") to Type0Font.LEAD_GENERIC
            name.startsWith("GBK") -> listOf("GBK") to Type0Font.LEAD_GENERIC
            name.startsWith("GB-EUC") || name.startsWith("GBpc-EUC") -> listOf("GB2312", "GBK") to Type0Font.LEAD_GENERIC
            name.contains("RKSJ") -> listOf("windows-31j", "Shift_JIS") to Type0Font.LEAD_SJIS
            name == "EUC-H" || name == "EUC-V" -> listOf("EUC-JP") to Type0Font.LEAD_EUCJP
            name.contains("B5") -> listOf("Big5", "x-windows-950") to Type0Font.LEAD_GENERIC
            else -> return null
        }
        for (n in cs) {
            try {
                if (Charset.isSupported(n)) return Charset.forName(n) to lead
            } catch (_: IllegalArgumentException) {
                // try the next alias
            }
        }
        return null
    }
}
