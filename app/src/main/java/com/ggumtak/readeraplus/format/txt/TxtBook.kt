package com.ggumtak.readeraplus.format.txt

import com.ggumtak.readeraplus.engine.SectionContent
import com.ggumtak.readeraplus.format.BookDocument
import com.ggumtak.readeraplus.format.BookFormat
import com.ggumtak.readeraplus.format.DocMeta
import com.ggumtak.readeraplus.format.DocPosition
import com.ggumtak.readeraplus.format.DocumentException
import com.ggumtak.readeraplus.format.ParseOptions
import com.ggumtak.readeraplus.format.SectionInfo
import com.ggumtak.readeraplus.format.TocEntry
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * An opened TXT book backed by a [TxtIndex]: each section is rebuilt on demand from its byte range
 * (RandomAccessFile, synchronised), the last [CACHE_SIZE] sections are kept. Safe for concurrent use.
 */
internal class TxtBook(
    override val file: File,
    private val index: TxtIndex,
    private val options: ParseOptions,
) : BookDocument {
    override val format: BookFormat get() = BookFormat.TXT

    override val meta: DocMeta = DocMeta(
        title = file.nameWithoutExtension.ifEmpty { file.name },
        encoding = index.encoding,
    )

    override val sections: List<SectionInfo> = List(index.size) { i ->
        SectionInfo(if (index.isChapter(i)) index.titles[i] else null, index.chars[i])
    }

    override val toc: List<TocEntry> = buildList {
        for (i in 0 until index.size) {
            if (index.isChapter(i)) add(TocEntry(index.titles[i] ?: "", 1, i, 0))
        }
    }

    /** Resolved on first load (building the CP949 table then happens on the loader thread, not in open()). */
    private val decoder: TxtDecoder by lazy { TxtCharsets.forName(index.encoding) ?: Utf8Decoder }
    private val rules: ReplaceRules? = ReplaceRules.parse(options.txtReplaceRules)

    private val fileLock = Any()
    private var raf: RandomAccessFile? = null
    private var closed = false

    private val cache = object : LinkedHashMap<Int, SectionContent>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, SectionContent>?): Boolean =
            size > CACHE_SIZE
    }

    /** The underlying index (tests / diagnostics). */
    internal val txtIndex: TxtIndex get() = index

    override fun loadSection(index: Int): SectionContent {
        if (index < 0 || index >= this.index.size) return SectionContent.EMPTY
        synchronized(cache) { cache[index]?.let { return it } }
        val start = this.index.byteStart[index]
        val end = this.index.byteEnd[index]
        val bytes = readRange(start, end)
        val content = TxtParser.loadSection(bytes, bytes.size, this.index, index, decoder, options, rules)
        synchronized(cache) { cache[index] = content }
        return content
    }

    private fun readRange(start: Int, end: Int): ByteArray {
        synchronized(fileLock) {
            try {
                var r = raf
                val temporary = closed
                if (r == null) {
                    r = RandomAccessFile(file, "r")
                    if (!temporary) raf = r
                }
                try {
                    val fileLen = r.length()
                    val s = start.toLong().coerceIn(0L, fileLen)
                    val e = end.toLong().coerceIn(s, fileLen)
                    val buf = ByteArray((e - s).toInt())
                    r.seek(s)
                    r.readFully(buf)
                    return buf
                } finally {
                    if (temporary) r.close()
                }
            } catch (e: IOException) {
                throw DocumentException("읽기 실패: ${file.name}", e)
            }
        }
    }

    override fun loadImage(src: String): ByteArray? = null

    override fun coverImage(): ByteArray? = null

    override fun resolveLink(fromSection: Int, href: String): DocPosition? = null

    override fun resolveToc(entry: TocEntry): DocPosition {
        val s = entry.section.coerceIn(0, maxOf(0, index.size - 1))
        return DocPosition(s, maxOf(0, entry.offset))
    }

    override fun close() {
        synchronized(fileLock) {
            closed = true
            try {
                raf?.close()
            } catch (_: IOException) {
            }
            raf = null
        }
        synchronized(cache) { cache.clear() }
    }

    companion object {
        const val CACHE_SIZE = 4
    }
}
