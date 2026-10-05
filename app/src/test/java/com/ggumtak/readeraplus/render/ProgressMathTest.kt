package com.ggumtak.readeraplus.render

import org.junit.Assert.*
import org.junit.Test

class ProgressMathTest {
 @Test fun laneRadiiAndCentreStayInsideMargins() {
  // Comet (720x1440, density 2): the lane ends StatusFit.EDGE_DP (8 px) above the screen edge, the dot at its top.
  val bottom=1440-StatusFit.edgePx(2f);assertEquals(1432,bottom)
  for((lane,y) in listOf(24f to 1415,20f to 1419,16f to 1423,12f to 1426)) {
   assertEquals(y,ProgressMath.yc(bottom,lane,2f));val r=ProgressMath.rDot(lane,2f)
   assertTrue("dot inside the lane",y+0.5f-r>=bottom-lane&&y+0.5f+r<=bottom)
  }
  for(lane in listOf(24f,20f,16f)) assertEquals(6f,ProgressMath.rDot(lane,2f),0f)
  // The lane (12 dp, its own band since 2026-10-05): ≥ 18 px (1.6 mm) of paper under the dot, 24 px under the line.
  assertTrue(1440-(ProgressMath.yc(bottom,StatusFit.lanePx(2f).toFloat(),2f)+0.5f+6f)>=18f)
  assertEquals(0f,ProgressMath.rDot(0f,2f),0f) }
 @Test fun capTouchAndPixelRounding() { assertEquals(24,ProgressMath.x0(720,2f));assertEquals(696,ProgressMath.x1(720,2f));assertEquals(654,ProgressMath.trackPx(720,24f,2f));assertEquals(33.5f,ProgressMath.dotX(0f,720,24f,2f),0f);assertEquals(687.5f,ProgressMath.dotX(1f,720,24f,2f),0f);assertEquals(360.5f,ProgressMath.dotX(.5f,720,24f,2f),0f);assertTrue(ProgressMath.trackPx(720,12f,2f)>ProgressMath.trackPx(720,24f,2f)) }
}
