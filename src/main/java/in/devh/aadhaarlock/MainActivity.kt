package `in`.devh.aadhaarlock

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Bundle
import android.os.PersistableBundle
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.util.Base64
import android.view.View
import android.view.WindowInsets
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import `in`.devh.aadhaarlock.databinding.ActivityMainBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.text.DateFormat
import java.util.Date
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher

class MainActivity : Screen() {
    private enum class Step { START, CAPTCHA, OTP }
    private enum class Bio { UNKNOWN, LOCKED, UNLOCKED, TEMP }
    private enum class Uid { EDITING, LOCKED, UNLOCKED }

    private lateinit var b: HomeUi
    private lateinit var prefs: Prefs
    private lateinit var api: TathyaApi
    private lateinit var tokens: TokenVault
    private lateinit var vault: UidVault
    private lateinit var smsWatcher: SmsWatcher
    private var otpWatchJob: Job? = null
    private var otpAfterMs = 0L
    private val otpListener = { otp: String -> runOnUiThread { fillOtp(otp) } }

    private var step = Step.START
    private var bio = Bio.UNKNOWN
    private var bioUntil = ""
    private var signedIn = false
    private var busy = false
    private var uidState = Uid.EDITING
    private var uidBeforeEdit = Uid.EDITING
    private var uidPlain = ""
    private lateinit var cache: DetailsCache
    private var detailsKey: ByteArray? = null
    private var profile: Profile? = null
    private var profileRoot: JSONObject? = null
    private var profileAt = 0L
    private var profileLive = false
    private var lastBio = Bio.UNKNOWN
    private var lastBioUntil = ""
    private var revealed = false
    private val masks = HashMap<String, String>()
    private var idFlipped = false
    private var idFlipping = false

    private val smsPerms = arrayOf(
        Manifest.permission.RECEIVE_SMS,
        Manifest.permission.READ_SMS,
    )

    private val smsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        DebugLog.line("sms perms $result")
        smsWatcher.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DebugLog.init(this)
        prefs = Prefs(this)
        tokens = TokenVault(prefs)
        api = TathyaApi(tokens)
        vault = UidVault(this, prefs)
        cache = DetailsCache(this)
        smsWatcher = SmsWatcher(this)
        SmsInbox.rootEnabled = prefs.rootSms
        signedIn = tokens.hasSession

        val openSettings = { startActivity(Intent(this, SettingsActivity::class.java)) }
        b = if (colorOs) {
            ColorOsHome(this, coui, openSettings).also { coui.show(this, it.root) }
        } else {
            val binding = ActivityMainBinding.inflate(layoutInflater)
            setContentView(binding.root)
            fitInsets(binding.root)
            MaterialHome(this, binding, openSettings)
        }
        bindActions()

        if (vault.hasSaved) {
            uidState = Uid.LOCKED
        } else {
            b.uidField.setText(prefs.uid)
        }
        render()

        OtpBus.add(otpListener)
        askSms()
        smsWatcher.start()

        DebugLog.section("start")
        DebugLog.line("web OAuth client_id=myAadhaar")
        DebugLog.line("READ_SMS=${granted(Manifest.permission.READ_SMS)} RECEIVE_SMS=${granted(Manifest.permission.RECEIVE_SMS)}")
        DebugLog.line("telemetry=${api.telemetry}")
        DebugLog.line("vault saved=${vault.hasSaved} biometrics=${vault.canUseBiometrics()} session=${tokens.load() != null}")

        if (uidState == Uid.LOCKED && vault.canUseBiometrics()) {
            b.root.post { promptUnlock() }
        } else if (signedIn && uidState == Uid.EDITING && b.uidField.text.toString().isNotBlank()) {
            requireUid()?.let { id -> act("Status") { refreshStatus(id) } }
        }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onStop() {
        revealed = false
        masks.clear()
        render()
        super.onStop()
    }

    override fun onDestroy() {
        OtpBus.remove(otpListener)
        smsWatcher.stop()
        super.onDestroy()
    }

    private fun bindActions() {
        b.flowMain.setOnClickListener {
            val id = requireUid() ?: return@setOnClickListener
            when (step) {
                Step.START -> act("Init") { initSession() }
                Step.CAPTCHA -> act("Send OTP") { sendOtp(id) }
                Step.OTP -> act("Login") { login(id) }
            }
        }
        b.flowAlt.setOnClickListener {
            otpWatchJob?.cancel()
            act("Init") { initSession() }
        }
        b.onSolve { act("Solve captcha") { solveCaptcha() } }
        b.refreshBtn.setOnClickListener {
            val id = requireUid() ?: return@setOnClickListener
            act("Status") { refreshStatus(id) }
        }
        b.card.idCard.cameraDistance = 12_000 * resources.displayMetrics.density
        b.card.idCard.setOnClickListener { flipIdCard() }
        b.idView.setOnClickListener { toggleReveal() }
        b.uidView.setOnClickListener { toggleReveal() }
        b.idCopy.setOnClickListener { chooseCopy() }
        b.uidCopy.setOnClickListener { copy("Aadhaar number", uidPlain) }
        b.idForget.setOnClickListener { forgetDetails() }
        b.lockBtn.setOnClickListener {
            val id = requireUid() ?: return@setOnClickListener
            act("Lock") { doLock(id, TathyaApi.LOCK) }
        }
        b.unlockBtn.setOnClickListener {
            confirm("Unlock biometrics?") { id ->
                act("Unlock") { doLock(id, TathyaApi.UNLOCK) }
            }
        }
        b.tempBtn.setOnClickListener {
            confirm("Unlock temporarily?") { id ->
                act("Temp unlock") { doLock(id, TathyaApi.UNLOCK_TEMP) }
            }
        }
        b.signOutBtn.setOnClickListener { signOut("Signed out") }
        b.uidMain.setOnClickListener { uidMainAction() }
        b.uidAlt.setOnClickListener { uidAltAction() }
    }

    private fun confirm(title: String, go: (String) -> Unit) {
        val id = requireUid() ?: return
        b.confirm(title, "Unlock") { go(id) }
    }

    private fun act(label: String, block: suspend () -> Unit) {
        if (busy) return
        setBusy(true)
        lifecycleScope.launch {
            DebugLog.section(label)
            try {
                withContext(Dispatchers.IO) { block() }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                DebugLog.line("ERR ${t.javaClass.simpleName}: ${t.message}")
                DebugLog.line(t.stackTraceToString().take(1500))
                setStatus(t.message ?: "Something went wrong")
            } finally {
                setBusy(false)
            }
        }
    }

    private fun setBusy(on: Boolean) {
        busy = on
        b.busy(on)
        render()
    }

    private fun render() {
        renderHero()
        renderProfile()
        renderUid()
        renderFlow()
    }

    private fun renderProfile() {
        val p = profile?.takeIf { uidState != Uid.LOCKED }
        b.idGroup.isVisible = p != null
        if (p == null) return
        fun value(s: String, groups: IntRange, keepEnd: Int = 0) = when {
            s.isEmpty() -> "—"
            revealed -> s
            else -> hidden(s, groups, keepEnd)
        }
        b.card.idPhoto.setRenderEffect(
            if (revealed) null else RenderEffect.createBlurEffect(28f, 28f, Shader.TileMode.CLAMP),
        )
        b.card.idName.text = value(p.name, 2..3)
        b.card.idLocalName.text = value(p.localName, 2..3)
        b.card.idLocalName.isVisible = p.localName.isNotEmpty()
        b.card.idDob.text = value(p.dob, 2..2)
        b.card.idGender.text = value(p.gender, 1..1)
        b.card.idNumber.text = value(p.spacedUid, 2..3, keepEnd = 4)
        b.card.idAddress.text = value(p.address, 6..11)
        b.card.idLocalAddress.text = value(p.localAddress, 6..11)
        b.card.idLocalAddress.isVisible = p.localAddress.isNotEmpty()
        b.card.idMobile.text = value(p.mobile, 1..2, keepEnd = 4)
        b.card.idEmail.text = value(p.email, 1..2)
        b.revealed(revealed)
        b.idForget.isVisible = detailsKey != null
        b.idSaved.text = savedNote()
        b.idSaved.isVisible = !profileLive
        b.idNotices.text = p.notices
        b.idNotices.isVisible = p.notices.isNotEmpty()
    }

    // stable between renders, redrawn each time values are hidden again
    private fun hidden(value: String, groups: IntRange, keepEnd: Int): String =
        masks.getOrPut(value) { Profile.dots(groups, value.takeLast(keepEnd)) }

    private fun savedNote(): String {
        val at = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(profileAt))
        val state = when (lastBio) {
            Bio.LOCKED -> " · Locked"
            Bio.UNLOCKED -> " · Unlocked"
            Bio.TEMP -> " · Temporarily unlocked"
            Bio.UNKNOWN -> ""
        }
        return "Saved $at$state"
    }

    private fun showProfile(root: JSONObject, p: Profile, at: Long, live: Boolean) {
        val appearing = !b.idGroup.isVisible
        profile = p
        profileRoot = root
        profileAt = at
        profileLive = live
        b.card.idPhoto.setImageBitmap(p.photo)
        b.card.idPhoto.isVisible = p.photo != null
        render()
        if (appearing && b.idGroup.isVisible) {
            b.card.idCard.alpha = 0f
            b.card.idCard.translationY = 24 * resources.displayMetrics.density
            b.card.idCard.animate().alpha(1f).translationY(0f).setDuration(280)
                .setInterpolator(DecelerateInterpolator()).start()
        }
    }

    private fun clearProfile() {
        profile = null
        profileRoot = null
        profileLive = false
        revealed = false
        masks.clear()
        b.card.idCard.animate().cancel()
        b.card.idCard.rotationY = 0f
        idFlipping = false
        showIdFace(back = false)
        b.card.idPhoto.setImageDrawable(null)
    }

    private fun restoreDetails() {
        val key = detailsKey ?: return
        val saved = cache.read(key) ?: return
        val p = Profile.parse(saved.profile) ?: return
        lastBio = Bio.entries.firstOrNull { it.name == saved.bio } ?: Bio.UNKNOWN
        lastBioUntil = saved.bioUntil
        showProfile(saved.profile, p, saved.savedAt, live = false)
    }

    private fun persistDetails() {
        val key = detailsKey ?: return
        val root = profileRoot ?: return
        cache.write(key, DetailsCache.Saved(root, lastBio.name, lastBioUntil, profileAt))
    }

    private fun forgetDetails() {
        b.confirm("Remove saved details?", "Remove") {
            cache.clear()
            clearProfile()
            setStatus("Saved details removed")
            render()
        }
    }

    private fun toggleReveal() {
        revealed = !revealed
        masks.clear()
        render()
    }

    private fun chooseCopy() {
        val fields = profile?.copyable() ?: return
        b.choose("Copy", fields.map { it.first }) { i -> copy(fields[i].first, fields[i].second) }
    }

    private fun copy(label: String, value: String) {
        val clip = ClipData.newPlainText(label, value)
        clip.description.extras = PersistableBundle().apply {
            putBoolean("android.content.extra.IS_SENSITIVE", true)
        }
        getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
        setStatus("$label copied")
    }

    private fun showIdFace(back: Boolean) {
        idFlipped = back
        b.card.idFront.visibility = if (back) View.INVISIBLE else View.VISIBLE
        b.card.idBack.visibility = if (back) View.VISIBLE else View.INVISIBLE
    }

    private fun flipIdCard() {
        if (idFlipping) return
        idFlipping = true
        val card = b.card.idCard
        card.animate().rotationY(90f).setDuration(140).setInterpolator(AccelerateInterpolator())
            .withEndAction {
                showIdFace(!idFlipped)
                card.rotationY = -90f
                card.animate().rotationY(0f).setDuration(200).setInterpolator(DecelerateInterpolator())
                    .withEndAction { idFlipping = false }
                    .start()
            }
            .start()
    }

    private fun renderHero() {
        val (title, icon, tone) = when {
            !signedIn -> Triple("Not signed in", R.drawable.ic_shield, Tone.NEUTRAL)
            bio == Bio.LOCKED -> Triple("Biometrics locked", R.drawable.ic_lock, Tone.GOOD)
            bio == Bio.UNLOCKED -> Triple("Biometrics unlocked", R.drawable.ic_lock_open, Tone.BAD)
            bio == Bio.TEMP -> Triple("Unlocked until $bioUntil", R.drawable.ic_lock_open, Tone.WARN)
            else -> Triple("Status unknown", R.drawable.ic_shield, Tone.NEUTRAL)
        }
        b.hero(title, icon, tone)
        b.actions.isVisible = signedIn
        b.signedIn(signedIn)
        b.refreshBtn.isEnabled = !busy
        b.lockBtn.isEnabled = !busy && bio != Bio.LOCKED
        b.unlockBtn.isEnabled = !busy && bio != Bio.UNLOCKED
        b.tempBtn.isEnabled = !busy && bio != Bio.UNLOCKED
    }

    private fun renderUid() {
        val canSave = vault.canUseBiometrics()
        b.uidValue.isVisible = uidState != Uid.EDITING
        b.uidLayout.isVisible = uidState == Uid.EDITING
        b.uidIcon.setImageResource(if (uidState == Uid.LOCKED) R.drawable.ic_lock else R.drawable.ic_lock_open)
        b.uidView.isVisible = uidState == Uid.UNLOCKED
        b.uidCopy.isVisible = uidState == Uid.UNLOCKED
        b.revealed(revealed)
        b.uidHint.isVisible = uidState == Uid.EDITING && !canSave
        when (uidState) {
            Uid.LOCKED -> {
                b.uidValue.text = "•••• •••• ••••"
                b.uidMain.text = "Unlock"
                b.uidMain.isEnabled = canSave
                b.uidAlt.text = "Replace"
                b.uidAlt.isVisible = true
            }
            Uid.UNLOCKED -> {
                val spaced = uidPlain.chunked(4).joinToString(" ")
                b.uidValue.text = if (revealed) spaced else hidden(spaced, 2..3, keepEnd = 4)
                b.uidMain.text = "Lock"
                b.uidMain.isEnabled = true
                b.uidAlt.text = "Edit"
                b.uidAlt.isVisible = true
            }
            Uid.EDITING -> {
                b.uidHint.text = "Add a fingerprint or strong biometric to save this number"
                b.uidMain.text = "Save"
                b.uidMain.isEnabled = canSave
                b.uidAlt.text = "Cancel"
                b.uidAlt.isVisible = uidBeforeEdit != Uid.EDITING
            }
        }
    }

    private fun renderFlow() {
        b.signInCard.isVisible = !signedIn
        b.captchaGroup.isVisible = step == Step.CAPTCHA
        b.otpLayout.isVisible = step == Step.OTP
        b.flowAlt.isVisible = step != Step.START
        b.flowMain.isEnabled = !busy
        b.flowAlt.isEnabled = !busy
        b.showSolve(!busy && prefs.apiKey.isNotBlank())
        b.stepHint.isVisible = step == Step.START
        when (step) {
            Step.START -> {
                b.stepHint.text = "OTP goes to your Aadhaar mobile"
                b.flowMain.text = "Get captcha"
            }
            Step.CAPTCHA -> {
                b.flowMain.text = "Send OTP"
                b.flowAlt.text = "New captcha"
            }
            Step.OTP -> {
                b.flowMain.text = "Sign in"
                b.flowAlt.text = "Start over"
            }
        }
    }

    private fun uidMainAction() {
        when (uidState) {
            Uid.LOCKED -> promptUnlock()
            Uid.UNLOCKED -> {
                uidPlain = ""
                uidState = Uid.LOCKED
                detailsKey = null
                clearProfile()
                render()
            }
            Uid.EDITING -> {
                val id = validUid() ?: return
                saveUid(id)
            }
        }
    }

    private fun uidAltAction() {
        when (uidState) {
            Uid.LOCKED, Uid.UNLOCKED -> {
                uidBeforeEdit = uidState
                b.uidField.setText(uidPlain)
                b.showUidError(null)
                uidState = Uid.EDITING
            }
            Uid.EDITING -> {
                b.uidField.setText("")
                uidState = uidBeforeEdit
                uidBeforeEdit = Uid.EDITING
            }
        }
        render()
    }

    private fun promptUnlock() {
        val cipher = try {
            vault.decryptCipher()
        } catch (_: KeyPermanentlyInvalidatedException) {
            lostUid()
            return
        } catch (t: Exception) {
            DebugLog.line("vault open ${t.javaClass.simpleName}: ${t.message}")
            setStatus("Couldn't open the saved number. Replace it to continue.")
            return
        }
        authenticate("Unlock Aadhaar", cipher) { done ->
            val opened = try {
                vault.read(done)
            } catch (t: AEADBadTagException) {
                lostUid()
                return@authenticate
            }
            val id = opened.uid
            uidPlain = id
            uidState = Uid.UNLOCKED
            uidBeforeEdit = Uid.EDITING
            detailsKey = opened.detailsKey
            b.uidField.setText("")
            restoreDetails()
            render()
            if (signedIn) act("Status") { refreshStatus(id) }
        }
    }

    private fun saveUid(id: String) {
        val cipher = try {
            vault.encryptCipher()
        } catch (t: Exception) {
            DebugLog.line("vault seal ${t.javaClass.simpleName}: ${t.message}")
            setStatus("Couldn't save: ${t.message}")
            return
        }
        authenticate("Save Aadhaar", cipher) { done ->
            val key = DetailsCache.newKey()
            vault.store(done, id, key)
            detailsKey = key
            uidPlain = id
            uidState = Uid.UNLOCKED
            uidBeforeEdit = Uid.EDITING
            b.uidField.setText("")
            // the new key can't open the old file, and a different number means different details
            cache.clear()
            val p = profile
            if (p != null && (p.uid == id || id.length == 16)) persistDetails() else clearProfile()
            setStatus("Number saved")
            render()
        }
    }

    private fun lostUid() {
        DebugLog.line("vault key invalidated")
        vault.wipe()
        cache.clear()
        detailsKey = null
        clearProfile()
        uidPlain = ""
        uidState = Uid.EDITING
        uidBeforeEdit = Uid.EDITING
        setStatus("Your biometrics changed, so the saved number can't be read. Enter it again.")
        render()
    }

    private fun authenticate(title: String, cipher: Cipher, onDone: (Cipher) -> Unit) =
        BiometricGate.authenticate(this, title, cipher, { setStatus(it) }, onDone)

    private fun validUid(): String? {
        val id = b.uidField.text.toString().trim()
        if ((id.length == 12 || id.length == 16) && id.all { it.isDigit() }) return id
        b.showUidError("Enter a 12-digit Aadhaar or 16-digit VID")
        return null
    }

    private fun requireUid(): String? = when (uidState) {
        Uid.UNLOCKED -> uidPlain
        Uid.EDITING -> validUid()
        Uid.LOCKED -> {
            setStatus("Unlock your Aadhaar number first")
            if (vault.canUseBiometrics()) promptUnlock()
            null
        }
    }

    private fun granted(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun askSms() {
        val missing = smsPerms.filter { !granted(it) }
        if (missing.isNotEmpty()) smsLauncher.launch(missing.toTypedArray())
    }

    private fun fillOtp(otp: String) {
        if (b.otpField.text.toString().trim() == otp) return
        b.otpField.setText(otp)
        DebugLog.line("OTP filled $otp")
    }

    private suspend fun initSession() {
        val cap = api.startLogin()
        showCaptcha(cap.imageB64)
        withContext(Dispatchers.Main) {
            b.captchaField.setText("")
            b.otpField.setText("")
            step = Step.CAPTCHA
            render()
        }
        if (prefs.apiKey.isBlank()) {
            typeCaptcha()
            return
        }
        try {
            solveCaptcha()
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            DebugLog.line("auto solve skipped: ${t.message}")
            setStatus("Couldn't read the captcha")
            typeCaptcha()
        }
    }

    private suspend fun typeCaptcha() = withContext(Dispatchers.Main) {
        b.captchaField.requestFocus()
        b.captchaField.windowInsetsController?.show(WindowInsets.Type.ime())
    }

    private suspend fun solveCaptcha() {
        val img = api.captchaImageB64
        check(img.isNotBlank()) { "Get a captcha first" }
        val code = CaptchaSolver.solve(img, prefs.apiKey, prefs.apiBase, prefs.apiModel)
        withContext(Dispatchers.Main) { b.captchaField.setText(code) }
    }

    private suspend fun sendOtp(uid: String) {
        check(api.captchaTxnId.isNotBlank()) { "Get a captcha first" }
        val captcha = withContext(Dispatchers.Main) { b.captchaField.text.toString().trim() }
        check(captcha.isNotBlank()) { "Enter the captcha" }
        otpWatchJob?.cancel()
        withContext(Dispatchers.Main) { b.otpField.setText("") }
        otpAfterMs = System.currentTimeMillis()
        OtpBus.afterMs = otpAfterMs
        DebugLog.line("otp window date>$otpAfterMs")
        api.requestOtp(uid, captcha)
        withContext(Dispatchers.Main) {
            step = Step.OTP
            render()
        }
        setStatus("OTP sent")
        withContext(Dispatchers.Main) { b.otpField.requestFocus() }
        otpWatchJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                val otp = OtpWait.await(this@MainActivity, otpAfterMs) {
                    withContext(Dispatchers.Main) { b.otpField.text.toString() }
                }
                withContext(Dispatchers.Main) { fillOtp(otp) }
                setStatus("OTP received")
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                DebugLog.line("otp watch ${t.message}")
            }
        }
    }

    private suspend fun login(uid: String) {
        val otp = withContext(Dispatchers.Main) { b.otpField.text.toString().trim() }
        check(otp.length == 6 && otp.all { it.isDigit() }) { "Enter the 6-digit OTP" }
        api.verifyOtp(uid, otp)
        otpWatchJob?.cancel()
        withContext(Dispatchers.Main) {
            signedIn = true
            step = Step.START
            b.captchaField.setText("")
            b.otpField.setText("")
            render()
        }
        setStatus("")
        refreshStatus(uid)
    }

    private suspend fun doLock(uid: String, action: Int) {
        val obj = api.lockUnlock(uid, action)
        setStatus(obj.optJSONObject("responseData")?.optString("message") ?: obj.toString())
        refreshStatus(uid, reloadProfile = false)
    }

    private suspend fun refreshStatus(uid: String, reloadProfile: Boolean = true) {
        val obj = try {
            api.preferences(uid)
        } catch (io: IOException) {
            throw io
        } catch (j: JSONException) {
            throw j
        } catch (t: Exception) {
            DebugLog.line("session rejected ${t.message}")
            withContext(Dispatchers.Main) { signOut("Session expired, sign in again") }
            return
        }
        val data = obj.optJSONObject("responseData") ?: obj
        val temp = data.optJSONObject("unlockBiometrics")
        val exp = temp?.optLong("expirationTime") ?: 0
        val state = when {
            temp?.optBoolean("value") == true -> Bio.TEMP
            data.optBoolean("enableBiometricsLock") -> Bio.LOCKED
            else -> Bio.UNLOCKED
        }
        DebugLog.line("prefs $data")
        withContext(Dispatchers.Main) {
            bio = state
            bioUntil = DateFormat.getTimeInstance(DateFormat.SHORT)
                .format(Date(if (exp < 100_000_000_000L) exp * 1000 else exp))
            lastBio = state
            lastBioUntil = bioUntil
            persistDetails()
            render()
        }
        if (reloadProfile || profile == null) loadProfile(uid)
    }

    // a failed profile fetch must not cost the session, the lock controls or the saved copy
    private suspend fun loadProfile(uid: String) {
        val loaded = try {
            val root = api.profile(uid)
            Profile.parse(root)?.let { root to it }
        } catch (c: CancellationException) {
            throw c
        } catch (t: Exception) {
            DebugLog.line("profile ${t.javaClass.simpleName}: ${t.message}")
            null
        }
        withContext(Dispatchers.Main) {
            when {
                loaded == null -> setStatus(
                    if (profile != null) "Couldn't update, showing saved details" else "Couldn't load your Aadhaar profile",
                )
                // the number may have been locked while the request was in flight
                uidState != Uid.LOCKED && signedIn -> {
                    showProfile(loaded.first, loaded.second, System.currentTimeMillis(), live = true)
                    persistDetails()
                }
            }
        }
    }

    private fun signOut(message: String) {
        tokens.clear()
        signedIn = false
        bio = Bio.UNKNOWN
        step = Step.START
        profileLive = false
        setStatus(message)
        render()
    }

    private fun showCaptcha(b64: String) {
        val bytes = Base64.decode(b64, Base64.DEFAULT)
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        runOnUiThread { b.captchaView.setImageBitmap(bmp) }
    }

    private fun setStatus(s: String) {
        DebugLog.line("status $s")
        runOnUiThread {
            b.heroMessage.text = s
            b.heroMessage.isVisible = s.isNotBlank()
        }
    }
}
