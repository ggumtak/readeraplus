package com.ggumtak.readeraplus.data


object NotesExport {
    enum class Format(val ext: String,val mime: String) { MARKDOWN("md","text/markdown"), TXT("txt","text/plain") }
    fun write(rows: Sequence<NoteRow>, books: List<NoteBook>, q: NotesQuery, format: Format, out: java.io.Writer, now: Long): Int = TODO("owner: DA-N")
}
