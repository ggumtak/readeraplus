package com.ggumtak.readeraplus.reader

import com.ggumtak.readeraplus.engine.OBJECT_CHAR
import com.ggumtak.readeraplus.format.DocumentException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.FileNotFoundException
import java.io.IOException
import java.util.zip.ZipException

class ReaderFormatTest {

    @Test
    fun pageLabels() {
        // plain numbers, estimated or not (no "~")
        assertEquals("12 / 3259", ReaderFormat.pageLabel(12, 3259))
        assertEquals("40 / 120", ReaderFormat.pageLabel(40, 120))
        // a total below the page (estimates) never shows "50 / 30"
        assertEquals("50 / 50", ReaderFormat.pageLabel(50, 30))
    }

    @Test
    fun percent() {
        assertEquals(0, ReaderFormat.percent(0f))
        assertEquals(34, ReaderFormat.percent(0.345f))
        assertEquals(29, ReaderFormat.percent(0.29f))
        assertEquals(100, ReaderFormat.percent(1f))
        assertEquals(100, ReaderFormat.percent(1.4f))
        assertEquals(0, ReaderFormat.percent(-1f))
    }

    @Test
    fun clock() {
        assertEquals("14:05", ReaderFormat.clock(14, 5, true))
        assertEquals("09:30", ReaderFormat.clock(9, 30, true))
        assertEquals("2:05", ReaderFormat.clock(14, 5, false))
        assertEquals("12:00", ReaderFormat.clock(0, 0, false))
        assertEquals("12:59", ReaderFormat.clock(12, 59, false))
    }

    @Test
    fun footers() {
        // The battery is drawn by the renderer as an icon + digits, never as a second bare percent here.
        assertEquals("34%  ·  14:05", ReaderFormat.footerRight(34, "14:05"))
        assertEquals("14:05", ReaderFormat.footerRight(null, "14:05"))
        assertEquals("100%", ReaderFormat.footerRight(100, null))
        assertNull(ReaderFormat.footerRight(null, null))
        assertEquals("12 / 3259", ReaderFormat.footerLeft("12 / 3259", null))
        assertEquals("12 / 3259  ·  챕터 5쪽 남음", ReaderFormat.footerLeft("12 / 3259", 5))
        assertEquals("챕터 마지막 쪽", ReaderFormat.footerLeft(null, 0))
        assertNull(ReaderFormat.footerLeft(null, null))
    }

    @Test
    fun snippets() {
        val text = "첫 줄입니다.\n\n  둘째   줄$OBJECT_CHAR 셋째 줄."
        assertEquals("첫 줄입니다. 둘째 줄 셋째 줄.", ReaderFormat.snippet(text, 0, text.length))
        val long = "가".repeat(200)
        val s = ReaderFormat.snippet(long, 0, long.length, max = 80)
        assertEquals(81, s.length)
        assertTrue(s.endsWith("…"))
        assertEquals("", ReaderFormat.snippet("abc", 5, 2))
        assertEquals("bc", ReaderFormat.snippet("abc", 1, 99))
    }

    @Test
    fun chipsAndPreview() {
        assertEquals("← 돌아가기 (p. 12)", ReaderFormat.returnChip(12))
        assertEquals("p. 7 · 3화 등불", ReaderFormat.previewLabel(7, " 3화 등불 "))
        assertEquals("p. 7", ReaderFormat.previewLabel(7, null))
        // nothing the reader formats shows a tilde any more
        for (s in listOf(
            ReaderFormat.pageLabel(3, 9), ReaderFormat.returnChip(3), ReaderFormat.previewLabel(3, "제목"),
            ReaderFormat.footerLeft(ReaderFormat.pageLabel(3, 9), 4)!!, ReaderFormat.chapterLeft(2),
        )) {
            assertTrue(s, '~' !in s)
        }
        assertEquals("밝기 40%", ReaderFormat.brightness(0.4f))
        assertEquals("밝기 자동", ReaderFormat.brightness(-1f))
        assertEquals("자동 넘김 켜짐 (30초)", ReaderFormat.autoTurnOn(30))
    }

    @Test
    fun openErrorsAreFriendly() {
        assertEquals("파일을 찾을 수 없습니다", ReaderFormat.openError(FileNotFoundException("/storage/x.txt: open failed: ENOENT")))
        assertEquals("EPUB 파일이 손상되었습니다", ReaderFormat.openError(ZipException("invalid LOC header")))
        assertEquals("파일을 읽지 못했습니다", ReaderFormat.openError(IOException("read failed: EIO")))
        assertEquals("책을 열지 못했습니다", ReaderFormat.openError(IllegalStateException("boom")))
        assertEquals("책을 열지 못했습니다", ReaderFormat.openError(NullPointerException()))
        assertEquals("메모리가 부족합니다", ReaderFormat.openError(OutOfMemoryError()))
        assertEquals("파일 접근 권한이 없습니다", ReaderFormat.openError(SecurityException("Permission denial")))
        assertEquals("저장 공간이 부족합니다", ReaderFormat.openError(IOException("write failed: ENOSPC (No space left on device)")))
        // Our own messages are kept as they are.
        assertEquals("내용이 없는 문서입니다.", ReaderFormat.openError(DocumentException("내용이 없는 문서입니다.")))
        assertEquals(
            "손상된 EPUB 파일입니다: a.epub",
            ReaderFormat.openError(DocumentException("손상된 EPUB 파일입니다: a.epub", ZipException("bad"))),
        )
        // Never an exception message or class name in the message itself.
        for (t in listOf(IOException("secret/path"), RuntimeException("x"), ZipException("y"))) {
            val m = ReaderFormat.openError(t)
            assertTrue(m, "Exception" !in m && "secret" !in m)
        }
    }

    @Test
    fun openErrorDetailNamesTheClass() {
        assertEquals("자세히: ZipException", ReaderFormat.openErrorDetail(ZipException("bad")))
        assertEquals("자세히: IllegalStateException", ReaderFormat.openErrorDetail(IllegalStateException("x")))
        // A DocumentException's detail is its cause; without one there is no detail line.
        assertEquals("자세히: ZipException", ReaderFormat.openErrorDetail(DocumentException("손상", ZipException("bad"))))
        assertNull(ReaderFormat.openErrorDetail(DocumentException("내용이 없는 문서입니다.")))
    }

    @Test
    fun aPathOrUriInOurMessageNeverReachesTheMessageLine() {
        val missing = DocumentException("파일을 찾을 수 없습니다.\n/storage/emulated/0/책/소설.txt")
        assertEquals("파일을 찾을 수 없습니다.", ReaderFormat.openError(missing))
        // The path is the grey detail line: it tells which file is missing.
        assertEquals("/storage/emulated/0/책/소설.txt", ReaderFormat.openErrorDetail(missing))
        // A content URI is dropped altogether (percent-encoded, unreadable).
        val uri = DocumentException("파일을 읽을 수 없습니다.\ncontent://com.android.externalstorage.documents/document/primary%3ADownload%2Fa.txt")
        assertEquals("파일을 읽을 수 없습니다.", ReaderFormat.openError(uri))
        assertNull(ReaderFormat.openErrorDetail(uri))
    }

    @Test
    fun sectionErrorsNeverShowTheExceptionMessage() {
        assertEquals("이 부분을 불러오지 못했습니다 (EPUB 파일이 손상되었습니다)",
            ReaderFormat.sectionError(ZipException("invalid entry size (expected 10 but got 5) OEBPS/ch1.xhtml")))
        assertEquals("이 부분을 불러오지 못했습니다 (파일을 읽지 못했습니다)", ReaderFormat.sectionError(IOException("EIO")))
        assertEquals("이 부분을 불러오지 못했습니다 (EPUB을 해석할 수 없습니다: a.epub)",
            ReaderFormat.sectionError(DocumentException("EPUB을 해석할 수 없습니다: a.epub", IllegalStateException("x"))))
        assertEquals("이 부분을 불러오지 못했습니다 (자세히: IllegalStateException)",
            ReaderFormat.sectionError(IllegalStateException("unexpected token <p> at OEBPS/Text/ch1.xhtml")))
        assertEquals("메모리가 부족해 이 부분을 표시하지 못했습니다.", ReaderFormat.sectionError(OutOfMemoryError()))
    }

    @Test
    fun encodingLabels() {
        assertEquals("자동 감지", ReaderFormat.encodingLabel(""))
        assertEquals("CP949 (한국어 확장 완성형)", ReaderFormat.encodingLabel("MS949"))
        assertEquals("UTF-8 (유니코드)", ReaderFormat.encodingLabel("utf-8"))
        assertEquals("Shift_JIS", ReaderFormat.encodingLabel("Shift_JIS"))
    }
}
