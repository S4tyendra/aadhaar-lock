package `in`.devh.aadhaarlock

import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import `in`.devh.aadhaarlock.databinding.ViewIdCardBinding

enum class Tone { NEUTRAL, GOOD, BAD, WARN }

/** What MainActivity needs from the home screen, whichever interface is drawing it. */
interface HomeUi {
    val root: View
    val card: ViewIdCardBinding
    val heroMessage: TextView
    val actions: View
    val lockBtn: Button
    val unlockBtn: Button
    val tempBtn: Button
    val refreshBtn: View
    val signOutBtn: View
    val idGroup: View
    val idView: Button
    val idCopy: View
    val idForget: View
    val idSaved: TextView
    val idNotices: TextView
    val uidIcon: ImageView
    val uidValue: TextView
    val uidView: View
    val uidCopy: View
    val uidLayout: View
    val uidField: EditText
    val uidHint: TextView
    val uidAlt: Button
    val uidMain: Button
    val signInCard: View
    val stepHint: TextView
    val captchaGroup: View
    val captchaView: ImageView
    val captchaField: EditText
    val otpLayout: View
    val otpField: EditText
    val flowAlt: Button
    val flowMain: Button

    fun hero(title: String, icon: Int, tone: Tone)
    fun busy(on: Boolean)
    fun signedIn(on: Boolean)
    fun revealed(on: Boolean)
    fun showUidError(message: String?)
    fun onSolve(action: () -> Unit)
    fun showSolve(on: Boolean)
    fun confirm(title: String, positive: String, go: () -> Unit)
    fun choose(title: String, items: List<String>, pick: (Int) -> Unit)
}
