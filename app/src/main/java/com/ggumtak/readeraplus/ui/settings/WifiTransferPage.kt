package com.ggumtak.readeraplus.ui.settings

import android.content.Intent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.ggumtak.readeraplus.data.Book
import com.ggumtak.readeraplus.data.LanUpload
import com.ggumtak.readeraplus.ui.kit.Ink
import com.ggumtak.readeraplus.ui.kit.dp
import com.ggumtak.readeraplus.ui.kit.label
import com.ggumtak.readeraplus.ui.kit.row
import com.ggumtak.readeraplus.ui.kit.toast
import com.ggumtak.readeraplus.ui.kit.vertical
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import android.provider.Settings as SystemSettings

/**
 * "Wi-Fi로 책 받기" (T1-12): shows the address a browser on the same Wi-Fi opens to upload .txt / .epub files, the
 * destination folder and the files received. The upload server ([LanUpload], DATA) runs only while this page is the
 * top page and the activity is resumed: it starts on [onShown] / [onResume] and stops on [onPause], [onHidden] and
 * [onDestroy] (a restart gets a new access code). The screen stays on while the page is shown. Nothing here polls:
 * the page changes only when a file arrives or fails.
 */
internal class WifiTransferPage(a: SettingsActivity) : SettingsPage(a, SettingsActivity.PAGE_WIFI, "Wi-Fi로 책 받기") {
    private lateinit var urlText: TextView
    private lateinit var statusText: TextView
    private lateinit var retryBar: View
    private lateinit var folderRow: View
    private lateinit var receivedHeader: TextView
    private lateinit var receivedBox: LinearLayout
    private lateinit var errorText: TextView

    private var server: LanUpload? = null
    private var destDir: File? = null
    private var startJob: Job? = null
    /** Files received while this page lived (newest first); kept across a pause. */
    private val received = ArrayList<Pair<String, String>>()
    private var shown = false

    override fun build(): View {
        val body = ctx.pageBody()
        body.section("브라우저 주소", first = true)
        body.addView(ctx.note("PC나 휴대폰이 같은 Wi-Fi에 있어야 합니다. 브라우저 주소창에 입력하세요:"))
        urlText = ctx.label("주소를 준비하는 중…", 22f, bold = true).apply {
            setPadding(ctx.dp(16), ctx.dp(4), ctx.dp(16), ctx.dp(8))
            // Not selectable: a long-press would start a selection with handles (several e-ink updates).
            setTextIsSelectable(false)
        }
        body.addView(urlText)
        statusText = ctx.note("").apply { visibility = View.GONE }.also(body::addView)
        retryBar = ctx.buttonBar(
            ctx.textButton("다시 시도") { startIfVisible() },
            ctx.textButton("Wi-Fi 설정") { openWifiSettings() },
        ).apply { visibility = View.GONE }.also(body::addView)
        folderRow = ctx.row("받는 폴더", "확인 중…").also(body::addView)

        body.section("받은 파일")
        // The section header, renamed with the count ("받은 파일 (3)").
        receivedHeader = body.getChildAt(body.childCount - 1) as TextView
        receivedBox = ctx.vertical().also(body::addView)
        errorText = ctx.note("").apply { setTextColor(Ink.BLACK); visibility = View.GONE }.also(body::addView)
        fillReceived()

        body.section("알아 두기")
        body.addView(ctx.note(
            "이 화면을 닫으면 전송이 멈춥니다. 화면을 다시 열면 주소 끝의 접속 코드가 바뀝니다.\n" +
                "TXT · EPUB 파일만, 한 파일에 200MB까지 받습니다.\n" +
                "공유기의 게스트 네트워크·AP 격리에서는 연결되지 않을 수 있습니다.",
        ))
        return ctx.pageScroll(body)
    }

    override fun onShown() {
        shown = true
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        startIfVisible()
    }

    override fun onResume() {
        startIfVisible()
    }

    override fun onPause() {
        stopServer(paused = true)
    }

    override fun onHidden() {
        shown = false
        activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        stopServer(paused = true)
    }

    override fun onDestroy() {
        shown = false
        activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        stopServer(paused = false)
    }

    /** Starts the server when this page is on top of a resumed activity (the folder is resolved once, on IO). */
    private fun startIfVisible() {
        if (!shown || !activity.isResumedNow) return
        if (server?.isRunning == true || startJob?.isActive == true) return
        startJob = activity.scope.launch {
            val dir = destDir ?: withContext(Dispatchers.IO) {
                runCatching { LanUpload.destinationDir(activity.applicationContext) }.getOrNull()
            }?.also { destDir = it }
            if (dir == null) {
                showFailure("받을 폴더를 만들지 못했습니다", "저장 공간과 파일 접근 권한을 확인한 뒤 [다시 시도]를 누르세요.")
                return@launch
            }
            folderRow.setSummary(dir.absolutePath)
            // The page may have gone meanwhile (a pause during the folder lookup).
            if (!shown || !activity.isResumedNow) return@launch
            val s = server ?: LanUpload(activity.applicationContext, dir, listener).also { server = it }
            val result = runCatching { s.start() }.getOrNull()
            if (result == null) {
                showFailure(s.lastError ?: "전송을 시작하지 못했습니다", "Wi-Fi 연결을 확인한 뒤 [다시 시도]를 누르세요.")
            } else {
                urlText.text = result.url
                urlText.setTextColor(Ink.BLACK)
                statusText.text = "접속 코드는 주소 끝의 '${result.code}'입니다. 받은 파일은 바로 서재에 추가됩니다."
                statusText.visibility = View.VISIBLE
                retryBar.visibility = View.GONE
            }
        }
    }

    private fun stopServer(paused: Boolean) {
        startJob?.cancel()
        startJob = null
        val s = server ?: return
        if (s.isRunning) runCatching { s.stop() }
        if (paused && ::urlText.isInitialized) {
            urlText.text = "전송이 멈췄습니다"
            urlText.setTextColor(Ink.GRAY)
            statusText.visibility = View.GONE
        }
    }

    private fun showFailure(message: String, hint: String) {
        urlText.text = message
        urlText.setTextColor(Ink.BLACK)
        statusText.text = hint
        statusText.visibility = View.VISIBLE
        retryBar.visibility = View.VISIBLE
    }

    private val listener = object : LanUpload.Listener {
        override fun onReceived(file: File, book: Book?) {
            received.add(0, file.name to SettingsFormat.received(file.length(), book != null))
            errorText.visibility = View.GONE
            fillReceived()
        }

        override fun onError(message: String) {
            errorText.text = "받지 못한 파일이 있습니다: $message"
            errorText.visibility = View.VISIBLE
        }
    }

    /** "받은 파일 (3)" and one row per file, newest first. */
    private fun fillReceived() {
        receivedHeader.text = if (received.isEmpty()) "받은 파일" else "받은 파일 (${received.size})"
        receivedBox.removeAllViews()
        if (received.isEmpty()) {
            receivedBox.addView(ctx.note("아직 받은 파일이 없습니다."))
            return
        }
        for ((name, line) in received) receivedBox.addView(ctx.row(name, line))
    }

    private fun openWifiSettings() {
        try {
            activity.startActivity(Intent(SystemSettings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION))
        } catch (_: Exception) {
            ctx.toast("Wi-Fi 설정 화면을 열 수 없습니다")
        }
    }
}
