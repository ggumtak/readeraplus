package com.ggumtak.readeraplus.render

/** Persistent palette ids: unknown future ids are stored, and displayed as yellow. */
object QuoteStyles {
    const val YELLOW = 0; const val GREEN = 1; const val BLUE = 2; const val RED = 3; const val PURPLE = 4
    const val UNDERLINE = 5; const val COUNT = 6; const val MAX_STORED = 15
    const val LINE_NONE = 0; const val LINE_THIN = 1; const val LINE_THICK = 2; const val LINE_DASHED = 3; const val LINE_BOX = 4

    /** RIDI's red for 밑줄 on colour screens: its swatch's line and the underline the page draws (e-ink keeps the ink line). */
    const val UNDERLINE_ACCENT = 0xFFE5453B.toInt()

    private val labels = arrayOf("노랑", "초록", "파랑", "빨강", "보라", "밑줄")

    /** RIDI's highlighter colours (measured on its 1080 × 2340 screenshots): yellow, green, blue, pink, purple. */
    private val day = intArrayOf(0xFFE7D87C.toInt(), 0xFFBED370.toInt(), 0xFF7CB9C8.toInt(), 0xFFD6959C.toInt(), 0xFFBE93CC.toInt(), 0)

    /** The same hues muted for dark pages: dark enough for the page's light text, still told apart. */
    private val night = intArrayOf(0xFF524C28.toInt(), 0xFF3D4E2A.toInt(), 0xFF2B4E57.toInt(), 0xFF603438.toInt(), 0xFF573762.toInt(), 0)

    /** The order the palettes show the styles in (RIDI's): yellow, green, purple, blue, pink, then the underline. */
    private val order = intArrayOf(YELLOW, GREEN, PURPLE, BLUE, RED, UNDERLINE)
    private val grey = intArrayOf(0xDD, 0xEE, 0xCC, 0xBB, -1, -1)
    private val lines = intArrayOf(LINE_THIN, LINE_DASHED, LINE_NONE, LINE_THICK, LINE_BOX, LINE_THICK)
    fun of(stored: Int): Int = if (stored in 0 until COUNT) stored else YELLOW
    fun label(style: Int): String = labels[of(style)]
    fun tag(style: Int): String = "[" + label(style) + "]"
    fun colorFill(style: Int, night: Boolean): Int = (if (night) this.night else day)[of(style)]
    fun colorLine(style: Int): Int = if (of(style) == UNDERLINE) LINE_THICK else LINE_NONE
    fun inkGrey(style: Int): Int = grey[of(style)]
    fun inkLine(style: Int): Int = lines[of(style)]
    fun thumbGrey(style: Int): Int = inkGrey(style).let { if (it < 0) 0xCC else it }

    /** The style shown at [position] (0 until [COUNT]) of a palette row; out of range = the first. */
    fun paletteStyle(position: Int): Int = if (position in 0 until COUNT) order[position] else order[0]

    /** Where [style] sits in a palette row (the inverse of [paletteStyle]). */
    fun palettePosition(style: Int): Int {
        val s = of(style)
        for (i in 0 until COUNT) if (order[i] == s) return i
        return 0
    }
}

/**
 * The text selection's colours on a colour screen (RIDI's, measured on its screenshots): a light blue fill drawn under
 * the text and a stronger blue for the handles; a muted blue fill and a lighter handle on a dark page. E-ink screens
 * keep the grey fill and the page-coloured handles (the renderer and `HandleColors` decide by the device).
 */
object SelectionColors {
    private const val FILL = 0xFF99C9F0.toInt()
    private const val FILL_DARK = 0xFF2F5F8E.toInt()
    private const val HANDLE = 0xFF1F8CE6.toInt()
    private const val HANDLE_DARK = 0xFF4DA3EE.toInt()

    fun fill(dark: Boolean): Int = if (dark) FILL_DARK else FILL
    fun handle(dark: Boolean): Int = if (dark) HANDLE_DARK else HANDLE
}
