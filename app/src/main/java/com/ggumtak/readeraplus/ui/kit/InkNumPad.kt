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

/** Pure state of an [InkNumPad]: the digits typed so far (unit-tested). */
class NumPadState(maxLength: Int = 5) {
    /** Most digits accepted (1..[MAX_LENGTH]); lowering it drops the extra digits. */
    var maxLength: Int = clampLength(maxLength)
        set(v) {
            field = clampLength(v)
            if (text.length > field) text = text.substring(0, field)
        }

    /** The digits, without leading zeros ("0" alone is kept). */
    var text: String = ""
        private set

    val isEmpty: Boolean get() = text.isEmpty()

    /** The typed number, or null when nothing was typed. */
    val value: Int? get() = text.toIntOrNull()

    /** Appends digit [d]. False (nothing changes) when [d] is not 0..9, the field is full or it would be a leading 0. */
    fun digit(d: Int): Boolean {
        if (d !in 0..9) return false
        if (text == "0") {
            if (d == 0) return false
            text = d.toString()
            return true
        }
        if (text.length >= maxLength) return false
        text += ('0' + d)
        return true
    }

    /** Removes the last digit; false when there is none. */
    fun backspace(): Boolean {
        if (text.isEmpty()) return false
        text = text.substring(0, text.length - 1)
        return true
    }

    /** Removes every digit; false when there was none. */
    fun clear(): Boolean {
        if (text.isEmpty()) return false
        text = ""
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
class InkNumPad(context: Context, maxLength: Int = 5) : LinearLayout(context) {
    val state = NumPadState(maxLength)

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
        val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "⌫", "0", "이동")
        for (r in 0 until 4) {
            val row = LinearLayout(context).apply { orientation = HORIZONTAL }
            for (c in 0 until 3) row.addView(key(keys[r * 3 + c]), LayoutParams(0, context.dp(KEY_DP), 1f).apply {
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
        display.text = ""
        clearError()
        setHint(hint)
    }

    /** Hardware keys: digits, Del, Enter. True when the key is the pad's (its DOWN and UP are both consumed). */
    fun handleKey(keyCode: Int, ev: KeyEvent): Boolean {
        val d = when (keyCode) {
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> keyCode - KeyEvent.KEYCODE_0
            in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> keyCode - KeyEvent.KEYCODE_NUMPAD_0
            else -> -1
        }
        val isEnter = keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
        if (d < 0 && !isEnter && keyCode != KeyEvent.KEYCODE_DEL) return false
        if (ev.action != KeyEvent.ACTION_DOWN) return true
        when {
            d >= 0 -> edit(state.digit(d))
            isEnter -> if (ev.repeatCount == 0) enter()
            else -> edit(state.backspace())
        }
        return true
    }

    private fun edit(changed: Boolean) {
        clearError()
        if (changed) display.text = state.text
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
            pad.onEnter = { v ->
                if (v == null) {
                    pad.showError("숫자를 입력하세요")
                } else {
                    val msg = onEnter(v)
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
