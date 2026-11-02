package `in`.devh.aadhaarlock

import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import `in`.devh.aadhaarlock.databinding.ActivityAiBinding

class AiSettingsActivity : Screen() {
    private lateinit var prefs: Prefs
    private lateinit var baseField: EditText
    private lateinit var modelField: EditText
    private lateinit var keyField: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        if (colorOs) buildColorOs() else buildMaterial()
        baseField.setText(prefs.apiBase)
        modelField.setText(prefs.apiModel)
        keyField.setText(prefs.apiKey)
    }

    private fun buildMaterial() {
        val b = ActivityAiBinding.inflate(layoutInflater)
        setContentView(b.root)
        fitInsets(b.root)
        b.toolbar.setNavigationOnClickListener { finish() }
        baseField = b.baseField
        modelField = b.modelField
        keyField = b.keyField
        b.resetBtn.setOnClickListener { reset() }
    }

    private fun buildColorOs() {
        val base = coui.input("Base URL")
        val model = coui.input("Model")
        val key = coui.input("API key", password = true)
        baseField = coui.inputText(base).apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI }
        modelField = coui.inputText(model)
        keyField = coui.inputText(key).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val resetBtn = coui.button("Reset", primary = false) { reset() }
        coui.show(this, coui.screen("Captcha AI", coui.column(base, model, key, resetBtn), onBack = { finish() }))
    }

    private fun reset() {
        baseField.setText(CaptchaSolver.DEFAULT_BASE)
        modelField.setText(CaptchaSolver.DEFAULT_MODEL)
    }

    override fun onPause() {
        super.onPause()
        prefs.apiBase = baseField.text.toString().trim().ifBlank { CaptchaSolver.DEFAULT_BASE }
        prefs.apiModel = modelField.text.toString().trim().ifBlank { CaptchaSolver.DEFAULT_MODEL }
        prefs.apiKey = keyField.text.toString().trim()
    }
}
