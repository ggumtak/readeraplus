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
)

data class BookCollection(val id: Long, val name: String, val createdAt: Long, val bookCount: Int = 0)

/** The drawer destinations (ReadEra-style). */
enum class Shelf(val label: String) {
    READING_NOW("읽고있는 문서"),
    ALL("책 & 문서"),
    FAVORITES("즐겨찾기"),
    TO_READ("읽을 문서"),
    HAVE_READ("읽던 문서"),
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
