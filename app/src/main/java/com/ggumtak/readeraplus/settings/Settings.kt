package com.ggumtak.readeraplus.settings

import android.content.Context
import android.content.SharedPreferences
import com.ggumtak.readeraplus.engine.PageBreakMode
import com.ggumtak.readeraplus.engine.Align
import com.ggumtak.readeraplus.engine.LineBreakMode

/**
 * Persistence for ReaderSettings / AppSettings in SharedPreferences. Values are cached in memory;
 * listeners are notified on the main thread caller's thread after save.
 *
 * Loading is tolerant: a key that is missing (a field newer than the saved prefs) or holds an unknown enum name
 * takes the field's default. The first [app] read happens on the main thread during a cold start, so [loadApp]
 * only reads plain values; the saved styles' JSON is parsed separately, on the first [userStyles] access.
 */
object Settings {
    private const val PREFS = "settings"

    /** Prefs key of the saved styles (a JSON array, see [UserStyles]); backed up as a typed list, not a raw pref. */
    const val KEY_USER_STYLES = "a.userStyles"

    /** Where TTS voices were stored before [AppSettings.ttsVoice] existed (read as the fallback). */
    private const val LEGACY_TTS_VOICE = "extras.ttsVoice"

    private lateinit var prefs: SharedPreferences

    @Volatile private var readerCache: ReaderSettings? = null
    @Volatile private var appCache: AppSettings? = null
    @Volatile private var userStylesCache: List<UserStyle>? = null
    private val listeners = mutableListOf<() -> Unit>()

    fun init(context: Context) {
        if (!::prefs.isInitialized) prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    /** Test hook: [p] becomes the store and every cached value is dropped. */
    internal fun initForTest(p: SharedPreferences) {
        prefs = p
        readerCache = null
        appCache = null
        userStylesCache = null
    }

    val reader: ReaderSettings
        get() = readerCache ?: loadReader().also { readerCache = it }

    val app: AppSettings
        get() = appCache ?: loadApp().also { appCache = it }

    /**
     * The saved styles ("내 스타일"), at most [UserStyles.MAX], oldest first. Parsed from JSON on the first access
     * (the reading-settings popup's style row), never as part of [app]: keep it off cold-start paths.
     */
    val userStyles: List<UserStyle>
        get() = userStylesCache ?: UserStyles.parse(prefs.getString(KEY_USER_STYLES, null)).also { userStylesCache = it }

    /**
     * Replaces the saved styles (only the first [UserStyles.MAX] are kept). Listeners are not notified: saving a style
     * changes no applied setting.
     */
    fun saveUserStyles(list: List<UserStyle>) {
        val kept = list.take(UserStyles.MAX)
        userStylesCache = kept
        prefs.edit().apply {
            if (kept.isEmpty()) remove(KEY_USER_STYLES) else putString(KEY_USER_STYLES, UserStyles.toJson(kept).toString())
        }.apply()
    }

    fun saveReader(s: ReaderSettings) {
        readerCache = s
        prefs.edit().apply {
            putString("r.fontId", s.fontId)
            putFloat("r.fontSizeSp", s.fontSizeSp)
            putInt("r.fontWeight", s.fontWeight)
            putInt("r.lineHeightPct", s.lineHeightPct)
            putInt("r.paragraphSpacingPct", s.paragraphSpacingPct)
            putInt("r.indentPct", s.indentPct)
            putInt("r.letterSpacingPm", s.letterSpacingPm)
            putString("r.align", s.align.name)
            putString("r.lineBreak", s.lineBreak.name)
            putInt("r.marginLeftDp", s.marginLeftDp)
            putInt("r.marginRightDp", s.marginRightDp)
            putInt("r.marginTopDp", s.marginTopDp)
            putInt("r.marginBottomDp", s.marginBottomDp)
            putBoolean("r.pageMargins", s.pageMargins)
            putBoolean("r.invert", s.invert)
            putString("r.pageTheme", s.pageTheme.name)
            putString("r.headerLeft", s.headerLeft.name)
            putString("r.headerCenter", s.headerCenter.name)
            putString("r.headerRight", s.headerRight.name)
            putString("r.footerLeft", s.footerLeft.name)
            putString("r.footerCenter", s.footerCenter.name)
            putString("r.footerRight", s.footerRight.name)
            putBoolean("r.progressBar", s.progressBar)
            putString("r.pageBreak", s.pageBreak.name)
            putInt(SideMargin.KEY, SideMargin.ZERO_DP)
            putInt(VerticalMargin.KEY, VerticalMargin.BANDS)
            putBoolean(MaruHeader.KEY, true)
            putBoolean(MaruSize.KEY, true)
            for (key in StatusMigration.LEGACY_KEYS) remove(key)
            putFloat("r.statusFontSizeSp", s.statusFontSizeSp)
            putBoolean("r.widowOrphanControl", s.widowOrphanControl)
            putInt("r.txtBlankLines", s.txtBlankLines)
            putBoolean("r.txtStripIndent", s.txtStripIndent)
            putInt("r.txtJoinWrappedLines", s.txtJoinWrappedLines)
            putBoolean("r.txtDetectChapters", s.txtDetectChapters)
            putString("r.txtChapterRegex", s.txtChapterRegex)
            putBoolean("r.txtEmphasizeHeadings", s.txtEmphasizeHeadings)
            putString("r.txtReplaceRules", s.txtReplaceRules)
            putBoolean("r.epubPublisherStyles", s.epubPublisherStyles)
        }.apply()
        notifyListeners()
    }

    fun saveApp(s: AppSettings) {
        appCache = s
        prefs.edit().apply {
            putString("a.tapZoneMode", s.tapZoneMode.name)
            putString("a.customTapZones", s.customTapZones.joinToString(",") { it.name })
            putBoolean("a.invertTaps", s.invertTaps)
            remove("a.pinChrome")
            remove("reader.brightnessCollapsed")
            putString("a.readMode", s.readMode.name)
            putString("a.scrollStyle", s.scrollStyle.name)
            putBoolean("a.autoBackup", s.autoBackup)
            putBoolean("a.brightnessDevice", s.brightnessDevice)
            putBoolean("a.brightnessRestore", s.brightnessRestore)
            putInt("a.highlightLook", s.highlightLook)
            putInt("a.listPaging", s.listPaging)
            putBoolean("a.recordLookups", s.recordLookups)
            putBoolean("a.swipeToTurn", s.swipeToTurn)
            putBoolean("a.verticalSwipe", s.verticalSwipe)
            putBoolean("a.volumeKeysTurn", s.volumeKeysTurn)
            putBoolean("a.invertVolumeKeys", s.invertVolumeKeys)
            putStringSet("a.nextPageKeys", s.nextPageKeys.map { it.toString() }.toSet())
            putStringSet("a.prevPageKeys", s.prevPageKeys.map { it.toString() }.toSet())
            putString("a.keyBindings", encodeKeyBindings(s.keyBindings))
            putString("a.keyHold", s.keyHold.name)
            putBoolean("a.longPressSelect", s.longPressSelect)
            putInt("a.longPressMs", s.longPressMs)
            putBoolean("a.bookmarkByTouch", s.bookmarkByTouch)
            putBoolean("a.invertByTouch", s.invertByTouch)
            putBoolean("a.fullscreen", s.fullscreen)
            putBoolean("a.keepScreenOn", s.keepScreenOn)
            putBoolean("a.brightnessSwipe", s.brightnessSwipe)
            putBoolean("a.openLastOnStart", s.openLastOnStart)
            putBoolean("a.autoMarkFinished", s.autoMarkFinished)
            putInt("a.einkRefreshEvery", s.einkRefreshEvery)
            putInt("a.einkMode", s.einkMode)
            putBoolean("a.einkRefreshOnChapter", s.einkRefreshOnChapter)
            putInt("a.einkRefreshMethod", s.einkRefreshMethod)
            putInt("a.einkFlashMs", s.einkFlashMs)
            putInt("a.einkRefreshEveryNight", s.einkRefreshEveryNight)
            putBoolean("a.einkFlashImages", s.einkFlashImages)
            putInt("a.autoTurnSeconds", s.autoTurnSeconds)
            putFloat("a.ttsRate", s.ttsRate)
            putFloat("a.ttsPitch", s.ttsPitch)
            putInt("a.ttsSleepMinutes", s.ttsSleepMinutes)
            putInt("a.ttsSleepChapters", s.ttsSleepChapters)
            putBoolean("a.ttsHighlight", s.ttsHighlight)
            putString("a.ttsVoice", s.ttsVoice)
            putString("a.webSearchUrl", s.webSearchUrl)
            putString("a.librarySort", s.librarySort.name)
            putString("a.libraryListMode", s.libraryListMode.name)
            putStringSet("a.scanFolders", s.scanFolders)
            putStringSet("a.excludedFolders", s.excludedFolders)
            putInt("a.orientationLock", s.orientationLock)
            putFloat("a.brightness", s.brightness)
            putInt(BrightnessEncoding.KEY_VERSION, BrightnessEncoding.VERSION)
        }.apply()
        notifyListeners()
    }

    /** Registers a change listener; returns an unregister function. */
    fun addListener(l: () -> Unit): () -> Unit {
        synchronized(listeners) { listeners += l }
        return { synchronized(listeners) { listeners -= l } }
    }

    private fun notifyListeners() {
        val copy = synchronized(listeners) { listeners.toList() }
        copy.forEach { it() }
    }

    /** Raw prefs for small unrelated values (e.g. last opened book id). */
    fun raw(): SharedPreferences = prefs

    private fun loadReader(): ReaderSettings {
        val d = ReaderSettings()
        val p = prefs
        val mig = if (p.contains(StatusMigration.MARKER_KEY)) null else StatusMigration.migrate(StatusMigration.Legacy.from(p))
        val sideBase = if (p.contains(SideMargin.KEY)) p.getInt(SideMargin.KEY, 0) else null
        val sideLegacy = SideMargin.isLegacyDefault(sideBase, p.getInt("r.marginLeftDp", d.marginLeftDp), p.getInt("r.marginRightDp", d.marginRightDp))
        val verticalBase = if (p.contains(VerticalMargin.KEY)) p.getInt(VerticalMargin.KEY, 0) else null
        val verticalLegacy = VerticalMargin.isLegacyDefault(verticalBase != null, p.getInt("r.marginTopDp", d.marginTopDp), p.getInt("r.marginBottomDp", d.marginBottomDp))
        return ReaderSettings(
            fontId = p.getString("r.fontId", d.fontId) ?: d.fontId,
            fontSizeSp = p.getFloat("r.fontSizeSp", d.fontSizeSp),
            fontWeight = p.getInt("r.fontWeight", d.fontWeight),
            lineHeightPct = p.getInt("r.lineHeightPct", d.lineHeightPct),
            paragraphSpacingPct = p.getInt("r.paragraphSpacingPct", d.paragraphSpacingPct),
            indentPct = p.getInt("r.indentPct", d.indentPct),
            letterSpacingPm = p.getInt("r.letterSpacingPm", d.letterSpacingPm),
            align = enumOr(p.getString("r.align", null), d.align),
            lineBreak = enumOr(p.getString("r.lineBreak", null), d.lineBreak),
            marginLeftDp = if (sideLegacy) SideMargin.ZERO_DP else p.getInt("r.marginLeftDp", d.marginLeftDp),
            marginRightDp = if (sideLegacy) SideMargin.ZERO_DP else p.getInt("r.marginRightDp", d.marginRightDp),
            marginTopDp = if (verticalLegacy) VerticalMargin.EDGE_DP else p.getInt("r.marginTopDp", d.marginTopDp),
            marginBottomDp = if (verticalLegacy) VerticalMargin.EDGE_DP else p.getInt("r.marginBottomDp", d.marginBottomDp),
            pageMargins = p.getBoolean("r.pageMargins", d.pageMargins),
            invert = p.getBoolean("r.invert", d.invert),
            pageTheme = enumOr(p.getString("r.pageTheme", null), d.pageTheme),
            headerLeft = mig?.headerLeft ?: enumOr(p.getString("r.headerLeft", null), d.headerLeft),
            headerCenter = mig?.headerCenter ?: enumOr(p.getString("r.headerCenter", null), d.headerCenter),
            headerRight = mig?.headerRight ?: enumOr(p.getString("r.headerRight", null), d.headerRight),
            footerLeft = mig?.footerLeft ?: enumOr(p.getString("r.footerLeft", null), d.footerLeft),
            footerCenter = mig?.footerCenter ?: enumOr(p.getString("r.footerCenter", null), d.footerCenter),
            footerRight = mig?.footerRight ?: enumOr(p.getString("r.footerRight", null), d.footerRight),
            progressBar = p.getBoolean("r.progressBar", d.progressBar),
            pageBreak = enumOr(p.getString("r.pageBreak", null), d.pageBreak),
            statusFontSizeSp = p.getFloat("r.statusFontSizeSp", d.statusFontSizeSp),
            widowOrphanControl = p.getBoolean("r.widowOrphanControl", d.widowOrphanControl),
            txtBlankLines = p.getInt("r.txtBlankLines", d.txtBlankLines),
            txtStripIndent = p.getBoolean("r.txtStripIndent", d.txtStripIndent),
            txtJoinWrappedLines = p.getInt("r.txtJoinWrappedLines", d.txtJoinWrappedLines),
            txtDetectChapters = p.getBoolean("r.txtDetectChapters", d.txtDetectChapters),
            txtChapterRegex = p.getString("r.txtChapterRegex", d.txtChapterRegex) ?: "",
            txtEmphasizeHeadings = p.getBoolean("r.txtEmphasizeHeadings", d.txtEmphasizeHeadings),
            txtReplaceRules = p.getString("r.txtReplaceRules", d.txtReplaceRules) ?: "",
            epubPublisherStyles = p.getBoolean("r.epubPublisherStyles", d.epubPublisherStyles),
        ).let { if (p.contains(MaruHeader.KEY)) it else MaruHeader.applyTo(it) } // saved before MaruViewer's header
            .let { before ->
                // MaruViewer's status size for an untouched 11 sp (not while 여백 사용 is off: its minimal margin could not
                // give the bands' growth back, MaruSize.applyTo). Then top/bottom saved from the screen's edge are
                // counted from the bands the page now has (MaruViewer's header and size included), and those counted from
                // the 11 sp bands lose what the 13 sp bands add: either way the text box stays. Read only: the next
                // saveReader writes VerticalMargin.BANDS and MaruSize.KEY.
                val sized = if (p.contains(MaruSize.KEY)) before else MaruSize.applyTo(before)
                when {
                    VerticalMargin.countsFromEdge(verticalBase) ->
                        VerticalMargin.fromEdge(sized, p.contains("r.marginTopDp"), p.contains("r.marginBottomDp"))
                    sized !== before -> MaruSize.keepBox(before, sized)
                    else -> sized
                }
            }
            .let {
                // A bottom margin saved before its "0" moved from 22 to 10 dp comes 12 dp closer to the progress line.
                if (p.contains("r.marginBottomDp") && VerticalMargin.needsBottomShift(verticalBase))
                    it.copy(marginBottomDp = VerticalMargin.shiftBottom(it.marginBottomDp))
                else it
            }
    }

    private fun loadApp(): AppSettings {
        val d = AppSettings()
        val p = prefs
        val zones = p.getString("a.customTapZones", null)
            ?.split(',')?.mapNotNull { n -> TapAction.entries.firstOrNull { it.name == n } }
            ?.takeIf { it.size == 9 } ?: d.customTapZones
        return AppSettings(
            tapZoneMode = enumOr(p.getString("a.tapZoneMode", null), d.tapZoneMode),
            customTapZones = zones,
            invertTaps = p.getBoolean("a.invertTaps", d.invertTaps),
            readMode = enumOr(p.getString("a.readMode", null), d.readMode),
            scrollStyle = enumOr(p.getString("a.scrollStyle", null), d.scrollStyle),
            autoBackup = p.getBoolean("a.autoBackup", d.autoBackup),
            brightnessDevice = p.getBoolean("a.brightnessDevice", d.brightnessDevice),
            brightnessRestore = p.getBoolean("a.brightnessRestore", d.brightnessRestore),
            highlightLook = p.getInt("a.highlightLook", d.highlightLook).takeIf { it in 0..2 } ?: 0,
            listPaging = p.getInt("a.listPaging", d.listPaging).takeIf { it in 0..2 } ?: 0,
            recordLookups = p.getBoolean("a.recordLookups", d.recordLookups),
            swipeToTurn = p.getBoolean("a.swipeToTurn", d.swipeToTurn),
            verticalSwipe = p.getBoolean("a.verticalSwipe", d.verticalSwipe),
            volumeKeysTurn = p.getBoolean("a.volumeKeysTurn", d.volumeKeysTurn),
            invertVolumeKeys = p.getBoolean("a.invertVolumeKeys", d.invertVolumeKeys),
            nextPageKeys = p.getStringSet("a.nextPageKeys", null)?.mapNotNull { it.toIntOrNull() }?.toSet() ?: d.nextPageKeys,
            prevPageKeys = p.getStringSet("a.prevPageKeys", null)?.mapNotNull { it.toIntOrNull() }?.toSet() ?: d.prevPageKeys,
            keyBindings = decodeKeyBindings(p.getString("a.keyBindings", null)),
            keyHold = enumOr(p.getString("a.keyHold", null), d.keyHold),
            longPressSelect = p.getBoolean("a.longPressSelect", d.longPressSelect),
            longPressMs = p.getInt("a.longPressMs", d.longPressMs),
            bookmarkByTouch = p.getBoolean("a.bookmarkByTouch", d.bookmarkByTouch),
            invertByTouch = p.getBoolean("a.invertByTouch", d.invertByTouch),
            fullscreen = p.getBoolean("a.fullscreen", d.fullscreen),
            keepScreenOn = p.getBoolean("a.keepScreenOn", d.keepScreenOn),
            brightnessSwipe = p.getBoolean("a.brightnessSwipe", d.brightnessSwipe),
            openLastOnStart = p.getBoolean("a.openLastOnStart", d.openLastOnStart),
            autoMarkFinished = p.getBoolean("a.autoMarkFinished", d.autoMarkFinished),
            einkRefreshEvery = p.getInt("a.einkRefreshEvery", d.einkRefreshEvery),
            einkMode = p.getInt("a.einkMode", d.einkMode),
            einkRefreshOnChapter = p.getBoolean("a.einkRefreshOnChapter", d.einkRefreshOnChapter),
            einkRefreshMethod = p.getInt("a.einkRefreshMethod", d.einkRefreshMethod),
            einkFlashMs = p.getInt("a.einkFlashMs", d.einkFlashMs),
            einkRefreshEveryNight = p.getInt("a.einkRefreshEveryNight", d.einkRefreshEveryNight),
            einkFlashImages = p.getBoolean("a.einkFlashImages", d.einkFlashImages),
            autoTurnSeconds = p.getInt("a.autoTurnSeconds", d.autoTurnSeconds),
            ttsRate = p.getFloat("a.ttsRate", d.ttsRate),
            ttsPitch = p.getFloat("a.ttsPitch", d.ttsPitch),
            ttsSleepMinutes = p.getInt("a.ttsSleepMinutes", d.ttsSleepMinutes),
            ttsSleepChapters = p.getInt("a.ttsSleepChapters", d.ttsSleepChapters),
            ttsHighlight = p.getBoolean("a.ttsHighlight", d.ttsHighlight),
            ttsVoice = p.getString("a.ttsVoice", null) ?: p.getString(LEGACY_TTS_VOICE, null) ?: d.ttsVoice,
            webSearchUrl = p.getString("a.webSearchUrl", d.webSearchUrl) ?: d.webSearchUrl,
            librarySort = enumOr(p.getString("a.librarySort", null), d.librarySort),
            libraryListMode = enumOr(p.getString("a.libraryListMode", null), d.libraryListMode),
            scanFolders = p.getStringSet("a.scanFolders", d.scanFolders)?.toSet() ?: d.scanFolders,
            excludedFolders = p.getStringSet("a.excludedFolders", d.excludedFolders)?.toSet() ?: d.excludedFolders,
            orientationLock = p.getInt("a.orientationLock", d.orientationLock),
            brightness = BrightnessEncoding.fromStored(p.getFloat("a.brightness", d.brightness),
                p.contains(BrightnessEncoding.KEY_VERSION), p.getBoolean("a.brightnessDevice", d.brightnessDevice)),
        )
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        if (name == null) default else enumValues<E>().firstOrNull { it.name == name } ?: default

    /** [AppSettings.keyBindings] as stored: "24:NEXT,25:PREV" (sorted by key code; "" when empty). */
    fun encodeKeyBindings(map: Map<Int, TapAction>): String =
        map.entries.sortedBy { it.key }.joinToString(",") { "${it.key}:${it.value.name}" }

    /**
     * Inverse of [encodeKeyBindings]. Tolerant: blank → empty; entries with a bad key code (not a number, negative) or
     * an unknown action name are skipped (a newer build's action read by an older one); for a repeated key code the
     * last entry wins. Key code 0 (KEYCODE_UNKNOWN: every key without a code of its own) is a real binding.
     */
    fun decodeKeyBindings(text: String?): Map<Int, TapAction> {
        if (text.isNullOrBlank()) return emptyMap()
        val out = LinkedHashMap<Int, TapAction>()
        for (part in text.split(',')) {
            val colon = part.indexOf(':')
            if (colon <= 0) continue
            val code = part.substring(0, colon).trim().toIntOrNull() ?: continue
            if (code < 0) continue
            val name = part.substring(colon + 1).trim()
            val action = TapAction.entries.firstOrNull { it.name == name } ?: continue
            out[code] = action
        }
        return out
    }
}
