package `in`.devh.aadhaarlock

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.TextView
import `in`.devh.aadhaarlock.databinding.ActivitySettingsBinding

class SettingsActivity : Screen() {
    private companion object {
        const val SOURCE = "https://github.com/S4tyendra/aadhaar-lock"
    }

    private lateinit var prefs: Prefs
    private lateinit var aiSummary: TextView
    private lateinit var logsSummary: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
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
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
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
