package com.ggumtak.readeraplus.settings

import org.junit.Assert.*
import org.junit.Test

class SideMarginTest {
 @Test fun scaleAndLegacyMarker() { assertEquals(0,SideMargin.toUi(40));assertEquals(0,SideMargin.toDp(-40));assertEquals(80,SideMargin.toDp(40));assertEquals("+2",SideMargin.label(2));assertEquals("−2",SideMargin.label(-2));assertEquals("0",SideMargin.label(0));assertTrue(SideMargin.isLegacyDefault(false,18,18));assertFalse(SideMargin.isLegacyDefault(true,18,18));assertFalse(SideMargin.isLegacyDefault(false,17,18)) }
}
