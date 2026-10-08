package com.ggumtak.readeraplus.render.pdftext

/**
 * The object layer of a PDF: cross-reference tables/streams (with /Prev chains and hybrids), object streams and
 * a scan-based recovery for damaged files. Not thread-safe.
 */
internal class PdfDoc(@JvmField val src: ByteSrc) {
    private val size = src.size
    private var typ = ByteArray(0)
    private var offA = IntArray(0)
    private var auxB = IntArray(0)
    private var maxNum = -1
    private var base = 0
    private var rebuilt = false
    private var encryptSeen = false
    private var lengthDepth = 0

    /** Free entries of the table being read; a hybrid file's /XRefStm may override them (they mark its objects). */
    private var weakFree: HashSet<Int>? = null
    private val cache = HashMap<Int, Any?>()
    private val loading = HashSet<Int>()
    private val objStmCache = arrayOfNulls<ObjStmData>(4)
    private var objStmNext = 0

    /** Newest trailer dictionary (or the trailer-like dictionary recovered by scanning). */
    var trailer: PdfDict? = null
        private set

    private class ObjStmData(val num: Int, val data: ByteArray, val nums: IntArray, val offs: IntArray, val first: Int) {
        val source = ArraySrc(data)
    }

    /** Opens the structure. Throws [PdfTextException] for non-PDF or encrypted files. */
    fun load() {
        var h = -1
        val lim = minOf(size - 5, 1024)
        for (i in 0..lim) {
            if (src[i] == 37 && src[i + 1] == 80 && src[i + 2] == 68 && src[i + 3] == 70 && src[i + 4] == 45) {
                h = i
                break
            }
        }
        if (h < 0) throw PdfTextException("not a PDF")
        base = h
        var ok = false
        try {
            val sx = findStartXref()
            if (sx >= 0) {
                readChain(sx)
                ok = rootOk()
            }
        } catch (_: PdfFormatException) {
            ok = false
        } catch (_: StackOverflowError) {
            ok = false
        } catch (_: IndexOutOfBoundsException) {
            ok = false
        }
        if (encryptSeen) throw PdfTextException("encrypted")
        if (!ok) {
            rebuild()
            if (encryptSeen) throw PdfTextException("encrypted")
            if (!rootOk()) throw PdfTextException("no document catalog")
        }
    }

    private fun rootOk(): Boolean {
        val root = trailer?.get("Root") as? PdfDict ?: return false
        return root.get("Pages") is PdfDict
    }

    /** Follows references (bounded) to a non-reference value; null for free/missing objects. */
    fun resolve(o: Any?): Any? {
        var x = o
        var n = 0
        while (x is PdfRef) {
            if (++n > 16) return null
            x = getObject(x.num)
        }
        return x
    }

    /** The object [num] (generation ignored), parsed once and cached; null when absent or unreadable. */
    fun getObject(num: Int): Any? {
        if (num < 0 || num > MAX_OBJ) return null
        val c = cache[num]
        if (c != null) return c
        if (!loading.add(num)) return null
        try {
            val o = try {
                loadObject(num)
            } catch (_: StackOverflowError) {
                null
            }
            if (cache.size > 20000) cache.clear()
            if (o != null) cache[num] = o
            return o
        } finally {
            loading.remove(num)
        }
    }

    /** Highest object number with a cross-reference entry. */
    fun maxObjectNumber(): Int = maxNum

    private fun typeOf(num: Int): Int = if (num in 0..maxNum) typ[num].toInt() else 0

    private fun loadObject(num: Int): Any? {
        when (typeOf(num)) {
            T_INUSE -> {
                try {
                    return parseAt(offA[num] + base, num)
                } catch (e: PdfFormatException) {
                    if (rebuilt) return null
                    rebuild()
                    return loadObject(num)
                } catch (e: IndexOutOfBoundsException) {
                    if (rebuilt) return null
                    rebuild()
                    return loadObject(num)
                }
            }
            T_COMP -> return try {
                loadCompressed(offA[num], auxB[num], num)
            } catch (_: PdfFormatException) {
                null
            }
            else -> return null
        }
    }

    private fun parseAt(off: Int, expectNum: Int): Any? {
        if (off < 0 || off >= size) throw PdfFormatException("bad offset")
        val lx = PdfLexer(src, off, size, this)
        if (lx.next() != T_NUM || !lx.numIsInt) throw PdfFormatException("no object header")
        if (expectNum >= 0 && lx.numVal.toInt() != expectNum) throw PdfFormatException("object number mismatch")
        if (lx.next() != T_NUM) throw PdfFormatException("no generation")
        if (lx.next() != T_KW || !lx.kwIs("obj")) throw PdfFormatException("no obj keyword")
        val o = lx.readObject(0, true)
        if (o is PdfDict) {
            val save = lx.pos
            if (lx.next() == T_KW && lx.kwIs("stream")) return makeStream(o, lx.pos)
            lx.pos = save
        }
        return if (o === EndMarker || o is PdfKw) null else o
    }

    private fun makeStream(dict: PdfDict, afterKeyword: Int): PdfStream {
        var p = afterKeyword
        while (p < size && (src[p] == 32 || src[p] == 9)) p++
        if (p < size && src[p] == 13) {
            p++
            if (p < size && src[p] == 10) p++
        } else if (p < size && src[p] == 10) {
            p++
        }
        var len = -1
        val lv = dict.raw("Length")
        val l: Any? = if (lv is PdfRef) {
            if (lengthDepth < 3) {
                lengthDepth++
                try {
                    resolve(lv)
                } finally {
                    lengthDepth--
                }
            } else {
                null
            }
        } else {
            lv
        }
        if (l is Int) len = l
        var ok = false
        if (len >= 0 && p + len <= size) {
            var q = p + len
            val lim = minOf(size, q + 16)
            while (q < lim && isWs(src[q])) q++
            ok = matchesAt(q, "endstream") || matchesAt(q, "endobj")
        }
        if (!ok) {
            val idx = indexOf("endstream", p)
            val e = if (idx >= 0) idx else {
                val e2 = indexOf("endobj", p)
                if (e2 >= 0) e2 else size
            }
            len = e - p
            if (len > 0 && src[p + len - 1] == 10) {
                len--
                if (len > 0 && src[p + len - 1] == 13) len--
            } else if (len > 0 && src[p + len - 1] == 13) {
                len--
            }
        }
        return PdfStream(dict, src, p, maxOf(0, len))
    }

    private fun matchesAt(p: Int, s: String): Boolean {
        if (p < 0 || p + s.length > size) return false
        for (i in s.indices) if (src[p + i] != s[i].code) return false
        return true
    }

    private fun indexOf(s: String, from: Int): Int {
        val c0 = s[0].code
        var i = maxOf(0, from)
        val last = size - s.length
        while (i <= last) {
            if (src[i] == c0 && matchesAt(i, s)) return i
            i++
        }
        return -1
    }

    private fun lastIndexOf(s: String, from: Int, to: Int): Int {
        var i = minOf(from, size - s.length)
        while (i >= to) {
            if (matchesAt(i, s)) return i
            i--
        }
        return -1
    }

    // ---- object streams ----

    private fun loadCompressed(stmNum: Int, idx: Int, num: Int): Any? {
        val d = objStm(stmNum) ?: return null
        var i = idx
        if (i < 0 || i >= d.nums.size || d.nums[i] != num) {
            i = d.nums.indexOf(num)
            if (i < 0) return null
        }
        val start = d.first + d.offs[i]
        if (start < 0 || start >= d.data.size) return null
        val o = PdfLexer(d.source, start, d.data.size, this).readObject(0, true)
        return if (o === EndMarker || o is PdfKw) null else o
    }

    private fun objStm(num: Int): ObjStmData? {
        for (c in objStmCache) if (c != null && c.num == num) return c
        val s = getObject(num) as? PdfStream ?: return null
        val n = s.dict.int("N") ?: return null
        val first = s.dict.int("First") ?: return null
        val data = try {
            PdfFilters.decode(s)
        } catch (_: PdfFormatException) {
            return null
        }
        // Each header entry takes at least 4 bytes ("n o "): a bigger /N is corrupt, not an allocation to make.
        if (n < 0 || n > data.size / 4 || first < 0) return null
        val lx = PdfLexer(ArraySrc(data), 0, minOf(first, data.size), null)
        val nums = IntArray(n)
        val offs = IntArray(n)
        var cnt = 0
        while (cnt < n) {
            if (lx.next() != T_NUM) break
            val a = lx.numVal.toInt()
            if (lx.next() != T_NUM) break
            nums[cnt] = a
            offs[cnt] = lx.numVal.toInt()
            cnt++
        }
        val d = ObjStmData(num, data, nums.copyOf(cnt), offs.copyOf(cnt), first)
        objStmCache[objStmNext] = d
        objStmNext = (objStmNext + 1) % objStmCache.size
        return d
    }

    // ---- cross-reference reading ----

    private fun ensure(num: Int) {
        if (num <= maxNum) return
        if (num >= typ.size) {
            val n = maxOf(num + 1, minOf(typ.size * 2, MAX_OBJ + 1), 64)
            typ = typ.copyOf(n)
            offA = offA.copyOf(n)
            auxB = auxB.copyOf(n)
        }
        maxNum = num
    }

    /** Records an entry unless a newer section already did (or [force]). */
    private fun setEntry(num: Int, type: Int, a: Int, b: Int, force: Boolean = false) {
        if (num < 0 || num > MAX_OBJ) return
        ensure(num)
        if (typ[num].toInt() != 0 && !force) {
            val wf = weakFree
            if (!(typ[num].toInt() == T_FREE && type != T_FREE && wf != null && wf.remove(num))) return
        }
        typ[num] = type.toByte()
        offA[num] = a
        auxB[num] = b
    }

    private fun findStartXref(): Int {
        val at = lastIndexOf("startxref", size - 9, maxOf(0, size - 2048))
        if (at < 0) return -1
        val lx = PdfLexer(src, at + 9, size, null)
        if (lx.next() != T_NUM) return -1
        return lx.numVal.toInt()
    }

    private fun readChain(start: Int) {
        val visited = HashSet<Int>()
        var off = start
        var first = true
        while (off >= 0 && off + base < size && visited.add(off) && visited.size < 4096) {
            weakFree = HashSet()
            val d = try {
                readSection(off + base) ?: throw PdfFormatException("bad xref section")
            } catch (e: PdfFormatException) {
                weakFree = null
                throw e
            }
            if (d.raw("Encrypt") != null) encryptSeen = true
            if (first) {
                trailer = d
                first = false
            } else if (trailer?.raw("Root") == null && d.raw("Root") != null) {
                trailer = d
            }
            d.int("XRefStm")?.let { sx ->
                if (sx >= 0 && sx + base < size && visited.add(sx)) {
                    try {
                        readSection(sx + base)
                    } catch (_: PdfFormatException) {
                        // a damaged hybrid stream falls back to the table entries
                    }
                }
            }
            weakFree = null
            off = d.int("Prev") ?: -1
        }
        if (first) throw PdfFormatException("no xref")
    }

    /** Reads the table or stream at [off], registering its entries; returns the trailer dictionary. */
    private fun readSection(off: Int): PdfDict? {
        val lx = PdfLexer(src, off, size, this)
        val t = lx.next()
        if (t == T_KW && lx.kwIs("xref")) return readTable(lx)
        if (t == T_NUM) {
            val s = parseAt(off, -1) as? PdfStream ?: return null
            readXrefStream(s)
            return s.dict
        }
        return null
    }

    private fun readTable(lx: PdfLexer): PdfDict? {
        while (true) {
            val t = lx.next()
            if (t == T_KW && lx.kwIs("trailer")) {
                return lx.readObject(0, true) as? PdfDict
            }
            if (t != T_NUM || !lx.numIsInt) return null
            var start = lx.numVal.toInt()
            if (lx.next() != T_NUM) return null
            val count = lx.numVal.toInt()
            if (count < 0 || count > MAX_OBJ) return null
            var i = 0
            while (i < count) {
                val save = lx.pos
                if (lx.next() != T_NUM) {
                    lx.pos = save
                    break
                }
                val o = lx.numVal
                if (lx.next() != T_NUM) return null
                val g = lx.numVal.toInt()
                if (lx.next() != T_KW) return null
                val inUse = lx.kwIs("n")
                if (!inUse && !lx.kwIs("f")) return null
                if (i == 0 && start == 1 && !inUse && g == 65535) start = 0 // off-by-one subsection header
                if (inUse) {
                    if (o >= 0 && o < size) setEntry(start + i, T_INUSE, o.toInt(), g) else setEntry(start + i, T_FREE, 0, 0)
                } else {
                    setEntry(start + i, T_FREE, 0, 0)
                    weakFree?.add(start + i)
                }
                i++
            }
        }
    }

    private fun readXrefStream(s: PdfStream) {
        val w = s.dict.arr("W") ?: throw PdfFormatException("no /W")
        if (w.size < 3) throw PdfFormatException("bad /W")
        val w0 = w.num(0)?.toInt() ?: 0
        val w1 = w.num(1)?.toInt() ?: 0
        val w2 = w.num(2)?.toInt() ?: 0
        if (w0 !in 0..4 || w1 !in 0..4 || w2 !in 0..4 || w0 + w1 + w2 == 0) throw PdfFormatException("bad /W")
        val data = PdfFilters.decode(s)
        val rowLen = w0 + w1 + w2
        val sizeV = s.dict.int("Size") ?: 0
        val idx = s.dict.arr("Index")
        val ranges = ArrayList<IntArray>()
        if (idx != null && idx.size >= 2) {
            var k = 0
            while (k + 1 < idx.size) {
                val a = idx.num(k)?.toInt() ?: break
                val b = idx.num(k + 1)?.toInt() ?: break
                ranges.add(intArrayOf(a, b))
                k += 2
            }
        } else {
            ranges.add(intArrayOf(0, sizeV))
        }
        var p = 0
        for (r in ranges) {
            for (i in 0 until r[1]) {
                if (p + rowLen > data.size) return
                var type = 1
                if (w0 > 0) {
                    type = 0
                    for (k in 0 until w0) type = (type shl 8) or (data[p + k].toInt() and 0xFF)
                }
                var f2 = 0L
                for (k in 0 until w1) f2 = (f2 shl 8) or (data[p + w0 + k].toLong() and 0xFF)
                var f3 = 0L
                for (k in 0 until w2) f3 = (f3 shl 8) or (data[p + w0 + w1 + k].toLong() and 0xFF)
                p += rowLen
                val num = r[0] + i
                when (type) {
                    0 -> setEntry(num, T_FREE, 0, 0)
                    1 -> if (f2 in 0 until size) setEntry(num, T_INUSE, f2.toInt(), f3.toInt())
                    2 -> if (f2 in 1..MAX_OBJ.toLong()) setEntry(num, T_COMP, f2.toInt(), f3.toInt())
                    else -> {}
                }
            }
        }
    }

    // ---- recovery ----

    private fun isDigit(c: Int) = c in 48..57

    /** Rebuilds the entry table by scanning the whole file for `n g obj`; the last definition of a number wins. */
    private fun rebuild() {
        rebuilt = true
        cache.clear()
        objStmCache.fill(null)
        typ = ByteArray(0)
        offA = IntArray(0)
        auxB = IntArray(0)
        maxNum = -1
        trailer = null
        var cand: PdfDict? = null
        var i = 0
        val n = size
        while (i < n - 2) {
            val c = src[i]
            if (c == 111 && src[i + 1] == 98 && src[i + 2] == 106) {
                i = scanObjHeader(i)
            } else if (c == 116 && matchesAt(i, "trailer")) {
                val d = try {
                    PdfLexer(src, i + 7, n, this).readObject(0, true) as? PdfDict
                } catch (_: PdfFormatException) {
                    null
                }
                if (d != null) {
                    if (d.raw("Encrypt") != null) encryptSeen = true
                    if (d.raw("Root") != null) cand = d
                }
                i += 7
            } else {
                i++
            }
        }
        trailer = cand
        var catalogNum = -1
        var xrefDict: PdfDict? = null
        val objStms = ArrayList<Int>()
        for (num in 0..maxNum) {
            if (typ[num].toInt() != T_INUSE) continue
            val o = getObject(num)
            val d = (o as? PdfStream)?.dict ?: (o as? PdfDict) ?: continue
            when (d.name("Type")) {
                "ObjStm" -> objStms.add(num)
                "XRef" -> {
                    if (d.raw("Encrypt") != null) encryptSeen = true
                    if (d.raw("Root") != null) xrefDict = d
                }
                "Catalog" -> catalogNum = num
                else -> {}
            }
        }
        for (sn in objStms) {
            val d = objStm(sn) ?: continue
            for (k in d.nums.indices) setEntry(d.nums[k], T_COMP, sn, k)
        }
        if (!rootOk()) {
            val xr = xrefDict
            if (xr != null && xr.get("Root") is PdfDict) {
                trailer = xr
            } else {
                if (catalogNum < 0) {
                    outer@ for (sn in objStms) {
                        val d = objStm(sn) ?: continue
                        for (k in d.nums.indices) {
                            val o = getObject(d.nums[k])
                            if (o is PdfDict && o.name("Type") == "Catalog") {
                                catalogNum = d.nums[k]
                                break@outer
                            }
                        }
                    }
                }
                if (catalogNum >= 0) {
                    val m = HashMap<String, Any?>()
                    m["Root"] = PdfRef(catalogNum, 0)
                    trailer = PdfDict(this, m)
                }
            }
        }
        cache.clear()
    }

    /** [i] points at `obj`; registers the header before it when well-formed. Returns the next scan position. */
    private fun scanObjHeader(i: Int): Int {
        if (i + 3 < size && CHAR_CLASS[src[i + 3]].toInt() == 0) return i + 1
        var p = i - 1
        var q = p
        while (q >= 0 && isWs(src[q])) q--
        if (q == p) return i + 3
        p = q
        while (q >= 0 && isDigit(src[q])) q--
        if (q == p) return i + 3
        val genStart = q + 1
        p = q
        while (q >= 0 && isWs(src[q])) q--
        if (q == p) return i + 3
        p = q
        while (q >= 0 && isDigit(src[q])) q--
        if (q == p) return i + 3
        val numStart = q + 1
        if (p - numStart >= 9 || (q >= 0 && CHAR_CLASS[src[q]].toInt() == 0)) return i + 3
        var num = 0
        for (k in numStart..p) num = num * 10 + (src[k] - 48)
        var gen = 0
        var k = genStart
        while (k < i && isDigit(src[k]) && gen < 100000) {
            gen = gen * 10 + (src[k] - 48)
            k++
        }
        setEntry(num, T_INUSE, numStart - base, gen, true)
        return i + 3
    }

    companion object {
        const val T_FREE = 1
        const val T_INUSE = 2
        const val T_COMP = 3
        const val MAX_OBJ = 3_000_000
    }
}
