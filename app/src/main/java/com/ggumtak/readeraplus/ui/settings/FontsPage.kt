package com.ggumtak.readeraplus.ui.settings

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.TextView
import com.ggumtak.readeraplus.R
import com.ggumtak.readeraplus.render.FontCatalog
import com.ggumtak.readeraplus.render.FontInfo
import com.ggumtak.readeraplus.render.FontManager
import com.ggumtak.readeraplus.render.FontSource
import com.ggumtak.readeraplus.settings.ReaderSettings
import com.ggumtak.readeraplus.settings.Settings
import com.ggumtak.readeraplus.ui.kit.Ink
import com.ggumtak.readeraplus.ui.kit.confirm
import com.ggumtak.readeraplus.ui.kit.dp
import com.ggumtak.readeraplus.ui.kit.horizontal
import com.ggumtak.readeraplus.ui.kit.icon
import com.ggumtak.readeraplus.ui.kit.iconButton
import com.ggumtak.readeraplus.ui.kit.label
import com.ggumtak.readeraplus.ui.kit.lp
import com.ggumtak.readeraplus.ui.kit.pressableBackground
import com.ggumtak.readeraplus.ui.kit.row
import com.ggumtak.readeraplus.ui.kit.toast
import com.ggumtak.readeraplus.ui.kit.vertical
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * "글꼴 관리": every font with a sample in its own face; tap = use for reading, delete user fonts, import .ttf/.otf
 * via SAF, rescan /sdcard/Fonts.
 */
internal class FontsPage(a: SettingsActivity) : SettingsPage(a, SettingsActivity.PAGE_FONTS, "글꼴 관리") {
    private lateinit var listBox: LinearLayout
    private var loadGen = 0

    override fun build(): View {
        val body = ctx.pageBody()
        body.section("글꼴 추가", first = true)
        body.addView(ctx.row("글꼴 파일 추가", ".ttf · .otf · .ttc 파일을 골라 앱에 복사합니다 (여러 개 선택 가능)", ctx.icon(R.drawable.ic_add, 24)) {
            pickFonts()
        })
        body.addView(ctx.row("글꼴 폴더 다시 읽기", "내부 저장소의 Fonts 폴더에 넣은 글꼴을 다시 찾습니다", ctx.icon(R.drawable.ic_refresh, 24)) {
            rescan()
        })
        body.addView(ctx.note("내부 저장소의 'Fonts' 폴더(/sdcard/Fonts)에 글꼴 파일을 넣어 두면 복사하지 않고 바로 쓸 수 있습니다 (모든 파일 접근 권한 필요)."))
        body.section("읽기 글꼴")
        listBox = ctx.vertical().also(body::addView)
        load()
        return ctx.pageScroll(body)
    }

    override fun onShown() {
        // The reading font may have changed in the reader popup meanwhile.
        val shown = shownFontId
        val current = Settings.reader.fontId
        if (shown != null && shown != current) {
            if (rows.containsKey(current)) markSelected(current) else load()
        }
    }

    private var shownFontId: String? = null

    /** Views of one font row, kept so selection and late-loaded typefaces update in place. */
    private class RowViews(val title: TextView, val sample: TextView, val check: View)

    private val rows = LinkedHashMap<String, RowViews>()
    private var missingNote: View? = null

    /**
     * Lists the fonts, then loads their typefaces off the main thread. Faces that load within a short budget are
     * shown with the first draw; the rest are applied in a few batches (each batch = one e-ink update) so a big
     * /sdcard/Fonts folder never blocks the list.
     */
    private fun load() {
        val gen = ++loadGen
        listBox.removeAllViews()
        rows.clear()
        missingNote = null
        listBox.addView(ctx.note("불러오는 중…"))
        activity.scope.launch {
            val fonts = withContext(Dispatchers.IO) { runCatching { FontManager.fonts() }.getOrDefault(emptyList()) }
            if (gen != loadGen) return@launch
            val first = HashMap<String, Typeface>()
            var next = withContext(Dispatchers.IO) { loadFaces(fonts, 0, first, FIRST_BUDGET_MS) }
            if (gen != loadGen) return@launch
            fill(fonts, first)
            while (next < fonts.size) {
                val batch = HashMap<String, Typeface>()
                next = withContext(Dispatchers.IO) { loadFaces(fonts, next, batch, BATCH_BUDGET_MS) }
                if (gen != loadGen) return@launch
                for ((id, face) in batch) rows[id]?.let { it.title.typeface = face; it.sample.typeface = face }
            }
        }
    }

    /** Loads typefaces from [from] until [budgetMs] has passed; returns the index of the next font (blocking). */
    private fun loadFaces(fonts: List<FontInfo>, from: Int, out: MutableMap<String, Typeface>, budgetMs: Long): Int {
        val start = SystemClock.uptimeMillis()
        var i = from
        while (i < fonts.size) {
            val f = fonts[i++]
            runCatching { FontManager.typeface(f.id) }.getOrNull()?.let { out[f.id] = it }
            if (SystemClock.uptimeMillis() - start >= budgetMs) break
        }
        return i
    }

    private fun fill(fonts: List<FontInfo>, faces: Map<String, Typeface>) {
        listBox.removeAllViews()
        rows.clear()
        missingNote = null
        val current = Settings.reader.fontId
        shownFontId = current
        if (fonts.isEmpty()) {
            listBox.addView(ctx.note("글꼴 목록을 읽지 못했습니다."))
            return
        }
        val userDir = File(activity.filesDir, "fonts").absolutePath
        for (f in fonts) listBox.addView(fontRow(f, faces[f.id], f.id == current, f.path.startsWith("$userDir/")))
        if (fonts.none { it.id == current }) {
            val fallback = fonts.firstOrNull { it.id == FontCatalog.DEFAULT_ID }?.name ?: "나눔명조"
            missingNote = ctx.note("현재 글꼴('$current')을 찾을 수 없어 기본 글꼴($fallback)로 표시됩니다.").also(listBox::addView)
        }
    }

    private fun sourceLabel(f: FontInfo, deletable: Boolean): String = when (f.source) {
        FontSource.BUNDLED -> if (f.id == DEFAULT_FONT) "기본 제공 · 기본값" else "기본 제공"
        FontSource.SYSTEM -> "시스템"
        FontSource.USER -> if (deletable) "추가한 글꼴" else "Fonts 폴더"
    }

    private fun fontRow(f: FontInfo, face: Typeface?, selected: Boolean, deletable: Boolean): View {
        val r = ctx.horizontal {
            minimumHeight = ctx.dp(72)
            setPadding(ctx.dp(16), ctx.dp(10), ctx.dp(8), ctx.dp(10))
            background = pressableBackground()
            setOnClickListener { select(f) }
            if (deletable) setOnLongClickListener { delete(f); true }
        }
        val texts = ctx.vertical()
        val title = ctx.label(f.name, 19f, maxLines = 1).apply { face?.let { typeface = it } }
        texts.addView(title)
        val sample = ctx.label("가나다라 한글 글꼴 Aa 123", 16f, maxLines = 1).apply {
            face?.let { typeface = it }
            setPadding(0, ctx.dp(4), 0, 0)
        }
        texts.addView(sample)
        texts.addView(ctx.label(sourceLabel(f, deletable) + (if (f.variable) " · 가변 굵기" else "") + (if (f.boldPath != null) " · 굵은 글꼴 포함" else ""), 13f, color = Ink.GRAY).apply {
            setPadding(0, ctx.dp(4), 0, 0)
        })
        r.addView(texts, lp(0, WRAP_CONTENT, 1f))
        // Always laid out (INVISIBLE when not selected) so moving the check mark never shifts the rows.
        val check = ctx.icon(R.drawable.ic_check, 26).apply {
            contentDescription = "사용 중"
            visibility = if (selected) View.VISIBLE else View.INVISIBLE
        }
        r.addView(check)
        if (deletable) {
            r.addView(ctx.iconButton(R.drawable.ic_delete, "글꼴 삭제") { delete(f) })
        }
        rows[f.id] = RowViews(title, sample, check)
        return r
    }

    /** Moves the check mark without rebuilding the list (keeps the scroll position; one small e-ink update). */
    private fun markSelected(id: String) {
        shownFontId = id
        for ((rid, v) in rows) v.check.visibility = if (rid == id) View.VISIBLE else View.INVISIBLE
        if (rows.containsKey(id)) missingNote?.let { listBox.removeView(it); missingNote = null }
    }

    private fun select(f: FontInfo) {
        if (Settings.reader.fontId == f.id) return
        editReader { it.copy(fontId = f.id) }
        ctx.toast("읽기 글꼴: ${f.name}")
        if (rows.containsKey(f.id)) markSelected(f.id) else load()
    }

    private fun delete(f: FontInfo) {
        ctx.confirm("글꼴 삭제", "'${f.name}' 글꼴 파일을 앱에서 지울까요?", ok = "삭제") {
            activity.scope.launch {
                val ok = withContext(Dispatchers.IO) { runCatching { FontManager.deleteUserFont(f.id) }.isSuccess }
                if (!ok) {
                    ctx.toast("삭제하지 못했습니다")
                    return@launch
                }
                if (Settings.reader.fontId == f.id) editReader { it.copy(fontId = DEFAULT_FONT) }
                ctx.toast("삭제했습니다")
                load()
            }
        }
    }

    private fun rescan() {
        val appCtx = activity.applicationContext
        activity.scope.launch {
            withContext(Dispatchers.IO) { runCatching { FontManager.refresh(appCtx) } }
            load()
            ctx.toast("글꼴 목록을 새로 읽었습니다")
        }
    }

    private fun pickFonts() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        try {
            @Suppress("DEPRECATION")
            activity.startActivityForResult(intent, SettingsActivity.REQ_FONT_IMPORT)
        } catch (_: Exception) {
            ctx.toast("파일 선택 화면을 열 수 없습니다")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != SettingsActivity.REQ_FONT_IMPORT) return false
        if (resultCode != Activity.RESULT_OK || data == null) return true
        val uris = ArrayList<Uri>()
        val clip = data.clipData
        if (clip != null) {
            for (i in 0 until clip.itemCount) clip.getItemAt(i)?.uri?.let(uris::add)
        } else {
            data.data?.let(uris::add)
        }
        if (uris.isNotEmpty()) import(uris)
        return true
    }

    private fun import(uris: List<Uri>) {
        val appCtx = activity.applicationContext
        listBox.removeAllViews()
        listBox.addView(ctx.note("글꼴을 추가하는 중…"))
        activity.scope.launch {
            val added = ArrayList<FontInfo>()
            val failed = ArrayList<String>()
            withContext(Dispatchers.IO) {
                for (u in uris) {
                    runCatching { FontManager.importFont(appCtx, u) }
                        .onSuccess { added += it }
                        .onFailure { e ->
                            val name = u.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':') ?: u.toString()
                            failed += "$name (${ErrorLines.reason(e) ?: "글꼴 파일이 아닙니다"})"
                        }
                }
            }
            load()
            if (failed.isNotEmpty()) {
                ctx.toast("추가하지 못한 파일 ${failed.size}개: ${failed.first()}")
            }
            if (added.size == 1) {
                val f = added[0]
                ctx.confirm("글꼴 추가됨", "'${f.name}' 글꼴로 읽을까요?", ok = "사용") { select(f) }
            } else if (added.size > 1) {
                ctx.toast("글꼴 ${added.size}개를 추가했습니다")
            }
        }
    }

    companion object {
        /** The reading font of a fresh install ("기본값"; also what deleting the current font falls back to). */
        private val DEFAULT_FONT = ReaderSettings().fontId

        /** Typefaces loaded before the list is first drawn (bundled fonts usually all fit). */
        private const val FIRST_BUDGET_MS = 400L
        /** Later batches: one e-ink update per batch. */
        private const val BATCH_BUDGET_MS = 600L
    }
}
