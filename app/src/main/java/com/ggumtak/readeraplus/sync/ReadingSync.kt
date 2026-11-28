package com.ggumtak.readeraplus.sync

import android.content.Context
import android.content.SharedPreferences
import com.ggumtak.readeraplus.data.Book
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** The base revision is what the reader actually saw. Never replay an offline edit over a different revision. */
internal enum class Replay { SEND, ACKNOWLEDGE, KEEP_REMOTE }
internal fun replay(base: String, pending: String, remote: String): Replay = when (remote) {
    pending -> Replay.ACKNOWLEDGE
    base -> Replay.SEND
    else -> Replay.KEEP_REMOTE
}

internal object ReadingSync {
    private val worker = ScheduledThreadPoolExecutor(1).apply { removeOnCancelPolicy = true }
    private val delayed = mutableMapOf<String, ScheduledFuture<*>>()
    private val sessions = java.util.WeakHashMap<Session, Boolean>()
    private fun prefs(c: Context) = c.getSharedPreferences("reading_sync", Context.MODE_PRIVATE)
    private fun <T> serial(block: () -> T): T = worker.submit(Callable { block() }).get()

    class Session internal constructor(
        internal val document: String, internal val accountId: String, internal var base: String,
        val opening: SyncPoint?, internal var seed: Boolean,
    ) {
        // Only the reader's main thread uses these fields.
        private var section = -1
        private var offset = -1
        fun start(section: Int, offset: Int) { this.section = section; this.offset = offset }
        internal fun moved(point: SyncPoint): Boolean {
            if (section < 0) return false
            if (!seed && section == point.section && offset == point.offset) return false
            seed = false
            start(point.section, point.offset)
            return true
        }
    }

    private fun message(c: Context, text: String) { prefs(c).edit().putString("status", text).apply() }
    fun status(c: Context): String = prefs(c).getString("status", "") ?: ""
    fun user(c: Context): String? = prefs(c).getString("user", null)

    /** Called from an IO dispatcher. Existing credentials and pending edits survive a failed sign-in. */
    fun connect(c: Context, account: SyncAccount, register: Boolean) = serial {
        when {
            account.drive -> DriveShelf({ DriveAuthorization.token(c, account.user) }).connect()
            account.dav -> DavShelf(account).connect()
            else -> SyncApi(account).login(register)
        }
        val existing = AccountVault.read(c)
        if (account.drive && existing?.drive == true && existing.user == account.user &&
            prefs(c).getString("account", null) == existing.device) {
            message(c, "Google Drive에 다시 연결했습니다.")
            return@serial true
        }
        AccountVault.save(c, account)
        invalidate()
        prefs(c).edit().clear().putString("user", account.user).putString("account", account.device).putBoolean("dav", account.dav).putBoolean("drive", account.drive)
            .putString("status", "연결됐습니다. 두 기기에서 같은 책 파일을 여세요.").commit()
    }

    private fun invalidate() {
        delayed.values.forEach { it.cancel(false) }
        delayed.clear()
        sessions.clear()
    }

    fun disconnect(c: Context) = serial {
        invalidate()
        AccountVault.clear(c)
        prefs(c).edit().clear().commit()
    }

    private fun document(c: Context, book: Book): String {
        val f = File(book.path)
        val cacheKey = "book." + book.id
        val stamp = "${f.length()}:${f.lastModified()}:${f.absolutePath}"
        val prefs = prefs(c)
        if (prefs.getString("$cacheKey.stamp", null) == stamp) prefs.getString(cacheKey, null)?.let { return it }
        val digest = MessageDigest.getInstance("SHA-256")
        f.inputStream().buffered().use { input ->
            val buf = ByteArray(65536)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        val doc = "pr1_" + digest.digest().hex()
        prefs.edit().putString(cacheKey, doc).putString("$cacheKey.stamp", stamp).apply()
        return doc
    }

    /** On the book-opening IO thread. Offline books still open at the local position. */
    fun open(context: Context, book: Book): Session? {
        val c = context.applicationContext
        if (user(c) == null) return null
        return runCatching { serial {
            val account = AccountVault.read(c) ?: return@serial null
            val doc = document(c, book)
            val p = prefs(c)
            if (account.device != p.getString("account", null)) return@serial null
            if (account.supportsBooks) CloudBooks.onOpen(c, book, doc.removePrefix("pr1_"), account.device)
            val result = runCatching { reconcile(c, p, account, doc) }
            val remote = result.getOrNull()
            if (result.isFailure) message(c, "서버에 연결하지 못했습니다. 기기에 저장하고 다음 연결 때 다시 시도합니다.")
            val base = p.getString("base.$doc", "") ?: ""
            Session(doc, account.device, base, remote?.point, remote == null && book.lastReadAt > 0)
                .also { next ->
                    sessions.keys.removeAll { it.document == doc }
                    sessions[next] = true
                }
        } }.getOrNull()
    }

    /** Writes are serialized with opens, not with UI rendering. Persist edits before attempting the network. */
    fun save(context: Context, session: Session, point: SyncPoint, closing: Boolean) {
        val changed = session.moved(point)
        if (!changed && !closing) return
        val c = context.applicationContext
        worker.execute {
            val doc = session.document
            val p = prefs(c)
            if (sessions[session] != true || session.accountId != p.getString("account", null)) return@execute
            if (changed) {
                val record = SyncRecord(UUID.randomUUID().toString(), point)
                val entry = JSONObject().put("base", session.base).put("record", record.encode())
                p.edit().putString("pending.$doc", entry.toString()).commit()
            }
            if (!p.contains("pending.$doc")) return@execute
            delayed.remove(doc)?.cancel(false)
            val task = Runnable {
                delayed.remove(doc)
                if (p.getString("account", null) != session.accountId) return@Runnable
                flush(c, doc)
            }
            if (closing) task.run() else delayed[doc] = worker.schedule(task, 8, TimeUnit.SECONDS)
        }
    }

    fun checkOnReturn(context: Context, session: Session, changed: () -> Unit) {
        val c = context.applicationContext
        worker.execute {
            try {
                val p = prefs(c)
                if (p.getString("account", null) != session.accountId || !sessions.containsKey(session)) return@execute
                val account = AccountVault.read(c) ?: return@execute
                if (p.contains("pending.${session.document}")) flush(c, session.document)
                if (p.contains("pending.${session.document}")) return@execute
                val remote = if (account.supportsBooks) FileShelf.open(c, account).readProgress(session.document).first
                    else SyncApi(account).read(session.document)
                if (remote != null && remote.id != session.base) {
                    android.os.Handler(android.os.Looper.getMainLooper()).post { changed() }
                }
            } catch (_: Exception) {
                // The open reader remains usable offline.
            }
        }
    }

    private fun flush(c: Context, doc: String) {
        val p = prefs(c)
        try {
            val account = AccountVault.read(c) ?: return
            reconcile(c, p, account, doc)
        } catch (_: Exception) {
            message(c, "보낼 위치가 기기에 저장됐습니다. 연결 후 다시 시도할 수 있습니다.")
        }
    }

    fun retry(c: Context) = serial {
        val p = prefs(c)
        val docs = p.all.keys.filter { it.startsWith("pending.") }
        if (docs.isEmpty()) {
            val account = AccountVault.read(c) ?: return@serial
            if (account.supportsBooks) FileShelf.open(c, account).list() else SyncApi(account).login(false)
            message(c, "연결됐습니다. 전송 대기 중인 위치가 없습니다.")
        } else {
            for (key in docs) flush(c, key.removePrefix("pending."))
        }
    }

    private fun reconcile(c: Context, p: SharedPreferences, account: SyncAccount, doc: String): SyncRecord? {
        val api = SyncApi(account)
        val shelf = if (account.supportsBooks) FileShelf.open(c, account) else null
        val (remote, tag) = shelf?.readProgress(doc) ?: (api.read(doc) to null)
        val version = remote?.id.orEmpty()
        val key = "pending.$doc"
        val pending = p.getString(key, null)
        if (pending == null) {
            p.edit().putString("base.$doc", version).apply()
            return remote
        }
        val entry = JSONObject(pending)
        val local = SyncRecord.decode(entry.getString("record"))
        val previousAttempt = p.getString("attempt.$doc", null)
        val baseSeen = if (previousAttempt != null && previousAttempt == version) version else entry.getString("base")
        val decision = replay(baseSeen, local.id, version)
        val accepted = when (decision) {
            Replay.SEND -> {
                p.edit().putString("attempt.$doc", local.id).commit()
                if (shelf == null) api.write(doc, local) else {
                    check(remote == null || tag != null)
                    check(shelf.putProgress(doc, local, tag))
                }
                local
            }
            Replay.ACKNOWLEDGE -> local
            Replay.KEEP_REMOTE -> remote
        }
        val base = accepted?.id.orEmpty()
        for (session in sessions.keys.toList()) {
            if (session.document == doc) {
                if (decision == Replay.KEEP_REMOTE) sessions[session] = false else session.base = base
            }
        }
        val status = if (decision == Replay.KEEP_REMOTE) "다른 기기에서도 위치가 바뀌었습니다. 책을 다시 열면 서버 위치에서 이어집니다."
            else "마지막 읽은 위치를 동기화했습니다."
        p.edit().remove(key).remove("attempt.$doc").putString("base.$doc", base).putString("status", status).commit()
        return accepted
    }
}
