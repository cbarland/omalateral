package dev.omalauncher.ui

import android.view.View
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider

/** Optional embedding hook. Standalone Omalauncher keeps its normal window behavior. */
val LocalDesktopWindowHost = staticCompositionLocalOf<((View, () -> Unit) -> (() -> Unit))?> { null }
val LocalDesktopKeyboardHost = staticCompositionLocalOf<((View) -> (() -> Unit))?> { null }

/** Register the actual popup/dialog root, after Android attaches it to its own window. */
@Composable
internal fun DesktopWindow(onDismiss: () -> Unit) {
    val host = LocalDesktopWindowHost.current
    val view = LocalView.current
    val dismiss = rememberUpdatedState(onDismiss)
    DisposableEffect(host, view) {
        val window = if (host != null) (view.parent as? DialogWindowProvider)?.window else null
        val imeFlag = WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
        val wasImeBlocked = window?.attributes?.flags?.and(imeFlag) != 0
        window?.addFlags(imeFlag)
        var release: (() -> Unit)? = null
        var disposed = false
        val attach = Runnable {
            if (!disposed && view.isAttachedToWindow) release = host?.invoke(view.rootView) { dismiss.value() }
        }
        view.post(attach)
        onDispose {
            disposed = true
            view.removeCallbacks(attach)
            release?.invoke()
            if (!wasImeBlocked) window?.clearFlags(imeFlag)
        }
    }
}
