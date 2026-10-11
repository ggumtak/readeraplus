package com.ggumtak.readeraplus.ui.settings

import android.view.View
import android.widget.LinearLayout
import com.ggumtak.readeraplus.reader.extras.Fmt
import com.ggumtak.readeraplus.reader.extras.StatusUi
import com.ggumtak.readeraplus.settings.ReaderSettings
import com.ggumtak.readeraplus.settings.Settings
import com.ggumtak.readeraplus.settings.StatusItem
import com.ggumtak.readeraplus.ui.kit.chooser
import com.ggumtak.readeraplus.ui.kit.stepperRow

/**
 * "상태 표시줄" (reached from 읽기 설정 and the quick options): 위쪽 상태 표시줄 and 아래쪽 상태 표시줄 (three slots each,
 * titled by their place only, then 진행 막대 and 상태 글자 크기 with the bottom band; UI_SPEC §5.5, anchor §2.7). The
 * status bands have their own places (`StatusBands`): another item in a slot only repaints the page; a band that comes,
 * goes or changes height (진행 막대, 상태 글자 크기) re-lays it at the same first character.
 */
internal class StatusBarPage(a: SettingsActivity) : SettingsPage(a, SettingsActivity.PAGE_STATUS, "상태 표시줄") {
    /** The reading settings the rows show (읽기 설정's margins or a reset rebuild the page in [onShown]). */
    private var seenReader: ReaderSettings? = null
    /** The six slot rows, index band * 3 + pos. */
    private val slotRows = arrayOfNulls<View>(6)
    private var statusSizeRow: View? = null

    override fun build(): View {
        seenReader = Settings.reader
        val body = ctx.pageBody()
        addStatusBar(body)
        return ctx.pageScroll(body)
    }

    override fun onShown() {
        if (Settings.reader != seenReader) {
            activity.rebuildTop()
            return
        }
        updateStatusUi()
    }

    /** Saves a reading setting changed on this page; [onShown] then knows the rows already show it. */
    private inline fun editOwn(f: (ReaderSettings) -> ReaderSettings) {
        editReader(f)
        seenReader = Settings.reader
    }

    /**
     * The two bands, a section each ("위쪽 상태 표시줄" / "아래쪽 상태 표시줄"): six rows that read alike in one block
     * hid where the top band ends. A row says only its place (왼쪽 / 가운데 / 오른쪽, its header says the band); its
     * chooser's title says both ("아래 가운데").
     */
    private fun addStatusBar(body: LinearLayout) {
        val r = Settings.reader
        for (band in 0..1) {
            body.section(R3Rows.bandHeader(band))
            for (pos in 0..2) addSlotRow(body, r, band, pos)
        }
        body.addView(ctx.toggleRow("진행 막대", null, r.progressBar) { v ->
            editOwn { it.copy(progressBar = v) }
            updateStatusUi()
        })
        statusSizeRow = ctx.stepperRow("상태 글자 크기", r.statusFontSizeSp, 8f, 16f, 0.5f, Fmt::number) { v ->
            editOwn { it.copy(statusFontSizeSp = v) }
            updateStatusUi()
        }.liveStepperValue().also(body::addView)
        updateStatusUi()
    }

    private fun addSlotRow(body: LinearLayout, r: ReaderSettings, band: Int, pos: Int) {
        val k = band * 3 + pos
        slotRows[k] = ctx.valueRow(StatusUi.posWord(pos), r.slot(band, pos).label) {
            val all = StatusItem.entries
            ctx.chooser(R3Rows.slotTitle(band, pos), all.map { R3Rows.slotChoice(it) }, all.indexOf(Settings.reader.slot(band, pos))) { i ->
                if (Settings.reader.slot(band, pos) != all[i]) editOwn { it.withSlot(band, pos, all[i]) }
                slotRows[k]?.setSummary(all[i].label)
                updateStatusUi()
            }
        }.also(body::addView)
    }

    /** The size row shows while a band has text. */
    private fun updateStatusUi() {
        statusSizeRow?.setShown(R3Rows.hasStatusText(Settings.reader))
    }
}
