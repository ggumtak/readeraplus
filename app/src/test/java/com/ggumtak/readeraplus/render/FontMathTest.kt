package com.ggumtak.readeraplus.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FontMathTest {

    @Test
    fun normalizeWeight() {
        assertEquals(100, FontMath.normalizeWeight(0))
        assertEquals(100, FontMath.normalizeWeight(-50))
        assertEquals(900, FontMath.normalizeWeight(1200))
        assertEquals(400, FontMath.normalizeWeight(400))
        assertEquals(450, FontMath.normalizeWeight(450))
        assertEquals(450, FontMath.normalizeWeight(430))
        assertEquals(400, FontMath.normalizeWeight(424))
        assertEquals(600, FontMath.normalizeWeight(575))
    }

    @Test
    fun runWeight() {
        assertEquals(400, FontMath.runWeight(400, false))
        assertEquals(700, FontMath.runWeight(400, true))
        assertEquals(900, FontMath.runWeight(700, true))
        assertEquals(900, FontMath.runWeight(2000, false))
        assertEquals(400, FontMath.runWeight(100, true))
    }

    @Test
    fun boldFileThreshold() {
        assertFalse(FontMath.usesBoldFile(550, true))
        assertTrue(FontMath.usesBoldFile(600, true))
        assertFalse(FontMath.usesBoldFile(900, false))
    }

    @Test
    fun strokeMath() {
        val size = 40f
        assertEquals(0f, FontMath.syntheticStroke(400, false, size), 0f)
        assertEquals(0f, FontMath.syntheticStroke(300, false, size), 0f)
        assertEquals(0.012f * size, FontMath.syntheticStroke(500, false, size), 1e-5f)
        // 900 on a regular-only font ≈ 6% of the size
        assertEquals(0.06f * size, FontMath.syntheticStroke(900, false, size), 1e-5f)
        // bold file ≈ 700: no stroke at 700, 2 steps at 900
        assertEquals(0f, FontMath.syntheticStroke(700, true, size), 0f)
        assertEquals(0f, FontMath.syntheticStroke(600, true, size), 0f)
        assertEquals(0.024f * size, FontMath.syntheticStroke(900, true, size), 1e-5f)
        // proportional to size, never negative / NaN
        assertEquals(2f * FontMath.syntheticStroke(650, false, 10f), FontMath.syntheticStroke(650, false, 20f), 1e-5f)
        assertEquals(0f, FontMath.syntheticStroke(900, false, Float.NaN), 0f)
        assertEquals(0f, FontMath.syntheticStroke(900, false, -3f), 0f)
        assertEquals(0f, FontMath.syntheticStroke(900, false, Float.POSITIVE_INFINITY), 0f)
    }

    @Test
    fun sampleSize() {
        assertEquals(1, ImageMath.sampleSize(100, 100, 100, 100))
        assertEquals(1, ImageMath.sampleSize(100, 100, 200, 200))
        assertEquals(2, ImageMath.sampleSize(400, 400, 200, 200))
        assertEquals(2, ImageMath.sampleSize(400, 400, 150, 150))
        assertEquals(4, ImageMath.sampleSize(4000, 3000, 720, 540))
        // keep ≥ target on both axes
        val s = ImageMath.sampleSize(3000, 1000, 700, 400)
        assertTrue(3000 / s >= 700 && 1000 / s >= 400)
        assertEquals(1, ImageMath.sampleSize(0, 10, 1, 1))
        assertEquals(1, ImageMath.sampleSize(10, 10, 0, 1))
    }

    @Test
    fun sampleForScale() {
        assertEquals(1, ImageMath.sampleForScale(1f))
        assertEquals(1, ImageMath.sampleForScale(2f))
        assertEquals(1, ImageMath.sampleForScale(0.6f))
        assertEquals(2, ImageMath.sampleForScale(0.5f))
        assertEquals(2, ImageMath.sampleForScale(0.3f))
        assertEquals(4, ImageMath.sampleForScale(0.25f))
        assertEquals(8, ImageMath.sampleForScale(0.1f))
        assertEquals(1, ImageMath.sampleForScale(0f))
        assertEquals(1, ImageMath.sampleForScale(Float.NaN))
    }

    @Test
    fun fitNoUpscale() {
        var p = ImageMath.fitNoUpscale(2000, 1000, 1000, 1000)
        assertEquals(1000, ImageMath.packedW(p))
        assertEquals(500, ImageMath.packedH(p))
        p = ImageMath.fitNoUpscale(100, 50, 1000, 1000)
        assertEquals(100, ImageMath.packedW(p))
        assertEquals(50, ImageMath.packedH(p))
        p = ImageMath.fitNoUpscale(10000, 1, 100, 100)
        assertEquals(100, ImageMath.packedW(p))
        assertEquals(1, ImageMath.packedH(p))
        assertEquals(0L, ImageMath.fitNoUpscale(0, 1, 1, 1))
    }

    @Test
    fun coverCropDecision() {
        assertTrue(ImageMath.cropCover(600, 850, 96, 136))
        assertTrue(ImageMath.cropCover(700, 1000, 100, 130))
        assertFalse(ImageMath.cropCover(1000, 600, 96, 136)) // landscape image on a portrait card
        assertFalse(ImageMath.cropCover(300, 1200, 96, 136)) // very tall strip
        assertFalse(ImageMath.cropCover(0, 1, 1, 1))
    }
}
