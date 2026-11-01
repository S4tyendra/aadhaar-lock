package `in`.devh.aadhaarlock

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

object DebugLog {
    private const val TAG = "aadhaarlock"
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val lines = CopyOnWriteArrayList<String>()
    private var file: File? = null
    @Volatile var enabled = false
        private set
    var listener: ((String) -> Unit)? = null

    fun init(context: Context) {
        file = File(context.filesDir, "aadhaarlock.log")
        update(Prefs(context).logging)
    }

    // turning logging off also erases what was collected
    @Synchronized
    fun update(on: Boolean) {
        enabled = on
        if (!on) {
            lines.clear()
            file?.delete()
        }
    }

    @Synchronized
    fun line(msg: String) {
        if (!enabled) return
        val row = "${fmt.format(Date())}  $msg"
        lines += row
        Log.i(TAG, msg)
        try {
            file?.appendText(row + "\n")
        } catch (_: Exception) {
        }
        listener?.invoke(row)
    }

    fun section(title: String) {
        line("")
        line("======== $title ========")
    }

    fun dump(): String = lines.joinToString("\n")

    fun clear() {
        lines.clear()
        file?.delete()
        line("log cleared")
    }
}
