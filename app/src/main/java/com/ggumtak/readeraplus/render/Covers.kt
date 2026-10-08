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
import com.ggumtak.readeraplus.format.epub.EpubDocuments
import com.ggumtak.readeraplus.format.txt.TxtDocuments
import com.ggumtak.readeraplus.settings.ReaderSettings
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Library cover thumbnails: EPUB cover image, a rendered mini first page for TXT, the first page of a PDF. Disk-cached as
 * `cacheDir/thumbs/<bookId>/<version>@<w>x<h>.png` (see [CoverKeys]): writing or invalidating a thumbnail only
 * lists that book's own directory, however large the library.
 */
object Covers {
    private const val TAG = "Covers"
    private const val DIR = "thumbs"
    /** Flat `covers/<id>_<mtime>_<w>x<h>.png` cache of earlier builds (its key lacked the TXT encoding). */
    private const val LEGACY_DIR = "covers"
    private val legacyChecked = AtomicBoolean(false)
    private const val MAX_SIDE = 2048
    /** Lines of text that fit on a TXT mini page. */
    private const val MINI_LINES = 18
    private const val MINI_LINE_HEIGHT = 1.5f
    private const val MINI_MARGIN = 0.08f
    private const val PREVIEW_CHARS = 1200
    private const val BORDER_GREY = 0xFF888888.toInt()
    /** Weight the placeholder's title looks like (synthetic stroke on the regular face). */
    private const val TITLE_WEIGHT = 700

    /** Returns a cached/generated thumbnail (blocking; call off main thread). */
    fun thumbnail(context: Context, book: Book, widthPx: Int, heightPx: Int): Bitmap? {
        if (widthPx <= 0 || heightPx <= 0) return null
        val w = widthPx.coerceAtMost(MAX_SIDE)
        val h = heightPx.coerceAtMost(MAX_SIDE)
        return try {
            dropLegacyCache(context)
            val dir = File(File(context.cacheDir, DIR), book.id.toString())
            val version = CoverKeys.version(book.modifiedAt, book.format == BookFormat.TXT, book.encoding)
            val cached = File(dir, CoverKeys.fileName(version, w, h))
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
            if (readable) save(dir, cached, bmp, version)
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
            File(File(context.cacheDir, DIR), bookId.toString()).deleteRecursively()
        } catch (t: Throwable) {
            Log.w(TAG, "invalidate failed", t)
        }
    }

    /** Removes the cache directory of earlier builds once per process (a single `exists` check afterwards). */
    private fun dropLegacyCache(context: Context) {
        if (!legacyChecked.compareAndSet(false, true)) return
        try {
            val legacy = File(context.cacheDir, LEGACY_DIR)
            if (legacy.exists()) legacy.deleteRecursively()
        } catch (t: Throwable) {
            Log.w(TAG, "legacy cover cache cleanup failed", t)
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
                // Cover-only lookup (container → OPF → cover): no TOC parse or section scan per thumbnail.
                EpubDocuments.coverImage(file)
            } catch (t: Throwable) {
                Log.w(TAG, "epub cover failed: ${file.name}", t)
                null
            }
            bytes?.let { decodeCover(it, w, h) }
        }
        BookFormat.TXT -> txtPage(context, book, file, w, h)
        BookFormat.PDF -> pdfPage(file, w, h)
    }

    /** The first page of a PDF, fitted on white inside w×h (never cropped: it is the page itself, not a cover). */
    private fun pdfPage(file: File, w: Int, h: Int): Bitmap? = PdfPages.open(file, withText = false).use { pdf ->
        val pw = pdf.pageWidth(0)
        val ph = pdf.pageHeight(0)
        val scale = minOf(w.toFloat() / pw, h.toFloat() / ph)
        val dw = (pw * scale).toInt().coerceIn(1, w)
        val dh = (ph * scale).toInt().coerceIn(1, h)
        // PdfRenderer draws into ARGB_8888 only; the cached thumbnail is RGB_565 like the others.
        val page = Bitmap.createBitmap(dw, dh, Bitmap.Config.ARGB_8888)
        pdf.renderWhole(0, page)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
        val c = Canvas(out)
        c.drawColor(Color.WHITE)
        c.drawBitmap(page, ((w - dw) / 2).toFloat(), ((h - dh) / 2).toFloat(), Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG))
        page.recycle()
        border(c, w, h, BORDER_GREY)
        out
    }

    private fun decodeCached(f: File): Bitmap? {
        val o = BitmapFactory.Options()
        o.inPreferredConfig = Bitmap.Config.RGB_565
        return BitmapFactory.decodeFile(f.path, o)
    }

    /** Writes [bmp] to [target] inside the book's own directory [dir]. */
    private fun save(dir: File, target: File, bmp: Bitmap, version: String) {
        try {
            if (!dir.isDirectory && !dir.mkdirs()) return
            // Drop thumbnails of older versions of this book (other sizes of the current version stay). Only this
            // book's directory is listed: a few entries, not the whole library.
            dir.listFiles()?.forEach { if (!CoverKeys.isVersion(it.name, version)) it.delete() }
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
            headerCenter = com.ggumtak.readeraplus.settings.StatusItem.NONE,
            progressBar = false,
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
            // The default font's regular face, emboldened by stroke: the face the mini pages and the first book use,
            // so the library inflates one CJK typeface, not a second bold file (나눔명조 ships one; A8).
            val regular = try {
                FontManager.typeface(FontCatalog.DEFAULT_ID, FontMath.REGULAR)
            } catch (t: Throwable) {
                null
            }
            typeface = regular ?: Typeface.create(Typeface.SERIF, Typeface.BOLD)
            val stroke = if (regular != null) FontMath.syntheticStroke(TITLE_WEIGHT, false, textSize) else 0f
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

/**
 * Disk-cache names of cover thumbnails (pure, unit-tested). A thumbnail belongs to a *version* of its book: the
 * file's mtime, plus the forced encoding for TXT (the mini page is decoded with it, so a changed encoding —
 * from the library, the reader or a restored backup — must never show the old decoding).
 *
 * The cover face is deliberately not part of the version: when `FontCatalog.DEFAULT_ID` became 나눔명조 (R2, A8),
 * covers already on disk kept 리디바탕 until their book changes or 캐시 비우기. At thumbnail size (a mini page's em is
 * ≈ 9 px) the two serif faces are hard to tell apart, while a new tag would re-read every book and repaint every
 * visible cover one by one — each an e-ink update — on the first library screen after the update.
 */
internal object CoverKeys {
    private const val SIZE_SEP = '@'
    private const val ENCODING_SEP = '~'

    fun version(modifiedAt: Long, txt: Boolean, encoding: String): String {
        val tag = if (txt) encodingTag(encoding) else ""
        return if (tag.isEmpty()) modifiedAt.toString() else "$modifiedAt$ENCODING_SEP$tag"
    }

    fun fileName(version: String, w: Int, h: Int): String = "$version$SIZE_SEP${w}x$h.png"

    /** True for files of [version] (any size, including a temp file being written for it). */
    fun isVersion(name: String, version: String): Boolean =
        name.length > version.length && name.startsWith(version) && name[version.length] == SIZE_SEP

    /** Case-insensitive charset name reduced to file-name-safe characters ("" = auto-detect). */
    private fun encodingTag(encoding: String): String {
        val e = encoding.trim()
        if (e.isEmpty()) return ""
        val sb = StringBuilder(e.length)
        for (c in e.lowercase(Locale.ROOT)) {
            sb.append(if (c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '_' || c == '.' || c == '+') c else '-')
        }
        return sb.toString()
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
