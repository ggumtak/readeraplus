package com.ggumtak.readeraplus.settings

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class StatusMigrationTest {
 private fun m(vararg kv: Pair<String,Any>): ReaderSettings { val o=JSONObject();kv.forEach { o.put("r."+it.first,it.second) };return StatusMigration.migrate(StatusMigration.Legacy.from(o)).applyTo(ReaderSettings()) }
 /** ≤ R2 had the chapter title centred on top (the MaruViewer header is [MaruHeader]'s, for prefs only). */
 private val r2Header = ReaderSettings(headerLeft = StatusItem.NONE, headerCenter = StatusItem.CHAPTER, headerRight = StatusItem.NONE)
 @Test fun absentAndUntouchedAndHidden() { assertEquals(r2Header,m());assertFalse(m("showFooter" to false).hasFooterText);assertEquals(StatusItem.NONE,m("showHeader" to false).headerCenter);assertFalse(m("footerPage" to true,"footerPercent" to true,"footerClock" to true,"footerBattery" to true).hasFooterText) }
 @Test fun priorityAndRightSide() { val s=m("footerEpisode" to true,"footerChapterLeft" to true,"footerTimeLeft" to 2);assertEquals(StatusItem.PAGE,s.footerLeft);assertEquals(StatusItem.EPISODE,s.footerCenter);assertEquals(StatusItem.CLOCK_BATTERY,s.footerRight) }
 @Test fun individualChoices() {
  fun solo(k:String,v:Any)=m("footerPage" to false,"footerPercent" to false,"footerClock" to false,"footerBattery" to false,k to v)
  assertEquals(StatusItem.PAGE,solo("footerPage",true).footerLeft);assertEquals(StatusItem.TIME_LEFT_EPISODE,solo("footerTimeLeft",1).footerLeft);assertEquals(StatusItem.TIME_LEFT_BOOK,solo("footerTimeLeft",2).footerLeft)
  val s=m("footerPage" to false);assertEquals(StatusItem.PERCENT,s.footerLeft);assertEquals(StatusItem.NONE,s.footerCenter);assertEquals(StatusItem.CLOCK_BATTERY,s.footerRight)
 }
 @Test fun wrongJsonTypesAreAbsent() { val l=StatusMigration.Legacy.from(JSONObject().put("r.showHeader",3).put("r.footerPage","true").put("r.footerTimeLeft","2"));assertNull(l.showHeader);assertNull(l.page);assertNull(l.timeLeft) }
}
