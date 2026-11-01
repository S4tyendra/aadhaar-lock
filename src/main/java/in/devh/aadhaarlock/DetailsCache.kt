package `in`.devh.aadhaarlock

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

// Read-only details for offline viewing. The key lives inside the UidVault blob, so one
// biometric opens the number and this file, and refreshes can rewrite it without a prompt.
class DetailsCache(context: Context) {
    data class Saved(val profile: JSONObject, val bio: String, val bioUntil: String, val savedAt: Long)

    private val file = File(context.noBackupFilesDir, "details.bin")

    fun write(key: ByteArray, saved: Saved) {
        val plain = JSONObject()
            .put("profile", saved.profile)
            .put("bio", saved.bio)
            .put("bioUntil", saved.bioUntil)
            .put("savedAt", saved.savedAt)
            .toString()
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        file.writeBytes(cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8)))
    }

    fun read(key: ByteArray): Saved? {
        if (!file.exists()) return null
        return try {
            val blob = file.readBytes()
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, blob, 0, IV_BYTES))
            val obj = JSONObject(String(cipher.doFinal(blob, IV_BYTES, blob.size - IV_BYTES), Charsets.UTF_8))
            Saved(
                obj.getJSONObject("profile"),
                obj.optString("bio"),
                obj.optString("bioUntil"),
                obj.optLong("savedAt"),
            )
        } catch (t: GeneralSecurityException) {
            DebugLog.line("details cache ${t.javaClass.simpleName}: ${t.message}")
            null
        }
    }

    fun clear() {
        file.delete()
    }

    companion object {
        private const val TRANSFORM = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128

        fun newKey(): ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }
    }
}
