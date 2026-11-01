package `in`.devh.aadhaarlock

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object CaptchaSolver {
    const val DEFAULT_BASE = "https://generativelanguage.googleapis.com/v1beta/openai/"
    const val DEFAULT_MODEL = "gemini-2.5-flash-lite"

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    fun solve(imageB64: String, apiKey: String, baseUrl: String, model: String): String {
        check(apiKey.isNotBlank()) { "set Gemini / OpenAI-compatible API key" }
        val base = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        val body = JSONObject()
            .put("model", model)
            .put("temperature", 0)
            .put(
                "messages",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put(
                            "content",
                            JSONArray()
                                .put(
                                    JSONObject()
                                        .put("type", "text")
                                        .put(
                                            "text",
                                            "Read this UIDAI captcha. It is 5 alphanumeric characters (a-z, 0-9). " +
                                                "Reply with only those characters, lowercase, no spaces.",
                                        ),
                                )
                                .put(
                                    JSONObject()
                                        .put("type", "image_url")
                                        .put(
                                            "image_url",
                                            JSONObject().put("url", "data:image/jpeg;base64,$imageB64"),
                                        ),
                                ),
                        ),
                ),
            )
        val req = Request.Builder()
            .url("${base}chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        DebugLog.line("captcha LLM $model ${base}chat/completions")
        http.newCall(req).execute().use { rsp ->
            val text = rsp.body?.string().orEmpty()
            if (!rsp.isSuccessful) error("LLM ${rsp.code} $text")
            val content = JSONObject(text)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .optString("content")
            val code = Regex("[A-Za-z0-9]{4,8}").find(content.replace(" ", ""))?.value?.lowercase()
                ?: error("LLM did not return captcha: $content")
            DebugLog.line("captcha LLM='$content' parsed='$code'")
            return code
        }
    }
}
