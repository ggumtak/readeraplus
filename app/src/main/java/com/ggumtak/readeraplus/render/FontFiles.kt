package com.ggumtak.readeraplus.render

/** Pure helpers for font file names, import naming, bold-file pairing and weight math (unit-tested). */
internal object FontFiles {
    const val USER_PREFIX = "user:"
    private val FONT_EXTS = arrayOf("ttf", "otf", "ttc")
    private const val MAX_NAME = 120
    /** File names are limited to 255 bytes (UTF-8): 3 bytes per Hangul syllable. */
    private const val MAX_NAME_BYTES = 200

    private val REGULAR_STYLES = setOf("regular", "normal", "book", "roman", "plain", "standard", "보통", "표준", "일반", "레귤러")

    /** Weight/style words that mark a file as a non-regular face (never paired with a bold sibling). */
    private val WEIGHT_TOKENS = arrayOf(
        "extrabold", "ultrabold", "semibold", "demibold", "extralight", "ultralight", "hairline",
        "bold", "light", "thin", "medium", "black", "heavy", "italic", "oblique",
    )

    private val BOLD_SUFFIXES = arrayOf("-Bold", "_Bold", " Bold", "Bold", "-B", "_B", "B")
    private val REGULAR_SUFFIXES = arrayOf("regular", "normal", "book", "roman", "r")

    private val SANS_WORDS = arrayOf("gothic", "sans", "고딕", "돋움", "dotum", "굴림", "gulim", "grotesk", "pretendard", "suit", "round")
    private val SERIF_WORDS = arrayOf("myeongjo", "명조", "batang", "바탕", "serif", "부리", "mincho", "song", "ming", "garamond", "times")

    fun isFontFileName(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in FONT_EXTS && !name.startsWith(".")
    }

    fun userId(fileName: String): String = USER_PREFIX + fileName

    fun isRegularStyleName(s: String): Boolean = s.trim().lowercase() in REGULAR_STYLES

    /** File extension matching the sfnt signature in [header] (otf for CFF, ttc for collections, else ttf). */
    fun extensionFor(header: ByteArray): String {
        if (header.size < 4) return "ttf"
        return when (SfntReader.u32(header, 0)) {
            SfntReader.TAG_OTTO -> "otf"
            SfntReader.TAG_TTCF -> "ttc"
            else -> "ttf"
        }
    }

    /**
     * Safe file name for an imported font: path parts and illegal characters removed, length capped, and a
     * font extension guaranteed (taken from [header] when the display name has none).
     */
    fun sanitizeFileName(displayName: String?, header: ByteArray, fallbackStamp: Long): String {
        var n = (displayName ?: "").substringAfterLast('/').substringAfterLast('\\')
        val sb = StringBuilder(n.length)
        for (c in n) {
            sb.append(if (c < ' ' || c == '\u007F' || c in "/\\:*?\"<>|") '_' else c)
        }
        n = sb.toString().trim()
        var ext = n.substringAfterLast('.', "").lowercase()
        var stem = if (n.contains('.')) n.substringBeforeLast('.') else n
        if (ext !in FONT_EXTS) {
            stem = n
            ext = extensionFor(header)
        }
        stem = stem.trimStart('.').trim()
        if (stem.isEmpty()) stem = "font_$fallbackStamp"
        stem = truncate(stem, MAX_NAME, MAX_NAME_BYTES).trim()
        if (stem.isEmpty()) stem = "font_$fallbackStamp"
        return "$stem.$ext"
    }

    /** Longest prefix of [s] with at most [maxChars] chars and [maxUtf8Bytes] UTF-8 bytes; never splits a surrogate pair. */
    fun truncate(s: String, maxChars: Int, maxUtf8Bytes: Int): String {
        var bytes = 0
        var i = 0
        val n = s.length
        while (i < n) {
            val c = s[i]
            var units = 1
            val len = when {
                c.code < 0x80 -> 1
                c.code < 0x800 -> 2
                Character.isHighSurrogate(c) && i + 1 < n && Character.isLowSurrogate(s[i + 1]) -> { units = 2; 4 }
                else -> 3
            }
            if (i + units > maxChars || bytes + len > maxUtf8Bytes) break
            bytes += len
            i += units
        }
        return if (i == n) s else s.substring(0, i)
    }

    /** Lower-cased file name → actual name, for [findBoldSibling] over a whole folder (built once per folder). */
    fun lowerIndex(names: Collection<String>): Map<String, String> {
        val m = HashMap<String, String>(names.size * 2)
        for (s in names) m[s.lowercase()] = s
        return m
    }

    /**
     * Finds the bold companion of a regular static font file among [siblings] (file names in the same folder):
     * `X-Regular.ttf` → `X-Bold.ttf`, `NanumFoo.ttf` → `NanumFooBold.ttf`, `Foo_R.otf` → `Foo_B.otf`.
     * Returns null for files that are themselves a non-regular face.
     */
    fun findBoldSibling(fileName: String, siblings: Collection<String>): String? = findBoldSibling(fileName, lowerIndex(siblings))

    /** [findBoldSibling] against a prebuilt [lowerIndex] of the folder. */
    fun findBoldSibling(fileName: String, lower: Map<String, String>): String? {
        val ext = fileName.substringAfterLast('.', "")
        if (ext.isEmpty()) return null
        val base = fileName.substring(0, fileName.length - ext.length - 1)
        // "FooBatangR" ↔ "FooBatangB" (Korean foundries often mark weights with a trailing capital letter).
        if (base.length > 2 && base.last() == 'R' && base[base.length - 2].isLowerCase()) {
            val cand = base.substring(0, base.length - 1) + "B"
            for (e in FONT_EXTS) lower["$cand.$e".lowercase()]?.let { return it }
        }
        val stem = regularStem(base) ?: return null
        if (stem.isEmpty()) return null
        for (suf in BOLD_SUFFIXES) {
            val cand = "$stem$suf"
            // "B" style suffixes only when the regular file used the matching "R" convention.
            if ((suf == "B" || suf == "-B" || suf == "_B") && !base.endsWith(suf.replace('B', 'R'))) continue
            for (e in FONT_EXTS) {
                val hit = lower["$cand.$e".lowercase()]
                if (hit != null && !hit.equals(fileName, ignoreCase = true)) return hit
            }
        }
        return null
    }

    /** Stem of a regular-face file base name (regular token removed), or null if it names another weight. */
    private fun regularStem(base: String): String? {
        val l = base.lowercase()
        for (r in REGULAR_SUFFIXES) {
            for (sep in arrayOf("-", "_", " ", "")) {
                val tok = sep + r
                if (r == "r" && sep.isEmpty()) continue
                if (l.endsWith(tok) && l.length > tok.length) {
                    val stem = base.substring(0, base.length - tok.length)
                    return if (hasWeightSuffix(stem.lowercase())) null else stem
                }
            }
        }
        if (hasWeightSuffix(l)) return null
        return base
    }

    private fun hasWeightSuffix(l: String): Boolean {
        for (t in WEIGHT_TOKENS) if (l.endsWith(t)) return true
        return false
    }

    /** Serif guess for a user font: name keywords first, then the PANOSE hint, default serif. */
    fun serifGuess(name: String, sansHint: Boolean?): Boolean {
        val l = name.lowercase()
        for (w in SANS_WORDS) if (l.contains(w)) return false
        for (w in SERIF_WORDS) if (l.contains(w)) return true
        return sansHint != true
    }
}

/** Weight / stroke arithmetic shared by FontManager and the measurer (unit-tested). */
internal object FontMath {
    /** Static bold files are assumed to be ≈ 700; synthetic stroke only covers what the file doesn't. */
    const val BOLD_FILE_WEIGHT_OFFSET = 300
    /** Stroke per 100 weight units above 400, as a fraction of the text size. */
    const val STROKE_PER_100 = 0.012f

    /** Clamps to 100..900 and rounds to the nearest 50 (the settings step). */
    fun normalizeWeight(weight: Int): Int {
        val w = weight.coerceIn(100, 900)
        return ((w + 25) / 50) * 50
    }

    /** The regular weight: the lightest a static (non-variable) font file can show. */
    const val REGULAR = 400

    /** A font's own weight (see [FontInfo.naturalWeight]): the default `wght` of a variable font, else [REGULAR]. */
    fun naturalWeight(variable: Boolean, wghtDefault: Float): Int =
        if (variable && wghtDefault >= 100f && wghtDefault <= 900f) normalizeWeight(Math.round(wghtDefault)) else REGULAR

    /**
     * Lowest body weight that renders differently from [REGULAR]: variable fonts (`wght` axis) and the system
     * faces can go lighter, a static file cannot (synthetic stroke only thickens).
     */
    fun minWeight(variable: Boolean, system: Boolean): Int = if (variable || system) 100 else REGULAR

    /**
     * Body weight actually drawn for a requested [weight]. Below [minWeight] a static font already looks exactly
     * like 400, but bold runs (`base + 300`) would fall short of the bold file / stroke and lose their emphasis;
     * clamping keeps "가늘게" on such a font a pure no-op.
     */
    fun effectiveBase(weight: Int, minWeight: Int): Int = weight.coerceIn(minWeight.coerceIn(100, 900), 900)

    /** Weight used for a run: bold adds 300, capped at 900. */
    fun runWeight(base: Int, bold: Boolean): Int {
        val b = base.coerceIn(100, 900)
        return if (bold) minOf(900, b + 300) else b
    }

    /**
     * The system fallback chain behind every font file's own glyphs (Hanja the font lacks, symbols, other scripts): the
     * default one, which `Typeface.Builder` puts behind a face unless told otherwise, for 명조 / 바탕 faces too. That is
     * MaruViewer's look on the user's S25 (its 나눔명조 page draws 聖 / 俗 in the system's gothic, 2026-10-05
     * screenshot), and those strokes hold up in the Comet's fast black-and-white modes, where a thin serif CJK breaks
     * up. The serif chain (Noto Serif CJK where the device has it) would be `"serif"`: one line, if a Myeongjo-style
     * Hanja is ever wanted. Measuring is the same as before the explicit chain, so cached page counts of fonts without
     * blank glyphs stay valid.
     */
    const val SYSTEM_FALLBACK = "sans-serif"

    /**
     * The page-count key's part for a font's blank-glyph repairs: "" when no file of it loads repaired, else the repair
     * rules' [version] and [files] ("r" regular, "b" bold, in that order) that do.
     */
    fun repairTag(files: String, version: Int): String = if (files.isEmpty()) "" else "|hg$version:$files"

    /** Static fonts switch to their bold file from 600 up. */
    fun usesBoldFile(weight: Int, hasBoldFile: Boolean): Boolean = hasBoldFile && weight >= 600

    /**
     * Synthetic emboldening stroke (px) for a static font: `max(0, (w - 400) / 100) * 0.012 * size` where
     * `w = weight - 300` when the bold file is used. 900 on a regular-only font ≈ 6% of the size.
     */
    fun syntheticStroke(weight: Int, boldFileUsed: Boolean, textSizePx: Float): Float {
        if (!(textSizePx > 0f) || textSizePx.isInfinite()) return 0f
        val w = weight.coerceIn(100, 900) - if (boldFileUsed) BOLD_FILE_WEIGHT_OFFSET else 0
        val steps = (w - 400) / 100f
        return if (steps > 0f) steps * STROKE_PER_100 * textSizePx else 0f
    }
}

/** Bitmap sampling arithmetic (unit-tested). */
internal object ImageMath {
    /** Largest power of two `s` such that `w / s >= targetW` and `h / s >= targetH` (at least 1). */
    fun sampleSize(w: Int, h: Int, targetW: Int, targetH: Int): Int {
        if (w <= 0 || h <= 0 || targetW <= 0 || targetH <= 0) return 1
        var s = 1
        while (s < (1 shl 16) && w / (s * 2) >= targetW && h / (s * 2) >= targetH) s *= 2
        return s
    }

    /** Largest power of two not above `1 / scale` (decode ≥ the needed resolution). */
    fun sampleForScale(scale: Float): Int {
        if (!(scale > 0f) || scale >= 1f) return 1
        var s = 1
        while (s < (1 shl 16) && s * 2 * scale <= 1f) s *= 2
        return s
    }

    /**
     * Size fitting (w, h) into (maxW, maxH) keeping the aspect ratio, never upscaling. Packed as
     * `(width shl 32) or height`.
     */
    fun fitNoUpscale(w: Int, h: Int, maxW: Int, maxH: Int): Long {
        if (w <= 0 || h <= 0 || maxW <= 0 || maxH <= 0) return 0L
        val scale = minOf(1f, minOf(maxW.toFloat() / w, maxH.toFloat() / h))
        val fw = Math.round(w * scale).coerceIn(1, maxOf(1, maxW))
        val fh = Math.round(h * scale).coerceIn(1, maxOf(1, maxH))
        return (fw.toLong() shl 32) or fh.toLong()
    }

    fun packedW(p: Long): Int = (p ushr 32).toInt()
    fun packedH(p: Long): Int = (p and 0xFFFFFFFFL).toInt()

    /** Cover placement: centre-crop unless the aspect ratios differ by more than ~33% (then fit). */
    fun cropCover(iw: Int, ih: Int, w: Int, h: Int): Boolean {
        if (iw <= 0 || ih <= 0 || w <= 0 || h <= 0) return false
        val ratio = (iw.toFloat() / ih) / (w.toFloat() / h)
        return ratio in 0.75f..1.3333f
    }
}
