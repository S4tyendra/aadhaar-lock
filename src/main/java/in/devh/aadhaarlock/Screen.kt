package `in`.devh.aadhaarlock

import android.os.Bundle
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import `in`.devh.coui.Coui

open class Screen : AppCompatActivity() {
    protected var colorOs = true
        private set

    /** Only touched in the ColorOS interface, so Material never loads the COUI pack. */
    protected val coui: Coui by lazy { Coui(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        colorOs = Prefs(this).colorOsUi
        if (!colorOs) enableEdgeToEdge()
        super.onCreate(savedInstanceState)
    }

    protected fun fitInsets(root: View) {
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout() or
                    WindowInsetsCompat.Type.ime(),
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
    }
}
