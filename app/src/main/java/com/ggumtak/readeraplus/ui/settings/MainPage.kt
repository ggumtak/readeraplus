package com.ggumtak.readeraplus.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.LinearLayout
import com.ggumtak.readeraplus.BuildConfig
import com.ggumtak.readeraplus.reader.DeviceLight
import com.ggumtak.readeraplus.reader.extras.QuoteSwatch
import com.ggumtak.readeraplus.render.DeviceClass
import com.ggumtak.readeraplus.render.FontCatalog
import com.ggumtak.readeraplus.render.FontManager
import com.ggumtak.readeraplus.render.QuoteStyles
import com.ggumtak.readeraplus.settings.AppSettings
import com.ggumtak.readeraplus.settings.LibraryListMode
import com.ggumtak.readeraplus.settings.LibrarySort
import com.ggumtak.readeraplus.settings.ReadMode
import com.ggumtak.readeraplus.settings.Settings
import com.ggumtak.readeraplus.ui.kit.alert
import com.ggumtak.readeraplus.ui.kit.chooser
import com.ggumtak.readeraplus.ui.kit.confirm
import com.ggumtak.readeraplus.ui.kit.dp
import com.ggumtak.readeraplus.ui.kit.horizontal
import com.ggumtak.readeraplus.ui.kit.row
import com.ggumtak.readeraplus.ui.kit.showNoAnim
import com.ggumtak.readeraplus.ui.kit.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import android.provider.Settings as SystemSettings

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
    private var listPagingRow: View? = null
    private var swipeLightRow: View? = null
    private var deviceRow: View? = null
    private var restoreRow: View? = null
    private var swatches: LinearLayout? = null
    private var swatchInk: Boolean? = null
    /** "시스템 설정 수정" as last read on IO; null until the first read (the subtitle then assumes it is granted). */
    private var canWrite: Boolean? = null
    /** The device's original brightness mode is automatic ([DeviceLight.readOrigAuto]): the keep subtitle says so. */
    private var origAuto = false
    /** Verdict NONE: the app cannot change the front light here (the light rows are disabled). */
    private var lightNone = false
    /** The user went to the permission page from "기기 밝기 직접 조절": turn it on when they come back granted. */
    private var pendingDevice = false
    private var lightDialog: android.app.AlertDialog? = null
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
            ctx.chooser("서재 보기", all.map { R3Rows.libraryViewChoice(it) }, all.indexOf(Settings.app.libraryListMode)) { i ->
                editApp { it.copy(libraryListMode = all[i]) }
                listModeRow?.setSummary(all[i].label)
            }
        }.also(body::addView)
        listPagingRow = ctx.valueRow("목록 넘기기", R3Rows.listPagingSummary(app.listPaging, DeviceClass.cached(ctx))) {
            val opts = R3Rows.LIST_PAGINGS
            val eink = DeviceClass.cached(ctx)
            ctx.chooser("목록 넘기기", opts.map { R3Rows.listPaging(it, eink) }, opts.indexOf(Settings.app.listPaging)) { i ->
                editApp { it.copy(listPaging = opts[i]) }
                listPagingRow?.setSummary(R3Rows.listPagingSummary(opts[i], eink))
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
        swipeLightRow = ctx.toggleRow("스와이프로 밝기 조절", R3Rows.brightnessSwipe(app.readMode == ReadMode.SCROLL, false), app.brightnessSwipe) { v ->
            editApp { it.copy(brightnessSwipe = v) }
        }.also(body::addView)
        addBrightness(body, app)
        addHighlightLook(body, app)
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
        body.addView(ctx.row("설정 초기화", SettingsReset.SUMMARY) { resetSettings() })
        body.addView(ctx.navRow("정보", "버전 ${BuildConfig.VERSION_NAME}") { activity.push(SettingsActivity.PAGE_ABOUT) })
        return ctx.pageScroll(body)
    }

    override fun onShown() {
        refreshSummaries()
    }

    override fun onResume() {
        refreshSummaries()
    }

    override fun onDestroy() {
        runCatching { lightDialog?.dismiss() }
        lightDialog = null
    }

    // ---------------------------------------------------------------- brightness (UI_SPEC §4.6, brightness.md §5.6)

    private fun addBrightness(body: LinearLayout, app: AppSettings) {
        deviceRow = ctx.toggleRow(
            "기기 밝기 직접 조절",
            R3Rows.brightnessDevice(app.brightnessDevice, app.brightnessRestore, canWrite = true, none = false),
            app.brightnessDevice,
        ) { on -> onDeviceSwitch(on) }.also(body::addView)
        restoreRow = ctx.toggleRow("리더를 나가면 원래 밝기로", R3Rows.BRIGHTNESS_RESTORE, app.brightnessRestore) { v ->
            editApp { it.copy(brightnessRestore = v) }
            updateLightUi()
        }.also(body::addView)
        body.addView(ctx.row("밝기 방식 다시 확인", R3Rows.VERDICT_RESET) { resetVerdict() })
        body.addView(ctx.navRow("기기 조명 설정 열기", R3Rows.LIGHT_SETTINGS) { openDisplaySettings() })
        updateLightUi()
    }

    /**
     * The switch flipped to [on]. Turning it on needs "시스템 설정 수정": without it the switch stays off and dialog A
     * leads to the permission page; the grant is picked up in [refreshLight] when the user comes back. A tap while it
     * is on but the permission was revoked opens the same flow instead of turning it off.
     */
    private fun onDeviceSwitch(on: Boolean) {
        val app = Settings.app
        if (on && canWrite != true) {
            deviceRow?.setToggleChecked(false)
            askPermission()
            return
        }
        if (!on && app.brightnessDevice && canWrite == false) {
            deviceRow?.setToggleChecked(true)
            askPermission()
            return
        }
        if (app.brightnessDevice != on) editApp { it.copy(brightnessDevice = on) }
        updateLightUi()
    }

    /** Re-reads the permission and the verdict on IO (prefs and a system call; never on main), then updates the rows. */
    private fun refreshLight() {
        val appCtx = activity.applicationContext
        activity.scope.launch {
            val (write, verdict, auto) = withContext(Dispatchers.IO) {
                Triple(
                    runCatching { SystemSettings.System.canWrite(appCtx) }.getOrDefault(false),
                    runCatching { DeviceLight.verdict(appCtx) }.getOrDefault(DeviceLight.VERDICT_UNKNOWN),
                    DeviceLight.readOrigAuto(appCtx),
                )
            }
            canWrite = write
            if (auto != null) origAuto = auto
            lightNone = verdict == DeviceLight.VERDICT_NONE
            if (pendingDevice) {
                pendingDevice = false
                if (write && !lightNone) {
                    editApp { it.copy(brightnessDevice = true) }
                    deviceRow?.setToggleChecked(true)
                } else if (!write) {
                    ctx.toast("권한이 허용되지 않아 앱 화면 밝기로 조절합니다")
                }
            }
            updateLightUi()
        }
    }

    private fun updateLightUi() {
        val app = Settings.app
        val none = lightNone
        swipeLightRow?.let { row ->
            row.setRowEnabled(!none)
            row.setSummary(R3Rows.brightnessSwipe(app.readMode == ReadMode.SCROLL, none))
            row.setToggleChecked(app.brightnessSwipe)
        }
        deviceRow?.let { row ->
            row.setRowEnabled(!none)
            row.setSummary(R3Rows.brightnessDevice(app.brightnessDevice, app.brightnessRestore, canWrite != false, none, origAuto))
            row.setToggleChecked(app.brightnessDevice)
        }
        restoreRow?.let { row ->
            row.setRowEnabled(app.brightnessDevice && !none)
            row.setSummary(if (app.brightnessDevice) R3Rows.BRIGHTNESS_RESTORE else R3Rows.BRIGHTNESS_RESTORE_OFF)
        }
    }

    /** Dialog A (brightness.md §5.3), then the system's "시스템 설정 수정" page for this app. */
    private fun askPermission() {
        if (lightDialog?.isShowing == true) return
        lightDialog = ctx.alert()
            .setTitle("기기 밝기 직접 조절")
            .setMessage(R3Rows.DEVICE_DIALOG)
            .setNegativeButton("취소", null)
            .setPositiveButton("허용하러 가기") { _, _ -> openWritePermission() }
            .showNoAnim()
    }

    private fun openWritePermission() {
        val pkg = activity.packageName
        val withUri = Intent(SystemSettings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$pkg"))
        val plain = Intent(SystemSettings.ACTION_MANAGE_WRITE_SETTINGS)
        for (intent in arrayOf(withUri, plain)) {
            try {
                activity.startActivity(intent)
                pendingDevice = true
                return
            } catch (_: Exception) {
                // Trimmed firmware: try the next form, then explain the adb grant.
            }
        }
        lightDialog = ctx.alert()
            .setTitle("권한 화면을 찾을 수 없어요")
            .setMessage(R3Rows.NO_PERMISSION_SCREEN)
            .setNegativeButton("닫기", null)
            .setPositiveButton("명령 복사") { _, _ ->
                runCatching {
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("adb", R3Rows.ADB_GRANT))
                }
                ctx.toast("복사했습니다")
            }
            .showNoAnim()
    }

    /** "밝기 방식 다시 확인": the reader asks again at the next brightness change. */
    private fun resetVerdict() {
        val appCtx = activity.applicationContext
        activity.scope.launch {
            withContext(Dispatchers.IO) { runCatching { DeviceLight.setVerdict(appCtx, DeviceLight.VERDICT_UNKNOWN) } }
            lightNone = false
            updateLightUi()
            ctx.toast("다음에 밝기를 조절할 때 다시 묻습니다")
        }
    }

    private fun openDisplaySettings() {
        try {
            activity.startActivity(Intent(SystemSettings.ACTION_DISPLAY_SETTINGS))
        } catch (_: Exception) {
            ctx.toast("화면 위에서 아래로 내려 기기 조명을 조절하세요")
        }
    }

    // ---------------------------------------------------------------- 인용문 색 표시 (NOTES §11)

    private fun addHighlightLook(body: LinearLayout, app: AppSettings) {
        val eink = DeviceClass.cached(ctx)
        var lookRow: View? = null
        lookRow = ctx.valueRow("인용문 색 표시", R3Rows.highlightLook(app.highlightLook, eink)) {
            val opts = R3Rows.HL_LOOKS
            val e = DeviceClass.cached(ctx)
            ctx.chooser("인용문 색 표시", opts.map { R3Rows.highlightLook(it, e) }, opts.indexOf(Settings.app.highlightLook)) { i ->
                if (Settings.app.highlightLook != opts[i]) editApp { it.copy(highlightLook = opts[i]) }
                lookRow?.setSummary(R3Rows.highlightLook(opts[i], e))
                fillSwatches(R3Rows.inkLook(opts[i], e))
            }
        }.also(body::addView)
        swatches = ctx.horizontal {
            setPadding(ctx.dp(16), 0, ctx.dp(16), ctx.dp(8))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }.also(body::addView)
        fillSwatches(R3Rows.inkLook(app.highlightLook, eink))
        body.addView(ctx.note(R3Rows.HL_LOOK_NOTE))
    }

    /** A static strip of the six quote looks (22 × 14 dp each) as they are drawn on the page. */
    private fun fillSwatches(ink: Boolean) {
        val strip = swatches ?: return
        if (swatchInk == ink && strip.childCount > 0) return
        swatchInk = ink
        strip.removeAllViews()
        for (style in 0 until QuoteStyles.COUNT) {
            strip.addView(QuoteSwatch(ctx, style, SWATCH_W_DP, ink).apply { reserveRing = false }, LinearLayout.LayoutParams(ctx.dp(SWATCH_W_DP), ctx.dp(SWATCH_H_DP)).apply {
                if (style > 0) leftMargin = ctx.dp(8)
            })
        }
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
        listPagingRow?.setSummary(R3Rows.listPagingSummary(app.listPaging, DeviceClass.cached(ctx)))
        refreshLight()
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
        if (app.readMode == ReadMode.SCROLL) parts += "스크롤"
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
     * Back to the defaults, except what took the user work to set up (scan folders, assigned keys, the library view,
     * the TXT cleanup defaults: their replacement rules; resetting them would also re-parse every TXT once) and the
     * privacy and device choices (자동 백업, 찾아본 단어 기록, 기기 밝기 직접 조절, 목록 넘기기): see [SettingsReset].
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

private const val SWATCH_W_DP = 22
private const val SWATCH_H_DP = 14

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
