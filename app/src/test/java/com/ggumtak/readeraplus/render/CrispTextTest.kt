package com.ggumtak.readeraplus.render

import android.graphics.Paint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CrispTextTest {

    @Test
    fun bodyPaintsAreHintedAndOnWholePixels() {
        // LINEAR_TEXT_FLAG makes Android draw unhinted (hwui Canvas::drawText: linear metrics force kNone), the soft text
        // the user compared with MaruViewer's (2026-10-05); SUBPIXEL_TEXT_FLAG puts glyphs on quarter pixels. Neither.
        assertEquals(0, CrispText.PAINT_FLAGS and Paint.LINEAR_TEXT_FLAG)
        assertEquals(0, CrispText.PAINT_FLAGS and Paint.SUBPIXEL_TEXT_FLAG)
        assertEquals(Paint.ANTI_ALIAS_FLAG, CrispText.PAINT_FLAGS and Paint.ANTI_ALIAS_FLAG)
        // No fake bold, no hinting-off flag: only anti-aliasing.
        assertEquals(Paint.ANTI_ALIAS_FLAG, CrispText.PAINT_FLAGS)
    }

    @Test
    fun textSizeIsTheWholePixelsAndroidDrawsAt() {
        // 17 sp on the S25 (2.8125 px per dp) is 47.81 px: drawn at 47, MaruViewer's size (minikin truncates it anyway).
        assertEquals(47f, CrispText.textPx(17f * 2.8125f), 0f)
        assertEquals(47f, CrispText.textPx(47.99f), 0f)
        // The Comet (2 px per dp, 0.5 sp steps) and whole sizes stay as they are.
        assertEquals(34f, CrispText.textPx(17f * 2f), 0f)
        assertEquals(33f, CrispText.textPx(16.5f * 2f), 0f)
        assertEquals(48f, CrispText.textPx(48f), 0f)
        // A product that is whole in decimal stays whole although its float is a hair below it.
        val scaled = 1.05f * 60f
        assertTrue(scaled < 63f)
        assertEquals(63f, CrispText.textPx(scaled), 0f)
        // Run scales (headings, small print) get whole sizes too; the slider's 0.5 sp steps stay apart on the S25.
        assertEquals(57f, CrispText.textPx(47.8125f * 1.2f), 0f)
        assertTrue(CrispText.textPx(16.5f * 2.8125f) < CrispText.textPx(17f * 2.8125f))
        // At least 1 px; nonsense in, 1 px out.
        assertEquals(1f, CrispText.textPx(0.4f), 0f)
        assertEquals(1f, CrispText.textPx(0f), 0f)
        assertEquals(1f, CrispText.textPx(-3f), 0f)
        assertEquals(1f, CrispText.textPx(Float.NaN), 0f)
        assertEquals(1f, CrispText.textPx(Float.POSITIVE_INFINITY), 0f)
    }

    @Test
    fun baselinesLandOnWholeRows() {
        // TypesetPass centres the glyphs in a 95.6 px line box (200 % of 47.81 px): fractional baselines.
        assertEquals(1132f, CrispText.baselineY(1131.6f), 0f)
        assertEquals(1131f, CrispText.baselineY(1131.4f), 0f)
        assertEquals(1132f, CrispText.baselineY(1131.5f), 0f)
        assertEquals(40f, CrispText.baselineY(40f), 0f)
        assertEquals(-2f, CrispText.baselineY(-2.4f), 0f)
        // Every line of a page at a fractional pitch: a whole row each, the text and its shadow the same distance apart.
        var top = 207f
        for (i in 0 until 20) {
            val y = CrispText.baselineY(top + 16.49f + i * 95.625f)
            assertEquals(y, Math.floor(y.toDouble()).toFloat(), 0f)
            assertEquals(1f, CrispText.baselineY(y + CrispText.shadowOffsetPx(0.36f, 2.8125f)) - y, 0f)
        }
        assertTrue(CrispText.baselineY(Float.NaN).isNaN())
    }

    @Test
    fun shadowOffsetIsWholePixels() {
        // MaruViewer's (2, 1) px on the S25 (2.8125 px per dp), the same at 3; at least 1 px once there is an offset.
        assertEquals(2f, CrispText.shadowOffsetPx(0.71f, 2.8125f), 0f)
        assertEquals(1f, CrispText.shadowOffsetPx(0.36f, 2.8125f), 0f)
        assertEquals(2f, CrispText.shadowOffsetPx(0.71f, 3f), 0f)
        assertEquals(1f, CrispText.shadowOffsetPx(0.36f, 3f), 0f)
        assertEquals(1f, CrispText.shadowOffsetPx(0.71f, 2f), 0f)
        assertEquals(1f, CrispText.shadowOffsetPx(0.1f, 1f), 0f)
        assertEquals(-1f, CrispText.shadowOffsetPx(-0.1f, 1f), 0f)
        assertEquals(-2f, CrispText.shadowOffsetPx(-0.71f, 2.8125f), 0f)
        assertEquals(0f, CrispText.shadowOffsetPx(0f, 2.8125f), 0f)
        assertEquals(0f, CrispText.shadowOffsetPx(Float.NaN, 2.8125f), 0f)
        // A glyph on a whole pixel and a whole-px offset: its shadow is the same distance away wherever the glyph is.
        for (x in 0 until 40) {
            val glyph = x * 45f
            assertEquals(2f, Math.round(glyph + CrispText.shadowOffsetPx(0.71f, 2.8125f)) - glyph, 0f)
        }
    }
}
