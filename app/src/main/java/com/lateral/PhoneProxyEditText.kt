package com.lateral

import android.content.Context
import android.util.AttributeSet
import android.view.KeyEvent
import androidx.appcompat.widget.AppCompatEditText
import com.lateral.beast.LauncherSearchSession

/** Phone-owned input used only for Beast launcher search. */
class PhoneProxyEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : AppCompatEditText(context, attrs) {
    override fun onKeyPreIme(keyCode: Int, event: KeyEvent): Boolean {
        if (LauncherSearchSession.active &&
            (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE)
        ) {
            // Close search before the phone IME consumes Back merely to hide itself.
            if (event.action == KeyEvent.ACTION_UP) LauncherSearchSession.close()
            return true
        }
        return super.onKeyPreIme(keyCode, event)
    }
}
