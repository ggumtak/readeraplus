package com.ggumtak.readeraplus.data

import com.ggumtak.readeraplus.settings.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SettingsJsonStatusTest {
    @Test fun legacyDefaultAndCustomBackupMapToSlots() {
        val old=JSONObject().put("r.showFooter",true).put("r.footerPage",true).put("r.footerPercent",true).put("r.footerClock",true).put("r.footerBattery",true)
        assertFalse(SettingsJson.readerFromJson(old,ReaderSettings()).hasFooterText)
        old.put("r.footerEpisode",true)
        val s=SettingsJson.readerFromJson(old,ReaderSettings())
        assertEquals(StatusItem.PAGE,s.footerLeft);assertEquals(StatusItem.EPISODE,s.footerCenter);assertEquals(StatusItem.CLOCK_BATTERY,s.footerRight)
    }
    @Test fun everySlotRoundTripsAndNewSlotKeysWin() {
        for(b in 0..1) for(p in 0..2) for(item in StatusItem.entries) {
            val s=ReaderSettings().withSlot(b,p,item)
            val o=SettingsJson.readerToJson(s).put("r.showHeader",false).put("r.showFooter",false)
            assertEquals(s,SettingsJson.readerFromJson(o,ReaderSettings()))
        }
    }
    @Test fun deviceBrightnessIsNeverExportedOrRestoredAndDroppedKeysStayDropped() {
        val base=AppSettings(brightnessDevice=true)
        val raw=SettingsJson.DROPPED_KEYS.associateWith { true as Any? }
        val env=SettingsJson.settingsToJson(ReaderSettings(),base,raw)
        val a=env.getJSONObject("app");val r=env.getJSONObject("reader")
        for(k in raw.keys) { assertFalse(k,a.has(k));assertFalse(k,r.has(k)) }
        assertTrue(SettingsJson.appFromJson(JSONObject().put("a.brightnessDevice",false).put("a.pinChrome",true),base).brightnessDevice)
        assertFalse(SettingsJson.appFromJson(JSONObject().put("a.brightnessDevice",true),AppSettings()).brightnessDevice)
        val bad=JSONObject();raw.forEach { (k,v)->bad.put(k,v) }
        assertTrue(SettingsJson.unmappedFromJson(bad,SettingsJson.APP_PREFIX,raw).isEmpty())
    }
}
