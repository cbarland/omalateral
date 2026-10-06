package com.lateral.privileged

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.Process
import android.util.Log
import com.lateral.BuildConfig
import rikka.shizuku.Shizuku
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Application-owned binding of the existing helper; it has no network dependency. */
class ShizukuPrivilegeBackend(context: Context) : PrivilegeBackend {
    override val id = PrivilegeBackendId.SHIZUKU
    private val appContext = context.applicationContext
    private val control = Executors.newSingleThreadScheduledExecutor { Thread(it, "lateral-shizuku") }
    private val currentAttempt = AtomicLong(0)
    private var callback: PrivilegeBackend.Callback? = null
    private var connection: ServiceConnection? = null
    private var helper: IBinder? = null
    private var deadline: ScheduledFuture<*>? = null
    private val prefs = appContext.getSharedPreferences("shizuku_authorization", Context.MODE_PRIVATE)
    private val args = Shizuku.UserServiceArgs(
        ComponentName(appContext.packageName, PrivilegedServer::class.java.name),
    ).tag("${appContext.packageName}.privileged")
        .processNameSuffix("shizuku_privileged")
        .version(BuildConfig.VERSION_CODE)
        .debuggable(BuildConfig.DEBUG)
        .daemon(false)

    private val binderReceived = Shizuku.OnBinderReceivedListener {
        control.execute { evaluate(currentAttempt.get()) }
    }
    private val binderDead = Shizuku.OnBinderDeadListener {
        control.execute {
            if (Shizuku.pingBinder()) return@execute
            connection = null
            helper = null
            deadline?.cancel(false)
            unavailable(currentAttempt.get(), UnavailableReason.CONNECTION_LOST)
        }
    }
    private val permissionResult = Shizuku.OnRequestPermissionResultListener { code, result ->
        if (code == REQUEST_CODE) control.execute {
            prefs.edit().putBoolean("denied", result != PackageManager.PERMISSION_GRANTED).apply()
            evaluate(currentAttempt.get())
        }
    }

    init {
        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
        Shizuku.addRequestPermissionResultListener(permissionResult)
    }

    override fun start(attemptId: Long, callback: PrivilegeBackend.Callback) {
        currentAttempt.set(attemptId)
        control.execute {
            this.callback = callback
            if (helper == null && connection != null) {
                runCatching { Shizuku.unbindUserService(args, connection!!, false) }
                connection = null
                deadline?.cancel(false)
            }
            evaluate(attemptId)
        }
    }

    /** Authorization is requested only by an explicit action in the privilege panel. */
    fun requestAuthorization() {
        control.execute {
            val attempt = currentAttempt.get()
            try {
                if (!Shizuku.pingBinder()) { evaluate(attempt); return@execute }
                if (Shizuku.isPreV11() || Shizuku.getVersion() < 13 || Shizuku.getUid() != Process.SHELL_UID) {
                    unavailable(attempt, UnavailableReason.INCOMPATIBLE_RUNTIME)
                    return@execute
                }
                if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                    evaluate(attempt)
                } else Shizuku.requestPermission(REQUEST_CODE)
            } catch (failure: RuntimeException) {
                Log.w(TAG, "Shizuku authorization became unavailable", failure)
                unavailable(attempt, UnavailableReason.CONNECTION_LOST)
            }
        }
    }

    private fun evaluate(attempt: Long) {
        if (attempt == 0L || attempt != currentAttempt.get()) return
        try {
            if (!Shizuku.pingBinder()) {
                val installed = runCatching {
                    appContext.packageManager.getApplicationInfo(MANAGER_PACKAGE, 0)
                }.isSuccess
                unavailable(attempt, if (installed) UnavailableReason.SHIZUKU_NOT_RUNNING
                    else UnavailableReason.SHIZUKU_NOT_INSTALLED)
                return
            }
            if (Shizuku.isPreV11() || Shizuku.getVersion() < 13 || Shizuku.getUid() != Process.SHELL_UID) {
                unavailable(attempt, UnavailableReason.INCOMPATIBLE_RUNTIME)
                return
            }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                unavailable(attempt, if (prefs.getBoolean("denied", false) ||
                    Shizuku.shouldShowRequestPermissionRationale()) UnavailableReason.AUTHORIZATION_DENIED
                    else UnavailableReason.AUTHORIZATION_REQUIRED)
                return
            }
            prefs.edit().remove("denied").apply()
            helper?.takeIf { it.pingBinder() }?.let {
                callback?.onConnected(attempt, it)
                return
            }
            if (connection != null) return
            val binding = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                    control.execute {
                        if (connection !== this || currentAttempt.get() != attempt) return@execute
                        deadline?.cancel(false)
                        helper = binder
                        callback?.onConnected(attempt, binder)
                    }
                }
                override fun onServiceDisconnected(name: ComponentName) {
                    control.execute {
                        if (connection !== this || currentAttempt.get() != attempt) return@execute
                        connection = null
                        helper = null
                        unavailable(attempt, UnavailableReason.CONNECTION_LOST)
                    }
                }
            }
            connection = binding
            callback?.onConnecting(attempt)
            Shizuku.bindUserService(args, binding)
            deadline = control.schedule({
                if (currentAttempt.get() == attempt && helper == null && connection === binding) {
                    unavailable(attempt, UnavailableReason.START_TIMEOUT)
                    stop { result -> result.exceptionOrNull()?.let { Log.w(TAG, "Timed out helper cleanup failed", it) } }
                }
            }, 20, TimeUnit.SECONDS)
        } catch (failure: RuntimeException) {
            Log.w(TAG, "Shizuku service disappeared during startup", failure)
            connection?.let { runCatching { Shizuku.unbindUserService(args, it, false) } }
            connection = null
            unavailable(attempt, UnavailableReason.CONNECTION_LOST)
        }
    }

    override fun stop(onComplete: (Result<Unit>) -> Unit) {
        currentAttempt.set(0)
        control.execute {
            val owned = helper
            val binding = connection
            deadline?.cancel(false)
            onComplete(runCatching {
                HelperShutdown.awaitDeath(owned) {
                    if (Shizuku.pingBinder()) {
                        // Removal is intentional; removing only the callback leaves a remote service.
                        Shizuku.unbindUserService(args, binding ?: NO_CONNECTION, true)
                    } else if (owned?.pingBinder() == true) {
                        IPrivilegedService.Stub.asInterface(owned).exit()
                    }
                }
                helper = null
                connection = null
                callback = null
            })
        }
    }

    fun helperLost(binder: IBinder) {
        control.execute {
            if (helper != binder) return@execute
            helper = null
            connection?.let { runCatching { Shizuku.unbindUserService(args, it, false) } }
            connection = null
            deadline?.cancel(false)
        }
    }

    private fun unavailable(attempt: Long, reason: UnavailableReason) {
        if (attempt != 0L && currentAttempt.get() == attempt) callback?.onUnavailable(attempt, reason)
    }

    companion object {
        const val MANAGER_PACKAGE = "moe.shizuku.privileged.api"
        private const val TAG = "Lateral/Shizuku"
        private const val REQUEST_CODE = 5107
        private val NO_CONNECTION = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) = Unit
            override fun onServiceDisconnected(name: ComponentName) = Unit
        }
    }
}
