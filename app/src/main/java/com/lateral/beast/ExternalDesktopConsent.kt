package com.lateral.beast

import android.app.Activity
import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Intent
import android.view.Display
import com.lateral.BuildConfig
import com.lateral.MainActivity

/** Keep the approval task on PhoneUI instead of relocating the external workspace. */
internal object ExternalDesktopConsent {
    val ACTION = BuildConfig.APPLICATION_ID + ".action.EXTERNAL_DESKTOP_CONSENT"
    private const val REQUEST = 4816
    private data class Request(val owner: Any, val intent: PendingIntent, val result: (Int) -> Unit)
    private var queued: Request? = null
    private var active: Request? = null

    fun begin(owner: Any, activity: Activity, intent: PendingIntent, result: (Int) -> Unit) {
        queued = Request(owner, intent, result)
        activity.startActivity(Intent(activity, MainActivity::class.java).setAction(ACTION)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            ActivityOptions.makeBasic().apply { launchDisplayId = Display.DEFAULT_DISPLAY }.toBundle())
    }

    @Suppress("DEPRECATION")
    fun show(activity: Activity) {
        val request = queued ?: return
        queued = null
        active = request
        runCatching { activity.startIntentSenderForResult(request.intent.intentSender, REQUEST, null, 0, 0, 0) }
            .onFailure { active = null; request.result(Activity.RESULT_CANCELED) }
    }

    fun finish(requestCode: Int, resultCode: Int): Boolean {
        if (requestCode != REQUEST) return false
        val request = active
        active = null
        request?.result?.invoke(resultCode)
        return true
    }

    fun cancel(owner: Any) {
        if (queued?.owner === owner) queued = null
        if (active?.owner === owner) active = null
    }
}
