package com.ggumtak.readeraplus.reader.extras

import com.ggumtak.readeraplus.engine.PageBreakMode
import com.ggumtak.readeraplus.settings.ReaderSettings
import com.ggumtak.readeraplus.settings.SideMargin
import com.ggumtak.readeraplus.settings.StatusItem
import com.ggumtak.readeraplus.settings.VerticalMargin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reading-settings popup geometry (U polish 7, 8; U §5.5) and its "상태 표시" / margin wording. */
class PopupGeometryTest {

    // ------------------------------------------------------------------ width / top / height

    @Test
    fun widthIsTheScreenLess16dpCappedAt400dp() {
        assertEquals(16, PopupGeometry.SIDE_GAP_DP)
        assertEquals(400, PopupGeometry.MAX_WIDTH_DP)
        // The Comet, 720 px wide at 2.0 (360 dp): 344 dp, 8 dp on each side (the popup is centred).
        assertEquals(688, PopupGeometry.width(720, 2f))
        // 1600 px at 2.0 (800 dp): capped at 400 dp.
        assertEquals(800, PopupGeometry.width(1600, 2f))
        // 1080 px at 3.0 (360 dp): 344 dp.
        assertEquals(1032, PopupGeometry.width(1080, 3f))
        // 832 px at 2.0 (416 dp): exactly the cap either way.
        assertEquals(800, PopupGeometry.width(832, 2f))
        assertTrue(PopupGeometry.width(100, 2f) <= 100)
        assertEquals(1, PopupGeometry.width(0, 2f))
        assertEquals(1, PopupGeometry.width(10, 2f))
    }

    @Test
    fun topIsTheInsetPlus8dpAndHeightAtMost56Percent() {
        assertEquals(0.56f, PopupGeometry.HEIGHT_FRACTION, 0f)
        assertEquals(0.62f, PopupGeometry.SETTINGS_FRACTION, 0f)
        // Status bar 48 px at 2.0: top 48 + 16; 62% of 1440 = 892 px.
        val p = PopupGeometry.settings(1440, 48, 2f)
        assertEquals(64, p.top)
        assertEquals(892, p.height)
        // Immersive (no inset): 8 dp from the top.
        assertEquals(16, PopupGeometry.settings(1440, 0, 2f).top)
        // A negative inset never puts it above the window.
        assertEquals(16, PopupGeometry.settings(1440, -10, 2f).top)
        // Little room (landscape / split screen): at least 160 dp, moved up to stay on screen.
        val q = PopupGeometry.settings(600, 480, 2f)
        assertEquals(320, q.height)
        assertEquals(600 - 16 - 320, q.top)
        // Tiny window: never taller than 62%.
        val r = PopupGeometry.settings(400, 380, 2f)
        assertTrue(r.height <= 248)
        assertTrue(r.top >= 0 && r.top + r.height <= 400)
    }

    @Test
    fun quickOptionsNeverScrollAt1440px() {
        // 전체 읽기 설정 › · 닫기 / 글자 크기 / 굵기 / 줄 간격 / 문단 간격 / 좌우 여백 / 상하 여백 / 글꼴 / 배경 (2026-10-05).
        assertEquals(8, PopupGeometry.QUICK_ROWS)
        assertEquals(48, Compact.ROW_DP)
        assertEquals(48, Compact.BAR_DP)
        assertEquals(48, Compact.STEP_DP)
        assertEquals(48, Compact.LIST_ROW_DP)
        assertEquals(16f, Compact.LABEL_SP, 0f)
        assertEquals(17f, Compact.VALUE_SP, 0f)
        assertEquals(432, PopupGeometry.QUICK_HEIGHT_DP)
        // Emulator / Comet: 720×1440 px at 2.0, with and without a status bar (up to 32 dp).
        for (inset in intArrayOf(0, 48, 64)) {
            val place = PopupGeometry.settings(1440, inset, 2f)
            val whole = Math.round(PopupGeometry.QUICK_HEIGHT_DP * 2f) // 864 px
            // The whole popup + the 1 px border on each side.
            assertTrue("inset $inset: popup ${whole + 2} px > ${place.height} px", whole + 2 <= place.height)
        }
        // Every button is the 48 dp minimum touch target and fits its row.
        assertTrue(Compact.STEP_DP >= 48 && Compact.STEP_DP <= Compact.ROW_DP)
        assertTrue(Compact.STEP_DP <= Compact.BAR_DP)
    }

    @Test
    fun quickFieldsCarryTheSevenQuickSettings() {
        val base = ReaderSettings(marginLeftDp = 30, invert = true, txtBlankLines = 2, lineHeightPct = 150)
        val src = ReaderSettings(fontSizeSp = 23f, fontWeight = 600, lineHeightPct = 190, paragraphSpacingPct = 40,
            fontId = "x", indentPct = 300, invert = false, txtBlankLines = 0, marginLeftDp = 5, marginRightDp = 5,
            marginTopDp = 60, marginBottomDp = 60, pageMargins = false)
        val out = QuickFields.onto(base, src)
        assertEquals(23f, out.fontSizeSp, 0f)
        assertEquals(600, out.fontWeight)
        assertEquals(190, out.lineHeightPct)
        assertEquals(40, out.paragraphSpacingPct)
        assertEquals("x", out.fontId)
        assertEquals(listOf(5, 5, 60, 60), listOf(out.marginLeftDp, out.marginRightDp, out.marginTopDp, out.marginBottomDp))
        assertFalse(out.pageMargins)
        // Everything else stays the saved settings' (a stale popup copy never overwrites 설정's changes).
        assertEquals(300, src.indentPct)
        assertEquals(base.indentPct, out.indentPct)
        assertTrue(out.invert)
        assertEquals(2, out.txtBlankLines)
        assertEquals(
            base.copy(fontSizeSp = 23f, fontWeight = 600, lineHeightPct = 190, paragraphSpacingPct = 40, fontId = "x",
                marginLeftDp = 5, marginRightDp = 5, marginTopDp = 60, marginBottomDp = 60, pageMargins = false),
            out,
        )
    }

    @Test
    fun aMarginStepTurnsTheMarginsOn() {
        val off = ReaderSettings(pageMargins = false)
        val side = QuickFields.withSide(off, -10)
        assertTrue(side.pageMargins)
        assertEquals(SideMargin.toDp(-10), side.marginLeftDp)
        assertEquals(SideMargin.toDp(-10), side.marginRightDp)
        val vertical = QuickFields.withVertical(off, QuickFields.verticalUi(off), 6)
        assertTrue(vertical.pageMargins)
        // "0" is the default margin, the same value 읽기 설정 shows (top and bottom: each its own), and a step there and
        // back returns to it.
        val d = ReaderSettings()
        assertEquals(d.marginLeftDp, QuickFields.withSide(off, 0).marginLeftDp)
        val up = QuickFields.withVertical(d, 0, 2)
        assertEquals(listOf(VerticalMargin.topDp(2), VerticalMargin.bottomDp(2)), listOf(up.marginTopDp, up.marginBottomDp))
        assertEquals(d, QuickFields.withVertical(up, 2, 0))
        // On, a step changes its own axis only; 50/50 is off the defaults' line ("+35"), so both move by the step.
        val on = ReaderSettings(marginLeftDp = 30, marginRightDp = 30, marginTopDp = 50, marginBottomDp = 50)
        assertEquals(listOf(SideMargin.toDp(4), SideMargin.toDp(4), 50, 50), QuickFields.withSide(on, 4).let {
            listOf(it.marginLeftDp, it.marginRightDp, it.marginTopDp, it.marginBottomDp)
        })
        assertEquals(35, QuickFields.verticalUi(on))
        assertEquals(listOf(30, 30, 46, 46), QuickFields.withVertical(on, 35, 31).let {
            listOf(it.marginLeftDp, it.marginRightDp, it.marginTopDp, it.marginBottomDp)
        })
    }

    @Test
    fun whileTheMarginsAreOffTheSteppersStartFromTheMarginThePageHas() {
        // The defaults stored (20/20/15/22), 여백 사용 off: the page has the minimal 4 dp margins, and the steppers show
        // that ("−16"; "−11" from the top's 15 dp), not the stored "0".
        val tiny = com.ggumtak.readeraplus.reader.LayoutKeys.TINY_MARGIN_DP
        val off = ReaderSettings(pageMargins = false)
        assertEquals(SideMargin.toUi(tiny), QuickFields.sideUi(off))
        assertEquals(-16, QuickFields.sideUi(off))
        assertEquals(VerticalMargin.toUi(tiny, tiny), QuickFields.verticalUi(off))
        assertEquals(-11, QuickFields.verticalUi(off))
        assertEquals(0, QuickFields.sideUi(off.copy(pageMargins = true)))
        assertEquals(0, QuickFields.verticalUi(off.copy(pageMargins = true)))
        // "좌우 여백 줄이기": the sides narrow by one step from 4 dp, and top / bottom stay at the 4 dp they had.
        val narrower = QuickFields.withSide(off, QuickFields.sideUi(off) - SideMargin.UI_STEP)
        assertTrue(narrower.pageMargins)
        assertEquals(tiny - SideMargin.UI_STEP, narrower.marginLeftDp)
        assertEquals(tiny - SideMargin.UI_STEP, narrower.marginRightDp)
        assertEquals(tiny, narrower.marginTopDp)
        assertEquals(tiny, narrower.marginBottomDp)
        // "상하 여백 늘리기": the same from the other axis (top and bottom one step up from the 4 dp they had); the sides
        // keep their 4 dp.
        val shown = QuickFields.verticalUi(off)
        val taller = QuickFields.withVertical(off, shown, shown + VerticalMargin.UI_STEP)
        assertTrue(taller.pageMargins)
        assertEquals(tiny + VerticalMargin.UI_STEP, taller.marginTopDp)
        assertEquals(tiny + VerticalMargin.UI_STEP, taller.marginBottomDp)
        assertEquals(shown + VerticalMargin.UI_STEP, QuickFields.verticalUi(taller))
        assertEquals(tiny, taller.marginLeftDp)
        assertEquals(tiny, taller.marginRightDp)
    }

    // ------------------------------------------------------------------ drop-down lists

    @Test
    fun dropdownPlacementDefaultsTo56Percent() {
        // Fits under the row.
        val below = PopupGeometry.dropdown(1440, 300, 380, 400, 2f)
        assertEquals(380, below.top)
        assertEquals(400, below.height)
        // No room below: above the row.
        val above = PopupGeometry.dropdown(1440, 1000, 1080, 500, 2f)
        assertEquals(500, above.top)
        assertEquals(500, above.height)
        // Taller than 56%: capped (scrolls); no room either side → as low as fits.
        val tall = PopupGeometry.dropdown(1440, 600, 680, 2000, 2f)
        assertEquals(806, tall.height)
        assertEquals(1440 - 16 - 806, tall.top)
        // Right-aligned to the anchor, kept on screen.
        assertEquals(100, PopupGeometry.dropdownLeft(720, 700, 600))
        assertEquals(0, PopupGeometry.dropdownLeft(720, 500, 600))
        assertEquals(120, PopupGeometry.dropdownLeft(720, 800, 600))
        assertEquals(0, PopupGeometry.dropdownLeft(500, 500, 600))
    }

    @Test
    fun dropdownMaxHeightFraction() {
        assertEquals(0.8f, PopupGeometry.TALL_LIST_FRACTION, 0f)
        // A 12-item list × 48 dp = 1152 px fits whole under 0.8 × 1440 = 1152 px.
        val rows = StatusItem.entries.size * Compact.LIST_ROW_DP * 2
        val slot = PopupGeometry.dropdown(1440, 100, 188, rows, 2f, maxHeightFraction = 0.8f)
        assertEquals(rows, slot.height)
        assertEquals(188, slot.top)
        // Capped at 80% when longer, and still inside the window edges.
        val capped = PopupGeometry.dropdown(1440, 100, 188, 3000, 2f, maxHeightFraction = 0.8f)
        assertEquals(1152, capped.height)
        assertTrue(capped.top >= 0 && capped.top + capped.height <= 1440)
        // The same list under the default 56% cap would scroll.
        assertEquals(806, PopupGeometry.dropdown(1440, 100, 188, 3000, 2f).height)
        // Out-of-range fractions are clamped.
        assertEquals(1440, PopupGeometry.maxHeight(1440, 5f))
        assertEquals(144, PopupGeometry.maxHeight(1440, 0f))
    }

    // ------------------------------------------------------------------ margins

    @Test
    fun marginSteppersSpeakSignedValues() {
        assertEquals("−10", Fmt.signed(-10))
        assertEquals("−10", Fmt.signed(-10))
        assertEquals("+4", Fmt.signed(4))
        assertEquals("0", Fmt.signed(0))
        for (ui in SideMargin.UI_MIN..SideMargin.UI_MAX step SideMargin.UI_STEP) {
            assertEquals(SideMargin.label(ui), Fmt.signed(ui))
            assertEquals(VerticalMargin.label(ui), Fmt.signed(ui))
        }
        for (ui in VerticalMargin.UI_MIN..VerticalMargin.UI_MAX step VerticalMargin.UI_STEP)
            assertEquals(VerticalMargin.label(ui), Fmt.signed(ui))
        assertEquals(VerticalMargin.label(VerticalMargin.UI_MAX), Fmt.signed(VerticalMargin.UI_MAX))
        // "0" = 20 dp at the sides (MaruViewer), 15 / 22 dp at top and bottom (from the status bands); the defaults show "0".
        val d = ReaderSettings()
        assertEquals("0", Fmt.signed(SideMargin.toUi(d.marginLeftDp)))
        assertEquals("0", Fmt.signed(VerticalMargin.toUi(d.marginTopDp, d.marginBottomDp)))
        assertEquals("+10", Fmt.signed(SideMargin.toUi(30)))
        assertEquals("−10", Fmt.signed(VerticalMargin.toUi(5, 12)))
    }

    // ------------------------------------------------------------------ 상태 표시

    @Test
    fun slotDescriptions() {
        assertEquals("아래 오른쪽: 시계", StatusUi.slotDescription(1, 2, StatusItem.CLOCK))
        assertEquals("위 가운데: 챕터 제목", StatusUi.slotDescription(0, 1, StatusItem.CHAPTER))
        assertEquals("위 왼쪽: 없음", StatusUi.slotDescription(0, 0, StatusItem.NONE))
    }

    @Test
    fun sizeRowFollowsTheBands() {
        val d = ReaderSettings()
        assertTrue(StatusUi.showsSize(d))
        val none = d.copy(headerLeft = StatusItem.NONE, headerCenter = StatusItem.NONE, headerRight = StatusItem.NONE)
        assertFalse(none.hasHeader || none.hasFooterText)
        assertFalse(StatusUi.showsSize(none))
        assertTrue(StatusUi.showsSize(none.withSlot(1, 2, StatusItem.CLOCK)))
    }

    @Test
    fun widowSummaryFollowsPageBreak() {
        assertEquals("한 쪽보다 긴 문단에만", StatusUi.widowSummary(PageBreakMode.PARAGRAPH))
        assertEquals("문단 첫 줄 · 끝 줄이 홀로 남지 않게", StatusUi.widowSummary(PageBreakMode.LINE))
        assertEquals("화면 맨 아래 가는 선", StatusUi.PROGRESS_SUMMARY)
    }
}
