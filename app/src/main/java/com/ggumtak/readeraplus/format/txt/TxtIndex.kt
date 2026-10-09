package com.ggumtak.readeraplus.format.txt

import com.ggumtak.readeraplus.format.Documents
import com.ggumtak.readeraplus.format.ParseOptions
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer

/**
 * Everything needed to rebuild any section of a TXT book from its bytes alone: the charset, the resolved
 * global decisions and, per section, the original byte range plus title / flags / exact char length.
 */
internal class TxtIndex(
    /** Cache key (path, size, mtime, parse options, parser version). */
    val key: String,
    /** Decoder name (see TxtCharsets.forName). */
    val encoding: String,
    /** Newline code unit: 0x0A (LF / CRLF) or 0x0D (old Mac CR). */
    val newline: Int,
    val decisions: TxtDecisions,
    val byteStart: IntArray,
    val byteEnd: IntArray,
    /** [CHAPTER] | [STARTS_CONT] | [ENDS_SEG]. */
    val flags: IntArray,
    /** Exact char length of each section's text. */
    val chars: IntArray,
    /** Chapter title for sections that start a chapter, else null. */
    val titles: Array<String?>,
    /**
     * [CHAPTER] sections: the heading's line index relative to the section's first line. 0 normally; > 0 when a
     * short preface (text before the first chapter) was merged into the first chapter's section.
     */
    val headLine: IntArray = IntArray(byteStart.size),
    /** [CHAPTER] sections: char offset of the heading paragraph in the section text (the TOC entry offset). */
    val headChar: IntArray = IntArray(byteStart.size),
) {
    val size: Int get() = byteStart.size

    fun isChapter(i: Int): Boolean = flags[i] and CHAPTER != 0

    companion object {
        /** The section starts with a chapter heading line. */
        const val CHAPTER = 1
        /** The section starts inside an overlong line (continuation segment). */
        const val STARTS_CONT = 2
        /** The section ends inside an overlong line (the next section starts with its continuation). */
        const val ENDS_SEG = 4
    }
}

/** Persists [TxtIndex] under `Documents.cacheDir/txtindex/<hash>.idx`. All failures are silent (cache only). */
internal object TxtIndexStore {
    /**
     * Bump whenever parsing output could change for the same input. At most once per release (every large TXT then
     * parses in full once): 4 = release 2, author-note pruning (A5); 5 = the user heading rule adds to the built-in
     * rules, easy patterns (HeadingRule), numbered headings ending with '.'; 6 = one-space wrap leftovers don't start
     * paragraphs (`TxtDecisions.wrapSpaces`).
     */
    const val VERSION = 6
    private const val MAGIC = 0x52505458 // "RPTX"
    /** Fixed bytes per section record: byteStart, byteEnd, flags, chars, headLine, headChar, title marker. */
    private const val SECTION_BYTES = 6 * 4 + 1
    private const val MAX_FILES = 300
    private const val KEEP_FILES = 200

    fun dir(): File? = Documents.cacheDir?.let { File(it, "txtindex") }

    /** Cache key: file identity plus every option that changes the index. (Heading emphasis doesn't.) */
    fun key(file: File, o: ParseOptions): String = buildString {
        append("v").append(VERSION)
        append('|').append(file.absolutePath)
        append('|').append(file.length())
        append('|').append(file.lastModified())
        append("|b").append(o.txtBlankLines)
        append("|s").append(if (o.txtStripIndent) 1 else 0)
        append("|j").append(o.txtJoinWrappedLines)
        append("|d").append(if (o.txtDetectChapters) 1 else 0)
        append("|e").append(o.txtEncoding.trim())
        append("|c").append(o.txtChapterRegex.length).append(':').append(o.txtChapterRegex)
        append("|r").append(o.txtReplaceRules.length).append(':').append(o.txtReplaceRules)
    }

    fun fileFor(key: String): File? {
        val d = dir() ?: return null
        var h = -0x340d631b7bdddcdbL // FNV-1a 64 offset basis
        for (c in key) {
            h = h xor (c.code.toLong() and 0xFF)
            h *= 0x100000001b3L
            h = h xor (c.code.toLong() ushr 8)
            h *= 0x100000001b3L
        }
        return File(d, java.lang.Long.toHexString(h) + ".idx")
    }

    /** Loads the index for [key] if present and consistent with a file of [fileLength] bytes. */
    fun load(key: String, fileLength: Long): TxtIndex? {
        val f = fileFor(key) ?: return null
        return try {
            if (!f.isFile) return null
            val data = f.readBytes()
            decode(data, key, fileLength)?.also {
                // keep recently used indexes when trimming the directory
                f.setLastModified(System.currentTimeMillis())
            }
        } catch (_: Throwable) {
            null
        }
    }

    fun save(index: TxtIndex) {
        val f = fileFor(index.key) ?: return
        try {
            val d = f.parentFile ?: return
            if (!d.isDirectory && !d.mkdirs()) return
            val data = encode(index)
            val tmp = File(d, f.name + ".tmp" + Thread.currentThread().id)
            FileOutputStream(tmp).use { it.write(data) }
            if (!tmp.renameTo(f)) {
                f.delete()
                if (!tmp.renameTo(f)) tmp.delete()
            }
            trim(d)
        } catch (_: Throwable) {
        }
    }

    private fun trim(d: File) {
        val files = d.listFiles() ?: return
        if (files.size <= MAX_FILES) return
        files.sortByDescending { it.lastModified() }
        for (k in KEEP_FILES until files.size) files[k].delete()
    }

    internal fun encode(x: TxtIndex): ByteArray {
        var size = 4 * 4 + 8 + x.key.length * 2 + 4 + x.encoding.length * 2 + 4 * 6 + 1
        for (i in 0 until x.size) size += SECTION_BYTES + (x.titles[i]?.let { 4 + it.length * 2 } ?: 0)
        val out = ByteBuffer.allocate(size)
        out.putInt(MAGIC)
        out.putInt(VERSION)
        putStr(out, x.key)
        putStr(out, x.encoding)
        out.putInt(x.newline)
        out.putInt(x.decisions.blankMode)
        out.putInt(x.decisions.sceneRun)
        out.putInt(x.decisions.joinMinWidth)
        out.put(if (x.decisions.joinStopAtIndent) 1 else 0)
        out.put(if (x.decisions.joinIgnoreTerminal) 1 else 0)
        out.put(if (x.decisions.wrapSpaces) 1 else 0)
        out.putInt(x.size)
        for (i in 0 until x.size) {
            out.putInt(x.byteStart[i])
            out.putInt(x.byteEnd[i])
            out.putInt(x.flags[i])
            out.putInt(x.chars[i])
            out.putInt(x.headLine[i])
            out.putInt(x.headChar[i])
            val t = x.titles[i]
            out.put(if (t != null) 1 else 0)
            if (t != null) putStr(out, t)
        }
        out.putInt(MAGIC)
        return out.array().copyOf(out.position())
    }

    /** Decodes and validates an index; null on any inconsistency (never throws). */
    internal fun decode(data: ByteArray, key: String, fileLength: Long): TxtIndex? = try {
        decodeOrThrow(ByteBuffer.wrap(data), key, fileLength)
    } catch (_: RuntimeException) {
        null
    }

    private fun decodeOrThrow(inp: ByteBuffer, key: String, fileLength: Long): TxtIndex? {
        if (inp.getInt() != MAGIC || inp.getInt() != VERSION) return null
        if (!strEquals(inp, key)) return null
        val encoding = getStr(inp) ?: return null
        val newline = inp.getInt()
        if (newline != 0x0A && newline != 0x0D) return null
        val blankMode = inp.getInt()
        if (blankMode !in TxtDecisions.REMOVE_SINGLES..TxtDecisions.KEEP) return null
        val sceneRun = inp.getInt()
        if (sceneRun < 1) return null
        val joinMin = inp.getInt()
        if (joinMin < 0) return null
        val stopIndent = inp.get().toInt() != 0
        val ignoreTerminal = inp.get().toInt() != 0
        val wrapSpaces = inp.get().toInt() != 0
        val n = inp.getInt()
        if (n < 1 || n > inp.remaining() / SECTION_BYTES) return null
        val bs = IntArray(n)
        val be = IntArray(n)
        val fl = IntArray(n)
        val ch = IntArray(n)
        val hl = IntArray(n)
        val hc = IntArray(n)
        val ti = arrayOfNulls<String>(n)
        var prevEnd = 0
        for (i in 0 until n) {
            bs[i] = inp.getInt()
            be[i] = inp.getInt()
            fl[i] = inp.getInt()
            ch[i] = inp.getInt()
            hl[i] = inp.getInt()
            hc[i] = inp.getInt()
            if (inp.get().toInt() != 0) ti[i] = getStr(inp) ?: return null
            if (bs[i] < prevEnd || be[i] < bs[i] || be[i] > fileLength || ch[i] < 0) return null
            if (hl[i] < 0 || hc[i] < 0 || hc[i] > ch[i]) return null
            prevEnd = be[i]
        }
        if (inp.getInt() != MAGIC) return null
        return TxtIndex(
            key, encoding, newline, TxtDecisions(blankMode, sceneRun, joinMin, stopIndent, ignoreTerminal, wrapSpaces),
            bs, be, fl, ch, ti, hl, hc,
        )
    }

    private fun putStr(out: ByteBuffer, s: String) {
        out.putInt(s.length)
        for (c in s) out.putChar(c)
    }

    private fun getStr(inp: ByteBuffer): String? {
        val n = inp.getInt()
        if (n < 0 || n > inp.remaining() / 2) return null
        val c = CharArray(n)
        for (k in 0 until n) c[k] = inp.getChar()
        return String(c)
    }

    private fun strEquals(inp: ByteBuffer, s: String): Boolean {
        val n = inp.getInt()
        if (n != s.length || n > inp.remaining() / 2) return false
        for (k in 0 until n) if (inp.getChar() != s[k]) return false
        return true
    }
}
