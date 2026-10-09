package com.ggumtak.readeraplus.render

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * Copies of font files without their blank mappings ([HollowGlyphs]), in cacheDir/fonts-fixed.
 *
 * The first typeface build of a font file scans it once (the reader starts it for its font's two files next to its
 * warm-up: [FontManager.prepare]). When it maps characters to glyphs without an outline, a copy whose cmap leaves them
 * out is written here and the typeface loads the copy, so the system font draws them. The verdict (that copy, or an
 * empty `.ok` marker for a file that needs none) is named after the file's content identity (an asset: its length and
 * its first bytes, the table directory with every table's checksum, so an app update that keeps the font keeps the
 * verdict; a user font: its size and mtime) and [VERSION] ([RepairNames]), so a later process start reads or stats one
 * file per face; a new verdict deletes the file's older ones. A file that could not be read through gets no verdict
 * (asked again next process), nor does one whose copy could not be written (no space: the original is used for the
 * rest of the process). The copies never leave the device and the shipped assets stay unmodified (OFL;
 * assets/fonts/licenses/FONTS.txt). 캐시 비우기 or the system may delete them at any time: a typeface already loaded
 * keeps working and the next build writes the copy again.
 */
internal object FontRepairs {
    private const val TAG = "FontRepairs"
    private const val DIR = "fonts-fixed"
    /**
     * Version of what [HollowGlyphs] drops: bump it when that changes, so every verdict on disk is made again. The
     * page-count keys of repaired fonts carry it (`FontManager.layoutTag`), so their counts are made again too.
     */
    const val VERSION = 1

    /** Larger font files are used as they are: a copy of a huge collection in the cache is not worth it. */
    private const val MAX_COPY_BYTES = 128L shl 20

    private val locks = HashMap<String, Any>()
    /** Asset path → its content stamp ([RepairNames.assetStamp]), read once per process. */
    private val assetStamps = HashMap<String, String>()
    /** Verdicts whose copy could not be written in this process: the original file is used, without a retry. */
    private val failed = HashSet<String>()

    /**
     * The file a typeface of font file [path] ([source] BUNDLED: an asset path) should load: the repaired copy, or
     * null for the original (nothing to repair, or it could not be checked). Disk IO the first time per font file
     * (an asset: per version of it, not per app update), after that a stat or two (and an asset's first 4 KB once per
     * process). Any thread.
     */
    fun fileFor(ctx: Context, source: FontSource, path: String): File? {
        val key = RepairNames.sourceKey(source == FontSource.BUNDLED, path)
        val stamp = if (source == FontSource.BUNDLED) assetStamp(ctx, path) ?: return null
        else File(path).let { "${it.length()}:${it.lastModified()}" }
        val base = RepairNames.base(key, stamp, VERSION)
        val dir = File(ctx.cacheDir, DIR)
        val fixed = File(dir, base + RepairNames.FONT)
        val ok = File(dir, base + RepairNames.OK)
        val lock = synchronized(locks) { locks.getOrPut(base) { Any() } }
        synchronized(lock) {
            if (fixed.isFile) return fixed
            if (ok.isFile || synchronized(locks) { base in failed }) return null
            return make(ctx, source, path, dir, key, base, fixed, ok)
        }
    }

    /** Deletes every verdict of a user font file (it was deleted). */
    fun forget(ctx: Context, path: String) {
        val key = RepairNames.sourceKey(false, path)
        File(ctx.cacheDir, DIR).listFiles()?.forEach { if (RepairNames.isOf(it.name, key)) it.delete() }
    }

    private fun make(ctx: Context, source: FontSource, path: String, dir: File, key: String, base: String, fixed: File, ok: File): File? {
        val started = SystemClock.uptimeMillis()
        val size = if (source == FontSource.BUNDLED) 0L else File(path).length()
        if (size > MAX_COPY_BYTES) {
            // Not scanned at all: the verdict is the original file, for this size and mtime.
            Log.i(TAG, "$path: ${size shr 20} MB, used as it is (blank glyphs are not looked for past ${MAX_COPY_BYTES shr 20} MB)")
            marker(dir, key, base, ok, path)
            return null
        }
        val result = try {
            scan(ctx, source, path)
        } catch (t: Throwable) {
            // Out of memory, or the file went away: no verdict, the next process asks again.
            Log.w(TAG, "scan failed: $path", t)
            return null
        }
        if (result.unreadable) {
            Log.w(TAG, "could not read $path through: no verdict, asked again next time")
            return null
        }
        val scan = result.scan
        if (scan == null || scan.hollow.isEmpty()) {
            marker(dir, key, base, ok, path)
            return null
        }
        if (!prepareDir(dir, key, base)) return null
        val tmp = File(dir, base + RepairNames.TEMP)
        return try {
            val copied = open(ctx, source, path).use { input -> FileOutputStream(tmp).use { input.copyTo(it, 64 * 1024) } }
            RandomAccessFile(tmp, "rw").use { HollowGlyphs.patch(it, scan, HollowGlyphs.cmapWithout(scan)) }
            if (!tmp.renameTo(fixed)) throw IOException("rename failed")
            Log.i(TAG, "$path: ${scan.hollow.size} characters without an outline left to the system font, " +
                "a ${copied shr 10} KB copy in ${SystemClock.uptimeMillis() - started} ms")
            fixed
        } catch (t: Throwable) {
            tmp.delete()
            synchronized(locks) { failed.add(base) }
            Log.w(TAG, "repair failed: $path (the file is used as it is until the app starts again)", t)
            null
        }
    }

    /** The `.ok` verdict: [path] is used as it is. Deletes the file's older verdicts first. */
    private fun marker(dir: File, key: String, base: String, ok: File, path: String) {
        if (!prepareDir(dir, key, base)) return
        try {
            ok.createNewFile()
        } catch (e: IOException) {
            Log.w(TAG, "no marker for $path: $e")
        }
    }

    /** Makes [dir] and deletes the verdicts of [key] other than [base]'s; false when the directory can't be made. */
    private fun prepareDir(dir: File, key: String, base: String): Boolean {
        if (!dir.isDirectory && !dir.mkdirs()) return false
        dir.listFiles()?.forEach { if (RepairNames.isOf(it.name, key) && !RepairNames.isOf(it.name, key, base)) it.delete() }
        return true
    }

    /** [HollowGlyphs.scan]'s answer, and whether a read inside the file failed (an IO error, not a broken table). */
    private class ScanResult(val scan: HollowGlyphs.Scan?, val unreadable: Boolean)

    private fun scan(ctx: Context, source: FontSource, path: String): ScanResult {
        fun of(src: SfntSource): ScanResult {
            val checked = CheckedSfntSource(src)
            val scan = HollowGlyphs.scan(checked)
            return ScanResult(scan, scan == null && checked.failed)
        }
        if (source != FontSource.BUNDLED) return FileSfntSource(File(path)).use { of(it) }
        return try {
            // Fonts are stored uncompressed (noCompress): read the few tables in place, inside the APK.
            val afd = ctx.assets.openFd(path)
            afd.createInputStream().use { of(ChannelSfntSource(it.channel, afd.startOffset, afd.length)) }
        } catch (e: FileNotFoundException) {
            of(ByteArraySfntSource(ctx.assets.open(path).use { it.readBytes() }))
        }
    }

    private fun open(ctx: Context, source: FontSource, path: String): InputStream =
        if (source == FontSource.BUNDLED) ctx.assets.open(path) else FileInputStream(path)

    /**
     * Asset [path]'s content stamp ([RepairNames.assetStamp]): read once per process, null when it can't be read (no
     * verdict then: the asset loads as it is).
     */
    private fun assetStamp(ctx: Context, path: String): String? {
        synchronized(assetStamps) { assetStamps[path]?.let { return it } }
        val stamp = try {
            ctx.assets.open(path).use { input ->
                val head = ByteArray(RepairNames.STAMP_BYTES)
                var n = 0
                while (n < head.size) {
                    val r = input.read(head, n, head.size - n)
                    if (r < 0) break
                    n += r
                }
                // The length without reading the rest: the stream's remaining bytes (uncompressed assets know it).
                val length = n + input.available().toLong()
                RepairNames.assetStamp(length, head, n)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "could not read $path", t)
            return null
        }
        synchronized(assetStamps) { assetStamps[path] = stamp }
        return stamp
    }
}

/**
 * [inner], remembering whether a read inside its range failed: an IO error (a user font on removable storage), not a
 * table offset past the end, which [HollowGlyphs] treats as a broken font and leaves alone.
 */
internal class CheckedSfntSource(private val inner: SfntSource) : SfntSource {
    override val size: Long get() = inner.size
    var failed = false
        private set

    override fun read(pos: Long, dst: ByteArray, off: Int, len: Int): Boolean {
        val ok = inner.read(pos, dst, off, len)
        if (!ok && pos >= 0 && len >= 0 && off >= 0 && off + len <= dst.size && pos + len <= size) failed = true
        return ok
    }
}

/** Names of [FontRepairs]' files (pure, unit-tested): `<file>-<verdict>.font`, `.ok`, or `.tmp` while written. */
internal object RepairNames {
    const val FONT = ".font"
    const val OK = ".ok"
    const val TEMP = ".tmp"
    /** An asset's first bytes in its stamp: the sfnt header and table directory (up to 254 tables) fit. */
    const val STAMP_BYTES = 4096

    /**
     * An asset font's content identity: its [length] and a hash of its first [n] bytes ([head]), the table directory with
     * every table's checksum and length. Unlike the app's version or install time it changes only when the font does,
     * so an app update that keeps the font keeps its verdict (no new scan, no new copy).
     */
    fun assetStamp(length: Long, head: ByteArray, n: Int): String =
        "a$length:" + hex(MessageDigest.getInstance("SHA-1").apply { update(head, 0, n.coerceIn(0, head.size)) }.digest())

    /** One font file: an asset path or an absolute path. */
    fun sourceKey(asset: Boolean, path: String): String = (if (asset) "asset:" else "file:") + path

    /** A verdict's name without its extension: the file's part, then its [stamp] and the rules' [version]. */
    fun base(sourceKey: String, stamp: String, version: Int): String = prefix(sourceKey) + hash("$stamp|v$version")

    /** True for a file of [sourceKey] ([base] given: of that verdict only). */
    fun isOf(name: String, sourceKey: String, base: String? = null): Boolean =
        if (base != null) name.startsWith("$base.") else name.startsWith(prefix(sourceKey))

    private fun prefix(sourceKey: String): String = hash(sourceKey) + "-"

    /** 24 hex chars of the SHA-1. */
    private fun hash(s: String): String = hex(MessageDigest.getInstance("SHA-1").digest(s.toByteArray(Charsets.UTF_8)))

    /** The first 12 bytes of [d] in hex. */
    private fun hex(d: ByteArray): String {
        val hex = "0123456789abcdef"
        val out = CharArray(24)
        for (i in 0 until 12) {
            val v = d[i].toInt() and 0xFF
            out[i * 2] = hex[v ushr 4]
            out[i * 2 + 1] = hex[v and 0xF]
        }
        return String(out)
    }
}
