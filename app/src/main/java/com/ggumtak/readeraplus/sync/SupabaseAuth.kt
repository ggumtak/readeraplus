package com.ggumtak.readeraplus.sync

import android.content.Context
import com.ggumtak.readeraplus.R
import org.json.JSONObject
import java.util.UUID

internal object SupabaseAuth {
    class VerifyEmail : Exception()
    private fun url(c: Context) = c.getString(R.string.reader_supabase_url).trim().trimEnd('/')
    private fun publicKey(c: Context) = c.getString(R.string.reader_supabase_key).trim()
    fun configured(c: Context) = url(c).matches(Regex("https://[a-z0-9-]+\\.supabase\\.co")) &&
        publicKey(c).matches(Regex("sb_publishable_[A-Za-z0-9_-]+"))

    fun signIn(c: Context, email: String, password: String, signup: Boolean): SyncAccount {
        check(configured(c))
        val http = SupabaseHttp(url(c), publicKey(c))
        val path = if (signup) "/auth/v1/signup" else "/auth/v1/token?grant_type=password"
        val reply = JSONObject(http.request(path, "POST", body = JSONObject().put("email", email.trim()).put("password", password)))
        if (signup && !reply.has("access_token")) throw VerifyEmail()
        val key = session(reply)
        return SyncAccount(url(c), reply.getJSONObject("user").getString("email"), key.toString(), UUID.randomUUID().toString(), supabase = true)
    }

    private fun session(reply: JSONObject) = JSONObject().apply {
        put("uid", UUID.fromString(reply.getJSONObject("user").getString("id")).toString())
        put("access", reply.getString("access_token"))
        put("refresh", reply.getString("refresh_token"))
        put("expires", System.currentTimeMillis() / 1000 + reply.getLong("expires_in"))
    }
    fun uid(a: SyncAccount): UUID = UUID.fromString(JSONObject(a.key).getString("uid"))
    private fun http(c: Context, a: SyncAccount): SupabaseHttp {
        check(configured(c) && a.server == url(c))
        return SupabaseHttp(a.server, publicKey(c))
    }
    fun checkShelf(c: Context, a: SyncAccount) =
        SupabaseShelf(http(c, a), uid(a), { JSONObject(a.key).getString("access") }).checkConnection()

    @Synchronized private fun token(c: Context, original: SyncAccount): String {
        val a = checkNotNull(AccountVault.read(c))
        check(a.device == original.device && a.supabase)
        val saved = JSONObject(a.key)
        if (saved.getLong("expires") > System.currentTimeMillis() / 1000 + 60) return saved.getString("access")
        val reply = JSONObject(http(c, a).request("/auth/v1/token?grant_type=refresh_token", "POST",
            body = JSONObject().put("refresh_token", saved.getString("refresh"))))
        val next = session(reply)
        check(next.getString("uid") == saved.getString("uid"))
        AccountVault.replaceSession(c, a.copy(key = next.toString()))
        return next.getString("access")
    }

    fun shelf(c: Context, a: SyncAccount, allow: () -> Boolean) =
        SupabaseShelf(http(c, a), uid(a), { token(c, a) }, allow)
}
