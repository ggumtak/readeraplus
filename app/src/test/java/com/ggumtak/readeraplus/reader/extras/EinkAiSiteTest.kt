package com.ggumtak.readeraplus.reader.extras

import java.net.URI
import java.net.URLDecoder
import org.junit.Assert.*
import org.junit.Test

class EinkAiSiteTest {
    @Test fun selectionAndReservedCharactersStayInTheFragment() {
        val prompt = "귀접 & a=b#c 뜻"
        val uri = URI(EinkAiSite.url("https://dictionary.example/", prompt))
        assertNull(uri.rawQuery)
        assertEquals("dictionary.example", uri.host)
        assertEquals(prompt, URLDecoder.decode(uri.rawFragment.substringAfter("q=").substringBefore("&eink="), "UTF-8"))
        assertTrue(uri.rawFragment.endsWith("&eink=1"))
    }

    @Test fun pastedLookupAddressesDropOldQueryAndFragment() {
        assertEquals("https://dictionary.example/chat", EinkAiSite.normalize(" https://dictionary.example/chat/?old=word#q=old "))
        assertEquals("https://dictionary.example", EinkAiSite.normalize("https://dictionary.example/"))
    }

    @Test fun unsafeOrMalformedAddressesAreRejected() {
        for (address in listOf("javascript:alert(1)", "http://dictionary.example", "https://user:password@dictionary.example", "https://", "https://dictionary.example:70000", "not a url")) {
            assertNull(address, EinkAiSite.normalize(address))
        }
        assertTrue(EinkAiSite.url("invalid", "말 뜻").startsWith(EinkAiSite.DEFAULT_URL + "#q="))
    }
}
