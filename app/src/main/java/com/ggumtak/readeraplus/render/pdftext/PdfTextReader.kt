package com.ggumtak.readeraplus.render.pdftext

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.util.IdentityHashMap

/** Thrown when the file can't be read for text at all (encrypted, not a PDF, hopelessly broken). */
class PdfTextException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * One page's text in reading order. [text] holds the characters with '\n' between lines (no trailing '\n');
 * [boxes] has 4 floats per char of [text] (left, top, right, bottom) in page points, origin at the TOP-LEFT of the
 * page as displayed (page /Rotate applied; page box = CropBox clipped to MediaBox, else MediaBox), the same space as
 * Android PdfRenderer's page.width x page.height. A '\n' (and an inserted space) gets a zero-width box at the
 * end of the previous glyph. [lineStarts]: index in [text] where each line begins (ascending, first = 0; empty when
 * text is empty).
 */
class PageGlyphs(val text: String, val boxes: FloatArray, val lineStarts: IntArray) {
    companion object {
        val EMPTY = PageGlyphs("", FloatArray(0), IntArray(0))
    }
}

/**
 * Pure-JVM PDF text extractor, the fallback for devices whose platform PDF API has no text layer. It reads the
 * text of each page and per-character boxes without rendering. Not thread-safe: one thread uses an instance.
 * The file is memory-mapped (read-only) rather than read into the heap; a file cut short while it is open faults
 * the process (SIGBUS), as with any mapping. Limits: no decryption; text set vertically or turned within the page
 * comes out one character per line; hyphenated line ends are kept.
 */
class PdfTextReader private constructor(
    private val raf: RandomAccessFile,
    private val doc: PdfDoc,
    private val pages: List<PageEntry>,
) : Closeable {
    /** Number of pages (leaves of the page tree). */
    val pageCount: Int get() = pages.size

    private val fonts = IdentityHashMap<PdfDict, PdfFont>()
    private val badFonts = IdentityHashMap<PdfDict, Boolean>()
    private val fallbackFont: PdfFont by lazy { makeFallbackFont() }

    /** Width and height in points as displayed (rotation applied), like PdfRenderer reports. */
    fun pageSize(index: Int): FloatArray {
        val p = pages[index]
        return floatArrayOf(p.width.toFloat(), p.height.toFloat())
    }

    /** Text of page [index]; [PageGlyphs.EMPTY] for a page with none (e.g. scanned) or one that can't be read. */
    fun page(index: Int): PageGlyphs {
        if (index < 0 || index >= pages.size) return PageGlyphs.EMPTY
        return try {
            extract(pages[index])
        } catch (_: Exception) {
            PageGlyphs.EMPTY
        } catch (_: StackOverflowError) {
            PageGlyphs.EMPTY
        }
    }

    override fun close() {
        try {
            raf.close()
        } catch (_: IOException) {
            // nothing to do
        }
    }

    private fun extract(p: PageEntry): PageGlyphs {
        val content = pageContent(p)
        if (content.isEmpty()) return PageGlyphs.EMPTY
        val builder = PageTextBuilder(p.width.toFloat(), p.height.toFloat())
        val interp = PdfContentText({ fontFor(it) }, fallbackFont, builder)
        val res = doc.resolve(p.resources) as? PdfDict
        interp.runPage(content, res, p.matrix)
        return builder.build()
    }

    private fun pageContent(p: PageEntry): ByteArray {
        val c = p.dict["Contents"]
        val out = ByteSink(4096)
        fun add(s: Any?) {
            if (s is PdfStream && out.len < PdfFilters.MAX_OUT) {
                try {
                    val d = PdfFilters.decode(s)
                    out.put(d, 0, d.size)
                    out.put(32)
                } catch (_: PdfFormatException) {
                    // an undecodable part is skipped
                }
            }
        }
        when (c) {
            is PdfStream -> add(c)
            is PdfArray -> for (i in 0 until minOf(c.size, 100_000)) add(c[i])
            else -> {}
        }
        return out.toByteArray()
    }

    private fun fontFor(d: PdfDict): PdfFont? {
        fonts[d]?.let { return it }
        if (badFonts.containsKey(d)) return null
        // Bounded: a PDF with its own font objects on every page searched end to end would keep them all.
        if (fonts.size >= MAX_FONTS) fonts.clear()
        if (badFonts.size >= MAX_FONTS) badFonts.clear()
        return try {
            PdfFontLoader.load(d).also { fonts[d] = it }
        } catch (_: Exception) {
            badFonts[d] = true
            null
        }
    }

    private fun makeFallbackFont(): PdfFont {
        val w = FloatArray(256) { 0.5f }
        val u = arrayOfNulls<String>(256)
        for (c in 0..255) {
            val cp = PdfEncodings.winAnsi[c]
            if (cp != 0) u[c] = PdfEncodings.cpString(cp)
        }
        return SimpleFont(w, u)
    }

    internal class PageEntry(
        val dict: PdfDict,
        val resources: Any?,
        mediaRaw: Any?,
        cropRaw: Any?,
        rotateRaw: Any?,
        doc: PdfDoc,
    ) {
        val width: Double
        val height: Double

        /** User space -> displayed page (top-left origin, rotation applied). */
        val matrix: DoubleArray

        init {
            val media = rect(doc.resolve(mediaRaw)) ?: doubleArrayOf(0.0, 0.0, 612.0, 792.0)
            var box = rect(doc.resolve(cropRaw)) ?: media
            val ix0 = maxOf(box[0], media[0])
            val iy0 = maxOf(box[1], media[1])
            val ix1 = minOf(box[2], media[2])
            val iy1 = minOf(box[3], media[3])
            box = if (ix1 - ix0 > 0.0 && iy1 - iy0 > 0.0) doubleArrayOf(ix0, iy0, ix1, iy1) else media
            val w = box[2] - box[0]
            val h = box[3] - box[1]
            var rot = (numOf(doc.resolve(rotateRaw))?.toInt() ?: 0) % 360
            if (rot < 0) rot += 360
            rot = rot / 90 * 90
            val x0 = box[0]
            val y0 = box[1]
            val x1 = box[2]
            val y1 = box[3]
            when (rot) {
                90 -> {
                    width = h
                    height = w
                    matrix = doubleArrayOf(0.0, 1.0, 1.0, 0.0, -y0, -x0)
                }
                180 -> {
                    width = w
                    height = h
                    matrix = doubleArrayOf(-1.0, 0.0, 0.0, 1.0, x1, -y0)
                }
                270 -> {
                    width = h
                    height = w
                    matrix = doubleArrayOf(0.0, -1.0, -1.0, 0.0, y1, x1)
                }
                else -> {
                    width = w
                    height = h
                    matrix = doubleArrayOf(1.0, 0.0, 0.0, -1.0, -x0, y1)
                }
            }
        }

        private fun rect(o: Any?): DoubleArray? {
            val a = o as? PdfArray ?: return null
            if (a.size < 4) return null
            val v = DoubleArray(4)
            for (i in 0 until 4) v[i] = a.num(i) ?: return null
            if (v.any { it.isNaN() || it.isInfinite() }) return null
            val r = doubleArrayOf(minOf(v[0], v[2]), minOf(v[1], v[3]), maxOf(v[0], v[2]), maxOf(v[1], v[3]))
            return if (r[2] - r[0] > 0.0 && r[3] - r[1] > 0.0) r else null
        }
    }

    companion object {
        private const val MAX_PAGES = 500_000
        private const val MAX_FONTS = 64
        private const val MAX_TREE_DEPTH = 64

        /** Opens and parses the xref/trailer/page tree. Throws [PdfTextException] (encrypted -> message "encrypted"). */
        fun open(file: File): PdfTextReader {
            val raf = try {
                RandomAccessFile(file, "r")
            } catch (e: IOException) {
                throw PdfTextException("cannot open file", e)
            }
            try {
                val len = raf.length()
                if (len < 8) throw PdfTextException("not a PDF")
                if (len > Int.MAX_VALUE) throw PdfTextException("file too large")
                val buf = raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, len)
                val doc = PdfDoc(BufferSrc(buf))
                doc.load()
                val pages = collectPages(doc)
                if (pages.isEmpty()) throw PdfTextException("no pages")
                return PdfTextReader(raf, doc, pages)
            } catch (e: PdfTextException) {
                closeQuietly(raf)
                throw e
            } catch (e: Exception) {
                closeQuietly(raf)
                throw PdfTextException("unreadable PDF", e)
            } catch (e: StackOverflowError) {
                closeQuietly(raf)
                throw PdfTextException("unreadable PDF", e)
            }
        }

        private fun closeQuietly(c: Closeable) {
            try {
                c.close()
            } catch (_: IOException) {
                // ignore
            }
        }

        private fun collectPages(doc: PdfDoc): List<PageEntry> {
            val out = ArrayList<PageEntry>()
            val root = doc.trailer?.get("Root") as? PdfDict ?: return out
            val node = root.get("Pages") as? PdfDict ?: return out
            val visited = HashSet<Int>()
            (root.raw("Pages") as? PdfRef)?.let { visited.add(it.num) }
            visit(doc, node, null, null, null, null, 0, visited, out)
            return out
        }

        private fun visit(
            doc: PdfDoc,
            node: PdfDict,
            res: Any?,
            media: Any?,
            crop: Any?,
            rot: Any?,
            depth: Int,
            visited: HashSet<Int>,
            out: ArrayList<PageEntry>,
        ) {
            if (depth > MAX_TREE_DEPTH || out.size >= MAX_PAGES) return
            val r = node.raw("Resources") ?: res
            val m = node.raw("MediaBox") ?: media
            val c = node.raw("CropBox") ?: crop
            val ro = node.raw("Rotate") ?: rot
            val kids = node.arr("Kids")
            if (kids != null && node.name("Type") != "Page") {
                for (i in 0 until kids.size) {
                    val kr = kids.raw(i)
                    if (kr is PdfRef && !visited.add(kr.num)) continue
                    val k = kids[i] as? PdfDict ?: continue
                    visit(doc, k, r, m, c, ro, depth + 1, visited, out)
                    if (out.size >= MAX_PAGES) return
                }
            } else {
                out.add(PageEntry(node, r, m, c, ro, doc))
            }
        }
    }
}
