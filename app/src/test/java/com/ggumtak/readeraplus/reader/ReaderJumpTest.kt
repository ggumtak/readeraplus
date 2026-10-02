package com.ggumtak.readeraplus.reader

import org.junit.Assert.*
import org.junit.Test
import com.ggumtak.readeraplus.format.DocPosition

class ReaderJumpTest {
 @Test fun sanitizeRejectsBadCoordinatesAndFractions() { assertNull(ReaderJump.sanitize(-1,0,-1,.2f,null,null));assertNull(ReaderJump.sanitize(0,-1,-1,.2f,null,null));for (f in listOf(Float.NaN,Float.POSITIVE_INFINITY,1.1f,-2f)) assertNull(ReaderJump.sanitize(0,0,-1,f,null,null));val j=ReaderJump.sanitize(2,10,5,.5f,"sig","가".repeat(100))!!;assertEquals(-1,j.end);assertEquals(64,j.anchor.length);assertEquals(2,j.section) }
 @Test fun resolveCoordinatesAndFallbacks() { val fallback={f:Float->DocPosition(7,(f*100).toInt())};val j=ReaderJump(2,20,frac=.5f,sig="x");assertEquals(DocPosition(2,20),ReaderJump.resolve(j,"x",8,fallback));assertEquals(DocPosition(7,50),ReaderJump.resolve(j,"y",8,fallback));assertEquals(DocPosition(7,50),ReaderJump.resolve(j.copy(section=99),"x",8,fallback));assertNull(ReaderJump.resolve(j.copy(section=99,frac=-1f),"x",8,fallback));assertEquals(DocPosition(2,20),ReaderJump.resolve(j.copy(sig=""),"y",8,fallback)) }
 @Test fun anchorIgnoresWhitespaceAndObjectMarkers() { assertTrue(JumpAnchor.matches("가 \n나￼다",0,"가나다"));assertFalse(JumpAnchor.matches("가나다",0,"가너다"));assertTrue(JumpAnchor.matches("",0,""));assertFalse(JumpAnchor.matches("가",5,"가"));assertTrue(JumpAnchor.matches("가".repeat(24)+"나",0,"가".repeat(24)+"다")) }
 @Test fun fileSignatureIncludesSize() { assertEquals("e:12",NoteSig.of(null,12));assertEquals("txt:12",NoteSig.of("txt",12));assertNotEquals(NoteSig.of(null,12),NoteSig.of(null,13)) }
}
