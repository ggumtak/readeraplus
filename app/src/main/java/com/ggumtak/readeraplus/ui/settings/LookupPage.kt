package com.ggumtak.readeraplus.ui.settings

import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.ggumtak.readeraplus.data.Lookups
import com.ggumtak.readeraplus.reader.extras.LookupList
import com.ggumtak.readeraplus.reader.extras.TextActions
import com.ggumtak.readeraplus.settings.Settings
import com.ggumtak.readeraplus.ui.kit.confirm
import com.ggumtak.readeraplus.ui.kit.prompt
import com.ggumtak.readeraplus.ui.kit.row
import com.ggumtak.readeraplus.ui.kit.toast
import com.ggumtak.readeraplus.ui.kit.vertical
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "사전·번역·검색": the web search site for selected text (a preset or a typed address), the entries the selection
 * menu's "사전·번역" can list (the Naver Dictionary app when installed and every PROCESS_TEXT app, one toggle each; the
 * order is fixed by name and "웹 검색" is always last), and the 단어장 (whether lookups are recorded, clearing it; the
 * notes open it from the drawer and the reader's ⋮).
 */
internal class LookupPage(a: SettingsActivity) : SettingsPage(a, SettingsActivity.PAGE_LOOKUP, "사전·번역·검색") {
    private val engineRows = ArrayList<View>()
    private var customRow: View? = null
    private lateinit var appsText: TextView
    private lateinit var appsBox: LinearLayout
    private var recordRow: View? = null
    private var clearing = false
    /** Left the stack: a count that arrives later shows no dialog. */
    private var destroyed = false

    override fun onDestroy() {
        destroyed = true
    }

    override fun build(): View {
        val body = ctx.pageBody()
        body.section("웹 검색")
        val current = Settings.app.webSearchUrl
        val idx = WebEngines.indexOf(current)
        WebEngines.PRESETS.forEachIndexed { i, e ->
            val r = ctx.radioRow(e.name, null, i == idx) { setEngine(e.url) }
            engineRows += r
            body.addView(r)
        }
        // The typed address shows under 직접 입력 while it is the one in use.
        customRow = ctx.radioRow(WebEngines.CUSTOM, if (idx < 0) current else "", idx < 0) {
            ctx.prompt("검색 주소", if (WebEngines.indexOf(Settings.app.webSearchUrl) < 0) Settings.app.webSearchUrl else "https://", "https://example.com/search?q=%s") { text ->
                val t = WebEngines.normalizeTemplate(text)
                if (t == null) ctx.toast("주소에 검색어 자리(%s)를 넣어 주세요") else setEngine(t)
            }
        }.also(body::addView)
        body.addView(ctx.row("검색해 보기", null) { testSearch() })
        updateRadios()

        body.section("사전·번역 목록")
        body.addView(ctx.note("선택 메뉴의 ‘사전·번역’에 보일 항목입니다. 순서는 이름순으로 고정됩니다. ‘웹 검색’은 항상 맨 아래에 있습니다."))
        appsText = ctx.note("불러오는 중…").also(body::addView)
        appsBox = ctx.vertical().also(body::addView)
        loadApps()

        // ---- 단어장 (NOTES §11)
        body.section("단어장")
        recordRow = ctx.toggleRow("찾아본 단어 기록", R3Rows.RECORD_LOOKUPS, Settings.app.recordLookups) { v ->
            editApp { it.copy(recordLookups = v) }
        }.also(body::addView)
        body.addView(ctx.row("단어장 비우기", null) { clearWords() })
        return ctx.pageScroll(body)
    }

    /** The count is a DB read (IO); the clear runs on IO too. */
    private fun clearWords() {
        if (clearing) return
        activity.scope.launch {
            val n = withContext(Dispatchers.IO) { runCatching { Lookups.count() }.getOrDefault(0) }
            if (destroyed) return@launch
            if (n <= 0) {
                ctx.toast("단어장이 비어 있습니다")
                return@launch
            }
            ctx.confirm("단어장 비우기", R3Rows.clearLookups(n), ok = "지우기") {
                if (clearing) return@confirm
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
        customRow?.let { row ->
            row.setRadioChecked(idx < 0)
            row.setSummary(if (idx < 0) url else "")
            row.findViewWithTag<View>("summary")?.setShown(idx < 0)
        }
    }

    private fun testSearch() {
        val url = WebEngines.build(Settings.app.webSearchUrl, "전자책")
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            ctx.toast("웹 브라우저가 없습니다")
        }
    }

    /** One toggle per entry (checked = shown); the list and the first-use hidden set are read on IO. */
    private fun loadApps() {
        activity.scope.launch {
            val (entries, hidden) = withContext(Dispatchers.IO) {
                runCatching {
                    val all = TextActions.lookupEntries(activity)
                    all to TextActions.hiddenEntries(all)
                }.getOrDefault(emptyList<LookupList.Entry>() to emptySet())
            }
            if (destroyed) return@launch
            appsText.text = "설치된 앱이 없습니다. ‘웹 검색’만 보입니다."
            appsText.setShown(entries.isEmpty())
            val off = HashSet(hidden)
            for (e in entries) {
                appsBox.addView(ctx.toggleRow(LookupList.title(e), null, e.key !in off) { shown ->
                    if (shown) off.remove(e.key) else off.add(e.key)
                    TextActions.setHiddenEntries(off)
                })
            }
        }
    }
}
