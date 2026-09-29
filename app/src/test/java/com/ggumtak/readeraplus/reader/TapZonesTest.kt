package com.ggumtak.readeraplus.reader

import com.ggumtak.readeraplus.settings.AppSettings
import com.ggumtak.readeraplus.settings.TapAction
import com.ggumtak.readeraplus.settings.TapZoneMode
import org.junit.Assert.assertEquals
import org.junit.Test

class TapZonesTest {
    private val w = 720
    private val h = 1440
    private val defaults = AppSettings().customTapZones

    private fun at(mode: TapZoneMode, fx: Float, fy: Float, custom: List<TapAction> = defaults) =
        TapZones.actionAt(mode, custom, fx * w, fy * h, w, h)

    @Test
    fun leftRight() {
        assertEquals(TapAction.PREV, at(TapZoneMode.LEFT_RIGHT, 0.1f, 0.1f))
        assertEquals(TapAction.PREV, at(TapZoneMode.LEFT_RIGHT, 0.3f, 0.5f))
        assertEquals(TapAction.PREV, at(TapZoneMode.LEFT_RIGHT, 0.2f, 0.95f))
        assertEquals(TapAction.MENU, at(TapZoneMode.LEFT_RIGHT, 0.5f, 0.5f))
        assertEquals(TapAction.NEXT, at(TapZoneMode.LEFT_RIGHT, 0.5f, 0.1f))
        assertEquals(TapAction.NEXT, at(TapZoneMode.LEFT_RIGHT, 0.5f, 0.9f))
        assertEquals(TapAction.NEXT, at(TapZoneMode.LEFT_RIGHT, 0.9f, 0.5f))
    }

    @Test
    fun allNext() {
        assertEquals(TapAction.MENU, at(TapZoneMode.ALL_NEXT, 0.5f, 0.5f))
        assertEquals(TapAction.PREV, at(TapZoneMode.ALL_NEXT, 0.05f, 0.5f))
        assertEquals(TapAction.PREV, at(TapZoneMode.ALL_NEXT, 0.11f, 0.95f))
        assertEquals(TapAction.NEXT, at(TapZoneMode.ALL_NEXT, 0.13f, 0.5f))
        assertEquals(TapAction.NEXT, at(TapZoneMode.ALL_NEXT, 0.2f, 0.1f))
        assertEquals(TapAction.NEXT, at(TapZoneMode.ALL_NEXT, 0.5f, 0.9f))
        assertEquals(TapAction.NEXT, at(TapZoneMode.ALL_NEXT, 0.95f, 0.05f))
    }

    @Test
    fun topBottom() {
        assertEquals(TapAction.PREV, at(TapZoneMode.TOP_BOTTOM, 0.9f, 0.1f))
        assertEquals(TapAction.PREV, at(TapZoneMode.TOP_BOTTOM, 0.5f, 0.39f))
        assertEquals(TapAction.NEXT, at(TapZoneMode.TOP_BOTTOM, 0.1f, 0.61f))
        assertEquals(TapAction.NEXT, at(TapZoneMode.TOP_BOTTOM, 0.5f, 0.99f))
        assertEquals(TapAction.MENU, at(TapZoneMode.TOP_BOTTOM, 0.5f, 0.5f))
        assertEquals(TapAction.NEXT, at(TapZoneMode.TOP_BOTTOM, 0.1f, 0.5f))
        assertEquals(TapAction.NEXT, at(TapZoneMode.TOP_BOTTOM, 0.9f, 0.5f))
    }

    @Test
    fun customGridFollowsCells() {
        val grid = listOf(
            TapAction.TOC, TapAction.SEARCH, TapAction.BOOKMARK,
            TapAction.PREV, TapAction.MENU, TapAction.NEXT,
            TapAction.PREV_CHAPTER, TapAction.REFRESH, TapAction.NEXT_CHAPTER,
        )
        assertEquals(TapAction.TOC, at(TapZoneMode.CUSTOM, 0.1f, 0.1f, grid))
        assertEquals(TapAction.SEARCH, at(TapZoneMode.CUSTOM, 0.5f, 0.1f, grid))
        assertEquals(TapAction.BOOKMARK, at(TapZoneMode.CUSTOM, 0.9f, 0.1f, grid))
        assertEquals(TapAction.MENU, at(TapZoneMode.CUSTOM, 0.5f, 0.5f, grid))
        assertEquals(TapAction.NEXT_CHAPTER, at(TapZoneMode.CUSTOM, 0.99f, 0.99f, grid))
        assertEquals(TapAction.REFRESH, at(TapZoneMode.CUSTOM, 0.5f, 0.8f, grid))
    }

    @Test
    fun customWithoutMenuKeepsCentreMenu() {
        val allNext = List(9) { TapAction.NEXT }
        assertEquals(TapAction.MENU, at(TapZoneMode.CUSTOM, 0.5f, 0.5f, allNext))
        assertEquals(TapAction.NEXT, at(TapZoneMode.CUSTOM, 0.1f, 0.5f, allNext))
    }

    @Test
    fun customWrongSizeFallsBackToDefaults() {
        assertEquals(TapAction.PREV, at(TapZoneMode.CUSTOM, 0.1f, 0.5f, listOf(TapAction.NEXT)))
        assertEquals(TapAction.MENU, at(TapZoneMode.CUSTOM, 0.5f, 0.5f, emptyList()))
    }

    @Test
    fun boundariesAndCells() {
        assertEquals(0, TapZones.cell(0f, 0f, w, h))
        assertEquals(8, TapZones.cell(w - 1f, h - 1f, w, h))
        assertEquals(4, TapZones.cell(w / 3f, h / 3f, w, h))
        assertEquals(5, TapZones.cell(w * 2f / 3f, h / 2f, w, h))
        assertEquals(TapAction.NONE, TapZones.actionAt(TapZoneMode.LEFT_RIGHT, defaults, 1f, 1f, 0, 0))
    }

    @Test
    fun corners() {
        assertEquals(Corner.TOP_LEFT, TapZones.corner(10f, 10f, w, h))
        assertEquals(Corner.TOP_RIGHT, TapZones.corner(w - 10f, 10f, w, h))
        assertEquals(Corner.NONE, TapZones.corner(w / 2f, 10f, w, h))
        assertEquals(Corner.NONE, TapZones.corner(10f, h * 0.2f, w, h))
        assertEquals(Corner.NONE, TapZones.corner(w - 10f, h * 0.11f, w, h))
        assertEquals(Corner.TOP_RIGHT, TapZones.corner(w * 0.86f, h * 0.09f, w, h))
    }
}
