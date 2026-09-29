package com.ggumtak.readeraplus.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TreePathsTest {
    private val ext = "content://com.android.externalstorage.documents/tree/"

    @Test
    fun primaryTreeUri() {
        assertEquals("/storage/emulated/0/Books", TreePaths.fromTreeUri(ext + "primary%3ABooks"))
        assertEquals("/storage/emulated/0", TreePaths.fromTreeUri(ext + "primary%3A"))
        assertEquals("/storage/emulated/0/Books/웹소설", TreePaths.fromTreeUri(ext + "primary%3ABooks%2F%EC%9B%B9%EC%86%8C%EC%84%A4"))
    }

    @Test
    fun treeUriWithDocumentSuffixAndQuery() {
        assertEquals(
            "/storage/emulated/0/Download/novels",
            TreePaths.fromTreeUri(ext + "primary%3ADownload%2Fnovels/document/primary%3ADownload%2Fnovels"),
        )
        assertEquals("/storage/emulated/0/A", TreePaths.fromTreeUri(ext + "primary%3AA?x=1"))
    }

    @Test
    fun sdCardVolume() {
        assertEquals("/storage/1A2B-3C4D/Novels", TreePaths.fromTreeUri(ext + "1A2B-3C4D%3ANovels"))
        assertEquals("/storage/1A2B-3C4D", TreePaths.fromDocumentId("1a2b-3c4d:"))
    }

    @Test
    fun spacesAndPlusSigns() {
        assertEquals("/storage/emulated/0/My Books+", TreePaths.fromTreeUri(ext + "primary%3AMy%20Books%2B"))
        assertEquals("/storage/emulated/0/a+b", TreePaths.fromTreeUri(ext + "primary%3Aa+b"))
    }

    @Test
    fun otherDocumentIds() {
        assertEquals("/storage/emulated/0/Documents/x", TreePaths.fromDocumentId("home:x"))
        assertEquals("/storage/emulated/0/Download", TreePaths.fromDocumentId("raw:/storage/emulated/0/Download/"))
        assertEquals("/storage/emulated/0/Download", TreePaths.fromDocumentId("downloads"))
        assertEquals("/mnt/x", TreePaths.fromDocumentId("/mnt//x/"))
        assertEquals("/sdcard/Books", TreePaths.fromDocumentId("primary:Books", primaryRoot = "/sdcard"))
    }

    @Test
    fun unsupported() {
        assertNull(TreePaths.fromTreeUri(null))
        assertNull(TreePaths.fromTreeUri("content://com.google.android.apps.docs.storage/tree/acc%3D1%3Bdoc%3Dabc"))
        assertNull(TreePaths.fromTreeUri("content://com.android.externalstorage.documents/document/primary%3AA"))
        assertNull(TreePaths.fromDocumentId("msf:1234"))
        assertNull(TreePaths.fromDocumentId("1234"))
        assertNull(TreePaths.fromDocumentId(""))
    }

    @Test
    fun percentDecodeEdgeCases() {
        assertEquals("100%", TreePaths.percentDecode("100%"))
        assertEquals("%zz", TreePaths.percentDecode("%zz"))
        assertEquals("a%2", TreePaths.percentDecode("a%2"))
        assertEquals("한:", TreePaths.percentDecode("%ED%95%9C%3A"))
        assertEquals("📚", TreePaths.percentDecode("📚"))
    }

    @Test
    fun volumeRootsFromStorageManager() {
        val vols = mapOf("0123456789abcdef" to "/storage/0123456789ABCDEF", "1a2b-3c4d" to "/mnt/media_rw/1A2B-3C4D")
        // A non-FAT id is only resolvable through the mounted-volume map.
        assertNull(TreePaths.fromDocumentId("0123456789ABCDEF:Books"))
        assertEquals("/storage/0123456789ABCDEF/Books", TreePaths.fromDocumentId("0123456789ABCDEF:Books", volumeRoots = vols))
        // The map wins over the /storage/<id> guess.
        assertEquals("/mnt/media_rw/1A2B-3C4D/x", TreePaths.fromDocumentId("1A2B-3C4D:x", volumeRoots = vols))
        assertEquals("/storage/0123456789ABCDEF", TreePaths.fromTreeUri(ext + "0123456789ABCDEF%3A", volumeRoots = vols))
        // Primary keeps using the primary root even if a volume map is given.
        assertEquals("/storage/emulated/0/A", TreePaths.fromDocumentId("primary:A", volumeRoots = vols))
    }
}
