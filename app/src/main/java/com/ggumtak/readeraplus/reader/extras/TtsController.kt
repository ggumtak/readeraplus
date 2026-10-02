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
import com.ggumtak.readeraplus.data.Library
import com.ggumtak.readeraplus.data.ReadingLog
import com.ggumtak.readeraplus.engine.OBJECT_CHAR
import com.ggumtak.readeraplus.format.BookDocument
import com.ggumtak.readeraplus.format.DocPosition
import com.ggumtak.readeraplus.reader.ChapterIndex
import com.ggumtak.readeraplus.reader.ReaderHost
import com.ggumtak.readeraplus.reader.ReaderIo
import com.ggumtak.readeraplus.reader.ReaderFormat
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
import com.ggumtak.readeraplus.ui.kit.switchRow
import com.ggumtak.readeraplus.ui.kit.toast
import com.ggumtak.readeraplus.ui.kit.vertical
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference
import java.util.Locale

/**
 * Text-to-speech: reads from the current page sentence by sentence (a few utterances queued ahead), highlights
 * the spoken sentence (owner "tts"; "읽는 문장 표시" off = no page redraw between turns), turns pages as speech moves
 * on (mid-sentence when the engine reports word ranges), continues into following sections, and shows its own
 * control bar over the reader ([×] [⚙] page [‹] [›] + round play/pause). Handles audio focus, headphone unplugging
 * and the sleep timer (minutes on `elapsedRealtime`, or to the end of this / the next episode).
 *
 * Screen off (T1-11): while a session is active, [TtsService] keeps the process in the foreground with a media
 * notification and holds a wake lock only while speaking; pages keep turning in the background (the reader redraws
 * and refreshes on resume only when the page changed). With the reader in the background the time spent speaking is
 * written to the reading log here ([onReaderPaused] / [onReaderResumed]).
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
    /** Sleep timer by minutes: when to stop, on `SystemClock.elapsedRealtime` (uptime stops in deep sleep); 0 = off. */
    private var sleepDeadline = 0L
    /** Sleep timer by episodes: where the count started (the spoken sentence at play) and how many boundaries. */
    private var sleepFrom: DocPosition? = null
    private var sleepCount = 0
    private var sleepStop: DocPosition? = null
    private var sleepStopDirty = true
    private var sleepLeft = 0
    private var noisyRegistered = false
    /** A voice chooser (or other caller) waiting for the engine to finish initialising. */
    private var afterInit: ((TextToSpeech) -> Unit)? = null

    /** TOC of the spoken document (sleep by episodes, the notification's chapter title); rebuilt per document. */
    private var chapters: ChapterIndex? = null
    private var chaptersDoc: BookDocument? = null
    /** The spoken chapter's range (packed positions) and title, so a sentence start is O(1) when it stays inside. */
    private var chapterFrom = Long.MAX_VALUE
    private var chapterTo = Long.MIN_VALUE
    private var chapterTitle = ""
    /** Last wake-lock renewal (elapsedRealtime). */
    private var lastAwake = 0L

    /** The reader is in the background (between [onReaderPaused] and [onReaderResumed]): TTS counts its own time. */
    private var background = false
    private val spoken = SpeakClock()
    private var focusRequest: AudioFocusRequest? = null

    private val audioAttrs: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    init {
        TtsRegistry.put(host, this)
        TtsBridge.controller = WeakReference(this)
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
            ctx.toast("책을 여는 중입니다")
            return
        }
        session = true
        // A pending "user turned pages" restart must not override the explicitly chosen start.
        main.removeCallbacks(navRestart)
        userMoved = false
        restartEpisodeCount()
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
        if (playing && background) {
            spoken.stop(SystemClock.elapsedRealtime())
            flushSpoken()
        }
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
        TtsService.stop()
    }

    /** Host calls this when the user turned pages manually while TTS is active. */
    fun onUserNavigated() {
        if (!session || selfNav) return
        userMoved = true
        navTries = 0
        main.removeCallbacks(navRestart)
        main.postDelayed(navRestart, 600)
    }

    /**
     * R2 (T1-6 / T1-11): the reader went to the background (ReaderActivity.onPause, after its ReadingTracker closed
     * the page). From here until [onReaderResumed], time spent speaking (from play / pause timestamps) is written to
     * ReadingLog and Library.addReadingTime by this controller, on IO; the reader counts none. Main thread.
     */
    fun onReaderPaused() {
        background = true
        if (playing) spoken.start(SystemClock.elapsedRealtime())
    }

    /** R2: the reader is in front again (ReaderActivity.onResume): flush the background speaking time, stop counting. */
    fun onReaderResumed() {
        if (!background) return
        spoken.stop(SystemClock.elapsedRealtime())
        flushSpoken()
        background = false
    }

    fun release() {
        stop()
        if (background) {
            spoken.stop(SystemClock.elapsedRealtime())
            flushSpoken()
        }
        afterInit = null
        runCatching { tts?.shutdown() }
        tts = null
        ready = false
        initializing = false
        scope.cancel()
        main.removeCallbacksAndMessages(null)
        TtsRegistry.remove(host, this)
        if (TtsBridge.controller() === this) TtsBridge.controller = null
    }

    /**
     * A button of the TTS notification or the media session (headset, lock screen): [TtsService.ACTION_PLAY] …
     * Main thread.
     */
    internal fun onRemote(action: String) {
        when (action) {
            TtsService.ACTION_PLAY -> if (session && !playing && !pendingPlay) start()
            TtsService.ACTION_PAUSE -> if (playing || pendingPlay) pause()
            TtsService.ACTION_NEXT -> if (session) jump(+1)
            TtsService.ACTION_PREV -> if (session) jump(-1)
            TtsService.ACTION_STOP -> stop()
        }
    }

    /** Writes the speaking time counted in the background to the reading log (IO; nothing when there is none). */
    private fun flushSpoken() {
        val t = spoken.take(SystemClock.elapsedRealtime()) ?: return
        val bookId = runCatching { host.book.id }.getOrNull() ?: return
        val day = ReadingLog.day(System.currentTimeMillis())
        ReaderIo.launch {
            ReadingLog.add(bookId, day, t.first, t.second, t.third)
            if (t.first > 0) Library.addReadingTime(bookId, t.first)
        }
    }

    // ------------------------------------------------------------------ engine

    private fun ensureEngine() {
        if (tts != null) {
            if (ready && pendingPlay && loaded) play()
            return
        }
        createEngine()
    }

    private fun createEngine() {
        if (tts != null || initializing) return
        initializing = true
        tts = TextToSpeech(ctx.applicationContext) { status -> main.post { onInit(status) } }
    }

    /** Runs [block] with the ready engine, creating it first when needed (A13: the voice chooser needs no play). */
    private fun withEngine(block: (TextToSpeech) -> Unit) {
        val engine = tts
        if (engine != null && ready) {
            block(engine)
            return
        }
        afterInit = block
        createEngine()
    }

    private fun onInit(status: Int) {
        initializing = false
        val engine = tts ?: return
        if (status != TextToSpeech.SUCCESS) {
            runCatching { engine.shutdown() }
            tts = null
            afterInit = null
            ctx.toast("TTS 엔진을 사용할 수 없습니다. 설정에서 TTS 엔진을 확인하세요.")
            stop()
            return
        }
        ready = true
        engine.setOnUtteranceProgressListener(listener)
        runCatching { engine.setAudioAttributes(audioAttrs) }
        chooseVoice(engine)
        afterInit?.let { cb ->
            afterInit = null
            cb(engine)
        }
        if (pendingPlay && loaded && session) play()
    }

    private fun chooseVoice(engine: TextToSpeech) {
        val saved = runCatching { Settings.app.ttsVoice }.getOrNull()?.takeIf { it.isNotEmpty() }
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

    /** Text of section [sec] (the laid-out one when shown); its EPUB anchors refine the chapter positions. */
    private suspend fun loadText(sec: Int): String? {
        val layout = host.currentLayout
        val content = if (layout != null && host.currentPosition().section == sec) {
            layout.content
        } else {
            val doc = host.document ?: return null
            withContext(Dispatchers.Default) { runCatching { doc.loadSection(sec) }.getOrNull() } ?: return null
        }
        if (content.anchors.isNotEmpty()) chapterIndex()?.let { ch ->
            if (ch.resolveAnchors(sec, content.anchors)) invalidateChapters()
        }
        return content.text
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
        val stopAt = sleepStop()
        if (stopAt != null && enqueued == pos && itemAt(pos)?.let { !before(it, stopAt) } == true) {
            // "이 화 끝까지": the episode's last sentence is done and nothing of the next one was queued.
            sleepNow()
            return
        }
        while (enqueued < base + items.size && enqueued - pos < AHEAD) {
            val u = items[enqueued - base]
            if (stopAt != null && !before(u, stopAt)) break
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

    /** The end of the book: the reader's end panel ("next" on the last page does the same), else a message. */
    private fun finished() {
        stop()
        val end = host as? ReaderEndHost
        if (end != null) runCatching { end.showBookEnd() } else ctx.toast("끝까지 읽었습니다")
    }

    private fun onUttStart(id: String?) {
        val p = UtteranceId.parse(id) ?: return
        if (p[4] != uttGen || !playing) return
        val u = itemAt(p[3]) ?: return
        errorStreak = 0
        pos = u.serial
        trimBefore(u)
        if (sleepDeadline > 0L && SystemClock.elapsedRealtime() >= sleepDeadline) {
            sleepNow()
            return
        }
        if (background) spoken.addChars(u.end - u.start)
        follow(u.sec, u.start)
        highlight(u)
        onChapter(u)
        renewAwake()
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
                if (background) spoken.addPage()
            } else if (page != null && layout != null && (off >= page.end || off < page.start)) {
                val target = layout.pageForOffset(off)
                val turned = if (target == host.currentPageIndex + 1) {
                    host.nextPage()
                } else {
                    host.goTo(DocPosition(sec, off), remember = false)
                    true
                }
                if (turned && background) spoken.addPage()
            }
        } finally {
            selfNav = false
        }
    }

    /** Underlines the spoken sentence ("읽는 문장 표시"; each change redraws the page: off = no update between turns). */
    private fun highlight(u: Utt) {
        if (!Settings.app.ttsHighlight) {
            clearHighlight()
            return
        }
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
            restartEpisodeCount()
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
        if (background) spoken.start(SystemClock.elapsedRealtime())
        startSleepTimer()
        itemAt(pos)?.let { u ->
            follow(u.sec, u.start)
            highlight(u)
            onChapter(u)
        }
        pushService()
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
            if (background) {
                spoken.stop(SystemClock.elapsedRealtime())
                flushSpoken()
            }
        }
        if (abandonFocus) {
            pausedByFocus = false
            abandonFocus()
        }
        pushService()
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
                pausedByFocus = true
                pauseInternal(abandonFocus = false)
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

    /**
     * The minute timer's check. The handler's delay runs on uptime, which stops while the CPU sleeps: the deadline is
     * on elapsedRealtime, re-checked here and at every sentence start, and an early wake-up just waits again.
     */
    private val sleepRunnable: Runnable = object : Runnable {
        override fun run() {
            if (sleepDeadline <= 0L || !playing) return
            val left = sleepDeadline - SystemClock.elapsedRealtime()
            if (left > 500L) main.postDelayed(this, left) else sleepNow()
        }
    }

    /** The sleep timer ran out: pause (the session, its bar and the notification stay; play resumes here). */
    private fun sleepNow() {
        cancelSleep()
        if (playing || pendingPlay) {
            pause()
            ctx.toast("수면 타이머: TTS를 멈췄습니다")
        }
    }

    private fun startSleepTimer() {
        main.removeCallbacks(sleepRunnable)
        val app = Settings.app
        if (app.ttsSleepChapters > 0) {
            sleepDeadline = 0L
            if (sleepFrom == null) {
                sleepFrom = itemAt(pos)?.let { DocPosition(it.sec, it.start) } ?: host.currentPosition()
                sleepCount = app.ttsSleepChapters
                sleepStopDirty = true
                if ((chapterIndex()?.size ?: 0) == 0) ctx.toast("목차가 없어 화 단위 수면 타이머를 쓸 수 없습니다")
            }
            return
        }
        sleepFrom = null
        sleepStop = null
        val min = app.ttsSleepMinutes
        if (min <= 0) {
            sleepDeadline = 0L
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (sleepDeadline == 0L) sleepDeadline = now + min * 60_000L
        main.postDelayed(sleepRunnable, (sleepDeadline - now).coerceAtLeast(0L))
    }

    private fun cancelSleep() {
        main.removeCallbacks(sleepRunnable)
        sleepDeadline = 0L
        sleepFrom = null
        sleepStop = null
        sleepStopDirty = true
        sleepLeft = 0
    }

    /** Speech moves elsewhere (여기서 읽기, pages turned by hand): "이 화 끝까지" counts from where it resumes. */
    private fun restartEpisodeCount() {
        sleepFrom = null
        sleepStop = null
        sleepStopDirty = true
        sleepLeft = 0
    }

    /** Where "이 화 / 2화 끝까지" stops: the start of the [sleepCount]-th episode after [sleepFrom]; null = no stop. */
    private fun sleepStop(): DocPosition? {
        val from = sleepFrom ?: return null
        if (sleepStopDirty) {
            sleepStopDirty = false
            sleepStop = chapterIndex()?.let { TtsChapters.boundary(it, from.section, from.offset, sleepCount) }
        }
        return sleepStop
    }

    private fun before(u: Utt, p: DocPosition): Boolean = u.sec < p.section || (u.sec == p.section && u.start < p.offset)

    // ------------------------------------------------------------------ chapters (notification, sleep by episodes)

    /** The document's TOC as a [ChapterIndex], built on first use per document (not on the open path). */
    private fun chapterIndex(): ChapterIndex? {
        val doc = host.document ?: return null
        if (doc !== chaptersDoc) {
            chaptersDoc = doc
            chapters = runCatching { ChapterIndex(doc.toc, doc.sections.size) }.getOrNull()
            invalidateChapters()
        }
        return chapters
    }

    private fun invalidateChapters() {
        chapterFrom = Long.MAX_VALUE
        chapterTo = Long.MIN_VALUE
        sleepStopDirty = true
    }

    /** The spoken sentence [u] entered another chapter: new title in the notification, the sleep count updated. */
    private fun onChapter(u: Utt) {
        val p = TtsChapters.pack(u.sec, u.start)
        if (p in chapterFrom until chapterTo) return
        val ch = chapterIndex()
        val i = ch?.indexAt(u.sec, u.start) ?: -1
        val next = ch?.nextAfter(u.sec, u.start) ?: -1
        chapterFrom = if (ch != null && i >= 0) TtsChapters.pack(ch.section(i), ch.offset(i)) else Long.MIN_VALUE
        chapterTo = if (ch != null && next >= 0) TtsChapters.pack(ch.section(next), ch.offset(next)) else Long.MAX_VALUE
        val title = if (ch != null && i >= 0) ch.title(i) else ""
        sleepLeft = if (ch != null) TtsChapters.left(ch, u.sec, u.start, sleepStop()) else 0
        if (title != chapterTitle) {
            chapterTitle = title
            pushService()
        }
    }

    // ------------------------------------------------------------------ foreground service (T1-11)

    /** Shows the state in the TTS notification / media session (starts the service on the first play). */
    private fun pushService() {
        if (!session) return
        val b = runCatching { host.book }.getOrNull() ?: return
        TtsBridge.controller = WeakReference(this)
        val active = playing || pendingPlay
        if (active) lastAwake = SystemClock.elapsedRealtime()
        runCatching { TtsService.update(ctx, TtsState(b.id, b.title, chapterTitle, active, awakeMs(), pausedByFocus)) }
    }

    /** Wake-lock timeout: to the sleep deadline (plus a minute), else [TtsService.MAX_AWAKE_MS]. */
    private fun awakeMs(): Long =
        if (sleepDeadline > 0L) (sleepDeadline - SystemClock.elapsedRealtime() + 60_000L).coerceAtLeast(60_000L) else TtsService.MAX_AWAKE_MS

    /** Renews the service's wake lock every [AWAKE_RENEW_MS] of speaking (a sentence start checks the clock only). */
    private fun renewAwake() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastAwake < AWAKE_RENEW_MS) return
        lastAwake = now
        TtsService.keepAwake(awakeMs())
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
        val remaining = if (sleepDeadline > 0L) (sleepDeadline - SystemClock.elapsedRealtime()).coerceAtLeast(1L) else 0L
        val note = SleepChoice.barNote(remaining, if (sleepFrom != null && sleepStop() != null) sleepLeft.coerceAtLeast(1) else 0)
        val text = if (note.isEmpty()) page else "$page  ·  $note"
        if (text != lastBarText) {
            barLabel?.text = text
            lastBarText = text
        }
    }

    private fun settingsDialog() {
        val app = Settings.app
        val box = ctx.vertical { setPadding(0, ctx.dp(4), 0, ctx.dp(4)) }
        box.addView(ctx.stepperRow("속도", app.ttsRate, 0.5f, 3f, 0.1f, ReaderFormat::ttsRate) { v ->
            Settings.saveApp(Settings.app.copy(ttsRate = v))
            paramsChanged()
        })
        box.addView(ctx.stepperRow("음높이", app.ttsPitch, 0.5f, 2f, 0.1f, ReaderFormat::ttsPitch) { v ->
            Settings.saveApp(Settings.app.copy(ttsPitch = v))
            paramsChanged()
        })
        lateinit var sleepRow: LinearLayout
        sleepRow = ctx.row("수면 타이머", SleepChoice.summary(app.ttsSleepMinutes, app.ttsSleepChapters)) {
            val a = Settings.app
            val opts = SleepChoice.OPTIONS
            ctx.chooser("수면 타이머", opts.map { it.label }, SleepChoice.indexOf(a.ttsSleepMinutes, a.ttsSleepChapters)) { i ->
                val o = opts[i]
                // A minutes choice keeps no episode count; an episode choice keeps the minutes for later.
                val next = if (o.chapters > 0) Settings.app.copy(ttsSleepChapters = o.chapters) else Settings.app.copy(ttsSleepMinutes = o.minutes, ttsSleepChapters = 0)
                Settings.saveApp(next)
                sleepRow.findViewWithTag<TextView>("summary")?.text = SleepChoice.summary(next.ttsSleepMinutes, next.ttsSleepChapters)
                cancelSleep()
                if (playing) {
                    startSleepTimer()
                    itemAt(pos)?.let { invalidateChapters(); onChapter(it) }
                    pushService()
                }
                updateBar()
            }
        }
        box.addView(sleepRow, lp())
        box.addView(ctx.switchRow("읽는 문장 표시", "끄면 문장이 바뀔 때 화면을 다시 그리지 않습니다", app.ttsHighlight) { on ->
            Settings.saveApp(Settings.app.copy(ttsHighlight = on))
            if (!on) clearHighlight() else if (session) itemAt(pos)?.let { highlight(it) }
        }, lp())
        lateinit var voiceRow: LinearLayout
        voiceRow = ctx.row("목소리", currentVoiceName()) { chooseVoiceDialog { name -> voiceRow.findViewWithTag<TextView>("summary")?.text = name } }
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

    /** The saved voice's readable name (once the engine lists its voices), else "기본 음성" / "고른 목소리". */
    private fun currentVoiceName(): String {
        val saved = runCatching { Settings.app.ttsVoice }.getOrDefault("")
        if (saved.isEmpty()) return "기본 음성"
        val engine = tts?.takeIf { ready } ?: return "고른 목소리"
        return voiceChoices(engine).firstOrNull { it.first.name == saved }?.second ?: "고른 목소리"
    }

    /** The engine's voices as the chooser lists them: Korean first, then the book's language (A13). */
    private fun voiceChoices(engine: TextToSpeech): List<Pair<Voice, String>> {
        val voices: List<Voice> = runCatching { engine.voices?.toList() }.getOrNull().orEmpty()
        val infos = voices.map { v ->
            VoiceChoice.Info(
                name = v.name,
                lang = v.locale.language.lowercase(Locale.ROOT),
                language = runCatching { v.locale.getDisplayLanguage(Locale.KOREAN) }.getOrDefault(""),
                quality = v.quality,
                network = v.isNetworkConnectionRequired,
                notInstalled = v.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) == true,
            )
        }
        val bookLang = runCatching { host.book.language }.getOrNull()?.takeIf { it.isNotBlank() }
            ?.let { runCatching { Locale.forLanguageTag(it).language }.getOrNull() }
        val byName = voices.associateBy { it.name }
        return VoiceChoice.list(infos, bookLang).mapNotNull { (info, label) -> byName[info.name]?.let { it to label } }
    }

    /** The voice chooser; creates the engine first when TTS has not played yet (A13: no dead end). */
    private fun chooseVoiceDialog(onChosen: (String) -> Unit) {
        withEngine { engine ->
            if (ctx.isFinishing || ctx.isDestroyed) return@withEngine
            val voices = voiceChoices(engine)
            if (voices.isEmpty()) {
                ctx.toast("고를 수 있는 목소리가 없습니다")
                return@withEngine
            }
            val labels = listOf("기본 음성") + voices.map { it.second }
            val current = runCatching { Settings.app.ttsVoice }.getOrDefault("")
            val sel = if (current.isEmpty()) 0 else voices.indexOfFirst { it.first.name == current }.let { if (it < 0) -1 else it + 1 }
            ctx.chooser("목소리", labels, sel) { i ->
                if (i == 0) {
                    Settings.saveApp(Settings.app.copy(ttsVoice = ""))
                    chooseVoice(engine)
                    onChosen("기본 음성")
                } else {
                    val (v, label) = voices[i - 1]
                    runCatching { engine.setVoice(v) }
                    Settings.saveApp(Settings.app.copy(ttsVoice = v.name))
                    onChosen(label)
                }
                paramsChanged()
            }
        }
    }

    private companion object {
        const val AHEAD = 3
        /** Consecutive engine errors before giving up (instead of racing through the book). */
        const val MAX_ERRORS = 3
        /** Speaking this long renews the service's wake lock (its timeout is the sleep deadline or 2 h). */
        const val AWAKE_RENEW_MS = 30 * 60_000L
    }
}
