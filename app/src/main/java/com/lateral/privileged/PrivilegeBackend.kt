package com.lateral.privileged

import android.os.IBinder

enum class PrivilegeBackendId { SHIZUKU, WIRELESS_ADB }

enum class UnavailableReason {
    SHIZUKU_NOT_INSTALLED,
    SHIZUKU_NOT_RUNNING,
    AUTHORIZATION_REQUIRED,
    AUTHORIZATION_DENIED,
    INCOMPATIBLE_RUNTIME,
    CONNECTION_LOST,
    START_TIMEOUT,
    DEVELOPER_OPTIONS_REQUIRED,
    WIRELESS_DEBUGGING_REQUIRED,
    PAIRING_REQUIRED,
    START_FAILED,
}

interface PrivilegeBackend {
    val id: PrivilegeBackendId

    fun start(attemptId: Long, callback: Callback)
    fun stop(onComplete: (Result<Unit>) -> Unit)

    interface Callback {
        fun onConnecting(attemptId: Long)
        fun onConnected(attemptId: Long, binder: IBinder)
        fun onUnavailable(attemptId: Long, reason: UnavailableReason)
    }
}
