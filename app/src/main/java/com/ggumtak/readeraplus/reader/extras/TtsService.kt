package com.ggumtak.readeraplus.reader.extras

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import com.ggumtak.readeraplus.R
import com.ggumtak.readeraplus.reader.ReaderActivity
import java.lang.ref.WeakReference

/**
 * What the TTS notification and media session show (T1-11). Sent by [TtsController] on play / pause and when the
 * spoken chapter changes, never per sentence. [keepAwakeMs] is the wake-lock timeout while playing (the sleep
 * deadline, else 2 h; the controller renews it while it keeps speaking).
 */
internal class TtsState(
    val bookId: Long,
    val title: String,
    val chapter: String,
    val playing: Boolean,
    val keepAwakeMs: Long,
    /** A transient audio-focus pause must stay available until focus returns, without holding a wake lock. */
    val holdForeground: Boolean = false,
) {
    /** Only an ordinary user pause starts the service's idle-stop wait. */
    val idleStopAllowed: Boolean get() = !playing && !holdForeground

    /** Same notification content (a wake-lock renewal alone does not repost it). */
    fun sameShown(o: TtsState?): Boolean = o != null && o.bookId == bookId && o.title == title && o.chapter == chapter && o.playing == playing
}

/**
 * The service's buttons and the media session reach the reader's controller through this (same process, main
 * thread). Weak: a notification left behind must never keep a closed reader alive.
 */
internal object TtsBridge {
    var controller: WeakReference<TtsController>? = null

    fun controller(): TtsController? = controller?.get()
}

/**
 * Keeps TTS alive with the screen off (T1-11): a media-playback foreground service (declared in the manifest with
 * `foregroundServiceType="mediaPlayback"`, not exported). The engine stays in [TtsController] in the same process;
 * this is only the keep-alive and control shell.
 *
 * - `startForeground(id, notification, FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)` on start; a `Notification.MediaStyle`
 *   notification on channel "tts" (IMPORTANCE_LOW): book title, chapter title, actions 이전 / 재생·일시정지 / 다음 /
 *   정지 (previous / next sentence), a tap reopens the reader. Reposted only when [TtsState] changes what it shows.
 * - A platform `MediaSession` (active) whose callbacks reach the controller through [TtsBridge] (headset and
 *   lock-screen buttons too).
 * - A PARTIAL_WAKE_LOCK "readeraplus:tts" only while speaking (timeout [TtsState.keepAwakeMs]), released on pause.
 *   Paused, the service stays in the foreground (play from the notification needs no background-start exemption)
 *   and stops itself after [IDLE_STOP_MS] of awake time during a user pause. Deep sleep delays that callback;
 *   a transient audio-focus pause keeps the service until focus returns. Swiping the paused notification away
 *   stops it too.
 *
 * Started, updated and stopped by TtsController only ([update] / [stop], main thread). Nothing runs while TTS is off.
 */
class TtsService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var session: MediaSession? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var foreground = false
    private var shown: TtsState? = null
    private var pausedSince = -1L

    private val idleStop = Runnable {
        // This callback is scheduled on uptime, so deep sleep delays it. Re-check elapsed time when it runs.
        val left = IDLE_STOP_MS - (SystemClock.elapsedRealtime() - pausedSince)
        if (pausedSince >= 0 && left > 1000L) restartIdleTimer(left) else if (pausedSince >= 0) finish()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        runCatching {
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(
                NotificationChannel(CHANNEL, "TTS 읽기", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "화면이 꺼져도 TTS가 계속 읽는 동안 표시됩니다"
                    setShowBadge(false)
                    enableVibration(false)
                    setSound(null, null)
                },
            )
        }
        ensureSession()
    }

    /** The media session (again after [finish] when a new start reached this same instance before onDestroy). */
    private fun ensureSession() {
        if (session != null) return
        session = runCatching {
            MediaSession(this, "readeraplus-tts").apply {
                setCallback(object : MediaSession.Callback() {
                    override fun onPlay() = remote(ACTION_PLAY)
                    override fun onPause() = remote(ACTION_PAUSE)
                    override fun onSkipToNext() = remote(ACTION_NEXT)
                    override fun onSkipToPrevious() = remote(ACTION_PREV)
                    override fun onStop() = remote(ACTION_STOP)
                }, main)
                isActive = true
            }
        }.getOrNull()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        starting = false
        instance = this
        ensureSession()
        val s = state
        // startForegroundService must be answered with startForeground, also when the TTS stopped meanwhile.
        if (!foreground) {
            val ok = runCatching {
                val n = notification(s ?: TtsState(-1L, "", "", playing = false, keepAwakeMs = 0L))
                if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
                else startForeground(NOTIFICATION_ID, n)
            }.isSuccess
            if (!ok) {
                finish()
                return START_NOT_STICKY
            }
            foreground = true
        }
        when (val a = intent?.action) {
            ACTION_DISMISS -> {
                // The paused notification was swiped away: end the service, keep the reader's TTS bar.
                finish()
                return START_NOT_STICKY
            }
            null -> {}
            else -> remote(a)
        }
        val now = state
        if (now == null) finish() else apply(now)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        release()
        if (instance === this) instance = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------ state

    private fun apply(s: TtsState) {
        if (!s.sameShown(shown)) {
            val n = notification(s)
            runCatching { getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, n) }
            session?.let { ms ->
                runCatching {
                    ms.setMetadata(
                        MediaMetadata.Builder()
                            .putString(MediaMetadata.METADATA_KEY_TITLE, s.title)
                            .putString(MediaMetadata.METADATA_KEY_ARTIST, s.chapter)
                            .putString(MediaMetadata.METADATA_KEY_ALBUM, s.title)
                            .build(),
                    )
                    ms.setPlaybackState(
                        PlaybackState.Builder()
                            .setActions(
                                PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                                    PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_STOP,
                            )
                            .setState(
                                if (s.playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                                PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                                if (s.playing) 1f else 0f,
                            )
                            .build(),
                    )
                }
            }
            shown = s
        }
        if (s.playing) {
            pausedSince = -1L
            main.removeCallbacks(idleStop)
            keepAwake(s.keepAwakeMs)
        } else {
            releaseWakeLock()
            if (!s.idleStopAllowed) {
                pausedSince = -1L
                main.removeCallbacks(idleStop)
            } else if (pausedSince < 0) {
                pausedSince = SystemClock.elapsedRealtime()
                restartIdleTimer(IDLE_STOP_MS)
            }
        }
    }

    private fun restartIdleTimer(ms: Long) {
        main.removeCallbacks(idleStop)
        main.postDelayed(idleStop, ms)
    }

    /** (Re)acquires the partial wake lock for [ms] (a non-counted lock: a renewal only moves the timeout). */
    private fun keepAwake(ms: Long) {
        val wl = wakeLock ?: runCatching {
            getSystemService(PowerManager::class.java)?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_TAG)?.apply { setReferenceCounted(false) }
        }.getOrNull()?.also { wakeLock = it } ?: return
        runCatching { wl.acquire(ms.coerceIn(60_000L, MAX_AWAKE_MS)) }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { wl -> runCatching { if (wl.isHeld) wl.release() } }
    }

    private fun finish() {
        release()
        if (foreground) {
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            foreground = false
        }
        stopSelf()
        if (instance === this) instance = null
    }

    private fun release() {
        main.removeCallbacks(idleStop)
        pausedSince = -1L
        releaseWakeLock()
        session?.let { runCatching { it.isActive = false; it.release() } }
        session = null
        shown = null
    }

    private fun remote(action: String) {
        val c = TtsBridge.controller() ?: return
        runCatching { c.onRemote(action) }
    }

    // ------------------------------------------------------------------ notification

    private fun notification(s: TtsState): Notification {
        val open = Intent(this, ReaderActivity::class.java)
            .putExtra(ReaderActivity.EXTRA_BOOK_ID, s.bookId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        val content = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_headphones)
            .setContentTitle(s.title.ifBlank { "TTS 읽기" })
            .setContentText(s.chapter)
            .setContentIntent(content)
            .setDeleteIntent(action(ACTION_DISMISS, 5))
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setOngoing(s.playing)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(button(R.drawable.ic_skip_previous, "이전 문장", ACTION_PREV, 1))
            .addAction(
                if (s.playing) button(R.drawable.ic_pause, "일시정지", ACTION_PAUSE, 2)
                else button(R.drawable.ic_play_arrow, "재생", ACTION_PLAY, 2),
            )
            .addAction(button(R.drawable.ic_skip_next, "다음 문장", ACTION_NEXT, 3))
            .addAction(button(R.drawable.ic_close, "정지", ACTION_STOP, 4))
        val style = Notification.MediaStyle().setShowActionsInCompactView(0, 1, 2)
        session?.let { style.setMediaSession(it.sessionToken) }
        b.setStyle(style)
        return b.build()
    }

    private fun button(icon: Int, title: String, act: String, code: Int): Notification.Action =
        Notification.Action.Builder(Icon.createWithResource(this, icon), title, action(act, code)).build()

    private fun action(act: String, code: Int): PendingIntent =
        PendingIntent.getService(
            this,
            code,
            Intent(this, TtsService::class.java).setAction(act),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    companion object {
        private const val CHANNEL = "tts"
        private const val NOTIFICATION_ID = 7301
        private const val WAKE_TAG = "readeraplus:tts"
        /** A user-paused service stops after this much awake time (deep sleep delays the callback). */
        const val IDLE_STOP_MS = 10 * 60_000L
        /** Wake-lock timeout without a sleep timer (the controller renews it while speaking). */
        const val MAX_AWAKE_MS = 2 * 60 * 60_000L

        internal const val ACTION_PLAY = "com.ggumtak.readeraplus.tts.PLAY"
        internal const val ACTION_PAUSE = "com.ggumtak.readeraplus.tts.PAUSE"
        internal const val ACTION_NEXT = "com.ggumtak.readeraplus.tts.NEXT"
        internal const val ACTION_PREV = "com.ggumtak.readeraplus.tts.PREV"
        internal const val ACTION_STOP = "com.ggumtak.readeraplus.tts.STOP"
        private const val ACTION_DISMISS = "com.ggumtak.readeraplus.tts.DISMISS"

        /** The running service (main thread), or null. */
        private var instance: TtsService? = null
        /** startForegroundService was called and onStartCommand has not run yet (it applies [state] then). */
        private var starting = false
        /** What the controller wants shown; null = stop. */
        private var state: TtsState? = null

        /**
         * Shows [s]: starts the foreground service on the first call (the reader is in front then: the user pressed
         * play), else updates it in place. A start the system refuses (e.g. from the background after the idle stop)
         * is ignored: speech goes on without the service. Main thread.
         */
        internal fun update(ctx: Context, s: TtsState) {
            state = s
            val svc = instance
            if (svc != null && svc.foreground) {
                svc.apply(s)
                return
            }
            if (starting) return
            starting = true
            try {
                ctx.applicationContext.startForegroundService(Intent(ctx.applicationContext, TtsService::class.java))
            } catch (_: Exception) {
                // ForegroundServiceStartNotAllowedException (API 31+, app in the background) / SecurityException.
                starting = false
            }
        }

        /**
         * Renews the wake lock while speaking goes on (called now and then by the controller, not per sentence).
         * No-op when the service isn't running. Main thread.
         */
        internal fun keepAwake(ms: Long) {
            val svc = instance ?: return
            if (svc.foreground && state?.playing == true) svc.keepAwake(ms)
        }

        /** Ends the service (notification, session, wake lock). A start still pending ends as soon as it runs. */
        internal fun stop() {
            state = null
            val svc = instance ?: return
            if (svc.foreground) svc.finish()
        }
    }
}
