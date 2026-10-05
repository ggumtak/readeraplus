package com.ggumtak.readeraplus.reader.extras

import com.ggumtak.readeraplus.data.TxtOverride
import com.ggumtak.readeraplus.engine.PageBreakMode
import com.ggumtak.readeraplus.format.DocPosition
import com.ggumtak.readeraplus.reader.ReaderFormat
import com.ggumtak.readeraplus.render.StatusFit
import com.ggumtak.readeraplus.settings.ReaderSettings
import com.ggumtak.readeraplus.settings.StatusItem
import com.ggumtak.readeraplus.settings.StylePreset
import com.ggumtak.readeraplus.settings.UserStyle
import com.ggumtak.readeraplus.settings.UserStyles
import com.ggumtak.readeraplus.ui.kit.isNoSpace
import com.ggumtak.readeraplus.ui.kit.ownMessage
import com.ggumtak.readeraplus.ui.kit.userMessage
import com.ggumtak.readeraplus.ui.library.LibraryText
import com.ggumtak.readeraplus.ui.settings.SettingsFormat
import java.io.IOException
import java.util.regex.PatternSyntaxException
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * Pure formatting / mapping helpers for the extras UI (unit-tested on the JVM).
 */

internal object Fmt {

    /** "532B", "812KB", "3.4MB": the library card's wording ([LibraryText.formatSize]), one size wording in the app. */
    fun fileSize(bytes: Long): String = LibraryText.formatSize(bytes)

    /** Reading time: "0분", "1분 미만", "45분", "3시간 12분". */
    fun duration(seconds: Long): String {
        if (seconds <= 0) return "0분"
        if (seconds < 60) return "1분 미만"
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        return if (h > 0) "${h}시간 ${m}분" else "${m}분"
    }

    /** "9월 29일 14:05" (another year: "2025년 9월 29일 14:05"), or "-" for 0: the app's one wording ([SettingsFormat]). */
    fun dateTime(ms: Long): String = if (ms <= 0) "-" else SettingsFormat.dateTime(ms)

    /** A list's date, "9월 29일" (another year: "2025년 9월 29일"), or "-" for 0. */
    fun date(ms: Long): String = if (ms <= 0) "-" else SettingsFormat.date(ms)

    /** 0..1 → "34%", "0.4%", "100%". */
    fun percent(fraction: Float): String {
        val tenths = (fraction.coerceIn(0f, 1f) * 1000f).roundToInt()
        return if (tenths % 10 == 0) "${tenths / 10}%" else "${tenths / 10}.${tenths % 10}%"
    }

    /** "20" or "20.5". */
    fun number(v: Float): String {
        val r = (v * 10f).roundToInt()
        return if (r % 10 == 0) (r / 10).toString() else "${r / 10}.${abs(r % 10)}"
    }

    fun pct(v: Int): String = "$v%"

    /** Indent in % of a character's width (em) → "없음", "1자", "1.25자". */
    fun em(pct: Int): String {
        if (pct <= 0) return "없음"
        val whole = pct / 100
        val frac = pct % 100
        return when {
            frac == 0 -> "${whole}자"
            frac % 10 == 0 -> "$whole.${frac / 10}자"
            else -> "$whole.${frac.toString().padStart(2, '0')}자"
        }
    }

    /** Letter spacing in per-mille of em → "기본", "+2%", "−1%" (U+2212, as [signed]), "+1.5%". */
    fun letterSpacing(pm: Int): String {
        if (pm == 0) return "기본"
        val sign = if (pm > 0) "+" else "\u2212"
        val a = abs(pm)
        return if (a % 10 == 0) "$sign${a / 10}%" else "$sign${a / 10}.${a % 10}%"
    }

    /** Signed whole number for the margin steppers: "0", "+4", "−10" (U+2212, read as "minus" by TalkBack). */
    fun signed(v: Int): String = when {
        v > 0 -> "+$v"
        v < 0 -> "\u2212${-v}"
        else -> "0"
    }

    /** Font weight as a plain number ("500"): short and constant-width, so steppers never shift. */
    fun weight(w: Int): String = w.toString()

    /** TTS rate/pitch "1.0x". */
    fun rate(v: Float): String {
        val r = (v * 10f).roundToInt()
        return "${r / 10}.${r % 10}x"
    }

    /** Sleep timer: "끔", "30분", "1시간", "1시간 30분" (the TTS settings page's wording). */
    fun minutes(min: Int): String = when {
        min <= 0 -> "끔"
        min % 60 == 0 -> "${min / 60}시간"
        min > 60 -> "${min / 60}시간 ${min % 60}분"
        else -> "${min}분"
    }

    /** Snaps [v] to [step] and clamps into [min, max]. */
    fun stepInt(v: Int, step: Int, min: Int, max: Int): Int {
        val snapped = if (step > 0) ((v.toDouble() / step).roundToInt() * step) else v
        return snapped.coerceIn(min, max)
    }

    fun stepFloat(v: Float, step: Float, min: Float, max: Float): Float {
        val snapped = if (step > 0f) (v / step).roundToInt() * step else v
        // Kill float noise such as 20.499998.
        val clean = (snapped * 1000f).roundToInt() / 1000f
        return clean.coerceIn(min, max)
    }

    /**
     * The 바꾸기 규칙 row: "N개 켜짐" (the rules the parser applies, [RuleList.enabledCount]), or "없음 · 광고 문구 등
     * 지우기" saying what the rules are for.
     */
    fun rulesLabel(rules: String): String {
        val n = RuleList.enabledCount(rules)
        return if (n == 0) "없음 · 광고 문구 등 지우기" else "${n}개 켜짐"
    }

    /** Number of non-comment rule lines without "=>" or whose pattern does not compile. */
    fun invalidRuleCount(rules: String): Int = rules.lineSequence()
        .filter { it.isNotBlank() && !it.trimStart().startsWith("#") }
        .count { line ->
            val idx = line.indexOf("=>")
            idx <= 0 || line.substring(0, idx).isBlank() || runCatching { Regex(line.substring(0, idx).trim()) }.isFailure
        }

    /** Strips HTML tags and collapses whitespace (EPUB descriptions). */
    fun plainText(s: String): String =
        s.replace(Regex("<[^>]*>"), " ")
            .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'")
            .replace(Regex("[ \\t\\x0B\\f\\r]+"), " ")
            .replace(Regex(" *\\n *"), "\n")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
}

/**
 * Parsing of ReaderHost.pageLabel strings ("12 / 3259"; older builds marked estimates "~12 / ~3260") and
 * position ↔ page/percent maths. Nothing shown to the user carries a "~": estimated numbers are shown plain.
 */
internal object PageLabel {
    /** [estimated] = the label carried a "~" / "～" mark (labels without marks parse the same). */
    class Parsed(val page: Int, val total: Int, val estimated: Boolean)

    private val NUM = Regex("\\d[\\d,]*")

    private fun isTilde(c: Char): Boolean = c == '~' || c == '～' || c == '∼'

    fun parse(label: String?): Parsed {
        if (label.isNullOrBlank()) return Parsed(-1, -1, false)
        val nums = NUM.findAll(label).mapNotNull { it.value.replace(",", "").toIntOrNull() }.toList()
        val page = nums.firstOrNull() ?: -1
        val total = if (nums.size >= 2) nums.last() else -1
        return Parsed(page, total, label.any(::isTilde))
    }

    /**
     * [label] for display without estimate marks: "~12 / ~3260" → "12 / 3260". A mark between two digits (a range
     * such as "3~5") becomes "–". Labels without a mark are returned unchanged.
     */
    fun clean(label: String?): String {
        if (label.isNullOrEmpty()) return ""
        if (label.none(::isTilde)) return label
        val sb = StringBuilder(label.length)
        for (i in label.indices) {
            val c = label[i]
            if (!isTilde(c)) {
                sb.append(c)
                continue
            }
            val prev = label.getOrNull(i - 1)
            val next = label.getOrNull(i + 1)
            if (prev != null && next != null && prev.isDigit() && next.isDigit()) sb.append('–')
        }
        return sb.toString().trim()
    }

    /** Just the page number part, never with a "~": "12 / 3259" → "12", "~12 / ~3260" → "12". */
    fun pageOnly(label: String?): String {
        if (label == null) return ""
        val p = parse(label)
        return if (p.page < 0) clean(label).trim() else p.page.toString()
    }

    /** Position at [fraction] (0..1) of the book, by section char counts. */
    fun positionForFraction(chars: IntArray, fraction: Float): DocPosition {
        if (chars.isEmpty()) return DocPosition.START
        var total = 0L
        for (c in chars) total += c.coerceAtLeast(0)
        val f = fraction.coerceIn(0f, 1f)
        if (total <= 0L) return DocPosition((f * (chars.size - 1)).roundToInt(), 0)
        val target = (f.toDouble() * total).toLong()
        var acc = 0L
        for (i in chars.indices) {
            val c = chars[i].coerceAtLeast(0)
            if (target < acc + c || i == chars.lastIndex) {
                val off = (target - acc).coerceIn(0L, (c - 1).coerceAtLeast(0).toLong())
                return DocPosition(i, off.toInt())
            }
            acc += c
        }
        return DocPosition.START
    }

    /** Fraction (0..1) of [pos] by section char counts. */
    fun fractionOf(chars: IntArray, pos: DocPosition): Float {
        if (chars.isEmpty()) return 0f
        var total = 0L
        var before = 0L
        for (i in chars.indices) {
            val c = chars[i].coerceAtLeast(0)
            if (i < pos.section) before += c
            total += c
        }
        if (total <= 0L) return 0f
        val inSec = if (pos.section in chars.indices) pos.offset.coerceIn(0, chars[pos.section].coerceAtLeast(0)) else 0
        return ((before + inSec).toDouble() / total).toFloat().coerceIn(0f, 1f)
    }

    /**
     * Largest section index whose first page ([startPageOf], 1-based, non-decreasing) is <= [page].
     * Binary search: O(log n) calls.
     */
    inline fun sectionForPage(sectionCount: Int, page: Int, startPageOf: (Int) -> Int): Int {
        var lo = 0
        var hi = sectionCount - 1
        var ans = 0
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (startPageOf(mid) <= page) {
                ans = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return ans
    }

    /** Page [index] (0-based) of [section], which has [pagesInSection] pages (≥ 1). */
    class Target(val section: Int, val index: Int, val pagesInSection: Int)

    /**
     * Where global page [page] (1-based) of a [total]-page book is, from the sections' first pages ([startPageOf],
     * 1-based, non-decreasing). The index is clamped to the section.
     */
    inline fun pageTarget(sectionCount: Int, page: Int, total: Int, startPageOf: (Int) -> Int): Target {
        if (sectionCount <= 0) return Target(0, 0, 1)
        val sec = sectionForPage(sectionCount, page, startPageOf)
        val first = startPageOf(sec).coerceAtLeast(1)
        val next = if (sec + 1 < sectionCount) startPageOf(sec + 1) else total + 1
        val pagesInSec = (next - first).coerceAtLeast(1)
        return Target(sec, (page - first).coerceIn(0, pagesInSec - 1), pagesInSec)
    }

    /** Global page (1..[total]) for [percent] (0..100) of a book of [total] pages: 0 → 1, 100 → last. */
    fun pageForPercent(percent: Float, total: Int): Int {
        if (total <= 1) return 1
        val p = percent.coerceIn(0f, 100f)
        return (1 + (p / 100f * (total - 1)).roundToInt()).coerceIn(1, total)
    }

    /** Estimated offset of page [pageInSection] (0-based) in a section of [pages] pages and [chars] chars. */
    fun approxOffset(pageInSection: Int, pages: Int, chars: Int): Int {
        if (pages <= 1 || chars <= 0) return 0
        val k = pageInSection.coerceIn(0, pages - 1)
        return ((k.toLong() * chars) / pages).toInt().coerceIn(0, chars - 1)
    }
}

/** Texts of the 페이지 이동 dialog (no "~" marks: estimated numbers are shown plain). */
internal object GoToText {
    /** Percent exactly as the reader footer prints it for [fraction] (0..1). */
    fun percent(fraction: Float): String = "${ReaderFormat.percent(if (fraction.isNaN()) 0f else fraction)}%"

    /** "현재 12 / 3259쪽 · 34%" (+ a note while the page count is still running). */
    fun info(page: Int, total: Int, fraction: Float, pagesKnown: Boolean): String {
        val where = if (page > 0) "현재 $page${if (total > 0) " / $total" else ""}쪽 · " else "현재 "
        val note = if (!pagesKnown) "\n쪽수 계산 중 · %로 이동하세요" else ""
        return where + percent(fraction) + note
    }
}

/** Error texts the extras show (never a raw exception message: platform ones are English and may hold paths). */
internal object ErrorText {
    /** "글꼴을 추가할 수 없습니다: 글꼴 파일이 너무 큽니다" (just the first part when there is nothing useful to add). */
    fun fontImport(t: Throwable): String {
        val why = ownReason(t)
            ?: if (t is IOException || t is SecurityException || t is OutOfMemoryError || isNoSpace(t)) userMessage(t) else null
        return if (why != null) "글꼴을 추가할 수 없습니다: $why" else "글꼴을 추가할 수 없습니다"
    }

    /**
     * The app's own reason (FontManager rejects a file with a Korean sentence meant for users), or null for a
     * platform message, even one holding a Korean file name ([ownMessage]). A full disk or memory always reads as
     * such, whatever the message.
     */
    fun ownReason(t: Throwable): String? = ownMessage(t)

    /** "정규식이 올바르지 않습니다 (5번째 글자 근처)" for a pattern [Regex] rejected. */
    fun regex(t: Throwable): String {
        val p = t as? PatternSyntaxException
        val len = p?.pattern?.length ?: 0
        val at = p?.index ?: -1
        return "정규식이 올바르지 않습니다" + when {
            at < 0 || len == 0 -> ""
            at >= len -> " (끝 부분)"
            else -> " (${at + 1}번째 글자 근처)"
        }
    }
}

/** Status line of the search screen (refreshed at most every [SearchPanel.FLUSH_MS] while scanning). */
internal object SearchText {
    /** Share of sections scanned; at most 99 while the scan runs. */
    fun percent(scanned: Int, total: Int): Int =
        if (total <= 0) 0 else (scanned.toLong() * 100 / total).toInt().coerceIn(0, 99)

    /** "검색 중 34%" (+ " · 8개" once something is found), then "57개 결과", "결과 없음" or the capped count. */
    fun status(scanned: Int, total: Int, hits: Int, complete: Boolean, capped: Boolean, max: Int): String = when {
        !complete -> "검색 중 ${percent(scanned, total)}%" + if (hits > 0) " · ${hits}개" else ""
        capped -> "${max}개 이상 (앞 ${max}개만 표시)"
        hits == 0 -> "결과 없음"
        else -> "${hits}개 결과"
    }

    /** The empty list after a search: "‘등불’이 들어간 곳이 없습니다". */
    fun noHits(query: String): String = "‘$query’${Josa.iGa(query)} 들어간 곳이 없습니다"
}

/**
 * Size / placement maths of the quick reading options (⚙) and the drop-down lists they open (px in the reader
 * window). Sized for the ~6" 360×720 dp e-ink screen (U polish 7): centred, the whole width but 8 dp on each side
 * (≤ 400 dp), 8 dp under the status-bar inset, and at most 56% of the height, so the lower part of the page stays in
 * view as the preview while the whole popup ([QUICK_HEIGHT_DP] = 384 dp) never scrolls.
 */
internal object PopupGeometry {
    /** The quick options' rows under the top bar: 글자 크기, 굵기, 줄 간격, 문단 간격, 좌우 여백, 상하 여백, 글꼴. */
    const val QUICK_ROWS = 7
    /** The whole popup: the top bar ("전체 읽기 설정 ›" · 닫기) and [QUICK_ROWS] rows (48 + 7 × 48 = 384 dp). */
    const val QUICK_HEIGHT_DP = Compact.BAR_DP + QUICK_ROWS * Compact.ROW_DP
    /** Space left beside the popup, both sides together (dp): it is centred, 8 dp from each edge. */
    const val SIDE_GAP_DP = 16
    const val MAX_WIDTH_DP = 400
    const val HEIGHT_FRACTION = 0.56f
    /** Drop-down lists with many entries may take this much of the screen (e.g. 12 rows × 48 dp). */
    const val TALL_LIST_FRACTION = 0.8f
    /** Gap between the status-bar inset and the popup's top edge (dp). */
    const val TOP_GAP_DP = 8
    /** Smallest useful height (dp) when the space under the anchor is short (landscape / split screen). */
    const val MIN_HEIGHT_DP = 160
    /** Gap kept to the window edges (dp). */
    const val EDGE_DP = 8

    /** Top and maximum (or actual, for a list) height. */
    class Placement(val top: Int, val height: Int)

    /** Popup width: min([screenW] − 16 dp, 400 dp), never wider than the screen. */
    fun width(screenW: Int, density: Float): Int =
        minOf(screenW - (SIDE_GAP_DP * density).roundToInt(), (MAX_WIDTH_DP * density).roundToInt(), screenW).coerceAtLeast(1)

    /**
     * The settings popup [TOP_GAP_DP] under [topInset] (the status bar / cutout; the reader's bars are hidden while it
     * is open): its top and max height (56% of [screenH], and never past the bottom edge; moved up when less than
     * [MIN_HEIGHT_DP] is left).
     */
    fun settings(screenH: Int, topInset: Int, density: Float): Placement {
        val edge = (EDGE_DP * density).roundToInt()
        val cap = (screenH * HEIGHT_FRACTION).toInt().coerceAtLeast(1)
        val top = (topInset.coerceAtLeast(0) + (TOP_GAP_DP * density).roundToInt()).coerceIn(0, screenH)
        val room = screenH - top - edge
        val min = minOf(cap, (MIN_HEIGHT_DP * density).roundToInt())
        if (room >= min) return Placement(top, minOf(cap, room))
        val h = min.coerceAtMost((screenH - edge).coerceAtLeast(1))
        return Placement((screenH - edge - h).coerceAtLeast(0), h)
    }

    /**
     * A drop-down list [contentHeight] px tall for a row spanning [anchorTop]..[anchorBottom]: height capped at
     * [maxHeightFraction] of [screenH]; placed under the row when it fits, else above it, else as low as fits on screen.
     */
    fun dropdown(
        screenH: Int,
        anchorTop: Int,
        anchorBottom: Int,
        contentHeight: Int,
        density: Float,
        maxHeightFraction: Float = HEIGHT_FRACTION,
    ): Placement {
        val edge = (EDGE_DP * density).roundToInt()
        val h = minOf(contentHeight, maxHeight(screenH, maxHeightFraction), screenH - 2 * edge).coerceAtLeast(1)
        val top = when {
            anchorBottom + h <= screenH - edge -> anchorBottom
            anchorTop - h >= edge -> anchorTop - h
            else -> (screenH - edge - h).coerceAtLeast(0)
        }
        return Placement(top, h)
    }

    /** The list's height cap: [fraction] (clamped to 0.1..1) of [screenH]. */
    fun maxHeight(screenH: Int, fraction: Float): Int = (screenH * fraction.coerceIn(0.1f, 1f)).toInt().coerceAtLeast(1)

    /** Left edge of a [width]-px list whose right edge lines up with [anchorRight], kept inside [screenW]. */
    fun dropdownLeft(screenW: Int, anchorRight: Int, width: Int): Int =
        (anchorRight - width).coerceIn(0, (screenW - width).coerceAtLeast(0))
}

/**
 * "상태 표시" wording (U §5.5, A §2.7): slot wording, which rows show, the fit note and 외톨이 줄 방지's summary (읽기
 * 설정). A status change only repaints the page (the bands live in the margins), so none of this touches the layout.
 */
internal object StatusUi {
    const val FIT_NOTE = "상하 여백이 좁아 상태 표시줄이 가려집니다. 읽기 설정에서 ‘상하 여백’을 늘리세요."
    const val PROGRESS_SUMMARY = "화면 맨 아래 가는 선"
    /** Margin of a page whose "페이지 여백" switch is off (LayoutKeys.TINY_MARGIN_DP). */
    const val TINY_MARGIN_DP = 4

    /** "위" / "아래". */
    fun bandWord(band: Int): String = if (band == 0) "위" else "아래"

    /** "왼쪽" / "가운데" / "오른쪽". */
    fun posWord(pos: Int): String = when (pos) {
        0 -> "왼쪽"
        1 -> "가운데"
        else -> "오른쪽"
    }

    /** The slot button's content description: "아래 오른쪽: 시계" (CI reads it). */
    fun slotDescription(band: Int, pos: Int, item: StatusItem): String = "${bandWord(band)} ${posWord(pos)}: ${item.label}"

    /** "상태 글자 크기" shows only while some band has text. */
    fun showsSize(s: ReaderSettings): Boolean = s.hasHeader || s.hasFooterText

    /** The [FIT_NOTE] warning: a band with items whose margin is too small to draw it. Nothing is disabled. */
    fun showsFitNote(s: ReaderSettings): Boolean {
        val top = if (s.pageMargins) s.marginTopDp else TINY_MARGIN_DP
        val bottom = if (s.pageMargins) s.marginBottomDp else TINY_MARGIN_DP
        val headerHidden = s.hasHeader && !StatusFit.fitsDp(s.statusFontSizeSp, top, 0f)
        val footerHidden = s.hasFooterText &&
            !StatusFit.footerFitsDp(s.statusFontSizeSp, bottom, s.progressBar)
        return headerHidden || footerHidden
    }

    /** "외톨이 줄 방지" summary: in 문단 단위 only paragraphs taller than a page are split. */
    fun widowSummary(mode: PageBreakMode): String =
        if (mode == PageBreakMode.PARAGRAPH) "한 쪽보다 긴 문단에만" else "문단 첫 줄 · 끝 줄이 홀로 남지 않게"
}

/**
 * Which style the settings currently match (shown inverted in the popup's "스타일" row: the one-tap presets and the
 * "내 스타일" button), and the list edits of the saved styles (T1-8; [UserStyles.MAX] at most, names unique).
 */
internal object StyleChoice {
    /** Label of the saved-styles button when no saved style matches. */
    const val USER_LABEL = "내 스타일"

    /** The first preset whose typography and page colours equal [s] exactly, or null ("기본" or "직접 설정"). */
    fun selected(s: ReaderSettings): StylePreset? = StylePreset.entries.firstOrNull { it.matches(s) }

    /**
     * True when [s] has the defaults' typography and page colours (the fields a preset sets): "기본". The defaults
     * match no preset since 웹소설 became the 마루뷰어 page (2026-10-04); untouched settings are not "직접 설정".
     */
    fun isDefault(s: ReaderSettings): Boolean {
        val d = ReaderSettings()
        return s.fontId == d.fontId && s.fontWeight == d.fontWeight && s.lineHeightPct == d.lineHeightPct &&
            s.paragraphSpacingPct == d.paragraphSpacingPct && s.indentPct == d.indentPct &&
            s.letterSpacingPm == d.letterSpacingPm && s.align == d.align && s.lineBreak == d.lineBreak &&
            s.pageTheme == d.pageTheme
    }

    /**
     * [s] with the defaults' typography and page colours: exactly the fields a preset sets ([isDefault] is then true),
     * so a preset can always be undone. Font size, margins, status bar, 흑백 반전 and the TXT options stay.
     */
    fun applyDefault(s: ReaderSettings): ReaderSettings {
        val d = ReaderSettings()
        return s.copy(
            fontId = d.fontId, fontWeight = d.fontWeight, lineHeightPct = d.lineHeightPct,
            paragraphSpacingPct = d.paragraphSpacingPct, indentPct = d.indentPct, letterSpacingPm = d.letterSpacingPm,
            align = d.align, lineBreak = d.lineBreak, pageTheme = d.pageTheme,
        )
    }

    /** The first saved style [s] looks exactly like, or null. */
    fun selectedUser(s: ReaderSettings, styles: List<UserStyle>): UserStyle? = styles.firstOrNull { it.matches(s) }

    /** The saved-styles button text: the matching style's name, else "내 스타일". */
    fun userLabel(match: UserStyle?): String = match?.name ?: USER_LABEL

    /** Whether one more style can be saved under [name] (a new name needs a free slot; an existing one is replaced). */
    fun canSave(list: List<UserStyle>, name: String): Boolean = list.size < UserStyles.MAX || list.any { it.name == name }

    /** [list] with [style] in place of the style of the same name, or appended (the caller checked [canSave]). */
    fun put(list: List<UserStyle>, style: UserStyle): List<UserStyle> {
        val i = list.indexOfFirst { it.name == style.name }
        return if (i >= 0) list.toMutableList().also { it[i] = style } else (list + style).take(UserStyles.MAX)
    }

    /** [list] with style [old] renamed to [newName] (cleaned), or null when the name is empty or taken by another. */
    fun rename(list: List<UserStyle>, old: String, newName: String): List<UserStyle>? {
        val n = UserStyles.cleanName(newName)
        if (n.isEmpty() || (n != old && list.any { it.name == n })) return null
        return list.map { if (it.name == old) it.copy(name = n) else it }
    }

    fun remove(list: List<UserStyle>, name: String): List<UserStyle> = list.filter { it.name != name }
}

/**
 * The TXT options of one book (T1-9, 설정 → 이 책의 TXT 정리): what it shows are the book's effective values
 * (`Settings.reader.withTxt(override)`); what it stores is the override those values need. Pure, unit-tested.
 */
internal object TxtEdits {
    /** [s] with the TXT parse options of [src] (the seven fields a [TxtOverride] can hold); the rest of [s] kept. */
    fun withTxtFrom(s: ReaderSettings, src: ReaderSettings): ReaderSettings {
        if (sameTxt(s, src)) return s
        return s.copy(
            txtBlankLines = src.txtBlankLines,
            txtStripIndent = src.txtStripIndent,
            txtJoinWrappedLines = src.txtJoinWrappedLines,
            txtDetectChapters = src.txtDetectChapters,
            txtChapterRegex = src.txtChapterRegex,
            txtEmphasizeHeadings = src.txtEmphasizeHeadings,
            txtReplaceRules = src.txtReplaceRules,
        )
    }

    fun sameTxt(a: ReaderSettings, b: ReaderSettings): Boolean =
        a.txtBlankLines == b.txtBlankLines && a.txtStripIndent == b.txtStripIndent &&
            a.txtJoinWrappedLines == b.txtJoinWrappedLines && a.txtDetectChapters == b.txtDetectChapters &&
            a.txtChapterRegex == b.txtChapterRegex && a.txtEmphasizeHeadings == b.txtEmphasizeHeadings &&
            a.txtReplaceRules == b.txtReplaceRules

    /**
     * The override that makes [global] read like [eff]: only the options that differ (so the book keeps following the
     * defaults it agrees with); null when there is no difference ("이 책 설정 지우기" then has nothing to clear).
     */
    fun overrideFor(global: ReaderSettings, eff: ReaderSettings): TxtOverride? {
        val o = TxtOverride(
            blankLines = eff.txtBlankLines.takeIf { it != global.txtBlankLines },
            stripIndent = eff.txtStripIndent.takeIf { it != global.txtStripIndent },
            joinWrapped = eff.txtJoinWrappedLines.takeIf { it != global.txtJoinWrappedLines },
            detectChapters = eff.txtDetectChapters.takeIf { it != global.txtDetectChapters },
            chapterRegex = eff.txtChapterRegex.takeIf { it != global.txtChapterRegex },
            emphasizeHeadings = eff.txtEmphasizeHeadings.takeIf { it != global.txtEmphasizeHeadings },
            replaceRules = eff.txtReplaceRules.takeIf { it != global.txtReplaceRules },
        )
        return if (o.isEmpty) null else o
    }

    /** The rule text after appending [rule] to [rules] (a line of its own); [rules] unchanged when it already has it. */
    fun appendRule(rules: String, rule: String): String {
        val body = rules.trimEnd()
        if (body.lineSequence().any { it.trim() == rule.trim() }) return rules
        return if (body.isEmpty()) rule else "$body\n$rule"
    }
}

/** Korean particles after a word the app quotes (a selected phrase, a style name). Pure. */
internal object Josa {
    /** "이" after a final consonant, "가" after a vowel, "이(가)" when the word ends in something else. */
    fun iGa(word: String): String = pick(word, "이", "가", "이(가)")

    /** "을" / "를" / "을(를)". */
    fun eulReul(word: String): String = pick(word, "을", "를", "을(를)")

    private fun pick(word: String, consonant: String, vowel: String, unknown: String): String {
        val c = word.lastOrNull { it.isLetterOrDigit() } ?: return unknown
        return when {
            c in '가'..'힣' -> if ((c - '가') % 28 != 0) consonant else vowel
            // 영 일 삼 육 칠 팔 end in a consonant; 이 사 오 구 do not.
            c in '0'..'9' -> if (c in "013678") consonant else vowel
            else -> unknown
        }
    }
}

/**
 * The TTS voice list (A13): Korean voices first, then the book's language, with readable names such as
 * "한국어 · 목소리 2 (고음질, 오프라인)" instead of engine ids. Pure (android.speech.tts.Voice is mapped by the caller).
 */
internal object VoiceChoice {
    /** One engine voice: [lang] is the ISO 639 code, [language] its name in Korean ("한국어"). */
    class Info(
        val name: String,
        val lang: String,
        val language: String,
        val quality: Int,
        val network: Boolean,
        val notInstalled: Boolean,
    )

    /**
     * The voices to offer, in order, with their labels: Korean ("ko") first, then [bookLang]'s; every voice when
     * neither has any. Within a language by engine name, numbered from 1.
     */
    fun list(voices: List<Info>, bookLang: String?): List<Pair<Info, String>> {
        val book = bookLang?.lowercase()?.takeIf { it.isNotBlank() && it != "ko" }
        var pick = voices.filter { it.lang == "ko" || (book != null && it.lang == book) }
        if (pick.isEmpty()) pick = voices
        val sorted = pick.sortedWith(compareBy<Info>({ rank(it.lang, book) }, { it.language }, { it.lang }, { it.name }))
        val counts = HashMap<String, Int>()
        return sorted.map { v ->
            val n = (counts[v.lang] ?: 0) + 1
            counts[v.lang] = n
            v to label(v, n)
        }
    }

    fun label(v: Info, number: Int): String {
        val notes = listOfNotNull(quality(v.quality), if (v.network) "온라인" else "오프라인", if (v.notInstalled) "설치 필요" else null)
        return "${v.language.ifBlank { v.lang }} · 목소리 $number (${notes.joinToString(", ")})"
    }

    /** android.speech.tts.Voice.QUALITY_*: 400+ high, 300 normal, below low. */
    fun quality(q: Int): String = when {
        q >= 400 -> "고음질"
        q >= 300 -> "보통 음질"
        else -> "저음질"
    }

    private fun rank(lang: String, book: String?): Int = when (lang) {
        "ko" -> 0
        book -> 1
        else -> 2
    }
}

/**
 * The 멈춤 예약 choices (T1-11): 끔 · 15 · 30 · 45 · 60 · 90분 · 이 챕터 끝까지 · 다음 챕터 끝까지. Chapters
 * ([AppSettings.ttsSleepChapters]) win over minutes. Pure.
 */
internal object SleepChoice {
    class Option(val minutes: Int, val chapters: Int, val label: String)

    val OPTIONS: List<Option> = listOf(0, 15, 30, 45, 60, 90).map { Option(it, 0, Fmt.minutes(it)) } +
        Option(0, 1, "이 챕터 끝까지") + Option(0, 2, "다음 챕터 끝까지")

    /** Chooser index of the saved values; -1 when they are not among the options (an older build's 10 / 120분). */
    fun indexOf(minutes: Int, chapters: Int): Int =
        if (chapters > 0) OPTIONS.indexOfFirst { it.chapters == chapters } else OPTIONS.indexOfFirst { it.chapters == 0 && it.minutes == minutes }

    /** Row summary: "끔", "30분", "이 챕터 끝까지", "다음 챕터 끝까지" ("챕터 3개 끝까지" from an older build). */
    fun summary(minutes: Int, chapters: Int): String = when {
        chapters == 1 -> "이 챕터 끝까지"
        chapters == 2 -> "다음 챕터 끝까지"
        chapters > 2 -> "챕터 ${chapters}개 끝까지"
        else -> Fmt.minutes(minutes)
    }

    /**
     * The control bar's note while the timer runs: "3분 뒤 멈춤" ([remainingMs], rounded up), "이 챕터 끝나면 멈춤" /
     * "다음 챕터 끝나면 멈춤" / "챕터 3개 끝나면 멈춤" ([chaptersLeft] boundaries to go); "" when no timer runs.
     */
    fun barNote(remainingMs: Long, chaptersLeft: Int): String = when {
        chaptersLeft == 1 -> "이 챕터 끝나면 멈춤"
        chaptersLeft == 2 -> "다음 챕터 끝나면 멈춤"
        chaptersLeft > 2 -> "챕터 ${chaptersLeft}개 끝나면 멈춤"     // as 설정's summary (SettingsFormat.sleepSummary)
        remainingMs > 0 -> "${(remainingMs + 59_999L) / 60_000L}분 뒤 멈춤"
        else -> ""
    }
}

/**
 * Finds where the page's content box sits inside the page view, using only ReaderHost.hitTest (view coords) and
 * the typesetter's content-box geometry. The contract exposes no content origin, so we observe where hit-test
 * results change from one line / char to the next and compare with the known content coordinates.
 */
internal object OriginCalibrator {
    class LineBox(val start: Int, val end: Int, val top: Float, val bottom: Float)

    /**
     * Vertical offset (view y − content y), or NaN. Scans y at view x [probeX]; a transition between two lines
     * that touch (a.bottom == b.top) gives the exact offset; otherwise the gap midpoint is assumed.
     */
    inline fun calibrateY(lines: List<LineBox>, viewHeight: Int, probeX: Float, hit: (Float, Float) -> Int): Float {
        if (lines.size < 2) return Float.NaN
        val exact = ArrayList<Float>()
        val mid = ArrayList<Float>()
        var prev = -1
        var y = 0
        while (y < viewHeight) {
            val r = hit(probeX, y.toFloat())
            if (r >= 0) {
                val li = lineOf(lines, r)
                if (li >= 0) {
                    if (prev >= 0 && li == prev + 1) {
                        val a = lines[prev]
                        val b = lines[li]
                        if (abs(b.top - a.bottom) < 0.5f) exact.add(y - b.top) else mid.add(y - (a.bottom + b.top) / 2f)
                    }
                    prev = li
                }
            }
            y++
        }
        return median(exact).takeIf { !it.isNaN() } ?: median(mid)
    }

    /**
     * Horizontal offset (view x − content x), or NaN. Scans x at view y [probeY] across one line whose chars have
     * content-x lefts [lefts] (index = offset − [lineStart]) and [advances] (index = offset).
     */
    inline fun calibrateX(
        lineStart: Int,
        lineEnd: Int,
        lefts: FloatArray,
        advances: FloatArray,
        viewWidth: Int,
        probeY: Float,
        hit: (Float, Float) -> Int,
    ): Float {
        val exact = ArrayList<Float>()
        val mid = ArrayList<Float>()
        var prev = -1
        var x = 0
        while (x < viewWidth) {
            val r = hit(x.toFloat(), probeY)
            if (r in lineStart until lineEnd) {
                if (prev >= 0 && r > prev) {
                    val right = lefts[prev - lineStart] + advances[prev]
                    val left = lefts[r - lineStart]
                    if (abs(left - right) < 0.5f && advances[r] > 0f) exact.add(x - left) else mid.add(x - (left + right) / 2f)
                }
                prev = r
            }
            x++
        }
        return median(exact).takeIf { !it.isNaN() } ?: median(mid)
    }

    /** Index of the line containing offset [r]; -1 when ambiguous (r is both a line end and the next line start). */
    fun lineOf(lines: List<LineBox>, r: Int): Int {
        var lo = 0
        var hi = lines.size - 1
        var ans = -1
        while (lo <= hi) {
            val m = (lo + hi) ushr 1
            if (lines[m].start <= r) {
                ans = m
                lo = m + 1
            } else {
                hi = m - 1
            }
        }
        if (ans > 0) {
            val a = lines[ans - 1]
            val b = lines[ans]
            if (b.start == r && a.end == r && a.end > a.start) return -1
        }
        return ans
    }

    fun median(v: List<Float>): Float {
        if (v.isEmpty()) return Float.NaN
        val s = v.sorted()
        return s[s.size / 2]
    }
}
