package com.ggumtak.readeraplus.settings

import android.content.Context
import android.content.SharedPreferences
import com.ggumtak.readeraplus.engine.Align
import com.ggumtak.readeraplus.engine.LineBreakMode

/**
 * Persistence for ReaderSettings / AppSettings in SharedPreferences. Values are cached in memory;
 * listeners are notified on the main thread caller's thread after save.
 */
object Settings {
    private const val PREFS = "settings"
    private lateinit var prefs: SharedPreferences

    @Volatile private var readerCache: ReaderSettings? = null
    @Volatile private var appCache: AppSettings? = null
    private val listeners = mutableListOf<() -> Unit>()

    fun init(context: Context) {
        if (!::prefs.isInitialized) prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    val reader: ReaderSettings
        get() = readerCache ?: loadReader().also { readerCache = it }

    val app: AppSettings
        get() = appCache ?: loadApp().also { appCache = it }

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
            putBoolean("r.showHeader", s.showHeader)
            putBoolean("r.showFooter", s.showFooter)
            putBoolean("r.footerPage", s.footerPage)
            putBoolean("r.footerChapterLeft", s.footerChapterLeft)
            putBoolean("r.footerPercent", s.footerPercent)
            putBoolean("r.footerClock", s.footerClock)
            putBoolean("r.footerBattery", s.footerBattery)
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
            putBoolean("a.pinChrome", s.pinChrome)
            putBoolean("a.swipeToTurn", s.swipeToTurn)
            putBoolean("a.verticalSwipe", s.verticalSwipe)
            putBoolean("a.volumeKeysTurn", s.volumeKeysTurn)
            putBoolean("a.invertVolumeKeys", s.invertVolumeKeys)
            putStringSet("a.nextPageKeys", s.nextPageKeys.map { it.toString() }.toSet())
            putStringSet("a.prevPageKeys", s.prevPageKeys.map { it.toString() }.toSet())
            putBoolean("a.longPressSelect", s.longPressSelect)
            putBoolean("a.bookmarkByTouch", s.bookmarkByTouch)
            putBoolean("a.invertByTouch", s.invertByTouch)
            putBoolean("a.fullscreen", s.fullscreen)
            putBoolean("a.keepScreenOn", s.keepScreenOn)
            putBoolean("a.brightnessSwipe", s.brightnessSwipe)
            putBoolean("a.openLastOnStart", s.openLastOnStart)
            putInt("a.einkRefreshEvery", s.einkRefreshEvery)
            putBoolean("a.einkRefreshOnChapter", s.einkRefreshOnChapter)
            putInt("a.autoTurnSeconds", s.autoTurnSeconds)
            putFloat("a.ttsRate", s.ttsRate)
            putFloat("a.ttsPitch", s.ttsPitch)
            putInt("a.ttsSleepMinutes", s.ttsSleepMinutes)
            putString("a.webSearchUrl", s.webSearchUrl)
            putString("a.librarySort", s.librarySort.name)
            putString("a.libraryListMode", s.libraryListMode.name)
            putStringSet("a.scanFolders", s.scanFolders)
            putStringSet("a.excludedFolders", s.excludedFolders)
            putInt("a.orientationLock", s.orientationLock)
            putFloat("a.brightness", s.brightness)
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
            marginLeftDp = p.getInt("r.marginLeftDp", d.marginLeftDp),
            marginRightDp = p.getInt("r.marginRightDp", d.marginRightDp),
            marginTopDp = p.getInt("r.marginTopDp", d.marginTopDp),
            marginBottomDp = p.getInt("r.marginBottomDp", d.marginBottomDp),
            pageMargins = p.getBoolean("r.pageMargins", d.pageMargins),
            invert = p.getBoolean("r.invert", d.invert),
            showHeader = p.getBoolean("r.showHeader", d.showHeader),
            showFooter = p.getBoolean("r.showFooter", d.showFooter),
            footerPage = p.getBoolean("r.footerPage", d.footerPage),
            footerChapterLeft = p.getBoolean("r.footerChapterLeft", d.footerChapterLeft),
            footerPercent = p.getBoolean("r.footerPercent", d.footerPercent),
            footerClock = p.getBoolean("r.footerClock", d.footerClock),
            footerBattery = p.getBoolean("r.footerBattery", d.footerBattery),
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
        )
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
            pinChrome = p.getBoolean("a.pinChrome", d.pinChrome),
            swipeToTurn = p.getBoolean("a.swipeToTurn", d.swipeToTurn),
            verticalSwipe = p.getBoolean("a.verticalSwipe", d.verticalSwipe),
            volumeKeysTurn = p.getBoolean("a.volumeKeysTurn", d.volumeKeysTurn),
            invertVolumeKeys = p.getBoolean("a.invertVolumeKeys", d.invertVolumeKeys),
            nextPageKeys = p.getStringSet("a.nextPageKeys", null)?.mapNotNull { it.toIntOrNull() }?.toSet() ?: d.nextPageKeys,
            prevPageKeys = p.getStringSet("a.prevPageKeys", null)?.mapNotNull { it.toIntOrNull() }?.toSet() ?: d.prevPageKeys,
            longPressSelect = p.getBoolean("a.longPressSelect", d.longPressSelect),
            bookmarkByTouch = p.getBoolean("a.bookmarkByTouch", d.bookmarkByTouch),
            invertByTouch = p.getBoolean("a.invertByTouch", d.invertByTouch),
            fullscreen = p.getBoolean("a.fullscreen", d.fullscreen),
            keepScreenOn = p.getBoolean("a.keepScreenOn", d.keepScreenOn),
            brightnessSwipe = p.getBoolean("a.brightnessSwipe", d.brightnessSwipe),
            openLastOnStart = p.getBoolean("a.openLastOnStart", d.openLastOnStart),
            einkRefreshEvery = p.getInt("a.einkRefreshEvery", d.einkRefreshEvery),
            einkRefreshOnChapter = p.getBoolean("a.einkRefreshOnChapter", d.einkRefreshOnChapter),
            autoTurnSeconds = p.getInt("a.autoTurnSeconds", d.autoTurnSeconds),
            ttsRate = p.getFloat("a.ttsRate", d.ttsRate),
            ttsPitch = p.getFloat("a.ttsPitch", d.ttsPitch),
            ttsSleepMinutes = p.getInt("a.ttsSleepMinutes", d.ttsSleepMinutes),
            webSearchUrl = p.getString("a.webSearchUrl", d.webSearchUrl) ?: d.webSearchUrl,
            librarySort = enumOr(p.getString("a.librarySort", null), d.librarySort),
            libraryListMode = enumOr(p.getString("a.libraryListMode", null), d.libraryListMode),
            scanFolders = p.getStringSet("a.scanFolders", d.scanFolders)?.toSet() ?: d.scanFolders,
            excludedFolders = p.getStringSet("a.excludedFolders", d.excludedFolders)?.toSet() ?: d.excludedFolders,
            orientationLock = p.getInt("a.orientationLock", d.orientationLock),
            brightness = p.getFloat("a.brightness", d.brightness),
        )
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        if (name == null) default else enumValues<E>().firstOrNull { it.name == name } ?: default
}
