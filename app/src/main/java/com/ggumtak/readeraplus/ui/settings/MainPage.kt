package com.ggumtak.readeraplus.ui.settings

import android.view.View
import android.widget.LinearLayout
import com.ggumtak.readeraplus.BuildConfig
import com.ggumtak.readeraplus.settings.AppSettings
import com.ggumtak.readeraplus.settings.LibraryListMode
import com.ggumtak.readeraplus.settings.LibrarySort
import com.ggumtak.readeraplus.settings.Settings
import com.ggumtak.readeraplus.ui.kit.chooser
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
 * The main list, in small groups of one kind of row each: 읽기 (읽기 설정, which holds everything that adjusts the page:
 * letters, margins, colours, 상태 표시줄, 넘기기·터치·키, 글꼴 관리 and the TXT options; then e-ink 화면, 화면·밝기, 듣기,
 * 사전·검색), 서재 (the library's choices), 책 가져오기 (the library's pages that bring books in), 기록·데이터 (읽기 기록,
 * 백업·복원, 캐시 비우기) and 기타 (모든 설정 초기화, 정보).
 *
 * Opened from the reader ([OpenBook.info] set; memory only, no IO) it leaves out what belongs to the library: the
 * 서재, 책 가져오기 and 기록·데이터 groups (restoring a backup under an open book is unsafe; no walk over the open
 * book's cache).
 */
internal class MainPage(a: SettingsActivity) : SettingsPage(a, SettingsActivity.PAGE_MAIN, "설정") {
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

        body.section("읽기")
        body.addView(ctx.navRow("읽기 설정", "글자 · 여백 · 상태 표시줄 · 넘기기·터치") { activity.push(SettingsActivity.PAGE_READING) })
        einkRow = ctx.navRow(EinkPage.TITLE, EinkChoices.mainSummary(app)) { activity.push(SettingsActivity.PAGE_EINK) }.also(body::addView)
        body.addView(ctx.navRow("화면·밝기", "전체 화면 · 화면 방향 · 밝기") { activity.push(SettingsActivity.PAGE_SCREEN) })
        ttsRow = ctx.navRow("듣기", ttsSummary(app)) { activity.push(SettingsActivity.PAGE_TTS) }.also(body::addView)
        lookupRow = ctx.navRow("사전·검색", lookupSummary(app)) { activity.push(SettingsActivity.PAGE_LOOKUP) }.also(body::addView)

        if (book == null) {
            addLibrary(body, app)
            addData(body, app)
        }

        body.section("기타")
        body.addView(ctx.row("모든 설정 초기화", SettingsReset.SUMMARY) { resetSettings() })
        if (AppUpdater.enabled()) body.addView(ctx.row("업데이트 확인", "지금 빌드 ${BuildConfig.VERSION_CODE}") { AppUpdater.check(activity, manual = true) })
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
        body.addView(ctx.toggleRow("시작할 때 읽던 책 열기", null, app.openLastOnStart) { v ->
            editApp { it.copy(openLastOnStart = v) }
        })

        body.section("책 가져오기")
        scanRow = ctx.navRow("책 스캔", scanSummary(app)) { activity.push(SettingsActivity.PAGE_SCAN) }.also(body::addView)
        body.addView(ctx.navRow("Wi-Fi로 책 받기", "PC · 휴대폰 브라우저에서 보내기") { activity.push(SettingsActivity.PAGE_WIFI) })
    }

    /** "기록·데이터": reading statistics, the backup and the cache. */
    private fun addData(body: LinearLayout, app: AppSettings) {
        body.section("기록·데이터")
        body.addView(ctx.navRow("읽기 기록", "읽은 시간 · 연속 기록 · 다 읽은 책") { activity.push(SettingsActivity.PAGE_STATS) })
        backupRow = ctx.navRow("백업·복원", backupSummary(app)) { activity.push(SettingsActivity.PAGE_BACKUP) }.also(body::addView)
        cacheRow = ctx.row("캐시 비우기", "계산 중…") { clearCache() }.also(body::addView)
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
        einkRow?.setSummary(EinkChoices.mainSummary(app))
        ttsRow?.setSummary(ttsSummary(app))
        lookupRow?.setSummary(lookupSummary(app))
        scanRow?.setSummary(scanSummary(app))
        sortRow?.setSummary(app.librarySort.label)
        listModeRow?.setSummary(app.libraryListMode.label)
        listPagingRow?.setSummary(R3Rows.listPaging(app.listPaging))
        backupRow?.setSummary(backupSummary(app))
        val row = cacheRow ?: return
        if (!cacheBusy && sizeJob?.isActive != true) {
            sizeJob = activity.scope.launch {
                val size = withContext(Dispatchers.IO) { cacheDirs().sumOf { dirSize(it) } }
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
        ctx.confirm("모든 설정 초기화", SettingsReset.MESSAGE, ok = "초기화") {
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
