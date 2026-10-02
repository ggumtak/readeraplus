package com.ggumtak.readeraplus.data


object Notes {
    const val PAGE_ROWS = 50; const val BODY_CHARS = 600; const val NOTE_CHARS = 400
    fun counts(q: NotesQuery): NotesCounts = NotesCounts(0,0,0,0,0) // R3 stub (owner: DA-N)                                  // one UNION ALL … GROUP BY k statement
    fun styleCounts(q: NotesQuery): IntArray = IntArray(DataLimits.QUOTE_STYLE_MAX+1) // R3 stub (owner: DA-N)                                // index = style 0..QUOTE_STYLE_MAX (Q arm, filters except style)
    fun books(q: NotesQuery): List<NoteBook> = emptyList() // R3 stub (owner: DA-N)                                // sorted per q.order (natural title when !byBook)
    fun page(q: NotesQuery, index: Int, books: List<NoteBook>?): NotesPage = NotesPage(index,emptyList(),null,BooleanArray(0)) // R3 stub (owner: DA-N)  // books required when q.order.byBook
    fun refs(q: NotesQuery): LongArray = LongArray(0) // R3 stub (owner: DA-N)                                      // packed NoteRef of every row (select all, export)
    fun fullText(ref: NoteRef): Pair<String, String>? = null // R3 stub (owner: DA-N)                       // (body, note), untruncated
    fun drawerCounts(): IntArray = IntArray(2) // R3 stub (owner: DA-N)                                            // [quotes + bookmarks + reviews, lookups]
    fun countForBooks(bookIds: Collection<Long>): Int = 0 // R3 stub (owner: DA-N)                       // delete / empty-trash warnings
    fun export(refs: LongArray?, q: NotesQuery, format: NotesExport.Format, out: java.io.Writer, now: Long): Int = 0 // R3 stub (owner: DA-N)
}
class NotesPage(val index: Int, val rows: List<NoteRow>, val before: NoteRow?, val firstOfBook: BooleanArray)
internal object BookSpans { fun prefix(counts: IntArray): IntArray = TODO("owner: DA-N")
    fun locate(prefix: IntArray, row: Int): Long = TODO("owner: DA-N") }
