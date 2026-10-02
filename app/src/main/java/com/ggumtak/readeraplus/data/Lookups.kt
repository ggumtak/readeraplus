package com.ggumtak.readeraplus.data


object Lookups {
    const val VIA_APP=0; const val VIA_WEB=1; const val VIA_WEB_FALLBACK=2; const val DEDUPE_MS=10*60_000L
    fun record(bookId: Long, word: String, section: Int, start: Int, end: Int, context: String,
               place: NotePlace?, via: Int, app: String, now: Long=System.currentTimeMillis()): Long = -1 // R3 stub (owner: DA-N)
    fun setNote(id: Long, note: String) {} // R3 stub (owner: DA-N)
    fun delete(ids: Collection<Long>) {} // R3 stub (owner: DA-N)
    fun clearAll() {} // R3 stub (owner: DA-N)
    fun count(): Int = 0 // R3 stub (owner: DA-N)
}
object LookupWords { fun key(word: String): String = TODO("owner: DA-N") }
