package com.lateral.beast

import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.lateral.InputSettings
import com.lateral.OmarchyThemes
import dev.omalauncher.RuntimeOwner
import dev.omalauncher.ui.LauncherRoot
import dev.omalauncher.ui.LauncherState
import dev.omalauncher.ui.PromptRequest

/** The upstream launcher rendered in Beast's window, with Android launches owned by Beast. */
class OmaDesktop(
    private val activity: ComponentActivity,
    private val onLaunch: () -> Unit,
    private val onOverlayChanged: (Boolean) -> Unit,
) : DesktopSession {
    private val runtime = (activity.application as RuntimeOwner).runtime
    private val state = LauncherState()
    private var started = false
    private val launch: (dev.omalauncher.AndroidApp) -> Unit = { app ->
        WorkspaceState.launchNew(app.component.packageName, app.component.className, app.label)
        onLaunch()
    }
    private val appearance: () -> Unit = {
        val selected = InputSettings.theme.id
        if (runtime.themes.picked != selected) {
            runtime.themes.bundled.firstOrNull { it.name == selected }?.let(runtime.themes::pick)
        }
    }

    override val view = ComposeView(activity).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            LauncherRoot(runtime, state)
            LaunchedEffect(runtime) {
                snapshotFlow { runtime.themes.picked }.collect { name ->
                    val theme = OmarchyThemes.all.firstOrNull { it.id == name }
                    if (theme != null && InputSettings.theme.id != name) InputSettings.setTheme(activity, theme)
                }
            }
            LaunchedEffect(state) {
                snapshotFlow {
                    state.drawer.value > 0.01f || state.homeCovered || state.homeMenuOpen ||
                        state.editing != null || runtime.widgets.openScreen != null
                }.collect(onOverlayChanged)
            }
        }
    }

    init {
        runtime.androidLauncher = launch
        // The embedded desktop is ready without the optional Linux onboarding.
        runtime.finishWelcome()
        runtime.start()
        appearance()
        InputSettings.addAppearanceListener(appearance)
    }

    override fun visible(visible: Boolean) {
        if (started == visible) return
        started = visible
        if (visible) {
            runtime.widgets.homeStarted()
            runtime.screenStarted()
        } else {
            runtime.widgets.homeStopped()
            runtime.screenStopped()
        }
    }

    override fun search(query: String, submit: Boolean) {
        state.query = query
        state.promptRequest = if (submit) PromptRequest.Submit else PromptRequest.Type
    }

    override fun widgets() { state.storeOpen = true }
    override fun themes() { state.styleOpen = true }

    override fun dismissPanels() {
        state.closeNow()
        state.closeScreens()
        state.editing = null
        runtime.widgets.openScreen = null
        view.clearFocus()
    }

    override fun key(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN || !event.isMetaPressed) return false
        when (event.keyCode) {
            KeyEvent.KEYCODE_ENTER -> runtime.terminal(null, "Terminal")
            KeyEvent.KEYCODE_SPACE -> state.toggle()
            KeyEvent.KEYCODE_A -> state.promptRequest = PromptRequest.Type
            in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9 -> state.requestedPage = event.keyCode - KeyEvent.KEYCODE_1
            else -> return false
        }
        return true
    }

    override fun close() {
        visible(false)
        InputSettings.removeAppearanceListener(appearance)
        if (runtime.androidLauncher === launch) runtime.androidLauncher = null
        view.disposeComposition()
    }
}
