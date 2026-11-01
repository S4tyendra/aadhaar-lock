package `in`.devh.aadhaarlock

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

class TathyaApi(private val tokens: TokenVault) {
    companion object {
        const val TATHYA = "https://tathya.uidai.gov.in"
        const val MYAADHAAR = "https://myaadhaar.uidai.gov.in"
        const val CLIENT_ID = "myAadhaar"
        const val UA =
            "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
        private val JSON = "application/json; charset=utf-8".toMediaType()

        const val LOCK = 2
        const val UNLOCK = 3
        const val UNLOCK_TEMP = 4
        private const val SESSION_ACTIVE = "UOS-AUT-001"
    }

    private val cookies = SessionJar()
    val telemetry = UUID.randomUUID().toString()
    private var reqSeq = 0
    var codeVerifier: String = ""
        private set
    private var authorizeUrl: String = ""
    var captchaTxnId: String = ""
        private set
    var captchaImageB64: String = ""
        private set
    var otpTxnId: String = ""
        private set

    private val http = OkHttpClient.Builder()
        .cookieJar(cookies)
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val n = reqSeq++
            val req = chain.request()
            DebugLog.line("→ ${req.method} ${req.url} [#${n}] cookie=${cookieHint(req)}")
            val rsp = chain.proceed(req)
            val loc = rsp.header("Location")
            DebugLog.line("← ${rsp.code} ${req.url.encodedPath} loc=${loc ?: "-"}")
            rsp.headers("Set-Cookie").forEach { DebugLog.line("  set-cookie $it") }
            rsp
        }
        .build()

    fun startLogin(): Captcha {
        cookies.clear()
        reqSeq = 1
        val pkce = Pkce.generate()
        codeVerifier = pkce.verifier
        val authorize = HttpUrl.Builder()
            .scheme("https")
            .host("tathya.uidai.gov.in")
            .addPathSegments("access/oauth/authorize")
            .addQueryParameter("grant_type", "authorization_code")
            .addQueryParameter("response_type", "code")
            .addQueryParameter("client_id", CLIENT_ID)
            .addQueryParameter("scope", "READ")
            .addQueryParameter("code_challenge", pkce.challenge)
            .addQueryParameter("code_challenge_method", "S256")
            .addQueryParameter("telemetry_session_id", telemetry)
            .build()
        authorizeUrl = authorize.toString()
        val authRsp = call(
            Request.Builder()
                .url(authorize)
                .header("User-Agent", UA)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Referer", "$MYAADHAAR/")
                .header("Upgrade-Insecure-Requests", "1")
                .get()
                .build(),
        )
        authRsp.close()
        if (authRsp.code !in 300..399) {
            error("authorize expected 302, got ${authRsp.code}")
        }
        DebugLog.line("SESSION=${cookies.sessionHint()} (set on authorize 302, then Location login)")

        val captchaRsp = call(
            jsonGet("$TATHYA/access/generateCaptcha"),
        )
        val body = captchaRsp.body?.string().orEmpty()
        captchaRsp.close()
        DebugLog.line("captcha raw ${body.take(180)}")
        val outer = JSONObject(body)
        val inner = JSONObject(outer.optString("message", "{}"))
        captchaImageB64 = inner.optString("imageBase64")
        captchaTxnId = inner.optString("transactionId")
        if (captchaImageB64.isBlank() || captchaTxnId.isBlank()) {
            error("captcha missing image/txn: $body")
        }
        DebugLog.line("captchaTxnId=$captchaTxnId image.len=${captchaImageB64.length}")
        return Captcha(captchaTxnId, captchaImageB64)
    }

    fun requestOtp(uid: String, captcha: String): String {
        val payload = JSONObject()
            .put("uid", uid)
            .put("captcha", captcha)
            .put("captchaTxnId", captchaTxnId)
            .toString()
        val rsp = call(jsonPost("$TATHYA/access/generateOTPForOAuth", payload))
        val body = rsp.body?.string().orEmpty()
        rsp.close()
        DebugLog.line("generateOTP $body")
        val obj = JSONObject(body)
        if (!obj.optBoolean("status")) {
            error(obj.optString("message", body))
        }
        otpTxnId = obj.optString("txnId")
        return otpTxnId
    }

    fun verifyOtp(uid: String, otp: String): TokenPair {
        var code: String? = null
        var failure = "login failed"
        for (attempt in 1..2) {
            val login = postLogin(uid, otp)
            if (login?.optString("status") == "Y") {
                var redirect = login.optString("redirectURL")
                if (redirect.startsWith("http://")) redirect = "https://" + redirect.removePrefix("http://")
                DebugLog.line("follow $redirect")
                code = authorizeCode(redirect)
                break
            }
            if (login != null && login.optString("errorCode") != SESSION_ACTIVE) {
                error(login.optString("errorMessage", login.toString()))
            }
            // an active session or a redirected post leaves a usable SESSION in the jar
            failure = login?.optString("errorMessage") ?: "login redirected, no oauth code"
            DebugLog.line("login attempt $attempt: re-authorize")
            code = authorizeCode(authorizeUrl)
            if (code != null) break
        }
        if (code == null) error(failure)
        DebugLog.line("auth code=${code.take(8)}…")

        val tokenUrl = HttpUrl.Builder()
            .scheme("https")
            .host("tathya.uidai.gov.in")
            .addPathSegments("access/oauth/token")
            .addQueryParameter("client_id", CLIENT_ID)
            .addQueryParameter("grant_type", "authorization_code")
            .addQueryParameter("code_verifier", codeVerifier)
            .addQueryParameter("code", code)
            .build()
        val tokenRsp = call(
            Request.Builder()
                .url(tokenUrl)
                .header("User-Agent", UA)
                .header("Accept", "application/json, text/plain, */*")
                .header("Origin", MYAADHAAR)
                .header("Referer", "$MYAADHAAR/")
                .post("".toRequestBody(null))
                .build(),
        )
        val tokenBody = tokenRsp.body?.string().orEmpty()
        tokenRsp.close()
        DebugLog.line("token ${maskTokens(tokenBody)}")
        return storeTokens(JSONObject(tokenBody))
    }

    // null when the post redirected: the new SESSION is in the jar and the redirect has been followed
    private fun postLogin(uid: String, otp: String): JSONObject? {
        val payload = JSONObject().put("uid", uid).put("otp", otp).toString()
        val rsp = call(jsonPost("$TATHYA/access/login", payload))
        val body = rsp.body?.string().orEmpty()
        val status = rsp.code
        val location = rsp.header("Location")?.let { rsp.request.url.resolve(it) }
        rsp.close()
        DebugLog.line("login $status $body")
        if (status !in 300..399) return JSONObject(body)
        if (location != null) {
            call(
                Request.Builder()
                    .url(location)
                    .header("User-Agent", UA)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Referer", "$TATHYA/access/login?role=resident")
                    .get()
                    .build(),
            ).close()
        }
        return null
    }

    private fun authorizeCode(url: String): String? {
        val rsp = call(
            Request.Builder()
                .url(url)
                .header("User-Agent", UA)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Referer", "$TATHYA/access/login?role=resident")
                .header("Upgrade-Insecure-Requests", "1")
                .get()
                .build(),
        )
        val location = rsp.header("Location").orEmpty()
        val status = rsp.code
        rsp.close()
        val code = location.toHttpUrlOrNull()?.queryParameter("code")
        if (code == null) DebugLog.line("no oauth code in Location=$location status=$status")
        return code
    }

    fun refresh(): TokenPair {
        val refreshToken = tokens.load()?.refresh.orEmpty()
        check(refreshToken.isNotBlank()) { "no refresh token" }
        val url = HttpUrl.Builder()
            .scheme("https")
            .host("tathya.uidai.gov.in")
            .addPathSegments("access/oauth/token")
            .addQueryParameter("grant_type", "refresh_token")
            .addQueryParameter("refresh_token", refreshToken)
            .addQueryParameter("client_id", CLIENT_ID)
            .build()
        val rsp = call(
            Request.Builder()
                .url(url)
                .header("User-Agent", UA)
                .header("Accept", "application/json, text/plain, */*")
                .header("Origin", MYAADHAAR)
                .header("Referer", "$MYAADHAAR/")
                .header("Content-Type", "application/json")
                .post("{}".toRequestBody(JSON))
                .build(),
        )
        val body = rsp.body?.string().orEmpty()
        rsp.close()
        DebugLog.line("refresh ${maskTokens(body)}")
        val obj = JSONObject(body)
        if (obj.has("error")) error(obj.optString("error_description", body))
        return storeTokens(obj)
    }

    fun ensureFresh() {
        val current = tokens.load() ?: error("not logged in")
        if (System.currentTimeMillis() > current.expiresAtMs - 20_000) {
            DebugLog.line("access token near expiry, refreshing")
            refresh()
        }
    }

    fun preferences(uid: String): JSONObject = authed("lockUnlockBiometrics/ext/v1/oauth/getUidPreferences", uid) { body ->
        JSONObject().put("uid", uid)
    }

    fun lockUnlock(uid: String, action: Int): JSONObject =
        authed("lockUnlockBiometrics/ext/v1/oauth/lockUnlock", uid) {
            JSONObject().put("uid", uid).put("bioLockOrUnlock", action)
        }

    fun profile(uid: String): JSONObject =
        authed("profileService/api/v1/oauth/profile", uid, logBody = false) {
            JSONObject().put("uidNumber", uid)
        }

    private fun authed(
        path: String,
        uid: String,
        logBody: Boolean = true,
        body: (String) -> JSONObject,
    ): JSONObject {
        fun logged(text: String) = if (logBody) text else "(${text.length} chars)"

        fun once(): Pair<Int, String> {
            ensureFresh()
            val rsp = call(
                Request.Builder()
                    .url("$TATHYA/$path")
                    .header("User-Agent", UA)
                    .header("Accept", "application/json, text/plain, */*")
                    .header("Accept-Language", "en_IN")
                    .header("Content-Type", "application/json")
                    .header("appID", "MYAADHAAR")
                    .header("Authorization", "Bearer ${tokens.load()?.access.orEmpty()}")
                    .header("X-Request-ID", telemetry)
                    .header("Origin", MYAADHAAR)
                    .header("Referer", "$MYAADHAAR/")
                    .post(body(uid).toString().toRequestBody(JSON))
                    .build(),
            )
            val text = rsp.body?.string().orEmpty()
            val code = rsp.code
            rsp.close()
            return code to text
        }
        var (code, text) = once()
        DebugLog.line("$path $code ${logged(text)}")
        if (text.contains("invalid_token") || code == 401) {
            DebugLog.line("token rejected, refresh+retry")
            refresh()
            val retry = once()
            code = retry.first
            text = retry.second
            DebugLog.line("$path retry $code ${logged(text)}")
        }
        val obj = JSONObject(text)
        if (obj.has("error") && obj.optString("error").isNotBlank()) {
            error(obj.optString("error_description", text))
        }
        return obj
    }

    private fun jsonGet(url: String): Request = Request.Builder()
        .url(url)
        .header("User-Agent", UA)
        .header("Accept", "application/json, text/plain, */*")
        .header("X-Request-Id", nextReqId())
        .header("Referer", "$TATHYA/access/login?role=resident")
        .get()
        .build()

    private fun jsonPost(url: String, json: String): Request = Request.Builder()
        .url(url)
        .header("User-Agent", UA)
        .header("Accept", "application/json, text/plain, */*")
        .header("Content-Type", "application/json")
        .header("X-Request-Id", nextReqId())
        .header("Origin", TATHYA)
        .header("Referer", "$TATHYA/access/login?role=resident")
        .post(json.toRequestBody(JSON))
        .build()

    private fun nextReqId(): String = "$telemetry-${reqSeq}"

    private fun call(req: Request) = http.newCall(req).execute()

    private fun storeTokens(obj: JSONObject): TokenPair {
        if (obj.has("error")) error(obj.optString("error_description", obj.toString()))
        val ttl = obj.optLong("expires_in", 299)
        val pair = TokenPair(
            obj.getString("access_token"),
            obj.optString("refresh_token").ifBlank { tokens.load()?.refresh.orEmpty() },
            System.currentTimeMillis() + ttl * 1000,
        )
        tokens.save(pair)
        DebugLog.line("tokens ttl=${ttl}s user=${obj.optString("user_name")}")
        return pair
    }

    private fun cookieHint(req: Request): String {
        val c = req.header("Cookie") ?: return "none"
        return if (c.contains("SESSION=")) "SESSION" else c.take(24)
    }

    private fun maskTokens(s: String): String =
        s.replace(Regex("eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")) { "JWT(${it.value.length})" }

    data class Captcha(val txnId: String, val imageB64: String)
    data class TokenPair(val access: String, val refresh: String, val expiresAtMs: Long)
}

class SessionJar : CookieJar {
    private val store = LinkedHashMap<String, Cookie>()

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        cookies.forEach { c ->
            if (c.value.isEmpty()) store.remove(c.name) else store[c.name] = c
        }
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> =
        store.values.filter { it.matches(url) }

    @Synchronized
    fun clear() = store.clear()

    @Synchronized
    fun sessionHint(): String = store["SESSION"]?.value?.take(8)?.plus("…") ?: "none"
}

object Pkce {
    fun generate(): Pair {
        val verifier = buildString(128) {
            val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
            val rng = java.security.SecureRandom()
            repeat(128) { append(alphabet[rng.nextInt(alphabet.length)]) }
        }
        val hex = sha256(verifier).joinToString("") { "%02x".format(it) }
        val challenge = android.util.Base64.encodeToString(
            hex.toByteArray(Charsets.US_ASCII),
            android.util.Base64.NO_WRAP,
        )
        return Pair(verifier, challenge)
    }

    private fun sha256(s: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.US_ASCII))

    data class Pair(val verifier: String, val challenge: String)
}
