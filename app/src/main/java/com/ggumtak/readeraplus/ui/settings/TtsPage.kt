package com.ggumtak.readeraplus.ui.settings

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
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

/** "Text to speech (TTS)": rate, pitch, sleep timer, engine settings, installed engines and a spoken preview. */
internal class TtsPage(a: SettingsActivity) : SettingsPage(a, SettingsActivity.PAGE_TTS, "Text to speech (TTS)") {
    private var sleepRow: View? = null
    private lateinit var statusText: TextView
    private lateinit var enginesText: TextView
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var enginesGen = 0
    private var ttsGen = 0
    private val main = Handler(Looper.getMainLooper())

    override fun build(): View {
        val app = Settings.app
        val body = ctx.pageBody()
        body.section("음성", first = true)
        body.addView(ctx.stepperRow("속도", app.ttsRate, 0.5f, 2.0f, 0.1f, { SettingsFormat.rate(it) }) { v ->
            editApp { it.copy(ttsRate = v) }
        })
        body.addView(ctx.stepperRow("피치 (음높이)", app.ttsPitch, 0.5f, 2.0f, 0.1f, { SettingsFormat.pitch(it) }) { v ->
            editApp { it.copy(ttsPitch = v) }
        })
        body.addView(ctx.row("미리 듣기", "현재 속도와 피치로 짧은 문장을 읽습니다") { preview() })
        statusText = ctx.note("").apply { visibility = View.GONE }.also(body::addView)

        body.section("수면 타이머")
        sleepRow = ctx.valueRow("수면 타이머", SettingsFormat.sleep(app.ttsSleepMinutes)) {
            val opts = SettingsFormat.SLEEP_OPTIONS
            val sel = opts.indexOf(Settings.app.ttsSleepMinutes).coerceAtLeast(0)
            ctx.chooser("수면 타이머", opts.map { SettingsFormat.sleep(it) }, sel) { i ->
                editApp { it.copy(ttsSleepMinutes = opts[i]) }
                sleepRow?.setSummary(SettingsFormat.sleep(opts[i]))
            }
        }.also(body::addView)
        body.addView(ctx.note("설정한 시간이 지나면 읽기를 멈춥니다. 읽는 동안에는 화면이 꺼지지 않습니다."))

        body.section("TTS 엔진")
        body.addView(ctx.row("TTS 엔진 설정 열기", "기본 엔진 · 한국어 음성 데이터 설치 (시스템 설정)") { openEngineSettings() })
        enginesText = ctx.note("설치된 엔진 확인 중…").also(body::addView)
        body.addView(ctx.note("한국어 음성이 없다면 'Google 음성 인식 및 음성 합성' 또는 삼성 TTS 같은 엔진을 설치한 뒤 한국어 음성 데이터를 받으세요."))
        loadEngines()
        return ctx.pageScroll(body)
    }

    override fun onResume() {
        // Back from the system TTS screen: the default engine or its voices may have changed. Drop the preview
        // instance (it stays bound to the old engine) and list the engines again.
        if (tts != null) releaseTts()
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
        if (tts != null) {
            if (ttsReady) speak() else statusText.text = "음성 엔진 준비 중…"
            return
        }
        statusText.text = "음성 엔진 준비 중…"
        ttsReady = false
        val gen = ++ttsGen
        // onInit may run synchronously inside the constructor (no engine installed) or later; always handle it
        // on a later main-loop turn, when [tts] is assigned, and ignore it if this instance was released.
        tts = TextToSpeech(activity.applicationContext) { status -> main.post { onTtsInit(gen, status) } }
    }

    private fun onTtsInit(gen: Int, status: Int) {
        if (gen != ttsGen || tts == null) return
        if (status == TextToSpeech.SUCCESS) {
            ttsReady = true
            speak()
        } else {
            statusText.text = "TTS 엔진을 시작할 수 없습니다. 엔진 설정을 확인하세요."
            releaseTts()
        }
    }

    private fun speak() {
        val t = tts ?: return
        val app = Settings.app
        val lang = runCatching { t.setLanguage(Locale.KOREAN) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
        t.setSpeechRate(app.ttsRate)
        t.setPitch(app.ttsPitch)
        val r = t.speak(SAMPLE, TextToSpeech.QUEUE_FLUSH, null, "settings-preview")
        statusText.text = when {
            r != TextToSpeech.SUCCESS -> "읽기를 시작하지 못했습니다."
            lang == TextToSpeech.LANG_MISSING_DATA -> "한국어 음성 데이터가 설치되지 않았습니다. 엔진 설정에서 받으세요."
            lang == TextToSpeech.LANG_NOT_SUPPORTED -> "이 엔진은 한국어를 지원하지 않습니다."
            else -> "속도 ${SettingsFormat.rate(app.ttsRate)} · 피치 ${SettingsFormat.pitch(app.ttsPitch)}로 읽는 중"
        }
    }

    override fun onDestroy() {
        releaseTts()
    }

    private fun releaseTts() {
        ttsGen++
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
