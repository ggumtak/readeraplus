package com.ggumtak.readeraplus.format.epub

import com.ggumtak.readeraplus.engine.ImageBlock
import com.ggumtak.readeraplus.engine.OBJECT_CHAR
import com.ggumtak.readeraplus.engine.ParagraphBlock
import com.ggumtak.readeraplus.engine.RuleBlock
import com.ggumtak.readeraplus.engine.SectionContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Helpers for the EPUB tests. All sample text is original. */
internal object EpubTestUtil {
    /** Converts an XHTML string located at [path] with a fake resource resolver (every image exists). */
    fun convert(
        xhtml: String,
        publisherStyles: Boolean = true,
        path: String = "OEBPS/Text/ch1.xhtml",
        css: Map<String, String> = emptyMap(),
        missingImages: Set<String> = emptySet(),
    ): SectionContent {
        val res = object : XhtmlResources {
            override fun imagePath(path: String): String? = if (path in missingImages) null else path
            override fun styleSheets(path: String): List<CssSheet> =
                css[path]?.let { listOf(CssParser.parse(it)) } ?: emptyList()
        }
        val c = XhtmlConverter(publisherStyles, res).convert(xhtml, path)
        checkInvariants(c)
        return c
    }

    fun html(body: String, head: String = ""): String =
        "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
            "<!DOCTYPE html PUBLIC \"-//W3C//DTD XHTML 1.1//EN\" \"http://www.w3.org/TR/xhtml11/DTD/xhtml11.dtd\">\n" +
            "<html xmlns=\"http://www.w3.org/1999/xhtml\" xmlns:epub=\"http://www.idpf.org/2007/ops\">\n" +
            "<head><title>T</title>$head</head>\n<body>\n$body\n</body>\n</html>\n"

    /**
     * A whole-book XHTML item as TXT→EPUB converters write it, of about [utf8Bytes]: a class on every `<p>`, an id
     * on each chapter heading, some classed `<span>`s and no '&' anywhere. With [wordPis] every 4th paragraph also
     * starts with the `<?xml:namespace … />` that Word-made HTML repeats (no "?>" after it).
     */
    fun converterItem(utf8Bytes: Int, wordPis: Boolean = false): String {
        val sb = StringBuilder(utf8Bytes / 2)
        var bytes = 0
        fun add(s: String) {
            sb.append(s)
            bytes += s.toByteArray(Charsets.UTF_8).size
        }
        add("<?xml version='1.0' encoding='utf-8'?>\n<html xmlns=\"http://www.w3.org/1999/xhtml\">\n<head><title>통짜 소설</title>")
        add("<link href=\"../Styles/stylesheet.css\" rel=\"stylesheet\" type=\"text/css\"/></head>\n<body class=\"calibre\">\n")
        var p = 0
        while (bytes < utf8Bytes) {
            if (p % 200 == 0) add("<h2 class=\"calibre3\" id=\"toc_${p / 200}\">제${p / 200 + 1}화</h2>\n")
            add("<p class=\"calibre1\">")
            if (wordPis && p % 4 == 0) add("<?xml:namespace prefix = o ns = \"urn:schemas-microsoft-com:office:office\" />")
            if (p % 5 == 0) add("<span class=\"calibre2\">${SENTENCES[p % SENTENCES.size]}</span> ")
            add(SENTENCES[p * 3 % SENTENCES.size])
            add(" ")
            add(SENTENCES[(p * 5 + 1) % SENTENCES.size])
            add("</p>\n")
            p++
        }
        add("</body>\n</html>\n")
        return sb.toString()
    }

    /** Paragraph/image/rule texts of a section, in order ("" for empty paragraphs, "[img:src]", "[hr]"). */
    fun blockTexts(c: SectionContent): List<String> = c.blocks.map { b ->
        when (b) {
            is ParagraphBlock -> c.text.substring(b.start, b.end)
            is ImageBlock -> "[img:${b.src}]"
            is RuleBlock -> "[hr]"
        }
    }

    /** Structural invariants every SectionContent must satisfy. */
    fun checkInvariants(c: SectionContent) {
        val t = c.text
        val blocks = c.blocks
        if (blocks.isEmpty()) {
            assertEquals("no blocks → empty text", "", t)
        }
        var expected = 0
        for ((i, b) in blocks.withIndex()) {
            assertEquals("block $i start", expected, b.start)
            assertTrue("block $i end >= start", b.end >= b.start)
            when (b) {
                is ImageBlock -> {
                    assertEquals("image char", OBJECT_CHAR, t[b.start])
                    assertTrue(b.src.isNotEmpty())
                }
                is RuleBlock -> assertEquals(b.start, b.end)
                is ParagraphBlock -> for (k in b.start until b.end) {
                    val ch = t[k]
                    if (ch == '\n' || ch == OBJECT_CHAR) fail("paragraph $i contains separator/object at $k: ${t.substring(b.start, b.end)}")
                }
            }
            if (i < blocks.size - 1) {
                assertTrue("separator after block $i", b.end < t.length)
                assertEquals("separator after block $i", '\n', t[b.end])
                expected = b.end + 1
            } else {
                assertEquals("last block ends at text end", t.length, b.end)
            }
        }
        var newlines = 0
        for (ch in t) if (ch == '\n') newlines++
        assertEquals("one separator per block boundary", maxOf(0, blocks.size - 1), newlines)
        var prevEnd = 0
        for (r in c.styleRuns) {
            assertTrue("run start < end: ${r.start}..${r.end}", r.start < r.end)
            assertTrue("runs sorted/non-overlapping", r.start >= prevEnd)
            assertTrue("run inside text", r.end <= t.length)
            prevEnd = r.end
        }
        for ((k, v) in c.anchors) assertTrue("anchor $k=$v in range", v in 0..t.length)
    }

    // ================================================================ EPUB building

    class Entry(val name: String, val data: ByteArray, val stored: Boolean = false)

    fun text(name: String, s: String) = Entry(name, s.toByteArray(Charsets.UTF_8))

    /** Writes a zip with a stored "mimetype" first (like real EPUBs) followed by [entries]. */
    fun writeEpub(entries: List<Entry>, name: String = "book.epub", mimetype: Boolean = true): File {
        val dir = Files.createTempDirectory("epubtest").toFile()
        dir.deleteOnExit()
        val f = File(dir, name)
        ZipOutputStream(FileOutputStream(f)).use { zos ->
            if (mimetype) putStored(zos, "mimetype", "application/epub+zip".toByteArray())
            for (e in entries) {
                if (e.stored) {
                    putStored(zos, e.name, e.data)
                } else {
                    zos.putNextEntry(ZipEntry(e.name))
                    zos.write(e.data)
                    zos.closeEntry()
                }
            }
        }
        f.deleteOnExit()
        return f
    }

    private fun putStored(zos: ZipOutputStream, name: String, data: ByteArray) {
        val e = ZipEntry(name)
        e.method = ZipEntry.STORED
        e.size = data.size.toLong()
        e.compressedSize = data.size.toLong()
        val crc = CRC32()
        crc.update(data)
        e.crc = crc.value
        zos.putNextEntry(e)
        zos.write(data)
        zos.closeEntry()
    }

    fun container(opfPath: String = "OEBPS/content.opf") = text(
        "META-INF/container.xml",
        """<?xml version="1.0"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles>
    <rootfile full-path="$opfPath" media-type="application/oebps-package+xml"/>
  </rootfiles>
</container>""",
    )

    /** Tiny valid-looking PNG header bytes (content is irrelevant to the parser). */
    fun png(tag: Int): ByteArray = byteArrayOf(
        0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10, tag.toByte(),
    )

    // Original sample prose (Korean), used as filler.
    val SENTENCES = listOf(
        "아침 안개가 천천히 걷히자 작은 항구 마을의 지붕들이 하나둘 모습을 드러냈다.",
        "그는 낡은 가방을 어깨에 메고 오래전에 적어 둔 주소를 다시 확인했다.",
        "“여기서 조금만 더 가면 돼.” 그녀가 손가락으로 언덕 너머를 가리켰다.",
        "바람에 실려 온 소금 냄새가 어린 시절의 여름을 떠올리게 했다.",
        "창가에 놓인 화분에는 이름을 알 수 없는 파란 꽃이 피어 있었다.",
        "시계탑의 종이 세 번 울리고 나서야 광장은 조용해졌다.",
        "The lantern flickered twice before the old keeper finally lit it.",
        "그들은 말없이 차를 마시며 비가 그치기를 기다렸다.",
    )
}
