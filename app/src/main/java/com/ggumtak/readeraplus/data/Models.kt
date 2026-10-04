package com.ggumtak.readeraplus.data

import com.ggumtak.readeraplus.format.BookFormat

/** A library entry (one file on disk). Mutable flags are persisted through Library. */
data class Book(
    val id: Long,
    val path: String,
    val fileName: String,
    val title: String,
    val author: String,
    val series: String?,
    val seriesIndex: Float?,
    val format: BookFormat,
    val sizeBytes: Long,
    val modifiedAt: Long,
    val addedAt: Long,
    /** 0 = never opened. */
    val lastReadAt: Long = 0,
    /** Saved reading position. */
    val posSection: Int = 0,
    val posOffset: Int = 0,
    /** 0..1 reading progress (by pages when known, else by chars). */
    val progress: Float = 0f,
    val favorite: Boolean = false,
    val toRead: Boolean = false,
    val haveRead: Boolean = false,
    val trashed: Boolean = false,
    /** "My review" text. */
    val review: String = "",
    /** Forced TXT encoding ("" = auto). */
    val encoding: String = "",
    val language: String? = null,
    /** Total reading time in seconds. */
    val readingSeconds: Long = 0,
    /** Positive when a missing file caused this book to be moved to trash. */
    val missingAt: Long = 0,
) {
    val folder: String get() = path.substringBeforeLast('/', "")
}

data class Bookmark(
    val id: Long,
    val bookId: Long,
    val section: Int,
    val offset: Int,
    /** First ~80 chars of the page for the list. */
    val snippet: String,
    val createdAt: Long,
    val note: String = "",
    val chapter: String = "", val frac: Float = -1f, val sig: String = "",
)

/** A saved quote / highlight ("인용문"). */
data class Quote(
    val id: Long,
    val bookId: Long,
    val section: Int,
    val start: Int,
    val end: Int,
    val text: String,
    val note: String = "",
    val createdAt: Long,
    val style: Int = 0,
    val chapter: String = "", val frac: Float = -1f, val sig: String = "",
)

data class BookCollection(val id: Long, val name: String, val createdAt: Long, val bookCount: Int = 0)

/**
 * The drawer destinations (ReadEra-style). [label] is the one wording of each shelf: the drawer, the toolbar title,
 * the book menus' flag items and the empty-shelf texts all use `Shelf.X.label` (glossary: 책, never 문서).
 */
enum class Shelf(val label: String) {
    READING_NOW("읽고 있는 책"),
    ALL("모든 책"),
    FAVORITES("즐겨찾기"),
    TO_READ("읽을 책"),
    HAVE_READ("다 읽은 책"),
    AUTHORS("작가"),
    SERIES("시리즈"),
    COLLECTIONS("컬렉션"),
    FORMATS("형식"),
    FOLDERS("폴더"),
    DOWNLOADS("다운로드"),
    TRASH("휴지통"),
}

/**
 * What to list. [group] narrows a grouped shelf (author name, series name, collection id as string,
 * format name, folder path). [query] filters title/author/file name (case-insensitive substring).
 */
data class LibraryQuery(
    val shelf: Shelf = Shelf.ALL,
    val group: String? = null,
    val query: String = "",
)

/** A group row for grouped shelves (authors, series, collections, formats, folders). */
data class ShelfGroup(val key: String, val label: String, val count: Int)

/** Where a note sits, computed by the reader at creation (NotePlaceHost). */
data class NotePlace(val chapter: String, val frac: Float, val sig: String) {
    companion object { val UNKNOWN = NotePlace("", -1f, "") }
}

enum class NoteKind(val code: Int, val label: String) { QUOTE(1, "인용문"), BOOKMARK(2, "북마크"), REVIEW(3, "리뷰"), LOOKUP(4, "단어") }
enum class NotesTab(val label: String) { ALL("전체"), QUOTES("인용문"), MEMOS("메모"), BOOKMARKS("북마크"), REVIEWS("리뷰"), WORDS("단어장") }
/** The hub's orders (stored by name): [label] in the chooser, [short] on the chip. */
enum class NotesOrder(val label: String, val short: String) {
    NEWEST("최신순", "최신순"), OLDEST("오래된순", "오래된순"),
    BOOK_RECENT("책별 · 최근 읽은 순", "책별 · 최근"), BOOK_TITLE("책별 · 제목순", "책별 · 제목");
    val byBook: Boolean get() = this == BOOK_RECENT || this == BOOK_TITLE
}

/** What the hub lists. [bookId] null = all books; [text] = search words; [style] = colour filter (QUOTES tab only). */
data class NotesQuery(
    val tab: NotesTab = NotesTab.ALL, val order: NotesOrder = NotesOrder.NEWEST,
    val bookId: Long? = null, val text: String = "", val style: Int? = null,
    /** WORDS tab: one row per word (its latest lookup). */
    val wordsOnce: Boolean = false,
)

/** Identity of one note across the four sources. REVIEW's id is the book id. */
data class NoteRef(val kind: NoteKind, val id: Long) {
    fun packed(): Long = (kind.code.toLong() shl 56) or (id and 0x00FF_FFFF_FFFF_FFFFL)
    companion object {
        fun unpack(v: Long): NoteRef? {
            val kind = NoteKind.entries.firstOrNull { it.code == (v ushr 56).toInt() } ?: return null
            return NoteRef(kind, v and 0x00FF_FFFF_FFFF_FFFFL)
        }
    }   // null for an unknown kind code
}

/** One hub row; [body] ≤ 600 chars (quote text, bookmark snippet, review, lookup sentence), [note] ≤ 400. */
data class NoteRow(
    val ref: NoteRef, val bookId: Long,
    val section: Int, val start: Int, val end: Int,
    val body: String, val bodyCut: Boolean, val note: String, val noteCut: Boolean,
    val word: String, val wordCount: Int,       // LOOKUP only (wordCount ≥ 1)
    val style: Int,                             // QUOTE only
    val via: Int, val app: String,              // LOOKUP only
    val chapter: String, val frac: Float, val sig: String,
    val time: Long,
)
data class NoteBook(val id: Long, val title: String, val author: String, val path: String,
                    val trashed: Boolean, val missing: Boolean, val lastReadAt: Long, val count: Int)
data class NotesCounts(val quotes: Int, val memos: Int, val bookmarks: Int, val reviews: Int, val words: Int) {
    val all: Int get() = quotes + bookmarks + reviews + words
    fun of(tab: NotesTab): Int = when (tab) {
        NotesTab.ALL -> all; NotesTab.QUOTES -> quotes; NotesTab.MEMOS -> memos
        NotesTab.BOOKMARKS -> bookmarks; NotesTab.REVIEWS -> reviews; NotesTab.WORDS -> words
    }
}
data class Lookup(
    val id: Long, val bookId: Long, val word: String, val section: Int, val start: Int, val end: Int,
    val context: String, val chapter: String, val frac: Float, val sig: String,
    val via: Int, val app: String, val note: String, val createdAt: Long,
)
