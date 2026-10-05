package com.ggumtak.readeraplus.reader

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.SystemClock
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.ggumtak.readeraplus.R
import com.ggumtak.readeraplus.render.ChromePalette
import com.ggumtak.readeraplus.render.PagePalette
import com.ggumtak.readeraplus.ui.kit.InkToggle
import com.ggumtak.readeraplus.ui.kit.dp
import com.ggumtak.readeraplus.ui.kit.dpF
import com.ggumtak.readeraplus.ui.kit.horizontal
import com.ggumtak.readeraplus.ui.kit.icon
import com.ggumtak.readeraplus.ui.kit.iconButton
import com.ggumtak.readeraplus.ui.kit.keepAll
import com.ggumtak.readeraplus.ui.kit.label
import com.ggumtak.readeraplus.ui.kit.lp
import com.ggumtak.readeraplus.ui.kit.toast
import com.ggumtak.readeraplus.ui.kit.vertical

/**
 * Reader chrome (hidden by default, U §2): an overlay that never resizes the page. The top bar holds the actions
 * (back, bookmark, TTS, search, TOC, settings, more) and the one-line book title on its panel, then, on the page colour
 * below the panel's edge as in ReadEra, the brightness row with its lazily built options panel; the bottom bar holds
 * the history row ([ReturnNav.dock], on the page colour right above the panel), the page label centred on the full
 * width with [rotation][pin] on the right, and the seek row ([이전 챕터] seek bar [다음 챕터]), as low as ReadEra's
 * ([ChromeMath.PANEL_DP], then the bottom gap). Both bars are [ChromeBar]s in the page's theme ([setLook] →
 * [ChromePalette]: a surface close to the page, never a white bar over a dark one; the theme's accent for states that
 * stay and for progress). On a phone they meet the page with a short shadow, buttons show a pressed state, and the
 * bars fade and slide in and out (150–200 ms, at once when the system's animations are off; the top bar, brightness
 * row included, moves as one view). On e-ink every action stays one update: solid 1 px
 * edges, no pressed state, no fade, state shown by swapping icons (never `isSelected`). Only alpha and translation
 * move, so the page never re-lays out. Both bars swallow touches so taps never fall through to the page (except a new
 * touch while one fades out). While the seek bar is dragged a full-width preview box floats just above the bottom bar
 * (outside the bars, so their heights never change). Every setter compares with the last bound value: an unchanged
 * view is never touched (e-ink).
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
        /** The pin: this page is saved as the newest place to go back to (or, when it already is, released). */
        fun onPinHere()
        fun onSeekStart()
        /** Preview text for a seek position while dragging. */
        fun onSeekPreview(progress: Int): String
        fun onSeekDone(progress: Int)
    }

    /** The colours in use; [wanted] is the look [setLook] asked for, applied when the bars show (at once while up). */
    private var look = ChromePalette.DEFAULT
    private var wanted = ChromePalette.DEFAULT

    val top: ChromeBar
    val bottom: ChromeBar
    val gear: ImageButton
    val more: ImageButton
    private val bookmark: ImageButton
    /** Icon buttons in the text colour (the toggles bookmark, pin and rotation are painted by [paintToggle]). */
    private val icons = ArrayList<ImageButton>(10)
    /** The brightness row's icons, on the page colour: painted like the history row's glyphs ([ChromePalette.hist]). */
    private val pageIcons = ArrayList<ImageButton>(2)
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
    /** The page label's text as last bound ("3 / 183"; the view holds it with spans). */
    private var boundLabel = ""
    /** The text drawn instead of [boundLabel] while the pages are counted ([setPage]); null = the label itself. */
    private var boundShown: String? = null
    private val rotation: ImageButton
    private val pin: ImageButton
    private val seek: SeekBar
    /** Seek preview ("1234쪽 · 제3장 …"), shown over the page just above the bottom bar while dragging. */
    private val seekInfo: TextView
    private var root: FrameLayout? = null

    /** The bars are shown, also while they fade in; false from the moment a hide starts. */
    private var shown = false

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
    private var insetTop = 0
    private var insetRight = 0
    private var sizedRowW = -1

    // Last bound states: page turns re-bind the chrome, and an unchanged view must not be redrawn (e-ink).
    private var boundBookmarked: Boolean? = null
    private var boundRotationLocked: Boolean? = null
    private var boundPinned: Boolean? = null
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

    /** The device class the sliders' drawables are sized for ([sizeSliders]); the default look is the e-ink one. */
    private var slidersEink = look.eink
    // Slider thumbs (U §2.2), coloured in place on a look change: manual = a solid accent dot; auto = a hollow ring
    // over a track-coloured progress. Each bar has its own drawable (a drawable has one callback).
    private var seekThumb = dot(hollow = false)
    private var manualThumb = dot(hollow = false)
    private var autoDot: GradientDrawable? = null

    init {
        top = ChromeBar(ctx, edgeAtTop = false)
        val actionsRow = ctx.horizontal {
            minimumHeight = ctx.dp(ChromeMath.ACTIONS_ROW_DP)
            setPadding(ctx.dp(4), 0, ctx.dp(4), 0)
        }
        actionsRow.addView(plainIcon(R.drawable.ic_arrow_back, "뒤로") { actions.onBack() })
        actionsRow.addView(View(ctx), lp(0, 1, 1f))
        bookmark = ctx.iconButton(R.drawable.ic_bookmark, "북마크 추가") { actions.onBookmark() }
        bookmark.setOnLongClickListener { v -> ctx.toast(v.contentDescription); true }   // the current description
        actionsRow.addView(bookmark)
        actionsRow.addView(plainIcon(R.drawable.ic_volume_up, "듣기") { actions.onTts() })
        actionsRow.addView(plainIcon(R.drawable.ic_search, "검색") { actions.onSearch() })
        actionsRow.addView(plainIcon(R.drawable.ic_toc, "목차") { actions.onToc() })
        gear = plainIcon(R.drawable.ic_settings, "읽기 설정") { v -> actions.onSettings(v) }
        actionsRow.addView(gear)
        more = plainIcon(R.drawable.ic_more_vert, "더보기") { v -> actions.onMore(v) }
        actionsRow.addView(more)
        top.addView(actionsRow, lp())

        // One line, so the bar height never depends on the title; text on the 20 dp keyline (polish 10). The top of the
        // type scale (U §2.1): 18 sp bold, above the page label's 17 and the history row's 14, as in ReadEra. Its box
        // starts inside the action row's empty foot (ChromeMath.TITLE_LIFT_DP): the title block as short as ReadEra's.
        // Not clickable, so a tap there still reaches the buttons.
        title = ctx.label("", 18f, bold = true, maxLines = 1).apply {
            setPadding(ctx.dp(20), 0, ctx.dp(16), ctx.dp(12))
        }
        top.addView(title, lp().apply { topMargin = -ctx.dp(ChromeMath.TITLE_LIFT_DP) })

        // The panel ends under the title (its edge there, no rule): the brightness row and its options sit on the page
        // colour itself, with nothing under the row, as in ReadEra (the user, 2026-10-05: "밝기 부분도 … 색을 아예 똑같이").
        top.pageFrom = top.childCount
        brightnessRow = ctx.horizontal {
            minimumHeight = ctx.dp(48)
            setPadding(ctx.dp(4), 0, ctx.dp(4), 0)
        }
        brightnessAuto = pageIcon(R.drawable.ic_brightness_medium, AUTO_FOLLOW) { light.onAuto() }
        brightnessAuto.setOnLongClickListener(null)
        brightnessRow.addView(brightnessAuto)
        brightnessBar = chromeSeekBar(manualThumb).apply {
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
        brightnessRow.addView(brightnessBar, lp(0, ctx.dp(48), 1f))
        optionsButton = pageIcon(R.drawable.ic_expand_more, OPTIONS) { setBrightnessOptionsOpen(!optionsOpen) }
        brightnessRow.addView(optionsButton)
        top.addView(brightnessRow, lp())
        optionsLine = ruleLine().apply { visibility = View.GONE }
        top.addView(optionsLine)
        optionsPanel = ctx.vertical { visibility = View.GONE }
        top.addView(optionsPanel, lp())
        // The edges are drawn by ChromeBar (a shadow on phones, a 1 px line on e-ink): under the title, and under the
        // options while they are open (padTop).

        bottom = ChromeBar(ctx, edgeAtTop = true)
        // The history row sits on the page colour directly above the panel, which starts at the label row.
        bottom.addView(returnDock, lp())
        bottom.panelFrom = 1
        // The panel's two rows as low as ReadEra's (ChromeMath.PANEL_DP): the seek row starts 12 dp inside the label row,
        // so their 48 dp touch areas overlap in the empty space between the glyphs, and the label row, added last, takes
        // the touch there (a near miss of the pin or the label never jumps to another chapter or page).
        val panelRows = FrameLayout(ctx)
        val labelRow = FrameLayout(ctx)
        // Centred on the FULL width with a fixed width (rowW − 2·108 dp, set in setVisible): autosize is unreliable
        // with wrap_content, and the fixed box can never run under the right cluster. No underline (polish 1). The
        // current page reads first (17 sp bold, below the 18 sp title), the total after it smaller and in the
        // secondary colour.
        pageLabel = ctx.label("", 17f, maxLines = 1).apply {
            gravity = Gravity.CENTER
            setPadding(ctx.dp(12), 0, ctx.dp(12), 0)
            fontFeatureSettings = "tnum"
            setAutoSizeTextTypeUniformWithConfiguration(14, 17, 1, TypedValue.COMPLEX_UNIT_SP)
            contentDescription = PAGE_LABEL
            setOnClickListener { actions.onPageLabel() }
        }
        labelRow.addView(pageLabel, FrameLayout.LayoutParams(ctx.dp(144), ctx.dp(ChromeMath.TOUCH_DP), Gravity.CENTER))
        val cluster = ctx.horizontal()
        rotation = ctx.iconButton(R.drawable.ic_screen_rotation, ROTATION_LOCK) { actions.onRotation() }
        rotation.setOnLongClickListener { actions.onRotationChooser(); true }
        cluster.addView(rotation)
        pin = ctx.iconButton(R.drawable.ic_push_pin, PIN_SET) { actions.onPinHere() }
        pin.setOnLongClickListener { v -> ctx.toast(v.contentDescription); true }
        cluster.addView(pin)
        labelRow.addView(cluster, FrameLayout.LayoutParams(WRAP_CONTENT, ctx.dp(ChromeMath.TOUCH_DP), Gravity.END or Gravity.CENTER_VERTICAL).apply {
            marginEnd = ctx.dp(4)
        })
        seekInfo = ctx.label("", 16f, bold = true, maxLines = 2).apply {
            gravity = Gravity.CENTER
            setPadding(ctx.dp(16), ctx.dp(10), ctx.dp(16), ctx.dp(10))
            visibility = View.GONE
        }
        seek = chromeSeekBar(seekThumb).apply {
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
        seekRow.addView(plainIcon(R.drawable.ic_skip_previous, "이전 챕터") { actions.onChapter(false) })
        seekRow.addView(seek, lp(0, ctx.dp(48), 1f))
        seekRow.addView(plainIcon(R.drawable.ic_skip_next, "다음 챕터") { actions.onChapter(true) })
        panelRows.addView(seekRow, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            topMargin = ctx.dp(ChromeMath.SEEK_TOP_DP)
        })
        panelRows.addView(labelRow, FrameLayout.LayoutParams(MATCH_PARENT, ctx.dp(ChromeMath.LABEL_ROW_DP)))
        bottom.addView(panelRows, lp())
        top.visibility = View.GONE
        bottom.visibility = View.GONE
        paint()
        padTop()
        padBottom()
    }

    /** An icon button of the chrome in the text colour: 24 dp glyph, 48 dp touch target (U §2.1). */
    private fun plainIcon(res: Int, description: String, onClick: (View) -> Unit): ImageButton =
        ctx.iconButton(res, description, onClick = onClick).also { icons.add(it) }

    /** An icon button of the brightness row, on the page colour (the history row's colour for its glyph). */
    private fun pageIcon(res: Int, description: String, onClick: (View) -> Unit): ImageButton =
        ctx.iconButton(res, description, onClick = onClick).also { pageIcons.add(it) }

    /**
     * Brightness and page bars alike (U §2.2): a rounded track (inactive part in the track colour, progress in the
     * accent) and a dot ([sizeSliders]: 2 / 16 dp on a phone, 3 / 18 dp on e-ink), in a 48 dp tall touch area (its row
     * gives it exactly 48 dp: the platform measure would add the theme's minimum track height). The platform thumb is
     * an animated selector (it grows on press: several e-ink updates), so the thumb is a plain dot. Vertical swipes
     * over the bars belong to the system (home, recents, notifications): [SwipeSafeSeekBar].
     */
    private fun chromeSeekBar(thumbDot: Drawable): SeekBar = SwipeSafeSeekBar(ctx).apply {
        progressDrawable = track()
        setThumb(this, thumbDot)
        background = null
        splitTrack = false
        minimumHeight = ctx.dp(48)
        setPadding(ctx.dp(12), ctx.dp(15), ctx.dp(12), ctx.dp(15))
    }

    private fun setThumb(bar: SeekBar, d: Drawable) {
        bar.thumb = d
        bar.thumbOffset = d.intrinsicWidth / 2
    }

    /**
     * The track: two white rounded bars (coloured by the bar's tint lists), [TRACK_DP] tall (e-ink [TRACK_DP_EINK]) and
     * centred whatever height the platform gives the track; the progress layer is clipped to the progress.
     */
    private fun track(): Drawable {
        val h = ctx.dp(if (slidersEink) TRACK_DP_EINK else TRACK_DP)
        fun bar() = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = h / 2f
        }
        return LayerDrawable(arrayOf(bar(), ClipDrawable(bar(), Gravity.START, ClipDrawable.HORIZONTAL))).apply {
            setId(0, android.R.id.background)
            setId(1, android.R.id.progress)
            for (i in 0..1) {
                setLayerGravity(i, Gravity.CENTER_VERTICAL or Gravity.FILL_HORIZONTAL)
                setLayerHeight(i, h)
            }
        }
    }

    /**
     * A [THUMB_DP] thumb (e-ink [THUMB_DP_EINK]): a solid accent dot, or ([hollow], the brightness bar's auto look) a
     * ring of the text colour filled with the page colour its row sits on ([paintDot]).
     */
    private fun dot(hollow: Boolean): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        val d = ctx.dp(if (slidersEink) THUMB_DP_EINK else THUMB_DP)
        setSize(d, d)
        paintDot(this, hollow)
    }

    /**
     * The sliders' sizes for the device class (U §2.2): ReadEra's lighter 2 dp track and 16 dp thumb on a phone, 3 / 18
     * dp on e-ink; the drag area stays the 48 dp row. New drawables only when the class changes (once, when the probe
     * says phone, in the update that shows the bars); [paint] colours them next.
     */
    private fun sizeSliders(eink: Boolean) {
        if (eink == slidersEink) return
        slidersEink = eink
        seekThumb = dot(hollow = false)
        manualThumb = dot(hollow = false)
        autoDot = null
        seek.progressDrawable = track()
        brightnessBar.progressDrawable = track()
        setThumb(seek, seekThumb)
        setThumb(brightnessBar, if (boundAuto == true) autoThumb() else manualThumb)
    }

    private fun paintDot(d: GradientDrawable, hollow: Boolean) {
        if (hollow) {
            d.setColor(look.page)
            d.setStroke(ctx.dpF(1.5f).toInt().coerceAtLeast(1), look.text)
        } else {
            d.setColor(look.accent)
        }
    }

    private fun autoThumb(): GradientDrawable = autoDot ?: dot(hollow = true).also { autoDot = it }

    /** The line above the options panel ([paintRule] sets its colour and insets). */
    private fun ruleLine(): View = View(ctx).apply { layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 1) }

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

    // ------------------------------------------------------------------ look (U §2.1)

    /**
     * The chrome's colours for [page] on a device of class [eink] (null = not probed yet: the e-ink set). Stored only
     * while the bars are hidden (nothing is drawn before they show: the colours change in the same update that shows
     * them); applied at once while they are up.
     */
    fun setLook(page: PagePalette, eink: Boolean?) {
        val want = ChromePalette.of(page, eink)
        if (want === wanted) return
        wanted = want
        if (shown) applyLook()
    }

    private fun applyLook() {
        if (look === wanted) return
        look = wanted
        paint()
    }

    /**
     * Colours every view built so far with [look] (and sizes the sliders for its device class); views built later
     * (option rows, the NONE link) take it then.
     */
    private fun paint() {
        val k = look
        if (top.setLook(k)) padTop()
        if (bottom.setLook(k)) padBottom()
        sizeSliders(k.eink)
        val ink = ColorStateList.valueOf(k.text)
        for (b in icons) {
            b.imageTintList = ink
            b.background = ctx.chromeIconBackground(k, false)
        }
        val onPage = ColorStateList.valueOf(k.hist)
        for (b in pageIcons) {
            b.imageTintList = onPage
            b.background = ctx.chromeIconBackground(k, false)
        }
        paintToggle(bookmark, boundBookmarked == true)
        paintToggle(pin, boundPinned == true)
        paintToggle(rotation, boundRotationLocked == true)
        title.setTextColor(k.text)
        paintRule(optionsLine)
        val trackTint = ColorStateList.valueOf(k.track)
        val accentTint = ColorStateList.valueOf(k.accent)
        seek.progressBackgroundTintList = trackTint
        seek.progressTintList = accentTint
        brightnessBar.progressBackgroundTintList = trackTint
        brightnessBar.progressTintList = if (boundAuto == true) trackTint else accentTint
        paintDot(seekThumb, hollow = false)
        paintDot(manualThumb, hollow = false)
        autoDot?.let { paintDot(it, hollow = true) }
        pageLabel.setTextColor(k.text)
        pageLabel.background = ctx.chromePressed(k, 8f)
        boundShown?.let { pageLabel.text = pendingText(it) }
            ?: run { if (boundLabel.isNotEmpty()) pageLabel.text = pageLabelText(boundLabel) }
        seekInfo.setTextColor(k.text)
        seekInfo.background = previewBox()
        unavailableLink?.let { paintLink(it) }
        rows?.paint()
    }

    /** A state that stays (bookmark, pin, rotation lock): the accent while on, with its circle on a phone (U §2.1). */
    private fun paintToggle(b: ImageButton, on: Boolean) {
        b.imageTintList = ColorStateList.valueOf(if (on) look.accent else look.text)
        b.background = ctx.chromeIconBackground(look, on)
    }

    /** The rule colour; inset to the text keylines (20 dp, 16 dp) on a phone, the full width on e-ink as before. */
    private fun paintRule(v: View) {
        v.setBackgroundColor(look.rule)
        val lp = v.layoutParams as LinearLayout.LayoutParams
        val start = if (look.eink) 0 else ctx.dp(20)
        val end = if (look.eink) 0 else ctx.dp(16)
        if (lp.marginStart != start || lp.marginEnd != end) {
            lp.marginStart = start
            lp.marginEnd = end
            v.layoutParams = lp
        }
    }

    /**
     * The seek preview's box over the page text: the surface with a rounded 1 px border in the track colour on a phone
     * (the divider would melt into the page), a square 1 dp edge on e-ink.
     */
    private fun previewBox(): Drawable = GradientDrawable().apply {
        setColor(look.surface)
        if (look.eink) {
            setStroke(ctx.dp(1).coerceAtLeast(1), look.edge)
        } else {
            setStroke(1, look.track)
            cornerRadius = ctx.dpF(8f)
        }
    }

    /** "3 / 183": the current page bold, the total after it at [TOTAL_SCALE] in the secondary colour (U §2.4). */
    /** [pageLabelText]'s total style (smaller, grey) over the whole of [text]. */
    private fun pendingText(text: String): CharSequence {
        val s = SpannableString(text)
        s.setSpan(RelativeSizeSpan(TOTAL_SCALE), 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        s.setSpan(ForegroundColorSpan(look.text2), 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return s
    }

    private fun pageLabelText(label: String): CharSequence {
        val cut = ReaderFormat.pageLabelCut(label)
        val head = if (cut < 0) label.length else cut
        val s = SpannableString(label)
        s.setSpan(StyleSpan(Typeface.BOLD), 0, head, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (cut >= 0) {
            s.setSpan(RelativeSizeSpan(TOTAL_SCALE), cut, label.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            s.setSpan(ForegroundColorSpan(look.text2), cut, label.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return s
    }

    // ------------------------------------------------------------------ show / hide (U §2.1 Motion)

    /**
     * Shows or hides both bars; hiding also closes the seek preview and the options panel. On a phone whose system
     * animates ([ChromeMath.animates]) the bars fade and slide [ChromeMath.SLIDE_DP] from their edge
     * ([ChromeMath.SHOW_MS] in, [ChromeMath.HIDE_MS] out; a new touch passes to the page while they leave, a drag
     * already on a bar ends there, and an open options panel closes once its bar is gone); otherwise they appear and
     * vanish in one frame. A pending look is applied before the bars show, in that same update.
     */
    fun setVisible(visible: Boolean) {
        if (visible) {
            applyLook()
            sizeForWidth()
        }
        var fade = false
        if (visible != shown) {
            shown = visible
            // The motion flag first: e-ink never asks the system for its animation scale.
            fade = look.motion && ChromeMath.animates(true, animatorScale())
            move(top, visible, fade, -1f)
            move(bottom, visible, fade, 1f)
            if (ReaderPerf.turns) {
                val how = if (!fade) "instant" else "fade ${if (visible) ChromeMath.SHOW_MS else ChromeMath.HIDE_MS}"
                Log.d(ReaderPerf.TAG, "chrome ${if (visible) "show" else "hide"} $how")
            }
        }
        if (!visible) {
            dropHeldSeek()
            showSeekInfo(false)
            if (!fade) setBrightnessOptionsOpen(false)
        }
    }

    /** One bar in or out; [side] = −1 for the top bar (it slides up when leaving), 1 for the bottom one. */
    private fun move(bar: ChromeBar, visible: Boolean, fade: Boolean, side: Float) {
        // Only a bar that moved has an animator to stop (e-ink never creates one).
        if (fade || bar.inert || bar.alpha != 1f || bar.translationY != 0f) bar.animate().cancel()
        if (!fade) {
            bar.inert = false
            if (bar.alpha != 1f) bar.alpha = 1f
            if (bar.translationY != 0f) bar.translationY = 0f
            val v = if (visible) View.VISIBLE else View.GONE
            if (bar.visibility != v) bar.visibility = v
            return
        }
        val slide = ctx.dpF(ChromeMath.SLIDE_DP.toFloat()) * side
        if (visible) {
            if (bar.visibility != View.VISIBLE) {
                bar.alpha = 0f
                bar.translationY = slide
                bar.visibility = View.VISIBLE
            }
            bar.inert = false
            bar.animate().alpha(1f).translationY(0f).setDuration(ChromeMath.SHOW_MS)
                .setInterpolator(SHOW_EASE).withLayer()
        } else {
            if (bar.visibility != View.VISIBLE) return
            bar.inert = true
            bar.animate().alpha(0f).translationY(slide).setDuration(ChromeMath.HIDE_MS)
                .setInterpolator(HIDE_EASE).withLayer().withEndAction {
                    if (!shown) {
                        bar.visibility = View.GONE
                        bar.alpha = 1f
                        bar.translationY = 0f
                        bar.inert = false
                        if (bar === top) setBrightnessOptionsOpen(false)
                    }
                }
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

    /** The bars are shown (or fading in); false as soon as a hide starts. */
    val isVisible: Boolean get() = shown

    fun setInsets(left: Int, topInset: Int, right: Int, bottomInset: Int) {
        insetTop = topInset
        barInsetBottom = bottomInset
        insetLeft = left
        insetRight = right
        padTop()
        padBottom()
        if (isVisible) sizeForWidth()   // returns at once when the row width is unchanged (also after a rotation)
    }

    /**
     * The top bar: one inset on top (the status bar or cutout band, in the surface colour); below, the edge band under
     * the options while they are open (the brightness row alone ends the bar with nothing under it).
     */
    private fun padTop() {
        val b = if (optionsOpen) top.edgeArea else 0
        val same = top.paddingLeft == insetLeft && top.paddingTop == insetTop && top.paddingRight == insetRight &&
            top.paddingBottom == b
        if (!same) top.setPadding(insetLeft, insetTop, insetRight, b)
    }

    /**
     * The bottom strip the system keeps for its own swipes (home, recents: the mandatory gesture inset), and whether the
     * window [floats] above the display's bottom edge (the upper window of a split screen, a pop-up window). The bottom
     * bar sits above the strip, and at least [ChromeMath.BOTTOM_GAP_DP] above the screen edge (ReadEra's gap, the user's
     * reference, 2026-10-05; fullscreen hides the navigation bar, so its inset is 0 while the handle still shows): a
     * swipe that starts at the edge never lands on the page bar or the chapter buttons. A floating window without any
     * bottom inset ends its panel at its own edge, with ReadEra's 85 dp of rows ([ChromeMath.bottomGap]).
     */
    fun setGestureBottom(px: Int, floats: Boolean) {
        if (gestureBottom == px && floating == floats) return
        gestureBottom = px
        floating = floats
        padBottom()
    }

    private var barInsetBottom = 0
    private var gestureBottom = 0
    private var floating = false

    /** The edge band on top; below, [ChromeMath.bottomGap]: the bar inset, the gesture strip or the gap (never a sum). */
    private fun padBottom() {
        val t = bottom.edgeArea
        val b = ChromeMath.bottomGap(barInsetBottom, gestureBottom, ctx.dp(ChromeMath.BOTTOM_GAP_DP), floating)
        val same = bottom.paddingLeft == insetLeft && bottom.paddingTop == t && bottom.paddingRight == insetRight &&
            bottom.paddingBottom == b
        if (!same) bottom.setPadding(insetLeft, t, insetRight, b)
    }

    fun setTitle(text: CharSequence) {
        if (title.text.toString() != text.toString()) title.text = text
    }

    /**
     * Page label and seek position (ignored while the user drags the seek bar). The label's content description
     * carries the page ("페이지 이동, 3 / 167"); it is set with the text, so it costs nothing while the chrome is hidden.
     */
    /**
     * The page label and the seek bar. [shown] (when not null) is drawn instead of [label], small and grey: "쪽수 계산
     * 중" while the pages are counted; the description reads it too.
     */
    fun setPage(label: String, max: Int, progress: Int, shown: String? = null) {
        if (isSeeking) return
        if (label != boundLabel || shown != boundShown) {
            // While [shown] stays, a changed estimate redraws nothing (no e-ink update without a visible change).
            val redraw = shown == null || shown != boundShown
            boundLabel = label
            boundShown = shown
            if (redraw) {
                pageLabel.text = if (shown == null) pageLabelText(label) else pendingText(shown)
                pageLabel.contentDescription = "$PAGE_LABEL, ${shown ?: label}"
            }
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
        paintToggle(bookmark, on)
    }

    /**
     * The pin's icon and content description (U §3.1): filled, "고정 해제", while this page is the newest place to go
     * back to (ReturnNav.pinnedHere); otherwise the outline, "이 페이지 고정".
     */
    fun setPinned(on: Boolean) {
        if (boundPinned == on) return
        boundPinned = on
        pin.setImageResource(if (on) R.drawable.ic_push_pin_fill else R.drawable.ic_push_pin)
        pin.contentDescription = if (on) PIN_RELEASE else PIN_SET
        paintToggle(pin, on)
    }

    fun setRotationLocked(locked: Boolean) {
        if (boundRotationLocked == locked) return
        boundRotationLocked = locked
        rotation.setImageResource(if (locked) R.drawable.ic_screen_lock_rotation else R.drawable.ic_screen_rotation)
        // What a tap does: unlock while locked.
        rotation.contentDescription = if (locked) ROTATION_UNLOCK else ROTATION_LOCK
        paintToggle(rotation, locked)
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
        brightnessBar.progressTintList = ColorStateList.valueOf(if (auto) look.track else look.accent)
        brightnessBar.thumb = if (auto) autoThumb() else manualThumb
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
        padTop()
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
            setOnClickListener { light.onOpenPanel() }
        }
        paintLink(link)
        brightnessRow.addView(link, 0, lp(0, ctx.dp(48), 1f))
        unavailableLink = link
    }

    /** The NONE link on the page colour, in the history row's colour like the row's icons. */
    private fun paintLink(link: TextView) {
        link.setTextColor(look.hist)
        val start = tinted(R.drawable.ic_brightness_medium, 24)
        link.setCompoundDrawablesRelative(start, null, tinted(R.drawable.ic_chevron_right, 18), null)
        link.background = ctx.chromePressed(look, 8f)
    }

    private fun tinted(res: Int, sizeDp: Int): Drawable {
        val d = ctx.getDrawable(res)!!.mutate()
        val s = ctx.dp(sizeDp)
        d.setBounds(0, 0, s, s)
        d.setTintList(ColorStateList.valueOf(look.hist))
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
        r.paint()
    }

    /** The panel's rows in order: question (while asked), swipe switch, device switch, device light settings link. */
    private inner class OptionRows {
        private val askRow: LinearLayout
        private val askLine: View
        private val askText: TextView
        private val answers: List<TextView>
        val swipe: ToggleRow
        private val switchLine: View
        val device: ToggleRow
        private val panelLine: View
        private val panelRow: LinearLayout
        private val panelTitle: TextView
        private val panelSub: TextView
        private val panelChevron: ImageView

        init {
            askRow = ctx.horizontal {
                minimumHeight = ctx.dp(56)
                setPadding(ctx.dp(20), ctx.dp(4), ctx.dp(8), ctx.dp(4))
                visibility = View.GONE
            }
            askText = ctx.label("", 15f, maxLines = 2).keepAll()
            askRow.addView(askText, lp(0, WRAP_CONTENT, 1f))
            answers = listOf(answer("예", true), answer("아니요", false))
            for (a in answers) askRow.addView(a)
            optionsPanel.addView(askRow, lp())
            askLine = lightLine().apply { visibility = View.GONE }
            optionsPanel.addView(askLine)

            swipe = ToggleRow(SWIPE_TITLE) { on -> light.onSwipeSwitch(on) }
            optionsPanel.addView(swipe.row, lp())
            switchLine = lightLine()
            optionsPanel.addView(switchLine)
            device = ToggleRow(DEVICE_TITLE) { on -> light.onDeviceSwitch(on) }
            optionsPanel.addView(device.row, lp())

            panelLine = lightLine().apply { visibility = View.GONE }
            optionsPanel.addView(panelLine)
            panelRow = ctx.horizontal {
                minimumHeight = ctx.dp(56)
                setPadding(ctx.dp(20), ctx.dp(8), ctx.dp(16), ctx.dp(8))
                setOnClickListener { light.onOpenPanel() }
                visibility = View.GONE
            }
            val texts = ctx.vertical()
            panelTitle = ctx.label(PANEL_TITLE, 15f, maxLines = 1)
            texts.addView(panelTitle)
            panelSub = subtitle(LightPolicy.PANEL_SUBTITLE)
            texts.addView(panelSub)
            panelRow.addView(texts, lp(0, WRAP_CONTENT, 1f))
            panelChevron = ctx.icon(R.drawable.ic_chevron_right, 24)
            panelRow.addView(panelChevron)
            optionsPanel.addView(panelRow, lp())
        }

        private fun answer(text: String, yes: Boolean): TextView = ctx.label(text, 15f, bold = true, maxLines = 1).apply {
            gravity = Gravity.CENTER
            minWidth = ctx.dp(56)
            minHeight = ctx.dp(48)
            setPadding(ctx.dp(8), 0, ctx.dp(8), 0)
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

        fun paint() {
            val k = look
            askText.setTextColor(k.text)
            for (a in answers) {
                a.setTextColor(k.text)
                a.background = ctx.chromePressed(k, 8f)
            }
            for (line in listOf(askLine, switchLine, panelLine)) line.setBackgroundColor(k.divider)
            swipe.paint()
            device.paint()
            panelRow.background = ctx.chromePressed(k, 0f)
            panelTitle.setTextColor(k.text)
            panelSub.setTextColor(k.text2)
            panelChevron.imageTintList = ColorStateList.valueOf(k.text2)
        }
    }

    /**
     * One switch row: title 15 sp, subtitle 13 sp in the secondary colour (max 2 lines), the [InkToggle] ending at
     * W − 16 dp. Tapping anywhere toggles; the row is the one accessibility unit (checkable, checked = the toggle,
     * text = both lines).
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
                paintTitle()
            }
        }

        fun paint() {
            row.background = ctx.chromePressed(look, 0f)
            paintTitle()
            sub.setTextColor(look.text2)
            toggle.setColors(look.text, look.page, look.accent)
        }

        /** Disabled = the secondary colour (#555 on e-ink: it survives every waveform) and the subtitle's own words. */
        private fun paintTitle() {
            titleView.setTextColor(if (row.isEnabled) look.text else look.text2)
        }
    }

    private fun subtitle(text: String): TextView = ctx.label(text, 13f, color = look.text2, maxLines = 2).apply {
        keepAll()
        setPadding(0, ctx.dp(2), 0, 0)
    }

    /** A light separator inside a band group: 1 px in the divider colour, inset 20 dp left and 16 dp right. */
    private fun lightLine(): View = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 1).apply {
            marginStart = ctx.dp(20)
            marginEnd = ctx.dp(16)
        }
    }

    val bottomHeight: Int get() = bottom.height

    private companion object {
        const val PAGE_LABEL = "페이지 이동"
        const val PIN_SET = "이 페이지 고정"
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
        /** Slider track and thumb on a phone (U §2.2): ReadEra's 2 dp track, the spec's smallest thumb. */
        const val TRACK_DP = 2
        const val THUMB_DP = 16
        /** The same on e-ink: a heavier line survives every waveform. */
        const val TRACK_DP_EINK = 3
        const val THUMB_DP_EINK = 18
        /** The page label's total ("/ 183") against the current page: 17 sp → ≈ 14 sp, regular, in text2. */
        const val TOTAL_SCALE = 0.82f
        /** Decelerate in, accelerate out (Material's standard curves); built on the first fade (never on e-ink). */
        val SHOW_EASE by lazy(LazyThreadSafetyMode.NONE) { PathInterpolator(0f, 0f, 0.2f, 1f) }
        val HIDE_EASE by lazy(LazyThreadSafetyMode.NONE) { PathInterpolator(0.4f, 0f, 1f, 1f) }
    }
}
