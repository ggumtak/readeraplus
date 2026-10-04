package com.ggumtak.readeraplus.reader

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.ggumtak.readeraplus.R
import com.ggumtak.readeraplus.ui.kit.Ink
import com.ggumtak.readeraplus.ui.kit.borderBox
import com.ggumtak.readeraplus.ui.kit.dp
import com.ggumtak.readeraplus.ui.kit.hairline
import com.ggumtak.readeraplus.ui.kit.horizontal
import com.ggumtak.readeraplus.ui.kit.icon
import com.ggumtak.readeraplus.ui.kit.label
import com.ggumtak.readeraplus.ui.kit.lp
import com.ggumtak.readeraplus.ui.kit.vertical
import java.io.File

/** What the end panel shows, loaded on IO when it opens (the book's row, NextPart). */
internal class EndInfo(
    val title: String,
    /** Total reading time of the book (Library), the page just closed included. */
    val readingSeconds: Long,
    /** The next part of a work split into files, or null (the row is left out). */
    val nextPart: File?,
    /** The book is marked 다 읽음. */
    val finished: Boolean,
)

/**
 * The end-of-book panel (T1-2): "다 읽었습니다", the title, the reading time, [다음 권 읽기 ›] with the next file's name
 * (only when one was found), the 다 읽음 check box, and [서재로] [처음부터] [리뷰 쓰기]. A white box with a 1px border over
 * the page, no animation, filled before it is shown: one e-ink update. A tap anywhere but on a button closes it (the
 * reader stays on the last page), like BACK or "previous". Built when a book first ends in the reader, then reused.
 * Main thread only.
 */
internal class EndPanel(private val ctx: Context, private val actions: Actions) {

    interface Actions {
        fun onEndNextPart(file: File)
        /** The 완독 toggle: [finished] is the new state. */
        fun onEndFinished(finished: Boolean)
        fun onEndLibrary()
        fun onEndRestart()
        fun onEndReview()
        fun onEndClose()
    }

    /** Full-size touch catcher: the page under it gets no taps while the panel shows. */
    private val cover: FrameLayout
    private val box: LinearLayout
    private val title: TextView
    private val time: TextView
    private val nextBlock: LinearLayout
    private val nextName: TextView
    private val finishedIcon: ImageView
    private var nextFile: File? = null
    private var finished = false

    init {
        cover = FrameLayout(ctx).apply {
            visibility = View.GONE
            isClickable = true
            setOnClickListener { actions.onEndClose() }
        }
        box = ctx.vertical {
            background = ctx.borderBox()
            setPadding(ctx.dp(20), ctx.dp(20), ctx.dp(20), ctx.dp(16))
        }
        box.addView(ctx.label("다 읽었습니다", 20f, bold = true), lp())
        title = ctx.label("", 16f, maxLines = 2).apply { setPadding(0, ctx.dp(8), 0, 0) }
        box.addView(title, lp())
        time = ctx.label("", 14f, color = Ink.GRAY).apply { setPadding(0, ctx.dp(6), 0, ctx.dp(14)) }
        box.addView(time, lp())
        box.addView(ctx.hairline())

        nextBlock = ctx.vertical { setPadding(0, ctx.dp(14), 0, 0) }
        nextBlock.addView(button("다음 권 읽기 ›") { nextFile?.let { actions.onEndNextPart(it) } }, lp())
        nextName = ctx.label("", 14f, color = Ink.GRAY, maxLines = 2).apply { setPadding(0, ctx.dp(6), 0, 0) }
        nextBlock.addView(nextName, lp())
        box.addView(nextBlock, lp())

        val finishedRow = ctx.horizontal {
            minimumHeight = ctx.dp(48)
            isClickable = true
            setOnClickListener {
                setFinished(!finished)
                actions.onEndFinished(finished)
            }
            // The label stays the same: the box shows the state, and TalkBack reads it as a check box.
            accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.isCheckable = true
                    info.isChecked = finished
                }
            }
        }
        finishedIcon = ctx.icon(R.drawable.ic_check_box_outline_blank, 24)
        finishedRow.addView(finishedIcon)
        finishedRow.addView(ctx.label(FINISHED_LABEL, 16f).apply { setPadding(ctx.dp(10), 0, 0, 0) }, lp(0, WRAP_CONTENT, 1f))
        box.addView(finishedRow, lp().apply { topMargin = ctx.dp(8) })

        val buttons = ctx.horizontal { setPadding(0, ctx.dp(8), 0, 0) }
        buttons.addView(button("서재로") { actions.onEndLibrary() }, lp(0, WRAP_CONTENT, 1f))
        buttons.addView(button("처음부터") { actions.onEndRestart() }, lp(0, WRAP_CONTENT, 1f).apply {
            leftMargin = ctx.dp(8)
            rightMargin = ctx.dp(8)
        })
        buttons.addView(button("리뷰 쓰기") { actions.onEndReview() }, lp(0, WRAP_CONTENT, 1f))
        box.addView(buttons, lp())
        cover.addView(box, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.CENTER))
    }

    private fun button(text: String, onClick: () -> Unit): TextView = ctx.label(text, 16f, bold = true, maxLines = 1).apply {
        setAutoSizeTextTypeUniformWithConfiguration(9, 16, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
        gravity = Gravity.CENTER
        minHeight = ctx.dp(48)
        setPadding(ctx.dp(8), 0, ctx.dp(8), 0)
        background = ctx.borderBox()
        // Reachable with a remote's arrow keys (the reader passes them on while the panel shows).
        isFocusable = true
        setOnClickListener { onClick() }
    }

    fun attach(root: FrameLayout) {
        root.addView(cover, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }

    fun owns(v: View): Boolean = v === cover

    val isShowing: Boolean get() = cover.visibility == View.VISIBLE

    /** Fills the panel with [info] and shows it, [widthPx] wide at most (all set before the one draw). */
    fun show(info: EndInfo, widthPx: Int) {
        title.text = info.title
        time.text = ReaderFormat.readTime(info.readingSeconds)
        nextFile = info.nextPart
        nextBlock.visibility = if (info.nextPart != null) View.VISIBLE else View.GONE
        nextName.text = info.nextPart?.name ?: ""
        setFinished(info.finished)
        fit(widthPx)
        cover.bringToFront()
        cover.visibility = View.VISIBLE
    }

    /** Box width for a window [widthPx] wide: 16dp from the sides, 420dp at most (again after a rotation). */
    fun fit(widthPx: Int) {
        if (widthPx <= 0) return
        val lp = box.layoutParams as FrameLayout.LayoutParams
        val w = minOf(widthPx - 2 * ctx.dp(16), ctx.dp(420)).coerceAtLeast(ctx.dp(200))
        if (lp.width != w) {
            lp.width = w
            box.layoutParams = lp
        }
    }

    fun hide() {
        if (cover.visibility != View.GONE) cover.visibility = View.GONE
        nextFile = null
    }

    private fun setFinished(on: Boolean) {
        finished = on
        finishedIcon.setImageResource(if (on) R.drawable.ic_check_box else R.drawable.ic_check_box_outline_blank)
    }

    companion object {
        /** The 다 읽음 check box's label, the same in both states (the box shows which). */
        const val FINISHED_LABEL = "다 읽은 책으로 표시"
    }
}
