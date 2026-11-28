package com.ggumtak.readeraplus.ui.settings

import android.view.View
import android.widget.LinearLayout
import com.ggumtak.readeraplus.BuildConfig
import com.ggumtak.readeraplus.format.BookFormat
import com.ggumtak.readeraplus.settings.AppSettings
import com.ggumtak.readeraplus.settings.ReadMode
import com.ggumtak.readeraplus.settings.Settings
import com.ggumtak.readeraplus.ui.kit.confirm
import com.ggumtak.readeraplus.ui.kit.row
import com.ggumtak.readeraplus.ui.kit.toast
import com.ggumtak.readeraplus.update.AppUpdater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The main list: a map of the settings, one home for each (the user, 2026-11-20: the options felt scattered rather than
 * organised). It only moves on (every row but the three actions at its end opens a page), in groups that say what the
 * pages are about, at most two levels deep:
 *
 * 책 화면 (how the page looks: 보기 설정, the page the quick sheet of the same name continues on; 상태 표시줄; the TXT
 * cleanup) · 조작·기기 (how the reader is worked: 넘기기·터치·키, 화면·밝기, e-ink 화면) · 기능 (듣기, 사전·검색) · 서재
 * (서재 보기, 책 스캔, Wi-Fi로 책 받기) · 기록·데이터 (읽기 기록, 백업·복원, 캐시 비우기) · 앱 (업데이트 확인, 정보, 모든 설정
 * 초기화: the destructive row last).
 *
 * Opened from the reader's 뷰어 설정 button ([OpenBook.info] set; memory only, no IO) it carries that name and leaves
 * out what belongs to the library: the 서재 and 기록·데이터 groups (restoring a backup under an open book is unsafe; no
 * walk over the open book's cache).
 */
internal class MainPage(a: SettingsActivity) :
    SettingsPage(a, SettingsActivity.PAGE_MAIN, if (OpenBook.info != null) READER_TITLE else "설정") {
    private var bookTxtRow: View? = null
    private var turnRow: View? = null
    private var einkRow: View? = null
    private var ttsRow: View? = null
    private var lookupRow: View? = null
    private var libraryRow: View? = null
    private var scanRow: View? = null
    private var backupRow: View? = null
    private var cacheRow: View? = null
    private var cacheBusy = false
    /** Cache-size walk in flight (onShown and onResume both refresh on first open; walk the tree once). */
    private var sizeJob: Job? = null

    override fun build(): View {
        val app = Settings.app
        val book = OpenBook.info
        val body = ctx.pageBody()

        body.section("책 화면")
        body.addView(ctx.navRow(ReadingPage.TITLE, "글꼴 · 글자 · 줄·문단 · 여백 · 색") { activity.push(SettingsActivity.PAGE_READING) })
        body.addView(ctx.navRow("상태 표시줄", "위·아래 표시 항목 · 진행 막대") { activity.push(SettingsActivity.PAGE_STATUS) })
        // The open TXT book's own cleanup first (the reader's context only), then the one every TXT book follows.
        if (book != null && book.format == BookFormat.TXT) {
            bookTxtRow = ctx.navRow(BookTxtPage.TITLE, MainRows.bookTxt(book.override != null)) { activity.push(SettingsActivity.PAGE_BOOK_TXT) }
                .also(body::addView)
        }
        body.addView(ctx.navRow(TxtDefaultsPage.TITLE, "빈 줄 · 줄 합치기 · 챕터 인식") { activity.push(SettingsActivity.PAGE_TXT_DEFAULTS) })

        body.section("조작·기기")
        turnRow = ctx.navRow("넘기기·터치·키", MainRows.turn(app)) { activity.push(SettingsActivity.PAGE_PAGE_TURNING) }.also(body::addView)
        body.addView(ctx.navRow("화면·밝기", "전체 화면 · 화면 방향 · 가로 두 쪽 · 밝기") { activity.push(SettingsActivity.PAGE_SCREEN) })
        einkRow = ctx.navRow(EinkPage.TITLE, EinkChoices.mainSummary(app)) { activity.push(SettingsActivity.PAGE_EINK) }.also(body::addView)

        body.section("기능")
        ttsRow = ctx.navRow("듣기", ttsSummary(app)) { activity.push(SettingsActivity.PAGE_TTS) }.also(body::addView)
        lookupRow = ctx.navRow("사전·검색", lookupSummary(app)) { activity.push(SettingsActivity.PAGE_LOOKUP) }.also(body::addView)

        if (book == null) {
            addLibrary(body, app)
            addData(body, app)
        }

        // The app itself; the one row that undoes the user's work comes last.
        body.section("앱")
        body.addView(ctx.row("업데이트 확인", "현재 버전 ${BuildConfig.VERSION_NAME} (빌드 ${BuildConfig.VERSION_CODE})") { AppUpdater.check(activity, manual = true) })
        body.addView(ctx.navRow("정보", "버전 ${BuildConfig.VERSION_NAME}") { activity.push(SettingsActivity.PAGE_ABOUT) })
        body.addView(ctx.row("모든 설정 초기화", SettingsReset.SUMMARY) { resetSettings() })
        return ctx.pageScroll(body)
    }

    /** "서재": how the library lists its books ([LibraryViewPage]) and its two pages that bring books in. */
    private fun addLibrary(body: LinearLayout, app: AppSettings) {
        body.section("서재")
        libraryRow = ctx.navRow(LibraryViewPage.TITLE, MainRows.library(app)) { activity.push(SettingsActivity.PAGE_LIBRARY_VIEW) }.also(body::addView)
        scanRow = ctx.navRow("책 스캔", scanSummary(app)) { activity.push(SettingsActivity.PAGE_SCAN) }.also(body::addView)
        body.addView(ctx.navRow("Wi-Fi로 책 받기", "PC · 휴대폰 브라우저에서 보내기") { activity.push(SettingsActivity.PAGE_WIFI) })
    }

    /** "기록·데이터": reading statistics, the backup and the cache. */
    private fun addData(body: LinearLayout, app: AppSettings) {
        body.section("기록·데이터")
        body.addView(ctx.navRow("읽기 기록", "읽은 시간 · 연속 기록 · 다 읽은 책") { activity.push(SettingsActivity.PAGE_STATS) })
        body.addView(ctx.navRow("동기화 서재", "내 기기에서 책 파일 · 읽던 위치 이어받기") { activity.push(SettingsActivity.PAGE_SYNC) })
        backupRow = ctx.navRow("백업·복원", backupSummary(app)) { activity.push(SettingsActivity.PAGE_BACKUP) }.also(body::addView)
        cacheRow = ctx.row("캐시 비우기", "계산 중…") { clearCache() }.also(body::addView)
    }

    override fun onShown() {
        refreshSummaries()
    }

    override fun onResume() {
        refreshSummaries()
        // Back from the system's ‘알 수 없는 앱 설치’ page: the update that waited for the permission goes on.
        AppUpdater.resume(activity)
    }

    /** The summaries other pages may have changed (rows left out in the reader's context are null). */
    private fun refreshSummaries() {
        val app = Settings.app
        // 이 책의 TXT 정리 may have given the book its own options, or cleared them.
        OpenBook.info?.let { book -> bookTxtRow?.setSummary(MainRows.bookTxt(book.override != null)) }
        turnRow?.setSummary(MainRows.turn(app))
        einkRow?.setSummary(EinkChoices.mainSummary(app))
        ttsRow?.setSummary(ttsSummary(app))
        lookupRow?.setSummary(lookupSummary(app))
        libraryRow?.setSummary(MainRows.library(app))
        scanRow?.setSummary(scanSummary(app))
        backupRow?.setSummary(backupSummary(app))
        val row = cacheRow ?: return
        if (!cacheBusy && sizeJob?.isActive != true) {
            sizeJob = activity.scope.launch {
                val size = withContext(Dispatchers.IO) { clearableCacheSize() }
                if (!cacheBusy) row.setSummary(cacheSummary(size))
            }
        }
    }

    private fun scanSummary(app: AppSettings): String {
        val where = when (app.scanFolders.size) {
            // No folder: the scanner walks the internal storage and the SD card.
            0 -> "전체 저장소"
            1 -> FolderSets.displayName(app.scanFolders.first())
            else -> "폴더 ${app.scanFolders.size}개"
        }
        return where + if (app.excludedFolders.isEmpty()) "" else " · 제외 ${app.excludedFolders.size}개"
    }

    /** "속도 1.0배 · 음높이 1.0", then the timer while one is set ("30분 뒤 멈춤"). */
    private fun ttsSummary(app: AppSettings): String =
        listOfNotNull(
            "속도 ${SettingsFormat.rate(app.ttsRate)}",
            "음높이 ${SettingsFormat.pitch(app.ttsPitch)}",
            SettingsFormat.sleepSummary(app.ttsSleepMinutes, app.ttsSleepChapters),
        ).joinToString(" · ")

    private fun lookupSummary(app: AppSettings): String = "웹 검색: ${WebEngines.nameOf(app.webSearchUrl)}"

    private fun backupSummary(app: AppSettings): String = if (app.autoBackup) "자동 백업 켜짐" else "자동 백업 꺼짐"

    /** "12MB · 표지 · 색인 · 페이지 정보": the size first, then what the cache holds. */
    private fun cacheSummary(size: Long): String = "${SettingsFormat.bytes(size)} · 표지 · 색인 · 페이지 정보"

    private fun cacheDirs(): List<File> = listOfNotNull(activity.cacheDir, activity.externalCacheDir)

    /** Keeps in-flight APK updates (`update/`) and saved Notes selection refs (`*.refs`). */
    private fun keepInCache(f: File): Boolean = f.name == "update" || f.name.endsWith(".refs")

    private fun clearableCacheSize(): Long {
        var total = 0L
        for (dir in cacheDirs()) {
            dir.listFiles()?.forEach { f ->
                if (!keepInCache(f)) total += dirSize(f)
            }
        }
        return total
    }

    private fun clearCache() {
        if (cacheBusy) return
        ctx.confirm(
            "캐시 비우기",
            "표지 이미지 · TXT 색인 · 페이지 정보를 지울까요? 책을 처음 열 때 다시 만들어 잠시 느려질 수 있습니다.",
            ok = "지우기",
        ) {
            cacheBusy = true
            cacheRow?.setSummary("지우는 중…")
            activity.scope.launch {
                val freed = withContext(Dispatchers.IO) {
                    var total = 0L
                    for (dir in cacheDirs()) {
                        dir.listFiles()?.forEach { f ->
                            if (keepInCache(f)) return@forEach
                            val s = dirSize(f)
                            if (f.deleteRecursively()) total += s
                        }
                    }
                    Settings.raw().edit().putLong(SettingsActivity.PREF_CACHE_EPOCH, System.currentTimeMillis()).apply()
                    runCatching { com.ggumtak.readeraplus.data.Library.clearPageCounts() }
                    total
                }
                cacheBusy = false
                cacheRow?.setSummary(cacheSummary(0L))
                ctx.toast("${SettingsFormat.bytes(freed)} 비웠습니다")
            }
        }
    }

    /**
     * Back to the defaults, except what took the user work to set up (scan folders, assigned keys, the library view,
     * the TXT cleanup defaults: their replacement rules; resetting them would also re-parse every TXT once, a typed
     * web search address, the chosen voice) and the privacy and device choices (자동 백업, 찾아본 단어 기록, 기기 밝기
     * 직접 조절, 목록 넘기기): see [SettingsReset].
     */
    private fun resetSettings() {
        ctx.confirm("모든 설정 초기화", SettingsReset.MESSAGE, ok = "초기화") {
            Settings.saveApp(SettingsReset.app(Settings.app))
            Settings.saveReader(SettingsReset.reader(Settings.reader))
            // Rebuild so every switch shows its new value.
            activity.rebuildTop()
            ctx.toast("기본값으로 되돌렸습니다")
        }
    }

    companion object {
        /** The main list's title over an open book: the name of the reader's button that opens it. */
        const val READER_TITLE = "뷰어 설정"
    }
}

/** What the main list's rows say about the pages behind them. Pure, unit-tested. */
internal object MainRows {
    /**
     * 넘기기·터치·키: "터치: 좌우 넘김 · 볼륨 키 · 지정 키 2개", with "스크롤" first in scroll mode (paged is the default: not
     * named, so "넘김" is never said twice; the e-ink cadence has its own row).
     */
    fun turn(app: AppSettings): String {
        val parts = ArrayList<String>()
        if (app.readMode == ReadMode.SCROLL) parts += R3Rows.readMode(app.readMode)
        parts += "터치: " + TapZoneModel.modeName(app.tapZoneMode)
        if (app.volumeKeysTurn) parts += "볼륨 키"
        val keys = KeyAssign.entries(app).size
        if (keys > 0) parts += "지정 키 ${keys}개"
        return parts.joinToString(" · ")
    }

    /** 서재 보기: the two choices the library shows by, "제목순 · 자세히". */
    fun library(app: AppSettings): String = app.librarySort.label + " · " + app.libraryListMode.label

    /** 이 책의 TXT 정리: "따로 정함" while the open book has TXT options of its own, else "기본값 따름". */
    fun bookTxt(own: Boolean): String = if (own) "따로 정함" else "기본값 따름"
}

/** Total size of a file or directory tree (blocking). */
internal fun dirSize(f: File): Long {
    if (!f.exists()) return 0
    if (f.isFile) return f.length()
    var total = 0L
    val stack = ArrayDeque<File>()
    stack.addLast(f)
    while (stack.isNotEmpty()) {
        val d = stack.removeLast()
        val children = d.listFiles() ?: continue
        for (c in children) {
            if (c.isDirectory) stack.addLast(c) else total += c.length()
        }
    }
    return total
}
