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
        // Runs inside SQLiteOpenHelper's transaction: all or nothing. New tables / indexes first (idempotent), then
        // the columns newer versions added to existing tables (skipped when a column is already there), then (v3) the
        // orphan sweep of rows a downgraded build left behind. A constant number of statements, no per-row work: the
        // v2 → v3 step stays within its 50 ms budget on 10k notes (it runs once, on "library-db-open").
        val t0 = System.nanoTime()
        for (sql in LibrarySchema.CREATE_ALL) db.execSQL(sql)
        // Asked only now: CREATE_ALL may just have made a table (with every column) that an ALTER would otherwise add.
        val tail = upgradeTail(oldVersion) { table -> columnsOf(db, table) }
        for (sql in tail) db.execSQL(sql)
        Log.i(TAG, "upgrade v$oldVersion → v$newVersion: ${LibrarySchema.CREATE_ALL.size + tail.size} statements, " +
            "${(System.nanoTime() - t0) / 1_000_000} ms")
    }

    /** Column names of [table] (`PRAGMA table_info`); empty when the table doesn't exist. */
    private fun columnsOf(db: SQLiteDatabase, table: String): Set<String> =
        db.queryList("PRAGMA table_info($table)", null) { c -> c.getString(c.getColumnIndexOrThrow("name")) ?: "" }.toHashSet()

    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // An older build on a newer file: keep the data (newer schemas only add columns / tables).
    }

    companion object {
        const val TAG = "LibraryDb"

        /**
         * What [onUpgrade] runs from [oldVersion] AFTER [LibrarySchema.CREATE_ALL], in order: the missing columns
         * ([LibrarySchema.upgradeStatements]; [columnsOf] = a table's columns after CREATE_ALL, asked at most once per
         * table) and, below v3, [LibrarySchema.UPGRADE_SWEEP] + [NOTES_SWEEP] after the ALTERs. Pure (JVM-tested; `tools/check_sql.py`
         * runs the same order).
         */
        internal fun upgradeTail(oldVersion: Int, columnsOf: (String) -> Set<String>): List<String> {
            val cache = HashMap<String, Set<String>>()
            val out = ArrayList<String>(16)
            out += LibrarySchema.upgradeStatements(oldVersion) { t -> cache.getOrPut(t) { columnsOf(t) } }
            if (oldVersion < 3) {
                out += LibrarySchema.UPGRADE_SWEEP
                out += NOTES_SWEEP
            }
            return out
        }

        /**
         * N §4.1's defence sweep of quotes and bookmarks left without a book (the frozen [LibrarySchema.UPGRADE_SWEEP]
         * covers lookups, book_prefs and reading_log). Set-based, one statement per table.
         */
        internal val NOTES_SWEEP = listOf(
            "DELETE FROM quotes WHERE book_id NOT IN (SELECT id FROM books)",
            "DELETE FROM bookmarks WHERE book_id NOT IN (SELECT id FROM books)",
        )
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
