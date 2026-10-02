package com.lateral

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import com.lateral.beast.BeastActivity
import java.util.concurrent.CopyOnWriteArraySet

object DisplayPreferences {
    data class Target(val key: DisplayProfileKey, val label: String)
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    fun addListener(listener: () -> Unit) { listeners += listener }
    fun removeListener(listener: () -> Unit) { listeners -= listener }

    fun target(context: Context, display: Display): Target {
        val phone = display.displayId == Display.DEFAULT_DISPLAY
        val identity = if (phone) "phoneview:built-in" else {
            // Public product identifiers survive reconnects. Android's getUniqueId()
            // is a blocked test API, and displayId is only a session number.
            val product = if (android.os.Build.VERSION.SDK_INT >= 31) display.deviceProductInfo else null
            val identity = product?.let {
                listOf(display.name, it.manufacturerPnpId, it.productId, it.modelYear.toString())
                    .joinToString("|") { part -> "${part.length}:$part" }
            } ?: display.name
            // Identical products without an exposed serial share preferences.
            "external:$identity"
        }
        val mode = display.mode
        return Target(DisplayProfileKey(identity, mode.physicalWidth, mode.physicalHeight),
            "${if (phone) "PhoneView" else display.name} · ${mode.physicalWidth} × ${mode.physicalHeight}")
    }

    /** PhoneUI edits the connected workspace display, or the phone profile when unplugged. */
    fun workspaceTarget(context: Context): Target {
        val display = BeastActivity.findExternalDisplay(context)
            ?: context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        return target(context, display)
    }

    private fun store(context: Context): DisplayProfileStore {
        val prefs = context.getSharedPreferences("lateral_display_profiles", Context.MODE_PRIVATE)
        return DisplayProfileStore({ key, fallback -> prefs.getFloat(key, fallback) }, { values ->
            prefs.edit().also { edit -> values.forEach { (key, value) -> edit.putFloat(key, value) } }.apply()
        })
    }

    fun load(context: Context, target: Target): DisplayProfile {
        // Legacy preferences remain unchanged, so new profiles never inherit another
        // display's latest edits. Preserve previous standard/ultrawide defaults.
        val legacy = context.getSharedPreferences("lateral_input", Context.MODE_PRIVATE)
        val wide = target.key.identity.startsWith("external:") && target.key.width >= 3000
        val legacySupersampling = "display_supersampling".takeIf(legacy::contains)
            ?.let { legacy.getFloat(it, 1.25f) }
        val defaults = DisplayProfile(
            uiScale = legacy.getFloat(if (wide) "ultrawide_ui_scale" else "standard_ui_scale", if (wide) 1.5f else 1.1f),
            hostedScale = legacy.getFloat("hosted_app_scale", 1f),
            supersampling = defaultSupersampling(target.key.identity, legacySupersampling),
        )
        return store(context).load(target.key, defaults)
    }
    fun save(context: Context, target: Target, value: DisplayProfile) {
        store(context).save(target.key, value)
        listeners.forEach { it() }
    }
}

/** New PhoneView profiles favor a safe raster size; explicit legacy choices still win. */
internal fun defaultSupersampling(identity: String, legacyValue: Float?): Float =
    legacyValue ?: if (identity == "phoneview:built-in") 1.5f else 1.25f
