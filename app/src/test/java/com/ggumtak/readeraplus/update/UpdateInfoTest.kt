package com.ggumtak.readeraplus.update

import android.content.pm.PackageInstaller
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateInfoTest {
    @Test
    fun parsesTheWebAppsAnswer() {
        val l = UpdateInfo.parse("""{"build":185,"id":"1AbC-def_ghIJklmnOP"}""")
        assertNotNull(l)
        assertEquals(185, l!!.build)
        assertEquals("1AbC-def_ghIJklmnOP", l.fileId)
        // no build yet, an error page, a broken id
        assertNull(UpdateInfo.parse("""{"build":0}"""))
        assertNull(UpdateInfo.parse("<html><body>Error</body></html>"))
        assertNull(UpdateInfo.parse("""{"build":185,"id":"../../x"}"""))
        assertNull(UpdateInfo.parse("""{"build":185,"id":"short"}"""))
        assertNull(UpdateInfo.parse(""))
    }

    /** The shape of drive.google.com/embeddedfolderview: entry id, then the file link, then the name. */
    @Test
    fun findsTheNewestApkInAFolderListing() {
        fun entry(id: String, name: String) =
            "<div class=\"flip-entry\" id=\"entry-$id\" tabindex=\"0\" role=\"link\"><div class=\"flip-entry-info\">" +
                "<a href=\"https://drive.google.com/file/d/$id/view?usp=drive_web\" target=\"_blank\">" +
                "<div class=\"flip-entry-visual\"><img src=\"https://drive-thirdparty.googleusercontent.com/16/type/x\"></div>" +
                "<div class=\"flip-entry-title\">$name</div></a></div>" +
                "<div class=\"flip-entry-last-modified\"><div>오후 1:00</div></div></div>"
        val html = "<html><body><div class=\"flip-entries\">" +
            entry("1aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "ReaderaPlus-184.apk") +
            entry("1bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", "ReaderaPlus-185.apk") +
            entry("1cccccccccccccccccccccccccccccccc", "notes.txt") +
            "</div></body></html>"
        val l = UpdateInfo.parseFolder(html)
        assertNotNull(l)
        assertEquals(185, l!!.build)
        assertEquals("1bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", l.fileId)
        // a link-only listing works too; a sign-in page or an empty folder lists nothing
        val plain = "<a href=\"/file/d/1ddddddddddddddddddddd/view\">ReaderaPlus-190.apk</a>"
        assertEquals(190, UpdateInfo.parseFolder(plain)!!.build)
        assertNull(UpdateInfo.parseFolder("<html><title>Google Drive - Sign in</title></html>"))
        assertNull(UpdateInfo.parseFolder(entry("1cccccccccccccccccccccccccccccccc", "notes.txt")))
        assertEquals(
            "https://drive.google.com/embeddedfolderview?id=17HGqQJ-A0rT_OhkkvIrDrWgGPiq3DcPl",
            UpdateInfo.folderUrl(UpdateInfo.DRIVE_FOLDER),
        )
    }

    @Test
    fun newerOnlyWhenTheBuildNumberIsHigher() {
        val l = UpdateInfo.Latest(185, "1AbC-def_ghIJklmnOP")
        assertTrue(UpdateInfo.isNewer(l, 184))
        assertFalse(UpdateInfo.isNewer(l, 185))
        assertFalse(UpdateInfo.isNewer(l, 190))
    }

    @Test
    fun urls() {
        assertEquals("https://script.google.com/macros/s/X/exec?what=latest", UpdateInfo.checkUrl("https://script.google.com/macros/s/X/exec"))
        assertEquals("https://a/exec?k=1&what=latest", UpdateInfo.checkUrl("https://a/exec?k=1"))
        assertEquals(
            "https://drive.usercontent.google.com/download?id=1AbC-def_ghIJklmnOP&export=download&confirm=t",
            UpdateInfo.downloadUrl("1AbC-def_ghIJklmnOP"),
        )
    }

    @Test
    fun automaticChecksAreSpacedOut() {
        val h12 = 12L * 3600_000
        assertTrue(UpdateInfo.due(1_000_000, 0, h12))
        assertFalse(UpdateInfo.due(h12, 1, h12))
        assertTrue(UpdateInfo.due(h12 + 1, 1, h12))
        assertTrue(UpdateInfo.due(5, 1_000, h12)) // the clock went back
    }

    @Test
    fun apkHeaderAndCachedNames() {
        assertTrue(UpdateInfo.looksLikeApk(byteArrayOf(0x50, 0x4B, 3, 4), 4))
        assertFalse(UpdateInfo.looksLikeApk("<htm".toByteArray(), 4))
        assertFalse(UpdateInfo.looksLikeApk(byteArrayOf(0x50, 0x4B), 2))
        assertEquals("ReaderaPlus-185.apk", UpdateInfo.fileName(185))
        assertEquals(185, UpdateInfo.buildOf("ReaderaPlus-185.apk"))
        assertEquals(0, UpdateInfo.buildOf("ReaderaPlus-185.apk.part"))
        assertEquals(0, UpdateInfo.buildOf("other.apk"))
    }

    @Test
    fun messages() {
        assertEquals("새 버전이 있습니다 (빌드 185 · 지금 184).\n지금 업데이트할까요?", UpdateInfo.offerMessage(185, 184))
        assertEquals("내려받는 중… 40%", UpdateInfo.progressMessage(40))
        assertEquals("내려받는 중…", UpdateInfo.progressMessage(-1))
        assertNull(UpdateInfo.failureMessage(PackageInstaller.STATUS_SUCCESS))
        assertNull(UpdateInfo.failureMessage(PackageInstaller.STATUS_FAILURE_ABORTED))
        assertTrue(UpdateInfo.failureMessage(PackageInstaller.STATUS_FAILURE_CONFLICT)!!.contains("서명"))
        assertEquals("업데이트하지 못했습니다", UpdateInfo.failureMessage(PackageInstaller.STATUS_FAILURE))
    }
}
