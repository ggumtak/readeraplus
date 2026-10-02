package com.ggumtak.readeraplus.ui.settings

import com.ggumtak.readeraplus.reader.extras.VoiceChoice
import com.ggumtak.readeraplus.reader.ReaderFormat
import com.ggumtak.readeraplus.reader.KeyMap
import com.ggumtak.readeraplus.render.DeviceCleanInfo
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
import com.ggumtak.readeraplus.settings.ReaderSettings
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

    /** "남은 시간" footer item ([ReaderSettings.footerTimeLeft]), the popup's [끔] [이 화] [책]. */
    val TIME_LEFT: List<Pair<String, Int>> = listOf(
        "끔" to ReaderSettings.TIME_LEFT_OFF,
        "이 화" to ReaderSettings.TIME_LEFT_EPISODE,
        "책" to ReaderSettings.TIME_LEFT_BOOK,
    )

    fun timeLeft(value: Int): String = TIME_LEFT.firstOrNull { it.second == value }?.first ?: TIME_LEFT[0].first

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

    /** "밤 모드(반전)에서" ([AppSettings.einkRefreshEveryNight]): -1 = same as by day. */
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
