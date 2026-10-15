package com.lateral.beast

import android.view.AttachedSurfaceControl
import android.view.SurfaceControl
import androidx.annotation.Keep

/** Read this process's own ViewRoot handle; never changes runtime API-exemption settings. */
@Keep
internal object ProtectedHostSurface {
    init { System.loadLibrary("lateral_beast_control") }
    external fun read(root: AttachedSurfaceControl): SurfaceControl?
}
