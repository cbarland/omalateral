package com.lateral.privileged

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/** Persisted choice of activation path; selection is never changed by an automatic fallback. */
object PrivilegePreferences {
    private const val PREFS = "privilege_backend"
    private const val KEY_BACKEND = "selected_backend"

    fun selected(context: Context): PrivilegeBackendId {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY_BACKEND, null)
        if (stored != null) return runCatching { PrivilegeBackendId.valueOf(stored) }
            .getOrDefault(PrivilegeBackendId.WIRELESS_ADB)

        // Existing installs keep the self-contained ADB path until the user opts into Shizuku.
        // On a clean install Shizuku is the recommended first choice.
        val app = runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                context.packageManager.getPackageInfo(context.packageName,
                    PackageManager.PackageInfoFlags.of(PackageManager.GET_META_DATA.toLong()))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_META_DATA)
            }
        }.getOrNull()
        val freshlyInstalled = app != null && app.firstInstallTime == app.lastUpdateTime
        val initial = if (freshlyInstalled) PrivilegeBackendId.SHIZUKU else PrivilegeBackendId.WIRELESS_ADB
        select(context, initial)
        return initial
    }

    fun select(context: Context, backend: PrivilegeBackendId) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_BACKEND, backend.name).apply()
    }
}
