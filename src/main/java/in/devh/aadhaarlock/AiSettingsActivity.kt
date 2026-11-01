package `in`.devh.aadhaarlock

import android.os.Bundle
import `in`.devh.aadhaarlock.databinding.ActivityAiBinding

class AiSettingsActivity : Screen() {
    private lateinit var b: ActivityAiBinding
    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityAiBinding.inflate(layoutInflater)
        setContentView(b.root)
        fitInsets(b.root)
        prefs = Prefs(this)
        b.toolbar.setNavigationOnClickListener { finish() }
        b.baseField.setText(prefs.apiBase)
        b.modelField.setText(prefs.apiModel)
        b.keyField.setText(prefs.apiKey)
        b.resetBtn.setOnClickListener {
            b.baseField.setText(CaptchaSolver.DEFAULT_BASE)
            b.modelField.setText(CaptchaSolver.DEFAULT_MODEL)
        }
    }

    override fun onPause() {
        super.onPause()
        prefs.apiBase = b.baseField.text.toString().trim().ifBlank { CaptchaSolver.DEFAULT_BASE }
        prefs.apiModel = b.modelField.text.toString().trim().ifBlank { CaptchaSolver.DEFAULT_MODEL }
        prefs.apiKey = b.keyField.text.toString().trim()
    }
}
