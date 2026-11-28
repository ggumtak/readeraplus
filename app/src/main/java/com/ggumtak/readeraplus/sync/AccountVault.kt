package com.ggumtak.readeraplus.sync

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Device-only account; the encrypted file is outside auto-backup and the key stays in Android Keystore. */
internal object AccountVault {
    private const val ALIAS = "purereader.sync.v1"
    private fun file(ctx: Context) = AtomicFile(File(ctx.noBackupFilesDir, "reading-sync.account"))

    private fun key(create: Boolean): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        check(create)
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    @Synchronized fun save(ctx: Context, a: SyncAccount) {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key(true))
        require(c.iv.size == 12)
        val json = JSONObject().put("server", a.server).put("user", a.user).put("key", a.key).put("device", a.device).put("dav", a.dav).put("drive", a.drive).put("supabase", a.supabase)
        val bytes = c.iv + c.doFinal(json.toString().toByteArray(Charsets.UTF_8))
        val f = file(ctx)
        val out = f.startWrite()
        try { out.write(bytes); f.finishWrite(out) } catch (e: Exception) { f.failWrite(out); throw e }
    }

    @Synchronized fun read(ctx: Context): SyncAccount? {
        val bytes = try { file(ctx).readFully() } catch (_: java.io.FileNotFoundException) { return null }
        require(bytes.size in 29..16384)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, bytes, 0, 12))
        val j = JSONObject(String(c.doFinal(bytes, 12, bytes.size - 12), Charsets.UTF_8))
        return SyncAccount(serverUrl(j.getString("server")), j.getString("user"), j.getString("key"), j.getString("device"), j.optBoolean("dav"), j.optBoolean("drive"), j.optBoolean("supabase"))
    }

    @Synchronized fun replaceSession(ctx: Context, account: SyncAccount) {
        check(read(ctx)?.device == account.device)
        save(ctx, account)
    }

    @Synchronized fun clear(ctx: Context) = file(ctx).delete()
}
