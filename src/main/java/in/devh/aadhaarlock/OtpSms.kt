package `in`.devh.aadhaarlock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import android.telephony.SmsMessage
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

data class SmsRow(
    val id: Long,
    val date: Long,
    val address: String,
    val body: String,
)

object OtpParser {
    private val leadSixIsOtp = Regex("""^\s*(\d{6})\s+is\s+OTP\b""", RegexOption.IGNORE_CASE)
    private val sixIsOtpForAadhaar = Regex("""\b(\d{6})\s+is\s+OTP\s+for\s+Aadhaar\b""", RegexOption.IGNORE_CASE)
    private val sixIsOtp = Regex("""\b(\d{6})\s+is\s+OTP\b""", RegexOption.IGNORE_CASE)

    fun isUidai(address: String, body: String): Boolean {
        val a = address.uppercase()
        if (a.contains("ADHAAR") || a.contains("UIDAI")) return true
        if (body.contains("UIDAI", true)) return true
        if (body.contains("Aadhaar", true) || body.contains("Aadhar", true)) return true
        return false
    }

    fun extract(address: String, body: String): String? {
        if (!isUidai(address, body)) return null
        val text = body.replace('\u00a0', ' ').trim()
        leadSixIsOtp.find(text)?.groupValues?.get(1)?.let { return it }
        sixIsOtpForAadhaar.find(text)?.groupValues?.get(1)?.let { return it }
        sixIsOtp.find(text)?.groupValues?.get(1)?.let { return it }
        return null
    }
}

class OtpSmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        DebugLog.line("sms broadcast action=${intent.action}")
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION &&
            intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION
        ) return
        val msgs = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        val sender = msgs.firstOrNull()?.displayOriginatingAddress.orEmpty()
        val body = msgs.joinToString("") { it.messageBody.orEmpty() }
        val date = msgs.maxOfOrNull { it.timestampMillis } ?: System.currentTimeMillis()
        DebugLog.line("sms pdu date=$date addr=$sender body=${body.take(180)}")
        // pdu timestamp is the service center clock, a live broadcast is new by definition
        OtpParser.extract(sender, body)?.let { OtpBus.emit(it, System.currentTimeMillis()) }
    }
}

object OtpBus {
    const val CLOSED = Long.MAX_VALUE

    @Volatile var afterMs = CLOSED
    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()
    fun add(l: (String) -> Unit) { listeners += l }
    fun remove(l: (String) -> Unit) { listeners -= l }
    fun close(window: Long) { if (afterMs == window) afterMs = CLOSED }
    fun emit(otp: String, date: Long) {
        if (date <= afterMs) return
        DebugLog.line("otp bus $otp date=$date listeners=${listeners.size}")
        listeners.forEach { it(otp) }
    }
}

object SmsInbox {
    private val smsDb = "/data/data/com.android.providers.telephony/databases/mmssms.db"

    // off: broadcast and provider only. Newer Android hides OTP texts there, so the field stays typeable
    @Volatile var rootEnabled = false

    fun latestUidaiOtp(context: Context, afterMs: Long): String? {
        val rows = after(context, afterMs)
        if (rows.isEmpty()) {
            DebugLog.line("sms after $afterMs: none")
            return null
        }
        for (r in rows) {
            val otp = OtpParser.extract(r.address, r.body)
            DebugLog.line("sms id=${r.id} date=${r.date} addr=${r.address} otp=${otp ?: "-"} body=${r.body.replace("\n", " ").take(160)}")
            if (otp != null) return otp
        }
        return null
    }

    fun after(context: Context, afterMs: Long): List<SmsRow> {
        val seen = LinkedHashMap<Long, SmsRow>()
        fun add(row: SmsRow) {
            if (row.date <= afterMs) return
            if (row.body.isBlank()) return
            seen.putIfAbsent(row.id.takeIf { it > 0 } ?: (row.date xor row.body.hashCode().toLong()), row)
        }
        val sqlite = if (rootEnabled) dumpSqlite(context) else emptyList()
        sqlite.forEach(::add)
        dumpProvider(context).forEach(::add)
        val maxId = (sqlite + seen.values).maxOfOrNull { it.id } ?: 0L
        dumpByIdProbe(context, maxId).forEach(::add)
        return seen.values.sortedByDescending { it.date }
    }

    fun readUri(context: Context, uri: Uri): List<SmsRow> {
        val out = ArrayList<SmsRow>()
        try {
            val cur = context.contentResolver.query(uri, null, null, null, null) ?: return out
            cur.use {
                val iId = it.getColumnIndex("_id")
                val iDate = it.getColumnIndex("date")
                val iAddr = it.getColumnIndex("address")
                val iBody = it.getColumnIndex("body")
                val iPdu = it.getColumnIndex("pdu")
                val cols = it.columnNames.joinToString(",")
                var n = 0
                while (it.moveToNext() && n++ < 20) {
                    var body = if (iBody >= 0) it.getString(iBody).orEmpty() else ""
                    var addr = if (iAddr >= 0) it.getString(iAddr).orEmpty() else ""
                    var date = if (iDate >= 0) it.getLong(iDate) else 0L
                    if (body.isBlank() && iPdu >= 0) {
                        val decoded = decodePdu(it, iPdu)
                        if (decoded != null) {
                            addr = decoded.displayOriginatingAddress.orEmpty()
                            body = decoded.messageBody.orEmpty()
                            if (date == 0L) date = decoded.timestampMillis
                        }
                    }
                    val id = if (iId >= 0) it.getLong(iId) else uri.lastPathSegment?.toLongOrNull() ?: 0L
                    DebugLog.line("uri $uri cols=$cols id=$id date=$date addr=$addr body=${body.take(120)}")
                    if (body.isNotBlank()) out += SmsRow(id, date, addr, body)
                }
            }
        } catch (t: Throwable) {
            DebugLog.line("uri $uri fail ${t.javaClass.simpleName}: ${t.message}")
        }
        return out
    }

    private fun decodePdu(cur: android.database.Cursor, iPdu: Int): SmsMessage? {
        return try {
            val blob = if (cur.getType(iPdu) == android.database.Cursor.FIELD_TYPE_BLOB) {
                cur.getBlob(iPdu)
            } else {
                val hex = cur.getString(iPdu) ?: return null
                hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            }
            SmsMessage.createFromPdu(blob, "3gpp")
                ?: SmsMessage.createFromPdu(blob, "3gpp2")
        } catch (t: Throwable) {
            DebugLog.line("pdu decode ${t.message}")
            null
        }
    }

    private fun dumpProvider(context: Context): List<SmsRow> {
        val out = ArrayList<SmsRow>()
        for (uri in listOf(Uri.parse("content://sms/inbox"), Uri.parse("content://sms"))) {
            try {
                val cur = context.contentResolver.query(
                    uri,
                    arrayOf("_id", "date", "address", "body", "type", "contains_otp"),
                    null,
                    null,
                    "date DESC",
                ) ?: continue
                cur.use {
                    val iId = it.getColumnIndex("_id")
                    val iDate = it.getColumnIndex("date")
                    val iAddr = it.getColumnIndex("address")
                    val iBody = it.getColumnIndex("body")
                    val iType = it.getColumnIndex("type")
                    val iOtp = it.getColumnIndex("contains_otp")
                    var n = 0
                    var newest = 0L
                    var newestId = 0L
                    while (it.moveToNext() && n++ < 40) {
                        if (iType >= 0 && it.getInt(iType) != 1) continue
                        val row = SmsRow(
                            id = it.getLong(iId),
                            date = it.getLong(iDate),
                            address = it.getString(iAddr).orEmpty(),
                            body = it.getString(iBody).orEmpty(),
                        )
                        if (n == 1) {
                            newest = row.date
                            newestId = row.id
                            val flag = if (iOtp >= 0) it.getInt(iOtp) else -1
                            DebugLog.line("provider $uri newest id=$newestId date=$newest otpFlag=$flag addr=${row.address}")
                        }
                        out += row
                    }
                    DebugLog.line("provider $uri scanned=$n")
                }
            } catch (t: Throwable) {
                DebugLog.line("provider $uri fail ${t.javaClass.simpleName}: ${t.message}")
            }
        }
        return out
    }

    private fun dumpByIdProbe(context: Context, maxId: Long): List<SmsRow> {
        val out = ArrayList<SmsRow>()
        val start = if (maxId > 0) maxId else 1L
        for (id in start..(start + 8)) {
            out += readUri(context, Uri.parse("content://sms/$id"))
        }
        return out
    }

    private fun dumpSqlite(context: Context): List<SmsRow> {
        val dest = File(context.cacheDir, "mmssms.db")
        val wal = File(context.cacheDir, "mmssms.db-wal")
        return try {
            // drop the previous copy so a stale -wal or -shm can't mix with this one
            SQLiteDatabase.deleteDatabase(dest)
            val (code, err) = suCat(smsDb, dest)
            if (code != 0 || dest.length() < 1000) {
                DebugLog.line("su cat mmssms.db exit=$code size=${dest.length()} $err")
                return emptyList()
            }
            if (suCat("$smsDb-wal", wal).first != 0) wal.delete()
            val walSize = wal.length()
            // read-write so sqlite can recover a wal into our private copy
            val db = SQLiteDatabase.openDatabase(
                dest.path,
                null,
                SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
            )
            val out = ArrayList<SmsRow>()
            db.use {
                it.rawQuery(
                    "SELECT _id,date,address,body FROM sms WHERE type=1 AND deleted=0 ORDER BY date DESC LIMIT 30",
                    null,
                ).use { cur ->
                    while (cur.moveToNext()) {
                        out += SmsRow(
                            id = cur.getLong(0),
                            date = cur.getLong(1),
                            address = cur.getString(2).orEmpty(),
                            body = cur.getString(3).orEmpty(),
                        )
                    }
                }
            }
            val top = out.firstOrNull()
            DebugLog.line("sqlite rows=${out.size} wal=$walSize newest id=${top?.id} date=${top?.date} addr=${top?.address}")
            out
        } catch (t: Throwable) {
            DebugLog.line("sqlite fail ${t.javaClass.simpleName}: ${t.message}")
            emptyList()
        } finally {
            SQLiteDatabase.deleteDatabase(dest)
        }
    }

    // app data isolation hides other apps' data in our mount namespace, so read from the global one.
    // stream through su so the copy is owned and labeled as ours
    private fun suCat(src: String, dest: File): Pair<Int, String> {
        val p = ProcessBuilder("su", "--mount-master").start()
        p.outputStream.bufferedWriter().use { w ->
            w.write("cat $src\n")
            w.write("exit\n")
        }
        dest.outputStream().use { p.inputStream.copyTo(it) }
        val err = p.errorStream.bufferedReader().readText().trim()
        return p.waitFor() to err.take(200)
    }

    private fun su(cmd: String): Pair<Int, String> {
        return try {
            val p = ProcessBuilder("su").redirectErrorStream(true).start()
            p.outputStream.bufferedWriter().use { w ->
                w.write("$cmd\n")
                w.write("exit\n")
            }
            val out = p.inputStream.bufferedReader().readText().trim()
            p.waitFor() to out.take(400)
        } catch (t: Throwable) {
            -1 to (t.message ?: "su fail")
        }
    }

    fun grantOtpRead(context: Context) {
        if (!rootEnabled) return
        val pkg = context.packageName
        Thread {
            su("appops set $pkg READ_RESTRICTED_MESSAGES allow")
            su("appops set $pkg READ_SMS allow")
            su("appops set $pkg RECEIVE_SMS allow")
        }.start()
    }
}

class SmsWatcher(private val context: Context) {
    private val receiver = OtpSmsReceiver()
    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = onChange(selfChange, null)
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            DebugLog.line("sms inbox changed uri=$uri")
            if (uri != null) {
                SmsInbox.readUri(context, uri).forEach { r ->
                    OtpParser.extract(r.address, r.body)?.let { OtpBus.emit(it, r.date) }
                }
            }
        }
    }
    private var registered = false

    fun start() {
        if (registered) return
        SmsInbox.grantOtpRead(context)
        val filter = IntentFilter(Telephony.Sms.Intents.SMS_RECEIVED_ACTION).apply { priority = Int.MAX_VALUE }
        try {
            ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
            DebugLog.line("sms receiver registered")
        } catch (t: Throwable) {
            DebugLog.line("sms receiver fail ${t.message}")
        }
        try {
            context.contentResolver.registerContentObserver(Uri.parse("content://sms"), true, observer)
            context.contentResolver.registerContentObserver(Uri.parse("content://sms/raw"), true, observer)
            DebugLog.line("sms observer registered")
        } catch (t: Throwable) {
            DebugLog.line("sms observer fail ${t.message}")
        }
        registered = true
    }

    fun stop() {
        if (!registered) return
        try { context.unregisterReceiver(receiver) } catch (_: Throwable) {}
        try { context.contentResolver.unregisterContentObserver(observer) } catch (_: Throwable) {}
        registered = false
    }
}

object OtpWait {
    suspend fun await(
        context: Context,
        afterMs: Long,
        timeoutMs: Long = 90_000,
        typed: suspend () -> String,
    ): String {
        val got = AtomicReference<String?>(null)
        val listener: (String) -> Unit = { otp -> got.compareAndSet(null, otp) }
        OtpBus.add(listener)
        try {
            val hit = withTimeoutOrNull(timeoutMs) {
                var n = 0
                while (got.get() == null) {
                    val field = typed().trim()
                    if (field.length == 6 && field.all { it.isDigit() }) return@withTimeoutOrNull field
                    SmsInbox.latestUidaiOtp(context, afterMs)?.let { got.compareAndSet(null, it) }
                    if (got.get() != null) break
                    if (n++ % 4 == 0) DebugLog.line("waiting sms date>$afterMs tick=$n")
                    delay(700)
                }
                got.get()
            }
            return hit ?: error("no UIDAI SMS with date > $afterMs")
        } finally {
            OtpBus.remove(listener)
            OtpBus.close(afterMs)
        }
    }
}
