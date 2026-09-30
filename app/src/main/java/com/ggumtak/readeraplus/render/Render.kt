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
)

/** Range highlight kinds drawn under/over text. */
enum class HighlightKind { QUOTE, SELECTION, TTS, SEARCH }

class Highlight(val start: Int, val end: Int, val kind: HighlightKind)

/** Everything drawn on a page besides the text itself. */
class PageDecor(
    val highlights: List<Highlight> = emptyList(),
    /** Draw the bookmark ribbon in the top-right corner. */
    val bookmarked: Boolean = false,
    /** Header text (chapter title) or null. */
    val header: String? = null,
    /** Footer left/right strings or null. */
    val footerLeft: String? = null,
    val footerRight: String? = null,
    /** Battery percent 0..100 drawn as a small battery icon + digits at the footer's right end; -1 = none. */
    val battery: Int = -1,
)

/** Application context captured by [FontManager.init] (used by Eink/Covers helpers that have no context). */
internal object RenderContext {
    @Volatile var app: Context? = null
}
