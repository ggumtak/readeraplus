package com.ggumtak.readeraplus.render

internal object QuoteLook {
    @Volatile var generation: Int = 0
        private set
    @Volatile private var useInk = false
    @Synchronized fun update(mode: Int, eink: Boolean?) {
        val next = when (mode) {
            com.ggumtak.readeraplus.settings.HL_LOOK_INK -> true
            com.ggumtak.readeraplus.settings.HL_LOOK_COLOR -> false
            else -> eink == true
        }
        if (next == useInk) return
        useInk = next
        generation++
    }
    fun ink(): Boolean = useInk
}
