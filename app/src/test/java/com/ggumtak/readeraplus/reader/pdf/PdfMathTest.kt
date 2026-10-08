package com.ggumtak.readeraplus.reader.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PdfMathTest {
    private val eps = 1e-4f

    // fitScale
    @Test fun fitScaleUsesTighterAxis() {
        assertEquals(2f, PdfMath.fitScale(100, 200, 400, 400), eps)
        assertEquals(0.5f, PdfMath.fitScale(200, 100, 100, 400), eps)
    }

    @Test fun fitScaleZeroOnBadSizes() {
        assertEquals(0f, PdfMath.fitScale(0, 100, 100, 100), 0f)
        assertEquals(0f, PdfMath.fitScale(100, -1, 100, 100), 0f)
        assertEquals(0f, PdfMath.fitScale(100, 100, 0, 100), 0f)
        assertEquals(0f, PdfMath.fitScale(100, 100, 100, -5), 0f)
    }

    // clampZoom
    @Test fun clampZoomBounds() {
        assertEquals(1f, PdfMath.clampZoom(0.2f), 0f)
        assertEquals(1f, PdfMath.clampZoom(-3f), 0f)
        assertEquals(5f, PdfMath.clampZoom(9f), 0f)
        assertEquals(2.5f, PdfMath.clampZoom(2.5f), 0f)
        assertEquals(1f, PdfMath.clampZoom(Float.NaN), 0f)
        assertEquals(5f, PdfMath.clampZoom(Float.POSITIVE_INFINITY), 0f)
        assertEquals(1f, PdfMath.clampZoom(Float.NEGATIVE_INFINITY), 0f)
    }

    // clampOffset
    @Test fun clampOffsetCentresSmallContent() {
        assertEquals(25f, PdfMath.clampOffset(-100f, 50f, 100f), 0f)
        assertEquals(0f, PdfMath.clampOffset(30f, 100f, 100f), 0f)
    }

    @Test fun clampOffsetClampsLargeContent() {
        assertEquals(0f, PdfMath.clampOffset(10f, 300f, 100f), 0f)
        assertEquals(-200f, PdfMath.clampOffset(-500f, 300f, 100f), 0f)
        assertEquals(-50f, PdfMath.clampOffset(-50f, 300f, 100f), 0f)
        assertEquals(0f, PdfMath.clampOffset(Float.NaN, 300f, 100f), 0f)
    }

    // zoomOffset
    @Test fun zoomOffsetKeepsFocusPointFixed() {
        val offset = -40f
        val focus = 70f
        val oldZ = 1.5f
        val newZ = 3f
        val n = PdfMath.zoomOffset(offset, focus, oldZ, newZ)
        // content coordinate (at zoom 1) under the focus before and after
        val before = (focus - offset) / oldZ
        val after = (focus - n) / newZ
        assertEquals(before, after, eps)
    }

    @Test fun zoomOffsetFormulaAndIdentity() {
        assertEquals(100f - (100f - 20f) * 2f, PdfMath.zoomOffset(20f, 100f, 1f, 2f), eps)
        assertEquals(20f, PdfMath.zoomOffset(20f, 100f, 2f, 2f), eps)
    }

    @Test fun zoomOffsetBadOldZoomReturnsOffset() {
        assertEquals(7f, PdfMath.zoomOffset(7f, 100f, 0f, 2f), 0f)
        assertEquals(7f, PdfMath.zoomOffset(7f, 100f, -1f, 2f), 0f)
    }

    // stepOffset
    @Test fun stepForwardMovesNinetyPercent() {
        assertEquals(-90f, PdfMath.stepOffset(0f, 1000f, 100f, 1), eps)
        assertEquals(-180f, PdfMath.stepOffset(-90f, 1000f, 100f, 1), eps)
    }

    @Test fun stepBackMovesNinetyPercent() {
        assertEquals(-110f, PdfMath.stepOffset(-200f, 1000f, 100f, -1), eps)
    }

    @Test fun stepPartialLastStepClampsToEdge() {
        // lowest offset is -900; at -850 only 50 px remain
        assertEquals(-900f, PdfMath.stepOffset(-850f, 1000f, 100f, 1), eps)
        assertEquals(0f, PdfMath.stepOffset(-30f, 1000f, 100f, -1), eps)
    }

    @Test fun stepAtEdgesReturnsNaN() {
        assertTrue(PdfMath.stepOffset(-900f, 1000f, 100f, 1).isNaN())
        assertTrue(PdfMath.stepOffset(0f, 1000f, 100f, -1).isNaN())
        // within 0.5 px counts as at the edge
        assertTrue(PdfMath.stepOffset(-899.7f, 1000f, 100f, 1).isNaN())
        assertTrue(PdfMath.stepOffset(-0.3f, 1000f, 100f, -1).isNaN())
        // more than 0.5 px left still moves
        assertEquals(-900f, PdfMath.stepOffset(-899f, 1000f, 100f, 1), eps)
    }

    @Test fun stepOnFittingContentReturnsNaN() {
        assertTrue(PdfMath.stepOffset(0f, 100f, 100f, 1).isNaN())
        assertTrue(PdfMath.stepOffset(0f, 80f, 100f, -1).isNaN())
    }

    @Test fun stepClampsOutOfRangeOffsetFirst() {
        // offset past the top edge behaves like offset 0
        assertEquals(-90f, PdfMath.stepOffset(50f, 1000f, 100f, 1), eps)
        assertTrue(PdfMath.stepOffset(50f, 1000f, 100f, -1).isNaN())
    }

    @Test fun stepZeroDirIsNaN() {
        assertTrue(PdfMath.stepOffset(-100f, 1000f, 100f, 0).isNaN())
    }

    // tapZone
    @Test fun tapZoneBoundaries() {
        assertEquals(-1, PdfMath.tapZone(0f, 300))
        assertEquals(-1, PdfMath.tapZone(99.9f, 300))
        assertEquals(0, PdfMath.tapZone(100f, 300))
        assertEquals(0, PdfMath.tapZone(150f, 300))
        assertEquals(0, PdfMath.tapZone(199.9f, 300))
        assertEquals(1, PdfMath.tapZone(200f, 300))
        assertEquals(1, PdfMath.tapZone(299f, 300))
    }

    @Test fun tapZoneOutsideAndBadWidth() {
        assertEquals(-1, PdfMath.tapZone(-10f, 300))
        assertEquals(1, PdfMath.tapZone(500f, 300))
        assertEquals(0, PdfMath.tapZone(10f, 0))
        assertEquals(0, PdfMath.tapZone(10f, -5))
    }

    // clampPage / progress / label
    @Test fun clampPageRange() {
        assertEquals(0, PdfMath.clampPage(-3, 10))
        assertEquals(9, PdfMath.clampPage(10, 10))
        assertEquals(4, PdfMath.clampPage(4, 10))
        assertEquals(0, PdfMath.clampPage(5, 0))
        assertEquals(0, PdfMath.clampPage(5, -2))
    }

    @Test fun progressValues() {
        assertEquals(1f, PdfMath.progress(9, 10), 0f)
        assertEquals(0.1f, PdfMath.progress(0, 10), eps)
        assertEquals(0.5f, PdfMath.progress(4, 10), eps)
        assertEquals(1f, PdfMath.progress(99, 10), 0f)
        assertEquals(0f, PdfMath.progress(0, 0), 0f)
        assertEquals(1f, PdfMath.progress(0, 1), 0f)
    }

    @Test fun pageLabelFormat() {
        assertEquals("12 / 340", PdfMath.pageLabel(11, 340))
        assertEquals("1 / 1", PdfMath.pageLabel(0, 1))
        assertEquals("0 / 0", PdfMath.pageLabel(0, 0))
        assertEquals("0 / 0", PdfMath.pageLabel(3, -1))
        assertEquals("10 / 10", PdfMath.pageLabel(50, 10))
    }

    // renderSize
    @Test fun renderSizeCeilsAndPacks() {
        val p = PdfMath.renderSize(100, 200, 1.5f, Int.MAX_VALUE)
        assertEquals(150, PdfMath.packedW(p))
        assertEquals(300, PdfMath.packedH(p))
        val q = PdfMath.renderSize(101, 51, 0.5f, Int.MAX_VALUE)
        assertEquals(51, PdfMath.packedW(q))
        assertEquals(26, PdfMath.packedH(q))
    }

    @Test fun renderSizeAtLeastOne() {
        val p = PdfMath.renderSize(0, -5, 1f, 1000)
        assertEquals(1, PdfMath.packedW(p))
        assertEquals(1, PdfMath.packedH(p))
        val q = PdfMath.renderSize(100, 100, 0f, 1000)
        assertEquals(1, PdfMath.packedW(q))
        assertEquals(1, PdfMath.packedH(q))
        val r = PdfMath.renderSize(100, 100, Float.NaN, 1000)
        assertEquals(1, PdfMath.packedW(r))
        assertEquals(1, PdfMath.packedH(r))
    }

    @Test fun renderSizeCapRespectsMaxAndAspect() {
        val cases = listOf(
            intArrayOf(612, 792, 4000),
            intArrayOf(595, 842, 3000),
            intArrayOf(1000, 10, 5000),
            intArrayOf(10, 1000, 5000),
            intArrayOf(333, 777, 12345)
        )
        for (c in cases) {
            val scale = 7.3f
            val max = c[2]
            val fullW = Math.ceil(c[0] * scale.toDouble())
            val fullH = Math.ceil(c[1] * scale.toDouble())
            val p = PdfMath.renderSize(c[0], c[1], scale, max)
            val w = PdfMath.packedW(p)
            val h = PdfMath.packedH(p)
            assertTrue("w*h=${w.toLong() * h} > $max", w.toLong() * h <= max)
            assertTrue(w >= 1 && h >= 1)
            val f = Math.sqrt(max / (fullW * fullH))
            assertTrue("w off: $w vs ${fullW * f}", abs(w - fullW * f) <= 1.0 + 1e-6)
            assertTrue("h off: $h vs ${fullH * f}", abs(h - fullH * f) <= 1.0 + 1e-6)
        }
    }

    @Test fun renderSizeUnderCapUnchanged() {
        val p = PdfMath.renderSize(100, 100, 2f, 40000)
        assertEquals(200, PdfMath.packedW(p))
        assertEquals(200, PdfMath.packedH(p))
    }

    @Test fun renderSizeHugeDoesNotOverflow() {
        val p = PdfMath.renderSize(Int.MAX_VALUE, Int.MAX_VALUE, 5f, 1_000_000)
        val w = PdfMath.packedW(p)
        val h = PdfMath.packedH(p)
        assertTrue(w >= 1 && h >= 1)
        assertTrue(w.toLong() * h <= 1_000_000L)
    }

    @Test fun renderSizeTinyMaxStillOneByOne() {
        val p = PdfMath.renderSize(500, 500, 1f, 0)
        assertEquals(1, PdfMath.packedW(p))
        assertEquals(1, PdfMath.packedH(p))
    }

    @Test
    fun dragSlideFollowsTheFingerAndResistsAtMissingPages() {
        assertEquals(-30f, PdfMath.dragSlide(0f, -30f, 1000f, hasPrev = true, hasNext = true), eps)
        assertEquals(40f, PdfMath.dragSlide(10f, 30f, 1000f, hasPrev = true, hasNext = true), eps)
        // No next page: a drag to the left moves only a part of the way.
        assertEquals(-30f * PdfMath.EDGE_RESISTANCE, PdfMath.dragSlide(0f, -30f, 1000f, hasPrev = true, hasNext = false), eps)
        // No previous page: the same to the right.
        assertEquals(30f * PdfMath.EDGE_RESISTANCE, PdfMath.dragSlide(0f, 30f, 1000f, hasPrev = false, hasNext = true), eps)
        // Clamped to one page step.
        assertEquals(-1000f, PdfMath.dragSlide(-990f, -50f, 1000f, hasPrev = true, hasNext = true), eps)
        assertEquals(1000f, PdfMath.dragSlide(990f, 50f, 1000f, hasPrev = true, hasNext = true), eps)
    }

    @Test
    fun settleDirByDistanceFlingAndAvailablePages() {
        // Short drags go back, long ones turn.
        assertEquals(0, PdfMath.settleDir(-100f, 1000f, 0, hasPrev = true, hasNext = true))
        assertEquals(1, PdfMath.settleDir(-250f, 1000f, 0, hasPrev = true, hasNext = true))
        assertEquals(-1, PdfMath.settleDir(300f, 1000f, 0, hasPrev = true, hasNext = true))
        // A fling turns even a short drag.
        assertEquals(1, PdfMath.settleDir(-20f, 1000f, 1, hasPrev = true, hasNext = true))
        assertEquals(-1, PdfMath.settleDir(20f, 1000f, -1, hasPrev = true, hasNext = true))
        // Never against the drag, never to a missing page.
        assertEquals(0, PdfMath.settleDir(20f, 1000f, 1, hasPrev = true, hasNext = true))
        assertEquals(0, PdfMath.settleDir(-500f, 1000f, 0, hasPrev = true, hasNext = false))
        assertEquals(0, PdfMath.settleDir(500f, 1000f, -1, hasPrev = false, hasNext = true))
    }

    @Test
    fun animMsIsProportionalAndBounded() {
        assertEquals(PdfMath.MAX_ANIM_MS, PdfMath.animMs(1000f, 1000f))
        assertEquals(PdfMath.MAX_ANIM_MS / 2, PdfMath.animMs(-500f, 1000f))
        assertEquals(80L, PdfMath.animMs(1f, 1000f))
        assertEquals(PdfMath.MAX_ANIM_MS, PdfMath.animMs(5000f, 1000f))
        assertEquals(80L, PdfMath.animMs(100f, 0f))
    }
}
