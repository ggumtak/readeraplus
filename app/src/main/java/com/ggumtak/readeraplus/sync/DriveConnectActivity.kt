package com.ggumtak.readeraplus.sync

import android.accounts.AccountManager
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.api.ApiException

/** Account selection and Google's consent screen belong to this activity, never to background sync. */
class DriveConnectActivity : Activity() {
    private var email: String? = null
    private var waitingConsent = false

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        email = state?.getString("account") ?: intent.getStringExtra("reauthorize")
        waitingConsent = state?.getBoolean("consent") == true
        if (GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(this) != ConnectionResult.SUCCESS) {
            fail("Google Play 서비스를 설치하거나 업데이트한 뒤 다시 시도하세요.")
            return
        }
        if (email != null && !waitingConsent) authorize()
        else if (state == null && email == null) {
            try {
                val chooser = AccountManager.newChooseAccountIntent(null, null, arrayOf("com.google"), null, null, null, null)
                startActivityForResult(chooser, ACCOUNT)
            } catch (_: Exception) { fail("이 기기에서 Google 계정 선택 화면을 열 수 없습니다.") }
        }
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putString("account", email)
        out.putBoolean("consent", waitingConsent)
    }

    private fun authorize() {
        val user = email ?: return
        Identity.getAuthorizationClient(this).authorize(DriveAuthorization.request(user))
            .addOnSuccessListener { result ->
                if (!isFinishing && !isDestroyed) handle(result)
            }.addOnFailureListener { e -> if (!isFinishing && !isDestroyed) authError(e) }
    }

    private fun handle(result: AuthorizationResult) {
        if (result.hasResolution()) {
            try {
                val pending = checkNotNull(result.pendingIntent)
                waitingConsent = true
                startIntentSenderForResult(pending.intentSender, CONSENT, null, 0, 0, 0)
            } catch (_: Exception) { fail("Google Drive 권한 화면을 열 수 없습니다.") }
            return
        }
        if (result.accessToken.isNullOrBlank()) { fail("Google Drive 파일 접근 권한이 필요합니다."); return }
        setResult(RESULT_OK, Intent().putExtra("account", email))
        finish()
    }

    @Deprecated("Activity result callback for native settings")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) { finish(); return }
        when (requestCode) {
            ACCOUNT -> {
                email = data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)
                if (email.isNullOrBlank()) finish() else authorize()
            }
            CONSENT -> {
                waitingConsent = false
                try { handle(Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(data)) }
                catch (e: Exception) { authError(e) }
            }
        }
    }

    private fun authError(e: Exception) {
        fail(if ((e as? ApiException)?.statusCode == 10) "Google Cloud에서 이 앱의 Android OAuth 등록을 확인해야 합니다."
            else "Google Drive 연결을 승인하지 못했습니다. 계정과 네트워크를 확인하세요.")
    }

    private fun fail(message: String) {
        setResult(RESULT_CANCELED, Intent().putExtra("error", message))
        finish()
    }

    private companion object {
        const val ACCOUNT = 1
        const val CONSENT = 2
    }
}
