package com.ggumtak.readeraplus.render

import java.io.ByteArrayOutputStream

/** Builds minimal synthetic sfnt fonts (only the tables the reader looks at) for tests. */
internal class SfntBuilder(private val signature: Long = 0x00010000L) {
    private val tables = sortedMapOf<String, ByteArray>()

    fun table(tag: String, bytes: ByteArray): SfntBuilder {
        require(tag.length == 4)
        tables[tag] = bytes
        return this
    }

    fun build(): ByteArray {
        val n = tables.size
        val out = ByteArrayOutputStream()
        u32(out, signature)
        u16(out, n)
        u16(out, 16) // searchRange (not validated by the reader)
        u16(out, 0)
        u16(out, 0)
        var offset = 12 + 16 * n
        val offsets = ArrayList<Int>()
        for ((_, b) in tables) {
            offsets.add(offset)
            offset += (b.size + 3) and 3.inv()
        }
        var i = 0
        for ((tag, b) in tables) {
            for (c in tag) out.write(c.code)
            u32(out, 0) // checksum
            u32(out, offsets[i].toLong())
            u32(out, b.size.toLong())
            i++
        }
        for ((_, b) in tables) {
            out.write(b)
            repeat(((b.size + 3) and 3.inv()) - b.size) { out.write(0) }
        }
        return out.toByteArray()
    }

    companion object {
        fun u16(out: ByteArrayOutputStream, v: Int) {
            out.write((v ushr 8) and 0xFF)
            out.write(v and 0xFF)
        }

        fun u32(out: ByteArrayOutputStream, v: Long) {
            out.write(((v ushr 24) and 0xFF).toInt())
            out.write(((v ushr 16) and 0xFF).toInt())
            out.write(((v ushr 8) and 0xFF).toInt())
            out.write((v and 0xFF).toInt())
        }

        /** Wraps complete fonts into a TrueType collection. */
        fun ttc(vararg fonts: ByteArray): ByteArray {
            val out = ByteArrayOutputStream()
            u32(out, 0x74746366L)
            u16(out, 1)
            u16(out, 0)
            u32(out, fonts.size.toLong())
            var off = 12 + 4 * fonts.size
            for (f in fonts) {
                u32(out, off.toLong())
                off += f.size
            }
            // Each font's table offsets are relative to the file start: rebase them.
            var base = 12 + 4 * fonts.size
            for (f in fonts) {
                out.write(rebase(f, base))
                base += f.size
            }
            return out.toByteArray()
        }

        private fun rebase(font: ByteArray, base: Int): ByteArray {
            val b = font.copyOf()
            val n = ((b[4].toInt() and 0xFF) shl 8) or (b[5].toInt() and 0xFF)
            for (i in 0 until n) {
                val p = 12 + i * 16 + 8
                val v = ((b[p].toLong() and 0xFF) shl 24) or ((b[p + 1].toLong() and 0xFF) shl 16) or
                    ((b[p + 2].toLong() and 0xFF) shl 8) or (b[p + 3].toLong() and 0xFF)
                val nv = v + base
                b[p] = (nv ushr 24).toByte()
                b[p + 1] = (nv ushr 16).toByte()
                b[p + 2] = (nv ushr 8).toByte()
                b[p + 3] = nv.toByte()
            }
            return b
        }
    }
}

internal class NameRecord(val platform: Int, val encoding: Int, val language: Int, val nameId: Int, val bytes: ByteArray)

internal object NameTables {
    fun win(nameId: Int, text: String, language: Int = 0x0409) =
        NameRecord(3, 1, language, nameId, text.toByteArray(Charsets.UTF_16BE))

    fun mac(nameId: Int, text: String, language: Int = 0) =
        NameRecord(1, 0, language, nameId, text.toByteArray(Charsets.ISO_8859_1))

    fun unicode(nameId: Int, text: String) = NameRecord(0, 3, 0, nameId, text.toByteArray(Charsets.UTF_16BE))

    fun build(vararg records: NameRecord): ByteArray {
        val out = ByteArrayOutputStream()
        val strings = ByteArrayOutputStream()
        SfntBuilder.u16(out, 0)
        SfntBuilder.u16(out, records.size)
        SfntBuilder.u16(out, 6 + 12 * records.size)
        for (r in records) {
            SfntBuilder.u16(out, r.platform)
            SfntBuilder.u16(out, r.encoding)
            SfntBuilder.u16(out, r.language)
            SfntBuilder.u16(out, r.nameId)
            SfntBuilder.u16(out, r.bytes.size)
            SfntBuilder.u16(out, strings.size())
            strings.write(r.bytes)
        }
        out.write(strings.toByteArray())
        return out.toByteArray()
    }

    /** fvar with the given axes (tag, min, default, max). */
    fun fvar(vararg axes: Triple<String, Float, Pair<Float, Float>>): ByteArray {
        val out = ByteArrayOutputStream()
        SfntBuilder.u16(out, 1)
        SfntBuilder.u16(out, 0)
        SfntBuilder.u16(out, 16) // axesArrayOffset
        SfntBuilder.u16(out, 2) // reserved
        SfntBuilder.u16(out, axes.size)
        SfntBuilder.u16(out, 20)
        SfntBuilder.u16(out, 0)
        SfntBuilder.u16(out, 0)
        for ((tag, min, rest) in axes) {
            for (c in tag) out.write(c.code)
            SfntBuilder.u32(out, fixed(min))
            SfntBuilder.u32(out, fixed(rest.first))
            SfntBuilder.u32(out, fixed(rest.second))
            SfntBuilder.u16(out, 0)
            SfntBuilder.u16(out, 256)
        }
        return out.toByteArray()
    }

    /** OS/2 table (version 4 length 96) with weight class, PANOSE family/serif style and fsSelection. */
    fun os2(weightClass: Int, panoseFamily: Int = 0, panoseSerif: Int = 0, fsSelection: Int = 0x40): ByteArray {
        val b = ByteArray(96)
        b[0] = 0
        b[1] = 4
        b[4] = (weightClass ushr 8).toByte()
        b[5] = weightClass.toByte()
        b[32] = panoseFamily.toByte()
        b[33] = panoseSerif.toByte()
        b[62] = (fsSelection ushr 8).toByte()
        b[63] = fsSelection.toByte()
        return b
    }

    private fun fixed(v: Float): Long = (Math.round(v * 65536f).toLong()) and 0xFFFFFFFFL
}
