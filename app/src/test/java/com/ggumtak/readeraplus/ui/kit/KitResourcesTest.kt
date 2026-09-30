package com.ggumtak.readeraplus.ui.kit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Guards the e-ink window/launch settings that live in the manifest and themes (text checks, no aapt). */
class KitResourcesTest {
    private val main: File? = listOf("app/src/main", "src/main").map(::File).firstOrNull { File(it, "AndroidManifest.xml").isFile }

    private fun read(path: String): String {
        assumeTrue("sources not found from ${File("").absolutePath}", main != null)
        return File(main, path).readText()
    }

    private fun block(xml: String, start: String, end: String): String {
        val i = xml.indexOf(start)
        assumeTrue("missing $start", i >= 0)
        return xml.substring(i, xml.indexOf(end, i))
    }

    private fun item(style: String, name: String): String? =
        Regex("""<item name="android:$name">([^<]*)</item>""").find(style)?.groupValues?.get(1)

    @Test
    fun launcherActivityIsNotSingleTask() {
        val lib = block(read("AndroidManifest.xml"), "android:name=\".ui.library.LibraryActivity\"", "</activity>")
        assertFalse("singleTask on the launcher root clears the open reader on relaunch", lib.contains("singleTask"))
        assertFalse(lib.contains("singleInstance"))
    }

    @Test
    fun noThemeWideAutoHidingFastScroller() {
        val themes = read("res/values/themes.xml")
        assertFalse(Regex("""fastScrollEnabled">true""").containsMatchIn(themes))
    }

    @Test
    fun alertDialogThemeHasNoDimShadowOrAnimation() {
        val themes = read("res/values/themes.xml")
        val dialog = block(themes, "<style name=\"InkDialog\"", "</style>")
        assertEquals("false", item(dialog, "backgroundDimEnabled"))
        assertEquals("0dp", item(dialog, "windowElevation"))
        assertEquals("@null", item(dialog, "windowAnimationStyle"))
        assertEquals("@android:color/black", item(dialog, "colorAccent"))
        assertEquals("@android:color/transparent", item(dialog, "colorControlHighlight"))
    }

    @Test
    fun fullScreenDialogThemeDoesNotForceFullscreen() {
        val dialog = block(read("res/values/themes.xml"), "<style name=\"InkScreenDialog\"", "</style>")
        assertEquals("false", item(dialog, "windowFullscreen"))
        assertEquals("false", item(dialog, "backgroundDimEnabled"))
    }

    @Test
    fun splashScreenIsPlainWhiteOnEveryActivityTheme() {
        // R2 (A4.5): API 31+ adds the splash attributes to AppTheme; every activity theme inherits AppTheme.
        val base = read("res/values/themes.xml")
        assertNotNull(Regex("""<style name="AppTheme" parent="Base.AppTheme"\s*/>""").find(base))
        assertNotNull(Regex("""<style name="ReaderTheme" parent="AppTheme">""").find(base))
        val v31 = block(read("res/values-v31/themes.xml"), "<style name=\"AppTheme\" parent=\"Base.AppTheme\">", "</style>")
        assertEquals("@android:color/transparent", item(v31, "windowSplashScreenAnimatedIcon"))
        assertEquals("@android:color/white", item(v31, "windowSplashScreenBackground"))
        assertEquals("@android:color/white", item(v31, "windowSplashScreenIconBackgroundColor"))
        // Every activity in the manifest uses AppTheme (the default) or ReaderTheme.
        val manifest = read("AndroidManifest.xml")
        for (m in Regex("""android:theme="@style/([A-Za-z.]+)"""").findAll(manifest)) {
            assertTrue(m.groupValues[1], m.groupValues[1] == "AppTheme" || m.groupValues[1] == "ReaderTheme")
        }
    }

    @Test
    fun r2PermissionsAndTtsService() {
        val manifest = read("AndroidManifest.xml")
        for (p in listOf("INTERNET", "WAKE_LOCK", "FOREGROUND_SERVICE", "FOREGROUND_SERVICE_MEDIA_PLAYBACK")) {
            assertTrue(p, manifest.contains("<uses-permission android:name=\"android.permission.$p\" />"))
        }
        val service = block(manifest, "android:name=\".reader.extras.TtsService\"", "/>")
        assertTrue(service.contains("android:exported=\"false\""))
        assertTrue(service.contains("android:foregroundServiceType=\"mediaPlayback\""))
        // No notification permission prompt: a media-session notification is exempt.
        assertFalse(manifest.contains("POST_NOTIFICATIONS"))
    }

    @Test
    fun whiteNavigationBarOnlyWithDarkButtons() {
        fun color(path: String, name: String) = Regex("""<color name="$name">([^<]*)</color>""").find(read(path))?.groupValues?.get(1)
        fun bool(path: String, name: String) = Regex("""<bool name="$name">([^<]*)</bool>""").find(read(path))?.groupValues?.get(1)
        // API 26 (base values): no light-navigation-bar support, so the bar must not be white.
        assertNotNull(color("res/values/colors.xml", "nav_bar"))
        assertFalse(color("res/values/colors.xml", "nav_bar")!!.uppercase().endsWith("FFFFFF"))
        assertEquals("false", bool("res/values/colors.xml", "light_nav_bar"))
        assertEquals("#FFFFFFFF", color("res/values-v27/colors.xml", "nav_bar"))
        assertEquals("true", bool("res/values-v27/colors.xml", "light_nav_bar"))
    }
}
