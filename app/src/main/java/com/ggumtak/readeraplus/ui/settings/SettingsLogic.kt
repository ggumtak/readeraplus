package com.ggumtak.readeraplus.ui.settings

import com.ggumtak.readeraplus.data.AutoBackup
import com.ggumtak.readeraplus.engine.PageBreakMode
import com.ggumtak.readeraplus.reader.extras.VoiceChoice
import com.ggumtak.readeraplus.reader.ReaderFormat
import com.ggumtak.readeraplus.reader.KeyMap
import com.ggumtak.readeraplus.reader.LightPolicy
import com.ggumtak.readeraplus.render.DeviceCleanInfo
import com.ggumtak.readeraplus.render.StatusFit
import com.ggumtak.readeraplus.settings.AppSettings
import com.ggumtak.readeraplus.settings.EINK_MODE_FAST
import com.ggumtak.readeraplus.settings.EINK_MODE_HD
import com.ggumtak.readeraplus.settings.EINK_MODE_NORMAL
import com.ggumtak.readeraplus.settings.EINK_MODE_REGAL
import com.ggumtak.readeraplus.settings.EINK_MODE_SYSTEM
import com.ggumtak.readeraplus.settings.EINK_REFRESH_AUTO
import com.ggumtak.readeraplus.settings.EINK_REFRESH_CLEAN
import com.ggumtak.readeraplus.settings.EINK_REFRESH_FLASH
import com.ggumtak.readeraplus.settings.EINK_REFRESH_GC16
import com.ggumtak.readeraplus.settings.HL_LOOK_AUTO
import com.ggumtak.readeraplus.settings.HL_LOOK_COLOR
import com.ggumtak.readeraplus.settings.HL_LOOK_INK
import com.ggumtak.readeraplus.settings.LIST_PAGING_PAGED
import com.ggumtak.readeraplus.settings.LIST_PAGING_SCROLL
import com.ggumtak.readeraplus.settings.LibraryListMode
import com.ggumtak.readeraplus.settings.PageTheme
import com.ggumtak.readeraplus.settings.ReadMode
import com.ggumtak.readeraplus.settings.ReaderSettings
import com.ggumtak.readeraplus.settings.ScrollStyle
import com.ggumtak.readeraplus.settings.StatusItem
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/*
 * Pure helpers of the settings screens (no Android types; unit-tested on the JVM).
 */

/**
 * Converts folders picked with `ACTION_OPEN_DOCUMENT_TREE` into filesystem paths usable by the scanner
 * (the app scans with java.io.File under "모든 파일 접근").
 */
object TreePaths {
    const val PRIMARY_ROOT = "/storage/emulated/0"
    private val VOLUME_ID = Regex("^[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}$")

    /**
     * `content://com.android.externalstorage.documents/tree/primary%3ABooks` → `/storage/emulated/0/Books`.
     * Returns null when the URI is not a tree URI or its document id has no path equivalent (cloud providers,
     * media ids, ...).
     */
    fun fromTreeUri(uri: String?, primaryRoot: String = PRIMARY_ROOT, volumeRoots: Map<String, String> = emptyMap()): String? {
        if (uri.isNullOrEmpty()) return null
        val i = uri.indexOf("/tree/")
        if (i < 0) return null
        var seg = uri.substring(i + "/tree/".length)
        for (stop in charArrayOf('/', '?', '#')) {
            val k = seg.indexOf(stop)
            if (k >= 0) seg = seg.substring(0, k)
        }
        if (seg.isEmpty()) return null
        return fromDocumentId(percentDecode(seg), primaryRoot, volumeRoots)
    }

    /**
     * Document id (from `DocumentsContract.getTreeDocumentId`) → path:
     * `primary:Books` → `<primaryRoot>/Books`, `1A2B-3C4D:Novels` → `/storage/1A2B-3C4D/Novels`,
     * `home:x` → `<primaryRoot>/Documents/x`, `raw:/storage/...` → as is, `downloads` → `<primaryRoot>/Download`.
     * [volumeRoots] maps lower-case volume UUIDs to their mount directories (from `StorageManager`); it wins
     * over the `/storage/<id>` guess, which is only made for FAT-style `XXXX-XXXX` ids.
     */
    fun fromDocumentId(docId: String?, primaryRoot: String = PRIMARY_ROOT, volumeRoots: Map<String, String> = emptyMap()): String? {
        if (docId.isNullOrEmpty()) return null
        if (docId.startsWith("raw:")) return docId.substring(4).takeIf { it.startsWith("/") }?.let { FolderSets.normalize(it) }
        if (docId.startsWith("/")) return FolderSets.normalize(docId)
        if (docId == "downloads") return FolderSets.normalize("$primaryRoot/Download")
        val colon = docId.indexOf(':')
        if (colon <= 0) return null
        val volume = docId.substring(0, colon)
        val rest = docId.substring(colon + 1).trim('/')
        val base = when {
            volume.equals("primary", ignoreCase = true) -> primaryRoot
            volume.equals("home", ignoreCase = true) -> "$primaryRoot/Documents"
            volumeRoots.containsKey(volume.lowercase(Locale.ROOT)) -> volumeRoots.getValue(volume.lowercase(Locale.ROOT))
            VOLUME_ID.matches(volume) -> "/storage/${volume.uppercase(Locale.ROOT)}"
            else -> return null
        }
        return FolderSets.normalize(if (rest.isEmpty()) base else "$base/$rest")
    }

    /** RFC 3986 percent-decoding as UTF-8. Unlike URLDecoder, '+' stays '+'; malformed escapes stay literal. */
    fun percentDecode(s: String): String {
        if (s.indexOf('%') < 0) return s
        val out = ByteArrayOutputStream(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length) {
                val hi = Character.digit(s[i + 1], 16)
                val lo = Character.digit(s[i + 2], 16)
                if (hi >= 0 && lo >= 0) {
                    out.write(hi * 16 + lo)
                    i += 3
                    continue
                }
            }
            // Literal char (a surrogate pair is copied as one code point).
            val n = if (Character.isHighSurrogate(c) && i + 1 < s.length) 2 else 1
            out.write(s.substring(i, i + n).toByteArray(Charsets.UTF_8))
            i += n
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }
}

/** Scan / excluded folder set operations (paths are absolute, normalised, without trailing slash). */
object FolderSets {
    /** Result of [add]: the new set, whether it changed, and a short Korean message for a toast. */
    class AddResult(val folders: Set<String>, val added: Boolean, val message: String)

    /** Trims, turns '\' into '/', collapses repeated slashes and drops a trailing slash (except for "/"). */
    fun normalize(path: String): String {
        val t = path.trim().replace('\\', '/')
        if (t.isEmpty()) return ""
        val sb = StringBuilder(t.length)
        for (c in t) {
            if (c == '/' && sb.isNotEmpty() && sb[sb.length - 1] == '/') continue
            sb.append(c)
        }
        while (sb.length > 1 && sb[sb.length - 1] == '/') sb.setLength(sb.length - 1)
        return sb.toString()
    }

    /** True when [path] equals [parent] or lies below it. */
    fun isSameOrInside(path: String, parent: String): Boolean {
        if (path == parent) return true
        if (parent == "/") return path.startsWith("/")
        return path.startsWith("$parent/")
    }

    /**
     * Adds [path]: ignored when already covered by an entry; entries below the new folder are merged into it.
     */
    fun add(folders: Set<String>, path: String): AddResult {
        val p = normalize(path)
        if (p.isEmpty() || !p.startsWith("/")) return AddResult(folders, false, "올바른 폴더 경로가 아닙니다")
        folders.firstOrNull { isSameOrInside(p, it) }?.let { cover ->
            val msg = if (cover == p) "이미 추가된 폴더입니다" else "'${displayName(cover)}' 폴더에 이미 포함되어 있습니다"
            return AddResult(folders, false, msg)
        }
        val merged = folders.filter { isSameOrInside(it, p) }
        val next = LinkedHashSet<String>(folders.size + 1)
        folders.filterTo(next) { it !in merged }
        next += p
        val msg = if (merged.isEmpty()) "추가했습니다" else "추가했습니다 (하위 폴더 ${merged.size}개 합침)"
        return AddResult(next, true, msg)
    }

    fun remove(folders: Set<String>, path: String): Set<String> = folders.filterTo(LinkedHashSet()) { it != path }

    fun sorted(folders: Collection<String>): List<String> = folders.sortedWith(String.CASE_INSENSITIVE_ORDER)

    /** True when [path] lies inside one of [roots] (an excluded folder outside every root has no effect). */
    fun isCovered(path: String, roots: Collection<String>): Boolean = roots.any { isSameOrInside(normalize(path), normalize(it)) }

    /**
     * True when excluding [path] can't change anything: explicit scan folders are set ([scanFolders] non-empty)
     * and none of them contains it. (With no scan folders the scanner walks all storage, so every path counts.)
     */
    fun exclusionHasNoEffect(scanFolders: Collection<String>, path: String): Boolean =
        scanFolders.isNotEmpty() && !isCovered(path, scanFolders)

    /** True when excluding [path] would skip a whole scan folder (it equals or contains one of [scanFolders]). */
    fun exclusionHidesScanFolder(scanFolders: Collection<String>, path: String): Boolean {
        val p = normalize(path)
        return p.isNotEmpty() && scanFolders.any { isSameOrInside(normalize(it), p) }
    }

    /**
     * Friendly label: `/storage/emulated/0/Books` → `내부 저장소/Books`, `/storage/1A2B-3C4D/x` → `SD 카드 (1A2B-3C4D)/x`.
     */
    fun displayName(path: String, primaryRoot: String = TreePaths.PRIMARY_ROOT): String {
        val p = normalize(path)
        if (p == primaryRoot) return "내부 저장소"
        if (p.startsWith("$primaryRoot/")) return "내부 저장소/" + p.substring(primaryRoot.length + 1)
        val m = Regex("^/storage/([0-9A-Fa-f]{4}-[0-9A-Fa-f]{4})(/.*)?$").find(p)
        if (m != null) {
            val rest = m.groupValues[2]
            return "SD 카드 (${m.groupValues[1]})" + rest
        }
        return p
    }
}

/** Formatting of values shown in the settings rows. */
object SettingsFormat {
    /** Sleep timer choices in minutes (0 = off). */
    val SLEEP_OPTIONS: List<Int> = listOf(0, 15, 30, 45, 60, 90)

    /**
     * "수면 타이머" choices as (minutes, chapters) (T1-11): 끔 / 15 / 30 / 45 / 60 / 90분 / 이 화 끝까지 / 2화 끝까지.
     * Chapters ([AppSettings.ttsSleepChapters]) win over minutes, so a chapter choice stores minutes 0.
     */
    val SLEEP_CHOICES: List<Pair<Int, Int>> = SLEEP_OPTIONS.map { it to 0 } + listOf(0 to 1, 0 to 2)

    /** "끔", "30분", "1시간 30분", "이 화 끝까지", "2화 끝까지". */
    fun sleepChoice(minutes: Int, chapters: Int): String = when {
        chapters == 1 -> "이 화 끝까지"
        chapters >= 2 -> "${chapters}화 끝까지"
        else -> sleep(minutes)
    }

    /** Index of the saved values in [SLEEP_CHOICES]; -1 for values no choice offers (an older build's 10 / 120분). */
    fun sleepIndex(minutes: Int, chapters: Int): Int =
        if (chapters > 0) SLEEP_CHOICES.indexOfFirst { it.second == chapters } else SLEEP_CHOICES.indexOf(minutes to 0)

    /** "길게 누르기 시간" choices in ms ([AppSettings.longPressMs]). */
    val LONG_PRESS_OPTIONS: List<Int> = listOf(400, 500, 700, 1000)

    /** 400 → "0.4초", 1000 → "1.0초" (the default gets " (기본)" in the chooser, see [longPressChoice]). */
    fun longPress(ms: Int): String = String.format(Locale.US, "%.1f초", ms.coerceAtLeast(0) / 1000f)

    fun longPressChoice(ms: Int): String = longPress(ms) + if (ms == AppSettings().longPressMs) " (기본)" else ""

    /** A received book's second line on the Wi-Fi page: "12.3 MB · 서재에 추가됨". */
    fun received(bytes: Long, added: Boolean): String =
        bytes(bytes) + if (added) " · 서재에 추가됨" else " · 서재에 추가하지 못함"

    /** Screen orientation choices: label to `ActivityInfo.SCREEN_ORIENTATION_*` value. */
    val ORIENTATIONS: List<Pair<String, Int>> = listOf(
        "자동 회전" to -1,
        "세로" to 1,
        "가로" to 0,
        "세로 (뒤집힘)" to 9,
        "가로 (뒤집힘)" to 8,
    )

    fun orientation(value: Int): String = ORIENTATIONS.firstOrNull { it.second == value }?.first ?: "자동 회전"

    fun bytes(n: Long): String {
        if (n < 1024) return "${n.coerceAtLeast(0)} B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var v = n / 1024.0
        var u = 0
        while (v >= 1024 && u < units.size - 1) {
            v /= 1024
            u++
        }
        return if (v >= 100) String.format(Locale.US, "%.0f %s", v, units[u]) else String.format(Locale.US, "%.1f %s", v, units[u])
    }

    /** 30 → "30초", 60 → "1분", 90 → "1분 30초". */
    fun seconds(sec: Int): String {
        val s = sec.coerceAtLeast(0)
        if (s < 60) return "${s}초"
        val m = s / 60
        val r = s % 60
        return if (r == 0) "${m}분" else "${m}분 ${r}초"
    }

    /** Reader page e-ink modes (AppSettings.einkMode): label to value; the first one is the default. */
    val EINK_MODES: List<Pair<String, Int>> = listOf(
        "기기 설정 따름 (권장·기본)" to EINK_MODE_SYSTEM,
        "선명하게 (HD)" to EINK_MODE_HD,
        "잔상 적게 (REGAL)" to EINK_MODE_REGAL,
        "빠르게 (FAST)" to EINK_MODE_FAST,
        "보통 (NORMAL)" to EINK_MODE_NORMAL,
    )

    /** Label of an e-ink mode value (an unknown value reads as the default). */
    fun einkMode(value: Int): String = EINK_MODES.firstOrNull { it.second == value }?.first ?: EINK_MODES[0].first

    /** E-ink full refresh cadence: 0 → "끔", n → "n쪽마다". */
    fun refreshEvery(n: Int): String = if (n <= 0) "끔" else "${n}쪽마다"

    fun sleep(min: Int): String = when {
        min <= 0 -> "끔"
        min % 60 == 0 -> "${min / 60}시간"
        min > 60 -> "${min / 60}시간 ${min % 60}분"
        else -> "${min}분"
    }

    fun rate(v: Float): String = ReaderFormat.ttsRate(v)

    fun pitch(v: Float): String = ReaderFormat.ttsPitch(v)

    /** Live volume-row summary, including precedence of a custom key action. */
    fun volumeSummary(app: AppSettings): String = when {
        KeyMap.volumeBound(app) -> "키 지정에서 볼륨 키 동작을 정했습니다"
        !app.volumeKeysTurn -> "끄면 볼륨 키는 소리 크기를 조절합니다"
        app.invertVolumeKeys -> "볼륨 위 = 다음, 볼륨 아래 = 이전"
        else -> "볼륨 아래 = 다음, 볼륨 위 = 이전"
    }

    fun sp(v: Float): String = if (v == Math.round(v).toFloat()) "${Math.round(v)}sp" else String.format(Locale.US, "%.1fsp", v)

    /** "readeraplus-backup-20260929.json" (local date). */
    fun backupFileName(millis: Long, tz: TimeZone = TimeZone.getDefault()): String {
        val f = SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = tz }
        return "readeraplus-backup-${f.format(Date(millis))}.json"
    }

    fun dateTime(millis: Long, tz: TimeZone = TimeZone.getDefault()): String {
        val f = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply { timeZone = tz }
        return f.format(Date(millis))
    }
}

/**
 * The R3 rows of the settings pages (scroll SPEC §1.2 / §3.8, UI_SPEC §4.6 / §5.5, anchor §2.7 / §4.4, NOTES §11):
 * choices, labels, summaries and notes. "자동" choices show what they resolve to on this device; an unknown device
 * class (null, before the probe) resolves like a phone, the same rule the reader and the library apply.
 */
object R3Rows {
    // ---- 넘기는 방식 (scroll SPEC §1.2)

    val READ_MODES: List<ReadMode> = listOf(ReadMode.PAGED, ReadMode.SCROLL)

    fun readMode(m: ReadMode): String = if (m == ReadMode.SCROLL) "스크롤" else "페이지 넘김 (기본)"

    const val READ_MODE_NOTE =
        "스크롤에서도 화면 터치 · 볼륨 키 · 페이지 키는 한 화면씩 넘깁니다. 화면 아래에서 잘린 줄이 다음 화면의 첫 줄이 됩니다."

    val SCROLL_STYLES: List<ScrollStyle> = listOf(ScrollStyle.AUTO, ScrollStyle.SMOOTH, ScrollStyle.STEP)

    /**
     * "자동 (손가락을 따라 이동)" / "손가락을 따라 이동" / "손을 떼면 이동 (e-ink)". AUTO follows the finger on every device
     * since 2026-10-04 ([com.ggumtak.readeraplus.reader.ScrollWiring.stepMotion]); [eink] no longer changes a label.
     */
    @Suppress("UNUSED_PARAMETER")
    fun scrollStyle(s: ScrollStyle, eink: Boolean?): String = when (s) {
        ScrollStyle.AUTO -> "자동 (${ScrollStyle.SMOOTH.label})"
        ScrollStyle.SMOOTH -> s.label
        ScrollStyle.STEP -> "${s.label} (e-ink)"
    }

    const val SWIPE_TURN = "좌우로 밀어서 페이지 넘김 (오른쪽→왼쪽 = 다음)"
    const val SWIPE_TURN_SCROLL = "좌우로 밀면 한 화면씩"
    const val VERTICAL_SWIPE = "위로 밀면 다음 페이지, 아래로 밀면 이전 페이지"
    const val VERTICAL_SWIPE_SCROLL = "스크롤 모드에서는 쓰지 않음 (위아래로 끌면 스크롤)"

    /** "자동 (이 기기: …)". */
    fun auto(resolved: String): String = "자동 (이 기기: $resolved)"

    // ---- 인용문 색 표시 / 목록 넘기기 / 서재 보기 (NOTES §11, §10.2)

    val HL_LOOKS: List<Int> = listOf(HL_LOOK_AUTO, HL_LOOK_COLOR, HL_LOOK_INK)

    fun highlightLook(v: Int, eink: Boolean?): String = when (v) {
        HL_LOOK_COLOR -> "색"
        HL_LOOK_INK -> "흑백 무늬"
        else -> auto(if (eink == true) "흑백 무늬" else "색")
    }

    /** What [AppSettings.highlightLook] draws with on this device (true = the e-ink looks). */
    fun inkLook(v: Int, eink: Boolean?): Boolean = v == HL_LOOK_INK || (v != HL_LOOK_COLOR && eink == true)

    const val HL_LOOK_NOTE = "e-ink 화면에서는 색이 비슷한 회색으로 보여 무늬로 구분합니다 (노랑 = 회색+밑줄, 초록 = 옅은 회색+점선, " +
        "파랑 = 회색, 빨강 = 진한 회색+굵은 밑줄, 보라 = 테두리, 밑줄 = 밑줄만)"

    /** The 목록 넘기기 chooser: 스크롤 (also what 자동, the stored default, means on every device) or 쪽 단위. */
    val LIST_PAGINGS: List<Int> = listOf(LIST_PAGING_SCROLL, LIST_PAGING_PAGED)

    /** The row's value and the chooser's items: "스크롤" or "쪽 단위 (한 화면씩)". */
    fun listPaging(v: Int): String = if (v == LIST_PAGING_PAGED) "쪽 단위 (한 화면씩)" else "스크롤"

    /** The chooser's checked item for a stored value (자동 reads as 스크롤). */
    fun listPagingIndex(v: Int): Int = if (v == LIST_PAGING_PAGED) 1 else 0

    /** The "서재 보기" chooser (NOTES §10.2). */
    fun libraryViewChoice(m: LibraryListMode): String = when (m) {
        LibraryListMode.LIST -> "전체 — 표지 · 정보 · 버튼"
        LibraryListMode.COMPACT -> "요약 — 작은 표지와 한 줄 정보"
        LibraryListMode.GRID -> "썸네일 — 표지 3열"
        LibraryListMode.COVERS -> "그리드 — 작은 표지 4열"
    }

    // ---- 페이지 표시 (scroll SPEC §2.4, anchor §3.3 / §4.4)

    const val MARGIN_NOTE = "0이 기본 여백입니다. −로 좁히고 +로 넓힙니다. 위 · 아래 정보와 진행 막대는 이 여백 안에 표시됩니다."

    val PAGE_BREAKS: List<PageBreakMode> = listOf(PageBreakMode.LINE, PageBreakMode.PARAGRAPH)

    fun pageBreak(m: PageBreakMode): String = if (m == PageBreakMode.PARAGRAPH) "문단 단위" else "줄 단위"

    fun pageBreakChoice(m: PageBreakMode): String =
        if (m == PageBreakMode.PARAGRAPH) {
            "문단 단위 — 한 쪽에 들어가는 문단은 나누지 않습니다. 쪽 아래가 비기도 합니다."
        } else {
            "줄 단위 (기본) — 쪽을 끝까지 채웁니다. 문단이 다음 쪽으로 이어질 수 있습니다."
        }

    // ---- 화면 색 (읽기 설정 → 스타일; 웹소설 = 마루뷰어, 2026-10-04)

    val PAGE_THEMES: List<PageTheme> = listOf(PageTheme.PAPER, PageTheme.MARU)

    /** The "화면 색" chooser: the theme's name and what it looks like. */
    fun pageThemeChoice(t: PageTheme): String = when (t) {
        PageTheme.PAPER -> "${t.label} (기본)"
        PageTheme.MARU -> "${t.label} — 어두운 회색 바탕 · 밝은 글자 · 그림자"
    }

    const val PAGE_THEME_NOTE = "흑백 반전(넘김·화면 설정)을 켜 두면 화면 색과 상관없이 검은 바탕에 흰 글자로 보입니다."

    // ---- 상태 표시줄 (UI_SPEC §5.5, anchor §2.7)

    const val STATUS_NOTE = "위 · 아래 줄의 왼쪽 · 가운데 · 오른쪽에 보일 정보를 고르세요. 한 줄이 모두 '없음'이면 그 줄은 나타나지 않습니다."
    const val PROGRESS_SUMMARY = "화면 맨 아래에 읽은 위치를 가는 선과 점으로 표시"
    const val FIT_NOTE = "여백이 좁아 위 · 아래 정보가 보이지 않습니다. 상하 여백을 늘리세요."

    /** Margin used for the bands while "페이지 여백" is off (anchor §2.7). */
    const val NO_MARGIN_DP = 4

    /** "위 · 왼쪽" … "아래 · 오른쪽" ([band] 0 = top, [pos] 0..2 = left, centre, right). */
    fun slotTitle(band: Int, pos: Int): String =
        (if (band == 0) "위" else "아래") + " · " + when (pos) { 0 -> "왼쪽"; 1 -> "가운데"; else -> "오른쪽" }

    /** A slot chooser entry: "쪽 번호  (3 / 167)", or the bare label for items without an example. */
    fun slotChoice(item: StatusItem): String = item.label + (item.example?.let { "  ($it)" } ?: "")

    /** "상태 표시 글자 크기" matters only while a band shows text. */
    fun hasStatusText(r: ReaderSettings): Boolean = r.hasHeader || r.hasFooterText

    /** False when a band with items has no room in its margin (anchor §2.7: the grey note shows; nothing is disabled). */
    fun statusFits(r: ReaderSettings): Boolean {
        val top = if (r.pageMargins) r.marginTopDp else NO_MARGIN_DP
        val bottom = if (r.pageMargins) r.marginBottomDp else NO_MARGIN_DP
        if (r.hasHeader && !StatusFit.fitsDp(r.statusFontSizeSp, top, 0f)) return false
        if (r.hasFooterText && !StatusFit.footerFitsDp(r.statusFontSizeSp, bottom, r.progressBar)) return false
        return true
    }

    // ---- brightness (UI_SPEC §4.4 / §4.6, brightness.md §5.2)

    const val SWIPE_BRIGHTNESS = "화면 좌측을 위아래로 스와이프하여 밝기를 조절합니다"

    /** "스와이프로 밝기 조절" summary; [none] = verdict NONE (the row is disabled). */
    fun brightnessSwipe(scroll: Boolean, none: Boolean): String = when {
        none -> "이 기기에서는 밝기 스와이프를 쓸 수 없습니다"
        scroll -> "화면 왼쪽 끝(10%)을 위아래로 끌면 밝기 · 나머지는 스크롤"
        else -> SWIPE_BRIGHTNESS
    }

    /**
     * "기기 밝기 직접 조절" subtitle. [origAuto]: the original mode is automatic, which leaving the reader turns back on
     * even when the level stays (UI_SPEC §4.3 fix 2): the reader's options panel says the same (LightPolicy).
     */
    fun brightnessDevice(on: Boolean, restore: Boolean, canWrite: Boolean, none: Boolean, origAuto: Boolean = false): String = when {
        none -> "이 기기는 앱이 전면광을 바꿀 수 없습니다"
        on && !canWrite -> "'시스템 설정 수정' 권한이 필요합니다 · 눌러서 허용"
        !on -> "전면광이 안 바뀔 때 켜세요 · 기기 전체 밝기를 바꿉니다"
        restore -> "기기 전체 밝기를 바꿉니다 · 리더를 나가면 원래대로"
        origAuto -> LightPolicy.DEVICE_ON_KEEP_AUTO
        else -> "기기 전체 밝기를 바꿉니다 · 나가도 그대로 유지"
    }

    const val BRIGHTNESS_RESTORE = "켜 두면 다른 앱과 서재는 원래 밝기를 씁니다. 끄면 리더에서 바꾼 밝기가 기기 밝기로 남습니다."
    const val BRIGHTNESS_RESTORE_OFF = "'기기 밝기 직접 조절'을 켜면 쓸 수 있습니다"
    const val VERDICT_RESET = "다음에 밝기를 조절할 때 어떤 방식이 되는지 다시 묻습니다"
    const val LIGHT_SETTINGS = "밝기 · 색온도를 기기 설정에서 조절"

    /** brightness.md §5.3, verbatim. */
    const val DEVICE_DIALOG = "이 기기의 전면광은 앱 화면 밝기를 따르지 않을 수 있습니다.\n" +
        "'시스템 설정 수정'을 허용하면 리더가 기기 밝기 설정을 직접 바꿉니다.\n\n" +
        "· 기기 전체 밝기가 바뀝니다. 다른 앱에서도 같은 밝기가 보일 수 있습니다.\n" +
        "· 리더를 나가면 원래 밝기로 되돌립니다. (설정에서 바꿀 수 있어요)\n" +
        "· 다음 화면에서 ReaderaPlus를 찾아 허용을 켠 뒤 돌아오세요."

    const val ADB_GRANT = "adb shell appops set com.ggumtak.readeraplus WRITE_SETTINGS allow"

    /** brightness.md §5.5. */
    const val NO_PERMISSION_SCREEN = "이 기기에는 '시스템 설정 수정' 화면이 없습니다. PC에 연결해 다음 명령으로 허용할 수 있습니다.\n$ADB_GRANT"

    // ---- 자동 백업 (scroll SPEC §3.8, NOTES §11)

    /** The toggle's summary; [lastAt] 0 = nothing written yet. */
    fun autoBackupSummary(fullAccess: Boolean, location: String, lastAt: Long, tz: TimeZone = TimeZone.getDefault()): String =
        if (fullAccess) {
            "앱을 지워도 남는 곳에 저장 · $location" + if (lastAt > 0) " · 마지막: ${SettingsFormat.dateTime(lastAt, tz)}" else ""
        } else {
            "모든 파일 접근 권한이 없어 ${location}에 저장합니다. 다시 설치한 뒤에는 권한을 허용해야 자동으로 찾습니다."
        }

    /** The status line after "지금 자동 백업하기". */
    fun autoBackupOutcome(o: AutoBackup.Outcome, location: String): String = when (o) {
        AutoBackup.Outcome.WROTE -> "자동 백업을 저장했습니다 · $location"
        AutoBackup.Outcome.UNCHANGED -> "지난 자동 백업과 같아 새로 저장하지 않았습니다"
        AutoBackup.Outcome.NOT_DUE -> "오늘 이미 자동 백업했습니다"
        AutoBackup.Outcome.DISABLED -> "자동 백업이 꺼져 있습니다"
        AutoBackup.Outcome.NO_LOCATION -> "저장할 곳($location)을 쓸 수 없습니다"
        AutoBackup.Outcome.BUSY -> "책을 읽는 중이라 저장하지 않았습니다. 잠시 뒤 다시 해 보세요"
        AutoBackup.Outcome.SKIPPED_EMPTY -> "저장할 읽기 기록이 없습니다"
        AutoBackup.Outcome.FAILED -> "자동 백업 실패"
    }

    /** One restore choice: "2026-09-30 08:12 · 책 12권 (읽던 책 3권) · 북마크 4개 · 인용문 5개 · 자동". */
    fun candidate(createdAt: Long, s: AutoBackup.Summary, auto: Boolean, tz: TimeZone = TimeZone.getDefault()): String =
        "${SettingsFormat.dateTime(createdAt, tz)} · 책 ${s.books}권 (읽던 책 ${s.read}권) · 북마크 ${s.bookmarks}개 · " +
            "인용문 ${s.quotes}개 · " + if (auto) "자동" else "직접 내보냄"

    /** "자동 백업 파일 지우기" confirm; [others] = this install may delete other installs' files too. */
    fun deleteAutoFiles(n: Int, others: Boolean): String =
        if (others) {
            "자동 백업 파일 ${n}개를 지울까요? 이전 설치의 파일도 함께 지웁니다."
        } else {
            "이 설치에서 만든 자동 백업 파일 ${n}개를 지울까요? 이전 설치의 파일은 '모든 파일 접근'을 허용해야 지울 수 있습니다."
        }

    const val BACKUP_PRIVACY = "백업에는 책 제목 · 경로 · 읽은 기록 · 북마크 · 인용문 · 메모 · 리뷰 · 단어장이 들어 있습니다. " +
        "공용 저장소에 있으므로 USB로 연결한 PC나 '모든 파일 접근' 권한이 있는 앱(Android 10 이하에서는 저장소 권한이 있는 앱)이 " +
        "읽을 수 있고, 다운로드 폴더를 동기화하도록 설정한 앱이 있으면 그 앱이 올릴 수 있습니다. 이 앱은 인터넷으로 보내지 않습니다."

    const val BACKUP_MERGE = "다른 기기에서 만든 백업을 복원하면 두 기기의 인용문·메모·북마크·단어장이 합쳐지고, 읽던 위치는 더 최근 것이 " +
        "남습니다. 지워지는 것은 없습니다. 이 기기에 없는 책의 노트는 휴지통에 '(파일 없음)'으로 보관됩니다."

    // ---- 단어장 (NOTES §11)

    const val RECORD_LOOKUPS = "선택한 글자를 사전·번역·웹 검색으로 찾으면 단어장에 문장과 함께 남깁니다. 기기 안에만 저장됩니다"

    fun clearLookups(n: Int): String = "찾아본 단어 ${n}개를 모두 지울까요?"
}

/**
 * "설정 초기화" (MainPage, K12): back to the defaults except what took the user work to set up or is a privacy or
 * device choice. Reader settings go to [ReaderSettings] defaults (40/40/40/40 margins, 줄 단위, the default status
 * slots) but keep the TXT cleanup defaults; app settings keep the scan folders, the assigned keys, the library sort
 * and view, 목록 넘기기, 자동 백업, 찾아본 단어 기록 and 기기 밝기 직접 조절.
 */
object SettingsReset {
    const val MESSAGE = "글꼴 · 글자 크기 · 간격 · 여백과 넘김 · 화면 설정을 기본값으로 되돌릴까요?\n" +
        "TXT 정리 설정 · 스캔 폴더 · 지정한 키 · 서재 정렬과 보기 · 목록 넘기기 · 자동 백업 · 찾아본 단어 기록 · " +
        "기기 밝기 직접 조절은 그대로 둡니다."

    const val SUMMARY = "읽기 · 넘김 · 화면 설정을 기본값으로 (TXT 정리 설정 · 스캔 폴더 · 키 지정 · 서재 · 백업 · 단어장 · 기기 밝기 설정은 유지)"

    fun app(old: AppSettings): AppSettings = AppSettings().copy(
        scanFolders = old.scanFolders,
        excludedFolders = old.excludedFolders,
        nextPageKeys = old.nextPageKeys,
        prevPageKeys = old.prevPageKeys,
        keyBindings = old.keyBindings,
        librarySort = old.librarySort,
        libraryListMode = old.libraryListMode,
        listPaging = old.listPaging,
        autoBackup = old.autoBackup,
        recordLookups = old.recordLookups,
        brightnessDevice = old.brightnessDevice,
    )

    fun reader(old: ReaderSettings): ReaderSettings = ReaderSettings().copy(
        txtBlankLines = old.txtBlankLines,
        txtStripIndent = old.txtStripIndent,
        txtJoinWrappedLines = old.txtJoinWrappedLines,
        txtDetectChapters = old.txtDetectChapters,
        txtChapterRegex = old.txtChapterRegex,
        txtEmphasizeHeadings = old.txtEmphasizeHeadings,
        txtReplaceRules = old.txtReplaceRules,
    )
}

/** The e-ink rows of "넘김·화면 설정" (T1-3): choices, labels and the device readout. */
object EinkChoices {
    /** "새로고침 방식" ([AppSettings.einkRefreshMethod]): label to value, in the chooser's order. */
    val METHODS: List<Pair<String, Int>> = listOf(
        "자동 (기본)" to EINK_REFRESH_AUTO,
        "기기 GC16" to EINK_REFRESH_GC16,
        "기기 잔상 제거 (CLEAN)" to EINK_REFRESH_CLEAN,
        "검은 화면 깜빡임" to EINK_REFRESH_FLASH,
    )

    /** True for the Bigme xrz methods, offered only where the firmware has the global refresh (`Eink.hasXrzRefresh`). */
    fun isDeviceMethod(method: Int): Boolean = method == EINK_REFRESH_GC16 || method == EINK_REFRESH_CLEAN

    /** The methods this device can offer. */
    fun methods(hasXrz: Boolean): List<Pair<String, Int>> = METHODS.filter { hasXrz || !isDeviceMethod(it.second) }

    /** Label of a stored method (an unknown value reads as 자동). */
    fun method(value: Int): String = METHODS.firstOrNull { it.second == value }?.first ?: METHODS[0].first

    /** The method [다른 방식 시험] tries after [current]: the next one this device offers, wrapping around. */
    fun next(current: Int, hasXrz: Boolean): Int {
        val list = methods(hasXrz).map { it.second }
        val i = list.indexOf(current)
        return list[(i + 1).mod(list.size)]
    }

    /** "깜빡임 길이" ([AppSettings.einkFlashMs]). */
    val FLASH_MS: List<Int> = listOf(100, 200, 350)

    fun flash(ms: Int): String = "${ms}ms" + if (ms == AppSettings().einkFlashMs) " (기본)" else ""

    /**
     * The cadence row of a dark page ([AppSettings.einkRefreshEveryNight]): 흑백 반전, and the 마루뷰어 화면 색 since
     * 2026-10-04 (`PagePalette.dark`); it was "밤 모드(반전)에서".
     */
    const val NIGHT_TITLE = "어두운 화면(흑백 반전 · 마루뷰어)에서"

    /** [NIGHT_TITLE]'s choices: -1 = same as by day. */
    val NIGHT: List<Int> = listOf(-1, 3, 5, 10, 20)

    fun night(value: Int): String = if (value < 0) "낮과 같게" else SettingsFormat.refreshEvery(value)

    /** "기기 자체 잔상 제거: 켜짐 · 10쪽마다" / "…: 켜짐" / "…: 꺼짐"; null when the device has no such setting. */
    fun deviceClean(info: DeviceCleanInfo?): String? {
        info ?: return null
        val state = when {
            !info.autoClean -> "꺼짐"
            info.everyPages > 0 -> "켜짐 · ${info.everyPages}쪽마다"
            else -> "켜짐"
        }
        return "기기 자체 잔상 제거: $state"
    }

    const val DOUBLE_FLASH = "기기와 앱이 모두 잔상을 지우면 두 번 깜빡입니다. 한쪽만 켜세요."

    /**
     * The "진단" readout of the 고급 group (T1-3a, from T2-20): the vendor API found, whether the device refresh methods
     * and view modes work here, the device's own ghost clearing and the screen.
     */
    fun diagnostics(
        vendor: String?,
        hasXrz: Boolean,
        viewMode: Boolean,
        clean: DeviceCleanInfo?,
        widthPx: Int,
        heightPx: Int,
        dpi: Int,
    ): String =
        listOf(
            "e-ink 제어: " + (vendor ?: "없음 (검은 화면을 잠깐 띄워 잔상을 지웁니다)"),
            "기기 새로고침 (GC16 · CLEAN): " + if (hasXrz) "사용 가능" else "없음",
            "e-ink 화면 모드 바꾸기: " + if (viewMode) "가능" else "안 됨",
            deviceClean(clean) ?: "기기 자체 잔상 제거: 확인할 수 없음",
            "화면: $widthPx × $heightPx px · $dpi dpi",
        ).joinToString("\n")

    /** True when the device clears ghosts by itself and the app has a page cadence too (day or night). */
    fun doubleFlash(info: DeviceCleanInfo?, app: AppSettings): Boolean =
        info?.autoClean == true && (app.einkRefreshEvery > 0 || app.einkRefreshEveryNight > 0)
}

/**
 * The "목소리" chooser of the TTS page (A13): Korean voices first, then the other languages by name, each numbered
 * within its language: "한국어 · 목소리 2 (고음질, 오프라인)". The same wording as the reader's own voice chooser.
 */
object TtsVoices {
    /** One engine voice: [lang] = ISO 639 code, [language] = its name in Korean ("한국어"). */
    class Info(
        val name: String,
        val lang: String,
        val language: String,
        val quality: Int,
        val network: Boolean,
        val notInstalled: Boolean,
    )

    /** "기본 음성": [AppSettings.ttsVoice] "" (the engine's default voice for Korean). */
    const val DEFAULT = "기본 음성"

    fun list(voices: List<Info>): List<Pair<Info, String>> {
        val sorted = voices.sortedWith(
            compareBy<Info>({ if (it.lang == "ko") 0 else 1 }, { it.language }, { it.lang }, { it.name }),
        )
        val counts = HashMap<String, Int>()
        return sorted.map { v ->
            val n = (counts[v.lang] ?: 0) + 1
            counts[v.lang] = n
            v to label(v, n)
        }
    }

    /** The reader's own wording ([VoiceChoice.label]), so a voice reads the same in both choosers. */
    fun label(v: Info, number: Int): String =
        VoiceChoice.label(VoiceChoice.Info(v.name, v.lang, v.language, v.quality, v.network, v.notInstalled), number)

    /** android.speech.tts.Voice.QUALITY_*: 400 and up high, 300 normal, below low. */
    fun quality(q: Int): String = VoiceChoice.quality(q)
}

/** Web search URL templates offered in "사전 · 번역 · 웹 검색". `%s` = URL-encoded query. */
object WebEngines {
    class Engine(val name: String, val url: String)

    val PRESETS: List<Engine> = listOf(
        Engine("Google", "https://www.google.com/search?q=%s"),
        Engine("네이버", "https://search.naver.com/search.naver?query=%s"),
        Engine("다음", "https://search.daum.net/search?q=%s"),
        Engine("네이버 국어사전", "https://ko.dict.naver.com/#/search?query=%s"),
        Engine("위키백과", "https://ko.wikipedia.org/w/index.php?search=%s"),
        Engine("나무위키", "https://namu.wiki/Search?q=%s"),
    )

    /** Index of the preset with this template, or -1 (custom). */
    fun indexOf(url: String): Int = PRESETS.indexOfFirst { it.url == url.trim() }

    fun nameOf(url: String): String = PRESETS.getOrNull(indexOf(url))?.name ?: "사용자 지정"

    /**
     * Cleans user input: trims, adds "https://" when no scheme is given. Returns null when the template has no
     * `%s`, contains whitespace or is not http(s).
     */
    fun normalizeTemplate(input: String): String? {
        var t = input.trim()
        if (t.isEmpty() || t.any { it.isWhitespace() }) return null
        if (!t.contains("://")) t = "https://$t"
        val lower = t.lowercase(Locale.ROOT)
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return null
        if (!t.contains("%s")) return null
        return t
    }

    fun build(template: String, query: String): String =
        template.replace("%s", URLEncoder.encode(query, "UTF-8").replace("+", "%20"))
}
