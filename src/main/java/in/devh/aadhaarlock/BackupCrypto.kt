package `in`.devh.aadhaarlock

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import org.json.JSONObject
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.text.Normalizer
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Password-encrypted backup files. Argon2id turns the password into a key, because a phone's backup
 * can be attacked offline with GPUs. AES-256-GCM seals the data, and the KDF settings are its
 * associated data, so editing them in the file fails like any other tampering.
 */
object BackupCrypto {
    const val MIN_PASSWORD = 12
    const val MAX_PASSWORD = 256
    private const val FORMAT = "aadhaar-lock-backup"
    private const val VERSION = 1
    private const val MEMORY_KIB = 64 * 1024
    private const val ITERATIONS = 3
    private const val PARALLELISM = 1
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val KEY_BYTES = 32
    private const val TAG_BITS = 128

    // a hostile file must not be able to demand gigabytes of memory
    private const val MAX_MEMORY_KIB = 512 * 1024
    private const val MAX_ITERATIONS = 16

    class BadBackup(message: String) : Exception(message)

    /** Counts characters as people see them, so an emoji is one and not two. */
    fun validPassword(password: String): Boolean =
        password.codePointCount(0, password.length) in MIN_PASSWORD..MAX_PASSWORD

    fun seal(plain: ByteArray, password: String): ByteArray {
        require(validPassword(password)) { "Password must be $MIN_PASSWORD to $MAX_PASSWORD characters" }
        val random = SecureRandom()
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = cipher(Cipher.ENCRYPT_MODE, password, salt, iv, MEMORY_KIB, ITERATIONS, PARALLELISM)
        cipher.updateAAD(header(salt, MEMORY_KIB, ITERATIONS, PARALLELISM))
        return JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("kdf", "argon2id")
            .put("memoryKib", MEMORY_KIB)
            .put("iterations", ITERATIONS)
            .put("parallelism", PARALLELISM)
            .put("salt", encode(salt))
            .put("iv", encode(iv))
            .put("data", encode(cipher.doFinal(plain)))
            .toString()
            .toByteArray(Charsets.UTF_8)
    }

    /** @throws BadBackup when the password is wrong or the file was changed; the two look identical */
    fun open(file: ByteArray, password: String): ByteArray {
        val obj = try {
            JSONObject(String(file, Charsets.UTF_8))
        } catch (_: org.json.JSONException) {
            throw BadBackup("This isn't an Aadhaar Lock backup")
        }
        if (obj.optString("format") != FORMAT) throw BadBackup("This isn't an Aadhaar Lock backup")
        if (obj.optInt("version") != VERSION) throw BadBackup("This backup needs a newer version of the app")
        try {
            val memory = obj.getInt("memoryKib")
            val iterations = obj.getInt("iterations")
            val parallelism = obj.getInt("parallelism")
            if (obj.getString("kdf") != "argon2id" || memory !in 8..MAX_MEMORY_KIB ||
                iterations !in 1..MAX_ITERATIONS || parallelism !in 1..4
            ) {
                throw BadBackup("This backup is damaged")
            }
            val salt = decode(obj.getString("salt"))
            val cipher = cipher(Cipher.DECRYPT_MODE, password, salt, decode(obj.getString("iv")), memory, iterations, parallelism)
            cipher.updateAAD(header(salt, memory, iterations, parallelism))
            return cipher.doFinal(decode(obj.getString("data")))
        } catch (_: GeneralSecurityException) {
            throw BadBackup("Wrong password, or the backup is damaged")
        } catch (_: org.json.JSONException) {
            throw BadBackup("This backup is damaged")
        } catch (_: IllegalArgumentException) {
            throw BadBackup("This backup is damaged")
        }
    }

    private fun cipher(mode: Int, password: String, salt: ByteArray, iv: ByteArray, memory: Int, iterations: Int, parallelism: Int): Cipher {
        val key = deriveKey(password, salt, memory, iterations, parallelism)
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
            key.fill(0)
        }
    }

    private fun deriveKey(password: String, salt: ByteArray, memory: Int, iterations: Int, parallelism: Int): ByteArray {
        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withMemoryAsKB(memory)
            .withIterations(iterations)
            .withParallelism(parallelism)
            .withSalt(salt)
            .build()
        // the same password can arrive as different code points from different keyboards
        val secret = Normalizer.normalize(password, Normalizer.Form.NFC).toByteArray(Charsets.UTF_8)
        val key = ByteArray(KEY_BYTES)
        Argon2BytesGenerator().apply { init(params) }.generateBytes(secret, key)
        secret.fill(0)
        return key
    }

    private fun header(salt: ByteArray, memory: Int, iterations: Int, parallelism: Int): ByteArray =
        "$FORMAT|$VERSION|argon2id|$memory|$iterations|$parallelism|${encode(salt)}".toByteArray(Charsets.UTF_8)

    private fun encode(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)
    private fun decode(text: String) = Base64.getDecoder().decode(text)
}
