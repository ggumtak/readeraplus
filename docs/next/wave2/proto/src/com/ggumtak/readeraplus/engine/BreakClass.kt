package com.ggumtak.readeraplus.engine

/**
 * Line-breaking character classes used by the typesetter and [LineGeometry].
 *
 * One byte per UTF-16 code unit, built once (64 KB) so the hot loops do a single array load per char.
 * The classes are a small, Korean-oriented subset of UAX #14 plus the 금칙 (kinsoku) sets from the spec.
 */
internal object BreakClass {
    /** Letters, digits, symbols: no break opportunity of their own. */
    const val OTHER = 0
    /** Breaking white space: a break is allowed after a run of these; they hang at a line end. */
    const val SPACE = 1
    /** Opening punctuation: never break after it. */
    const val OPEN = 2
    /** Closing punctuation: never break before it. */
    const val CLOSE = 3
    /** ASCII `"` / `'`: closing unless preceded by a space (then opening). */
    const val QUOTE = 4
    /** Hangul syllables and leading/compatibility jamo. */
    const val HANGUL = 5
    /** CJK ideographs, kana, full-width forms, CJK extension lead surrogates: breaks allowed on both sides. */
    const val CJK = 6
    /** Hyphen and dashes: break after when followed by a letter. */
    const val DASH = 7
    /** U+200B zero width space: break after. */
    const val ZWSP = 8
    /** Never break before: combining marks, trailing jamo, low surrogates, variation selectors. */
    const val GLUE = 9
    /** Never break before or after: NBSP, ZWJ, word joiner, narrow NBSP, figure space. */
    const val JOIN = 10
    /**
     * Supplementary-plane emoji / pictograph lead surrogates. Like [CJK] in CHAR mode; in WORD (keep-all) mode
     * they belong to the surrounding word (like CSS keep-all), so "좋아😊" never breaks before the emoji.
     */
    const val EMOJI = 11

    @JvmField
    val TABLE: ByteArray = build()

    @JvmStatic
    fun of(c: Char): Int = TABLE[c.code].toInt()

    /** Spaces that receive extra width in EXPAND_SPACES justification (shared by layout and geometry). */
    @JvmStatic
    fun isExpandSpace(c: Char): Boolean = c == ' ' || c == '\u00A0' || c == '\u3000'

    private fun build(): ByteArray {
        val t = ByteArray(65536)
        // BMP blocks that contain combining marks (Mn/Mc/Me). Only these are queried with Character.getType,
        // which is an ICU call on Android: ~9k lookups instead of 65k keeps the one-time build cheap on device.
        val markRanges = intArrayOf(
            0x0300, 0x1DFF, 0x20D0, 0x20FF, 0x2CEF, 0x2DFF, 0x302A, 0x302F, 0x3099, 0x309A,
            0xA66F, 0xABFF, 0xFB1E, 0xFB1E, 0xFE00, 0xFE2F,
        )
        var r = 0
        while (r < markRanges.size) {
            for (cp in markRanges[r]..markRanges[r + 1]) {
                when (Character.getType(cp)) {
                    Character.NON_SPACING_MARK.toInt(),
                    Character.ENCLOSING_MARK.toInt(),
                    Character.COMBINING_SPACING_MARK.toInt() -> t[cp] = GLUE.toByte()
                }
            }
            r += 2
        }
        fun range(a: Int, b: Int, cls: Int) {
            for (cp in a..b) t[cp] = cls.toByte()
        }
        fun chars(s: String, cls: Int) {
            for (ch in s) t[ch.code] = cls.toByte()
        }
        // Hangul
        range(0xAC00, 0xD7A3, HANGUL)
        range(0x1100, 0x115F, HANGUL) // leading consonants
        range(0xA960, 0xA97F, HANGUL)
        range(0x3131, 0x318E, HANGUL) // compatibility jamo (ㅋㅋ, ㅠㅠ)
        range(0x1160, 0x11FF, GLUE) // medial vowels / final consonants of conjoining sequences
        range(0xD7B0, 0xD7FF, GLUE)
        // CJK ideographs, kana, full-width letters/digits
        range(0x2E80, 0x2FDF, CJK)
        range(0x3005, 0x3007, CJK)
        range(0x3040, 0x309F, CJK)
        range(0x30A0, 0x30FF, CJK)
        range(0x31F0, 0x31FF, CJK)
        range(0x3200, 0x33FF, CJK)
        range(0x3400, 0x4DBF, CJK)
        range(0x4E00, 0x9FFF, CJK)
        range(0xF900, 0xFAFF, CJK)
        range(0xFF10, 0xFF19, CJK)
        range(0xFF21, 0xFF3A, CJK)
        range(0xFF41, 0xFF5A, CJK)
        range(0xFF66, 0xFF9F, CJK)
        range(0xD83C, 0xD83E, EMOJI) // emoji lead surrogates (UAX #14 class ID)
        range(0xD840, 0xD87F, CJK) // CJK extension B+ lead surrogates
        range(0x3099, 0x309A, GLUE) // combining kana voiced marks
        // Low surrogates and variation selectors / tag lead surrogate
        range(0xDC00, 0xDFFF, GLUE)
        range(0xFE00, 0xFE0F, GLUE)
        t[0xDB40] = GLUE.toByte()
        // Spaces
        chars(" \t\n\r\u000B\u000C\u1680\u205F\u3000", SPACE)
        range(0x2000, 0x2006, SPACE)
        range(0x2008, 0x200A, SPACE)
        // Joiners / non-breaking spaces
        chars("\u00A0\u2007\u202F\u200D\u2060\uFEFF\u180E", JOIN)
        t[0x200B] = ZWSP.toByte()
        // Kinsoku sets
        chars("\u201C\u2018([{\u3008\u300A\u300C\u300E\u3010\u3014\u3016\u3018\u301A" +
            "\uFF08\uFF3B\uFF5B\uFF5F\uFF62\u00AB\u2039\u201E\u201A", OPEN)
        chars(".,!?:;\u2026\u2025\u201D\u2019)]}\u3009\u300B\u300D\u300F\u3011\u3015\u3017\u3019\u301B" +
            "\uFF5E~\u00B7\u3001\u3002\uFF0C\uFF09\uFF3D\uFF5D\uFF01\uFF1F\uFF1A\uFF1B\uFF0E\uFF61\uFF64" +
            "\uFF63\uFF60\u00BB\u203A\u30FB\u30FC\u301C\u2027\u203C\u2047\u2048\u2049\u3003" +
            // U+318D (araea) serves as the interpunct (middle dot) in modern Korean text, not as a letter.
            "\u318D", CLOSE)
        chars("\"'", QUOTE)
        chars("-\u2010\u2013\u2014\u2015", DASH)
        return t
    }
}
