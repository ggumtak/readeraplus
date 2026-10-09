package com.ggumtak.readeraplus.ui.kit

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
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
 * - [singleLine] = true (search boxes, a page number: typing goes at the end): no blinking caret. An [InkEditText]
 *   draws a still one instead while it has the focus, where the user tapped (the user asked to see it, 2026-10-04);
 *   the platform caret shows only during a long press and the 붙여넣기 / selection menu it opens (the platform offers
 *   that menu only while its caret is visible).
 * - false (multi-line fields, and single-line fields that open with text to edit, such as a book's title): the caret
 *   shows after a touch inside the field (the user is placing it; a tap moves it there, visibly).
 * Either way it hides again when the field loses focus or, on API 30+, when the keyboard is hidden.
 */
@SuppressLint("ClickableViewAccessibility")
fun EditText.inkCursor(singleLine: Boolean) {
    isCursorVisible = false
    if (singleLine) {
        (this as? InkEditText)?.stillCaret = true
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

/**
 * EditText with a still caret ([inkCursor] singleLine = true turns it on): a thin line in the text colour at the
 * cursor while the field has the focus, drawn here and never blinking, so the user sees where a tap put the cursor
 * without an e-ink update twice a second. A tap moves it (the platform moves the selection even with its own caret
 * hidden); typing, deleting and the field's horizontal scroll carry it along. Not drawn while there is a selection or
 * while the platform caret is shown (a long press and its menu).
 */
open class InkEditText(context: Context) : EditText(context) {
    internal var stillCaret = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }
    private val caretPaint = Paint()
    private val lineBox = Rect()

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        if (stillCaret) invalidate()
    }

    override fun onFocusChanged(focused: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(focused, direction, previouslyFocusedRect)
        if (stillCaret) invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!stillCaret || !isFocused || isCursorVisible) return
        val l = layout ?: return
        val at = selectionStart
        if (at < 0 || at != selectionEnd || at > l.text.length) return
        // A keyboard's composing text can leave the layout a step behind the selection: the caret is decoration,
        // never worth a crash.
        try {
            // Content coordinates, as TextView draws its text: the layout sits at the compound padding, the line box
            // (with the vertical gravity offset) comes from getLineBounds, the scroll is already on the canvas.
            val baseline = getLineBounds(l.getLineForOffset(at), lineBox)
            val w = maxOf(2f, resources.displayMetrics.density * CARET_DP)
            val x = (compoundPaddingLeft + l.getPrimaryHorizontal(at)).coerceAtMost(scrollX + width - w)
            caretPaint.color = currentTextColor
            canvas.drawRect(x, baseline + paint.ascent(), x + w, baseline + paint.descent(), caretPaint)
        } catch (_: RuntimeException) {
        }
    }

    private companion object {
        const val CARET_DP = 1.5f
    }
}
