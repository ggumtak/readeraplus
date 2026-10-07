package com.ggumtak.readeraplus.format

import com.ggumtak.readeraplus.engine.SectionContent
import java.io.Closeable
import java.io.File

enum class BookFormat(val label: String, val extensions: List<String>) {
    TXT("TXT", listOf("txt")),
    EPUB("EPUB", listOf("epub"));

    companion object {
        fun forFile(name: String): BookFormat? {
            val ext = name.substringAfterLast('.', "").lowercase()
            return entries.firstOrNull { ext in it.extensions }
        }
    }
}

/** Reading position: a section index and a char offset in that section's text. */
data class DocPosition(val section: Int, val offset: Int) {
    companion object {
        @JvmField val START = DocPosition(0, 0)
    }
}

class DocMeta(
    val title: String,
    val authors: List<String> = emptyList(),
    val series: String? = null,
    val seriesIndex: Float? = null,
    val language: String? = null,
    val publisher: String? = null,
    val description: String? = null,
    /** TXT: detected charset name. */
    val encoding: String? = null,
)

/** Table-of-contents entry. [anchor] (EPUB fragment id) is resolved against SectionContent.anchors on use. */
class TocEntry(
    val title: String,
    val level: Int,
    val section: Int,
    val offset: Int = 0,
    val anchor: String? = null,
)

/** Static info about a layout unit, known without loading it. */
class SectionInfo(
    /** Chapter title if this section starts a chapter (TXT), else null. */
    val title: String?,
    /** Approximate length in chars (exact for TXT) — used for progress estimation before page counts exist. */
    val approxChars: Int,
)

/**
 * An opened book. Implementations must be safe to call from multiple background threads concurrently
 * (loadSection / loadImage), and cheap to keep open while reading. Never call from the main thread.
 */
interface BookDocument : Closeable {
    val file: File
    val format: BookFormat
    val meta: DocMeta
    val sections: List<SectionInfo>
    val toc: List<TocEntry>

    /** Builds (or returns cached) content of section [index]. */
    fun loadSection(index: Int): SectionContent

    /** Raw bytes of an image referenced by ImageBlock.src, or null. */
    fun loadImage(src: String): ByteArray?

    /** Cover image bytes (EPUB), or null (TXT — the UI renders a text thumbnail instead). */
    fun coverImage(): ByteArray?

    /** Resolves a link found in section [fromSection] (RunStyle.link) to a position, or null if external. */
    fun resolveLink(fromSection: Int, href: String): DocPosition?

    /** Resolves a TOC entry to a concrete position (may load the section to resolve the anchor). */
    fun resolveToc(entry: TocEntry): DocPosition
}

/** Options that change how a document is parsed (part of the page-count cache key). */
data class ParseOptions(
    /** TXT blank lines: BLANK_AUTO, BLANK_REMOVE_ALL, BLANK_COLLAPSE, BLANK_KEEP (see TxtDocuments). */
    val txtBlankLines: Int = BLANK_AUTO,
    /** TXT: drop the source's leading spaces / U+3000 / tabs (our own indent setting applies instead). */
    val txtStripIndent: Boolean = true,
    /** TXT: join hard-wrapped lines: 0 = off, 1 = auto-detect, 2 = always. */
    val txtJoinWrappedLines: Int = 1,
    /** TXT: detect chapter headings to build a TOC and split sections. */
    val txtDetectChapters: Boolean = true,
    /** TXT: extra user regex for chapter headings (matched against trimmed lines), empty = none. */
    val txtChapterRegex: String = "",
    /** TXT: render detected chapter headings bold/larger/centered. */
    val txtEmphasizeHeadings: Boolean = true,
    /**
     * TXT: regex replacement rules applied per line before anything else, one per line:
     * `pattern => replacement` (a line starting with # is a comment). Invalid rules are ignored.
     */
    val txtReplaceRules: String = "",
    /** TXT: forced encoding name, empty = auto-detect. */
    val txtEncoding: String = "",
    /** EPUB: keep publisher CSS hints (alignment, margins, font sizes of headings). */
    val epubPublisherStyles: Boolean = true,
    /** EPUB: ignore the book's font-size declarations except on headings (body text is the reader's size). */
    val epubIgnoreBookSizes: Boolean = true,
) {
    companion object {
        /** Remove blank lines only when the file alternates text/blank lines; runs of 2+ become scene breaks. */
        const val BLANK_AUTO = 0
        /** Remove every blank line (spacing comes from paragraph spacing); runs of 2+ still mark scene breaks. */
        const val BLANK_REMOVE_ALL = 1
        /** Collapse each run of blank lines into one empty paragraph. */
        const val BLANK_COLLAPSE = 2
        /** Keep every blank line as an empty paragraph. */
        const val BLANK_KEEP = 3
    }
}

class DocumentException(message: String, cause: Throwable? = null) : Exception(message, cause)
