package com.ggumtak.readeraplus.format.epub

import com.ggumtak.readeraplus.engine.Align
import com.ggumtak.readeraplus.engine.ImageBlock
import com.ggumtak.readeraplus.engine.ParagraphBlock
import com.ggumtak.readeraplus.format.DocPosition
import com.ggumtak.readeraplus.format.DocumentException
import com.ggumtak.readeraplus.format.Documents
import com.ggumtak.readeraplus.format.ParseOptions
import com.ggumtak.readeraplus.format.epub.EpubTestUtil.Entry
import com.ggumtak.readeraplus.format.epub.EpubTestUtil.blockTexts
import com.ggumtak.readeraplus.format.epub.EpubTestUtil.checkInvariants
import com.ggumtak.readeraplus.format.epub.EpubTestUtil.container
import com.ggumtak.readeraplus.format.epub.EpubTestUtil.png
import com.ggumtak.readeraplus.format.epub.EpubTestUtil.text
import com.ggumtak.readeraplus.format.epub.EpubTestUtil.writeEpub
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class EpubDocumentsTest {
    private fun xhtml(title: String, body: String, head: String = "") =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<!DOCTYPE html>\n" +
            "<html xmlns=\"http://www.w3.org/1999/xhtml\" xmlns:epub=\"http://www.idpf.org/2007/ops\">" +
            "<head><title>$title</title>$head</head><body>$body</body></html>"

    /** EPUB2 with NCX, calibre series, meta cover, linked CSS, images in a sibling folder. */
    private fun epub2(): File {
        val opf = """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="uid">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:opf="http://www.idpf.org/2007/opf">
    <dc:title>바다로 가는 길</dc:title>
    <dc:creator opf:role="aut">김하늘</dc:creator>
    <dc:creator opf:role="aut">Lee Byul</dc:creator>
    <dc:language>ko</dc:language>
    <dc:publisher>작은 출판사</dc:publisher>
    <dc:description>&lt;p&gt;첫 문단 &amp;amp; 소개.&lt;/p&gt;&lt;p&gt;둘째   문단.&lt;/p&gt;</dc:description>
    <meta name="calibre:series" content="항구 이야기"/>
    <meta name="calibre:series_index" content="2.0"/>
    <meta name="cover" content="cover-img"/>
  </metadata>
  <manifest>
    <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
    <item id="css" href="Styles/book.css" media-type="text/css"/>
    <item id="cover-img" href="Images/front.png" media-type="image/png"/>
    <item id="c1" href="Text/ch1.xhtml" media-type="application/xhtml+xml"/>
    <item id="c2" href="Text/ch2.xhtml" media-type="application/xhtml+xml"/>
    <item id="pic" href="Images/a.png" media-type="image/png"/>
  </manifest>
  <spine toc="ncx">
    <itemref idref="c1"/>
    <itemref idref="missing"/>
    <itemref idref="c2" linear="no"/>
  </spine>
</package>"""
        val ncx = """<?xml version="1.0" encoding="UTF-8"?>
<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
  <head/><docTitle><text>바다로 가는 길</text></docTitle>
  <navMap>
    <navPoint id="n1" playOrder="1"><navLabel><text>1장 출발</text></navLabel><content src="Text/ch1.xhtml"/>
      <navPoint id="n2" playOrder="2"><navLabel><text> 1.1  항구에서 </text></navLabel><content src="Text/ch1.xhtml#harbor"/></navPoint>
    </navPoint>
    <navPoint id="n3" playOrder="3"><navLabel><text>2장 도착</text></navLabel><content src="Text/ch2.xhtml#top"/></navPoint>
    <navPoint id="n4" playOrder="4"><navLabel><text>외부</text></navLabel><content src="http://example.com/x"/></navPoint>
  </navMap>
</ncx>"""
        val ch1 = xhtml(
            "Ch1",
            "<h1>1장 출발</h1><p class=\"lead\">${EpubTestUtil.SENTENCES[0]}</p>" +
                "<p>${EpubTestUtil.SENTENCES[1]}<img src=\"../Images/a.png\" alt=\"삽화\"/></p>" +
                "<h2 id=\"harbor\">항구에서</h2><p>${EpubTestUtil.SENTENCES[2]} <a href=\"#note1\">[1]</a> " +
                "<a href=\"ch2.xhtml#top\">다음 장</a> <a href=\"http://example.com\">웹</a></p>" +
                "<p id=\"note1\">[1] 주석 내용.</p>",
            "<link rel=\"stylesheet\" href=\"../Styles/book.css\" type=\"text/css\"/>",
        )
        val ch2 = xhtml("Ch2", "<div id=\"top\"><h1>2장 도착</h1><p>${EpubTestUtil.SENTENCES[3]}</p></div>")
        return writeEpub(
            listOf(
                container(),
                text("OEBPS/content.opf", opf),
                text("OEBPS/toc.ncx", ncx),
                text("OEBPS/Styles/book.css", "@charset \"utf-8\";\n.lead { text-align: center; text-indent: 0 }"),
                text("OEBPS/Text/ch1.xhtml", ch1),
                text("OEBPS/Text/ch2.xhtml", ch2),
                Entry("OEBPS/Images/front.png", png(1)),
                Entry("OEBPS/Images/a.png", png(2)),
            ),
        )
    }

    @Test
    fun epub2WithNcx() {
        val f = epub2()
        EpubDocuments.open(f, ParseOptions()).use { doc ->
            val m = doc.meta
            assertEquals("바다로 가는 길", m.title)
            assertEquals(listOf("김하늘", "Lee Byul"), m.authors)
            assertEquals("ko", m.language)
            assertEquals("작은 출판사", m.publisher)
            assertEquals("첫 문단 & 소개.\n둘째 문단.", m.description)
            assertEquals("항구 이야기", m.series)
            assertEquals(2f, m.seriesIndex!!, 0.001f)
            assertEquals(2, doc.sections.size) // missing idref skipped, linear="no" kept
            assertTrue(doc.sections.all { it.approxChars > 0 })

            val toc = doc.toc
            assertEquals(listOf("1장 출발", "1.1 항구에서", "2장 도착"), toc.map { it.title })
            assertEquals(listOf(1, 2, 1), toc.map { it.level })
            assertEquals(listOf(0, 0, 1), toc.map { it.section })
            assertEquals("harbor", toc[1].anchor)

            val s0 = doc.loadSection(0)
            checkInvariants(s0)
            assertEquals(doc.resolveToc(toc[1]), DocPosition(0, s0.text.indexOf("항구에서")))
            assertEquals(DocPosition(0, 0), doc.resolveToc(toc[0]))
            assertEquals(DocPosition(1, 0), doc.resolveToc(toc[2]))

            // linked CSS applied
            val lead = s0.blocks[1] as ParagraphBlock
            assertEquals(Align.CENTER, lead.style.align)
            assertFalse(lead.style.indent)
            // relative image path resolved and loadable
            val img = s0.blocks.first { it is ImageBlock } as ImageBlock
            assertEquals("OEBPS/Images/a.png", img.src)
            assertArrayEquals(png(2), doc.loadImage(img.src))
            assertNull(doc.loadImage("OEBPS/Images/none.png"))

            // links
            assertEquals(DocPosition(0, s0.text.indexOf("[1] 주석")), doc.resolveLink(0, "#note1"))
            assertEquals(DocPosition(1, 0), doc.resolveLink(0, "ch2.xhtml#top"))
            assertEquals(DocPosition(1, 0), doc.resolveLink(0, "ch2.xhtml"))
            assertNull(doc.resolveLink(0, "http://example.com"))
            assertNull(doc.resolveLink(0, "#nowhere"))
            assertEquals(DocPosition(1, 0), doc.resolveLink(0, "ch2.xhtml#nowhere"))
            assertNull(doc.resolveLink(0, "../Images/a.png"))

            // cover via <meta name="cover">
            assertArrayEquals(png(1), doc.coverImage())
        }
        val meta = EpubDocuments.readMeta(f)
        assertEquals("바다로 가는 길", meta.title)
        assertEquals(2, meta.authors.size)
        assertEquals("항구 이야기", Documents.readMeta(f).series)
    }

    /** EPUB3 with nav, cover-image property, collections and refined titles; OPF at the zip root. */
    private fun epub3(extraManifest: String = "", navBody: String? = null): File {
        val opf = """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:title id="sub">부제목입니다</dc:title>
    <dc:title id="main">별빛 정원</dc:title>
    <meta refines="#main" property="title-type">main</meta>
    <meta refines="#sub" property="title-type">subtitle</meta>
    <dc:creator id="a1">박누리</dc:creator>
    <meta refines="#a1" property="role" scheme="marc:relators">aut</meta>
    <meta property="belongs-to-collection" id="c01">정원 연작</meta>
    <meta refines="#c01" property="collection-type">series</meta>
    <meta refines="#c01" property="group-position">3</meta>
    <dc:language>ko-KR</dc:language>
  </metadata>
  <manifest>
    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
    <item id="img" href="img/star%20cover.jpg" media-type="image/jpeg" properties="cover-image"/>
    <item id="p1" href="text/part%201.xhtml" media-type="application/xhtml+xml"/>
    <item id="p2" href="text/Part2.XHTML" media-type="application/xhtml+xml"/>
    <item id="p3" href="img/plate.png" media-type="image/png"/>
    $extraManifest
  </manifest>
  <spine>
    <itemref idref="p1"/>
    <itemref idref="p2"/>
    <itemref idref="p3"/>
  </spine>
</package>"""
        val nav = xhtml(
            "목차",
            navBody ?: ("<nav epub:type=\"landmarks\" hidden=\"\"><ol><li><a href=\"text/part%201.xhtml\">표지</a></li></ol></nav>" +
                "<nav epub:type=\"toc\" id=\"toc\"><h1>차례</h1><ol>" +
                "<li><span>제1부</span><ol><li><a href=\"text/part%201.xhtml#s1\">첫째 <b>장</b></a></li>" +
                "<li><a href=\"text/part2.xhtml\">둘째 장</a></li></ol></li>" +
                "<li><a href=\"img/plate.png\">그림</a></li>" +
                "<li><a href=\"nowhere.xhtml\">없는 장</a></li>" +
                "</ol></nav>"),
        )
        return writeEpub(
            listOf(
                container("package.opf"),
                text("package.opf", opf),
                text("nav.xhtml", nav),
                Entry("img/star cover.jpg", png(7)),
                Entry("img/plate.png", png(8)),
                text("text/part 1.xhtml", xhtml("P1", "<p>도입</p><h2 id=\"s1\">첫째 장</h2><p>${EpubTestUtil.SENTENCES[4]}</p>")),
                text("text/Part2.xhtml", xhtml("P2", "<p>${EpubTestUtil.SENTENCES[5]}</p>")),
            ),
        )
    }

    @Test
    fun epub3WithNav() {
        EpubDocuments.open(epub3(), ParseOptions()).use { doc ->
            assertEquals("별빛 정원", doc.meta.title)
            assertEquals(listOf("박누리"), doc.meta.authors)
            assertEquals("정원 연작", doc.meta.series)
            assertEquals(3f, doc.meta.seriesIndex!!, 0.001f)
            assertEquals(3, doc.sections.size)
            val toc = doc.toc
            assertEquals(listOf("제1부", "첫째 장", "둘째 장", "그림"), toc.map { it.title })
            assertEquals(listOf(1, 2, 2, 1), toc.map { it.level })
            assertEquals(listOf(0, 0, 1, 2), toc.map { it.section })
            assertEquals("s1", toc[0].anchor) // label-only item takes its first child's target
            val pos = doc.resolveToc(toc[1])
            assertEquals(doc.loadSection(0).text.indexOf("첫째 장"), pos.offset)
            // URL-encoded + case-insensitive entry names
            assertEquals(listOf("text/part 1.xhtml", "text/Part2.xhtml", "img/plate.png"), (doc as EpubBook).spinePaths)
            assertEquals(listOf(EpubTestUtil.SENTENCES[5]), blockTexts(doc.loadSection(1)))
            // image spine item → one image section
            val s2 = doc.loadSection(2)
            checkInvariants(s2)
            assertEquals(listOf("[img:img/plate.png]"), blockTexts(s2))
            assertArrayEquals(png(7), doc.coverImage())
            assertEquals(DocPosition(1, 0), doc.resolveLink(0, "Part2.xhtml"))
            assertEquals(DocPosition(0, doc.loadSection(0).text.indexOf("첫째 장")), doc.resolveLink(1, "part%201.xhtml#s1"))
        }
    }

    @Test
    fun missingTocFallsBackToHeadings() {
        val opf = """<package version="2.0"><metadata><dc:title>제목만</dc:title></metadata>
<manifest>
<item id="a" href="a.html" media-type="application/xhtml+xml"/>
<item id="b" href="b.html" media-type="application/xhtml+xml"/>
<item id="c" href="c.html" media-type="application/xhtml+xml"/>
<item id="d" href="d.html" media-type="application/xhtml+xml"/>
</manifest><spine><itemref idref="a"/><itemref idref="b"/><itemref idref="c"/><itemref idref="d"/></spine></package>"""
        val f = writeEpub(
            listOf(
                container("content.opf"),
                text("content.opf", opf),
                text("a.html", xhtml("제목만", "<p>표지 글</p>")),
                text("b.html", xhtml("제목만", "<div><h2>첫 <rt>x</rt>이야기</h2><p>본문</p></div>")),
                text("c.html", xhtml("세 번째 파일", "<p>본문</p>")),
                text("d.html", xhtml("", "<p>no title</p>")),
            ),
        )
        EpubDocuments.open(f, ParseOptions()).use { doc ->
            assertEquals(listOf("첫 이야기", "세 번째 파일"), doc.toc.map { it.title })
            assertEquals(listOf(1, 2), doc.toc.map { it.section })
        }
    }

    @Test
    fun coverFromGuide() {
        val opf = """<package version="2.0"><metadata><dc:title>가이드</dc:title></metadata>
<manifest>
<item id="t" href="Text/titlepage.xhtml" media-type="application/xhtml+xml"/>
<item id="x" href="Text/ch.xhtml" media-type="application/xhtml+xml"/>
<item id="i1" href="Images/p1.png" media-type="image/png"/>
<item id="i2" href="Images/p2.png" media-type="image/png"/>
</manifest><spine><itemref idref="x"/></spine>
<guide><reference type="cover" href="Text/titlepage.xhtml" title="Cover"/></guide></package>"""
        val f = writeEpub(
            listOf(
                container("OPS/content.opf"),
                text("OPS/content.opf", opf),
                text("OPS/Text/titlepage.xhtml", xhtml("c", "<div><img src=\"../Images/p2.png\" alt=\"\"/></div>")),
                text("OPS/Text/ch.xhtml", xhtml("c", "<p><img src=\"../Images/p1.png\"/></p>")),
                Entry("OPS/Images/p1.png", png(1)),
                Entry("OPS/Images/p2.png", png(2)),
            ),
        )
        EpubDocuments.open(f, ParseOptions()).use { assertArrayEquals(png(2), it.coverImage()) }
    }

    @Test
    fun coverFromNameThenFirstImage() {
        fun book(withNamed: Boolean): File {
            val opf = """<package version="2.0"><metadata><dc:title>이름</dc:title></metadata>
<manifest>
<item id="x" href="ch.xhtml" media-type="application/xhtml+xml"/>
<item id="i1" href="first.png" media-type="image/png"/>
${if (withNamed) "<item id=\"i2\" href=\"MyCover.PNG\" media-type=\"image/png\"/>" else ""}
</manifest><spine><itemref idref="x"/></spine></package>"""
            return writeEpub(
                listOf(
                    container("content.opf"),
                    text("content.opf", opf),
                    text("ch.xhtml", xhtml("c", "<p>글</p><svg><image xlink:href=\"first.png\"/></svg>")),
                    Entry("first.png", png(1)),
                    Entry("MyCover.PNG", png(3)),
                ),
            )
        }
        EpubDocuments.open(book(true), ParseOptions()).use { assertArrayEquals(png(3), it.coverImage()) }
        EpubDocuments.open(book(false), ParseOptions()).use { assertArrayEquals(png(1), it.coverImage()) }
    }

    @Test
    fun noCoverAnywhere() {
        val opf = """<package><metadata/><manifest><item id="x" href="ch.xhtml" media-type="application/xhtml+xml"/></manifest>
<spine><itemref idref="x"/></spine></package>"""
        val f = writeEpub(listOf(container("content.opf"), text("content.opf", opf), text("ch.xhtml", xhtml("c", "<p>글</p>"))), "untitled.epub")
        EpubDocuments.open(f, ParseOptions()).use { doc ->
            assertNull(doc.coverImage())
            assertEquals("untitled", doc.meta.title) // (ASCII: the test JVM may not use UTF-8 file names)
            assertTrue(doc.toc.isEmpty() || doc.toc.all { it.section == 0 })
        }
    }

    @Test
    fun sectionCacheReturnsSameInstanceAndSurvivesClose() {
        val f = epub2()
        val doc = EpubDocuments.open(f, ParseOptions())
        val a = doc.loadSection(0)
        assertSame(a, doc.loadSection(0))
        doc.loadSection(1)
        doc.close()
        // late background work after close must still work (temporary reopen), never crash
        val again = doc.loadSection(0)
        assertEquals(a.text, again.text)
        assertNotNull(doc.loadImage("OEBPS/Images/a.png"))
        assertEquals(0, doc.loadSection(99).length)
        assertEquals(0, doc.loadSection(-1).length)
    }

    @Test
    fun lruEvictsBeyondFour() {
        val entries = ArrayList<Entry>()
        val manifest = StringBuilder()
        val spine = StringBuilder()
        for (i in 0 until 7) {
            entries.add(text("s$i.xhtml", xhtml("s$i", "<p>섹션 $i</p>")))
            manifest.append("<item id=\"s$i\" href=\"s$i.xhtml\" media-type=\"application/xhtml+xml\"/>")
            spine.append("<itemref idref=\"s$i\"/>")
        }
        entries.add(0, text("content.opf", "<package><metadata/><manifest>$manifest</manifest><spine>$spine</spine></package>"))
        entries.add(0, container("content.opf"))
        EpubDocuments.open(writeEpub(entries), ParseOptions()).use { doc ->
            val first = doc.loadSection(0)
            for (i in 1 until 7) doc.loadSection(i)
            val reloaded = doc.loadSection(0)
            assertEquals(first.text, reloaded.text)
            assertFalse(first === reloaded)
            assertSame(doc.loadSection(6), doc.loadSection(6))
        }
    }

    @Test
    fun concurrentLoadsAreConsistent() {
        val f = epub2()
        EpubDocuments.open(f, ParseOptions()).use { doc ->
            val pool = Executors.newFixedThreadPool(6)
            try {
                val tasks = (0 until 60).map { i -> Callable { doc.loadSection(i % 2).text } }
                val results = pool.invokeAll(tasks).map { it.get() }
                for (i in results.indices) assertEquals(results[i % 2], results[i])
            } finally {
                pool.shutdown()
            }
        }
    }

    @Test
    fun publisherStylesOptionReachesConverter() {
        val f = epub2()
        EpubDocuments.open(f, ParseOptions(epubPublisherStyles = false)).use { doc ->
            val lead = doc.loadSection(0).blocks[1] as ParagraphBlock
            assertEquals(Align.DEFAULT, lead.style.align)
            assertTrue(lead.style.indent)
        }
    }

    @Test
    fun noContainerUsesOpfFromListing() {
        val opf = """<package><metadata><dc:title>컨테이너 없음</dc:title></metadata>
<manifest><item id="x" href="x.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="x"/></spine></package>"""
        val f = writeEpub(listOf(text("OEBPS/book.opf", opf), text("OEBPS/x.xhtml", xhtml("x", "<p>내용</p>"))))
        EpubDocuments.open(f, ParseOptions()).use { doc ->
            assertEquals("컨테이너 없음", doc.meta.title)
            assertEquals(listOf("내용"), blockTexts(doc.loadSection(0)))
        }
    }

    @Test
    fun noOpfAtAllUsesHtmlEntries() {
        val f = writeEpub(
            listOf(
                text("ch10.html", "<p>열</p>"),
                text("ch2.html", "<p>둘</p>"),
                text("ch1.html", "<p>하나</p>"),
                text("META-INF/container.xml", "<container><rootfiles/></container>"),
            ),
            "loose.epub",
        )
        EpubDocuments.open(f, ParseOptions()).use { doc ->
            assertEquals("loose", doc.meta.title)
            assertEquals(3, doc.sections.size)
            assertEquals(listOf("하나"), blockTexts(doc.loadSection(0)))
            assertEquals(listOf("열"), blockTexts(doc.loadSection(2)))
        }
        assertEquals("loose", EpubDocuments.readMeta(f).title)
    }

    @Test
    fun brokenFilesThrowDocumentException() {
        val dir = createTempDir()
        val notZip = File(dir, "bad.epub").apply { writeText("this is not a zip file at all") }
        val empty = writeEpub(emptyList(), "empty.epub")
        val missing = File(dir, "missing.epub")
        for (f in listOf(notZip, empty, missing)) {
            try {
                EpubDocuments.open(f, ParseOptions()).close()
                fail("expected DocumentException for ${f.name}")
            } catch (_: DocumentException) {
            }
        }
        try {
            EpubDocuments.readMeta(notZip)
            fail("expected DocumentException")
        } catch (_: DocumentException) {
        }
        assertEquals("empty", EpubDocuments.readMeta(empty).title)
    }

    @Test
    fun malformedOpfAndContentDoNotCrash() {
        val opf = "<package><metadata><dc:title>깨진 <b>OPF</dc:title><manifest><item id=x href=x.xhtml " +
            "media-type=application/xhtml+xml><item id=y href='y.xhtml'></manifest><spine><itemref idref=x><itemref idref=\"y\""
        val f = writeEpub(
            listOf(
                container("content.opf"),
                text("content.opf", opf),
                text("x.xhtml", "<html><body><p>열린 <b>태그<p>그리고 <<<  & 끝"),
                text("y.xhtml", "\u0000\u0001garbage<"),
            ),
        )
        EpubDocuments.open(f, ParseOptions()).use { doc ->
            assertEquals(2, doc.sections.size)
            for (i in doc.sections.indices) checkInvariants(doc.loadSection(i))
            assertTrue(doc.loadSection(0).text.contains("열린 태그"))
            doc.toc
            doc.coverImage()
        }
    }

    @Test
    fun encodingsInContentDocuments() {
        val cp949 = EpubText.cp949()!!
        val krDoc = "<?xml version=\"1.0\" encoding=\"euc-kr\"?><html><body><p>똠방각하 쐈다</p></body></html>"
        val u16 = "<html><body><p>유니코드 16</p></body></html>"
        val opf = """<package><metadata/><manifest>
<item id="a" href="a.xhtml" media-type="application/xhtml+xml"/>
<item id="b" href="b.xhtml" media-type="application/xhtml+xml"/>
</manifest><spine><itemref idref="a"/><itemref idref="b"/></spine></package>"""
        val f = writeEpub(
            listOf(
                container("content.opf"),
                text("content.opf", opf),
                Entry("a.xhtml", krDoc.toByteArray(cp949)),
                Entry("b.xhtml", byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + u16.toByteArray(Charsets.UTF_16LE)),
            ),
        )
        EpubDocuments.open(f, ParseOptions()).use { doc ->
            assertEquals("똠방각하 쐈다", doc.loadSection(0).text)
            assertEquals("유니코드 16", doc.loadSection(1).text)
        }
    }

    @Test
    fun cssImportsAndCycles() {
        val opf = """<package><metadata/><manifest><item id="a" href="t/a.xhtml" media-type="application/xhtml+xml"/></manifest>
<spine><itemref idref="a"/></spine></package>"""
        val f = writeEpub(
            listOf(
                container("content.opf"),
                text("content.opf", opf),
                text("css/main.css", "@import url('base.css'); .r { text-align: right }"),
                text("css/base.css", "@import \"main.css\"; .c { text-align: center } .r { text-align: left }"),
                text("t/a.xhtml", xhtml("a", "<p class=\"c\">가</p><p class=\"r\">나</p>", "<link rel=\"stylesheet\" href=\"../css/main.css\"/>")),
            ),
        )
        EpubDocuments.open(f, ParseOptions()).use { doc ->
            val s = doc.loadSection(0)
            assertEquals(Align.CENTER, (s.blocks[0] as ParagraphBlock).style.align)
            assertEquals(Align.RIGHT, (s.blocks[1] as ParagraphBlock).style.align) // importing sheet wins
        }
    }

    @Test
    fun zipLookupNormalisesAndIgnoresCase() {
        val f = writeEpub(listOf(text("OEBPS/./Text/../Images/x.txt", "x"), text("Dir/File.XHTML", "y")))
        EpubZip.open(f).use { z ->
            assertEquals("OEBPS/Images/x.txt", z.find("OEBPS/Images/x.txt"))
            assertEquals("Dir/File.XHTML", z.find("dir/file.xhtml"))
            assertNull(z.find("nope"))
            assertEquals("y", String(z.read("dir/file.xhtml")!!))
        }
    }

    @Test
    fun ncxAndNavParsersDirectly() {
        val nav = EpubTocParser.parseNav(
            "<nav><ol><li><a href=\"a.html\">A</a><ol><li><a href=\"a.html#x\">A.1</a></li></ol></li><li><a href=\"b.html\"> </a></li></ol></nav>",
        )
        assertEquals(listOf("A", "A.1"), nav.map { it.title })
        assertEquals(listOf(1, 2), nav.map { it.level })
        val ncx = EpubTocParser.parseNcx(
            "<ncx><navMap><navPoint><navLabel><text>하나</text></navLabel><navLabel><text>One</text></navLabel>" +
                "<content src=\"1.html\"/><navPoint><navLabel><text>둘</text></navLabel><content src=\"2.html\"/>" +
                "</navPoint></navPoint></navMap><pageList><pageTarget><navLabel><text>p1</text></navLabel>" +
                "<content src=\"1.html#p1\"/></pageTarget></pageList></ncx>",
        )
        assertEquals(listOf("하나", "둘"), ncx.map { it.title })
        assertEquals(listOf(1, 2), ncx.map { it.level })
        assertEquals(listOf("1.html", "2.html"), ncx.map { it.href })
    }

    @Test
    fun containerRootfile() {
        assertEquals("a/b.opf", OpfParser.rootfile(EpubTestUtil.container("a/b.opf").data.toString(Charsets.UTF_8)))
        assertNull(OpfParser.rootfile("<container/>"))
        assertEquals(
            "x.opf",
            OpfParser.rootfile("<rootfile full-path=\"x.pdf\" media-type=\"application/pdf\"/><rootfile full-path=\"x.opf\"/>"),
        )
    }

    @Test
    fun drmEncryptedContentIsRejectedButFontObfuscationIsNot() {
        val opf = """<package><metadata><dc:title>잠김</dc:title></metadata><manifest>
<item id="x" href="Text/x.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="x"/></spine></package>"""
        fun book(algorithm: String, uri: String) = writeEpub(
            listOf(
                container("OEBPS/content.opf"),
                text(
                    "META-INF/encryption.xml",
                    "<encryption xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\" " +
                        "xmlns:enc=\"http://www.w3.org/2001/04/xmlenc#\"><enc:EncryptedData>" +
                        "<enc:EncryptionMethod Algorithm=\"$algorithm\"/><enc:CipherData>" +
                        "<enc:CipherReference URI=\"$uri\"/></enc:CipherData></enc:EncryptedData></encryption>",
                ),
                text("OEBPS/content.opf", opf),
                text("OEBPS/Text/x.xhtml", xhtml("x", "<p>본문</p>")),
            ),
        )
        try {
            EpubDocuments.open(book("http://www.w3.org/2001/04/xmlenc#aes128-cbc", "OEBPS/Text/x.xhtml"), ParseOptions()).close()
            fail("expected DRM DocumentException")
        } catch (e: DocumentException) {
            assertTrue(e.message!!.contains("DRM"))
        }
        // obfuscated fonts only: opens normally, metadata still readable for DRM books
        EpubDocuments.open(book("http://www.idpf.org/2008/embedding", "OEBPS/Fonts/a.otf"), ParseOptions()).use {
            assertEquals(listOf("본문"), blockTexts(it.loadSection(0)))
        }
        assertEquals("잠김", EpubDocuments.readMeta(book("http://www.w3.org/2001/04/xmlenc#aes128-cbc", "OEBPS/Text/x.xhtml")).title)
    }

    @Test
    fun cp949EntryNamesAreReadable() {
        val cp949 = EpubText.cp949()!!
        val dir = createTempDir()
        val f = File(dir, "legacy.epub")
        java.util.zip.ZipOutputStream(java.io.FileOutputStream(f), cp949).use { zos ->
            fun put(name: String, data: String) {
                zos.putNextEntry(java.util.zip.ZipEntry(name))
                zos.write(data.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
            put("META-INF/container.xml", EpubTestUtil.container("content.opf").data.toString(Charsets.UTF_8))
            put(
                "content.opf",
                "<package><metadata><dc:title>옛날 압축</dc:title></metadata><manifest>" +
                    "<item id=\"a\" href=\"%EB%B3%B8%EB%AC%B8.xhtml\" media-type=\"application/xhtml+xml\"/>" +
                    "</manifest><spine><itemref idref=\"a\"/></spine></package>",
            )
            put("본문.xhtml", xhtml("본문", "<p>한글 파일 이름</p>"))
        }
        EpubDocuments.open(f, ParseOptions()).use { doc ->
            assertEquals("옛날 압축", doc.meta.title)
            assertEquals(listOf("한글 파일 이름"), blockTexts(doc.loadSection(0)))
        }
    }

    @Test
    fun readPrefixDecompressesOnlyTheHead() {
        val big = "<h1>머리</h1>" + "<p>${EpubTestUtil.SENTENCES[0]}</p>".repeat(5000)
        val f = writeEpub(listOf(text("a.xhtml", big)))
        EpubZip.open(f).use { z ->
            val head = z.readPrefix("a.xhtml", 100)!!
            assertEquals(100, head.size)
            assertEquals(big.toByteArray().copyOf(100).toList(), head.toList())
            assertEquals(3, z.readPrefix("mimetype", 3)!!.size)
            assertEquals("application/epub+zip".length, z.readPrefix("mimetype", 1000)!!.size)
            assertNull(z.readPrefix("nope", 10))
        }
    }

    @Test
    fun descriptionStripping() {
        assertEquals("가 나\n다", OpfParser.stripTags("<p>가   나</p><p>다</p>"))
        assertEquals("a < b", OpfParser.stripTags("a &lt; b"))
        assertEquals("x", OpfParser.stripTags("x"))
    }
}
