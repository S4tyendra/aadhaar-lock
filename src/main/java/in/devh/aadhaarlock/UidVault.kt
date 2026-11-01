package `in`.devh.aadhaarlock

import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

// AES-GCM key held in StrongBox when present, otherwise the TEE; every use needs a strong biometric.
class UidVault(private val context: Context, private val prefs: Prefs) {
    private companion object {
        const val STORE = "AndroidKeyStore"
        const val ALIAS = "aadhaarlock_uid"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }

    val hasSaved: Boolean get() = prefs.uidBlob.isNotBlank()

    fun canUseBiometrics(): Boolean =
        context.getSystemService(BiometricManager::class.java)
            ?.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS

    fun encryptCipher(): Cipher {
        val cipher = Cipher.getInstance(TRANSFORM)
        try {
            cipher.init(Cipher.ENCRYPT_MODE, key())
        } catch (_: KeyPermanentlyInvalidatedException) {
            keyStore().deleteEntry(ALIAS)
            cipher.init(Cipher.ENCRYPT_MODE, key())
        }
        return cipher
    }

    // throws KeyPermanentlyInvalidatedException once enrolled biometrics change
    fun decryptCipher(): Cipher {
        val blob = Base64.decode(prefs.uidBlob, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, existingKey(), GCMParameterSpec(TAG_BITS, blob, 0, IV_BYTES))
        return cipher
    }

    fun store(cipher: Cipher, uid: String, detailsKey: ByteArray) {
        val plain = JSONObject()
            .put("uid", uid)
            .put("detailsKey", Base64.encodeToString(detailsKey, Base64.NO_WRAP))
            .toString()
        val sealed = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        prefs.uidBlob = Base64.encodeToString(cipher.iv + sealed, Base64.NO_WRAP)
        prefs.uid = ""
    }

    fun read(cipher: Cipher): Opened {
        val blob = Base64.decode(prefs.uidBlob, Base64.NO_WRAP)
        val obj = JSONObject(String(cipher.doFinal(blob, IV_BYTES, blob.size - IV_BYTES), Charsets.UTF_8))
        return Opened(obj.getString("uid"), Base64.decode(obj.getString("detailsKey"), Base64.NO_WRAP))
    }

    class Opened(val uid: String, val detailsKey: ByteArray)

    fun wipe() {
        prefs.uidBlob = ""
        keyStore().deleteEntry(ALIAS)
    }

    private fun keyStore() = KeyStore.getInstance(STORE).apply { load(null) }

    private fun existingKey(): SecretKey =
        keyStore().getKey(ALIAS, null) as? SecretKey ?: throw KeyPermanentlyInvalidatedException()

    private fun key(): SecretKey =
        keyStore().getKey(ALIAS, null) as? SecretKey ?: try {
            generate(strongBox = true)
        } catch (_: StrongBoxUnavailableException) {
            generate(strongBox = false)
        }

    private fun generate(strongBox: Boolean): SecretKey {
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
            .setInvalidatedByBiometricEnrollment(true)
            .setIsStrongBoxBacked(strongBox)
            .build()
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, STORE)
        gen.init(spec)
        val key = gen.generateKey()
        DebugLog.line("vault key strongBox=$strongBox sdk=${Build.VERSION.SDK_INT}")
        return key
    }
}
