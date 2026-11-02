package `in`.devh.aadhaarlock

import android.content.DialogInterface
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import `in`.devh.aadhaarlock.databinding.ActivitySettingsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

class SettingsActivity : Screen() {
    private companion object {
        const val SOURCE = "https://github.com/S4tyendra/aadhaar-lock"
        const val PASSWORD_INPUT = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
    }

    private lateinit var prefs: Prefs
    private lateinit var aiSummary: TextView
    private lateinit var logsSummary: TextView
    private lateinit var vault: UidVault
    private lateinit var cache: DetailsCache
    private var pendingBackup: ByteArray? = null

    private val backupPicker = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val bytes = pendingBackup
        pendingBackup = null
        if (uri != null && bytes != null) writeBackup(uri, bytes)
    }

    private val restorePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) askPassword(confirm = false) { password -> openBackup(uri, password) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        vault = UidVault(this, prefs)
        cache = DetailsCache(this)
        DebugLog.init(this)
        if (colorOs) buildColorOs() else buildMaterial()
    }

    private fun buildMaterial() {
        val b = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(b.root)
        fitInsets(b.root)
        aiSummary = b.aiSummary
        logsSummary = b.logsSummary
        b.uiSwitch.isChecked = false
        b.uiSwitch.setOnCheckedChangeListener { _, on -> setInterface(on) }
        b.uiRow.setOnClickListener { b.uiSwitch.toggle() }
        b.rootSwitch.isChecked = prefs.rootSms
        b.rootSwitch.setOnCheckedChangeListener { _, on -> setRootSms(on) }
        b.rootRow.setOnClickListener { b.rootSwitch.toggle() }
        b.logSwitch.isChecked = prefs.logging
        b.logSwitch.setOnCheckedChangeListener { _, on -> setLogging(on) }
        b.logRow.setOnClickListener { b.logSwitch.toggle() }
        b.toolbar.setNavigationOnClickListener { finish() }
        b.aiRow.setOnClickListener { openAi() }
        b.logsRow.setOnClickListener { openLogs() }
        b.backupRow.setOnClickListener { startBackup() }
        b.restoreRow.setOnClickListener { restorePicker.launch(arrayOf("*/*")) }
        b.aboutVersion.text = "Version ${versionName()}"
        b.sourceBtn.setOnClickListener { openSource() }
    }

    private fun buildColorOs() {
        val aiRow = coui.row("Captcha AI", "") { openAi() }
        val logsRow = coui.row("Logs", "") { openLogs() }
        aiSummary = aiRow.findViewById(android.R.id.summary)
        logsSummary = logsRow.findViewById(android.R.id.summary)
        val page = coui.column(
            coui.group(coui.row("ColorOS interface", "Off uses Material", coui.switch(true) { on -> setInterface(on) })),
            coui.group(
                aiRow,
                coui.row("Read SMS with root", widget = coui.switch(prefs.rootSms) { on -> setRootSms(on) }),
                coui.row("Debug logging", widget = coui.switch(prefs.logging) { on -> setLogging(on) }),
                logsRow,
            ),
            coui.group(
                coui.row("Back up", "Save an encrypted file, protected by a password") { startBackup() },
                coui.row("Restore", "Bring back a backup file") { restorePicker.launch(arrayOf("*/*")) },
            ),
            coui.group(
                coui.row(
                    "About Aadhaar Lock",
                    "Version ${versionName()}\nLocks and unlocks Aadhaar biometrics from your phone. " +
                        "Not affiliated with UIDAI.\nMade by S4tyendra",
                ),
                coui.row("Source code") { openSource() },
            ),
        )
        coui.show(this, coui.screen("Settings", page, onBack = { finish() }))
    }

    /** Every open screen was built for the old interface, so start over from home. */
    private fun setInterface(colorOs: Boolean) {
        prefs.colorOsUi = colorOs
        restart()
    }

    private fun restart() = startActivity(
        Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
    )

    private fun startBackup() {
        askPassword(confirm = true) { password ->
            if (!vault.hasSaved) {
                sealBackup(backupOf(null, null), password)
                return@askPassword
            }
            val cipher = try {
                vault.decryptCipher()
            } catch (t: Exception) {
                DebugLog.line("backup vault ${t.javaClass.simpleName}: ${t.message}")
                say("Couldn't open the saved number")
                return@askPassword
            }
            BiometricGate.authenticate(this, "Back up Aadhaar", cipher, ::say) { done ->
                val opened = vault.read(done)
                sealBackup(backupOf(opened.uid, cache.read(opened.detailsKey)), password)
            }
        }
    }

    private fun backupOf(uid: String?, details: DetailsCache.Saved?) =
        Backup(uid, details, prefs.apiBase, prefs.apiModel, prefs.apiKey, prefs.rootSms, prefs.colorOsUi)

    private fun sealBackup(backup: Backup, password: String) {
        say("Encrypting...")
        lifecycleScope.launch {
            pendingBackup = withContext(Dispatchers.Default) { BackupCrypto.seal(backup.toBytes(), password) }
            backupPicker.launch("aadhaar-lock-${LocalDate.now()}.albackup")
        }
    }

    private fun writeBackup(uri: Uri, bytes: ByteArray) {
        lifecycleScope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching { contentResolver.openOutputStream(uri, "wt")!!.use { it.write(bytes) } }
            }
            say(if (saved.isSuccess) "Backup saved" else "Couldn't save the backup")
        }
    }

    private fun openBackup(uri: Uri, password: String) {
        say("Decrypting...")
        lifecycleScope.launch {
            val backup = withContext(Dispatchers.Default) {
                runCatching {
                    val file = contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                    Backup.parse(BackupCrypto.open(file, password))
                }
            }
            backup.onSuccess { confirmRestore(it) }.onFailure {
                DebugLog.line("restore ${it.javaClass.simpleName}")
                say((it as? BackupCrypto.BadBackup)?.message ?: "Couldn't read the backup")
            }
        }
    }

    private fun confirmRestore(backup: Backup) {
        if (backup.uid == null || !vault.hasSaved) {
            restore(backup)
            return
        }
        val title = "Replace saved card?"
        val message = "The Aadhaar number and card saved on this phone will be replaced by the backup."
        if (colorOs) {
            coui.dialog(title, message, "Replace", "Cancel", { restore(backup) })
        } else {
            MaterialAlertDialogBuilder(this)
                .setTitle(title)
                .setMessage(message)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Replace") { _, _ -> restore(backup) }
                .show()
        }
    }

    private fun restore(backup: Backup) {
        val uid = backup.uid
        if (uid == null) {
            applySettings(backup)
            say("Settings restored")
            restart()
            return
        }
        if (!vault.canUseBiometrics()) {
            say("Add a fingerprint or strong biometric first, then restore again")
            return
        }
        val cipher = try {
            vault.encryptCipher()
        } catch (t: Exception) {
            DebugLog.line("restore vault ${t.javaClass.simpleName}: ${t.message}")
            say("Couldn't restore: ${t.message}")
            return
        }
        BiometricGate.authenticate(this, "Restore Aadhaar", cipher, ::say) { done ->
            val key = DetailsCache.newKey()
            vault.store(done, uid, key)
            cache.clear()
            backup.details?.let { cache.write(key, it) }
            applySettings(backup)
            say("Backup restored")
            restart()
        }
    }

    private fun applySettings(backup: Backup) {
        prefs.apiBase = backup.apiBase
        prefs.apiModel = backup.apiModel
        prefs.apiKey = backup.apiKey
        prefs.rootSms = backup.rootSms
        prefs.colorOsUi = backup.colorOsUi
        SmsInbox.rootEnabled = backup.rootSms
    }

    private fun say(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    /** The password dialog keeps its button off until the password is usable. */
    private fun askPassword(confirm: Boolean, onPassword: (String) -> Unit) {
        val title = "Backup password"
        val rule = "${BackupCrypto.MIN_PASSWORD} to ${BackupCrypto.MAX_PASSWORD} characters"
        if (colorOs) {
            lateinit var first: EditText
            var second: EditText? = null
            var button: View? = null
            val check = { button?.isEnabled = usable(first, second) }
            val firstInput = coui.input("Password", rule, password = true) { check() }
            first = coui.inputText(firstInput).apply { inputType = PASSWORD_INPUT }
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                val pad = (24 * resources.displayMetrics.density).toInt()
                setPadding(pad, 0, pad, 0)
                addView(firstInput)
            }
            if (confirm) {
                val secondInput = coui.input("Confirm password", password = true) { check() }
                second = coui.inputText(secondInput).apply { inputType = PASSWORD_INPUT }
                box.addView(secondInput)
            }
            val dialog = coui.dialog(title, null, "OK", "Cancel", { onPassword(first.text.toString()) }, content = box)
            button = coui.call(dialog, "getButton", DialogInterface.BUTTON_POSITIVE) as View?
            check()
            return
        }
        val pad = (24 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        fun field(hint: String): EditText {
            val edit = TextInputEditText(this).apply {
                inputType = PASSWORD_INPUT
            }
            box.addView(
                TextInputLayout(this).apply {
                    this.hint = hint
                    endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
                    addView(edit)
                },
            )
            return edit
        }
        val first = field("Password, $rule")
        val second = if (confirm) field("Confirm password") else null
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setView(box)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("OK") { _, _ -> onPassword(first.text.toString()) }
            .show()
        val button = dialog.getButton(DialogInterface.BUTTON_POSITIVE)
        val check = { button.isEnabled = usable(first, second) }
        first.doAfterTextChanged { check() }
        second?.doAfterTextChanged { check() }
        check()
    }

    private fun usable(first: EditText, second: EditText?): Boolean {
        val password = first.text.toString()
        return BackupCrypto.validPassword(password) && (second == null || second.text.toString() == password)
    }

    private fun setRootSms(on: Boolean) {
        prefs.rootSms = on
        SmsInbox.rootEnabled = on
        SmsInbox.grantOtpRead(this)
    }

    private fun setLogging(on: Boolean) {
        prefs.logging = on
        DebugLog.update(on)
        showSummaries()
    }

    private fun openAi() = startActivity(Intent(this, AiSettingsActivity::class.java))
    private fun openLogs() = startActivity(Intent(this, LogsActivity::class.java))
    private fun openSource() = startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SOURCE)))
    private fun versionName() = packageManager.getPackageInfo(packageName, 0).versionName

    override fun onResume() {
        super.onResume()
        showSummaries()
    }

    private fun showSummaries() {
        aiSummary.text = if (prefs.apiKey.isBlank()) "Off" else prefs.apiModel
        logsSummary.text = if (DebugLog.enabled) "View, copy or clear" else "Logging is off"
    }
}
