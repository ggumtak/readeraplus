package com.ggumtak.readeraplus.format.epub

import com.ggumtak.readeraplus.format.DocumentException
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.locks.ReentrantReadWriteLock
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile

/**
 * Random-access view of an EPUB container. One [ZipFile] stays open while the book is open (concurrent reads
 * share a read lock; [close] takes the write lock). Entry names are normalised (`a/./b/../c` → `a/c`) and
 * looked up case-sensitively first, then case-insensitively. Reads after [close] reopen the file temporarily,
 * so late background work never crashes.
 */
internal class EpubZip private constructor(
    val file: File,
    private var zip: ZipFile?,
    private val entries: HashMap<String, ZipEntry>,
) : Closeable {
    private val lock = ReentrantReadWriteLock()
    @Volatile private var lowerIndex: HashMap<String, String>? = null

    /** Normalised names of all file entries (unordered). */
    val names: Set<String> get() = entries.keys

    /** Canonical entry name for a normalised [path] (exact, then case-insensitive), or null. */
    fun find(path: String): String? {
        if (path.isEmpty()) return null
        if (entries.containsKey(path)) return path
        val lower = lowerIndex ?: synchronized(this) {
            lowerIndex ?: HashMap<String, String>(entries.size * 2).also { m ->
                for (k in entries.keys) m.putIfAbsent(k.lowercase(), k)
                lowerIndex = m
            }
        }
        return lower[path.lowercase()]
    }

    /** Uncompressed size of [name] (canonical), -1 if unknown/missing. */
    fun size(name: String): Long = entries[name]?.size ?: -1L

    /**
     * Bytes of entry [name] (canonical, or any spelling [find] resolves), or null when missing, larger than
     * [maxBytes] or unreadable (corrupt data).
     */
    fun read(name: String, maxBytes: Int = MAX_ENTRY_BYTES): ByteArray? {
        val key = if (entries.containsKey(name)) name else find(EpubPaths.normalize(name)) ?: return null
        val entry = entries[key] ?: return null
        if (entry.size > maxBytes) return null
        val rl = lock.readLock()
        rl.lock()
        try {
            val z = zip
            if (z != null) return readEntry(z, entry, maxBytes)
        } catch (_: IOException) {
            return null
        } catch (_: IllegalStateException) {
            return null
        } finally {
            rl.unlock()
        }
        // closed: temporary reopen
        return try {
            openZip(file).use { t ->
                val e = t.getEntry(entry.name) ?: return null
                readEntry(t, e, maxBytes)
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * At most [maxBytes] leading bytes of entry [name] (decompressing only that much), or null if missing or
     * unreadable. Used for cheap scans of large documents.
     */
    fun readPrefix(name: String, maxBytes: Int): ByteArray? {
        val key = if (entries.containsKey(name)) name else find(EpubPaths.normalize(name)) ?: return null
        val entry = entries[key] ?: return null
        val rl = lock.readLock()
        rl.lock()
        try {
            val z = zip ?: return null
            z.getInputStream(entry).use { ins ->
                val want = if (entry.size in 0 until maxBytes.toLong()) entry.size.toInt() else maxBytes
                val buf = ByteArray(want)
                var n = 0
                while (n < want) {
                    val r = ins.read(buf, n, want - n)
                    if (r < 0) break
                    n += r
                }
                return if (n == want) buf else buf.copyOf(n)
            }
        } catch (_: IOException) {
            return null
        } catch (_: IllegalStateException) {
            return null
        } finally {
            rl.unlock()
        }
    }

    override fun close() {
        val wl = lock.writeLock()
        wl.lock()
        try {
            try {
                zip?.close()
            } catch (_: IOException) {
            }
            zip = null
        } finally {
            wl.unlock()
        }
    }

    companion object {
        /** Guard against zip bombs / absurd entries (a single XHTML or image above this is ignored). */
        const val MAX_ENTRY_BYTES = 48 * 1024 * 1024

        /** Opens [file]; throws [DocumentException] if it is not a readable zip. */
        fun open(file: File): EpubZip {
            val z = try {
                openZip(file)
            } catch (e: IOException) {
                throw DocumentException("파일을 읽지 못했습니다", e)
            } catch (e: SecurityException) {
                throw DocumentException("파일 접근 권한이 없습니다", e)
            }
            try {
                val map = HashMap<String, ZipEntry>()
                val en = z.entries()
                while (en.hasMoreElements()) {
                    val e = en.nextElement()
                    if (e.isDirectory) continue
                    val n = EpubPaths.normalize(e.name)
                    if (n.isEmpty() || n.endsWith("/")) continue
                    map.putIfAbsent(n, e)
                }
                return EpubZip(file, z, map)
            } catch (e: Exception) {
                try {
                    z.close()
                } catch (_: IOException) {
                }
                throw DocumentException("EPUB 파일이 손상되었습니다", e)
            }
        }

        private fun openZip(file: File): ZipFile {
            val first: ZipException
            try {
                return ZipFile(file)
            } catch (e: ZipException) {
                first = e
            } catch (e: IllegalArgumentException) { // malformed entry names on some runtimes
                first = ZipException(e.message ?: "bad entry name")
            }
            // Android 14+ rejects entries named with ".." or a leading '/' for apps targeting API 34.
            // Such EPUBs exist and are harmless here (nothing is extracted to disk): relax and retry.
            if (ZipCompat.relaxPathValidation()) {
                try {
                    return ZipFile(file)
                } catch (_: ZipException) {
                } catch (_: IllegalArgumentException) {
                }
            }
            // Zips repacked by legacy Korean tools store CP949 (not UTF-8) entry names.
            val cp949 = EpubText.cp949()
            if (cp949 != null && file.isFile) {
                try {
                    return ZipFile(file, ZipFile.OPEN_READ, cp949)
                } catch (_: ZipException) {
                } catch (_: IllegalArgumentException) {
                }
            }
            throw first
        }

        private fun readEntry(z: ZipFile, e: ZipEntry, maxBytes: Int): ByteArray? {
            z.getInputStream(e).use { ins -> return readFully(ins, e.size, maxBytes) }
        }

        /** Reads a stream fully; [sizeHint] (-1 = unknown) presizes the buffer. Null if larger than [max]. */
        fun readFully(ins: InputStream, sizeHint: Long, max: Int): ByteArray? {
            var buf = ByteArray(if (sizeHint in 0..max.toLong()) maxOf(sizeHint.toInt(), 1) else 16 * 1024)
            var n = 0
            while (true) {
                if (n == buf.size) {
                    val b = ins.read()
                    if (b < 0) break
                    if (buf.size >= max) return null
                    buf = buf.copyOf(minOf(max.toLong(), buf.size.toLong() * 2).toInt())
                    buf[n++] = b.toByte()
                    continue
                }
                val r = ins.read(buf, n, buf.size - n)
                if (r < 0) break
                n += r
            }
            return if (n == buf.size) buf else buf.copyOf(n)
        }
    }
}

/** Android-version specific zip workarounds, isolated so JVM tests never touch android.os. */
internal object ZipCompat {
    @Volatile private var relaxed = false

    /** Clears the API 34 zip path validator once. Returns true if a retry may help. */
    fun relaxPathValidation(): Boolean {
        if (relaxed) return false
        return try {
            if (android.os.Build.VERSION.SDK_INT >= 34) {
                dalvik.system.ZipPathValidator.clearCallback()
                relaxed = true
                true
            } else {
                false
            }
        } catch (_: Throwable) {
            false
        }
    }
}
