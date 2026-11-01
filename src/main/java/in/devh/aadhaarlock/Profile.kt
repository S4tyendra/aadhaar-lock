package `in`.devh.aadhaarlock

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

data class Profile(
    val name: String,
    val localName: String,
    val gender: String,
    val dob: String,
    val uid: String,
    val mobile: String,
    val email: String,
    val photo: Bitmap?,
    val address: String,
    val localAddress: String,
    val notices: String,
) {
    val spacedUid: String
        get() = uid.chunked(4).joinToString(" ")

    fun copyable(): List<Pair<String, String>> {
        val fields = listOf(
            "Aadhaar number" to uid,
            "Name" to name,
            "Local name" to localName,
            "Date of birth" to dob,
            "Gender" to gender,
            "Mobile" to mobile,
            "Email" to email,
            "Address" to address,
            "Local address" to localAddress,
        ).filter { it.second.isNotEmpty() }
        return fields + ("All details" to fields.joinToString("\n") { "${it.first}: ${it.second}" })
    }

    companion object {
        private val DOB_FORMAT = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)

        // random groups, so hidden values don't give away their real length
        fun dots(groups: IntRange, tail: String): String =
            (List(groups.random()) { "•".repeat((3..7).random()) } + tail)
                .filter { it.isNotEmpty() }
                .joinToString(" ")

        fun parse(root: JSONObject): Profile? {
            val data = root.optJSONObject("responseData") ?: return null
            val d = data.optJSONObject("demographicsInfo") ?: return null
            val expiry = d.text("aadhaarExpiryDate")
            return Profile(
                name = d.text("name"),
                localName = d.text("localResName"),
                gender = d.text("gender").lowercase(Locale.ENGLISH)
                    .replaceFirstChar { it.titlecase(Locale.ENGLISH) },
                dob = formatDob(d.text("dob")),
                uid = d.text("uid"),
                mobile = d.text("mobile"),
                email = d.text("email"),
                photo = decodePhoto(d.text("residentPhoto")),
                address = d.join(
                    "careof", "building", "street", "landmark", "locality", "line1", "line2", "line3",
                    "vtcName", "poName", "subDistrictName", "districtName", "stateName", "pincode",
                ),
                localAddress = d.join(
                    "localCareof", "localBuilding", "localStreet", "localLandmark", "localLocality",
                    "localLine1", "localLine2", "localLine3", "localVtc", "poNameLocal",
                    "subDistrictLocalName", "localDistrict", "localState", "pincode",
                ),
                notices = listOf(
                    data.text("bioUpdateStatusMessage"),
                    data.text("docUpdateStatusMessage"),
                    data.text("suspectedDeceasedStatusMessage"),
                    if (expiry.isEmpty()) "" else "Aadhaar valid until $expiry",
                ).filter { it.isNotEmpty() }.joinToString("\n"),
            )
        }

        private fun formatDob(raw: String): String = try {
            LocalDate.parse(raw).format(DOB_FORMAT)
        } catch (_: DateTimeParseException) {
            raw
        }

        private fun decodePhoto(raw: String): Bitmap? {
            if (raw.isEmpty()) return null
            return try {
                val bytes = Base64.decode(raw.substringAfter(','), Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } catch (_: IllegalArgumentException) {
                null
            }
        }

        // optString turns a JSON null into the text "null"
        private fun JSONObject.text(key: String): String =
            if (isNull(key)) "" else optString(key).trim().replace(Regex("\\s+"), " ")

        private fun JSONObject.join(vararg keys: String): String =
            keys.map { text(it) }.filter { it.isNotEmpty() }.joinToString(", ")
    }
}
