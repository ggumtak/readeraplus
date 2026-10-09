package com.ggumtak.readeraplus.reader

import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.FrameMetrics
import android.view.Window

/**
 * RAPerf DEBUG only: the window's FrameMetrics for the frames that drew a traced turn or a book's first page, one
 * "frame #<turn> …" line each ([PerfLines.frameLine]). The page view notes those frames in [trace]; every other frame
 * costs one lookup on this watch's own thread. The reader creates it only with the tag on ([start]), so a normal run
 * registers nothing. Watching draws nothing and invalidates nothing.
 */
internal class FrameWatch private constructor(
    private val thread: HandlerThread,
) : Window.OnFrameMetricsAvailableListener {
    val trace = FrameTrace()

    // The watch thread only.
    private val found = LongArray(FrameTrace.FIELDS)
    private val parts = LongArray(PerfLines.FRAME_PARTS)
    private val text = StringBuilder(220)

    override fun onFrameMetricsAvailable(window: Window, metrics: FrameMetrics, dropCountSinceLastInvocation: Int) {
        val vsyncMs = metrics.getMetric(FrameMetrics.VSYNC_TIMESTAMP) / NS_PER_MS
        // Usually no entry: one lookup per frame. Two traces drawn by one frame (an open and a turn) share its metrics.
        while (trace.take(vsyncMs, found)) {
            for (i in 0 until PART_METRICS.size) parts[i] = metrics.getMetric(PART_METRICS[i])
            parts[PerfLines.FRAME_PARTS - 1] =
                if (Build.VERSION.SDK_INT >= 31) metrics.getMetric(FrameMetrics.GPU_DURATION) else -1L
            val total = metrics.getMetric(FrameMetrics.TOTAL_DURATION)
            val doneAt = (metrics.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP) + total) / NS_PER_MS
            text.setLength(0)
            PerfLines.frameLine(text, found[0].toInt(), found[1].toInt(), parts, total, doneAt, found[2], found[3])
            Log.d(ReaderPerf.TAG, text.toString())
        }
    }

    /** Unregisters and ends the watch thread (the activity is going away). */
    fun stop(window: Window) {
        try {
            window.removeOnFrameMetricsAvailableListener(this)
        } catch (t: Throwable) {
            Log.w(ReaderPerf.TAG, "frame metrics listener removal failed", t)
        }
        thread.quitSafely()
    }

    companion object {
        private const val NS_PER_MS = 1_000_000L

        /** FrameMetrics durations in [PerfLines] order, all but the last ("gpu", API 31). */
        private val PART_METRICS = intArrayOf(
            FrameMetrics.UNKNOWN_DELAY_DURATION, FrameMetrics.INPUT_HANDLING_DURATION, FrameMetrics.ANIMATION_DURATION,
            FrameMetrics.LAYOUT_MEASURE_DURATION, FrameMetrics.DRAW_DURATION, FrameMetrics.SYNC_DURATION,
            FrameMetrics.COMMAND_ISSUE_DURATION, FrameMetrics.SWAP_BUFFERS_DURATION,
        )

        /** Watches [window] (main thread, only with [ReaderPerf.turns]); null when the window can't be observed. */
        fun start(window: Window): FrameWatch? {
            val thread = HandlerThread("reader-frames").apply { start() }
            return try {
                FrameWatch(thread).also { window.addOnFrameMetricsAvailableListener(it, Handler(thread.looper)) }
            } catch (t: Throwable) {
                // Under the RAPerf tag, so `adb logcat -s RAPerf` shows why no "frame" line comes (DEVICE_CHECKLIST 15d).
                Log.w(ReaderPerf.TAG, "frame metrics unavailable", t)
                thread.quitSafely()
                null
            }
        }
    }
}
