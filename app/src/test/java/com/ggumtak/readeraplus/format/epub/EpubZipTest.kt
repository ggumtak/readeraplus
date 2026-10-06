package com.ggumtak.readeraplus.format.epub

import com.ggumtak.readeraplus.format.DocumentException
import com.ggumtak.readeraplus.format.epub.EpubTestUtil.Entry
import com.ggumtak.readeraplus.format.epub.EpubTestUtil.text
import com.ggumtak.readeraplus.format.epub.EpubTestUtil.writeEpub
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.IOException

/** [EpubZip.readOrThrow] (a section's own file: failures are reported) and [EpubZip.read] (optional resources: null). */
class EpubZipTest {
    private fun zip(vararg extra: Entry): EpubZip =
        EpubZip.open(writeEpub(listOf(text("OEBPS/a.xhtml", "<p>가나다</p>"), Entry("OEBPS/b.png", ByteArray(100) { it.toByte() })) + extra))

    private fun documentError(body: () -> Unit): DocumentException {
        try {
            body()
        } catch (e: DocumentException) {
            return e
        }
        fail("expected a DocumentException")
        throw AssertionError()
    }

    @Test
    fun readOrThrowReturnsTheBytes() {
        zip().use { z ->
            assertEquals("<p>가나다</p>", String(z.readOrThrow("OEBPS/a.xhtml"), Charsets.UTF_8))
            // any spelling find() resolves
            assertEquals("<p>가나다</p>", String(z.readOrThrow("OEBPS/./A.XHTML"), Charsets.UTF_8))
        }
    }

    @Test
    fun missingEntryThrowsButReadStaysNull() {
        zip().use { z ->
            val e = documentError { z.readOrThrow("OEBPS/nope.xhtml") }
            assertEquals("책 안의 파일을 찾지 못했습니다", e.message)
            assertNull(z.read("OEBPS/nope.xhtml"))
            assertNull(z.read(""))
        }
    }

    @Test
    fun entryOverMaxThrowsButReadStaysNull() {
        zip().use { z ->
            val e = documentError { z.readOrThrow("OEBPS/b.png", 99) }
            assertEquals("책 안의 파일이 너무 큽니다", e.message)
            assertNull(z.read("OEBPS/b.png", 99))
            assertEquals(100, z.readOrThrow("OEBPS/b.png", 100).size)
            assertEquals(100, z.read("OEBPS/b.png", 100)?.size)
        }
    }

    @Test
    fun corruptDataThrowsIoExceptionButReadStaysNull() {
        // Deflated entry, its compressed bytes overwritten: the inflater rejects (or the stream ends short).
        val payload = ByteArray(20000) { ((it * 31) xor (it ushr 3)).toByte() }
        val f = writeEpub(listOf(Entry("OEBPS/c.bin", payload)))
        val raw = f.readBytes()
        val local = indexOf(raw, "OEBPS/c.bin".toByteArray()) + "OEBPS/c.bin".length
        for (i in local until local + 600) raw[i] = 0xFF.toByte()
        f.writeBytes(raw)
        EpubZip.open(f).use { z ->
            try {
                z.readOrThrow("OEBPS/c.bin")
                fail("expected an IOException")
            } catch (_: IOException) {
            }
            assertNull(z.read("OEBPS/c.bin"))
        }
    }

    @Test
    fun closedZipWithVanishedFileThrowsIoExceptionAndReadsAfterRestoreWork() {
        val f = writeEpub(listOf(text("OEBPS/a.xhtml", "<p>가나다</p>")))
        val z = EpubZip.open(f)
        z.close()
        val aside = File(f.parentFile, "aside.epub")
        assertTrue(f.renameTo(aside))
        try {
            try {
                z.readOrThrow("OEBPS/a.xhtml")
                fail("expected an IOException")
            } catch (_: IOException) {
            }
            assertNull(z.read("OEBPS/a.xhtml"))
        } finally {
            assertTrue(aside.renameTo(f))
        }
        // a closed zip reopens temporarily for the read
        assertEquals("<p>가나다</p>", String(z.readOrThrow("OEBPS/a.xhtml"), Charsets.UTF_8))
        assertArrayEquals("<p>가나다</p>".toByteArray(), z.read("OEBPS/a.xhtml"))
    }

    private fun indexOf(hay: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..hay.size - needle.size) {
            for (k in needle.indices) if (hay[i + k] != needle[k]) continue@outer
            return i
        }
        throw AssertionError("needle not found")
    }
}
