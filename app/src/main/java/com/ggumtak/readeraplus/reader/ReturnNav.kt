package com.ggumtak.readeraplus.reader

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import com.ggumtak.readeraplus.format.DocPosition

internal interface ReturnHost {                       // implemented by ReaderActivity (READER_A)
    val chromeVisible: Boolean
    fun currentPosition(): DocPosition                // paged: page start; scroll: top line
    fun isOnCurrentPage(pos: DocPosition): Boolean    // paged: on the page; scroll: in the visible range
    fun globalPageOf(pos: DocPosition): Int           // 1-based; estimate until counted (never "~")
    /** Jump without creating a return point; the chrome stays as it is. Scroll mode: top-line placement. */
    fun jumpToReturn(pos: DocPosition)
    fun charProgressOf(pos: DocPosition): Float       // counts.charProgress
    fun locateFraction(f: Float): DocPosition         // counts.locateFraction
    fun textSignature(): String?                      // LayoutKeys.textSignature(...) for TXT, null for EPUB
    fun saveReturnMark(text: String?)                 // IO write
    fun onReturnChanged()                             // host: chrome.setPinned(...), updateChipPosition()
}

internal class ReturnNav(ctx: Context, private val host: ReturnHost) {
    val dock: View = FrameLayout(ctx).apply { visibility=View.GONE }
    val chip: View = FrameLayout(ctx).apply { visibility=View.GONE }
    val pinned: Boolean get() = false
    fun markOnScreen(): Boolean = false // R3 stub (owner: RU)
    fun onJump(from: DocPosition) {} // R3 stub (owner: RU)
    fun onManualTurn() {} // R3 stub (owner: RU)
    fun onPinPressed() {} // R3 stub (owner: RU)
    fun onChromeShown() {} // R3 stub (owner: RU)
    fun onChromeHidden() {} // R3 stub (owner: RU)
    fun bind() {} // R3 stub (owner: RU)
    fun restore(saved: String?) {} // R3 stub (owner: RU)
    fun markFraction(): Float = Float.NaN // R3 stub (owner: RU)
    fun reparsed(fraction: Float, exact: Boolean) {} // R3 stub (owner: RU)
    fun reset() {} // R3 stub (owner: RU)
    companion object { const val PIN_FLOATS = false }
}
internal class ReturnPoints {
    enum class Chip { NONE, MARK, OTHER }
    var mark: DocPosition? = null; var pinned=false; var other: DocPosition?=null
    var offer=Chip.NONE; var turns=0; var chainOffer=Chip.NONE; var landed=false
    fun pin(here: DocPosition, onMark: Boolean) {} // R3 stub (owner: RU)
    fun jumped(from: DocPosition, fromOnMark: Boolean) {} // R3 stub (owner: RU)
    fun useMark(here: DocPosition, onMark: Boolean): DocPosition? = null // R3 stub (owner: RU)
    fun useOther(here: DocPosition, onMark: Boolean): DocPosition? = null // R3 stub (owner: RU)
    fun clear() {} // R3 stub (owner: RU)
    fun manualTurn(): Boolean = false // R3 stub (owner: RU)
    fun hideChip() {} // R3 stub (owner: RU)
    fun restorePinned(pos: DocPosition) {} // R3 stub (owner: RU)
    fun reparsed(p: DocPosition?) {} // R3 stub (owner: RU)
}
internal object ReturnMarkCodec {
    class Mark(val pos: DocPosition, val fraction: Float, val sig: String?)
    fun encode(pos: DocPosition, fraction: Float, sig: String?): String = TODO("owner: RU")
    fun decode(text: String?): Mark? = null // R3 stub (owner: RU)
}
