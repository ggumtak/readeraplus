package com.ggumtak.readeraplus.render

/**
 * One picture at one target size: the key of the decoded bitmaps, the queued and running decodes and the failures.
 * Unit-tested, no Android classes. A key stored in a map is never changed; the owner of a map also owns one more
 * instance ([ImageKeyMap], [ImageLru]) that it re-aims at each lookup, so asking whether a picture is there allocates
 * nothing (a "src|w|h" String per draw would).
 */
internal class ImageKey(src: String, w: Int, h: Int) {
    var src: String = src
        private set
    var w: Int = w
        private set
    var h: Int = h
        private set

    /** Re-aims a lookup instance. Never call it on a key that is stored in a map. */
    fun aim(src: String, w: Int, h: Int) {
        this.src = src
        this.w = w
        this.h = h
    }

    override fun hashCode(): Int = (src.hashCode() * 31 + w) * 31 + h

    override fun equals(other: Any?): Boolean =
        other is ImageKey && other.w == w && other.h == h && other.src == src

    override fun toString(): String = "$src|$w|$h"
}

/**
 * HashMap by [ImageKey] whose lookups by (src, w, h) allocate nothing. Not thread-safe: the owner holds its own lock
 * around every call (the lookup instance is shared by them).
 */
internal class ImageKeyMap<V : Any> {
    private val map = HashMap<ImageKey, V>()
    private val probe = ImageKey("", 0, 0)

    fun isEmpty(): Boolean = map.isEmpty()

    fun get(src: String, w: Int, h: Int): V? {
        probe.aim(src, w, h)
        return map[probe]
    }

    fun contains(src: String, w: Int, h: Int): Boolean = get(src, w, h) != null

    fun put(key: ImageKey, value: V) {
        map[key] = value
    }

    fun remove(key: ImageKey): V? = map.remove(key)

    fun clear() = map.clear()
}

/**
 * LRU of decoded pictures by bytes (unit-tested with any value type), thread-safe. [sizeOf] gives a value's bytes.
 * A hit allocates nothing, so [PageRenderer.draw] can ask for every picture of every frame. Nothing is recycled on
 * eviction: a page being drawn may hold the value. [close] drops everything and refuses later puts (a decode that
 * ends after its book closed must not fill the cache again).
 */
internal class ImageLru<V : Any>(private val maxBytes: Int, private val sizeOf: (V) -> Int) {
    private val map = LinkedHashMap<ImageKey, V>(16, 0.75f, true)
    private val probe = ImageKey("", 0, 0)
    private var bytes = 0L
    private var closed = false

    /** The value cached for exactly this picture and size, now the most recently used; null when none. */
    fun get(src: String, w: Int, h: Int): V? = synchronized(this) {
        probe.aim(src, w, h)
        map[probe]
    }

    /** Stores [value] under [key] (replacing one), then evicts the least recently used until the budget holds. */
    fun put(key: ImageKey, value: V): Boolean = synchronized(this) {
        if (closed) return false
        val old = map.put(key, value)
        if (old != null) bytes -= size(old)
        bytes += size(value)
        trim(maxBytes.toLong())
        true
    }

    /** Evicts the least recently used values until at most [limit] bytes remain. */
    fun trimTo(limit: Int) = synchronized(this) { trim(limit.toLong()) }

    fun evictAll() = synchronized(this) {
        map.clear()
        bytes = 0L
    }

    /** Evicts everything and refuses every later [put]. */
    fun close() = synchronized(this) {
        closed = true
        map.clear()
        bytes = 0L
    }

    val byteCount: Long get() = synchronized(this) { bytes }
    val count: Int get() = synchronized(this) { map.size }

    private fun size(v: V): Int = sizeOf(v).coerceAtLeast(1)

    private fun trim(limit: Long) {
        if (bytes <= limit) return
        val it = map.entries.iterator()
        while (bytes > limit && it.hasNext()) {
            val e = it.next()
            bytes -= size(e.value)
            it.remove()
        }
    }
}
