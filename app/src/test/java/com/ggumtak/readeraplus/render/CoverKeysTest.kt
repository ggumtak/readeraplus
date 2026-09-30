package com.ggumtak.readeraplus.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverKeysTest {

    @Test
    fun txtEncodingIsPartOfTheVersion() {
        // Regression: the disk key ignored the forced encoding, so a restored/changed TXT encoding kept the
        // mini page decoded with the old one.
        val auto = CoverKeys.version(1000L, txt = true, encoding = "")
        val euc = CoverKeys.version(1000L, txt = true, encoding = "EUC-KR")
        val utf = CoverKeys.version(1000L, txt = true, encoding = "UTF-8")
        assertEquals("1000", auto)
        assertNotEquals(auto, euc)
        assertNotEquals(euc, utf)
        assertEquals(euc, CoverKeys.version(1000L, txt = true, encoding = " euc-kr "))
        assertNotEquals(auto, CoverKeys.version(1001L, txt = true, encoding = ""))
    }

    @Test
    fun epubIgnoresEncoding() {
        assertEquals(CoverKeys.version(5L, txt = false, encoding = ""), CoverKeys.version(5L, txt = false, encoding = "UTF-8"))
    }

    @Test
    fun namesAreFileSafe() {
        val v = CoverKeys.version(7L, txt = true, encoding = "x/../y z@~")
        val name = CoverKeys.fileName(v, 120, 180)
        assertFalse(name.contains('/'))
        assertFalse(name.contains(' '))
        assertEquals(1, name.count { it == '@' })
        assertTrue(name.endsWith("@120x180.png"))
    }

    @Test
    fun staleVersionsAreRecognised() {
        val cur = CoverKeys.version(1000L, txt = true, encoding = "")
        val enc = CoverKeys.version(1000L, txt = true, encoding = "cp949")
        assertTrue(CoverKeys.isVersion(CoverKeys.fileName(cur, 10, 20), cur))
        assertTrue(CoverKeys.isVersion(CoverKeys.fileName(cur, 30, 40) + ".tmp12", cur))
        assertFalse(CoverKeys.isVersion(CoverKeys.fileName(enc, 10, 20), cur))
        assertFalse(CoverKeys.isVersion(CoverKeys.fileName(cur, 10, 20), enc))
        assertFalse(CoverKeys.isVersion(CoverKeys.fileName("10000", 10, 20), cur))
        assertFalse(CoverKeys.isVersion("1000", cur))
        // Files of the previous flat layout never count as current.
        assertFalse(CoverKeys.isVersion("5_1000_10x20.png", cur))
    }
}
