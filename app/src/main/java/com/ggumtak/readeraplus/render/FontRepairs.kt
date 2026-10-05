package com.ggumtak.readeraplus.render

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.ggumtak.readeraplus.BuildConfig
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
 * The first typeface build of a font file scans it once. When it maps characters to glyphs without an outline, a copy
 * whose cmap leaves them out is written here and the typeface loads the copy, so the system font draws them. The
 * verdict (that copy, or an empty `.ok` marker for a file that needs none) is named after the file's identity (an
 * asset: the app install; a user font: its size and mtime) and [VERSION] ([RepairNames]), so a later process start
 * stats one file per face; a new verdict deletes the file's older ones. The copies never leave the device and the
 * shipped assets stay unmodified (OFL; assets/fonts/licenses/FONTS.txt). 캐시 비우기 or the system may delete them at
 * any time: a typeface already loaded keeps working and the next build writes the copy again.
 */
internal object FontRepairs {
    private const val TAG = "FontRepairs"
    private const val DIR = "fonts-fixed"
    /**
     * Version of what [HollowGlyphs] drops: bump it (and `LayoutKeys.ALGO_VERSION`, since the dropped characters
     * measure differently) when that changes, so every verdict on disk is made again.
     */
    const val VERSION = 1

    private val locks = HashMap<String, Any>()
    @Volatile private var install: String? = null

    /**
     * The file a typeface of font file [path] ([source] BUNDLED: an asset path) should load: the repaired copy, or
     * null for the original (nothing to repair, or it could not be checked). Disk IO the first time per file and app
     * install, a stat or two after that. Any thread.
     */
    fun fileFor(ctx: Context, source: FontSource, path: String): File? {
        val key = RepairNames.sourceKey(source == FontSource.BUNDLED, path)
        val stamp = if (source == FontSource.BUNDLED) installStamp(ctx) else File(path).let { "${it.length()}:${it.lastModified()}" }
        val base = RepairNames.base(key, stamp, VERSION)
        val dir = File(ctx.cacheDir, DIR)
        val fixed = File(dir, base + RepairNames.FONT)
        val ok = File(dir, base + RepairNames.OK)
        val lock = synchronized(locks) { locks.getOrPut(base) { Any() } }
        synchronized(lock) {
            if (fixed.isFile) return fixed
            if (ok.isFile) return null
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
        val scan = try {
            scan(ctx, source, path)
        } catch (t: Throwable) {
            Log.w(TAG, "scan failed: $path", t)
            return null
        }
        if (!dir.isDirectory && !dir.mkdirs()) return null
        dir.listFiles()?.forEach { if (RepairNames.isOf(it.name, key) && !RepairNames.isOf(it.name, key, base)) it.delete() }
        if (scan == null || scan.hollow.isEmpty()) {
            try {
                ok.createNewFile()
            } catch (e: IOException) {
                Log.w(TAG, "no marker for $path: $e")
            }
            return null
        }
        val tmp = File(dir, base + RepairNames.TEMP)
        return try {
            open(ctx, source, path).use { input -> FileOutputStream(tmp).use { input.copyTo(it, 64 * 1024) } }
            RandomAccessFile(tmp, "rw").use { HollowGlyphs.patch(it, scan, HollowGlyphs.cmapWithout(scan)) }
            if (!tmp.renameTo(fixed)) throw IOException("rename failed")
            Log.i(TAG, "$path: ${scan.hollow.size} characters without an outline left to the system font, " +
                "${SystemClock.uptimeMillis() - started} ms")
            fixed
        } catch (t: Throwable) {
            tmp.delete()
            Log.w(TAG, "repair failed: $path", t)
            null
        }
    }

    private fun scan(ctx: Context, source: FontSource, path: String): HollowGlyphs.Scan? {
        if (source != FontSource.BUNDLED) return FileSfntSource(File(path)).use { HollowGlyphs.scan(it) }
        return try {
            // Fonts are stored uncompressed (noCompress): read the few tables in place, inside the APK.
            val afd = ctx.assets.openFd(path)
            afd.createInputStream().use { HollowGlyphs.scan(ChannelSfntSource(it.channel, afd.startOffset, afd.length)) }
        } catch (e: FileNotFoundException) {
            HollowGlyphs.scan(ByteArraySfntSource(ctx.assets.open(path).use { it.readBytes() }))
        }
    }

    private fun open(ctx: Context, source: FontSource, path: String): InputStream =
        if (source == FontSource.BUNDLED) ctx.assets.open(path) else FileInputStream(path)

    /** The app install an asset belongs to: the version code and the install / update time. */
    private fun installStamp(ctx: Context): String {
        install?.let { return it }
        val updated = try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).lastUpdateTime
        } catch (t: Throwable) {
            0L
        }
        return "${BuildConfig.VERSION_CODE}:$updated".also { install = it }
    }
}

/** Names of [FontRepairs]' files (pure, unit-tested): `<file>-<verdict>.font`, `.ok`, or `.tmp` while written. */
internal object RepairNames {
    const val FONT = ".font"
    const val OK = ".ok"
    const val TEMP = ".tmp"

    /** One font file: an asset path or an absolute path. */
    fun sourceKey(asset: Boolean, path: String): String = (if (asset) "asset:" else "file:") + path

    /** A verdict's name without its extension: the file's part, then its [stamp] and the rules' [version]. */
    fun base(sourceKey: String, stamp: String, version: Int): String = prefix(sourceKey) + hash("$stamp|v$version")

    /** True for a file of [sourceKey] ([base] given: of that verdict only). */
    fun isOf(name: String, sourceKey: String, base: String? = null): Boolean =
        if (base != null) name.startsWith("$base.") else name.startsWith(prefix(sourceKey))

    private fun prefix(sourceKey: String): String = hash(sourceKey) + "-"

    /** 24 hex chars of the SHA-1. */
    private fun hash(s: String): String {
        val d = MessageDigest.getInstance("SHA-1").digest(s.toByteArray(Charsets.UTF_8))
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
