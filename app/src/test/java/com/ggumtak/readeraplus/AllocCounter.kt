package com.ggumtak.readeraplus

/**
 * Per-thread allocated bytes for allocation tests. `java.lang.management` is not on the Android unit-test compile
 * classpath, so `com.sun.management.ThreadMXBean.getThreadAllocatedBytes` is reached reflectively; [supported] is
 * false (and tests should skip) on a JVM without the counter. The reflective read itself allocates a little (boxing),
 * so [measure] subtracts the cost of measuring an empty block.
 */
object AllocCounter {
    private val read: (() -> Long)? = try {
        val bean = Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null)
        val iface = Class.forName("com.sun.management.ThreadMXBean")
        if (!iface.isInstance(bean) || iface.getMethod("isThreadAllocatedMemorySupported").invoke(bean) != true) {
            null
        } else {
            val get = iface.getMethod("getThreadAllocatedBytes", Long::class.javaPrimitiveType)
            val f: () -> Long = { get.invoke(bean, Thread.currentThread().id) as Long }
            f
        }
    } catch (t: Throwable) {
        null
    }

    val supported: Boolean get() = read != null

    /** The current thread's allocated-bytes counter (-1 when unsupported). */
    fun bytes(): Long = read?.invoke() ?: -1L

    /** Bytes [block] allocated on this thread, net of the measuring overhead; null when unsupported. */
    fun measure(block: () -> Unit): Long? {
        val r = read ?: return null
        var overhead = Long.MAX_VALUE
        repeat(5) {
            val a = r()
            val b = r()
            overhead = minOf(overhead, b - a)
        }
        val before = r()
        block()
        val after = r()
        return (after - before - overhead).coerceAtLeast(0L)
    }
}
