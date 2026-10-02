package com.ggumtak.readeraplus.reader

import android.content.Context
import com.ggumtak.readeraplus.render.DeviceClass

internal object DeviceLight {
    const val VERDICT_UNKNOWN=0; const val VERDICT_WINDOW=1; const val VERDICT_DEVICE=2; const val VERDICT_NONE=3
    const val MIN_GAP_MS=100L; const val ECHO_MS=1500L
    @Volatile var noPermission=false; @Volatile var deviceOut=-1f
    var onExternal: Runnable?=null; var onNoPermission: Runnable?=null
    fun init(ctx: Context) {} // R3 stub (owner: RU)
    fun set(out: Float) {} // R3 stub (owner: RU)
    fun restore() {} // R3 stub (owner: RU)
    fun restoreIfStale(ctx: Context) {} // R3 stub (owner: RU)
    fun refresh() {} // R3 stub (owner: RU)
    fun verdict(ctx: Context): Int = VERDICT_UNKNOWN // R3 stub (owner: RU)
    fun setVerdict(ctx: Context, v: Int) {} // R3 stub (owner: RU)
    fun asks(ctx: Context): Int = 0 // R3 stub (owner: RU)
    fun countAsk(ctx: Context) {} // R3 stub (owner: RU)
    fun looksEink(ctx: Context): Boolean = DeviceClass.cached(ctx) ?: DeviceClass.probe(ctx)
}
