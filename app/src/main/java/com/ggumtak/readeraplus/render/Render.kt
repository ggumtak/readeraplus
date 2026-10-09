package com.ggumtak.readeraplus.render

import android.content.Context

/*
 * Shared types of the render module (Android drawing side of the engine). Implementations live in
 * FontManager.kt, AndroidTextMeasurer.kt, ImageCache.kt, PageRenderer.kt, Covers.kt and Eink.kt.
 * See docs/ARCHITECTURE.md "render".
 */

enum class FontSource { BUNDLED, USER, SYSTEM }

class FontInfo(
    /** Stable id stored in ReaderSettings.fontId. */
    val id: String,
    /** Display name (Korean). */
    val name: String,
    val source: FontSource,
    /** Asset path (BUNDLED) or absolute file path. */
    val path: String,
    /** Bold face file, if shipped separately. */
    val boldPath: String? = null,
    /** Has a 'wght' variation axis. */
    val variable: Boolean = false,
    val serif: Boolean = true,
    /** The font's own weight, shown as 굵기 "기본": a variable font's default `wght`, else 400 (a static file as is). */
    val naturalWeight: Int = 400,
)

/** Range highlight kinds drawn under/over text. */
enum class HighlightKind { QUOTE, SELECTION, TTS, SEARCH }

class Highlight(val start: Int, val end: Int, val kind: HighlightKind, val style: Int = 0)

/** Everything drawn on a page besides the text itself. */
class PageDecor(
    val highlights: List<Highlight> = emptyList(),
    val bookmarked: Boolean = false,
    /** UI-thread status of the visible page; covers and thumbnails pass null. */
    val status: StatusDecor? = null,
    val statusVersion: Int = 0,
)

/**
 * The right page of a landscape spread ([PageRenderer.draw]): page [pageIndex] of [layout] (null = blank: the left page
 * is a section's last and the next section is not laid out), its text box at x [left], the highlights and bookmark it
 * carries in [decor] (no status: the bands belong to the left page's decor and span the view), and [section] its section.
 * [boundary] is the view x from which a touch belongs to this page (the middle of the gutter).
 */
class SpreadPage(
    val section: Int,
    val layout: com.ggumtak.readeraplus.engine.SectionLayout?,
    val pageIndex: Int,
    val left: Float,
    val boundary: Float,
    val decor: PageDecor = PageDecor(),
)

/** Application context captured by [FontManager.init] (used by Eink/Covers helpers that have no context). */
internal object RenderContext {
    @Volatile var app: Context? = null
}
