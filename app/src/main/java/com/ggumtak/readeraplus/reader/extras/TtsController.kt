package com.ggumtak.readeraplus.reader.extras

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.view.Gravity
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import com.ggumtak.readeraplus.R
import com.ggumtak.readeraplus.engine.OBJECT_CHAR
import com.ggumtak.readeraplus.format.DocPosition
import com.ggumtak.readeraplus.reader.ReaderHost
import com.ggumtak.readeraplus.render.Highlight
import com.ggumtak.readeraplus.render.HighlightKind
import com.ggumtak.readeraplus.settings.Settings
import com.ggumtak.readeraplus.ui.kit.Ink
import com.ggumtak.readeraplus.ui.kit.alert
import com.ggumtak.readeraplus.ui.kit.chooser
import com.ggumtak.readeraplus.ui.kit.dp
import com.ggumtak.readeraplus.ui.kit.label
import com.ggumtak.readeraplus.ui.kit.lp
import com.ggumtak.readeraplus.ui.kit.row
import com.ggumtak.readeraplus.ui.kit.showNoAnim
import com.ggumtak.readeraplus.ui.kit.stepperRow
import com.ggumtak.readeraplus.ui.kit.toast
import com.ggumtak.readeraplus.ui.kit.vertical
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Text-to-speech: reads from the current page sentence by sentence (a few utterances queued ahead), highlights
 * the spoken sentence (owner "tts"), turns pages as speech moves on (mid-sentence when the engine reports word
 * ranges), continues into following sections, and shows its own control bar over the reader
 * ([×] [⚙] page [‹] [›] + round play/pause). Handles audio focus, headphone unplugging and the sleep timer.
 */
class TtsController(private val host: ReaderHost) {
    // Resolved lazily: the host may construct this before its own properties are initialised.
    private val ctx get() = host.activity
    private val main = Handler(Looper.getMainLooper())
    private val scope = MainScope()

    private var tts: TextToSpeech? = null
    private var ready = false
    private var initializing = false
    private var pendingPlay = false

    private var session = false
    private var playing = false
    private var loaded = false
    /** Bumped whenever the engine queue is flushed; stale callbacks carry an older value. */
    private var uttGen = 0
    /** Bumped when the queue is rebuilt; stale section loads are dropped. */
    private var loadGen = 0

    private class Utt(val serial: Int, val sec: Int, val start: Int, val end: Int, val text: String)

    private val items = ArrayList<Utt>()
    private var base = 0
    private var pos = 0
    private var enqueued = 0
    private var lastLoadedSection = -1
    private var loadingNext = false
    private var endOfBook = false
    private var highlightedSection = -1
    private var errorStreak = 0
    private var selfNav = false
    /** The user turned pages: don't pull the page back to the spoken sentence until we restart there. */
    private var userMoved = false
    private var navTries = 0
    private var pausedByFocus = false

    private var bar: LinearLayout? = null
    private var playButton: ImageButton? = null
    private var barLabel: TextView? = null
    private var sleepDeadline = 0L
    private var noisyRegistered = false
    private var focusRequest: AudioFocusRequest? = null

    private val audioAttrs: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    init {
        TtsRegistry.put(host, this)
    }

    /** True while a TTS session is active (speaking, or paused with the control bar shown). */
    val isSpeaking: Boolean get() = session

    /** True only while audio is actually playing (not paused). */
    val isPlaying: Boolean get() = playing

    fun start() {
        if (session && userMoved && !playing) {
            // Paused, then the user turned pages: resume from the page now shown, not the old sentence.
            main.removeCallbacks(navRestart)
            navTries = 0
            pendingPlay = true
            navRestart.run()
            if (!userMoved && loaded && !playing && pendingPlay) play()
            updateBar()
            return
        }
        if (session && loaded && !playing) {
            play()
            return
        }
        if (session && (playing || pendingPlay)) return
        startFrom(host.currentPosition())
    }

    /** Starts (or restarts) reading at [p] (used by the selection popup's "여기서 읽기"). */
    fun startFrom(p: DocPosition) {
        if (host.document == null) {
            ctx.toast("문서를 여는 중입니다")
            return
        }
        session = true
        // A pending "user turned pages" restart must not override the explicitly chosen start.
        main.removeCallbacks(navRestart)
        userMoved = false
        showBar()
        flushEngine()
        resetQueue()
        playing = false
        pendingPlay = true
        ensureEngine()
        loadInitial(p)
        updateBar()
    }

    fun pause() = pauseInternal(abandonFocus = true)

    fun stop() {
        pendingPlay = false
        playing = false
        pausedByFocus = false
        flushEngine()
        abandonFocus()
        unregisterNoisy()
        cancelSleep()
        main.removeCallbacks(navRestart)
        main.removeCallbacks(paramsRestart)
        userMoved = false
        clearHighlight()
        resetQueue()
        session = false
        removeBar()
    }

    /** Host calls this when the user turned pages manually while TTS is active. */
    fun onUserNavigated() {
        if (!session || selfNav) return
        userMoved = true
        navTries = 0
        main.removeCallbacks(navRestart)
        main.postDelayed(navRestart, 600)
    }

    fun release() {
        stop()
        runCatching { tts?.shutdown() }
        tts = null
        ready = false
        initializing = false
        scope.cancel()
        main.removeCallbacksAndMessages(null)
        TtsRegistry.remove(host, this)
    }

    // ------------------------------------------------------------------ engine

    private fun ensureEngine() {
        if (tts != null) {
            if (ready && pendingPlay && loaded) play()
            return
        }
        if (initializing) return
        initializing = true
        tts = TextToSpeech(ctx.applicationContext) { status -> main.post { onInit(status) } }
    }

    private fun onInit(status: Int) {
        initializing = false
        val engine = tts ?: return
        if (status != TextToSpeech.SUCCESS) {
            runCatching { engine.shutdown() }
            tts = null
            ctx.toast("TTS 엔진을 사용할 수 없습니다. 설정에서 TTS 엔진을 확인하세요.")
            stop()
            return
        }
        ready = true
        engine.setOnUtteranceProgressListener(listener)
        runCatching { engine.setAudioAttributes(audioAttrs) }
        chooseVoice(engine)
        if (pendingPlay && loaded && session) play()
    }

    private fun chooseVoice(engine: TextToSpeech) {
        val saved = runCatching { Settings.raw().getString(PREF_VOICE, null) }.getOrNull()
        if (saved != null) {
            val v = runCatching { engine.voices?.firstOrNull { it.name == saved } }.getOrNull()
            if (v != null && runCatching { engine.setVoice(v) }.getOrDefault(TextToSpeech.ERROR) == TextToSpeech.SUCCESS) return
        }
        val sample = host.currentLayout?.content?.text?.let { t -> t.substring(0, minOf(t.length, 2000)) } ?: ""
        val hangul = sample.any { it in '가'..'힣' }
        val bookLang = host.book.language?.takeIf { it.isNotBlank() }?.let { runCatching { Locale.forLanguageTag(it) }.getOrNull() }
        val want = if (hangul || bookLang == null) Locale.KOREAN else bookLang
        val ok = runCatching { engine.isLanguageAvailable(want) >= TextToSpeech.LANG_AVAILABLE }.getOrDefault(false)
        if (ok) {
            runCatching { engine.language = want }
        } else if (runCatching { engine.isLanguageAvailable(Locale.KOREAN) >= TextToSpeech.LANG_AVAILABLE }.getOrDefault(false)) {
            runCatching { engine.language = Locale.KOREAN }
        } else if (hangul) {
            ctx.toast("한국어 음성이 없습니다. TTS 엔진에서 한국어 음성 데이터를 설치하세요.")
        }
    }

    private fun applyParams() {
        val engine = tts ?: return
        val app = Settings.app
        runCatching {
            engine.setSpeechRate(app.ttsRate.coerceIn(0.3f, 3f))
            engine.setPitch(app.ttsPitch.coerceIn(0.3f, 3f))
        }
    }

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            main.post { onUttStart(utteranceId) }
        }

        override fun onDone(utteranceId: String?) {
            main.post { onUttDone(utteranceId, error = false) }
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            main.post { onUttDone(utteranceId, error = true) }
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            main.post { onUttDone(utteranceId, error = true) }
        }

        override fun onStop(utteranceId: String?, interrupted: Boolean) {}

        override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
            main.post { onRange(utteranceId, start) }
        }
    }

    // ------------------------------------------------------------------ queue

    private fun resetQueue() {
        items.clear()
        base = 0
        pos = 0
        enqueued = 0
        lastLoadedSection = -1
        loadingNext = false
        endOfBook = false
        loaded = false
        errorStreak = 0
        loadGen++
    }

    private fun flushEngine() {
        uttGen++
        runCatching { tts?.stop() }
    }

    private suspend fun loadText(sec: Int): String? {
        val layout = host.currentLayout
        if (layout != null && host.currentPosition().section == sec) return layout.content.text
        val doc = host.document ?: return null
        return withContext(Dispatchers.Default) { runCatching { doc.loadSection(sec).text }.getOrNull() }
    }

    /** Loads sentences from [p]; plays when done unless the user paused meanwhile (pendingPlay cleared). */
    private fun loadInitial(p: DocPosition) {
        val g = loadGen
        scope.launch {
            val text = loadText(p.section)
            val ranges = if (text != null) withContext(Dispatchers.Default) { SentenceSplitter.split(text, p.offset.coerceIn(0, text.length)) } else null
            if (g != loadGen || !session) return@launch
            if (text == null || ranges == null) {
                ctx.toast("이 부분을 읽을 수 없습니다")
                stop()
                return@launch
            }
            append(p.section, text, ranges)
            lastLoadedSection = p.section
            pos = base
            enqueued = base
            loaded = true
            if (pendingPlay) {
                if (ready) play() else ensureEngine()
            } else {
                updateBar()
            }
        }
    }

    private fun append(sec: Int, text: String, ranges: IntArray) {
        var i = 0
        while (i + 1 < ranges.size) {
            val s = ranges[i]
            val e = ranges[i + 1]
            items.add(Utt(base + items.size, sec, s, e, text.substring(s, e).replace(OBJECT_CHAR, ' ')))
            i += 2
        }
    }

    private fun itemAt(serial: Int): Utt? = items.getOrNull(serial - base)

    private fun prefetchNext() {
        if (loadingNext || endOfBook) return
        val doc = host.document ?: return
        val next = lastLoadedSection + 1
        if (next >= doc.sections.size) {
            endOfBook = true
            return
        }
        loadingNext = true
        val g = loadGen
        scope.launch {
            val text = loadText(next)
            val ranges = if (text != null) withContext(Dispatchers.Default) { SentenceSplitter.split(text) } else IntArray(0)
            if (g != loadGen) return@launch
            loadingNext = false
            lastLoadedSection = next
            if (text != null) append(next, text, ranges)
            if (playing) fill() else if (base + items.size - pos <= AHEAD + 2) prefetchNext()
        }
    }

    /** Keeps [AHEAD] utterances queued in the engine and the next section loaded in time. */
    private fun fill() {
        val engine = tts ?: return
        if (!playing) return
        while (enqueued < base + items.size && enqueued - pos < AHEAD) {
            val u = items[enqueued - base]
            val id = UtteranceId.make(u.sec, u.start, u.end, u.serial, uttGen)
            val r = runCatching { engine.speak(u.text, TextToSpeech.QUEUE_ADD, Bundle(), id) }.getOrDefault(TextToSpeech.ERROR)
            enqueued++
            if (r == TextToSpeech.ERROR) {
                // A rejected utterance never reports back: without this the session would hang "playing" silently.
                errorStreak++
                if (errorStreak >= MAX_ERRORS) {
                    pause()
                    ctx.toast("TTS 오류: 음성 데이터와 TTS 엔진 설정을 확인하세요")
                    return
                }
            }
        }
        if (base + items.size - pos <= AHEAD + 2) prefetchNext()
        if (pos >= base + items.size && endOfBook && !loadingNext) finished()
    }

    private fun finished() {
        ctx.toast("끝까지 읽었습니다")
        stop()
    }

    private fun onUttStart(id: String?) {
        val p = UtteranceId.parse(id) ?: return
        if (p[4] != uttGen || !playing) return
        val u = itemAt(p[3]) ?: return
        errorStreak = 0
        pos = u.serial
        trimBefore(u)
        follow(u.sec, u.start)
        highlight(u)
        updateBar()
    }

    private fun onUttDone(id: String?, error: Boolean) {
        val p = UtteranceId.parse(id) ?: return
        if (p[4] != uttGen || !playing) return
        if (error) {
            errorStreak++
            if (errorStreak >= MAX_ERRORS) {
                pause()
                ctx.toast("TTS 오류: 음성 데이터와 TTS 엔진 설정을 확인하세요")
                return
            }
        }
        if (p[3] + 1 > pos) pos = p[3] + 1
        fill()
    }

    private fun onRange(id: String?, start: Int) {
        val p = UtteranceId.parse(id) ?: return
        if (p[4] != uttGen || !playing) return
        val u = itemAt(p[3]) ?: return
        val page = host.currentPage ?: return
        val off = u.start + start
        if (host.currentPosition().section == u.sec && off >= page.end) follow(u.sec, off)
    }

    /** Drops sentences of sections already finished (keeps memory flat over a long book). */
    private fun trimBefore(u: Utt) {
        var k = 0
        while (k < items.size && items[k].sec < u.sec && items[k].serial < u.serial) k++
        if (k > 0) {
            items.subList(0, k).clear()
            base += k
        }
    }

    // ------------------------------------------------------------------ page follow & highlight

    private fun follow(sec: Int, off: Int) {
        if (userMoved) return
        val cur = host.currentPosition()
        val page = host.currentPage
        val layout = host.currentLayout
        selfNav = true
        try {
            if (cur.section != sec) {
                host.goTo(DocPosition(sec, off), remember = false)
            } else if (page != null && layout != null && (off >= page.end || off < page.start)) {
                val target = layout.pageForOffset(off)
                if (target == host.currentPageIndex + 1) host.nextPage() else host.goTo(DocPosition(sec, off), remember = false)
            }
        } finally {
            selfNav = false
        }
    }

    private fun highlight(u: Utt) {
        if (highlightedSection >= 0 && highlightedSection != u.sec) runCatching { host.setHighlights("tts", highlightedSection, emptyList()) }
        runCatching { host.setHighlights("tts", u.sec, listOf(Highlight(u.start, u.end, HighlightKind.TTS))) }
        highlightedSection = u.sec
    }

    private fun clearHighlight() {
        if (highlightedSection >= 0) runCatching { host.setHighlights("tts", highlightedSection, emptyList()) }
        highlightedSection = -1
    }

    private val navRestart: Runnable = object : Runnable {
        override fun run() {
            if (!session) {
                userMoved = false
                return
            }
            val page = host.currentPage
            if (page == null || host.currentLayout == null) {
                // The new section is still being laid out.
                if (++navTries < 25) main.postDelayed(this, 200) else userMoved = false
                return
            }
            userMoved = false
            val cur = host.currentPosition()
            val u = itemAt(pos)
            // Our own page turn echoed back by the host: still on the page being spoken.
            if (u != null && u.sec == cur.section && u.start >= page.start && u.start < page.end) return
            val autoplay = playing || pendingPlay
            flushEngine()
            playing = false
            clearHighlight()
            resetQueue()
            pendingPlay = autoplay
            loadInitial(DocPosition(cur.section, page.start))
            updateBar()
        }
    }

    // ------------------------------------------------------------------ play / pause

    private fun play() {
        if (!session) return
        val engine = tts
        if (engine == null || !ready || !loaded) {
            pendingPlay = true
            ensureEngine()
            updateBar()
            return
        }
        pendingPlay = false
        if (playing) return
        requestFocus()
        registerNoisy()
        applyParams()
        playing = true
        errorStreak = 0
        enqueued = pos
        startSleepTimer()
        itemAt(pos)?.let { u ->
            follow(u.sec, u.start)
            highlight(u)
        }
        fill()
        updateBar()
    }

    private fun pauseInternal(abandonFocus: Boolean) {
        if (!session) return
        pendingPlay = false
        if (playing) {
            playing = false
            flushEngine()
            cancelSleep()
            unregisterNoisy()
        }
        if (abandonFocus) {
            pausedByFocus = false
            abandonFocus()
        }
        updateBar()
    }

    private fun jump(delta: Int) {
        val target = pos + delta
        if (target < base || target >= base + items.size) {
            if (delta > 0 && !endOfBook) prefetchNext()
            return
        }
        val wasPlaying = playing
        flushEngine()
        pos = target
        enqueued = pos
        val u = items[pos - base]
        follow(u.sec, u.start)
        highlight(u)
        if (wasPlaying) fill()
        updateBar()
    }

    private val paramsRestart = Runnable {
        if (!playing) return@Runnable
        flushEngine()
        applyParams()
        enqueued = pos
        fill()
    }

    // ------------------------------------------------------------------ audio focus, noisy, sleep

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> pause()
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> if (playing) {
                pauseInternal(abandonFocus = false)
                pausedByFocus = true
            }
            AudioManager.AUDIOFOCUS_GAIN -> if (pausedByFocus && session) {
                pausedByFocus = false
                start()
            }
        }
    }

    private fun requestFocus() {
        val am = ctx.getSystemService(AudioManager::class.java) ?: return
        val req = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(audioAttrs)
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener(focusListener, main)
            .build()
            .also { focusRequest = it }
        runCatching { am.requestAudioFocus(req) }
    }

    private fun abandonFocus() {
        val req = focusRequest ?: return
        runCatching { ctx.getSystemService(AudioManager::class.java)?.abandonAudioFocusRequest(req) }
    }

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) pause()
        }
    }

    private fun registerNoisy() {
        if (noisyRegistered) return
        val f = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        runCatching {
            if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(noisyReceiver, f, Context.RECEIVER_NOT_EXPORTED)
            else ctx.registerReceiver(noisyReceiver, f)
            noisyRegistered = true
        }
    }

    private fun unregisterNoisy() {
        if (!noisyRegistered) return
        runCatching { ctx.unregisterReceiver(noisyReceiver) }
        noisyRegistered = false
    }

    private val sleepRunnable = Runnable {
        sleepDeadline = 0L
        if (playing) {
            pause()
            ctx.toast("수면 타이머: TTS를 멈췄습니다")
        }
    }

    private fun startSleepTimer() {
        main.removeCallbacks(sleepRunnable)
        val min = Settings.app.ttsSleepMinutes
        if (min <= 0) {
            sleepDeadline = 0L
            return
        }
        if (sleepDeadline == 0L) sleepDeadline = SystemClock.uptimeMillis() + min * 60_000L
        main.postAtTime(sleepRunnable, sleepDeadline)
    }

    private fun cancelSleep() {
        main.removeCallbacks(sleepRunnable)
        sleepDeadline = 0L
    }

    // ------------------------------------------------------------------ control bar

    private fun showBar() {
        if (bar != null) return
        val parent = Overlay.parentOf(host) ?: return
        SearchPanel.clearHighlight(host)
        SearchNavBar.remove()
        val inset = Overlay.bottomInset(host.pageView)
        val row = Overlay.bar(ctx)
        row.setPadding(row.paddingLeft, row.paddingTop, row.paddingRight, inset)
        row.addView(ctx.flatIcon(R.drawable.ic_close, "TTS 끄기") { stop() })
        row.addView(ctx.flatIcon(R.drawable.ic_settings, "TTS 설정") { settingsDialog() })
        val label = ctx.label("", 15f, maxLines = 1).apply { gravity = Gravity.CENTER }
        row.addView(label, lp(0, WRAP_CONTENT, 1f))
        row.addView(ctx.flatIcon(R.drawable.ic_chevron_left, "이전 문장") { jump(-1) })
        row.addView(ctx.flatIcon(R.drawable.ic_chevron_right, "다음 문장") { jump(+1) })
        parent.addView(row, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))

        val play = ctx.roundBlackButton(R.drawable.ic_pause, "재생 / 일시정지", 60) {
            if (playing || pendingPlay) pause() else start()
        }
        val lpPlay = FrameLayout.LayoutParams(ctx.dp(60), ctx.dp(60), Gravity.BOTTOM or Gravity.END).apply {
            rightMargin = ctx.dp(16)
            bottomMargin = ctx.dp(52) + inset + ctx.dp(14)
        }
        parent.addView(play, lpPlay)
        bar = row
        playButton = play
        barLabel = label
    }

    private fun removeBar() {
        bar?.let { (it.parent as? ViewGroup)?.removeView(it) }
        playButton?.let { (it.parent as? ViewGroup)?.removeView(it) }
        bar = null
        playButton = null
        barLabel = null
    }

    private var lastBarText: String? = null
    private var lastPlayIcon = 0

    private fun updateBar() {
        val b = bar ?: return
        val active = playing || pendingPlay
        b.keepScreenOn = active
        val icon = if (active) R.drawable.ic_pause else R.drawable.ic_play_arrow
        if (icon != lastPlayIcon) {
            playButton?.setImageResource(icon)
            lastPlayIcon = icon
        }
        val page = PageLabel.clean(runCatching { host.pageLabel(host.currentPosition()) }.getOrNull())
        val sleep = if (sleepDeadline > 0L) {
            val left = ((sleepDeadline - SystemClock.uptimeMillis()) / 60_000L + 1).coerceAtLeast(1)
            "  ·  ${left}분 후 멈춤"
        } else {
            ""
        }
        val text = page + sleep
        if (text != lastBarText) {
            barLabel?.text = text
            lastBarText = text
        }
    }

    private fun settingsDialog() {
        val app = Settings.app
        val box = ctx.vertical { setPadding(0, ctx.dp(4), 0, ctx.dp(4)) }
        box.addView(ctx.stepperRow("속도", app.ttsRate, 0.5f, 3f, 0.1f, Fmt::rate) { v ->
            Settings.saveApp(Settings.app.copy(ttsRate = v))
            paramsChanged()
        })
        box.addView(ctx.stepperRow("높낮이", app.ttsPitch, 0.5f, 2f, 0.1f, Fmt::rate) { v ->
            Settings.saveApp(Settings.app.copy(ttsPitch = v))
            paramsChanged()
        })
        lateinit var sleepRow: LinearLayout
        sleepRow = ctx.row("수면 타이머", Fmt.minutes(app.ttsSleepMinutes)) {
            val opts = listOf(0, 10, 15, 30, 45, 60, 90, 120)
            ctx.chooser("수면 타이머", opts.map { Fmt.minutes(it) }, opts.indexOf(Settings.app.ttsSleepMinutes).coerceAtLeast(0)) { i ->
                Settings.saveApp(Settings.app.copy(ttsSleepMinutes = opts[i]))
                sleepRow.findViewWithTag<TextView>("summary")?.text = Fmt.minutes(opts[i])
                cancelSleep()
                if (playing) startSleepTimer()
                updateBar()
            }
        }
        box.addView(sleepRow, lp())
        lateinit var voiceRow: LinearLayout
        voiceRow = ctx.row("음성", currentVoiceName()) { chooseVoiceDialog { name -> voiceRow.findViewWithTag<TextView>("summary")?.text = name } }
        box.addView(voiceRow, lp())
        box.addView(ctx.row("TTS 엔진 설정", "시스템 TTS 엔진 · 음성 데이터 설치") {
            TextActions.start(ctx, Intent("com.android.settings.TTS_SETTINGS"))
        }, lp())
        PanelRegistry.dialog(ctx, ctx.alert().setTitle("TTS 설정")
            .setView(ctx.einkScroll(box))
            .setPositiveButton("닫기", null)
            .showNoAnim())
    }

    private fun paramsChanged() {
        main.removeCallbacks(paramsRestart)
        main.postDelayed(paramsRestart, 500)
    }

    private fun currentVoiceName(): String =
        runCatching { tts?.voice?.name }.getOrNull() ?: runCatching { Settings.raw().getString(PREF_VOICE, null) }.getOrNull() ?: "기본 음성"

    private fun chooseVoiceDialog(onChosen: (String) -> Unit) {
        val engine = tts
        if (engine == null || !ready) {
            ctx.toast("TTS를 한 번 재생한 뒤 음성을 고를 수 있습니다")
            return
        }
        val lang = runCatching { engine.voice?.locale?.language }.getOrNull() ?: "ko"
        val voices: List<Voice> = runCatching { engine.voices?.toList() }.getOrNull().orEmpty()
            .filter { it.locale.language == lang }
            .sortedBy { it.name }
        if (voices.isEmpty()) {
            ctx.toast("선택할 수 있는 음성이 없습니다")
            return
        }
        val labels = listOf("기본 음성") + voices.map { v ->
            v.name + (if (v.isNetworkConnectionRequired) " (온라인)" else "")
        }
        val current = runCatching { engine.voice?.name }.getOrNull()
        val sel = voices.indexOfFirst { it.name == current }.let { if (it < 0) 0 else it + 1 }
        ctx.chooser("음성", labels, sel) { i ->
            if (i == 0) {
                runCatching { Settings.raw().edit().remove(PREF_VOICE).apply() }
                runCatching { engine.language = Locale.KOREAN }
                onChosen("기본 음성")
            } else {
                val v = voices[i - 1]
                runCatching { engine.setVoice(v) }
                runCatching { Settings.raw().edit().putString(PREF_VOICE, v.name).apply() }
                onChosen(v.name)
            }
            paramsChanged()
        }
    }

    private companion object {
        const val AHEAD = 3
        /** Consecutive engine errors before giving up (instead of racing through the book). */
        const val MAX_ERRORS = 3
        const val PREF_VOICE = "extras.ttsVoice"
    }
}
