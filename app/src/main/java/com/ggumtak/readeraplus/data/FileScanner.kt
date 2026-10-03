package com.ggumtak.readeraplus.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.util.Log
import com.ggumtak.readeraplus.format.BookFormat
import com.ggumtak.readeraplus.settings.Settings
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes

/**
 * Finds book files on storage and syncs them into [Library].
 *
 * The walk is iterative (no recursion), lists names only and stats just what it must: book-named entries
 * (size + mtime) and entries that might be directories (one lstat); obvious media files are skipped by name.
 * Metadata of new / changed files is read outside of transactions, then written in small batches so
 * readers (and the reader's position saves) are never blocked for long.
 */
object FileScanner {
    private const val TAG = "FileScanner"
    private const val PROGRESS_EVERY = 50
    private const val META_BATCH = 32
    private const val MAX_DEPTH = 40
    /** Inside one large directory the stop check also runs every this many entries. */
    private const val STOP_CHECK_EVERY = 64

    private val lock = Any()
    private val NEVER: () -> Boolean = { false }

    /**
     * Thrown by [scan] when its stop check said so. Nothing is half-written: the walk writes nothing, and the sync
     * stops while a metadata batch is still being read (each batch is written in its own transaction) or before any
     * deletion, so the next scan simply picks up what is left.
     */
    class Stopped : RuntimeException("scan stopped")

    /** Scans roots (AppSettings.scanFolders or primary storage), adds new files, drops vanished ones.
     *  Calls [progress] with the number of files found so far. Returns number of books in library.
     *  [stopWhen] is checked between directories and before each metadata parse (any thread; keep it cheap);
     *  when it returns true the scan ends with [Stopped]. [synced] learns whether the scan added, moved, refreshed
     *  or removed any book (false: the library's rows are as they were, so a list shown needs no reload). */
    fun scan(
        context: Context,
        stopWhen: () -> Boolean = NEVER,
        synced: (changed: Boolean) -> Unit = {},
        progress: (Int) -> Unit = {},
    ): Int = synchronized(lock) {
        Library.init(context)
        Settings.init(context)
        val primary = Library.primaryRoot()
        val app = Settings.app
        val configured = app.scanFolders.filter { it.isNotBlank() }
        val roots = if (configured.isNotEmpty()) {
            DataPaths.dedupeRoots(configured, primary)
        } else {
            DataPaths.dedupeRoots(defaultRoots(context).map { it.path }, primary)
        }
        val excluded = app.excludedFolders.filter { it.isNotBlank() }.map { DataPaths.normalize(it.trim(), primary) }
        val walk = walk(roots, excluded, stopWhen, progress)
        synced(sync(context, walk, excluded, stopWhen))
        Library.count()
    }

    /** Default roots when the user configured none. */
    fun defaultRoots(context: Context): List<File> {
        val primary = Library.primaryRoot()
        val candidates = ArrayList<String>()
        try {
            @Suppress("DEPRECATION")
            Environment.getExternalStorageDirectory()?.absolutePath?.let(candidates::add)
        } catch (_: Throwable) {
        }
        val appDirs = try {
            context.getExternalFilesDirs(null).map { it?.absolutePath }
        } catch (_: Throwable) {
            emptyList()
        }
        candidates += DataPaths.volumeRootsFromAppDirs(appDirs, primary)
        return DataPaths.dedupeRoots(candidates, primary).map(::File).filter {
            try {
                it.isDirectory
            } catch (_: Throwable) {
                false
            }
        }
    }

    // ---- walking ----

    internal class Found(val path: String, val name: String, val format: BookFormat, val size: Long, val mtime: Long)

    internal class WalkResult(
        val found: HashMap<String, Found>,
        /** Roots that could be listed (absence of files below them is meaningful). */
        val listedRoots: List<String>,
        /** Directories below the roots that could not be listed. */
        val unreadable: List<String>,
    )

    /**
     * Walks [roots] (normalised absolute paths) skipping [excluded] folders; pure java.io/nio. Throws [Stopped] when
     * [stopWhen] returns true before a directory is listed (or within a large one).
     */
    internal fun walk(
        roots: List<String>,
        excluded: List<String>,
        stopWhen: () -> Boolean = NEVER,
        progress: (Int) -> Unit,
    ): WalkResult = Walker(excluded, stopWhen, progress).run(roots)

    private class Dir(val path: String, val canonical: String, val depth: Int)

    private class Walker(
        private val excluded: List<String>,
        private val stopWhen: () -> Boolean,
        private val progress: (Int) -> Unit,
    ) {
        private val found = HashMap<String, Found>(256)
        private val visited = HashSet<String>(256)
        private val queue = ArrayDeque<Dir>()
        private val listedRoots = ArrayList<String>()
        private val unreadable = ArrayList<String>()
        private var lastReported = 0

        fun run(roots: List<String>): WalkResult {
            for (r in roots) {
                if (stopWhen()) throw Stopped()
                if (isExcluded(r)) continue
                val dir = File(r)
                val canonical = try {
                    dir.canonicalPath
                } catch (_: Throwable) {
                    r
                }
                if (!visited.add(canonical)) continue
                val names = safeList(dir) ?: continue
                listedRoots += r
                visitDir(Dir(r, canonical, 0), names)
                while (queue.isNotEmpty()) {
                    if (stopWhen()) throw Stopped()
                    val d = queue.removeFirst()
                    val list = safeList(File(d.path))
                    if (list == null) {
                        unreadable += d.path
                        continue
                    }
                    visitDir(d, list)
                }
            }
            report(force = true)
            return WalkResult(found, listedRoots, unreadable)
        }

        private fun visitDir(dir: Dir, names: Array<String>) {
            val base = if (dir.path.endsWith("/")) dir.path else dir.path + "/"
            val canonBase = if (dir.canonical.endsWith("/")) dir.canonical else dir.canonical + "/"
            for (i in names.indices) {
                if (i > 0 && i % STOP_CHECK_EVERY == 0 && stopWhen()) throw Stopped()
                val name = names[i]
                if (name.isEmpty() || name[0] == '.') continue
                val childPath = base + name
                val format = DataPaths.bookFormatOf(name)
                if (format != null) {
                    val f = File(childPath)
                    if (f.isFile) {
                        val size = f.length()
                        if (DataPaths.acceptSize(format, size)) {
                            found[childPath] = Found(childPath, name, format, size, f.lastModified())
                            report(force = false)
                        }
                        continue
                    }
                    // A directory named "*.epub" (unpacked book folder): fall through and descend.
                } else if (DataPaths.obviouslyNotBookFile(name)) {
                    continue
                }
                if (DataPaths.skipDir(dir.path, name) || dir.depth + 1 > MAX_DEPTH || isExcluded(childPath)) continue
                val canonical = when (dirKind(childPath)) {
                    KIND_DIR -> canonBase + name
                    KIND_LINKED_DIR -> try {
                        File(childPath).canonicalPath
                    } catch (_: Throwable) {
                        null
                    }
                    else -> null
                } ?: continue
                if (visited.add(canonical)) queue.addLast(Dir(childPath, canonical, dir.depth + 1))
            }
        }

        private fun isExcluded(path: String): Boolean {
            for (e in excluded) if (DataPaths.isUnder(path, e)) return true
            return false
        }

        private fun report(force: Boolean) {
            val n = found.size
            if (!force && n - lastReported < PROGRESS_EVERY) return
            lastReported = n
            try {
                progress(n)
            } catch (t: Throwable) {
                Log.w(TAG, "progress callback failed", t)
            }
        }
    }

    private const val KIND_OTHER = 0
    private const val KIND_DIR = 1
    /** A symlink to a directory (or an entry whose type could only be read by following links). */
    private const val KIND_LINKED_DIR = 2

    /** One lstat: plain directory, symlinked directory or something else. */
    private fun dirKind(path: String): Int {
        try {
            val a = Files.readAttributes(Paths.get(path), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            return when {
                a.isDirectory -> KIND_DIR
                a.isSymbolicLink -> if (File(path).isDirectory) KIND_LINKED_DIR else KIND_OTHER
                else -> KIND_OTHER
            }
        } catch (_: Throwable) {
            // No nio attributes (odd name / provider): follow links and canonicalise to stay loop-safe.
            return try {
                if (File(path).isDirectory) KIND_LINKED_DIR else KIND_OTHER
            } catch (_: Throwable) {
                KIND_OTHER
            }
        }
    }

    private fun safeList(dir: File): Array<String>? = try {
        dir.list()
    } catch (_: Throwable) {
        null
    }

    // ---- syncing ----

    /** Library state of one entry, as the scanner needs it. */
    internal class Known(
        val id: Long,
        val path: String,
        val size: Long,
        val mtime: Long,
        val trashed: Boolean,
        val fileName: String,
        /** > 0: trashed by a scan because the file vanished while the book had notes (revived when it is back). */
        val missingAt: Long = 0,
    )

    internal class SyncPlan(
        /** Files to write, sorted by path: (file, entry to refresh / re-point, or null for a new entry). */
        val todo: List<Pair<Found, Known?>>,
        /** Entries to delete (vanished and not moved, or excluded without user data). */
        val gone: List<Long>,
        /** "Removed from library" marks whose files are gone. */
        val deadIgnored: List<String>,
        /** Vanished (not moved) entries with notes: moved to the trash as missing ([LibrarySql.SET_MISSING]). */
        val trash: List<Long> = emptyList(),
        /** Missing entries whose file is back, at its path or moved: out of the trash ([LibrarySql.CLEAR_MISSING]). */
        val revive: List<Long> = emptyList(),
    ) {
        /** Whether applying this plan changes any book row (the "removed" marks are not shown anywhere). */
        val changesBooks: Boolean
            get() = todo.isNotEmpty() || gone.isNotEmpty() || trash.isNotEmpty() || revive.isNotEmpty()
    }

    /**
     * Decides what a scan changes (pure; IO comes in through [vanished], [userDataIds] and [noteIds]). Notes are never
     * lost silently (N §5.5):
     * - Trashed entries are kept until the trash is emptied; a user's own 휴지통 entry (`missingAt = 0`) is never
     *   revived, re-pointed or marked missing.
     * - A vanished entry whose file name and size match a new file was moved: it is re-pointed (history kept).
     * - Other vanished entries with notes ([noteIds]) go to the trash as missing ([SyncPlan.trash]); the rest are
     *   dropped.
     * - A missing entry (`missingAt > 0`) whose file is found again at its path, or matched as moved, is revived
     *   ([SyncPlan.revive]); while its file stays away it is left as it is.
     * - Existing files under an excluded folder are dropped unless the entry carries user data
     *   ([userDataIds], only queried when needed): excluding a folder must not destroy bookmarks / quotes.
     * [userDataIds] and [noteIds] are only called when some entry needs them, so a scan that finds nothing gone runs
     * no extra statement.
     */
    internal fun plan(
        known: Collection<Known>,
        found: Map<String, Found>,
        ignored: Set<String>,
        excluded: List<String>,
        vanished: (String) -> Boolean,
        userDataIds: () -> Set<Long>,
        noteIds: () -> Set<Long> = { emptySet() },
    ): SyncPlan {
        val vanishedBooks = ArrayList<Known>()
        val excludedBooks = ArrayList<Known>()
        val revive = ArrayList<Long>()
        val byPath = HashMap<String, Known>(known.size * 2)
        for (k in known) {
            byPath[k.path] = k
            val missing = k.missingAt > 0
            if (found.containsKey(k.path)) {
                if (missing && k.path !in ignored) revive += k.id
                continue
            }
            if (k.trashed && !missing) continue
            when {
                // First: a file moved out of an excluded folder is still a move.
                vanished(k.path) -> vanishedBooks += k
                // A missing entry stays as it is (trashed, with its notes) until its file is back.
                missing -> {}
                excluded.any { DataPaths.isUnder(k.path, it) } -> excludedBooks += k
            }
        }
        val movable = HashMap<String, ArrayDeque<Known>>()
        for (k in vanishedBooks) movable.getOrPut(moveKey(k.fileName, k.size)) { ArrayDeque() }.addLast(k)

        val todo = ArrayList<Pair<Found, Known?>>()
        val moved = HashSet<Long>()
        for (f in found.values) {
            if (f.path in ignored) continue
            val k = byPath[f.path]
            if (k != null) {
                if (k.size != f.size || k.mtime != f.mtime) todo += f to k
                continue
            }
            val from = movable[moveKey(f.name, f.size)]?.removeFirstOrNull()
            if (from != null) {
                moved += from.id
                if (from.missingAt > 0) revive += from.id
            }
            todo += f to from
        }
        todo.sortBy { it.first.path }

        val gone = ArrayList<Long>()
        val trash = ArrayList<Long>()
        var withNotes: Set<Long>? = null
        for (k in vanishedBooks) {
            if (k.id in moved) continue
            // Already in the trash as missing: nothing to do until the file is back. (Missing but not trashed: an R2
            // build's 복원 left it so; it is judged again like any vanished entry.)
            if (k.missingAt > 0 && k.trashed) continue
            val notes = withNotes ?: noteIds().also { withNotes = it }
            if (k.id in notes) trash += k.id else gone += k.id
        }
        if (excludedBooks.isNotEmpty()) {
            val keep = userDataIds()
            for (k in excludedBooks) if (k.id !in keep) gone += k.id
        }
        val deadIgnored = ignored.filter { !found.containsKey(it) && vanished(it) }
        revive.sort()
        return SyncPlan(todo, gone, deadIgnored, trash, revive)
    }

    /** Applies the scan to the library; returns [SyncPlan.changesBooks]. */
    private fun sync(context: Context, walk: WalkResult, excluded: List<String>, stopWhen: () -> Boolean): Boolean {
        val db = Library.db()
        val known = db.queryList(LibrarySql.SELECT_SCAN_STATE, null) { c ->
            Known(
                c.getLong(0), c.getString(1) ?: "", c.getLong(2), c.getLong(3), c.getInt(4) != 0, c.getString(5) ?: "",
                c.getLong(7),
            )
        }
        val ignored = HashSet<String>(db.queryList(LibrarySql.SELECT_IGNORED, null) { it.getString(0) ?: "" })

        val trusted = absenceTrust(context)
        fun vanished(path: String): Boolean {
            if (!trusted(path)) return false
            if (walk.listedRoots.any { DataPaths.isUnder(path, it) }) {
                if (walk.unreadable.any { DataPaths.isUnder(path, it) }) return false
                return !File(path).exists()
            }
            // Outside the scanned roots: only when its folder is there (volume mounted) but the file is not.
            val parent = File(path).parentFile ?: return false
            return parent.isDirectory && !File(path).exists()
        }
        val plan = plan(
            known, walk.found, ignored, excluded, ::vanished,
            userDataIds = { db.queryList(LibrarySql.SELECT_IDS_WITH_USER_DATA, null) { it.getLong(0) }.toHashSet() },
            noteIds = { db.queryList(LibrarySql.SELECT_IDS_WITH_NOTES, null) { it.getLong(0) }.toHashSet() },
        )
        val reviving = plan.revive.toHashSet()

        // New, moved or changed files: metadata read outside transactions, writes in small batches.
        val todo = plan.todo
        var i = 0
        while (i < todo.size) {
            if (stopWhen()) throw Stopped()
            val end = minOf(i + META_BATCH, todo.size)
            val batch = todo.subList(i, end)
            // A move of an unchanged file needs no parsing. Each parse (an EPUB's zip + OPF: tens of ms on the Comet)
            // checks for a stop first; nothing of this batch is written yet.
            val metas = batch.map { (f, k) ->
                if (k != null && k.path != f.path && k.mtime == f.mtime) {
                    null
                } else {
                    if (stopWhen()) throw Stopped()
                    Library.readMeta(File(f.path))
                }
            }
            db.inTransaction {
                for (j in batch.indices) {
                    val (f, k) = batch[j]
                    val info = FileInfo(f.path, f.name, f.format, f.size, f.mtime)
                    val meta = metas[j]
                    if (k != null && k.path != f.path) {
                        Library.moveFile(this, k.id, info, meta)
                    } else {
                        Library.writeFile(this, info, meta ?: Library.readMeta(File(f.path)), k?.id)
                    }
                    // Revived with its (re-pointed / refreshed) file, in the same transaction.
                    if (k != null && k.id in reviving) exec(LibrarySql.CLEAR_MISSING, k.id)
                }
            }
            Library.notesChanged()
            i = end
        }

        // Missing entries back unchanged at their own path (no todo entry): revive them now.
        val written = HashSet<Long>()
        for ((_, k) in todo) if (k != null) written += k.id
        val reviveOnly = plan.revive.filter { it !in written }
        val now = System.currentTimeMillis()
        if (plan.gone.isNotEmpty() || plan.deadIgnored.isNotEmpty() || plan.trash.isNotEmpty() || reviveOnly.isNotEmpty()) {
            if (stopWhen()) throw Stopped()
            db.inTransaction {
                for (id in reviveOnly) exec(LibrarySql.CLEAR_MISSING, id)
                // Notes are never lost silently: a vanished book with notes goes to the trash as missing.
                for (id in plan.trash) exec(LibrarySql.SET_MISSING, now, id)
                for (id in plan.gone) Library.deleteBookRows(this, id)
                for (p in plan.deadIgnored) exec(LibrarySql.DELETE_IGNORED, p)
            }
            if (plan.gone.isNotEmpty() || plan.trash.isNotEmpty() || reviveOnly.isNotEmpty()) Library.notesChanged()
            for (id in plan.gone) Library.invalidateCover(id)
        }
        return plan.changesBooks
    }

    /**
     * Whether a missing file really is gone: only with full storage access (scoped storage hides non-media
     * files, which would otherwise look deleted) or inside the app's own folders.
     */
    internal fun absenceTrust(context: Context): (String) -> Boolean {
        if (hasAllFilesAccess(context)) return { true }
        val own = ownDirs(context)
        return { path -> own.any { DataPaths.isUnder(path, it) } }
    }

    /**
     * The entry a newly seen file at [newPath] was moved from: the first candidate (same name and size) whose
     * own file is missing, when that absence can be trusted. Null when none.
     */
    internal fun <T> movedFrom(
        candidates: List<T>,
        pathOf: (T) -> String,
        newPath: String,
        trusted: (String) -> Boolean,
        exists: (String) -> Boolean,
    ): T? = candidates.firstOrNull { c ->
        val p = pathOf(c)
        p != newPath && p.isNotEmpty() && trusted(p) && !exists(p)
    }

    private fun moveKey(fileName: String, size: Long): String = "$fileName\u0000$size"

    /** All-files access (API 30+), else the legacy storage permission: absent files then really are gone. */
    internal fun hasAllFilesAccess(context: Context): Boolean = try {
        if (Build.VERSION.SDK_INT >= 30) {
            Environment.isExternalStorageManager()
        } else {
            context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    } catch (_: Throwable) {
        false
    }

    /** The app's private folders (always fully visible to us). */
    private fun ownDirs(context: Context): List<String> {
        val out = ArrayList<String>()
        try {
            context.getExternalFilesDirs(null).forEach { d -> d?.parentFile?.absolutePath?.let { out += Library.normalizePath(it) } }
        } catch (_: Throwable) {
        }
        try {
            context.filesDir?.parentFile?.absolutePath?.let { out += it }
        } catch (_: Throwable) {
        }
        return out
    }
}
