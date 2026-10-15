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
    private data class Request(val owner: Any, val intent: PendingIntent, var result: ((Int) -> Unit)?)
    private val queued = ArrayDeque<Request>()
    private var active: Request? = null

    fun begin(owner: Any, activity: Activity, intent: PendingIntent, result: (Int) -> Unit) {
        queued.addLast(Request(owner, intent, result))
        if (active != null || queued.size > 1) return
        activity.startActivity(Intent(activity, MainActivity::class.java).setAction(ACTION)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            ActivityOptions.makeBasic().apply { launchDisplayId = Display.DEFAULT_DISPLAY }.toBundle())
    }

    @Suppress("DEPRECATION")
    fun show(activity: Activity) {
        if (active != null) return
        val request = queued.removeFirstOrNull() ?: return
        active = request
        runCatching { activity.startIntentSenderForResult(request.intent.intentSender, REQUEST, null, 0, 0, 0) }
            .onFailure {
                active = null
                request.result?.invoke(Activity.RESULT_CANCELED)
                show(activity)
            }
    }

    fun finish(activity: Activity, requestCode: Int, resultCode: Int): Boolean {
        if (requestCode != REQUEST) return false
        val request = active
        active = null
        request?.result?.invoke(resultCode)
        show(activity)
        return true
    }

    fun cancel(owner: Any) {
        queued.removeAll { it.owner === owner }
        // Keep the in-flight slot until Android returns its result. Otherwise that
        // result could be delivered to a different session using the same request code.
        if (active?.owner === owner) active?.result = null
    }
}
