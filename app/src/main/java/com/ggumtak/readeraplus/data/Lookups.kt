package com.ggumtak.readeraplus.data

import android.util.Log
import com.ggumtak.readeraplus.settings.Settings
import java.text.Normalizer

/**
 * The 단어장 (N §5.4, hub.md §5): every dictionary / web-search pick from a selection. All calls are blocking (IO) and
 * thread-safe; every write bumps the hub's change counter ([Notes.generation]) after its commit.
 */
object Lookups {
    const val VIA_APP=0; const val VIA_WEB=1; const val VIA_WEB_FALLBACK=2; const val DEDUPE_MS=10*60_000L
    private const val TAG = "Lookups"
    private const val DELETE_CHUNK = 500

    /**
     * IO. Records one lookup. The same book + word key + section + start within [DEDUPE_MS] refreshes that row
     * (created_at, via, app, context) instead of inserting; one transaction. Returns the row id; −1 for a missing
     * book, a blank word, recording turned off ("찾아본 단어 기록", `AppSettings.recordLookups`) or a failure (never
     * throws). Lengths are capped with [DataLimits].
     */
    fun record(bookId: Long, word: String, section: Int, start: Int, end: Int, context: String,
               place: NotePlace?, via: Int, app: String, now: Long=System.currentTimeMillis()): Long {
        if (!recording()) return -1
        val w = MetaInfo.truncate(LibrarySql.nfc(word.trim()), DataLimits.WORD).trim()
        val key = LookupWords.key(w)
        if (key.isEmpty()) return -1
        val t0 = System.nanoTime()
        val s = minOf(start, end).coerceAtLeast(0)
        val e = maxOf(start, end).coerceAtLeast(0)
        val sec = section.coerceAtLeast(0)
        val ctx = MetaInfo.truncate(context.trim(), DataLimits.CONTEXT)
        val ap = MetaInfo.truncate(app.trim(), DataLimits.APP)
        val v = if (via in VIA_APP..VIA_WEB_FALLBACK) via else VIA_APP
        val p = place ?: NotePlace.UNKNOWN
        val id = try {
            Library.db().inTransaction {
                if (queryFirst(NotesSql.BOOK_EXISTS, args(bookId)) { 1 } == null) return@inTransaction -1L
                val old = queryFirst(NotesSql.LOOKUP_FIND_RECENT, args(bookId, key, sec, s, now - DEDUPE_MS)) { it.getLong(0) }
                if (old != null) {
                    exec(NotesSql.LOOKUP_REFRESH, now, v, ap, ctx, old)
                    old
                } else {
                    insertRow(NotesSql.LOOKUP_INSERT, bookId, w, key, sec, s, e, ctx,
                        MetaInfo.truncate(p.chapter, DataLimits.CHAPTER), p.frac.toDouble(), p.sig, v, ap, now)
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "record failed", t)
            -1L
        }
        if (id > 0) Notes.bumpLocal()
        perf("record", t0)
        return id
    }

    /** The "뜻 메모" of one lookup ("" clears it). */
    fun setNote(id: Long, note: String) {
        Library.db().exec(NotesSql.LOOKUP_SET_NOTE, MetaInfo.truncate(note.trim(), DataLimits.NOTE), id)
        Notes.bumpLocal()
    }

    /** Deletes lookups by id (one transaction). */
    fun delete(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        val list = ids.distinct()
        Library.db().inTransaction {
            var i = 0
            while (i < list.size) {
                val chunk = list.subList(i, minOf(list.size, i + DELETE_CHUNK))
                exec(NotesSql.lookupDelete(chunk.size), *chunk.toTypedArray())
                i += DELETE_CHUNK
            }
        }
        Notes.bumpLocal()
    }

    /** "단어장 비우기". */
    fun clearAll() {
        Library.db().execSQL(NotesSql.LOOKUP_CLEAR)
        Notes.bumpLocal()
    }

    fun count(): Int = Library.db().queryFirst(NotesSql.LOOKUP_COUNT, null) { it.getInt(0) } ?: 0

    /** The "찾아본 단어 기록" setting; on when settings are unavailable (tests, early calls). */
    private fun recording(): Boolean = try {
        Settings.app.recordLookups
    } catch (_: Throwable) {
        true
    }

    private fun perf(label: String, t0: Long) {
        if (!com.ggumtak.readeraplus.BuildConfig.DEBUG) return
        val ms = (System.nanoTime() - t0) / 1_000_000
        try {
            if (ms > 5) Log.w(Notes.TAG, "$label $ms ms (budget 5)") else Log.d(Notes.TAG, "$label $ms ms")
        } catch (_: Throwable) {
        }
    }
}

/**
 * Pure. The grouping key of a looked-up word ("N회", 같은 단어 한 번만): NFC, trim, strip surrounding quotes, brackets
 * and punctuation (“”‘’"'「」『』()[]《》〈〉.,!?…·~), collapse whitespace, ASCII-lowercase, ≤ 100 chars. Korean particles
 * are NOT stripped ("비명을" ≠ "비명"): there is no reliable rule.
 */
object LookupWords {
    const val MAX = 100
    private const val STRIP = "“”‘’\"'「」『』()[]《》〈〉.,!?…·~"

    fun key(word: String): String {
        var s = word
        if (!Normalizer.isNormalized(s, Normalizer.Form.NFC)) s = Normalizer.normalize(s, Normalizer.Form.NFC)
        // Collapse every whitespace run (incl. newlines and NBSP-like spaces) to one space.
        val sb = StringBuilder(s.length)
        var space = false
        for (ch in s) {
            if (Character.isWhitespace(ch) || Character.isSpaceChar(ch)) {
                space = true
            } else {
                if (space && sb.isNotEmpty()) sb.append(' ')
                space = false
                sb.append(ch)
            }
        }
        var a = 0
        var z = sb.length
        while (true) {
            val before = z - a
            while (a < z && (STRIP.indexOf(sb[a]) >= 0 || sb[a] == ' ')) a++
            while (z > a && (STRIP.indexOf(sb[z - 1]) >= 0 || sb[z - 1] == ' ')) z--
            if (z - a == before) break
        }
        val t = MetaInfo.asciiLower(sb.substring(a, z))
        return MetaInfo.truncate(t, MAX).trimEnd()
    }
}
