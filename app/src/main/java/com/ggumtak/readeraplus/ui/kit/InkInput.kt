package com.ggumtak.readeraplus.ui.kit

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.os.Build
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.widget.EditText

/**
 * E-ink friendly caret. A blinking cursor redraws its line about twice a second, and on e-ink every blink is a panel
 * update (flicker, ghosting, battery).
 * - [singleLine] = true (search boxes, a page number: typing goes at the end): no caret, except during a long press
 *   and the 붙여넣기 / selection menu it opens (the platform offers that menu only while the caret is visible).
 * - false (multi-line fields, and single-line fields that open with text to edit, such as a book's title): the caret
 *   shows after a touch inside the field (the user is placing it; a tap moves it there, visibly).
 * Either way it hides again when the field loses focus or, on API 30+, when the keyboard is hidden.
 */
@SuppressLint("ClickableViewAccessibility")
fun EditText.inkCursor(singleLine: Boolean) {
    isCursorVisible = false
    if (singleLine) {
        setOnLongClickListener {
            if (!isCursorVisible) isCursorVisible = true
            false // the field's own long press (insertion or selection menu) runs next
        }
        val hideAfterMenu = object : ActionMode.Callback {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean = true
            override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = false
            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean = false
            override fun onDestroyActionMode(mode: ActionMode) {
                if (isCursorVisible) isCursorVisible = false
            }
        }
        customInsertionActionModeCallback = hideAfterMenu
        customSelectionActionModeCallback = hideAfterMenu
    } else {
        setOnTouchListener { _, ev ->
            if (ev.actionMasked == MotionEvent.ACTION_DOWN && !isCursorVisible) isCursorVisible = true
            false
        }
    }
    setOnFocusChangeListener { _, hasFocus -> if (!hasFocus && isCursorVisible) isCursorVisible = false }
    if (Build.VERSION.SDK_INT >= 30) hideCaretWithKeyboard()
}

/**
 * Hides the caret when the keyboard goes from shown to hidden (Back closes it, the field keeps its focus). Read from
 * the root insets on each layout of the window (an IME change relayouts it): a listener for this view's own insets
 * is never called in a window that fits the system bars, a dialog's included, as the decor consumes them first.
 */
@TargetApi(Build.VERSION_CODES.R)
private fun EditText.hideCaretWithKeyboard() {
    var imeShown = false
    val onLayout = ViewTreeObserver.OnGlobalLayoutListener {
        val ime = rootWindowInsets?.isVisible(WindowInsets.Type.ime()) ?: return@OnGlobalLayoutListener
        if (imeShown && !ime && isCursorVisible) isCursorVisible = false
        imeShown = ime
    }
    addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) {
            v.viewTreeObserver.addOnGlobalLayoutListener(onLayout)
        }

        override fun onViewDetachedFromWindow(v: View) {
            v.viewTreeObserver.removeOnGlobalLayoutListener(onLayout)
        }
    })
    if (isAttachedToWindow) viewTreeObserver.addOnGlobalLayoutListener(onLayout)
}
