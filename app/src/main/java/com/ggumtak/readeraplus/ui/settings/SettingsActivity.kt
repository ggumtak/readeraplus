package com.ggumtak.readeraplus.ui.settings

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import android.widget.TextView
import com.ggumtak.readeraplus.R
import com.ggumtak.readeraplus.settings.Settings
import com.ggumtak.readeraplus.ui.kit.Ink
import com.ggumtak.readeraplus.ui.kit.lp
import com.ggumtak.readeraplus.ui.kit.toolbar
import com.ggumtak.readeraplus.ui.kit.vertical
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel

/**
 * App settings (ReadEra-style "설정"): a main list plus sub-pages kept in an in-activity stack. The toolbar back
 * arrow and the Back key pop the stack; [EXTRA_PAGE] opens a sub-page directly (Back then closes the activity).
 * Every change is saved immediately through [Settings].
 */
class SettingsActivity : Activity() {
    companion object {
        /** [page]: null = main list, or one of PAGE_* to open a sub-page directly. */
        const val EXTRA_PAGE = "page"
        const val PAGE_PAGE_TURNING = "page_turning"
        const val PAGE_FONTS = "fonts"
        const val PAGE_TTS = "tts"
        /** Scan folders / excluded folders / scan now. */
        const val PAGE_SCAN = "scan"
        /** Backup file export / restore. */
        const val PAGE_BACKUP = "backup"
        /** Dictionary, translator and web search. */
        const val PAGE_LOOKUP = "lookup"
        /** Version, licenses, device info. */
        const val PAGE_ABOUT = "about"
        /** "Wi-Fi로 책 받기" (T1-12): the upload server runs only while this page is shown and resumed. */
        const val PAGE_WIFI = "wifi"
        /** "읽기 기록" (T1-6): reading statistics, heatmap, finished books. */
        const val PAGE_STATS = "stats"
        /** "TXT 기본 정리 설정" (T1-9): the global TXT options every book without its own override uses. */
        const val PAGE_TXT_DEFAULTS = "txt_defaults"

        /**
         * Raw pref (Long, epoch millis) bumped by "캐시 비우기". Modules that cache derived data outside cacheDir
         * (e.g. page counts in the library DB) can fold it into their cache keys.
         */
        const val PREF_CACHE_EPOCH = "cacheEpoch"
        /** Raw pref (Long) with the time of the last completed library scan (shared with the library screen). */
        const val PREF_LAST_SCAN_AT = "lastScanAt"

        internal const val PAGE_MAIN = "main"
        private const val STATE_STACK = "settings.stack"

        internal const val REQ_ADD_SCAN_FOLDER = 4101
        internal const val REQ_ADD_EXCLUDED_FOLDER = 4102
        internal const val REQ_BACKUP_CREATE = 4201
        internal const val REQ_BACKUP_RESTORE = 4202
        internal const val REQ_FONT_IMPORT = 4301
        internal const val REQ_STORAGE_PERMISSION = 4401

        /**
         * Opens settings on [page] (null = the main list; an id this build doesn't know opens the main list too).
         * Callable from any activity: the library drawer (PAGE_STATS, PAGE_WIFI, PAGE_ABOUT), the reader.
         */
        fun open(context: Context, page: String? = null) {
            context.startActivity(
                Intent(context, SettingsActivity::class.java)
                    .putExtra(EXTRA_PAGE, page)
                    .addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION),
            )
        }
    }

    /** UI-bound scope for page work (IO via withContext); cancelled in onDestroy. */
    internal val scope: CoroutineScope = MainScope()

    private val stack = ArrayList<SettingsPage>()
    private lateinit var content: FrameLayout
    private lateinit var titleView: TextView

    /** True between onResume and onPause (a page that serves the network runs only then: Wi-Fi 전송). */
    internal var isResumedNow = false
        private set

    /** Last key seen anywhere in settings (for the "키 테스트" row); -1 = none yet. */
    internal var lastKeyCode = -1
        private set
    internal var lastScanCode = -1
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Settings.init(this)
        val root = vertical { setBackgroundColor(Ink.WHITE) }
        val bar = toolbar("설정", R.drawable.ic_arrow_back, onNav = { goBack() })
        titleView = bar.findViewWithTag("title")
        root.addView(bar, lp())
        content = FrameLayout(this).apply { setBackgroundColor(Ink.WHITE) }
        root.addView(content, lp(MATCH_PARENT, 0, 1f))
        setContentView(root)

        val ids = savedInstanceState?.getStringArrayList(STATE_STACK)?.takeIf { it.isNotEmpty() }
            ?: listOf(intent?.getStringExtra(EXTRA_PAGE) ?: PAGE_MAIN)
        for (id in ids) stack += createPage(id)
        showTop()
    }

    private fun createPage(id: String): SettingsPage = when (id) {
        PAGE_PAGE_TURNING -> PageTurningPage(this)
        PAGE_FONTS -> FontsPage(this)
        PAGE_TTS -> TtsPage(this)
        PAGE_SCAN -> ScanPage(this)
        PAGE_BACKUP -> BackupPage(this)
        PAGE_LOOKUP -> LookupPage(this)
        PAGE_ABOUT -> AboutPage(this)
        PAGE_WIFI -> WifiTransferPage(this)
        PAGE_STATS -> StatsPage(this)
        PAGE_TXT_DEFAULTS -> TxtDefaultsPage(this)
        else -> MainPage(this)
    }

    /** Opens a sub-page on top of the current one. */
    internal fun push(id: String) {
        stack.lastOrNull()?.onHidden()
        stack += createPage(id)
        showTop()
    }

    /** Pops the current page; closes the activity when it was the last one. */
    internal fun goBack() {
        if (stack.size <= 1) {
            finish()
            return
        }
        val top = stack.removeAt(stack.size - 1)
        top.onDestroy()
        showTop()
    }

    /** Rebuilds the current page's view from scratch (after bulk changes such as a settings reset). */
    internal fun rebuildTop() {
        stack.lastOrNull()?.view = null
        showTop()
    }

    private fun showTop() {
        val page = stack.last()
        titleView.text = page.title
        val v = page.view ?: page.build().also { page.view = it }
        content.removeAllViews()
        (v.parent as? android.view.ViewGroup)?.removeView(v)
        content.addView(v, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        page.onShown()
    }

    override fun onResume() {
        super.onResume()
        isResumedNow = true
        stack.lastOrNull()?.onResume()
    }

    override fun onPause() {
        isResumedNow = false
        stack.lastOrNull()?.onPause()
        super.onPause()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        goBack()
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && event.keyCode != KeyEvent.KEYCODE_BACK) {
            lastKeyCode = event.keyCode
            lastScanCode = event.scanCode
            stack.lastOrNull()?.onKeyPressed(event.keyCode, event.scanCode)
        }
        return super.dispatchKeyEvent(event)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        for (i in stack.indices.reversed()) {
            if (stack[i].onActivityResult(requestCode, resultCode, data)) break
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_STORAGE_PERMISSION) stack.lastOrNull()?.onResume()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList(STATE_STACK, ArrayList(stack.map { it.id }))
    }

    override fun onDestroy() {
        stack.forEach { runCatching { it.onDestroy() } }
        scope.cancel()
        super.onDestroy()
    }
}
