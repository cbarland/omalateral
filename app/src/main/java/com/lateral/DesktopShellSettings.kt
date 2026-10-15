package com.lateral

import android.content.Context

/** Omalateral is an opt-in desktop shell alongside the device's Android/OEM desktop. */
object DesktopShellSettings {
    private const val PREFS = "desktop_shell"
    private const val AUTO_START = "auto_start_omalateral"
    fun autoStart(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(AUTO_START, true)
    fun setAutoStart(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(AUTO_START, enabled).apply()
    }
}
