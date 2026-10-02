package com.ggumtak.readeraplus.ui.settings

import com.ggumtak.readeraplus.settings.AppSettings
import com.ggumtak.readeraplus.settings.ReaderSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class SettingsFormatTest {
    @Test
    fun bytes() {
        assertEquals("0 B", SettingsFormat.bytes(0))
        assertEquals("1023 B", SettingsFormat.bytes(1023))
        assertEquals("1.0 KB", SettingsFormat.bytes(1024))
        assertEquals("12.3 MB", SettingsFormat.bytes((12.3 * 1024 * 1024).toLong()))
        assertEquals("150 MB", SettingsFormat.bytes(150L * 1024 * 1024))
        assertEquals("2.0 GB", SettingsFormat.bytes(2L * 1024 * 1024 * 1024))
    }

    @Test
    fun seconds() {
        assertEquals("5초", SettingsFormat.seconds(5))
        assertEquals("1분", SettingsFormat.seconds(60))
        assertEquals("1분 30초", SettingsFormat.seconds(90))
        assertEquals("5분", SettingsFormat.seconds(300))
    }

    @Test
    fun refreshAndSleep() {
        assertEquals("끔", SettingsFormat.refreshEvery(0))
        assertEquals("5쪽마다", SettingsFormat.refreshEvery(5))
        assertEquals("끔", SettingsFormat.sleep(0))
        assertEquals("15분", SettingsFormat.sleep(15))
        assertEquals("1시간", SettingsFormat.sleep(60))
        assertEquals("1시간 30분", SettingsFormat.sleep(90))
    }

    @Test
    fun numbers() {
        assertEquals("1.0배", SettingsFormat.rate(1f))
        assertEquals("1.3배", SettingsFormat.rate(1.3000001f))
        assertEquals("0.8", SettingsFormat.pitch(0.8f))
        assertEquals("11sp", SettingsFormat.sp(11f))
        assertEquals("11.5sp", SettingsFormat.sp(11.5f))
    }

    @Test
    fun backupName() {
        val utc = TimeZone.getTimeZone("UTC")
        // 2026-09-29T12:00:00Z
        assertEquals("readeraplus-backup-20260929.json", SettingsFormat.backupFileName(1790683200000L, utc))
        assertEquals("2026-09-29 12:00", SettingsFormat.dateTime(1790683200000L, utc))
        // Local date: 23:30 UTC is already the next day in Seoul.
        val seoul = TimeZone.getTimeZone("Asia/Seoul")
        assertEquals("readeraplus-backup-20260930.json", SettingsFormat.backupFileName(1790724600000L, seoul))
    }

    @Test
    fun orientation() {
        assertEquals("자동 회전", SettingsFormat.orientation(-1))
        assertEquals("세로", SettingsFormat.orientation(1))
        assertEquals("자동 회전", SettingsFormat.orientation(12345))
    }

    @Test
    fun webEngines() {
        assertEquals(0, WebEngines.indexOf("https://www.google.com/search?q=%s"))
        assertEquals("네이버", WebEngines.nameOf("https://search.naver.com/search.naver?query=%s"))
        assertEquals(-1, WebEngines.indexOf("https://example.com/?q=%s"))
        assertEquals("사용자 지정", WebEngines.nameOf("https://example.com/?q=%s"))
        assertEquals("https://example.com/?q=%s", WebEngines.normalizeTemplate(" example.com/?q=%s "))
        assertEquals("http://x.org/%s", WebEngines.normalizeTemplate("http://x.org/%s"))
        assertNull(WebEngines.normalizeTemplate("https://example.com/"))
        assertNull(WebEngines.normalizeTemplate("ftp://x/%s"))
        assertNull(WebEngines.normalizeTemplate("https://a b/%s"))
        assertEquals(
            "https://search.naver.com/search.naver?query=%ED%95%9C%EA%B8%80%20a%2Bb",
            WebEngines.build("https://search.naver.com/search.naver?query=%s", "한글 a+b"),
        )
    }

    @Test
    fun sleepChoicesIncludeEpisodes() {
        // 끔 / 15 / 30 / 45 / 60 / 90분 / 이 화 끝까지 / 2화 끝까지 (T1-11).
        assertEquals(
            listOf("끔", "15분", "30분", "45분", "1시간", "1시간 30분", "이 화 끝까지", "2화 끝까지"),
            SettingsFormat.SLEEP_CHOICES.map { (m, c) -> SettingsFormat.sleepChoice(m, c) },
        )
        // A chapter choice stores minutes 0; chapters win when both are set.
        assertEquals(0 to 1, SettingsFormat.SLEEP_CHOICES[6])
        assertEquals("이 화 끝까지", SettingsFormat.sleepChoice(30, 1))
        assertEquals("3화 끝까지", SettingsFormat.sleepChoice(0, 3))
        assertEquals(0, SettingsFormat.sleepIndex(0, 0))
        assertEquals(4, SettingsFormat.sleepIndex(60, 0))
        assertEquals(6, SettingsFormat.sleepIndex(0, 1))
        assertEquals(7, SettingsFormat.sleepIndex(45, 2))
        // Values no choice offers (an older build's 10 / 120분, a restored 3화) select nothing.
        assertEquals(-1, SettingsFormat.sleepIndex(10, 0))
        assertEquals(-1, SettingsFormat.sleepIndex(0, 3))
    }

    @Test
    fun longPressTimes() {
        assertEquals(listOf("0.4초", "0.5초", "0.7초", "1.0초"), SettingsFormat.LONG_PRESS_OPTIONS.map { SettingsFormat.longPress(it) })
        assertEquals("0.5초 (기본)", SettingsFormat.longPressChoice(AppSettings().longPressMs))
        assertEquals("0.7초", SettingsFormat.longPressChoice(700))
        assertTrue(AppSettings().longPressMs in SettingsFormat.LONG_PRESS_OPTIONS)
    }


    @Test
    fun receivedLine() {
        assertEquals("12.3 MB · 서재에 추가됨", SettingsFormat.received((12.3 * 1024 * 1024).toLong(), added = true))
        assertEquals("500 B · 서재에 추가하지 못함", SettingsFormat.received(500, added = false))
    }
}
