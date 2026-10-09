package com.ggumtak.readeraplus.render

/**
 * Fonts shipped in assets/fonts (all SIL OFL 1.1, unmodified; notices in assets/fonts/licenses).
 *
 * 나눔명조 is Naver's TrueType release (3.011, as in Debian's fonts-nanum), not its OTF: the OTF maps all 4,888
 * KS X 1001 Hanja (and 、 。) to blank glyphs, so Android never fell back and 聖 drew as a gap (user report
 * 2026-10-05, "성(   )과 속(   )"). The TTF has no Hanja at all, like MaruViewer's 나눔명조: the system font draws
 * them. The Hangul, Latin and punctuation advances are the same, the line box metrics differ (ascent / descent 0.92 /
 * 0.23 em, were 0.80 / 0.30), and a few symbols changed (fontTools, both weights): 14 characters the OTF drew are not
 * in the TTF and come from the system font now (¢ £ ¥ ¬ ‐ ‾ ∶ ⋯ ⥣ ⥥ ⫋ ⫌ 〜 ・; 〜 and ・ do occur in Korean prose),
 * and a few advances moved (₩ 0.95 → 1.0 em; ‼ ㊞ ㏋ → 0.928 em; Bold ㊔ ㊥ 0.969 → 0.95 em). Cached page counts are
 * keyed by the asset path, so they were counted again anyway. `HollowGlyphsTest` keeps every bundled file from drawing
 * Hanja or 、 。 blank.
 */
object FontCatalog {
    class Bundled(val id: String, val name: String, val regular: String, val bold: String?, val serif: Boolean)

    val BUNDLED: List<Bundled> = listOf(
        Bundled("ridibatang", "리디바탕", "fonts/RIDIBatang.otf", null, true),
        Bundled("nanummyeongjo", "나눔명조", "fonts/NanumMyeongjo.ttf", "fonts/NanumMyeongjoBold.ttf", true),
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
