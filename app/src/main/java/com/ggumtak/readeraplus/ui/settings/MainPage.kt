package com.ggumtak.readeraplus.ui.settings

import android.view.View
import android.widget.LinearLayout
import com.ggumtak.readeraplus.BuildConfig
import com.ggumtak.readeraplus.format.BookFormat
import com.ggumtak.readeraplus.render.FontCatalog
import com.ggumtak.readeraplus.render.FontManager
import com.ggumtak.readeraplus.settings.AppSettings
import com.ggumtak.readeraplus.settings.LibraryListMode
import com.ggumtak.readeraplus.settings.LibrarySort
import com.ggumtak.readeraplus.settings.ReadMode
import com.ggumtak.readeraplus.settings.Settings
import com.ggumtak.readeraplus.ui.kit.chooser
import com.ggumtak.readeraplus.ui.kit.confirm
import com.ggumtak.readeraplus.ui.kit.row
import com.ggumtak.readeraplus.ui.kit.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The main list, in small groups of one kind of row each (2026-10-04: the old 읽기 block of eight look-alike rows was
 * too hard to scan): 읽기 화면 (how the page looks), 조작·기능 (turning, listening, looking up), 서재 (the library's
 * choices), 책 가져오기 (the library's pages that bring books in) and 기타 (읽기 기록, 백업·복원, 캐시 비우기, 설정
 * 초기화, 정보). Each setting has one home: the screen, brightness and corner switches live on 화면·밝기 and
 * 넘기기·터치·키, the TXT defaults on 읽기 설정 → 파일.
 *
 * Opened from the reader ([OpenBook.info] set; memory only, no IO) it leaves out what belongs to the library: the
 * 서재 and 책 가져오기 groups, 읽기 기록, 백업·복원 (restoring under an open book is unsafe) and 캐시 비우기 (no walk
 * over the open book's cache); a TXT book gets "이 책의 TXT 정리" as the first row.
 */
internal class MainPage(a: SettingsActivity) : SettingsPage(a, SettingsActivity.PAGE_MAIN, "설정") {
    private var bookTxtRow: View? = null
    private var fontRow: View? = null
    private var turnRow: View? = null
    private var einkRow: View? = null
    private var ttsRow: View? = null
    private var lookupRow: View? = null
    private var scanRow: View? = null
    private var sortRow: View? = null
    private var listModeRow: View? = null
    private var listPagingRow: View? = null
    private var backupRow: View? = null
    private var cacheRow: View? = null
    private var cacheBusy = false
    /** Cache-size walk in flight (onShown and onResume both refresh on first open; walk the tree once). */
    private var sizeJob: Job? = null

    override fun build(): View {
        val app = Settings.app
        val book = OpenBook.info
        val body = ctx.pageBody()

        body.section("읽기 화면")
        if (book?.format == BookFormat.TXT) {
            bookTxtRow = ctx.navRow(BookTxtPage.TITLE, bookTxtSummary(book)) { activity.push(SettingsActivity.PAGE_BOOK_TXT) }
                .also(body::addView)
        }
        body.addView(ctx.navRow("읽기 설정", "스타일 · 글꼴 · 간격 · 여백") { activity.push(SettingsActivity.PAGE_READING) })
        fontRow = ctx.navRow("글꼴 관리", "…") { activity.push(SettingsActivity.PAGE_FONTS) }.also(body::addView)
        body.addView(ctx.navRow("화면·밝기", "상태 표시줄 · 전체 화면 · 밝기") { activity.push(SettingsActivity.PAGE_SCREEN) })
        einkRow = ctx.navRow(EinkPage.TITLE, EinkChoices.mainSummary(app)) { activity.push(SettingsActivity.PAGE_EINK) }.also(body::addView)

        body.section("조작·기능")
        turnRow = ctx.navRow("넘기기·터치·키", turnSummary(app)) { activity.push(SettingsActivity.PAGE_PAGE_TURNING) }.also(body::addView)
        ttsRow = ctx.navRow("듣기 설정", ttsSummary(app)) { activity.push(SettingsActivity.PAGE_TTS) }.also(body::addView)
        lookupRow = ctx.navRow("사전·번역·검색", lookupSummary(app)) { activity.push(SettingsActivity.PAGE_LOOKUP) }.also(body::addView)

        if (book == null) addLibrary(body, app)

        body.section("기타")
        if (book == null) {
            body.addView(ctx.navRow("읽기 기록", "읽은 시간 · 연속 기록 · 다 읽은 책") { activity.push(SettingsActivity.PAGE_STATS) })
            backupRow = ctx.navRow("백업·복원", backupSummary(app)) { activity.push(SettingsActivity.PAGE_BACKUP) }.also(body::addView)
            cacheRow = ctx.row("캐시 비우기", "계산 중…") { clearCache() }.also(body::addView)
        }
        body.addView(ctx.row("설정 초기화", SettingsReset.SUMMARY) { resetSettings() })
        body.addView(ctx.navRow("정보", "버전 ${BuildConfig.VERSION_NAME}") { activity.push(SettingsActivity.PAGE_ABOUT) })
        return ctx.pageScroll(body)
    }

    /** "서재": the library's own choices (choosers and a switch); "책 가져오기": its pages that bring books in. */
    private fun addLibrary(body: LinearLayout, app: AppSettings) {
        body.section("서재")
        sortRow = ctx.valueRow("정렬", app.librarySort.label) {
            val all = LibrarySort.entries
            ctx.chooser("정렬", all.map { it.label }, all.indexOf(Settings.app.librarySort)) { i ->
                editApp { it.copy(librarySort = all[i]) }
                sortRow?.setSummary(all[i].label)
            }
        }.also(body::addView)
        listModeRow = ctx.valueRow("보기", app.libraryListMode.label) {
            val all = LibraryListMode.entries
            ctx.chooser("보기", all.map { R3Rows.libraryViewChoice(it) }, all.indexOf(Settings.app.libraryListMode)) { i ->
                editApp { it.copy(libraryListMode = all[i]) }
                listModeRow?.setSummary(all[i].label)
            }
        }.also(body::addView)
        listPagingRow = ctx.valueRow("목록 넘기기", R3Rows.listPaging(app.listPaging)) {
            val opts = R3Rows.LIST_PAGINGS
            ctx.chooser("목록 넘기기", opts.map { R3Rows.listPaging(it) }, R3Rows.listPagingIndex(Settings.app.listPaging)) { i ->
                editApp { it.copy(listPaging = opts[i]) }
                listPagingRow?.setSummary(R3Rows.listPaging(opts[i]))
            }
        }.also(body::addView)
        // Off, the app still comes back to a book the system closed while it was open (LibraryText.startMode: RESUME).
        body.addView(ctx.toggleRow("시작할 때 읽던 책 열기", "끄면 서재부터 · 앱이 강제로 닫혔을 때는 그 책으로", app.openLastOnStart) { v ->
            editApp { it.copy(openLastOnStart = v) }
        })

        body.section("책 가져오기")
        scanRow = ctx.navRow("책 스캔", scanSummary(app)) { activity.push(SettingsActivity.PAGE_SCAN) }.also(body::addView)
        body.addView(ctx.navRow("Wi-Fi로 책 받기", "PC · 휴대폰 브라우저에서 보내기") { activity.push(SettingsActivity.PAGE_WIFI) })
    }

    override fun onShown() {
        refreshSummaries()
    }

    override fun onResume() {
        refreshSummaries()
    }

    /** The summaries other pages may have changed (rows left out in the reader's context are null). */
    private fun refreshSummaries() {
        val app = Settings.app
        OpenBook.info?.let { book -> bookTxtRow?.setSummary(bookTxtSummary(book)) }
        turnRow?.setSummary(turnSummary(app))
        einkRow?.setSummary(EinkChoices.mainSummary(app))
        ttsRow?.setSummary(ttsSummary(app))
        lookupRow?.setSummary(lookupSummary(app))
        scanRow?.setSummary(scanSummary(app))
        sortRow?.setSummary(app.librarySort.label)
        listModeRow?.setSummary(app.libraryListMode.label)
        listPagingRow?.setSummary(R3Rows.listPaging(app.listPaging))
        backupRow?.setSummary(backupSummary(app))
        val fontId = Settings.reader.fontId
        activity.scope.launch {
            val name = withContext(Dispatchers.IO) {
                runCatching { FontManager.font(fontId)?.name }.getOrNull()
                    ?: FontCatalog.BUNDLED.firstOrNull { it.id == fontId }?.name ?: fontId
            }
            fontRow?.setSummary(name)
        }
        val row = cacheRow ?: return
        if (!cacheBusy && sizeJob?.isActive != true) {
            sizeJob = activity.scope.launch {
                val size = withContext(Dispatchers.IO) { cacheDirs().sumOf { dirSize(it) } }
                if (!cacheBusy) row.setSummary(cacheSummary(size))
            }
        }
    }

    /** "이 책의 TXT 정리": whether the open book has TXT options of its own (memory only). */
    private fun bookTxtSummary(book: OpenBook.Info): String = if (book.override != null) "따로 정함" else "기본값 따름"

    private fun scanSummary(app: AppSettings): String {
        val where = when (app.scanFolders.size) {
            // No folder: the scanner walks the internal storage and the SD card.
            0 -> "전체 저장소"
            1 -> FolderSets.displayName(app.scanFolders.first())
            else -> "폴더 ${app.scanFolders.size}개"
        }
        return where + if (app.excludedFolders.isEmpty()) "" else " · 제외 ${app.excludedFolders.size}개"
    }

    /**
     * "터치: 좌우 넘김 · 볼륨 키 · 지정 키 2개", with "스크롤" first in scroll mode (paged is the default: not named, so
     * "넘김" is never said twice; the e-ink cadence has its own row).
     */
    private fun turnSummary(app: AppSettings): String {
        val parts = ArrayList<String>()
        if (app.readMode == ReadMode.SCROLL) parts += R3Rows.readMode(app.readMode)
        parts += "터치: " + TapZoneModel.modeName(app.tapZoneMode)
        if (app.volumeKeysTurn) parts += "볼륨 키"
        val keys = KeyAssign.entries(app).size
        if (keys > 0) parts += "지정 키 ${keys}개"
        return parts.joinToString(" · ")
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

    /** "12MB · 표지 · 색인 · 페이지 수": the size first, then what the cache holds. */
    private fun cacheSummary(size: Long): String = "${SettingsFormat.bytes(size)} · 표지 · 색인 · 페이지 수"

    private fun cacheDirs(): List<File> = listOfNotNull(activity.cacheDir, activity.externalCacheDir)

    private fun clearCache() {
        if (cacheBusy) return
        ctx.confirm(
            "캐시 비우기",
            "표지 이미지 · TXT 색인 · 페이지 수 계산을 지울까요? 책을 처음 열 때 다시 만들어 잠시 느려질 수 있습니다.",
            ok = "지우기",
        ) {
            cacheBusy = true
            cacheRow?.setSummary("지우는 중…")
            activity.scope.launch {
                val freed = withContext(Dispatchers.IO) {
                    var total = 0L
                    for (dir in cacheDirs()) {
                        dir.listFiles()?.forEach { f ->
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
        ctx.confirm("설정 초기화", SettingsReset.MESSAGE, ok = "초기화") {
            Settings.saveApp(SettingsReset.app(Settings.app))
            Settings.saveReader(SettingsReset.reader(Settings.reader))
            // Rebuild so every switch shows its new value.
            activity.rebuildTop()
            ctx.toast("기본값으로 되돌렸습니다")
        }
    }
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
