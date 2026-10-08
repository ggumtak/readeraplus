package com.ggumtak.readeraplus.render.pdftext

/** Glyph names and the three PDF base encodings (PDF 1.7 Annex D), as Unicode code points. */
internal object PdfEncodings {
    private const val ASCII_NAMES =
        "space exclam quotedbl numbersign dollar percent ampersand quotesingle parenleft parenright asterisk plus " +
            "comma hyphen period slash zero one two three four five six seven eight nine colon semicolon less equal " +
            "greater question at A B C D E F G H I J K L M N O P Q R S T U V W X Y Z bracketleft backslash " +
            "bracketright asciicircum underscore grave a b c d e f g h i j k l m n o p q r s t u v w x y z " +
            "braceleft bar braceright asciitilde"

    private const val LATIN1_NAMES =
        "space exclamdown cent sterling currency yen brokenbar section dieresis copyright ordfeminine " +
            "guillemotleft logicalnot hyphen registered macron degree plusminus twosuperior threesuperior acute mu " +
            "paragraph periodcentered cedilla onesuperior ordmasculine guillemotright onequarter onehalf " +
            "threequarters questiondown Agrave Aacute Acircumflex Atilde Adieresis Aring AE Ccedilla Egrave Eacute " +
            "Ecircumflex Edieresis Igrave Iacute Icircumflex Idieresis Eth Ntilde Ograve Oacute Ocircumflex Otilde " +
            "Odieresis multiply Oslash Ugrave Uacute Ucircumflex Udieresis Yacute Thorn germandbls agrave aacute " +
            "acircumflex atilde adieresis aring ae ccedilla egrave eacute ecircumflex edieresis igrave iacute " +
            "icircumflex idieresis eth ntilde ograve oacute ocircumflex otilde odieresis divide oslash ugrave uacute " +
            "ucircumflex udieresis yacute thorn ydieresis"

    private const val EXTRA_NAMES =
        "Euro:20AC bullet:2022 quotesinglbase:201A florin:192 quotedblbase:201E ellipsis:2026 dagger:2020 " +
            "daggerdbl:2021 circumflex:2C6 perthousand:2030 Scaron:160 guilsinglleft:2039 OE:152 Zcaron:17D " +
            "quoteleft:2018 quoteright:2019 quotedblleft:201C quotedblright:201D endash:2013 emdash:2014 tilde:2DC " +
            "trademark:2122 scaron:161 guilsinglright:203A oe:153 zcaron:17E Ydieresis:178 fraction:2044 " +
            "dotlessi:131 Lslash:141 lslash:142 breve:2D8 dotaccent:2D9 ring:2DA ogonek:2DB caron:2C7 " +
            "hungarumlaut:2DD fi:FB01 fl:FB02 ff:FB00 ffi:FB03 ffl:FB04 minus:2212 notequal:2260 infinity:221E " +
            "lessequal:2264 greaterequal:2265 partialdiff:2202 summation:2211 product:220F pi:3C0 integral:222B " +
            "radical:221A approxequal:2248 Delta:2206 lozenge:25CA apple:F8FF nbspace:A0 nonbreakingspace:A0 " +
            "sfthyphen:AD softhyphen:AD middot:B7 hyphenminus:2D macron:AF overscore:AF asteriskmath:2217 " +
            "arrowright:2192 arrowleft:2190 arrowup:2191 arrowdown:2193 plusminus:B1 multiply:D7 divide:F7 " +
            "degree:B0 section:A7 bar:7C brokenbar:A6 Euro:20AC tab:20 afii10017:410 " +
            "Ccaron:10C ccaron:10D Rcaron:158 rcaron:159 Ecaron:11A ecaron:11B Dcaron:10E dcaron:10F " +
            "Ncaron:147 ncaron:148 Uring:16E uring:16F Tcaron:164 tcaron:165 Abreve:102 abreve:103 " +
            "Aogonek:104 aogonek:105 Cacute:106 cacute:107 Eogonek:118 eogonek:119 Nacute:143 nacute:144 " +
            "Sacute:15A sacute:15B Zacute:179 zacute:17A Zdotaccent:17B zdotaccent:17C Odblacute:150 odblacute:151 " +
            "Udblacute:170 udblacute:171 Gbreve:11E gbreve:11F Idotaccent:130 Scedilla:15E scedilla:15F " +
            "Tcommaaccent:162 tcommaaccent:163 Scommaaccent:218 scommaaccent:219"

    private val GREEK_UPPER = "Alpha Beta Gamma Delta Epsilon Zeta Eta Theta Iota Kappa Lambda Mu Nu Xi Omicron Pi Rho " +
        "- Sigma Tau Upsilon Phi Chi Psi Omega"
    private val GREEK_LOWER = "alpha beta gamma delta epsilon zeta eta theta iota kappa lambda mu nu xi omicron pi rho " +
        "sigma1 sigma tau upsilon phi chi psi omega"

    /** The Latin-1 supplement names, exposed for tests (must be 96). */
    val latin1Names: List<String> = LATIN1_NAMES.split(' ')
    val asciiNames: List<String> = ASCII_NAMES.split(' ')

    private val nameMap: HashMap<String, Int> by lazy {
        val m = HashMap<String, Int>(1024)
        for ((i, n) in asciiNames.withIndex()) m[n] = 0x20 + i
        for ((i, n) in latin1Names.withIndex()) m.putIfAbsent(n, 0xA0 + i)
        m["Delta"] = 0x2206
        for (e in EXTRA_NAMES.split(' ')) {
            val c = e.indexOf(':')
            if (c > 0) m[e.substring(0, c)] = e.substring(c + 1).toInt(16)
        }
        m["mu"] = 0xB5
        m["Omega"] = 0x2126
        for ((i, n) in GREEK_UPPER.split(' ').withIndex()) {
            if (n != "-") m.putIfAbsent(n, 0x391 + i)
        }
        for ((i, n) in GREEK_LOWER.split(' ').withIndex()) m.putIfAbsent(n, 0x3B1 + i)
        m["Omega"] = 0x3A9
        m["mu"] = 0xB5
        m["pi"] = 0x3C0
        m["Delta"] = 0x394
        m
    }

    private val cpStrings = arrayOfNulls<String>(256)

    /** String of one code point (cached for Latin-1). */
    fun cpString(cp: Int): String {
        if (cp in 0..255) {
            cpStrings[cp]?.let { return it }
            val s = cp.toChar().toString()
            cpStrings[cp] = s
            return s
        }
        return String(Character.toChars(cp))
    }

    /** Unicode text of a glyph name, or null when it carries none (`gNN`, `cidNN`, unknown names). */
    fun nameToUnicode(name: String): String? {
        if (name.isEmpty()) return null
        nameMap[name]?.let { return cpString(it) }
        val dot = name.indexOf('.')
        if (dot == 0) return null
        val base = if (dot > 0) name.substring(0, dot) else name
        if (dot > 0) nameMap[base]?.let { return cpString(it) }
        if (base.indexOf('_') > 0) {
            val sb = StringBuilder()
            for (part in base.split('_')) sb.append(nameToUnicode(part) ?: return null)
            return sb.toString()
        }
        if (base.startsWith("uni") && base.length >= 7 && (base.length - 3) % 4 == 0) {
            val sb = StringBuilder()
            var i = 3
            while (i < base.length) {
                val v = hexOrNeg(base, i, i + 4)
                if (v < 0 || (v in 0xD800..0xDFFF)) return null
                sb.append(v.toChar())
                i += 4
            }
            return sb.toString()
        }
        if (base[0] == 'u' && base.length in 5..7) {
            val v = hexOrNeg(base, 1, base.length)
            if (v in 0..0x10FFFF && v !in 0xD800..0xDFFF) return cpString(v)
        }
        return null
    }

    private fun hexOrNeg(s: String, from: Int, to: Int): Int {
        var v = 0
        for (i in from until to) {
            val h = hexVal(s[i].code)
            if (h < 0) return -1
            v = v * 16 + h
        }
        return v
    }

    private val CP1252_HIGH = intArrayOf(
        0x20AC, 0x2022, 0x201A, 0x192, 0x201E, 0x2026, 0x2020, 0x2021, 0x2C6, 0x2030, 0x160, 0x2039, 0x152, 0x2022, 0x17D, 0x2022,
        0x2022, 0x2018, 0x2019, 0x201C, 0x201D, 0x2022, 0x2013, 0x2014, 0x2DC, 0x2122, 0x161, 0x203A, 0x153, 0x2022, 0x17E, 0x178,
    )

    private val MAC_HIGH = intArrayOf(
        0xC4, 0xC5, 0xC7, 0xC9, 0xD1, 0xD6, 0xDC, 0xE1, 0xE0, 0xE2, 0xE4, 0xE3, 0xE5, 0xE7, 0xE9, 0xE8,
        0xEA, 0xEB, 0xED, 0xEC, 0xEE, 0xEF, 0xF1, 0xF3, 0xF2, 0xF4, 0xF6, 0xF5, 0xFA, 0xF9, 0xFB, 0xFC,
        0x2020, 0xB0, 0xA2, 0xA3, 0xA7, 0x2022, 0xB6, 0xDF, 0xAE, 0xA9, 0x2122, 0xB4, 0xA8, 0x2260, 0xC6, 0xD8,
        0x221E, 0xB1, 0x2264, 0x2265, 0xA5, 0xB5, 0x2202, 0x2211, 0x220F, 0x3C0, 0x222B, 0xAA, 0xBA, 0x3A9, 0xE6, 0xF8,
        0xBF, 0xA1, 0xAC, 0x221A, 0x192, 0x2248, 0x2206, 0xAB, 0xBB, 0x2026, 0xA0, 0xC0, 0xC3, 0xD5, 0x152, 0x153,
        0x2013, 0x2014, 0x201C, 0x201D, 0x2018, 0x2019, 0xF7, 0x25CA, 0xFF, 0x178, 0x2044, 0xA4, 0x2039, 0x203A, 0xFB01, 0xFB02,
        0x2021, 0xB7, 0x201A, 0x201E, 0x2030, 0xC2, 0xCA, 0xC1, 0xCB, 0xC8, 0xCD, 0xCE, 0xCF, 0xCC, 0xD3, 0xD4,
        0xF8FF, 0xD2, 0xDA, 0xDB, 0xD9, 0x131, 0x2C6, 0x2DC, 0xAF, 0x2D8, 0x2D9, 0x2DA, 0xB8, 0x2DD, 0x2DB, 0x2C7,
    )

    /** Pairs of (code, code point) for the upper half of StandardEncoding. */
    private val STD_HIGH = intArrayOf(
        0xA1, 0xA1, 0xA2, 0xA2, 0xA3, 0xA3, 0xA4, 0x2044, 0xA5, 0xA5, 0xA6, 0x192, 0xA7, 0xA7, 0xA8, 0xA4,
        0xA9, 0x27, 0xAA, 0x201C, 0xAB, 0xAB, 0xAC, 0x2039, 0xAD, 0x203A, 0xAE, 0xFB01, 0xAF, 0xFB02,
        0xB1, 0x2013, 0xB2, 0x2020, 0xB3, 0x2021, 0xB4, 0xB7, 0xB6, 0xB6, 0xB7, 0x2022, 0xB8, 0x201A,
        0xB9, 0x201E, 0xBA, 0x201D, 0xBB, 0xBB, 0xBC, 0x2026, 0xBD, 0x2030, 0xBF, 0xBF,
        0xC1, 0x60, 0xC2, 0xB4, 0xC3, 0x2C6, 0xC4, 0x2DC, 0xC5, 0xAF, 0xC6, 0x2D8, 0xC7, 0x2D9, 0xC8, 0xA8,
        0xCA, 0x2DA, 0xCB, 0xB8, 0xCD, 0x2DD, 0xCE, 0x2DB, 0xCF, 0x2C7, 0xD0, 0x2014,
        0xE1, 0xC6, 0xE3, 0xAA, 0xE8, 0x141, 0xE9, 0xD8, 0xEA, 0x152, 0xEB, 0xBA,
        0xF1, 0xE6, 0xF5, 0x131, 0xF8, 0x142, 0xF9, 0xF8, 0xFA, 0x153, 0xFB, 0xDF,
    )

    /** WinAnsiEncoding: code -> code point (0 = none). */
    val winAnsi: IntArray by lazy {
        val t = IntArray(256)
        for (c in 0x20..0x7E) t[c] = c
        for (c in 0x80..0x9F) t[c] = CP1252_HIGH[c - 0x80]
        for (c in 0xA0..0xFF) t[c] = c
        t[0xAD] = 0x2D
        t
    }

    val macRoman: IntArray by lazy {
        val t = IntArray(256)
        for (c in 0x20..0x7E) t[c] = c
        for (c in 0x80..0xFF) t[c] = MAC_HIGH[c - 0x80]
        t
    }

    val standard: IntArray by lazy {
        val t = IntArray(256)
        for (c in 0x20..0x7E) t[c] = c
        t[0x27] = 0x2019
        t[0x60] = 0x2018
        var i = 0
        while (i < STD_HIGH.size) {
            t[STD_HIGH[i]] = STD_HIGH[i + 1]
            i += 2
        }
        t
    }

    fun byName(name: String?): IntArray? = when (name) {
        "WinAnsiEncoding" -> winAnsi
        "MacRomanEncoding" -> macRoman
        "StandardEncoding" -> standard
        else -> null
    }
}
