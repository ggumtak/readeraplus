package com.ggumtak.readeraplus.format

import com.ggumtak.readeraplus.format.epub.EpubDocuments
import com.ggumtak.readeraplus.format.epub.EpubPlanCache
import com.ggumtak.readeraplus.format.txt.TxtDocuments
import java.io.File

/** Entry point for opening books. Dispatches on file extension. */
object Documents {
    /** App cache directory (set once by App.onCreate); parsers may keep index caches under it. */
    @Volatile var cacheDir: File? = null

    /** Opens [file] (blocking: IO + parsing; never on the main thread). Throws on failure. */
    fun open(file: File, options: ParseOptions = ParseOptions()): BookDocument =
        when (BookFormat.forFile(file.name)) {
            BookFormat.TXT -> TxtDocuments.open(file, options)
            BookFormat.EPUB -> EpubDocuments.open(file, options)
            null -> throw DocumentException("지원하지 않는 형식: ${file.name}")
        }

    /**
     * R2 (A12-1): writes the cache files that opens computed but deliberately did not write on the opening thread
     * (the EPUB section-plan cache, `EpubPlanCache`). The reader calls it once per open, after the first page is shown
     * (`afterOpen` → `ReaderIo.launch`). Blocking IO; no-op when nothing is pending; never throws. Owner: FORMAT.
     */
    fun writeDeferredCaches() {
        EpubPlanCache.writePending()
    }

    /** Fast metadata for library scanning (EPUB: OPF only; TXT: title from file name + encoding sniff). */
    fun readMeta(file: File): DocMeta =
        when (BookFormat.forFile(file.name)) {
            BookFormat.TXT -> TxtDocuments.readMeta(file)
            BookFormat.EPUB -> EpubDocuments.readMeta(file)
            null -> DocMeta(file.nameWithoutExtension)
        }
}
