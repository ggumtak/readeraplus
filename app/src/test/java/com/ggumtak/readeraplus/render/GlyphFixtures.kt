package com.ggumtak.readeraplus.render

import java.io.ByteArrayOutputStream

/** Synthetic outline and cmap tables for [HollowGlyphsTest] (only what the scanner reads). */
internal object GlyphFixtures {
    private fun out(block: ByteArrayOutputStream.() -> Unit): ByteArray = ByteArrayOutputStream().apply(block).toByteArray()

    private fun ByteArrayOutputStream.u8(v: Int) = write(v and 0xFF)
    private fun ByteArrayOutputStream.u16(v: Int) = SfntBuilder.u16(this, v)
    private fun ByteArrayOutputStream.u32(v: Long) = SfntBuilder.u32(this, v)

    /** A cmap with a (3, 1) format 4 subtable (one segment per BMP mapping) and, for the rest, (3, 10) format 12. */
    fun cmap(map: Map<Int, Int>, uvs: ByteArray? = null): ByteArray {
        val bmp = map.filterKeys { it < 0xFFFF }.toSortedMap()
        val format4 = out {
            val seg = bmp.size + 1
            u16(4); u16(16 + 8 * seg); u16(0); u16(2 * seg); u16(2); u16(0); u16(2 * seg - 2)
            for (c in bmp.keys) u16(c)
            u16(0xFFFF)
            u16(0)
            for (c in bmp.keys) u16(c)
            u16(0xFFFF)
            for ((c, g) in bmp) u16((g - c) and 0xFFFF)
            u16(1)
            repeat(seg) { u16(0) }
        }
        val all = map.toSortedMap()
        val format12 = out {
            u16(12); u16(0); u32(16L + 12L * all.size); u32(0); u32(all.size.toLong())
            for ((c, g) in all) { u32(c.toLong()); u32(c.toLong()); u32(g.toLong()) }
        }
        val subtables = listOfNotNull(uvs?.let { Triple(0, 5, it) }, Triple(3, 1, format4), Triple(3, 10, format12))
        return out {
            u16(0); u16(subtables.size)
            var off = 4 + 8 * subtables.size
            for ((p, e, b) in subtables) { u16(p); u16(e); u32(off.toLong()); off += b.size }
            for ((_, _, b) in subtables) write(b)
        }
    }

    /** A format 14 subtable with one default variation sequence record (U+FE00 after U+8056). */
    fun uvs(): ByteArray = out {
        u16(14); u32(10L + 11L + 4L + 4L); u32(1)
        u8(0); u8(0xFE); u8(0x00); u32(21); u32(0) // varSelector U+FE00, defaultUVSOffset 21, no non-default
        u32(1); u8(0); u8(0x80); u8(0x56); u8(0) // one range: U+8056, additionalCount 0
    }

    /** head with the given loca format (0 short, 1 long). */
    fun head(longLoca: Boolean = false): ByteArray = ByteArray(54).also { if (longLoca) it[51] = 1 }

    fun maxp(numGlyphs: Int): ByteArray = out { u32(0x00005000L); u16(numGlyphs) }

    /** A one-point simple glyph (an outline, 19 bytes). */
    fun dot(): ByteArray = out {
        u16(1); u16(0); u16(0); u16(10); u16(10) // one contour, bbox
        u16(0) // endPtsOfContours
        u16(0) // no instructions
        u8(0x01) // on curve, 16-bit x and y
        u16(5); u16(5)
    }

    /** A simple glyph header without contours (12 bytes): draws nothing. */
    fun noContours(): ByteArray = out { repeat(5) { u16(0) }; u16(0) }

    /** glyf and short loca for [glyphs] (an empty array = an empty glyph). */
    fun glyfLoca(glyphs: List<ByteArray>): Pair<ByteArray, ByteArray> {
        val glyf = ByteArrayOutputStream()
        val loca = ByteArrayOutputStream()
        for (g in glyphs) {
            loca.u16(glyf.size() / 2)
            glyf.write(g)
            if (glyf.size() % 2 != 0) glyf.write(0)
        }
        loca.u16(glyf.size() / 2)
        return glyf.toByteArray() to loca.toByteArray()
    }

    /** A TrueType font: [glyphs] by glyph id, [map] code point → glyph id, extra tables. */
    fun trueType(glyphs: List<ByteArray>, map: Map<Int, Int>, extra: SfntBuilder.() -> Unit = {}): ByteArray {
        val (glyf, loca) = glyfLoca(glyphs)
        return SfntBuilder()
            .table("cmap", cmap(map))
            .table("head", head())
            .table("maxp", maxp(glyphs.size))
            .table("loca", loca)
            .table("glyf", glyf)
            .apply(extra)
            .build()
    }

    /** A CFF table (Name, Top DICT with CharStrings, empty String / Global Subr INDEXes, CharStrings). */
    fun cff(charStrings: List<ByteArray>): ByteArray {
        val top = out { u8(29); u32(25); u8(17) } // CharStrings at 25 (an int32 operand keeps the size fixed)
        return out {
            u8(1); u8(0); u8(4); u8(1)
            u16(1); u8(1); u8(1); u8(2); u8('A'.code) // Name INDEX
            u16(1); u8(1); u8(1); u8(1 + top.size); write(top) // Top DICT INDEX
            u16(0) // String INDEX
            u16(0) // Global Subr INDEX
            check(size() == 25)
            u16(charStrings.size); u8(2)
            var o = 1
            u16(o)
            for (cs in charStrings) { o += cs.size; u16(o) }
            for (cs in charStrings) write(cs)
        }
    }

    /** An OpenType CFF font: [charStrings] by glyph id, [map] code point → glyph id. */
    fun openType(charStrings: List<ByteArray>, map: Map<Int, Int>, extra: SfntBuilder.() -> Unit = {}): ByteArray =
        SfntBuilder(0x4F54544FL).table("cmap", cmap(map)).table("CFF ", cff(charStrings)).apply(extra).build()

    /** `endchar` alone, `<width> endchar`, and a glyph that draws (`rmoveto`, `rlineto`, `endchar`). */
    val ENDCHAR = byteArrayOf(14)
    val WIDTH_ENDCHAR = byteArrayOf(28, 0x03, 0xB6.toByte(), 14) // 950 endchar
    val DRAWN = byteArrayOf(139.toByte(), 139.toByte(), 21, 239.toByte(), 239.toByte(), 5, 14)

    /** A broken GSUB: [features] default-on features that all share one feature table listing 65,535 lookups. */
    fun gsubSharingOneHugeFeature(features: Int): ByteArray {
        val featureList = out {
            u16(features)
            repeat(features) { for (c in "liga") u8(c.code); u16(2 + 6 * features) }
            u16(0); u16(0xFFFF)
            repeat(0xFFFF) { u16(0) }
        }
        return out {
            u16(1); u16(0)
            u16(10); u16(12); u16(12) // a script list of nothing; the lookup list read where the features are
            u16(0)
            write(featureList)
        }
    }

    /** A GSUB whose single feature [tag] runs one single-substitution lookup covering [glyphs]. */
    fun gsub(tag: String, glyphs: List<Int>): ByteArray {
        val coverage = out { u16(1); u16(glyphs.size); for (g in glyphs.sorted()) u16(g) }
        val single = out { u16(1); u16(6); u16(1); write(coverage) } // format 1, coverage at 6, delta 1
        return gsubLookup(tag, 1, single)
    }

    /** A format 1 Coverage of [glyphs]. */
    fun coverage(vararg glyphs: Int): ByteArray = out { u16(1); u16(glyphs.size); for (g in glyphs.sorted()) u16(g) }

    /** Type 4: one ligature of [components] (the first in the Coverage). */
    fun ligature(components: List<Int>): ByteArray = out {
        val cov = coverage(components[0])
        val lig = out { u16(1); u16(components.size); for (c in components.drop(1)) u16(c) }
        u16(1); u16(8 + 4 + lig.size); u16(1); u16(8) // format 1, coverage after the ligature, one set at 8
        u16(1); u16(4) // the set: one ligature at +4
        write(lig)
        write(cov)
    }

    /** Type 6 format 1: one rule, [backtrack] · [input] (the first in the Coverage) · [lookahead], no lookups. */
    fun chainRule(backtrack: List<Int>, input: List<Int>, lookahead: List<Int>): ByteArray = out {
        val rule = out {
            u16(backtrack.size); for (g in backtrack) u16(g)
            u16(input.size); for (g in input.drop(1)) u16(g)
            u16(lookahead.size); for (g in lookahead) u16(g)
            u16(0)
        }
        u16(1); u16(8 + 4 + rule.size); u16(1); u16(8)
        u16(1); u16(4)
        write(rule)
        write(coverage(input[0]))
    }

    /** Type 5 format 2 over [first] with a format 2 ClassDef giving [classed] (a range) class 1; no rules. */
    fun classContext(first: Int, classed: IntRange): ByteArray = out {
        u16(2); u16(8 + 10); u16(8); u16(0)
        u16(2); u16(1); u16(classed.first); u16(classed.last); u16(1)
        write(coverage(first))
    }

    /** Type 6 format 2 over [first]: no backtrack or lookahead ClassDef, an input one of format 1 giving [classed] class 1. */
    fun chainClassContext(first: Int, classed: Int): ByteArray = out {
        u16(2); u16(14 + 8); u16(0); u16(14); u16(0); u16(0); u16(0) // 12-byte header, 2 of padding, the ClassDef at 14
        u16(1); u16(classed); u16(1); u16(1) // format 1: one glyph, class 1
        write(coverage(first))
    }

    /** A GSUB whose single feature [tag] runs one lookup of [type] with [subtable]. */
    fun gsubLookup(tag: String, type: Int, subtable: ByteArray): ByteArray {
        val lookup = out { u16(type); u16(0); u16(1); u16(8); write(subtable) }
        val lookupList = out { u16(1); u16(4); write(lookup) }
        val feature = out { u16(0); u16(1); u16(0) }
        val featureList = out { u16(1); for (c in tag) u8(c.code); u16(8); write(feature) }
        val scriptList = out { u16(0) }
        return out {
            u16(1); u16(0)
            val fl = 10 + scriptList.size
            u16(10); u16(fl); u16(fl + featureList.size)
            write(scriptList); write(featureList); write(lookupList)
        }
    }
}
