package com.ggumtak.readeraplus.data

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor

/**
 * CONTRACT STUB — read-only provider so "파일 공유" can hand book files to other apps without AndroidX
 * FileProvider. URI: content://<applicationId>.files/book/<bookId>. Implemented by the data owner.
 */
class BookFileProvider : ContentProvider() {
    companion object {
        fun uriFor(authorityPackage: String, bookId: Long): Uri = TODO("data")
    }
    override fun onCreate(): Boolean = true
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = TODO("data")
    override fun getType(uri: Uri): String? = TODO("data")
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? = TODO("data")
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
