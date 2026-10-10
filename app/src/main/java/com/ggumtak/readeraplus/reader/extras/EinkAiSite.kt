package com.ggumtak.readeraplus.reader.extras

import java.net.URI
import java.net.URLEncoder

/** The lightweight AI dictionary's device-local address. No API credential belongs in the app or URL. */
internal object EinkAiSite {
    const val PREF_KEY = "extras.einkAiSite"
    const val DEFAULT_URL = "https://readeraplus-ai-dictionary.jinaoneday.chatgpt.site"

    /** Accept a HTTPS page address; a pasted lookup's old query/fragment is removed. */
    fun normalize(address: String): String? = try {
        val uri = URI(address.trim()).normalize()
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank() ||
            uri.userInfo != null || uri.port > 65535) null
        else uri.toASCIIString().substringBefore('?').substringBefore('#').trimEnd('/')
    } catch (_: Exception) { null }

    /** The selected text stays in the fragment, outside HTTP request URLs and referrer headers. */
    fun url(address: String, prompt: String): String =
        (normalize(address) ?: DEFAULT_URL) + "#q=" +
            URLEncoder.encode(prompt, "UTF-8").replace("+", "%20") + "&eink=1"
}
