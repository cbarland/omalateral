package com.lateral

import android.app.Activity
import android.app.ActivityOptions
import android.content.Intent
import android.os.Bundle
import android.view.Display
import com.lateral.beast.BeastActivity

/** Entry point used by a secondary home to wake PhoneUI when a display appears. */
class ExternalDisplayLaunchActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.action == ACTION_AUTO_OPEN_EXTERNAL &&
            DesktopShellSettings.autoStart(this) &&
            BeastActivity.findExternalDisplay(this) != null
        ) {
            val phone = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            val options = ActivityOptions.makeBasic().apply { launchDisplayId = Display.DEFAULT_DISPLAY }
            startActivity(phone, options.toBundle())
        }
        finish()
    }

    companion object {
        const val ACTION_AUTO_OPEN_EXTERNAL = "com.omalateral.action.AUTO_OPEN_EXTERNAL"
    }
}
