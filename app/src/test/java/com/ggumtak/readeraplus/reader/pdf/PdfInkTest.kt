package com.ggumtak.readeraplus.reader.pdf

import java.io.File
import java.nio.file.Files
import java.util.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfInkTest {
    private fun stroke(vararg pts: Float, width: Float = 2f, tool: Int = InkTool.PEN, color: Int = 0xFF000000.toInt()) =
        InkStroke(tool, color, width, pts)

    // ---- add / strokes / pagesWithInk ----

    @Test
    fun addAndRead() {
        val n = PdfNotes()
        assertTrue(n.isEmpty)
        assertTrue(n.strokes(3).isEmpty())
        val a = stroke(0f, 0f, 10f, 10f)
        val b = stroke(5f, 5f)
        n.add(7, a)
        n.add(2, b)
        n.add(7, b)
        assertFalse(n.isEmpty)
        assertEquals(2, n.strokes(7).size)
        assertSame(a, n.strokes(7)[0])
        assertSame(b, n.strokes(7)[1])
        assertArrayEquals(intArrayOf(2, 7), n.pagesWithInk())
        assertEquals(1, b.pointCount)
        assertEquals(2, a.pointCount)
    }

    @Test
    fun bookmarkOnlyIsNotEmpty() {
        val n = PdfNotes()
        n.toggleBookmark(1)
        assertFalse(n.isEmpty)
        assertEquals(0, n.pagesWithInk().size)
    }

    // ---- erase ----

    @Test
    fun eraseHitsBySegmentDistance() {
        val n = PdfNotes()
        n.add(0, stroke(0f, 0f, 100f, 0f, width = 0f))
        // Above the middle of the segment, far from both endpoints.
        assertFalse(n.eraseAt(0, 50f, 10f, 5f))
        assertTrue(n.eraseAt(0, 50f, 4f, 5f))
        assertTrue(n.strokes(0).isEmpty())
        assertEquals(0, n.pagesWithInk().size)
    }

    @Test
    fun eraseIncludesHalfStrokeWidth() {
        val n = PdfNotes()
        n.add(0, stroke(0f, 0f, 100f, 0f, width = 10f))
        assertFalse(n.eraseAt(0, 50f, 8f, 2f)) // reach = 2 + 5 = 7
        assertTrue(n.eraseAt(0, 50f, 7f, 2f))
    }

    @Test
    fun erasePastSegmentEndUsesEndpointDistance() {
        val n = PdfNotes()
        n.add(0, stroke(0f, 0f, 10f, 0f, width = 0f))
        assertFalse(n.eraseAt(0, 20f, 0f, 5f))
        assertTrue(n.eraseAt(0, 14f, 0f, 5f))
    }

    @Test
    fun eraseSinglePointStroke() {
        val n = PdfNotes()
        n.add(0, stroke(10f, 10f, width = 2f))
        assertFalse(n.eraseAt(0, 20f, 10f, 5f))
        assertTrue(n.eraseAt(0, 15f, 10f, 4f)) // 5 <= 4 + 1
    }

    @Test
    fun eraseMissesOtherPageAndEmptyPage() {
        val n = PdfNotes()
        n.add(1, stroke(0f, 0f, 10f, 10f))
        assertFalse(n.eraseAt(2, 5f, 5f, 50f))
        assertEquals(1, n.strokes(1).size)
        assertFalse(n.canUndo.not())
    }

    @Test
    fun multiEraseIsOneUndoStepAndRestoresOrder() {
        val n = PdfNotes()
        val s0 = stroke(0f, 0f, 10f, 0f)
        val s1 = stroke(0f, 100f, 10f, 100f)
        val s2 = stroke(0f, 1f, 10f, 1f)
        val s3 = stroke(0f, 200f, 10f, 200f)
        val s4 = stroke(0f, 2f, 10f, 2f)
        for (s in listOf(s0, s1, s2, s3, s4)) n.add(4, s)
        assertTrue(n.eraseAt(4, 5f, 0f, 3f))
        assertEquals(listOf(s1, s3), n.strokes(4))
        assertEquals(4, n.undo())
        val list = n.strokes(4)
        assertEquals(5, list.size)
        for ((i, s) in listOf(s0, s1, s2, s3, s4).withIndex()) assertSame(s, list[i])
        // The next undo reverts the last add, proving the erase was a single step.
        assertEquals(4, n.undo())
        assertEquals(4, n.strokes(4).size)
        assertFalse(n.strokes(4).contains(s4))
    }

    @Test
    fun eraseEverythingThenUndoRestoresPage() {
        val n = PdfNotes()
        val s = stroke(0f, 0f, 1f, 1f)
        n.add(3, s)
        assertTrue(n.eraseAt(3, 0f, 0f, 1f))
        assertEquals(0, n.pagesWithInk().size)
        assertEquals(3, n.undo())
        assertSame(s, n.strokes(3)[0])
    }

    // ---- undo ----

    @Test
    fun undoOfAdd() {
        val n = PdfNotes()
        assertFalse(n.canUndo)
        assertEquals(-1, n.undo())
        val a = stroke(0f, 0f, 1f, 1f)
        val b = stroke(2f, 2f, 3f, 3f)
        n.add(5, a)
        n.add(5, b)
        assertTrue(n.canUndo)
        assertEquals(5, n.undo())
        assertEquals(listOf(a), n.strokes(5))
        assertEquals(5, n.undo())
        assertTrue(n.isEmpty)
        assertFalse(n.canUndo)
        assertEquals(-1, n.undo())
    }

    @Test
    fun historyCappedAt100Units() {
        val n = PdfNotes()
        for (i in 0 until 105) n.add(0, stroke(i.toFloat(), 0f))
        var undone = 0
        while (n.canUndo) {
            n.undo()
            undone++
        }
        assertEquals(100, undone)
        assertEquals(5, n.strokes(0).size)
        assertEquals(0f, n.strokes(0)[0].points[0], 0f)
    }

    @Test
    fun bookmarkIsNotAnUndoStep() {
        val n = PdfNotes()
        n.toggleBookmark(1)
        assertFalse(n.canUndo)
    }

    // ---- bookmarks ----

    @Test
    fun bookmarksToggleAndSort() {
        val n = PdfNotes()
        assertTrue(n.toggleBookmark(9))
        assertTrue(n.toggleBookmark(2))
        assertTrue(n.toggleBookmark(5))
        assertArrayEquals(intArrayOf(2, 5, 9), n.bookmarks())
        assertTrue(n.isBookmarked(5))
        assertFalse(n.toggleBookmark(5))
        assertFalse(n.isBookmarked(5))
        assertArrayEquals(intArrayOf(2, 9), n.bookmarks())
        assertFalse(n.isBookmarked(100))
    }

    // ---- dirty ----

    @Test
    fun dirtyFlag() {
        val n = PdfNotes()
        assertFalse(n.dirty)
        n.add(0, stroke(0f, 0f, 5f, 5f))
        assertTrue(n.dirty)
        n.dirty = false
        assertFalse(n.eraseAt(0, 500f, 500f, 1f))
        assertFalse(n.dirty)
        assertTrue(n.eraseAt(0, 0f, 0f, 1f))
        assertTrue(n.dirty)
        n.dirty = false
        n.undo()
        assertTrue(n.dirty)
        n.dirty = false
        n.undo() // nothing left to undo
        n.toggleBookmark(1)
        assertTrue(n.dirty)
        assertFalse(PdfNotes.fromJson(n.toJson()).dirty)
    }

    // ---- JSON ----

    @Test
    fun emptyJson() {
        val j = PdfNotes().toJson()
        assertEquals("{\"v\":1,\"bookmarks\":[],\"pages\":{}}", j)
        assertTrue(PdfNotes.fromJson(j).isEmpty)
    }

    @Test
    fun jsonRoundTripRoundsToTwoDecimals() {
        val n = PdfNotes()
        n.add(3, stroke(1.234f, 5.678f, 10f, -2.5f, width = 1.506f, tool = InkTool.HIGHLIGHTER, color = 0x80FFEB3B.toInt()))
        n.add(3, stroke(0.004f, 100f, tool = InkTool.PEN, color = 0xFF112233.toInt()))
        n.add(0, stroke(7f, 8f, 9f, 10f, width = 0.5f))
        n.toggleBookmark(12)
        n.toggleBookmark(4)
        val j = n.toJson()
        val back = PdfNotes.fromJson(j)
        assertArrayEquals(intArrayOf(4, 12), back.bookmarks())
        assertArrayEquals(intArrayOf(0, 3), back.pagesWithInk())
        val s = back.strokes(3)
        assertEquals(2, s.size)
        assertEquals(InkTool.HIGHLIGHTER, s[0].tool)
        assertEquals(0x80FFEB3B.toInt(), s[0].color)
        assertEquals(1.51f, s[0].width, 0.0051f)
        assertArrayEquals(floatArrayOf(1.23f, 5.68f, 10f, -2.5f), s[0].points, 0.0001f)
        assertEquals(0xFF112233.toInt(), s[1].color)
        assertArrayEquals(floatArrayOf(0f, 100f), s[1].points, 0.0001f)
        assertEquals(0.5f, back.strokes(0)[0].width, 0.0001f)
        // Serialising again is stable.
        assertEquals(back.toJson(), PdfNotes.fromJson(back.toJson()).toJson())
        assertTrue(j.contains("\"p\":[1.23,5.68,10,-2.5]"))
    }

    @Test
    fun tolerantGarbage() {
        for (g in listOf("", "   ", "not json", "[1,2,3]", "{", "null", "{\"v\":1,\"pages\":5,\"bookmarks\":\"x\"}")) {
            val n = PdfNotes.fromJson(g)
            assertTrue("garbage: $g", n.isEmpty)
            assertFalse(n.dirty)
        }
    }

    @Test
    fun tolerantMissingKeys() {
        val onlyMarks = PdfNotes.fromJson("{\"bookmarks\":[3,1]}")
        assertArrayEquals(intArrayOf(1, 3), onlyMarks.bookmarks())
        val onlyPages = PdfNotes.fromJson("{\"pages\":{\"2\":[{\"t\":0,\"c\":-1,\"w\":1,\"p\":[1,2]}]}}")
        assertEquals(1, onlyPages.strokes(2).size)
        assertEquals(-1, onlyPages.strokes(2)[0].color)
    }

    @Test
    fun tolerantBadStrokesAndPages() {
        val json = "{\"v\":1,\"bookmarks\":[-1,2,\"x\",null,3.5],\"pages\":{" +
            "\"-3\":[{\"t\":0,\"c\":1,\"w\":1,\"p\":[1,2]}]," +
            "\"abc\":[{\"t\":0,\"c\":1,\"w\":1,\"p\":[1,2]}]," +
            "\"1\":[" +
            "{\"t\":0,\"c\":1,\"w\":1,\"p\":[1,2,3]}," + // odd length
            "{\"t\":0,\"c\":1,\"w\":1,\"p\":[]}," + // no points
            "{\"t\":0,\"c\":1,\"w\":1}," + // no p
            "{\"t\":0,\"c\":1,\"p\":[1,2]}," + // no w
            "{\"c\":1,\"w\":1,\"p\":[1,2]}," + // no t
            "{\"t\":9,\"c\":1,\"w\":1,\"p\":[1,2]}," + // unknown tool
            "{\"t\":0,\"c\":1,\"w\":-4,\"p\":[1,2]}," + // negative width
            "{\"t\":0,\"c\":1,\"w\":1,\"p\":[1,\"a\"]}," + // non-numeric point
            "7," +
            "{\"t\":1,\"c\":5,\"w\":3,\"p\":[1,2,3,4]}" + // the only good one
            "]," +
            "\"4\":\"nope\"}}"
        val n = PdfNotes.fromJson(json)
        assertArrayEquals(intArrayOf(2), n.bookmarks())
        assertArrayEquals(intArrayOf(1), n.pagesWithInk())
        val s = n.strokes(1)
        assertEquals(1, s.size)
        assertEquals(InkTool.HIGHLIGHTER, s[0].tool)
        assertArrayEquals(floatArrayOf(1f, 2f, 3f, 4f), s[0].points, 0f)
        assertFalse(n.dirty)
        assertFalse(n.canUndo)
    }

    // ---- store ----

    private fun tempDir(): File = Files.createTempDirectory("pdfnotes").toFile()

    @Test
    fun storeSaveLoadDelete() {
        val root = tempDir()
        val dir = File(root, "nested/notes") // not created yet
        try {
            assertEquals(File(dir, "42.json"), PdfNotesStore.file(dir, 42L))
            assertTrue(PdfNotesStore.load(dir, 42L).isEmpty)

            val n = PdfNotes()
            n.add(1, stroke(1f, 2f, 3f, 4f))
            n.toggleBookmark(6)
            assertTrue(PdfNotesStore.save(dir, 42L, n.toJson()))
            assertTrue(PdfNotesStore.file(dir, 42L).isFile)
            assertEquals(listOf("42.json"), dir.list()!!.toList())
            val back = PdfNotesStore.load(dir, 42L)
            assertEquals(n.toJson(), back.toJson())
            assertFalse(back.dirty)

            // Overwrite works.
            assertTrue(PdfNotesStore.save(dir, 42L, PdfNotes().toJson()))
            assertTrue(PdfNotesStore.load(dir, 42L).isEmpty)

            // Other book is independent.
            assertTrue(PdfNotesStore.load(dir, 43L).isEmpty)

            assertTrue(PdfNotesStore.save(dir, 42L, null))
            assertFalse(PdfNotesStore.file(dir, 42L).exists())
            assertTrue(PdfNotesStore.save(dir, 42L, null)) // deleting a missing file is fine
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun storeCorruptFileLoadsEmpty() {
        val dir = tempDir()
        try {
            PdfNotesStore.file(dir, 1L).writeText("{{{ definitely not json")
            val n = PdfNotesStore.load(dir, 1L)
            assertNotNull(n)
            assertTrue(n.isEmpty)
            PdfNotesStore.file(dir, 2L).writeBytes(byteArrayOf(0, -1, -2, 7))
            assertTrue(PdfNotesStore.load(dir, 2L).isEmpty)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun storeSaveFailsGracefully() {
        val root = tempDir()
        try {
            val blocker = File(root, "file")
            blocker.writeText("x")
            // dir is a regular file: cannot create or write inside it.
            assertFalse(PdfNotesStore.save(blocker, 1L, "{}"))
        } finally {
            root.deleteRecursively()
        }
    }

    // ---- InkMath ----

    @Test
    fun simplifyDropsClosePoints() {
        val pts = floatArrayOf(0f, 0f, 1f, 0f, 2f, 0f, 5f, 0f, 5.5f, 0f, 6f, 0f)
        val out = InkMath.simplify(pts, 6, 3f)
        assertArrayEquals(floatArrayOf(0f, 0f, 5f, 0f, 6f, 0f), out, 0f)
    }

    @Test
    fun simplifyKeepsFirstAndLastAlways() {
        val pts = floatArrayOf(0f, 0f, 0.1f, 0f, 0.2f, 0f)
        assertArrayEquals(floatArrayOf(0f, 0f, 0.2f, 0f), InkMath.simplify(pts, 3, 10f), 0f)
        assertArrayEquals(pts, InkMath.simplify(pts, 3, 0f), 0f)
    }

    @Test
    fun simplifyEdgeCases() {
        assertEquals(0, InkMath.simplify(FloatArray(0), 0, 1f).size)
        assertEquals(0, InkMath.simplify(floatArrayOf(1f, 2f), 0, 1f).size)
        assertArrayEquals(floatArrayOf(1f, 2f), InkMath.simplify(floatArrayOf(1f, 2f, 3f, 4f), 1, 1f), 0f)
        // count larger than the data is clamped; count smaller ignores the tail.
        assertArrayEquals(floatArrayOf(1f, 2f, 3f, 4f), InkMath.simplify(floatArrayOf(1f, 2f, 3f, 4f), 9, 0.5f), 0f)
        val tail = floatArrayOf(0f, 0f, 10f, 0f, 99f, 99f)
        assertArrayEquals(floatArrayOf(0f, 0f, 10f, 0f), InkMath.simplify(tail, 2, 1f), 0f)
        // The result is a fresh array.
        val src = floatArrayOf(0f, 0f, 10f, 0f)
        assertTrue(src !== InkMath.simplify(src, 2, 1f))
    }

    @Test
    fun distToSegment() {
        assertEquals(5f, InkMath.distToSegment(5f, 5f, 0f, 0f, 10f, 0f), 1e-5f)
        assertEquals(5f, InkMath.distToSegment(-3f, 4f, 0f, 0f, 10f, 0f), 1e-5f) // beyond A
        assertEquals(5f, InkMath.distToSegment(13f, 4f, 0f, 0f, 10f, 0f), 1e-5f) // beyond B
        assertEquals(0f, InkMath.distToSegment(4f, 0f, 0f, 0f, 10f, 0f), 1e-5f)
        assertEquals(5f, InkMath.distToSegment(3f, 4f, 0f, 0f, 0f, 0f), 1e-5f) // degenerate
        assertEquals(Math.sqrt(2.0).toFloat(), InkMath.distToSegment(0f, 2f, 0f, 0f, 2f, 2f), 1e-5f)
    }

    @Test
    fun containsEvenOdd() {
        val square = floatArrayOf(0f, 0f, 10f, 0f, 10f, 10f, 0f, 10f)
        assertTrue(InkMath.contains(square, 4, 5f, 5f))
        assertFalse(InkMath.contains(square, 4, 15f, 5f))
        assertFalse(InkMath.contains(square, 4, 5f, -1f))
        assertFalse(InkMath.contains(square, 2, 5f, 5f))
        assertFalse(InkMath.contains(square, 0, 5f, 5f))
        // Triangle (concave check with the tail ignored): only the first n points count.
        val tri = floatArrayOf(0f, 0f, 10f, 0f, 0f, 10f, 100f, 100f)
        assertTrue(InkMath.contains(tri, 3, 2f, 2f))
        assertFalse(InkMath.contains(tri, 3, 8f, 8f))
        // Concave "C" shape: the notch is outside.
        val c = floatArrayOf(0f, 0f, 10f, 0f, 10f, 3f, 3f, 3f, 3f, 7f, 10f, 7f, 10f, 10f, 0f, 10f)
        assertTrue(InkMath.contains(c, 8, 1f, 5f))
        assertFalse(InkMath.contains(c, 8, 6f, 5f))
        // Self-crossing bow tie: even-odd leaves the loops filled, centre-crossing area is consistent.
        val bow = floatArrayOf(0f, 0f, 10f, 10f, 10f, 0f, 0f, 10f)
        assertTrue(InkMath.contains(bow, 4, 9f, 5f))
        assertFalse(InkMath.contains(bow, 4, 5f, 1f))
    }

    @Test
    fun bounds() {
        assertArrayEquals(floatArrayOf(3f, 4f, 3f, 4f), InkMath.bounds(floatArrayOf(3f, 4f), 1), 0f)
        assertArrayEquals(
            floatArrayOf(-2f, -1f, 9f, 8f),
            InkMath.bounds(floatArrayOf(1f, 8f, -2f, 3f, 9f, -1f, 100f, 100f), 3),
            0f,
        )
        assertArrayEquals(floatArrayOf(0f, 0f, 0f, 0f), InkMath.bounds(FloatArray(0), 0), 0f)
    }

    @Test
    fun groupedChangesUndoAsOneStep() {
        val n = PdfNotes()
        val keep = InkStroke(InkTool.PEN, 0, 1f, floatArrayOf(0f, 0f, 10f, 0f))
        n.add(0, keep)
        n.add(0, InkStroke(InkTool.PEN, 0, 1f, floatArrayOf(0f, 50f, 10f, 50f)))
        n.add(0, InkStroke(InkTool.PEN, 0, 1f, floatArrayOf(0f, 100f, 10f, 100f)))
        // An eraser drag: two erases in one group.
        n.beginGroup()
        assertTrue(n.eraseAt(0, 5f, 50f, 2f))
        assertTrue(n.eraseAt(0, 5f, 100f, 2f))
        n.endGroup()
        assertEquals(1, n.strokes(0).size)
        assertEquals(0, n.undo())
        assertEquals(3, n.strokes(0).size)
        // A highlight over two lines: two adds in one group.
        n.beginGroup()
        n.add(1, InkStroke(InkTool.HIGHLIGHTER, 0, 10f, floatArrayOf(0f, 0f, 100f, 0f)))
        n.add(1, InkStroke(InkTool.HIGHLIGHTER, 0, 10f, floatArrayOf(0f, 20f, 100f, 20f)))
        n.endGroup()
        assertEquals(1, n.undo())
        assertTrue(n.strokes(1).isEmpty())
        // Ungrouped steps still undo one at a time.
        assertEquals(0, n.undo())
        assertEquals(2, n.strokes(0).size)
        assertTrue(n.strokes(0)[0] === keep)
    }

    @Test
    fun pressuresRoundTripAndAreOptional() {
        val n = PdfNotes()
        n.add(2, InkStroke(InkTool.PEN, 0xFF000000.toInt(), 2f, floatArrayOf(0f, 0f, 5f, 5f), floatArrayOf(0.25f, 0.875f)))
        n.add(2, InkStroke(InkTool.PEN, 0xFF000000.toInt(), 2f, floatArrayOf(1f, 1f)))
        val back = PdfNotes.fromJson(n.toJson())
        val list = back.strokes(2)
        assertEquals(2, list.size)
        assertArrayEquals(floatArrayOf(0.25f, 0.88f), list[0].pressures!!, 1e-4f)
        assertEquals(null, list[1].pressures)
        // A pressure list of the wrong length is dropped, the stroke kept.
        val odd = PdfNotes.fromJson("{\"v\":1,\"pages\":{\"0\":[{\"t\":0,\"c\":0,\"w\":1,\"p\":[0,0,1,1],\"q\":[0.5]}]}}")
        assertEquals(1, odd.strokes(0).size)
        assertEquals(null, odd.strokes(0)[0].pressures)
    }

    @Test
    fun clearPageUndoesAndClearAllKeepsBookmarks() {
        val n = PdfNotes()
        val a = InkStroke(InkTool.PEN, 0, 1f, floatArrayOf(0f, 0f))
        val b = InkStroke(InkTool.PEN, 0, 1f, floatArrayOf(5f, 5f))
        n.add(3, a)
        n.add(3, b)
        n.add(4, InkStroke(InkTool.PEN, 0, 1f, floatArrayOf(1f, 1f)))
        n.toggleBookmark(4)
        assertTrue(n.clearPage(3))
        assertTrue(n.strokes(3).isEmpty())
        assertFalse(n.clearPage(3))
        assertEquals(3, n.undo())
        assertTrue(n.strokes(3)[0] === a && n.strokes(3)[1] === b)
        assertEquals(3, n.clearAllInk())
        assertTrue(n.pagesWithInk().isEmpty())
        assertFalse(n.canUndo)
        assertTrue(n.isBookmarked(4))
        assertEquals(0, n.clearAllInk())
    }

    // ---- eraser: bounding box ----

    /** The eraser as it was before the bounding box: every segment of every stroke. */
    private fun bruteHit(s: InkStroke, x: Float, y: Float, radius: Float): Boolean {
        val p = s.points
        val reach = radius + s.width * 0.5f
        val n = p.size / 2
        if (n == 0) return false
        if (n == 1) return InkMath.distToSegment(x, y, p[0], p[1], p[0], p[1]) <= reach
        for (i in 0 until n - 1) {
            val o = i * 2
            if (InkMath.distToSegment(x, y, p[o], p[o + 1], p[o + 2], p[o + 3]) <= reach) return true
        }
        return false
    }

    @Test
    fun boxSkipsFarStrokesAndKeepsNearOnes() {
        // The box of these points is x 10..50, y 10..60; mayReach takes the full reach (radius + half the width).
        val s = stroke(10f, 10f, 50f, 30f, 20f, 60f, width = 4f)
        assertTrue(s.mayReach(30f, 30f, 3f))
        assertTrue(s.mayReach(8f, 10f, 2f)) // 2 left of the box, exactly the reach
        assertTrue(s.mayReach(52f, 62f, 2f))
        assertFalse(s.mayReach(7f, 10f, 2f))
        assertFalse(s.mayReach(200f, 30f, 7f))
        assertFalse(s.mayReach(30f, -100f, 7f))
        assertFalse(s.mayReach(30f, 70f, 7f)) // 10 below the box
        assertTrue(s.mayReach(30f, 70f, 10f))
        assertFalse(stroke().mayReach(0f, 0f, 100f)) // no points: nothing to reach
    }

    @Test
    fun farStrokeIsNotHitAndNearOneIs() {
        val n = PdfNotes()
        val near = stroke(0f, 0f, 100f, 0f, width = 2f)
        val far = stroke(0f, 500f, 100f, 500f, width = 2f)
        n.add(0, near)
        n.add(0, far)
        assertFalse(n.eraseAt(0, 50f, 300f, 10f))
        assertEquals(2, n.strokes(0).size)
        assertTrue(n.eraseAt(0, 50f, 6f, 5f))
        assertEquals(listOf(far), n.strokes(0))
        assertEquals(0, n.undo())
        assertEquals(listOf(near, far), n.strokes(0))
    }

    @Test
    fun eraseAtTheEdgeOfReachStillHits() {
        val n = PdfNotes()
        n.add(0, stroke(0f, 0f, 100f, 0f, width = 0f))
        assertTrue(n.eraseAt(0, 50f, 5f, 5f)) // distance 5 <= reach 5, right on the box edge
    }

    @Test
    fun strokeWithNanPointIsStillJudgedByTheExactTest() {
        val n = PdfNotes()
        n.add(0, stroke(0f, 0f, 10f, 0f, Float.NaN, Float.NaN, width = 0f))
        assertFalse(n.eraseAt(0, 50f, 50f, 2f))
        assertTrue(n.eraseAt(0, 5f, 1f, 2f))
    }

    @Test
    fun boxedEraserEqualsBruteForceOnRandomStrokes() {
        val rnd = Random(20260412L)
        var hitQueries = 0
        for (round in 0 until 30) {
            val n = PdfNotes()
            for (k in 0 until 60) {
                val pts = FloatArray((1 + rnd.nextInt(8)) * 2) { rnd.nextFloat() * 400f }
                n.add(round % 3, InkStroke(InkTool.PEN, 0, rnd.nextFloat() * 12f, pts))
            }
            val page = round % 3
            for (q in 0 until 120) {
                val x = rnd.nextFloat() * 440f - 20f
                val y = rnd.nextFloat() * 440f - 20f
                val r = rnd.nextFloat() * 25f
                val before = ArrayList(n.strokes(page))
                val expectedGone = before.filter { bruteHit(it, x, y, r) }
                val expectedKept = before.filterNot { bruteHit(it, x, y, r) }
                val removed = n.eraseAt(page, x, y, r)
                assertEquals(expectedGone.isNotEmpty(), removed)
                val after = n.strokes(page)
                assertEquals(expectedKept.size, after.size)
                for (i in expectedKept.indices) assertSame(expectedKept[i], after[i])
                if (removed) hitQueries++
            }
        }
        // Sanity: the random set really exercised both outcomes.
        assertTrue(hitQueries > 50)
    }

    @Test
    fun eraseMissLeavesHistoryAndListAlone() {
        val n = PdfNotes()
        n.add(0, stroke(0f, 0f, 10f, 10f))
        val list = n.strokes(0)
        assertFalse(n.eraseAt(0, 300f, 300f, 4f))
        assertSame(list, n.strokes(0))
        assertEquals(0, n.undo()) // only the add is in the history
        assertFalse(n.canUndo)
    }

    // ---- redo ----

    @Test
    fun redoPutsBackWhatUndoRevertedInOrder() {
        val n = PdfNotes()
        assertFalse(n.canRedo)
        assertEquals(-1, n.redo())
        val a = stroke(0f, 0f)
        val b = stroke(1f, 1f)
        val c = stroke(2f, 2f)
        n.add(5, a)
        n.add(5, b)
        n.add(6, c)
        assertFalse(n.canRedo)
        assertEquals(6, n.undo())
        assertEquals(5, n.undo())
        assertTrue(n.canRedo)
        assertEquals(listOf(a), n.strokes(5))
        n.dirty = false
        assertEquals(5, n.redo()) // b
        assertTrue(n.dirty)
        assertEquals(listOf(a, b), n.strokes(5))
        assertTrue(n.canRedo)
        assertEquals(6, n.redo()) // c
        assertSame(c, n.strokes(6)[0])
        assertFalse(n.canRedo)
        assertEquals(-1, n.redo())
        // Redone steps are undoable again.
        assertEquals(6, n.undo())
        assertTrue(n.strokes(6).isEmpty())
        assertEquals(6, n.redo())
        assertSame(c, n.strokes(6)[0])
    }

    @Test
    fun redoOfAnEraseRemovesTheSameStrokesAgain() {
        val n = PdfNotes()
        val s0 = stroke(0f, 0f, 10f, 0f)
        val s1 = stroke(0f, 100f, 10f, 100f)
        val s2 = stroke(0f, 1f, 10f, 1f)
        val s3 = stroke(0f, 200f, 10f, 200f)
        for (s in listOf(s0, s1, s2, s3)) n.add(4, s)
        assertTrue(n.eraseAt(4, 5f, 0f, 3f))
        assertEquals(listOf(s1, s3), n.strokes(4))
        assertEquals(4, n.undo())
        assertEquals(listOf(s0, s1, s2, s3), n.strokes(4))
        assertEquals(4, n.redo())
        assertEquals(listOf(s1, s3), n.strokes(4))
        // And back once more: the original order is restored again.
        assertEquals(4, n.undo())
        assertEquals(listOf(s0, s1, s2, s3), n.strokes(4))
    }

    @Test
    fun redoOfClearPageAndEraseEverything() {
        val n = PdfNotes()
        val a = stroke(0f, 0f)
        val b = stroke(5f, 5f)
        n.add(3, a)
        n.add(3, b)
        assertTrue(n.clearPage(3))
        assertEquals(3, n.undo())
        assertEquals(listOf(a, b), n.strokes(3))
        assertEquals(3, n.redo())
        assertTrue(n.strokes(3).isEmpty())
        assertEquals(0, n.pagesWithInk().size)
        assertEquals(3, n.undo())
        assertEquals(listOf(a, b), n.strokes(3))
    }

    @Test
    fun redoRestoresAWholeGroupAtOnce() {
        val n = PdfNotes()
        n.add(0, InkStroke(InkTool.PEN, 0, 1f, floatArrayOf(0f, 0f, 10f, 0f)))
        n.add(0, InkStroke(InkTool.PEN, 0, 1f, floatArrayOf(0f, 50f, 10f, 50f)))
        n.add(0, InkStroke(InkTool.PEN, 0, 1f, floatArrayOf(0f, 100f, 10f, 100f)))
        n.beginGroup()
        assertTrue(n.eraseAt(0, 5f, 50f, 2f))
        assertTrue(n.eraseAt(0, 5f, 100f, 2f))
        n.endGroup()
        assertEquals(1, n.strokes(0).size)
        assertEquals(0, n.undo())
        assertEquals(3, n.strokes(0).size)
        assertEquals(0, n.redo())
        assertEquals(1, n.strokes(0).size)
        assertFalse(n.canRedo)
        // The group is one undo step again.
        assertEquals(0, n.undo())
        assertEquals(3, n.strokes(0).size)

        // A group of adds comes back in its original order.
        val h1 = InkStroke(InkTool.HIGHLIGHTER, 0, 10f, floatArrayOf(0f, 0f, 100f, 0f))
        val h2 = InkStroke(InkTool.HIGHLIGHTER, 0, 10f, floatArrayOf(0f, 20f, 100f, 20f))
        n.beginGroup()
        n.add(1, h1)
        n.add(1, h2)
        n.endGroup()
        assertEquals(1, n.undo())
        assertTrue(n.strokes(1).isEmpty())
        assertEquals(1, n.redo())
        assertEquals(listOf(h1, h2), n.strokes(1))
    }

    @Test
    fun anyNewEditEndsTheRedo() {
        fun undone(): PdfNotes {
            val n = PdfNotes()
            n.add(0, stroke(0f, 0f, 10f, 0f))
            n.add(0, stroke(0f, 50f, 10f, 50f))
            n.undo()
            assertTrue(n.canRedo)
            return n
        }
        val add = undone()
        add.add(0, stroke(1f, 1f))
        assertFalse(add.canRedo)
        assertEquals(-1, add.redo())

        val erase = undone()
        assertTrue(erase.eraseAt(0, 5f, 0f, 2f))
        assertFalse(erase.canRedo)
        assertEquals(-1, erase.redo())

        val clearPage = undone()
        assertTrue(clearPage.clearPage(0))
        assertFalse(clearPage.canRedo)

        val clearAll = undone()
        assertEquals(1, clearAll.clearAllInk())
        assertFalse(clearAll.canRedo)
        assertFalse(clearAll.canUndo)
        assertEquals(-1, clearAll.redo())
    }

    @Test
    fun editsThatChangeNothingKeepTheRedo() {
        val n = PdfNotes()
        n.add(0, stroke(0f, 0f, 10f, 0f))
        n.add(0, stroke(0f, 50f, 10f, 50f))
        n.undo()
        assertFalse(n.eraseAt(0, 500f, 500f, 2f)) // a miss
        assertFalse(n.clearPage(9)) // an empty page
        n.toggleBookmark(2) // not an undo step
        assertTrue(n.canRedo)
        assertEquals(0, n.redo())
        assertEquals(2, n.strokes(0).size)
        // Nothing to undo / redo on an empty history leaves things alone.
        val e = PdfNotes()
        assertEquals(-1, e.undo())
        assertEquals(-1, e.redo())
        assertFalse(e.dirty)
    }

    @Test
    fun undoThenRedoIsByteIdenticalOnDisk() {
        val n = PdfNotes()
        n.add(1, InkStroke(InkTool.PEN, 0xFF000000.toInt(), 2f, floatArrayOf(1f, 2f, 3f, 4f), floatArrayOf(0.5f, 0.75f)))
        n.add(1, InkStroke(InkTool.HIGHLIGHTER, 0x80FFEB3B.toInt(), 9f, floatArrayOf(0f, 0f, 50f, 0f)))
        n.add(2, InkStroke(InkTool.PEN, 0xFF112233.toInt(), 1f, floatArrayOf(7f, 7f)))
        n.eraseAt(1, 25f, 0f, 2f)
        val before = n.toJson()
        while (n.canUndo) n.undo()
        assertTrue(n.isEmpty)
        while (n.canRedo) n.redo()
        assertEquals(before, n.toJson())
    }

    // ---- history cap: 100 undo units, a group counts once ----

    /** How many undo() calls it takes to empty the history (one per unit). */
    private fun undoCalls(n: PdfNotes): Int {
        var calls = 0
        while (n.canUndo) {
            n.undo()
            calls++
        }
        return calls
    }

    @Test
    fun capDropsTheOldestWholeGroupNeverHalfOfOne() {
        val n = PdfNotes()
        // An old eraser-drag-like group of 30 steps (adds here) = 1 unit, then 100 single steps: 101 units.
        n.beginGroup()
        for (i in 0 until 30) n.add(0, stroke(i.toFloat(), 0f))
        n.endGroup()
        for (i in 0 until 100) n.add(1, stroke(i.toFloat(), 1f))
        // The whole group left the history (not 1 of its 30 steps): 100 singles undo, its 30 strokes stay.
        assertEquals(100, undoCalls(n))
        assertEquals(30, n.strokes(0).size)
        assertTrue(n.strokes(1).isEmpty())
    }

    @Test
    fun aGroupCountsAsOneUnitNotAsItsSteps() {
        val n = PdfNotes()
        // 100 groups of 3 steps = 300 steps but 100 units: nothing is dropped.
        for (g in 0 until 100) {
            n.beginGroup()
            for (i in 0 until 3) n.add(g, stroke(i.toFloat(), 0f))
            n.endGroup()
        }
        assertEquals(100, undoCalls(n))
        for (g in 0 until 100) assertTrue(n.strokes(g).isEmpty())
    }

    @Test
    fun capTrimsSeveralOldGroupsUntilItFits() {
        val n = PdfNotes()
        // 60 groups of 2 steps (60 units), then 60 single steps: 120 units, the 20 oldest groups go.
        for (g in 0 until 60) {
            n.beginGroup()
            for (i in 0 until 2) n.add(g, stroke(i.toFloat(), 0f))
            n.endGroup()
        }
        for (i in 0 until 60) n.add(100, stroke(i.toFloat(), 0f))
        assertEquals(100, undoCalls(n))
        for (g in 0 until 20) assertEquals(2, n.strokes(g).size) // out of the history, never half undone
        for (g in 20 until 60) assertTrue(n.strokes(g).isEmpty())
        assertTrue(n.strokes(100).isEmpty())
    }

    @Test
    fun aLongEraserDragSurvivesTheNextEdit() {
        val n = PdfNotes()
        for (i in 0 until 130) n.add(0, stroke(i.toFloat() * 10f, 0f, i.toFloat() * 10f + 5f, 0f, width = 1f))
        n.beginGroup()
        for (i in 0 until 130) assertTrue(n.eraseAt(0, i.toFloat() * 10f + 2f, 0f, 1f)) // 130 erase steps, 1 unit
        n.endGroup()
        assertTrue(n.strokes(0).isEmpty())
        // Further edits do not push the drag out of the history.
        n.add(1, stroke(0f, 0f))
        n.add(1, stroke(1f, 1f))
        assertEquals(1, n.undo())
        assertEquals(1, n.undo())
        assertEquals(0, n.undo()) // the whole drag at once
        assertEquals(130, n.strokes(0).size)
    }

    @Test
    fun theGroupBeingBuiltIsNeverCut() {
        val n = PdfNotes()
        n.beginGroup()
        for (i in 0 until 130) n.add(0, stroke(i.toFloat(), 0f))
        n.endGroup()
        assertEquals(130, n.strokes(0).size)
        // One undo takes the whole over-long group back, nothing half.
        assertEquals(0, n.undo())
        assertTrue(n.strokes(0).isEmpty())
        assertFalse(n.canUndo)
        assertTrue(n.canRedo)
        assertEquals(0, n.redo())
        assertEquals(130, n.strokes(0).size)
    }

    @Test
    fun theOpenGroupStaysWhileOlderUnitsAreDropped() {
        val n = PdfNotes()
        for (i in 0 until 100) n.add(0, stroke(i.toFloat(), 0f))
        n.beginGroup()
        for (i in 0 until 5) n.add(1, stroke(i.toFloat(), 1f)) // 101st unit: the oldest single goes
        n.endGroup()
        assertEquals(100, undoCalls(n)) // 99 singles + the whole group
        assertTrue(n.strokes(1).isEmpty())
        assertEquals(1, n.strokes(0).size)
    }

    @Test
    fun ungroupedCapStillExactlyOneHundred() {
        val n = PdfNotes()
        for (i in 0 until 250) n.add(0, stroke(i.toFloat(), 0f))
        assertEquals(100, undoCalls(n))
        assertEquals(150, n.strokes(0).size)
    }

    @Test
    fun twoGroupsInARowAreTwoUnits() {
        val n = PdfNotes()
        for (g in 0 until 2) {
            n.beginGroup()
            n.add(g, stroke(0f, 0f))
            n.add(g, stroke(1f, 1f))
            n.endGroup()
        }
        assertEquals(1, n.undo()) // only the second group
        assertTrue(n.strokes(1).isEmpty())
        assertEquals(2, n.strokes(0).size)
        assertEquals(0, n.undo())
        assertTrue(n.strokes(0).isEmpty())
        assertFalse(n.canUndo)
    }

    @Test
    fun redoneStepsReturnToTheCappedHistory() {
        val n = PdfNotes()
        for (i in 0 until 100) n.add(0, stroke(i.toFloat(), 0f))
        for (i in 0 until 40) n.undo()
        for (i in 0 until 40) assertEquals(0, n.redo())
        assertFalse(n.canRedo)
        // Back to 100 units: one more edit still trims exactly one.
        n.add(0, stroke(500f, 0f))
        assertEquals(100, undoCalls(n))
    }

    // ---- snapshot (saved off the main thread) ----

    @Test
    fun snapshotJsonIsTheNotesJsonByteForByte() {
        val n = PdfNotes()
        n.toggleBookmark(3)
        n.toggleBookmark(1)
        n.add(2, InkStroke(InkTool.PEN, 0xFF000000.toInt(), 1.5f, floatArrayOf(1f, 2f, 3.25f, 4.5f), floatArrayOf(0.25f, 0.5f)))
        n.add(0, InkStroke(InkTool.HIGHLIGHTER, 0x80FFEB3B.toInt(), 11f, floatArrayOf(0.004f, 10f, -5.678f, 10f)))
        val expected = "{\"v\":1,\"bookmarks\":[1,3],\"pages\":{" +
            "\"0\":[{\"t\":1,\"c\":-2130711749,\"w\":11,\"p\":[0,10,-5.68,10]}]," +
            "\"2\":[{\"t\":0,\"c\":-16777216,\"w\":1.5,\"p\":[1,2,3.25,4.5],\"q\":[0.25,0.5]}]}}"
        assertEquals(expected, n.toJson())
        assertEquals(expected, n.snapshot().toJson())
        assertEquals("{\"v\":1,\"bookmarks\":[],\"pages\":{}}", PdfNotes().snapshot().toJson())
    }

    @Test
    fun snapshotIsUnaffectedByLaterEdits() {
        val n = PdfNotes()
        n.add(0, stroke(0f, 0f, 10f, 0f))
        n.add(0, stroke(0f, 50f, 10f, 50f))
        n.toggleBookmark(4)
        val snap = n.snapshot()
        val json = snap.toJson()
        // Everything the main thread can do next, while the snapshot is being written elsewhere.
        n.add(0, stroke(1f, 1f))
        n.add(7, stroke(2f, 2f))
        n.eraseAt(0, 5f, 0f, 2f)
        n.undo()
        n.undo()
        n.clearAllInk()
        n.toggleBookmark(4)
        n.toggleBookmark(9)
        assertEquals(json, snap.toJson())
        assertFalse(snap.isEmpty)
    }

    @Test
    fun snapshotEmptinessMatchesTheNotes() {
        val n = PdfNotes()
        assertTrue(n.snapshot().isEmpty)
        n.toggleBookmark(1)
        assertFalse(n.snapshot().isEmpty) // a bookmark alone is kept on disk
        n.toggleBookmark(1)
        assertTrue(n.snapshot().isEmpty)
        n.add(0, stroke(0f, 0f))
        assertFalse(n.snapshot().isEmpty)
        n.undo()
        assertTrue(n.snapshot().isEmpty)
        assertEquals(n.isEmpty, n.snapshot().isEmpty)
    }

    @Test
    fun snapshotWrittenAndLoadedBackRoundTrips() {
        val dir = tempDir()
        try {
            val n = PdfNotes()
            n.add(1, stroke(1f, 2f, 3f, 4f))
            n.toggleBookmark(6)
            val snap = n.snapshot()
            assertTrue(PdfNotesStore.save(dir, 5L, snap.toJson()))
            assertEquals(n.toJson(), PdfNotesStore.load(dir, 5L).toJson())
        } finally {
            dir.deleteRecursively()
        }
    }
}
