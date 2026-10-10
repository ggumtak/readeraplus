package com.ggumtak.readeraplus.reader.extras

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A picture for the AI dictionary's 사진 button: the picked image is decoded, turned upright, scaled to at most
 * [MAX_EDGE] px on its long edge (the size Claude reads without resizing) and handed to the page as a base64 JPEG,
 * so the key-bearing WebView keeps file and content access off.
 */
internal object AiImage {
    const val MAX_EDGE = 1568
    private const val QUALITY = 85
    /** Base64 characters the page accepts for one picture (the API takes up to 5 MB). */
    const val MAX_BASE64 = 4_000_000

    /** The largest power-of-two sample size that still leaves the long edge at least [maxEdge] (pure). */
    fun sampleSize(width: Int, height: Int, maxEdge: Int = MAX_EDGE): Int {
        val edge = max(width, height)
        var s = 1
        while (edge / (s * 2) >= maxEdge) s *= 2
        return s
    }

    /** [width] × [height] scaled down to fit [maxEdge] on the long edge, never up (pure). */
    fun fit(width: Int, height: Int, maxEdge: Int = MAX_EDGE): Pair<Int, Int> {
        val edge = max(width, height)
        if (edge <= maxEdge) return width to height
        val f = maxEdge.toDouble() / edge
        return max(1, (width * f).roundToInt()) to max(1, (height * f).roundToInt())
    }

    /** The page call for one picture; null clears it. Base64 needs no escaping inside a JS string. */
    fun attachScript(base64: String?): String =
        if (base64 == null) "window.readerAttach&&readerAttach(null)" else "window.readerAttach&&readerAttach(\"$base64\")"

    /** Decodes [uri] into the page's JPEG; null when it is not a readable picture. Worker thread only. */
    fun encode(resolver: ContentResolver, uri: Uri): String? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        // A bounds-only decode always returns null; only the stream itself can be missing.
        val probe = resolver.openInputStream(uri) ?: return null
        probe.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight) }
        var bmp = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        try {
            val degrees = runCatching {
                resolver.openInputStream(uri)?.use {
                    when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                        ExifInterface.ORIENTATION_ROTATE_90 -> 90
                        ExifInterface.ORIENTATION_ROTATE_180 -> 180
                        ExifInterface.ORIENTATION_ROTATE_270 -> 270
                        else -> 0
                    }
                } ?: 0
            }.getOrDefault(0)
            val (w, h) = fit(bmp.width, bmp.height)
            if (degrees != 0 || w != bmp.width || h != bmp.height) {
                val m = Matrix()
                m.postScale(w.toFloat() / bmp.width, h.toFloat() / bmp.height)
                m.postRotate(degrees.toFloat())
                val next = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                if (next !== bmp) bmp.recycle()
                bmp = next
            }
            if (bmp.hasAlpha()) {
                // JPEG has no alpha: transparent parts go on white instead of black.
                val flat = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
                Canvas(flat).apply { drawColor(Color.WHITE); drawBitmap(bmp, 0f, 0f, null) }
                bmp.recycle()
                bmp = flat
            }
            val out = ByteArrayOutputStream()
            if (!bmp.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)) return null
            val text = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
            return if (text.length <= MAX_BASE64) text else null
        } finally {
            bmp.recycle()
        }
    }
}
