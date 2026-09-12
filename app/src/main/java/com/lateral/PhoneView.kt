package com.lateral

import android.app.Activity
import android.app.ActivityOptions
import android.app.AlertDialog
import android.app.Application
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.input.InputManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.InputDevice
import com.lateral.beast.BeastActivity
import com.lateral.privileged.PrivilegedService
import java.lang.ref.WeakReference

/** Process-owned device monitoring continues while PhoneUI is behind the workspace. */
object PhoneView : InputManager.InputDeviceListener, DisplayManager.DisplayListener {
    private lateinit var app: Application
    private val handler = Handler(Looper.getMainLooper())
    private val policy = PhoneViewPolicy()
    private var foreground = WeakReference<Activity>(null)
    private val offer = OwnedPopup<AlertDialog> { it.dismiss() }
    private val listeners = linkedSetOf<() -> Unit>()
    val active get() = policy.active
    val eligible get() = policy.eligible
    val canEnter get() = policy.canEnter
    var exiting = false
        private set

    fun beginExit() { exiting = true }

    /** Called only after the old workspace has detached and retained its task surfaces. */
    fun completeExit() {
        exiting = false
        if (active) return
        app.startActivity(Intent(app, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }, ActivityOptions.makeBasic().apply { launchDisplayId = Display.DEFAULT_DISPLAY }.toBundle())
        // Cross-display task moves can expose Home after MainActivity has paused.
        // Keep this bounded repair process-owned; the helper never steals focus
        // from a non-Home app the user has deliberately opened.
        for (delay in listOf(300L, 800L, 1600L)) handler.postDelayed({
            if (!active && !exiting) {
                val taskId = MainActivity.currentPhoneTaskId()
                if (taskId >= 0) PrivilegedService.restorePhoneTaskIfStillHomeAsync(taskId)
            }
        }, delay)
    }

    fun init(application: Application) {
        app = application
        app.getSystemService(InputManager::class.java).registerInputDeviceListener(this, handler)
        app.getSystemService(DisplayManager::class.java).registerDisplayListener(this, handler)
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                foreground = WeakReference(activity)
                handler.post { refresh(); maybeOffer() }
            }
            override fun onActivityPaused(activity: Activity) {
                if (foreground.get() === activity) foreground.clear()
                offer.clearFor(activity)
            }
            override fun onActivityDestroyed(activity: Activity) {
                offer.clearFor(activity)
            }
            override fun onActivityCreated(a: Activity, state: Bundle?) = Unit
            override fun onActivityStarted(a: Activity) = Unit
            override fun onActivityStopped(a: Activity) = Unit
            override fun onActivitySaveInstanceState(a: Activity, state: Bundle) = Unit
        })
        refresh()
    }

    fun addListener(listener: () -> Unit) { listeners += listener }
    fun removeListener(listener: () -> Unit) { listeners -= listener }

    fun refresh() {
        val wasActive = active
        val wasEligible = eligible
        val couldEnter = canEnter
        val keyboard = InputDevice.getDeviceIds().any { id ->
            InputDevice.getDevice(id)?.let {
                !it.isVirtual && it.isExternal && it.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC
            } == true
        }
        policy.update(keyboard, BeastActivity.findExternalDisplay(app) != null)
        if (!eligible) offer.clear()
        if (wasActive != active || wasEligible != eligible || couldEnter != canEnter) {
            android.util.Log.i("PhoneView", "devices: keyboard=$keyboard canEnter=$canEnter active=$active (was $wasActive)")
            PrivilegedService.setHotkeyMonitoringEnabled(!active)
            listeners.toList().forEach { it() }
        }
        maybeOffer()
    }

    private fun maybeOffer() {
        val activity = foreground.get() as? MainActivity ?: return
        if (activity.isFinishing || activity.isDestroyed || offer.current != null || !policy.takeOffer()) return
        val dialog = AlertDialog.Builder(activity)
            .setTitle("PhoneView")
            .setMessage("View the LATERAL_ workspace on your phone with the connected keyboard and mouse?")
            .setPositiveButton("Use PhoneView", null)
            .setNegativeButton("Not now", null)
            .create()
        offer.attach(activity, dialog)
        dialog.setOnDismissListener { offer.dismissed(dialog) }
        try {
            dialog.show()
        } catch (_: android.view.WindowManager.BadTokenException) {
            offer.clear()
            policy.offerPresentationFailed()
            return
        }
        // Override automatic button dismissal: dispose before launching/pausing the owner.
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            offer.clear()
            setEnabled(activity, true)
        }
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener { offer.clear() }
    }

    fun setEnabled(activity: Activity, enabled: Boolean) {
        if (exiting) return
        refresh()
        val before = active
        val bridgeReady = !enabled || PrivilegedService.isBridgeReady()
        if (enabled && !bridgeReady) {
            PrivilegedService.ensureRunning()
            android.widget.Toast.makeText(activity,
                "PhoneView needs the ADB input bridge. Connect or pair it in PhoneUI, then try again.",
                android.widget.Toast.LENGTH_LONG).show()
            android.util.Log.w("PhoneView", "entry blocked: bridge=${PrivilegedService.state}")
            listeners.toList().forEach { it() }
            return
        }
        if (enabled && canEnter) com.lateral.beast.HostedTextInputSession.close()
        policy.setActive(enabled, bridgeReady)
        android.util.Log.i("PhoneView", "manual/accepted entry=$enabled active=$active bridgeReady=$bridgeReady")
        PrivilegedService.setHotkeyMonitoringEnabled(!active)
        if (before != active) listeners.toList().forEach { it() }
        if (active) {
            val options = ActivityOptions.makeBasic().apply { launchDisplayId = Display.DEFAULT_DISPLAY }
            try {
                activity.startActivity(Intent(activity, BeastActivity::class.java).apply {
                    action = BeastActivity.ACTION_ENSURE_WORKSPACE
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }, options.toBundle())
            } catch (error: RuntimeException) {
                policy.setActive(false)
                PrivilegedService.setHotkeyMonitoringEnabled(true)
                listeners.toList().forEach { it() }
                android.widget.Toast.makeText(activity, error.message ?: "Could not open PhoneView", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onInputDeviceAdded(id: Int) = refresh()
    override fun onInputDeviceRemoved(id: Int) = refresh()
    override fun onInputDeviceChanged(id: Int) = refresh()
    override fun onDisplayAdded(id: Int) = refresh()
    override fun onDisplayRemoved(id: Int) = refresh()
    override fun onDisplayChanged(id: Int) = refresh()
}
