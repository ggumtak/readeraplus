package com.ggumtak.readeraplus.data

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.database.sqlite.SQLiteStatement
import android.util.Log

/**
 * The library database (`library.db`, WAL, synchronous=NORMAL). Constructing it is cheap: the file is
 * opened on first use (or by [Library.init]'s background warm-up).
 */
internal class LibraryDb(context: Context) :
    SQLiteOpenHelper(context, LibrarySchema.DB_NAME, null, LibrarySchema.DB_VERSION) {

    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onConfigure(db: SQLiteDatabase) {
        try {
            // Runs on the primary (write) connection, which is the one that syncs.
            db.execSQL(LibrarySql.PRAGMA_SYNCHRONOUS)
        } catch (t: Throwable) {
            Log.w(TAG, "synchronous pragma failed", t)
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        for (sql in LibrarySchema.CREATE_ALL) db.execSQL(sql)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v1 is the first schema; future migrations go here. CREATE ... IF NOT EXISTS keeps this idempotent.
        for (sql in LibrarySchema.CREATE_ALL) db.execSQL(sql)
    }

    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // An older build on a newer file: keep the data (newer schemas only add columns / tables).
    }

    companion object {
        const val TAG = "LibraryDb"
    }
}

// ---- small SQLite helpers (internal to the data module) ----

/** Runs [block] with a compiled statement, closing it afterwards (Android caches the prepared statement). */
internal inline fun <T> SQLiteDatabase.withStatement(sql: String, block: (SQLiteStatement) -> T): T {
    val st = compileStatement(sql)
    try {
        return block(st)
    } finally {
        st.close()
    }
}

/** Binds positional args by their Kotlin type (null, Long/Int/Short/Byte, Boolean → 0/1, Float/Double, ByteArray, else string). */
internal fun SQLiteStatement.bindAll(vararg args: Any?) {
    clearBindings()
    for (i in args.indices) {
        val idx = i + 1
        when (val a = args[i]) {
            null -> bindNull(idx)
            is Long -> bindLong(idx, a)
            is Int -> bindLong(idx, a.toLong())
            is Short -> bindLong(idx, a.toLong())
            is Byte -> bindLong(idx, a.toLong())
            is Boolean -> bindLong(idx, if (a) 1L else 0L)
            is Float -> bindDouble(idx, a.toDouble())
            is Double -> bindDouble(idx, a)
            is ByteArray -> bindBlob(idx, a)
            else -> bindString(idx, a.toString())
        }
    }
}

/** UPDATE / DELETE with typed args; returns the number of changed rows. */
internal fun SQLiteDatabase.exec(sql: String, vararg args: Any?): Int =
    withStatement(sql) { st ->
        st.bindAll(*args)
        st.executeUpdateDelete()
    }

/** INSERT with typed args; returns the new row id or -1 when nothing was inserted (OR IGNORE). */
internal fun SQLiteDatabase.insertRow(sql: String, vararg args: Any?): Long =
    withStatement(sql) { st ->
        st.bindAll(*args)
        st.executeInsert()
    }

/** Runs a SELECT and maps every row. */
internal inline fun <T> SQLiteDatabase.queryList(sql: String, args: Array<String>?, map: (Cursor) -> T): List<T> {
    val c = rawQuery(sql, args)
    try {
        if (c.count <= 0) return emptyList()
        val out = ArrayList<T>(c.count)
        while (c.moveToNext()) out += map(c)
        return out
    } finally {
        c.close()
    }
}

/** Runs a SELECT and maps the first row, or null. */
internal inline fun <T> SQLiteDatabase.queryFirst(sql: String, args: Array<String>?, map: (Cursor) -> T): T? {
    val c = rawQuery(sql, args)
    try {
        return if (c.moveToFirst()) map(c) else null
    } finally {
        c.close()
    }
}

/** Runs [block] inside a (non-exclusive, WAL-friendly) transaction. */
internal inline fun <T> SQLiteDatabase.inTransaction(block: SQLiteDatabase.() -> T): T {
    beginTransactionNonExclusive()
    try {
        val r = block()
        setTransactionSuccessful()
        return r
    } finally {
        endTransaction()
    }
}

internal fun args(vararg a: Any): Array<String> = Array(a.size) { a[it].toString() }
