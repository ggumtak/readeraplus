package com.ggumtak.readeraplus.ui.settings

import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.LinearLayout
import com.ggumtak.readeraplus.data.Lookups
import com.ggumtak.readeraplus.data.NotesTab
import com.ggumtak.readeraplus.settings.Settings
import com.ggumtak.readeraplus.ui.kit.confirm
import com.ggumtak.readeraplus.ui.notes.NotesActivity
import com.ggumtak.readeraplus.ui.kit.prompt
import com.ggumtak.readeraplus.ui.kit.row
import com.ggumtak.readeraplus.ui.kit.toast
import com.ggumtak.readeraplus.ui.kit.vertical
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "사전 · 번역 · 웹 검색": web search engine for selected text + installed PROCESS_TEXT apps (info), and the 단어장
 * (whether lookups are recorded, the hub's 단어 tab, clearing it).
 */
internal class LookupPage(a: SettingsActivity) : SettingsPage(a, SettingsActivity.PAGE_LOOKUP, "사전 · 번역 · 웹 검색") {
    private val engineRows = ArrayList<View>()
    private var customRow: View? = null
    private lateinit var appsBox: LinearLayout
    private var recordRow: View? = null
    private var clearing = false

    override fun build(): View {
        val body = ctx.pageBody()
        body.section("웹 검색", first = true)
        body.addView(ctx.note("글자를 길게 눌러 선택한 뒤 '웹 검색'을 누르면 이 사이트에서 찾습니다."))
        val current = Settings.app.webSearchUrl
        val idx = WebEngines.indexOf(current)
        WebEngines.PRESETS.forEachIndexed { i, e ->
            val r = ctx.radioRow(e.name, e.url.substringAfter("://").substringBefore('/'), i == idx) {
                setEngine(e.url)
            }
            engineRows += r
            body.addView(r)
        }
        customRow = ctx.radioRow("사용자 지정", if (idx < 0) current else "검색 주소를 직접 입력 (%s = 검색어)", idx < 0) {
            ctx.prompt("검색 주소", if (WebEngines.indexOf(Settings.app.webSearchUrl) < 0) Settings.app.webSearchUrl else "https://", "https://example.com/search?q=%s") { text ->
                val t = WebEngines.normalizeTemplate(text)
                if (t == null) ctx.toast("주소에 검색어 자리 %s 가 있어야 합니다 (http/https)") else setEngine(t)
            }
        }.also(body::addView)
        body.addView(ctx.row("검색 해보기", "선택한 검색 사이트에서 '전자책'을 찾아봅니다") { testSearch() })

        body.section("사전 · 번역 앱")
        body.addView(ctx.note("선택 메뉴의 '사전·번역'은 아래 앱들로 보냅니다 (글자 처리 기능을 지원하는 앱). 파파고, 구글 번역, 사전 앱 등을 설치하면 여기에 나타납니다."))
        appsBox = ctx.vertical().also(body::addView)
        loadApps()

        // ---- 단어장 (NOTES §11)
        body.section("단어장")
        recordRow = ctx.toggleRow("찾아본 단어 기록", R3Rows.RECORD_LOOKUPS, Settings.app.recordLookups) { v ->
            editApp { it.copy(recordLookups = v) }
        }.also(body::addView)
        body.addView(ctx.navRow("단어장 열기", "독서 노트의 '단어' 탭에서 찾아본 단어와 문장을 봅니다") {
            NotesActivity.open(activity, NotesTab.WORDS)
        })
        body.addView(ctx.row("단어장 비우기", "찾아본 단어 기록을 모두 지웁니다") { clearWords() })
        return ctx.pageScroll(body)
    }

    /** The count is a DB read (IO); the clear runs on IO too. */
    private fun clearWords() {
        if (clearing) return
        activity.scope.launch {
            val n = withContext(Dispatchers.IO) { runCatching { Lookups.count() }.getOrDefault(0) }
            if (n <= 0) {
                ctx.toast("단어장이 비어 있습니다")
                return@launch
            }
            ctx.confirm("단어장 비우기", R3Rows.clearLookups(n), ok = "비우기") {
                clearing = true
                activity.scope.launch {
                    val ok = withContext(Dispatchers.IO) { runCatching { Lookups.clearAll() }.isSuccess }
                    clearing = false
                    ctx.toast(if (ok) "단어장을 비웠습니다" else "단어장을 비우지 못했습니다")
                }
            }
        }
    }

    override fun onShown() {
        updateRadios()
        recordRow?.setToggleChecked(Settings.app.recordLookups)
    }

    private fun setEngine(url: String) {
        editApp { it.copy(webSearchUrl = url) }
        updateRadios()
    }

    private fun updateRadios() {
        val url = Settings.app.webSearchUrl
        val idx = WebEngines.indexOf(url)
        engineRows.forEachIndexed { i, r -> r.setRadioChecked(i == idx) }
        customRow?.setRadioChecked(idx < 0)
        customRow?.setSummary(if (idx < 0) url else "검색 주소를 직접 입력 (%s = 검색어)")
    }

    private fun testSearch() {
        val url = WebEngines.build(Settings.app.webSearchUrl, "전자책")
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            ctx.toast("웹 브라우저가 없습니다")
        }
    }

    private fun loadApps() {
        appsBox.removeAllViews()
        appsBox.addView(ctx.note("불러오는 중…"))
        activity.scope.launch {
            val names = withContext(Dispatchers.IO) {
                runCatching {
                    val pm = activity.packageManager
                    val intent = Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain")
                    @Suppress("DEPRECATION")
                    pm.queryIntentActivities(intent, 0)
                        .filter { it.activityInfo.packageName != activity.packageName }
                        .map { ri ->
                            val label = ri.loadLabel(pm)?.toString().orEmpty()
                            val app = runCatching { ri.activityInfo.applicationInfo.loadLabel(pm).toString() }.getOrDefault("")
                            if (app.isNotEmpty() && app != label) "$label ($app)" else label
                        }
                        .filter { it.isNotBlank() }
                        .distinct()
                        .sorted()
                }.getOrDefault(emptyList())
            }
            appsBox.removeAllViews()
            if (names.isEmpty()) {
                appsBox.addView(ctx.note("설치된 사전 · 번역 앱이 없습니다. 이 경우 웹 검색을 사용합니다."))
            } else {
                for (n in names) appsBox.addView(ctx.row(n, null))
            }
        }
    }
}
