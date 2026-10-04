package com.ggumtak.readeraplus.ui.settings

import com.ggumtak.readeraplus.data.AutoBackup
import com.ggumtak.readeraplus.engine.PageBreakMode
import com.ggumtak.readeraplus.settings.AppSettings
import com.ggumtak.readeraplus.settings.HL_LOOK_AUTO
import com.ggumtak.readeraplus.settings.HL_LOOK_COLOR
import com.ggumtak.readeraplus.settings.HL_LOOK_INK
import com.ggumtak.readeraplus.settings.LIST_PAGING_AUTO
import com.ggumtak.readeraplus.settings.LIST_PAGING_PAGED
import com.ggumtak.readeraplus.settings.LIST_PAGING_SCROLL
import com.ggumtak.readeraplus.settings.LibraryListMode
import com.ggumtak.readeraplus.settings.ReadMode
import com.ggumtak.readeraplus.settings.ReaderSettings
import com.ggumtak.readeraplus.settings.ScrollStyle
import com.ggumtak.readeraplus.settings.StatusItem
import com.ggumtak.readeraplus.settings.TapAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class SettingsFormatTest {
    @Test
    fun bytes() {
        assertEquals("0 B", SettingsFormat.bytes(0))
        assertEquals("1023 B", SettingsFormat.bytes(1023))
        assertEquals("1.0 KB", SettingsFormat.bytes(1024))
        assertEquals("12.3 MB", SettingsFormat.bytes((12.3 * 1024 * 1024).toLong()))
        assertEquals("150 MB", SettingsFormat.bytes(150L * 1024 * 1024))
        assertEquals("2.0 GB", SettingsFormat.bytes(2L * 1024 * 1024 * 1024))
    }

    @Test
    fun seconds() {
        assertEquals("5초", SettingsFormat.seconds(5))
        assertEquals("1분", SettingsFormat.seconds(60))
        assertEquals("1분 30초", SettingsFormat.seconds(90))
        assertEquals("5분", SettingsFormat.seconds(300))
    }

    @Test
    fun refreshAndSleep() {
        assertEquals("끔", SettingsFormat.refreshEvery(0))
        assertEquals("5쪽마다", SettingsFormat.refreshEvery(5))
        assertEquals("끔", SettingsFormat.sleep(0))
        assertEquals("15분", SettingsFormat.sleep(15))
        assertEquals("1시간", SettingsFormat.sleep(60))
        assertEquals("1시간 30분", SettingsFormat.sleep(90))
    }

    @Test
    fun numbers() {
        assertEquals("1.0배", SettingsFormat.rate(1f))
        assertEquals("1.3배", SettingsFormat.rate(1.3000001f))
        assertEquals("0.8", SettingsFormat.pitch(0.8f))
        assertEquals("11sp", SettingsFormat.sp(11f))
        assertEquals("11.5sp", SettingsFormat.sp(11.5f))
    }

    @Test
    fun backupName() {
        val utc = TimeZone.getTimeZone("UTC")
        // 2026-09-29T12:00:00Z
        assertEquals("readeraplus-backup-20260929.json", SettingsFormat.backupFileName(1790683200000L, utc))
        assertEquals("2026-09-29 12:00", SettingsFormat.dateTime(1790683200000L, utc))
        // Local date: 23:30 UTC is already the next day in Seoul.
        val seoul = TimeZone.getTimeZone("Asia/Seoul")
        assertEquals("readeraplus-backup-20260930.json", SettingsFormat.backupFileName(1790724600000L, seoul))
    }

    @Test
    fun orientation() {
        assertEquals("자동 회전", SettingsFormat.orientation(-1))
        assertEquals("세로", SettingsFormat.orientation(1))
        assertEquals("자동 회전", SettingsFormat.orientation(12345))
    }

    @Test
    fun webEngines() {
        assertEquals(0, WebEngines.indexOf("https://www.google.com/search?q=%s"))
        assertEquals("네이버", WebEngines.nameOf("https://search.naver.com/search.naver?query=%s"))
        assertEquals(-1, WebEngines.indexOf("https://example.com/?q=%s"))
        assertEquals("사용자 지정", WebEngines.nameOf("https://example.com/?q=%s"))
        assertEquals("https://example.com/?q=%s", WebEngines.normalizeTemplate(" example.com/?q=%s "))
        assertEquals("http://x.org/%s", WebEngines.normalizeTemplate("http://x.org/%s"))
        assertNull(WebEngines.normalizeTemplate("https://example.com/"))
        assertNull(WebEngines.normalizeTemplate("ftp://x/%s"))
        assertNull(WebEngines.normalizeTemplate("https://a b/%s"))
        assertEquals(
            "https://search.naver.com/search.naver?query=%ED%95%9C%EA%B8%80%20a%2Bb",
            WebEngines.build("https://search.naver.com/search.naver?query=%s", "한글 a+b"),
        )
    }

    @Test
    fun sleepChoicesIncludeEpisodes() {
        // 끔 / 15 / 30 / 45 / 60 / 90분 / 이 화 끝까지 / 2화 끝까지 (T1-11).
        assertEquals(
            listOf("끔", "15분", "30분", "45분", "1시간", "1시간 30분", "이 화 끝까지", "2화 끝까지"),
            SettingsFormat.SLEEP_CHOICES.map { (m, c) -> SettingsFormat.sleepChoice(m, c) },
        )
        // A chapter choice stores minutes 0; chapters win when both are set.
        assertEquals(0 to 1, SettingsFormat.SLEEP_CHOICES[6])
        assertEquals("이 화 끝까지", SettingsFormat.sleepChoice(30, 1))
        assertEquals("3화 끝까지", SettingsFormat.sleepChoice(0, 3))
        assertEquals(0, SettingsFormat.sleepIndex(0, 0))
        assertEquals(4, SettingsFormat.sleepIndex(60, 0))
        assertEquals(6, SettingsFormat.sleepIndex(0, 1))
        assertEquals(7, SettingsFormat.sleepIndex(45, 2))
        // Values no choice offers (an older build's 10 / 120분, a restored 3화) select nothing.
        assertEquals(-1, SettingsFormat.sleepIndex(10, 0))
        assertEquals(-1, SettingsFormat.sleepIndex(0, 3))
    }

    @Test
    fun longPressTimes() {
        assertEquals(listOf("0.4초", "0.5초", "0.7초", "1.0초"), SettingsFormat.LONG_PRESS_OPTIONS.map { SettingsFormat.longPress(it) })
        assertEquals("0.5초 (기본)", SettingsFormat.longPressChoice(AppSettings().longPressMs))
        assertEquals("0.7초", SettingsFormat.longPressChoice(700))
        assertTrue(AppSettings().longPressMs in SettingsFormat.LONG_PRESS_OPTIONS)
    }


    @Test
    fun receivedLine() {
        assertEquals("12.3 MB · 서재에 추가됨", SettingsFormat.received((12.3 * 1024 * 1024).toLong(), added = true))
        assertEquals("500 B · 서재에 추가하지 못함", SettingsFormat.received(500, added = false))
    }

    // ---------------------------------------------------------------- R3 rows

    @Test
    fun autoChoicesShowWhatTheyResolveTo() {
        // true = e-ink, false = phone, null = not probed yet (resolves like a phone, as the reader and library do).
        assertEquals("자동 (이 기기: 흑백 무늬)", R3Rows.highlightLook(HL_LOOK_AUTO, true))
        assertEquals("자동 (이 기기: 색)", R3Rows.highlightLook(HL_LOOK_AUTO, false))
        assertEquals("자동 (이 기기: 색)", R3Rows.highlightLook(HL_LOOK_AUTO, null))
        assertEquals("자동 (이 기기: 쪽 단위)", R3Rows.listPaging(LIST_PAGING_AUTO, true))
        assertEquals("자동 (이 기기: 스크롤)", R3Rows.listPaging(LIST_PAGING_AUTO, false))
        assertEquals("자동 (이 기기: 스크롤)", R3Rows.listPaging(LIST_PAGING_AUTO, null))
        assertEquals("자동 (이 기기: e-ink → 손을 떼면 이동)", R3Rows.scrollStyle(ScrollStyle.AUTO, true))
        assertEquals("자동 (이 기기: 휴대폰 → 손가락을 따라 이동)", R3Rows.scrollStyle(ScrollStyle.AUTO, false))
        assertEquals("자동 (이 기기: 휴대폰 → 손가락을 따라 이동)", R3Rows.scrollStyle(ScrollStyle.AUTO, null))
        // Fixed choices don't depend on the device.
        for (eink in listOf(true, false, null)) {
            assertEquals(listOf("색", "흑백 무늬"), R3Rows.HL_LOOKS.drop(1).map { R3Rows.highlightLook(it, eink) })
            assertEquals(listOf("쪽 단위", "스크롤"), R3Rows.LIST_PAGINGS.drop(1).map { R3Rows.listPaging(it, eink) })
            assertEquals(
                listOf("손가락을 따라 이동 (휴대폰)", "손을 떼면 이동 (e-ink)"),
                R3Rows.SCROLL_STYLES.drop(1).map { R3Rows.scrollStyle(it, eink) },
            )
        }
        assertEquals(listOf(HL_LOOK_AUTO, HL_LOOK_COLOR, HL_LOOK_INK), R3Rows.HL_LOOKS)
        assertEquals(listOf(LIST_PAGING_AUTO, LIST_PAGING_PAGED, LIST_PAGING_SCROLL), R3Rows.LIST_PAGINGS)
        assertEquals("자동 (이 기기: 쪽 단위) · 서재와 독서 노트를 한 화면씩 넘깁니다 (e-ink 권장)", R3Rows.listPagingSummary(LIST_PAGING_AUTO, true))
    }

    @Test
    fun inkLookFollowsTheChoice() {
        assertTrue(R3Rows.inkLook(HL_LOOK_INK, false))
        assertFalse(R3Rows.inkLook(HL_LOOK_COLOR, true))
        assertTrue(R3Rows.inkLook(HL_LOOK_AUTO, true))
        assertFalse(R3Rows.inkLook(HL_LOOK_AUTO, null))
    }

    @Test
    fun readModeAndViews() {
        assertEquals(listOf("페이지 넘김 (기본)", "스크롤"), R3Rows.READ_MODES.map { R3Rows.readMode(it) })
        assertEquals(ReadMode.entries.toSet(), R3Rows.READ_MODES.toSet())
        assertEquals(ScrollStyle.entries.toSet(), R3Rows.SCROLL_STYLES.toSet())
        assertEquals(
            listOf("전체 — 표지 · 정보 · 버튼", "요약 — 작은 표지와 한 줄 정보", "썸네일 — 표지 3열", "그리드 — 작은 표지 4열"),
            LibraryListMode.entries.map { R3Rows.libraryViewChoice(it) },
        )
    }

    @Test
    fun pageBreakChoices() {
        assertEquals(listOf("줄 단위", "문단 단위"), R3Rows.PAGE_BREAKS.map { R3Rows.pageBreak(it) })
        assertEquals("줄 단위", R3Rows.pageBreak(ReaderSettings().pageBreak))
        assertEquals("줄 단위 (기본) — 쪽을 끝까지 채웁니다. 문단이 다음 쪽으로 이어질 수 있습니다.", R3Rows.pageBreakChoice(PageBreakMode.LINE))
        assertEquals("문단 단위 — 한 쪽에 들어가는 문단은 나누지 않습니다. 쪽 아래가 비기도 합니다.", R3Rows.pageBreakChoice(PageBreakMode.PARAGRAPH))
        assertEquals(PageBreakMode.entries.toSet(), R3Rows.PAGE_BREAKS.toSet())
    }

    @Test
    fun slotLabels() {
        val titles = (0..1).flatMap { b -> (0..2).map { p -> R3Rows.slotTitle(b, p) } }
        assertEquals(listOf("위 · 왼쪽", "위 · 가운데", "위 · 오른쪽", "아래 · 왼쪽", "아래 · 가운데", "아래 · 오른쪽"), titles)
        assertEquals("없음", R3Rows.slotChoice(StatusItem.NONE))
        assertEquals("시계  (14:05)", R3Rows.slotChoice(StatusItem.CLOCK))
        assertEquals("쪽 번호  (12 / 3259)", R3Rows.slotChoice(StatusItem.PAGE))
    }

    @Test
    fun statusFitNote() {
        val r = ReaderSettings()
        // Defaults: 40 dp top and bottom hold the header and the progress lane.
        assertTrue(R3Rows.statusFits(r))
        assertTrue(R3Rows.hasStatusText(r))
        // No text in any band: nothing to fit, and no size row.
        val none = r.withSlot(0, 1, StatusItem.NONE)
        assertFalse(R3Rows.hasStatusText(none))
        assertTrue(R3Rows.statusFits(none.copy(marginTopDp = 0, marginBottomDp = 0)))
        // A header in a 4 dp margin does not fit.
        assertFalse(R3Rows.statusFits(r.copy(marginTopDp = 4)))
        // 페이지 여백 off: bands get 4 dp.
        assertFalse(R3Rows.statusFits(r.copy(pageMargins = false)))
        // Footer text: the progress lane takes 12 dp of the bottom margin.
        val footer = none.withSlot(1, 2, StatusItem.CLOCK)
        assertTrue(R3Rows.statusFits(footer.copy(marginBottomDp = 26, progressBar = false)))
        assertFalse(R3Rows.statusFits(footer.copy(marginBottomDp = 26, progressBar = true)))
    }

    @Test
    fun brightnessSubtitles() {
        assertEquals("화면 좌측을 위아래로 스와이프하여 밝기를 조절합니다", R3Rows.brightnessSwipe(scroll = false, none = false))
        assertEquals("화면 왼쪽 끝(10%)을 위아래로 끌면 밝기 · 나머지는 스크롤", R3Rows.brightnessSwipe(scroll = true, none = false))
        assertEquals("이 기기에서는 밝기 스와이프를 쓸 수 없습니다", R3Rows.brightnessSwipe(scroll = true, none = true))
        assertEquals("전면광이 안 바뀔 때 켜세요 · 기기 전체 밝기를 바꿉니다", R3Rows.brightnessDevice(false, true, true, false))
        assertEquals("기기 전체 밝기를 바꿉니다 · 리더를 나가면 원래대로", R3Rows.brightnessDevice(true, true, true, false))
        assertEquals("기기 전체 밝기를 바꿉니다 · 나가도 그대로 유지", R3Rows.brightnessDevice(true, false, true, false))
        assertEquals("'시스템 설정 수정' 권한이 필요합니다 · 눌러서 허용", R3Rows.brightnessDevice(true, true, false, false))
        assertEquals("이 기기는 앱이 전면광을 바꿀 수 없습니다", R3Rows.brightnessDevice(true, true, true, true))
        // UI_SPEC §4.3 fix 2: an automatic original comes back on leave even when the level stays.
        assertEquals("기기 전체 밝기를 바꿉니다 · 나가도 그대로 유지 (자동 밝기는 다시 켜짐)",
            R3Rows.brightnessDevice(true, false, true, false, origAuto = true))
        assertEquals("기기 전체 밝기를 바꿉니다 · 리더를 나가면 원래대로", R3Rows.brightnessDevice(true, true, true, false, origAuto = true))
        assertEquals("전면광이 안 바뀔 때 켜세요 · 기기 전체 밝기를 바꿉니다", R3Rows.brightnessDevice(false, false, true, false, origAuto = true))
        assertTrue(R3Rows.NO_PERMISSION_SCREEN.endsWith("adb shell appops set com.ggumtak.readeraplus WRITE_SETTINGS allow"))
    }

    @Test
    fun autoBackupTexts() {
        val utc = TimeZone.getTimeZone("UTC")
        val loc = "다운로드/ReaderaPlus/backup"
        assertEquals(
            "앱을 지워도 남는 곳에 저장 · 다운로드/ReaderaPlus/backup · 마지막: 2026-09-29 12:00",
            R3Rows.autoBackupSummary(true, loc, 1790683200000L, utc),
        )
        assertEquals("앱을 지워도 남는 곳에 저장 · 다운로드/ReaderaPlus/backup", R3Rows.autoBackupSummary(true, loc, 0L, utc))
        assertEquals(
            "모든 파일 접근 권한이 없어 다운로드/ReaderaPlus/backup에 저장합니다. 다시 설치한 뒤에는 권한을 허용해야 자동으로 찾습니다.",
            R3Rows.autoBackupSummary(false, loc, 1790683200000L, utc),
        )
        for (o in AutoBackup.Outcome.entries) assertTrue(R3Rows.autoBackupOutcome(o, loc).isNotBlank())
        assertEquals(
            "2026-09-29 12:00 · 책 12권 (읽던 책 3권) · 북마크 4개 · 인용문 5개 · 자동",
            R3Rows.candidate(1790683200000L, AutoBackup.Summary(12, 3, 4, 5), auto = true, tz = utc),
        )
        assertTrue(R3Rows.candidate(0L, AutoBackup.Summary(0, 0, 0, 0), auto = false, tz = utc).endsWith("직접 내보냄"))
        assertEquals("자동 백업 파일 3개를 지울까요? 이전 설치의 파일도 함께 지웁니다.", R3Rows.deleteAutoFiles(3, others = true))
        assertTrue(R3Rows.deleteAutoFiles(2, others = false).startsWith("이 설치에서 만든 자동 백업 파일 2개를"))
        assertTrue(R3Rows.BACKUP_PRIVACY.contains("단어장"))
        assertTrue(R3Rows.BACKUP_MERGE.endsWith("이 기기에 없는 책의 노트는 휴지통에 '(파일 없음)'으로 보관됩니다."))
        assertEquals("찾아본 단어 7개를 모두 지울까요?", R3Rows.clearLookups(7))
    }

    @Test
    fun resetKeepsPrivacyAndDeviceChoices() {
        val old = AppSettings(
            autoBackup = false,
            recordLookups = false,
            brightnessDevice = true,
            listPaging = LIST_PAGING_SCROLL,
            libraryListMode = LibraryListMode.COVERS,
            keyBindings = mapOf(24 to TapAction.TOC),
            scanFolders = setOf("/storage/emulated/0/Books"),
            readMode = ReadMode.SCROLL,
            swipeToTurn = false,
            highlightLook = HL_LOOK_INK,
        )
        val a = SettingsReset.app(old)
        assertFalse(a.autoBackup)
        assertFalse(a.recordLookups)
        assertTrue(a.brightnessDevice)
        assertEquals(LIST_PAGING_SCROLL, a.listPaging)
        assertEquals(LibraryListMode.COVERS, a.libraryListMode)
        assertEquals(old.keyBindings, a.keyBindings)
        assertEquals(old.scanFolders, a.scanFolders)
        // Reading choices go back to the defaults.
        assertEquals(ReadMode.PAGED, a.readMode)
        assertTrue(a.swipeToTurn)
        assertEquals(HL_LOOK_AUTO, a.highlightLook)
        // Every kept field is named in the dialog.
        for (word in listOf("자동 백업", "찾아본 단어 기록", "기기 밝기 직접 조절", "목록 넘기기", "지정한 키", "스캔 폴더", "TXT 정리 설정")) {
            assertTrue(word, SettingsReset.MESSAGE.contains(word))
        }

        val r = SettingsReset.reader(
            ReaderSettings(
                marginLeftDp = 10, marginRightDp = 10, marginTopDp = 70, marginBottomDp = 70,
                pageBreak = PageBreakMode.PARAGRAPH, footerRight = StatusItem.CLOCK, headerCenter = StatusItem.NONE,
                txtReplaceRules = "a=>b", txtDetectChapters = false,
            ),
        )
        assertEquals(listOf(40, 40, 40, 40), listOf(r.marginLeftDp, r.marginRightDp, r.marginTopDp, r.marginBottomDp))
        assertEquals(PageBreakMode.LINE, r.pageBreak)
        assertEquals(StatusItem.CHAPTER, r.headerCenter)
        assertEquals(StatusItem.NONE, r.footerRight)
        assertEquals("a=>b", r.txtReplaceRules)
        assertFalse(r.txtDetectChapters)
    }
}
