package `in`.devh.aadhaarlock

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.TextView
import android.widget.Toast
import `in`.devh.aadhaarlock.databinding.ActivityLogsBinding
import `in`.devh.coui.Coui

class LogsActivity : Screen() {
    private lateinit var logView: TextView
    private lateinit var scrollDown: () -> Unit
    private var materialMenu: android.view.Menu? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (colorOs) buildColorOs() else buildMaterial()
    }

    private fun buildMaterial() {
        val b = ActivityLogsBinding.inflate(layoutInflater)
        setContentView(b.root)
        fitInsets(b.root)
        logView = b.logView
        materialMenu = b.toolbar.menu
        scrollDown = { b.scroller.post { b.scroller.fullScroll(View.FOCUS_DOWN) } }
        b.toolbar.setNavigationOnClickListener { finish() }
        b.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.copy -> copyLog()
                R.id.clear -> clearLog()
            }
            true
        }
    }

    private fun buildColorOs() {
        logView = coui.text("").apply {
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextIsSelectable(true)
        }
        val scroller = coui.column(logView)
        scrollDown = { scroller.post { coui.call(scroller, "fullScroll", View.FOCUS_DOWN) } }
        val menu = if (DebugLog.enabled) {
            listOf(Coui.Item("Copy") { copyLog() }, Coui.Item("Clear") { clearLog() })
        } else {
            emptyList()
        }
        coui.show(this, coui.screen("Logs", scroller, onBack = { finish() }, menu = menu))
    }

    override fun onStart() {
        super.onStart()
        logView.text = if (DebugLog.enabled) DebugLog.dump() + "\n" else "Logging is off. Turn it on in Settings."
        materialMenu?.setGroupVisible(0, DebugLog.enabled)
        scrollDown()
        DebugLog.listener = { row ->
            runOnUiThread {
                logView.append(row + "\n")
                scrollDown()
            }
        }
    }

    override fun onStop() {
        DebugLog.listener = null
        super.onStop()
    }

    private fun copyLog() {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("aadhaarlock", DebugLog.dump()))
        Toast.makeText(this, "Logs copied", Toast.LENGTH_SHORT).show()
    }

    private fun clearLog() {
        logView.text = ""
        DebugLog.clear()
    }
}
