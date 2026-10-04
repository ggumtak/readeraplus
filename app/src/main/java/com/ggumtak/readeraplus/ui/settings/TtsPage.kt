package com.ggumtak.readeraplus.ui.settings

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.view.View
import android.widget.TextView
import com.ggumtak.readeraplus.settings.Settings
import com.ggumtak.readeraplus.ui.kit.chooser
import com.ggumtak.readeraplus.ui.kit.row
import com.ggumtak.readeraplus.ui.kit.stepperRow
import com.ggumtak.readeraplus.ui.kit.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import android.provider.Settings as SystemSettings

/**
 * "듣기 설정": 음성 (the voice (A13), rate, pitch and a spoken preview), 읽는 동안 ("읽는 문장 표시" and 멈춤 예약 by
 * minutes or chapters, T1-11) and 음성 엔진 (the system's engine settings, named after the default engine). The page's
 * own [TextToSpeech] exists only while the page is open, and only once the voice or the preview needs it (or to name a
 * chosen voice); it is released when the page closes and when the activity comes back from the system TTS settings.
 * The line under the preview shows failures only, and goes at the next tap.
 */
internal class TtsPage(a: SettingsActivity) : SettingsPage(a, SettingsActivity.PAGE_TTS, "듣기 설정") {
    private var sleepRow: View? = null
    private var voiceRow: View? = null
    private var engineRow: View? = null
    private lateinit var statusText: TextView
    private lateinit var engineHint: TextView
    /** From the last engine list: no engine installed (null until read). */
    private var noEngine: Boolean? = null
    /** The page's engine has no Korean voice (learned once it is up). */
    private var noKorean = false
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    /** Work waiting for the engine's onInit. */
    private val pending = ArrayList<(TextToSpeech) -> Unit>()
    private var enginesGen = 0
    private var ttsGen = 0
    private val main = Handler(Looper.getMainLooper())

    override fun build(): View {
        val app = Settings.app
        val body = ctx.pageBody()
        body.section("음성")
        voiceRow = ctx.valueRow("목소리", if (app.ttsVoice.isEmpty()) TtsVoices.DEFAULT else "…") { chooseVoice() }.also(body::addView)
        body.addView(ctx.stepperRow("속도", app.ttsRate, 0.5f, 3.0f, 0.1f, { SettingsFormat.rate(it) }) { v ->
            editApp { it.copy(ttsRate = v) }
        }.liveStepperValue())
        body.addView(ctx.stepperRow("음높이", app.ttsPitch, 0.5f, 2.0f, 0.1f, { SettingsFormat.pitch(it) }) { v ->
            editApp { it.copy(ttsPitch = v) }
        }.liveStepperValue())
        body.addView(ctx.row("미리 듣기", null) { preview() })
        statusText = ctx.note("").apply { visibility = View.GONE }.also(body::addView)
        if (app.ttsVoice.isNotEmpty()) nameVoice()

        body.section("읽는 동안")
        body.addView(ctx.toggleRow("읽는 문장 표시", "읽는 문장에 밑줄 · 끄면 화면 갱신이 줄어듭니다", app.ttsHighlight) { v ->
            editApp { it.copy(ttsHighlight = v) }
        })
        sleepRow = ctx.valueRow("멈춤 예약", SettingsFormat.sleepChoice(app.ttsSleepMinutes, app.ttsSleepChapters)) {
            val opts = SettingsFormat.SLEEP_CHOICES
            val cur = Settings.app
            val sel = SettingsFormat.sleepIndex(cur.ttsSleepMinutes, cur.ttsSleepChapters)
            ctx.chooser("멈춤 예약", opts.map { (m, c) -> SettingsFormat.sleepChoice(m, c) }, sel) { i ->
                val (m, c) = opts[i]
                editApp { it.copy(ttsSleepMinutes = m, ttsSleepChapters = c) }
                sleepRow?.setSummary(SettingsFormat.sleepChoice(m, c))
            }
        }.also(body::addView)
        body.addView(ctx.note("화면을 꺼도 계속 읽고, 알림에서 멈출 수 있습니다."))

        body.section("음성 엔진")
        engineRow = ctx.navRow("음성 엔진 설정", "…") { openEngineSettings() }.also(body::addView)
        engineHint = ctx.note("한국어 음성이 없으면 음성 엔진 설정에서 받으세요.").apply { visibility = View.GONE }.also(body::addView)
        loadEngines()
        return ctx.pageScroll(body)
    }

    override fun onResume() {
        // Back from the system TTS screen: the default engine or its voices may have changed. Drop the page's
        // instance (it stays bound to the old engine) and list the engines again.
        if (tts != null) {
            releaseTts()
            noKorean = false
            if (Settings.app.ttsVoice.isNotEmpty()) nameVoice()
        }
        if (::engineHint.isInitialized) loadEngines()
    }

    /** The default engine's name on the 음성 엔진 설정 row (a package query on IO). */
    private fun loadEngines() {
        val gen = ++enginesGen
        activity.scope.launch {
            val name = withContext(Dispatchers.IO) {
                runCatching {
                    val pm = activity.packageManager
                    @Suppress("DEPRECATION")
                    val services = pm.queryIntentServices(Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), PackageManager.GET_META_DATA)
                    val def = runCatching { SystemSettings.Secure.getString(activity.contentResolver, SystemSettings.Secure.TTS_DEFAULT_SYNTH) }.getOrNull()
                    val ri = services.firstOrNull { it.serviceInfo.packageName == def } ?: services.firstOrNull()
                    ri?.let { it.loadLabel(pm)?.toString() ?: it.serviceInfo.packageName } ?: ""
                }.getOrNull()
            }
            if (gen != enginesGen) return@launch
            noEngine = name?.isEmpty()
            engineRow?.setSummary(when {
                name == null -> "확인하지 못했습니다"
                name.isEmpty() -> "설치된 음성 엔진 없음"
                else -> name
            })
            updateEngineHint()
        }
    }

    /** "한국어 음성이 없으면 …": only while there is no engine, or the engine has no Korean voice. */
    private fun updateEngineHint() {
        engineHint.setShown(noEngine == true || noKorean)
    }

    private fun openEngineSettings() {
        val intents = listOf(
            Intent("com.android.settings.TTS_SETTINGS"),
            Intent(SystemSettings.ACTION_SETTINGS),
        )
        for (i in intents) {
            try {
                activity.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION))
                return
            } catch (_: Exception) {
                // try the next one
            }
        }
        ctx.toast("음성 엔진 설정을 열 수 없습니다")
    }

    private fun preview() {
        showStatus(null)
        withEngine { speak(it) }
    }

    /** The failure line under 미리 듣기; null hides it. */
    private fun showStatus(text: String?) {
        if (text != null) statusText.text = text
        statusText.setShown(text != null)
    }

    /** Runs [f] with the page's engine, creating it first (onInit arrives later; [f] waits for it). */
    private fun withEngine(f: (TextToSpeech) -> Unit) {
        val t = tts
        if (t != null) {
            if (ttsReady) f(t) else pending += f
            return
        }
        ttsReady = false
        pending.clear()
        pending += f
        val gen = ++ttsGen
        // onInit may run synchronously inside the constructor (no engine installed) or later; always handle it
        // on a later main-loop turn, when [tts] is assigned, and ignore it if this instance was released.
        tts = TextToSpeech(activity.applicationContext) { status -> main.post { onTtsInit(gen, status) } }
    }

    private fun onTtsInit(gen: Int, status: Int) {
        val t = tts
        if (gen != ttsGen || t == null) return
        if (status == TextToSpeech.SUCCESS) {
            ttsReady = true
            noKorean = runCatching { t.voices?.none { it.locale.language.lowercase(Locale.ROOT) == "ko" } }.getOrNull() == true
            updateEngineHint()
            val work = pending.toList()
            pending.clear()
            for (f in work) f(t)
        } else {
            showStatus(if (noEngine == true) "설치된 음성 엔진이 없습니다" else "음성 엔진을 시작할 수 없습니다")
            releaseTts()
        }
    }

    private fun speak(t: TextToSpeech) {
        val app = Settings.app
        val voice = savedVoice(t)
        val lang = if (voice != null) {
            if (runCatching { t.setVoice(voice) }.getOrDefault(TextToSpeech.ERROR) == TextToSpeech.SUCCESS) {
                TextToSpeech.LANG_AVAILABLE
            } else {
                runCatching { t.setLanguage(Locale.KOREAN) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
            }
        } else {
            runCatching { t.setLanguage(Locale.KOREAN) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
        }
        t.setSpeechRate(app.ttsRate)
        t.setPitch(app.ttsPitch)
        val r = t.speak(SAMPLE, TextToSpeech.QUEUE_FLUSH, null, "settings-preview")
        showStatus(when {
            r != TextToSpeech.SUCCESS -> "읽기를 시작하지 못했습니다"
            lang == TextToSpeech.LANG_MISSING_DATA -> "한국어 음성이 없습니다. 음성 엔진 설정에서 받으세요."
            lang == TextToSpeech.LANG_NOT_SUPPORTED -> "이 음성 엔진은 한국어를 읽지 못합니다."
            else -> null
        })
    }

    // ---------------------------------------------------------------- voice (A13)

    /** The saved voice ([com.ggumtak.readeraplus.settings.AppSettings.ttsVoice]) if this engine has it. */
    private fun savedVoice(t: TextToSpeech): Voice? {
        val name = Settings.app.ttsVoice.ifEmpty { return null }
        return runCatching { t.voices?.firstOrNull { it.name == name } }.getOrNull()
    }

    /** The engine's voices as the chooser lists them ([TtsVoices]): Korean and the saved voice's language. */
    private fun voiceChoices(t: TextToSpeech): List<Pair<Voice, String>> {
        val voices: List<Voice> = runCatching { t.voices?.toList() }.getOrNull().orEmpty()
        val infos = voices.map { v ->
            TtsVoices.Info(
                name = v.name,
                lang = v.locale.language.lowercase(Locale.ROOT),
                language = runCatching { v.locale.getDisplayLanguage(Locale.KOREAN) }.getOrDefault(""),
                quality = v.quality,
                network = v.isNetworkConnectionRequired,
                notInstalled = v.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) == true,
            )
        }
        val saved = Settings.app.ttsVoice
        val keep = infos.firstOrNull { it.name == saved }?.lang
        val byName = voices.associateBy { it.name }
        return TtsVoices.list(infos, keep).mapNotNull { (info, label) -> byName[info.name]?.let { it to label } }
    }

    /** Shows the saved voice's readable name on its row (needs the engine's voice list). */
    private fun nameVoice() {
        withEngine { t ->
            val name = Settings.app.ttsVoice
            val label = when {
                name.isEmpty() -> TtsVoices.DEFAULT
                else -> voiceChoices(t).firstOrNull { it.first.name == name }?.second ?: "없는 목소리 · 기본 목소리로 읽음"
            }
            voiceRow?.setSummary(label)
        }
    }

    /** "목소리": 기본 목소리 + the engine's voices; a choice is saved and read aloud at once. */
    private fun chooseVoice() {
        showStatus(null)
        withEngine { t ->
            if (activity.isFinishing || activity.isDestroyed) return@withEngine
            val voices = voiceChoices(t)
            if (voices.isEmpty()) {
                showStatus("이 음성 엔진에는 고를 수 있는 목소리가 없습니다")
                return@withEngine
            }
            val current = Settings.app.ttsVoice
            val sel = if (current.isEmpty()) 0 else voices.indexOfFirst { it.first.name == current }.let { if (it < 0) -1 else it + 1 }
            ctx.chooser("목소리", listOf(TtsVoices.DEFAULT) + voices.map { it.second }, sel) { i ->
                val name = if (i == 0) "" else voices[i - 1].first.name
                editApp { it.copy(ttsVoice = name) }
                voiceRow?.setSummary(if (i == 0) TtsVoices.DEFAULT else voices[i - 1].second)
                // The page's engine may have been renewed while the chooser was open (onResume).
                withEngine { speak(it) }
            }
        }
    }

    override fun onDestroy() {
        releaseTts()
    }

    private fun releaseTts() {
        ttsGen++
        pending.clear()
        tts?.let {
            runCatching { it.stop() }
            runCatching { it.shutdown() }
        }
        tts = null
        ttsReady = false
    }

    companion object {
        private const val SAMPLE = "안녕하세요. 지금 들리는 목소리의 빠르기와 높이로 책을 읽어 드립니다."
    }
}
