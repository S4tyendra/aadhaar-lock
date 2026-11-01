package `in`.devh.aadhaarlock

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

// AES-GCM key held in the TEE with no user auth, so api calls can decrypt tokens unattended.
class TokenVault(private val prefs: Prefs) {
    private companion object {
        const val STORE = "AndroidKeyStore"
        const val ALIAS = "aadhaarlock_tokens"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }

    init {
        prefs.takeLegacyTokens()?.let {
            save(it)
            DebugLog.line("tokens moved to vault")
        }
    }

    val hasSession: Boolean get() = prefs.tokenBlob.isNotBlank()

    fun load(): TathyaApi.TokenPair? {
        if (!hasSession) return null
        return try {
            val blob = Base64.decode(prefs.tokenBlob, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, blob, 0, IV_BYTES))
            val obj = JSONObject(String(cipher.doFinal(blob, IV_BYTES, blob.size - IV_BYTES), Charsets.UTF_8))
            TathyaApi.TokenPair(obj.getString("access"), obj.getString("refresh"), obj.getLong("exp"))
        } catch (t: Exception) {
            DebugLog.line("token vault ${t.javaClass.simpleName}: ${t.message}")
            clear()
            null
        }
    }

    fun save(tokens: TathyaApi.TokenPair) {
        val plain = JSONObject()
            .put("access", tokens.access)
            .put("refresh", tokens.refresh)
            .put("exp", tokens.expiresAtMs)
            .toString()
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val sealed = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        prefs.tokenBlob = Base64.encodeToString(cipher.iv + sealed, Base64.NO_WRAP)
    }

    fun clear() {
        prefs.tokenBlob = ""
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(STORE).apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, STORE)
        gen.init(spec)
        return gen.generateKey()
    }
}
