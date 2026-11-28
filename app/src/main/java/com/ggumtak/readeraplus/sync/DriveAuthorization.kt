package com.ggumtak.readeraplus.sync

import android.accounts.Account
import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import java.io.IOException
import java.util.concurrent.TimeUnit

internal object DriveAuthorization {
    const val SCOPE = "https://www.googleapis.com/auth/drive.file"
    fun request(user: String): AuthorizationRequest = AuthorizationRequest.builder()
        .setAccount(Account(user, "com.google"))
        .setRequestedScopes(listOf(Scope(SCOPE)))
        .build()

    /** Background operations never open the consent screen. Play services supplies a fresh access token as needed. */
    fun token(c: Context, user: String): String {
        val result = Tasks.await(Identity.getAuthorizationClient(c).authorize(request(user)), 10, TimeUnit.SECONDS)
        return result.accessToken?.takeUnless { result.hasResolution() } ?: throw IOException("Drive consent required")
    }
}
