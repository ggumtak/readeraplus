package com.ggumtak.readeraplus.render

import android.annotation.TargetApi
import android.content.Context
import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.OpenableColumns
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.text.Collator
import java.util.Locale

/**
 * Font registry: bundled asset fonts + user-imported fonts (files/fonts) + fonts in /sdcard/Fonts.
 *
 * [init] only records the context and the built-in entries (no disk access). User folders are listed on the
 * first call that needs them and each font file's `name`/`fvar` tables are read lazily (cached by path, size
 * and mtime). Typefaces are created on demand and cached, so paints always get the same instance.
 * Thread-safe; [fonts], [importFont] and [refresh] do IO and belong on a background thread.
 */
object FontManager {
    private const val TAG = "FontManager"
    private const val USER_DIR = "fonts"
    private const val MAX_IMPORT_BYTES = 128L * 1024 * 1024

    private val lock = Any()
    @Volatile private var app: Context? = null

    /** Bundled + system entries (static data, available even before [init]). */
    private val builtIn: Map<String, FontInfo> = LinkedHashMap<String, FontInfo>().apply {
        for (b in FontCatalog.BUNDLED) put(b.id, FontInfo(b.id, b.name, FontSource.BUNDLED, b.regular, b.bold, false, b.serif))
        put(FontCatalog.SYSTEM_SERIF, FontInfo(FontCatalog.SYSTEM_SERIF, "시스템 명조", FontSource.SYSTEM, "", null, false, true))
        put(FontCatalog.SYSTEM_SANS, FontInfo(FontCatalog.SYSTEM_SANS, "시스템 고딕", FontSource.SYSTEM, "", null, false, false))
    }

    private class UserFile(val id: String, val file: File, val bold: File?, val size: Long, val mtime: Long) {
        @Volatile var info: FontInfo? = null
        @Volatile var parsed = false

        fun sameAs(o: UserFile): Boolean =
            size == o.size && mtime == o.mtime && file.path == o.file.path && bold?.path == o.bold?.path
    }

    /** User folder listing state: a scan that started before an import/delete/refresh never commits. */
    private val scanGate = RescanGate()
    private var userFiles: Map<String, UserFile> = emptyMap()
    /** "path|size|mtime" → parsed info (null value = not a usable font). Survives rescans. */
    private val parsedCache = HashMap<String, SfntInfo?>()
    private val typefaces = HashMap<String, Typeface>()
    /** One lock object per typeface cache key: concurrent requests for the same face build it once. */
    private val buildLocks = HashMap<String, Any>()
    /**
     * Font file path → whether its typefaces load from the [FontRepairs] copy (true) or the file itself (false), as built
     * in this process: what [layoutTag] reports, so cached page counts follow the glyph widths actually measured.
     */
    private val loadedRepaired = HashMap<String, Boolean>()

    /** Records the application context (cheap; no disk access). Called from App.onCreate. */
    fun init(context: Context) {
        val a = context.applicationContext ?: context
        app = a
        RenderContext.app = a
    }

    /** All selectable fonts: bundled (catalog order), user fonts (by name), then the system faces. */
    fun fonts(): List<FontInfo> {
        ensureScanned()
        val users = synchronized(lock) { ArrayList(userFiles.values) }
        val userInfos = ArrayList<FontInfo>(users.size)
        for (u in users) userInfo(u)?.let { userInfos.add(it) }
        val collator = Collator.getInstance(Locale.KOREAN)
        userInfos.sortWith { a, b -> collator.compare(a.name, b.name) }
        val out = ArrayList<FontInfo>(builtIn.size + userInfos.size)
        for (b in FontCatalog.BUNDLED) builtIn[b.id]?.let { out.add(it) }
        // Same display name twice (e.g. two copies in different folders): tell them apart by file name.
        val seen = HashSet<String>()
        for (b in out) seen.add(b.name)
        for (u in userInfos) {
            if (seen.add(u.name)) {
                out.add(u)
            } else {
                val fileName = u.id.removePrefix(FontFiles.USER_PREFIX)
                out.add(FontInfo(u.id, "${u.name} ($fileName)", u.source, u.path, u.boldPath, u.variable, u.serif, u.naturalWeight))
            }
        }
        builtIn[FontCatalog.SYSTEM_SERIF]?.let { out.add(it) }
        builtIn[FontCatalog.SYSTEM_SANS]?.let { out.add(it) }
        return out
    }

    /**
     * Font entry for [id], or null if unknown / no longer present / not a readable font file. Bundled and system ids
     * are a map lookup. A `user:` id's first lookup lists the font folders and reads that file's name table (disk IO):
     * the reader makes it in its open IO block (A12-2), so later lookups on the main thread (layout key, renderer,
     * typeface) only find cached entries. Any thread.
     */
    fun font(id: String): FontInfo? {
        builtIn[id]?.let { return it }
        if (!id.startsWith(FontFiles.USER_PREFIX)) return null
        ensureScanned()
        val uf = synchronized(lock) { userFiles[id] } ?: return null
        return userInfo(uf)
    }

    /** Typeface for [id] at [weight] (100..900) and [italic]; cached. Falls back to the default font. */
    fun typeface(id: String, weight: Int = 400, italic: Boolean = false): Typeface {
        val info = resolve(id)
        val w = FontMath.normalizeWeight(weight)
        val key = cacheKey(info, w, italic)
        val keyLock = synchronized(lock) {
            typefaces[key]?.let { return it }
            buildLocks.getOrPut(key) { Any() }
        }
        // The reader's font warm-up, layout and page-count threads ask for the same face at once when a book
        // opens: build it once while the others wait for the result.
        synchronized(keyLock) {
            synchronized(lock) { typefaces[key]?.let { return it } }
            val built = build(info, w, italic)
            // Before init() nothing can be loaded: answer with a system face but don't cache that answer.
            if (built == null && app == null && info.source != FontSource.SYSTEM) return systemTypeface(info.serif, w, italic)
            val tf = built
                ?: if (info.id != FontCatalog.DEFAULT_ID) typeface(FontCatalog.DEFAULT_ID, weight, italic) else systemTypeface(true, w, italic)
            synchronized(lock) { typefaces[key] = tf }
            return tf
        }
    }

    /**
     * The typeface [typeface] would answer for [id] at [weight] if it is built already, else null: no IO, for the main
     * thread (a label in the font's own face). A user font must have been looked up before ([font]).
     */
    fun cachedTypeface(id: String, weight: Int = 400, italic: Boolean = false): Typeface? {
        val info = resolve(id)
        val key = cacheKey(info, FontMath.normalizeWeight(weight), italic)
        return synchronized(lock) { typefaces[key] }
    }

    /**
     * Checks font [id]'s regular and bold files for blank glyphs ([FontRepairs]) so the first page or the settings popup
     * never waits for that on first use: the reader calls it on a background thread next to its font warm-up. Disk IO
     * the first time per file, a stat or two after that.
     */
    fun prepare(id: String) {
        val info = resolve(id)
        if (info.source == FontSource.SYSTEM) return
        val ctx = app ?: return
        for (path in listOfNotNull(info.path, info.boldPath)) {
            try {
                FontRepairs.fileFor(ctx, info.source, path)
            } catch (t: Throwable) {
                Log.w(TAG, "font check failed: $path", t)
            }
        }
    }

    /**
     * What font [id]'s blank-glyph repairs ([FontRepairs]) add to the page-count key: "" when neither file needs one
     * (its glyph widths are those of the file, as before the repairs existed, so its cached counts stay valid), else
     * the repair rules' version and which files load from a repaired copy. A file not built in this process yet
     * answers with the verdict its build would use. Disk IO the first time per file (background threads only).
     */
    fun layoutTag(id: String): String {
        val info = resolve(id)
        if (info.source == FontSource.SYSTEM) return ""
        val ctx = app ?: return ""
        val sb = StringBuilder()
        for ((i, path) in listOfNotNull(info.path, info.boldPath).withIndex()) {
            val used = synchronized(lock) { loadedRepaired[path] } ?: try {
                FontRepairs.fileFor(ctx, info.source, path) != null
            } catch (t: Throwable) {
                false
            }
            if (used) sb.append(if (i == 0) 'r' else 'b')
        }
        return FontMath.repairTag(sb.toString(), FontRepairs.VERSION)
    }

    /**
     * Lowest weight that looks different from 400 in font [id] (100 for variable and system fonts, 400 for static
     * files). The weight slider should start here; lighter settings render as 400 (see [FontMath.effectiveBase]).
     */
    fun minWeight(id: String): Int {
        val info = resolve(id)
        return FontMath.minWeight(info.variable, info.source == FontSource.SYSTEM)
    }

    /** The weight font [id] shows as is (굵기 "기본"); 400 when it can't be resolved. */
    fun naturalWeight(id: String): Int = runCatching { resolve(id).naturalWeight }.getOrDefault(FontMath.REGULAR)

    /** Extra synthetic stroke width (px) to emulate [weight] for a static font at [textSizePx]; 0 if not needed. */
    fun syntheticStroke(id: String, weight: Int, textSizePx: Float): Float {
        val info = resolve(id)
        if (info.variable) return 0f
        val w = FontMath.normalizeWeight(weight)
        val boldUsed = if (info.source == FontSource.SYSTEM) w >= 600 else FontMath.usesBoldFile(w, info.boldPath != null)
        return FontMath.syntheticStroke(w, boldUsed, textSizePx)
    }

    /** Copies a .ttf/.otf from [uri] into app storage and registers it. */
    fun importFont(context: Context, uri: Uri): FontInfo {
        if (app == null) init(context)
        val cr = context.contentResolver
        var display: String? = null
        try {
            cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (i >= 0) display = c.getString(i)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "display name query failed", e)
        }
        if (display.isNullOrBlank()) display = uri.lastPathSegment
        val dir = File(context.filesDir, USER_DIR)
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("글꼴 폴더를 만들 수 없습니다")
        val tmp = File(dir, ".import-${System.nanoTime()}.tmp")
        val header = ByteArray(12)
        try {
            val input = cr.openInputStream(uri) ?: throw IOException("파일을 읽지 못했습니다")
            input.use {
                val n = readUpTo(it, header)
                if (n < 12 || !SfntReader.looksLikeSfnt(header, n)) {
                    throw IllegalArgumentException("TTF/OTF 글꼴 파일이 아닙니다")
                }
                FileOutputStream(tmp).use { out ->
                    out.write(header, 0, n)
                    copyLimited(it, out, MAX_IMPORT_BYTES - n)
                }
            }
            val sfnt = SfntReader.parse(tmp) ?: throw IllegalArgumentException("글꼴 파일이 손상되었습니다")
            val name = FontFiles.sanitizeFileName(display, header, System.currentTimeMillis())
            val target = File(dir, name)
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) {
                FileInputStream(tmp).use { i -> FileOutputStream(target).use { o -> i.copyTo(o, 64 * 1024) } }
            }
            val id = FontFiles.userId(name)
            synchronized(lock) { dropTypefaces(id) }
            scanGate.invalidate()
            ensureScanned()
            return font(id) ?: FontInfo(
                id, sfnt.displayName ?: target.nameWithoutExtension, FontSource.USER, target.absolutePath,
                null, sfnt.variable, FontFiles.serifGuess(sfnt.displayName ?: name, sfnt.sansHint),
                FontMath.naturalWeight(sfnt.variable, sfnt.wghtDefault),
            )
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    /** Deletes a user font file (only fonts imported into app storage; other folders are left alone). */
    fun deleteUserFont(id: String) {
        if (!id.startsWith(FontFiles.USER_PREFIX)) return
        val ctx = app ?: return
        ensureScanned()
        val uf = synchronized(lock) { userFiles[id] } ?: return
        val dir = File(ctx.filesDir, USER_DIR)
        if (!isDirectChild(uf.file, dir)) return
        if (!uf.file.delete() && uf.file.exists()) Log.w(TAG, "could not delete ${uf.file}")
        FontRepairs.forget(ctx, uf.file.path)
        synchronized(lock) { dropTypefaces(id) }
        scanGate.invalidate()
    }

    /** Re-scans user font folders. */
    fun refresh(context: Context) {
        if (app == null) init(context)
        scanGate.invalidate()
        ensureScanned()
    }

    // ---------------------------------------------------------------------------------------------

    private fun resolve(id: String): FontInfo = font(id) ?: builtIn.getValue(FontCatalog.DEFAULT_ID)

    private fun cacheKey(info: FontInfo, w: Int, italic: Boolean): String = when {
        info.variable -> "${info.id}|v$w|$italic"
        info.source == FontSource.SYSTEM -> "${info.id}|w$w|$italic"
        else -> "${info.id}|${if (FontMath.usesBoldFile(w, info.boldPath != null)) "b" else "r"}|$italic"
    }

    private fun build(info: FontInfo, w: Int, italic: Boolean): Typeface? {
        if (info.source == FontSource.SYSTEM) {
            return try {
                systemTypeface(info.serif, w, italic)
            } catch (t: Throwable) {
                null
            }
        }
        val ctx = app ?: return null
        val bold = info.boldPath
        val useBold = !info.variable && bold != null && FontMath.usesBoldFile(w, true)
        var base = if (useBold) buildFile(ctx, info, bold!!, w) else null
        if (base == null) base = buildFile(ctx, info, info.path, w)
        if (base == null) return null
        return if (italic) {
            try {
                // Relative ITALIC keeps the face's own weight (no extra fake bold); Minikin slants it if needed.
                Typeface.create(base, Typeface.ITALIC)
            } catch (t: Throwable) {
                base
            }
        } else {
            base
        }
    }

    /**
     * One font file as a typeface with the system fallback chain behind it ([FontMath.SYSTEM_FALLBACK]). A file that
     * maps characters to blank glyphs loads from its repaired copy ([FontRepairs]), so the fallback draws those too.
     * Measuring and drawing share the typeface, so they agree.
     */
    private fun buildFile(ctx: Context, info: FontInfo, path: String, w: Int): Typeface? {
        val fixed = try {
            FontRepairs.fileFor(ctx, info.source, path)
        } catch (t: Throwable) {
            Log.w(TAG, "font check failed: $path", t)
            null
        }
        // A repaired copy that doesn't load (damaged since) leaves the original file, blanks and all.
        if (fixed != null) {
            buildFace(ctx, info, path, fixed, w)?.let {
                synchronized(lock) { loadedRepaired[path] = true }
                return it
            }
        }
        return buildFace(ctx, info, path, null, w)?.also { synchronized(lock) { loadedRepaired[path] = false } }
    }

    private fun buildFace(ctx: Context, info: FontInfo, path: String, fixed: File?, w: Int): Typeface? = try {
        if (Build.VERSION.SDK_INT >= 29) buildWithFallback(ctx, info, path, fixed, w) else buildLegacy(ctx, info, path, fixed, w)
    } catch (t: Throwable) {
        Log.w(TAG, "typeface build failed: ${fixed ?: path}", t)
        null
    }

    /** API 29+: what `Typeface.Builder` builds, with the fallback chain named explicitly. Throws when the file can't load. */
    @TargetApi(29)
    private fun buildWithFallback(ctx: Context, info: FontInfo, path: String, fixed: File?, w: Int): Typeface {
        val b = when {
            fixed != null -> Font.Builder(fixed)
            info.source == FontSource.BUNDLED -> Font.Builder(ctx.assets, path)
            else -> Font.Builder(File(path))
        }
        if (info.variable) {
            b.setFontVariationSettings("'wght' $w")
            b.setWeight(w)
        }
        val font = b.build()
        return Typeface.CustomFallbackBuilder(FontFamily.Builder(font).build())
            .setStyle(font.style)
            .setSystemFallback(FontMath.SYSTEM_FALLBACK)
            .build()
    }

    /**
     * API 26–28: `Typeface.Builder`, built once. Its default chain is [FontMath.SYSTEM_FALLBACK] already; build() answers
     * null for a file that can't load.
     */
    private fun buildLegacy(ctx: Context, info: FontInfo, path: String, fixed: File?, w: Int): Typeface? {
        val b = when {
            fixed != null -> Typeface.Builder(fixed)
            info.source == FontSource.BUNDLED -> Typeface.Builder(ctx.assets, path)
            else -> Typeface.Builder(File(path))
        }
        if (info.variable) {
            b.setFontVariationSettings("'wght' $w")
            b.setWeight(w)
        }
        return b.build()
    }

    private fun systemTypeface(serif: Boolean, w: Int, italic: Boolean): Typeface {
        val base = if (serif) Typeface.SERIF else Typeface.SANS_SERIF
        return if (Build.VERSION.SDK_INT >= 28) {
            Typeface.create(base, w, italic)
        } else {
            Typeface.create(base, (if (w >= 600) Typeface.BOLD else 0) or (if (italic) Typeface.ITALIC else 0))
        }
    }

    /** Forgets font [id]'s typefaces (and, for a user font, which of its files loaded repaired). Under [lock]. */
    private fun dropTypefaces(id: String) {
        val prefix = "$id|"
        val it = typefaces.keys.iterator()
        while (it.hasNext()) if (it.next().startsWith(prefix)) it.remove()
        userFiles[id]?.let { u ->
            loadedRepaired.remove(u.file.absolutePath)
            u.bold?.let { b -> loadedRepaired.remove(b.absolutePath) }
        }
    }

    private fun userInfo(uf: UserFile): FontInfo? {
        uf.info?.let { return it }
        if (uf.parsed) return null
        val key = "${uf.file.path}|${uf.size}|${uf.mtime}"
        var known = false
        var sfnt: SfntInfo? = null
        synchronized(lock) {
            if (parsedCache.containsKey(key)) {
                known = true
                sfnt = parsedCache[key]
            }
        }
        if (!known) {
            sfnt = SfntReader.parse(uf.file)
            synchronized(lock) { parsedCache[key] = sfnt }
        }
        val s = sfnt
        val info = if (s == null) {
            null
        } else {
            val name = s.displayName ?: uf.file.nameWithoutExtension
            FontInfo(
                id = uf.id,
                name = name,
                source = FontSource.USER,
                path = uf.file.absolutePath,
                boldPath = if (s.variable) null else uf.bold?.absolutePath,
                variable = s.variable,
                serif = FontFiles.serifGuess(name, s.sansHint),
                naturalWeight = FontMath.naturalWeight(s.variable, s.wghtDefault),
            )
        }
        uf.info = info
        uf.parsed = true
        return info
    }

    private fun ensureScanned() {
        if (scanGate.isCurrent) return
        val ctx = app ?: return
        scanGate.ensure({ scanDirs(ctx) }) { found ->
            synchronized(lock) {
                val old = userFiles
                for ((id, o) in old) {
                    val n = found[id]
                    if (n == null || !n.sameAs(o)) {
                        dropTypefaces(id)
                    } else {
                        n.info = o.info
                        n.parsed = o.parsed
                    }
                }
                userFiles = found
            }
        }
    }

    private fun userDirs(ctx: Context): List<File> {
        val dirs = ArrayList<File>(3)
        dirs.add(File(ctx.filesDir, USER_DIR))
        try {
            @Suppress("DEPRECATION")
            val ext = Environment.getExternalStorageDirectory()
            if (ext != null) {
                dirs.add(File(ext, "Fonts"))
                dirs.add(File(ext, "fonts"))
            }
        } catch (t: Throwable) {
            // no external storage
        }
        return dirs
    }

    private fun scanDirs(ctx: Context): LinkedHashMap<String, UserFile> {
        val result = LinkedHashMap<String, UserFile>()
        val seenDirs = HashSet<String>()
        for (dir in userDirs(ctx)) {
            try {
                if (!dir.isDirectory) continue
                val canon = try { dir.canonicalPath } catch (e: IOException) { dir.absolutePath }
                // External storage is case-insensitive: /sdcard/Fonts and /sdcard/fonts are one folder.
                if (!seenDirs.add(canon.lowercase(Locale.ROOT))) continue
                val files = dir.listFiles() ?: continue
                val names = ArrayList<String>(files.size)
                for (f in files) names.add(f.name)
                val index = FontFiles.lowerIndex(names)
                for (f in files) {
                    val name = f.name
                    if (!FontFiles.isFontFileName(name) || !f.isFile) continue
                    val len = f.length()
                    if (len < 12) continue
                    val id = FontFiles.userId(name)
                    if (result.containsKey(id)) continue
                    val boldName = FontFiles.findBoldSibling(name, index)
                    result[id] = UserFile(id, File(dir, name), boldName?.let { File(dir, it) }, len, f.lastModified())
                }
            } catch (t: Throwable) {
                Log.w(TAG, "font folder scan failed: $dir", t)
            }
        }
        return result
    }

    private fun isDirectChild(file: File, dir: File): Boolean = try {
        file.canonicalFile.parentFile == dir.canonicalFile
    } catch (e: IOException) {
        false
    }

    private fun readUpTo(input: InputStream, buf: ByteArray): Int {
        var n = 0
        while (n < buf.size) {
            val r = input.read(buf, n, buf.size - n)
            if (r < 0) break
            n += r
        }
        return n
    }

    private fun copyLimited(input: InputStream, out: FileOutputStream, limit: Long) {
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val r = input.read(buf)
            if (r < 0) break
            total += r
            if (total > limit) throw IllegalArgumentException("글꼴 파일이 너무 큽니다")
            out.write(buf, 0, r)
        }
    }
}

/**
 * "List once, list again after invalidation" guard (unit-tested). [ensure] runs the listing outside any lock and
 * commits it only if [invalidate] was not called meanwhile; otherwise it lists again. This keeps a listing that
 * started before an import (and so misses the new file) from being committed after the import's own rescan.
 */
internal class RescanGate {
    @Volatile private var gen = 0
    @Volatile private var committedGen = -1

    /** True when the last committed listing is still valid. */
    val isCurrent: Boolean get() = committedGen == gen

    /** Marks the committed listing stale. */
    fun invalidate() {
        synchronized(this) { gen++ }
    }

    /** Makes the committed listing current: [list] (unlocked, may repeat) then [commit] (under this gate's lock). */
    fun <T> ensure(list: () -> T, commit: (T) -> Unit) {
        while (true) {
            val g = gen
            if (committedGen == g) return
            val found = list()
            val done = synchronized(this) {
                when {
                    gen != g -> false // invalidated while listing: the result may be stale, list again
                    committedGen == g -> true // another thread committed this generation meanwhile
                    else -> {
                        commit(found)
                        committedGen = g
                        true
                    }
                }
            }
            if (done) return
        }
    }
}
