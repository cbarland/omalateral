package com.lateral.privileged

import android.view.SurfaceControl

/** Shell-only leases for the validated, caller-owned external host, never source apps. */
internal object ProtectedHostOverlay {
    private val owners = HashMap<Int, Int>()
    private val layerId = SurfaceControl::class.java.getMethod("getLayerId")
    private val trusted = SurfaceControl.Transaction::class.java.getMethod(
        "setTrustedOverlay", SurfaceControl::class.java, Boolean::class.javaPrimitiveType)

    @Synchronized fun acquire(surface: SurfaceControl) {
        val id = layerId.invoke(surface) as Int
        check(id >= 0 && surface.isValid)
        val count = owners[id] ?: 0
        if (count == 0) setTrusted(surface, true)
        owners[id] = count + 1
    }

    @Synchronized fun release(surface: SurfaceControl) {
        val id = layerId.invoke(surface) as Int
        val count = owners[id] ?: return
        if (count > 1) owners[id] = count - 1
        else {
            // Reset before dropping the final lease, even if the tile was hidden.
            if (surface.isValid) setTrusted(surface, false)
            owners.remove(id)
        }
    }

    private fun setTrusted(surface: SurfaceControl, value: Boolean) {
        SurfaceControl.Transaction().use {
            trusted.invoke(it, surface, value)
            it.apply()
        }
    }
}
