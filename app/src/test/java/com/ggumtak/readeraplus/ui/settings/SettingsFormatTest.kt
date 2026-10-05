package com.ggumtak.readeraplus.ui.settings

import com.ggumtak.readeraplus.data.AutoBackup
import com.ggumtak.readeraplus.engine.PageBreakMode
import com.ggumtak.readeraplus.reader.LightPolicy
import com.ggumtak.readeraplus.reader.extras.SleepChoice
import com.ggumtak.readeraplus.settings.AppSettings
import com.ggumtak.readeraplus.settings.HL_LOOK_AUTO
import com.ggumtak.readeraplus.settings.HL_LOOK_COLOR
import com.ggumtak.readeraplus.settings.HL_LOOK_INK
import com.ggumtak.readeraplus.settings.LIST_PAGING_AUTO
import com.ggumtak.readeraplus.settings.LIST_PAGING_PAGED
import com.ggumtak.readeraplus.settings.LIST_PAGING_SCROLL
import com.ggumtak.readeraplus.settings.LibraryListMode
import com.ggumtak.readeraplus.settings.PageTheme
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
        // The library card's wording everywhere (Wi-Fi's received files, 캐시 비우기), as in "200MB까지".
        assertEquals("0B", SettingsFormat.bytes(0))
        assertEquals("1023B", SettingsFormat.bytes(1023))
        assertEquals("1KB", SettingsFormat.bytes(1024))
        assertEquals("3.4MB", SettingsFormat.bytes((3.4 * 1024 * 1024).toLong()))
        assertEquals("12MB", SettingsFormat.bytes((12.3 * 1024 * 1024).toLong()))
        assertEquals("150MB", SettingsFormat.bytes(150L * 1024 * 1024))
        assertEquals("2GB", SettingsFormat.bytes(2L * 1024 * 1024 * 1024))
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
    }

    @Test
    fun backupName() {
        val utc = TimeZone.getTimeZone("UTC")
        // 2026-09-29T12:00:00Z
        assertEquals("readeraplus-backup-20260929.json", SettingsFormat.backupFileName(1790683200000L, utc))
        // Local date: 23:30 UTC is already the next day in Seoul.
        val seoul = TimeZone.getTimeZone("Asia/Seoul")
        assertEquals("readeraplus-backup-20260930.json", SettingsFormat.backupFileName(1790724600000L, seoul))
    }

    @Test
    fun datesLeaveOutThisYear() {
        val utc = TimeZone.getTimeZone("UTC")
        val at = 1790683200000L // 2026-09-29T12:00:00Z
        val laterThisYear = 1792368000000L // 2026-10-19T00:00:00Z
        val nextYear = 1800000000000L // 2027-01-15
        // This year: the month and day only, no zero padding, a 24-hour time.
        assertEquals("9월 29일 12:00", SettingsFormat.dateTime(at, utc, laterThisYear))
        assertEquals("9월 29일", SettingsFormat.date(at, utc, laterThisYear))
        // Another year: the year first.
        assertEquals("2026년 9월 29일 12:00", SettingsFormat.dateTime(at, utc, nextYear))
        assertEquals("2026년 9월 29일", SettingsFormat.date(at, utc, nextYear))
        assertEquals("1월 5일 08:05", SettingsFormat.dateTime(1799136300000L, utc, nextYear))
        // The local day: 23:30 UTC is already the next day in Seoul.
        assertEquals("9월 30일 08:30", SettingsFormat.dateTime(1790724600000L, TimeZone.getTimeZone("Asia/Seoul"), laterThisYear))
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
        assertEquals("직접 입력", WebEngines.nameOf("https://example.com/?q=%s"))
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
        // 끔, 15 … 90분, then one or two chapters (T1-11), worded as the reader's own chooser (one copy).
        assertEquals(
            listOf("끔", "15분", "30분", "45분", "1시간", "1시간 30분") + listOf(1, 2).map { SleepChoice.summary(0, it) },
            SettingsFormat.SLEEP_CHOICES.map { (m, c) -> SettingsFormat.sleepChoice(m, c) },
        )
        // A chapter choice stores minutes 0; chapters win when both are set.
        assertEquals(0 to 1, SettingsFormat.SLEEP_CHOICES[6])
        assertEquals(SleepChoice.summary(0, 1), SettingsFormat.sleepChoice(30, 1))
        assertEquals(SleepChoice.summary(0, 3), SettingsFormat.sleepChoice(0, 3))
        assertEquals(0, SettingsFormat.sleepIndex(0, 0))
        assertEquals(4, SettingsFormat.sleepIndex(60, 0))
        assertEquals(6, SettingsFormat.sleepIndex(0, 1))
        assertEquals(7, SettingsFormat.sleepIndex(45, 2))
        // Values no choice offers (an older build's 10 / 120분, a restored 3화) select nothing.
        assertEquals(-1, SettingsFormat.sleepIndex(10, 0))
        assertEquals(-1, SettingsFormat.sleepIndex(0, 3))
    }

    @Test
    fun sleepSummaryOfTheListenRow() {
        // The 듣기 설정 row: "속도 1.0배 · 음높이 1.0 · 30분 뒤 멈춤"; nothing while no timer is set.
        assertNull(SettingsFormat.sleepSummary(0, 0))
        assertEquals("30분 뒤 멈춤", SettingsFormat.sleepSummary(30, 0))
        assertEquals("1시간 30분 뒤 멈춤", SettingsFormat.sleepSummary(90, 0))
        assertEquals("이 챕터 끝나면 멈춤", SettingsFormat.sleepSummary(0, 1))
        assertEquals("다음 챕터 끝나면 멈춤", SettingsFormat.sleepSummary(30, 2))
        assertEquals("챕터 3개 끝나면 멈춤", SettingsFormat.sleepSummary(0, 3))
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
        assertEquals("12MB · 서재에 추가됨", SettingsFormat.received((12.3 * 1024 * 1024).toLong(), added = true))
        assertEquals("500B · 서재에 추가하지 못함", SettingsFormat.received(500, added = false))
    }

    // ---------------------------------------------------------------- R3 rows

    @Test
    fun autoChoicesShowWhatTheyResolveTo() {
        // true = e-ink, false = phone, null = not probed yet (resolves like a phone, as the reader and library do).
        assertEquals("자동 (흑백 무늬)", R3Rows.highlightLook(HL_LOOK_AUTO, true))
        assertEquals("자동 (색 그대로)", R3Rows.highlightLook(HL_LOOK_AUTO, false))
        assertEquals("자동 (색 그대로)", R3Rows.highlightLook(HL_LOOK_AUTO, null))
        // 목록 넘기기 has no device-dependent 자동 since 2026-10-04: the stored default scrolls everywhere.
        assertEquals("스크롤", R3Rows.listPaging(LIST_PAGING_AUTO))
        assertEquals(0, R3Rows.listPagingIndex(LIST_PAGING_AUTO))
        assertEquals(0, R3Rows.listPagingIndex(LIST_PAGING_SCROLL))
        assertEquals(1, R3Rows.listPagingIndex(LIST_PAGING_PAGED))
        // Fixed choices don't depend on the device.
        for (eink in listOf(true, false, null)) {
            assertEquals(listOf("색 그대로", "흑백 무늬"), R3Rows.HL_LOOKS.drop(1).map { R3Rows.highlightLook(it, eink) })
        }
        assertEquals(listOf(HL_LOOK_AUTO, HL_LOOK_COLOR, HL_LOOK_INK), R3Rows.HL_LOOKS)
        assertEquals(listOf(LIST_PAGING_SCROLL, LIST_PAGING_PAGED), R3Rows.LIST_PAGINGS)
        assertEquals(listOf("스크롤", "한 화면씩"), R3Rows.LIST_PAGINGS.map { R3Rows.listPaging(it) })
    }

    @Test
    fun scrollMotionHasTwoChoices() {
        // Follow the finger (the default, on every device since 2026-10-04) or move on release.
        assertEquals(listOf(ScrollStyle.AUTO, ScrollStyle.STEP), R3Rows.SCROLL_STYLES)
        assertEquals(listOf("손가락을 따라 (기본)", "손을 떼면 이동"), R3Rows.SCROLL_STYLES.map { R3Rows.scrollStyleChoice(it) })
        assertEquals("손가락을 따라", R3Rows.scrollStyle(ScrollStyle.AUTO))
        assertEquals("손을 떼면 이동", R3Rows.scrollStyle(ScrollStyle.STEP))
        // An older build's SMOOTH reads and selects as the first entry; stored values are never rewritten.
        assertEquals("손가락을 따라", R3Rows.scrollStyle(ScrollStyle.SMOOTH))
        assertEquals(0, R3Rows.scrollStyleIndex(ScrollStyle.SMOOTH))
        assertEquals(0, R3Rows.scrollStyleIndex(ScrollStyle.AUTO))
        assertEquals(1, R3Rows.scrollStyleIndex(ScrollStyle.STEP))
        assertEquals(AppSettings().scrollStyle, R3Rows.SCROLL_STYLES[0])
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
        // The row shows the value, the chooser marks the default.
        assertEquals(listOf("페이지 넘김", "스크롤"), R3Rows.READ_MODES.map { R3Rows.readMode(it) })
        assertEquals(listOf("페이지 넘김 (기본)", "스크롤 (위아래로 읽기)"), R3Rows.READ_MODES.map { R3Rows.readModeChoice(it) })
        assertEquals(ReadMode.entries.toSet(), R3Rows.READ_MODES.toSet())
        assertEquals(
            listOf("자세히", "간단히 (한 줄)", "큰 표지 (3열)", "작은 표지 (4열)"),
            LibraryListMode.entries.map { R3Rows.libraryViewChoice(it) },
        )
    }

    @Test
    fun pageBreakChoices() {
        assertEquals(listOf("줄 단위", "문단 단위"), R3Rows.PAGE_BREAKS.map { R3Rows.pageBreak(it) })
        assertEquals("줄 단위", R3Rows.pageBreak(ReaderSettings().pageBreak))
        assertEquals("줄 단위 (기본)", R3Rows.pageBreakChoice(PageBreakMode.LINE))
        assertEquals("문단 단위 (페이지 아래가 빌 수 있음)", R3Rows.pageBreakChoice(PageBreakMode.PARAGRAPH))
        assertEquals(PageBreakMode.entries.toSet(), R3Rows.PAGE_BREAKS.toSet())
    }

    @Test
    fun pageThemeChoices() {
        // 읽기 설정 → 스타일 → 화면 색: every theme, the default first.
        assertEquals(PageTheme.entries.toList(), R3Rows.PAGE_THEMES)
        assertEquals(ReaderSettings().pageTheme, R3Rows.PAGE_THEMES.first())
        assertEquals(
            listOf("흰 바탕 (기본)", "마루뷰어 (어두운 회색 바탕)"),
            R3Rows.PAGE_THEMES.map { R3Rows.pageThemeChoice(it) },
        )
        assertEquals(listOf("흰 바탕", "마루뷰어"), R3Rows.PAGE_THEMES.map { it.label })
    }

    @Test
    fun slotLabels() {
        val titles = (0..1).flatMap { b -> (0..2).map { p -> R3Rows.slotTitle(b, p) } }
        assertEquals(listOf("위 왼쪽", "위 가운데", "위 오른쪽", "아래 왼쪽", "아래 가운데", "아래 오른쪽"), titles)
        // On 화면·밝기 each band is a section of its own; its rows say only the place.
        assertEquals(listOf("위쪽 상태 표시줄", "아래쪽 상태 표시줄"), (0..1).map { R3Rows.bandHeader(it) })
        assertEquals(listOf("왼쪽", "가운데", "오른쪽"), (0..2).map { com.ggumtak.readeraplus.reader.extras.StatusUi.posWord(it) })
        // The quick status panel names the slots the same way.
        assertEquals("아래 오른쪽: 시계", com.ggumtak.readeraplus.reader.extras.StatusUi.slotDescription(1, 2, StatusItem.CLOCK))
        assertEquals("없음", R3Rows.slotChoice(StatusItem.NONE))
        assertEquals("시계 (14:05)", R3Rows.slotChoice(StatusItem.CLOCK))
        assertEquals("쪽 번호 (12 / 3259)", R3Rows.slotChoice(StatusItem.PAGE))
        // R2: the chapter's page sits right under the book's.
        assertEquals("챕터 쪽 번호 (2 / 32)", R3Rows.slotChoice(StatusItem.CHAPTER_PAGES_LEFT))
        assertEquals(StatusItem.PAGE.ordinal + 1, StatusItem.CHAPTER_PAGES_LEFT.ordinal)
        assertEquals("챕터 남은 시간 (챕터 3분)", R3Rows.slotChoice(StatusItem.TIME_LEFT_EPISODE))
        // The page draws the battery icon, then the bare number: the example promises no "%".
        assertEquals("배터리 (80)", R3Rows.slotChoice(StatusItem.BATTERY))
        // A title has no example: the chooser does not repeat the name.
        assertEquals("책 제목", R3Rows.slotChoice(StatusItem.BOOK_TITLE))
        assertEquals("모두 ‘없음’인 줄은 숨깁니다.", R3Rows.STATUS_NOTE)
    }

    @Test
    fun statusFitNote() {
        val r = ReaderSettings()
        // Defaults: 40 dp top and bottom hold the header and the progress lane.
        assertTrue(R3Rows.statusFits(r))
        assertTrue(R3Rows.hasStatusText(r))
        // No text in any band: nothing to fit, and no size row.
        val none = r.copy(headerLeft = StatusItem.NONE, headerCenter = StatusItem.NONE, headerRight = StatusItem.NONE)
        assertFalse(R3Rows.hasStatusText(none))
        assertTrue(R3Rows.statusFits(none.copy(marginTopDp = 0, marginBottomDp = 0)))
        // A header in a 4 dp margin does not fit, unless the camera band above it (fullscreen S25) gives it room.
        assertFalse(R3Rows.statusFits(r.copy(marginTopDp = 4)))
        assertTrue(R3Rows.statusFits(r.copy(marginTopDp = 4), cutoutDp = 37))
        // 페이지 여백 off: bands get 4 dp.
        assertFalse(R3Rows.statusFits(r.copy(pageMargins = false)))
        // Footer text: the progress lane takes 12 dp of the bottom margin.
        val footer = none.withSlot(1, 2, StatusItem.CLOCK)
        assertTrue(R3Rows.statusFits(footer.copy(marginBottomDp = 26, progressBar = false)))
        assertFalse(R3Rows.statusFits(footer.copy(marginBottomDp = 26, progressBar = true)))
    }

    @Test
    fun brightnessSubtitles() {
        assertEquals("왼쪽 가장자리를 위아래로 밀기", R3Rows.brightnessSwipe(scroll = false, none = false))
        assertEquals("왼쪽 가장자리만 밝기 · 나머지는 스크롤", R3Rows.brightnessSwipe(scroll = true, none = false))
        assertEquals("이 기기에서는 밝기 스와이프를 쓸 수 없습니다", R3Rows.brightnessSwipe(scroll = true, none = true))
        assertEquals("조명이 안 바뀔 때 켜세요", R3Rows.brightnessDevice(false, true, true, false))
        assertEquals("나가면 원래 밝기로", R3Rows.brightnessDevice(true, true, true, false))
        assertEquals("나가도 이 밝기 유지", R3Rows.brightnessDevice(true, false, true, false))
        assertEquals("권한 필요 · 눌러서 허용", R3Rows.brightnessDevice(true, true, false, false))
        assertEquals("이 기기는 앱이 조명을 바꿀 수 없습니다", R3Rows.brightnessDevice(true, true, true, true))
        // UI_SPEC §4.3 fix 2: an automatic original comes back on leave even when the level stays.
        assertEquals("나가도 이 밝기 유지 · 자동 밝기는 다시 켬", R3Rows.brightnessDevice(true, false, true, false, origAuto = true))
        assertEquals("나가면 원래 밝기로", R3Rows.brightnessDevice(true, true, true, false, origAuto = true))
        assertEquals("조명이 안 바뀔 때 켜세요", R3Rows.brightnessDevice(false, false, true, false, origAuto = true))
        // The settings page and the reader say the same.
        for (on in booleanArrayOf(true, false)) for (restore in booleanArrayOf(true, false)) for (write in booleanArrayOf(true, false)) {
            assertEquals(LightPolicy.deviceSubtitle(on, restore, on && !write, false, false), R3Rows.brightnessDevice(on, restore, write, false))
        }
        assertEquals("밝기 · 색온도(따뜻한 빛)", R3Rows.LIGHT_SETTINGS)
        assertEquals(LightPolicy.DEVICE_DIALOG, R3Rows.DEVICE_DIALOG)
        assertTrue(R3Rows.NO_PERMISSION_SCREEN.endsWith("명령으로 허용할 수 있습니다.\n\nadb shell appops set com.ggumtak.readeraplus WRITE_SETTINGS allow"))
    }

    @Test
    fun autoBackupTexts() {
        val utc = TimeZone.getTimeZone("UTC")
        val loc = "다운로드/ReaderaPlus/backup"
        val now = 1792368000000L // 2026-10-19
        assertEquals(
            "다운로드/ReaderaPlus/backup · 마지막 9월 29일 12:00",
            R3Rows.autoBackupSummary(true, loc, 1790683200000L, utc, now),
        )
        assertEquals("다운로드/ReaderaPlus/backup", R3Rows.autoBackupSummary(true, loc, 0L, utc, now))
        assertEquals(
            "다운로드/ReaderaPlus/backup · 재설치 후에는 ‘모든 파일 접근’이 필요합니다",
            R3Rows.autoBackupSummary(false, loc, 1790683200000L, utc, now),
        )
        for (o in AutoBackup.Outcome.entries) assertTrue(R3Rows.autoBackupOutcome(o, loc).isNotBlank())
        assertEquals("지금은 저장할 수 없습니다. 잠시 뒤 다시 해 보세요", R3Rows.autoBackupOutcome(AutoBackup.Outcome.BUSY, loc))
        assertEquals("자동 백업을 저장하지 못했습니다", R3Rows.autoBackupOutcome(AutoBackup.Outcome.FAILED, loc))
        // Two lines: when and how it was made, then what it holds.
        assertEquals(
            "9월 29일 12:00 · 자동\n책 12권 · 북마크 4개 · 인용문 5개",
            R3Rows.candidate(1790683200000L, AutoBackup.Summary(12, 3, 4, 5), auto = true, tz = utc, now = now),
        )
        assertTrue(R3Rows.candidate(0L, AutoBackup.Summary(0, 0, 0, 0), auto = false, tz = utc, now = now).startsWith("1970년 1월 1일 00:00 · 수동\n"))
        assertEquals("자동 백업 파일 3개를 지울까요? 이전 설치의 파일도 함께 지웁니다.", R3Rows.deleteAutoFiles(3, others = true))
        assertTrue(R3Rows.deleteAutoFiles(2, others = false).startsWith("이 설치에서 만든 자동 백업 파일 2개를"))
        assertTrue(R3Rows.BACKUP_PRIVACY.contains("단어장"))
        assertTrue(R3Rows.BACKUP_MERGE.endsWith("새 기기에서는 책을 옮기고 책 스캔을 한 뒤 복원하세요."))
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
            webSearchUrl = "https://example.com/?q=%s",
            ttsVoice = "ko-kr-x-ism-local",
            ttsRate = 1.5f,
        )
        val a = SettingsReset.app(old)
        assertFalse(a.autoBackup)
        assertFalse(a.recordLookups)
        assertTrue(a.brightnessDevice)
        assertEquals(LIST_PAGING_SCROLL, a.listPaging)
        assertEquals(LibraryListMode.COVERS, a.libraryListMode)
        assertEquals(old.keyBindings, a.keyBindings)
        assertEquals(old.scanFolders, a.scanFolders)
        // A typed search address and the chosen voice took work to set up: kept too.
        assertEquals(old.webSearchUrl, a.webSearchUrl)
        assertEquals(old.ttsVoice, a.ttsVoice)
        assertEquals(AppSettings().ttsRate, a.ttsRate, 0f)
        // Reading choices go back to the defaults.
        assertEquals(ReadMode.PAGED, a.readMode)
        assertTrue(a.swipeToTurn)
        assertEquals(HL_LOOK_AUTO, a.highlightLook)
        // Every kept field is named in the dialog.
        for (word in listOf("자동 백업", "찾아본 단어 기록", "기기 밝기 직접 조절", "목록 넘기기", "지정한 키", "스캔 폴더", "TXT 정리 설정", "웹 검색", "목소리")) {
            assertTrue(word, SettingsReset.MESSAGE.contains(word))
        }

        val r = SettingsReset.reader(
            ReaderSettings(
                marginLeftDp = 10, marginRightDp = 10, marginTopDp = 70, marginBottomDp = 70,
                pageBreak = PageBreakMode.PARAGRAPH, footerRight = StatusItem.CLOCK, headerCenter = StatusItem.NONE,
                txtReplaceRules = "a=>b", txtDetectChapters = false,
            ),
        )
        assertEquals(listOf(20, 20, 40, 40), listOf(r.marginLeftDp, r.marginRightDp, r.marginTopDp, r.marginBottomDp))
        assertEquals(PageBreakMode.LINE, r.pageBreak)
        assertEquals(StatusItem.BOOK_TITLE, r.headerCenter)
        assertEquals(StatusItem.NONE, r.footerRight)
        assertEquals("a=>b", r.txtReplaceRules)
        assertFalse(r.txtDetectChapters)
    }
}
