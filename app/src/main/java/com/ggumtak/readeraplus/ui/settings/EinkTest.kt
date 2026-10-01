package com.ggumtak.readeraplus.ui.settings

import android.app.Dialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import android.widget.TextView
import com.ggumtak.readeraplus.R
import com.ggumtak.readeraplus.render.Eink
import com.ggumtak.readeraplus.ui.kit.Ink
import com.ggumtak.readeraplus.ui.kit.dp
import com.ggumtak.readeraplus.ui.kit.fullScreenDialog
import com.ggumtak.readeraplus.ui.kit.hairline
import com.ggumtak.readeraplus.ui.kit.label
import com.ggumtak.readeraplus.ui.kit.lp
import com.ggumtak.readeraplus.ui.kit.toolbar
import com.ggumtak.readeraplus.ui.kit.vertical

/**
 * "새로고침 시험" (T1-3a): a full-screen test of one refresh method. 16 px black and white stripes stay up for 1 s,
 * then a text sample replaces them (the stripes' ghost stays under it on e-ink), then the chosen method refreshes
 * the whole window ([Eink.fullRefresh] with that method, no chain), then the page asks "잔상이 깨끗이 지워졌나요?":
 * [예, 이 방식으로] keeps the method ([onChosen]), [다른 방식 시험] runs the next method this device offers. The
 * timers run only while the test runs, and the flash is the one the user asked for (rule 5).
 */
internal class EinkTest(
    private val context: Context,
    private var method: Int,
    private val flashMs: Int,
    private val hasXrz: Boolean,
    private val onChosen: (Int) -> Unit,
) {
    private lateinit var dialog: Dialog
    private lateinit var stage: FrameLayout
    private lateinit var stripes: View
    private lateinit var sample: TextView
    private lateinit var status: TextView
    private lateinit var hint: TextView
    private lateinit var buttons: View
    /** Bumped by every run and by dismissal: a pending step of an older run does nothing. */
    private var gen = 0

    fun show(): Dialog {
        val root = context.vertical { setBackgroundColor(Ink.WHITE) }
        root.addView(context.toolbar("새로고침 시험", R.drawable.ic_arrow_back, onNav = { dialog.dismiss() }), lp())
        stage = FrameLayout(context).apply { setBackgroundColor(Ink.WHITE) }
        stripes = StripesView(context)
        stage.addView(stripes, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        sample = context.label(SAMPLE, 20f).apply {
            setPadding(context.dp(24), context.dp(24), context.dp(24), context.dp(24))
            setLineSpacing(0f, 1.5f)
            visibility = View.GONE
        }
        stage.addView(sample, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.addView(stage, lp(MATCH_PARENT, 0, 1f))
        root.addView(context.hairline())
        val panel = context.vertical { setPadding(0, context.dp(12), 0, context.dp(12)) }
        status = context.label("", 18f, bold = true).apply { setPadding(context.dp(16), 0, context.dp(16), 0) }
        panel.addView(status)
        hint = context.note("")
        panel.addView(hint)
        buttons = context.buttonBar(
            context.textButton("예, 이 방식으로") {
                onChosen(method)
                dialog.dismiss()
            },
            context.textButton("다른 방식 시험") {
                method = EinkChoices.next(method, hasXrz)
                run()
            },
        )
        panel.addView(buttons)
        root.addView(panel, lp())
        dialog = context.fullScreenDialog(root)
        dialog.setOnDismissListener { gen++ }
        dialog.show()
        run()
        return dialog
    }

    /** One test of [method]: stripes, then the sample, then the refresh, then the question. */
    private fun run() {
        val g = ++gen
        status.text = "시험 중: ${EinkChoices.method(method)}"
        hint.text = "줄무늬를 1초 동안 보여 준 뒤 글자로 바꾸고, 고른 방식으로 화면 전체를 새로고침합니다."
        // INVISIBLE, not GONE: the panel keeps its height, so the stage never moves between the steps.
        buttons.visibility = View.INVISIBLE
        sample.visibility = View.GONE
        stripes.visibility = View.VISIBLE
        stage.postDelayed({
            if (g != gen) return@postDelayed
            stripes.visibility = View.GONE
            sample.visibility = View.VISIBLE
            stage.postDelayed({
                if (g != gen) return@postDelayed
                val target = dialog.window?.decorView ?: stage
                Eink.fullRefresh(target, method, flashMs)
                stage.postDelayed({ if (g == gen) ask() }, flashMs + SETTLE_MS)
            }, TEXT_MS)
        }, STRIPES_MS)
    }

    private fun ask() {
        status.text = "잔상이 깨끗이 지워졌나요?"
        hint.text = if (EinkChoices.isDeviceMethod(method) && Eink.lastRefreshFellBack) {
            "기기 방식을 쓸 수 없어 화면 깜빡임으로 대신했습니다. 다른 방식을 시험해 보세요."
        } else {
            "시험한 방식: ${EinkChoices.method(method)}. 줄무늬 자국이 남았다면 다른 방식을 시험해 보세요."
        }
        buttons.visibility = View.VISIBLE
    }

    /** 16 px black and white horizontal stripes (drawn once; no animation). */
    private class StripesView(context: Context) : View(context) {
        private val black = Paint().apply { color = Ink.BLACK; style = Paint.Style.FILL }

        override fun onDraw(canvas: Canvas) {
            var y = 0
            while (y < height) {
                canvas.drawRect(0f, y.toFloat(), width.toFloat(), (y + STRIPE_PX).toFloat(), black)
                y += 2 * STRIPE_PX
            }
        }
    }

    private companion object {
        const val STRIPE_PX = 16
        const val STRIPES_MS = 1000L
        /** The sample stays up ghosted this long before the refresh, so the difference is visible. */
        const val TEXT_MS = 700L
        /** After the refresh call: two frames plus the panel's own update. */
        const val SETTLE_MS = 700L
        const val SAMPLE = "창밖에는 가을비가 조용히 내리고 있었다. 그는 오래된 책을 펼쳐 첫 문장을 천천히 소리 내어 읽었다.\n\n" +
            "글자 사이에 줄무늬 자국이 보이면 잔상이 남은 것입니다."
    }
}
