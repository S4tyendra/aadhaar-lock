package `in`.devh.aadhaarlock

import org.json.JSONObject

/** What a backup holds. Session tokens stay out: they expire, so restoring one wouldn't last. */
class Backup(
    val uid: String?,
    val details: DetailsCache.Saved?,
    val apiBase: String,
    val apiModel: String,
    val apiKey: String,
    val rootSms: Boolean,
    val colorOsUi: Boolean,
) {
    fun toBytes(): ByteArray {
        val root = JSONObject()
            .put("apiBase", apiBase)
            .put("apiModel", apiModel)
            .put("apiKey", apiKey)
            .put("rootSms", rootSms)
            .put("colorOsUi", colorOsUi)
        uid?.let { root.put("uid", it) }
        details?.let {
            root.put(
                "details",
                JSONObject()
                    .put("profile", it.profile)
                    .put("bio", it.bio)
                    .put("bioUntil", it.bioUntil)
                    .put("savedAt", it.savedAt),
            )
        }
        return root.toString().toByteArray(Charsets.UTF_8)
    }

    companion object {
        fun parse(bytes: ByteArray): Backup {
            val root = JSONObject(String(bytes, Charsets.UTF_8))
            val details = root.optJSONObject("details")?.let {
                DetailsCache.Saved(it.getJSONObject("profile"), it.optString("bio"), it.optString("bioUntil"), it.optLong("savedAt"))
            }
            return Backup(
                root.optString("uid").ifEmpty { null },
                details,
                root.getString("apiBase"),
                root.getString("apiModel"),
                root.getString("apiKey"),
                root.optBoolean("rootSms"),
                root.optBoolean("colorOsUi", true),
            )
        }
    }
}
