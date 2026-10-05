package com.ggumtak.readeraplus.render

import java.io.ByteArrayOutputStream
import java.io.RandomAccessFile
import java.util.BitSet

/**
 * Characters a font claims but cannot draw (pure, unit-tested).
 *
 * Some fonts map a character to a glyph without an outline: Naver's NanumMyeongjo OTF maps all 4,888 KS X 1001 Hanja
 * (and 、 。) to blank Adobe-Korea1 CIDs; MaruBuri, SUIT and 학교안심 바른바탕 do it for the Hangul syllables outside
 * KS X 1001 (똠, 됬, 햏 …), 나눔바른고딕 for ‐ and ∥, 이롭게 바탕 for €. Minikin picks a font by its cmap alone (the
 * first family that maps the code point wins) and Paint.hasGlyph sees a real glyph id, so the system font is never
 * tried: the page keeps the blank glyph's advance and draws nothing ("성(   )과 속(   )", user report 2026-10-05).
 *
 * [scan] lists those mappings in a font's first face: TrueType glyphs whose `glyf` entry is empty or has no contour,
 * CFF glyphs whose charstring is only `[width] endchar`. Characters that are blank by design ([needsInk]) and glyphs
 * a default GSUB feature substitutes (the input of jamo composition or a ligature may be a placeholder) keep their
 * mapping. [cmapWithout] builds a `cmap` without the rest and [patch] puts it into a copy of the file ([FontRepairs]):
 * the system font then draws those characters, and measuring and drawing agree because both use that one typeface.
 * Faces with colour or bitmap glyphs (an empty outline is right there) and CFF2 faces are left alone (null).
 */
internal object HollowGlyphs {
    /** What [scan] found in a font's first face. */
    class Scan(
        /** Where the face's table directory starts: 0, or its offset in a collection. */
        val faceOffset: Long,
        /** Index of the face's `cmap` record in that directory. */
        val cmapIndex: Int,
        /** The face's Unicode mappings, by code point, and their glyphs. */
        val codePoints: IntArray,
        val glyphs: IntArray,
        /** The code points to leave to the system font (sorted); empty when the face draws all it maps. */
        val hollow: IntArray,
        /** The (0, 5) format 14 subtable (variation sequences) as it was; null without one. */
        val variations: ByteArray?,
    )

    private const val TAG_CMAP = 0x636D6170L
    private const val TAG_HEAD = 0x68656164L
    private const val TAG_MAXP = 0x6D617870L
    private const val TAG_LOCA = 0x6C6F6361L
    private const val TAG_GLYF = 0x676C7966L
    private const val TAG_CFF = 0x43464620L
    private const val TAG_GSUB = 0x47535542L

    /** COLR, sbix, CBDT, SVG, EBDT (glyphs painted without, or over, the outline) and CFF2 (not read here). */
    private val LEFT_ALONE = longArrayOf(0x434F4C52L, 0x73626978L, 0x43424454L, 0x53564720L, 0x45424454L, 0x43464632L)

    /**
     * GSUB features a text engine applies only on request (alternates, numerals, widths, vertical forms, Hanja ↔
     * Hangul …): a glyph only these substitute is not a placeholder. Every other feature counts as default-on.
     */
    private val OPT_IN = setOf(
        "aalt", "salt", "swsh", "cswh", "titl", "hist", "hlig", "dlig", "smcp", "c2sc", "pcap", "c2pc", "unic",
        "onum", "lnum", "pnum", "tnum", "frac", "afrc", "dnom", "numr", "sinf", "subs", "sups", "ordn", "zero",
        "case", "hwid", "fwid", "pwid", "qwid", "twid", "vert", "vrt2", "vkna", "vrtr", "ruby", "hngl", "hanj",
        "hkna", "hojo", "jp78", "jp83", "jp90", "jp04", "nlck", "expt", "trad", "smpl", "tnam", "ital", "ornm",
        "nalt", "mgrk",
    )

    private const val MAX_TABLES = 1024
    private const val MAX_CMAP = 16L shl 20
    private const val MAX_GSUB = 16L shl 20
    /** Lookup indices and Coverage entries read from one GSUB (a CJK font's whole `locl` · `vert` is far less). */
    private const val MAX_GSUB_WORK = 8_000_000L
    /** Mappings read from one cmap (a Last Resort font maps every code point: not a reading font). */
    private const val MAX_MAPPINGS = 400_000
    /** Code points one cmap subtable can cover without overlapping itself. */
    private const val MAX_CODE_POINTS = 0x110000
    /** Longest `[width] endchar` charstring: a 5-byte number and the operator. */
    private const val MAX_BLANK_CHARSTRING = 6
    /** A TrueType glyph this short may be a header without contours. */
    private const val SMALL_GLYF = 16L

    private const val OP_CHARSTRINGS = 17
    private const val OP_CHARSTRING_TYPE = 0x0C06
    private const val CS_ENDCHAR = 14

    /** Scans [src]'s first face; null when it can't be read or is left alone (see the class). Never throws. */
    fun scan(src: SfntSource): Scan? = try {
        scanFace(src)
    } catch (e: Exception) {
        null
    } catch (e: OutOfMemoryError) {
        null
    }

    /**
     * False for characters that are blank by design, whose empty glyph is right: spaces, controls, format characters
     * (ZWJ, soft hyphen …), combining and enclosing marks (variation selectors among them), the Hangul fillers, the
     * blank Braille pattern, and code points without a character to draw (private use, unassigned, surrogates).
     */
    fun needsInk(cp: Int): Boolean {
        if (cp == 0x115F || cp == 0x1160 || cp == 0x3164 || cp == 0xFFA0 || cp == 0x2800) return false
        return when (Character.getType(cp).toByte()) {
            Character.SPACE_SEPARATOR, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR, Character.CONTROL,
            Character.FORMAT, Character.NON_SPACING_MARK, Character.ENCLOSING_MARK, Character.PRIVATE_USE,
            Character.UNASSIGNED, Character.SURROGATE -> false
            else -> true
        }
    }

    /**
     * A `cmap` table with [scan]'s mappings except [Scan.hollow]: (3, 10) format 12 for all of them, (3, 1) format 4
     * for the BMP when it fits in one (64 KB), and the variation sequences (0, 5) as they were.
     */
    fun cmapWithout(scan: Scan): ByteArray {
        val n = scan.codePoints.size
        val cps = IntArray(n)
        val gids = IntArray(n)
        var kept = 0
        var d = 0
        val drop = scan.hollow
        for (k in 0 until n) {
            val c = scan.codePoints[k]
            while (d < drop.size && drop[d] < c) d++
            if (d < drop.size && drop[d] == c) continue
            cps[kept] = c
            gids[kept] = scan.glyphs[k]
            kept++
        }
        val subtables = ArrayList<Triple<Int, Int, ByteArray>>(3)
        scan.variations?.let { subtables.add(Triple(0, 5, it)) }
        format4(cps, gids, kept)?.let { subtables.add(Triple(3, 1, it)) }
        subtables.add(Triple(3, 10, format12(cps, gids, kept)))
        val out = ByteArrayOutputStream()
        u16(out, 0)
        u16(out, subtables.size)
        var offset = 4 + 8 * subtables.size
        for ((platform, encoding, bytes) in subtables) {
            u16(out, platform)
            u16(out, encoding)
            u32(out, offset.toLong())
            offset += padded(bytes.size)
        }
        for ((_, _, bytes) in subtables) {
            out.write(bytes)
            repeat(padded(bytes.size) - bytes.size) { out.write(0) }
        }
        return out.toByteArray()
    }

    /**
     * Turns [file], a byte-for-byte copy of the font [scan] read, into that font with [cmap]: the table goes at the end
     * (4-byte aligned) and the face's `cmap` record points at it. The old table, the other faces of a collection and
     * `head.checkSumAdjustment` stay as they were (no Android font loader checks the whole-file sum).
     */
    fun patch(file: RandomAccessFile, scan: Scan, cmap: ByteArray) {
        val end = file.length()
        val at = (end + 3) and 3L.inv()
        file.seek(end)
        for (i in end until at) file.write(0)
        file.write(cmap)
        for (i in cmap.size until padded(cmap.size)) file.write(0)
        file.seek(scan.faceOffset + 12 + 16L * scan.cmapIndex + 4)
        file.writeInt(checksum(cmap).toInt())
        file.writeInt(at.toInt())
        file.writeInt(cmap.size)
    }

    /** The sfnt table checksum: the sum of the big-endian 32-bit words, zero-padded. */
    fun checksum(b: ByteArray): Long {
        var sum = 0L
        var i = 0
        while (i < b.size) {
            var w = 0L
            for (j in 0 until 4) w = (w shl 8) or (if (i + j < b.size) (b[i + j].toLong() and 0xFF) else 0L)
            sum = (sum + w) and 0xFFFFFFFFL
            i += 4
        }
        return sum
    }

    /**
     * True when a Type 2 charstring draws nothing: its first operator is `endchar` with at most the width before it
     * (four operands would be the accented-character form, which draws two glyphs).
     */
    fun isBlankCharString(cs: ByteArray, len: Int): Boolean {
        var i = 0
        var operands = 0
        while (i < len) {
            val b0 = cs[i].toInt() and 0xFF
            when {
                b0 == 28 -> i += 3
                b0 in 32..246 -> i += 1
                b0 in 247..254 -> i += 2
                b0 == 255 -> i += 5
                else -> return b0 == CS_ENDCHAR && operands <= 1
            }
            operands++
        }
        return false
    }

    // ------------------------------------------------------------------ the face

    private class Table(val offset: Long, val length: Long)

    private fun scanFace(src: SfntSource): Scan? {
        val hdr = ByteArray(12)
        if (!src.read(0, hdr, 0, 12)) return null
        var face = 0L
        var tag = u32(hdr, 0)
        if (tag == SfntReader.TAG_TTCF) {
            val first = ByteArray(4)
            if (u32(hdr, 8) < 1 || !src.read(12, first, 0, 4)) return null
            face = u32(first, 0)
            if (!src.read(face, hdr, 0, 12)) return null
            tag = u32(hdr, 0)
        }
        if (tag != SfntReader.TAG_TRUE_TYPE && tag != SfntReader.TAG_OTTO && tag != SfntReader.TAG_TRUE) return null
        val numTables = u16(hdr, 4)
        if (numTables < 1 || numTables > MAX_TABLES) return null
        val dir = ByteArray(numTables * 16)
        if (!src.read(face + 12, dir, 0, dir.size)) return null
        val tables = HashMap<Long, Table>()
        var cmapIndex = -1
        for (i in 0 until numTables) {
            val t = u32(dir, i * 16)
            if (t in LEFT_ALONE) return null
            val o = u32(dir, i * 16 + 8)
            val l = u32(dir, i * 16 + 12)
            if (o + l > src.size) continue
            tables[t] = Table(o, l)
            if (t == TAG_CMAP) cmapIndex = i
        }
        val cmapTable = tables[TAG_CMAP] ?: return null
        val cmap = read(src, cmapTable, MAX_CMAP) ?: return null
        val mappings = unicodeMappings(cmap) ?: return null
        val n = mappings.size
        val cps = IntArray(n)
        val gids = IntArray(n)
        val used = BitSet()
        for (k in 0 until n) {
            cps[k] = (mappings[k] ushr 32).toInt()
            gids[k] = (mappings[k] and 0xFFFF).toInt()
            used.set(gids[k])
        }
        val empty = when {
            tables.containsKey(TAG_GLYF) -> emptyTrueTypeGlyphs(src, tables, used)
            tables.containsKey(TAG_CFF) -> emptyCffGlyphs(src, tables.getValue(TAG_CFF), used)
            else -> null
        } ?: return null
        var candidates = 0
        for (k in 0 until n) if (empty.get(gids[k]) && needsInk(cps[k])) candidates++
        var hollow = IntArray(0)
        if (candidates > 0) {
            val substituted = substitutedGlyphs(src, tables[TAG_GSUB]) ?: return null
            hollow = IntArray(candidates)
            var h = 0
            for (k in 0 until n) {
                if (empty.get(gids[k]) && needsInk(cps[k]) && !substituted.get(gids[k])) hollow[h++] = cps[k]
            }
            hollow = hollow.copyOf(h)
        }
        return Scan(face, cmapIndex, cps, gids, hollow, variations(cmap))
    }

    private fun read(src: SfntSource, t: Table, max: Long): ByteArray? {
        if (t.length > max) return null
        val b = ByteArray(t.length.toInt())
        return if (src.read(t.offset, b, 0, b.size)) b else null
    }

    // ------------------------------------------------------------------ cmap

    /** The best Unicode subtable's mappings as sorted `codePoint shl 32 or glyph` (glyph 0 left out), or null. */
    private fun unicodeMappings(t: ByteArray): LongArray? {
        val count = u16(t, 2)
        var best = -1
        var bestRank = 0
        for (i in 0 until count) {
            val r = 4 + 8 * i
            if (r + 8 > t.size) break
            val o = u32(t, r + 4)
            if (o + 4 > t.size) continue
            val at = o.toInt()
            val rank = subtableRank(u16(t, r), u16(t, r + 2), u16(t, at))
            if (rank > bestRank) {
                bestRank = rank
                best = at
            }
        }
        if (best < 0) return null
        val out = Mappings()
        val ok = if (u16(t, best) == 12) readFormat12(t, best, out) else readFormat4(t, best, out)
        return if (ok && out.size > 0) out.sorted() else null
    }

    private fun subtableRank(platform: Int, encoding: Int, format: Int): Int = when {
        format == 12 && platform == 3 && encoding == 10 -> 4
        format == 12 && platform == 0 -> 3
        format == 4 && platform == 3 && encoding == 1 -> 2
        format == 4 && platform == 0 -> 1
        else -> 0
    }

    private fun readFormat4(t: ByteArray, at: Int, out: Mappings): Boolean {
        val segX2 = u16(t, at + 6)
        val ends = at + 14
        val starts = ends + segX2 + 2
        val deltas = starts + segX2
        val ranges = deltas + segX2
        if (ranges + segX2 > t.size) return false
        var visited = 0
        for (s in 0 until segX2 / 2) {
            val start = u16(t, starts + 2 * s)
            val end = minOf(u16(t, ends + 2 * s), 0xFFFE)
            val delta = u16(t, deltas + 2 * s)
            val rangeOffset = u16(t, ranges + 2 * s)
            visited += maxOf(0, end - start + 1)
            if (visited > MAX_CODE_POINTS) return false // overlapping segments: a broken table
            for (c in start..end) {
                var g = if (rangeOffset == 0) c + delta else u16(t, ranges + 2 * s + rangeOffset + 2 * (c - start))
                if (rangeOffset != 0 && g != 0) g += delta
                g = g and 0xFFFF
                if (g != 0 && !out.add(c, g)) return false
            }
        }
        return true
    }

    private fun readFormat12(t: ByteArray, at: Int, out: Mappings): Boolean {
        val groups = u32(t, at + 12)
        if (groups > (t.size - at - 16L) / 12) return false
        var visited = 0L
        for (k in 0 until groups.toInt()) {
            val p = at + 16 + 12 * k
            val start = u32(t, p)
            val end = minOf(u32(t, p + 4), 0x10FFFFL)
            val first = u32(t, p + 8)
            if (start > end) continue
            visited += end - start + 1
            // Never drop part of the font's coverage: too many mappings, or overlapping groups, leave the font alone.
            if (end - start >= MAX_MAPPINGS || visited > MAX_CODE_POINTS) return false
            for (c in start..end) {
                val g = first + (c - start)
                if (g in 1..0xFFFF && !out.add(c.toInt(), g.toInt())) return false
            }
        }
        return true
    }

    /** The (0, 5) format 14 subtable's bytes, or null. */
    private fun variations(t: ByteArray): ByteArray? {
        val count = u16(t, 2)
        for (i in 0 until count) {
            val r = 4 + 8 * i
            if (r + 8 > t.size) break
            if (u16(t, r) != 0 || u16(t, r + 2) != 5) continue
            val at = u32(t, r + 4)
            if (at + 10 > t.size || u16(t, at.toInt()) != 14) continue
            val len = u32(t, at.toInt() + 2)
            if (at + len > t.size) continue
            return t.copyOfRange(at.toInt(), (at + len).toInt())
        }
        return null
    }

    private class Mappings {
        var size = 0
        private var items = LongArray(1024)

        fun add(cp: Int, glyph: Int): Boolean {
            if (size >= MAX_MAPPINGS) return false
            if (size == items.size) items = items.copyOf(size * 2)
            items[size++] = (cp.toLong() shl 32) or glyph.toLong()
            return true
        }

        /** Sorted by code point; a code point mapped twice keeps its first glyph. */
        fun sorted(): LongArray {
            val a = items.copyOf(size)
            a.sort()
            var n = 0
            for (v in a) if (n == 0 || (a[n - 1] ushr 32) != (v ushr 32)) a[n++] = v
            return a.copyOf(n)
        }
    }

    private fun format4(cps: IntArray, gids: IntArray, n: Int): ByteArray? {
        val starts = ArrayList<Int>()
        val ends = ArrayList<Int>()
        val deltas = ArrayList<Int>()
        var k = 0
        while (k < n && cps[k] < 0xFFFF) {
            val s = k
            while (k + 1 < n && cps[k + 1] == cps[k] + 1 && cps[k + 1] < 0xFFFF &&
                gids[k + 1] - cps[k + 1] == gids[s] - cps[s]
            ) k++
            starts.add(cps[s])
            ends.add(cps[k])
            deltas.add((gids[s] - cps[s]) and 0xFFFF)
            k++
        }
        starts.add(0xFFFF)
        ends.add(0xFFFF)
        deltas.add(1)
        val seg = starts.size
        val length = 16 + 8 * seg
        if (length > 0xFFFF) return null
        var pow = 1
        var log = 0
        while (pow * 2 <= seg) {
            pow *= 2
            log++
        }
        val out = ByteArrayOutputStream(length)
        u16(out, 4)
        u16(out, length)
        u16(out, 0)
        u16(out, 2 * seg)
        u16(out, 2 * pow)
        u16(out, log)
        u16(out, 2 * seg - 2 * pow)
        for (e in ends) u16(out, e)
        u16(out, 0)
        for (s in starts) u16(out, s)
        for (d in deltas) u16(out, d)
        repeat(seg) { u16(out, 0) }
        return out.toByteArray()
    }

    private fun format12(cps: IntArray, gids: IntArray, n: Int): ByteArray {
        val groups = ByteArrayOutputStream()
        var count = 0
        var k = 0
        while (k < n) {
            val s = k
            while (k + 1 < n && cps[k + 1] == cps[k] + 1 && gids[k + 1] == gids[k] + 1) k++
            u32(groups, cps[s].toLong())
            u32(groups, cps[k].toLong())
            u32(groups, gids[s].toLong())
            count++
            k++
        }
        val out = ByteArrayOutputStream(16 + groups.size())
        u16(out, 12)
        u16(out, 0)
        u32(out, 16L + 12L * count)
        u32(out, 0)
        u32(out, count.toLong())
        groups.writeTo(out)
        return out.toByteArray()
    }

    // ------------------------------------------------------------------ outlines

    private fun emptyTrueTypeGlyphs(src: SfntSource, tables: Map<Long, Table>, used: BitSet): BitSet? {
        val head = read(src, tables[TAG_HEAD] ?: return null, 1024) ?: return null
        val maxp = read(src, tables[TAG_MAXP] ?: return null, 1024) ?: return null
        val locaTable = tables[TAG_LOCA] ?: return null
        val glyf = tables[TAG_GLYF] ?: return null
        if (head.size < 54 || maxp.size < 6) return null
        val long = u16(head, 50) == 1
        val numGlyphs = u16(maxp, 4)
        val entry = if (long) 4 else 2
        if (numGlyphs == 0 || locaTable.length < (numGlyphs + 1L) * entry) return null
        val loca = ByteArray((numGlyphs + 1) * entry)
        if (!src.read(locaTable.offset, loca, 0, loca.size)) return null
        fun at(g: Int): Long = if (long) u32(loca, 4 * g) else 2L * u16(loca, 2 * g)
        val empty = BitSet()
        val header = ByteArray(2)
        var g = used.nextSetBit(0)
        while (g in 0 until numGlyphs) {
            val a = at(g)
            val b = at(g + 1)
            if (b <= a || b > glyf.length) {
                empty.set(g)
            } else if (b - a <= SMALL_GLYF && src.read(glyf.offset + a, header, 0, 2) && s16(header, 0) == 0) {
                empty.set(g) // a header without contours
            }
            g = used.nextSetBit(g + 1)
        }
        return empty
    }

    private fun emptyCffGlyphs(src: SfntSource, cff: Table, used: BitSet): BitSet? {
        val base = cff.offset
        val h = ByteArray(4)
        if (!src.read(base, h, 0, 4) || h[0].toInt() != 1) return null
        val topIndex = indexEnd(src, base + (h[2].toInt() and 0xFF)) // past the Name INDEX
        if (topIndex < 0) return null
        val top = indexEntry(src, topIndex, 0) ?: return null
        if ((dictValue(top, OP_CHARSTRING_TYPE) ?: 2L) != 2L) return null
        val charStrings = dictValue(top, OP_CHARSTRINGS) ?: return null
        if (charStrings <= 0 || charStrings >= cff.length) return null
        val at = base + charStrings
        if (!src.read(at, h, 0, 3)) return null
        val count = u16(h, 0)
        val offSize = h[2].toInt() and 0xFF
        if (count == 0 || offSize !in 1..4) return null
        val offs = ByteArray((count + 1) * offSize)
        if (!src.read(at + 3, offs, 0, offs.size)) return null
        val data = at + 3 + offs.size - 1
        val cs = ByteArray(MAX_BLANK_CHARSTRING)
        val empty = BitSet()
        var g = used.nextSetBit(0)
        while (g in 0 until count) {
            val a = offset(offs, g * offSize, offSize)
            val len = offset(offs, (g + 1) * offSize, offSize) - a
            if (len <= 0) {
                empty.set(g)
            } else if (len <= MAX_BLANK_CHARSTRING && src.read(data + a, cs, 0, len.toInt()) &&
                isBlankCharString(cs, len.toInt())
            ) {
                empty.set(g)
            }
            g = used.nextSetBit(g + 1)
        }
        return empty
    }

    /** Position right after the CFF INDEX at [pos], or -1. */
    private fun indexEnd(src: SfntSource, pos: Long): Long {
        val h = ByteArray(4)
        if (!src.read(pos, h, 0, 2)) return -1
        val count = u16(h, 0)
        if (count == 0) return pos + 2
        if (!src.read(pos + 2, h, 0, 1)) return -1
        val offSize = h[0].toInt() and 0xFF
        if (offSize !in 1..4 || !src.read(pos + 3 + count.toLong() * offSize, h, 0, offSize)) return -1
        return pos + 3 + (count + 1L) * offSize + offset(h, 0, offSize) - 1
    }

    /** Entry [i] of the CFF INDEX at [pos] (at most 64 KB), or null. */
    private fun indexEntry(src: SfntSource, pos: Long, i: Int): ByteArray? {
        val h = ByteArray(8)
        if (!src.read(pos, h, 0, 3)) return null
        val count = u16(h, 0)
        val offSize = h[2].toInt() and 0xFF
        if (i >= count || offSize !in 1..4) return null
        if (!src.read(pos + 3 + i.toLong() * offSize, h, 0, 2 * offSize)) return null
        val a = offset(h, 0, offSize)
        val len = offset(h, offSize, offSize) - a
        if (a < 1 || len < 0 || len > 0xFFFF) return null
        val b = ByteArray(len.toInt())
        val data = pos + 3 + (count + 1L) * offSize - 1
        return if (src.read(data + a, b, 0, b.size)) b else null
    }

    /**
     * The integer operand of operator [op] in a CFF DICT ([op] is `0x0C00 or b1` for the two-byte operators), or
     * null when the DICT doesn't set it or can't be read.
     */
    fun dictValue(d: ByteArray, op: Int): Long? {
        var i = 0
        var last: Long? = null
        while (i < d.size) {
            val b0 = d[i].toInt() and 0xFF
            when {
                b0 <= 21 -> {
                    val o = if (b0 == 12) 0x0C00 or (if (i + 1 < d.size) d[i + 1].toInt() and 0xFF else return null) else b0
                    if (o == op) return last
                    last = null
                    i += if (b0 == 12) 2 else 1
                }
                b0 == 28 -> {
                    if (i + 3 > d.size) return null
                    last = s16(d, i + 1).toLong()
                    i += 3
                }
                b0 == 29 -> {
                    if (i + 5 > d.size) return null
                    last = u32(d, i + 1).toInt().toLong()
                    i += 5
                }
                b0 == 30 -> { // a real number: its nibbles end with 0xF
                    i++
                    while (i < d.size) {
                        val b = d[i++].toInt() and 0xFF
                        if ((b and 0x0F) == 0x0F || (b ushr 4) == 0x0F) break
                    }
                    last = 0L
                }
                b0 in 32..246 -> {
                    last = (b0 - 139).toLong()
                    i++
                }
                b0 in 247..254 -> {
                    if (i + 2 > d.size) return null
                    val b1 = d[i + 1].toInt() and 0xFF
                    last = (if (b0 <= 250) (b0 - 247) * 256 + b1 + 108 else -(b0 - 251) * 256 - b1 - 108).toLong()
                    i += 2
                }
                else -> return null
            }
        }
        return null
    }

    // ------------------------------------------------------------------ GSUB

    /**
     * Glyphs a default-on GSUB feature may substitute: the Coverage of every lookup a feature outside [OPT_IN] uses
     * (for the contextual format 3, all its coverages). None without a GSUB; null when the table can't be read.
     */
    private fun substitutedGlyphs(src: SfntSource, gsub: Table?): BitSet? {
        if (gsub == null) return BitSet()
        val t = read(src, gsub, MAX_GSUB) ?: return null
        return if (t.size < 10) null else GsubCoverage(t).read()
    }

    /**
     * One GSUB's default coverage. Offsets are shared freely, so a broken table could make every feature point at the
     * same 65,535 lookups: past [MAX_GSUB_WORK] entries it throws, and [scan] leaves the font alone.
     */
    private class GsubCoverage(private val t: ByteArray) {
        private val out = BitSet()
        private var work = 0L

        fun read(): BitSet {
            val featureList = u16(t, 6)
            val lookupList = u16(t, 8)
            val lookups = BitSet()
            for (k in 0 until u16(t, featureList)) {
                val r = featureList + 2 + 6 * k
                val tag = tagName(u32(t, r))
                if (tag in OPT_IN || isNumberedOptIn(tag)) continue
                val feature = featureList + u16(t, r + 4)
                val n = u16(t, feature + 2)
                spend(n)
                for (j in 0 until n) lookups.set(u16(t, feature + 4 + 2 * j))
            }
            val count = u16(t, lookupList)
            var li = lookups.nextSetBit(0)
            while (li in 0 until count) {
                val lookup = lookupList + u16(t, lookupList + 2 + 2 * li)
                val type = u16(t, lookup)
                for (s in 0 until u16(t, lookup + 4)) {
                    var sub = lookup + u16(t, lookup + 6 + 2 * s)
                    var subType = type
                    if (subType == 7) { // Extension: the real subtable's type and 32-bit offset
                        subType = u16(t, sub + 2)
                        val ext = u32(t, sub + 4)
                        if (sub + ext >= t.size) continue
                        sub += ext.toInt()
                    }
                    coverages(sub, subType)
                }
                li = lookups.nextSetBit(li + 1)
            }
            return out
        }

        private fun coverages(sub: Int, type: Int) {
            if ((type == 5 || type == 6) && u16(t, sub) == 3) {
                if (type == 5) {
                    for (k in 0 until u16(t, sub + 2)) coverage(sub + u16(t, sub + 6 + 2 * k))
                } else {
                    var p = sub + 2
                    repeat(3) { // backtrack, input, lookahead
                        val n = u16(t, p)
                        for (k in 0 until n) coverage(sub + u16(t, p + 2 + 2 * k))
                        p += 2 + 2 * n
                    }
                }
            } else {
                coverage(sub + u16(t, sub + 2))
            }
        }

        private fun coverage(at: Int) {
            val n = u16(t, at + 2)
            when (u16(t, at)) {
                1 -> {
                    spend(n)
                    for (k in 0 until n) out.set(u16(t, at + 4 + 2 * k))
                }
                2 -> {
                    spend(n)
                    for (k in 0 until n) {
                        val r = at + 4 + 6 * k
                        val first = u16(t, r)
                        val last = u16(t, r + 2)
                        if (first <= last) out.set(first, last + 1)
                    }
                }
            }
        }

        private fun spend(n: Int) {
            work += n + 1
            if (work > MAX_GSUB_WORK) throw IllegalStateException("GSUB too large")
        }
    }

    private fun tagName(tag: Long): String {
        val c = CharArray(4) { ((tag ushr (24 - 8 * it)) and 0xFF).toInt().toChar() }
        return String(c)
    }

    /** Stylistic sets ss01–ss20 and character variants cv01–cv99. */
    private fun isNumberedOptIn(tag: String): Boolean =
        (tag.startsWith("ss") || tag.startsWith("cv")) && tag[2].isDigit() && tag[3].isDigit()

    // ------------------------------------------------------------------ bytes

    private fun padded(n: Int): Int = (n + 3) and 3.inv()

    private fun u16(b: ByteArray, p: Int): Int = SfntReader.u16(b, p)

    private fun u32(b: ByteArray, p: Int): Long = SfntReader.u32(b, p)

    private fun s16(b: ByteArray, p: Int): Int = u16(b, p).toShort().toInt()

    /** A big-endian unsigned number of [size] bytes at [p]. */
    private fun offset(b: ByteArray, p: Int, size: Int): Long {
        var v = 0L
        for (i in 0 until size) v = (v shl 8) or (b[p + i].toLong() and 0xFF)
        return v
    }

    private fun u16(out: ByteArrayOutputStream, v: Int) {
        out.write((v ushr 8) and 0xFF)
        out.write(v and 0xFF)
    }

    private fun u32(out: ByteArrayOutputStream, v: Long) {
        out.write(((v ushr 24) and 0xFF).toInt())
        out.write(((v ushr 16) and 0xFF).toInt())
        out.write(((v ushr 8) and 0xFF).toInt())
        out.write((v and 0xFF).toInt())
    }
}
