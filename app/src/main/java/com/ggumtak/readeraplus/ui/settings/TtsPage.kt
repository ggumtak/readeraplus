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
 * "Text to speech (TTS)": rate, pitch, the voice (A13), a spoken preview, "읽는 문장 표시", the sleep timer (minutes or
 * episodes, T1-11), engine settings and the installed engines. The page's own [TextToSpeech] exists only while the
 * page is open, and only once the voice or the preview needs it (or to name a chosen voice); it is released when the
 * page closes and when the activity comes back from the system TTS settings.
 */
internal class TtsPage(a: SettingsActivity) : SettingsPage(a, SettingsActivity.PAGE_TTS, "Text to speech (TTS)") {
    private var sleepRow: View? = null
    private var voiceRow: View? = null
    private lateinit var statusText: TextView
    private lateinit var enginesText: TextView
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
        body.section("음성", first = true)
        body.addView(ctx.stepperRow("속도", app.ttsRate, 0.5f, 3.0f, 0.1f, { SettingsFormat.rate(it) }) { v ->
            editApp { it.copy(ttsRate = v) }
        })
        body.addView(ctx.stepperRow("음높이", app.ttsPitch, 0.5f, 2.0f, 0.1f, { SettingsFormat.pitch(it) }) { v ->
            editApp { it.copy(ttsPitch = v) }
        })
        val voiceName = if (app.ttsVoice.isEmpty()) TtsVoices.DEFAULT else "고른 목소리"
        voiceRow = ctx.valueRow("목소리", voiceName) { chooseVoice() }.also(body::addView)
        body.addView(ctx.row("미리 듣기", "지금 고른 속도 · 음높이 · 목소리로 짧은 문장을 읽습니다") { preview() })
        statusText = ctx.note("").apply { visibility = View.GONE }.also(body::addView)
        val highlightNote = "읽고 있는 문장에 밑줄을 긋습니다. 끄면 문장이 바뀔 때 화면을 다시 그리지 않습니다 (e-ink 절약)"
        body.addView(ctx.toggleRow("읽는 문장 표시", highlightNote, app.ttsHighlight) { v -> editApp { it.copy(ttsHighlight = v) } })
        if (app.ttsVoice.isNotEmpty()) nameVoice()

        body.section("수면 타이머")
        sleepRow = ctx.valueRow("수면 타이머", SettingsFormat.sleepChoice(app.ttsSleepMinutes, app.ttsSleepChapters)) {
            val opts = SettingsFormat.SLEEP_CHOICES
            val cur = Settings.app
            val sel = SettingsFormat.sleepIndex(cur.ttsSleepMinutes, cur.ttsSleepChapters)
            ctx.chooser("수면 타이머", opts.map { (m, c) -> SettingsFormat.sleepChoice(m, c) }, sel) { i ->
                val (m, c) = opts[i]
                editApp { it.copy(ttsSleepMinutes = m, ttsSleepChapters = c) }
                sleepRow?.setSummary(SettingsFormat.sleepChoice(m, c))
            }
        }.also(body::addView)
        body.addView(ctx.note("고른 시간이 지나거나 고른 화가 끝나면 읽기를 멈춥니다. 화면을 꺼도 계속 읽고, 알림에서 멈추거나 다시 재생할 수 있습니다."))

        body.section("TTS 엔진")
        body.addView(ctx.row("TTS 엔진 설정 열기", "기본 엔진 · 한국어 음성 데이터 설치 (시스템 설정)") { openEngineSettings() })
        enginesText = ctx.note("설치된 엔진 확인 중…").also(body::addView)
        body.addView(ctx.note("한국어 음성이 없다면 'Google 음성 인식 및 음성 합성' 또는 삼성 TTS 같은 엔진을 설치한 뒤 한국어 음성 데이터를 받으세요."))
        loadEngines()
        return ctx.pageScroll(body)
    }

    override fun onResume() {
        // Back from the system TTS screen: the default engine or its voices may have changed. Drop the page's
        // instance (it stays bound to the old engine) and list the engines again.
        if (tts != null) {
            releaseTts()
            if (Settings.app.ttsVoice.isNotEmpty()) nameVoice()
        }
        if (::enginesText.isInitialized) loadEngines()
    }

    private fun loadEngines() {
        val gen = ++enginesGen
        activity.scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    val pm = activity.packageManager
                    @Suppress("DEPRECATION")
                    val services = pm.queryIntentServices(Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), PackageManager.GET_META_DATA)
                    val def = runCatching { SystemSettings.Secure.getString(activity.contentResolver, SystemSettings.Secure.TTS_DEFAULT_SYNTH) }.getOrNull()
                    if (services.isEmpty()) {
                        "설치된 TTS 엔진이 없습니다."
                    } else {
                        "설치된 엔진:\n" + services.joinToString("\n") { ri ->
                            val name = ri.loadLabel(pm)?.toString() ?: ri.serviceInfo.packageName
                            "· $name" + if (ri.serviceInfo.packageName == def) " (기본)" else ""
                        }
                    }
                }.getOrDefault("엔진 목록을 읽지 못했습니다.")
            }
            if (gen == enginesGen && enginesText.text != text) enginesText.text = text
        }
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
        ctx.toast("TTS 설정 화면을 열 수 없습니다")
    }

    private fun preview() {
        statusText.visibility = View.VISIBLE
        if (!ttsReady) statusText.text = "음성 엔진 준비 중…"
        withEngine { speak(it) }
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
            val work = pending.toList()
            pending.clear()
            for (f in work) f(t)
        } else {
            statusText.visibility = View.VISIBLE
            statusText.text = "TTS 엔진을 시작할 수 없습니다. 엔진 설정을 확인하세요."
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
        statusText.visibility = View.VISIBLE
        statusText.text = when {
            r != TextToSpeech.SUCCESS -> "읽기를 시작하지 못했습니다."
            lang == TextToSpeech.LANG_MISSING_DATA -> "한국어 음성 데이터가 설치되지 않았습니다. 엔진 설정에서 받으세요."
            lang == TextToSpeech.LANG_NOT_SUPPORTED -> "이 엔진은 한국어를 지원하지 않습니다."
            else -> "읽는 중: 속도 ${SettingsFormat.rate(app.ttsRate)} · 음높이 ${SettingsFormat.pitch(app.ttsPitch)}"
        }
    }

    // ---------------------------------------------------------------- voice (A13)

    /** The saved voice ([com.ggumtak.readeraplus.settings.AppSettings.ttsVoice]) if this engine has it. */
    private fun savedVoice(t: TextToSpeech): Voice? {
        val name = Settings.app.ttsVoice.ifEmpty { return null }
        return runCatching { t.voices?.firstOrNull { it.name == name } }.getOrNull()
    }

    /** The engine's voices as the chooser lists them: Korean first, readable names ([TtsVoices]). */
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
        val byName = voices.associateBy { it.name }
        return TtsVoices.list(infos).mapNotNull { (info, label) -> byName[info.name]?.let { it to label } }
    }

    /** Shows the saved voice's readable name on its row (needs the engine's voice list). */
    private fun nameVoice() {
        withEngine { t ->
            val name = Settings.app.ttsVoice
            val label = when {
                name.isEmpty() -> TtsVoices.DEFAULT
                else -> voiceChoices(t).firstOrNull { it.first.name == name }?.second ?: "이 엔진에 없는 목소리 (기본 음성으로 읽음)"
            }
            voiceRow?.setSummary(label)
        }
    }

    /** "목소리": 기본 음성 + the engine's voices; a choice is saved and read aloud at once. */
    private fun chooseVoice() {
        if (!ttsReady) {
            statusText.visibility = View.VISIBLE
            statusText.text = "목소리 목록을 불러오는 중…"
        }
        withEngine { t ->
            if (activity.isFinishing || activity.isDestroyed) return@withEngine
            val voices = voiceChoices(t)
            if (voices.isEmpty()) {
                statusText.visibility = View.VISIBLE
                statusText.text = "이 엔진에는 고를 수 있는 목소리가 없습니다."
                return@withEngine
            }
            statusText.visibility = View.GONE
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
