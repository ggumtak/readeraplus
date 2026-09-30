package com.ggumtak.readeraplus.reader.extras

import com.ggumtak.readeraplus.format.DocPosition
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * Pure formatting / mapping helpers for the extras UI (unit-tested on the JVM).
 */

internal object Fmt {

    /** "532 B", "812 KB", "15.3 MB", "1.05 GB". */
    fun fileSize(bytes: Long): String {
        if (bytes < 1024) return "${bytes.coerceAtLeast(0)} B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var v = bytes / 1024.0
        var u = 0
        while (v >= 1024 && u < units.size - 1) {
            v /= 1024.0
            u++
        }
        val num = when {
            v < 10 -> String.format(Locale.US, "%.2f", v)
            v < 100 -> String.format(Locale.US, "%.1f", v)
            else -> v.roundToInt().toString()
        }
        return "$num ${units[u]}"
    }

    /** Reading time: "0분", "1분 미만", "45분", "3시간 12분". */
    fun duration(seconds: Long): String {
        if (seconds <= 0) return "0분"
        if (seconds < 60) return "1분 미만"
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        return if (h > 0) "${h}시간 ${m}분" else "${m}분"
    }

    /** "2026.09.29 14:05", or "-" for 0. */
    fun dateTime(ms: Long): String =
        if (ms <= 0) "-" else SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.KOREA).format(Date(ms))

    /** "yyyy.MM.dd" or "-". */
    fun date(ms: Long): String =
        if (ms <= 0) "-" else SimpleDateFormat("yyyy.MM.dd", Locale.KOREA).format(Date(ms))

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

    /** Indent in % of em → "없음", "1em", "1.25em". */
    fun em(pct: Int): String {
        if (pct <= 0) return "없음"
        val whole = pct / 100
        val frac = pct % 100
        return when {
            frac == 0 -> "${whole}em"
            frac % 10 == 0 -> "$whole.${frac / 10}em"
            else -> "$whole.${frac.toString().padStart(2, '0')}em"
        }
    }

    /** Letter spacing in per-mille of em → "기본", "+2%", "-1%", "+1.5%". */
    fun letterSpacing(pm: Int): String {
        if (pm == 0) return "기본"
        val sign = if (pm > 0) "+" else "-"
        val a = abs(pm)
        return if (a % 10 == 0) "$sign${a / 10}%" else "$sign${a / 10}.${a % 10}%"
    }

    /** "400 · 보통". */
    fun weight(w: Int): String {
        val name = when (w) {
            100 -> "가장 가늘게"
            200 -> "매우 가늘게"
            300 -> "가늘게"
            400 -> "보통"
            500 -> "중간"
            600 -> "약간 굵게"
            700 -> "굵게"
            800 -> "매우 굵게"
            900 -> "가장 굵게"
            else -> null
        }
        return if (name == null) w.toString() else "$w · $name"
    }

    /** TTS rate/pitch "1.0x". */
    fun rate(v: Float): String {
        val r = (v * 10f).roundToInt()
        return "${r / 10}.${r % 10}x"
    }

    fun minutes(min: Int): String = if (min <= 0) "끔" else "${min}분"

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

    /** "없음" or "N개 규칙" for TXT replace rules (comment lines start with #). */
    fun rulesLabel(rules: String): String {
        val n = rules.lineSequence().count { it.isNotBlank() && !it.trimStart().startsWith("#") }
        return if (n == 0) "없음" else "${n}개 규칙"
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

/** Parsing of ReaderHost.pageLabel strings ("12 / 3259", "~12 / ~3260") and position ↔ page/percent maths. */
internal object PageLabel {
    class Parsed(val page: Int, val total: Int, val estimated: Boolean)

    private val NUM = Regex("\\d[\\d,]*")

    fun parse(label: String?): Parsed {
        if (label.isNullOrBlank()) return Parsed(-1, -1, false)
        val nums = NUM.findAll(label).mapNotNull { it.value.replace(",", "").toIntOrNull() }.toList()
        val page = nums.firstOrNull() ?: -1
        val total = if (nums.size >= 2) nums.last() else -1
        return Parsed(page, total, label.contains('~'))
    }

    /** Just the page number part: "12 / 3259" → "12", "~12 / ~3260" → "~12". */
    fun pageOnly(label: String?): String {
        if (label == null) return ""
        val p = parse(label)
        if (p.page < 0) return label.trim()
        val est = label.substringBefore('/').contains('~')
        return if (est) "~${p.page}" else p.page.toString()
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
