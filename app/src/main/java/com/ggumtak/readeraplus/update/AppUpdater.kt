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
import java.lang.ref.WeakReference
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Checks the GitHub release tagged latest, downloads its APK, and hands it to the Android installer.
 * The library checks at most every 12 h (30 min after a check that got no answer). Settings can check immediately.
 *
 * An update the user asked for is never dropped quietly: a download that finished after its screen was gone, or one
 * that waits for the ‘알 수 없는 앱 설치’ permission, or whose confirm screen could not open in the background, is
 * remembered ([K_PENDING]) and installed the next time the library or the settings list comes to the front ([resume]).
 * The permission prompt is shown once per build ([K_ASKED], [UpdateInfo.resumeStep]).
 */
internal object AppUpdater {
    private const val PREFS = "app_update"
    private const val K_LAST = "last_check"
    private const val K_SKIPPED = "skipped_build"
    /** The build whose verified download waits in cache/update to be installed (0 = none). */
    private const val K_PENDING = "pending_build"
    /** The pending build whose ‘알 수 없는 앱 설치’ prompt was shown already (0 = none). */
    private const val K_ASKED = "pending_asked"
    private const val AUTO_INTERVAL_MS = 12L * 60 * 60 * 1000
    private const val RETRY_MS = 30L * 60 * 1000
    private const val DIR = "update"
    const val ACTION_STATUS = "com.ggumtak.readeraplus.UPDATE_STATUS"
    /** The build an install session carries to [UpdateReceiver]. */
    const val EXTRA_BUILD = "com.ggumtak.readeraplus.extra.UPDATE_BUILD"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    /** Pending-state reads and writes in order (a quick 취소 must not be overtaken by the write that set it). */
    private val prefsIo = Dispatchers.IO.limitedParallelism(1)
    private var busy = false
    /** The screen that last called in: a download that outlived its own screen installs from here. Weak: no leak. */
    private var front: WeakReference<Activity>? = null
    /** [K_ASKED] as soon as the prompt shows (the prefs write follows): a second onResume must not prompt again. */
    @Volatile private var askedBuild = 0

    /**
     * A library visit: first an update that waits to be installed ([resume]); else checks at most every 12 h and
     * offers a newer build unless the user said 나중에 to it.
     */
    fun autoCheck(activity: Activity) {
        front = WeakReference(activity)
        if (busy) return
        val app = activity.applicationContext
        scope.launch {
            if (resumeWaiting(activity, app)) return@launch
            val due = withContext(Dispatchers.IO) {
                val prefs = prefs(app)
                val now = System.currentTimeMillis()
                UpdateInfo.due(now, prefs.getLong(K_LAST, 0L), AUTO_INTERVAL_MS).also { if (it) prefs.edit().putLong(K_LAST, now).apply() }
            }
            if (due && alive(activity)) check(activity, manual = false)
        }
    }

    /**
     * The settings list is in front again (back from the system's ‘알 수 없는 앱 설치’ page): installs the update that
     * waited for it, or shows the permission prompt once. Nothing else (settings has no automatic check).
     */
    fun resume(activity: Activity) {
        front = WeakReference(activity)
        if (busy) return
        val app = activity.applicationContext
        scope.launch { resumeWaiting(activity, app) }
    }

    /**
     * Installs the waiting download, or asks for the permission it needs, as [UpdateInfo.resumeStep] says.
     * False when the caller should go on with its usual check ([UpdateInfo.ResumeStep.CHECK]).
     */
    private suspend fun resumeWaiting(activity: Activity, app: Context): Boolean {
        // The prefs file is read off the main thread (its first load blocks).
        val (waiting, marked) = withContext(prefsIo) {
            val file = pendingFile(app)
            file to (file != null && prefs(app).getInt(K_ASKED, 0) == UpdateInfo.buildOf(file.name))
        }
        val asked = waiting != null && (marked || askedBuild == UpdateInfo.buildOf(waiting.name))
        val step = UpdateInfo.resumeStep(
            pending = waiting != null,
            permitted = activity.packageManager.canRequestPackageInstalls(),
            asked = asked,
        )
        if (step == UpdateInfo.ResumeStep.CHECK || waiting == null) return false
        // A check, a download or an install may have started while the prefs were read: it owns the update now.
        if (!busy && alive(activity)) install(activity, waiting)
        return true
    }

    /** The verified download that waits to be installed, or null (a stale mark is dropped). IO. */
    private fun pendingFile(context: Context): File? {
        val build = prefs(context).getInt(K_PENDING, 0)
        if (build == 0) return null
        // Only a download that passed isExpectedApk carries this name.
        val file = File(File(context.cacheDir, DIR), UpdateInfo.fileName(build))
        if (build > BuildConfig.VERSION_CODE && file.isFile) return file
        setPending(context, 0)
        return null
    }

    /** Marks [build] as waiting to be installed, or (0) forgets the waiting one together with its prompt mark. */
    private fun setPending(context: Context, build: Int) {
        val e = prefs(context).edit()
        if (build > 0) {
            e.putInt(K_PENDING, build)
        } else {
            askedBuild = 0
            e.remove(K_PENDING).remove(K_ASKED)
        }
        e.apply()
    }

    /**
     * The installer's confirm screen for [build] could not open (the app was not in front): its download waits to be
     * installed the next time the library or the settings list comes to the front. The prompt mark is kept (the
     * permission is there already). A short prefs write, allowed from [UpdateReceiver.onReceive].
     */
    fun keepPending(context: Context, build: Int) {
        if (build > 0) setPending(context, build)
    }

    /** Asks GitHub now; [manual] says what it found even when there is nothing to update. */
    fun check(activity: Activity, manual: Boolean) {
        front = WeakReference(activity)
        if (busy) {
            // A tap must always answer (an automatic check, a download or an install is under way).
            if (manual) activity.toast("이미 업데이트를 진행 중입니다")
            return
        }
        if (manual) activity.toast("업데이트 확인 중…")
        busy = true
        val app = activity.applicationContext
        scope.launch {
            val latest = withContext(Dispatchers.IO) {
                runCatching {
                    sweep(app)
                    getText(UpdateInfo.LATEST_URL)?.let(UpdateInfo::parse)
                }.getOrNull().also { answer ->
                    // No answer (offline when the library opened): the automatic check tries again in 30 min, not 12 h.
                    val now = System.currentTimeMillis()
                    val stamp = if (answer != null) now else UpdateInfo.retryStamp(now, AUTO_INTERVAL_MS, RETRY_MS)
                    runCatching { prefs(app).edit().putLong(K_LAST, stamp).apply() }
                }
            }
            busy = false
            if (!alive(activity)) return@launch
            val current = BuildConfig.VERSION_CODE
            when {
                latest == null -> if (manual) activity.toast("업데이트를 확인하지 못했습니다")
                !UpdateInfo.isNewer(latest, current) -> if (manual) activity.toast("최신 버전입니다 (현재 빌드 $current)")
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
            var shownBucket = -1
            val file = withContext(Dispatchers.IO) {
                runCatching {
                    fetchApk(app, latest, cancelled) { percent ->
                        // Every 10 %: each change is an e-ink update (bucket -1 ensures 0% updates immediately).
                        val bucket = percent / 10
                        if (bucket != shownBucket) {
                            shownBucket = bucket
                            scope.launch {
                                if (alive(activity) && dialog.isShowing) {
                                    dialog.setMessage(UpdateInfo.progressMessage(percent))
                                }
                            }
                        }
                    }
                }.getOrNull()
            }
            if (dialog.isShowing) runCatching { dialog.dismiss() }
            busy = false
            if (cancelled.get()) return@launch
            if (!alive(activity)) {
                // The screen was rebuilt meanwhile (rotation, dark mode, font scale). Its successor's autoCheck went by
                // while this was busy, so the install goes on from the screen in front now; with none, the download is
                // kept for the next autoCheck / resume. The progress dialog belonged to the old screen: not shown again.
                val next = front?.get()?.takeIf { alive(it) }
                when {
                    file == null -> app.toast("업데이트를 내려받지 못했습니다")
                    next != null -> install(next, file)
                    else -> {
                        withContext(prefsIo) { setPending(app, latest.build) }
                        app.toast("업데이트 파일을 받았습니다. 앱으로 돌아오면 설치를 이어갑니다")
                    }
                }
                return@launch
            }
            if (file == null) {
                activity.toast("업데이트를 내려받지 못했습니다")
                return@launch
            }
            install(activity, file)
        }
    }

    private fun install(activity: Activity, file: File) {
        val app = activity.applicationContext
        if (!activity.packageManager.canRequestPackageInstalls()) {
            // Remembered across the system's page (the process may be restarted there): the install goes on when the
            // library or the settings list is in front again, with nothing to press a second time. The prompt itself is
            // marked so that those screens do not show it again (a refusal leaves the usual check running).
            val build = UpdateInfo.buildOf(file.name)
            askedBuild = build
            scope.launch(prefsIo) { prefs(app).edit().putInt(K_PENDING, build).putInt(K_ASKED, build).apply() }
            // 취소, back or a touch outside: the user does not want it now. 설정 열기 keeps the update waiting.
            val drop = { scope.launch(prefsIo) { setPending(app, 0) }; Unit }
            activity.alert()
                .setTitle("설치 허용")
                .setMessage("업데이트를 설치하려면 이 앱의 ‘알 수 없는 앱 설치’ 권한을 허용해야 합니다. 허용 후 돌아오면 설치를 이어갑니다.")
                .setPositiveButton("설정 열기") { _, _ ->
                    runCatching {
                        activity.startActivity(
                            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + activity.packageName)),
                        )
                    }
                }
                .setNegativeButton("취소") { _, _ -> drop() }
                .setOnCancelListener { drop() }
                .showNoAnim()
            return
        }
        busy = true
        scope.launch {
            withContext(prefsIo) { setPending(app, 0) }
            val ok = withContext(Dispatchers.IO) {
                runCatching { commit(app, file) }.isSuccess.also { if (!it) file.delete() }
            }
            busy = false
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
                // The build rides along: a confirm screen that cannot open puts it back to pending (UpdateReceiver).
                val intent = Intent(context, UpdateReceiver::class.java).setAction(ACTION_STATUS)
                    .putExtra(EXTRA_BUILD, UpdateInfo.buildOf(file.name))
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

    /** A page's text (at most 2 MB), or null for anything but 200. */
    private fun getText(url: String): String? {
        val conn = open(url).apply {
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        }
        try {
            if (conn.responseCode != 200) return null
            val bytes = conn.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                while (out.size() < 2 * 1024 * 1024) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            }
            return bytes.toString(Charsets.UTF_8)
        } finally {
            conn.disconnect()
        }
    }

    /** The APK of [latest] in cache/update, downloaded unless an earlier attempt finished it. */
    private fun fetchApk(context: Context, latest: UpdateInfo.Latest, cancelled: AtomicBoolean, progress: (Int) -> Unit): File? {
        val dir = File(context.cacheDir, DIR).apply { mkdirs() }
        val target = File(dir, UpdateInfo.fileName(latest.build))
        if (isExpectedApk(context, target, latest)) return target
        val part = File(dir, "download-${latest.build}.apk")
        val conn = open(latest.url)
        try {
            if (conn.responseCode != 200) return null
            val total = conn.contentLengthLong
            if (total >= 0 && total != latest.size) return null
            var done = 0L
            conn.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        if (cancelled.get()) return null
                        val n = input.read(buf)
                        if (n < 0) break
                        done += n
                        if (done > latest.size) return null
                        out.write(buf, 0, n)
                        progress((done * 100 / latest.size).toInt())
                    }
                }
            }
            if (cancelled.get() || !isExpectedApk(context, part, latest)) return null
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
            setRequestProperty("User-Agent", "PureReader/${BuildConfig.VERSION_CODE}")
        }

    @Suppress("DEPRECATION")
    private fun isExpectedApk(context: Context, file: File, latest: UpdateInfo.Latest): Boolean {
        if (file.length() != latest.size) return false
        val head = ByteArray(4)
        if (!file.inputStream().use { it.read(head) == head.size && UpdateInfo.looksLikeApk(head) }) return false
        if (latest.sha256 != null) {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            if (hash != latest.sha256) return false
        }
        val archive = context.packageManager.getPackageArchiveInfo(file.absolutePath, 0) ?: return false
        val version = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else archive.versionCode.toLong()
        return archive.packageName == context.packageName && version == latest.build.toLong()
    }

    /** Drops every cached download: a failed install must not reuse a file that may be cut short. */
    fun forgetDownloads(context: Context) {
        File(context.cacheDir, DIR).listFiles()?.forEach { it.delete() }
        setPending(context, 0)
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
