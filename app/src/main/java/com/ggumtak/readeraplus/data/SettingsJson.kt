package com.ggumtak.readeraplus.data

import com.ggumtak.readeraplus.settings.AppSettings
import com.ggumtak.readeraplus.settings.ReaderSettings
import com.ggumtak.readeraplus.settings.TapAction
import org.json.JSONArray
import org.json.JSONObject

/** A raw SharedPreferences entry restored from a backup. [type] is one of the `TYPE_*` names. */
internal data class RawPref(val key: String, val type: String, val value: Any)

/**
 * Settings ⇄ JSON for backups (pure). Keys are the SharedPreferences keys of `Settings` ("r.fontId",
 * "a.tapZoneMode", …) so a backup doubles as a readable prefs dump. Restoring goes through typed
 * ReaderSettings / AppSettings objects (never raw puts) so a malformed backup can't plant a wrongly typed
 * pref that would crash Settings on load; values are clamped to sane ranges.
 */
internal object SettingsJson {
    const val TYPE_STRING = "string"
    const val TYPE_INT = "int"
    const val TYPE_LONG = "long"
    const val TYPE_FLOAT = "float"
    const val TYPE_BOOL = "bool"
    const val TYPE_SET = "set"

    const val READER_PREFIX = "r."
    const val APP_PREFIX = "a."

    /**
     * Raw pref keys that are device/session state and must not travel with a backup (matched as lower-case
     * substrings). The permission ones would hide the "모든 파일 접근" panel on a device that lacks the permission.
     */
    private val TRANSIENT = listOf("lastscan", "lastbackup", "cacheepoch", "permpanelhidden", "legacypermasked")

    fun isTransient(key: String): Boolean {
        val k = key.lowercase()
        return TRANSIENT.any { k.contains(it) }
    }

    // ---- reader ----

    fun readerToJson(s: ReaderSettings): JSONObject = JSONObject()
        .put("r.fontId", s.fontId)
        .put("r.fontSizeSp", s.fontSizeSp.toDouble())
        .put("r.fontWeight", s.fontWeight)
        .put("r.lineHeightPct", s.lineHeightPct)
        .put("r.paragraphSpacingPct", s.paragraphSpacingPct)
        .put("r.indentPct", s.indentPct)
        .put("r.letterSpacingPm", s.letterSpacingPm)
        .put("r.align", s.align.name)
        .put("r.lineBreak", s.lineBreak.name)
        .put("r.marginLeftDp", s.marginLeftDp)
        .put("r.marginRightDp", s.marginRightDp)
        .put("r.marginTopDp", s.marginTopDp)
        .put("r.marginBottomDp", s.marginBottomDp)
        .put("r.pageMargins", s.pageMargins)
        .put("r.invert", s.invert)
        .put("r.showHeader", s.showHeader)
        .put("r.showFooter", s.showFooter)
        .put("r.footerPage", s.footerPage)
        .put("r.footerChapterLeft", s.footerChapterLeft)
        .put("r.footerPercent", s.footerPercent)
        .put("r.footerClock", s.footerClock)
        .put("r.footerBattery", s.footerBattery)
        .put("r.statusFontSizeSp", s.statusFontSizeSp.toDouble())
        .put("r.widowOrphanControl", s.widowOrphanControl)
        .put("r.txtBlankLines", s.txtBlankLines)
        .put("r.txtStripIndent", s.txtStripIndent)
        .put("r.txtJoinWrappedLines", s.txtJoinWrappedLines)
        .put("r.txtDetectChapters", s.txtDetectChapters)
        .put("r.txtChapterRegex", s.txtChapterRegex)
        .put("r.txtEmphasizeHeadings", s.txtEmphasizeHeadings)
        .put("r.txtReplaceRules", s.txtReplaceRules)
        .put("r.epubPublisherStyles", s.epubPublisherStyles)

    /** Fields missing from [o] keep their value from [base] (as do fields this mapper doesn't name). */
    fun readerFromJson(o: JSONObject, base: ReaderSettings): ReaderSettings = base.copy(
        fontId = BackupJson.str(o, "r.fontId", base.fontId).trim().ifEmpty { base.fontId },
        fontSizeSp = BackupJson.float(o, "r.fontSizeSp", base.fontSizeSp)
            .coerceIn(ReaderSettings.MIN_FONT_SP, ReaderSettings.MAX_FONT_SP),
        fontWeight = BackupJson.int(o, "r.fontWeight", base.fontWeight).coerceIn(100, 900),
        lineHeightPct = BackupJson.int(o, "r.lineHeightPct", base.lineHeightPct).coerceIn(50, 500),
        paragraphSpacingPct = BackupJson.int(o, "r.paragraphSpacingPct", base.paragraphSpacingPct).coerceIn(0, 1000),
        indentPct = BackupJson.int(o, "r.indentPct", base.indentPct).coerceIn(0, 1000),
        letterSpacingPm = BackupJson.int(o, "r.letterSpacingPm", base.letterSpacingPm).coerceIn(-500, 1000),
        align = enumOf(BackupJson.strOrNull(o, "r.align"), base.align),
        lineBreak = enumOf(BackupJson.strOrNull(o, "r.lineBreak"), base.lineBreak),
        marginLeftDp = BackupJson.int(o, "r.marginLeftDp", base.marginLeftDp).coerceIn(0, 300),
        marginRightDp = BackupJson.int(o, "r.marginRightDp", base.marginRightDp).coerceIn(0, 300),
        marginTopDp = BackupJson.int(o, "r.marginTopDp", base.marginTopDp).coerceIn(0, 300),
        marginBottomDp = BackupJson.int(o, "r.marginBottomDp", base.marginBottomDp).coerceIn(0, 300),
        pageMargins = BackupJson.bool(o, "r.pageMargins", base.pageMargins),
        invert = BackupJson.bool(o, "r.invert", base.invert),
        showHeader = BackupJson.bool(o, "r.showHeader", base.showHeader),
        showFooter = BackupJson.bool(o, "r.showFooter", base.showFooter),
        footerPage = BackupJson.bool(o, "r.footerPage", base.footerPage),
        footerChapterLeft = BackupJson.bool(o, "r.footerChapterLeft", base.footerChapterLeft),
        footerPercent = BackupJson.bool(o, "r.footerPercent", base.footerPercent),
        footerClock = BackupJson.bool(o, "r.footerClock", base.footerClock),
        footerBattery = BackupJson.bool(o, "r.footerBattery", base.footerBattery),
        statusFontSizeSp = BackupJson.float(o, "r.statusFontSizeSp", base.statusFontSizeSp).coerceIn(6f, 40f),
        widowOrphanControl = BackupJson.bool(o, "r.widowOrphanControl", base.widowOrphanControl),
        txtBlankLines = BackupJson.int(o, "r.txtBlankLines", base.txtBlankLines).coerceIn(0, 3),
        txtStripIndent = BackupJson.bool(o, "r.txtStripIndent", base.txtStripIndent),
        txtJoinWrappedLines = BackupJson.int(o, "r.txtJoinWrappedLines", base.txtJoinWrappedLines).coerceIn(0, 2),
        txtDetectChapters = BackupJson.bool(o, "r.txtDetectChapters", base.txtDetectChapters),
        txtChapterRegex = BackupJson.str(o, "r.txtChapterRegex", base.txtChapterRegex),
        txtEmphasizeHeadings = BackupJson.bool(o, "r.txtEmphasizeHeadings", base.txtEmphasizeHeadings),
        txtReplaceRules = BackupJson.str(o, "r.txtReplaceRules", base.txtReplaceRules),
        epubPublisherStyles = BackupJson.bool(o, "r.epubPublisherStyles", base.epubPublisherStyles),
    )

    // ---- app ----

    fun appToJson(s: AppSettings): JSONObject = JSONObject()
        .put("a.tapZoneMode", s.tapZoneMode.name)
        .put("a.customTapZones", s.customTapZones.joinToString(",") { it.name })
        .put("a.swipeToTurn", s.swipeToTurn)
        .put("a.verticalSwipe", s.verticalSwipe)
        .put("a.volumeKeysTurn", s.volumeKeysTurn)
        .put("a.invertVolumeKeys", s.invertVolumeKeys)
        .put("a.nextPageKeys", JSONArray().also { a -> s.nextPageKeys.sorted().forEach { a.put(it) } })
        .put("a.prevPageKeys", JSONArray().also { a -> s.prevPageKeys.sorted().forEach { a.put(it) } })
        .put("a.longPressSelect", s.longPressSelect)
        .put("a.bookmarkByTouch", s.bookmarkByTouch)
        .put("a.invertByTouch", s.invertByTouch)
        .put("a.fullscreen", s.fullscreen)
        .put("a.keepScreenOn", s.keepScreenOn)
        .put("a.brightnessSwipe", s.brightnessSwipe)
        .put("a.openLastOnStart", s.openLastOnStart)
        .put("a.einkRefreshEvery", s.einkRefreshEvery)
        .put("a.einkRefreshOnChapter", s.einkRefreshOnChapter)
        .put("a.autoTurnSeconds", s.autoTurnSeconds)
        .put("a.ttsRate", s.ttsRate.toDouble())
        .put("a.ttsPitch", s.ttsPitch.toDouble())
        .put("a.ttsSleepMinutes", s.ttsSleepMinutes)
        .put("a.webSearchUrl", s.webSearchUrl)
        .put("a.librarySort", s.librarySort.name)
        .put("a.libraryListMode", s.libraryListMode.name)
        .put("a.scanFolders", JSONArray().also { a -> s.scanFolders.sorted().forEach { a.put(it) } })
        .put("a.excludedFolders", JSONArray().also { a -> s.excludedFolders.sorted().forEach { a.put(it) } })
        .put("a.orientationLock", s.orientationLock)
        .put("a.brightness", if (s.brightness.isFinite()) s.brightness.toDouble() else -1.0)

    /**
     * Fields missing from [o] keep their value from [base]. Fields this mapper doesn't name (newer settings) are
     * also kept from [base] (`copy`), never reset to defaults; their stored values are restored separately via
     * [unmappedFromJson].
     */
    fun appFromJson(o: JSONObject, base: AppSettings): AppSettings {
        val brightness = BackupJson.float(o, "a.brightness", base.brightness)
        return base.copy(
            tapZoneMode = enumOf(BackupJson.strOrNull(o, "a.tapZoneMode"), base.tapZoneMode),
            customTapZones = tapZones(o.opt("a.customTapZones")) ?: base.customTapZones,
            swipeToTurn = BackupJson.bool(o, "a.swipeToTurn", base.swipeToTurn),
            verticalSwipe = BackupJson.bool(o, "a.verticalSwipe", base.verticalSwipe),
            volumeKeysTurn = BackupJson.bool(o, "a.volumeKeysTurn", base.volumeKeysTurn),
            invertVolumeKeys = BackupJson.bool(o, "a.invertVolumeKeys", base.invertVolumeKeys),
            nextPageKeys = intSet(o.opt("a.nextPageKeys")) ?: base.nextPageKeys,
            prevPageKeys = intSet(o.opt("a.prevPageKeys")) ?: base.prevPageKeys,
            longPressSelect = BackupJson.bool(o, "a.longPressSelect", base.longPressSelect),
            bookmarkByTouch = BackupJson.bool(o, "a.bookmarkByTouch", base.bookmarkByTouch),
            invertByTouch = BackupJson.bool(o, "a.invertByTouch", base.invertByTouch),
            fullscreen = BackupJson.bool(o, "a.fullscreen", base.fullscreen),
            keepScreenOn = BackupJson.bool(o, "a.keepScreenOn", base.keepScreenOn),
            brightnessSwipe = BackupJson.bool(o, "a.brightnessSwipe", base.brightnessSwipe),
            openLastOnStart = BackupJson.bool(o, "a.openLastOnStart", base.openLastOnStart),
            einkRefreshEvery = BackupJson.int(o, "a.einkRefreshEvery", base.einkRefreshEvery).coerceIn(0, 100),
            einkRefreshOnChapter = BackupJson.bool(o, "a.einkRefreshOnChapter", base.einkRefreshOnChapter),
            autoTurnSeconds = BackupJson.int(o, "a.autoTurnSeconds", base.autoTurnSeconds).coerceIn(1, 3600),
            ttsRate = BackupJson.float(o, "a.ttsRate", base.ttsRate).coerceIn(0.1f, 4f),
            ttsPitch = BackupJson.float(o, "a.ttsPitch", base.ttsPitch).coerceIn(0.1f, 4f),
            ttsSleepMinutes = BackupJson.int(o, "a.ttsSleepMinutes", base.ttsSleepMinutes).coerceIn(0, 24 * 60),
            webSearchUrl = BackupJson.str(o, "a.webSearchUrl", base.webSearchUrl).trim().ifEmpty { base.webSearchUrl },
            librarySort = enumOf(BackupJson.strOrNull(o, "a.librarySort"), base.librarySort),
            libraryListMode = enumOf(BackupJson.strOrNull(o, "a.libraryListMode"), base.libraryListMode),
            scanFolders = stringSet(o.opt("a.scanFolders")) ?: base.scanFolders,
            excludedFolders = stringSet(o.opt("a.excludedFolders")) ?: base.excludedFolders,
            orientationLock = BackupJson.int(o, "a.orientationLock", base.orientationLock).coerceIn(-1, 20),
            brightness = if (brightness < 0f) -1f else brightness.coerceAtMost(1f),
        )
    }

    // ---- other raw prefs ----

    /**
     * Raw prefs that are neither reader ("r.") nor app ("a.") settings nor transient, as
     * `values` + `types` objects (JSON loses the Int/Long/Float distinction, so types are kept separately).
     */
    fun otherToJson(all: Map<String, *>): Pair<JSONObject, JSONObject> {
        val values = JSONObject()
        val types = JSONObject()
        for ((k, v) in all.entries.sortedBy { it.key }) {
            if (k.startsWith("r.") || k.startsWith("a.") || isTransient(k) || v == null) continue
            when (v) {
                is String -> { values.put(k, v); types.put(k, TYPE_STRING) }
                is Boolean -> { values.put(k, v); types.put(k, TYPE_BOOL) }
                is Int -> { values.put(k, v); types.put(k, TYPE_INT) }
                is Long -> { values.put(k, v); types.put(k, TYPE_LONG) }
                is Float -> if (v.isFinite()) { values.put(k, v.toDouble()); types.put(k, TYPE_FLOAT) }
                is Set<*> -> {
                    values.put(k, JSONArray().also { a -> v.filterIsInstance<String>().sorted().forEach { a.put(it) } })
                    types.put(k, TYPE_SET)
                }
                else -> {}
            }
        }
        return values to types
    }

    /** Typed entries to restore; entries whose value doesn't fit their type are dropped. */
    fun otherFromJson(values: JSONObject?, types: JSONObject?): List<RawPref> {
        if (values == null) return emptyList()
        val out = ArrayList<RawPref>()
        val keys = values.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            if (k.isEmpty() || k.startsWith("r.") || k.startsWith("a.") || isTransient(k) || values.isNull(k)) continue
            val declared = types?.let { BackupJson.strOrNull(it, k) }
            val type = declared ?: inferType(values.opt(k)) ?: continue
            val value: Any = when (type) {
                TYPE_STRING -> BackupJson.strOrNull(values, k)
                TYPE_BOOL -> (values.opt(k) as? Boolean)
                TYPE_INT -> (values.opt(k) as? Number)?.let { n ->
                    val l = n.toLong()
                    if (n.toDouble() == l.toDouble() && l in Int.MIN_VALUE..Int.MAX_VALUE) l.toInt() else null
                }
                TYPE_LONG -> (values.opt(k) as? Number)?.let { n ->
                    val d = n.toDouble()
                    if (d.isFinite() && d == Math.rint(d)) n.toLong() else null
                }
                TYPE_FLOAT -> (values.opt(k) as? Number)?.toFloat()?.takeIf { it.isFinite() }
                TYPE_SET -> (values.opt(k) as? JSONArray)?.let { BackupJson.strings(it).toSet() }
                else -> null
            } ?: continue
            out += RawPref(k, type, value)
        }
        return out.sortedBy { it.key }
    }

    private fun inferType(v: Any?): String? = when (v) {
        is String -> TYPE_STRING
        is Boolean -> TYPE_BOOL
        is JSONArray -> TYPE_SET
        is Int -> TYPE_INT
        is Long -> if (v in Int.MIN_VALUE..Int.MAX_VALUE) TYPE_INT else TYPE_LONG
        is Number -> {
            val d = v.toDouble()
            if (d.isFinite() && d == Math.rint(d) && d >= Int.MIN_VALUE && d <= Int.MAX_VALUE) TYPE_INT else TYPE_FLOAT
        }
        else -> null
    }

    /** `{reader, app, other, otherTypes}` for the backup file. */
    fun settingsToJson(reader: ReaderSettings, app: AppSettings, raw: Map<String, *>): JSONObject {
        val (values, types) = otherToJson(raw)
        return JSONObject()
            .put("reader", addUnmapped(readerToJson(reader), READER_PREFIX, raw))
            .put("app", addUnmapped(appToJson(app), APP_PREFIX, raw))
            .put("other", values)
            .put("otherTypes", types)
    }

    /**
     * Copies raw prefs with [prefix] that [target] (a typed mapping) lacks: settings fields newer than this
     * mapper still travel with the backup. Returns [target].
     */
    fun addUnmapped(target: JSONObject, prefix: String, raw: Map<String, *>): JSONObject {
        for ((k, v) in raw.entries.sortedBy { it.key }) {
            if (!k.startsWith(prefix) || k.length == prefix.length || target.has(k) || v == null) continue
            when (v) {
                is String, is Boolean, is Int, is Long -> target.put(k, v)
                is Float -> if (v.isFinite()) target.put(k, v.toDouble())
                is Set<*> -> target.put(k, JSONArray().also { a -> v.filterIsInstance<String>().sorted().forEach { a.put(it) } })
                else -> {}
            }
        }
        return target
    }

    /** Keys the typed mappings restore themselves. */
    private val MAPPED_KEYS: Set<String> by lazy {
        val out = HashSet<String>()
        for (o in listOf(readerToJson(ReaderSettings()), appToJson(AppSettings()))) {
            val it = o.keys()
            while (it.hasNext()) out += it.next()
        }
        out
    }

    /**
     * Entries of a backup's reader/app object ([prefix] keys) that the typed mappings don't restore, converted
     * to the type this device stores under the same key ([current] = the prefs after the typed restore).
     * Keys this device doesn't store are skipped (no settings field of this build reads them), as are values
     * that don't fit the stored type — a wrongly typed pref would crash `Settings` on load.
     */
    fun unmappedFromJson(o: JSONObject?, prefix: String, current: Map<String, *>): List<RawPref> {
        if (o == null) return emptyList()
        val out = ArrayList<RawPref>()
        val keys = o.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            if (!k.startsWith(prefix) || k in MAPPED_KEYS || o.isNull(k)) continue
            val like = current[k] ?: continue
            val v = o.opt(k)
            val p: RawPref? = when (like) {
                is Boolean -> (v as? Boolean)?.let { RawPref(k, TYPE_BOOL, it) }
                is Int -> integral(v)?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.let { RawPref(k, TYPE_INT, it.toInt()) }
                is Long -> integral(v)?.let { RawPref(k, TYPE_LONG, it) }
                is Float -> (v as? Number)?.toFloat()?.takeIf { it.isFinite() }?.let { RawPref(k, TYPE_FLOAT, it) }
                is String -> (v as? String)?.let { RawPref(k, TYPE_STRING, it) }
                is Set<*> -> (v as? JSONArray)?.let { RawPref(k, TYPE_SET, BackupJson.strings(it).toSet()) }
                else -> null
            }
            if (p != null) out += p
        }
        return out.sortedBy { it.key }
    }

    private fun integral(v: Any?): Long? {
        val n = v as? Number ?: return null
        val d = n.toDouble()
        return if (d.isFinite() && d == Math.rint(d)) n.toLong() else null
    }

    // ---- helpers ----

    private inline fun <reified E : Enum<E>> enumOf(name: String?, def: E): E {
        if (name == null) return def
        val n = name.trim()
        return enumValues<E>().firstOrNull { it.name == n } ?: enumValues<E>().firstOrNull { it.name.equals(n, true) } ?: def
    }

    /** "PREV,NEXT,…" (the prefs format) or a JSON array of names; exactly 9 valid actions required. */
    private fun tapZones(v: Any?): List<TapAction>? {
        val names: List<String> = when (v) {
            is String -> v.split(',').map { it.trim() }
            is JSONArray -> BackupJson.strings(v).map { it.trim() }
            else -> return null
        }
        val actions = names.mapNotNull { n -> TapAction.entries.firstOrNull { it.name == n } }
        return actions.takeIf { it.size == 9 && names.size == 9 }
    }

    private fun intSet(v: Any?): Set<Int>? {
        val arr = v as? JSONArray ?: return null
        val out = LinkedHashSet<Int>()
        for (i in 0 until arr.length()) {
            when (val x = arr.opt(i)) {
                is Number -> x.toDouble().takeIf { it.isFinite() && it == Math.rint(it) }?.let { out += it.toInt() }
                is String -> x.trim().toIntOrNull()?.let { out += it }
                else -> {}
            }
        }
        return out
    }

    private fun stringSet(v: Any?): Set<String>? {
        val arr = v as? JSONArray ?: return null
        return BackupJson.strings(arr).map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }
}
