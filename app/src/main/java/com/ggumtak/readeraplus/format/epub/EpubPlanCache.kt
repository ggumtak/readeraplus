package com.ggumtak.readeraplus.format.epub

import com.ggumtak.readeraplus.format.Documents
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * Section plan of an EPUB's oversized spine items (A12-1), persisted under `Documents.cacheDir/epubplan/<hash>.bin`
 * so a reopen skips the text scan of every item above [EpubSplit.SCAN_MIN_BYTES] (`EpubBook.planSections`).
 *
 * - Key `"v$VERSION|path|size|mtime"`; the file repeats the key, the spine size, the scanned items and a hash of
 *   the TOC anchors they were scanned for, and is used only when all of them still agree.
 * - [load] runs on the opening thread, and only for a book that has such an item (a small EPUB never pays for a
 *   cache-miss file open). A fresh plan is not written there: [stage] keeps its bytes and [writePending] writes them
 *   after the first page (`Documents.writeDeferredCaches`, from the reader's `afterOpen` on its IO thread).
 * - Newest [KEEP_FILES] files are kept, as with the TXT index. All failures are silent (cache only).
 */
internal object EpubPlanCache {
    /**
     * Bump whenever [EpubSplit.partsFor], [EpubSplit.scan] or [EpubSplit.assign] can give a different result for the
     * same item: a stale plan would cut sections differently from a fresh one. `EpubPlanCacheTest`'s golden values
     * fail when they change.
     */
    const val VERSION = 1
    private const val MAGIC = 0x52504550 // "RPEP"
    private const val MAX_FILES = 300
    private const val KEEP_FILES = 200
    /** Staged writes kept (the reader writes them after every open; anything else that opens EPUBs can't pile up). */
    private const val MAX_PENDING = 4

    /** The plan of the scanned spine items (those above [EpubSplit.SCAN_MIN_BYTES]) of one book. */
    class Plan(
        /** Spine size of the book when the plan was made. */
        val spineSize: Int,
        /** [anchorHash] of the TOC anchors the items were scanned for. */
        val anchors: Long,
        /** Spine indices of the scanned items, ascending. */
        val items: IntArray,
        /** Sections of each scanned item (1: not split). */
        val parts: IntArray,
        /** Scanned text chars of each item (used for split items). */
        val chars: IntArray,
        /** Split items: TOC anchor → part (as [EpubSplit.assign] gave it); null for an item that is not split. */
        val frags: Array<Map<String, Int>?>,
    )

    private class Pending(val key: String, val data: ByteArray?)

    private val pending = ArrayList<Pending>()

    fun dir(): File? = Documents.cacheDir?.let { File(it, "epubplan") }

    fun key(file: File): String = "v$VERSION|${file.absolutePath}|${file.length()}|${file.lastModified()}"

    fun fileFor(key: String): File? {
        val d = dir() ?: return null
        var h = -0x340d631b7bdddcdbL // FNV-1a 64 offset basis
        for (c in key) {
            h = h xor (c.code.toLong() and 0xFF)
            h *= 0x100000001b3L
            h = h xor (c.code.toLong() ushr 8)
            h *= 0x100000001b3L
        }
        return File(d, java.lang.Long.toHexString(h) + ".bin")
    }

    /** The plan stored for [key], or null (missing, stale or damaged). Blocking IO. */
    fun load(key: String): Plan? {
        val f = fileFor(key) ?: return null
        lookups++
        return try {
            if (!f.isFile) return null
            decode(f.readBytes(), key)?.also { touchLater(key) }
        } catch (_: Throwable) {
            null
        }
    }

    /** Keeps [plan] for [writePending] (encoded now: a few hundred bytes; nothing is written here). */
    fun stage(key: String, plan: Plan) {
        if (dir() == null) return
        val data = try {
            encode(key, plan)
        } catch (_: Exception) {
            return
        }
        add(Pending(key, data))
    }

    /** Marks the file of [key] as recently used, later ([writePending]): trimming keeps the newest files. */
    private fun touchLater(key: String) = add(Pending(key, null))

    private fun add(p: Pending) {
        synchronized(pending) {
            pending.removeAll { it.key == p.key }
            if (pending.size >= MAX_PENDING) pending.removeAt(0)
            pending.add(p)
        }
    }

    /** Writes the staged plans (and the recently used marks). Blocking IO; never throws. */
    fun writePending() {
        val todo = synchronized(pending) {
            if (pending.isEmpty()) return
            ArrayList(pending).also { pending.clear() }
        }
        var wrote = false
        for (p in todo) {
            try {
                val f = fileFor(p.key) ?: continue
                if (p.data == null) {
                    f.setLastModified(System.currentTimeMillis())
                    continue
                }
                val d = f.parentFile ?: continue
                if (!d.isDirectory && !d.mkdirs()) continue
                val tmp = File(d, f.name + ".tmp" + Thread.currentThread().id)
                FileOutputStream(tmp).use { it.write(p.data) }
                if (!tmp.renameTo(f)) {
                    f.delete()
                    if (!tmp.renameTo(f)) tmp.delete()
                }
                wrote = true
            } catch (_: Throwable) {
            }
        }
        if (wrote) trim()
    }

    /** Number of staged writes (tests). */
    internal val pendingCount: Int get() = synchronized(pending) { pending.size }

    /** Cache files looked up so far (tests: a small EPUB must not look). */
    @Volatile internal var lookups = 0
        private set

    private fun trim() {
        try {
            val files = dir()?.listFiles() ?: return
            if (files.size <= MAX_FILES) return
            files.sortByDescending { it.lastModified() }
            for (k in KEEP_FILES until files.size) files[k].delete()
        } catch (_: Exception) {
        }
    }

    /**
     * Order-independent hash of the TOC anchors each scanned item is asked for ([wanted] by spine index; items not in
     * [items] are ignored): a TOC parsed differently (another app version) invalidates the plan.
     */
    fun anchorHash(items: IntArray, n: Int, wanted: Array<out Set<String>?>?): Long {
        var h = n.toLong()
        if (wanted == null) return mix(h)
        for (k in 0 until n) {
            val i = items[k]
            val w = wanted[i] ?: continue
            for (a in w) h += mix((i.toLong() shl 32) xor (a.hashCode().toLong() and 0xFFFFFFFFL) xor (a.length.toLong() shl 48))
        }
        return mix(h)
    }

    private fun mix(x: Long): Long { // SplitMix64 finaliser
        var z = x + -0x61c8864680b583ebL
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        return z xor (z ushr 31)
    }

    internal fun encode(key: String, p: Plan): ByteArray {
        val bytes = ByteArrayOutputStream(256)
        DataOutputStream(bytes).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(VERSION)
            out.writeUTF(key)
            out.writeInt(p.spineSize)
            out.writeLong(p.anchors)
            out.writeInt(p.items.size)
            for (k in p.items.indices) {
                out.writeInt(p.items[k])
                out.writeInt(p.parts[k])
                out.writeInt(p.chars[k])
                val m = p.frags[k]
                out.writeInt(m?.size ?: -1)
                if (m != null) {
                    for ((id, part) in m) {
                        out.writeUTF(id)
                        out.writeInt(part)
                    }
                }
            }
            out.writeInt(MAGIC)
        }
        return bytes.toByteArray()
    }

    /** Decodes and validates a plan; null on any inconsistency (never throws). */
    internal fun decode(data: ByteArray, key: String): Plan? = try {
        decodeOrThrow(DataInputStream(ByteArrayInputStream(data)), data.size, key)
    } catch (_: Exception) {
        null
    }

    private fun decodeOrThrow(inp: DataInputStream, size: Int, key: String): Plan? {
        if (inp.readInt() != MAGIC || inp.readInt() != VERSION) return null
        if (inp.readUTF() != key) return null
        val spineSize = inp.readInt()
        val anchors = inp.readLong()
        val n = inp.readInt()
        if (spineSize < 1 || n < 1 || n > spineSize || n > size / 16) return null
        val items = IntArray(n)
        val parts = IntArray(n)
        val chars = IntArray(n)
        val frags = arrayOfNulls<Map<String, Int>>(n)
        for (k in 0 until n) {
            items[k] = inp.readInt()
            parts[k] = inp.readInt()
            chars[k] = inp.readInt()
            if (items[k] !in 0 until spineSize || (k > 0 && items[k] <= items[k - 1])) return null
            if (parts[k] !in 1..EpubSplit.MAX_PARTS || chars[k] < 0) return null
            val m = inp.readInt()
            if (m < -1 || m > size / 6) return null
            if (m >= 0) {
                if (parts[k] == 1) return null
                val map = HashMap<String, Int>(m * 2)
                repeat(m) {
                    val id = inp.readUTF()
                    val part = inp.readInt()
                    if (part !in 0 until parts[k]) return null
                    map[id] = part
                }
                frags[k] = map
            } else if (parts[k] > 1) {
                return null
            }
        }
        if (inp.readInt() != MAGIC) return null
        return Plan(spineSize, anchors, items, parts, chars, frags)
    }
}
