package com.ggumtak.readeraplus.render.pdftext

import java.nio.ByteBuffer

/** Internal parse failure; callers turn it into "skip this object/page". */
internal class PdfFormatException(message: String) : Exception(message)

/** Random-access read-only bytes: the memory-mapped file or a decoded stream. */
internal abstract class ByteSrc {
    abstract val size: Int

    /** Unsigned byte at [i] (0..255); [i] must be in range. */
    abstract operator fun get(i: Int): Int

    abstract fun copy(start: Int, len: Int): ByteArray
}

internal class ArraySrc(private val a: ByteArray) : ByteSrc() {
    override val size: Int get() = a.size
    override fun get(i: Int): Int = a[i].toInt() and 0xFF
    override fun copy(start: Int, len: Int): ByteArray = a.copyOfRange(start, start + len)
}

internal class BufferSrc(private val b: ByteBuffer) : ByteSrc() {
    override val size: Int = b.limit()
    override fun get(i: Int): Int = b.get(i).toInt() and 0xFF
    override fun copy(start: Int, len: Int): ByteArray {
        val out = ByteArray(len)
        val d = b.duplicate()
        d.position(start)
        d.get(out, 0, len)
        return out
    }
}

internal class PdfName(@JvmField val v: String)
internal class PdfString(@JvmField val b: ByteArray)
internal class PdfRef(@JvmField val num: Int, @JvmField val gen: Int)

/** A bare keyword met where an object was expected (operators, `endobj`, ...). */
internal class PdfKw(@JvmField val s: String)

/** Closing `]`, `>>` or end of data met where an object was expected. */
internal object EndMarker

/** Dictionary; [get] and the typed getters resolve indirect references. */
internal class PdfDict(private val doc: PdfDoc?, @JvmField val m: HashMap<String, Any?> = HashMap()) {
    fun raw(k: String): Any? = m[k]

    operator fun get(k: String): Any? {
        val v = m[k]
        return if (v is PdfRef) doc?.resolve(v) else v
    }

    fun name(k: String): String? = (get(k) as? PdfName)?.v
    fun num(k: String): Double? = numOf(get(k))
    fun int(k: String): Int? = numOf(get(k))?.let { if (it.isNaN() || it > 2.0E9 || it < -2.0E9) null else it.toInt() }
    fun dict(k: String): PdfDict? = when (val v = get(k)) {
        is PdfDict -> v
        is PdfStream -> v.dict
        else -> null
    }
    fun arr(k: String): PdfArray? = get(k) as? PdfArray
    fun stream(k: String): PdfStream? = get(k) as? PdfStream
}

/** Array; element getters resolve indirect references. */
internal class PdfArray(private val doc: PdfDoc?, @JvmField val list: ArrayList<Any?>) {
    val size: Int get() = list.size
    fun raw(i: Int): Any? = list[i]
    operator fun get(i: Int): Any? {
        val v = list[i]
        return if (v is PdfRef) doc?.resolve(v) else v
    }
    fun num(i: Int): Double? = numOf(get(i))
}

internal class PdfStream(
    @JvmField val dict: PdfDict,
    @JvmField val src: ByteSrc,
    @JvmField val start: Int,
    @JvmField val len: Int,
)

internal fun numOf(o: Any?): Double? = when (o) {
    is Int -> o.toDouble()
    is Double -> o
    else -> null
}

internal val EMPTY_BYTES = ByteArray(0)

internal const val T_EOF = 0
internal const val T_NUM = 1
internal const val T_STR = 2
internal const val T_NAME = 3
internal const val T_KW = 4
internal const val T_ARR_OPEN = 5
internal const val T_ARR_CLOSE = 6
internal const val T_DICT_OPEN = 7
internal const val T_DICT_CLOSE = 8

/** 0 = regular, 1 = whitespace, 2 = delimiter. */
internal val CHAR_CLASS = ByteArray(256).also { t ->
    for (c in intArrayOf(0, 9, 10, 12, 13, 32)) t[c] = 1
    for (c in "()<>[]{}/%") t[c.code] = 2
}

internal fun isWs(c: Int): Boolean = CHAR_CLASS[c].toInt() == 1

private const val MAX_DEPTH = 80

/**
 * Tokenizer and object parser over [src] between [pos] and [end]. The token API ([next]) sets the value fields
 * without allocating for numbers and keywords; [readObject] builds the object model on top of it.
 * [doc] is attached to parsed dictionaries/arrays so they can resolve references (null for content/CMap data).
 */
internal class PdfLexer(
    private val src: ByteSrc,
    @JvmField var pos: Int,
    private val end: Int = src.size,
    private val doc: PdfDoc? = null,
) {
    @JvmField var numVal = 0.0
    @JvmField var numIsInt = false
    @JvmField var strVal: ByteArray = EMPTY_BYTES
    @JvmField var nameVal = ""
    @JvmField var kwStart = 0
    @JvmField var kwEnd = 0

    /** Up to three keyword bytes packed little-endian, or -1 for longer keywords. */
    @JvmField var kwCode = -1
    private var buf = ByteArray(64)

    fun kwString(): String = String(src.copy(kwStart, kwEnd - kwStart), Charsets.ISO_8859_1)

    fun kwIs(s: String): Boolean {
        if (kwEnd - kwStart != s.length) return false
        for (i in s.indices) if (src[kwStart + i] != s[i].code) return false
        return true
    }

    /** Next token type; value in the matching field. */
    fun next(): Int {
        while (true) {
            var p = pos
            while (p < end) {
                val c = src[p]
                if (CHAR_CLASS[c].toInt() == 1) {
                    p++
                } else if (c == 37) {
                    p++
                    while (p < end && src[p] != 10 && src[p] != 13) p++
                } else {
                    break
                }
            }
            pos = p
            if (p >= end) return T_EOF
            when (src[p]) {
                47 -> return readName()
                40 -> return readLiteral()
                60 -> {
                    if (p + 1 < end && src[p + 1] == 60) {
                        pos = p + 2
                        return T_DICT_OPEN
                    }
                    return readHex()
                }
                62 -> {
                    if (p + 1 < end && src[p + 1] == 62) {
                        pos = p + 2
                        return T_DICT_CLOSE
                    }
                    pos = p + 1
                    continue
                }
                91 -> {
                    pos = p + 1
                    return T_ARR_OPEN
                }
                93 -> {
                    pos = p + 1
                    return T_ARR_CLOSE
                }
                123, 125, 41 -> {
                    kwStart = p
                    kwEnd = p + 1
                    kwCode = src[p]
                    pos = p + 1
                    return T_KW
                }
                else -> {
                    if (isNumStart(src[p]) && readNumber()) return T_NUM
                    return readKeyword()
                }
            }
        }
    }

    private fun isNumStart(c: Int) = (c in 48..57) || c == 43 || c == 45 || c == 46

    private fun readNumber(): Boolean {
        var p = pos
        var neg = false
        while (p < end && (src[p] == 43 || src[p] == 45)) {
            if (src[p] == 45) neg = true
            p++
        }
        var v = 0.0
        var digits = 0
        while (p < end && src[p] in 48..57) {
            v = v * 10 + (src[p] - 48)
            p++
            digits++
        }
        var isInt = true
        if (p < end && src[p] == 46) {
            isInt = false
            p++
            var scale = 0.1
            while (p < end && src[p] in 48..57) {
                v += (src[p] - 48) * scale
                scale /= 10
                p++
                digits++
            }
        }
        if (digits == 0) return false
        numVal = if (neg) -v else v
        numIsInt = isInt
        pos = p
        return true
    }

    private fun readKeyword(): Int {
        var p = pos
        val s = p
        while (p < end && CHAR_CLASS[src[p]].toInt() == 0) p++
        if (p == s) p++ // never stall on an unexpected byte
        kwStart = s
        kwEnd = p
        val n = p - s
        kwCode = if (n <= 3) {
            var code = 0
            for (i in 0 until n) code = code or (src[s + i] shl (8 * i))
            code
        } else {
            -1
        }
        pos = p
        return T_KW
    }

    private fun put(n: Int, b: Int): Int {
        if (n == buf.size) buf = buf.copyOf(n * 2)
        buf[n] = b.toByte()
        return n + 1
    }

    private fun readName(): Int {
        var p = pos + 1
        var n = 0
        while (p < end) {
            val c = src[p]
            if (CHAR_CLASS[c].toInt() != 0) break
            if (c == 35 && p + 2 < end && hexVal(src[p + 1]) >= 0 && hexVal(src[p + 2]) >= 0) {
                n = put(n, hexVal(src[p + 1]) * 16 + hexVal(src[p + 2]))
                p += 3
            } else {
                n = put(n, c)
                p++
            }
        }
        pos = p
        nameVal = String(buf, 0, n, Charsets.ISO_8859_1)
        return T_NAME
    }

    private fun readLiteral(): Int {
        var p = pos + 1
        var depth = 1
        var n = 0
        while (p < end) {
            val c = src[p++]
            when (c) {
                92 -> {
                    if (p >= end) break
                    val e = src[p++]
                    when (e) {
                        110 -> n = put(n, 10)
                        114 -> n = put(n, 13)
                        116 -> n = put(n, 9)
                        98 -> n = put(n, 8)
                        102 -> n = put(n, 12)
                        13 -> if (p < end && src[p] == 10) p++
                        10 -> {}
                        in 48..55 -> {
                            var v = e - 48
                            var k = 0
                            while (k < 2 && p < end && src[p] in 48..55) {
                                v = v * 8 + (src[p] - 48)
                                p++
                                k++
                            }
                            n = put(n, v and 0xFF)
                        }
                        else -> n = put(n, e)
                    }
                }
                40 -> {
                    depth++
                    n = put(n, c)
                }
                41 -> {
                    depth--
                    if (depth == 0) break
                    n = put(n, c)
                }
                13 -> {
                    if (p < end && src[p] == 10) p++
                    n = put(n, 10)
                }
                else -> n = put(n, c)
            }
        }
        pos = p
        strVal = buf.copyOf(n)
        return T_STR
    }

    private fun readHex(): Int {
        var p = pos + 1
        var n = 0
        var hi = -1
        while (p < end) {
            val c = src[p++]
            if (c == 62) break
            val h = hexVal(c)
            if (h < 0) continue
            if (hi < 0) {
                hi = h
            } else {
                n = put(n, hi * 16 + h)
                hi = -1
            }
        }
        if (hi >= 0) n = put(n, hi * 16)
        pos = p
        strVal = buf.copyOf(n)
        return T_STR
    }

    /** Reads one object; [refs] enables `n g R` recognition. Returns [EndMarker]/[PdfKw] for non-objects. */
    fun readObject(depth: Int = 0, refs: Boolean = true): Any? = objectFrom(next(), depth, refs)

    fun objectFrom(t: Int, depth: Int, refs: Boolean): Any? = when (t) {
        T_NUM -> if (refs && numIsInt && numVal >= 0 && numVal < 2.0E9) readRefOrInt() else numberValue()
        T_STR -> PdfString(strVal)
        T_NAME -> PdfName(nameVal)
        T_ARR_OPEN -> readArray(depth + 1, refs)
        T_DICT_OPEN -> readDict(depth + 1, refs)
        T_KW -> when {
            kwIs("true") -> true
            kwIs("false") -> false
            kwIs("null") -> null
            else -> PdfKw(kwString())
        }
        else -> EndMarker
    }

    private fun numberValue(): Any = if (numIsInt && numVal > -2.0E9 && numVal < 2.0E9) numVal.toInt() else numVal

    private fun readRefOrInt(): Any {
        val n = numVal.toInt()
        val save = pos
        if (next() == T_NUM && numIsInt && numVal >= 0) {
            val g = numVal.toInt()
            if (next() == T_KW && kwEnd - kwStart == 1 && src[kwStart] == 82) return PdfRef(n, g)
        }
        pos = save
        return n
    }

    private fun isStructural(): Boolean =
        kwIs("endobj") || kwIs("endstream") || kwIs("stream") || kwIs("obj") ||
            kwIs("xref") || kwIs("trailer") || kwIs("startxref")

    fun readArray(depth: Int, refs: Boolean): PdfArray {
        if (depth > MAX_DEPTH) throw PdfFormatException("nesting too deep")
        val list = ArrayList<Any?>()
        while (true) {
            val t = next()
            if (t == T_ARR_CLOSE || t == T_EOF) break
            if (t == T_DICT_CLOSE) continue
            val o = objectFrom(t, depth, refs)
            if (o is PdfKw) {
                if (isStructural()) {
                    pos = kwStart
                    break
                }
                continue
            }
            if (o === EndMarker) continue
            list.add(o)
        }
        return PdfArray(doc, list)
    }

    fun readDict(depth: Int, refs: Boolean): PdfDict {
        if (depth > MAX_DEPTH) throw PdfFormatException("nesting too deep")
        val m = HashMap<String, Any?>()
        while (true) {
            val t = next()
            if (t == T_DICT_CLOSE || t == T_EOF) break
            if (t != T_NAME) {
                if (t == T_KW && isStructural()) {
                    pos = kwStart
                    break
                }
                continue
            }
            val key = nameVal
            val t2 = next()
            if (t2 == T_DICT_CLOSE || t2 == T_EOF) break
            val v = objectFrom(t2, depth, refs)
            if (v is PdfKw) {
                if (isStructural()) {
                    pos = kwStart
                    break
                }
                continue
            }
            if (v === EndMarker) continue
            m[key] = v
        }
        return PdfDict(doc, m)
    }
}

internal fun hexVal(c: Int): Int = when (c) {
    in 48..57 -> c - 48
    in 65..70 -> c - 55
    in 97..102 -> c - 87
    else -> -1
}

/** Growable byte output. */
internal class ByteSink(initial: Int = 256) {
    @JvmField var buf = ByteArray(maxOf(16, initial))
    @JvmField var len = 0

    private fun grow(need: Int) {
        var n = buf.size.toLong() * 2
        while (n < need) n *= 2
        buf = buf.copyOf(minOf(n, Int.MAX_VALUE.toLong() - 16).toInt())
    }

    fun put(b: Int) {
        if (len == buf.size) grow(len + 1)
        buf[len++] = b.toByte()
    }

    fun put(a: ByteArray, off: Int, n: Int) {
        if (n <= 0) return
        if (len + n > buf.size) grow(len + n)
        System.arraycopy(a, off, buf, len, n)
        len += n
    }

    fun toByteArray(): ByteArray = buf.copyOf(len)
}

/** Growable int list. */
internal class IntList(cap: Int = 16) {
    @JvmField var a = IntArray(cap)
    @JvmField var size = 0
    fun add(v: Int) {
        if (size == a.size) a = a.copyOf(size * 2)
        a[size++] = v
    }
}

/** Open-addressing Int -> object map (values are never null). */
internal class IntObjMap<V : Any>(cap: Int = 16) {
    private var keys = IntArray(cap)
    private var vals = arrayOfNulls<Any>(cap)
    var size = 0
        private set

    private fun slot(k: Int, mask: Int) = (k * -1640531535 ushr 7) and mask

    @Suppress("UNCHECKED_CAST")
    operator fun get(k: Int): V? {
        val mask = keys.size - 1
        var i = slot(k, mask)
        while (true) {
            val v = vals[i] ?: return null
            if (keys[i] == k) return v as V
            i = (i + 1) and mask
        }
    }

    operator fun set(k: Int, v: V) {
        if ((size + 1) * 2 > keys.size) grow()
        val mask = keys.size - 1
        var i = slot(k, mask)
        while (vals[i] != null) {
            if (keys[i] == k) {
                vals[i] = v
                return
            }
            i = (i + 1) and mask
        }
        keys[i] = k
        vals[i] = v
        size++
    }

    private fun grow() {
        val ok = keys
        val ov = vals
        keys = IntArray(ok.size * 2)
        vals = arrayOfNulls(ok.size * 2)
        size = 0
        for (i in ok.indices) {
            @Suppress("UNCHECKED_CAST")
            val v = ov[i] as V?
            if (v != null) set(ok[i], v)
        }
    }
}

/** Open-addressing Int -> Float map. */
internal class IntFloatMap(cap: Int = 16) {
    private var keys = IntArray(cap)
    private var vals = FloatArray(cap)
    private var used = BooleanArray(cap)
    var size = 0
        private set

    private fun slot(k: Int, mask: Int) = (k * -1640531535 ushr 7) and mask

    /** Value for [k] or NaN when absent. */
    operator fun get(k: Int): Float {
        val mask = keys.size - 1
        var i = slot(k, mask)
        while (used[i]) {
            if (keys[i] == k) return vals[i]
            i = (i + 1) and mask
        }
        return Float.NaN
    }

    operator fun set(k: Int, v: Float) {
        if ((size + 1) * 2 > keys.size) grow()
        val mask = keys.size - 1
        var i = slot(k, mask)
        while (used[i]) {
            if (keys[i] == k) {
                vals[i] = v
                return
            }
            i = (i + 1) and mask
        }
        used[i] = true
        keys[i] = k
        vals[i] = v
        size++
    }

    private fun grow() {
        val ok = keys
        val ov = vals
        val ou = used
        keys = IntArray(ok.size * 2)
        vals = FloatArray(ok.size * 2)
        used = BooleanArray(ok.size * 2)
        size = 0
        for (i in ok.indices) if (ou[i]) set(ok[i], ov[i])
    }
}
