package `in`.devh.aadhaarlock

import android.app.Activity
import android.content.DialogInterface
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import javax.crypto.Cipher

/**
 * Any Class 3 biometric, which includes face on phones whose face unlock is rated that strong.
 * Weaker face unlock can't open a hardware key, and Android hides Class 1 from apps entirely.
 */
object BiometricGate {
    fun authenticate(activity: Activity, title: String, cipher: Cipher, onError: (String) -> Unit, onDone: (Cipher) -> Unit) {
        val prompt = BiometricPrompt.Builder(activity)
            .setTitle(title)
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setNegativeButton("Cancel", activity.mainExecutor, DialogInterface.OnClickListener { _, _ -> })
            .build()
        prompt.authenticate(
            BiometricPrompt.CryptoObject(cipher),
            CancellationSignal(),
            activity.mainExecutor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                    DebugLog.line("biometric error $errorCode $errString")
                    if (errorCode != BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED &&
                        errorCode != BiometricPrompt.BIOMETRIC_ERROR_CANCELED
                    ) {
                        onError(errString?.toString() ?: "Biometric error")
                    }
                }

                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val done = result.cryptoObject.cipher ?: return
                    try {
                        onDone(done)
                    } catch (t: Exception) {
                        DebugLog.line("vault ${t.javaClass.simpleName}: ${t.message}")
                        onError("Biometric step failed: ${t.message}")
                    }
                }
            },
        )
    }
}
