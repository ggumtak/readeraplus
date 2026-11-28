package com.ggumtak.readeraplus.sync

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.util.UUID

internal const val DEFAULT_SERVER = "https://sync.koreader.rocks"

internal fun serverUrl(raw: String): String {
    val uri = URI(raw.trim().trimEnd('/'))
    require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null)
    return uri.toASCIIString()
}

internal fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
internal data class SyncAccount(val server: String, val user: String, val key: String, val device: String, val dav: Boolean = false, val drive: Boolean = false) {
    val supportsBooks get() = dav || drive
    companion object {
        fun from(raw: String, name: String, password: String, dav: Boolean = false): SyncAccount {
            val user = name.trim()
            if (dav) {
                require(user.isNotBlank() && ':' !in user && user.none { it.isISOControl() } && password.isNotBlank())
                return SyncAccount(serverUrl(raw), user, password, UUID.randomUUID().toString(), true)
            }
            require(user.matches(Regex("[A-Za-z0-9_.@+-]{1,128}")) && password.isNotEmpty())
            // This is the KOReader protocol's reusable credential. Store it as a secret.
            val key = MessageDigest.getInstance("MD5").digest(password.toByteArray(Charsets.UTF_8)).hex()
            return SyncAccount(serverUrl(raw), user, key, UUID.randomUUID().toString())
        }
    }
}

internal data class SyncPoint(val section: Int, val offset: Int, val fraction: Float, val signature: String) {
    init {
        require(section >= 0 && offset >= 0 && fraction in 0f..1f && signature.length <= 64)
    }
}

internal data class SyncRecord(val id: String, val point: SyncPoint) {
    fun encode(): String = JSONObject().put("v", 1).put("id", id).put("s", point.section).put("o", point.offset)
        .put("f", point.fraction.toDouble()).put("sig", point.signature).toString()

    companion object {
        fun decode(text: String): SyncRecord {
            val j = JSONObject(text)
            require(j.getInt("v") == 1)
            val id = UUID.fromString(j.getString("id")).toString()
            return SyncRecord(id, SyncPoint(j.getInt("s"), j.getInt("o"), j.getDouble("f").toFloat(), j.getString("sig")))
        }
    }
}

internal class SyncHttpError(val status: Int) : IOException("Sync HTTP $status")

/** Credentials stay on the configured HTTPS origin, including when a server returns a redirect. */
internal class SyncApi(private val account: SyncAccount) {
    fun login(register: Boolean) {
        if (register) {
            call("/users/create", "POST", JSONObject().put("username", account.user).put("password", account.key), authenticate = false)
        }
        check(call("/users/auth").optString("authorized") == "OK")
    }

    fun read(doc: String): SyncRecord? {
        val reply = call("/syncs/progress/$doc")
        if (reply.length() == 0) return null
        require(reply.getString("document") == doc)
        return SyncRecord.decode(reply.getString("progress"))
    }

    fun write(doc: String, record: SyncRecord) {
        val reply = call("/syncs/progress", "PUT", JSONObject().put("document", doc)
            .put("progress", record.encode()).put("percentage", record.point.fraction.toDouble())
            .put("device", "PureReader").put("device_id", account.device))
        check(reply.getString("document") == doc)
    }

    private fun call(path: String, method: String = "GET", data: JSONObject? = null, authenticate: Boolean = true): JSONObject {
        val conn = URL(account.server + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.instanceFollowRedirects = false
            conn.useCaches = false
            conn.connectTimeout = 2500
            conn.readTimeout = 3500
            conn.setRequestProperty("Accept", "application/vnd.koreader.v1+json")
            if (authenticate) {
                conn.setRequestProperty("x-auth-user", account.user)
                conn.setRequestProperty("x-auth-key", account.key)
            }
            if (data != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(data.toString().toByteArray(Charsets.UTF_8)) }
            }
            if (conn.responseCode !in 200..299) throw SyncHttpError(conn.responseCode)
            val bytes = java.io.ByteArrayOutputStream()
            conn.inputStream.use { input ->
                val buf = ByteArray(2048)
                while (true) {
                    val count = input.read(buf)
                    if (count < 0) break
                    require(bytes.size() + count <= 16384)
                    bytes.write(buf, 0, count)
                }
            }
            return JSONObject(bytes.toString("UTF-8"))
        } finally {
            conn.disconnect()
        }
    }
}
