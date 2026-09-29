package com.ggumtak.readeraplus.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import com.ggumtak.readeraplus.data.Book
import com.ggumtak.readeraplus.engine.Align
import com.ggumtak.readeraplus.engine.Block
import com.ggumtak.readeraplus.engine.LayoutConfig
import com.ggumtak.readeraplus.engine.LineBreakMode
import com.ggumtak.readeraplus.engine.ParagraphBlock
import com.ggumtak.readeraplus.engine.SectionContent
import com.ggumtak.readeraplus.engine.Typesetter
import com.ggumtak.readeraplus.format.BookFormat
import com.ggumtak.readeraplus.format.ParseOptions
import com.ggumtak.readeraplus.format.epub.EpubDocuments
import com.ggumtak.readeraplus.format.txt.TxtDocuments
import com.ggumtak.readeraplus.settings.ReaderSettings
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

/** Library cover thumbnails: EPUB cover image or a rendered mini first page for TXT. Disk-cached. */
object Covers {
    private const val TAG = "Covers"
    private const val DIR = "covers"
    private const val MAX_SIDE = 2048
    /** Lines of text that fit on a TXT mini page. */
    private const val MINI_LINES = 18
    private const val MINI_LINE_HEIGHT = 1.5f
    private const val MINI_MARGIN = 0.08f
    private const val PREVIEW_CHARS = 1200
    private const val BORDER_GREY = 0xFF888888.toInt()

    /** Returns a cached/generated thumbnail (blocking; call off main thread). */
    fun thumbnail(context: Context, book: Book, widthPx: Int, heightPx: Int): Bitmap? {
        if (widthPx <= 0 || heightPx <= 0) return null
        val w = widthPx.coerceAtMost(MAX_SIDE)
        val h = heightPx.coerceAtMost(MAX_SIDE)
        return try {
            val dir = File(context.cacheDir, DIR)
            val cached = File(dir, "${book.id}_${book.modifiedAt}_${w}x$h.png")
            if (cached.isFile) {
                decodeCached(cached)?.let { return it }
                cached.delete()
            }
            val file = File(book.path)
            val readable = file.isFile && file.canRead()
            val bmp = (if (readable) generateSafely(context, book, file, w, h) else null) ?: placeholder(context, book, w, h)
            // Placeholders for unreadable files are not cached: permission may be granted later. A readable file
            // whose cover/preview can't be produced (corrupt EPUB, binary TXT) caches its placeholder, so the
            // library doesn't re-parse it on every bind.
            if (readable) save(dir, cached, bmp, book)
            bmp
        } catch (oom: OutOfMemoryError) {
            null
        } catch (t: Throwable) {
            Log.w(TAG, "thumbnail failed: ${book.path}", t)
            try {
                placeholder(context, book, w, h)
            } catch (t2: Throwable) {
                null
            }
        }
    }

    /** Deletes every cached thumbnail of [bookId] (all sizes and file versions). */
    fun invalidate(context: Context, bookId: Long) {
        try {
            val dir = File(context.cacheDir, DIR)
            val prefix = "${bookId}_"
            dir.listFiles()?.forEach { if (it.name.startsWith(prefix)) it.delete() }
        } catch (t: Throwable) {
            Log.w(TAG, "invalidate failed", t)
        }
    }

    // ---------------------------------------------------------------------------------------------

    /** [generate], with parse/decode failures mapped to null (out-of-memory still propagates: it is transient). */
    private fun generateSafely(context: Context, book: Book, file: File, w: Int, h: Int): Bitmap? = try {
        generate(context, book, file, w, h)
    } catch (oom: OutOfMemoryError) {
        throw oom
    } catch (t: Throwable) {
        Log.w(TAG, "cover generation failed: ${file.name}", t)
        null
    }

    private fun generate(context: Context, book: Book, file: File, w: Int, h: Int): Bitmap? = when (book.format) {
        BookFormat.EPUB -> {
            val bytes = try {
                EpubDocuments.open(file, ParseOptions()).use { it.coverImage() }
            } catch (t: Throwable) {
                Log.w(TAG, "epub cover failed: ${file.name}", t)
                null
            }
            bytes?.let { decodeCover(it, w, h) }
        }
        BookFormat.TXT -> txtPage(context, book, file, w, h)
    }

    private fun decodeCached(f: File): Bitmap? {
        val o = BitmapFactory.Options()
        o.inPreferredConfig = Bitmap.Config.RGB_565
        return BitmapFactory.decodeFile(f.path, o)
    }

    private fun save(dir: File, target: File, bmp: Bitmap, book: Book) {
        try {
            if (!dir.isDirectory && !dir.mkdirs()) return
            // Drop thumbnails of older versions of this file (other sizes of the current version stay).
            val stalePrefix = "${book.id}_"
            val currentPrefix = "${book.id}_${book.modifiedAt}_"
            dir.listFiles()?.forEach {
                val n = it.name
                if (n.startsWith(stalePrefix) && !n.startsWith(currentPrefix)) it.delete()
            }
            val tmp = File(dir, target.name + ".tmp" + Thread.currentThread().id)
            FileOutputStream(tmp).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            if (!tmp.renameTo(target)) tmp.delete()
        } catch (t: Throwable) {
            Log.w(TAG, "cover cache write failed", t)
        }
    }

    /** Sampled decode, then centre-crop (or fit on white when the aspect ratio differs a lot) to w×h. */
    private fun decodeCover(bytes: ByteArray, w: Int, h: Int): Bitmap? {
        val o = BitmapFactory.Options()
        o.inJustDecodeBounds = true
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        val iw = o.outWidth
        val ih = o.outHeight
        if (iw <= 0 || ih <= 0) return null
        val crop = ImageMath.cropCover(iw, ih, w, h)
        val sx = w.toFloat() / iw
        val sy = h.toFloat() / ih
        val scale = if (crop) maxOf(sx, sy) else minOf(sx, sy)
        val d = BitmapFactory.Options()
        d.inSampleSize = ImageMath.sampleForScale(scale)
        // The decoder keeps ARGB_8888 by itself for images with alpha; those are drawn onto white below.
        d.inPreferredConfig = Bitmap.Config.RGB_565
        val src = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, d) ?: return null
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
        val c = Canvas(out)
        c.drawColor(Color.WHITE)
        val dw = iw * scale
        val dh = ih * scale
        val l = (w - dw) / 2f
        val t = (h - dh) / 2f
        c.drawBitmap(src, null, RectF(l, t, l + dw, t + dh), Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG))
        src.recycle()
        if (!crop) border(c, w, h, BORDER_GREY)
        return out
    }

    /** ReadEra-like mini first page: the book's opening text typeset with our own engine. */
    private fun txtPage(context: Context, book: Book, file: File, w: Int, h: Int): Bitmap? {
        val text = TxtDocuments.preview(file, PREVIEW_CHARS, book.encoding)
        if (text.isBlank()) return null
        val margin = Math.round(minOf(w, h) * MINI_MARGIN).coerceAtLeast(1)
        val cw = w - 2 * margin
        val ch = h - 2 * margin
        if (cw < 8 || ch < 8) return null
        val emPx = ch / (MINI_LINES * MINI_LINE_HEIGHT)
        val s = ReaderSettings(
            fontId = FontCatalog.DEFAULT_ID,
            fontSizeSp = pxToSp(context, emPx),
            fontWeight = 400,
            lineHeightPct = Math.round(MINI_LINE_HEIGHT * 100),
            paragraphSpacingPct = 60,
            indentPct = 0,
            letterSpacingPm = 0,
            align = Align.LEFT,
            lineBreak = LineBreakMode.CHAR,
            invert = false,
            showHeader = false,
            showFooter = false,
            widowOrphanControl = false,
        )
        val m = AndroidTextMeasurer(context, s) { null }
        val config = LayoutConfig(
            width = cw,
            height = ch,
            lineHeightEm = MINI_LINE_HEIGHT,
            paragraphSpacingEm = 0.6f,
            indentEm = 0f,
            align = Align.LEFT,
            lineBreak = LineBreakMode.CHAR,
            publisherStyles = true,
            maxImageHeightFraction = 1f,
            widowOrphanControl = false,
        )
        val layout = Typesetter(m, config).layout(CoverText.content(text, PREVIEW_CHARS))
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
        val c = Canvas(out)
        PageRenderer(context, m, null).draw(c, layout, 0, margin.toFloat(), margin.toFloat(), w, h, PageDecor())
        border(c, w, h, BORDER_GREY)
        return out
    }

    /** Typographic placeholder: title (bold) and author on white inside a 1px border, format at the bottom. */
    private fun placeholder(context: Context, book: Book, w: Int, h: Int): Bitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
        val c = Canvas(out)
        c.drawColor(Color.WHITE)
        border(c, w, h, Color.BLACK)
        val pad = maxOf(4f, w * 0.1f)
        val avail = (w - 2 * pad).toInt()
        if (avail <= 8) return out
        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textLocale = Locale.KOREAN
            textSize = maxOf(8f, w * 0.11f)
            typeface = try {
                FontManager.typeface(FontCatalog.DEFAULT_ID, 700)
            } catch (t: Throwable) {
                Typeface.create(Typeface.SERIF, Typeface.BOLD)
            }
            val stroke = try { FontManager.syntheticStroke(FontCatalog.DEFAULT_ID, 700, textSize) } catch (t: Throwable) { 0f }
            if (stroke > 0f) {
                style = Paint.Style.FILL_AND_STROKE
                strokeWidth = stroke
            }
        }
        val title = book.title.ifBlank { book.fileName }.ifBlank { "제목 없음" }
        val titleLayout = staticLayout(title, titlePaint, avail, 5)
        var y = h * 0.18f
        c.save()
        c.translate(pad, y)
        titleLayout.draw(c)
        c.restore()
        y += titleLayout.height + titlePaint.textSize * 0.8f
        if (book.author.isNotBlank() && y < h * 0.8f) {
            val authorPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFF444444.toInt()
                textLocale = Locale.KOREAN
                textSize = maxOf(7f, w * 0.075f)
                typeface = Typeface.SANS_SERIF
            }
            val al = staticLayout(book.author, authorPaint, avail, 2)
            c.save()
            c.translate(pad, y)
            al.draw(c)
            c.restore()
        }
        val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF444444.toInt()
            textLocale = Locale.KOREAN
            textSize = maxOf(7f, w * 0.07f)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            letterSpacing = 0.1f
        }
        val label = book.format.label
        val lw = labelPaint.measureText(label)
        c.drawText(label, (w - lw) / 2f, h - pad, labelPaint)
        return out
    }

    private fun staticLayout(text: String, paint: TextPaint, width: Int, maxLines: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setMaxLines(maxLines)
            .setEllipsize(TextUtils.TruncateAt.END)
            .setIncludePad(false)
            .build()

    private fun border(c: Canvas, w: Int, h: Int, color: Int) {
        val p = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = 1f
            this.color = color
        }
        c.drawRect(0.5f, 0.5f, w - 0.5f, h - 0.5f, p)
    }

    private fun pxToSp(context: Context, px: Float): Float {
        val dm = context.resources.displayMetrics
        val sp = if (Build.VERSION.SDK_INT >= 34) {
            TypedValue.deriveDimension(TypedValue.COMPLEX_UNIT_SP, px, dm)
        } else {
            @Suppress("DEPRECATION")
            px / (if (dm.scaledDensity > 0f) dm.scaledDensity else 1f)
        }
        return if (sp.isFinite() && sp > 0f) sp else 4f
    }
}

/** Pure helper: preview text → SectionContent with one paragraph per line (unit-tested). */
internal object CoverText {
    fun content(text: String, maxChars: Int): SectionContent {
        var t = if (text.length > maxChars) text.substring(0, cutPoint(text, maxChars)) else text
        t = t.trimEnd('\n', '\r', ' ')
        val blocks = ArrayList<Block>()
        var start = 0
        val n = t.length
        while (start <= n) {
            var end = t.indexOf('\n', start)
            if (end < 0) end = n
            blocks.add(ParagraphBlock(start, end))
            start = end + 1
        }
        return SectionContent(t, blocks)
    }

    /** Cut position ≤ [max] that does not split a surrogate pair. */
    private fun cutPoint(t: String, max: Int): Int {
        var m = max.coerceIn(0, t.length)
        if (m in 1 until t.length && Character.isHighSurrogate(t[m - 1]) && Character.isLowSurrogate(t[m])) m--
        return m
    }
}
