package com.ggumtak.readeraplus.settings

/** Maps the ≤ R2 footer toggles to status slots (prefs and backups). Pure, JVM-tested. */
object StatusMigration {
    /** Present = the store is already slot-based. */
    const val MARKER_KEY = "r.footerLeft"
    val LEGACY_KEYS = listOf("r.showHeader", "r.showFooter", "r.footerPage", "r.footerChapterLeft", "r.footerEpisode",
        "r.footerTimeLeft", "r.footerPercent", "r.footerClock", "r.footerBattery")
    const val LEGACY_TIME_LEFT_OFF = 0; const val LEGACY_TIME_LEFT_EPISODE = 1; const val LEGACY_TIME_LEFT_BOOK = 2

    /** The legacy fields; null = key absent (or of the wrong type). */
    class Legacy(val showHeader: Boolean?, val showFooter: Boolean?, val page: Boolean?, val chapterLeft: Boolean?,
                 val episode: Boolean?, val timeLeft: Int?, val percent: Boolean?, val clock: Boolean?, val battery: Boolean?) {
        companion object {
            /** Each read in try/catch: a wrongly typed pref reads as null (never throws). */
            fun from(p: android.content.SharedPreferences): Legacy {
                fun b(k: String): Boolean? = try { if (p.contains(k)) p.getBoolean(k, false) else null } catch (_: ClassCastException) { null }
                fun i(k: String): Int? = try { if (p.contains(k)) p.getInt(k, 0) else null } catch (_: ClassCastException) { null }
                return Legacy(b("r.showHeader"), b("r.showFooter"), b("r.footerPage"), b("r.footerChapterLeft"),
                    b("r.footerEpisode"), i("r.footerTimeLeft"), b("r.footerPercent"), b("r.footerClock"), b("r.footerBattery"))
            }
            /** BackupJson-style tolerant reads (Boolean / Number only). */
            fun from(o: org.json.JSONObject): Legacy {
                fun b(k: String): Boolean? = o.opt(k) as? Boolean
                val time = (o.opt("r.footerTimeLeft") as? Number)?.toDouble()?.takeIf { it.isFinite() }?.toInt()
                return Legacy(b("r.showHeader"), b("r.showFooter"), b("r.footerPage"), b("r.footerChapterLeft"),
                    b("r.footerEpisode"), time, b("r.footerPercent"), b("r.footerClock"), b("r.footerBattery"))
            }
        }
    }
    class Slots(val headerLeft: StatusItem, val headerCenter: StatusItem, val headerRight: StatusItem,
                val footerLeft: StatusItem, val footerCenter: StatusItem, val footerRight: StatusItem) {
        fun applyTo(s: ReaderSettings): ReaderSettings = s.copy(headerLeft = headerLeft, headerCenter = headerCenter,
            headerRight = headerRight, footerLeft = footerLeft, footerCenter = footerCenter, footerRight = footerRight)
    }

    fun migrate(l: Legacy): Slots {
        val header = if (l.showHeader ?: true) StatusItem.CHAPTER else StatusItem.NONE
        val page = l.page ?: true; val chapterLeft = l.chapterLeft ?: false; val episode = l.episode ?: false
        val timeLeft = (l.timeLeft ?: 0).coerceIn(0, 2); val percent = l.percent ?: true; val clock = l.clock ?: true; val battery = l.battery ?: true
        // The untouched old default is indistinguishable from "chosen" (saveReader writes every key): the user asked
        // for "nothing chosen → no footer", so it becomes no footer.
        val untouched = page && !chapterLeft && !episode && timeLeft == 0 && percent && clock && battery
        if (!(l.showFooter ?: true) || untouched) return Slots(StatusItem.NONE, header, StatusItem.NONE, StatusItem.NONE, StatusItem.NONE, StatusItem.NONE)
        val q = ArrayList<StatusItem>(5)
        if (page) q += StatusItem.PAGE
        if (episode) q += StatusItem.EPISODE
        if (chapterLeft) q += StatusItem.CHAPTER_PAGES_LEFT
        if (timeLeft == LEGACY_TIME_LEFT_EPISODE) q += StatusItem.TIME_LEFT_EPISODE
        if (timeLeft == LEGACY_TIME_LEFT_BOOK) q += StatusItem.TIME_LEFT_BOOK
        if (percent) q += StatusItem.PERCENT
        val right = when { clock && battery -> StatusItem.CLOCK_BATTERY; clock -> StatusItem.CLOCK; battery -> StatusItem.BATTERY; else -> null }
        return Slots(StatusItem.NONE, header, StatusItem.NONE,
            q.getOrNull(0) ?: StatusItem.NONE, q.getOrNull(1) ?: StatusItem.NONE, right ?: q.getOrNull(2) ?: StatusItem.NONE)
    }
}

/**
 * MaruViewer's status line as the header (user, 2026-10-05: "위에 써있는 것도 보이지? 저런 식으로 최대한 카피해줘"):
 * prefs saved before it ([KEY] absent) load with the header slots of the [ReaderSettings] defaults; the footer, the
 * progress bar and the status size stay. Like the margin markers, loading never writes: every `Settings.saveReader`
 * stores [KEY], so the first save keeps the new header and any later choice. [KEY] is no reader setting and never
 * travels with a backup (`SettingsJson` drops it): a restore applies the backup's slots as they are.
 */
object MaruHeader {
    const val KEY = "status.maruHeader.v1"

    /** [s] with the default header slots. */
    fun applyTo(s: ReaderSettings): ReaderSettings {
        val d = ReaderSettings()
        return s.copy(headerLeft = d.headerLeft, headerCenter = d.headerCenter, headerRight = d.headerRight)
    }
}
