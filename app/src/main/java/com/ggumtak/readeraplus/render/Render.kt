package com.ggumtak.readeraplus.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Typeface
import android.net.Uri
import android.text.TextPaint
import android.view.View
import com.ggumtak.readeraplus.data.Book
import com.ggumtak.readeraplus.engine.FontMetricsPx
import com.ggumtak.readeraplus.engine.IntSize
import com.ggumtak.readeraplus.engine.RunStyle
import com.ggumtak.readeraplus.engine.SectionLayout
import com.ggumtak.readeraplus.engine.TextMeasurer
import com.ggumtak.readeraplus.format.BookDocument
import com.ggumtak.readeraplus.settings.ReaderSettings

/*
 * CONTRACT STUBS for the render module (Android drawing side of the engine).
 * Public signatures are fixed. See docs/ARCHITECTURE.md "render".
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

/** Font registry: bundled asset fonts + user-imported fonts (files/fonts) + fonts in /sdcard/Fonts. */
object FontManager {
    fun init(context: Context): Unit = TODO("render")
    fun fonts(): List<FontInfo> = TODO("render")
    fun font(id: String): FontInfo? = TODO("render")
    /** Typeface for [id] at [weight] (100..900) and [italic]; cached. Falls back to the default font. */
    fun typeface(id: String, weight: Int = 400, italic: Boolean = false): Typeface = TODO("render")
    /** Extra synthetic stroke width (px) to emulate [weight] for a static font at [textSizePx]; 0 if not needed. */
    fun syntheticStroke(id: String, weight: Int, textSizePx: Float): Float = TODO("render")
    /** Copies a .ttf/.otf from [uri] into app storage and registers it. */
    fun importFont(context: Context, uri: Uri): FontInfo = TODO("render")
    fun deleteUserFont(id: String): Unit = TODO("render")
    /** Re-scans user font folders. */
    fun refresh(context: Context): Unit = TODO("render")
}

/**
 * TextMeasurer backed by TextPaint. One instance per thread. [paintFor] returns the exact paint used for
 * measuring a style, so the renderer draws with identical metrics.
 */
class AndroidTextMeasurer(
    context: Context,
    val settings: ReaderSettings,
    /** Resolves image intrinsic sizes (decodes bounds only). */
    private val imageSizer: (String) -> IntSize?,
) : TextMeasurer {
    override val emPx: Float get() = TODO("render")
    override fun measure(text: String, start: Int, end: Int, style: RunStyle, out: FloatArray, outOffset: Int): Unit = TODO("render")
    override fun metrics(style: RunStyle): FontMetricsPx = TODO("render")
    override fun imageSize(src: String): IntSize? = TODO("render")
    fun paintFor(style: RunStyle): TextPaint = TODO("render")
}

/** Decoded image cache for one open document (LRU by bytes). Thread-safe. */
class ImageCache(private val document: BookDocument, maxBytes: Int = 24 * 1024 * 1024) {
    /** Bitmap scaled to fit within maxW x maxH (decoded with inSampleSize), or null. */
    fun get(src: String, maxW: Int, maxH: Int): Bitmap? = TODO("render")
    /** Intrinsic size without decoding pixels. */
    fun size(src: String): IntSize? = TODO("render")
    fun clear(): Unit = TODO("render")
}

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
)

/**
 * Draws a laid-out page. The page's content box is placed at (contentLeft, contentTop) in canvas coordinates.
 * Colours: black text on white, or white on black when settings.invert.
 */
class PageRenderer(context: Context, private val measurer: AndroidTextMeasurer, private val images: ImageCache?) {
    fun draw(
        canvas: Canvas,
        layout: SectionLayout,
        pageIndex: Int,
        contentLeft: Float,
        contentTop: Float,
        viewWidth: Int,
        viewHeight: Int,
        decor: PageDecor,
    ): Unit = TODO("render")
}

/** Library cover thumbnails: EPUB cover image or a rendered mini first page for TXT. Disk-cached. */
object Covers {
    /** Returns a cached/generated thumbnail (blocking; call off main thread). */
    fun thumbnail(context: Context, book: Book, widthPx: Int, heightPx: Int): Bitmap? = TODO("render")
    fun invalidate(context: Context, bookId: Long): Unit = TODO("render")
}

/** E-ink helpers. */
object Eink {
    /** Forces a full panel refresh on [view] (brief black/white flash, then redraw). */
    fun fullRefresh(view: View): Unit = TODO("render")
}
