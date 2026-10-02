package com.ggumtak.readeraplus.reader


internal object LightCurve {
    const val LEVEL_MIN=1; const val LEVEL_MAX=255
    fun out(pos: Float): Float = TODO("owner: RU")
    fun pos(out: Float): Float = TODO("owner: RU")
    fun level(out: Float, min: Int=LEVEL_MIN, max: Int=LEVEL_MAX): Int = TODO("owner: RU")
    fun fraction(level: Int, min: Int=LEVEL_MIN, max: Int=LEVEL_MAX): Float = TODO("owner: RU")
    fun isExternal(value: Int, ours: Int, sinceOurWriteMs: Long, queued: Boolean, echoMs: Long=1500L): Boolean = TODO("owner: RU")
}
