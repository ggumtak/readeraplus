package com.ggumtak.readeraplus.data

import com.ggumtak.readeraplus.reader.UriPaths
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Placing book files into the library's copy folder (file picker / VIEW / SEND copies, imports, LAN uploads).
 * Every caller first writes the incoming bytes to a temp file in the same folder, then asks [existingCopy] whether
 * an identical file is already there and otherwise [place]s the temp file. One lock covers all threads, and a
 * move never replaces an existing file, so a book the library points at is never overwritten.
 */
internal object BookCopies {
    /** Numbered names "name (2).ext" … looked at when searching for an earlier copy. */
    private const val MAX_VARIANTS = 50
    private const val BUFFER_BYTES = 64 * 1024

    /** Temp files written next to the books before [settle]: file-picker copies, imports, LAN uploads. */
    internal const val COPY_PREFIX = ".copy-"
    internal const val IMPORT_PREFIX = ".import-"
    internal const val PART_SUFFIX = ".part"
    private const val STALE_TEMP_MS = 60 * 60 * 1000L

    /** Equal length and equal bytes; streams both files, stops at the first difference. */
    fun sameContent(a: File, b: File): Boolean {
        if (!a.isFile || !b.isFile) return false
        if (a.length() != b.length()) return false
        return try {
            a.inputStream().use { x -> b.inputStream().use { y -> sameBytes(x, y) } }
        } catch (e: IOException) {
            false
        }
    }

    private fun sameBytes(x: InputStream, y: InputStream): Boolean {
        val bx = ByteArray(BUFFER_BYTES)
        val by = ByteArray(BUFFER_BYTES)
        while (true) {
            val n = readFully(x, bx)
            if (n != readFully(y, by)) return false
            if (n == 0) return true
            for (i in 0 until n) if (bx[i] != by[i]) return false
        }
    }

    /** Reads until [buf] is full or the stream ends; the count read. */
    private fun readFully(input: InputStream, buf: ByteArray): Int {
        var total = 0
        while (total < buf.size) {
            val n = input.read(buf, total, buf.size - total)
            if (n < 0) break
            total += n
        }
        return total
    }

    /**
     * Moves the fully written [tmp] into [dir] as [fileName], or "name (2).ext", "name (3).ext", … — the first name
     * that does not exist. Never replaces a file; returns the final file.
     */
    @Synchronized
    fun place(tmp: File, dir: File, fileName: String): File {
        var n = 1
        while (true) {
            val target = File(dir, UriPaths.numberedName(fileName, n++))
            // ATOMIC_MOVE is rename(2), which replaces on POSIX: only this check under the lock keeps it safe.
            if (target.exists()) continue
            try {
                move(tmp, target)
                return target
            } catch (e: FileAlreadyExistsException) {
                // Taken since the check (by something outside this object): try the next number.
            }
        }
    }

    private fun move(tmp: File, target: File) {
        try {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), target.toPath())
        }
    }

    /** The file called [fileName] in [dir] (or a numbered variant of it) whose content equals [incoming]; else null. */
    @Synchronized
    fun existingCopy(dir: File, fileName: String, incoming: File): File? {
        for (n in 1..MAX_VARIANTS) {
            val f = File(dir, UriPaths.numberedName(fileName, n))
            if (f.isFile && sameContent(f, incoming)) return f
        }
        return null
    }

    /**
     * The usual last step of a copy: the identical earlier copy of [tmp] when there is one (the temp file is
     * deleted), else [tmp] placed under [fileName]. Check and move happen under one lock hold.
     */
    @Synchronized
    fun settle(tmp: File, dir: File, fileName: String): File {
        existingCopy(dir, fileName, tmp)?.let {
            tmp.delete()
            return it
        }
        return place(tmp, dir, fileName)
    }

    /**
     * Deletes the `.copy-*.part`, `.import-*.part` and `.upload-*.part` files in [dir] that were last written more
     * than [olderThanMs] ago: what a killed process left behind. A copy still being written keeps a fresh
     * modification time, so it is never touched. Best effort.
     */
    fun sweepTemps(dir: File, olderThanMs: Long = STALE_TEMP_MS) {
        try {
            val limit = System.currentTimeMillis() - olderThanMs
            dir.listFiles()?.forEach { f ->
                val n = f.name
                val temp = n.endsWith(PART_SUFFIX) &&
                    (n.startsWith(COPY_PREFIX) || n.startsWith(IMPORT_PREFIX) || n.startsWith(LanExchange.TEMP_PREFIX))
                if (temp && f.isFile && f.lastModified() < limit) f.delete()
            }
        } catch (t: Throwable) {
            // A leftover hidden temp file costs only its space.
        }
    }
}
