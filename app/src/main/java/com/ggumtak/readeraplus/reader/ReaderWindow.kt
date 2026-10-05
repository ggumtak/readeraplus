package com.ggumtak.readeraplus.reader

import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import android.provider.Settings as SystemSettings

/** Window setup for the reader: edge-to-edge, fullscreen, cutouts, brightness. */
internal object ReaderWindow {

    /** One-time window flags (call before setContentView). */
    fun setup(activity: Activity) {
        val w = activity.window
        if (Build.VERSION.SDK_INT >= 28) {
            w.attributes = w.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        // We lay out edge-to-edge and pad for insets ourselves (the Comet cuts off apps that ignore them).
        if (Build.VERSION.SDK_INT >= 30) {
            w.setDecorFitsSystemWindows(false)
        }
        w.setWindowAnimations(0)
    }

    fun applyFullscreen(activity: Activity, fullscreen: Boolean) {
        val w = activity.window
        if (Build.VERSION.SDK_INT >= 30) {
            w.setDecorFitsSystemWindows(false)
            val c = w.insetsController ?: return
            if (fullscreen) {
                c.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                c.hide(WindowInsets.Type.systemBars())
            } else {
                c.show(WindowInsets.Type.systemBars())
            }
        } else {
            @Suppress("DEPRECATION")
            run {
                var flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                if (fullscreen) {
                    flags = flags or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                }
                w.decorView.systemUiVisibility = flags
            }
        }
    }

    /**
     * API 30+: the status and navigation bars (shown when 전체 화면 is off, or swiped in over it) let the reader show
     * through: the page colour, or the chrome's surface while it is open, so a dark page never gets white system bars
     * (U §2.1, 2026-10-05). Dark icons over a light page, light ones over a dark page ([dark]). Before API 30 the bars
     * keep the theme's colours and [applyFullscreen]'s light-status-bar flag.
     */
    fun applyBarLook(activity: Activity, dark: Boolean) {
        if (Build.VERSION.SDK_INT < 30) return
        val w = activity.window
        w.statusBarColor = Color.TRANSPARENT
        w.navigationBarColor = Color.TRANSPARENT
        // No system scrim behind a transparent navigation bar: the chrome's bottom bar reaches the screen edge.
        w.isNavigationBarContrastEnforced = false
        val c = w.insetsController ?: return
        val mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
            WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        c.setSystemBarsAppearance(if (dark) 0 else mask, mask)
    }

    /** [value] 0..1, or < 0 for the system brightness. */
    fun applyBrightness(activity: Activity, value: Float) {
        val w = activity.window
        val lp = w.attributes
        val target = if (value < 0f) WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE else value.coerceIn(0.01f, 1f)
        if (lp.screenBrightness != target) {
            lp.screenBrightness = target
            w.attributes = lp
        }
    }

    /** Current system brightness as 0..1 (approximate; used as the start of a manual adjustment). */
    fun systemBrightness(activity: Activity): Float = try {
        SystemSettings.System.getInt(activity.contentResolver, SystemSettings.System.SCREEN_BRIGHTNESS, 128) / 255f
    } catch (t: Throwable) {
        0.5f
    }

    /**
     * Insets to keep the page and chrome clear of: cutouts always, system bars only when they are shown, as
     * [left, top, right, bottom, cutoutTop]. cutoutTop is the part of top that only a display cutout takes (no system
     * bar shown there: fullscreen on the S25); the page view reaches into it with its paper, its header and text start
     * below it (`applyPageInsets`), the chrome does not. API 30+: [WindowInsets.getInsets] only reports *visible* bars (hidden and swipe-revealed
     * transient bars count as 0), so asking for system bars even in fullscreen costs nothing, and keeps the page clear
     * of a navigation bar that a vendor firmware refuses to hide (the Comet cut-off-bottom-bar problem).
     */
    /** Height of the bottom strip the system keeps for its swipes (home, recents); 0 before API 29. */
    fun gestureBottom(insets: WindowInsets): Int = when {
        Build.VERSION.SDK_INT >= 30 -> insets.getInsets(WindowInsets.Type.mandatorySystemGestures()).bottom
        Build.VERSION.SDK_INT >= 29 -> @Suppress("DEPRECATION") insets.mandatorySystemGestureInsets.bottom
        else -> 0
    }

    fun insetsOf(insets: WindowInsets, fullscreen: Boolean): IntArray {
        if (Build.VERSION.SDK_INT >= 30) {
            val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            return intArrayOf(i.left, i.top, i.right, i.bottom, InsetSplit.cutoutTop(i.top, bars.top))
        }
        if (fullscreen) {
            if (Build.VERSION.SDK_INT >= 28) {
                val c = insets.displayCutout
                // Fullscreen before API 30: no bar shows, the whole top inset is the cutout's.
                if (c != null) return intArrayOf(c.safeInsetLeft, c.safeInsetTop, c.safeInsetRight, c.safeInsetBottom,
                    InsetSplit.cutoutTop(c.safeInsetTop, 0))
            }
            return IntArray(INSETS)
        }
        @Suppress("DEPRECATION")
        return intArrayOf(
            insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
            insets.systemWindowInsetRight, insets.systemWindowInsetBottom, 0,
        )
    }

    /** Size of [insetsOf]'s array. */
    const val INSETS = 5
}

/**
 * How [ReaderWindow.insetsOf] splits a top inset (pure, unit-tested): the part only a display cutout takes, which the
 * page view reaches into with its paper while the header and the text start below it (fullscreen on the S25: the camera
 * band, `LayoutKeys.geometry`'s extraTop), and the margin the page view keeps.
 */
internal object InsetSplit {
    /**
     * The cutout-only part of a [top] inset (system bars and cutout together): all of it when no system bar shows there
     * ([barsTop] 0: fullscreen), none when a bar does (the bar is at least as tall as the cutout and the page goes
     * below it, as before). 0 for a top without a cutout (the Comet, a side cutout in landscape).
     */
    fun cutoutTop(top: Int, barsTop: Int): Int = if (barsTop <= 0) top.coerceAtLeast(0) else 0

    /** The page view's top margin: the inset less the cutout band the view reaches into. */
    fun pageTopMargin(top: Int, cutoutTop: Int): Int = (top - cutoutTop.coerceIn(0, maxOf(0, top))).coerceAtLeast(0)
}

/**
 * Keeps the screen on while reading and lets it go after (system screen-off timeout + 10 min) without
 * interaction. [poke] on every touch/key/page turn is cheap (no re-posting while a check is pending).
 */
internal class ScreenOnKeeper(private val activity: Activity) {
    private val handler = Handler(Looper.getMainLooper())
    private var flagOn = false
    private var scheduled = false
    private var lastPoke = 0L
    private var timeoutMs = DEFAULT_TIMEOUT
    private var disposed = false

    var enabled = false
        set(v) {
            field = v
            if (v) poke() else release()
        }

    private val check: Runnable = object : Runnable {
        override fun run() {
            scheduled = false
            val idle = SystemClock.uptimeMillis() - lastPoke
            if (idle >= timeoutMs) {
                clearFlag()
            } else {
                scheduled = true
                handler.postDelayed(this, timeoutMs - idle)
            }
        }
    }

    fun poke() {
        if (!enabled || disposed) return
        lastPoke = SystemClock.uptimeMillis()
        if (!flagOn) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            flagOn = true
        }
        if (!scheduled) {
            timeoutMs = computeTimeout()
            scheduled = true
            handler.postDelayed(check, timeoutMs)
        }
    }

    fun release() {
        handler.removeCallbacks(check)
        scheduled = false
        clearFlag()
    }

    /** The activity is gone: drop the pending check (it holds the activity) and ignore later pokes. */
    fun dispose() {
        disposed = true
        release()
    }

    private fun clearFlag() {
        if (flagOn) {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            flagOn = false
        }
    }

    private fun computeTimeout(): Long {
        val system = try {
            SystemSettings.System.getInt(activity.contentResolver, SystemSettings.System.SCREEN_OFF_TIMEOUT, 60_000).toLong()
        } catch (t: Throwable) {
            60_000L
        }
        return system.coerceIn(15_000L, 60 * 60_000L) + EXTRA_MS
    }

    companion object {
        const val EXTRA_MS = 10 * 60_000L
        private const val DEFAULT_TIMEOUT = 11 * 60_000L
    }
}

/** Fire-and-forget background writes (positions, bookmarks) that must outlive the activity. */
internal object ReaderIo {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun launch(block: suspend () -> Unit): Job = scope.launch {
        try {
            block()
        } catch (t: Throwable) {
            Log.w("ReaderIo", "background task failed", t)
        }
    }
}
