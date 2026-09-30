package com.ggumtak.readeraplus.data

import com.ggumtak.readeraplus.format.BookFormat
import java.io.File
import java.util.Locale

/**
 * "다음 권 읽기" (T1-2): the file that continues a book split into parts ("소설A 1-100화.txt" → "소설A 101-200화.txt").
 *
 * Owner: DATA. User: READER_A's end panel, which calls [find] on IO only when the panel shows (never on the open
 * path) and always shows the found file's name so the user decides. Opening it: `Library.addOrUpdateFile(next)` on
 * IO, then the reader's normal open-by-id path.
 *
 * The pure helpers ([seriesKey], [sameSeries], [pickNext]) are pinned by NextPartTest.
 */
object NextPart {
    /** Folder entries tried when a picked name turns out not to be a file (e.g. a folder named "x.txt"). */
    private const val FOLDER_TRIES = 3

    private val BRACKETS = Regex("\\[[^\\]]*]|\\([^)]*\\)|（[^）]*）|【[^】]*】|〈[^〉]*〉")
    private const val DASH = "[-~～〜–]"
    /** "1-100화", "101 ~ 200", "제1-50화". */
    private val RANGE = Regex("(제\\s*)?\\d+\\s*$DASH\\s*\\d+\\s*화?")
    /** "3권", "2부", "제12화", "4 편", "7장". */
    private val NUMBERED = Regex("(제\\s*)?\\d+\\s*(권|부|화|편|장)")
    /** "완", "완결", "외전 포함" as words of their own ("완벽한" keeps its 완). */
    private val FINISHED = Regex("(?<![\\p{L}\\p{N}])(완결|완|외전\\s*포함)(?![\\p{L}\\p{N}])")
    /** A trailing 상 / 중 / 하 (권) volume word: "소설 상.txt", "소설 하권.txt" (NaturalOrder sorts 상 < 중 < 하). */
    private val HALVES = Regex("(?<![\\p{L}\\p{N}])(상|중|하)권?$")
    private val TRAILING_DIGITS = Regex("\\d+$")
    /** Separators left dangling at either end once the numbers are gone ("소설A_01" → "소설A_"). */
    private val EDGE_SEPARATORS = Regex("^[\\s_.,·:~-]+|[\\s_.,·:~-]+$")
    private val SPACES = Regex("[\\s_]+")

    /**
     * The next part of [book], or null. Blocking (IO): at most one DB query and one directory listing.
     * 1. A book with `series` and `seriesIndex` set (EPUB metadata, or the user's edit): the library book of the same
     *    series with the smallest `seriesIndex` greater than this one's (not trashed; its file must exist).
     * 2. Otherwise the parent folder: files of the same format family (TXT with TXT, EPUB with EPUB), sorted with
     *    [NaturalOrder]; the first one after [book]'s file whose [seriesKey] equals this file's ([sameSeries]).
     */
    fun find(book: Book): File? {
        seriesNext(book)?.let { return it }
        val file = File(book.path)
        val dir = file.parentFile ?: return null
        val names = try {
            dir.list()
        } catch (_: Throwable) {
            null
        } ?: return null
        val pool = names.toMutableList()
        repeat(FOLDER_TRIES) {
            val name = pickNext(file.name, pool) ?: return null
            val f = File(dir, name)
            if (f.isFile) return f
            pool.remove(name)
        }
        return null
    }

    /**
     * Step 1 of [find]: a book with a series and an index (EPUB metadata, or the user's edit of any book) continues
     * with the smallest greater index of the same series in the library (not trashed, file present).
     */
    private fun seriesNext(book: Book): File? {
        val series = book.series?.takeIf { it.isNotBlank() } ?: return null
        val index = book.seriesIndex?.takeIf { it.isFinite() } ?: return null
        // The stored REAL is the Float widened to a Double: bind exactly that, so equal indexes compare equal.
        val rows = Library.db().queryList(
            LibrarySql.SELECT_SERIES_NEXT,
            arrayOf(series, index.toDouble().toString(), book.id.toString()),
            BookRows::book,
        )
        for (b in rows) {
            val f = File(b.path)
            if (f.isFile) return f
        }
        return null
    }

    /**
     * The name reduced to what the parts of one work share (pure), in this order:
     * 1. strip the extension;
     * 2. strip bracketed segments `[..]`, `(..)`, `【..】`, `〈..〉`;
     * 3. strip `\d+\s*[-~]\s*\d+\s*화?`, `\d+\s*(권|부|화|편|장)`, `(완|완결|외전\s*포함)` and trailing digits;
     * 4. collapse whitespace and lowercase.
     * "소설A 1-100화.txt" and "소설A 101-200화 (완).txt" → "소설a"; "[작가] 소설A 3.txt" → "소설a".
     * Also: `제` before a number ("제3권"), full-width `（..）`, a last 상 / 중 / 하(권), `_` as a space and separators
     * left dangling at the ends ("소설A_01" → "소설a"). When step 2 leaves nothing ("[소설A] 1.txt"), the brackets stay.
     */
    fun seriesKey(fileName: String): String {
        val base = fileName.substringAfterLast('/')
        val dot = base.lastIndexOf('.')
        val name = if (dot > 0 && base.length - dot <= 6) base.substring(0, dot) else base
        // A name that is nothing but brackets ("[소설A] 1.txt"): its brackets are the title.
        return reduce(BRACKETS.replace(name, " ")).ifEmpty { reduce(name) }
    }

    /** Steps 3 and 4 of [seriesKey]. */
    private fun reduce(name: String): String {
        var s = RANGE.replace(name, " ")
        s = NUMBERED.replace(s, " ")
        s = FINISHED.replace(s, " ")
        s = EDGE_SEPARATORS.replace(s, "")
        s = HALVES.replace(s, "")
        s = EDGE_SEPARATORS.replace(s, "")
        s = TRAILING_DIGITS.replace(s, "")
        s = EDGE_SEPARATORS.replace(s, "")
        return SPACES.replace(s, " ").trim().lowercase(Locale.ROOT)
    }

    /**
     * Same work: equal keys, or one key a prefix of the other when the shorter is at least 4 chars (pure). [a] and
     * [b] are [seriesKey] results: "소설B 1-50화.txt" (key "소설b") is not a part of "소설A 1-100화.txt" ("소설a").
     */
    fun sameSeries(a: String, b: String): Boolean {
        if (a == b) return true
        val (shorter, longer) = if (a.length <= b.length) a to b else b to a
        return shorter.length >= MIN_PREFIX && longer.startsWith(shorter)
    }

    private const val MIN_PREFIX = 4

    /**
     * The folder step of [find] without IO (pure, unit-tested): among [names] (file names of one folder), the first
     * one after [current] in [NaturalOrder] with the same format family and [sameSeries] key; null when none.
     */
    fun pickNext(current: String, names: List<String>): String? {
        val family = BookFormat.forFile(current) ?: return null
        var key: String? = null
        var best: String? = null
        for (n in names) {
            if (n.isEmpty() || n[0] == '.' || n == current) continue
            // Cheap tests first: order, then format; the key (a few regexes) only for a would-be better pick.
            if (NaturalOrder.compare(n, current) <= 0) continue
            if (best != null && NaturalOrder.compare(n, best) >= 0) continue
            if (BookFormat.forFile(n) != family) continue
            val k = key ?: seriesKey(current).also { key = it }
            if (sameSeries(k, seriesKey(n))) best = n
        }
        return best
    }
}
