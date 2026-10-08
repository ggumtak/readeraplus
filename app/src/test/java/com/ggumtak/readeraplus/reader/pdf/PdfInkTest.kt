package com.ggumtak.readeraplus.reader.pdf

import java.io.File
import java.nio.file.Files
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
    fun historyCappedAt100() {
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
}
