package com.ggumtak.readeraplus.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class BookCopiesTest {
    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("bookcopies").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun file(name: String, text: String): File = File(dir, name).also { it.writeText(text) }

    private fun tmp(text: String): File = File(dir, ".t-${System.nanoTime()}.part").also { it.writeText(text) }

    @Test
    fun sameContentNeedsEqualLengthAndBytes() {
        assertTrue(BookCopies.sameContent(file("a.txt", "본문 하나"), file("b.txt", "본문 하나")))
        // Same length, one byte different.
        assertFalse(BookCopies.sameContent(file("c.txt", "abcdef"), file("d.txt", "abcdeg")))
        // Different length.
        assertFalse(BookCopies.sameContent(file("e.txt", "abc"), file("f.txt", "abcd")))
        assertTrue(BookCopies.sameContent(file("g.txt", ""), file("h.txt", "")))
        assertFalse(BookCopies.sameContent(file("a.txt", "x"), File(dir, "missing.txt")))
    }

    @Test
    fun sameContentSeesADifferenceFarPastTheFirstBuffer() {
        val a = ByteArray(200_000) { (it % 251).toByte() }
        val b = a.copyOf()
        File(dir, "a.epub").writeBytes(a)
        File(dir, "b.epub").writeBytes(b)
        assertTrue(BookCopies.sameContent(File(dir, "a.epub"), File(dir, "b.epub")))
        b[199_999] = (b[199_999] + 1).toByte()
        File(dir, "b.epub").writeBytes(b)
        assertFalse(BookCopies.sameContent(File(dir, "a.epub"), File(dir, "b.epub")))
    }

    @Test
    fun placeUsesTheNameWhenFree() {
        val t = tmp("one")
        val placed = BookCopies.place(t, dir, "novel.txt")
        assertEquals(File(dir, "novel.txt"), placed)
        assertEquals("one", placed.readText())
        assertFalse(t.exists())
    }

    @Test
    fun placePicksTheNextFreeNumberAndNeverOverwrites() {
        file("novel.txt", "first")
        file("novel (2).txt", "second")
        val placed = BookCopies.place(tmp("third"), dir, "novel.txt")
        assertEquals("novel (3).txt", placed.name)
        assertEquals("first", File(dir, "novel.txt").readText())
        assertEquals("second", File(dir, "novel (2).txt").readText())
        assertEquals("third", placed.readText())
        // A gap is filled by the lowest free number; a name without an extension is numbered too.
        File(dir, "novel (2).txt").delete()
        assertEquals("novel (2).txt", BookCopies.place(tmp("fourth"), dir, "novel.txt").name)
        file("noext", "x")
        assertEquals("noext (2)", BookCopies.place(tmp("y"), dir, "noext").name)
    }

    @Test
    fun existingCopyFindsAnIdenticalNumberedCopy() {
        file("novel.txt", "edition one")
        file("novel (2).txt", "edition two")
        file("novel (3).txt", "edition three")
        val incoming = tmp("edition two")
        assertEquals(File(dir, "novel (2).txt"), BookCopies.existingCopy(dir, "novel.txt", incoming))
        assertEquals(File(dir, "novel.txt"), BookCopies.existingCopy(dir, "novel.txt", tmp("edition one")))
    }

    @Test
    fun existingCopyIgnoresASameSizeDifferentFile() {
        file("novel.txt", "typo here")
        val corrected = tmp("typo hexe")
        assertEquals(file("novel.txt", "typo here").length(), corrected.length())
        assertNull(BookCopies.existingCopy(dir, "novel.txt", corrected))
        assertNull(BookCopies.existingCopy(dir, "other.txt", tmp("typo here")))
        // The corrected edition then lands beside the old one.
        val placed = BookCopies.place(corrected, dir, "novel.txt")
        assertEquals("novel (2).txt", placed.name)
        assertEquals("typo here", File(dir, "novel.txt").readText())
        assertEquals("typo hexe", placed.readText())
    }

    @Test
    fun settleReusesAnIdenticalCopyAndDeletesTheTemp() {
        file("a.txt", "same")
        val t = tmp("same")
        assertEquals(File(dir, "a.txt"), BookCopies.settle(t, dir, "a.txt"))
        assertFalse(t.exists())
        assertEquals(listOf("a.txt"), dir.list()!!.sorted())
    }

    @Test
    fun twoThreadsPlacingTheSameNameEndWithTwoFiles() {
        repeat(20) { round ->
            val d = Files.createTempDirectory("race").toFile()
            try {
                val a = File(d, ".a.part").also { it.writeText("A$round") }
                val b = File(d, ".b.part").also { it.writeText("B$round") }
                val pool = Executors.newFixedThreadPool(2)
                val start = CountDownLatch(1)
                val results = listOf(a, b).map { t ->
                    pool.submit<File> {
                        start.await()
                        BookCopies.place(t, d, "same.txt")
                    }
                }
                start.countDown()
                val placed = results.map { it.get(10, TimeUnit.SECONDS) }
                pool.shutdown()
                assertNotEquals(placed[0], placed[1])
                assertEquals(setOf("same.txt", "same (2).txt"), d.list()!!.toSet())
                assertEquals("A$round", placed[0].readText())
                assertEquals("B$round", placed[1].readText())
            } finally {
                d.deleteRecursively()
            }
        }
    }

    @Test
    fun sweepTempsDeletesOnlyOldPartFiles() {
        val old = System.currentTimeMillis() - 2 * 60 * 60 * 1000L
        for (n in listOf(".copy-1.part", ".import-2.part", ".upload-3.part", ".copy-old.txt", "book.part", "a.txt")) {
            file(n, "x").setLastModified(old)
        }
        file(".copy-fresh.part", "x")
        file(".upload-fresh.part", "x")
        BookCopies.sweepTemps(dir)
        assertEquals(setOf(".copy-old.txt", "book.part", "a.txt", ".copy-fresh.part", ".upload-fresh.part"), dir.list()!!.toSet())
        // An explicit age: a part file ten minutes old goes with a five-minute limit.
        File(dir, ".copy-fresh.part").setLastModified(System.currentTimeMillis() - 10 * 60 * 1000L)
        File(dir, ".upload-fresh.part").setLastModified(System.currentTimeMillis() - 10 * 60 * 1000L)
        BookCopies.sweepTemps(dir, 5 * 60 * 1000L)
        assertEquals(setOf(".copy-old.txt", "book.part", "a.txt"), dir.list()!!.toSet())
    }
}
