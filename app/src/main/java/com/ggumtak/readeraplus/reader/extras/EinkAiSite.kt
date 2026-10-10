package com.ggumtak.readeraplus.reader.extras

import java.net.URI
import java.net.URLEncoder

/** Stable HTTPS origin for the bundled dictionary. Never fetched from the network. */
internal object EinkAiSite {
    const val HOST = "appassets.androidplatform.net"
    const val PATH = "/readeraplus-ai-dictionary.html"
    const val ASSET = "ai-dictionary.html"
    const val DEFAULT_URL = "https://$HOST$PATH"
    const val API_URL = "https://api.anthropic.com/v1/messages"

    /** Only our exact bundled page may navigate in the key-bearing WebView. */
    fun isDocument(address: String): Boolean = try {
        val uri = URI(address)
        uri.scheme == "https" && uri.host == HOST && uri.rawPath == PATH &&
            uri.userInfo == null && uri.port == -1 && uri.rawQuery == null
    } catch (_: Exception) { false }

    /** Only this HTTPS API endpoint may leave the bundled page as a subresource request. */
    fun isApiRequest(address: String): Boolean = address == API_URL

    /** The selected text stays in the fragment, outside HTTP request URLs and referrer headers. */
    fun url(prompt: String): String =
        DEFAULT_URL + "#q=" +
            URLEncoder.encode(prompt, "UTF-8").replace("+", "%20") + "&eink=1"
}
