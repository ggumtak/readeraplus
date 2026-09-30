package com.ggumtak.readeraplus.render

/** Fonts shipped in assets/fonts (all SIL OFL 1.1, unmodified; notices in assets/fonts/licenses). */
object FontCatalog {
    class Bundled(val id: String, val name: String, val regular: String, val bold: String?, val serif: Boolean)

    val BUNDLED: List<Bundled> = listOf(
        Bundled("ridibatang", "리디바탕", "fonts/RIDIBatang.otf", null, true),
        Bundled("nanummyeongjo", "나눔명조", "fonts/NanumMyeongjo.otf", "fonts/NanumMyeongjoBold.otf", true),
        Bundled("maruburi", "마루 부리", "fonts/MaruBuri-Regular.otf", "fonts/MaruBuri-Bold.otf", true),
        Bundled("iropkebatang", "이롭게 바탕", "fonts/IropkeBatangM.otf", null, true),
        Bundled("bareonbatang", "학교안심 바른바탕", "fonts/HakgyoansimBareonbatangR.otf", "fonts/HakgyoansimBareonbatangB.otf", true),
        Bundled("nanumbarungothic", "나눔바른고딕", "fonts/NanumBarunGothic.otf", "fonts/NanumBarunGothicBold.otf", false),
        Bundled("pretendard", "프리텐다드", "fonts/Pretendard-Regular.otf", "fonts/Pretendard-Bold.otf", false),
        Bundled("suit", "SUIT", "fonts/SUIT-Regular.otf", "fonts/SUIT-Bold.otf", false),
    )

    /** Uses the device's default serif/sans (no asset). */
    const val SYSTEM_SERIF = "system:serif"
    const val SYSTEM_SANS = "system:sans"
    /**
     * The default reading font (= `ReaderSettings().fontId`), also the fallback for a missing font id (FontManager)
     * and the face of generated covers (Covers): one inflated CJK typeface serves the library and the first book.
     */
    const val DEFAULT_ID = "nanummyeongjo"
}
