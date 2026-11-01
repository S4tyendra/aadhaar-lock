package `in`.devh.aadhaarlock

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import `in`.devh.aadhaarlock.databinding.ActivitySettingsBinding

class SettingsActivity : Screen() {
    private companion object {
        const val SOURCE = "https://github.com/S4tyendra/aadhaar-lock"
    }

    private lateinit var b: ActivitySettingsBinding
    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(b.root)
        fitInsets(b.root)
        prefs = Prefs(this)
        DebugLog.init(this)
        b.rootSwitch.isChecked = prefs.rootSms
        b.rootSwitch.setOnCheckedChangeListener { _, on ->
            prefs.rootSms = on
            SmsInbox.rootEnabled = on
            SmsInbox.grantOtpRead(this)
        }
        b.rootRow.setOnClickListener { b.rootSwitch.toggle() }
        b.logSwitch.isChecked = prefs.logging
        b.logSwitch.setOnCheckedChangeListener { _, on ->
            prefs.logging = on
            DebugLog.update(on)
            showSummaries()
        }
        b.logRow.setOnClickListener { b.logSwitch.toggle() }
        b.toolbar.setNavigationOnClickListener { finish() }
        b.aiRow.setOnClickListener { startActivity(Intent(this, AiSettingsActivity::class.java)) }
        b.logsRow.setOnClickListener { startActivity(Intent(this, LogsActivity::class.java)) }
        b.aboutVersion.text = "Version ${packageManager.getPackageInfo(packageName, 0).versionName}"
        b.sourceBtn.setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SOURCE))) }
    }

    override fun onResume() {
        super.onResume()
        showSummaries()
    }

    private fun showSummaries() {
        b.aiSummary.text = if (prefs.apiKey.isBlank()) "Off" else prefs.apiModel
        b.logsSummary.text = if (DebugLog.enabled) "View, copy or clear" else "Logging is off"
    }
}
