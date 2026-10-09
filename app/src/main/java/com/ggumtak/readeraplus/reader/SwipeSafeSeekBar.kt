package com.ggumtak.readeraplus.reader

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.SeekBar
import kotlin.math.abs

/** Pure (JVM-tested): what a [SwipeSafeSeekBar] does with a touch that moved ([dx], [dy] px) from its down point. */
internal object SwipeGuard {
    const val WAIT = 0
    /** Sideways first: a drag of the bar (replayed from the down). */
    const val DRAG = 1
    /** Up or down first: the system's swipe (home, recents, notifications), not the bar's. */
    const val DROP = 2

    fun decide(dx: Float, dy: Float, slop: Float): Int {
        val ax = abs(dx)
        val ay = abs(dy)
        return when {
            ay > slop && ay >= ax -> DROP
            ax > slop -> DRAG
            else -> WAIT
        }
    }
}

/**
 * A SeekBar that leaves vertical swipes alone (user report, 2026-10-05): the swipe up for home or recents starts right
 * above the navigation handle, on the bottom bar's page bar, and the platform bar jumped to where the finger landed,
 * so the book moved to another page; the swipe down for the notifications crosses the top bar's brightness bar.
 * A touch is held until it moves: sideways past the touch slop it becomes a normal drag (replayed from its down
 * point), up or down first it is dropped (nothing changes), and a lift in place is a normal tap (down + up replayed).
 */
@SuppressLint("ClickableViewAccessibility")
class SwipeSafeSeekBar(context: Context) : SeekBar(context) {
    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private var down: MotionEvent? = null
    private var passing = false
    private var dropped = false

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!isEnabled) return super.onTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                forget()
                down = MotionEvent.obtain(ev)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (passing) return super.onTouchEvent(ev)
                if (dropped) return true
                val d = down ?: return true
                when (SwipeGuard.decide(ev.x - d.x, ev.y - d.y, slop)) {
                    SwipeGuard.DROP -> dropped = true
                    SwipeGuard.DRAG -> {
                        passing = true
                        super.onTouchEvent(d)
                        return super.onTouchEvent(ev)
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (passing) {
                    val r = super.onTouchEvent(ev)
                    forget()
                    return r
                }
                val d = down
                if (!dropped && d != null) {
                    super.onTouchEvent(d)
                    super.onTouchEvent(ev)
                }
                forget()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                if (passing) super.onTouchEvent(ev)
                forget()
                return true
            }
        }
        return if (passing) super.onTouchEvent(ev) else true
    }

    private fun forget() {
        down?.recycle()
        down = null
        passing = false
        dropped = false
    }
}
