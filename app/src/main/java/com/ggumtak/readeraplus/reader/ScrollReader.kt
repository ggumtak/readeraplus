package com.ggumtak.readeraplus.reader

import android.graphics.Canvas
import com.ggumtak.readeraplus.engine.PageInfo
import com.ggumtak.readeraplus.engine.SectionLayout
import com.ggumtak.readeraplus.render.Highlight
import com.ggumtak.readeraplus.render.PageDecor
import com.ggumtak.readeraplus.render.PageRenderer

internal enum class Motion { STEP, SMOOTH }
internal class ScrollReader(private val view: PageView, private val host: Host) : PageView.ScrollInput, StripSource {
    interface Host {
        fun session(): BookSession?; fun renderer(): PageRenderer?; fun geometry(): PageGeometry?
        fun decor(): PageDecor; fun highlights(section: Int): List<Highlight>; fun unitGap(section: Int): Float
        fun onTopPageChanged(section: Int, page: Int); fun onSettled(kind: SettleKind, movedPx: Float)
        fun onBlocked(section: Int)
    }
    var motion = Motion.STEP
    val pos = ScrollPos()
    override val sectionCount: Int get() = host.session()?.sectionCount ?: 0
    override fun layoutOf(section: Int): SectionLayout? = TODO("owner: RC-S")
    override fun unitGap(section: Int): Float = host.unitGap(section)
    fun showAt(section: Int, layout: SectionLayout, offset: Int, placement: Placement, kind: SettleKind): Long = TODO("owner: RC-S")
    /** Every page command is instantaneous in both modes. */
    fun step(next: Boolean): Step = TODO("owner: RC-S")
    fun onSectionStored(section: Int, layout: SectionLayout): Unit = TODO("owner: RC-S")
    fun onGenerationChanged(): Unit = TODO("owner: RC-S")
    fun onHighlightsChanged(section: Int): Unit = TODO("owner: RC-S")
    fun onTrimMemory(): Unit = TODO("owner: RC-S")
    fun anchor(): Long = TODO("owner: RC-S")
    fun topPage(): Long = TODO("owner: RC-S")
    fun atBookEnd(): Boolean = TODO("owner: RC-S")
    fun virtualPage(): VirtualPage? = TODO("owner: RC-S")
    fun focusAt(y: Float): Boolean = TODO("owner: RC-S")
    fun clearFocus(): Unit = TODO("owner: RC-S")
    fun lineWhollyVisible(section: Int, offset: Int): Boolean = TODO("owner: RC-S")
    fun visibleRanges(visit: (section: Int, start: Int, end: Int) -> Unit): Unit = TODO("owner: RC-S")
    fun userMoving(): Boolean = TODO("owner: RC-S")
    fun detach(): Unit = TODO("owner: RC-S")
    fun onDeviceClass(): Unit = TODO("owner: RC-S")
    override val live: Boolean get() = motion == Motion.SMOOTH
    override fun isMoving(): Boolean = TODO("owner: RC-S")
    override fun stopMotion(): Boolean = TODO("owner: RC-S")
    override fun dragBy(dy: Float): Unit = TODO("owner: RC-S")
    override fun release(totalDy: Float, velocityY: Float): Unit = TODO("owner: RC-S")
    override fun cancelDrag(): Unit = TODO("owner: RC-S")
    override fun a11yStep(next: Boolean): Boolean = TODO("owner: RC-S")
    override fun computeScroll(): Unit = TODO("owner: RC-S")
    override fun draw(canvas: Canvas, width: Int, height: Int): Unit = TODO("owner: RC-S")
}
internal class VirtualPage(val section: Int, val layout: SectionLayout, val page: PageInfo, val pageIndex: Int)
