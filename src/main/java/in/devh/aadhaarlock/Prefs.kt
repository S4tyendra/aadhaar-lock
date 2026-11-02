package `in`.devh.aadhaarlock

import android.content.Context

class Prefs(context: Context) {
    private val p = context.getSharedPreferences("aadhaarlock", Context.MODE_PRIVATE)

    var uid: String
        get() = p.getString("uid", "") ?: ""
        set(v) { p.edit().putString("uid", v).apply() }

    var uidBlob: String
        get() = p.getString("uidBlob", "") ?: ""
        set(v) { p.edit().putString("uidBlob", v).apply() }

    var apiKey: String
        get() = p.getString("apiKey", "") ?: ""
        set(v) { p.edit().putString("apiKey", v).apply() }

    var apiBase: String
        get() = p.getString("apiBase", CaptchaSolver.DEFAULT_BASE) ?: CaptchaSolver.DEFAULT_BASE
        set(v) { p.edit().putString("apiBase", v).apply() }

    var apiModel: String
        get() = p.getString("apiModel", CaptchaSolver.DEFAULT_MODEL) ?: CaptchaSolver.DEFAULT_MODEL
        set(v) { p.edit().putString("apiModel", v).apply() }

    var logging: Boolean
        get() = p.getBoolean("logging", false)
        set(v) { p.edit().putBoolean("logging", v).apply() }

    var rootSms: Boolean
        get() = p.getBoolean("rootSms", false)
        set(v) { p.edit().putBoolean("rootSms", v).apply() }

    var colorOsUi: Boolean
        get() = p.getBoolean("colorOsUi", true)
        set(v) { p.edit().putBoolean("colorOsUi", v).apply() }

    var tokenBlob: String
        get() = p.getString("tokenBlob", "") ?: ""
        set(v) { p.edit().putString("tokenBlob", v).apply() }

    fun takeLegacyTokens(): TathyaApi.TokenPair? {
        val access = p.getString("access", "").orEmpty()
        val tokens = if (access.isBlank()) {
            null
        } else {
            TathyaApi.TokenPair(access, p.getString("refresh", "").orEmpty(), p.getLong("exp", 0))
        }
        p.edit().remove("access").remove("refresh").remove("exp").apply()
        return tokens
    }
}
