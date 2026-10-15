package com.lateral.beast

import android.content.Context
import android.content.pm.PackageManager

/** Installation is not a connection: stock Omalauncher has no companion endpoint. */
internal enum class OmalauncherPresence {
    ABSENT, DISABLED, BRIDGE_REQUIRED;

    companion object {
        const val PACKAGE = "dev.omalauncher"

        fun detect(context: Context): OmalauncherPresence {
            val installed = ExternalOmaDesktop.PACKAGES.mapNotNull { name ->
                try { context.packageManager.getApplicationInfo(name, 0).enabled }
                catch (_: PackageManager.NameNotFoundException) { null }
            }
            return classify(if (installed.isEmpty()) null else installed.any { it })
        }

        internal fun classify(enabled: Boolean?): OmalauncherPresence = when (enabled) {
            null -> ABSENT
            false -> DISABLED
            true -> BRIDGE_REQUIRED
        }
    }
}
