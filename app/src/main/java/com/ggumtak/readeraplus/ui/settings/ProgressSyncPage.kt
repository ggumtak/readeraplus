package com.ggumtak.readeraplus.ui.settings

import android.app.Activity
import android.content.Intent
import com.ggumtak.readeraplus.sync.DriveConnectActivity
import java.util.UUID
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.ggumtak.readeraplus.sync.CloudBooks
import com.ggumtak.readeraplus.sync.ShelfBook
import com.ggumtak.readeraplus.reader.ReaderActivity
import com.ggumtak.readeraplus.ui.kit.row
import com.ggumtak.readeraplus.sync.DEFAULT_SERVER
import com.ggumtak.readeraplus.sync.ReadingSync
import com.ggumtak.readeraplus.sync.SyncAccount
import com.ggumtak.readeraplus.sync.SyncHttpError
import com.ggumtak.readeraplus.ui.kit.confirm
import com.ggumtak.readeraplus.ui.kit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class ProgressSyncPage(a: SettingsActivity) : SettingsPage(a, SettingsActivity.PAGE_SYNC, "동기화 서재") {
    private lateinit var body: LinearLayout
    private lateinit var status: TextView
    private var busy = false
    private var gone = false
    private var showOther = false
    private var dav = true
    private var shelf: List<ShelfBook>? = null

    override fun build(): View {
        body = ctx.pageBody()
        render()
        return ctx.pageScroll(body)
    }

    private fun render() {
        body.removeAllViews()
        body.addView(ctx.note("두 기기를 같은 계정에 연결하면 책을 열 때 읽던 위치를 가져옵니다."))
        status = ctx.note(ReadingSync.status(ctx)).also(body::addView)
        val user = ReadingSync.user(ctx)
        if (user != null) {
            body.addView(ctx.infoRow("연결된 계정", user))
            if (CloudBooks.usesDrive(ctx)) {
                body.addView(ctx.note("Google Drive · PureReader 폴더"))
                body.addView(ctx.textButton("Google 권한 다시 연결") { chooseDrive(user) })
            }
            if (CloudBooks.supportsFiles(ctx)) fileControls()
            body.addView(ctx.textButton("연결 확인 · 대기 중인 위치 보내기") { runTask { ReadingSync.retry(ctx) } })
            body.addView(ctx.textButton("이 기기 로그아웃") {
                activity.confirm("이 기기에서 로그아웃할까요?", "아직 보내지 않은 동기화 기록은 지워집니다. 기기에서 읽던 위치는 보관합니다.") {
                    runTask { ReadingSync.disconnect(ctx) }; shelf = null
                }
            })
            return
        }
        body.section("Google Drive")
        body.addView(ctx.note("두 기기에서 같은 Google 계정을 선택하세요. PureReader 폴더에 읽던 위치를 보관하고, 책 파일 전송은 연결 후 켤 수 있습니다."))
        body.addView(ctx.textButton("Google Drive 연결") { chooseDrive(null) })
        body.addView(ctx.textButton(if (showOther) "다른 연결 방식 접기" else "WebDAV · KOReader 연결") {
            if (!busy) { showOther = !showOther; render() }
        })
        if (!showOther) return
        body.section(if (dav) "책 파일과 위치 · 개인 WebDAV" else "읽던 위치만 · KOReader")
        body.addView(ctx.textButton(if (dav) "위치만 동기화하는 계정 사용" else "책 파일도 동기화하는 계정 사용") {
            if (!busy) { dav = !dav; render() }
        })
        body.addView(ctx.note(if (dav) "두 기기에 같은 개인 WebDAV 폴더 주소와 계정을 입력하세요. 연결하면 이 폴더에 읽은 위치가 저장됩니다. 파일 전송은 연결 후 따로 켤 수 있습니다." else "로그인 또는 가입하면 선택한 KOReader 동기화 서버에 책 파일의 식별값과 읽은 위치를 보냅니다. 책 본문·제목·메모는 보내지 않습니다."))
        val server = field(if (dav) "개인 WebDAV 폴더 주소 (https://…)" else "동기화 서버 주소", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI).apply { if (!dav) setText(DEFAULT_SERVER) }
        val name = field(if (dav) "WebDAV 아이디" else "아이디 (영문·숫자)", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
        val secret = field(if (dav) "WebDAV 앱 비밀번호" else "동기화 계정 비밀번호", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        fun connect(register: Boolean) {
            val account = runCatching { SyncAccount.from(server.text.toString(), name.text.toString(), secret.text.toString(), dav) }.getOrNull()
            if (account == null) { status.text = "HTTPS 서버 주소, 영문 아이디, 비밀번호를 확인하세요."; return }
            runTask { ReadingSync.connect(ctx, account, register) }
        }
        if (dav) body.addView(ctx.textButton("개인 저장소 연결") { connect(false) })
        else body.addView(ctx.buttonBar(ctx.textButton("로그인") { connect(false) }, ctx.textButton("새 계정 만들기") { connect(true) }))
    }

    private fun chooseDrive(existingUser: String?) {
        if (busy) return
        activity.startActivityForResult(Intent(activity, DriveConnectActivity::class.java)
            .putExtra("reauthorize", existingUser), DRIVE_REQUEST)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != DRIVE_REQUEST) return false
        val name = data?.getStringExtra("account")
        if (resultCode == Activity.RESULT_OK && !name.isNullOrBlank()) {
            val account = SyncAccount("https://www.googleapis.com", name, "", UUID.randomUUID().toString(), drive = true)
            runTask { ReadingSync.connect(ctx, account, false) }
        } else data?.getStringExtra("error")?.let { status.text = it }
        return true
    }

    private companion object { const val DRIVE_REQUEST = 5701 }

    private fun fileControls() {
        body.section("책 파일")
        val enabled = CloudBooks.enabled(ctx)
        body.addView(ctx.row(if (enabled) "책 파일 동기화 켜짐" else "책 파일 동기화 켜기",
            if (enabled) "누르면 이 기기의 자동 업로드를 끕니다" else "읽는 TXT · EPUB · PDF를 내 저장소에 보관") {
            if (busy) return@row
            if (enabled) { CloudBooks.setEnabled(ctx, false); shelf = null; render() }
            else activity.confirm("책 파일도 동기화할까요?", "앞으로 여는 책의 파일 전체와 파일명을 연결한 개인 폴더에 올립니다. 다른 기기에서는 목록에서 책을 눌러 받을 수 있습니다.", "켜기") {
                CloudBooks.setEnabled(ctx, true); render()
            }
        })
        if (!enabled) return
        body.addView(ctx.note(CloudBooks.status(ctx).ifEmpty { "원본이 있는 기기에서 책을 한 번 열어 주세요." }))
        body.addView(ctx.textButton("동기화한 책 목록 불러오기") {
            loadShelf()
        })
        shelf?.let { books ->
            if (books.isEmpty()) body.addView(ctx.note("아직 올라온 책이 없습니다."))
            books.sortedBy { it.name }.forEach { item ->
                body.addView(ctx.row(item.name, "이 기기에서 열기 · " + SettingsFormat.bytes(item.size)) {
                    if (busy) return@row
                    busy = true
                    status.text = "책을 준비하는 중…"
                    activity.scope.launch {
                        val result = withContext(Dispatchers.IO) { runCatching { CloudBooks.fetch(ctx, item) } }
                        busy = false
                        if (!gone && activity.isResumedNow) result.onSuccess { book ->
                            status.text = ""
                            ReaderActivity.open(activity, book)
                        }.onFailure { status.text = "책을 받지 못했습니다. 저장 공간과 네트워크를 확인하세요." }
                    }
                })
            }
        }
    }

    private fun loadShelf() {
        if (busy) return
        busy = true
        status.text = "책 목록을 불러오는 중…"
        activity.scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { CloudBooks.list(ctx) } }
            busy = false
            if (!gone) result.onSuccess { shelf = it; render() }
                .onFailure { status.text = "책 목록을 불러오지 못했습니다. 연결을 확인하세요." }
        }
    }

    private fun field(label: String, type: Int): EditText = EditText(ctx).apply {
        setSingleLine(true)
        inputType = type
        hint = label
        contentDescription = label
        isSaveEnabled = false
        setPadding(ctx.dp(16), ctx.dp(10), ctx.dp(16), ctx.dp(10))
        body.addView(this)
    }

    private fun runTask(action: () -> Unit) {
        if (busy) return
        busy = true
        status.text = "연결 중…"
        activity.scope.launch {
            val error = withContext(Dispatchers.IO) { runCatching(action).exceptionOrNull() }
            busy = false
            if (gone) return@launch
            if (error == null) render() else {
                val http = generateSequence(error) { it.cause }.filterIsInstance<SyncHttpError>().firstOrNull()
                status.text = when (http?.status) {
                    401 -> "계정 권한을 확인하고 다시 연결하세요."
                    402, 409 -> "이미 사용 중인 아이디입니다."
                    403 -> "서버가 요청을 허용하지 않았습니다. 가입 허용 여부를 확인하세요."
                    else -> "연결하지 못했습니다. 네트워크와 서버 주소를 확인하고 다시 시도하세요."
                }
            }
        }
    }

    override fun onDestroy() { gone = true }
}
