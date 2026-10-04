package com.ggumtak.readeraplus.reader.extras

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.ggumtak.readeraplus.R
import com.ggumtak.readeraplus.settings.Settings
import com.ggumtak.readeraplus.ui.kit.Ink
import com.ggumtak.readeraplus.ui.kit.InkPager
import com.ggumtak.readeraplus.ui.kit.InkPagerBar
import com.ggumtak.readeraplus.ui.kit.alert
import com.ggumtak.readeraplus.ui.kit.borderBox
import com.ggumtak.readeraplus.ui.kit.confirm
import com.ggumtak.readeraplus.ui.kit.dp
import com.ggumtak.readeraplus.ui.kit.einkListView
import com.ggumtak.readeraplus.ui.kit.fullScreenDialog
import com.ggumtak.readeraplus.ui.kit.hairline
import com.ggumtak.readeraplus.ui.kit.horizontal
import com.ggumtak.readeraplus.ui.kit.icon
import com.ggumtak.readeraplus.ui.kit.inkCursor
import com.ggumtak.readeraplus.ui.kit.inkPagerKeys
import com.ggumtak.readeraplus.ui.kit.inkPaging
import com.ggumtak.readeraplus.ui.kit.label
import com.ggumtak.readeraplus.ui.kit.lp
import com.ggumtak.readeraplus.ui.kit.pressableBackground
import com.ggumtak.readeraplus.ui.kit.showNoAnim
import com.ggumtak.readeraplus.ui.kit.switchRow
import com.ggumtak.readeraplus.ui.kit.toast
import com.ggumtak.readeraplus.ui.kit.vertical

/**
 * The replacement-rule manager "치환 규칙" (T1-10): a full-screen ink dialog listing the rules of [RuleList] (checkbox,
 * name, "찾을 내용 → 바꿀 내용", "(잘못된 규칙)" for an invalid regex); a tap edits a rule (이름 / 찾을 내용 / 바꿀 내용
 * "비우면 지웁니다", a "정규식" switch that is off by default, "시험해 보기"), a long press offers 위로 / 아래로 / 삭제,
 * and the bottom row has [+ 규칙 추가] [정리 규칙 팩…] [텍스트로 편집]. The cleanup packs are all off until added.
 *
 * Owner: EXTRAS_TOOLS. Users: SETTINGS' "이 책의 TXT 정리" (this book's rules) and "TXT 기본 정리 설정" (the global
 * rules) pages. Main thread only.
 */
object RulesDialog {
    /**
     * Shows the manager for [rulesText] under [title]. [onSave] gets the new rule text (RuleList.serialize) once, when
     * the user leaves the dialog with changes (Back or 닫기); it is not called when nothing changed. The caller stores
     * it and triggers the one re-parse.
     */
    fun show(activity: Activity, title: String, rulesText: String, onSave: (String) -> Unit) {
        if (activity.isFinishing || activity.isDestroyed) return
        RulesScreen(activity, title, rulesText, onSave).show()
    }
}

/**
 * One manager window. The list is paged a screen at a time ([InkPager]: no scrolling frames on e-ink). The working
 * copy is [text] (the rule text as it would be saved) and [items] (its rules); leaving compares [text] with what the
 * dialog was opened with, so switching a rule off and on again, or opening a hand-written text with comments without
 * touching it, saves nothing.
 */
private class RulesScreen(
    private val ctx: Activity,
    private val title: String,
    private val original: String,
    private val onSave: (String) -> Unit,
) {
    private var items: MutableList<RuleItem> = RuleList.parse(original).toMutableList()
    /** The original's rules as the list would write them (a list edit that restores them is no change). */
    private val baseline = RuleList.serialize(items)
    private var text = original
    private lateinit var dialog: Dialog
    private lateinit var adapter: Adapter
    private lateinit var countLabel: TextView
    private var pager: InkPager? = null
    private var saved = false

    fun show() {
        val root = ctx.vertical { setBackgroundColor(Ink.WHITE) }
        val bar = ctx.horizontal { minimumHeight = ctx.dp(56); setPadding(ctx.dp(4), 0, ctx.dp(8), 0) }
        bar.addView(ctx.flatIcon(R.drawable.ic_arrow_back, "닫기") { dialog.dismiss() })
        bar.addView(ctx.label(title, 19f, bold = true, maxLines = 1).apply { setPadding(ctx.dp(12), 0, ctx.dp(8), 0) }, lp(0, WRAP_CONTENT, 1f))
        countLabel = ctx.label("", 14f, color = Ink.GRAY, maxLines = 1)
        bar.addView(countLabel)
        root.addView(bar, lp())
        root.addView(ctx.hairline())

        adapter = Adapter()
        val list = ctx.einkListView().apply { this.adapter = this@RulesScreen.adapter }
        val empty = ctx.emptyMessage("규칙이 없습니다.\n[+ 규칙 추가]로 만들거나\n[정리 규칙 팩…]에서 고르세요.")
        val frame = FrameLayout(ctx)
        frame.addView(list, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        frame.addView(empty, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.CENTER))
        list.emptyView = empty
        list.setOnItemClickListener { _, row, i, _ ->
            val r = items.getOrNull(i) ?: return@setOnItemClickListener
            if ((row as? RuleRow)?.onCheckbox() == true) toggle(i) else edit(i, r)
        }
        list.setOnItemLongClickListener { _, _, i, _ ->
            rowMenu(i)
            true
        }
        root.addView(frame, lp(MATCH_PARENT, 0, 1f))
        val pagerBar = InkPagerBar(ctx)
        root.addView(pagerBar)
        pager = list.inkPaging(pagerBar)

        val actions = ctx.horizontal {
            setPadding(ctx.dp(6), ctx.dp(6), ctx.dp(6), ctx.dp(6))
            background = ctx.compactRowBackground(pressable = false, topLine = true)
        }
        actions.addView(bottomButton("+ 규칙 추가") { edit(-1, null) }, lp(0, WRAP_CONTENT, 1f))
        actions.addView(bottomButton("정리 규칙 팩…") { packs() }, lp(0, WRAP_CONTENT, 1f).apply { leftMargin = ctx.dp(6) })
        actions.addView(bottomButton("텍스트로 편집") { editAsText() }, lp(0, WRAP_CONTENT, 1f).apply { leftMargin = ctx.dp(6) })
        root.addView(actions, lp())

        dialog = ctx.fullScreenDialog(root)
        dialog.setOnDismissListener { leave() }
        dialog.inkPagerKeys({ pager }, { code -> ListKeys.direction(code, Settings.app) })
        refresh()
        dialog.show()
        PanelRegistry.dialog(ctx, dialog)
    }

    /** Back / 닫기 (or the reader closing the book): hands the new text over once, when something changed. */
    private fun leave() {
        if (saved) return
        saved = true
        if (text.trim() == original.trim() || text == baseline) return
        onSave(text.trimEnd())
    }

    private fun refresh() {
        adapter.notifyDataSetChanged()
        val on = items.count { it.enabled }
        countLabel.text = if (items.isEmpty()) "" else "${on}개 켜짐"
        pager?.update()
    }

    /** A list edit: the text follows the items. */
    private fun commit() {
        text = RuleList.serialize(items)
        refresh()
    }

    // ------------------------------------------------------------------ list edits

    private fun toggle(i: Int) {
        val r = items[i]
        // A rule line starting with '#' is a comment to the parser: switched on, the character is escaped (same regex).
        val pattern = if (!r.enabled && r.pattern.startsWith("#")) "\\" + r.pattern else r.pattern
        items[i] = r.copy(pattern = pattern, enabled = !r.enabled)
        commit()
    }

    private fun rowMenu(i: Int) {
        val r = items.getOrNull(i) ?: return
        val labels = ArrayList<String>(4)
        val acts = ArrayList<() -> Unit>(4)
        if (i > 0) {
            labels += "위로"
            acts += { move(i, i - 1) }
        }
        if (i < items.size - 1) {
            labels += "아래로"
            acts += { move(i, i + 1) }
        }
        labels += if (r.enabled) "끄기" else "켜기"
        acts += { toggle(i) }
        labels += "삭제"
        acts += { delete(i) }
        PanelRegistry.dialog(
            ctx,
            ctx.alert().setTitle(r.name.ifBlank { RuleText.find(r) })
                .setItems(labels.toTypedArray()) { _, which -> acts.getOrNull(which)?.invoke() }
                .setNegativeButton("취소", null)
                .showNoAnim(),
        )
    }

    private fun move(from: Int, to: Int) {
        if (from !in items.indices || to !in items.indices) return
        val r = items.removeAt(from)
        items.add(to, r)
        commit()
    }

    private fun delete(i: Int) {
        val r = items.getOrNull(i) ?: return
        ctx.confirm("규칙 삭제", "‘${r.name.ifBlank { RuleText.find(r) }}’ 규칙을 지울까요?", "삭제") {
            if (items.getOrNull(i) !== r) return@confirm
            items.removeAt(i)
            commit()
        }
    }

    // ------------------------------------------------------------------ editor

    /** The rule editor for row [index] ([r]), or a new rule at the end (index -1). */
    private fun edit(index: Int, r: RuleItem?) {
        val f = r?.let { RuleEdit.fieldsOf(it) } ?: RuleEdit.Fields("", "", "", regex = false)
        var regex = f.regex
        val box = ctx.vertical { setPadding(ctx.dp(20), ctx.dp(4), ctx.dp(20), ctx.dp(4)) }
        fun field(caption: String, value: String, hint: String): EditText {
            box.addView(ctx.label(caption, 13f, bold = true, color = Ink.GRAY).apply { setPadding(0, ctx.dp(10), 0, 0) })
            val e = EditText(ctx).apply {
                setText(value)
                this.hint = hint
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                isSingleLine = true
                setTextColor(Ink.BLACK)
                setSelection(value.length)
                inkCursor(singleLine = false)
            }
            box.addView(e, lp())
            return e
        }
        val name = field("이름", f.name, "예: 광고 줄 (비워도 됩니다)")
        val find = field("찾을 내용", f.find, if (regex) "정규식" else "찾을 글자 그대로")
        val replace = field("바꿀 내용", f.replace, "비우면 지웁니다")
        box.addView(ctx.switchRow("정규식", "끄면 적은 글자 그대로 찾습니다", regex) { on ->
            regex = on
            find.hint = if (on) "정규식" else "찾을 글자 그대로"
            preview(find, replace, regex)
        }.apply { setPadding(0, paddingTop, 0, paddingBottom) }, lp())
        val sample = field("시험해 보기", "", "예문 한 줄을 적어 보세요")
        val result = ctx.label("", 15f).apply { setPadding(0, ctx.dp(6), 0, ctx.dp(8)); setLineSpacing(0f, 1.15f) }
        box.addView(result, lp())
        previewTarget = PreviewTarget(sample, result)
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = preview(find, replace, regex)
        }
        find.addTextChangedListener(watcher)
        replace.addTextChangedListener(watcher)
        sample.addTextChangedListener(watcher)
        preview(find, replace, regex)

        val b = ctx.alert().setTitle(if (r == null) "규칙 추가" else "규칙 편집")
            .setView(ctx.einkScroll(box))
            .setPositiveButton("저장", null)
            .setNegativeButton("취소", null)
        if (r != null) b.setNeutralButton("삭제") { _, _ -> delete(index) }
        val d = b.showNoAnim()
        PanelRegistry.dialog(ctx, d)
        d.setOnDismissListener { previewTarget = null }
        // Validated before closing: a rule the parser would skip is never saved.
        d.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
            val built = RuleEdit.build(
                RuleEdit.Fields(name.text.toString(), find.text.toString(), replace.text.toString(), regex),
                enabled = r?.enabled ?: true,
            )
            val item = built.item
            if (item == null) {
                ctx.toast(built.error ?: "규칙을 저장할 수 없습니다")
                return@setOnClickListener
            }
            val added = index !in items.indices
            if (added) items.add(item) else items[index] = item
            commit()
            d.dismiss()
            if (added) pager?.showRow(items.size - 1)
        }
    }

    private class PreviewTarget(val sample: EditText, val result: TextView)

    private var previewTarget: PreviewTarget? = null

    /** "시험해 보기": the sample line through the rule being edited (as the parser applies it), or why it can't be. */
    private fun preview(find: EditText, replace: EditText, regex: Boolean) {
        val t = previewTarget ?: return
        val sample = t.sample.text.toString()
        val built = RuleEdit.build(RuleEdit.Fields("", find.text.toString(), replace.text.toString(), regex))
        val msg = when {
            find.text.isEmpty() -> ""
            built.item == null -> built.error ?: ""
            sample.isEmpty() -> ""
            else -> RuleEdit.preview(built.item, sample).let { out ->
                when {
                    out == null -> "규칙이 올바르지 않습니다"
                    out == sample -> "결과: 바뀌지 않음"
                    out.isBlank() -> "결과: (줄이 지워짐)"
                    else -> "결과: $out"
                }
            }
        }
        if (t.result.text.toString() != msg) t.result.text = msg
    }

    // ------------------------------------------------------------------ packs, text

    private fun packs() {
        val packs = CleanupPacks.ALL
        val labels = packs.map { p ->
            val state = if (CleanupPacks.isAdded(items, p)) " · 추가됨" else ""
            val warn = p.warning?.let { "\n($it)" } ?: ""
            p.name + state + warn
        }
        PanelRegistry.dialog(
            ctx,
            ctx.alert().setTitle("정리 규칙 팩")
                .setItems(labels.toTypedArray()) { _, which ->
                    val p = packs[which]
                    when {
                        CleanupPacks.isAdded(items, p) -> ctx.toast("이미 추가한 규칙입니다")
                        p.warning != null -> ctx.confirm(p.name, "주의: ${p.warning}.\n추가할까요?", "추가") { addPack(p) }
                        else -> addPack(p)
                    }
                }
                .setNegativeButton("닫기", null)
                .showNoAnim(),
        )
    }

    private fun addPack(p: CleanupPacks.Pack) {
        if (CleanupPacks.isAdded(items, p)) return
        items.add(p.item)
        commit()
        pager?.showRow(items.size - 1)
    }

    /** The whole rule text in one field (power users; comments written here are kept while the list isn't edited). */
    private fun editAsText() {
        ctx.multilinePrompt(
            "치환 규칙",
            text,
            "찾을 정규식 => 바꿀 내용",
            minLines = 6,
            message = "한 줄에 규칙 하나: '찾을 정규식 => 바꿀 내용'. '## 이름'은 아래 규칙의 이름, '#- '로 시작하면 꺼진 규칙, 그 밖의 #은 주석입니다.",
        ) { t ->
            val bad = Fmt.invalidRuleCount(t)
            text = t.trimEnd()
            items = RuleList.parse(text).toMutableList()
            refresh()
            if (bad > 0) ctx.toast("잘못된 규칙 ${bad}개는 무시됩니다")
        }
    }

    private fun bottomButton(label: String, onClick: (View) -> Unit): TextView = ctx.label(label, 14f, bold = true, maxLines = 1).apply {
        setAutoSizeTextTypeUniformWithConfiguration(9, 14, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
        gravity = Gravity.CENTER
        minHeight = ctx.dp(44)
        setPadding(ctx.dp(4), 0, ctx.dp(4), 0)
        background = android.graphics.drawable.LayerDrawable(
            arrayOf(pressableBackground(Ink.WHITE), ctx.borderBox(android.graphics.Color.TRANSPARENT, radiusDp = 3f)),
        )
        setOnClickListener(onClick)
    }

    // ------------------------------------------------------------------ rows

    private inner class Adapter : BaseAdapter() {
        override fun getCount(): Int = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = convertView as? RuleRow ?: RuleRow(ctx)
            row.bind(items[position])
            return row
        }
    }
}

/**
 * One rule row: [☐/☑] name over "찾을 내용 → 바꿀 내용". No clickable children (the paged list must see drags first):
 * the row remembers where the finger went down, and a tap on the checkbox column toggles instead of editing.
 */
private class RuleRow(context: Context) : LinearLayout(context) {
    private val box: ImageView
    private val title: TextView
    private val summary: TextView
    private var downX = -1f

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = context.dp(60)
        setPadding(context.dp(8), context.dp(8), context.dp(16), context.dp(8))
        background = rowBackground(context)
        box = context.icon(R.drawable.ic_check_box_outline_blank, 24).apply {
            (layoutParams as LayoutParams).apply { leftMargin = context.dp(8); rightMargin = context.dp(16) }
        }
        addView(box)
        val texts = context.vertical()
        title = context.label("", 16f, maxLines = 1)
        summary = context.label("", 14f, color = Ink.GRAY, maxLines = 1).apply { setPadding(0, context.dp(4), 0, 0) }
        texts.addView(title)
        texts.addView(summary)
        addView(texts, lp(0, WRAP_CONTENT, 1f))
    }

    fun bind(r: RuleItem) {
        box.setImageResource(if (r.enabled) R.drawable.ic_check_box else R.drawable.ic_check_box_outline_blank)
        box.contentDescription = if (r.enabled) "켜짐" else "꺼짐"
        val bad = if (RuleList.isValid(r)) "" else " (잘못된 규칙)"
        val line = RuleText.summary(r)
        if (r.name.isNotBlank()) {
            title.text = r.name + bad
            summary.text = line
            summary.visibility = View.VISIBLE
        } else {
            title.text = line + bad
            summary.visibility = View.GONE
        }
        title.setTextColor(if (r.enabled) Ink.BLACK else Ink.GRAY)
    }

    /** The last press was on the checkbox column (left 56 dp). */
    fun onCheckbox(): Boolean = downX in 0f..context.dp(56).toFloat()

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) downX = ev.x
        return super.dispatchTouchEvent(ev)
    }
}

/** Row background: white with a 1px gray line along the bottom; no pressed state (the dialog that opens is the feedback). */
private fun rowBackground(context: Context): android.graphics.drawable.Drawable =
    android.graphics.drawable.LayerDrawable(
        arrayOf(android.graphics.drawable.ColorDrawable(Ink.WHITE), android.graphics.drawable.ColorDrawable(Ink.DISABLED)),
    ).apply {
        setLayerGravity(1, Gravity.BOTTOM or Gravity.FILL_HORIZONTAL)
        setLayerHeight(1, 1)
        setLayerInsetLeft(1, context.dp(16))
    }
