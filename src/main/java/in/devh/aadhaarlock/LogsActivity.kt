package `in`.devh.aadhaarlock

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.view.View
import android.widget.ScrollView
import com.google.android.material.snackbar.Snackbar
import `in`.devh.aadhaarlock.databinding.ActivityLogsBinding

class LogsActivity : Screen() {
    private lateinit var b: ActivityLogsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityLogsBinding.inflate(layoutInflater)
        setContentView(b.root)
        fitInsets(b.root)
        b.toolbar.setNavigationOnClickListener { finish() }
        b.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.copy -> copyLog()
                R.id.clear -> clearLog()
            }
            true
        }
    }

    override fun onStart() {
        super.onStart()
        b.logView.text = if (DebugLog.enabled) DebugLog.dump() + "\n" else "Logging is off. Turn it on in Settings."
        b.toolbar.menu.setGroupVisible(0, DebugLog.enabled)
        scrollDown()
        DebugLog.listener = { row ->
            runOnUiThread {
                b.logView.append(row + "\n")
                scrollDown()
            }
        }
    }

    override fun onStop() {
        DebugLog.listener = null
        super.onStop()
    }

    private fun scrollDown() {
        b.scroller.post { b.scroller.fullScroll(View.FOCUS_DOWN) }
    }

    private fun copyLog() {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("aadhaarlock", DebugLog.dump()))
        Snackbar.make(b.root, "Logs copied", Snackbar.LENGTH_SHORT).show()
    }

    private fun clearLog() {
        b.logView.text = ""
        DebugLog.clear()
    }
}
