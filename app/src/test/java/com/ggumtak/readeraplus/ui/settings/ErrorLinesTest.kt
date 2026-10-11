package com.ggumtak.readeraplus.ui.settings

import android.database.sqlite.SQLiteConstraintException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.FileNotFoundException
import java.io.IOException
import java.util.zip.ZipException

class ErrorLinesTest {
    @Test
    fun keepsTheAppsOwnKoreanSentence() {
        val e = IllegalArgumentException("리더플러스 백업 파일이 아닙니다")
        assertEquals("복원 실패: 리더플러스 백업 파일이 아닙니다", ErrorLines.line("복원 실패", e))
        assertNull(ErrorLines.detail(e))
        assertEquals("백업 실패: 파일을 열 수 없습니다", ErrorLines.line("백업 실패", IllegalStateException("파일을 열 수 없습니다")))
        assertEquals("비우지 못했습니다: 파일 3개가 지워지지 않습니다",
            ErrorLines.line("비우지 못했습니다", IOException("파일 3개가 지워지지 않습니다")))
    }

    @Test
    fun platformErrorsReadAsUserMessagesWithAClassDetail() {
        val fnf = FileNotFoundException("/storage/emulated/0/책/소설.txt: open failed: ENOENT (No such file or directory)")
        assertEquals("파일을 열 수 없습니다: 파일을 찾을 수 없습니다", ErrorLines.line("파일을 열 수 없습니다", fnf))
        assertEquals("자세히: FileNotFoundException", ErrorLines.detail(fnf))
        assertEquals("스캔 실패: 파일을 읽거나 쓰지 못했습니다", ErrorLines.line("스캔 실패", IOException("EIO")))
        assertEquals("가져오기 실패: 파일이 손상되었습니다", ErrorLines.line("가져오기 실패", ZipException("invalid LOC header")))
        assertEquals("공유할 수 없습니다: 파일 접근 권한이 없습니다",
            ErrorLines.line("공유할 수 없습니다", SecurityException("Permission Denial: opening provider x")))
    }

    @Test
    fun aFullDiskWinsOverAKoreanWrapper() {
        val t = IOException("복사 실패", IOException("write failed: ENOSPC (No space left on device)"))
        assertNull(ErrorLines.ownReason(t))
        assertEquals("백업 실패: 저장 공간이 부족합니다", ErrorLines.line("백업 실패", t))
    }

    @Test
    fun theCatchAllAddsNothing() {
        assertEquals("저장하지 못했습니다", ErrorLines.line("저장하지 못했습니다", SQLiteConstraintException("UNIQUE constraint failed: collections.name")))
        assertEquals("스캔 실패", ErrorLines.line("스캔 실패", IllegalStateException("Unknown URI content://x")))
        assertEquals("스캔 실패", ErrorLines.line("스캔 실패", RuntimeException()))
        assertNull(ErrorLines.reason(NullPointerException()))
        assertEquals("자세히: NullPointerException", ErrorLines.detail(NullPointerException()))
    }

    @Test
    fun ownReasonRejectsPathsWrappedMessagesAndLongText() {
        assertNull(ErrorLines.ownReason(IOException("/sdcard/책.txt")))
        assertNull(ErrorLines.ownReason(RuntimeException("java.io.IOException: 읽기 실패")))
        assertNull(ErrorLines.ownReason(IllegalArgumentException("bad input")))
        assertNull(ErrorLines.ownReason(IllegalArgumentException("   ")))
        assertNull(ErrorLines.ownReason(IllegalArgumentException("가".repeat(81))))
        assertNull(ErrorLines.ownReason(OutOfMemoryError("메모리")))
        assertEquals("컬렉션 이름이 비어 있습니다", ErrorLines.ownReason(IllegalArgumentException(" 컬렉션 이름이 비어 있습니다 ")))
    }

    @Test
    fun linesNeverCarryRawText() {
        val cases = listOf(
            FileNotFoundException("/storage/emulated/0/Download/a.epub"),
            IllegalStateException("Unknown URI content://com.android.providers/document/1"),
            ZipException("invalid CEN header (bad signature)"),
            RuntimeException("java.lang.NullPointerException"),
        )
        for (t in cases) {
            val s = ErrorLines.line("실패", t)
            assertFalse(s, s.contains('/') || s.contains("Exception") || s.contains("URI") || s.contains("header"))
        }
    }

    @Test
    fun withDetailAddsTheClassLineOnlyForPlatformErrors() {
        assertEquals("백업 실패: 파일을 읽거나 쓰지 못했습니다\n자세히: IOException",
            ErrorLines.withDetail(ErrorLines.line("백업 실패", IOException("EIO")), IOException("EIO")))
        val own = IllegalArgumentException("백업 파일이 너무 큽니다")
        assertEquals("복원 실패: 백업 파일이 너무 큽니다", ErrorLines.withDetail(ErrorLines.line("복원 실패", own), own))
    }
}
