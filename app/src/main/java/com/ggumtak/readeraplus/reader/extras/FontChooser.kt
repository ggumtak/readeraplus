package com.ggumtak.readeraplus.reader.extras

import android.app.Activity
import android.app.Fragment
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.ggumtak.readeraplus.R
import com.ggumtak.readeraplus.reader.ReaderHost
import com.ggumtak.readeraplus.render.FontInfo
import com.ggumtak.readeraplus.render.FontManager
import com.ggumtak.readeraplus.render.FontSource
import com.ggumtak.readeraplus.settings.Settings
import com.ggumtak.readeraplus.ui.kit.Ink
import com.ggumtak.readeraplus.ui.kit.alert
import com.ggumtak.readeraplus.ui.kit.dp
import com.ggumtak.readeraplus.ui.kit.horizontal
import com.ggumtak.readeraplus.ui.kit.icon
import com.ggumtak.readeraplus.ui.kit.label
import com.ggumtak.readeraplus.ui.kit.lp
import com.ggumtak.readeraplus.ui.kit.pressableBackground
import com.ggumtak.readeraplus.ui.kit.showNoAnim
import com.ggumtak.readeraplus.ui.kit.toast
import com.ggumtak.readeraplus.ui.kit.vertical
import com.ggumtak.readeraplus.ui.settings.SettingsActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "폰트 페이스" chooser: every font rendered in its own typeface with a sample line, plus "폰트 추가…"
 * (SAF import through [FontImportFragment]) and a shortcut to the font management page.
 */
internal object FontChooser {
    private const val SAMPLE = "가나다 한글 Aa 123"
    @Volatile private var loading = false

    fun show(activity: Activity, currentId: String, onPick: (String) -> Unit) {
        if (loading) return
        loading = true
        val scope = MainScope()
        scope.launch {
            // Name tables and typefaces are loaded off the main thread; the dialog opens once, fully drawn.
            val entries = withContext(Dispatchers.IO) {
                val fonts = runCatching { FontManager.fonts() }.getOrDefault(emptyList())
                fonts.map { f -> f to runCatching { FontManager.typeface(f.id) }.getOrNull() }
            }
            loading = false
            scope.cancel()
            if (activity.isFinishing || activity.isDestroyed) return@launch
            showDialog(activity, entries, currentId, onPick)
        }
    }

    private fun showDialog(activity: Activity, entries: List<Pair<FontInfo, Typeface?>>, currentId: String, onPick: (String) -> Unit) {
        val adapter = object : BaseAdapter() {
            override fun getCount() = entries.size
            override fun getItem(position: Int) = entries[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val row = (convertView as? LinearLayout) ?: buildRow(activity)
                val (font, tf) = entries[position]
                val name = row.findViewWithTag<TextView>("name")
                val sample = row.findViewWithTag<TextView>("sample")
                val src = row.findViewWithTag<TextView>("src")
                val check = row.findViewWithTag<ImageView>("check")
                name.text = font.name
                name.typeface = tf ?: Typeface.DEFAULT
                sample.text = SAMPLE
                sample.typeface = tf ?: Typeface.DEFAULT
                src.text = when (font.source) {
                    FontSource.BUNDLED -> "기본"
                    FontSource.USER -> "사용자"
                    FontSource.SYSTEM -> "시스템"
                }
                val selected = font.id == currentId
                check.setImageResource(if (selected) R.drawable.ic_radio_button_checked else R.drawable.ic_radio_button_unchecked)
                name.paint.isFakeBoldText = selected
                return row
            }
        }
        val dialog = activity.alert()
            .setTitle("폰트 페이스")
            .setAdapter(adapter) { d, which ->
                d.dismiss()
                onPick(entries[which].first.id)
            }
            .setNeutralButton("폰트 추가…") { _, _ -> FontImportFragment.start(activity, onPick) }
            .setPositiveButton("글꼴 관리") { _, _ -> SettingsActivity.open(activity, SettingsActivity.PAGE_FONTS) }
            .setNegativeButton("닫기", null)
            .showNoAnim()
        dialog.listView?.apply {
            selector = ColorDrawable(Color.TRANSPARENT)
            divider = ColorDrawable(Ink.DISABLED)
            dividerHeight = 1
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalFadingEdgeEnabled = false
            val idx = entries.indexOfFirst { it.first.id == currentId }
            if (idx > 2) setSelection(idx - 2)
        }
    }

    private fun buildRow(activity: Activity): LinearLayout = activity.horizontal {
        background = pressableBackground()
        minimumHeight = activity.dp(64)
        setPadding(activity.dp(20), activity.dp(10), activity.dp(16), activity.dp(10))
        val texts = activity.vertical()
        val top = activity.horizontal()
        top.addView(activity.label("", 20f, maxLines = 1).apply { tag = "name" }, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(activity.label("", 12f, color = Ink.GRAY).apply { tag = "src"; setPadding(activity.dp(8), 0, 0, 0) })
        texts.addView(top, lp())
        texts.addView(activity.label("", 15f, color = Ink.GRAY, maxLines = 1).apply { tag = "sample"; setPadding(0, activity.dp(4), 0, 0) })
        addView(texts, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(activity.icon(R.drawable.ic_radio_button_unchecked, 22).apply {
            tag = "check"
            (layoutParams as LinearLayout.LayoutParams).leftMargin = activity.dp(12)
        })
    }

    /** Imports [uri] and reports the new font id (main thread). */
    internal fun importFont(activity: Activity, uri: Uri, onPick: ((String) -> Unit)?) {
        val scope = MainScope()
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { FontManager.importFont(activity.applicationContext, uri) } }
            scope.cancel()
            if (activity.isDestroyed) return@launch
            result.onSuccess { info ->
                activity.toast("'${info.name}' 폰트를 추가했습니다")
                if (onPick != null) {
                    onPick(info.id)
                } else {
                    (activity as? ReaderHost)?.applySettings(Settings.reader.copy(fontId = info.id))
                }
            }.onFailure { e ->
                activity.toast("폰트를 추가할 수 없습니다" + (e.message?.let { ": $it" } ?: ""))
            }
        }
    }
}

/**
 * Headless platform fragment that runs the SAF font picker and receives its result, so the reader activity
 * doesn't need to forward onActivityResult. Public with a no-arg constructor (framework re-instantiation).
 */
@Suppress("DEPRECATION")
class FontImportFragment : Fragment() {
    private var launched = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        launched = savedInstanceState?.getBoolean("launched") ?: false
        if (!launched) {
            launched = true
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*")
                .putExtra(
                    Intent.EXTRA_MIME_TYPES,
                    arrayOf("font/ttf", "font/otf", "font/sfnt", "font/collection", "application/x-font-ttf",
                        "application/x-font-otf", "application/font-sfnt", "application/vnd.ms-opentype", "application/octet-stream"),
                )
            try {
                startActivityForResult(intent, REQ)
            } catch (_: Exception) {
                pending = null
                activity?.toast("파일 선택기를 열 수 없습니다")
                finish()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("launched", launched)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != REQ) return
        val act = activity
        val uri = data?.data
        val cb = pending
        pending = null
        if (act != null && resultCode == Activity.RESULT_OK && uri != null) FontChooser.importFont(act, uri, cb)
        finish()
    }

    private fun finish() {
        runCatching { fragmentManager?.beginTransaction()?.remove(this)?.commitAllowingStateLoss() }
    }

    companion object {
        private const val REQ = 0x7F01
        private const val TAG = "readeraplus.fontimport"
        /** Callback of the running import (captures the settings popup); cleared on every exit path. */
        private var pending: ((String) -> Unit)? = null

        fun start(activity: Activity, onPick: (String) -> Unit) {
            pending = onPick
            val fm = activity.fragmentManager
            fm.findFragmentByTag(TAG)?.let { old -> runCatching { fm.beginTransaction().remove(old).commitNowAllowingStateLoss() } }
            runCatching { fm.beginTransaction().add(FontImportFragment(), TAG).commitAllowingStateLoss() }
                .onFailure {
                    pending = null
                    activity.toast("파일 선택기를 열 수 없습니다")
                }
        }
    }
}
