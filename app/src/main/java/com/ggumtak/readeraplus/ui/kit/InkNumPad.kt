package com.ggumtak.readeraplus.ui.kit

import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/*
 * Numeric entry without the system keyboard (T1-1): the IME slides in with an animation and resizes the dialog window
 * (several full-screen e-ink updates); the pad changes one small label per digit and the dialog never moves.
 * Text input (Hangul) keeps the system IME.
 */

/**
 * Pure state of an [InkNumPad]: the digits typed so far (unit-tested). [signed] allows a minus ([minus]) and [decimals]
 * digits after a point ([point]), for setting values ("−4", "17.5"); without them it is the page pad it always was.
 */
class NumPadState(maxLength: Int = 5, val signed: Boolean = false, val decimals: Int = 0) {
    /** Most digits accepted (1..[MAX_LENGTH]); lowering it drops the extra digits. */
    var maxLength: Int = clampLength(maxLength)
        set(v) {
            field = clampLength(v)
            if (text.length > field) text = text.substring(0, field)
        }

    /** The digits (and a point), without leading zeros ("0" alone is kept, "0." before decimals). */
    var text: String = ""
        private set

    /** A minus was typed ([signed] only). */
    var negative: Boolean = false
        private set

    val isEmpty: Boolean get() = text.isEmpty() && !negative

    /** What the pad shows: the text with its minus ("−4"). */
    val shown: String get() = if (negative) "−$text" else text

    /** The typed whole number, or null when nothing was typed (the page pad). */
    val value: Int? get() = text.toIntOrNull()?.let { if (negative) -it else it }

    /** The typed number with its sign and decimals, or null when no digit was typed. */
    val number: Double?
        get() {
            val t = text.trimEnd('.')
            if (t.isEmpty()) return null
            val v = t.toDoubleOrNull() ?: return null
            return if (negative) -v else v
        }

    /**
     * Appends digit [d]. False (nothing changes) when [d] is not 0..9, the field is full ([maxLength] digits, or
     * [decimals] after the point) or it would be a leading 0.
     */
    fun digit(d: Int): Boolean {
        if (d !in 0..9) return false
        if (text == "0") {
            if (d == 0) return false
            text = d.toString()
            return true
        }
        val dot = text.indexOf('.')
        if (dot >= 0) {
            if (text.length - dot - 1 >= decimals) return false
        } else if (text.length >= maxLength) {
            return false
        }
        text += ('0' + d)
        return true
    }

    /** Starts the decimals ("0." when nothing was typed); false without [decimals] or with a point already. */
    fun point(): Boolean {
        if (decimals <= 0 || text.contains('.')) return false
        text = if (text.isEmpty()) "0." else "$text."
        return true
    }

    /** Turns the minus on or off; false when not [signed]. */
    fun minus(): Boolean {
        if (!signed) return false
        negative = !negative
        return true
    }

    /** Removes the last digit (or point), then the minus; false when there is nothing. */
    fun backspace(): Boolean {
        if (text.isEmpty()) {
            if (!negative) return false
            negative = false
            return true
        }
        text = text.substring(0, text.length - 1)
        return true
    }

    /** Removes everything typed; false when there was nothing. */
    fun clear(): Boolean {
        if (isEmpty) return false
        text = ""
        negative = false
        return true
    }

    /** The typed number clamped into [min]..[max] ("page 4000" of 3259 pages is the last page), or null when empty. */
    fun clamped(min: Int, max: Int): Int? = value?.coerceIn(min, maxOf(min, max))

    companion object {
        /** An Int never overflows. */
        const val MAX_LENGTH = 9

        /** Digits needed for numbers up to [max] (at least 1): the field's [maxLength]. */
        fun lengthFor(max: Int): Int = clampLength(max.coerceAtLeast(1).toString().length)

        private fun clampLength(n: Int): Int = n.coerceIn(1, MAX_LENGTH)
    }
}

/**
 * Number pad: a large number label, an optional hint line ("1–540", or a message after a wrong entry) and a 3×4 grid
 * of 56dp keys (1–9, ⌫, 0, 이동). Keys have no pressed state: the number changing is the feedback, one small e-ink
 * update per digit; the labels have fixed sizes, so typing never re-lays out the window. ⌫ held clears the field.
 * Hardware digit / Del / Enter keys work through [handleKey]. Main thread only.
 */
class InkNumPad(context: Context, maxLength: Int = 5, signed: Boolean = false, decimals: Int = 0) : LinearLayout(context) {
    val state = NumPadState(maxLength, signed, decimals)

    /** 이동 (or Enter) was pressed: the typed number, null when the field is empty. */
    var onEnter: ((Int?) -> Unit)? = null

    private var hint: CharSequence = ""
    private var error = false

    private val display = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 34f)
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Ink.BLACK)
        gravity = Gravity.CENTER
        includeFontPadding = false
        maxLines = 1
        background = context.borderBox(radiusDp = 3f)
    }

    private val hintLine = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setTextColor(Ink.GRAY)
        gravity = Gravity.CENTER
        includeFontPadding = false
        maxLines = 1
    }

    init {
        orientation = VERTICAL
        addView(display, LayoutParams(LayoutParams.MATCH_PARENT, context.dp(64)))
        addView(hintLine, LayoutParams(LayoutParams.MATCH_PARENT, context.dp(30)))
        // A value pad (sign or decimals) puts − and . beside the 0 and ⌫ with a wide 이동 on a row of their own.
        val extended = signed || decimals > 0
        val rows = if (!extended) {
            listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("⌫", "0", "이동"))
        } else {
            listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"),
                listOf(if (signed) "−" else "", "0", if (decimals > 0) "." else ""), listOf("⌫", "이동"))
        }
        for (keys in rows) {
            val row = LinearLayout(context).apply { orientation = HORIZONTAL }
            for (k in keys) row.addView(key(k), LayoutParams(0, context.dp(KEY_DP), if (k == "이동" && extended) 2f else 1f).apply {
                val m = context.dp(3)
                setMargins(m, m, m, m)
            })
            addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
    }

    private fun key(text: String): TextView = TextView(context).apply {
        this.text = text
        gravity = Gravity.CENTER
        includeFontPadding = false
        isClickable = true
        when (text) {
            "이동" -> {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Ink.WHITE)
                background = context.borderBox(fill = Ink.BLACK, radiusDp = 3f)
                setOnClickListener { enter() }
            }
            "⌫" -> {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                setTextColor(Ink.BLACK)
                background = context.borderBox(radiusDp = 3f)
                contentDescription = "지우기"
                setOnClickListener { edit(state.backspace()) }
                setOnLongClickListener { edit(state.clear()); true }
            }
            "" -> isClickable = false
            "−", "." -> {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
                setTextColor(Ink.BLACK)
                background = context.borderBox(radiusDp = 3f)
                contentDescription = if (text == "−") "빼기 부호" else "소수점"
                setOnClickListener { edit(if (text == "−") state.minus() else state.point()) }
            }
            else -> {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
                setTextColor(Ink.BLACK)
                background = context.borderBox(radiusDp = 3f)
                val d = text[0] - '0'
                setOnClickListener { edit(state.digit(d)) }
            }
        }
    }

    /** The gray line under the number ("1–540"); shown again after a message once a key is pressed. */
    fun setHint(text: CharSequence) {
        hint = text
        if (!error) hintLine.text = text
    }

    /** A message in place of the hint (a wrong entry), black, until the next key. */
    fun showError(text: CharSequence) {
        error = true
        hintLine.setTextColor(Ink.BLACK)
        hintLine.text = text
    }

    /** Empties the field and sets a new [maxLength] and [hint] (the go-to dialog switching segments). */
    fun reset(maxLength: Int, hint: CharSequence) {
        state.clear()
        state.maxLength = maxLength
        display.text = state.shown
        clearError()
        setHint(hint)
    }

    /** Hardware keys: digits, minus, point, Del, Enter. True when the key is the pad's (its DOWN and UP are both consumed). */
    fun handleKey(keyCode: Int, ev: KeyEvent): Boolean {
        val d = when (keyCode) {
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> keyCode - KeyEvent.KEYCODE_0
            in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> keyCode - KeyEvent.KEYCODE_NUMPAD_0
            else -> -1
        }
        val isEnter = keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
        val isMinus = state.signed && (keyCode == KeyEvent.KEYCODE_MINUS || keyCode == KeyEvent.KEYCODE_NUMPAD_SUBTRACT)
        val isPoint = state.decimals > 0 && (keyCode == KeyEvent.KEYCODE_PERIOD || keyCode == KeyEvent.KEYCODE_NUMPAD_DOT)
        if (d < 0 && !isEnter && !isMinus && !isPoint && keyCode != KeyEvent.KEYCODE_DEL) return false
        if (ev.action != KeyEvent.ACTION_DOWN) return true
        when {
            d >= 0 -> edit(state.digit(d))
            isEnter -> if (ev.repeatCount == 0) enter()
            isMinus -> edit(state.minus())
            isPoint -> edit(state.point())
            else -> edit(state.backspace())
        }
        return true
    }

    private fun edit(changed: Boolean) {
        clearError()
        if (changed) display.text = state.shown
    }

    private fun clearError() {
        if (!error) return
        error = false
        hintLine.setTextColor(Ink.GRAY)
        hintLine.text = hint
    }

    private fun enter() {
        onEnter?.invoke(state.value)
    }

    companion object {
        const val KEY_DP = 56

        /**
         * A number dialog: [title], the pad with [hint] ("1–540") and 취소. [onEnter] gets the typed number and returns
         * null to close the dialog, or a message shown in the hint line with the dialog staying open ("541화가
         * 없습니다"). An empty field asks for a number. No window animation, no keyboard.
         */
        fun show(context: Context, title: String, hint: CharSequence, maxLength: Int, onEnter: (Int) -> CharSequence?): AlertDialog {
            val pad = InkNumPad(context, maxLength)
            return dialog(context, title, hint, pad) { onEnter(pad.state.value!!) }
        }

        /**
         * A setting's value: like [show], with a minus key when [signed] and a point for [decimals] digits after it
         * ("17.5"). [onEnter] gets the typed number; null closes the dialog, a message keeps it open.
         */
        fun showValue(
            context: Context, title: String, hint: CharSequence, maxLength: Int, signed: Boolean, decimals: Int,
            onEnter: (Double) -> CharSequence?,
        ): AlertDialog {
            val pad = InkNumPad(context, maxLength, signed, decimals)
            return dialog(context, title, hint, pad) { onEnter(pad.state.number!!) }
        }

        private fun dialog(context: Context, title: String, hint: CharSequence, pad: InkNumPad, enter: () -> CharSequence?): AlertDialog {
            pad.setHint(hint)
            val box = FrameLayout(context).apply {
                setPadding(context.dp(20), context.dp(8), context.dp(20), 0)
                addView(pad, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
            }
            // Scrolls only on a screen too short for it (landscape); never while typing: the window keeps its size.
            val scroll = ScrollView(context).apply {
                overScrollMode = View.OVER_SCROLL_NEVER
                isVerticalFadingEdgeEnabled = false
                addView(box, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
            }
            val dialog = context.alert().setTitle(title).setView(scroll).setNegativeButton("취소", null).create()
            pad.onEnter = {
                if (pad.state.number == null) {
                    pad.showError("숫자를 입력하세요")
                } else {
                    val msg = enter()
                    if (msg == null) dialog.dismiss() else pad.showError(msg)
                }
            }
            dialog.setOnKeyListener { _, keyCode, ev -> pad.handleKey(keyCode, ev) }
            dialog.window?.setWindowAnimations(0)
            dialog.show()
            return dialog
        }
    }
}
