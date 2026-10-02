package com.ggumtak.readeraplus.render

/** Persistent palette ids: unknown future ids are stored, and displayed as yellow. */
object QuoteStyles {
    const val YELLOW = 0; const val GREEN = 1; const val BLUE = 2; const val RED = 3; const val PURPLE = 4
    const val UNDERLINE = 5; const val COUNT = 6; const val MAX_STORED = 15
    const val LINE_NONE = 0; const val LINE_THIN = 1; const val LINE_THICK = 2; const val LINE_DASHED = 3; const val LINE_BOX = 4
    private val labels = arrayOf("노랑", "초록", "파랑", "빨강", "보라", "밑줄")
    private val day = intArrayOf(0xFFFFE37A.toInt(), 0xFFB9E4A2.toInt(), 0xFFAFD3F5.toInt(), 0xFFFFB0AB.toInt(), 0xFFD9C2F2.toInt(), 0)
    private val night = intArrayOf(0xFF5E4F00.toInt(), 0xFF24502A.toInt(), 0xFF1D4570.toInt(), 0xFF6E2626.toInt(), 0xFF4D3470.toInt(), 0)
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
}
