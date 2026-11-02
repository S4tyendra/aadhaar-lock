package `in`.devh.aadhaarlock

import android.app.Activity
import android.view.View
import androidx.core.view.isVisible
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import `in`.devh.aadhaarlock.databinding.ActivityMainBinding

class MaterialHome(private val activity: Activity, private val b: ActivityMainBinding, onSettings: () -> Unit) : HomeUi {
    override val root = b.root
    override val card = b.card
    override val heroMessage = b.heroMessage
    override val actions = b.actions
    override val lockBtn = b.lockBtn
    override val unlockBtn = b.unlockBtn
    override val tempBtn = b.tempBtn
    override val refreshBtn = b.refreshBtn
    override val signOutBtn = b.signOutBtn
    override val idGroup = b.idGroup
    override val idView = b.idView
    override val idCopy = b.idCopy
    override val idForget = b.idForget
    override val idSaved = b.idSaved
    override val idNotices = b.idNotices
    override val uidIcon = b.uidIcon
    override val uidValue = b.uidValue
    override val uidView = b.uidView
    override val uidCopy = b.uidCopy
    override val uidLayout = b.uidLayout
    override val uidField = b.uidField
    override val uidHint = b.uidHint
    override val uidAlt = b.uidAlt
    override val uidMain = b.uidMain
    override val signInCard = b.signInCard
    override val stepHint = b.stepHint
    override val captchaGroup = b.captchaGroup
    override val captchaView = b.captchaView
    override val captchaField = b.captchaField
    override val otpLayout = b.otpLayout
    override val otpField = b.otpField
    override val flowAlt = b.flowAlt
    override val flowMain = b.flowMain

    init {
        b.toolbar.setOnMenuItemClickListener {
            onSettings()
            true
        }
        b.uidField.setOnFocusChangeListener { _, _ -> b.uidLayout.error = null }
    }

    override fun hero(title: String, icon: Int, tone: Tone) {
        val tint = when (tone) {
            Tone.NEUTRAL -> com.google.android.material.R.attr.colorSurfaceContainerHigh
            Tone.GOOD -> com.google.android.material.R.attr.colorPrimaryContainer
            Tone.BAD -> com.google.android.material.R.attr.colorErrorContainer
            Tone.WARN -> com.google.android.material.R.attr.colorTertiaryContainer
        }
        b.heroTitle.text = title
        b.heroIcon.setImageResource(icon)
        b.heroCard.setCardBackgroundColor(MaterialColors.getColor(b.heroCard, tint))
    }

    override fun busy(on: Boolean) {
        b.progress.visibility = if (on) View.VISIBLE else View.INVISIBLE
    }

    override fun signedIn(on: Boolean) {
        b.signOutBtn.isVisible = on
        b.refreshBtn.isVisible = on
    }

    override fun revealed(on: Boolean) {
        val icon = if (on) R.drawable.ic_visibility_off else R.drawable.ic_visibility
        b.idView.text = if (on) "Hide" else "View"
        b.idView.setIconResource(icon)
        b.uidView.setIconResource(icon)
        b.uidView.contentDescription = if (on) "Hide number" else "View number"
    }

    override fun showUidError(message: String?) {
        b.uidLayout.error = message
    }

    override fun onSolve(action: () -> Unit) = b.captchaLayout.setEndIconOnClickListener { action() }

    override fun showSolve(on: Boolean) {
        b.captchaLayout.isEndIconVisible = on
    }

    override fun confirm(title: String, positive: String, go: () -> Unit) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(title)
            .setNegativeButton("Cancel", null)
            .setPositiveButton(positive) { _, _ -> go() }
            .show()
    }

    override fun choose(title: String, items: List<String>, pick: (Int) -> Unit) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(title)
            .setItems(items.toTypedArray()) { _, i -> pick(i) }
            .show()
    }
}
