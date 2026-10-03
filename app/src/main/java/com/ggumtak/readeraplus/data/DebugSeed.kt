package com.ggumtak.readeraplus.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import java.io.Writer
import java.util.Random

/**
 * Debug-only notes fixture and benchmark (N §14, §5.8). `App` registers it only when `BuildConfig.DEBUG`; it is never
 * in a release build.
 *
 * ```
 * adb shell am broadcast -a com.ggumtak.readeraplus.DEBUG_SEED_NOTES --ei n 10000   # seed, then time the hub
 * adb shell am broadcast -a com.ggumtak.readeraplus.DEBUG_SEED_NOTES --ez clear true # remove the fixture
 * adb shell am broadcast -a com.ggumtak.readeraplus.DEBUG_SEED_NOTES --ez bench true # time only
 * adb logcat -s RANotes
 * ```
 *
 * The fixture lives in its own books (path `/debug-seed/…`, trashed and missing like a vanished file: they show as
 * "(파일 없음)" and never touch the user's own notes): [n] notes over 500 books, 60 % quotes, 20 % bookmarks, 15 %
 * lookups and up to one review per book; quote lengths 70 % ≤ 200 chars, 25 % up to 1,500, 5 % ≥ 1,500; a quarter
 * with a memo; colours 0..5; times over the last two years.
 */
object DebugSeed {
    const val ACTION = "com.ggumtak.readeraplus.DEBUG_SEED_NOTES"
    private const val PREFIX = "/debug-seed/"
    private const val BOOKS = 500

    fun register(context: Context) {
        val app = context.applicationContext ?: context
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val pending = goAsync()
                val n = intent.getIntExtra("n", 10_000).coerceIn(0, 200_000)
                val clear = intent.getBooleanExtra("clear", false)
                val benchOnly = intent.getBooleanExtra("bench", false)
                val t = Thread({
                    try {
                        Library.init(app)
                        when {
                            clear -> clear()
                            benchOnly -> bench()
                            else -> { seed(n); bench() }
                        }
                    } catch (t: Throwable) {
                        Log.w(Notes.TAG, "debug seed failed", t)
                    } finally {
                        pending.finish()
                    }
                }, "debug-seed-notes")
                t.isDaemon = true
                t.start()
            }
        }
        val filter = IntentFilter(ACTION)
        try {
            // adb's broadcast comes from the shell: the receiver must be exported (debug builds only).
            if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            else app.registerReceiver(receiver, filter)
        } catch (t: Throwable) {
            Log.w(Notes.TAG, "debug seed receiver not registered", t)
        }
    }

    private const val INSERT_BOOK = "INSERT INTO books(path, file_name, folder, title, author, format, size, mtime, " +
        "added_at, last_read_at, progress, trashed, missing_at, review, review_at) VALUES (?, ?, ?, ?, ?, 'TXT', ?, ?, ?, ?, ?, 1, ?, ?, ?)"
    private const val INSERT_QUOTE = "INSERT INTO quotes(book_id, section, start_offset, end_offset, quote_text, note, " +
        "created_at, style, chapter, frac, sig) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, '')"
    private const val INSERT_MARK = "INSERT INTO bookmarks(book_id, section, char_offset, snippet, note, created_at, " +
        "chapter, frac, sig) VALUES (?, ?, ?, ?, ?, ?, ?, ?, '')"
    private const val SEED_BOOKS = "SELECT id FROM books WHERE path LIKE '$PREFIX%'"

    private val WORDS = arrayOf("새벽", "공기는", "생각보다", "차가웠고", "그는", "천천히", "고개를", "들었다", "비명", "검은",
        "하늘", "아래", "기억", "과거로", "돌아가", "약속", "마지막", "문장", "바람이", "불었다", "summer", "Light", "회귀")

    private fun text(r: Random, chars: Int): String {
        val sb = StringBuilder(chars + 16)
        while (sb.length < chars) {
            sb.append(WORDS[r.nextInt(WORDS.size)])
            sb.append(if (r.nextInt(12) == 0) ".\n" else " ")
        }
        return sb.substring(0, chars).trim()
    }

    private fun quoteLength(r: Random): Int {
        val p = r.nextInt(100)
        return when {
            p < 70 -> 20 + r.nextInt(181)
            p < 95 -> 201 + r.nextInt(1299)
            else -> 1500 + r.nextInt(4500)
        }
    }

    private fun seed(n: Int) {
        val t0 = System.nanoTime()
        val r = Random(20261003L)
        val now = System.currentTimeMillis()
        val span = 2L * 365 * 24 * 3600 * 1000
        val db = Library.db()
        db.inTransaction {
            val books = LongArray(BOOKS)
            val base = queryFirst("SELECT COALESCE(MAX(id), 0) FROM books", null) { it.getLong(0) } ?: 0L
            for (i in 0 until BOOKS) {
                val name = "시드 ${base + i + 1}권.txt"
                val read = now - (r.nextDouble() * span).toLong()
                books[i] = insertRow(INSERT_BOOK, PREFIX + name, name, PREFIX.trimEnd('/'), "시드 소설 ${base + i + 1}권", "작가 ${i % 37}",
                    1000L + i, now, now, read, r.nextFloat(), now, "", 0L)
            }
            var reviews = 0
            for (j in 0 until n) {
                val book = books[r.nextInt(BOOKS)]
                val time = now - (r.nextDouble() * span).toLong()
                val sec = r.nextInt(40)
                val off = r.nextInt(30_000)
                val p = r.nextInt(100)
                val chapter = "${sec + 1}화"
                val frac = r.nextFloat().toDouble()
                when {
                    p < 60 -> {
                        val len = quoteLength(r)
                        insertRow(INSERT_QUOTE, book, sec, off, off + len, text(r, len),
                            if (r.nextInt(4) == 0) text(r, 10 + r.nextInt(120)) else "", time, r.nextInt(6), chapter, frac)
                    }
                    p < 80 -> insertRow(INSERT_MARK, book, sec, off, text(r, 60 + r.nextInt(100)),
                        if (r.nextInt(4) == 0) text(r, 10 + r.nextInt(80)) else "", time, chapter, frac)
                    p < 95 || reviews >= BOOKS -> {
                        val w = WORDS[r.nextInt(WORDS.size)]
                        insertRow(NotesSql.LOOKUP_INSERT, book, w, LookupWords.key(w), sec, off, off + w.length,
                            text(r, 40 + r.nextInt(150)), chapter, frac, "", r.nextInt(3), "파파고", time)
                    }
                    else -> {
                        val b = books[reviews++]
                        exec("UPDATE books SET review = ?, review_at = ? WHERE id = ?", text(r, 50 + r.nextInt(800)), time, b)
                    }
                }
            }
        }
        Notes.bumpLocal()
        Log.i(Notes.TAG, "seeded $n notes over $BOOKS books in ${(System.nanoTime() - t0) / 1_000_000} ms")
    }

    private fun clear() {
        val db = Library.db()
        val ids = db.queryList(SEED_BOOKS, null) { it.getLong(0) }
        db.inTransaction {
            for (id in ids) {
                exec("DELETE FROM quotes WHERE book_id = ?", id)
                exec("DELETE FROM bookmarks WHERE book_id = ?", id)
                exec("DELETE FROM lookups WHERE book_id = ?", id)
                exec("DELETE FROM books WHERE id = ?", id)
            }
        }
        Notes.bumpLocal()
        Log.i(Notes.TAG, "cleared ${ids.size} seed books")
    }

    /** Times every N §5.8 call once cold (caches dropped by a generation bump) and logs the totals under `RANotes`. */
    private fun bench() {
        fun <T> time(label: String, budget: Long, block: () -> T): T {
            val t0 = System.nanoTime()
            val v = block()
            val ms = (System.nanoTime() - t0) / 1_000_000
            Log.i(Notes.TAG, "bench $label: $ms ms (budget $budget)${if (ms > budget) " OVER" else ""}")
            return v
        }
        Notes.bumpLocal()
        time("drawerCounts", 5) { Notes.drawerCounts() }
        for (tab in NotesTab.entries) {
            val q = NotesQuery(tab = tab)
            time("counts ${tab.name}", 15) { Notes.counts(q) }
            time("page 0 ${tab.name}", 40) { Notes.page(q, 0, null) }
        }
        val all = NotesQuery()
        time("page 20 ALL", 40) { Notes.page(all, 20, null) }
        time("page 150 ALL", 80) { Notes.page(all, 150, null) }
        val byBook = NotesQuery(order = NotesOrder.BOOK_TITLE)
        val books = time("books", 50) { Notes.books(byBook) }
        time("book page 0", 40) { Notes.page(byBook, 0, books) }
        time("book page 100", 40) { Notes.page(byBook, 100, books) }
        Notes.bumpLocal()
        val search = NotesQuery(text = "새벽 공기")
        time("search page 0", 150) { Notes.page(search, 0, null) }
        time("search counts (cached scan)", 15) { Notes.counts(search) }
        time("search tab QUOTES", 40) { Notes.page(search.copy(tab = NotesTab.QUOTES), 0, null) }
        val ids = Library.db().queryList(SEED_BOOKS + " LIMIT 1", null) { it.getLong(0) }
        if (ids.isNotEmpty()) {
            time("record", 5) { Lookups.record(ids[0], "벤치", 0, 0, 2, "벤치 문장", null, Lookups.VIA_APP, "bench") }
        }
        val sink = object : Writer() {
            var chars = 0L
            override fun write(cbuf: CharArray, off: Int, len: Int) { chars += len }
            override fun flush() {}
            override fun close() {}
        }
        time("export all", 3000) { Notes.export(null, all, NotesExport.Format.MARKDOWN, sink, System.currentTimeMillis()) }
        Log.i(Notes.TAG, "bench export ${sink.chars} chars")
    }
}
