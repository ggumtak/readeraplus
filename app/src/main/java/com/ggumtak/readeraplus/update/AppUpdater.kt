package com.ggumtak.readeraplus.update

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.ggumtak.readeraplus.BuildConfig
import com.ggumtak.readeraplus.ui.kit.alert
import com.ggumtak.readeraplus.ui.kit.showNoAnim
import com.ggumtak.readeraplus.ui.kit.toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * In-app update (the user, 2026-10-11: "앱 내에서 업데이트가 있습니다 … 예 누르면 업데이트"). Every CI build is in the
 * user's Google Drive (tools/gdrive/upload.gs); its web app ([BuildConfig.UPDATE_URL], baked in by CI) answers
 * `?what=latest` with the newest build and its file id. A newer build is offered in a dialog; 업데이트 downloads the
 * APK (cache/update) and hands it to [PackageInstaller], which asks the user once to confirm (Android never installs
 * silently on a first update; later ones may skip it, [PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED]).
 * Android only accepts an APK signed like the installed app, so nothing else can be installed this way.
 *
 * The library checks at most every 12 h ([autoCheck]); 설정 → 업데이트 확인 checks now ([check] manual). Network and
 * file work run on IO; dialogs on the main thread, only while the activity is alive.
 */
internal object AppUpdater {
    private const val PREFS = "app_update"
    private const val K_LAST = "last_check"
    private const val K_SKIPPED = "skipped_build"
    private const val AUTO_INTERVAL_MS = 12L * 60 * 60 * 1000
    private const val DIR = "update"
    private const val MAX_BYTES = 300L * 1024 * 1024
    const val ACTION_STATUS = "com.ggumtak.readeraplus.UPDATE_STATUS"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var busy = false

    fun enabled(): Boolean = BuildConfig.UPDATE_URL.isNotEmpty()

    /** A library visit: checks at most every 12 h and offers a newer build unless the user said 나중에 to it. */
    fun autoCheck(activity: Activity) {
        if (!enabled() || busy) return
        val app = activity.applicationContext
        scope.launch {
            // The prefs file is read off the main thread (its first load blocks).
            val due = withContext(Dispatchers.IO) {
                val prefs = prefs(app)
                val now = System.currentTimeMillis()
                UpdateInfo.due(now, prefs.getLong(K_LAST, 0L), AUTO_INTERVAL_MS).also { if (it) prefs.edit().putLong(K_LAST, now).apply() }
            }
            if (due && alive(activity)) check(activity, manual = false)
        }
    }

    /** Asks the web app now; [manual] says what it found even when there is nothing to update. */
    fun check(activity: Activity, manual: Boolean) {
        if (!enabled()) {
            if (manual) activity.toast("이 빌드에는 업데이트 주소가 없습니다")
            return
        }
        if (manual) activity.toast("업데이트 확인 중…")
        if (busy) return
        busy = true
        val app = activity.applicationContext
        scope.launch {
            val latest = withContext(Dispatchers.IO) {
                sweep(app)
                runCatching { fetchLatest() }.getOrNull()
            }
            busy = false
            if (!alive(activity)) return@launch
            val current = BuildConfig.VERSION_CODE
            when {
                latest == null -> if (manual) activity.toast("업데이트를 확인하지 못했습니다")
                !UpdateInfo.isNewer(latest, current) -> if (manual) activity.toast("최신 버전입니다 (빌드 $current)")
                !manual && prefs(activity).getInt(K_SKIPPED, 0) >= latest.build -> Unit
                else -> offer(activity, latest)
            }
        }
    }

    private fun offer(activity: Activity, latest: UpdateInfo.Latest) {
        activity.alert()
            .setTitle("업데이트")
            .setMessage(UpdateInfo.offerMessage(latest.build, BuildConfig.VERSION_CODE))
            .setPositiveButton("업데이트") { _, _ -> download(activity, latest) }
            .setNegativeButton("나중에") { _, _ -> prefs(activity).edit().putInt(K_SKIPPED, latest.build).apply() }
            .showNoAnim()
    }

    private fun download(activity: Activity, latest: UpdateInfo.Latest) {
        if (busy) return
        busy = true
        val app = activity.applicationContext
        val cancelled = AtomicBoolean(false)
        val dialog: AlertDialog = activity.alert()
            .setTitle("업데이트")
            .setMessage(UpdateInfo.progressMessage(-1))
            .setNegativeButton("취소") { _, _ -> cancelled.set(true) }
            .setCancelable(false)
            .showNoAnim()
        scope.launch {
            var shown = -1
            val file = withContext(Dispatchers.IO) {
                runCatching {
                    fetchApk(app, latest, cancelled) { percent ->
                        // Every 10 %: each change is an e-ink update.
                        if (percent / 10 != shown / 10) {
                            shown = percent
                            scope.launch { if (dialog.isShowing) dialog.setMessage(UpdateInfo.progressMessage(percent)) }
                        }
                    }
                }.getOrNull()
            }
            if (dialog.isShowing) runCatching { dialog.dismiss() }
            busy = false
            if (cancelled.get() || !alive(activity)) return@launch
            if (file == null) {
                activity.toast("업데이트를 내려받지 못했습니다")
                return@launch
            }
            install(activity, file)
        }
    }

    private fun install(activity: Activity, file: File) {
        if (!activity.packageManager.canRequestPackageInstalls()) {
            activity.alert()
                .setTitle("설치 허용")
                .setMessage("업데이트를 설치하려면 이 앱의 ‘알 수 없는 앱 설치’를 허용해야 합니다. 허용한 뒤 업데이트를 다시 눌러 주세요.")
                .setPositiveButton("설정 열기") { _, _ ->
                    runCatching {
                        activity.startActivity(
                            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + activity.packageName)),
                        )
                    }
                }
                .setNegativeButton("취소", null)
                .showNoAnim()
            return
        }
        val app = activity.applicationContext
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { commit(app, file) }.isSuccess.also { if (!it) file.delete() }
            }
            if (!ok && alive(activity)) activity.toast("업데이트를 설치하지 못했습니다")
        }
    }

    /** Hands [file] to the platform installer; [UpdateReceiver] gets the outcome (the confirm screen first). */
    private fun commit(context: Context, file: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(file.length())
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { session ->
                session.openWrite("base.apk", 0, file.length()).use { out ->
                    file.inputStream().use { it.copyTo(out, 64 * 1024) }
                    session.fsync(out)
                }
                val intent = Intent(context, UpdateReceiver::class.java).setAction(ACTION_STATUS)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                val pending = PendingIntent.getBroadcast(context, id, intent, flags)
                session.commit(pending.intentSender)
            }
        } catch (t: Throwable) {
            runCatching { installer.abandonSession(id) }
            throw t
        }
    }

    // ------------------------------------------------------------------ network (IO)

    private fun fetchLatest(): UpdateInfo.Latest? {
        val conn = open(UpdateInfo.checkUrl(BuildConfig.UPDATE_URL))
        try {
            if (conn.responseCode != 200) return null
            val text = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            return UpdateInfo.parse(text)
        } finally {
            conn.disconnect()
        }
    }

    /** The APK of [latest] in cache/update, downloaded unless an earlier attempt finished it. */
    private fun fetchApk(context: Context, latest: UpdateInfo.Latest, cancelled: AtomicBoolean, progress: (Int) -> Unit): File? {
        val dir = File(context.cacheDir, DIR).apply { mkdirs() }
        val target = File(dir, UpdateInfo.fileName(latest.build))
        if (target.length() > 0 && isApk(target)) return target
        val part = File(dir, target.name + ".part")
        val conn = open(UpdateInfo.downloadUrl(latest.fileId))
        try {
            if (conn.responseCode != 200) return null
            val total = conn.contentLengthLong
            if (total > MAX_BYTES) return null
            var done = 0L
            conn.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        if (cancelled.get()) return null
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (done > MAX_BYTES) return null
                        progress(if (total > 0) (done * 100 / total).toInt().coerceIn(0, 100) else -1)
                    }
                }
            }
            if (!isApk(part) || (total > 0 && done != total)) return null
            target.delete()
            return if (part.renameTo(target)) target else null
        } finally {
            conn.disconnect()
            part.delete()
        }
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "ReaderaPlus/${BuildConfig.VERSION_CODE}")
        }

    private fun isApk(f: File): Boolean = try {
        val head = ByteArray(4)
        val n = f.inputStream().use { it.read(head) }
        UpdateInfo.looksLikeApk(head, n)
    } catch (e: Exception) {
        false
    }

    /** Drops every cached download: a failed install must not reuse a file that may be cut short. */
    fun forgetDownloads(context: Context) {
        File(context.cacheDir, DIR).listFiles()?.forEach { it.delete() }
    }

    /** Removes downloads of builds this one already is (or is newer than). */
    private fun sweep(context: Context) {
        val files = File(context.cacheDir, DIR).listFiles() ?: return
        for (f in files) {
            val b = UpdateInfo.buildOf(f.name)
            if (b == 0 || b <= BuildConfig.VERSION_CODE) f.delete()
        }
    }

    private fun prefs(context: Context): SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun alive(a: Activity): Boolean = !a.isFinishing && !a.isDestroyed
}
