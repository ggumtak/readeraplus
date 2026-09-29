package com.ggumtak.readeraplus.format.txt

import com.ggumtak.readeraplus.format.BookDocument
import com.ggumtak.readeraplus.format.DocMeta
import com.ggumtak.readeraplus.format.ParseOptions
import java.io.File

/** CONTRACT STUB — implemented by the TXT module owner (see docs/ARCHITECTURE.md "format/txt"). */
object TxtDocuments {
    fun open(file: File, options: ParseOptions): BookDocument = TODO("txt")
    fun readMeta(file: File): DocMeta = TODO("txt")

    /**
     * First paragraphs of the file for thumbnails: decodes only the beginning (<= 64 KB), normalises like
     * open() and returns at most [maxChars] chars, paragraphs separated by '\n'.
     */
    fun preview(file: File, maxChars: Int = 1500, encoding: String = ""): String = TODO("txt")

    /** Encodings offered in "인코딩 변경" (Java charset names). */
    val ENCODINGS: List<String> = listOf("UTF-8", "MS949", "EUC-KR", "UTF-16LE", "UTF-16BE")
}
