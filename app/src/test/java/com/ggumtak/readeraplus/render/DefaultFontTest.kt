package com.ggumtak.readeraplus.render

import com.ggumtak.readeraplus.settings.ReaderSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The default font (A8: 나눔명조) is also FontManager's missing-font fallback (`builtIn.getValue(DEFAULT_ID)`, which
 * throws for an id that isn't bundled) and the cover face.
 */
class DefaultFontTest {

    @Test
    fun defaultIsABundledFontAndTheReadersDefault() {
        val b = FontCatalog.BUNDLED.firstOrNull { it.id == FontCatalog.DEFAULT_ID }
        assertNotNull("DEFAULT_ID must be bundled: it is the fallback for a missing font", b)
        assertEquals("나눔명조", b!!.name)
        assertEquals(ReaderSettings().fontId, FontCatalog.DEFAULT_ID)
    }

    @Test
    fun defaultFontFilesAreShipped() {
        val assets = listOf("app/src/main/assets", "src/main/assets").map(::File).firstOrNull { it.isDirectory }
        assumeTrue("assets not found from ${File("").absolutePath}", assets != null)
        val b = FontCatalog.BUNDLED.first { it.id == FontCatalog.DEFAULT_ID }
        assertTrue(b.regular, File(assets, b.regular).isFile)
        b.bold?.let { assertTrue(it, File(assets, it).isFile) }
    }

    @Test
    fun coverTitleStrokeLooksLikeBold() {
        // Placeholder titles use the regular face + stroke (one inflated CJK face): 700 ≈ 3.6% of the size.
        assertEquals(0.036f * 20f, FontMath.syntheticStroke(700, false, 20f), 1e-5f)
    }
}
