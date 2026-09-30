package com.ggumtak.readeraplus.reader

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.ggumtak.readeraplus.R
import com.ggumtak.readeraplus.ui.kit.Ink
import com.ggumtak.readeraplus.ui.kit.borderBox
import com.ggumtak.readeraplus.ui.kit.dp
import com.ggumtak.readeraplus.ui.kit.hairline
import com.ggumtak.readeraplus.ui.kit.horizontal
import com.ggumtak.readeraplus.ui.kit.iconButton
import com.ggumtak.readeraplus.ui.kit.label
import com.ggumtak.readeraplus.ui.kit.lp
import com.ggumtak.readeraplus.ui.kit.pressableBackground
import com.ggumtak.readeraplus.ui.kit.vertical

/**
 * Reader chrome (hidden by default): a top panel with actions, book title and a brightness row, and a bottom
 * panel with the page label, rotation lock, bookmark toggle and a page seek bar. White, 1px black lines,
 * no animation. Both panels swallow touches so taps never fall through to the page. While the seek bar is dragged
 * a full-width preview box floats just above the bottom panel (outside the panels, so their heights never change).
 */
internal class ReaderChrome(private val ctx: Context, private val actions: Actions) {

    interface Actions {
        fun onBack()
        fun onTts()
        fun onSearch()
        fun onToc()
        fun onSettings(anchor: View)
        fun onMore(anchor: View)
        fun onBrightnessAuto()
        fun onBrightness(value: Float, done: Boolean)
        fun onBrightnessCollapsed(collapsed: Boolean)
        fun onPageLabel()
        fun onRotation()
        fun onRotationChooser()
        fun onBookmark()
        /** "메뉴 고정" toggle (ReadEra's pin). */
        fun onPin()
        fun onSeekStart()
        /** Preview text for a seek position while dragging. */
        fun onSeekPreview(progress: Int): String
        fun onSeekDone(progress: Int)
    }

    val top: LinearLayout
    val bottom: LinearLayout
    val gear: ImageButton
    val more: ImageButton
    private val title: TextView
    private val brightnessRow: LinearLayout
    private val brightnessAuto: ImageButton
    private val brightnessBar: SeekBar
    private val brightnessShow: ImageButton
    private val pageLabel: TextView
    private val rotation: ImageButton
    private val bookmark: ImageButton
    private val pin: ImageButton
    private val seek: SeekBar
    /** Seek preview ("p. 1234 · 제3장 …"), shown over the page just above the bottom panel while dragging. */
    private val seekInfo: TextView

    var isSeeking = false
        private set
    private var bindingBrightness = false
    // Last bound icon states: page turns re-bind the chrome, and an unchanged icon must not be redrawn (e-ink).
    private var boundBookmarked: Boolean? = null
    private var boundRotationLocked: Boolean? = null
    private var boundPinned: Boolean? = null

    init {
        top = ctx.vertical {
            setBackgroundColor(Ink.WHITE)
            isClickable = true
        }
        val actionsRow = ctx.horizontal {
            minimumHeight = ctx.dp(56)
            setPadding(ctx.dp(4), 0, ctx.dp(4), 0)
        }
        actionsRow.addView(ctx.iconButton(R.drawable.ic_arrow_back, "뒤로") { actions.onBack() })
        actionsRow.addView(View(ctx), lp(0, 1, 1f))
        actionsRow.addView(ctx.iconButton(R.drawable.ic_volume_up, "TTS 읽기") { actions.onTts() })
        actionsRow.addView(ctx.iconButton(R.drawable.ic_search, "검색") { actions.onSearch() })
        actionsRow.addView(ctx.iconButton(R.drawable.ic_toc, "목차") { actions.onToc() })
        gear = ctx.iconButton(R.drawable.ic_settings, "읽기 설정") { v -> actions.onSettings(v) }
        actionsRow.addView(gear)
        more = ctx.iconButton(R.drawable.ic_more_vert, "더보기") { v -> actions.onMore(v) }
        actionsRow.addView(more)
        top.addView(actionsRow, lp())

        val titleRow = ctx.horizontal { setPadding(ctx.dp(16), 0, ctx.dp(4), ctx.dp(6)) }
        title = ctx.label("", 18f, bold = true, maxLines = 2)
        titleRow.addView(title, lp(0, WRAP_CONTENT, 1f))
        brightnessShow = ctx.iconButton(R.drawable.ic_brightness_medium, "밝기 조절 보이기") {
            setBrightnessCollapsed(false)
            actions.onBrightnessCollapsed(false)
        }
        titleRow.addView(brightnessShow)
        top.addView(titleRow, lp())

        brightnessRow = ctx.horizontal { setPadding(ctx.dp(4), 0, ctx.dp(4), 0) }
        brightnessAuto = ctx.iconButton(R.drawable.ic_brightness_auto, "시스템 밝기") { actions.onBrightnessAuto() }
        brightnessRow.addView(brightnessAuto)
        brightnessBar = einkSeekBar().apply {
            max = 100
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                    if (fromUser && !bindingBrightness) actions.onBrightness(p / 100f, false)
                }

                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {
                    actions.onBrightness(s.progress / 100f, true)
                }
            })
        }
        brightnessRow.addView(brightnessBar, lp(0, WRAP_CONTENT, 1f))
        brightnessRow.addView(ctx.iconButton(R.drawable.ic_expand_less, "밝기 조절 숨기기") {
            setBrightnessCollapsed(true)
            actions.onBrightnessCollapsed(true)
        })
        top.addView(brightnessRow, lp())
        top.addView(ctx.hairline())

        bottom = ctx.vertical {
            setBackgroundColor(Ink.WHITE)
            isClickable = true
        }
        bottom.addView(ctx.hairline())
        val row = ctx.horizontal {
            minimumHeight = ctx.dp(56)
            setPadding(ctx.dp(4), 0, ctx.dp(4), 0)
        }
        // The label takes all the room left of the buttons (≈ 208dp on the 360dp-wide Comet), so
        // "12345 / 23259" fits; it is centred in that room rather than across the whole width.
        pageLabel = ctx.label("", 18f, bold = true, maxLines = 1).apply {
            gravity = Gravity.CENTER
            setPadding(ctx.dp(8), 0, ctx.dp(8), 0)
            minHeight = ctx.dp(48)
            background = pressableBackground()
            setOnClickListener { actions.onPageLabel() }
        }
        row.addView(pageLabel, lp(0, WRAP_CONTENT, 1f))
        rotation = ctx.iconButton(R.drawable.ic_screen_rotation, "화면 회전 잠금") { actions.onRotation() }
        rotation.setOnLongClickListener { actions.onRotationChooser(); true }
        row.addView(rotation)
        bookmark = ctx.iconButton(R.drawable.ic_bookmark, "북마크") { actions.onBookmark() }
        row.addView(bookmark)
        pin = ctx.iconButton(R.drawable.ic_push_pin, "메뉴 고정") { actions.onPin() }
        row.addView(pin)
        bottom.addView(row, lp())
        seekInfo = ctx.label("", 16f, bold = true, maxLines = 2).apply {
            gravity = Gravity.CENTER
            background = ctx.borderBox()
            setPadding(ctx.dp(16), ctx.dp(10), ctx.dp(16), ctx.dp(10))
            visibility = View.GONE
        }
        seek = einkSeekBar().apply {
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                    if (fromUser && isSeeking) setSeekInfo(actions.onSeekPreview(p))
                }

                override fun onStartTrackingTouch(s: SeekBar) {
                    isSeeking = true
                    actions.onSeekStart()
                    setSeekInfo(actions.onSeekPreview(s.progress))
                    showSeekInfo(true)
                }

                override fun onStopTrackingTouch(s: SeekBar) {
                    isSeeking = false
                    showSeekInfo(false)
                    actions.onSeekDone(s.progress)
                }
            })
        }
        bottom.addView(seek, lp())
        setBrightnessCollapsed(false)
    }

    private fun einkSeekBar(): SeekBar = SeekBar(ctx).apply {
        progressTintList = ColorStateList.valueOf(Ink.BLACK)
        progressBackgroundTintList = ColorStateList.valueOf(Ink.GRAY)
        // The platform thumb is an animated selector (grows on press = several e-ink updates): use a plain dot.
        thumb = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Ink.BLACK)
            val d = ctx.dp(20)
            setSize(d, d)
        }
        thumbOffset = ctx.dp(10)
        background = null
        splitTrack = false
        minimumHeight = ctx.dp(48)
        setPadding(ctx.dp(20), ctx.dp(14), ctx.dp(20), ctx.dp(14))
    }

    fun attach(root: FrameLayout) {
        root.addView(top, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP))
        root.addView(bottom, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
        root.addView(seekInfo, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM).apply {
            leftMargin = ctx.dp(16)
            rightMargin = ctx.dp(16)
        })
        setVisible(false)
    }

    fun setVisible(visible: Boolean) {
        val v = if (visible) View.VISIBLE else View.GONE
        top.visibility = v
        bottom.visibility = v
        if (!visible) showSeekInfo(false)
    }

    /** Views of the chrome itself (the host lays out other overlays around them). */
    fun owns(v: View): Boolean = v === top || v === bottom || v === seekInfo

    private fun setSeekInfo(text: CharSequence) {
        if (seekInfo.text.toString() != text.toString()) seekInfo.text = text
    }

    private fun showSeekInfo(show: Boolean) {
        if (!show) {
            if (seekInfo.visibility != View.GONE) seekInfo.visibility = View.GONE
            return
        }
        val lp = seekInfo.layoutParams as? FrameLayout.LayoutParams
        val above = bottom.height + ctx.dp(8)
        if (lp != null && lp.bottomMargin != above) {
            lp.bottomMargin = above
            seekInfo.layoutParams = lp
        }
        seekInfo.visibility = View.VISIBLE
    }

    val isVisible: Boolean get() = top.visibility == View.VISIBLE

    fun setInsets(left: Int, topInset: Int, right: Int, bottomInset: Int) {
        top.setPadding(left, topInset, right, 0)
        bottom.setPadding(left, 0, right, bottomInset)
    }

    fun setTitle(text: CharSequence) {
        if (title.text != text) title.text = text
    }

    /** Page label and seek position (ignored while the user drags the seek bar). */
    fun setPage(label: String, max: Int, progress: Int) {
        if (isSeeking) return
        if (pageLabel.text.toString() != label) pageLabel.text = label
        val m = max.coerceAtLeast(1)
        if (seek.max != m) seek.max = m
        val p = progress.coerceIn(0, m)
        if (seek.progress != p) seek.progress = p
    }

    fun setBookmarked(on: Boolean) {
        if (boundBookmarked == on) return
        boundBookmarked = on
        bookmark.setImageResource(if (on) R.drawable.ic_bookmark_fill else R.drawable.ic_bookmark)
        bookmark.contentDescription = if (on) "북마크 삭제" else "북마크 추가"
    }

    fun setPinned(on: Boolean) {
        if (boundPinned == on) return
        boundPinned = on
        pin.setImageResource(if (on) R.drawable.ic_push_pin_fill else R.drawable.ic_push_pin)
        pin.contentDescription = if (on) "메뉴 고정 해제" else "메뉴 고정"
        pin.isSelected = on
    }

    fun setRotationLocked(locked: Boolean) {
        if (boundRotationLocked == locked) return
        boundRotationLocked = locked
        rotation.setImageResource(if (locked) R.drawable.ic_screen_lock_rotation else R.drawable.ic_screen_rotation)
        rotation.isSelected = locked
    }

    /** [value] < 0 = system brightness ([systemValue] positions the bar). */
    fun setBrightness(value: Float, systemValue: Float) {
        bindingBrightness = true
        brightnessAuto.isSelected = value < 0f
        val p = Math.round((if (value < 0f) systemValue else value).coerceIn(0f, 1f) * 100f)
        if (brightnessBar.progress != p) brightnessBar.progress = p
        bindingBrightness = false
    }

    fun setBrightnessCollapsed(collapsed: Boolean) {
        brightnessRow.visibility = if (collapsed) View.GONE else View.VISIBLE
        brightnessShow.visibility = if (collapsed) View.VISIBLE else View.GONE
    }

    val bottomHeight: Int get() = bottom.height
}
