package com.ggumtak.readeraplus.reader.extras

import java.net.URI
import java.net.URLDecoder
import org.junit.Assert.*
import org.junit.Test

class EinkAiSiteTest {
    @Test fun selectionAndReservedCharactersStayInTheFragment() {
        val prompt = "귀접 & a=b#c 뜻"
        val uri = URI(EinkAiSite.url(prompt))
        assertNull(uri.rawQuery)
        assertEquals(EinkAiSite.HOST, uri.host)
        assertTrue(EinkAiSite.isDocument(uri.toString()))
        assertEquals(prompt, URLDecoder.decode(uri.rawFragment.substringAfter("q=").substringBefore("&eink="), "UTF-8"))
        assertTrue(uri.rawFragment.endsWith("&eink=1"))
    }

    @Test fun onlyTheBundledDocumentCanNavigate() {
        assertTrue(EinkAiSite.isDocument(EinkAiSite.DEFAULT_URL))
        for (address in listOf("http://${EinkAiSite.HOST}${EinkAiSite.PATH}",
            "https://evil.example${EinkAiSite.PATH}", "https://${EinkAiSite.HOST}.evil.example${EinkAiSite.PATH}",
            "https://user@${EinkAiSite.HOST}${EinkAiSite.PATH}",
            "https://${EinkAiSite.HOST}:443${EinkAiSite.PATH}",
            "https://${EinkAiSite.HOST}/other.html", "${EinkAiSite.DEFAULT_URL}?key=secret",
            "https://${EinkAiSite.HOST}/%72eaderaplus-ai-dictionary.html", "not a url")) {
            assertFalse(address, EinkAiSite.isDocument(address))
        }
    }

    @Test fun onlyTheExactClaudeEndpointCanLeaveAsARequest() {
        assertTrue(EinkAiSite.isApiRequest("https://api.anthropic.com/v1/messages"))
        for (address in listOf("http://api.anthropic.com/v1/messages",
            "https://api.anthropic.com.evil.example/v1/messages", "https://api.anthropic.com/v1/messages?key=secret",
            "https://user@api.anthropic.com/v1/messages", "https://api.anthropic.com/v1/other",
            "https://chatgpt.com/", "javascript:alert(1)")) {
            assertFalse(address, EinkAiSite.isApiRequest(address))
        }
    }

    @Test fun sourcesCanOnlyOpenOrdinaryWebAddressesWithoutCredentials() {
        assertTrue(EinkAiSite.isSourceLink("https://example.com/article?q=test#source"))
        assertTrue(EinkAiSite.isSourceLink("http://example.com/article"))
        for (address in listOf("javascript:alert(1)", "intent://example.com/", "file:///secret",
            "data:text/html,test", "https://user:password@example.com/", EinkAiSite.DEFAULT_URL,
            EinkAiSite.API_URL, "https://API.ANTHROPIC.COM/", "https://APPASSETS.ANDROIDPLATFORM.NET/",
            "https:/example.com", "not a url")) {
            assertFalse(address, EinkAiSite.isSourceLink(address))
        }
    }
}
