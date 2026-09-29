package com.ggumtak.readeraplus.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderSetsTest {
    @Test
    fun normalize() {
        assertEquals("/storage/emulated/0/Books", FolderSets.normalize(" /storage//emulated/0/Books/ "))
        assertEquals("/", FolderSets.normalize("/"))
        assertEquals("/a/b", FolderSets.normalize("\\a\\b\\"))
        assertEquals("", FolderSets.normalize("   "))
    }

    @Test
    fun insideChecks() {
        assertTrue(FolderSets.isSameOrInside("/a/b", "/a"))
        assertTrue(FolderSets.isSameOrInside("/a", "/a"))
        assertFalse(FolderSets.isSameOrInside("/ab", "/a"))
        assertTrue(FolderSets.isSameOrInside("/x", "/"))
    }

    @Test
    fun addNew() {
        val r = FolderSets.add(emptySet(), "/storage/emulated/0/Books/")
        assertTrue(r.added)
        assertEquals(setOf("/storage/emulated/0/Books"), r.folders)
    }

    @Test
    fun addDuplicateOrCovered() {
        val base = setOf("/storage/emulated/0/Books")
        val dup = FolderSets.add(base, "/storage/emulated/0/Books")
        assertFalse(dup.added)
        assertEquals(base, dup.folders)
        val sub = FolderSets.add(base, "/storage/emulated/0/Books/novels")
        assertFalse(sub.added)
        assertTrue(sub.message.contains("포함"))
        // Sibling with a common prefix is a different folder.
        assertTrue(FolderSets.add(base, "/storage/emulated/0/Books2").added)
    }

    @Test
    fun addParentMergesChildren() {
        val base = setOf("/s/a/x", "/s/a/y", "/s/b")
        val r = FolderSets.add(base, "/s/a")
        assertTrue(r.added)
        assertEquals(setOf("/s/a", "/s/b"), r.folders)
        assertTrue(r.message.contains("2"))
    }

    @Test
    fun rejectsRelative() {
        assertFalse(FolderSets.add(emptySet(), "Books").added)
        assertFalse(FolderSets.add(emptySet(), "").added)
    }

    @Test
    fun removeAndSort() {
        assertEquals(setOf("/b"), FolderSets.remove(setOf("/a", "/b"), "/a"))
        assertEquals(listOf("/a", "/B", "/c"), FolderSets.sorted(listOf("/c", "/B", "/a")))
    }

    @Test
    fun covered() {
        assertTrue(FolderSets.isCovered("/storage/emulated/0/Books/x", listOf("/storage/emulated/0")))
        assertFalse(FolderSets.isCovered("/storage/1A2B-3C4D/x", listOf("/storage/emulated/0")))
    }

    @Test
    fun displayNames() {
        assertEquals("내부 저장소", FolderSets.displayName("/storage/emulated/0"))
        assertEquals("내부 저장소/Books", FolderSets.displayName("/storage/emulated/0/Books"))
        assertEquals("SD 카드 (1A2B-3C4D)/Novels", FolderSets.displayName("/storage/1A2B-3C4D/Novels"))
        assertEquals("SD 카드 (1A2B-3C4D)", FolderSets.displayName("/storage/1A2B-3C4D"))
        assertEquals("/data/x", FolderSets.displayName("/data/x"))
    }

    @Test
    fun exclusionEffect() {
        val scan = setOf("/storage/emulated/0/Books")
        assertFalse(FolderSets.exclusionHasNoEffect(emptySet(), "/storage/emulated/0/Music"))
        assertTrue(FolderSets.exclusionHasNoEffect(scan, "/storage/emulated/0/Music"))
        assertFalse(FolderSets.exclusionHasNoEffect(scan, "/storage/emulated/0/Books/old"))
        assertTrue(FolderSets.exclusionHidesScanFolder(scan, "/storage/emulated/0/Books"))
        assertTrue(FolderSets.exclusionHidesScanFolder(scan, "/storage/emulated/0/"))
        assertFalse(FolderSets.exclusionHidesScanFolder(scan, "/storage/emulated/0/Books/old"))
        assertFalse(FolderSets.exclusionHidesScanFolder(scan, "/storage/emulated/0/Book"))
        assertFalse(FolderSets.exclusionHidesScanFolder(emptySet(), "/storage/emulated/0"))
    }
}
