package com.ggumtak.readeraplus.render

import org.junit.Assert.*
import org.junit.Test

class DeviceClassTest {
 @Test fun knownMakersAndHisenseModels() { for(m in listOf("Innospaceone","BIGME","Onyx","Boox","Kobo","Meebook")) assertTrue(m,DeviceClass.einkByBuild(m,m,"reader"));for(m in listOf("Hisense A5","A7","A9")) assertTrue(DeviceClass.einkByBuild("Hisense","Hisense",m));assertFalse(DeviceClass.einkByBuild("Hisense","Hisense","Infinity H50"));assertFalse(DeviceClass.einkByBuild("Google","google","Pixel 9 Pro Fold")) }
 @Test fun firmwareStampChanges() { assertEquals(DeviceClass.stamp("A","B","C"),DeviceClass.stamp("A","B","C"));assertNotEquals(DeviceClass.stamp("A","B","C"),DeviceClass.stamp("A","B","D"));assertTrue(DeviceClass.stamp("A","B","C").startsWith("A/B/")) }
}
