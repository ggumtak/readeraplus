package com.ggumtak.readeraplus.reader.extras

import android.annotation.TargetApi
import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.PixelCopy
import com.ggumtak.readeraplus.ui.kit.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Saves the reader window as a PNG in Pictures/ReaderaPlus (the selection menu's 스크린샷). The window is copied on the
 * main thread (PixelCopy, or drawing the decor view when that fails); encoding and writing run on [Dispatchers.IO].
 */
internal object ScreenCapture {
    private const val FOLDER = "ReaderaPlus"
    private const val MIME_PNG = "image/png"

    /** "ReaderaPlus_yyyyMMdd_HHmmss.png" for [millis] in [zone]. */
    fun fileName(millis: Long, zone: TimeZone = TimeZone.getDefault()): String {
        val format = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        format.timeZone = zone
        return "ReaderaPlus_" + format.format(Date(millis)) + ".png"
    }

    /**
     * Captures [activity]'s window and saves it; a toast tells the outcome and [onDone] gets it (on the main thread).
     * Call from the main thread, with the window showing what should be in the picture.
     */
    fun capture(activity: Activity, onDone: (Boolean) -> Unit) {
        val window = activity.window
        val decor = window?.decorView
        val w = decor?.width ?: 0
        val h = decor?.height ?: 0
        if (window == null || decor == null || w <= 0 || h <= 0 || activity.isFinishing || activity.isDestroyed) {
            report(activity, false, onDone)
            return
        }
        val bitmap = try {
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        } catch (t: Throwable) {
            report(activity, false, onDone)
            return
        }
        val main = Handler(Looper.getMainLooper())
        var finished = false
        val copied = Runnable {
            if (finished) return@Runnable
            finished = true
            save(activity, bitmap, onDone)
        }
        val drawFallback = Runnable {
            if (finished) return@Runnable
            val drawn = try {
                bitmap.eraseColor(Color.WHITE)
                decor.draw(Canvas(bitmap))
                true
            } catch (t: Throwable) {
                false
            }
            if (drawn) {
                copied.run()
            } else {
                finished = true
                bitmap.recycle()
                report(activity, false, onDone)
            }
        }
        try {
            PixelCopy.request(window, bitmap, { result ->
                if (result == PixelCopy.SUCCESS) copied.run() else drawFallback.run()
            }, main)
        } catch (t: Throwable) {
            drawFallback.run()
        }
    }

    /** Encodes and writes [bitmap] off the main thread, recycles it, then reports. */
    private fun save(activity: Activity, bitmap: Bitmap, onDone: (Boolean) -> Unit) {
        val appContext = activity.applicationContext
        val name = fileName(System.currentTimeMillis())
        val scope = MainScope()
        scope.launch {
            val ok = try {
                withContext(Dispatchers.IO) {
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) saveToMediaStore(appContext, bitmap, name)
                        else saveToPicturesDir(appContext, bitmap, name)
                    } catch (t: Throwable) {
                        false
                    }
                }
            } finally {
                bitmap.recycle()
            }
            report(activity, ok, onDone)
            scope.cancel()
        }
    }

    private fun report(activity: Activity, ok: Boolean, onDone: (Boolean) -> Unit) {
        activity.toast(if (ok) "스크린샷을 저장했습니다 (사진 › ReaderaPlus)" else "스크린샷을 저장하지 못했습니다")
        onDone(ok)
    }

    /** API 29+: a MediaStore row in Pictures/ReaderaPlus, pending while it is written, removed again on failure. */
    @TargetApi(Build.VERSION_CODES.Q)
    private fun saveToMediaStore(context: Context, bitmap: Bitmap, name: String): Boolean {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, MIME_PNG)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/" + FOLDER)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        try {
            val written = resolver.openOutputStream(uri)?.use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) } ?: false
            if (!written) {
                resolver.delete(uri, null, null)
                return false
            }
            val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
            return true
        } catch (t: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            return false
        }
    }

    /** API 26–28: a file in the public Pictures/ReaderaPlus folder, then handed to the media scanner. */
    @Suppress("DEPRECATION")
    private fun saveToPicturesDir(context: Context, bitmap: Bitmap, name: String): Boolean {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), FOLDER)
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) return false
        val file = File(dir, name)
        try {
            val written = FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
            if (!written) {
                file.delete()
                return false
            }
        } catch (t: Throwable) {
            runCatching { file.delete() }
            return false
        }
        MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf(MIME_PNG), null)
        return true
    }
}
