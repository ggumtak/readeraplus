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
        // Status bar 48 px at 2.0: top 48 + 16; 56% of 1440 = 806 px.
        val p = PopupGeometry.settings(1440, 48, 2f)
        assertEquals(64, p.top)
        assertEquals(806, p.height)
        // Immersive (no inset): 8 dp from the top.
        assertEquals(16, PopupGeometry.settings(1440, 0, 2f).top)
        // A negative inset never puts it above the window.
        assertEquals(16, PopupGeometry.settings(1440, -10, 2f).top)
        // Little room (landscape / split screen): at least 160 dp, moved up to stay on screen.
        val q = PopupGeometry.settings(600, 480, 2f)
        assertEquals(320, q.height)
        assertEquals(600 - 16 - 320, q.top)
        // Tiny window: never taller than 56%.
        val r = PopupGeometry.settings(400, 380, 2f)
        assertTrue(r.height <= 224)
        assertTrue(r.top >= 0 && r.top + r.height <= 400)
    }

    @Test
    fun mainSectionNeverScrollsAt1440px() {
        assertEquals(9, PopupGeometry.MAIN_ROWS)
        assertEquals(44, Compact.ROW_DP)
        assertEquals(44, Compact.STEP_DP)
        assertEquals(36, Compact.TOGGLE_DP)
        assertEquals(15f, Compact.LABEL_SP, 0f)
        assertEquals(16f, Compact.VALUE_SP, 0f)
        assertEquals(13f, Compact.HEADER_SP, 0f)
        // Emulator / Comet: 720×1440 px at 2.0, with and without a status bar (up to 32 dp).
        for (inset in intArrayOf(0, 48, 64)) {
            val place = PopupGeometry.settings(1440, inset, 2f)
            val main = Math.round(PopupGeometry.MAIN_ROWS * Compact.ROW_DP * 2f) // 792 px
            // Main section + the 1 px border on each side.
            assertTrue("inset $inset: main ${main + 2} px > ${place.height} px", main + 2 <= place.height)
        }
        // Stepper buttons and toggles make rows no taller than the others.
        assertTrue(Compact.STEP_DP <= Compact.ROW_DP)
        assertTrue(Compact.TOGGLE_DP <= Compact.ROW_DP)
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
        // The status slot chooser: 12 items × 40 dp = 960 px fits whole under 0.8 × 1440 = 1152 px.
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
        // "0" = 40 dp; the default shows "0".
        assertEquals("0", Fmt.signed(SideMargin.toUi(ReaderSettings().marginLeftDp)))
        assertEquals("0", Fmt.signed(VerticalMargin.toUi(ReaderSettings().marginTopDp)))
        assertEquals("−10", Fmt.signed(SideMargin.toUi(30)))
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
        val none = d.withSlot(0, 1, StatusItem.NONE)
        assertFalse(none.hasHeader || none.hasFooterText)
        assertFalse(StatusUi.showsSize(none))
        assertTrue(StatusUi.showsSize(none.withSlot(1, 2, StatusItem.CLOCK)))
    }

    @Test
    fun fitNoteWhenAMarginIsTooSmall() {
        val d = ReaderSettings()
        assertFalse(StatusUi.showsFitNote(d))
        // Header in a 4 dp top margin.
        assertTrue(StatusUi.showsFitNote(d.copy(marginTopDp = 4, marginBottomDp = 4)))
        // "페이지 여백" off = 4 dp margins.
        assertTrue(StatusUi.showsFitNote(d.copy(pageMargins = false)))
        // No band with items: never a note.
        val none = d.withSlot(0, 1, StatusItem.NONE)
        assertFalse(StatusUi.showsFitNote(none.copy(pageMargins = false)))
        // Footer text above the 12 dp progress lane: 20 dp fits without the line, not with it.
        val footer = none.withSlot(1, 1, StatusItem.PAGE).copy(marginBottomDp = 20)
        assertTrue(StatusUi.showsFitNote(footer.copy(progressBar = true)))
        assertFalse(StatusUi.showsFitNote(footer.copy(progressBar = false)))
    }

    @Test
    fun widowSummaryFollowsPageBreak() {
        assertEquals("한 쪽보다 긴 문단에만 적용", StatusUi.widowSummary(PageBreakMode.PARAGRAPH))
        assertEquals("문단의 첫 줄/마지막 줄이 홀로 남지 않게", StatusUi.widowSummary(PageBreakMode.LINE))
        assertEquals("여백이 좁아 위 · 아래 정보가 보이지 않습니다. 상하 여백을 늘리세요.", StatusUi.FIT_NOTE)
        assertEquals("화면 맨 아래 가는 선", StatusUi.PROGRESS_SUMMARY)
    }
}
