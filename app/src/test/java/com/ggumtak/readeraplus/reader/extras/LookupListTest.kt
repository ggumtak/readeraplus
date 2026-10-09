package com.ggumtak.readeraplus.reader.extras

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LookupListTest {
    private fun e(key: String, label: String, app: String = "") = LookupList.Entry(key, label, app)

    private val phone = listOf(
        e("p/T", "번역"),
        e("anki/A", "Anki 카드"),
        e("gpt/G", "ChatGPT에게 물어보세요"),
        e("claude/C", "Claude에게 묻기"),
        e("gemini/G", "Gemini에게 물어보기"),
        e("grok/G", "Grok에게 물어보기"),
        e("copilot/C", "M365 Copilot에게 질문하기"),
        e("pass/P", "삼성 패스에 저장"),
    )

    @Test
    fun naverFirstThenByLabelWhateverTheInputOrder() {
        val naver = e(LookupList.NAVERDIC_KEY, "네이버 사전")
        val want = listOf(
            LookupList.NAVERDIC_KEY, "anki/A", "gpt/G", "claude/C", "gemini/G", "grok/G", "copilot/C", "p/T", "pass/P",
        )
        assertEquals(want, LookupList.order(naver, phone).map { it.key })
        assertEquals(want, LookupList.order(naver, phone.reversed()).map { it.key })
        assertEquals(want, LookupList.order(naver, phone.shuffled(java.util.Random(7))).map { it.key })
    }

    @Test
    fun withoutTheNaverAppOnlyTheAppsAreListed() {
        assertEquals(listOf("a/1", "b/2"), LookupList.order(null, listOf(e("b/2", "나비"), e("a/1", "가방"))).map { it.key })
        assertEquals(emptyList<String>(), LookupList.order(null, emptyList()).map { it.key })
        assertEquals(listOf("naverdic"), LookupList.order(e("naverdic", "네이버 사전"), emptyList()).map { it.key })
    }

    @Test
    fun equalLabelsKeepAFixedOrderByKey() {
        val a = e("z/Z", "사전")
        val b = e("a/A", "사전")
        assertEquals(listOf("a/A", "z/Z"), LookupList.order(null, listOf(a, b)).map { it.key })
        assertEquals(listOf("a/A", "z/Z"), LookupList.order(null, listOf(b, a)).map { it.key })
    }

    @Test
    fun koreanLabelsSortInHangulOrder() {
        val list = listOf(e("3", "하늘"), e("1", "가방"), e("2", "나비"))
        assertEquals(listOf("1", "2", "3"), LookupList.order(null, list).map { it.key })
    }

    @Test
    fun hiddenKeysAreSkipped() {
        val all = LookupList.order(e(LookupList.NAVERDIC_KEY, "네이버 사전"), phone)
        val shown = LookupList.visible(all, setOf("gpt/G", LookupList.NAVERDIC_KEY, "gone/X"))
        assertEquals(all.size - 2, shown.size)
        assertEquals(false, shown.any { it.key == "gpt/G" || it.key == LookupList.NAVERDIC_KEY })
        assertEquals(all, LookupList.visible(all, emptySet()))
    }

    @Test
    fun firstUseHidesChatGptGrokCopilotAndSamsungPass() {
        val all = LookupList.order(e(LookupList.NAVERDIC_KEY, "네이버 사전"), phone)
        assertEquals(setOf("gpt/G", "grok/G", "copilot/C", "pass/P"), LookupList.seed(all))
        assertEquals(LookupList.seed(all), LookupList.hidden(null, all))
        // Latin names match in any case.
        assertEquals(setOf("x/1", "x/2"), LookupList.seed(listOf(e("x/1", "chatgpt"), e("x/2", "GROK ask"), e("x/3", "Gemini"))))
    }

    @Test
    fun aStoredSetIsKeptAsIs() {
        assertEquals(emptySet<String>(), LookupList.hidden(emptySet(), phone))
        assertEquals(setOf("anki/A"), LookupList.hidden(setOf("anki/A"), phone))
    }

    @Test
    fun titleAddsTheAppNameOnlyWhenItDiffers() {
        assertEquals("번역 (Google 번역)", LookupList.title(e("a", "번역", "Google 번역")))
        assertEquals("번역", LookupList.title(e("a", "번역", "번역")))
        assertEquals("번역", LookupList.title(e("a", "번역")))
        assertNull(LookupList.order(null, emptyList()).firstOrNull())
    }
}
