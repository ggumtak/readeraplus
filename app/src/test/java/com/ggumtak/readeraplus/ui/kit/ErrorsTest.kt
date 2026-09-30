package com.ggumtak.readeraplus.ui.kit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.FileNotFoundException
import java.io.IOException

/** [ownMessage]: only the app's own Korean sentences reach users; platform text never does, even with Korean in it. */
class ErrorsTest {

    @Test
    fun keepsTheAppsOwnSentences() {
        assertEquals("TTF/OTF 글꼴 파일이 아닙니다", ownMessage(IllegalArgumentException("TTF/OTF 글꼴 파일이 아닙니다")))
        assertEquals("글꼴 파일을 읽을 수 없습니다 (손상된 파일)", ownMessage(IllegalArgumentException("글꼴 파일을 읽을 수 없습니다 (손상된 파일)")))
        assertEquals("글꼴 폴더를 만들 수 없습니다", ownMessage(IOException(" 글꼴 폴더를 만들 수 없습니다 ")))
    }

    @Test
    fun platformMessagesWithKoreanFileNamesAreNotOwn() {
        assertNull(ownMessage(FileNotFoundException("/storage/emulated/0/Download/나눔명조.ttf: open failed: EACCES (Permission denied)")))
        assertNull(ownMessage(FileNotFoundException("Missing file for primary:Download/나눔글꼴.ttf at /storage/emulated/0/Download/나눔글꼴.ttf")))
        assertNull(ownMessage(FileNotFoundException("primary:Download/나눔글꼴.ttf")))
        assertNull(ownMessage(IOException("글꼴.ttf 열기 실패: content://com.android.providers/document/1")))
        assertNull(ownMessage(IOException("Books/소설/1권.txt")))
        assertNull(ownMessage(RuntimeException("java.io.IOException: 읽기 실패")))
        assertNull(ownMessage(IllegalArgumentException("가".repeat(81))))
    }

    @Test
    fun englishEmptyAndResourceErrorsAreNotOwn() {
        assertNull(ownMessage(IllegalArgumentException("bad font")))
        assertNull(ownMessage(IllegalArgumentException("  ")))
        assertNull(ownMessage(IllegalArgumentException()))
        assertNull(ownMessage(OutOfMemoryError("메모리")))
        assertNull(ownMessage(IOException("복사 실패", IOException("write failed: ENOSPC (No space left on device)"))))
    }
}
