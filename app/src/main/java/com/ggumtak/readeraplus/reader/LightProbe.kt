package com.ggumtak.readeraplus.reader

import android.content.Context
import java.io.File

internal object LightProbe {
    fun lightKeys(ctx: Context): Map<String,String> = emptyMap() // R3 stub (owner: RU)
    fun hasWarm(keys: Map<String,String>): Boolean = false // R3 stub (owner: RU)
    fun coldNode(): File? = null // R3 stub (owner: RU)
    fun warmNode(): File? = null // R3 stub (owner: RU)
    fun read(file: File?): Int = -1 // R3 stub (owner: RU)
    fun report(ctx: Context): List<String> = emptyList() // R3 stub (owner: RU)
}
