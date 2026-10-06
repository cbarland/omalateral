package com.lateral.privileged

import android.content.Context
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import io.github.muntashirakon.adb.AdbPairingRequiredException
import io.github.muntashirakon.adb.AdbStream
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Owns wireless-ADB pairing, discovery, bootstrap and stream lifetime. */
class AdbPrivilegeBackend(context: Context) : PrivilegeBackend {
    override val id = PrivilegeBackendId.WIRELESS_ADB
    private val appContext = context.applicationContext
    private val control = Executors.newSingleThreadExecutor { Thread(it, "lateral-adb-backend") }
    private val timeout = Executors.newSingleThreadScheduledExecutor { Thread(it, "lateral-adb-timeout") }
    private val currentAttempt = AtomicLong(0)
    @Volatile private var callback: PrivilegeBackend.Callback? = null
    @Volatile private var stream: AdbStream? = null
    private var streamAttempt = 0L
    @Volatile private var helper: IBinder? = null
    private var helperAttempt = 0L
    private var deadline: ScheduledFuture<*>? = null

    override fun start(attemptId: Long, callback: PrivilegeBackend.Callback) {
        if (currentAttempt.get() == attemptId) return
        this.callback = callback
        currentAttempt.set(attemptId)
        callback.onConnecting(attemptId)
        control.execute {
            try {
            if (!isCurrent(attemptId)) return@execute
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                unavailable(attemptId, UnavailableReason.INCOMPATIBLE_RUNTIME)
                return@execute
            }
            if (!isDeveloperOptionsEnabled()) {
                unavailable(attemptId, UnavailableReason.DEVELOPER_OPTIONS_REQUIRED)
                return@execute
            }
            if (!hasPaired()) {
                unavailable(attemptId, UnavailableReason.PAIRING_REQUIRED)
                return@execute
            }

            val endpoints = AdbDiscovery.discoverConnect(appContext, DISCOVERY_TIMEOUT_MS)
            if (!isCurrent(attemptId)) return@execute
            if (endpoints.isEmpty()) {
                unavailable(attemptId, UnavailableReason.WIRELESS_DEBUGGING_REQUIRED)
                return@execute
            }
            val adb = AdbConnectionManager.getInstance(appContext)
            if (!adb.isConnected) {
                var connected = false
                var lastFailure: Exception? = null
                for (endpoint in endpoints) {
                    if (!isCurrent(attemptId)) return@execute
                    try {
                        if (adb.connect(endpoint.host, endpoint.port)) {
                            connected = true
                            break
                        }
                    } catch (e: AdbPairingRequiredException) {
                        markPaired(false)
                        unavailable(attemptId, UnavailableReason.PAIRING_REQUIRED)
                        return@execute
                    } catch (e: Exception) {
                        lastFailure = e
                        Log.w(TAG, "ADB connect ${endpoint.host}:${endpoint.port} failed", e)
                    }
                }
                if (!connected) {
                    Log.w(TAG, "No ADB endpoint accepted the connection", lastFailure)
                    unavailable(attemptId, UnavailableReason.WIRELESS_DEBUGGING_REQUIRED)
                    return@execute
                }
            }

            if (!isCurrent(attemptId)) return@execute
            val started = ServerBootstrap.start(appContext, adb, attemptId)
            if (started == null) {
                unavailable(attemptId, UnavailableReason.START_FAILED)
                return@execute
            }
            stream = started
            streamAttempt = attemptId
            deadline = timeout.schedule({
                if (isCurrent(attemptId) && helper?.isBinderAlive != true) {
                    unavailable(attemptId, UnavailableReason.START_TIMEOUT)
                    currentAttempt.compareAndSet(attemptId, 0)
                    control.execute { if (streamAttempt == attemptId && helper == null) closeResources() }
                }
            }, START_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            } catch (failure: Exception) {
                Log.e(TAG, "ADB startup failed", failure)
                unavailable(attemptId, UnavailableReason.START_FAILED)
            }
        }
    }

    /** Called only by the restricted ADB Binder receiver, with the bootstrap attempt token. */
    fun offerBinder(attemptId: Long, binder: IBinder) {
        control.execute {
        if (!isCurrent(attemptId)) {
            if (helper != binder) runCatching { IPrivilegedService.Stub.asInterface(binder).exit() }
            return@execute
        }
        helper = binder
        helperAttempt = attemptId
        deadline?.cancel(false)
        callback?.onConnected(attemptId, binder)
        }
    }

    fun helperLost(binder: IBinder) {
        control.execute {
            if (helper != binder) return@execute
            currentAttempt.compareAndSet(helperAttempt, 0)
            deadline?.cancel(false)
            deadline = null
            closeResources()
        }
    }

    fun activate(pairingCode: String, done: (Boolean) -> Unit) {
        control.execute {
            try {
                val endpoint = AdbDiscovery.discoverPairing(appContext, DISCOVERY_TIMEOUT_MS)
                if (endpoint == null) {
                    markPaired(false)
                    done(false)
                    return@execute
                }
                val paired = AdbConnectionManager.getInstance(appContext)
                    .pair(endpoint.host, endpoint.port, pairingCode)
                markPaired(paired)
                done(paired)
            } catch (failure: Exception) {
                Log.e(TAG, "ADB pairing failed", failure)
                markPaired(false)
                done(false)
            }
        }
    }

    override fun stop(onComplete: (Result<Unit>) -> Unit) {
        currentAttempt.set(0)
        control.execute {
            try {
                callback = null
                deadline?.cancel(false)
                deadline = null
                // Ask the helper to release owned resources before closing the ADB transport.
                check(helper != null || stream == null) { "ADB startup is still relinquishing its helper; retry shutdown" }
                val owned = helper
                HelperShutdown.awaitDeath(owned) {
                    if (owned?.pingBinder() == true) IPrivilegedService.Stub.asInterface(owned).exit()
                }
                closeResources()
                onComplete(Result.success(Unit))
            } catch (failure: Throwable) {
                onComplete(Result.failure(failure))
            }
        }
    }

    fun isCurrent(attemptId: Long): Boolean = attemptId != 0L && currentAttempt.get() == attemptId

    private fun unavailable(attemptId: Long, reason: UnavailableReason) {
        if (!isCurrent(attemptId)) return
        callback?.onUnavailable(attemptId, reason)
    }

    private fun closeResources() {
        helper = null
        helperAttempt = 0
        runCatching { stream?.close() }
        stream = null
        streamAttempt = 0
    }

    private fun isDeveloperOptionsEnabled(): Boolean = runCatching {
        Settings.Global.getInt(appContext.contentResolver,
            Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) != 0
    }.getOrDefault(false)

    private fun hasPaired(): Boolean = File(appContext.filesDir, PAIRED_MARKER).exists()

    private fun markPaired(paired: Boolean) {
        val marker = File(appContext.filesDir, PAIRED_MARKER)
        if (paired) {
            marker.parentFile?.mkdirs()
            runCatching { marker.createNewFile() }
        } else runCatching { marker.delete() }
    }

    private companion object {
        const val TAG = "Lateral/Privileged"
        const val PAIRED_MARKER = "adb/lateral_paired_v2.flag"
        const val DISCOVERY_TIMEOUT_MS = 5_000L
        const val START_TIMEOUT_MS = 20_000L
    }
}
