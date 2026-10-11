package com.lateral.beast

import android.content.Context
import android.content.pm.PackageManager

/** Installation is not a connection: stock Omalauncher has no companion endpoint. */
internal enum class OmalauncherPresence {
    ABSENT, DISABLED, BRIDGE_REQUIRED;

    companion object {
        const val PACKAGE = "dev.omalauncher"

        fun detect(context: Context): OmalauncherPresence = classify(try {
            context.packageManager.getApplicationInfo(PACKAGE, 0).enabled
        } catch (_: PackageManager.NameNotFoundException) {
            null
        })

        internal fun classify(enabled: Boolean?): OmalauncherPresence = when (enabled) {
            null -> ABSENT
            false -> DISABLED
            true -> BRIDGE_REQUIRED
        }
    }
}
