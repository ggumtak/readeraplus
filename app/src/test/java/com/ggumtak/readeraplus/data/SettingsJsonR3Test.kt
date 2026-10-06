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
        // R2's 16/16 = 40/40 from the edge, counted from the bands (the backup has no slots or size: the device's defaults).
        assertEquals(20,moved.marginLeftDp);assertEquals(20,moved.marginRightDp);assertEquals(15,moved.marginTopDp);assertEquals(10,moved.marginBottomDp)
        val deliberate=ReaderSettings(marginLeftDp=18,marginRightDp=18,marginTopDp=16,marginBottomDp=16,pageBreak=PageBreakMode.PARAGRAPH)
        assertEquals(deliberate,SettingsJson.readerFromJson(SettingsJson.readerToJson(deliberate),ReaderSettings()))
    }
    @Test fun anR3BackupWithTheFortyDpDefaultGetsMaruViewersSides() {
        val r3=JSONObject().put(SideMargin.KEY,40).put(VerticalMargin.KEY,40)
            .put("r.marginLeftDp",40).put("r.marginRightDp",40).put("r.marginTopDp",40).put("r.marginBottomDp",40)
        val moved=SettingsJson.readerFromJson(r3,ReaderSettings(marginLeftDp=30,marginRightDp=30))
        assertEquals(listOf(20,20,15,10),listOf(moved.marginLeftDp,moved.marginRightDp,moved.marginTopDp,moved.marginBottomDp))
        // Chosen R3 values stay.
        val chosen=SettingsJson.readerFromJson(JSONObject(r3.toString()).put("r.marginLeftDp",24).put("r.marginRightDp",24),ReaderSettings())
        assertEquals(24,chosen.marginLeftDp)
        // A backup of this build writes the new "0"; its 40/40 is a choice and round-trips.
        val forty=ReaderSettings(marginLeftDp=40,marginRightDp=40)
        val json=SettingsJson.readerToJson(forty)
        assertEquals(20,json.getInt(SideMargin.KEY))
        assertEquals(forty,SettingsJson.readerFromJson(json,ReaderSettings()))
        // The one-time header switch is this device's state: never in a backup.
        val env=SettingsJson.settingsToJson(ReaderSettings(),AppSettings(),mapOf(MaruHeader.KEY to true)).toString()
        assertFalse(env.contains(MaruHeader.KEY))
        assertTrue(SettingsJson.otherFromJson(JSONObject().put(MaruHeader.KEY,true),null).isEmpty())
        // So is the status size switch (MaruSize): a backup's 11 sp and its margins come back as they are.
        val sized=SettingsJson.settingsToJson(ReaderSettings(),AppSettings(),mapOf(MaruSize.KEY to true)).toString()
        assertFalse(sized.contains(MaruSize.KEY))
        assertTrue(SettingsJson.otherFromJson(JSONObject().put(MaruSize.KEY,true),null).isEmpty())
        val eleven=ReaderSettings(statusFontSizeSp=11f,marginTopDp=18)
        assertEquals(eleven,SettingsJson.readerFromJson(SettingsJson.readerToJson(eleven),ReaderSettings()))
        // A backup's own header slots are restored as they are (here the R2-era chapter title).
        val r2=SettingsJson.readerFromJson(JSONObject().put("r.headerLeft","NONE").put("r.headerCenter","CHAPTER").put("r.headerRight","NONE"),ReaderSettings())
        assertEquals(listOf(StatusItem.NONE,StatusItem.CHAPTER,StatusItem.NONE),listOf(r2.headerLeft,r2.headerCenter,r2.headerRight))
    }
    @Test fun topAndBottomOfAnOlderBackupCountFromItsOwnBands() {
        // Saved from the edge (U3 marker 40) with the backup's own status: no header, footer items above the line (39 dp
        // at the default 13 sp: the backup holds no size).
        val r3=JSONObject().put(VerticalMargin.KEY,40).put("r.marginTopDp",40).put("r.marginBottomDp",50)
            .put("r.headerLeft","NONE").put("r.headerCenter","NONE").put("r.headerRight","NONE")
            .put("r.footerLeft","NONE").put("r.footerCenter","PAGE").put("r.footerRight","NONE").put("r.progressBar",true)
        val moved=SettingsJson.readerFromJson(r3,ReaderSettings())
        // The bottom then 12 dp less once (2026-10-06), never below 0.
        assertEquals(listOf(40,0),listOf(moved.marginTopDp,moved.marginBottomDp))
        // With the backup's own 11 sp: its 36 dp band.
        assertEquals(2,SettingsJson.readerFromJson(JSONObject(r3.toString()).put("r.statusFontSizeSp",11.0),ReaderSettings()).marginBottomDp)
        // Only what the backup holds moves: a missing bottom keeps the device's (already counted from the bands).
        val topOnly=SettingsJson.readerFromJson(JSONObject().put(VerticalMargin.KEY,40).put("r.marginTopDp",40),ReaderSettings(marginBottomDp=30))
        assertEquals(listOf(15,30),listOf(topOnly.marginTopDp,topOnly.marginBottomDp))
        // This build writes the band marker: a backup's 0/0 comes back 0/0, never moved twice.
        val zero=ReaderSettings(marginTopDp=0,marginBottomDp=0)
        val json=SettingsJson.readerToJson(zero)
        assertEquals(VerticalMargin.BANDS,json.getInt(VerticalMargin.KEY))
        assertEquals(zero,SettingsJson.readerFromJson(json,ReaderSettings()))
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
