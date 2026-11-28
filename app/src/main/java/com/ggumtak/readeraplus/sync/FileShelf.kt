package com.ggumtak.readeraplus.sync

import android.content.Context
import java.io.File

internal interface FileShelf {
    fun list(): List<ShelfBook>
    fun upload(book: ShelfBook, input: File)
    fun download(book: ShelfBook, target: File)
    fun readProgress(doc: String): Pair<SyncRecord?, String?>
    fun putProgress(doc: String, record: SyncRecord, expected: String?): Boolean

    companion object {
        fun open(c: Context, a: SyncAccount, allow: () -> Boolean = { true }): FileShelf =
            if (a.drive) DriveShelf({ DriveAuthorization.token(c, a.user) }, allow)
            else { require(a.dav); DavShelf(a, allow) }
    }
}
