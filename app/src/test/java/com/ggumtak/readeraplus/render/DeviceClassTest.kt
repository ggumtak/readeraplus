package com.ggumtak.readeraplus.render

import org.junit.Assert.*
import org.junit.Test

class DeviceClassTest {
 @Test fun foreignDeviceAndFirmwareCopiesAreIgnored() {
  val here=DeviceClass.stamp("Bigme","Comet","fw2")
  val there=DeviceClass.stamp("Google","Pixel","fw1")
  assertNull(DeviceClass.fromCache("lcd|$there",here,false))
  assertEquals(true,DeviceClass.fromCache("lcd|$there",here,true))
  assertNull(DeviceClass.fromCache("lcd",here,false))
  assertEquals(false,DeviceClass.fromCache("lcd|$here",here,true))
  assertEquals(true,DeviceClass.fromCache("eink|$here",here,false))
  assertNull(DeviceClass.fromCache("eink|"+DeviceClass.stamp("Bigme","Comet","fw1"),here,false))
 }
 @Test fun vendorDiscoveryComplementsTheBuildHeuristic() {
  assertTrue(DeviceClass.detected("Rockchip","Unknown","unknown","reader"))
  assertTrue(DeviceClass.detected(null,"Bigme","Bigme","Comet"))
  assertFalse(DeviceClass.detected(null,"Google","google","Pixel"))
 }
 @Test fun knownMakersAndHisenseModels() { for(m in listOf("Innospaceone","BIGME","Onyx","Boox","Kobo","Meebook")) assertTrue(m,DeviceClass.einkByBuild(m,m,"reader"));for(m in listOf("Hisense A5","A7","A9")) assertTrue(DeviceClass.einkByBuild("Hisense","Hisense",m));assertFalse(DeviceClass.einkByBuild("Hisense","Hisense","Infinity H50"));assertFalse(DeviceClass.einkByBuild("Google","google","Pixel 9 Pro Fold")) }
 @Test fun firmwareStampChanges() { assertEquals(DeviceClass.stamp("A","B","C"),DeviceClass.stamp("A","B","C"));assertNotEquals(DeviceClass.stamp("A","B","C"),DeviceClass.stamp("A","B","D"));assertTrue(DeviceClass.stamp("A","B","C").startsWith("A/B/")) }
}
