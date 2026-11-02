package `in`.devh.aadhaarlock

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputFilter
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import `in`.devh.aadhaarlock.databinding.ViewIdCardBinding
import `in`.devh.coui.Coui

/** The home screen's widgets. The Aadhaar card keeps its own design, everything else is COUI. */
class ColorOsHome(activity: Activity, private val coui: Coui, onSettings: () -> Unit) : HomeUi {
    private val density = activity.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    override val card: ViewIdCardBinding = ViewIdCardBinding.inflate(LayoutInflater.from(activity))

    /** COUI's label colors are dark before force dark, and force dark skips image tints. */
    private val neutral: Int = if (coui.res.configuration.isNightModeActive) 0xB3FFFFFF.toInt() else 0x8C000000.toInt()

    private val heroIcon = ImageView(coui.ctx)
    private val spinner = coui.loading().apply { isVisible = false }
    private val heroRow = coui.row("Status", "", FrameLayout(coui.ctx).apply {
        addView(heroIcon, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
        addView(spinner, FrameLayout.LayoutParams(dp(32), dp(32), Gravity.CENTER))
    }).apply { isClickable = false }
    private val heroTitle: TextView = heroRow.findViewById(android.R.id.title)
    override val heroMessage: TextView = heroRow.findViewById<TextView>(android.R.id.summary).apply { isVisible = false }

    override val lockBtn = coui.button("Lock") {}
    override val unlockBtn = coui.button("Unlock", primary = false) {}
    override val tempBtn = coui.button("Temp", primary = false) {}
    override val actions = rowOf(lockBtn, unlockBtn, tempBtn)

    override val idView = coui.button("View", primary = false) {}
    override val idCopy = coui.button("Copy", primary = false) {}
    override val idForget = coui.button("Remove", primary = false) {}
    override val idSaved = note()
    override val idNotices = note()
    override val idGroup = stack(card.idCard, gap(12), rowOf(idView, idCopy, idForget), idSaved, idNotices)

    override val uidView = coui.button("View", primary = false) {}
    override val uidCopy = coui.button("Copy", primary = false) {}
    override val uidIcon = ImageView(coui.ctx).apply { imageTintList = ColorStateList.valueOf(neutral) }
    override val uidValue = coui.text("").apply {
        typeface = Typeface.MONOSPACE
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
    }
    private val uidError = note().apply { setTextColor(coui.color("couiColorError")) }
    override val uidLayout = coui.input("Aadhaar / VID", password = true, onChange = { showUidError(null) })
    override val uidField: EditText = coui.inputText(uidLayout).apply {
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        filters = arrayOf(InputFilter.LengthFilter(16))
    }
    override val uidHint = note()
    override val uidAlt = coui.button("Edit", primary = false) {}
    override val uidMain = coui.button("Unlock") {}
    private val numberCard = stack(
        LinearLayout(coui.ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(heading("Aadhaar number"), LinearLayout.LayoutParams(0, -2, 1f))
            addView(uidIcon, LinearLayout.LayoutParams(dp(20), dp(20)))
        },
        gap(8), uidValue, uidLayout, uidError, uidHint, gap(12),
        rowOf(uidView, uidCopy), gap(8), rowOf(uidAlt, uidMain),
    )

    override val stepHint = note()
    override val captchaView = ImageView(coui.ctx).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        contentDescription = "Captcha"
        isForceDarkAllowed = false
    }
    private val captchaLayout = coui.input("Captcha")
    override val captchaField: EditText = coui.inputText(captchaLayout)
    private val solveBtn = coui.button("Solve with AI", primary = false) {}
    override val captchaGroup = stack(
        captchaView.also { it.layoutParams = LinearLayout.LayoutParams(-1, dp(72)) },
        captchaLayout, gap(8), solveBtn,
    )
    override val otpLayout = coui.input("OTP", maxCount = 6)
    override val otpField: EditText = coui.inputText(otpLayout).apply {
        inputType = InputType.TYPE_CLASS_NUMBER
        filters = arrayOf(InputFilter.LengthFilter(6))
    }
    override val flowAlt = coui.button("New captcha", primary = false) {}
    override val flowMain = coui.button("Get captcha") {}
    override val signInCard = stack(heading("Sign in"), stepHint, gap(8), captchaGroup, otpLayout, gap(12), rowOf(flowAlt, flowMain))

    override val refreshBtn: View = coui.row("Refresh status") {}
    override val signOutBtn: View = coui.row("Sign out") {}
    private val accountGroup = coui.group(refreshBtn, signOutBtn)

    private val content = coui.column(coui.group(heroRow), actions, idGroup, numberCard, signInCard, accountGroup)

    override val root: ViewGroup = coui.screen(
        "Aadhaar Lock",
        content,
        actions = listOf(Coui.Action("Settings", activity.getDrawable(R.drawable.ic_settings)!!, onSettings)),
        largeTitle = true,
    )

    init {
        // A page shorter than the screen can't hold the collapsed title, which then snaps back.
        content.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            val column = (v as ViewGroup).getChildAt(0)
            val height = v.height + dp(SCROLL_SLACK_DP)
            if (column.minimumHeight != height) v.post { column.minimumHeight = height }
        }
        card.idCard.isForceDarkAllowed = false
        card.idPhoto.clipToOutline = true
        card.idPhoto.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) =
                outline.setRoundRect(0, 0, view.width, view.height, 12 * density)
        }
    }

    override fun hero(title: String, icon: Int, tone: Tone) {
        heroTitle.text = title
        heroIcon.setImageResource(icon)
        heroIcon.imageTintList = ColorStateList.valueOf(
            when (tone) {
                Tone.NEUTRAL -> neutral
                Tone.GOOD -> coui.color("couiColorPrimary")
                Tone.BAD -> coui.color("couiColorError")
                Tone.WARN -> AMBER
            },
        )
    }

    override fun busy(on: Boolean) {
        spinner.isVisible = on
        heroIcon.isVisible = !on
    }

    override fun signedIn(on: Boolean) {
        accountGroup.isVisible = on
    }

    override fun revealed(on: Boolean) {
        idView.text = if (on) "Hide" else "View"
        uidView.text = if (on) "Hide" else "View"
    }

    override fun showUidError(message: String?) {
        uidError.text = message
        uidError.isVisible = message != null
    }

    override fun onSolve(action: () -> Unit) = solveBtn.setOnClickListener { action() }

    override fun showSolve(on: Boolean) {
        solveBtn.isVisible = on
    }

    override fun confirm(title: String, positive: String, go: () -> Unit) {
        coui.dialog(title, positive = positive, negative = "Cancel", onPositive = go)
    }

    override fun choose(title: String, items: List<String>, pick: (Int) -> Unit) {
        coui.bottomSheet { close ->
            coui.column(
                coui.group(
                    *items.mapIndexed { i, item ->
                        coui.row(item) {
                            pick(i)
                            close()
                        }
                    }.toTypedArray(),
                ),
            )
        }
    }

    private fun gap(height: Int) = View(coui.ctx).apply { layoutParams = LinearLayout.LayoutParams(-1, dp(height)) }

    private fun note() = coui.text("", secondary = true).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        isVisible = false
    }

    private fun heading(value: String) = coui.text(value).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setTypeface(typeface, Typeface.BOLD)
    }

    private fun stack(vararg views: View) = LinearLayout(coui.ctx).apply {
        orientation = LinearLayout.VERTICAL
        clipChildren = false
        clipToPadding = false
        views.forEach { addView(it, it.layoutParams ?: LinearLayout.LayoutParams(-1, -2)) }
    }

    private fun rowOf(vararg buttons: Button) = LinearLayout(coui.ctx).apply {
        clipChildren = false
        clipToPadding = false
        // a divider, unlike a margin, disappears along with a hidden button
        dividerDrawable = GradientDrawable().apply { setSize(dp(8), 0) }
        showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
        buttons.forEach { addView(it, LinearLayout.LayoutParams(0, -2, 1f)) }
    }

    private companion object {
        const val AMBER = 0xFFE8A33D.toInt()
        const val SCROLL_SLACK_DP = 24
    }
}
