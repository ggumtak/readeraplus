package com.ggumtak.readeraplus.render

import org.junit.Assert.*
import org.junit.Test

class ProgressMathTest {
 @Test fun laneRadiiAndCentreStayInsideMargins() { for((lane,y) in listOf(24f to 1428,20f to 1430,16f to 1432)) { assertEquals(y,ProgressMath.yc(1440,lane));assertEquals(6f,ProgressMath.rDot(lane,2f),0f) };assertEquals(0f,StatusFit.lane(8f,2f),0f);assertEquals(0f,ProgressMath.rDot(0f,2f),0f) }
 @Test fun capTouchAndPixelRounding() { assertEquals(24,ProgressMath.x0(720,2f));assertEquals(696,ProgressMath.x1(720,2f));assertEquals(654,ProgressMath.trackPx(720,24f,2f));assertEquals(33.5f,ProgressMath.dotX(0f,720,24f,2f),0f);assertEquals(687.5f,ProgressMath.dotX(1f,720,24f,2f),0f);assertEquals(360.5f,ProgressMath.dotX(.5f,720,24f,2f),0f);assertTrue(ProgressMath.trackPx(720,12f,2f)>ProgressMath.trackPx(720,24f,2f)) }
}
