package com.ggumtak.readeraplus.ui.settings

import android.view.View
import com.ggumtak.readeraplus.BuildConfig
import com.ggumtak.readeraplus.render.FontCatalog
import com.ggumtak.readeraplus.render.FontManager
import com.ggumtak.readeraplus.settings.AppSettings
import com.ggumtak.readeraplus.settings.LibraryListMode
import com.ggumtak.readeraplus.settings.LibrarySort
import com.ggumtak.readeraplus.settings.ReaderSettings
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

/** The ReadEra-like main list: 일반 · 읽기 설정 · 기타. */
internal class MainPage(a: SettingsActivity) : SettingsPage(a, SettingsActivity.PAGE_MAIN, "설정") {
    private var scanRow: View? = null
    private var permRow: View? = null
    private var turnRow: View? = null
    private var fontRow: View? = null
    private var ttsRow: View? = null
    private var lookupRow: View? = null
    private var cacheRow: View? = null
    private var sortRow: View? = null
    private var listModeRow: View? = null
    private var orientationRow: View? = null
    private var cacheBusy = false
    /** Cache-size walk in flight (onShown and onResume both refresh on first open; walk the tree once). */
    private var sizeJob: Job? = null

    override fun build(): View {
        val app = Settings.app
        val body = ctx.pageBody()

        body.section("일반", first = true)
        scanRow = ctx.navRow("파일 스캔", scanSummary(app)) { activity.push(SettingsActivity.PAGE_SCAN) }.also(body::addView)
        body.addView(ctx.navRow("백업 및 복원", "서재 기록 · 북마크 · 인용문 · 설정을 파일로 저장하고 되살립니다") {
            activity.push(SettingsActivity.PAGE_BACKUP)
        })
        body.addView(ctx.navRow("Wi-Fi로 책 받기", "같은 Wi-Fi의 PC · 휴대폰 브라우저에서 TXT · EPUB 파일을 보냅니다") {
            activity.push(SettingsActivity.PAGE_WIFI)
        })
        body.addView(ctx.navRow("읽기 기록", "읽은 시간 · 연속 기록 · 잔디 · 올해 다 읽은 책") { activity.push(SettingsActivity.PAGE_STATS) })
        body.addView(ctx.toggleRow("앱 시작 시 읽던 책 열기", "앱을 열면 마지막으로 읽던 책을 이어서 봅니다. 꺼도 읽던 중 시스템이 앱을 닫았다면 그 책으로 돌아갑니다", app.openLastOnStart) { v ->
            editApp { it.copy(openLastOnStart = v) }
        })
        permRow = ctx.row("모든 파일 접근 권한", StorageAccess.summary(ctx)) { StorageAccess.request(activity) }.also(body::addView)
        sortRow = ctx.valueRow("서재 정렬", app.librarySort.label) {
            val all = LibrarySort.entries
            ctx.chooser("서재 정렬", all.map { it.label }, all.indexOf(Settings.app.librarySort)) { i ->
                editApp { it.copy(librarySort = all[i]) }
                sortRow?.setSummary(all[i].label)
            }
        }.also(body::addView)
        listModeRow = ctx.valueRow("서재 보기", app.libraryListMode.label) {
            val all = LibraryListMode.entries
            ctx.chooser("서재 보기", all.map { it.label }, all.indexOf(Settings.app.libraryListMode)) { i ->
                editApp { it.copy(libraryListMode = all[i]) }
                listModeRow?.setSummary(all[i].label)
            }
        }.also(body::addView)

        body.section("읽기 설정")
        turnRow = ctx.navRow("넘김·화면 설정", turnSummary(app)) { activity.push(SettingsActivity.PAGE_PAGE_TURNING) }.also(body::addView)
        fontRow = ctx.navRow("글꼴 관리", "읽기 글꼴: …") { activity.push(SettingsActivity.PAGE_FONTS) }.also(body::addView)
        body.addView(ctx.navRow("TXT 기본 정리 설정", "빈 줄 · 줄 합치기 · 챕터 인식 · 치환 규칙 (따로 정하지 않은 모든 TXT)") {
            activity.push(SettingsActivity.PAGE_TXT_DEFAULTS)
        })
        ttsRow = ctx.navRow("Text to speech (TTS)", ttsSummary(app)) { activity.push(SettingsActivity.PAGE_TTS) }.also(body::addView)
        lookupRow = ctx.navRow("사전 · 번역 · 웹 검색", "웹 검색: ${WebEngines.nameOf(app.webSearchUrl)}") {
            activity.push(SettingsActivity.PAGE_LOOKUP)
        }.also(body::addView)
        body.addView(ctx.toggleRow("전체 화면 모드", "상태표시줄과 네비게이션바 숨김", app.fullscreen) { v -> editApp { it.copy(fullscreen = v) } })
        body.addView(ctx.toggleRow("스와이프로 밝기 조절", "화면 좌측을 위아래로 스와이프하여 밝기를 조절합니다", app.brightnessSwipe) { v ->
            editApp { it.copy(brightnessSwipe = v) }
        })
        body.addView(ctx.toggleRow("터치로 흑백 반전", "좌측 상단을 터치해 흰 바탕 ↔ 검은 바탕 전환", app.invertByTouch) { v ->
            editApp { it.copy(invertByTouch = v) }
        })
        body.addView(ctx.toggleRow("터치로 북마크", "우측 상단을 터치해 북마크 추가 / 삭제", app.bookmarkByTouch) { v ->
            editApp { it.copy(bookmarkByTouch = v) }
        })
        body.addView(ctx.toggleRow("화면 켜짐 유지", "시스템 화면 꺼짐 시간보다 10분 더 화면 켜짐을 유지합니다", app.keepScreenOn) { v ->
            editApp { it.copy(keepScreenOn = v) }
        })
        orientationRow = ctx.valueRow("화면 방향", SettingsFormat.orientation(app.orientationLock)) {
            val opts = SettingsFormat.ORIENTATIONS
            val sel = opts.indexOfFirst { it.second == Settings.app.orientationLock }.coerceAtLeast(0)
            ctx.chooser("화면 방향", opts.map { it.first }, sel) { i ->
                editApp { it.copy(orientationLock = opts[i].second) }
                orientationRow?.setSummary(opts[i].first)
            }
        }.also(body::addView)

        body.section("기타")
        cacheRow = ctx.row("캐시 비우기", "표지 · TXT 색인 · 쪽수 캐시 (계산 중…)") { clearCache() }.also(body::addView)
        body.addView(ctx.row("설정 초기화", "읽기 · 넘김 · 화면 설정을 기본값으로 (TXT 정리 설정 · 스캔 폴더 · 키 지정은 유지)") { resetSettings() })
        body.addView(ctx.navRow("정보", "버전 ${BuildConfig.VERSION_NAME}") { activity.push(SettingsActivity.PAGE_ABOUT) })
        return ctx.pageScroll(body)
    }

    override fun onShown() {
        refreshSummaries()
    }

    override fun onResume() {
        refreshSummaries()
    }

    private fun refreshSummaries() {
        val app = Settings.app
        scanRow?.setSummary(scanSummary(app))
        permRow?.setSummary(StorageAccess.summary(ctx))
        turnRow?.setSummary(turnSummary(app))
        ttsRow?.setSummary(ttsSummary(app))
        lookupRow?.setSummary("웹 검색: ${WebEngines.nameOf(app.webSearchUrl)}")
        sortRow?.setSummary(app.librarySort.label)
        listModeRow?.setSummary(app.libraryListMode.label)
        orientationRow?.setSummary(SettingsFormat.orientation(app.orientationLock))
        val fontId = Settings.reader.fontId
        activity.scope.launch {
            val name = withContext(Dispatchers.IO) {
                runCatching { FontManager.font(fontId)?.name }.getOrNull()
                    ?: FontCatalog.BUNDLED.firstOrNull { it.id == fontId }?.name ?: fontId
            }
            fontRow?.setSummary("읽기 글꼴: $name")
        }
        if (!cacheBusy && sizeJob?.isActive != true) {
            sizeJob = activity.scope.launch {
                val size = withContext(Dispatchers.IO) { cacheDirs().sumOf { dirSize(it) } }
                if (!cacheBusy) cacheRow?.setSummary("표지 · TXT 색인 · 쪽수 캐시 · ${SettingsFormat.bytes(size)}")
            }
        }
    }

    private fun scanSummary(app: AppSettings): String = when (app.scanFolders.size) {
        0 -> "내부 저장소 전체" + if (app.excludedFolders.isEmpty()) "" else " · 제외 ${app.excludedFolders.size}개"
        1 -> FolderSets.displayName(app.scanFolders.first()) + if (app.excludedFolders.isEmpty()) "" else " · 제외 ${app.excludedFolders.size}개"
        else -> "폴더 ${app.scanFolders.size}개" + if (app.excludedFolders.isEmpty()) "" else " · 제외 ${app.excludedFolders.size}개"
    }

    private fun turnSummary(app: AppSettings): String {
        val parts = ArrayList<String>()
        parts += TapZoneModel.modeName(app.tapZoneMode)
        if (app.volumeKeysTurn) parts += "볼륨 키"
        val keys = KeyAssign.entries(app).size
        if (keys > 0) parts += "지정 키 ${keys}개"
        if (app.einkRefreshEvery > 0) parts += "새로고침 ${SettingsFormat.refreshEvery(app.einkRefreshEvery)}"
        return parts.joinToString(" · ")
    }

    private fun ttsSummary(app: AppSettings): String =
        "속도 ${SettingsFormat.rate(app.ttsRate)} · 음높이 ${SettingsFormat.pitch(app.ttsPitch)}" +
            if (app.ttsSleepMinutes > 0 || app.ttsSleepChapters > 0) {
                " · 수면 ${SettingsFormat.sleepChoice(app.ttsSleepMinutes, app.ttsSleepChapters)}"
            } else {
                ""
            }

    private fun cacheDirs(): List<File> = listOfNotNull(activity.cacheDir, activity.externalCacheDir)

    private fun clearCache() {
        if (cacheBusy) return
        ctx.confirm(
            "캐시 비우기",
            "표지 이미지, TXT 색인, 쪽수 계산 결과를 지웁니다. 책을 처음 열 때 다시 만들어지므로 잠시 느려질 수 있습니다.",
            ok = "비우기",
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
                cacheRow?.setSummary("표지 · TXT 색인 · 쪽수 캐시 · 0 B")
                ctx.toast("${SettingsFormat.bytes(freed)} 비웠습니다")
            }
        }
    }

    /**
     * Back to the defaults, except what took the user work to set up: scan folders, assigned keys, the library view
     * and the TXT cleanup defaults (their replacement rules; resetting them would also re-parse every TXT once).
     */
    private fun resetSettings() {
        val msg = "글꼴 · 글자 크기 · 간격 · 여백과 넘김 · 화면 설정을 기본값으로 되돌릴까요?\nTXT 정리 설정 · 스캔 폴더 · 지정한 키는 그대로 둡니다."
        ctx.confirm("설정 초기화", msg, ok = "초기화") {
            val old = Settings.app
            Settings.saveApp(
                AppSettings().copy(
                    scanFolders = old.scanFolders,
                    excludedFolders = old.excludedFolders,
                    nextPageKeys = old.nextPageKeys,
                    prevPageKeys = old.prevPageKeys,
                    keyBindings = old.keyBindings,
                    librarySort = old.librarySort,
                    libraryListMode = old.libraryListMode,
                ),
            )
            val r = Settings.reader
            Settings.saveReader(
                ReaderSettings().copy(
                    txtBlankLines = r.txtBlankLines,
                    txtStripIndent = r.txtStripIndent,
                    txtJoinWrappedLines = r.txtJoinWrappedLines,
                    txtDetectChapters = r.txtDetectChapters,
                    txtChapterRegex = r.txtChapterRegex,
                    txtEmphasizeHeadings = r.txtEmphasizeHeadings,
                    txtReplaceRules = r.txtReplaceRules,
                ),
            )
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
