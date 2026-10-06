package com.ggumtak.readeraplus.reader.extras

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.InsetDrawable
import android.net.Uri
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import com.ggumtak.readeraplus.R
import com.ggumtak.readeraplus.ui.kit.Ink
import com.ggumtak.readeraplus.ui.kit.borderBox
import com.ggumtak.readeraplus.ui.kit.dp
import com.ggumtak.readeraplus.ui.kit.horizontal
import com.ggumtak.readeraplus.ui.kit.hairline
import com.ggumtak.readeraplus.ui.kit.label
import com.ggumtak.readeraplus.ui.kit.lp
import com.ggumtak.readeraplus.ui.kit.pressableBackground
import com.ggumtak.readeraplus.ui.kit.showNoAnim
import com.ggumtak.readeraplus.ui.kit.toast
import com.ggumtak.readeraplus.ui.kit.vertical

/**
 * The floating lookup window (the "네이버 사전" pick of 사전·번역): a bottom sheet-like dialog over the page, ~60% of
 * the screen high, with a slim title bar (the query, "브라우저", "닫기") and a WebView. Dragging the title bar resizes
 * it (30–90%); the window changes once, when the finger lifts. No animation, no per-percent progress drawing.
 */
internal object LookupPanel {
    private const val SIDE_DP = 8
    private const val BAR_DP = 44

    /**
     * Shows [url] in the window titled [title]. When no WebView can be created (no WebView provider installed) the
     * address opens in the browser instead, with a message. True once something was shown or started.
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun show(activity: Activity, title: String, url: String): Boolean {
        if (activity.isFinishing || activity.isDestroyed) return false
        val web = try {
            WebView(activity)
        } catch (_: Throwable) {
            activity.toast("웹 창을 열 수 없어 브라우저로 엽니다")
            return openInBrowser(activity, url)
        }
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
        }
        web.setBackgroundColor(Ink.WHITE)
        web.overScrollMode = View.OVER_SCROLL_NEVER
        web.isVerticalFadingEdgeEnabled = false

        // Re-read on every use: the reader keeps running through a rotation.
        val screen = { activity.resources.displayMetrics.heightPixels }
        var height = LookupQuery.startHeight(screen())
        var destroyed = false
        // A WebView outlives its dialog unless it is taken apart: on dismiss, and when the window goes away with
        // its activity (no dismiss then).
        fun destroyWeb() {
            if (destroyed) return
            destroyed = true
            runCatching {
                web.stopLoading()
                web.loadUrl("about:blank")
                (web.parent as? ViewGroup)?.removeView(web)
                web.removeAllViews()
                web.destroy()
            }
        }

        lateinit var dialog: Dialog
        val line = View(activity).apply { setBackgroundColor(Ink.BLACK); visibility = View.INVISIBLE }
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val scheme = request.url.scheme?.lowercase()
                // http(s) stays in the window; intent://, market:// and the like are never followed.
                return scheme != "http" && scheme != "https"
            }

            override fun onPageStarted(view: WebView, u: String?, favicon: Bitmap?) {
                line.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView, u: String?) {
                line.visibility = View.INVISIBLE
            }
        }

        fun button(text: String, onClick: () -> Unit): TextView = activity.label(text, 15f, bold = true).apply {
            gravity = Gravity.CENTER
            minHeight = activity.dp(BAR_DP)
            setPadding(activity.dp(12), 0, activity.dp(12), 0)
            background = pressableBackground()
            setOnClickListener { onClick() }
        }

        val titleView = activity.label(title, 15f, bold = true, maxLines = 1)
        val bar = activity.horizontal {
            minimumHeight = activity.dp(BAR_DP)
            setPadding(activity.dp(12), 0, 0, 0)
            addView(titleView, lp(0, WRAP_CONTENT, 1f))
            addView(button("브라우저") { openInBrowser(activity, web.url ?: url) })
            addView(button("닫기") { dialog.dismiss() })
        }
        attachResize(bar, screen, { height }) { h ->
            height = h
            dialog.window?.setLayout(MATCH_PARENT, h)
        }

        val root = activity.vertical {
            addView(bar, lp(MATCH_PARENT, WRAP_CONTENT))
            addView(activity.hairline())
            addView(line, lp(MATCH_PARENT, activity.dp(2)))
            addView(web, lp(MATCH_PARENT, 0, 1f))
        }

        dialog = object : Dialog(activity, R.style.InkDialog) {
            @Deprecated("Deprecated in Java")
            @Suppress("OVERRIDE_DEPRECATION")
            override fun onBackPressed() {
                if (web.canGoBack()) web.goBack() else dismiss()
            }
        }
        dialog.setContentView(root)
        dialog.setOnDismissListener { destroyWeb() }
        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) = destroyWeb()
        })
        dialog.window?.apply {
            setBackgroundDrawable(InsetDrawable(activity.borderBox(), activity.dp(SIDE_DP), 0, activity.dp(SIDE_DP), 0))
            setGravity(Gravity.BOTTOM)
            setLayout(MATCH_PARENT, height)
            // No dim: on e-ink it repaints the whole page grey (InkDialog has none either).
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
        try {
            web.loadUrl(url)
            dialog.showNoAnim()
        } catch (_: Exception) {
            destroyWeb()
            return false
        }
        PanelRegistry.dialog(activity, dialog)
        return true
    }

    /** Dragging [bar] up grows the window, down shrinks it; [apply] runs once, on the finger-up, with the clamped height. */
    @SuppressLint("ClickableViewAccessibility")
    private fun attachResize(bar: View, screen: () -> Int, current: () -> Int, apply: (Int) -> Unit) {
        var downY = 0f
        var startHeight = 0
        bar.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downY = e.rawY
                    startHeight = current()
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val h = LookupQuery.clampHeight(startHeight + (downY - e.rawY).toInt(), screen())
                    if (h != current()) apply(h)
                    true
                }
                else -> true
            }
        }
    }

    private fun openInBrowser(activity: Activity, url: String): Boolean =
        TextActions.start(activity, Intent(Intent.ACTION_VIEW, Uri.parse(url)))
}
