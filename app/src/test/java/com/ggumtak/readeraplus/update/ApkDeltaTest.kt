package com.ggumtak.readeraplus.update

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ApkDeltaTest {
    private fun patch(body: DataOutputStream.() -> Unit): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { it.writeBytes("RPD1"); it.body() }
        return bytes.toByteArray()
    }

    private fun apply(bytes: ByteArray, size: Long, cancel: Boolean = false): String {
        val base = File.createTempFile("update-base", ".apk")
        try {
            base.writeText("abcd")
            val out = ByteArrayOutputStream()
            ApkDelta.apply(base, bytes.inputStream(), out, size) { cancel }
            return out.toString("UTF-8")
        } finally {
            base.delete()
        }
    }

    @Test fun copiesExactBytesAndRejectsInvalidOrCancelledPatches() {
        val good = patch { writeInt(-3); writeInt(1); writeInt(1); writeByte(120) }
        assertEquals("bcdx", apply(good, 4))
        assertThrows(java.io.InterruptedIOException::class.java) { apply(good, 4, true) }
        assertThrows(IllegalArgumentException::class.java) { apply(good, 2) }
        assertThrows(IllegalArgumentException::class.java) { apply(good + 1.toByte(), 4) }
        assertThrows(java.io.EOFException::class.java) { apply(good.dropLast(1).toByteArray(), 4) }
        assertThrows(IllegalArgumentException::class.java) { apply(patch { writeInt(-2); writeInt(3) }, 2) }
        assertThrows(IllegalArgumentException::class.java) { apply(patch { writeInt(0) }, 2) }
    }
}
