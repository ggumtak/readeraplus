package com.ggumtak.readeraplus.data

import com.ggumtak.readeraplus.engine.PageBreakMode
import com.ggumtak.readeraplus.settings.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SettingsJsonR3Test {
    @Test fun oldMarginsMigrateAndNewDeliberateMarginsStay() {
        val old=JSONObject().put("r.marginLeftDp",18).put("r.marginRightDp",18).put("r.marginTopDp",16).put("r.marginBottomDp",16)
        val moved=SettingsJson.readerFromJson(old,ReaderSettings())
        assertEquals(40,moved.marginLeftDp);assertEquals(40,moved.marginTopDp)
        val deliberate=ReaderSettings(marginLeftDp=18,marginRightDp=18,marginTopDp=16,marginBottomDp=16,pageBreak=PageBreakMode.PARAGRAPH)
        assertEquals(deliberate,SettingsJson.readerFromJson(SettingsJson.readerToJson(deliberate),ReaderSettings()))
    }
    @Test fun oldBackupWithoutMarginsKeepsDevice() {
        val base=ReaderSettings(marginLeftDp=18,marginRightDp=18,marginTopDp=16,marginBottomDp=16)
        assertEquals(base,SettingsJson.readerFromJson(JSONObject(),base))
        assertEquals(base,SettingsJson.readerFromJson(JSONObject().put("r.fontSizeSp",base.fontSizeSp),base))
    }
    @Test fun appModesRoundTripAndTransientStateDoesNotTravel() {
        val a=AppSettings(readMode=ReadMode.SCROLL,scrollStyle=ScrollStyle.STEP,autoBackup=false,brightnessRestore=false)
        assertEquals(a,SettingsJson.appFromJson(SettingsJson.appToJson(a),AppSettings()))
        val raw=mapOf<String,Any?>("installId" to "old","restoreOffer.state" to "pending","backupAuto.hash" to "x","deviceClass" to "lcd|old")
        val o=SettingsJson.settingsToJson(ReaderSettings(),a,raw).toString()
        for(k in raw.keys) assertFalse(k,o.contains(k))
        val b=AppSettings();assertEquals(b,SettingsJson.appFromJson(JSONObject().put("a.readMode","X").put("a.scrollStyle","X"),b))
    }
}
