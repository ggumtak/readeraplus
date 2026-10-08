package com.ggumtak.readeraplus.render.pdftext

import java.util.zip.DataFormatException
import java.util.zip.Inflater

/** Stream filters (PDF 1.7 section 7.4) needed to read text: Flate, LZW, ASCII85, ASCIIHex, RunLength and predictors. */
internal object PdfFilters {
    /** One decoded stream never grows beyond this; longer output is truncated. */
    const val MAX_OUT = 64 * 1024 * 1024

    /** Decodes [s] through its /Filter chain. Throws [PdfFormatException] for image-only filters. */
    fun decode(s: PdfStream): ByteArray {
        val data = s.src.copy(s.start, s.len)
        return decodeChain(data, s.dict["Filter"], s.dict["DecodeParms"] ?: s.dict["DP"])
    }

    /** Decodes inline-image style data with abbreviated names allowed. */
    fun decodeChain(input: ByteArray, filter: Any?, parms: Any?): ByteArray {
        var data = input
        val names = ArrayList<String>()
        when (filter) {
            is PdfName -> names.add(filter.v)
            is PdfArray -> for (i in 0 until filter.size) (filter[i] as? PdfName)?.let { names.add(it.v) }
            else -> {}
        }
        for ((i, n) in names.withIndex()) {
            val p: PdfDict? = when (parms) {
                is PdfDict -> if (i == 0) parms else null
                is PdfArray -> if (i < parms.size) parms[i] as? PdfDict else null
                else -> null
            }
            data = when (n) {
                "FlateDecode", "Fl" -> predictor(inflate(data), p)
                "LZWDecode", "LZW" -> predictor(lzw(data, p?.int("EarlyChange") ?: 1), p)
                "ASCII85Decode", "A85" -> ascii85(data)
                "ASCIIHexDecode", "AHx" -> asciiHex(data)
                "RunLengthDecode", "RL" -> runLength(data)
                else -> throw PdfFormatException("unsupported filter $n")
            }
        }
        return data
    }

    fun inflate(data: ByteArray): ByteArray {
        var out = tryInflate(data, 0, false)
        if (out.len == 0 && data.size > 2) {
            // headerless or damaged zlib header: try raw deflate, with and without the 2 header bytes
            val raw = tryInflate(data, 0, true)
            out = if (raw.len > 0) raw else tryInflate(data, 2, true)
        }
        return out.toByteArray()
    }

    private fun tryInflate(data: ByteArray, off: Int, nowrap: Boolean): ByteSink {
        val out = ByteSink(maxOf(256, minOf(data.size * 4, 1 shl 20)))
        val inf = Inflater(nowrap)
        try {
            inf.setInput(data, off, data.size - off)
            val tmp = ByteArray(16384)
            while (out.len < MAX_OUT) {
                val n = inf.inflate(tmp)
                if (n > 0) {
                    out.put(tmp, 0, n)
                } else if (inf.finished() || inf.needsInput() || inf.needsDictionary()) {
                    break
                }
            }
        } catch (_: DataFormatException) {
            // keep what was decoded so far
        } finally {
            inf.end()
        }
        if (out.len > MAX_OUT) out.len = MAX_OUT
        return out
    }

    fun asciiHex(data: ByteArray): ByteArray {
        val out = ByteSink(data.size / 2 + 1)
        var hi = -1
        for (b in data) {
            val c = b.toInt() and 0xFF
            if (c == 62) break
            val h = hexVal(c)
            if (h < 0) continue
            if (hi < 0) {
                hi = h
            } else {
                out.put(hi * 16 + h)
                hi = -1
            }
        }
        if (hi >= 0) out.put(hi * 16)
        return out.toByteArray()
    }

    fun ascii85(data: ByteArray): ByteArray {
        val out = ByteSink(data.size)
        var i = 0
        if (data.size >= 2 && data[0] == '<'.code.toByte() && data[1] == '~'.code.toByte()) i = 2
        var tuple = 0L
        var cnt = 0
        while (i < data.size) {
            val c = data[i++].toInt() and 0xFF
            if (c == '~'.code) break
            if (c == 'z'.code && cnt == 0) {
                for (k in 0 until 4) out.put(0)
                continue
            }
            if (c < 33 || c > 117) continue
            tuple = tuple * 85 + (c - 33)
            if (++cnt == 5) {
                out.put((tuple shr 24).toInt())
                out.put((tuple shr 16).toInt())
                out.put((tuple shr 8).toInt())
                out.put(tuple.toInt())
                tuple = 0
                cnt = 0
            }
        }
        if (cnt > 1) {
            for (k in cnt until 5) tuple = tuple * 85 + 84
            for (k in 0 until cnt - 1) out.put((tuple shr (24 - 8 * k)).toInt())
        }
        return out.toByteArray()
    }

    fun runLength(data: ByteArray): ByteArray {
        val out = ByteSink(data.size * 2)
        var i = 0
        while (i < data.size && out.len < MAX_OUT) {
            val n = data[i++].toInt() and 0xFF
            if (n == 128) break
            if (n < 128) {
                val c = minOf(n + 1, data.size - i)
                out.put(data, i, c)
                i += c
            } else if (i < data.size) {
                val b = data[i++].toInt()
                for (k in 0 until 257 - n) out.put(b)
            }
        }
        return out.toByteArray()
    }

    fun lzw(data: ByteArray, early: Int): ByteArray {
        val out = ByteSink(data.size * 3)
        val prefix = IntArray(4096)
        val suffix = ByteArray(4096)
        val first = ByteArray(4096)
        val length = IntArray(4096)
        for (i in 0 until 256) {
            prefix[i] = -1
            suffix[i] = i.toByte()
            first[i] = i.toByte()
            length[i] = 1
        }
        val tmp = ByteArray(4097)
        var next = 258
        var bits = 9
        var prev = -1
        var bitBuf = 0
        var bitCnt = 0
        var p = 0
        val ec = if (early == 0) 0 else 1
        while (out.len < MAX_OUT) {
            while (bitCnt < bits && p < data.size) {
                bitBuf = (bitBuf shl 8) or (data[p++].toInt() and 0xFF)
                bitCnt += 8
            }
            if (bitCnt < bits) break
            val code = (bitBuf ushr (bitCnt - bits)) and ((1 shl bits) - 1)
            bitCnt -= bits
            bitBuf = bitBuf and ((1 shl bitCnt) - 1)
            if (code == 256) {
                next = 258
                bits = 9
                prev = -1
                continue
            }
            if (code == 257) break
            if (prev == -1) {
                if (code >= 256) break
                out.put(code)
                prev = code
                continue
            }
            if (code > next || (code == next && next >= 4096)) break
            if (next < 4096) {
                prefix[next] = prev
                suffix[next] = first[if (code < next) code else prev]
                first[next] = first[prev]
                length[next] = length[prev] + 1
                next++
            }
            var c = code
            val l = length[code]
            var k = l
            while (c >= 0 && k > 0) {
                tmp[--k] = suffix[c]
                c = prefix[c]
            }
            out.put(tmp, 0, l)
            prev = code
            bits = when {
                next + ec >= 2048 -> 12
                next + ec >= 1024 -> 11
                next + ec >= 512 -> 10
                else -> 9
            }
        }
        return out.toByteArray()
    }

    private fun predictor(data: ByteArray, p: PdfDict?): ByteArray {
        val pred = p?.int("Predictor") ?: 1
        if (p == null || pred <= 1) return data
        val colors = (p.int("Colors") ?: 1).coerceIn(1, 64)
        val bpc = p.int("BitsPerComponent") ?: 8
        val cols = (p.int("Columns") ?: 1).coerceIn(1, 1 shl 24)
        val rowBits = colors.toLong() * bpc * cols
        if (rowBits > (1L shl 28) || bpc !in intArrayOf(1, 2, 4, 8, 16)) return data
        val rowLen = ((rowBits + 7) / 8).toInt()
        val bpp = maxOf(1, colors * bpc / 8)
        if (pred >= 10) return pngPredictor(data, rowLen, bpp)
        if (pred == 2) return tiffPredictor(data, rowLen, colors, bpc)
        return data
    }

    private fun pngPredictor(data: ByteArray, rowLen: Int, bpp: Int): ByteArray {
        val stride = rowLen + 1
        val rows = (data.size + stride - 1) / stride
        val out = ByteArray(rows * rowLen)
        var total = 0
        for (r in 0 until rows) {
            val so = r * stride
            val avail = minOf(rowLen, data.size - so - 1)
            if (avail <= 0) break
            val ft = data[so].toInt()
            val o = r * rowLen
            for (i in 0 until avail) {
                val x = data[so + 1 + i].toInt() and 0xFF
                val a = if (i >= bpp) out[o + i - bpp].toInt() and 0xFF else 0
                val b = if (r > 0) out[o - rowLen + i].toInt() and 0xFF else 0
                val v = when (ft) {
                    1 -> x + a
                    2 -> x + b
                    3 -> x + ((a + b) shr 1)
                    4 -> {
                        val c = if (r > 0 && i >= bpp) out[o - rowLen + i - bpp].toInt() and 0xFF else 0
                        val pp = a + b - c
                        val pa = Math.abs(pp - a)
                        val pb = Math.abs(pp - b)
                        val pc = Math.abs(pp - c)
                        x + if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
                    }
                    else -> x
                }
                out[o + i] = v.toByte()
            }
            total = o + avail
        }
        return if (total == out.size) out else out.copyOf(total)
    }

    private fun tiffPredictor(data: ByteArray, rowLen: Int, colors: Int, bpc: Int): ByteArray {
        val out = data.copyOf()
        if (bpc == 8) {
            var o = 0
            while (o < out.size) {
                val e = minOf(o + rowLen, out.size)
                for (i in o + colors until e) out[i] = (out[i] + out[i - colors]).toByte()
                o += rowLen
            }
        } else if (bpc == 16) {
            var o = 0
            val step = colors * 2
            while (o < out.size) {
                val e = minOf(o + rowLen, out.size)
                var i = o + step
                while (i + 1 < e) {
                    val v = ((out[i].toInt() and 0xFF) shl 8 or (out[i + 1].toInt() and 0xFF)) +
                        ((out[i - step].toInt() and 0xFF) shl 8 or (out[i - step + 1].toInt() and 0xFF))
                    out[i] = (v shr 8).toByte()
                    out[i + 1] = v.toByte()
                    i += 2
                }
                o += rowLen
            }
        }
        return out
    }
}
