package com.ggumtak.readeraplus.ui.kit

import android.annotation.SuppressLint
import android.os.Build
import android.view.MotionEvent
import android.view.WindowInsets
import android.widget.EditText

/**
 * E-ink friendly caret. A blinking cursor redraws its line about twice a second, and on e-ink every blink is a panel
 * update (flicker, ghosting, battery). Single-line fields never show a caret (the text end is where typing goes).
 * Multi-line fields show it only after a touch inside the field (the user is placing it), and hide it again when the
 * field loses focus or, on API 30+, when the keyboard is hidden.
 */
@SuppressLint("ClickableViewAccessibility")
fun EditText.inkCursor(singleLine: Boolean) {
    isCursorVisible = false
    if (singleLine) return
    setOnTouchListener { _, ev ->
        if (ev.actionMasked == MotionEvent.ACTION_DOWN && !isCursorVisible) isCursorVisible = true
        false
    }
    setOnFocusChangeListener { _, hasFocus -> if (!hasFocus && isCursorVisible) isCursorVisible = false }
    if (Build.VERSION.SDK_INT >= 30) {
        setOnApplyWindowInsetsListener { v, insets ->
            if (isCursorVisible && !insets.isVisible(WindowInsets.Type.ime())) isCursorVisible = false
            v.onApplyWindowInsets(insets)
        }
    }
}
