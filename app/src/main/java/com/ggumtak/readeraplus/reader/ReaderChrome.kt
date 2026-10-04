package com.ggumtak.readeraplus.reader

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.ggumtak.readeraplus.R
import com.ggumtak.readeraplus.ui.kit.Ink
import com.ggumtak.readeraplus.ui.kit.InkToggle
import com.ggumtak.readeraplus.ui.kit.borderBox
import com.ggumtak.readeraplus.ui.kit.dp
import com.ggumtak.readeraplus.ui.kit.dpF
import com.ggumtak.readeraplus.ui.kit.hairline
import com.ggumtak.readeraplus.ui.kit.horizontal
import com.ggumtak.readeraplus.ui.kit.icon
import com.ggumtak.readeraplus.ui.kit.iconButton
import com.ggumtak.readeraplus.ui.kit.keepAll
import com.ggumtak.readeraplus.ui.kit.label
import com.ggumtak.readeraplus.ui.kit.lp
import com.ggumtak.readeraplus.ui.kit.pressableBackground
import com.ggumtak.readeraplus.ui.kit.toast
import com.ggumtak.readeraplus.ui.kit.vertical

/**
 * Reader chrome (hidden by default, U §2): an overlay that never resizes the page. The top bar holds the actions
 * (back, bookmark, TTS, search, TOC, settings, more), the one-line book title and the brightness row with its lazily
 * built options panel; the bottom bar holds the return strip ([ReturnNav.dock]), the page label centred on the full
 * width with [rotation][pin] on the right, and the seek row ([이전 챕터] seek bar [다음 챕터]). White, 1 px black lines, no
 * animation, state shown by swapping icons (never `isSelected`). Both bars swallow touches so taps never fall through
 * to the page. While the seek bar is dragged a full-width preview box floats just above the bottom bar (outside the
 * bars, so their heights never change). Every setter compares with the last bound value: an unchanged view is never
 * touched (e-ink).
 */
internal class ReaderChrome(private val ctx: Context, private val actions: Actions, returnDock: View, private val light: LightController) {

    interface Actions {
        fun onBack()
        fun onTts()
        fun onSearch()
        fun onToc()
        fun onSettings(anchor: View)
        fun onMore(anchor: View)
        fun onPageLabel()
        /** [이전 챕터] / [다음 챕터] beside the seek bar (T1-5): the previous / next chapter start, no return chip. */
        fun onChapter(next: Boolean)
        fun onRotation()
        fun onRotationChooser()
        fun onBookmark()
        /** The pin: this page becomes the book's return point (or is released, on that page). */
        fun onPinHere()
        fun onSeekStart()
        /** Preview text for a seek position while dragging. */
        fun onSeekPreview(progress: Int): String
        fun onSeekDone(progress: Int)
    }

    val top: LinearLayout
    val bottom: LinearLayout
    val gear: ImageButton
    val more: ImageButton
    private val bookmark: ImageButton
    private val title: TextView
    private val brightnessRow: LinearLayout
    private val brightnessAuto: ImageButton
    private val brightnessBar: SeekBar
    private val optionsButton: ImageButton
    /** Verdict NONE: "기기 조명 설정에서 조절 ›" in place of the auto button and the bar (built on first need). */
    private var unavailableLink: TextView? = null
    private val optionsLine: View
    /** The options panel: an empty container until it is first opened (nothing inflated before the first page). */
    private val optionsPanel: LinearLayout
    private val pageLabel: TextView
    private val rotation: ImageButton
    private val pin: ImageButton
    private val seek: SeekBar
    /** Seek preview ("1234쪽 · 제3장 …"), shown over the page just above the bottom bar while dragging. */
    private val seekInfo: TextView
    private var root: FrameLayout? = null

    var isSeeking = false
        private set
    /** The seek preview follows the drag at most 4 times a second (each text change is an e-ink update). */
    private val seekThrottle = Throttle(Throttle.LABEL_MS)
    /** Seek position whose preview was held back by [seekThrottle] (-1 = none). */
    private var seekHeld = -1
    private val showHeldSeek = Runnable {
        val p = seekHeld
        seekHeld = -1
        if (isSeeking && p >= 0) {
            seekThrottle.mark(SystemClock.uptimeMillis())
            setSeekInfo(actions.onSeekPreview(p))
        }
    }

    // Insets and the bar width the width-dependent views were last sized for (decided in setVisible(true) only).
    private var insetLeft = 0
    private var insetRight = 0
    private var sizedRowW = -1

    // Last bound states: page turns re-bind the chrome, and an unchanged view must not be redrawn (e-ink).
    private var boundBookmarked: Boolean? = null
    private var boundRotationLocked: Boolean? = null
    private var boundPinned: Boolean? = null
    private var boundPinDescription: String = PIN_SET
    private var boundAuto: Boolean? = null
    private var bindingBrightness = false
    private var optionsOpen = false
    private var unavailable = false

    // Options panel values: cached until the rows exist, then bound on change.
    private var swipeOn = false
    private var swipeEnabled = true
    private var swipeSubtitle = LightPolicy.SWIPE_SUBTITLE
    private var askKind = LightController.ASK_NONE
    private var deviceOn = false
    private var deviceSubtitle = LightPolicy.DEVICE_OFF
    private var deviceEnabled = true
    private var panelRowVisible = false
    private var panelSubtitle = LightPolicy.PANEL_SUBTITLE
    private var rows: OptionRows? = null

    // SeekBar looks (U §2.2): manual = solid black thumb; auto = hollow ring thumb and a grey progress track.
    private val manualThumb: Drawable = dot(hollow = false)
    private val autoThumb: Drawable by lazy { dot(hollow = true) }

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
        bookmark = ctx.iconButton(R.drawable.ic_bookmark, "북마크 추가") { actions.onBookmark() }
        bookmark.setOnLongClickListener { v -> ctx.toast(v.contentDescription); true }   // the current description
        actionsRow.addView(bookmark)
        actionsRow.addView(ctx.iconButton(R.drawable.ic_volume_up, "듣기") { actions.onTts() })
        actionsRow.addView(ctx.iconButton(R.drawable.ic_search, "검색") { actions.onSearch() })
        actionsRow.addView(ctx.iconButton(R.drawable.ic_toc, "목차") { actions.onToc() })
        gear = ctx.iconButton(R.drawable.ic_settings, "읽기 설정") { v -> actions.onSettings(v) }
        actionsRow.addView(gear)
        more = ctx.iconButton(R.drawable.ic_more_vert, "더보기") { v -> actions.onMore(v) }
        actionsRow.addView(more)
        top.addView(actionsRow, lp())

        // One line, so the bar height never depends on the title; text on the 20 dp keyline (polish 10).
        title = ctx.label("", 17f, bold = true, maxLines = 1).apply {
            setPadding(ctx.dp(20), 0, ctx.dp(16), ctx.dp(10))
        }
        top.addView(title, lp())
        top.addView(ctx.hairline())

        brightnessRow = ctx.horizontal {
            minimumHeight = ctx.dp(48)
            setPadding(ctx.dp(4), 0, ctx.dp(4), 0)
        }
        brightnessAuto = ctx.iconButton(R.drawable.ic_brightness_medium, AUTO_FOLLOW) { light.onAuto() }
        brightnessAuto.setOnLongClickListener(null)
        brightnessRow.addView(brightnessAuto)
        brightnessBar = einkSeekBar().apply {
            max = 100
            contentDescription = "밝기"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                /** A touch drag is running: its end saves. A step without one (keys, TalkBack) saves at once. */
                var tracking = false

                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                    if (!fromUser || bindingBrightness) return
                    // The icon never says "auto" mid-drag: the first user move switches to the manual look.
                    if (boundAuto == true) applyBrightnessLook(false)
                    light.onDrag(p / 100f, !tracking)
                }

                override fun onStartTrackingTouch(s: SeekBar) {
                    tracking = true
                }

                override fun onStopTrackingTouch(s: SeekBar) {
                    tracking = false
                    light.onDrag(s.progress / 100f, true)
                }
            })
        }
        brightnessRow.addView(brightnessBar, lp(0, WRAP_CONTENT, 1f))
        optionsButton = ctx.iconButton(R.drawable.ic_expand_more, OPTIONS) { setBrightnessOptionsOpen(!optionsOpen) }
        brightnessRow.addView(optionsButton)
        top.addView(brightnessRow, lp())
        optionsLine = ctx.hairline().apply { visibility = View.GONE }
        top.addView(optionsLine)
        optionsPanel = ctx.vertical { visibility = View.GONE }
        top.addView(optionsPanel, lp())
        top.addView(ctx.hairline())

        bottom = ctx.vertical {
            setBackgroundColor(Ink.WHITE)
            isClickable = true
        }
        bottom.addView(ctx.hairline())
        bottom.addView(returnDock, lp())
        val labelRow = FrameLayout(ctx).apply { minimumHeight = ctx.dp(52) }
        // Centred on the FULL width with a fixed width (rowW − 2·108 dp, set in setVisible): autosize is unreliable
        // with wrap_content, and the fixed box can never run under the right cluster. No underline (polish 1).
        pageLabel = ctx.label("", 17f, bold = true, maxLines = 1).apply {
            gravity = Gravity.CENTER
            setPadding(ctx.dp(12), 0, ctx.dp(12), 0)
            fontFeatureSettings = "tnum"
            setAutoSizeTextTypeUniformWithConfiguration(14, 17, 1, TypedValue.COMPLEX_UNIT_SP)
            contentDescription = PAGE_LABEL
            background = pressableBackground()
            setOnClickListener { actions.onPageLabel() }
        }
        labelRow.addView(pageLabel, FrameLayout.LayoutParams(ctx.dp(144), ctx.dp(48), Gravity.CENTER))
        val cluster = ctx.horizontal()
        rotation = ctx.iconButton(R.drawable.ic_screen_rotation, ROTATION_LOCK) { actions.onRotation() }
        rotation.setOnLongClickListener { actions.onRotationChooser(); true }
        cluster.addView(rotation)
        pin = ctx.iconButton(R.drawable.ic_push_pin, PIN_SET) { actions.onPinHere() }
        pin.setOnLongClickListener { v -> ctx.toast(v.contentDescription); true }
        cluster.addView(pin)
        labelRow.addView(cluster, FrameLayout.LayoutParams(WRAP_CONTENT, ctx.dp(48), Gravity.END or Gravity.CENTER_VERTICAL).apply {
            marginEnd = ctx.dp(4)
        })
        bottom.addView(labelRow, lp())
        seekInfo = ctx.label("", 16f, bold = true, maxLines = 2).apply {
            gravity = Gravity.CENTER
            background = ctx.borderBox()
            setPadding(ctx.dp(16), ctx.dp(10), ctx.dp(16), ctx.dp(10))
            visibility = View.GONE
        }
        seek = einkSeekBar().apply {
            contentDescription = "페이지 위치"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                    if (fromUser && isSeeking) previewSeek(p)
                }

                override fun onStartTrackingTouch(s: SeekBar) {
                    isSeeking = true
                    actions.onSeekStart()
                    // The start text is set outside the throttle: a touch on the track away from the thumb reports
                    // its position (onProgressChanged) in this same event, which then replaces this text before the
                    // first draw, so the box is drawn once, already showing the touched position.
                    dropHeldSeek()
                    seekThrottle.reset()
                    setSeekInfo(actions.onSeekPreview(s.progress))
                    showSeekInfo(true)
                }

                override fun onStopTrackingTouch(s: SeekBar) {
                    isSeeking = false
                    dropHeldSeek()
                    showSeekInfo(false)
                    actions.onSeekDone(s.progress)
                }
            })
        }
        val seekRow = ctx.horizontal { setPadding(ctx.dp(4), 0, ctx.dp(4), 0) }
        seekRow.addView(ctx.iconButton(R.drawable.ic_skip_previous, "이전 챕터") { actions.onChapter(false) })
        seekRow.addView(seek, lp(0, WRAP_CONTENT, 1f))
        seekRow.addView(ctx.iconButton(R.drawable.ic_skip_next, "다음 챕터") { actions.onChapter(true) })
        bottom.addView(seekRow, lp())
    }

    private fun einkSeekBar(): SeekBar = SeekBar(ctx).apply {
        progressTintList = ColorStateList.valueOf(Ink.BLACK)
        progressBackgroundTintList = ColorStateList.valueOf(Ink.DISABLED)
        // The platform thumb is an animated selector (grows on press = several e-ink updates): use a plain dot.
        thumb = dot(hollow = false)
        thumbOffset = ctx.dp(8)
        background = null
        splitTrack = false
        minimumHeight = ctx.dp(48)
        setPadding(ctx.dp(12), ctx.dp(16), ctx.dp(12), ctx.dp(16))
    }

    /** A 16 dp thumb: solid black, or ([hollow]) a white ring with a 1.5 dp black stroke. */
    private fun dot(hollow: Boolean): Drawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        val d = ctx.dp(16)
        setSize(d, d)
        if (hollow) {
            setColor(Ink.WHITE)
            setStroke(ctx.dpF(1.5f).toInt().coerceAtLeast(1), Ink.BLACK)
        } else {
            setColor(Ink.BLACK)
        }
    }

    fun attach(root: FrameLayout) {
        this.root = root
        root.addView(top, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP))
        root.addView(bottom, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
        root.addView(seekInfo, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM).apply {
            leftMargin = ctx.dp(16)
            rightMargin = ctx.dp(16)
        })
        setVisible(false)
    }

    /** Hiding also closes the options panel and the seek preview. */
    fun setVisible(visible: Boolean) {
        if (visible) sizeForWidth()
        val v = if (visible) View.VISIBLE else View.GONE
        if (top.visibility != v) top.visibility = v
        if (bottom.visibility != v) bottom.visibility = v
        if (!visible) {
            dropHeldSeek()
            showSeekInfo(false)
            setBrightnessOptionsOpen(false)
        }
    }

    /**
     * The width guard (U §2.2) and the fixed label width (§2.4), from the root width minus the side insets. Decided
     * here, before the bars become visible, and only when that width changed — never in a layout listener, where a
     * size or visibility change would force a second layout and draw (a second e-ink update on the first show).
     */
    private fun sizeForWidth() {
        // The display width, not root.width: it is already the new one when a rotation reaches setInsets.
        val full = root?.resources?.displayMetrics?.widthPixels ?: ctx.resources.displayMetrics.widthPixels
        val rowW = (full - insetLeft - insetRight).coerceAtLeast(0)
        if (rowW == sizedRowW) return
        sizedRowW = rowW
        val density = ctx.resources.displayMetrics.density
        val bv = if (ChromeMath.bookmarkFits(rowW, density)) View.VISIBLE else View.GONE
        if (bookmark.visibility != bv) bookmark.visibility = bv
        val labelW = ChromeMath.labelMaxWidth(rowW, density)
        val lpLabel = pageLabel.layoutParams as FrameLayout.LayoutParams
        if (lpLabel.width != labelW) {
            lpLabel.width = labelW
            pageLabel.layoutParams = lpLabel
        }
    }

    /**
     * True while the top-row bookmark is hidden by the width guard. The ⋮ menu decides from the live width instead
     * (ReaderActivity.bookmarkInChrome, C22).
     */
    val bookmarkHidden: Boolean get() = bookmark.visibility != View.VISIBLE

    /**
     * A rotation with the bars up: the width guard and the label width follow the new width now. Equal insets in
     * both orientations (fullscreen, gesture navigation) never reach [setInsets], which would otherwise do it.
     */
    fun onConfigurationChanged() {
        if (isVisible) sizeForWidth()
    }

    /** Views of the chrome itself (the host lays out other overlays around them). */
    fun owns(v: View): Boolean = v === top || v === bottom || v === seekInfo

    /**
     * Shows the preview of seek position [p] now, or once [seekThrottle] allows (the latest held position, so the box
     * always ends on the value under the finger).
     */
    private fun previewSeek(p: Int) {
        val now = SystemClock.uptimeMillis()
        if (seekThrottle.tryAcquire(now)) {
            dropHeldSeek()
            setSeekInfo(actions.onSeekPreview(p))
            return
        }
        if (seekHeld < 0) seekInfo.postDelayed(showHeldSeek, seekThrottle.waitMs(now))
        seekHeld = p
    }

    private fun dropHeldSeek() {
        if (seekHeld < 0) return
        seekHeld = -1
        seekInfo.removeCallbacks(showHeldSeek)
    }

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
        if (top.paddingLeft != left || top.paddingTop != topInset || top.paddingRight != right) {
            top.setPadding(left, topInset, right, 0)
        }
        if (bottom.paddingLeft != left || bottom.paddingRight != right || bottom.paddingBottom != bottomInset) {
            bottom.setPadding(left, 0, right, bottomInset)
        }
        insetLeft = left
        insetRight = right
        if (isVisible) sizeForWidth()   // returns at once when the row width is unchanged (also after a rotation)
    }

    fun setTitle(text: CharSequence) {
        if (title.text.toString() != text.toString()) title.text = text
    }

    /**
     * Page label and seek position (ignored while the user drags the seek bar). The label's content description
     * carries the page ("페이지 이동, 3 / 167"); it is set with the text, so it costs nothing while the chrome is hidden.
     */
    fun setPage(label: String, max: Int, progress: Int) {
        if (isSeeking) return
        if (pageLabel.text.toString() != label) {
            pageLabel.text = label
            pageLabel.contentDescription = "$PAGE_LABEL, $label"
        }
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

    /** The pin's icon (filled while the book has a pinned return point) and content description (U §3.1). */
    fun setPinned(on: Boolean, onMarkPage: Boolean) {
        if (boundPinned != on) {
            boundPinned = on
            pin.setImageResource(if (on) R.drawable.ic_push_pin_fill else R.drawable.ic_push_pin)
        }
        val d = when {
            !on -> PIN_SET
            onMarkPage -> PIN_RELEASE
            else -> PIN_MOVE
        }
        if (boundPinDescription !== d) {
            boundPinDescription = d
            pin.contentDescription = d
        }
    }

    fun setRotationLocked(locked: Boolean) {
        if (boundRotationLocked == locked) return
        boundRotationLocked = locked
        rotation.setImageResource(if (locked) R.drawable.ic_screen_lock_rotation else R.drawable.ic_screen_rotation)
        // What a tap does: unlock while locked.
        rotation.contentDescription = if (locked) ROTATION_UNLOCK else ROTATION_LOCK
    }

    // ------------------------------------------------------------------ brightness (bound by LightController only)

    /** [value] = the bar position 0..1; [auto] = system brightness (the bar then shows the auto look). */
    fun setBrightness(value: Float, auto: Boolean) {
        val p = Math.round((if (value.isNaN()) 0f else value).coerceIn(0f, 1f) * 100f)
        if (brightnessBar.progress != p) {
            bindingBrightness = true
            brightnessBar.progress = p
            bindingBrightness = false
        }
        if (boundAuto != auto) applyBrightnessLook(auto)
    }

    private fun applyBrightnessLook(auto: Boolean) {
        boundAuto = auto
        brightnessAuto.setImageResource(if (auto) R.drawable.ic_brightness_auto else R.drawable.ic_brightness_medium)
        brightnessAuto.contentDescription = if (auto) AUTO_MANUAL else AUTO_FOLLOW
        brightnessBar.progressTintList = ColorStateList.valueOf(if (auto) Ink.DISABLED else Ink.BLACK)
        brightnessBar.thumb = if (auto) autoThumb else manualThumb
    }

    /** The brightness row is never hidden any more (U §2.3); kept for source compatibility, a no-op. */
    @Suppress("UNUSED_PARAMETER")
    fun setBrightnessCollapsed(collapsed: Boolean) {}

    /** Opens or closes the options panel under the brightness row; its rows are built on the first open. */
    fun setBrightnessOptionsOpen(open: Boolean) {
        if (open == optionsOpen) return
        optionsOpen = open
        if (open) ensureRows()
        val v = if (open) View.VISIBLE else View.GONE
        optionsLine.visibility = v
        optionsPanel.visibility = v
        optionsButton.setImageResource(if (open) R.drawable.ic_expand_less else R.drawable.ic_expand_more)
    }

    fun setSwipeOption(on: Boolean, enabled: Boolean, subtitle: String) {
        if (on == swipeOn && enabled == swipeEnabled && subtitle == swipeSubtitle) return
        swipeOn = on
        swipeEnabled = enabled
        swipeSubtitle = subtitle
        rows?.swipe?.bind(on, enabled, subtitle)
    }

    /** [kind] = LightController.ASK_NONE / ASK_WINDOW / ASK_DEVICE: the question row at the top of the panel. */
    fun setLightAsk(kind: Int) {
        if (kind == askKind) return
        askKind = kind
        rows?.bindAsk(kind)
    }

    fun setLightDevice(on: Boolean, subtitle: String, enabled: Boolean) {
        if (on == deviceOn && enabled == deviceEnabled && subtitle == deviceSubtitle) return
        deviceOn = on
        deviceEnabled = enabled
        deviceSubtitle = subtitle
        rows?.device?.bind(on, enabled, subtitle)
    }

    /** Verdict NONE: one link "기기 조명 설정에서 조절 ›" replaces the auto button and the bar. */
    fun setBrightnessUnavailable(unavailable: Boolean) {
        if (unavailable == this.unavailable) return
        this.unavailable = unavailable
        if (unavailable) ensureUnavailableLink()
        val bar = if (unavailable) View.GONE else View.VISIBLE
        brightnessAuto.visibility = bar
        brightnessBar.visibility = bar
        unavailableLink?.visibility = if (unavailable) View.VISIBLE else View.GONE
    }

    /** The "기기 조명 설정 열기" row (a warm channel exists, or verdict NONE). */
    fun setLightPanelRow(visible: Boolean, subtitle: String) {
        if (visible == panelRowVisible && subtitle == panelSubtitle) return
        panelRowVisible = visible
        panelSubtitle = subtitle
        rows?.bindPanel(visible, subtitle)
    }

    private fun ensureUnavailableLink() {
        if (unavailableLink != null) return
        val link = ctx.label(UNAVAILABLE_LINK, 15f, maxLines = 1).apply {
            gravity = Gravity.CENTER_VERTICAL
            minHeight = ctx.dp(48)
            setPadding(ctx.dp(12), 0, ctx.dp(8), 0)
            compoundDrawablePadding = ctx.dp(8)
            setCompoundDrawablesRelative(tinted(R.drawable.ic_brightness_medium, 24), null, tinted(R.drawable.ic_chevron_right, 18), null)
            background = pressableBackground()
            setOnClickListener { light.onOpenPanel() }
        }
        brightnessRow.addView(link, 0, lp(0, ctx.dp(48), 1f))
        unavailableLink = link
    }

    private fun tinted(res: Int, sizeDp: Int): Drawable {
        val d = ctx.getDrawable(res)!!.mutate()
        val s = ctx.dp(sizeDp)
        d.setBounds(0, 0, s, s)
        d.setTintList(ColorStateList.valueOf(Ink.BLACK))
        return d
    }

    // ------------------------------------------------------------------ options panel rows (lazy)

    private fun ensureRows() {
        if (rows != null) return
        val r = OptionRows()
        rows = r
        r.swipe.bind(swipeOn, swipeEnabled, swipeSubtitle)
        r.device.bind(deviceOn, deviceEnabled, deviceSubtitle)
        r.bindAsk(askKind)
        r.bindPanel(panelRowVisible, panelSubtitle)
    }

    /** The panel's rows in order: question (while asked), swipe switch, device switch, device light settings link. */
    private inner class OptionRows {
        private val askRow: LinearLayout
        private val askLine: View
        private val askText: TextView
        val swipe: ToggleRow
        val device: ToggleRow
        private val panelLine: View
        private val panelRow: LinearLayout
        private val panelSub: TextView

        init {
            askRow = ctx.horizontal {
                minimumHeight = ctx.dp(56)
                setPadding(ctx.dp(20), ctx.dp(4), ctx.dp(8), ctx.dp(4))
                visibility = View.GONE
            }
            askText = ctx.label("", 15f, maxLines = 2).keepAll()
            askRow.addView(askText, lp(0, WRAP_CONTENT, 1f))
            askRow.addView(answer("예", true))
            askRow.addView(answer("아니요", false))
            optionsPanel.addView(askRow, lp())
            askLine = lightLine().apply { visibility = View.GONE }
            optionsPanel.addView(askLine)

            swipe = ToggleRow(SWIPE_TITLE) { on -> light.onSwipeSwitch(on) }
            optionsPanel.addView(swipe.row, lp())
            optionsPanel.addView(lightLine())
            device = ToggleRow(DEVICE_TITLE) { on -> light.onDeviceSwitch(on) }
            optionsPanel.addView(device.row, lp())

            panelLine = lightLine().apply { visibility = View.GONE }
            optionsPanel.addView(panelLine)
            panelRow = ctx.horizontal {
                minimumHeight = ctx.dp(56)
                setPadding(ctx.dp(20), ctx.dp(8), ctx.dp(16), ctx.dp(8))
                background = pressableBackground()
                setOnClickListener { light.onOpenPanel() }
                visibility = View.GONE
            }
            val texts = ctx.vertical()
            texts.addView(ctx.label(PANEL_TITLE, 15f, maxLines = 1))
            panelSub = subtitle(LightPolicy.PANEL_SUBTITLE)
            texts.addView(panelSub)
            panelRow.addView(texts, lp(0, WRAP_CONTENT, 1f))
            panelRow.addView(ctx.icon(R.drawable.ic_chevron_right, 24, Ink.GRAY))
            optionsPanel.addView(panelRow, lp())
        }

        private fun answer(text: String, yes: Boolean): TextView = ctx.label(text, 15f, bold = true, maxLines = 1).apply {
            gravity = Gravity.CENTER
            minWidth = ctx.dp(56)
            minHeight = ctx.dp(48)
            setPadding(ctx.dp(8), 0, ctx.dp(8), 0)
            background = pressableBackground()
            setOnClickListener { light.onAnswer(yes) }
        }

        fun bindAsk(kind: Int) {
            val text = when (kind) {
                LightController.ASK_WINDOW -> ASK_WINDOW_TEXT
                LightController.ASK_DEVICE -> ASK_DEVICE_TEXT
                else -> null
            }
            val v = if (text == null) View.GONE else View.VISIBLE
            if (text != null && askText.text.toString() != text) askText.text = text
            if (askRow.visibility != v) askRow.visibility = v
            if (askLine.visibility != v) askLine.visibility = v
        }

        fun bindPanel(visible: Boolean, subtitle: String) {
            if (panelSub.text.toString() != subtitle) panelSub.text = subtitle
            val v = if (visible) View.VISIBLE else View.GONE
            if (panelRow.visibility != v) panelRow.visibility = v
            if (panelLine.visibility != v) panelLine.visibility = v
        }
    }

    /**
     * One switch row: title 15 sp, subtitle 13 sp GRAY (max 2 lines), the [InkToggle] ending at W − 16 dp. Tapping
     * anywhere toggles; the row is the one accessibility unit (checkable, checked = the toggle, text = both lines).
     */
    private inner class ToggleRow(private val titleText: String, private val onSwitch: (Boolean) -> Unit) {
        val row: LinearLayout
        private val titleView: TextView
        private val sub: TextView
        private val toggle = InkToggle(ctx).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }

        init {
            row = ctx.horizontal {
                minimumHeight = ctx.dp(56)
                setPadding(ctx.dp(20), ctx.dp(8), ctx.dp(16), ctx.dp(8))
                background = pressableBackground()
                isFocusable = true
                setOnClickListener { if (isEnabled) flip() }
            }
            val texts = ctx.vertical()
            titleView = ctx.label(titleText, 15f, maxLines = 1)
            texts.addView(titleView)
            sub = subtitle("")
            texts.addView(sub)
            row.addView(texts, lp(0, WRAP_CONTENT, 1f))
            row.addView(toggle)
            row.accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.isCheckable = true
                    info.isChecked = toggle.isChecked
                    info.text = "$titleText, ${sub.text}"
                    info.className = "android.widget.Switch"
                }
            }
        }

        /** A user tap: the toggle flips at once (one update), then the controller is told; it re-binds if it refuses. */
        private fun flip() {
            val on = !toggle.isChecked
            toggle.isChecked = on
            if (this === rows?.swipe) swipeOn = on else deviceOn = on
            onSwitch(on)
        }

        fun bind(on: Boolean, enabled: Boolean, subtitle: String) {
            if (toggle.isChecked != on) toggle.isChecked = on
            if (sub.text.toString() != subtitle) sub.text = subtitle
            if (row.isEnabled != enabled) {
                row.isEnabled = enabled
                toggle.isEnabled = enabled
                // Disabled = GRAY (#555, survives every waveform) plus the subtitle's own wording, never grey alone.
                titleView.setTextColor(if (enabled) Ink.BLACK else Ink.GRAY)
            }
        }
    }

    private fun subtitle(text: String): TextView = ctx.label(text, 13f, color = Ink.GRAY, maxLines = 2).apply {
        keepAll()
        setPadding(0, ctx.dp(2), 0, 0)
    }

    /** A light separator inside a band group: 1 px, inset 20 dp left and 16 dp right. */
    private fun lightLine(): View = View(ctx).apply {
        setBackgroundColor(Ink.LINE_LIGHT)
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 1).apply {
            marginStart = ctx.dp(20)
            marginEnd = ctx.dp(16)
        }
    }

    val bottomHeight: Int get() = bottom.height

    private companion object {
        const val PAGE_LABEL = "페이지 이동"
        const val PIN_SET = "이 페이지 고정"
        const val PIN_MOVE = "여기로 고정 옮기기"
        const val PIN_RELEASE = "고정 해제"
        const val AUTO_FOLLOW = "시스템 밝기 따르기"
        const val AUTO_MANUAL = "직접 밝기 조절"
        const val OPTIONS = "밝기 옵션"
        const val UNAVAILABLE_LINK = "기기 조명 설정에서 조절"
        const val SWIPE_TITLE = "스와이프로 밝기 조절"
        const val DEVICE_TITLE = "기기 밝기 직접 조절"
        const val PANEL_TITLE = "기기 조명 설정 열기"
        const val ROTATION_LOCK = "화면 회전 잠금"
        const val ROTATION_UNLOCK = "화면 회전 잠금 해제"
        const val ASK_WINDOW_TEXT = "조명 밝기가 바뀌었나요?"
        const val ASK_DEVICE_TEXT = "막대를 움직여 보세요. 조명이 바뀌나요?"
    }
}
