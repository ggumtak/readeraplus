package com.ggumtak.readeraplus.render

import org.junit.Assert.*
import org.junit.Test
import com.ggumtak.readeraplus.data.DataLimits

class QuoteStylesTest {
 private fun lum(c:Int):Double { fun component(s:Int):Double { val v=((c ushr s) and 255)/255.0;return if(v<=.04045)v/12.92 else Math.pow((v+.055)/1.055,2.4) };return .2126*component(16)+.7152*component(8)+.0722*component(0) }
 @Test fun paletteIsReadableInBothSchemes() { for(s in 0..4) { assertTrue("day $s",(lum(QuoteStyles.colorFill(s,false))+.05)/.05>=12.0);assertTrue("night $s",1.05/(lum(QuoteStyles.colorFill(s,true))+.05)>=8.0) } }
 @Test fun exactInkLevelsAndDistinctMarks() { for(s in 0 until QuoteStyles.COUNT) { val g=QuoteStyles.inkGrey(s);assertTrue(g<0 || g in 0xBB..0xEE && g%0x11==0);assertTrue(QuoteStyles.thumbGrey(s)>=0);for(t in 0 until s) assertTrue(g!=QuoteStyles.inkGrey(t) || QuoteStyles.inkLine(s)!=QuoteStyles.inkLine(t)) };assertEquals(DataLimits.QUOTE_STYLE_MAX,QuoteStyles.MAX_STORED) }
 @Test fun stableIdsAndKoreanNames() { assertEquals(0,QuoteStyles.of(15));assertEquals(0,QuoteStyles.of(-1));assertEquals(listOf("노랑","초록","파랑","빨강","보라","밑줄"),(0..5).map { QuoteStyles.label(it) });assertEquals("[초록]",QuoteStyles.tag(1));assertEquals(0,QuoteStyles.colorFill(5,false)) }
}
