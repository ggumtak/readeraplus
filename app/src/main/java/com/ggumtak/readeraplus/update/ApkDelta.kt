package com.ggumtak.readeraplus.update

import java.io.DataInputStream
import java.io.File
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.io.RandomAccessFile

/** RPD1: positive lengths are literals; negative lengths copy bytes at an absolute offset in the installed APK. */
internal object ApkDelta {
    fun apply(base: File, patch: InputStream, output: OutputStream, expectedSize: Long, cancelled: () -> Boolean) {
        val input = DataInputStream(patch.buffered())
        require(input.readInt() == 0x52504431)
        val buffer = ByteArray(64 * 1024)
        var remaining = expectedSize
        RandomAccessFile(base, "r").use { old ->
            val baseSize = old.length()
            while (remaining > 0) {
                val instruction = input.readInt()
                val length = kotlin.math.abs(instruction.toLong())
                require(length in 1..remaining)
                if (instruction < 0) {
                    val offset = input.readInt().toLong()
                    require(offset >= 0 && offset + length <= baseSize)
                    old.seek(offset)
                }
                var left = length
                while (left > 0) {
                    if (cancelled()) throw InterruptedIOException("Update cancelled")
                    val count = minOf(left, buffer.size.toLong()).toInt()
                    if (instruction > 0) input.readFully(buffer, 0, count) else old.readFully(buffer, 0, count)
                    output.write(buffer, 0, count)
                    left -= count
                }
                remaining -= length
            }
        }
        require(input.read() == -1)
    }
}
