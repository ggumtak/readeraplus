package com.ggumtak.readeraplus.reader.extras

import java.util.regex.Pattern

/**
 * Episode ("화") numbers of TOC titles (T1-1, T1-5). Pure and unit-tested; owner: EXTRAS_NAV.
 *
 * Users: BookSession.episodes (READER_B) builds one [Episodes] per session on Dispatchers.Default; the TOC dialog,
 * the go-to dialog's [화] segment (EXTRAS_NAV) and the footer's 회차 item / chrome buttons (READER_A) read it.
 */
object EpisodeNumbers {
    /*
     * The spec's rules, with two guards against garbage numbers: rules 1–3 never start inside a longer number
     * ("123456화" is not 23456화), and rule 4 skips a volume / part number ("2권", "1부": a heading above episodes,
     * which would otherwise read as a duplicate of 1화 / 2화).
     */
    private val RULES = arrayOf(
        Pattern.compile("(?:제\\s*)?(?<!\\d)(\\d{1,5})\\s*(?:화|회|話)"),
        Pattern.compile("(?i)(?<![a-z])(?:ep|episode|chapter|ch|#)\\s*\\.?\\s*(\\d{1,5})(?!\\d)"),
        Pattern.compile("(?<!\\d)(\\d{1,5})\\s*(?:장|편|章)"),
        // A leading decorative symbol is skipped ("◈ 002. [STAGE 0] …", "◆ 3").
        Pattern.compile("^\\s*(?:$DECOR\\s*)*[\\[<(【〈《]?\\s*(\\d{1,5})(?!\\d)(?!\\s*(?:권|부|卷))"),
    )

    /** Geometric shapes, arrows, misc symbols and dingbats, plus ※ • · : decoration in front of a heading. */
    private const val DECOR = "[\\u2190-\\u21FF\\u25A0-\\u25FF\\u2600-\\u27BF※•·]"

    private val SPECIAL = arrayOf("외전", "번외", "특별", "후기", "공지")

    /**
     * The episode number of a TOC title, or null. Rules, tried in order (first match wins, first group = number):
     * 1. `(?:제\s*)?(\d{1,5})\s*(?:화|회|話)`
     * 2. `(?i)(?:ep|episode|chapter|ch|#)\s*\.?\s*(\d{1,5})`
     * 3. `(\d{1,5})\s*(?:장|편|章)`
     * 4. `^\s*[\[<(【〈《]?\s*(\d{1,5})(?!\d)` (after any decorative symbols)
     * The number never starts or ends inside a longer one (rules 1–3), rule 2's keyword is not the tail of a word
     * ("deep 3"), and rule 4 skips volume / part numbers ("2권", "1부"). Patterns are compiled once; a title without
     * a digit costs one scan. Fast enough for 2,000 titles in ≈ 20 ms on the device. Any thread.
     */
    fun parse(title: String): Int? {
        if (title.none { it in '0'..'9' }) return null
        for (p in RULES) {
            val m = p.matcher(title)
            if (m.find()) return m.group(1).toIntOrNull()
        }
        return null
    }

    /** Titles excluded from [Episodes.gaps] / [Episodes.dupes]: containing 외전, 번외, 특별, 후기 or 공지. */
    fun isSpecial(title: String): Boolean = SPECIAL.any { title.contains(it) }
}

/**
 * Episode numbers of one book's TOC. Index i is `document.toc[i]` (the TOC dialog's rows); ChapterIndex skips entries
 * outside the book, so READER_A maps its chapter index to the TOC index before looking a number up. Immutable; built
 * on any thread, read on any thread.
 */
class Episodes private constructor(
    /** Parsed number per TOC entry; -1 when the title has none. */
    val numbers: IntArray,
    /** Entries excluded from the gap / duplicate checks ([EpisodeNumbers.isSpecial]). */
    private val special: BooleanArray,
) {
    /** Number of TOC entries. */
    val size: Int get() = numbers.size

    /** Entries with a number. */
    val parsedCount: Int

    /**
     * Largest number, or -1 when none parsed ("540화" in "123/540화"). Special entries count only when every
     * numbered entry is special ("2024년 공지" must not make a 540-episode book read "2024화").
     */
    val maxNumber: Int

    /** Numbered entries that take part in the gap / duplicate checks (not [special]). */
    private val mainCount: Int

    /** Smallest / largest number of the [mainCount] entries (-1 when there are none). */
    private val mainMin: Int
    private val mainMax: Int

    init {
        var parsed = 0
        var max = -1
        var main = 0
        var lo = Int.MAX_VALUE
        var hi = -1
        for (i in numbers.indices) {
            val v = numbers[i]
            if (v < 0) continue
            parsed++
            if (v > max) max = v
            if (special[i]) continue
            main++
            if (v < lo) lo = v
            if (v > hi) hi = v
        }
        parsedCount = parsed
        maxNumber = if (main > 0) hi else max
        mainCount = main
        mainMin = if (main > 0) lo else -1
        mainMax = if (main > 0) hi else -1
    }

    /**
     * Gaps and duplicates are worth showing: at least 70% of the entries parse and `max - min <= 5 × count` (odd TOCs
     * give garbage numbers; the "빠진 화 N개 · 중복 N개" line is then hidden). min, max and count are those of the
     * entries the checks look at (numbered and not special).
     */
    val confident: Boolean =
        size > 0 && mainCount > 0 && parsedCount * 10 >= size * 7 && (mainMax - mainMin).toLong() <= 5L * mainCount

    /** The go-to dialog's [화] segment is enabled: at least 50% of the titles parse and there are at least 2 entries. */
    val usableForJump: Boolean = size >= 2 && parsedCount * 2 >= size

    /**
     * TOC index for episode [n]: the first entry numbered [n], else the first entry with the smallest number above
     * [n] (the caller says "57화가 없어 58화로 이동했습니다" when `numbers[i] != n`), else -1. Regular entries win over
     * special ones (외전 3화 is not "3화"); only a TOC whose numbered entries are all special is searched as it is.
     */
    fun find(n: Int): Int {
        if (n < 0) return -1
        val skipSpecial = mainCount > 0
        var exact = -1
        var above = -1
        for (i in numbers.indices) {
            val v = numbers[i]
            if (v < 0 || (skipSpecial && special[i])) continue
            if (v == n) {
                exact = i
                break
            }
            if (v > n && (above < 0 || v < numbers[above])) above = i
        }
        return if (exact >= 0) exact else above
    }

    /** Numbers missing between the smallest and the largest (special entries ignored), ascending. */
    fun gaps(): List<Int> {
        if (mainCount == 0) return emptyList()
        val span = mainMax - mainMin + 1
        val seen = BooleanArray(span)
        for (i in numbers.indices) if (numbers[i] >= 0 && !special[i]) seen[numbers[i] - mainMin] = true
        val out = ArrayList<Int>()
        for (k in 0 until span) if (!seen[k]) out.add(mainMin + k)
        return out
    }

    /** Numbers used by more than one (non-special) entry → how many entries use it, ascending by number. */
    fun dupes(): Map<Int, Int> {
        if (mainCount < 2) return emptyMap()
        val sorted = IntArray(mainCount)
        var k = 0
        for (i in numbers.indices) if (numbers[i] >= 0 && !special[i]) sorted[k++] = numbers[i]
        sorted.sort()
        val out = LinkedHashMap<Int, Int>()
        var i = 0
        while (i < sorted.size) {
            var j = i + 1
            while (j < sorted.size && sorted[j] == sorted[i]) j++
            if (j - i > 1) out[sorted[i]] = j - i
            i = j
        }
        return out
    }

    /**
     * The episode the reader is in when TOC entry [index] is the current one: that entry's number when it has one
     * and is not special, else the nearest such number before it (a 작가의 말 after 123화 is still "123화"); -1 when
     * there is none. Used for "123/540화".
     */
    internal fun numberAt(index: Int): Int {
        var i = index.coerceAtMost(numbers.size - 1)
        while (i >= 0) {
            if (numbers[i] >= 0 && !special[i]) return numbers[i]
            i--
        }
        return -1
    }

    /** Smallest number of the regular entries (-1 when none): the go-to / 화 번호 hint "1–540". */
    internal val minNumber: Int get() = if (mainCount > 0) mainMin else numbers.filter { it >= 0 }.minOrNull() ?: -1

    companion object {
        /** Parses every title once ([EpisodeNumbers.parse]). Pure; call off the main thread for large TOCs. */
        fun of(titles: List<String>): Episodes {
            val n = titles.size
            val numbers = IntArray(n)
            val special = BooleanArray(n)
            for (i in 0 until n) {
                val t = titles[i]
                numbers[i] = EpisodeNumbers.parse(t) ?: -1
                special[i] = EpisodeNumbers.isSpecial(t)
            }
            return Episodes(numbers, special)
        }
    }
}
