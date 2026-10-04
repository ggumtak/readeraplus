package com.ggumtak.readeraplus.ui.settings

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import com.ggumtak.readeraplus.ui.kit.alert
import com.ggumtak.readeraplus.ui.kit.showNoAnim
import com.ggumtak.readeraplus.ui.kit.toast
import java.io.File
import android.provider.Settings as SystemSettings

/** "모든 파일 접근" status and the system screens that grant it (with fallbacks for trimmed e-reader firmware). */
internal object StorageAccess {
    fun granted(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 30) {
            Environment.isExternalStorageManager()
        } else {
            context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }

    fun summary(context: Context): String = if (granted(context)) "허용됨" else "허용 안 됨 · 눌러서 허용"

    /** Opens the system permission screen (API 30+) or asks for READ_EXTERNAL_STORAGE (API 26–29). */
    fun request(activity: Activity) {
        if (Build.VERSION.SDK_INT < 30) {
            activity.requestPermissions(
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE),
                SettingsActivity.REQ_STORAGE_PERMISSION,
            )
            return
        }
        val specific = Intent(SystemSettings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${activity.packageName}"))
        val generic = Intent(SystemSettings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
        for (intent in listOf(specific, generic)) {
            try {
                activity.startActivity(intent)
                return
            } catch (_: Exception) {
                // Some e-reader firmwares strip these screens; try the next one.
            }
        }
        showAdbHint(activity)
    }

    /** No system screen for the grant (trimmed firmware): the library's way, or the adb command to copy. */
    fun showAdbHint(activity: Activity) {
        val command = "adb shell appops set ${activity.packageName} MANAGE_EXTERNAL_STORAGE allow"
        activity.alert()
            .setTitle("권한 화면을 열 수 없습니다")
            .setMessage(
                "이 기기에서는 ‘모든 파일 접근’ 화면을 열 수 없습니다. 서재 ⋮ 메뉴의 ‘스캔 폴더 추가’로 책 폴더를 고르거나, " +
                    "PC에 연결해 아래 명령을 실행하세요.\n\n$command",
            )
            .setNegativeButton("닫기", null)
            .setPositiveButton("명령 복사") { _, _ ->
                runCatching {
                    val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("adb", command))
                }
                activity.toast("명령을 복사했습니다")
            }
            .showNoAnim()
    }

    /** Primary shared storage root ("/storage/emulated/0"). */
    @Suppress("DEPRECATION")
    fun primaryRoot(): String = runCatching { Environment.getExternalStorageDirectory().absolutePath }
        .getOrNull()?.takeIf { it.isNotEmpty() } ?: TreePaths.PRIMARY_ROOT

    /** True when [path] is a directory we can list (blocking; call off the main thread). */
    fun isReadableDir(path: String): Boolean = runCatching { File(path).let { it.isDirectory && it.canRead() } }.getOrDefault(false)
}
