package com.ggumtak.readeraplus.reader.extras

import com.ggumtak.readeraplus.format.txt.TxtDocuments
import com.ggumtak.readeraplus.reader.ReaderFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.FileNotFoundException
import java.io.IOException

/** Search status text (A4) and the error texts that replace raw exception messages (A11). */
class ExtrasMessagesTest {

    @Test
    fun searchPercentIsFlooredAndBelow100WhileRunning() {
        assertEquals(0, SearchText.percent(0, 10))
        assertEquals(34, SearchText.percent(349, 1000))
        assertEquals(99, SearchText.percent(999, 1000))
        assertEquals(99, SearchText.percent(1000, 1000))
        assertEquals(0, SearchText.percent(5, 0))
        assertEquals(50, SearchText.percent(Int.MAX_VALUE / 2, Int.MAX_VALUE - 1))
    }

    @Test
    fun searchStatusTexts() {
        assertEquals("검색 중 0%", SearchText.status(0, 120, 0, complete = false, capped = false, max = 1000))
        assertEquals("검색 중 34% · 8개", SearchText.status(41, 120, 8, complete = false, capped = false, max = 1000))
        assertEquals("57개 결과", SearchText.status(120, 120, 57, complete = true, capped = false, max = 1000))
        assertEquals("결과 없음", SearchText.status(120, 120, 0, complete = true, capped = false, max = 1000))
        assertEquals("1000개 이상 (앞 1000개만 표시)", SearchText.status(80, 120, 1000, complete = true, capped = true, max = 1000))
        assertEquals("‘등불’이 들어간 곳이 없습니다", SearchText.noHits("등불"))
        assertEquals("‘바다’가 들어간 곳이 없습니다", SearchText.noHits("바다"))
    }

    @Test
    fun searchFlushesAtMostTwiceASecond() {
        assertTrue(SearchPanel.FLUSH_MS >= 500L)
    }

    @Test
    fun fontImportKeepsTheAppsOwnKoreanReason() {
        assertEquals("글꼴을 추가할 수 없습니다: TTF/OTF 글꼴 파일이 아닙니다",
            ErrorText.fontImport(IllegalArgumentException("TTF/OTF 글꼴 파일이 아닙니다")))
        assertEquals("글꼴을 추가할 수 없습니다: 글꼴 폴더를 만들 수 없습니다",
            ErrorText.fontImport(IOException("글꼴 폴더를 만들 수 없습니다")))
    }

    @Test
    fun fontImportNeverShowsPlatformText() {
        val fnf = ErrorText.fontImport(FileNotFoundException("/storage/emulated/0/Download/x.ttf: open failed: ENOENT"))
        assertEquals("글꼴을 추가할 수 없습니다: 파일을 찾을 수 없습니다", fnf)
        assertEquals("글꼴을 추가할 수 없습니다: 파일 접근 권한이 없습니다",
            ErrorText.fontImport(SecurityException("Permission Denial: opening provider com.android.x")))
        assertEquals("글꼴을 추가할 수 없습니다: 저장 공간이 부족합니다",
            ErrorText.fontImport(IOException("write failed: ENOSPC (No space left on device)")))
        // A full disk wins over a Korean message, and nothing useful to add leaves only the first part.
        assertEquals("글꼴을 추가할 수 없습니다: 저장 공간이 부족합니다",
            ErrorText.fontImport(IOException("복사 실패", IOException("ENOSPC"))))
        assertEquals("글꼴을 추가할 수 없습니다", ErrorText.fontImport(IllegalStateException("Unknown URI content://x")))
        assertEquals("글꼴을 추가할 수 없습니다", ErrorText.fontImport(RuntimeException()))
        // Korean in a platform message (a file name) does not make it the app's own sentence.
        assertEquals("글꼴을 추가할 수 없습니다: 파일을 찾을 수 없습니다",
            ErrorText.fontImport(FileNotFoundException("/storage/emulated/0/Download/나눔명조.ttf: open failed: EACCES (Permission denied)")))
        assertEquals("글꼴을 추가할 수 없습니다: 파일을 찾을 수 없습니다",
            ErrorText.fontImport(FileNotFoundException("Missing file for primary:Download/나눔글꼴.ttf at /storage/emulated/0/Download/나눔글꼴.ttf")))
        for (t in listOf(IllegalStateException("Unknown URI"), FileNotFoundException("/sdcard/a.ttf"))) {
            val s = ErrorText.fontImport(t)
            assertFalse(s, s.contains("URI") || s.contains("/sdcard") || s.contains("Exception"))
        }
    }

    @Test
    fun ownReasonOnlyForKoreanMessages() {
        assertEquals("글꼴 파일이 너무 큽니다", ErrorText.ownReason(IllegalArgumentException(" 글꼴 파일이 너무 큽니다 ")))
        assertNull(ErrorText.ownReason(IllegalArgumentException("bad font")))
        assertNull(ErrorText.ownReason(IllegalArgumentException()))
        assertNull(ErrorText.ownReason(OutOfMemoryError("메모리")))
    }

    @Test
    fun oneEncodingHasOneWordingInTheReader() {
        // The reading-settings popup names an encoding like the error panel's chooser and the library.
        for (e in listOf("") + TxtDocuments.ENCODINGS) {
            assertEquals(e, ReaderFormat.encodingLabel(e), ReadingSettingsPopup.encodingLabel(e))
        }
        assertEquals("CP949 (한국어 확장 완성형)", ReadingSettingsPopup.encodingLabel("MS949"))
        assertEquals("CP949", ReadingSettingsPopup.encodingShort("MS949"))
        assertEquals("UTF-16 LE", ReadingSettingsPopup.encodingShort("UTF-16LE"))
        assertEquals("자동 감지", ReadingSettingsPopup.encodingShort(""))
    }

    @Test
    fun regexErrorsAreKorean() {
        val unclosed = runCatching { Regex("^제(\\d+") }.exceptionOrNull()!!
        assertEquals("정규식이 올바르지 않습니다 (끝 부분)", ErrorText.regex(unclosed))
        val dangling = runCatching { Regex("*화") }.exceptionOrNull()!!
        assertEquals("정규식이 올바르지 않습니다 (1번째 글자 근처)", ErrorText.regex(dangling))
        assertEquals("정규식이 올바르지 않습니다", ErrorText.regex(IllegalArgumentException("x")))
    }
}
